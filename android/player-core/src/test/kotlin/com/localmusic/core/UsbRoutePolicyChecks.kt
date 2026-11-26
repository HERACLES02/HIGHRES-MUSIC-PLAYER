package com.localmusic.core

fun usbRoutePolicyChecks() {
    var count = 0
    fun verify(ok: Boolean, msg: String) { count++; check(ok) { "USB Route Policy: $msg" } }

    val rate = 96000

    // 1. Format ranking: Float > 24-bit packed > 32-bit > 16-bit
    val allFour = listOf(
        RouteFormatCandidate(rate, 2, PcmTargetEncoding.PCM_16BIT),
        RouteFormatCandidate(rate, 2, PcmTargetEncoding.PCM_32BIT),
        RouteFormatCandidate(rate, 2, PcmTargetEncoding.PCM_24BIT_PACKED),
        RouteFormatCandidate(rate, 2, PcmTargetEncoding.PCM_FLOAT)
    )
    val resFloat = UsbRoutePolicy.selectPreferredFormat(allFour, rate, sourceBitDepth = 24)
    verify(resFloat is RouteSelectionResult.Selected && resFloat.candidate.encoding == PcmTargetEncoding.PCM_FLOAT,
        "Float is ranked first when advertised")

    val threeInt = listOf(
        RouteFormatCandidate(rate, 2, PcmTargetEncoding.PCM_16BIT),
        RouteFormatCandidate(rate, 2, PcmTargetEncoding.PCM_32BIT),
        RouteFormatCandidate(rate, 2, PcmTargetEncoding.PCM_24BIT_PACKED)
    )
    val res24 = UsbRoutePolicy.selectPreferredFormat(threeInt, rate, sourceBitDepth = 24)
    verify(res24 is RouteSelectionResult.Selected && res24.candidate.encoding == PcmTargetEncoding.PCM_24BIT_PACKED,
        "Packed 24-bit is ranked second when Float is unavailable")

    val twoInt = listOf(
        RouteFormatCandidate(rate, 2, PcmTargetEncoding.PCM_16BIT),
        RouteFormatCandidate(rate, 2, PcmTargetEncoding.PCM_32BIT)
    )
    val res32 = UsbRoutePolicy.selectPreferredFormat(twoInt, rate, sourceBitDepth = 24)
    verify(res32 is RouteSelectionResult.Selected && res32.candidate.encoding == PcmTargetEncoding.PCM_32BIT,
        "32-bit integer is ranked third when Float and 24-bit are unavailable")

    // 2. 16-bit restriction: allowed only for <= 16-bit source
    val only16 = listOf(RouteFormatCandidate(rate, 2, PcmTargetEncoding.PCM_16BIT))
    val res16For16Source = UsbRoutePolicy.selectPreferredFormat(only16, rate, sourceBitDepth = 16)
    verify(res16For16Source is RouteSelectionResult.Selected && res16For16Source.candidate.encoding == PcmTargetEncoding.PCM_16BIT,
        "16-bit mixer format accepted for 16-bit source")

    val res16For24Source = UsbRoutePolicy.selectPreferredFormat(only16, rate, sourceBitDepth = 24)
    verify(res16For24Source is RouteSelectionResult.Fallback && res16For24Source.reason.contains("rejected 16-bit"),
        "16-bit mixer format rejected for 24-bit high-res source")

    // 3. Exact rate match required
    val dac44And48 = listOf(
        RouteFormatCandidate(44100, 2, PcmTargetEncoding.PCM_FLOAT),
        RouteFormatCandidate(48000, 2, PcmTargetEncoding.PCM_FLOAT)
    )
    val res88 = UsbRoutePolicy.selectPreferredFormat(dac44And48, 88200, sourceBitDepth = 24)
    verify(res88 is RouteSelectionResult.Fallback && res88.reason.contains("88200"),
        "Unsupported 88.2 kHz rate rejected with clear reason")

    // 4. Stereo requirement and default mixer behavior
    val nonStereoOrDefault = listOf(
        RouteFormatCandidate(rate, 6, PcmTargetEncoding.PCM_FLOAT), // 5.1
        RouteFormatCandidate(rate, 2, PcmTargetEncoding.PCM_FLOAT, isDefaultBehavior = false) // bit-perfect mode
    )
    val resRejectNonDefault = UsbRoutePolicy.selectPreferredFormat(nonStereoOrDefault, rate, sourceBitDepth = 24)
    verify(resRejectNonDefault is RouteSelectionResult.Fallback,
        "Multi-channel or non-default mixer behavior rejected")

    // 4b. Only a missing exact rate counts as the DAC refusing the rate (drives the "rate unsupported" badge)
    verify(res88 is RouteSelectionResult.Fallback && res88.rateRefused, "Android 14 exact-rate miss is a rate refusal")
    verify(res16For24Source is RouteSelectionResult.Fallback && !res16For24Source.rateRefused, "16-bit rejection is not a rate refusal")
    val resNoFormats = UsbRoutePolicy.selectPreferredFormat(emptyList(), rate, sourceBitDepth = 24)
    verify(resNoFormats is RouteSelectionResult.Fallback && !resNoFormats.rateRefused, "No reported formats is not a rate refusal")
    verify(resRejectNonDefault is RouteSelectionResult.Fallback && !resRejectNonDefault.rateRefused,
        "Non-stereo/non-default rejection is not a rate refusal")

    // 4c. Android 11–13 legacy route
    var platformAsked = false
    for (low in listOf(44100, 48000)) {
        val res = UsbRoutePolicy.selectLegacyRoute(low, { platformAsked = true; true }, { platformAsked = true; true })
        verify(res is RouteSelectionResult.Fallback && !res.rateRefused && res.reason.contains("<= 48 kHz"),
            "Legacy $low Hz stays on the normal route and is not a rate refusal")
    }
    verify(!platformAsked, "Legacy <= 48 kHz never queries the platform")
    val legacy192 = UsbRoutePolicy.selectLegacyRoute(192000, { false }, { true })
    verify(legacy192 is RouteSelectionResult.Fallback && legacy192.rateRefused, "Legacy rate not listed by the DAC is a rate refusal")
    val legacyNoDirect = UsbRoutePolicy.selectLegacyRoute(96000, { true }, { false })
    verify(legacyNoDirect is RouteSelectionResult.Fallback && !legacyNoDirect.rateRefused,
        "Legacy direct playback unsupported is not a rate refusal")
    val legacy96 = UsbRoutePolicy.selectLegacyRoute(96000, { true }, { true })
    verify(legacy96 == RouteSelectionResult.Selected(RouteFormatCandidate(96000, 2, PcmTargetEncoding.PCM_FLOAT)),
        "Legacy 96 kHz listed with direct playback selects 96 kHz float")

    // 5. Device count evaluation
    verify(UsbRoutePolicy.evaluateDevices(emptyList<String>()) is UsbDeviceEvaluation.NoDevice, "0 USB devices -> NoDevice")
    val single = UsbRoutePolicy.evaluateDevices(listOf("DAC1"))
    verify(single is UsbDeviceEvaluation.Single && single.device == "DAC1", "1 USB device -> Single")
    val multiple = UsbRoutePolicy.evaluateDevices(listOf("DAC1", "DAC2"))
    verify(multiple is UsbDeviceEvaluation.Multiple, "2 USB devices -> Multiple")

    println("USB route policy checks passed: $count assertions.")
}
