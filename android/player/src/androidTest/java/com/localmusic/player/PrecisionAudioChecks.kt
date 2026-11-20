@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercise the production renderer/sink with a real, muted emulator AudioTrack. */
internal object PrecisionAudioChecks {
    fun run(context: Context): Int {
        val thread = HandlerThread("precision-audio-check").apply { start() }
        val done = CountDownLatch(1)
        var result: Result<Int>? = null
        Handler(thread.looper).post {
            try { result = runCatching { runOnAudioThread(context) } } finally { done.countDown() }
        }
        try {
            check(done.await(30, TimeUnit.SECONDS)) { "Precision audio check timed out" }
            return requireNotNull(result).getOrThrow()
        } finally { thread.quitSafely() }
    }

    private fun runOnAudioThread(context: Context): Int {
        val sink = MusicRenderers(context, MusicAudioProcessor(), LocalAudioTrackProvider()).buildAudioSink(context, false, false)
        var initializedEncoding: Int? = null
        sink.setListener(object : AudioSink.Listener {
            override fun onPositionDiscontinuity() {}
            override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {}
            override fun onSkipSilenceEnabledChanged(enabled: Boolean) {}
            override fun onAudioTrackInitialized(config: AudioSink.AudioTrackConfig) { initializedEncoding = config.encoding }
        })
        try {
            sink.setVolume(0f)
            sink.configure(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setSampleRate(96000)
                .setChannelCount(2).setPcmEncoding(C.ENCODING_PCM_24BIT).build(), 0, null)
            val input = ByteBuffer.allocateDirect(600).order(ByteOrder.LITTLE_ENDIAN)
            repeat(100) { input.put(1).put(0).put(0).put(2).put(0).put(0) }
            input.flip()
            repeat(200) {
                if (initializedEncoding == null) { sink.handleBuffer(input, 0, 1); Thread.sleep(5) }
            }
            check(initializedEncoding == C.ENCODING_PCM_FLOAT) {
                "Production 24-bit path requested encoding $initializedEncoding, expected PCM_FLOAT (${C.ENCODING_PCM_FLOAT})"
            }
            return 1 + PrecisionBoundaryChecks.run(context) + PrecisionTrackChecks.run(context)
        } finally { sink.reset(); sink.release() }
    }
}
