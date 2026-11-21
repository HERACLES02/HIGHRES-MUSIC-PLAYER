package com.localmusic.player

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.localmusic.core.ArtistProfile
import com.localmusic.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

object UiBackupChecks {
    fun run(instrumentation: Instrumentation): Int = runBlocking {
        val context = instrumentation.targetContext
        val store = (context.applicationContext as MusicApplication).storage.await()
        val originalPrefs = store.backupPreferences()
        var checks = 0
        fun verify(ok: Boolean, message: String) { check(ok) { message }; checks++ }

        val backupDir = File(context.cacheDir, "test-backups").apply { mkdirs() }
        val testZip = File(backupDir, "backup-missing-art.zip").apply { delete() }
        val missingArtFile = File(context.filesDir, "artist-images/missing-test-art.jpg")
        val fakeArtist = ArtistProfile(
            providerId = UUID.randomUUID().toString(),
            name = "Test Missing Art Artist",
            biography = "Bio for test",
            imagePath = missingArtFile.absolutePath,
            imageCredit = "Tester",
            savedAtMs = System.currentTimeMillis()
        )

        val toolsDir = File(context.filesDir, "review-tools").apply { mkdirs() }
        try {
            // Seed artist with missing image
            val seededPrefs = originalPrefs.copy(artists = originalPrefs.artists + ("test-artist" to fakeArtist))
            store.restorePreferences(seededPrefs)

            val backups = AppBackups(context, store)
            val uri = Uri.fromFile(testZip)
            val pickerResult = Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri))
            fun picker(action: String) = instrumentation.addMonitor(IntentFilter(action).apply {
                addDataType("*/*")
                addCategory(Intent.CATEGORY_OPENABLE)
            }, pickerResult, true)
            val savePicker = picker(Intent.ACTION_CREATE_DOCUMENT)
            val openPicker = picker(Intent.ACTION_OPEN_DOCUMENT)

            store.clearError()
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val ui = instrumentation.uiAutomation
            try {
                fun allNodes(): List<AccessibilityNodeInfo> {
                    if (android.os.Build.VERSION.SDK_INT >= 34) ui.clearCache()
                    val pending = ArrayDeque<AccessibilityNodeInfo>()
                    ui.rootInActiveWindow?.let { pending.add(it) }
                    val list = mutableListOf<AccessibilityNodeInfo>()
                    while (pending.isNotEmpty()) {
                        val node = pending.removeFirst()
                        list.add(node)
                        for (i in 0 until node.childCount) node.getChild(i)?.let { pending.add(it) }
                    }
                    return list
                }

                fun click(node: AccessibilityNodeInfo) {
                    var target: AccessibilityNodeInfo? = node
                    while (target != null) {
                        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                        target = target.parent
                    }
                    error("Could not click node or any parent: text='${node.text}', desc='${node.contentDescription}'")
                }

                // Wait for activity ready and navigation visible
                withTimeout(15000) {
                    while (allNodes().none { it.text?.toString() == "Settings" || it.contentDescription?.toString() == "Settings" }) delay(150)
                }

                // Tap Settings tab
                val settingsNode = allNodes().first { it.text?.toString() == "Settings" || it.contentDescription?.toString() == "Settings" }
                click(settingsNode)

                // Wait for Settings screen to be active
                withTimeout(10000) {
                    while (allNodes().none { it.text?.contains("Sound & widgets") == true }) {
                        delay(200)
                    }
                }

                fun swipeUp(fraction: Float = 0.35f) {
                    val dm = context.resources.displayMetrics
                    val midX = dm.widthPixels / 2f
                    val startY = dm.heightPixels * 0.70f
                    val endY = startY - dm.heightPixels * fraction
                    val downTime = SystemClock.uptimeMillis()
                    ui.injectInputEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, midX, startY, 0), true)
                    for (step in 1..10) {
                        SystemClock.sleep(20)
                        val curY = startY + (endY - startY) * (step / 10f)
                        val curTime = SystemClock.uptimeMillis()
                        ui.injectInputEvent(MotionEvent.obtain(downTime, curTime, MotionEvent.ACTION_MOVE, midX, curY, 0), true)
                    }
                    SystemClock.sleep(20)
                    val upTime = SystemClock.uptimeMillis()
                    ui.injectInputEvent(MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, midX, endY, 0), true)
                }

                fun hasText(text: String): Boolean {
                    if (android.os.Build.VERSION.SDK_INT >= 34) ui.clearCache()
                    val pending = ArrayDeque<AccessibilityNodeInfo>()
                    ui.rootInActiveWindow?.let { pending.add(it) }
                    while (pending.isNotEmpty()) {
                        val node = pending.removeFirst()
                        val nodeText = node.text?.toString()
                        if (nodeText != null && nodeText.contains(text)) return true
                        for (i in 0 until node.childCount) node.getChild(i)?.let { pending.add(it) }
                    }
                    return false
                }

                suspend fun awaitText(text: String) {
                    withTimeout(10000) { while (!hasText(text)) delay(100) }
                }
                fun capture(name: String) {
                    val screenshot = checkNotNull(ui.takeScreenshot())
                    File(toolsDir, "$name.png").outputStream().use {
                        check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    screenshot.recycle()
                    File(toolsDir, "$name.txt").writeText(allNodes().mapNotNull {
                        it.text?.toString() ?: it.contentDescription?.toString()
                    }.joinToString("\n"))
                }
                withTimeout(10000) {
                    while (!hasText("Create backup")) {
                        swipeUp(0.25f)
                        delay(400)
                    }
                }
                click(allNodes().first { it.text?.toString() == "Create backup" })
                if (!hasText("1 missing artwork file(s) could not be included")) {
                    swipeUp(0.15f)
                }
                awaitText("1 missing artwork file(s) could not be included")
                verify(savePicker.hits == 1 && testZip.length() > 0, "Settings exported through the document picker")
                verify(store.backupPreferences().artists["test-artist"]?.imagePath == missingArtFile.absolutePath,
                    "Export preserves the live artwork reference")
                verify(backups.preview(uri).state.preferences.artists["test-artist"]?.imagePath == null,
                    "Archive omits the missing artwork")
                capture("backup-export-warning")

                click(allNodes().first { it.text?.toString() == "Restore backup" })
                awaitText("Restore this backup?")
                verify(openPicker.hits == 1 && hasText("saved artists"), "Preview summarizes the chosen archive")
                capture("backup-restore-preview")
                click(allNodes().first { it.text?.toString() == "Restore app data" })
                awaitText("App data restored. Playback is paused.")
                verify(store.backupPreferences().artists["test-artist"]?.imagePath == null,
                    "Confirmed restore applied the archived artist")
            } finally {
                instrumentation.removeMonitor(savePicker)
                instrumentation.removeMonitor(openPicker)
                instrumentation.runOnMainSync { activity.finish() }
            }
        } finally {
            store.restorePreferences(originalPrefs)
            testZip.delete()
        }
        checks
    }
}
