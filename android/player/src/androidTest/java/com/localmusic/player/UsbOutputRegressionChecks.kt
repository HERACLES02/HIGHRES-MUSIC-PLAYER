@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object UsbOutputRegressionChecks {
    fun run(context: Context, suite: String): Int {
        val device = context.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS).first()
        val stereo = AudioFormat.CHANNEL_OUT_STEREO
        val rate = 96000
        val mixer = AudioMixerAttributes.Builder(AudioFormat.Builder()
            .setSampleRate(rate).setChannelMask(stereo)
            .setEncoding(AudioFormat.ENCODING_PCM_24BIT_PACKED).build()).build()
        var mixerSets = 0
        var mixerClears = 0
        val router = object : AudioPlatformRouter {
            override fun getConnectedUsbOutputs() = listOf(device)
            override fun getSupportedMixerAttributes(device: AudioDeviceInfo) = listOf(mixer)
            override fun setPreferredMixerAttributes(device: AudioDeviceInfo, attributes: AudioMixerAttributes): Boolean {
                mixerSets++
                return true
            }
            override fun clearPreferredMixerAttributes(device: AudioDeviceInfo): Boolean {
                mixerClears++
                return true
            }
            override fun isDirectPlaybackSupported(format: AudioFormat) = format.sampleRate == rate
            override fun isSampleRateSupported(device: AudioDeviceInfo, sampleRateHz: Int) = sampleRateHz == rate
        }
        val inputConfig = AudioOutputProvider.OutputConfig.Builder().setSampleRate(rate)
            .setChannelMask(stereo).setEncoding(C.ENCODING_PCM_FLOAT).setBufferSize(192000).build()
        var checks = 0
        fun verify(condition: Boolean, detail: String) {
            check(condition) { detail }
            checks++
        }
        val attempts = mutableListOf<AudioOutputProvider.OutputConfig>()
        val writes = mutableListOf<Byte>()
        val sink = Proxy.newProxyInstance(AudioOutput::class.java.classLoader,
            arrayOf(AudioOutput::class.java)) { _, method, args ->
            when (method.name) {
                "write" -> {
                    val buffer = args[0] as ByteBuffer
                    while (buffer.hasRemaining()) writes += buffer.get()
                    true
                }
                "release" -> null
                else -> error("Unexpected output call ${method.name}")
            }
        } as AudioOutput
        val downstream = object : AudioOutputProvider by AudioTrackAudioOutputProvider.Builder(context).build() {
            override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
                attempts += config
                if (suite == "usb-output-fallback" && config.encoding == C.ENCODING_PCM_24BIT) {
                    throw AudioOutputProvider.InitializationException()
                }
                return sink
            }
        }
        val manager = UsbRouteManager(router, sdkInt = if (suite == "usb-output-buffer") 30 else 34)
        val provider = LocalAudioOutputProvider(context, manager, downstream)
        when (suite) {
            "usb-output-switch" -> {
                verify(!provider.preferAndroidUsb, "USB should start disabled")
                provider.preferAndroidUsb = true
                verify(mixerSets == 0, "Enabling should wait for output creation")
                provider.getAudioOutput(inputConfig).release()
                verify(mixerSets == 1, "Enabling the switch should request the mixer on the first output")
                provider.preferAndroidUsb = false
                provider.getAudioOutput(inputConfig).release()
                verify(mixerSets == 1 && !provider.preferAndroidUsb, "Turning USB off should restore Normal")
            }
            "usb-output-fallback" -> {
                manager.applyRoute(true, 48000)
                val output = provider.getAudioOutput(inputConfig)
                verify(attempts.map { it.encoding } == listOf(C.ENCODING_PCM_24BIT, C.ENCODING_PCM_FLOAT),
                    "Failed integer output should retry float once")
                verify(attempts.last() == inputConfig, "Retry should keep original session and buffer configuration")
                val input = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
                    .putFloat(0.25f).putFloat(-0.5f).apply { flip() }
                val original = ByteArray(input.remaining()).also { input.duplicate().get(it) }
                verify(output.write(input, 1, 0), "Fallback should accept the input")
                verify(writes.toByteArray().contentEquals(original), "Float fallback must receive the original float bytes")
                verify(mixerClears == 1 && !manager.activeDecision.isUsbActive &&
                    manager.activeDecision.fallbackReason?.contains("initialization failed") == true,
                    "Float fallback should clear mixer ownership and report the reason")
                output.release()
            }
            "usb-output-buffer" -> {
                manager.applyRoute(true, 48000)
                verify(!manager.activeDecision.isUsbActive, "Start without a prior qualifying output")
                val minimum = AudioTrack.getMinBufferSize(rate, stereo, AudioFormat.ENCODING_PCM_FLOAT)
                verify(minimum in 1 until inputConfig.bufferSize, "Fixture needs a smaller valid platform minimum")
                provider.getAudioOutput(inputConfig).release()
                verify(attempts.single().bufferSize == (minimum + 7) / 8 * 8,
                    "First qualifying Android 11 output should use the frame-aligned minimum")
                val lowRate = inputConfig.buildUpon().setSampleRate(48000).build()
                provider.getAudioOutput(lowRate).release()
                verify(attempts.last() == lowRate && mixerSets == 0 && mixerClears == 0,
                    "48 kHz should keep Normal buffering and API 30 should avoid mixer calls")
            }
            else -> error("Unknown USB regression suite: $suite")
        }
        return checks
    }
}
