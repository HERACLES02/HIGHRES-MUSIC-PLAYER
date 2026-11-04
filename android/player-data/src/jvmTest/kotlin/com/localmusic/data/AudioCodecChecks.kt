package com.localmusic.data

import com.localmusic.core.*

fun audioCodecChecks(){
    var count=0
    fun verify(ok:Boolean,label:String){count++;check(ok){"EQ JSON: $label"}}
    fun rejects(text:String){var failed=false;try{AudioCodec.preset(text)}catch(_:Exception){failed=true};verify(failed,"invalid JSON rejected")}
    val preset=EqPreset("বাংলা headphones",-3.0,listOf(3.0,2.0,1.0,0.0,-1.0,-1.0,0.0,1.0,1.0,0.0))
    val json=AudioCodec.preset(preset)
    verify(AudioCodec.preset(json)==preset,"Unicode and exact gains round-trip")
    val local=com.localmusic.core.ArtistProfile("00000000-0000-0000-0000-000000000001","New artist",biography="Keep my local note")
    val original=BackupPreferences(audio=AudioSettings(presets=listOf(EqPreset("New preset"))),artists=mapOf("new artist" to local))
    val incoming=BackupPreferences(audio=AudioSettings(presets=listOf(preset)))
    val merged=PreferenceCodec.mergeRestore(original,incoming)
    verify(merged.artists["new artist"]==local&&merged.audio.presets.size==2,"restore keeps unrelated newer artist notes and saved EQ presets")
    rejects(json.replace("\"version\":1","\"version\":2"));rejects(json.replace("graphic","parametric"))
    rejects(json.replace("\"version\":1","\"version\":\"1\""))
    rejects(json.replace("local-music-eq","other-format"));rejects(json.replace("\"preampDb\":-3.0","\"preampDb\":2"))
    rejects(json.replace("\"frequencyHz\":31","\"frequencyHz\":32"));rejects(json.replace("\"frequencyHz\":63","\"frequencyHz\":31"))
    rejects(json.replace("\"gainDb\":3.0","\"gainDb\":13"));rejects(json.replace("\"gainDb\":3.0","\"gainDb\":\"3.0\""))
    rejects("[]");rejects(" ".repeat(131073));rejects(json.dropLast(1));rejects(json.dropLast(1)+",\"url\":\"https://example.com\"}")
    val settings=AudioSettings(true,preset,listOf(preset),ReplayGainMode.ALBUM,4)
    verify(AudioCodec.settings(AudioCodec.settings(settings))==settings,"saved preset bank and effects round-trip")
    verify(AudioCodec.preset("\uFEFF"+json)==preset,"UTF-8 BOM is accepted")
    // Representative external graphic export, including inactive shelf controls.
    val external="""[{"name":"KZ ZSN Pro","preamp":0.0,"parametric":false,"bands":[
        {"type":0,"channels":0,"frequency":90,"q":0.8,"gain":0,"color":0},
        {"type":1,"channels":0,"frequency":10000,"q":0.6,"gain":0,"color":0},
        ${listOf(31,62,124,249,498,996,1995,3993,7993,16000).mapIndexed{i,f->
            """{"type":2,"channels":0,"frequency":$f,"q":0,"gain":${if(i==5)5.114406108856201 else 0.0},"color":0}"""}.joinToString(",")}
    ]}]"""
    val imported=AudioCodec.importPreset(external)
    verify(imported.preset.name=="KZ ZSN Pro"&&imported.preset.gains[5]==5.114406108856201,"external graphic gains imported without rounding")
    verify(imported.notice.contains("approximation"),"review discloses graphic frequency conversion")
    verify(AudioCodec.preset(AudioCodec.preset(imported.preset))==imported.preset,"converted preset survives export and restore")
    verify(AudioCodec.importPreset(json).preset==preset,"native JSON still imports exactly")
    val fixture=checkNotNull(AudioCodec::class.java.getResourceAsStream("/eq/external-graphic.json")).bufferedReader(Charsets.UTF_8).use{it.readText()}
    val supplied=AudioCodec.importPreset(fixture).preset
    verify(supplied.gains==listOf(2.75,2.009999990463257,-0.35862094163894653,0.1767115294933319,3.6451048851013184,5.114406108856201,0.29704749584198,2.7660531997680664,3.8268532752990723,-2.1189522743225098),"supplied KZ preset retains all ten exact gains")
    val kernel=DspKernel();kernel.configure(48000,1,DspConfiguration(AudioSettings(eqEnabled=true,preset=supplied)))
    var dry=0.0;var wet=0.0
    repeat(48000){i->val sample=.01*kotlin.math.sin(2*kotlin.math.PI*1000*i/48000);val out=kernel.sample(sample,0)
        if(i>=24000){dry+=sample*sample;wet+=out*out}}
    verify(10*kotlin.math.log10(wet/dry)>4.0,"supplied import measurably boosts 1 kHz PCM rather than staying flat")
    fun rejectsImport(text:String){var failed=false;try{AudioCodec.importPreset(text)}catch(_:Exception){failed=true};verify(failed,"unsupported external curve rejected")}
    rejectsImport(external.replace("\"parametric\":false","\"parametric\":true"))
    rejectsImport(external.replace("\"parametric\":false","\"parametric\":\"false\""))
    rejectsImport(external.replace("\"frequency\":996","\"frequency\":1200"))
    rejectsImport(external.replace("\"channels\":0","\"channels\":1"))
    rejectsImport(external.replace("\"q\":0,","\"q\":1,"))
    rejectsImport(external.replace("\"gain\":0,","\"gain\":3,"))
    rejectsImport("[$external,$external]")
    println("EQ JSON checks passed: $count assertions.")
}
