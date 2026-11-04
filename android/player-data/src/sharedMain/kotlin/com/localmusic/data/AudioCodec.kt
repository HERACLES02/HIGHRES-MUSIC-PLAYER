package com.localmusic.data

import com.localmusic.core.*
import kotlinx.serialization.json.*

data class EqPresetImport(val preset:EqPreset,val notice:String)

object AudioCodec {
    const val MAX_BYTES=131072
    /** File import is deliberately separate from the strict saved-settings/backup codec. */
    fun importPreset(text:String):EqPresetImport {
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES){"EQ preset exceeds 128 KiB."}
        require(text.count{it=='{'||it=='['}<=100){"EQ preset is too complex."}
        val root=Json.parseToJsonElement(text.removePrefix("\uFEFF"))
        if(root is JsonObject&&"format" in root)return EqPresetImport(preset(text),"Local Music JSON v1 - exact band gains.")
        require(root is JsonArray&&root.size==1&&root[0] is JsonObject){"Use Local Music JSON or a single exported graphic preset."}
        val o=root[0].jsonObject
        require(o.keys==setOf("name","preamp","parametric","bands")){"Unsupported external EQ fields."}
        require(o.getValue("parametric").jsonPrimitive.let{!it.isString&&it.booleanOrNull==false}){"Parametric EQ imports are not supported. Export a 10-band graphic preset instead."}
        fun number(e:JsonElement)=e.jsonPrimitive.also{require(!it.isString){"EQ values must be numbers."}}.double.also{require(it.isFinite())}
        require(o.getValue("name").jsonPrimitive.isString)
        val bands=o.getValue("bands").jsonArray.also{require(it.size in 10..12){"Import needs 10 graphic bands and at most two inactive shelves."}}
        val graphic=mutableListOf<Pair<Double,Double>>()
        val shelves=mutableSetOf<Int>()
        for(element in bands){
            val b=element.jsonObject
            require(b.keys.containsAll(setOf("type","channels","frequency","q","gain"))&&b.keys.all{it in setOf("type","channels","frequency","q","gain","color")}){"Unsupported external band fields."}
            val type=number(b.getValue("type"));val gain=number(b.getValue("gain"));val frequency=number(b.getValue("frequency"));val q=number(b.getValue("q"))
            require(number(b.getValue("channels"))==0.0){"Separate-channel EQ is not supported."}
            require(frequency>0&&q>=0)
            when(type){
                2.0->{require(q==0.0){"Custom band widths are not supported."};graphic.add(frequency to gain)}
                0.0,1.0->require(gain==0.0&&shelves.add(type.toInt())){"Active or duplicate shelf filters are not supported."}
                else->error("Unsupported EQ filter type.")
            }
        }
        val sorted=graphic.sortedBy{it.first}
        require(sorted.size==10&&sorted.map{it.first}.distinct().size==10&&sorted.indices.all{
            kotlin.math.abs(sorted[it].first/EqPreset.FREQUENCIES[it]-1.0)<=.02
        }){"Use a standard 10-band graphic preset. Each center must be within 2% of the Local Music band."}
        val result=EqPreset(o.getValue("name").jsonPrimitive.content.trim(),number(o.getValue("preamp")),sorted.map{it.second})
        return EqPresetImport(result,"External graphic EQ approximation: nearest band centers (within 2%). Filter response may differ from the original app.")
    }
    fun preset(text:String):EqPreset {
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES){"EQ preset exceeds 128 KiB."}
        require(text.count{it=='{'||it=='['}<=100){"EQ preset is too complex."}
        val o=Json.parseToJsonElement(text.removePrefix("\uFEFF")).jsonObject
        require(o.keys==setOf("format","version","name","type","preampDb","bands")){"The preset fields do not match Local Music EQ v1."}
        require(!o.getValue("version").jsonPrimitive.isString&&o.getValue("format").jsonPrimitive.isString&&o.getValue("type").jsonPrimitive.isString){"Preset version must be an integer and format/type must be text."}
        require(o.getValue("format").jsonPrimitive.content=="local-music-eq"&&o.getValue("version").jsonPrimitive.int==1&&
            o.getValue("type").jsonPrimitive.content=="graphic"){"Use Local Music graphic EQ JSON, version 1."}
        fun number(e:JsonElement)=e.jsonPrimitive.also{require(!it.isString)}.double.also{require(it.isFinite())}
        require(o.getValue("name").jsonPrimitive.isString)
        val bands=o.getValue("bands").jsonArray.also{require(it.size==10)}.map{element->val b=element.jsonObject
            require(b.keys==setOf("frequencyHz","gainDb"));number(b.getValue("frequencyHz")) to number(b.getValue("gainDb"))}.sortedBy{it.first}
        require(bands.map{it.first}==EqPreset.FREQUENCIES.map{it.toDouble()}){"EQ frequencies must match all 10 Local Music bands. No bands are remapped."}
        return EqPreset(o.getValue("name").jsonPrimitive.content.trim(),number(o.getValue("preampDb")),bands.map{it.second})
    }
    fun preset(value:EqPreset):String=buildJsonObject{
        put("format","local-music-eq");put("version",1);put("name",value.name);put("type","graphic");put("preampDb",value.preampDb)
        put("bands",JsonArray(value.gains.mapIndexed{i,g->buildJsonObject{put("frequencyHz",EqPreset.FREQUENCIES[i]);put("gainDb",g)}}))
    }.toString()
    fun settings(text:String):AudioSettings {
        require(text.length<=MAX_BYTES*2)
        val o=Json.parseToJsonElement(text).jsonObject;require(o.getValue("version").jsonPrimitive.int==1)
        return AudioSettings(o.getValue("enabled").jsonPrimitive.boolean,preset(o.getValue("preset").toString()),
            o.getValue("presets").jsonArray.also{require(it.size<=100)}.map{preset(it.toString())},
            ReplayGainMode.valueOf(o.getValue("replayGain").jsonPrimitive.content),o.getValue("crossfade").jsonPrimitive.int)
    }
    fun settings(value:AudioSettings):String=buildJsonObject{
        put("version",1);put("enabled",value.eqEnabled);put("preset",Json.parseToJsonElement(preset(value.preset)))
        put("presets",JsonArray(value.presets.map{Json.parseToJsonElement(preset(it))}));put("replayGain",value.replayGain.name);put("crossfade",value.crossfadeSeconds)
    }.toString()
}
