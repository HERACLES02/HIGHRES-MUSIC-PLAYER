@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.content.Context
import android.media.AudioTrack
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import org.json.JSONObject

/** Start local PCM promptly without reducing the buffer that protects ongoing playback. */
class LocalAudioTrackProvider : DefaultAudioSink.AudioTrackProvider by DefaultAudioSink.AudioTrackProvider.DEFAULT {
    data class Startup(val sampleRate: Int, val capacityFrames: Int, val originalFrames: Int?,
        val requestedFrames: Int?, val actualFrames: Int?, val status: String) {
        fun json() = JSONObject().put("sampleRate", sampleRate).put("capacityFrames", capacityFrames)
            .put("originalThresholdFrames", originalFrames ?: JSONObject.NULL)
            .put("requestedThresholdFrames", requestedFrames ?: JSONObject.NULL)
            .put("actualThresholdFrames", actualFrames ?: JSONObject.NULL).put("status", status)
    }
    @Volatile var startup: Startup? = null
        private set

    override fun getAudioTrack(config: AudioSink.AudioTrackConfig, attributes: AudioAttributes,
        sessionId: Int, context: Context?): AudioTrack {
        val track = DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config, attributes, sessionId, context)
        val pcm = config.encoding in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_8BIT)
        if (Build.VERSION.SDK_INT < 31 || !pcm || track.isOffloadedPlayback) {
            startup = Startup(config.sampleRate, track.bufferCapacityInFrames, null, null, null,
                if (Build.VERSION.SDK_INT < 31) "Platform startup threshold (Android 11)" else "Encoded/offloaded output unchanged")
            return track
        }
        val original = track.startThresholdInFrames
        // Never raise a platform's already smaller threshold. Keep all default allocation,
        // route selection and steady-state buffering; this is not an exclusive output path.
        val requested = minOf(original, (config.sampleRate / 10).coerceAtLeast(1))
        try {
            val actual = track.setStartThresholdInFrames(requested)
            startup = Startup(config.sampleRate, track.bufferCapacityInFrames, original, requested, actual,
                if (actual <= requested) "Local PCM startup threshold applied" else "Platform clamped startup threshold")
        } catch (_: IllegalArgumentException) {
            startup = Startup(config.sampleRate, track.bufferCapacityInFrames, original, requested, null,
                "Platform rejected startup threshold; default retained")
        } catch (_: IllegalStateException) {
            startup = Startup(config.sampleRate, track.bufferCapacityInFrames, original, requested, null,
                "Platform rejected startup threshold; default retained")
        }
        return track
    }
}
