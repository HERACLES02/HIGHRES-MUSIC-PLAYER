package com.localmusic.data

import com.localmusic.core.LrcParser
import kotlinx.serialization.json.*

/** Explicit artist/title lookup. No automatic fallback, scraping or repository writes. */
class LyricsOvhClient(private val cooldown:LyricsCooldown,private val transport:LyricsTransport=OkHttpLyricsTransport(),
    private val now:()->Long=System::currentTimeMillis) {
    private var nextRequestAt=0L
    @Synchronized fun search(artist:String,title:String):List<LyricsCandidate> {
        require(artist.isNotBlank()&&title.isNotBlank()&&artist.length<=300&&title.length<=300){"Enter an artist and title of 1-300 characters each."}
        val time=now();val wait=maxOf(cooldown.read(),nextRequestAt)-time
        check(wait<=0){"Lyrics.ovh asks you to wait ${((wait+999)/1000)} seconds before trying again."}
        if(Thread.currentThread().isInterrupted)throw InterruptedException("Lyrics lookup cancelled.")
        nextRequestAt=time+350
        val response=transport.request(lyricsOvhUrl(artist.trim(),title.trim()),mapOf("Accept" to "application/json",
            "User-Agent" to "LocalMusic/0.9.1 (+https://github.com/HERACLES02/HIGHRES-MUSIC-PLAYER)"))
        if(response.status==429||response.status==503){
            val seconds=response.retryAfter?.toLongOrNull()?.coerceIn(1,86400)?:30L
            cooldown.write(now()+seconds*1000)
            error("Lyrics.ovh is busy. Try again after $seconds seconds. Saved lyrics are unchanged.")
        }
        if(response.status==404)return emptyList()
        check(response.status==200){"Lyrics.ovh returned HTTP ${response.status}. Saved lyrics are unchanged."}
        check(response.contentType.startsWith("application/json",true)){"Lyrics.ovh returned an unexpected response."}
        require(response.body.length<=2_000_000){"Lyrics response is too large."}
        val root=Json.parseToJsonElement(response.body).jsonObject
        val text=root["lyrics"]?.jsonPrimitive?.also{require(it.isString){"Lyrics.ovh returned invalid lyric text."}}?.content
            ?.takeIf{it.isNotBlank()}?:return emptyList()
        require(text.length<=LrcParser.MAX_CHARS){"Lyrics exceed the supported length."}
        return listOf(LyricsCandidate(0,title.trim(),artist.trim(),"",0.0,false,text,null,LyricsSource.OVH))
    }
}
