package com.localmusic.core

import java.nio.ByteBuffer

/** The app halved the song's rate (once or twice), or doubled it, before handing it to Android. */
data class AppResample(val fromHz: Int, val toHz: Int) {
    init { require(fromHz > 0 && (fromHz == toHz * 2 || fromHz == toHz * 4 || toHz == fromHz * 2)) { "Only 2:1, 4:1 and 1:2 are supported" } }
    val up: Boolean get() = toHz > fromHz
    val stages: Int get() = if (fromHz == toHz * 4) 2 else 1
}

/**
 * Normal-mode Android USB route only: hand Android a rate the one USB DAC lists, in the same rate family, by an
 * integer step. Down: 176.4/352.8 → 88.2, 192/384 → 96 kHz. Up (Android 11–13 only): 44.1 → 88.2, 48 → 96 kHz,
 * because there Android's USB route only takes rates above 48 kHz and otherwise mixes them at 48 kHz.
 */
object RateFit {
    /** Returns [sourceHz] when the app should not resample. [dacRates] is empty unless the caller's conditions
     *  hold (Prefer Android USB and Match DAC rate on, exactly one USB DAC). */
    fun target(sourceHz: Int, dacRates: Set<Int>, sdkInt: Int, channelCount: Int = 2, carriesEncoderTrim: Boolean = false): Int {
        // Media3 trims encoder delay/padding in frames at the configured rate; skipping these formats keeps that exact.
        if (channelCount != 2 || carriesEncoderTrim) return sourceHz
        // Android 14+ exact-rate mixer attributes already accept 44.1/48 kHz, so only Android 11–13 up-fits.
        if (!AndroidUsbBufferRules.eligibleRate(sourceHz)) {
            val doubled = sourceHz * 2
            return if (sdkInt in 30..33 && doubled in dacRates && AndroidUsbBufferRules.eligibleRate(doubled)) doubled else sourceHz
        }
        if (sourceHz in dacRates) return sourceHz
        var rate = sourceHz
        repeat(2) {
            if (rate % 2 != 0) return sourceHz
            rate /= 2
            if (!AndroidUsbBufferRules.eligibleRate(rate)) return sourceHz
            if (rate in dacRates) return rate
        }
        return sourceHz
    }

    fun converter(fit: AppResample, channels: Int): PcmRateConverter =
        if (fit.up) HalfbandInterpolator(channels) else HalfbandDecimator(channels, fit.stages)

    fun describe(fit: AppResample): String = "${PlaybackPresentation.khz(fit.fromHz)} → ${PlaybackPresentation.khz(fit.toHz)} kHz (" +
        (if (fit.up) "1:2 halfband" else if (fit.stages == 1) "2:1 halfband" else "4:1, two 2:1 halfband stages") + ")"
}

/** One integer rate step on interleaved little-endian float PCM, history kept across buffers. */
interface PcmRateConverter {
    /** Consumes all of [input]; returns the converter's own output buffer, valid until the next call. */
    fun process(input: ByteBuffer): ByteBuffer
    fun reset()
}

/**
 * 2:1 decimation (4:1 as two stages) of interleaved little-endian float PCM: a linear-phase 111-tap Kaiser-windowed
 * halfband FIR in Double arithmetic, history kept across buffers. Per stage: flat within 0.00001 dB up to 0.208 of the
 * input rate (40 kHz at 192 kHz), at least 130 dB down from 0.292 (56 kHz). Delay: 55 input frames per stage.
 * Output can exceed ±1.0 (inter-sample peaks); nothing here limits it.
 */
class HalfbandDecimator(channels: Int, stages: Int) : PcmRateConverter {
    init { require(channels > 0 && stages in 1..2) }
    private val stageList = List(stages) { Stage(channels) }

    override fun process(input: ByteBuffer): ByteBuffer {
        var data = input
        for (stage in stageList) data = stage.process(data)
        return data
    }

    override fun reset() { stageList.forEach(Stage::reset) }

    private class Stage(private val channels: Int) {
        private val history = Array(channels) { DoubleArray(2 * TAPS) }
        private var position = 0
        private var odd = false
        private var output: ByteBuffer = ByteBuffer.allocateDirect(0).order(java.nio.ByteOrder.LITTLE_ENDIAN)

        fun reset() { history.forEach { it.fill(0.0) }; position = 0; odd = false }

        fun process(input: ByteBuffer): ByteBuffer {
            val frameBytes = 4 * channels
            require(input.remaining() % frameBytes == 0) { "Partial PCM frame" }
            val frames = input.remaining() / frameBytes
            val needed = (frames / 2 + 1) * frameBytes
            if (output.capacity() < needed) output = ByteBuffer.allocateDirect(needed).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            output.clear()
            val source = input.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var at = source.position()
            repeat(frames) {
                for (c in 0 until channels) {
                    val x = source.getFloat(at).toDouble(); at += 4
                    // Mirrored delay line: window history[position+1 .. position+TAPS] runs oldest to newest.
                    history[c][position] = x; history[c][position + TAPS] = x
                }
                if (odd) for (c in 0 until channels) {
                    val h = history[c]; val center = position + 1 + HALF
                    var y = 0.5 * h[center]
                    for (j in ODD.indices) { val k = 2 * j + 1; y += ODD[j] * (h[center - k] + h[center + k]) }
                    output.putFloat(y.toFloat())
                }
                odd = !odd
                position = if (position + 1 == TAPS) 0 else position + 1
            }
            input.position(input.limit())
            output.flip()
            return output
        }
    }

    private companion object {
        const val HALF = 55
        const val TAPS = 2 * HALF + 1
        /** Coefficients at offsets ±1, ±3, … ±55 from the centre tap (0.5). */
        val ODD: DoubleArray = halfbandOddTaps(HALF, 14.0)
    }
}

/**
 * 1:2 interpolation of interleaved little-endian float PCM: zero-stuffing then a linear-phase 199-tap Kaiser-windowed
 * halfband FIR in Double arithmetic with gain 2 (level unchanged), history kept across buffers. Every other output
 * frame is an input frame passed through unchanged; the frames between are computed. Sharper than the decimator's
 * filter because 44.1 → 88.2 kHz leaves only 20 → 24.1 kHz for the transition: flat within 0.00001 dB up to 0.2268 of
 * the output rate (20 kHz at 88.2 kHz), images at least 130 dB down from 0.2732 (24.1 kHz). Delay: 50 input frames.
 * Output can exceed ±1.0 (inter-sample peaks); nothing here limits it.
 */
class HalfbandInterpolator(private val channels: Int) : PcmRateConverter {
    init { require(channels > 0) }
    private val history = Array(channels) { DoubleArray(2 * LENGTH) }
    private var position = 0
    private var output: ByteBuffer = ByteBuffer.allocateDirect(0).order(java.nio.ByteOrder.LITTLE_ENDIAN)

    override fun reset() { history.forEach { it.fill(0.0) }; position = 0 }

    override fun process(input: ByteBuffer): ByteBuffer {
        val frameBytes = 4 * channels
        require(input.remaining() % frameBytes == 0) { "Partial PCM frame" }
        val frames = input.remaining() / frameBytes
        val needed = 2 * frames * frameBytes
        if (output.capacity() < needed) output = ByteBuffer.allocateDirect(needed).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        output.clear()
        val source = input.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var at = source.position()
        repeat(frames) {
            for (c in 0 until channels) {
                val x = source.getFloat(at).toDouble(); at += 4
                // Mirrored delay line: window history[position+1 .. position+LENGTH] runs oldest to newest.
                history[c][position] = x; history[c][position + LENGTH] = x
            }
            // The window's two middle frames: the older one passes through, then the point halfway to the newer one.
            for (c in 0 until channels) output.putFloat(history[c][position + DELAY].toFloat())
            for (c in 0 until channels) {
                val h = history[c]; val older = position + DELAY
                var y = 0.0
                for (j in ODD.indices) y += ODD[j] * (h[older + 1 + j] + h[older - j])
                output.putFloat((2 * y).toFloat())
            }
            position = if (position + 1 == LENGTH) 0 else position + 1
        }
        input.position(input.limit())
        output.flip()
        return output
    }

    private companion object {
        const val HALF = 99
        /** Input frames held: the halfband's non-zero taps at the input rate. */
        const val LENGTH = HALF + 1
        const val DELAY = LENGTH / 2
        val ODD: DoubleArray = halfbandOddTaps(HALF, 14.5)
    }
}

/** Kaiser-windowed halfband taps at offsets ±1, ±3, … ±[half] from the centre tap (0.5); even offsets are zero. */
private fun halfbandOddTaps(half: Int, beta: Double): DoubleArray {
    fun i0(x: Double): Double { var sum = 1.0; var term = 1.0; var k = 1
        while (term > 1e-20 * sum) { term *= (x / (2 * k)) * (x / (2 * k)); sum += term; k++ }; return sum }
    val raw = DoubleArray((half + 1) / 2) { j ->
        val n = 2 * j + 1
        val window = i0(beta * kotlin.math.sqrt(1 - (n.toDouble() / half).let { it * it })) / i0(beta)
        kotlin.math.sin(kotlin.math.PI * n / 2) / (kotlin.math.PI * n) * window
    }
    // Both sides of the odd taps sum to exactly 0.5, so DC gain is exactly 1.
    val scale = 0.25 / raw.sum()
    return DoubleArray(raw.size) { raw[it] * scale }
}
