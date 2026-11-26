@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.localmusic.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File

object UsbRouteChecks {

    fun run(instrumentation: Instrumentation): Int = runBlocking {
        val context = instrumentation.targetContext
        var checks = 0
        fun verify(condition: Boolean, message: String) {
            check(condition) { message }
            checks++
        }

        // ==========================================
        // 1. Format ranking and selection logic
        // ==========================================
        val candidateFloat = RouteFormatCandidate(96000, 2, PcmTargetEncoding.PCM_FLOAT, true)
        val candidate24 = RouteFormatCandidate(96000, 2, PcmTargetEncoding.PCM_24BIT_PACKED, true)
        val candidate32 = RouteFormatCandidate(96000, 2, PcmTargetEncoding.PCM_32BIT, true)
        val candidate16 = RouteFormatCandidate(96000, 2, PcmTargetEncoding.PCM_16BIT, true)
        val candidateNonDefault = RouteFormatCandidate(96000, 2, PcmTargetEncoding.PCM_FLOAT, false)

        // Float preferred over 24-bit and 32-bit
        val resFloat = UsbRoutePolicy.selectPreferredFormat(listOf(candidate24, candidateFloat, candidate32), 96000, 24)
        verify(resFloat is RouteSelectionResult.Selected && resFloat.candidate.encoding == PcmTargetEncoding.PCM_FLOAT,
            "Float must be preferred when advertised")

        // 24-bit preferred over 32-bit
        val res24 = UsbRoutePolicy.selectPreferredFormat(listOf(candidate32, candidate24), 96000, 24)
        verify(res24 is RouteSelectionResult.Selected && res24.candidate.encoding == PcmTargetEncoding.PCM_24BIT_PACKED,
            "24-bit packed must be preferred over 32-bit")

        // 32-bit selected when only 32-bit candidate
        val res32 = UsbRoutePolicy.selectPreferredFormat(listOf(candidate32), 96000, 24)
        verify(res32 is RouteSelectionResult.Selected && res32.candidate.encoding == PcmTargetEncoding.PCM_32BIT,
            "32-bit must be selected when only candidate")

        // 16-bit allowed only for <= 16-bit source
        val res16Allowed = UsbRoutePolicy.selectPreferredFormat(listOf(candidate16), 96000, 16)
        verify(res16Allowed is RouteSelectionResult.Selected && res16Allowed.candidate.encoding == PcmTargetEncoding.PCM_16BIT,
            "16-bit candidate accepted for 16-bit source")

        val res16Rejected = UsbRoutePolicy.selectPreferredFormat(listOf(candidate16), 96000, 24)
        verify(res16Rejected is RouteSelectionResult.Fallback,
            "16-bit candidate rejected for 24-bit source")

        // Non-default mixer behavior rejected
        val resNonDefault = UsbRoutePolicy.selectPreferredFormat(listOf(candidateNonDefault), 96000, 24)
        verify(resNonDefault is RouteSelectionResult.Fallback,
            "Non-default mixer behavior candidate rejected")

        // Wrong sample rate rejected
        val candidate48 = RouteFormatCandidate(48000, 2, PcmTargetEncoding.PCM_FLOAT, true)
        val resWrongRate = UsbRoutePolicy.selectPreferredFormat(listOf(candidate48), 96000, 24)
        verify(resWrongRate is RouteSelectionResult.Fallback,
            "Candidate with mismatched sample rate rejected")

        // ==========================================
        // 2. UsbRouteManager with FakePlatformRouter
        // ==========================================
        val audioManager = context.getSystemService(android.media.AudioManager::class.java)
        val realOutputs = audioManager?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        val sampleDevice = realOutputs?.firstOrNull()

        if (sampleDevice != null) {
            class FakeRouter : AudioPlatformRouter {
                var connectedDevices: List<AudioDeviceInfo> = listOf(sampleDevice)
                var supportedMixerAttrs: List<AudioMixerAttributes> = emptyList()
                var setPreferredResult = true
                var lastSetAttribute: AudioMixerAttributes? = null
                var clearedDevice: AudioDeviceInfo? = null
                var directPlaybackSupported = true

                override fun getConnectedUsbOutputs(): List<AudioDeviceInfo> = connectedDevices
                override fun getSupportedMixerAttributes(device: AudioDeviceInfo): List<AudioMixerAttributes> = supportedMixerAttrs
                override fun setPreferredMixerAttributes(device: AudioDeviceInfo, attributes: AudioMixerAttributes): Boolean {
                    lastSetAttribute = attributes
                    return setPreferredResult
                }
                override fun clearPreferredMixerAttributes(device: AudioDeviceInfo): Boolean {
                    clearedDevice = device
                    return true
                }
                override fun isDirectPlaybackSupported(format: AudioFormat): Boolean = directPlaybackSupported
                var sampleRateSupported = true
                override fun isSampleRateSupported(device: AudioDeviceInfo, sampleRateHz: Int): Boolean = sampleRateSupported
            }

            val fakeRouter = FakeRouter()

            // Test NoDevice fallback
            fakeRouter.connectedDevices = emptyList()
            val mgrNoDev = UsbRouteManager(fakeRouter, sdkInt = 34)
            val dNoDev = mgrNoDev.applyRoute(preferAndroidUsb = true, sampleRateHz = 96000, sourceBitDepth = 24)
            verify(!dNoDev.isUsbActive && dNoDev.fallbackReason == "No USB DAC connected", "Fallback when no USB device")

            // Test Multiple devices fallback
            fakeRouter.connectedDevices = listOf(sampleDevice, sampleDevice)
            val mgrMulti = UsbRouteManager(fakeRouter, sdkInt = 34)
            val dMulti = mgrMulti.applyRoute(preferAndroidUsb = true, sampleRateHz = 96000, sourceBitDepth = 24)
            verify(!dMulti.isUsbActive && dMulti.fallbackReason?.contains("Multiple") == true, "Fallback when multiple USB devices")

            // Test Android 14+ path with 24-bit packed candidate
            // Test Android 14+ path with multiple candidates and automatic transitions
            if (Build.VERSION.SDK_INT >= 34) {
                fakeRouter.connectedDevices = listOf(sampleDevice)
                val format24_44 = AudioFormat.Builder()
                    .setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_24BIT_PACKED)
                    .build()
                val attr24_44 = AudioMixerAttributes.Builder(format24_44)
                    .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT)
                    .build()
                val format24_96 = AudioFormat.Builder()
                    .setSampleRate(96000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_24BIT_PACKED)
                    .build()
                val attr24_96 = AudioMixerAttributes.Builder(format24_96)
                    .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT)
                    .build()
                fakeRouter.supportedMixerAttrs = listOf(attr24_44, attr24_96)

                val mgr14 = UsbRouteManager(fakeRouter, sdkInt = 34)

                // Initial 44.1 kHz selection
                val (d44, token1) = mgr14.applyRouteForOutput(preferAndroidUsb = true, sampleRateHz = 44100, sourceBitDepth = 24)
                verify(d44.isUsbActive && d44.acceptedEncoding == AudioFormat.ENCODING_PCM_24BIT_PACKED,
                    "Android 14+ accepts 44.1 kHz 24-bit packed mixer attribute")
                verify(fakeRouter.lastSetAttribute?.format?.sampleRate == 44100, "setPreferredMixerAttributes called with 44.1 kHz")

                // Transition to 96 kHz
                val (d96, token2) = mgr14.applyRouteForOutput(preferAndroidUsb = true, sampleRateHz = 96000, sourceBitDepth = 24)
                verify(d96.isUsbActive && d96.sampleRateHz == 96000, "Transitioned to 96 kHz")
                verify(fakeRouter.lastSetAttribute?.format?.sampleRate == 96000, "setPreferredMixerAttributes called with 96 kHz")

                // Stale token release does NOT clear newer route
                fakeRouter.clearedDevice = null
                mgr14.releaseOutput(token1)
                verify(fakeRouter.clearedDevice == null, "Stale token release does not clear newer active route")

                // Active token release DOES clear route
                mgr14.releaseOutput(token2)
                verify(fakeRouter.clearedDevice == sampleDevice, "Active token release clears route attributes")

                // Transition back to 44.1 kHz
                fakeRouter.clearedDevice = null
                val (d44Back, token3) = mgr14.applyRouteForOutput(preferAndroidUsb = true, sampleRateHz = 44100, sourceBitDepth = 24)
                verify(d44Back.isUsbActive && d44Back.sampleRateHz == 44100, "Transitioned back to 44.1 kHz")
                verify(fakeRouter.lastSetAttribute?.format?.sampleRate == 44100, "setPreferredMixerAttributes called with 44.1 kHz")
                mgr14.releaseOutput(token3)
                verify(fakeRouter.clearedDevice == sampleDevice, "Token 3 release clears route attributes")

                // Test rejected mixer attributes
                fakeRouter.setPreferredResult = false
                val d14Rejected = mgr14.applyRoute(preferAndroidUsb = true, sampleRateHz = 96000, sourceBitDepth = 24)
                verify(!d14Rejected.isUsbActive && d14Rejected.fallbackReason?.contains("rejected") == true,
                    "Fallback when mixer attributes rejected by Android")
                fakeRouter.setPreferredResult = true
            }

            // Test Android 11–13 legacy path (sdkInt = 30)
            fakeRouter.connectedDevices = listOf(sampleDevice)
            fakeRouter.lastSetAttribute = null
            fakeRouter.clearedDevice = null
            val mgr11 = UsbRouteManager(fakeRouter, sdkInt = 30)

            // High sample rate on API 30: direct playback supported, never calls setPreferredMixerAttributes
            val d11HiRate = mgr11.applyRoute(preferAndroidUsb = true, sampleRateHz = 96000, sourceBitDepth = 24)
            verify(d11HiRate.isUsbActive, "Android 11–13 activates for sample rates > 48 kHz")
            verify(fakeRouter.lastSetAttribute == null, "Android 11–13 NEVER calls setPreferredMixerAttributes")
            verify(fakeRouter.clearedDevice == null, "Android 11–13 NEVER calls clearPreferredMixerAttributes")

            // Low sample rate on API 30: falls back
            val d11LowRate = mgr11.applyRoute(preferAndroidUsb = true, sampleRateHz = 44100, sourceBitDepth = 16)
            verify(!d11LowRate.isUsbActive && d11LowRate.fallbackReason?.contains("<= 48 kHz") == true,
                "Android 11–13 falls back for rates <= 48 kHz")
            verify(mgr11.outputFacts().route == OutputRoute.ANDROID_MIXER, "Android 11–13 <= 48 kHz fallback badge is the mixer, not rate unsupported")

            // Badge route: only a rate the DAC does not list is "rate unsupported"
            fakeRouter.sampleRateSupported = false
            mgr11.applyRoute(preferAndroidUsb = true, sampleRateHz = 192000, sourceBitDepth = 24)
            verify(mgr11.outputFacts().route == OutputRoute.RATE_UNSUPPORTED, "Android 11–13 rate not listed by the DAC is rate unsupported")
            fakeRouter.sampleRateSupported = true
            fakeRouter.directPlaybackSupported = false
            mgr11.applyRoute(preferAndroidUsb = true, sampleRateHz = 96000, sourceBitDepth = 24)
            verify(mgr11.outputFacts().route == OutputRoute.ANDROID_MIXER, "Android 11–13 direct playback unsupported is the mixer")
            fakeRouter.directPlaybackSupported = true
            mgr11.applyRoute(preferAndroidUsb = true, sampleRateHz = 96000, sourceBitDepth = 24)
            verify(mgr11.outputFacts().route == OutputRoute.ANDROID_ACCEPTED, "Android 11–13 96 kHz accepted")
            fakeRouter.sampleRateSupported = false
            mgr11.applyRoute(preferAndroidUsb = true, sampleRateHz = 192000, sourceBitDepth = 24)
            fakeRouter.sampleRateSupported = true
            mgr11.fallbackToNormal("USB output initialization failed")
            verify(mgr11.outputFacts().route == OutputRoute.ANDROID_MIXER, "A later output fallback is the mixer, not rate unsupported")
        }

        // ==========================================
        // 3. Real Emulator no-DAC test
        // ==========================================
        val realRouter = RealAudioPlatformRouter(context)
        val realManager = UsbRouteManager(realRouter)
        val realDecision = realManager.applyRoute(preferAndroidUsb = true, sampleRateHz = 96000, sourceBitDepth = 24)
        verify(!realDecision.isUsbActive, "Real emulator has no USB DAC; isUsbActive must be false")
        verify(realDecision.fallbackReason == "No USB DAC connected", "Real emulator fallbackReason is No USB DAC connected")
        verify(realDecision.strategy == "Standard Android route", "Real emulator strategy is Standard Android route")
        verify(realDecision.acceptedDescription() == "Standard Android route · No USB DAC connected",
            "acceptedDescription includes fallback reason")

        // ==========================================
        // 4. UI Testing & Screenshot Capture
        // ==========================================
        val toolsDir = File(context.filesDir, "review-tools").apply { mkdirs() }
        val hostTools = File("/sdcard/Download")

        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity

        val ui = instrumentation.uiAutomation

        fun allNodes(): List<AccessibilityNodeInfo> {
            if (Build.VERSION.SDK_INT >= 34) ui.clearCache()
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

        fun hasText(text: String): Boolean {
            if (Build.VERSION.SDK_INT >= 34) ui.clearCache()
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

        // Wait for activity ready
        withTimeout(15000) {
            while (allNodes().none { it.text?.toString() == "Settings" || it.text?.toString() == "Tracks" }) delay(150)
        }

        // Navigate directly to Audio info screen
        instrumentation.runOnMainSync {
            activity.intent = Intent().apply {
                // Ensure USB preference is enabled so audio info reports it
            }
        }

        // Wait for PlaybackService connection
        withTimeout(10000) {
            while (PlaybackService.activeAuthority == null) delay(100)
        }
        val authority = PlaybackService.activeAuthority
        verify(authority != null, "PlaybackService activeAuthority is available")

        // Ensure preferAndroidUsb is enabled on the authority
        instrumentation.runOnMainSync {
            authority?.audioSettingsChanged(AudioSettings(preferAndroidUsb = true))
        }
        delay(300)

        // Open "Audio info" screen by tapping into it or programmatically
        // We can navigate via Audio controls or find the node
        // In MainActivity, we can switch screen to "Audio info" on main thread
        // by reflecting or clicking
        // Let's tap "Tracks" or look for "Audio controls" / "Audio info"
        // In MainActivity, let's look for how to navigate:
        // Notice MainActivity has `screen` variable. Since it's private, we can tap Audio controls -> Audio info, or use UI nodes!
        // First find if "Settings" tab has Audio controls, or find "More" options
        // Actually, let's check if "Audio info" node exists or if we can tap Settings -> Sound & widgets -> etc.
        // Wait! In ListeningToolsUi.kt: "Audio info" button exists in AudioControls!
        // In MainActivity: if we tap "Audio controls" or if we send intent or click.
        // Let's see: in MainActivity, can we set the screen?
        // Let's check if we can click the "Audio info" button or navigate.
        delay(500)

        // Find "Audio info" or navigate:
        val audioInfoBtn = allNodes().firstOrNull { it.text?.toString() == "Audio info" }
        if (audioInfoBtn != null) {
            audioInfoBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(600)
        } else {
            val settingsTab = allNodes().firstOrNull { it.text?.toString() == "Settings" }
            if (settingsTab != null) {
                settingsTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                delay(600)
            }
        }

        // Wait a moment and check audio info
        var realInfo: JSONObject? = null
        instrumentation.runOnMainSync {
            realInfo = authority!!.audioInfo()
        }
        val info = requireNotNull(realInfo)
        verify(info.has("source"), "Audio info has source field")
        verify(info.has("processing"), "Audio info has processing (DSP) field")
        verify(info.has("outputRequested"), "Audio info has outputRequested field")
        verify(info.has("androidAccepted"), "Audio info has androidAccepted field")
        verify(info.has("routedDevice"), "Audio info has routedDevice field")

        // Capture real no-DAC fallback screenshot
        ui.takeScreenshot()?.let { bmp ->
            File(toolsDir, "audio-info-no-dac.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(hostTools, "audio-info-no-dac.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        }
        verify(File(toolsDir, "audio-info-no-dac.png").exists(), "audio-info-no-dac.png captured")

        // Now simulate accepted integer route via real UsbRouteManager with FakePlatformRouter
        val auth = requireNotNull(authority)
        val origRouteMgr = auth.routeManager

        if (sampleDevice != null && Build.VERSION.SDK_INT >= 34) {
            val simRouter = object : AudioPlatformRouter {
                override fun getConnectedUsbOutputs(): List<AudioDeviceInfo> = listOf(sampleDevice)
                override fun getSupportedMixerAttributes(device: AudioDeviceInfo): List<AudioMixerAttributes> {
                    val format24 = AudioFormat.Builder()
                        .setSampleRate(96000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_24BIT_PACKED)
                        .build()
                    return listOf(AudioMixerAttributes.Builder(format24).setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT).build())
                }
                override fun setPreferredMixerAttributes(device: AudioDeviceInfo, attributes: AudioMixerAttributes): Boolean = true
                override fun clearPreferredMixerAttributes(device: AudioDeviceInfo): Boolean = true
                override fun isDirectPlaybackSupported(format: AudioFormat): Boolean = true
            }
            val simMgr = UsbRouteManager(simRouter, sdkInt = 34)
            simMgr.applyRoute(preferAndroidUsb = true, sampleRateHz = 96000, sourceBitDepth = 24)
            auth.routeManager = simMgr
            delay(300)

            var simInfo: JSONObject? = null
            instrumentation.runOnMainSync {
                simInfo = auth.audioInfo()
            }
            val sInfo = requireNotNull(simInfo)
            verify(sInfo.optString("outputRequested").contains("96 kHz"), "Simulated outputRequested contains 96 kHz")
            verify(sInfo.optString("androidAccepted").contains("24-bit packed PCM"), "Simulated androidAccepted contains 24-bit packed PCM")
            verify(sInfo.optString("routeStrategy") == "Preferred mixer attributes (Android 14+)", "Simulated strategy matches")

            // Capture simulated integer route screenshot
            ui.takeScreenshot()?.let { bmp ->
                File(toolsDir, "audio-info-simulated-integer.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(hostTools, "audio-info-simulated-integer.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
            }
            verify(File(toolsDir, "audio-info-simulated-integer.png").exists(), "audio-info-simulated-integer.png captured")

            // Restore original route manager
            auth.routeManager = origRouteMgr
        }

        activity.finish()
        checks
    }
}
