@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import com.localmusic.core.AndroidUsbBufferRules
import com.localmusic.core.AppResample
import com.localmusic.core.OutputRetirement
import com.localmusic.core.PcmOutputConverter
import com.localmusic.core.PcmOutputSession
import com.localmusic.core.PcmTargetEncoding
import com.localmusic.core.RateFit
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.nio.ByteBuffer

/**
 * Wraps Media3's AudioOutputProvider.
 * Keeps upstream sink in float PCM. For accepted integer formats on USB,
 * converts at the output boundary using a reusable direct buffer and PcmOutputSession.
 */
class LocalAudioOutputProvider(
    private val appContext: Context? = null,
    var routeManager: UsbRouteManager = UsbRouteManager(
        if (appContext != null) RealAudioPlatformRouter(appContext) else object : AudioPlatformRouter {
            override fun getConnectedUsbOutputs(): List<AudioDeviceInfo> = emptyList()
            override fun getSupportedMixerAttributes(device: AudioDeviceInfo) = emptyList<android.media.AudioMixerAttributes>()
            override fun setPreferredMixerAttributes(device: AudioDeviceInfo, attributes: android.media.AudioMixerAttributes) = false
            override fun clearPreferredMixerAttributes(device: AudioDeviceInfo) = true
            override fun isDirectPlaybackSupported(format: AudioFormat) = false
        }
    ),
    private val outputProviderOverride: AudioOutputProvider? = null
) : AudioOutputProvider, DefaultAudioSink.AudioTrackProvider {

    var preferAndroidUsb: Boolean
        get() = routeManager.activeDecision.preferAndroidUsb
        set(value) {
            val cur = routeManager.activeDecision
            if (cur.preferAndroidUsb != value) {
                routeManager.setPreference(value)
            }
        }

    var sourceBitDepthProvider: (() -> Int?)? = null

    /** "Match DAC rate (app resampling)"; only acts while [preferAndroidUsb] is on. */
    @Volatile var matchDacRate = true

    /** Written on the playback thread when a format starts playing; read by the badge tick and Audio info. */
    @Volatile var appResample: AppResample? = null
        private set

    /** Called by [PrecisionAudioSink] when a format is applied: the rate Media3 should be configured with. */
    fun fitRate(format: Format): Int {
        val target = if (preferAndroidUsb && matchDacRate) RateFit.target(format.sampleRate, routeManager.fitRates(), routeManager.sdkInt,
            format.channelCount, format.encoderDelay > 0 || format.encoderPadding > 0) else format.sampleRate
        appResample = if (target != format.sampleRate) AppResample(format.sampleRate, target) else null
        return target
    }

    private var lastTrack = WeakReference<AudioTrack>(null)
    val retirement = OutputRetirement()

    private fun rememberTrack(track: AudioTrack) {
        if (BuildConfig.DIRECT_USB_ENABLED) retirement.remember { track.state == AudioTrack.STATE_UNINITIALIZED }
    }
    private val pcmConverter = PcmOutputConverter()

    data class Startup(
        val sampleRate: Int,
        val capacityFrames: Int,
        val originalFrames: Int?,
        val requestedFrames: Int?,
        val actualFrames: Int?,
        val status: String
    ) {
        fun json() = JSONObject().put("sampleRate", sampleRate).put("capacityFrames", capacityFrames)
            .put("originalThresholdFrames", originalFrames ?: JSONObject.NULL)
            .put("requestedThresholdFrames", requestedFrames ?: JSONObject.NULL)
            .put("actualThresholdFrames", actualFrames ?: JSONObject.NULL).put("status", status)
    }

    @Volatile var startup: Startup? = null
        private set

    data class PcmOutput(
        val requestedSampleRateHz: Int,
        val requestedEncoding: Int,
        val channelCount: Int,
        val audioTrackSampleRateHz: Int,
        val audioTrackEncoding: Int,
        val strategy: String,
        val preferredDevice: String?,
        val requestedBufferBytes: Int,
        val fallbackReason: String? = null
    ) {
        fun json() = JSONObject().put("requestedSampleRateHz", requestedSampleRateHz)
            .put("requestedEncoding", requestedEncoding)
            .put("channelCount", channelCount)
            .put("audioTrackSampleRateHz", audioTrackSampleRateHz)
            .put("audioTrackEncoding", audioTrackEncoding)
            .put("strategy", strategy)
            .put("preferredDevice", preferredDevice ?: JSONObject.NULL)
            .put("fallbackReason", fallbackReason ?: JSONObject.NULL)
            .put("requestedBufferBytes", requestedBufferBytes)
            .put("limits", "AudioTrack request and client format; not a measured mixer, HAL or physical DAC format.")
    }

    @Volatile var pcmOutput: PcmOutput? = null
        private set

    fun outputJson(): JSONObject? = pcmOutput?.json()?.put("routedDevice", routeManager.getRoutedDeviceName())

    // Base AudioTrackAudioOutputProvider without modifying TrackBuilder encoding underneath float config
    private val baseProvider: AudioOutputProvider by lazy {
        outputProviderOverride?.let { return@lazy it }
        val builder = if (appContext != null) {
            AudioTrackAudioOutputProvider.Builder(appContext)
        } else {
            throw IllegalStateException("Context required for AudioTrackAudioOutputProvider")
        }
        builder.build()
    }

    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
        return baseProvider.getFormatSupport(formatConfig)
    }

    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        return baseProvider.getOutputConfig(formatConfig)
    }

    override fun getAudioOutput(outputConfig: AudioOutputProvider.OutputConfig): AudioOutput {
        val manager = routeManager
        return synchronized(manager) { openOutput(outputConfig, manager) }
    }

    private fun openOutput(outputConfig: AudioOutputProvider.OutputConfig, manager: UsbRouteManager): AudioOutput {
        val preferUsb = manager.activeDecision.preferAndroidUsb
        val eligiblePcm = outputConfig.encoding == C.ENCODING_PCM_FLOAT &&
            outputConfig.channelMask == AudioFormat.CHANNEL_OUT_STEREO &&
            !outputConfig.isOffload && !outputConfig.isTunneling
        val (decision, token) = manager.applyRouteForOutput(
            preferAndroidUsb = preferUsb && eligiblePcm,
            sampleRateHz = outputConfig.sampleRate,
            channelMask = outputConfig.channelMask,
            sourceBitDepth = sourceBitDepthProvider?.invoke()
        )
        if (preferUsb && !eligiblePcm) {
            manager.setPreference(true)
            manager.fallbackToNormal("USB preference requires stereo float PCM")
        }
        val targetEncoding = decision.targetEncoding
        val isConverting = decision.isUsbActive && targetEncoding != PcmTargetEncoding.PCM_FLOAT
        val isLegacyUsb = decision.isUsbActive && manager.sdkInt in 30..33

        var openedConfig = if (isConverting) {
            val media3Encoding = when (targetEncoding) {
                PcmTargetEncoding.PCM_24BIT_PACKED -> C.ENCODING_PCM_24BIT
                PcmTargetEncoding.PCM_32BIT -> C.ENCODING_PCM_32BIT
                PcmTargetEncoding.PCM_16BIT -> C.ENCODING_PCM_16BIT
                PcmTargetEncoding.PCM_FLOAT -> C.ENCODING_PCM_FLOAT
            }
            val frameBytes = targetEncoding.frameBytes(2)
            val minBuf = AudioTrack.getMinBufferSize(decision.sampleRateHz, decision.channelMask, decision.acceptedEncoding)
            val floatFrames = outputConfig.bufferSize / PcmTargetEncoding.PCM_FLOAT.frameBytes(2)
            val alignedMinimum = if (minBuf > 0) (minBuf + frameBytes - 1) / frameBytes * frameBytes else 0
            val alignedBufferSize = maxOf(floatFrames * frameBytes, alignedMinimum)

            outputConfig.buildUpon()
                .setEncoding(media3Encoding)
                .setSampleRate(decision.sampleRateHz)
                .setChannelMask(decision.channelMask)
                .setBufferSize(alignedBufferSize)
                .build()
        } else if (isLegacyUsb) {
            val frameBytes = PcmTargetEncoding.PCM_FLOAT.frameBytes(2)
            val minimum = AudioTrack.getMinBufferSize(outputConfig.sampleRate, outputConfig.channelMask, AudioFormat.ENCODING_PCM_FLOAT)
            val size = AndroidUsbBufferRules.bytes(minimum, outputConfig.bufferSize, frameBytes, true, true)
            if (size < outputConfig.bufferSize) {
                outputConfig.buildUpon().setBufferSize(size).build()
            } else {
                manager.fallbackToNormal("USB minimum buffer is unavailable")
                outputConfig
            }
        } else {
            outputConfig
        }

        fun openNormal(reason: String): AudioOutput {
            manager.fallbackToNormal(reason)
            openedConfig = outputConfig
            return baseProvider.getAudioOutput(outputConfig)
        }

        var baseOutput = try {
            baseProvider.getAudioOutput(openedConfig)
        } catch (e: AudioOutputProvider.InitializationException) {
            if (!manager.activeDecision.isUsbActive) throw e
            openNormal("USB output initialization failed")
        }
        if (isLegacyUsb && manager.activeDecision.isUsbActive) {
            val usbTrack = (baseOutput as? AudioTrackAudioOutput)?.audioTrack
            if (usbTrack != null && !usbTrack.setPreferredDevice(decision.targetUsbDevice)) {
                rememberTrack(usbTrack)
                baseOutput.release()
                baseOutput = openNormal("Device preference rejected by platform")
            }
        }

        val track = (baseOutput as? AudioTrackAudioOutput)?.audioTrack
        if (track != null) {
            lastTrack = WeakReference(track)
            rememberTrack(track)
            manager.registerActiveTrack(track)
            configureTrackStartup(track, openedConfig.sampleRate)
            val finalDecision = manager.activeDecision
            pcmOutput = PcmOutput(
                requestedSampleRateHz = outputConfig.sampleRate,
                requestedEncoding = outputConfig.encoding,
                channelCount = track.channelCount,
                audioTrackSampleRateHz = track.sampleRate,
                audioTrackEncoding = track.audioFormat,
                strategy = finalDecision.strategy,
                preferredDevice = finalDecision.usbDeviceName,
                requestedBufferBytes = openedConfig.bufferSize,
                fallbackReason = finalDecision.fallbackReason
            )
        }

        return when {
            openedConfig.encoding != outputConfig.encoding -> ConvertingAudioOutput(
                delegate = baseOutput,
                session = PcmOutputSession(pcmConverter, channelCount = 2, targetEncoding = targetEncoding),
                onRelease = { manager.releaseOutput(token) }
            )
            manager.activeDecision.isUsbActive -> object : ForwardingAudioOutput(baseOutput) {
                override fun release() {
                    try {
                        super.release()
                    } finally {
                        manager.releaseOutput(token)
                    }
                }
            }
            else -> baseOutput
        }
    }

    override fun addListener(listener: AudioOutputProvider.Listener) {
        baseProvider.addListener(listener)
    }

    override fun removeListener(listener: AudioOutputProvider.Listener) {
        baseProvider.removeListener(listener)
    }

    override fun setClock(clock: Clock) {
        baseProvider.setClock(clock)
    }

    override fun release() {
        baseProvider.release()
    }

    // Backwards-compatible getAudioTrack for tests / legacy sinks
    override fun getAudioTrack(
        config: AudioSink.AudioTrackConfig,
        attributes: AudioAttributes,
        sessionId: Int,
        context: Context?
    ): AudioTrack {
        val decision = routeManager.activeDecision
        val isUsb = decision.isUsbActive
        val encoding = if (isUsb) decision.acceptedEncoding else config.encoding
        val builder = AudioTrack.Builder()
            .setAudioAttributes(attributes.audioAttributesV21.audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(config.sampleRate)
                    .setChannelMask(config.channelConfig)
                    .setEncoding(encoding)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(config.bufferSize)
            .setSessionId(sessionId)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
        val track = builder.build()
        lastTrack = WeakReference(track)
        rememberTrack(track)
        routeManager.registerActiveTrack(track)
        configureTrackStartup(track, config.sampleRate)
        pcmOutput = PcmOutput(
            config.sampleRate, config.encoding, track.channelCount, track.sampleRate, track.audioFormat,
            decision.strategy, decision.usbDeviceName, config.bufferSize, decision.fallbackReason
        )
        return track
    }

    private fun configureTrackStartup(track: AudioTrack, sampleRate: Int) {
        val pcm = track.audioFormat in setOf(
            AudioFormat.ENCODING_PCM_16BIT,
            AudioFormat.ENCODING_PCM_24BIT_PACKED,
            AudioFormat.ENCODING_PCM_32BIT,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        if (Build.VERSION.SDK_INT < 31 || !pcm || track.isOffloadedPlayback) {
            startup = Startup(
                sampleRate, track.bufferCapacityInFrames, null, null, null,
                if (Build.VERSION.SDK_INT < 31) "Platform startup threshold (Android 11)" else "Encoded/offloaded output unchanged"
            )
            return
        }
        val original = track.startThresholdInFrames
        val requested = minOf(original, (sampleRate / 10).coerceAtLeast(1))
        try {
            val actual = track.setStartThresholdInFrames(requested)
            startup = Startup(
                sampleRate, track.bufferCapacityInFrames, original, requested, actual,
                if (actual <= requested) "Local PCM startup threshold applied" else "Platform clamped startup threshold"
            )
        } catch (_: Exception) {
            startup = Startup(
                sampleRate, track.bufferCapacityInFrames, original, requested, null,
                "Platform rejected startup threshold; default retained"
            )
        }
    }

    internal class ConvertingAudioOutput(
        val delegate: AudioOutput,
        val session: PcmOutputSession,
        val onRelease: (() -> Unit)? = null
    ) : ForwardingAudioOutput(delegate) {
        override fun write(audioData: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
            return session.write(audioData, presentationTimeUs) { buffer, size, timeUs ->
                val posBefore = buffer.position()
                val fullyHandled = delegate.write(buffer, encodedAccessUnitCount, timeUs)
                val bytesWritten = buffer.position() - posBefore
                if (bytesWritten > 0) bytesWritten else if (fullyHandled) size else 0
            }
        }

        override fun flush() {
            session.flush()
            super.flush()
        }

        override fun release() {
            session.flush()
            try {
                super.release()
            } finally {
                onRelease?.invoke()
            }
        }
    }
}

// Typealias so existing code expecting LocalAudioTrackProvider continues seamlessly
typealias LocalAudioTrackProvider = LocalAudioOutputProvider
