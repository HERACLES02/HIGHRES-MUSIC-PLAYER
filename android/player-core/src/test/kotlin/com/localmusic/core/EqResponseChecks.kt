package com.localmusic.core

import kotlin.math.*

fun eqResponseChecks() {
    var count = 0
    fun verify(ok: Boolean, label: String) { count++; check(ok) { "EQ response: $label" } }
    fun measuredDb(preset: EqPreset, rate: Int, frequency: Double): Double {
        val kernel = DspKernel()
        kernel.configure(rate, 1, DspConfiguration(AudioSettings(eqEnabled = true, preset = preset)))
        var power = 0.0
        val countSamples = rate * 2
        for (i in 0 until countSamples) {
            val output = kernel.sample(.001 * sin(2 * PI * frequency * i / rate), 0)
            if (i >= rate) power += output * output
        }
        return 20 * log10(sqrt(power / rate) / (.001 / sqrt(2.0)))
    }
    verify(EqResponse(EqPreset()).gainDbAt(1000.0) == 0.0, "flat response")
    val boost = EqPreset(filters = listOf(EqFilter(frequencyHz = 1000.0, gainDb = 6.0)))
    verify(abs(EqResponse(boost).gainDbAt(1000.0) - 6.0) < .001, "peaking center is +6 dB")
    verify(abs(EqResponse(boost.copy(preampDb = -6.0)).gainDbAt(1000.0)) < .001, "preamp offsets boost")
    for (rate in listOf(44100, 48000, 96000)) {
        for (type in EqFilterType.entries) {
            val preset = EqPreset(preampDb = -3.0, filters = listOf(EqFilter(type, 2000.0, 7.0, 1.7)))
            val response = EqResponse(preset, rate)
            for (frequency in listOf(200.0, 2000.0, 7000.0)) {
                verify(abs(response.gainDbAt(frequency) - measuredDb(preset, rate, frequency)) < .04,
                    "$type at $frequency Hz / $rate agrees with measured PCM")
            }
        }
    }
    val graphic = EqPreset(preampDb = -4.0, gains = List(10) { if (it in 4..6) 5.0 else 0.0 })
    verify(abs(EqResponse(graphic).gainDbAt(1000.0) - measuredDb(graphic, 48000, 1000.0)) < .04,
        "overlapping graphic bands agree with measured PCM")
    val skipped = EqPreset(filters = listOf(EqFilter(frequencyHz = 16000.0, gainDb = 12.0)))
    verify(EqResponse(skipped, 32000).gainDbAt(1000.0) == 0.0, "filter near Nyquist is bypassed like DSP")
    val curve = EqResponse(boost).curve()
    verify(curve.size == 201 && abs(curve.first().frequencyHz - 20.0) < 1e-6 &&
        abs(curve.last().frequencyHz - 20000.0) < 1e-6, "audible curve uses logarithmic 20 Hz to 20 kHz range")
    verify(curve.zipWithNext().all { (a, b) -> a.frequencyHz < b.frequencyHz }, "curve frequencies increase")
    val singleHeadroom = EqResponse(boost).headroom()
    verify(abs(singleHeadroom.peakFilterGainDb - 6.0) < .001 && singleHeadroom.recommendedPreampDb in -6.6..-6.5,
        "6 dB peak recommends attenuation with spare room")
    verify(EqResponse(boost.copy(preampDb = -12.0)).headroom() == singleHeadroom,
        "recommendation is independent of existing preamp")
    verify(EqResponse(EqPreset()).headroom().recommendedPreampDb == 0.0, "flat EQ needs no attenuation")
    val overlapping = boost.copy(filters = List(2) { boost.filters!!.first() })
    verify(EqResponse(overlapping).headroom().peakFilterGainDb > 11.99, "overlapping filters add to peak")
    val excessive = boost.copy(filters = List(5) { boost.filters!!.first() })
    val excessiveHeadroom = EqResponse(excessive).headroom()
    verify(excessiveHeadroom.recommendedPreampDb == -24.0 && excessiveHeadroom.peakFilterGainDb > 29.99,
        "uncompensable boost is reported while preamp stays in supported range")
    for (type in EqFilterType.entries) {
        val narrow = EqPreset(filters = listOf(EqFilter(type, 2345.67, 20.0, 20.0)))
        val response = EqResponse(narrow)
        verify(response.curve().all { it.gainDb.isFinite() } && response.headroom().peakFilterGainDb.isFinite(),
            "high-Q $type curve and estimate remain finite")
    }
    println("EQ response checks passed: $count assertions (analytical response against real synthetic PCM).")
}
