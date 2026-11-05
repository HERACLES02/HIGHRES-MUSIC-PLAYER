package com.localmusic.data

import com.localmusic.core.*
import kotlinx.serialization.json.*

/** Shared by DataStore, versioned backup and its durable preference recovery journal. */
object PreferenceCodec {
    fun mergeRestore(original:BackupPreferences,incoming:BackupPreferences):BackupPreferences {
        val artists=original.artists+incoming.artists
        require(artists.size<=100){"Restore would exceed 100 saved artists. Remove an unused saved artist match first."}
        val presets=(original.audio.presets+incoming.audio.presets).associateBy{it.name.lowercase(java.util.Locale.ROOT)}.values.toList()
        require(presets.size<=100){"Restore would exceed 100 saved EQ presets. Remove unused presets first."}
        return incoming.copy(artists=artists,audio=incoming.audio.copy(presets=presets)).also{decode(encode(it))}
    }
    fun encode(value:BackupPreferences):String=buildJsonObject{
        put("version",1);put("appearance",value.appearance);put("autoScan",value.autoScan);put("homeGrid",value.homeGrid);put("artworkSize",value.artworkSize)
        put("audio",Json.parseToJsonElement(AudioCodec.settings(value.audio)))
        put("widgets",buildJsonObject{put("theme",value.widgets.theme);put("artwork",value.widgets.artwork)})
        put("artists",JsonObject(value.artists.mapValues{(_,p)->buildJsonObject{
            put("id",p.providerId);put("name",p.name);put("description",p.description);put("biography",p.biography);put("biographyUrl",p.biographyUrl);put("biographyLicense",p.biographyLicense)
            put("imagePath",p.imagePath);put("imageUrl",p.imageUrl);put("imageCredit",p.imageCredit);put("imageLicense",p.imageLicense);put("imageSourceUrl",p.imageSourceUrl);put("savedAt",p.savedAtMs)
        }}))
    }.toString()
    fun decode(text:String):BackupPreferences {
        require(text.length<=2_000_000)
        val o=Json.parseToJsonElement(text).jsonObject
        require((o["version"]?.jsonPrimitive?.int?:1)==1)
        fun JsonObject.optional(key:String)=get(key)?.takeUnless{it==JsonNull}?.jsonPrimitive?.content
        val artists=o["artists"]?.jsonObject?.also{require(it.size<=100)}?.mapValues{(key,element)->val p=element.jsonObject
            require(key.isNotBlank()&&key.length<=300&&ArtistProfile.key(key)==key)
            ArtistProfile(p.getValue("id").jsonPrimitive.content,p.getValue("name").jsonPrimitive.content,p.optional("description").orEmpty(),p.optional("biography").orEmpty(),p.optional("biographyUrl"),p.optional("biographyLicense"),
                p.optional("imagePath"),p.optional("imageUrl"),p.optional("imageCredit"),p.optional("imageLicense"),p.optional("imageSourceUrl"),p.getValue("savedAt").jsonPrimitive.long)
        }.orEmpty()
        val widgets=o["widgets"]?.jsonObject?.let{WidgetSettings(it.getValue("theme").jsonPrimitive.content,it.getValue("artwork").jsonPrimitive.boolean)}?:WidgetSettings()
        return BackupPreferences(o.getValue("appearance").jsonPrimitive.content,o.getValue("autoScan").jsonPrimitive.boolean,o.getValue("homeGrid").jsonPrimitive.boolean,
            o["audio"]?.let{AudioCodec.settings(it.toString())}?:AudioSettings(),widgets,artists,o["artworkSize"]?.jsonPrimitive?.content ?: "Large").also{require(it.appearance in setOf("System","Light","Dark","AMOLED"));require(it.artworkSize in setOf("Compact","Large","Full width"))}
    }
}
