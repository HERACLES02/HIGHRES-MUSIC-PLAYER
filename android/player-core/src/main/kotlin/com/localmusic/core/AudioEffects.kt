package com.localmusic.core

import kotlin.math.*
import java.util.Locale

enum class ReplayGainMode { OFF, TRACK, ALBUM }
enum class EqFilterType { PEAKING, LOW_SHELF, HIGH_SHELF }
data class EqFilter(val type:EqFilterType=EqFilterType.PEAKING,val frequencyHz:Double=1000.0,val gainDb:Double=0.0,val q:Double=1.0) {
    init {
        require(frequencyHz.isFinite()&&frequencyHz in 10.0..40000.0) { "Filter frequency must be between 10 and 40,000 Hz." }
        require(gainDb.isFinite()&&gainDb in -24.0..24.0) { "Filter gain must be between -24 and +24 dB." }
        require(q.isFinite()&&q in 0.1..20.0) { "Filter Q must be between 0.1 and 20." }
    }
}
data class EqPreset(val name:String="Flat", val preampDb:Double=0.0, val gains:List<Double> = List(10){0.0},
    /** Null means the original fixed graphic bands; a list means a parametric preset. */
    val filters:List<EqFilter>?=null) {
    init {
        require(name.isNotBlank()&&name.length<=80&&name.none{it.code<32}) { "Use a preset name of 1–80 characters." }
        require(preampDb.isFinite()&&preampDb in -24.0..0.0) { "Preamp must be between -24 and 0 dB." }
        require(gains.size==10&&gains.all{it.isFinite()&&it in -12.0..12.0}) { "Include 10 band gains between -12 and +12 dB." }
        require(filters==null||filters.size in 1..20) { "A parametric preset must have between 1 and 20 filters." }
    }
    fun resolvedFilters():List<EqFilter> = filters ?: FREQUENCIES.mapIndexed{i,frequency->EqFilter(frequencyHz=frequency.toDouble(),gainDb=gains[i],q=GRAPHIC_Q)}
    companion object {
        val FREQUENCIES=listOf(31,63,125,250,500,1000,2000,4000,8000,16000)
        const val GRAPHIC_Q=1.4142135623730951
    }
}
data class AudioSettings(val eqEnabled:Boolean=false,val preset:EqPreset=EqPreset(),val presets:List<EqPreset> = emptyList(),
    val replayGain:ReplayGainMode=ReplayGainMode.OFF,val crossfadeSeconds:Int=0,val preferAndroidUsb:Boolean=false) {
    init { require(crossfadeSeconds in 0..6);require(presets.size<=100&&presets.map{it.name.lowercase(Locale.ROOT)}.distinct().size==presets.size) }
    val effectiveCrossfadeSeconds:Int get()=if(preferAndroidUsb)0 else crossfadeSeconds
}
data class ReplayGainInfo(val trackGainDb:Double?=null,val albumGainDb:Double?=null,val trackPeak:Double?=null,val albumPeak:Double?=null) {
    fun gainDb(mode:ReplayGainMode):Double {
        val value=when(mode){ReplayGainMode.OFF->return 0.0;ReplayGainMode.TRACK->trackGainDb;ReplayGainMode.ALBUM->albumGainDb}?:return 0.0
        val peak=if(mode==ReplayGainMode.ALBUM)albumPeak else trackPeak
        return minOf(value.coerceIn(-60.0,24.0),peak?.takeIf{it.isFinite()&&it>0}?.let{-20*log10(it)}?:24.0)
    }
    companion object {
        fun parse(tags:Map<String,String>):ReplayGainInfo {
            val normalized=tags.mapKeys{it.key.uppercase(Locale.ROOT)}
            fun number(key:String)=normalized[key]?.trim()?.replace(Regex("(?i)\\s*dB$"),"")?.toDoubleOrNull()?.takeIf{it.isFinite()}
            return ReplayGainInfo(number("REPLAYGAIN_TRACK_GAIN")?.takeIf{it in -60.0..24.0},number("REPLAYGAIN_ALBUM_GAIN")?.takeIf{it in -60.0..24.0},
                number("REPLAYGAIN_TRACK_PEAK")?.takeIf{it>0&&it<=100},number("REPLAYGAIN_ALBUM_PEAK")?.takeIf{it>0&&it<=100})
        }
    }
}
data class DspConfiguration(val settings:AudioSettings=AudioSettings(),val replayGain:ReplayGainInfo=ReplayGainInfo())
class DspKernel {
    private var channels=0
    private var coefficients=DoubleArray(0)
    private var history=DoubleArray(0)
    private var filterCount=0
    private var enabled=false
    private var gain=1.0
    private var targetGain=1.0
    private var smoothing=.001
    fun configure(sampleRate:Int,channels:Int,configuration:DspConfiguration) {
        require(sampleRate in 8000..768000&&channels in 1..32)
        val settings=configuration.settings
        val gainDb=(if(settings.eqEnabled)settings.preset.preampDb else 0.0)+configuration.replayGain.gainDb(settings.replayGain)
        targetGain=10.0.pow(gainDb/20);smoothing=1.0/(sampleRate*.015)
        enabled=settings.eqEnabled||gainDb!=0.0||abs(gain-targetGain)>.000001
        val filters=if(settings.eqEnabled)settings.preset.resolvedFilters() else emptyList()
        filterCount=filters.size
        coefficients=DoubleArray(filterCount*5)
        if(this.channels!=channels||history.size!=channels*filterCount*2){this.channels=channels;history=DoubleArray(channels*filterCount*2)}
        for((band,filter) in filters.withIndex()){
            filter.biquadCoefficients(sampleRate).copyInto(coefficients,band*5)
        }
    }
    fun sample(value:Double,channel:Int):Double {
        if(!enabled)return value
        if(channel==0)gain+=(targetGain-gain)*smoothing
        var x=if(value.isFinite())value*gain else 0.0
        for(band in 0 until filterCount){val c=band*5;val h=(channel*filterCount+band)*2
            val y=coefficients[c]*x+history[h]
            history[h]=coefficients[c+1]*x-coefficients[c+3]*y+history[h+1]
            history[h+1]=coefficients[c+2]*x-coefficients[c+4]*y;x=y
        }
        if(!x.isFinite()){reset();return 0.0}
        return x.coerceIn(-1.0,1.0)
    }
    fun reset(){history.fill(0.0)}
}
data class FadeWindow(val durationMs:Long) {
    init { require(durationMs>0) }
    fun incoming(elapsedMs:Long):Float=(elapsedMs.toDouble()/durationMs).coerceIn(0.0,1.0).toFloat()
    fun outgoing(elapsedMs:Long):Float=1f-incoming(elapsedMs)
}
object CrossfadeRules {
    fun window(seconds:Int,outgoingMs:Long,incomingMs:Long,repeat:RepeatMode,endTimer:Boolean):FadeWindow? {
        if(seconds<=0||outgoingMs<=0||incomingMs<=0||repeat==RepeatMode.ONE||endTimer)return null
        val duration=minOf(seconds.coerceAtMost(6)*1000L,outgoingMs/2,incomingMs/2)
        return if(duration<250)null else FadeWindow(duration)
    }
}
