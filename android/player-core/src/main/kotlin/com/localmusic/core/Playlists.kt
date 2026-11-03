package com.localmusic.core

/**
 * Pure saved-playlist ordering rules shared by storage and UI.
 * Entries are addressed by occurrence identity, never by track identity or position,
 * so repeated songs stay independently editable and unaffected operations keep their IDs.
 */
object Playlists {
    const val MAX_NAME_CHARS = 200
    const val MAX_ENTRIES = 5000

    /** Trims and validates a playlist name; callers persist the trimmed result. */
    fun requireName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "The playlist name cannot be empty." }
        require(trimmed.length <= MAX_NAME_CHARS) { "Playlist names are limited to $MAX_NAME_CHARS characters." }
        return trimmed
    }

    /** One playlist's entries in playback order, with identities validated and ranks normalized 0..n-1. */
    fun normalized(entries: List<PlaylistEntry>): List<PlaylistEntry> {
        require(entries.map { it.id }.toSet().size == entries.size) { "Playlist occurrences must have unique identities." }
        require(entries.asSequence().map { it.playlistId }.distinct().count() <= 1) { "Playlist occurrences must belong to one playlist." }
        return entries.sortedBy { it.rank }.mapIndexed { index, entry -> entry.copy(rank = index.toLong()) }
    }

    /** New occurrences append at the end and get their own identities; existing occurrences are untouched. */
    fun append(playlistId: PlaylistId, entries: List<PlaylistEntry>, trackIds: List<TrackId>): List<PlaylistEntry> {
        require(trackIds.isNotEmpty()) { "Choose at least one song." }
        val ordered = normalized(entries)
        require(ordered.size + trackIds.size <= MAX_ENTRIES) { "Playlists are limited to $MAX_ENTRIES entries." }
        val additions = trackIds.map { PlaylistEntry(EntryId.new(), playlistId, it, ordered.size.toLong()) }
        return normalized(ordered + additions)
    }

    /** Removes exactly one occurrence; a matching duplicate of the same song remains. */
    fun remove(entries: List<PlaylistEntry>, occurrence: EntryId): List<PlaylistEntry> {
        require(entries.any { it.id == occurrence }) { "That playlist entry no longer exists." }
        return normalized(entries.filterNot { it.id == occurrence })
    }

    /** Moves exactly one occurrence to its new position without reordering the rest. */
    fun move(entries: List<PlaylistEntry>, occurrence: EntryId, index: Int): List<PlaylistEntry> {
        val ordered = normalized(entries)
        require(index in ordered.indices) { "That playlist entry changed. Reload the list and try again." }
        val from = ordered.indexOfFirst { it.id == occurrence }
        require(from >= 0) { "That playlist entry no longer exists." }
        val target = ordered[from]
        val others = ordered.filterNot { it.id == occurrence }
        return (others.take(index) + target + others.drop(index))
            .mapIndexed { newIndex, entry -> entry.copy(rank = newIndex.toLong()) }
    }
}
