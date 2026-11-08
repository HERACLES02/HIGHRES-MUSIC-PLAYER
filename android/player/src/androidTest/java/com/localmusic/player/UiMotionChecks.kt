package com.localmusic.player

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Real gestures on the owned emulator; no playback or music-file writes. */
object UiMotionChecks {
    fun run(instrumentation:Instrumentation,launchOnly:Boolean):Int=runBlocking {
        require(Build.MODEL.startsWith("sdk_gphone")){"Motion checks require the disposable emulator"}
        val context=instrumentation.targetContext
        if(launchOnly) {
            val info=context.packageManager.getActivityInfo(ComponentName(context,MainActivity::class.java),0)
            val theme=context.resources.newTheme().apply{applyStyle(info.themeResource,true)}
            val preview=TypedValue()
            check(theme.resolveAttribute(android.R.attr.windowDisablePreview,preview,true) && preview.data==0){
                "A branded preview must respond while the app process starts"
            }
            val background=TypedValue()
            check(theme.resolveAttribute(android.R.attr.windowBackground,background,true))
            val drawable=context.resources.getDrawable(background.resourceId,theme)
            val bitmap=android.graphics.Bitmap.createBitmap(720,1280,android.graphics.Bitmap.Config.ARGB_8888)
            try {
                drawable.setBounds(0,0,bitmap.width,bitmap.height);drawable.draw(android.graphics.Canvas(bitmap))
                val brand=android.graphics.Color.rgb(24,59,67)
                check(bitmap.getPixel(0,0)==brand){"Launch background uses the app's branding"}
                var mark=0
                for(y in 340 until 940 step 4)for(x in 60 until 660 step 4)if(bitmap.getPixel(x,y)!=brand)mark++
                check(mark>20){"The starting window contains a visible app mark"}
            } finally {bitmap.recycle()}
            if(Build.VERSION.SDK_INT>=31) {
                val icon=TypedValue()
                check(theme.resolveAttribute(android.R.attr.windowSplashScreenAnimatedIcon,icon,true) && icon.resourceId!=0){
                    "Android 12+ also receives the launch logo"
                }
                return@runBlocking 3
            }
            return@runBlocking 2
        }
        val ui=instrumentation.uiAutomation
        fun nodes():List<AccessibilityNodeInfo> {
            if(Build.VERSION.SDK_INT>=34)ui.clearCache()
            val pending=ArrayDeque<AccessibilityNodeInfo>()
            ui.rootInActiveWindow?.let{it.refresh();pending.add(it)}
            val all=mutableListOf<AccessibilityNodeInfo>()
            while(pending.isNotEmpty() && all.size<3000) {
                val n=pending.removeFirst();if(Build.VERSION.SDK_INT<34)n.refresh();all.add(n)
                for(i in 0 until n.childCount)n.getChild(i)?.let{pending.add(it)}
            }
            return all
        }
        suspend fun find(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo=withTimeout(15000) {
            var found:AccessibilityNodeInfo?=null
            while(found==null){found=nodes().firstOrNull{it.isVisibleToUser && predicate(it)};if(found==null)delay(100)}
            requireNotNull(found)
        }
        fun shot(name:String) {
            val image=requireNotNull(ui.takeScreenshot())
            java.io.File(context.cacheDir,name).outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            image.recycle()
        }
        var downTime=0L
        fun touch(action:Int,x:Float,y:Float) {
            val now=SystemClock.uptimeMillis();if(action==MotionEvent.ACTION_DOWN)downTime=now
            val event=MotionEvent.obtain(downTime,now,action,x,y,0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { check(ui.injectInputEvent(event,true)) } finally { event.recycle() }
            if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL)downTime=0L
        }
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var x=0f;var end=0f
        try {
            find{it.text?.toString()=="Local Music"}
            val grid=find{it.isScrollable}
            grid.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);delay(600)
            val marker=find{it.contentDescription?.startsWith("Options for ")==true}
            val before=Rect().also{marker.getBoundsInScreen(it)}
            val label=marker.contentDescription.toString()
            find{n->n.actionList.any{it.label?.toString()=="Open Now Playing"}}.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            find{it.text?.toString()=="NOW PLAYING"};delay(600)
            val dimensions=requireNotNull(ui.takeScreenshot()).let{val pair=it.width to it.height;it.recycle();pair}
            x=dimensions.first*.5f;val start=dimensions.second*.35f;end=dimensions.second*.65f
            touch(MotionEvent.ACTION_DOWN,x,start)
            for(i in 1..18){touch(MotionEvent.ACTION_MOVE,x,start+(end-start)*i/18);delay(16)}
            delay(250);shot("ui-motion-held.png")
            check(nodes().any{it.isVisibleToUser && it.text?.toString()=="Local Music"}){
                "Dragging the player must reveal the existing Home screen, not an empty background"
            }
            touch(MotionEvent.ACTION_UP,x,end)
            find{it.text?.toString()=="Local Music"};delay(700)
            val after=Rect().also{find{it.contentDescription?.toString()==label}.getBoundsInScreen(it)}
            shot("ui-motion-return.png")
            check(kotlin.math.abs(before.top-after.top)<=3){"Dismissing the player must preserve Home scroll position: $before -> $after"}
            check(nodes().none{it.isVisibleToUser && it.text?.toString()=="NOW PLAYING"}){"Player is dismissed"}
        } finally {
            if(downTime!=0L)runCatching{touch(MotionEvent.ACTION_CANCEL,x,end)}
            instrumentation.runOnMainSync{activity.finish()}
        }
        3
    }
}
