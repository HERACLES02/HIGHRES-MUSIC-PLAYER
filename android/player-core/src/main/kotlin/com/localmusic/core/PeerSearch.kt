package com.localmusic.core

import java.math.BigDecimal
import java.util.Locale

enum class PeerAudioFormat { FLAC, MP3 }
const val UNKNOWN_PEER_QUALITY = "Unknown"

data class PeerSearchTrack(
    val user: String,
    val path: String,
    val size: Long,
    val bitrate: Long? = null,
    val sampleRate: Long? = null,
    val bitDepth: Long? = null,
) {
    val name: String get() = path.substringAfterLast('\\').substringAfterLast('/')
    val folder: String get() = path.substring(0, maxOf(path.lastIndexOf('\\'), path.lastIndexOf('/'), 0))
    val format: PeerAudioFormat? get() = PeerAudioFormat.entries.firstOrNull {
        name.substringAfterLast('.', "").equals(it.name, ignoreCase = true)
    }
    val quality: String? get() = when (format) {
        PeerAudioFormat.FLAC -> listOfNotNull(
            bitDepth?.let { "$it-bit" },
            sampleRate?.let { "${BigDecimal.valueOf(it, 3).stripTrailingZeros().toPlainString()} kHz" },
        ).takeIf { it.isNotEmpty() }?.joinToString(" / ")
        PeerAudioFormat.MP3 -> bitrate?.let { "$it kbps" }
        null -> null
    }
    val sizeLabel: String get() = String.format(Locale.getDefault(), "%.1f MB", size / 1_000_000.0)

    internal val qualityDetails: Int get() = when (format) {
        PeerAudioFormat.FLAC -> listOfNotNull(sampleRate, bitDepth).size
        PeerAudioFormat.MP3 -> if (bitrate == null) 0 else 1
        null -> 0
    }
}

fun collectPeerSearchTracks(tracks: List<PeerSearchTrack>): List<PeerSearchTrack> {
    val unique = linkedMapOf<Triple<String, String, Long>, PeerSearchTrack>()
    for (track in tracks) {
        val key = Triple(track.user, track.path, track.size)
        val previous = unique[key]
        if (previous == null || track.qualityDetails > previous.qualityDetails) unique[key] = track
    }
    return unique.values.toList()
}

enum class PeerSearchSort { NAME, SIZE_ASC, SIZE_DESC }

data class PeerSearchOptions(
    val format: PeerAudioFormat? = null,
    val quality: String? = null,
    val includeUnknown: Boolean = false,
    val minMb: String = "",
    val maxMb: String = "",
    val nameContains: String = "",
    val sort: PeerSearchSort = PeerSearchSort.NAME,
) {
    private val minimumBytes = parseMb(minMb)
    private val maximumBytes = parseMb(maxMb)
    val invalidSizeRange: Boolean get() = (minMb.isNotBlank() && minimumBytes == null) ||
        (maxMb.isNotBlank() && maximumBytes == null) ||
        (minimumBytes != null && maximumBytes != null && minimumBytes > maximumBytes)

    fun matching(collected: List<PeerSearchTrack>): List<PeerSearchTrack> {
        if (invalidSizeRange) return emptyList()
        val words = nameContains.split(' ').filter { it.isNotBlank() }
        val byName = compareBy<PeerSearchTrack, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val order = when (sort) {
            PeerSearchSort.NAME -> byName
            PeerSearchSort.SIZE_ASC -> compareBy(PeerSearchTrack::size).then(byName)
            PeerSearchSort.SIZE_DESC -> compareByDescending(PeerSearchTrack::size).then(byName)
        }
        return collected.filter { track ->
            (format == null || track.format == format) &&
                words.all { track.name.contains(it, ignoreCase = true) } &&
                (minimumBytes == null || BigDecimal.valueOf(track.size) >= minimumBytes) &&
                (maximumBytes == null || BigDecimal.valueOf(track.size) <= maximumBytes) &&
                matchesQuality(track)
        }.sortedWith(order)
    }

    private fun matchesQuality(track: PeerSearchTrack): Boolean {
        val requested = quality ?: return true
        val reported = track.quality ?: return includeUnknown || requested == UNKNOWN_PEER_QUALITY
        return reported == requested
    }

    private fun parseMb(text: String) = text.trim().replace(',', '.')
        .takeIf { it.matches(Regex("[0-9]{1,13}(\\.[0-9]{1,6})?")) }
        ?.toBigDecimalOrNull()?.movePointRight(6)
}
