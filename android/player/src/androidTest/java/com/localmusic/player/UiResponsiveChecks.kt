package com.localmusic.player

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Uses the owned emulator's existing paused session; never run on the user's phone. */
object UiResponsiveChecks {
    fun run(instrumentation:Instrumentation):Int=runBlocking {
        require(Build.MODEL.startsWith("sdk_gphone")){"Responsive checks require the disposable emulator."}
        val context=instrumentation.targetContext
        val automation=instrumentation.uiAutomation
        val keys=listOf("font_scale","accelerometer_rotation","user_rotation")
        val original=keys.associateWith{Settings.System.getString(context.contentResolver,it)}
        require(original.values.all{it==null || it.toFloatOrNull()!=null})
        var activity:Activity?=null
        var checks=0
        fun verify(value:Boolean,message:String){check(value){message};checks++}
        fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use{it.readText()}
        fun nodes():List<AccessibilityNodeInfo> {
            if(Build.VERSION.SDK_INT>=34)automation.clearCache()
            val pending=ArrayDeque<AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let{it.refresh();pending.add(it)}
            val result=mutableListOf<AccessibilityNodeInfo>()
            while(pending.isNotEmpty() && result.size<2500) {
                val node=pending.removeFirst()
                if(Build.VERSION.SDK_INT<34)node.refresh()
                result.add(node)
                for(i in 0 until node.childCount)node.getChild(i)?.let{pending.add(it)}
            }
            return result
        }
        suspend fun awaitNode(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo=withTimeout(20000) {
            var found:AccessibilityNodeInfo?=null
            while(found==null){found=nodes().firstOrNull{it.isVisibleToUser && predicate(it)};if(found==null)delay(200)}
            requireNotNull(found)
        }
        fun screenshot(name:String):Pair<Int,Int> {
            val bitmap=requireNotNull(automation.takeScreenshot())
            val dimensions=bitmap.width to bitmap.height
            java.io.File(context.cacheDir,name).outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
            return dimensions
        }
        try {
            shell("settings put system font_scale 1.5")
            shell("settings put system accelerometer_rotation 0")
            shell("settings put system user_rotation 0")
            check(Settings.System.getFloat(context.contentResolver,"font_scale")==1.5f)
            activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val open=awaitNode{node->node.actionList.any{it.label?.toString()=="Open Now Playing"}}
            verify(open.performAction(AccessibilityNodeInfo.ACTION_CLICK),"Mini player opens Now Playing")
            awaitNode{it.text?.toString()=="NOW PLAYING"}
            awaitNode{it.contentDescription?.toString()=="Play"}
            verify(true,"Large-text player keeps Play visible")
            awaitNode{it.contentDescription?.toString()=="Previous track"}
            awaitNode{it.contentDescription?.toString()=="Next track"}
            verify(true,"Large-text player keeps previous/next visible")
            delay(400)
            val portrait=screenshot("ui-responsive-portrait.png")
            verify(portrait.second>portrait.first,"Portrait player uses the portrait viewport")
            shell("settings put system user_rotation 1")
            delay(1000)
            withTimeout(10000){while(true){val bitmap=requireNotNull(automation.takeScreenshot());val wide=bitmap.width>bitmap.height;bitmap.recycle();if(wide)break;delay(200)}}
            nodes().filter{it.isScrollable}.lastOrNull()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            awaitNode{it.contentDescription?.toString()=="Open queue"}
            awaitNode{it.contentDescription?.toString()=="Playback options"}
            verify(true,"Landscape lower controls remain reachable")
            delay(400)
            val landscape=screenshot("ui-responsive-landscape.png")
            verify(landscape.first>landscape.second,"Landscape player uses the wide viewport")
        } finally {
            for((key,value) in original) {
                if(value==null)shell("settings delete system $key")else shell("settings put system $key $value")
            }
            activity?.let{instrumentation.runOnMainSync{it.finish()}}
        }
        checks
    }
}
