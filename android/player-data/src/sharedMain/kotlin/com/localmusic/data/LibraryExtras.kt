package com.localmusic.data

import com.localmusic.core.MusicTags
import kotlinx.serialization.json.*

/** Source tags stay separate from app-only edits and disposable artwork caches. */
data class LibraryExtras(val folders: Map<String, String> = emptyMap(), val canonical: Map<String, String> = emptyMap(),
    val artworkPath: String? = null, val artworkStamp: String? = null, val artworkOverride: String? = null,
    val tagsOverride: MusicTags? = null, val sidecarUri: String? = null,
    val sidecarVersion: String? = null, val embeddedVersion: String? = null, val tagFields: Map<String, String> = emptyMap(),
    val overridesVersion:Long=0,val lyricsAdjustmentMs:Long?=null,val replayGainTags:Map<String,String> = emptyMap(),val replayGainScanned:Boolean=false,
    val artworkCredit:String?=null,val artworkSourceUrl:String?=null,val hidden:Boolean=false) {
    init { require(lyricsAdjustmentMs == null || lyricsAdjustmentMs in -86_400_000L..86_400_000L) }
    fun encode(): String = buildJsonObject {
        put("version", 1)
        put("folders", JsonObject(folders.mapValues { JsonPrimitive(it.value) }))
        put("canonical", JsonObject(canonical.mapValues { JsonPrimitive(it.value) }))
        put("artworkPath", artworkPath); put("artworkStamp", artworkStamp); put("artworkOverride", artworkOverride)
        put("tagsOverride", tagsOverride?.let { Json.parseToJsonElement(ModelCodec.tags(it)) } ?: JsonNull)
        put("sidecarUri", sidecarUri)
        put("sidecarVersion", sidecarVersion); put("embeddedVersion", embeddedVersion)
        put("tagFields", JsonObject(tagFields.mapValues { JsonPrimitive(it.value) }))
        put("overridesVersion", overridesVersion)
        put("lyricsAdjustmentMs", lyricsAdjustmentMs)
        put("replayGainTags",JsonObject(replayGainTags.mapValues{JsonPrimitive(it.value)}));put("replayGainScanned",replayGainScanned)
        put("artworkCredit",artworkCredit);put("artworkSourceUrl",artworkSourceUrl)
        put("hidden",hidden)
    }.toString()
    companion object {
        fun decode(text: String): LibraryExtras {
            require(text.length <= 2_000_000)
            val o = Json.parseToJsonElement(text).jsonObject
            require(o.getValue("version").jsonPrimitive.int == 1)
            fun optional(k: String) = o[k]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
            fun map(k: String) = o[k]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
            return LibraryExtras(map("folders"), map("canonical"), optional("artworkPath"), optional("artworkStamp"), optional("artworkOverride"),
                o["tagsOverride"]?.takeUnless { it is JsonNull }?.let { ModelCodec.tags(it.toString()) }, optional("sidecarUri"), optional("sidecarVersion"), optional("embeddedVersion"), map("tagFields"), o["overridesVersion"]?.jsonPrimitive?.long ?: 0,
                o["lyricsAdjustmentMs"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long,map("replayGainTags"),o["replayGainScanned"]?.jsonPrimitive?.boolean?:false,
                optional("artworkCredit"),optional("artworkSourceUrl"),o["hidden"]?.jsonPrimitive?.boolean?:false)
        }
    }
}
