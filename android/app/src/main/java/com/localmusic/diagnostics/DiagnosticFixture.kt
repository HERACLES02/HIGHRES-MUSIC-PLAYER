package com.localmusic.diagnostics

/** Only bundled fixtures may reach either diagnostic engine. */
object DiagnosticFixture {
    private val classic=Regex("stereo-(16|24)-(44100|48000|88200|96000)\\.(wav|flac)")
    private val separated=Regex("channels-(16|24)-(44100|48000|88200|96000)\\.wav")
    fun supported(name: String)=classic.matches(name) || separated.matches(name)
    fun channelCheck(name: String)=separated.matches(name)
    fun manifest(name: String)=if(channelCheck(name)) "channel-fixtures-manifest.json" else "fixtures-manifest.json"
    fun pattern(name: String)=if(channelCheck(name))
        "0-2s left 440 Hz; 2-3s silence; 3-5s right 440 Hz; 5-6s silence. Repeats; no both-channel section."
        else "0-2s left 440 Hz; 2-4s right 660 Hz; 4-6s BOTH channels. Repeats; overlap is intentional in the final section."
}
