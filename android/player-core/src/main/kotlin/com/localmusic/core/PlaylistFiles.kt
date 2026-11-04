package com.localmusic.core

enum class PlaylistFileFormat(val extension: String) { M3U("m3u"), M3U8("m3u8"), PLS("pls") }
data class PlaylistFileSong(val id: TrackId, val locations: List<String>)
data class PlaylistFileMatch(val reference: String, val id: TrackId?, val reason: String)
data class PlaylistFileEntry(val location: String, val title: String, val durationMs: Long)

object PlaylistFiles {
    const val MAX_BYTES = 2_000_000
    fun parse(text: String, format: PlaylistFileFormat): List<String> {
        require(text.length <= MAX_BYTES && '\u0000' !in text) { "Playlist is too large or contains binary data." }
        val lines = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.toList()
        require(lines.none { it.length > 8192 }) { "A playlist line is too long." }
        val entries = if (format != PlaylistFileFormat.PLS) {
            require(lines.none { it.startsWith("#EXT-X-", true) }) { "Streaming/HLS playlists are not supported." }
            lines.filter { it.isNotEmpty() && !it.startsWith('#') }
        } else {
            require(lines.firstOrNull { it.isNotEmpty() }?.equals("[playlist]", true) == true) { "Expected a PLS [playlist] header." }
            val files = sortedMapOf<Int, String>()
            var declared: Int? = null
            for (line in lines.dropWhile { !it.equals("[playlist]", true) }.drop(1)) {
                if (line.isEmpty() || line.startsWith(';') || line.startsWith('#')) continue
                require(!line.startsWith('[')) { "Unexpected PLS section." }
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=', "").trim()
                if (key.equals("NumberOfEntries", true)) {
                    require(declared == null) { "Duplicate PLS entry count." }
                    declared = value.toIntOrNull() ?: throw IllegalArgumentException("Invalid PLS entry count.")
                } else if (key.startsWith("File", true)) {
                    val index = key.substring(4).toIntOrNull() ?: throw IllegalArgumentException("Invalid PLS file number.")
                    require(index in 1..Playlists.MAX_ENTRIES && value.isNotEmpty() && !files.containsKey(index)) { "Invalid or duplicate PLS entry." }
                    files[index] = value
                }
            }
            require(files.keys.toList() == (1..files.size).toList()) { "PLS file numbers must be consecutive." }
            require(declared == null || declared == files.size) { "PLS entry count does not match its files." }
            files.values.toList()
        }
        require(entries.size <= Playlists.MAX_ENTRIES) { "Playlists are limited to ${Playlists.MAX_ENTRIES} entries." }
        return entries
    }

    private fun path(reference: String): String? {
        val raw = if (reference.startsWith("file:", true)) {
            val uri = runCatching { java.net.URI(reference) }.getOrNull() ?: return null
            if (!uri.authority.isNullOrEmpty() && uri.authority != "localhost") return null
            uri.path ?: return null
        } else reference.replace('\\', '/')
        val parts = raw.split('/')
        if (parts.any { it == ".." }) return null
        return parts.filter { it.isNotEmpty() && it != "." }.joinToString("/").let { if (raw.startsWith('/')) "/$it" else it }
    }

    fun match(entries: List<String>, songs: List<PlaylistFileSong>, playlistLocation: String? = null): List<PlaylistFileMatch> {
        val exact = mutableMapOf<String, MutableSet<TrackId>>()
        val suffix = mutableMapOf<String, MutableSet<TrackId>>()
        for (song in songs) for (location in song.locations) {
            exact.getOrPut(location) { mutableSetOf() }.add(song.id)
            if (location.startsWith("content:", true)) continue
            val normalized = path(location) ?: continue
            exact.getOrPut(normalized) { mutableSetOf() }.add(song.id)
            val segments = normalized.trimStart('/').split('/')
            for (i in segments.indices) suffix.getOrPut(segments.drop(i).joinToString("/")) { mutableSetOf() }.add(song.id)
        }
        return entries.map { reference ->
            val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").find(reference)?.value?.lowercase()
            if (scheme != null && scheme !in setOf("file:", "content:") && !Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(reference)) {
                PlaylistFileMatch(reference, null, "Unsupported link")
            } else {
                val normalized = path(reference)
                val relative = scheme == null && !reference.replace('\\', '/').startsWith('/')
                val base = playlistLocation?.takeIf { it.startsWith('/') }
                val candidates = if (relative && base != null) {
                    val segments = mutableListOf<String>()
                    for (part in (base.substringBeforeLast('/') + "/" + reference.replace('\\', '/')).split('/')) {
                        when (part) {
                            "", "." -> Unit
                            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                            else -> segments.add(part)
                        }
                    }
                    exact["/" + segments.joinToString("/")]
                } else exact[reference] ?: normalized?.let { exact[it] }
                    ?: if (relative && normalized != null) suffix[normalized] else null
                PlaylistFileMatch(reference, candidates?.singleOrNull(), when {
                    candidates.isNullOrEmpty() -> "Missing"
                    candidates.size > 1 -> "Ambiguous"
                    else -> "Matched"
                })
            }
        }
    }

    fun encode(entries: List<PlaylistFileEntry>, format: PlaylistFileFormat): String {
        require(entries.size <= Playlists.MAX_ENTRIES)
        for (entry in entries) require(entry.location.isNotBlank() && entry.location.length <= 8192 &&
            entry.location.none { it == '\r' || it == '\n' || it == '\u0000' } && !entry.location.startsWith('#')) { "A song location cannot be represented safely." }
        fun title(entry: PlaylistFileEntry) = entry.title.replace('\r', ' ').replace('\n', ' ').replace('\u0000', ' ').take(1000)
        val result = buildString {
            if (format == PlaylistFileFormat.PLS) {
                append("[playlist]\nNumberOfEntries=${entries.size}\n")
                entries.forEachIndexed { index, entry ->
                    val n = index + 1
                    append("File$n=${entry.location}\nTitle$n=${title(entry)}\nLength$n=${entry.durationMs / 1000}\n")
                }
                append("Version=2\n")
            } else {
                append("#EXTM3U\n")
                for (entry in entries) append("#EXTINF:${entry.durationMs / 1000},${title(entry)}\n${entry.location}\n")
            }
        }
        require(result.lineSequence().none { it.length > 8192 }) { "An exported playlist line exceeds the supported limit." }
        require(result.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Export exceeds the 2 MB playlist limit." }
        return result
    }
}
