package com.localmusic.player

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** A parent can retain an exiting child for another frame after its callback. */
object UiDismissFrameChecks {
    fun run(instrumentation:Instrumentation):Int=runBlocking {
        require(Build.MODEL.startsWith("sdk_gphone")){"Use the owned emulator only"}
        val context=instrumentation.targetContext
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val ui=instrumentation.uiAutomation
        val dismissed=CompletableDeferred<Unit>()
        var held=false
        var down=0L
        var x=0f
        var y=0f
        fun touch(action:Int) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
            event.source=InputDevice.SOURCE_TOUCHSCREEN
            try{check(ui.injectInputEvent(event,true))}finally{event.recycle()}
        }
        fun pixel():Int {
            val image=requireNotNull(ui.takeScreenshot())
            return try{image.getPixel(image.width/2,image.height/2)}finally{image.recycle()}
        }
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Blue)) {
                        // Deliberately retain the dismissed child to expose a reset before disposal.
                        PlayerMotionFrame(true,{dismissed.complete(Unit)},Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Red))
                        }
                    }
                }
            }
            withTimeout(15000){while(pixel()!=Color.RED)delay(100)}
            val image=requireNotNull(ui.takeScreenshot())
            x=image.width*.5f;val start=image.height*.3f;val end=image.height*.75f;image.recycle()
            y=start;down=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN);held=true
            for(i in 1..18){y=start+(end-start)*i/18;touch(MotionEvent.ACTION_MOVE);delay(20)}
            delay(150);touch(MotionEvent.ACTION_UP);held=false
            withTimeout(10000){dismissed.await()}
            delay(150)
            val result=pixel()
            val frame=requireNotNull(ui.takeScreenshot())
            java.io.File(context.cacheDir,"ui-dismiss-after-callback.png").outputStream().use {
                frame.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            };frame.recycle()
            check(result==Color.BLUE){"Dismissed player must stay off-screen until disposal; center was ${Integer.toHexString(result)}"}
            delay(150)
            check(pixel()==Color.BLUE){"Retained exit content must not flash back"}
        } finally {
            if(held)runCatching{touch(MotionEvent.ACTION_CANCEL)}
            instrumentation.runOnMainSync{activity.finish()}
        }
        2
    }
}
