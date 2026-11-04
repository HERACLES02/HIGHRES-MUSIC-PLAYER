package com.localmusic.core

data class PlaybackPosition(val label: String, val number: Int?, val total: Int?)

object PlaybackPresentation {
    fun position(snapshot: QueueSnapshot): PlaybackPosition {
        val current = snapshot.history.getOrNull(snapshot.historyCursor) ?: return PlaybackPosition("Listening collection", null, null)
        val index = snapshot.collection.indexOfFirst { it.id == current.id }
        if (index >= 0) return PlaybackPosition(
            (snapshot.origin?.takeIf { it.isNotBlank() } ?: "Listening collection") + if (snapshot.shuffle) " · collection order" else "",
            index + 1, snapshot.collection.size)
        val collectionIds = snapshot.collection.map { it.id }.toSet()
        val manual = (snapshot.history.filter { it.id !in collectionIds } + snapshot.manual).distinctBy { it.id }
        val manualIndex = manual.indexOfFirst { it.id == current.id }
        return if (manualIndex >= 0) PlaybackPosition("Queue", manualIndex + 1, manual.size)
            else PlaybackPosition("Queue", null, null)
    }

    fun quality(audio: AudioProperties): String {
        val codec = when (audio.mimeType.lowercase()) {
            "audio/flac", "audio/x-flac" -> "FLAC"
            "audio/mpeg" -> "MP3"
            "audio/alac", "audio/x-alac" -> "ALAC"
            "audio/opus" -> "Opus"
            "audio/ogg", "application/ogg" -> "Ogg"
            "audio/wav", "audio/x-wav", "audio/wave" -> "WAV"
            "audio/mp4", "audio/mp4a-latm" -> "MP4 audio"
            "audio/aac" -> "AAC"
            else -> "Audio"
        }
        val bits = audio.meaningfulBits?.takeIf { it > 0 }?.let { "$it-bit" }
        val rate = audio.sampleRateHz?.takeIf { it > 0 }?.let {
            java.math.BigDecimal(it).divide(java.math.BigDecimal(1000)).stripTrailingZeros().toPlainString() + " kHz"
        }
        return codec + " · " + listOfNotNull(bits, rate).takeIf { it.isNotEmpty() }?.joinToString(" / ").orEmpty().ifEmpty { "details unknown" }
    }
}
