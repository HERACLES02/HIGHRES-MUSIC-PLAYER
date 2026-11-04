package com.localmusic.core

import java.util.Locale
import java.text.Normalizer

data class ArtistProfile(val providerId:String,val name:String,val description:String="",val biography:String="",val biographyUrl:String?=null,
    val biographyLicense:String?=null,val imagePath:String?=null,val imageUrl:String?=null,val imageCredit:String?=null,val imageLicense:String?=null,
    val imageSourceUrl:String?=null,val savedAtMs:Long=0) {
    init {
        val wikipedia=providerId.matches(Regex("wikipedia:[1-9][0-9]{0,11}"))
        require((wikipedia||runCatching{java.util.UUID.fromString(providerId).toString()==providerId}.getOrDefault(false))&&name.isNotBlank()&&name.length<=300)
        require(description.length<=1000&&biography.length<=6000&&savedAtMs>=0)
        listOf(biographyUrl,imageUrl,imageSourceUrl).filterNotNull().forEach{require(it.startsWith("https://")&&it.length<=8192)}
        listOf(imageCredit,imageLicense,biographyLicense).filterNotNull().forEach{require(it.length<=2000)}
    }
    val identityUrl:String? get()=when {
        providerId.startsWith("wikipedia:")->"https://en.wikipedia.org/?curid="+providerId.substringAfter(':')
        providerId=="00000000-0000-0000-0000-000000000000"->null
        else->"https://musicbrainz.org/artist/$providerId"
    }
    val providerName:String get()=if(providerId.startsWith("wikipedia:"))"Wikipedia" else "MusicBrainz"
    companion object { fun key(name:String)=Normalizer.normalize(name.trim(),Normalizer.Form.NFC).lowercase(Locale.ROOT) }
}
data class WidgetSettings(val theme:String="Match app",val artwork:Boolean=true) {
    init { require(theme in setOf("Match app","Light","Dark")) }
}
