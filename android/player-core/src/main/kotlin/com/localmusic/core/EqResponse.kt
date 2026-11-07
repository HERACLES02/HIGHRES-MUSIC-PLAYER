package com.localmusic.core

import kotlin.math.*

data class EqResponsePoint(val frequencyHz: Double, val gainDb: Double)
data class EqHeadroom(val peakFilterGainDb: Double, val recommendedPreampDb: Double)

/** Frequency response of the configured EQ, before ReplayGain and sample clipping. */
class EqResponse(private val preset: EqPreset, val sampleRate: Int = 48000) {
    init { require(sampleRate in 8000..768000) }
    private val coefficients = preset.resolvedFilters().map { it.biquadCoefficients(sampleRate) }

    fun gainDbAt(frequencyHz: Double): Double {
        require(frequencyHz.isFinite() && frequencyHz in 0.0..sampleRate / 2.0)
        val omega = 2 * PI * frequencyHz / sampleRate
        val c = cos(omega); val s = sin(omega)
        val c2 = cos(2 * omega); val s2 = sin(2 * omega)
        return preset.preampDb + coefficients.sumOf { b ->
            val real = b[0] + b[1] * c + b[2] * c2
            val imag = -b[1] * s - b[2] * s2
            val denominatorReal = 1 + b[3] * c + b[4] * c2
            val denominatorImag = -b[3] * s - b[4] * s2
            10 * log10(((real * real + imag * imag) /
                (denominatorReal * denominatorReal + denominatorImag * denominatorImag)).coerceAtLeast(1e-30))
        }
    }

    fun curve(pointCount: Int = 201): List<EqResponsePoint> {
        require(pointCount in 2..4097)
        val end = minOf(20000.0, sampleRate * .499)
        return List(pointCount) { i ->
            val frequency = exp(ln(20.0) + ln(end / 20.0) * i / (pointCount - 1))
            EqResponsePoint(frequency, gainDbAt(frequency))
        }
    }

    /** Estimate only: a dense steady-state response scan, with 0.5 dB spare room.
     * Does not model transients, ReplayGain, crossfade or the final hardware path. */
    fun headroom(): EqHeadroom {
        val end = sampleRate * .499
        var peak = 0.0
        for (i in 0..4096) {
            val frequency = exp(ln(end) * i / 4096)
            peak = maxOf(peak, gainDbAt(frequency) - preset.preampDb)
        }
        for (filter in preset.resolvedFilters()) {
            peak = maxOf(peak, gainDbAt(filter.frequencyHz.coerceAtMost(end)) - preset.preampDb)
        }
        val recommended = if (peak < .05) 0.0 else (floor((-peak - .5) * 10) / 10).coerceAtLeast(-24.0)
        return EqHeadroom(peak, recommended)
    }
}

/** Same normalized biquad coefficients for playback and the response preview. */
internal fun EqFilter.biquadCoefficients(sampleRate: Int): DoubleArray {
    val frequency = frequencyHz; val db = gainDb
    if (frequency >= sampleRate * .49 || db == 0.0) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)
    val a = 10.0.pow(db / 40); val omega = 2 * PI * frequency / sampleRate; val c = cos(omega); val s = sin(omega)
    val alpha = s / (2 * q)
    val raw = when (type) {
        EqFilterType.PEAKING -> doubleArrayOf(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
        EqFilterType.LOW_SHELF -> {
            val beta = 2 * sqrt(a) * alpha
            doubleArrayOf(a * ((a + 1) - (a - 1) * c + beta), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - beta),
                (a + 1) + (a - 1) * c + beta, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - beta)
        }
        EqFilterType.HIGH_SHELF -> {
            val beta = 2 * sqrt(a) * alpha
            doubleArrayOf(a * ((a + 1) + (a - 1) * c + beta), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - beta),
                (a + 1) - (a - 1) * c + beta, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - beta)
        }
    }
    val a0 = raw[3]
    return doubleArrayOf(raw[0] / a0, raw[1] / a0, raw[2] / a0, raw[4] / a0, raw[5] / a0)
}
