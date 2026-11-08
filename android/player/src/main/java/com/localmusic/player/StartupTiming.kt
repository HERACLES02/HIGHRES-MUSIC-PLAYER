package com.localmusic.player

import android.os.Process
import android.os.SystemClock
import android.util.Log

/** Local debug/preview timings; never include song names, paths or preferences. */
internal object StartupTiming {
    fun mark(phase:String) {
        if(BuildConfig.DEBUG || BuildConfig.BUILD_TYPE=="preview")Log.i("LocalMusicStartup",
            "$phase processMs=${SystemClock.uptimeMillis()-Process.getStartUptimeMillis()}")
    }
}
