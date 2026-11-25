package com.localmusic.core

enum class BrowseCategory { SONGS, ALBUMS, ARTISTS, FOLDERS }
enum class BrowseOrder { TITLE, NEWEST }
data class BrowseGroup(val key: String, val tracks: List<Track>, val availableCount: Int)

/** Library search, sort and grouping. Build once per library load; search keys are precomputed here. */
class LibraryBrowse(val tracks: List<Track>, private val folderOf: (TrackId) -> String?) {
    private class Entry(val track: Track, val key: String, val title: String, val id: String)
    private val entries = tracks.map { Entry(it, fold("${it.tags.title} ${artist(it)} ${it.tags.album.orEmpty()}"),
        it.tags.title.lowercase(), it.id.value.toString()) }

    fun search(query: String, order: BrowseOrder): List<Track> {
        val needle = fold(query)
        val found = if (needle.isEmpty()) entries else entries.filter { it.key.contains(needle) }
        val sorted = when (order) {
            BrowseOrder.NEWEST -> found.sortedByDescending { it.track.addedAtMs }
            BrowseOrder.TITLE -> found.sortedWith(compareBy<Entry> { it.title }.thenBy { it.id })
        }
        return sorted.map { it.track }
    }

    fun groupKey(track: Track, category: BrowseCategory): String = when (category) {
        BrowseCategory.ALBUMS -> albumKey(track)
        BrowseCategory.ARTISTS -> artist(track)
        BrowseCategory.FOLDERS -> folderOf(track.id) ?: "Folder information needs a rescan"
        BrowseCategory.SONGS -> ""
    }

    /** Groups sorted by key. Folder groups play by track number, then source; others keep [found]'s order. */
    fun groups(found: List<Track>, category: BrowseCategory): List<BrowseGroup> {
        if (category == BrowseCategory.SONGS) return emptyList()
        return found.groupBy { groupKey(it, category) }.toSortedMap().map { (key, members) ->
            val ordered = if (category == BrowseCategory.FOLDERS) members.sortedWith(
                compareBy<Track> { it.tags.trackNumber ?: Int.MAX_VALUE }.thenBy { it.sources.firstOrNull()?.uri ?: "" }) else members
            BrowseGroup(key, ordered, ordered.count { it.availability == Availability.AVAILABLE })
        }
    }

    companion object {
        fun artist(track: Track): String = track.tags.artists.joinToString(", ").ifBlank { "Unknown artist" }
        fun albumKey(track: Track): String = "${track.tags.album ?: "Unknown album"} · ${track.tags.albumArtist ?: artist(track)}"

        // Per-letter fold that matches String.contains(ignoreCase = true), e.g. Greek final sigma.
        private fun fold(text: String): String = buildString(text.length) { text.forEach { append(it.uppercaseChar().lowercaseChar()) } }
    }
}
