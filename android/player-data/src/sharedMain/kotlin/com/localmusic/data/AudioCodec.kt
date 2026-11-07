package com.localmusic.data

import com.localmusic.core.*
import kotlinx.serialization.json.*

data class EqPresetImport(val preset:EqPreset,val notice:String)

object AudioCodec {
    const val MAX_BYTES=131072
    /** File import is deliberately separate from the strict saved-settings/backup codec. */
    fun importPreset(text:String,suggestedName:String?=null):EqPresetImport {
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES){"EQ preset exceeds 128 KiB."}
        require(text.count{it=='{'||it=='['}<=100){"EQ preset is too complex."}
        val content=text.removePrefix("\uFEFF").trim()
        if(content.startsWith("{")||content.startsWith("[")){
            val root=Json.parseToJsonElement(content)
            if(root is JsonObject&&"format" in root){
                val result=preset(content)
                return EqPresetImport(result,if(result.filters==null)"Local Music graphic preset - exact band gains." else "Parametric preset - filter frequencies, shapes and Q are retained.")
            }
            return importLegacyGraphic(root)
        }
        return importEqualizerApo(content,suggestedName)
    }
    private fun importLegacyGraphic(root:JsonElement):EqPresetImport {
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

    /** Reads the documented Equalizer APO ParametricEQ text syntax used by AutoEQ. */
    private fun importEqualizerApo(text:String,suggestedName:String?):EqPresetImport {
        val numeric="[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?"
        val preamp=Regex("(?i)^Preamp\\s*:\\s*($numeric)\\s*dB\\s*$")
        val filter=Regex("(?i)^Filter(?:\\s+\\d+)?\\s*:\\s*(ON|OFF)\\s+PK\\s+Fc\\s*($numeric)\\s*Hz\\s+Gain\\s*($numeric)\\s*dB\\s+Q\\s*($numeric)\\s*$")
        var preampDb=0.0
        var preampSeen=false
        val filters=mutableListOf<EqFilter>()
        for((lineIndex,raw) in text.lineSequence().withIndex()){
            val line=raw.trim()
            if(line.isEmpty()||line.startsWith("#")||line.startsWith("//"))continue
            val preampMatch=preamp.matchEntire(line)
            if(preampMatch!=null){
                require(!preampSeen){"The file contains more than one Preamp line."}
                preampSeen=true;preampDb=preampMatch.groupValues[1].toDouble()
                require(preampDb.isFinite()&&preampDb in -24.0..0.0){"Preamp must be between -24 and 0 dB."}
                continue
            }
            val match=filter.matchEntire(line)?:throw IllegalArgumentException("Unsupported ParametricEQ line ${lineIndex+1}.")
            if(match.groupValues[1].equals("OFF",true))continue
            val frequency=match.groupValues[2].toDouble();val gain=match.groupValues[3].toDouble();val q=match.groupValues[4].toDouble()
            filters+=EqFilter(EqFilterType.PEAKING,frequency,gain,q)
            require(filters.size<=20){"Import supports up to 20 active filters."}
        }
        require(filters.isNotEmpty()){"No active parametric filters were found."}
        val name=suggestedName?.trim()?.take(80)?.takeIf{it.isNotBlank()}?:"AutoEQ import"
        return EqPresetImport(EqPreset(name,preampDb,filters=filters),"Equalizer APO / AutoEQ PK filters. ${filters.size} active peaking filter(s) imported exactly.")
    }
    fun preset(text:String):EqPreset {
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES){"EQ preset exceeds 128 KiB."}
        require(text.count{it=='{'||it=='['}<=100){"EQ preset is too complex."}
        val o=Json.parseToJsonElement(text.removePrefix("\uFEFF")).jsonObject
        val version=o.getValue("version").jsonPrimitive.also{require(!it.isString)}.int
        if(version==2){
            require(o.getValue("format").jsonPrimitive.content=="local-music-eq"&&o.getValue("name").jsonPrimitive.isString&&
                o.getValue("type").jsonPrimitive.isString){"Use Local Music EQ JSON, version 2."}
            fun number(e:JsonElement)=e.jsonPrimitive.also{require(!it.isString)}.double.also{require(it.isFinite())}
            val name=o.getValue("name").jsonPrimitive.content.trim();val preamp=number(o.getValue("preampDb"))
            return when(o.getValue("type").jsonPrimitive.content){
                "graphic"->{
                    require(o.keys==setOf("format","version","name","type","preampDb","bands"))
                    val bands=o.getValue("bands").jsonArray.also{require(it.size==10)}.map{element->val b=element.jsonObject
                        require(b.keys==setOf("frequencyHz","gainDb"));number(b.getValue("frequencyHz")) to number(b.getValue("gainDb"))}.sortedBy{it.first}
                    require(bands.map{it.first}==EqPreset.FREQUENCIES.map{it.toDouble()}){"EQ frequencies must match all 10 Local Music bands."}
                    EqPreset(name,preamp,bands.map{it.second})
                }
                "parametric"->{
                    require(o.keys==setOf("format","version","name","type","preampDb","filters")){"The preset fields do not match Local Music EQ v2."}
                    val filters=o.getValue("filters").jsonArray.also{require(it.size in 1..20)}.map{element->val b=element.jsonObject
                        require(b.keys==setOf("type","frequencyHz","gainDb","q"))
                        EqFilter(EqFilterType.valueOf(b.getValue("type").jsonPrimitive.content),number(b.getValue("frequencyHz")),number(b.getValue("gainDb")),number(b.getValue("q")))
                    }
                    EqPreset(name,preamp,filters=filters)
                }
                else->error("Use Local Music graphic or parametric EQ JSON, version 2.")
            }
        }
        require(version==1&&o.keys==setOf("format","version","name","type","preampDb","bands")){"The preset fields do not match Local Music EQ v1/v2."}
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
    fun preset(value:EqPreset):String {
        val parametricFilters=value.filters
        return if(parametricFilters==null)buildJsonObject{
            put("format","local-music-eq");put("version",1);put("name",value.name);put("type","graphic");put("preampDb",value.preampDb)
            put("bands",JsonArray(value.gains.mapIndexed{i,g->buildJsonObject{put("frequencyHz",EqPreset.FREQUENCIES[i]);put("gainDb",g)}}))
        }.toString() else buildJsonObject{
            put("format","local-music-eq");put("version",2);put("name",value.name);put("type","parametric");put("preampDb",value.preampDb)
            put("filters",JsonArray(parametricFilters.map{filter->buildJsonObject{
                put("type",filter.type.name);put("frequencyHz",filter.frequencyHz);put("gainDb",filter.gainDb);put("q",filter.q)
            }}))
        }.toString()
    }
    fun settings(text:String):AudioSettings {
        require(text.length<=MAX_BYTES*2)
        val o=Json.parseToJsonElement(text).jsonObject
        val version=o.getValue("version").jsonPrimitive.also{require(!it.isString)}.int
        require(version in 1..2)
        val preferUsb=o["preferAndroidUsb"]?.jsonPrimitive?.let{
            require(version==2&&!it.isString){"Android USB preference needs settings v2 and a boolean."}
            requireNotNull(it.booleanOrNull){"Android USB preference must be a boolean."}
        }?:false
        return AudioSettings(o.getValue("enabled").jsonPrimitive.boolean,preset(o.getValue("preset").toString()),
            o.getValue("presets").jsonArray.also{require(it.size<=100)}.map{preset(it.toString())},
            ReplayGainMode.valueOf(o.getValue("replayGain").jsonPrimitive.content),o.getValue("crossfade").jsonPrimitive.int,preferUsb)
    }
    fun settings(value:AudioSettings):String=buildJsonObject{
        put("version",if(value.preferAndroidUsb)2 else 1);put("enabled",value.eqEnabled);put("preset",Json.parseToJsonElement(preset(value.preset)))
        if(value.preferAndroidUsb)put("preferAndroidUsb",true)
        put("presets",JsonArray(value.presets.map{Json.parseToJsonElement(preset(it))}));put("replayGain",value.replayGain.name);put("crossfade",value.crossfadeSeconds)
    }.toString()
}
