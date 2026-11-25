package com.localmusic.core

/**
 * Pure derived live collections for Smart Playlists.
 * Excludes unavailable tracks; requires no Room schema changes.
 */
object SmartPlaylists {
    const val RECENTLY_ADDED_DAYS = 30
    const val HI_RES_MIN_SAMPLE_RATE_HZ = 48000

    fun recentlyAdded(tracks: List<Track>, nowEpochMs: Long): List<Track> {
        val cutoff = nowEpochMs - (RECENTLY_ADDED_DAYS.toLong() * 24L * 60L * 60L * 1000L)
        return tracks.asSequence()
            .filter { it.availability == Availability.AVAILABLE && it.addedAtMs >= cutoff }
            .sortedWith(
                compareByDescending<Track> { it.addedAtMs }
                    .thenBy { it.tags.title }
                    .thenBy { it.id.value }
            )
            .toList()
    }

    fun hiRes(tracks: List<Track>): List<Track> {
        return tracks.asSequence()
            .filter {
                it.availability == Availability.AVAILABLE &&
                    it.audio.sampleRateHz != null &&
                    it.audio.sampleRateHz > HI_RES_MIN_SAMPLE_RATE_HZ
            }
            .sortedWith(
                compareByDescending<Track> { it.audio.sampleRateHz ?: 0 }
                    .thenBy { it.tags.title }
                    .thenBy { it.id.value }
            )
            .toList()
    }

    fun availableGenres(tracks: List<Track>): List<String> {
        return tracks.asSequence()
            .filter { it.availability == Availability.AVAILABLE && it.favorite && !it.tags.genre.isNullOrBlank() }
            .map { it.tags.genre!!.trim() }
            .distinctBy { it.lowercase() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()
    }

    fun genreFavorites(tracks: List<Track>, genre: String): List<Track> {
        val trimmed = genre.trim()
        return tracks.asSequence()
            .filter {
                it.availability == Availability.AVAILABLE &&
                    it.favorite &&
                    it.tags.genre?.trim().equals(trimmed, ignoreCase = true)
            }
            .sortedWith(
                compareBy<Track> { it.tags.title }
                    .thenBy { it.id.value }
            )
            .toList()
    }
}
