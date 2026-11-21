package com.localmusic.core

import kotlin.math.abs

fun clippingFeedbackChecks() {
    var count = 0
    fun verify(ok: Boolean, label: String) { count++; check(ok) { "Clipping feedback: $label" } }

    val kernel = DspKernel()
    val boostedSettings = AudioSettings(
        eqEnabled = true,
        preset = EqPreset(name = "Boost", preampDb = 0.0, gains = List(10) { if (it == 5) 6.0 else 0.0 })
    )
    val safeSettings = AudioSettings(
        eqEnabled = true,
        preset = EqPreset(name = "Safe", preampDb = -6.0, gains = List(10) { if (it == 5) 6.0 else 0.0 })
    )

    // 1. Positive polarity: below, at, and above clamp
    kernel.configure(48000, 2, DspConfiguration(settings = AudioSettings(eqEnabled = false)))
    // Pass-through when disabled: no clipping
    kernel.sample(1.5, 0)
    verify(!kernel.lastSampleClipped, "disabled DSP does not report clipping")
    verify(kernel.clippedSamples == 0L, "disabled DSP sample count stays 0")

    // Configure with flat active EQ
    kernel.configure(48000, 2, DspConfiguration(settings = AudioSettings(eqEnabled = true, preset = EqPreset(preampDb = 0.0))))
    repeat(1000) { kernel.sample(0.0, 0) } // settle smoothing

    // Positive below clamp: 0.999
    val outBelowPos = kernel.sample(0.999, 0)
    verify(!kernel.lastSampleClipped, "positive below clamp (0.999) is not clipped")
    verify(kernel.clippedSamples == 0L, "count 0 below clamp")
    verify(abs(outBelowPos - 0.999) < 0.001, "output preserves sample value")

    // Positive at clamp: 1.0
    val outAtPos = kernel.sample(1.0, 0)
    verify(!kernel.lastSampleClipped, "positive at clamp (1.0) is not clipped")
    verify(kernel.clippedSamples == 0L, "count 0 at clamp")
    verify(abs(outAtPos - 1.0) < 0.001, "output reaches clamp")

    // Positive above clamp: 1.001, 1.5
    val outAbovePos1 = kernel.sample(1.001, 0)
    verify(kernel.lastSampleClipped, "positive above clamp (1.001) is clipped")
    verify(kernel.clippedSamples == 1L, "count 1 above clamp")
    verify(outAbovePos1 <= 1.0, "output clamped to 1.0")

    val outAbovePos2 = kernel.sample(1.5, 0)
    verify(kernel.lastSampleClipped, "positive above clamp (1.5) is clipped")
    verify(kernel.clippedSamples == 2L, "count 2 above clamp")
    verify(outAbovePos2 == 1.0, "output clamped to 1.0")

    // 2. Negative polarity: below, at, and above clamp
    kernel.resetClipping()
    // Negative below clamp: -0.999
    val outBelowNeg = kernel.sample(-0.999, 0)
    verify(!kernel.lastSampleClipped, "negative below clamp (-0.999) is not clipped")
    verify(kernel.clippedSamples == 0L, "negative count 0 below clamp")
    verify(abs(outBelowNeg - (-0.999)) < 0.001, "output preserves negative sample")

    // Negative at clamp: -1.0
    val outAtNeg = kernel.sample(-1.0, 0)
    verify(!kernel.lastSampleClipped, "negative at clamp (-1.0) is not clipped")
    verify(kernel.clippedSamples == 0L, "negative count 0 at clamp")
    verify(abs(outAtNeg - (-1.0)) < 0.001, "output reaches negative clamp")

    // Negative above clamp: -1.001, -2.0
    val outAboveNeg1 = kernel.sample(-1.001, 0)
    verify(kernel.lastSampleClipped, "negative above clamp (-1.001) is clipped")
    verify(kernel.clippedSamples == 1L, "negative count 1 above clamp")
    verify(outAboveNeg1 >= -1.0, "output clamped to -1.0")

    val outAboveNeg2 = kernel.sample(-2.0, 0)
    verify(kernel.lastSampleClipped, "negative above clamp (-2.0) is clipped")
    verify(kernel.clippedSamples == 2L, "negative count 2 above clamp")
    verify(outAboveNeg2 == -1.0, "output clamped to -1.0")

    // 3. Stereo input: channel 0 and channel 1
    kernel.resetClipping()
    kernel.sample(0.5, 0)
    kernel.sample(1.2, 1) // right channel clips
    verify(kernel.lastSampleClipped, "right channel clipping detected")
    verify(kernel.clippedSamples == 1L, "right channel incremented clipped samples")

    kernel.sample(1.3, 0) // left channel clips
    verify(kernel.lastSampleClipped, "left channel clipping detected")
    verify(kernel.clippedSamples == 2L, "left channel incremented clipped samples")

    // 4. Boosted vs safe settings
    // A 1000 Hz peaking filter boosts 1000 Hz frequency. Input of 0.8 at 1000 Hz with +6dB boost reaches ~1.6 and clips.
    kernel.configure(48000, 2, DspConfiguration(settings = boostedSettings))
    repeat(1000) { kernel.sample(0.0, 0) }
    var boostedClipped = false
    for (i in 0 until 480) {
        val sample = 0.8 * kotlin.math.sin(2.0 * kotlin.math.PI * 1000.0 * i / 48000.0)
        kernel.sample(sample, 0)
        if (kernel.lastSampleClipped) boostedClipped = true
    }
    verify(boostedClipped, "boosted setting causes clipping on 1000 Hz sine wave")
    val boostedCount = kernel.clippedSamples
    verify(boostedCount > 0, "boosted setting recorded clipped samples: $boostedCount")

    // Safe settings: preamp lowered to -6 dB compensates for the +6 dB boost
    kernel.configure(48000, 2, DspConfiguration(settings = safeSettings))
    verify(kernel.clippedSamples == 0L, "DSP configuration change resets clipped count")
    verify(!kernel.lastSampleClipped, "DSP configuration change resets lastSampleClipped")
    repeat(1000) { kernel.sample(0.0, 0) }
    var safeClipped = false
    for (i in 0 until 480) {
        val sample = 0.8 * kotlin.math.sin(2.0 * kotlin.math.PI * 1000.0 * i / 48000.0)
        kernel.sample(sample, 0)
        if (kernel.lastSampleClipped) safeClipped = true
    }
    verify(!safeClipped, "safe setting prevents clipping on 1000 Hz sine wave")
    verify(kernel.clippedSamples == 0L, "safe setting count remains 0")

    // 5. Track changes / seek flush: reset() clears clipping
    kernel.configure(48000, 2, DspConfiguration(settings = boostedSettings))
    repeat(1000) { kernel.sample(0.0, 0) }
    for (i in 0 until 480) {
        val sample = 0.8 * kotlin.math.sin(2.0 * kotlin.math.PI * 1000.0 * i / 48000.0)
        kernel.sample(sample, 0)
    }
    verify(kernel.clippedSamples > 0, "clipping occurred before track change")
    kernel.reset()
    verify(kernel.clippedSamples == 0L, "kernel.reset() resets clippedSamples")
    verify(!kernel.lastSampleClipped, "kernel.reset() resets lastSampleClipped")

    // 6. Output PCM mathematical equality before and after telemetry
    // Math curve and clamp behavior are unchanged: coerceIn(-1.0, 1.0)
    val testValues = listOf(-3.0, -1.5, -1.0, -0.5, 0.0, 0.5, 1.0, 1.5, 3.0)
    for (v in testValues) {
        val expected = v.coerceIn(-1.0, 1.0)
        // With flat active filter
        kernel.configure(48000, 2, DspConfiguration(settings = AudioSettings(eqEnabled = true, preset = EqPreset(preampDb = 0.0))))
        repeat(1000) { kernel.sample(0.0, 0) }
        val actual = kernel.sample(v, 0)
        verify(abs(actual - expected) < 0.001, "output PCM preserves exact clamp curve for $v: expected $expected, got $actual")
    }

    println("Clipping feedback checks passed: $count assertions.")
}
