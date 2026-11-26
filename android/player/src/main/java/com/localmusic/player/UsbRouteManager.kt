@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi
import com.localmusic.core.PcmTargetEncoding
import com.localmusic.core.RouteFormatCandidate
import com.localmusic.core.RouteSelectionResult
import com.localmusic.core.UsbDeviceEvaluation
import com.localmusic.core.UsbRoutePolicy
import com.localmusic.core.OutputDeviceKind
import com.localmusic.core.OutputFacts
import com.localmusic.core.OutputRoute
import org.json.JSONObject
import java.lang.ref.WeakReference

interface AudioPlatformRouter {
    fun getConnectedUsbOutputs(): List<AudioDeviceInfo>
    fun getSupportedMixerAttributes(device: AudioDeviceInfo): List<AudioMixerAttributes>
    fun setPreferredMixerAttributes(device: AudioDeviceInfo, attributes: AudioMixerAttributes): Boolean
    fun clearPreferredMixerAttributes(device: AudioDeviceInfo): Boolean
    fun isDirectPlaybackSupported(format: AudioFormat): Boolean
    fun isSampleRateSupported(device: AudioDeviceInfo, sampleRateHz: Int): Boolean =
        sampleRateHz in device.sampleRates
}

class RealAudioPlatformRouter(
    private val context: Context,
    private val audioAttributes: android.media.AudioAttributes = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
) : AudioPlatformRouter {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    override fun getConnectedUsbOutputs(): List<AudioDeviceInfo> {
        val manager = audioManager ?: return emptyList()
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
    }

    override fun getSupportedMixerAttributes(device: AudioDeviceInfo): List<AudioMixerAttributes> {
        if (Build.VERSION.SDK_INT < 34 || audioManager == null) return emptyList()
        return try {
            audioManager.getSupportedMixerAttributes(device)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun setPreferredMixerAttributes(device: AudioDeviceInfo, attributes: AudioMixerAttributes): Boolean {
        if (Build.VERSION.SDK_INT < 34 || audioManager == null) return false
        return try {
            audioManager.setPreferredMixerAttributes(audioAttributes, device, attributes)
        } catch (_: Exception) {
            false
        }
    }

    override fun clearPreferredMixerAttributes(device: AudioDeviceInfo): Boolean {
        if (Build.VERSION.SDK_INT < 34 || audioManager == null) return true
        return try {
            audioManager.clearPreferredMixerAttributes(audioAttributes, device)
        } catch (_: Exception) {
            false
        }
    }

    override fun isDirectPlaybackSupported(format: AudioFormat): Boolean {
        return try {
            AudioTrack.isDirectPlaybackSupported(format, audioAttributes)
        } catch (_: Exception) {
            false
        }
    }
}

data class UsbRouteDecision(
    val preferAndroidUsb: Boolean,
    val isUsbActive: Boolean,
    val acceptedEncoding: Int,
    val targetEncoding: PcmTargetEncoding,
    val sampleRateHz: Int,
    val channelMask: Int = AudioFormat.CHANNEL_OUT_STEREO,
    val fallbackReason: String? = null,
    val usbDeviceName: String? = null,
    val strategy: String = "Normal route",
    val targetUsbDevice: AudioDeviceInfo? = null,
    val rateRefused: Boolean = false
) {
    fun acceptedDescription(): String {
        if (!isUsbActive) {
            return if (fallbackReason != null) "Standard Android route · $fallbackReason" else "Standard Android route"
        }
        val encName = when (acceptedEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> "float PCM"
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit packed PCM"
            AudioFormat.ENCODING_PCM_32BIT -> "32-bit integer PCM"
            AudioFormat.ENCODING_PCM_16BIT -> "16-bit PCM"
            else -> "PCM format $acceptedEncoding"
        }
        val rateKhz = if (sampleRateHz > 0) "${sampleRateHz / 1000.0} kHz".replace(".0 kHz", " kHz") else "unknown rate"
        return if (strategy.contains("Android 11")) {
            "Preferred device route · ${usbDeviceName ?: "USB device"}"
        } else {
            "$rateKhz · stereo · $encName · default mixer behavior"
        }
    }
}

class UsbRouteManager(
    val router: AudioPlatformRouter,
    val sdkInt: Int = Build.VERSION.SDK_INT
) {
    @Volatile private var internalActiveDecision: UsbRouteDecision = UsbRouteDecision(
        preferAndroidUsb = false,
        isUsbActive = false,
        acceptedEncoding = AudioFormat.ENCODING_PCM_FLOAT,
        targetEncoding = PcmTargetEncoding.PCM_FLOAT,
        sampleRateHz = 48000,
        strategy = "Normal route"
    )

    var activeDecision: UsbRouteDecision
        get() = internalActiveDecision
        internal set(value) { internalActiveDecision = value }

    private var currentOwnerToken = 0L
    private var activeDevice: AudioDeviceInfo? = null
    private var lastTrack = WeakReference<AudioTrack>(null)

    fun registerActiveTrack(track: AudioTrack) {
        lastTrack = WeakReference(track)
    }

    @Synchronized
    fun fallbackToNormal(reason: String) {
        clearRouteOwned()
        activeDecision = activeDecision.copy(
            isUsbActive = false,
            acceptedEncoding = AudioFormat.ENCODING_PCM_FLOAT,
            targetEncoding = PcmTargetEncoding.PCM_FLOAT,
            fallbackReason = reason,
            strategy = "Standard Android route",
            targetUsbDevice = null,
            rateRefused = false
        )
    }

    @Synchronized
    fun setPreference(prefer: Boolean) {
        if (prefer) {
            activeDecision = activeDecision.copy(preferAndroidUsb = true)
        } else {
            clearRouteOwned()
            val d = UsbRouteDecision(
                preferAndroidUsb = false,
                isUsbActive = false,
                acceptedEncoding = AudioFormat.ENCODING_PCM_FLOAT,
                targetEncoding = PcmTargetEncoding.PCM_FLOAT,
                sampleRateHz = activeDecision.sampleRateHz,
                strategy = "Normal route"
            )
            activeDecision = d
        }
    }

    @Synchronized
    fun clearRouteOwned() {
        val dev = activeDevice
        if (dev != null) {
            if (sdkInt >= 34) {
                router.clearPreferredMixerAttributes(dev)
            }
            activeDevice = null
        }
        val track = lastTrack.get()
        if (track != null && sdkInt in 30..33) {
            try {
                track.setPreferredDevice(null)
            } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun releaseOutput(token: Long) {
        if (token == currentOwnerToken) {
            clearRouteOwned()
        }
    }

    @Synchronized
    fun applyRoute(
        preferAndroidUsb: Boolean,
        sampleRateHz: Int,
        sourceBitDepth: Int? = null,
        channelMask: Int = AudioFormat.CHANNEL_OUT_STEREO
    ): UsbRouteDecision {
        val (decision, _) = applyRouteForOutput(preferAndroidUsb, sampleRateHz, channelMask, sourceBitDepth)
        return decision
    }

    @Synchronized
    fun applyRouteForOutput(
        preferAndroidUsb: Boolean,
        sampleRateHz: Int,
        channelMask: Int = AudioFormat.CHANNEL_OUT_STEREO,
        sourceBitDepth: Int? = null
    ): Pair<UsbRouteDecision, Long> {
        val token = ++currentOwnerToken
        if (!preferAndroidUsb) {
            clearRouteOwned()
            val decision = UsbRouteDecision(
                preferAndroidUsb = false,
                isUsbActive = false,
                acceptedEncoding = AudioFormat.ENCODING_PCM_FLOAT,
                targetEncoding = PcmTargetEncoding.PCM_FLOAT,
                sampleRateHz = sampleRateHz,
                channelMask = channelMask,
                strategy = "Normal route"
            )
            activeDecision = decision
            return decision to token
        }

        val usbDevices = router.getConnectedUsbOutputs()
        when (val eval = UsbRoutePolicy.evaluateDevices(usbDevices)) {
            is UsbDeviceEvaluation.NoDevice -> {
                clearRouteOwned()
                val decision = UsbRouteDecision(
                    preferAndroidUsb = true,
                    isUsbActive = false,
                    acceptedEncoding = AudioFormat.ENCODING_PCM_FLOAT,
                    targetEncoding = PcmTargetEncoding.PCM_FLOAT,
                    sampleRateHz = sampleRateHz,
                    channelMask = channelMask,
                    fallbackReason = eval.reason,
                    strategy = "Standard Android route"
                )
                activeDecision = decision
                return decision to token
            }
            is UsbDeviceEvaluation.Multiple -> {
                clearRouteOwned()
                val decision = UsbRouteDecision(
                    preferAndroidUsb = true,
                    isUsbActive = false,
                    acceptedEncoding = AudioFormat.ENCODING_PCM_FLOAT,
                    targetEncoding = PcmTargetEncoding.PCM_FLOAT,
                    sampleRateHz = sampleRateHz,
                    channelMask = channelMask,
                    fallbackReason = eval.reason,
                    strategy = "Standard Android route"
                )
                activeDecision = decision
                return decision to token
            }
            is UsbDeviceEvaluation.Single -> {
                val usbDevice = eval.device
                if (Build.VERSION.SDK_INT >= 34 && sdkInt >= 34) {
                    val decision = applyAndroid14MixerPreference(usbDevice, sampleRateHz, channelMask, sourceBitDepth)
                    activeDecision = decision
                    return decision to token
                } else {
                    val decision = applyAndroid11LegacyPreference(usbDevice, sampleRateHz, channelMask)
                    activeDecision = decision
                    return decision to token
                }
            }
        }
    }

    @RequiresApi(34)
    private fun applyAndroid14MixerPreference(
        usbDevice: AudioDeviceInfo,
        sampleRateHz: Int,
        channelMask: Int,
        sourceBitDepth: Int?
    ): UsbRouteDecision {
        val mixerAttributesList = router.getSupportedMixerAttributes(usbDevice)
            .filter { it.format.channelMask == AudioFormat.CHANNEL_OUT_STEREO }
        val candidates = mixerAttributesList.mapNotNull { attr ->
            val fmt = attr.format
            val enc = when (fmt.encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> PcmTargetEncoding.PCM_FLOAT
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> PcmTargetEncoding.PCM_24BIT_PACKED
                AudioFormat.ENCODING_PCM_32BIT -> PcmTargetEncoding.PCM_32BIT
                AudioFormat.ENCODING_PCM_16BIT -> PcmTargetEncoding.PCM_16BIT
                else -> return@mapNotNull null
            }
            val channelCount = if (fmt.channelMask != 0) Integer.bitCount(fmt.channelMask) else fmt.channelCount
            val isDefault = attr.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT
            RouteFormatCandidate(fmt.sampleRate, channelCount, enc, isDefault)
        }

        when (val res = UsbRoutePolicy.selectPreferredFormat(candidates, sampleRateHz, sourceBitDepth)) {
            is RouteSelectionResult.Fallback -> {
                clearRouteOwned()
                return UsbRouteDecision(
                    preferAndroidUsb = true,
                    isUsbActive = false,
                    acceptedEncoding = AudioFormat.ENCODING_PCM_FLOAT,
                    targetEncoding = PcmTargetEncoding.PCM_FLOAT,
                    sampleRateHz = sampleRateHz,
                    channelMask = channelMask,
                    fallbackReason = res.reason,
                    usbDeviceName = usbDevice.productName?.toString(),
                    strategy = "Standard Android route",
                    rateRefused = res.rateRefused
                )
            }
            is RouteSelectionResult.Selected -> {
                val chosen = res.candidate
                val targetEnc = chosen.encoding
                val androidEnc = when (targetEnc) {
                    PcmTargetEncoding.PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
                    PcmTargetEncoding.PCM_24BIT_PACKED -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                    PcmTargetEncoding.PCM_32BIT -> AudioFormat.ENCODING_PCM_32BIT
                    PcmTargetEncoding.PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
                }

                val matchingAttr = mixerAttributesList.firstOrNull { attr ->
                    val f = attr.format
                    f.sampleRate == chosen.sampleRateHz &&
                        f.encoding == androidEnc &&
                        attr.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT
                }

                if (matchingAttr == null) {
                    clearRouteOwned()
                    return UsbRouteDecision(
                        preferAndroidUsb = true,
                        isUsbActive = false,
                        acceptedEncoding = AudioFormat.ENCODING_PCM_FLOAT,
                        targetEncoding = PcmTargetEncoding.PCM_FLOAT,
                        sampleRateHz = sampleRateHz,
                        channelMask = channelMask,
                        fallbackReason = "Selected mixer attributes matching failed",
                        usbDeviceName = usbDevice.productName?.toString(),
                        strategy = "Standard Android route"
                    )
                }

                clearRouteOwned()
                val setSuccess = router.setPreferredMixerAttributes(usbDevice, matchingAttr)
                if (!setSuccess) {
                    clearRouteOwned()
                    return UsbRouteDecision(
                        preferAndroidUsb = true,
                        isUsbActive = false,
                        acceptedEncoding = AudioFormat.ENCODING_PCM_FLOAT,
                        targetEncoding = PcmTargetEncoding.PCM_FLOAT,
                        sampleRateHz = sampleRateHz,
                        channelMask = channelMask,
                        fallbackReason = "Mixer attributes rejected by Android",
                        usbDeviceName = usbDevice.productName?.toString(),
                        strategy = "Standard Android route"
                    )
                }

                activeDevice = usbDevice
                return UsbRouteDecision(
                    preferAndroidUsb = true,
                    isUsbActive = true,
                    acceptedEncoding = androidEnc,
                    targetEncoding = targetEnc,
                    sampleRateHz = chosen.sampleRateHz,
                    channelMask = AudioFormat.CHANNEL_OUT_STEREO,
                    fallbackReason = null,
                    usbDeviceName = usbDevice.productName?.toString(),
                    strategy = "Preferred mixer attributes (Android 14+)",
                    targetUsbDevice = usbDevice
                )
            }
        }
    }

    private fun applyAndroid11LegacyPreference(
        usbDevice: AudioDeviceInfo,
        sampleRateHz: Int,
        channelMask: Int = AudioFormat.CHANNEL_OUT_STEREO
    ): UsbRouteDecision {
        val result = UsbRoutePolicy.selectLegacyRoute(
            sampleRateHz,
            deviceListsRate = { router.isSampleRateSupported(usbDevice, sampleRateHz) },
            directSupported = {
                router.isDirectPlaybackSupported(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(channelMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .build()
                )
            }
        )
        return when (result) {
            is RouteSelectionResult.Selected -> {
                activeDevice = usbDevice
                UsbRouteDecision(
                    preferAndroidUsb = true,
                    isUsbActive = true,
                    acceptedEncoding = AudioFormat.ENCODING_PCM_FLOAT,
                    targetEncoding = PcmTargetEncoding.PCM_FLOAT,
                    sampleRateHz = sampleRateHz,
                    channelMask = channelMask,
                    fallbackReason = null,
                    usbDeviceName = usbDevice.productName?.toString(),
                    strategy = "Preferred Android USB (Android 11–13)",
                    targetUsbDevice = usbDevice
                )
            }
            is RouteSelectionResult.Fallback -> {
                clearRouteOwned()
                UsbRouteDecision(
                    preferAndroidUsb = true,
                    isUsbActive = false,
                    acceptedEncoding = AudioFormat.ENCODING_PCM_FLOAT,
                    targetEncoding = PcmTargetEncoding.PCM_FLOAT,
                    sampleRateHz = sampleRateHz,
                    channelMask = channelMask,
                    fallbackReason = result.reason,
                    usbDeviceName = usbDevice.productName?.toString(),
                    strategy = "Standard Android route",
                    rateRefused = result.rateRefused
                )
            }
        }
    }

    /** Rates the one connected USB DAC offers: listed to Android (sdk 30–33) or as stereo default-behaviour mixer
     *  attributes (sdk 34+). Empty with no USB DAC or several, so the app never resamples for an unknown device. */
    @Synchronized
    fun fitRates(): Set<Int> {
        val device = (UsbRoutePolicy.evaluateDevices(router.getConnectedUsbOutputs()) as? UsbDeviceEvaluation.Single)?.device
            ?: return emptySet()
        return if (Build.VERSION.SDK_INT >= 34 && sdkInt >= 34) {
            router.getSupportedMixerAttributes(device)
                .filter { it.format.channelMask == AudioFormat.CHANNEL_OUT_STEREO && it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT }
                .map { it.format.sampleRate }.toSet()
        } else device.sampleRates.toSet()
    }

    fun getRoutedDeviceName(): String {
        val track = lastTrack.get() ?: return "Waiting for playback"
        return try {
            val dev = track.routedDevice
            if (dev != null) {
                val prod = dev.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Device ${dev.id}"
                val typeName = when (dev.type) {
                    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
                    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
                    else -> "Type ${dev.type}"
                }
                "$prod ($typeName)"
            } else {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) "Route unavailable" else "Waiting for playback"
            }
        } catch (_: Exception) {
            "Route unavailable"
        }
    }

    /** Reads only what the route decision and the active AudioTrack already report; never opens or reconfigures output. */
    fun outputFacts(): OutputFacts {
        val decision = activeDecision
        val device = try { lastTrack.get()?.routedDevice?.type } catch (_: Exception) { null }
        val kind = when (device) {
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputDeviceKind.USB
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG -> OutputDeviceKind.WIRED
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> OutputDeviceKind.BLUETOOTH
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> OutputDeviceKind.SPEAKER
            else -> null
        }
        return when {
            decision.isUsbActive -> OutputFacts(OutputRoute.ANDROID_ACCEPTED, decision.sampleRateHz, decision.targetEncoding, kind)
            // Only the DAC refusing this rate is "rate unsupported"; every other fallback (the app's <= 48 kHz rule,
            // no direct playback, mixer attributes rejected, buffer or init failures) is the plain mixer with its device.
            decision.rateRefused -> OutputFacts(OutputRoute.RATE_UNSUPPORTED, device = kind)
            else -> OutputFacts(OutputRoute.ANDROID_MIXER, device = kind)
        }
    }

    fun audioInfoJson(): JSONObject {
        val decision = activeDecision
        val requestedRate = decision.sampleRateHz
        val requestedRateStr = if (requestedRate > 0) "${requestedRate / 1000.0} kHz".replace(".0 kHz", " kHz") else "unknown rate"
        val outputRequested = "$requestedRateStr · stereo · float PCM"
        val routedDeviceStr = getRoutedDeviceName()

        return JSONObject()
            .put("preferAndroidUsb", decision.preferAndroidUsb)
            .put("isUsbActive", decision.isUsbActive)
            .put("outputRequested", outputRequested)
            .put("androidAccepted", decision.acceptedDescription())
            .put("acceptedEncoding", decision.acceptedEncoding)
            .put("fallbackReason", decision.fallbackReason ?: JSONObject.NULL)
            .put("usbDeviceName", decision.usbDeviceName ?: JSONObject.NULL)
            .put("routedDevice", routedDeviceStr)
            .put("strategy", decision.strategy)
    }
}
