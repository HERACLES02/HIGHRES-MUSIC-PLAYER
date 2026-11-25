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

    // 5. Device count evaluation
    verify(UsbRoutePolicy.evaluateDevices(emptyList<String>()) is UsbDeviceEvaluation.NoDevice, "0 USB devices -> NoDevice")
    val single = UsbRoutePolicy.evaluateDevices(listOf("DAC1"))
    verify(single is UsbDeviceEvaluation.Single && single.device == "DAC1", "1 USB device -> Single")
    val multiple = UsbRoutePolicy.evaluateDevices(listOf("DAC1", "DAC2"))
    verify(multiple is UsbDeviceEvaluation.Multiple, "2 USB devices -> Multiple")

    println("USB route policy checks passed: $count assertions.")
}
