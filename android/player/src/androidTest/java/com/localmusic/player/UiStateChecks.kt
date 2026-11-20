package com.localmusic.player

import android.content.Context
import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.util.TypedValue
import com.localmusic.core.ThemeAccent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Emulator-only settings checks; no music file writes or audio playback. */
object UiStateChecks {
    fun run(instrumentation:Instrumentation):Int=runBlocking {
        val context=instrumentation.targetContext
        val store=(context.applicationContext as MusicApplication).storage.await()
        val original=store.backupPreferences()
        var checks=0
        var activity:Activity?=null
        fun verify(ok:Boolean,message:String){check(ok){message};checks++}
        val playback=PlaybackUiState()
        playback.accept(JSONObject("""{"currentId":"one","position":100,"sleepRemaining":5000,"manualIds":["a","b"]}"""))
        val sameSong=playback.snapshot
        playback.accept(JSONObject("""{"currentId":"one","position":350,"sleepRemaining":4750,"manualIds":["a","b"]}"""))
        verify(playback.snapshot===sameSong,"Clock ticks must retain the cached browse snapshot")
        verify(playback.position.longValue==350L && playback.sleepRemaining.longValue==4750L,"Clock values still advance")
        playback.accept(JSONObject("""{"currentId":"one","position":350,"manualIds":["b","a"]}"""))
        verify(playback.snapshot!==sameSong,"Reorders invalidate the cached browse snapshot")
        try {
            store.setPreferences("Light",original.autoScan)
            store.setAccent(ThemeAccent.VIOLET)
            withTimeout(5000){store.preferences.first{it.appearance=="Light" && it.accent==ThemeAccent.VIOLET}}
            val launch=StartupAppearance.read(context)
            verify(launch.appearance=="Light" && launch.accent==ThemeAccent.VIOLET,"Launch cache must match saved light theme/accent")
            verify(!StartupAppearance.dark(context,"Light"),"Explicit light theme ignores system night mode")
            verify(StartupAppearance.dark(context,"Dark"),"Explicit dark theme stays dark")
            verify(StartupAppearance.dark(context,"AMOLED"),"AMOLED starts dark")
            val ui=store.uiState.first()
            store.scanProgress(123,"Background progress")
            verify(store.uiState.first()==ui,"Per-file progress does not change the visible library")
            verify(store.state.value.scanned==123,"Reports retain background scan progress")
            activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val background=TypedValue()
            activity!!.theme.resolveAttribute(android.R.attr.windowBackground,background,true)
            verify(background.data==android.graphics.Color.rgb(250,249,253),"Light launch window uses the saved light background")
            // Compose exposes virtual descendants: traverse them instead of the provider's text search.
            fun alertVisible():Boolean {
                val automation=instrumentation.uiAutomation
                if(android.os.Build.VERSION.SDK_INT>=34)automation.clearCache()
                val pending=ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                automation.rootInActiveWindow?.let{it.refresh();pending.add(it)}
                var visited=0
                while(pending.isNotEmpty() && visited++<5000) {
                    val node=pending.removeFirst()
                    if(android.os.Build.VERSION.SDK_INT<34)node.refresh()
                    if(node.isVisibleToUser && node.text?.contains("Temporary UI timeout check")==true)return true
                    for(index in 0 until node.childCount)node.getChild(index)?.let{pending.add(it)}
                }
                return false
            }
            withTimeout(15000){while(instrumentation.uiAutomation.rootInActiveWindow?.packageName!="com.localmusic.player")delay(150)}
            store.error("Temporary UI timeout check")
            withTimeout(8000){while(!alertVisible())delay(150)}
            verify(alertVisible(),"Transient alert is initially visible")
            delay(9400)
            verify(!alertVisible(),"Transient alert disappears after eight seconds")
            verify(store.state.value.error=="Temporary UI timeout check","Hiding a banner retains the underlying error record")
        } finally {
            store.clearError()
            activity?.let{instrumentation.runOnMainSync{it.finish()}}
            store.restorePreferences(original)
            withTimeout(5000){store.preferences.first{it.appearance==original.appearance && it.accent==original.accent}}
        }
        checks
    }
}
