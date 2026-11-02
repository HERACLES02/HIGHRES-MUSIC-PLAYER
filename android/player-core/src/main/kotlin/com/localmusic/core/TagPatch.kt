package com.localmusic.core

/** Absent fields stay untouched. An included empty string explicitly clears that field. */
data class TagPatch(val fields: Map<String, String>) {
    init {
        require(fields.keys.all { it in KEYS })
        require(fields.values.all { it.length <= 64000 && '\u0000' !in it })
        require(fields.filterKeys { it != "ARTIST" && it != "COMMENT" }.values.none { '\n' in it || '\r' in it }) { "Only artists and comments can contain line breaks." }
        for (key in listOf("TRACKNUMBER", "DISCNUMBER")) fields[key]?.takeIf { it.isNotEmpty() }?.let { require(it.toIntOrNull()?.let { n -> n in 1..99999 } == true) { "$key must be a positive number" } }
    }
    fun valuesFor(key: String): List<String> {
        val value = fields[key].orEmpty()
        if (value.isEmpty()) return emptyList()
        return if (key == "ARTIST") value.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { it.trim() }.filter { it.isNotEmpty() } else listOf(value)
    }
    fun apply(old: MusicTags): MusicTags {
        fun optional(key: String, value: String?) = if (key in fields) fields[key]?.takeIf { it.isNotEmpty() } else value
        return old.copy(title = fields["TITLE"] ?: old.title,
            artists = if ("ARTIST" in fields) valuesFor("ARTIST") else old.artists,
            albumArtist = optional("ALBUMARTIST", old.albumArtist), album = optional("ALBUM", old.album),
            trackNumber = if ("TRACKNUMBER" in fields) fields["TRACKNUMBER"]?.toIntOrNull() else old.trackNumber,
            discNumber = if ("DISCNUMBER" in fields) fields["DISCNUMBER"]?.toIntOrNull() else old.discNumber,
            date = optional("DATE", old.date), genre = optional("GENRE", old.genre), comment = optional("COMMENT", old.comment))
    }
    companion object { val KEYS = listOf("TITLE", "ARTIST", "ALBUMARTIST", "ALBUM", "TRACKNUMBER", "DISCNUMBER", "DATE", "GENRE", "COMMENT") }
}
