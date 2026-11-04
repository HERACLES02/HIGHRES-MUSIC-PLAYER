package com.localmusic.data

fun enrichmentChecks(){
    var count=0;var clock=10000L;var until=0L;var calls=0
    fun verify(ok:Boolean,label:String){count++;check(ok){"Enrichment: $label"}}
    fun rejects(block:()->Unit){var failed=false;try{block()}catch(_:Exception){failed=true};verify(failed,"invalid provider response rejected")}
    var response=EnrichmentResponse(200,"application/json","""{"artists":[{"id":"00000000-0000-0000-0000-000000000001","name":"Mira","disambiguation":"Folk","country":"GB"},{"id":"bad","name":"Bad"}]}""".toByteArray())
    var last="";var agent=""
    val transport=EnrichmentTransport{url,headers,_->calls++;last=url;agent=headers.getValue("User-Agent");response}
    val cooldown=object:LyricsCooldown{override fun read()=until;override fun write(untilMs:Long){until=untilMs}}
    val client=EnrichmentClient(cooldown,transport,{clock},{clock+=it})
    verify(calls==0,"construction performs no network request")
    val found=client.searchArtists("Mira & Guest")
    verify(found.size==1&&found[0].description.contains("Folk"),"namesake fields preserved; malformed identities skipped")
    verify(last.startsWith("https://musicbrainz.org/ws/2/artist?")&&"%26" in last,"query correctly encoded")
    verify(agent.contains("LocalMusic/")&&agent.contains("github.com/HERACLES02"),"meaningful user agent")
    client.searchArtists("Again");verify(clock>=11100,"request spacing respects MusicBrainz limit")
    rejects{client.searchArtists(" ")};rejects{client.searchArtists("x".repeat(301))}
    response=EnrichmentResponse(200,"text/html","<html>error</html>".toByteArray());rejects{client.searchArtists("Mira")}
    response=EnrichmentResponse(200,"application/json","{".toByteArray());rejects{client.searchArtists("Mira")}
    response=EnrichmentResponse(429,"application/json",byteArrayOf(),"10");rejects{client.searchArtists("Mira")}
    verify(until>=clock+10000,"server cooldown persisted")
    val before=calls;rejects{client.searchArtists("Mira")};verify(calls==before,"cooldown blocks outgoing requests")
    until=0;clock+=20000
    response=EnrichmentResponse(200,"application/json","""{"releases":[{"id":"00000000-0000-0000-0000-000000000002","title":"Album","date":"2026","country":"GB","disambiguation":"Deluxe","artist-credit":[{"name":"Mira"}]}]}""".toByteArray())
    val release=client.searchReleases("Mira","Album").single();verify(release.edition=="Deluxe"&&release.country=="GB","release edition and country retained")
    response=EnrichmentResponse(200,"application/json","""{"images":[{"front":true,"thumbnails":{"500":"https://evil.invalid/picture.jpg"}}]}""".toByteArray())
    rejects{client.cover(release)}
    response=EnrichmentResponse(302,"",byteArrayOf(),location="http://127.0.0.1/private");rejects{client.cover(release)}
    val imageClient=EnrichmentClient(cooldown,EnrichmentTransport{url,_,_->when{
        url=="https://coverartarchive.org/release/${release.id}"->EnrichmentResponse(200,"application/json","""{"images":[{"front":true,"thumbnails":{"500":"http://coverartarchive.org/release/${release.id}/123-500.jpg"}}]}""".toByteArray())
        url.startsWith("https://coverartarchive.org/release/")->EnrichmentResponse(302,"",byteArrayOf(),location="https://archive.org/download/cover.jpg")
        url=="https://archive.org/download/cover.jpg"->EnrichmentResponse(200,"image/jpeg",byteArrayOf(1,2,3))
        else->error("Unexpected fixture URL $url")
    }},{clock},{clock+=it})
    verify(imageClient.cover(release).image.contentEquals(byteArrayOf(1,2,3)),"official CAA thumbnail URLs upgrade to HTTPS and follow trusted redirects")
    val interruptedCalls=java.util.concurrent.atomic.AtomicInteger()
    val stopped=java.util.concurrent.atomic.AtomicBoolean()
    val interruptedClient=EnrichmentClient(cooldown,EnrichmentTransport{_,_,_->interruptedCalls.incrementAndGet();Thread.currentThread().interrupt();EnrichmentResponse(302,"",byteArrayOf(),location="https://archive.org/next")},{clock},{clock+=it})
    val thread=Thread{try{interruptedClient.cover(release)}catch(_:Exception){stopped.set(true)}finally{Thread.interrupted()}}
    thread.start();thread.join(2000)
    verify(stopped.get()&&interruptedCalls.get()==1,"cancellation prevents follow-up network requests")
    var licensed=true;var images=0;var thumbnailHost="upload.wikimedia.org"
    val artistClient=EnrichmentClient(cooldown,EnrichmentTransport{url,_,_->
        if(url.startsWith("https://$thumbnailHost/")){images++;EnrichmentResponse(200,"image/png",byteArrayOf(1,2))}
        else{val body=when{
            url.startsWith("https://musicbrainz.org/")->"""{"relations":[{"type":"wikidata","url":{"resource":"https://www.wikidata.org/wiki/Q1"}}]}"""
            url.startsWith("https://www.wikidata.org/")->"""{"entities":{"Q1":{"sitelinks":{"enwiki":{"title":"Mira One"}},"claims":{"P18":[{"mainsnak":{"datavalue":{"value":"Photo.png"}}}]}}}}"""
            url.startsWith("https://en.wikipedia.org/")->"""{"query":{"pages":[{"extract":"A fixture biography."}]}}"""
            url.startsWith("https://commons.wikimedia.org/")->"""{"query":{"pages":[{"imageinfo":[{"thumburl":"https://$thumbnailHost/wikipedia/commons/photo.png","extmetadata":{"Artist":{"value":"<a>Test Person</a>"},"Credit":{"value":"Own work"},"LicenseShortName":{"value":"${if(licensed)"CC BY-SA 4.0" else "All rights reserved"}"},"LicenseUrl":{"value":"https://creativecommons.org/licenses/by-sa/4.0/"}}}]}]}}"""
            else->error("Unexpected fixture URL $url")
        };EnrichmentResponse(200,"application/json",body.toByteArray())}
    },{clock},{clock+=it})
    val preview=artistClient.artist(found.single())
    verify(preview.profile.biography=="A fixture biography."&&preview.profile.biographyUrl?.contains("Mira_One")==true,"linked biography retains source and excerpt")
    verify(preview.profile.imageCredit?.contains("Test Person")==true&&preview.profile.imageLicense?.contains("CC BY-SA")==true&&preview.image!=null,"image credits and separate image license retained")
    thumbnailHost="thumb.wikimedia.org"
    verify(artistClient.artist(found.single()).image!=null&&images==2,"official Commons thumbnail host is accepted with credited image")
    thumbnailHost="thumb.wikimedia.org.evil.invalid";rejects{artistClient.artist(found.single())}
    verify(images==2,"lookalike thumbnail host never downloads")
    thumbnailHost="thumb.wikimedia.org"
    licensed=false;verify(artistClient.artist(found.single()).image==null&&images==2,"unusable image rights do not trigger image download")
    val wikiClient=EnrichmentClient(cooldown,EnrichmentTransport{url,_,_->
        verify(url.startsWith("https://en.wikipedia.org/w/api.php?"),"direct Wikipedia lookup never requires MusicBrainz")
        val body=if("list=search" in url)"""{"query":{"search":[{"pageid":123,"title":"Mira (singer)","snippet":"A <span>fixture</span> singer"},{"pageid":-1,"title":"Bad"}]}}"""
            else """{"query":{"pages":[{"pageid":123,"ns":0,"title":"Mira (singer)","extract":"An original fixture biography.","pageprops":{}}]}}"""
        EnrichmentResponse(200,"application/json",body.toByteArray())
    },{clock},{clock+=it})
    val wikiFound=wikiClient.searchWikipediaArtists("Mira")
    verify(wikiFound.size==1&&wikiFound[0].id=="wikipedia:123"&&!wikiFound[0].description.contains("<"),"Wikipedia page identity and plain snippet")
    val wiki=wikiClient.artist(wikiFound.single()).profile
    verify(wiki.providerId=="wikipedia:123"&&wiki.biography=="An original fixture biography."&&wiki.biographyUrl=="https://en.wikipedia.org/?curid=123","direct article preview retains independent provenance")
    val prefs=BackupPreferences(artists=mapOf("mira" to wiki))
    verify(PreferenceCodec.decode(PreferenceCodec.encode(prefs))==prefs,"Wikipedia match survives preferences and backup encoding")
    rejects{wikiClient.artist(ArtistCandidate("wikipedia:../private","Bad",""))}
    println("Enrichment fixture checks passed: $count assertions (no live provider calls).")
}
