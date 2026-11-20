package com.localmusic.player

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Checks the real activity's full-display signal, without playing or changing music. */
object UiStartupChecks {
    fun run(instrumentation:Instrumentation):Int=runBlocking {
        require(Build.MODEL.startsWith("sdk_gphone")){"Startup checks require the disposable emulator"}
        val context=instrumentation.targetContext
        val store=(context.applicationContext as MusicApplication).storage.await()
        store.error("Temporary startup measurement check")
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        fun reported():Boolean {
            var result=false
            instrumentation.runOnMainSync{result=activity.fullyDrawnReporter.isFullyDrawnReported}
            return result
        }
        try {
            delay(1000)
            check(!reported()){"An opening error must not count as a successful full display"}
            store.clearError()
            withTimeout(15000){while(!reported())delay(50)}
            check(reported()){"The ready library must report a full display"}
        } finally {
            store.clearError()
            instrumentation.runOnMainSync{activity.finish()}
        }
        2
    }
}
