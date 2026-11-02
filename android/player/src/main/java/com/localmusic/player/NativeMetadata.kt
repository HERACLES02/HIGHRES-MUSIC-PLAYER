package com.localmusic.player

import com.localmusic.core.MusicTags
import org.json.JSONObject

object NativeMetadata {
    fun tags(read: JSONObject, fallback: MusicTags): MusicTags {
        val p = read.getJSONObject("properties")
        fun values(k: String) = p.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        fun value(k: String) = values(k).firstOrNull()
        return MusicTags(value("TITLE") ?: fallback.title, values("ARTIST"), value("ALBUMARTIST"), value("ALBUM"),
            value("TRACKNUMBER")?.substringBefore('/')?.toIntOrNull(), value("DISCNUMBER")?.substringBefore('/')?.toIntOrNull(),
            value("DATE"), value("GENRE"), value("COMMENT"))
    }
}
