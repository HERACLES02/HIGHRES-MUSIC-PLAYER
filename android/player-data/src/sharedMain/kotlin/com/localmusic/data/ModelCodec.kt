package com.localmusic.data

import com.localmusic.core.*
import kotlinx.serialization.json.*

/** Versioned metadata payloads avoid losing ordered artist credits while the library evolves. */
object ModelCodec {
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.optional(key: String) = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
    fun tags(value: MusicTags) = buildJsonObject {
        put("version", 1); put("title", value.title); put("artists", JsonArray(value.artists.map(::JsonPrimitive)))
        put("albumArtist", value.albumArtist); put("album", value.album); put("trackNumber", value.trackNumber)
        put("discNumber", value.discNumber); put("date", value.date); put("genre", value.genre); put("comment", value.comment)
    }.toString()
    fun tags(text: String): MusicTags {
        val o = Json.parseToJsonElement(text).jsonObject; require(o.getValue("version").jsonPrimitive.int == 1)
        return MusicTags(o.str("title"), o.getValue("artists").jsonArray.map { it.jsonPrimitive.content },
            o.optional("albumArtist"), o.optional("album"), o.optional("trackNumber")?.toInt(),
            o.optional("discNumber")?.toInt(), o.optional("date"), o.optional("genre"), o.optional("comment"))
    }
    fun audio(value: AudioProperties) = buildJsonObject {
        put("version", 1); put("durationMs", value.durationMs); put("mimeType", value.mimeType)
        put("sampleRateHz", value.sampleRateHz); put("channels", value.channels); put("meaningfulBits", value.meaningfulBits)
    }.toString()
    fun audio(text: String): AudioProperties {
        val o = Json.parseToJsonElement(text).jsonObject; require(o.getValue("version").jsonPrimitive.int == 1)
        return AudioProperties(o.getValue("durationMs").jsonPrimitive.long, o.str("mimeType"),
            o.optional("sampleRateHz")?.toInt(), o.optional("channels")?.toInt(), o.optional("meaningfulBits")?.toInt())
    }
}
