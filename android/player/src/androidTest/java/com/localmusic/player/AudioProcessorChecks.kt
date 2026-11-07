@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.localmusic.core.AudioSettings
import com.localmusic.core.DspConfiguration
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Runs against the actual Media3 buffers and processor, without a mock audio sink. */
class AudioProcessorChecks : Instrumentation() {
    private var suite: String? = null
    private var fixtureUri: String? = null
    private var seekVariant: String? = null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); suite = arguments?.getString("suite"); fixtureUri = arguments?.getString("fixtureUri"); seekVariant = arguments?.getString("seekBuffer"); start() }

    override fun onStart() {
        val results = Bundle()
        try {
            if (suite == "precision") {
                val count = PrecisionAudioChecks.run(targetContext)
                results.putInt("checksPassed", count)
                results.putString("stream", "\nPrecision audio checks passed: $count\n")
                finish(Activity.RESULT_OK, results)
                return
            }
            if (suite == "flac-queue") {
                results.putString("stream", "\n" + FlacIndexChecks.queueRegression(targetContext, requireNotNull(fixtureUri)))
                results.putInt("checksPassed", 1)
                finish(Activity.RESULT_OK, results)
                return
            }
            if (suite == "flac-content") {
                results.putString("stream", "\n" + FlacIndexChecks.withContentFixture(targetContext, requireNotNull(fixtureUri)) {
                    SeekChecks.run(this, it, "production")
                })
                results.putInt("checksPassed", 1)
                finish(Activity.RESULT_OK, results)
                return
            }
            if (suite == "flac-index") {
                results.putString("stream", "\n" + FlacIndexChecks.run(targetContext, requireNotNull(fixtureUri)))
                results.putInt("checksPassed", 1)
                finish(Activity.RESULT_OK, results)
                return
            }
            if (suite == "seek") {
                results.putString("stream", "\n" + SeekChecks.run(this, requireNotNull(fixtureUri), seekVariant))
                results.putInt("checksPassed", 1)
                finish(Activity.RESULT_OK, results)
                return
            }
            if (suite == "artwork") {
                val count = ArtworkChecks.run(targetContext, fixtureUri)
                results.putInt("checksPassed", count)
                results.putString("stream", "\nArtwork checks passed: $count\n")
                finish(Activity.RESULT_OK, results)
                return
            }
            var passed = 0
            for (encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT,
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)) {
                for (eqEnabled in listOf(false, true)) {
                    val processor = MusicAudioProcessor()
                    processor.configuration = DspConfiguration(settings = AudioSettings(eqEnabled = eqEnabled))
                    processor.configure(AudioProcessor.AudioFormat(44100, 2, encoding))
                    processor.flush()

                    // Media3 uses this shared zero-capacity buffer while draining the pipeline.
                    processor.queueInput(AudioProcessor.EMPTY_BUFFER)
                    check(!processor.output.hasRemaining()) { "Empty input produced PCM" }

                    val bytesPerSample = when (encoding) {
                        C.ENCODING_PCM_16BIT -> 2
                        C.ENCODING_PCM_24BIT -> 3
                        else -> 4
                    }
                    val expected = if (encoding == C.ENCODING_PCM_FLOAT) {
                        ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
                            repeat(8) { putFloat((it - 4) / 16f) }
                        }.array()
                    } else ByteArray(bytesPerSample * 8) { (it * 7 - 60).toByte() }
                    val input = ByteBuffer.allocateDirect(expected.size).order(ByteOrder.LITTLE_ENDIAN)
                    input.put(expected).flip()
                    processor.queueInput(input)
                    check(!input.hasRemaining()) { "PCM input was not consumed" }

                    // An empty input must not erase output waiting for the sink to consume it.
                    processor.queueInput(AudioProcessor.EMPTY_BUFFER)
                    val output = processor.output
                    val actual = ByteArray(output.remaining()).also { output.get(it) }
                    check(actual.contentEquals(expected)) { "Flat/bypass PCM changed: $encoding, EQ=$eqEnabled" }

                    processor.queueInput(AudioProcessor.EMPTY_BUFFER)
                    check(!processor.output.hasRemaining()) { "Empty input replayed prior PCM" }
                    processor.queueEndOfStream()
                    check(processor.isEnded) { "Empty drain prevented end of stream" }
                    processor.flush()
                    processor.queueInput(AudioProcessor.EMPTY_BUFFER)
                    check(!processor.output.hasRemaining()) { "Flush retained old PCM" }
                    processor.reset()
                    passed++
                }
            }
            results.putString("stream", "\nAudio processor checks passed: $passed format/EQ combinations\n")
            results.putInt("checksPassed", passed)
            finish(Activity.RESULT_OK, results)
        } catch (failure: Throwable) {
            results.putString("stream", "\nAudio processor checks FAILED:\n${failure.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, results)
        }
    }
}
