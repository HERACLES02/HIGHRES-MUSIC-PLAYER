package com.localmusic.player

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun verifyPeerMp3(file: File): Boolean {
    val extractor = MediaExtractor()
    try {
        file.inputStream().use { extractor.setDataSource(it.fd) }
        val mime = MediaFormat.MIMETYPE_AUDIO_MPEG
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == mime
        } ?: return false
        extractor.selectTrack(track)
        val decoder = MediaCodec.createDecoderByType(mime)
        try {
            decoder.configure(extractor.getTrackFormat(track), null, null, 0)
            decoder.start()
            val eos = MediaCodec.BUFFER_FLAG_END_OF_STREAM
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var decodedAudio = false
            var lastProgress = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - lastProgress < 15_000) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val input = decoder.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        val buffer = checkNotNull(decoder.getInputBuffer(input))
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(input, 0, 0, 0, eos)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, 10_000)
                if (output >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) decodedAudio = true
                    decoder.releaseOutputBuffer(output, false)
                    if (info.flags and eos != 0) return inputEnded && decodedAudio
                    lastProgress = SystemClock.elapsedRealtime()
                }
            }
            return false
        } finally {
            decoder.release()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        return false
    } catch (_: IllegalArgumentException) {
        return false
    } catch (_: IllegalStateException) {
        return false
    } finally {
        extractor.release()
    }
}
