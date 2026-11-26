package com.localmusic.core

/** What the Normal-mode route reports about the current song's output. Never a measured DAC or HAL rate. */
enum class OutputRoute { ANDROID_MIXER, ANDROID_ACCEPTED, RATE_UNSUPPORTED }
enum class OutputDeviceKind(val label: String) { USB("USB"), WIRED("Wired"), BLUETOOTH("Bluetooth"), SPEAKER("Speaker") }
data class OutputFacts(val route: OutputRoute, val acceptedRateHz: Int = 0, val acceptedEncoding: PcmTargetEncoding? = null,
                       val device: OutputDeviceKind? = null)
data class RememberedOutput(val trackId: String, val facts: OutputFacts)

/** The one-line Now Playing badge: the file's format, then what Android did with the sound. */
object SignalBadge {
    private val lossy = setOf("MP3", "Opus", "Ogg", "AAC")

    fun source(audio: AudioProperties): String {
        val codec = PlaybackPresentation.codec(audio)
        val rate = audio.sampleRateHz?.takeIf { it > 0 }?.let(PlaybackPresentation::khz)
        val bits = audio.meaningfulBits?.takeIf { it > 0 && codec !in lossy && rate != null }
        val detail = if (bits != null) "$bits/$rate" else rate
        return listOfNotNull(codec, detail).joinToString(" ")
    }

    fun text(audio: AudioProperties, output: OutputFacts?, direct: Boolean): String {
        if (direct || PlaybackPresentation.codec(audio) == "Audio") return PlaybackPresentation.quality(audio)
        output ?: return source(audio)
        val end = when (output.route) {
            OutputRoute.RATE_UNSUPPORTED -> return "${source(audio)} → Android mixer · rate unsupported"
            OutputRoute.ANDROID_ACCEPTED -> if (output.acceptedRateHz > 0) "Android ${PlaybackPresentation.khz(output.acceptedRateHz)} kHz" +
                (output.acceptedEncoding?.let { " " + encoding(it) } ?: "") else "Android mixer"
            OutputRoute.ANDROID_MIXER -> "Android mixer"
        }
        return "${source(audio)} → $end" + (output.device?.let { " · ${it.label}" } ?: "")
    }

    /** Android reports no routed device while paused (and briefly on resume), so the same song keeps its last known
     *  output end and device; a new song shows the source side only until it actually plays. */
    fun remember(previous: RememberedOutput?, trackId: String, playing: Boolean, observed: OutputFacts?): RememberedOutput? = when {
        playing && observed != null -> RememberedOutput(trackId,
            if (observed.device == null && previous?.trackId == trackId) observed.copy(device = previous.facts.device) else observed)
        previous?.trackId == trackId -> previous
        else -> null
    }

    private fun encoding(value: PcmTargetEncoding) = when (value) {
        PcmTargetEncoding.PCM_FLOAT -> "float"
        PcmTargetEncoding.PCM_32BIT -> "32-bit"
        PcmTargetEncoding.PCM_24BIT_PACKED -> "24-bit"
        PcmTargetEncoding.PCM_16BIT -> "16-bit"
    }
}
