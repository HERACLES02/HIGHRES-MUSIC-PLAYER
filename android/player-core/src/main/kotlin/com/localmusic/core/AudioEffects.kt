package com.localmusic.core

import kotlin.math.*
import java.util.Locale

enum class ReplayGainMode { OFF, TRACK, ALBUM }
data class EqPreset(val name:String="Flat", val preampDb:Double=0.0, val gains:List<Double> = List(10){0.0}) {
    init {
        require(name.isNotBlank()&&name.length<=80&&name.none{it.code<32}) { "Use a preset name of 1–80 characters." }
        require(preampDb.isFinite()&&preampDb in -24.0..0.0) { "Preamp must be between -24 and 0 dB." }
        require(gains.size==10&&gains.all{it.isFinite()&&it in -12.0..12.0}) { "Include 10 band gains between -12 and +12 dB." }
    }
    companion object { val FREQUENCIES=listOf(31,63,125,250,500,1000,2000,4000,8000,16000) }
}
data class AudioSettings(val eqEnabled:Boolean=false,val preset:EqPreset=EqPreset(),val presets:List<EqPreset> = emptyList(),
    val replayGain:ReplayGainMode=ReplayGainMode.OFF,val crossfadeSeconds:Int=0) {
    init { require(crossfadeSeconds in 0..6);require(presets.size<=100&&presets.map{it.name.lowercase(Locale.ROOT)}.distinct().size==presets.size) }
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
    private var coefficients=DoubleArray(50)
    private var history=DoubleArray(0)
    private var enabled=false
    private var gain=1.0
    private var targetGain=1.0
    private var smoothing=.001
    fun configure(sampleRate:Int,channels:Int,configuration:DspConfiguration) {
        require(sampleRate in 8000..768000&&channels in 1..32)
        if(this.channels!=channels){this.channels=channels;history=DoubleArray(channels*20)}
        val settings=configuration.settings
        val gainDb=(if(settings.eqEnabled)settings.preset.preampDb else 0.0)+configuration.replayGain.gainDb(settings.replayGain)
        targetGain=10.0.pow(gainDb/20);smoothing=1.0/(sampleRate*.015)
        enabled=settings.eqEnabled||gainDb!=0.0||abs(gain-targetGain)>.000001
        for(band in 0..9){
            val i=band*5;val frequency=EqPreset.FREQUENCIES[band].toDouble();val db=if(settings.eqEnabled)settings.preset.gains[band] else 0.0
            if(frequency>=sampleRate*.49||db==0.0){coefficients[i]=1.0;for(j in 1..4)coefficients[i+j]=0.0;continue}
            // Peaking biquad, RBJ/W3C Audio EQ Cookbook, one-octave graphic bands (Q = sqrt(2)).
            val a=10.0.pow(db/40);val omega=2*PI*frequency/sampleRate;val alpha=sin(omega)/(2*sqrt(2.0));val a0=1+alpha/a
            coefficients[i]=(1+alpha*a)/a0;coefficients[i+1]=-2*cos(omega)/a0;coefficients[i+2]=(1-alpha*a)/a0
            coefficients[i+3]=-2*cos(omega)/a0;coefficients[i+4]=(1-alpha/a)/a0
        }
    }
    fun sample(value:Double,channel:Int):Double {
        if(!enabled)return value
        if(channel==0)gain+=(targetGain-gain)*smoothing
        var x=if(value.isFinite())value*gain else 0.0
        for(band in 0..9){val c=band*5;val h=channel*20+band*2
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
