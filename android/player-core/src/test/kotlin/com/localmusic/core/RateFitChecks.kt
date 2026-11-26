package com.localmusic.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

fun rateFitChecks() {
    var count = 0
    fun verify(ok: Boolean, msg: String) { count++; check(ok) { "Rate fit: $msg" } }

    // The KZ M2 lists these four rates to Android.
    val m2 = setOf(44100, 48000, 88200, 96000)
    verify(RateFit.target(192000, m2, 30) == 96000, "192 kHz fits to 96 kHz on a DAC listing 88.2/96")
    verify(RateFit.target(176400, m2, 30) == 88200, "176.4 kHz fits to 88.2 kHz")
    verify(RateFit.target(384000, m2, 30) == 96000, "384 kHz fits to 96 kHz in two 2:1 steps")
    verify(RateFit.target(352800, m2, 30) == 88200, "352.8 kHz fits to 88.2 kHz")
    verify(RateFit.target(96000, m2, 30) == 96000, "a listed rate is not resampled")
    verify(RateFit.target(192000, m2 + 192000, 30) == 192000, "a DAC that lists 192 kHz gets it unchanged")
    verify(RateFit.target(384000, m2 + 192000, 30) == 192000, "the smallest step the DAC lists wins")
    verify(RateFit.target(192000, setOf(44100, 48000), 30) == 192000, "no listed target above 48 kHz: no fitting")
    verify(RateFit.target(192000, emptySet(), 30) == 192000, "no DAC rates (preference/switch off, no DAC, two DACs): no fitting")
    verify(RateFit.target(192000, m2, 30, channelCount = 1) == 192000, "only stereo is fitted")
    verify(RateFit.target(192000, m2, 30, carriesEncoderTrim = true) == 192000,
        "formats carrying encoder delay/padding are not fitted (Media3 trims frames at the configured rate)")
    verify(RateFit.target(192000, m2, 34) == 96000, "down-fit is the same on Android 14+")
    verify(RateFit.target(44100, m2, 30) == 88200, "44.1 kHz up-fits to 88.2 kHz on Android 11")
    verify(RateFit.target(48000, m2, 30) == 96000, "48 kHz up-fits to 96 kHz on Android 11")
    verify(RateFit.target(44100, m2, 33) == 88200, "Android 13 still up-fits")
    verify(RateFit.target(44100, m2, 34) == 44100 && RateFit.target(48000, m2, 35) == 48000,
        "no up-fit on Android 14+ (exact-rate mixer attributes accept 44.1/48 kHz)")
    verify(RateFit.target(44100, m2, 29) == 44100, "no up-fit below Android 11 (no preferred USB route there)")
    verify(RateFit.target(44100, setOf(44100, 48000, 96000), 30) == 44100, "44.1 kHz on a DAC without 88.2 kHz is not up-fitted")
    verify(RateFit.target(48000, setOf(44100, 48000, 88200), 30) == 48000, "48 kHz on a DAC without 96 kHz is not up-fitted")
    verify(RateFit.target(44100, emptySet(), 30) == 44100, "no DAC rates: no up-fit")
    verify(RateFit.target(44100, m2, 30, channelCount = 1) == 44100, "only stereo is up-fitted")
    verify(RateFit.target(44100, m2, 30, carriesEncoderTrim = true) == 44100, "formats carrying encoder delay/padding are not up-fitted")
    verify(RateFit.target(24000, setOf(48000), 30) == 24000, "a doubled rate that is still <= 48 kHz is not a fit")
    verify(AppResample(44100, 88200).stages == 1, "1:2 is one halfband stage")
    verify(RateFit.describe(AppResample(44100, 88200)) == "44.1 → 88.2 kHz (1:2 halfband)", "Audio info line for 1:2")
    verify(AppResample(192000, 96000).stages == 1 && AppResample(384000, 96000).stages == 2, "2:1 is one halfband stage, 4:1 is two")
    verify(RateFit.describe(AppResample(192000, 96000)) == "192 → 96 kHz (2:1 halfband)", "Audio info line for 2:1")
    verify(RateFit.describe(AppResample(352800, 88200)) == "352.8 → 88.2 kHz (4:1, two 2:1 halfband stages)", "Audio info line for 4:1")

    fun stereo(frames: Int, left: (Int) -> Double, right: (Int) -> Double = left): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) { b.putFloat(left(i).toFloat()); b.putFloat(right(i).toFloat()) }
        b.flip(); return b
    }
    fun drain(out: ByteBuffer, into: MutableList<Float>) { while (out.hasRemaining()) into += out.float }
    fun run(converter: PcmRateConverter, input: ByteBuffer, chunks: List<Int> = emptyList()): FloatArray {
        val result = ArrayList<Float>()
        var frame = 0
        val total = input.remaining() / 8
        for (size in chunks + (total)) {
            val n = minOf(size, total - frame); if (n <= 0) break
            val slice = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            slice.position(frame * 8).limit((frame + n) * 8)
            val part = slice.slice().order(ByteOrder.LITTLE_ENDIAN)
            drain(converter.process(part), result)
            verify(!part.hasRemaining(), "the converter consumes its whole input")
            frame += n
        }
        return result.toFloatArray()
    }
    // Least-squares amplitude over a whole number of cycles of one channel.
    fun amplitude(out: FloatArray, channel: Int, hz: Double, rateHz: Int, from: Int, frames: Int): Double {
        var s = 0.0; var c = 0.0
        for (m in from until from + frames) {
            val w = 2 * PI * hz * m / rateHz; val y = out[2 * m + channel].toDouble()
            s += y * sin(w); c += y * cos(w)
        }
        return hypot(s, c) * 2 / frames
    }
    fun tone(hz: Double, rateHz: Int, level: Double = 0.5): (Int) -> Double = { i -> level * sin(2 * PI * hz * i / rateHz) }
    val withinHundredthDb = 10.0.pow(-0.01 / 20)..10.0.pow(0.01 / 20)
    val skip = 192; val measured = 9600 // 9600 output frames hold whole cycles of 1 kHz and 18 kHz at 96 kHz

    val dc = run(HalfbandDecimator(2, 1), stereo((skip + measured) * 2, { 0.5 }))
    verify(dc.size == (skip + measured) * 2, "2:1 output has exactly half the frames")
    verify((skip until skip + measured).all { abs(dc[2 * it] - 0.5) < 1e-6 && abs(dc[2 * it + 1] - 0.5) < 1e-6 }, "DC gain is 1")
    for (hz in listOf(1000.0, 18000.0)) {
        val out = run(HalfbandDecimator(2, 1), stereo((skip + measured) * 2, tone(hz, 192000)))
        val gain = amplitude(out, 0, hz, 96000, skip, measured) / 0.5
        verify(gain in withinHundredthDb, "$hz Hz keeps its level within 0.01 dB at 192 → 96 kHz (gain $gain)")
    }
    // Stopband starts at 56 kHz; a weaker window's ripple peaks near that edge, so 70 kHz alone would not catch it.
    for (hz in listOf(57000.0, 60000.0, 70000.0, 90000.0)) {
        val alias = run(HalfbandDecimator(2, 1), stereo((skip + measured) * 2, tone(hz, 192000, 1.0)))
        val aliasPeak = (2 * skip until alias.size).maxOf { abs(alias[it]).toDouble() }
        verify(aliasPeak <= 1e-6, "a full-scale $hz Hz tone at 192 kHz is at or below -120 dB after decimation (peak $aliasPeak)")
    }

    val noise = Random(7).let { r -> DoubleArray(20000) { r.nextDouble(-0.9, 0.9) } }
    val noisy = stereo(noise.size / 2, { noise[2 * it] }, { noise[2 * it + 1] })
    val whole = run(HalfbandDecimator(2, 1), noisy.duplicate())
    val split = run(HalfbandDecimator(2, 1), noisy.duplicate(), listOf(1, 7, 333, 4099, 1, 2))
    verify(whole.size == noise.size / 2 && whole.contentEquals(split), "odd-sized buffer splits give the same output as one buffer")
    val reused = HalfbandDecimator(2, 1)
    run(reused, stereo(1001, tone(5000.0, 192000)))
    reused.reset()
    verify(run(reused, noisy.duplicate()).contentEquals(whole), "reset clears the filter history")
    val leftOnly = run(HalfbandDecimator(2, 1), stereo(4000, tone(1000.0, 192000), { 0.0 }))
    verify((leftOnly.indices step 2).any { leftOnly[it] != 0f } && (1 until leftOnly.size step 2).all { leftOnly[it] == 0f },
        "channels stay independent (left-only tone leaves right silent)")

    val fourIn = (skip + measured) * 4
    val four = run(HalfbandDecimator(2, 2), stereo(fourIn, tone(1000.0, 384000)))
    verify(four.size == fourIn / 2, "4:1 output has exactly a quarter of the frames")
    verify(amplitude(four, 0, 1000.0, 96000, skip, measured) / 0.5 in withinHundredthDb, "1 kHz keeps its level at 384 → 96 kHz")
    val fourAlias = run(HalfbandDecimator(2, 2), stereo(fourIn, tone(70000.0, 384000, 1.0)))
    verify((2 * skip until fourAlias.size).maxOf { abs(fourAlias[it]).toDouble() } <= 1e-6, "a 70 kHz tone at 384 kHz is at or below -120 dB at 96 kHz")

    // 1:2 interpolation (up-fit). Window lengths hold whole cycles of every tone and image measured below.
    verify(RateFit.converter(AppResample(44100, 88200), 2) is HalfbandInterpolator &&
        RateFit.converter(AppResample(192000, 96000), 2) is HalfbandDecimator, "up-fits interpolate, down-fits decimate")
    for ((inRate, window) in listOf(44100 to 8820, 48000 to 9600)) {
        val outRate = inRate * 2
        val inFrames = (skip + window) / 2
        val up = run(HalfbandInterpolator(2), stereo(inFrames, { 0.5 }))
        verify(up.size == inFrames * 4, "1:2 output has exactly twice the frames at $inRate Hz")
        verify((skip until skip + window).all { abs(up[2 * it] - 0.5) < 1e-6 && abs(up[2 * it + 1] - 0.5) < 1e-6 }, "DC gain is 1 at $inRate → $outRate")
        // Passband edge: 20 kHz must stay flat even at 44.1 → 88.2, where it sits 4.1 kHz below the first image.
        for (hz in listOf(1000.0, 18000.0, 19500.0, 20000.0)) {
            val out = run(HalfbandInterpolator(2), stereo(inFrames, tone(hz, inRate)))
            val gain = amplitude(out, 0, hz, outRate, skip, window) / 0.5
            verify(gain in withinHundredthDb, "$hz Hz keeps its level within 0.01 dB at $inRate → $outRate (gain $gain)")
        }
        // Zero-stuffing images a tone at f to (input rate - f). Near the band edge a weaker filter's ripple peaks,
        // so the tones closest to 20 kHz matter most.
        for (hz in listOf(1000.0, 10000.0, 18000.0, 19000.0, 19500.0, 20000.0)) {
            val out = run(HalfbandInterpolator(2), stereo(inFrames, tone(hz, inRate, 1.0)))
            val image = amplitude(out, 0, inRate - hz, outRate, skip, window)
            verify(image <= 1e-6, "the image of a full-scale $hz Hz tone at ${inRate - hz} Hz is at or below -120 dB at $outRate (level $image)")
        }
    }
    val noise44 = stereo(noise.size / 2, { noise[2 * it] }, { noise[2 * it + 1] })
    val upWhole = run(HalfbandInterpolator(2), noise44.duplicate())
    val upSplit = run(HalfbandInterpolator(2), noise44.duplicate(), listOf(1, 7, 333, 4099, 1, 2))
    verify(upWhole.size == noise.size * 2 && upWhole.contentEquals(upSplit), "1:2 output is the same whole or split into odd-sized buffers")
    val upReused = HalfbandInterpolator(2)
    run(upReused, stereo(1001, tone(5000.0, 44100)))
    upReused.reset()
    verify(run(upReused, noise44.duplicate()).contentEquals(upWhole), "interpolator reset clears the filter history")
    val upLeft = run(HalfbandInterpolator(2), stereo(4000, tone(1000.0, 44100), { 0.0 }))
    verify((upLeft.indices step 2).any { upLeft[it] != 0f } && (1 until upLeft.size step 2).all { upLeft[it] == 0f },
        "interpolator channels stay independent (left-only tone leaves right silent)")
    // Round trip 44.1 → 88.2 → 44.1 returns the music (here: tones up to 15 kHz) after the two filters' 77-frame delay.
    val music: (Int) -> Double = { i -> tone(440.0, 44100, 0.4)(i) + tone(5000.0, 44100, 0.3)(i) + tone(15000.0, 44100, 0.2)(i) }
    val upOut = run(HalfbandInterpolator(2), stereo(6000, music, { -music(it) }))
    val trip = run(HalfbandDecimator(2, 1), stereo(upOut.size / 2, { upOut[2 * it].toDouble() }, { upOut[2 * it + 1].toDouble() }))
    verify(trip.size == 12000, "round trip keeps the frame count")
    val tripError = (400 until 6000).maxOf { maxOf(abs(trip[2 * it] - music(it - 77)), abs(trip[2 * it + 1] + music(it - 77))) }
    verify(tripError < 1e-5, "1:2 then 2:1 returns the original within 1e-5 (error $tripError)")
    println("Rate fit and halfband decimator/interpolator checks passed: $count assertions.")
}
