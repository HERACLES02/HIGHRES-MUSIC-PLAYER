package com.localmusic.core

data class RouteFormatCandidate(
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: PcmTargetEncoding,
    val isDefaultBehavior: Boolean = true
)

sealed interface RouteSelectionResult {
    data class Selected(val candidate: RouteFormatCandidate) : RouteSelectionResult
    data class Fallback(val reason: String) : RouteSelectionResult
}

object UsbRoutePolicy {
    /**
     * Selects exact-rate stereo format with default mixer behavior.
     * Ranking: Float -> 24-bit packed -> 32-bit integer -> 16-bit (only if source <= 16-bit).
     */
    fun selectPreferredFormat(
        candidates: List<RouteFormatCandidate>,
        targetRateHz: Int,
        sourceBitDepth: Int?
    ): RouteSelectionResult {
        if (candidates.isEmpty()) {
            return RouteSelectionResult.Fallback("DAC reported no supported mixer formats")
        }

        val exactRate = candidates.filter { it.sampleRateHz == targetRateHz }
        if (exactRate.isEmpty()) {
            return RouteSelectionResult.Fallback("No exact-rate format for $targetRateHz Hz")
        }

        val stereoDefault = exactRate.filter { it.channelCount == 2 && it.isDefaultBehavior }
        if (stereoDefault.isEmpty()) {
            return RouteSelectionResult.Fallback("No stereo candidate with default mixer behavior at $targetRateHz Hz")
        }

        val isHighResSource = sourceBitDepth != null && sourceBitDepth > 16
        val eligible = stereoDefault.filter { candidate ->
            if (candidate.encoding == PcmTargetEncoding.PCM_16BIT && isHighResSource) false else true
        }

        if (eligible.isEmpty()) {
            return RouteSelectionResult.Fallback("High-resolution source (${sourceBitDepth}-bit) rejected 16-bit mixer candidate")
        }

        // Rank: Float -> 24-bit packed -> 32-bit -> 16-bit
        val best = eligible.minByOrNull {
            when (it.encoding) {
                PcmTargetEncoding.PCM_FLOAT -> 0
                PcmTargetEncoding.PCM_24BIT_PACKED -> 1
                PcmTargetEncoding.PCM_32BIT -> 2
                PcmTargetEncoding.PCM_16BIT -> 3
            }
        } ?: return RouteSelectionResult.Fallback("No eligible format found")

        return RouteSelectionResult.Selected(best)
    }

    fun <T> evaluateDevices(usbDevices: List<T>): UsbDeviceEvaluation<T> {
        return when (usbDevices.size) {
            0 -> UsbDeviceEvaluation.NoDevice("No USB DAC connected")
            1 -> UsbDeviceEvaluation.Single(usbDevices[0])
            else -> UsbDeviceEvaluation.Multiple("Multiple USB DACs connected (${usbDevices.size})")
        }
    }
}

sealed interface UsbDeviceEvaluation<out T> {
    data class Single<T>(val device: T) : UsbDeviceEvaluation<T>
    data class NoDevice(val reason: String) : UsbDeviceEvaluation<Nothing>
    data class Multiple(val reason: String) : UsbDeviceEvaluation<Nothing>
}
