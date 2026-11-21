package com.localmusic.player

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.localmusic.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

object ClippingUiChecks {

    internal fun createTestWav(file: File, durationSec: Int = 20, sampleRate: Int = 48000, amplitude: Double = 0.8, freqHz: Double = 1000.0) {
        val numChannels = 2
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * (bitsPerSample / 8)
        val blockAlign = numChannels * (bitsPerSample / 8)
        val totalSamples = durationSec * sampleRate
        val dataSize = totalSamples * blockAlign
        val totalSize = 36 + dataSize

        file.outputStream().buffered().use { fos ->
            java.io.DataOutputStream(fos).use { dos ->
                dos.writeBytes("RIFF")
                dos.writeInt(Integer.reverseBytes(totalSize))
                dos.writeBytes("WAVE")
                dos.writeBytes("fmt ")
                dos.writeInt(Integer.reverseBytes(16))
                dos.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
                dos.writeShort(java.lang.Short.reverseBytes(numChannels.toShort()).toInt())
                dos.writeInt(Integer.reverseBytes(sampleRate))
                dos.writeInt(Integer.reverseBytes(byteRate))
                dos.writeShort(java.lang.Short.reverseBytes(blockAlign.toShort()).toInt())
                dos.writeShort(java.lang.Short.reverseBytes(bitsPerSample.toShort()).toInt())
                dos.writeBytes("data")
                dos.writeInt(Integer.reverseBytes(dataSize))
                for (i in 0 until totalSamples) {
                    val sampleValue = (amplitude * 32767.0 * kotlin.math.sin(2.0 * kotlin.math.PI * freqHz * i / sampleRate)).toInt().coerceIn(-32768, 32767).toShort()
                    val rev = java.lang.Short.reverseBytes(sampleValue).toInt()
                    dos.writeShort(rev)
                    dos.writeShort(rev)
                }
            }
        }
    }

    fun run(instrumentation: Instrumentation): Int = runBlocking {
        require(Build.MODEL.startsWith("sdk_gphone")) { "Clipping UI checks require the emulator." }
        val context = instrumentation.targetContext
        val ui = instrumentation.uiAutomation
        var checks = 0
        fun verify(ok: Boolean, msg: String) { checks++; check(ok) { msg } }

        fun allNodes(): List<AccessibilityNodeInfo> {
            if (Build.VERSION.SDK_INT >= 34) ui.clearCache()
            val pending = ArrayDeque<AccessibilityNodeInfo>()
            ui.rootInActiveWindow?.let { pending.add(it) }
            val result = mutableListOf<AccessibilityNodeInfo>()
            while (pending.isNotEmpty() && result.size < 2500) {
                val node = pending.removeFirst()
                result.add(node)
                for (i in 0 until node.childCount) node.getChild(i)?.let { pending.add(it) }
            }
            return result
        }

        suspend fun awaitNode(description: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
            try {
                return withTimeout(20000) {
                    var found: AccessibilityNodeInfo? = null
                    var scrollAttempts = 0
                    while (found == null) {
                        found = allNodes().firstOrNull { predicate(it) }
                        if (found != null) break
                        if (scrollAttempts++ % 3 == 2) {
                            allNodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        }
                        delay(250)
                    }
                    requireNotNull(found)
                }
            } catch (t: Throwable) {
                val texts = allNodes().map { "${it.className}: text='${it.text}', desc='${it.contentDescription}'" }
                throw IllegalStateException("Timed out waiting for '$description'. Nodes:\n${texts.joinToString("\n")}", t)
            }
        }

        fun click(node: AccessibilityNodeInfo) {
            var target: AccessibilityNodeInfo? = node
            while (target != null) {
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                target = target.parent
            }
            error("Could not click node or any parent: text='${node.text}', desc='${node.contentDescription}'")
        }

        val toolsDir = File(context.filesDir, "tools").apply { mkdirs() }
        val hostTools = File("/sdcard/Download")

        fun screenshot(name: String) {
            ui.takeScreenshot()?.let { bmp ->
                File(toolsDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(hostTools, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
            }
            verify(File(toolsDir, name).exists(), "$name captured")
        }

        val app = context.applicationContext as MusicApplication
        val store = app.storage.await()
        val repo = store.repository()

        val now = System.currentTimeMillis()
        val existingGrants = repo.folderGrants()
        val grantId = existingGrants.firstOrNull()?.id ?: UUID.randomUUID()
        if (existingGrants.isEmpty()) {
            repo.saveFolderGrant(FolderGrant(grantId, "content://clipping-test-grant", 1, now))
        }

        val fixtureDir = File(context.cacheDir, "clipping-fixtures").apply { mkdirs() }
        val wavFile = File(fixtureDir, "sine_1000hz.wav")
        createTestWav(wavFile, durationSec = 120, sampleRate = 48000, amplitude = 0.8, freqHz = 1000.0)

        val testTrackId = TrackId(UUID.fromString("00000000-0000-0000-0000-00000000c119"))
        val testTrack = Track(
            id = testTrackId,
            tags = MusicTags(title = "Sine 1000Hz", artists = listOf("DSP Test")),
            audio = AudioProperties(120000, "audio/wav", 48000, 2, 16),
            sources = listOf(TrackSource(Uri.fromFile(wavFile).toString(), SourceKind.DOCUMENT, ContentStamp(wavFile.length(), now), grantId)),
            availability = Availability.AVAILABLE,
            addedAtMs = now
        )

        repo.saveTrack(testTrack)
        store.refresh()
        withTimeout(10000) {
            while (store.state.value.tracks.none { it.id == testTrack.id }) delay(100)
        }

        val originalPrefs = store.backupPreferences()
        store.setPreferences("Light", originalPrefs.autoScan)

        // Start activity
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        delay(1500)

        withTimeout(10000) {
            while (PlaybackService.activeAuthority?.ready != true || PlaybackService.activeAuthority?.tracks?.any { it.id == testTrack.id } != true) delay(100)
        }
        val authority = requireNotNull(PlaybackService.activeAuthority)

        try {
            // Open Settings tab
            val settingsTab = awaitNode("Settings tab") {
                it.text?.toString() == "Settings" || it.contentDescription?.toString() == "Settings"
            }
            click(settingsTab)
            delay(500)

            val audioControls = awaitNode("Audio controls button") {
                it.text?.toString()?.contains("Audio controls") == true
            }
            click(audioControls)
            delay(500)

            val eqButton = awaitNode("Equalizer button") {
                it.text?.toString()?.contains("Equalizer") == true || it.text?.toString()?.contains("Edit EQ") == true
            }
            click(eqButton)
            delay(500)

            awaitNode("Equalizer header") { it.text?.toString() == "Your EQ, your preset." }
            verify(true, "Navigated to Equalizer screen")

            // Initially no clipping
            verify(allNodes().none { it.text?.toString() == "Clipping detected — lower preamp" }, "No clipping warning initially")

            // Start playback of test track with boosted 1000 Hz filter (+12 dB) at 0 dB preamp to induce real DSP clipping in MusicAudioProcessor
            val boostPreset = EqPreset(
                name = "ClippingBoost",
                preampDb = 0.0,
                filters = listOf(EqFilter(frequencyHz = 1000.0, gainDb = 12.0))
            )
            val boostSettings = AudioSettings(eqEnabled = true, preset = boostPreset)
            store.setAudio(boostSettings)
            instrumentation.runOnMainSync {
                authority.engine.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                authority.start(listOf(testTrack.id), 0)
                authority.audioSettingsChanged(boostSettings)
            }

            // Wait for clipping warning node
            awaitNode("Clipping warning banner (Light)") {
                it.text?.toString() == "Clipping detected — lower preamp"
            }
            verify(true, "Clipping warning banner appeared in UI via real DSP clipping")

            // Capture screenshot in light theme
            screenshot("clipping-warning-light.png")

            // Switch to dark theme
            store.setPreferences("Dark", originalPrefs.autoScan)
            delay(600)
            awaitNode("Clipping warning banner (Dark)") {
                it.text?.toString() == "Clipping detected — lower preamp"
            }
            screenshot("clipping-warning-dark.png")

            // Lower preamp to -15 dB: eliminates clipping
            val safePreset = EqPreset(
                name = "ClippingSafe",
                preampDb = -15.0,
                filters = listOf(EqFilter(frequencyHz = 1000.0, gainDb = 12.0))
            )
            val safeSettings = AudioSettings(eqEnabled = true, preset = safePreset)
            store.setAudio(safeSettings)
            instrumentation.runOnMainSync {
                authority.audioSettingsChanged(safeSettings)
            }
            delay(500)

            // Verify warning cleared after decay timer
            withTimeout(6000) {
                while (allNodes().any { it.text?.toString() == "Clipping detected — lower preamp" }) delay(150)
            }
            verify(allNodes().none { it.text?.toString() == "Clipping detected — lower preamp" }, "Clipping warning cleared after lowering preamp")

            // Capture cleared in dark theme
            screenshot("clipping-cleared-dark.png")

            // Switch back to light theme
            store.setPreferences("Light", originalPrefs.autoScan)
            delay(600)
            screenshot("clipping-cleared-light.png")

            instrumentation.runOnMainSync {
                authority.pause()
            }
        } finally {
            runCatching { store.setAudio(AudioSettings()) }
            runCatching {
                instrumentation.runOnMainSync {
                    PlaybackService.activeAuthority?.let { auth ->
                        auth.engine.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
                        auth.pause()
                    }
                }
            }
            store.restorePreferences(originalPrefs)
            repo.markUnavailable(testTrackId, Availability.MISSING)
            store.refresh()
            wavFile.delete()
            activity.finish()
        }

        checks
    }
}
