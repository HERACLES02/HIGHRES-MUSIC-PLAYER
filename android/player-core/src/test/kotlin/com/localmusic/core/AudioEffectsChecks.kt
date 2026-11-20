package com.localmusic.core

import kotlin.math.*

fun audioEffectsChecks(){
    eqResponseChecks()
    androidUsbRulesChecks()
    var count=0
    fun verify(ok:Boolean,label:String){count++;check(ok){"Audio effects: $label"}}
    fun rejects(block:()->Unit){var failed=false;try{block()}catch(_:IllegalArgumentException){failed=true};verify(failed,"invalid settings rejected")}
    rejects{EqPreset(gains=listOf(0.0))};rejects{EqPreset(gains=List(10){Double.NaN})};rejects{EqPreset(preampDb=1.0)}
    rejects{EqPreset(name=" ")};rejects{AudioSettings(crossfadeSeconds=20)}
    val metadata=ReplayGainInfo.parse(mapOf("REPLAYGAIN_TRACK_GAIN" to "-6.02 dB","replaygain_album_gain" to "+3 dB","REPLAYGAIN_ALBUM_PEAK" to "1.5"))
    verify(abs(metadata.gainDb(ReplayGainMode.TRACK)+6.02)<.001,"track gain parsed")
    verify(metadata.gainDb(ReplayGainMode.OFF)==0.0,"off bypasses tags")
    verify(metadata.gainDb(ReplayGainMode.ALBUM)<=20*log10(1/1.5)+.001,"album peak limits gain")
    verify(ReplayGainInfo().gainDb(ReplayGainMode.ALBUM)==0.0,"missing album tags unchanged")
    verify(ReplayGainInfo.parse(mapOf("REPLAYGAIN_TRACK_GAIN" to "NaN dB")).trackGainDb==null,"nonfinite tags ignored")
    val k=DspKernel();k.configure(48000,2,DspConfiguration());verify(k.sample(.123456789,0)==.123456789,"disabled exact passthrough")
    k.configure(48000,2,DspConfiguration(AudioSettings(replayGain=ReplayGainMode.TRACK),metadata));repeat(4800){k.sample(.5,0)}
    verify(abs(k.sample(.5,0)-.25)<.002,"ReplayGain changes samples")
    fun rms(freq:Double,gain:Double):Double{
        val kernel=DspKernel();kernel.configure(48000,2,DspConfiguration(AudioSettings(true,EqPreset(gains=List(10){if(it==5)gain else 0.0}))))
        var sum=0.0;for(i in 0 until 48000){val value=kernel.sample(.02*sin(2*PI*freq*i/48000),0);if(i>=24000)sum+=value*value};return sqrt(sum/24000)
    }
    verify(abs(20*log10(rms(1000.0,6.0)/rms(1000.0,0.0))-6)<.25,"1 kHz boost measured")
    verify(abs(20*log10(rms(1000.0,-6.0)/rms(1000.0,0.0))+6)<.25,"1 kHz cut measured")
    verify(rms(100.0,6.0)<rms(1000.0,6.0),"EQ is frequency selective")
    val independent=DspKernel();independent.configure(44100,2,DspConfiguration(AudioSettings(true,EqPreset(gains=List(10){3.0}))))
    repeat(100){independent.sample(.2,0)};verify(independent.sample(0.0,1)==0.0,"channels independent")
    independent.reset();verify(independent.sample(0.0,0)==0.0,"seek flush clears filter history")
    k.configure(32000,2,DspConfiguration(AudioSettings(true,EqPreset(gains=List(10){12.0}))))
    verify((0..1000).all{k.sample(if(it%2==0)1.0 else -1.0,it%2).let{v->v.isFinite()&&v in -1.0..1.0}},"Nyquist and overload bounded")
    verify(CrossfadeRules.window(0,100000,100000,RepeatMode.OFF,false)==null,"off preserves gapless")
    verify(CrossfadeRules.window(4,100000,100000,RepeatMode.OFF,true)==null,"end timer prevents fade")
    verify(CrossfadeRules.window(4,100000,100000,RepeatMode.ONE,false)==null,"repeat one stays gapless")
    val fade=CrossfadeRules.window(6,8000,4000,RepeatMode.OFF,false)!!;verify(fade.durationMs==2000L,"short songs limit overlap")
    verify(fade.incoming(0)==0f&&fade.outgoing(0)==1f,"starts outgoing only")
    verify(fade.incoming(2000)==1f&&fade.outgoing(2000)==0f,"ends incoming only")
    verify((0..2500).all{abs(fade.incoming(it.toLong())+fade.outgoing(it.toLong())-1)<.00001},"complementary mix gain")
    println("Audio effects checks passed: $count assertions (real PCM, EQ, gain, channels, fade boundaries).")
}
