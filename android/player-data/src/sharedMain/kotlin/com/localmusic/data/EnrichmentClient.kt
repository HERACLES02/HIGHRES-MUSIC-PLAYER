package com.localmusic.data

import com.localmusic.core.ArtistProfile
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream

data class ArtistCandidate(val id:String,val name:String,val description:String)
data class ReleaseCandidate(val id:String,val title:String,val artist:String,val date:String,val country:String,val edition:String)
data class ArtistPreview(val profile:ArtistProfile,val image:ByteArray?=null)
data class CoverPreview(val release:ReleaseCandidate,val image:ByteArray,val sourceUrl:String,val credit:String)
data class EnrichmentResponse(val status:Int,val contentType:String,val bytes:ByteArray,val retryAfter:String?=null,val location:String?=null)
fun interface EnrichmentTransport { fun get(url:String,headers:Map<String,String>,limit:Int):EnrichmentResponse }
class EnrichmentClient(private val cooldown:LyricsCooldown,private val transport:EnrichmentTransport=NetworkEnrichmentTransport(),
    private val now:()->Long=System::currentTimeMillis,private val wait:(Long)->Unit=Thread::sleep){
    private var nextMusicBrainz=0L
    private fun encode(value:String)=URLEncoder.encode(value,"UTF-8")
    private fun quoted(value:String)="\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\""
    private fun query(value:String):String {require(value.isNotBlank()&&value.length<=300){"Enter 1–300 characters."};return value.trim()}
    private fun id(value:String)=UUID.fromString(value).toString().also{require(it.equals(value,true))}
    private fun JsonObject.text(key:String)=get(key)?.takeUnless{it==JsonNull}?.jsonPrimitive?.content.orEmpty()
    private fun safe(url:String):String {
        val parsed=url.toHttpUrl();val host=parsed.host
        require(parsed.scheme=="https"&&parsed.username.isEmpty()&&parsed.password.isEmpty()&&parsed.port==443&&
            (host in setOf("musicbrainz.org","www.wikidata.org","en.wikipedia.org","commons.wikimedia.org","upload.wikimedia.org","thumb.wikimedia.org","coverartarchive.org","archive.org")||host.endsWith(".archive.org"))){"Provider returned an unsupported address."}
        return parsed.toString()
    }
    private fun fetch(url:String,image:Boolean=false):EnrichmentResponse {
        var target=safe(url)
        repeat(6){
            if(Thread.currentThread().isInterrupted)throw InterruptedException("Lookup cancelled.")
            val current=now();check(cooldown.read()<=current){"Provider asks you to wait before trying again. Saved results are unchanged."}
            if(target.toHttpUrl().host=="musicbrainz.org"){
                val remaining=nextMusicBrainz-current;if(remaining>0)wait(remaining)
                nextMusicBrainz=now()+1100
            }
            val limit=if(image)2*1024*1024 else 1024*1024
            val response=transport.get(target,mapOf("Accept" to if(image)"image/jpeg,image/png,image/webp" else "application/json",
                "User-Agent" to "LocalMusic/0.9.1 (+https://github.com/HERACLES02/HIGHRES-MUSIC-PLAYER)"),limit)
            require(response.bytes.size<=limit){"Provider response exceeds the download limit."}
            if(response.status in listOf(301,302,303,307,308)){
                target=safe(requireNotNull(target.toHttpUrl().resolve(requireNotNull(response.location))).toString());return@repeat
            }
            if(response.status==429||response.status==503){
                val seconds=response.retryAfter?.toLongOrNull()?.coerceIn(1,86400)?:30
                cooldown.write(now()+seconds*1000);error("Provider is busy. Try again after $seconds seconds; saved data is unchanged.")
            }
            check(response.status==200){if(response.status==404)"No information or cover is available for this selection." else "Provider returned HTTP ${response.status}. Saved data is unchanged."}
            if(image)require(response.contentType.substringBefore(';').lowercase() in setOf("image/jpeg","image/png","image/webp")){"Unsupported image response."}
            else require(response.contentType.startsWith("application/json",true)){"Provider returned an unexpected response."}
            return response
        }
        error("Too many provider redirects.")
    }
    private fun json(url:String):JsonObject {
        val raw=fetch(url).bytes.toString(Charsets.UTF_8)
        var depth=0;var quoted=false;var escaped=false
        raw.forEach{c->if(quoted){if(escaped)escaped=false else if(c=='\\')escaped=true else if(c=='"')quoted=false}
            else when(c){'"'->quoted=true;'{','['->{depth++;require(depth<=64)};'}',']'->depth--}}
        return Json.parseToJsonElement(raw).jsonObject
    }
    @Synchronized fun searchArtists(query:String):List<ArtistCandidate> {
        val q=query(query)
        val response=json("https://musicbrainz.org/ws/2/artist?query="+encode("artist:"+quoted(q))+"&fmt=json&limit=12")
        return response["artists"]?.jsonArray.orEmpty().take(12).mapNotNull{value->runCatching{val o=value.jsonObject
            ArtistCandidate(id(o.text("id")),o.text("name").take(300).also{require(it.isNotBlank())},
                listOf(o.text("disambiguation"),o.text("type"),o.text("country"),o["life-span"]?.jsonObject?.text("begin").orEmpty()).filter{it.isNotBlank()}.joinToString(" · ").take(1000))
        }.getOrNull()}
    }
    @Synchronized fun searchWikipediaArtists(query:String):List<ArtistCandidate> {
        val response=json("https://en.wikipedia.org/w/api.php?action=query&list=search&srnamespace=0&srlimit=12&format=json&srsearch="+encode(query(query)))
        return response["query"]?.jsonObject?.get("search")?.jsonArray.orEmpty().take(12).mapNotNull{value->runCatching{
            val o=value.jsonObject;val page=o.getValue("pageid").jsonPrimitive.long
            require(page in 1..999999999999L)
            ArtistCandidate("wikipedia:$page",o.text("title").take(300).also{require(it.isNotBlank())},plain(o.text("snippet")).take(1000))
        }.getOrNull()}
    }
    @Synchronized fun searchReleases(artist:String,album:String):List<ReleaseCandidate> {
        val q="release:"+quoted(query(album))+" AND artist:"+quoted(query(artist))
        val response=json("https://musicbrainz.org/ws/2/release?query="+encode(q)+"&fmt=json&limit=12")
        return response["releases"]?.jsonArray.orEmpty().take(12).mapNotNull{value->runCatching{val o=value.jsonObject
            ReleaseCandidate(id(o.text("id")),o.text("title").take(300),o["artist-credit"]?.jsonArray.orEmpty().joinToString(""){v->v.jsonObject.text("name")+v.jsonObject.text("joinphrase")}.take(300),
                o.text("date").take(50),o.text("country").take(50),listOf(o.text("disambiguation"),o["media"]?.jsonArray?.joinToString(", "){it.jsonObject.text("format")}.orEmpty()).filter{it.isNotBlank()}.joinToString(" · ").take(1000))
        }.getOrNull()}
    }
    private fun plain(value:String)=value.replace(Regex("<[^>]*>"),"").replace("&amp;","&").replace("&quot;","\"").replace("&#39;","'").replace("&nbsp;"," ").replace("&lt;","<").replace("&gt;",">").take(1800)
    @Synchronized fun artist(candidate:ArtistCandidate):ArtistPreview {
        if(candidate.id.startsWith("wikipedia:"))return wikipediaArtist(candidate)
        val detail=json("https://musicbrainz.org/ws/2/artist/${id(candidate.id)}?inc=url-rels&fmt=json")
        val wikidata=detail["relations"]?.jsonArray.orEmpty().mapNotNull{v->val o=v.jsonObject
            if(o.text("type")=="wikidata")o["url"]?.jsonObject?.text("resource") else null}.firstOrNull{it.matches(Regex("https://www\\.wikidata\\.org/wiki/Q[0-9]+"))}
        var profile=ArtistProfile(id(candidate.id),candidate.name,candidate.description,savedAtMs=now())
        if(wikidata==null)return ArtistPreview(profile)
        val entityId=wikidata.substringAfterLast('/')
        val entity=json("https://www.wikidata.org/wiki/Special:EntityData/$entityId.json")["entities"]?.jsonObject?.get(entityId)?.jsonObject?:return ArtistPreview(profile)
        entity["sitelinks"]?.jsonObject?.get("enwiki")?.jsonObject?.text("title")?.takeIf{it.isNotBlank()}?.let{title->
            val response=json("https://en.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&exchars=5000&format=json&formatversion=2&titles="+encode(title))
            val biography=response["query"]?.jsonObject?.get("pages")?.jsonArray?.firstOrNull()?.jsonObject?.text("extract").orEmpty().take(6000)
            profile=profile.copy(biography=biography,biographyUrl="https://en.wikipedia.org/wiki/"+encode(title.replace(' ','_')).replace("+","%20"),biographyLicense="Wikipedia contributors · CC BY-SA 4.0 (excerpt)")
        }
        return commonsImage(profile,entity)
    }
    private fun wikipediaArtist(candidate:ArtistCandidate):ArtistPreview {
        require(candidate.id.matches(Regex("wikipedia:[1-9][0-9]{0,11}"))){"Invalid Wikipedia page identity."}
        val pageId=candidate.id.substringAfter(':')
        val response=json("https://en.wikipedia.org/w/api.php?action=query&prop=extracts%7Cpageprops&exintro=1&explaintext=1&exchars=5000&format=json&formatversion=2&pageids=$pageId")
        val page=requireNotNull(response["query"]?.jsonObject?.get("pages")?.jsonArray?.firstOrNull()?.jsonObject){"This Wikipedia page is unavailable."}
        require(page["pageid"]?.jsonPrimitive?.longOrNull?.toString()==pageId&&page["ns"]?.jsonPrimitive?.intOrNull==0&&"missing" !in page){"This Wikipedia article is unavailable."}
        val props=page["pageprops"]?.jsonObject
        require(props==null||"disambiguation" !in props){"Choose a specific artist article, rather than a disambiguation page."}
        val profile=ArtistProfile(candidate.id,page.text("title").take(300),candidate.description,
            biography=page.text("extract").take(6000),biographyUrl="https://en.wikipedia.org/?curid=$pageId",
            biographyLicense="Wikipedia contributors · CC BY-SA 4.0 (excerpt)",savedAtMs=now())
        val entityId=props?.text("wikibase_item")?.takeIf{it.matches(Regex("Q[1-9][0-9]*"))}?:return ArtistPreview(profile)
        val entity=json("https://www.wikidata.org/wiki/Special:EntityData/$entityId.json")["entities"]?.jsonObject?.get(entityId)?.jsonObject?:return ArtistPreview(profile)
        return commonsImage(profile,entity)
    }
    private fun commonsImage(profile:ArtistProfile,entity:JsonObject):ArtistPreview {
        val filename=entity["claims"]?.jsonObject?.get("P18")?.jsonArray?.firstOrNull()?.jsonObject?.get("mainsnak")?.jsonObject?.get("datavalue")?.jsonObject?.text("value")
        if(filename.isNullOrBlank())return ArtistPreview(profile)
        val response=json("https://commons.wikimedia.org/w/api.php?action=query&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=512&format=json&formatversion=2&titles="+encode("File:$filename"))
        val info=response["query"]?.jsonObject?.get("pages")?.jsonArray?.firstOrNull()?.jsonObject?.get("imageinfo")?.jsonArray?.firstOrNull()?.jsonObject?:return ArtistPreview(profile)
        val metadata=info["extmetadata"]?.jsonObject?:return ArtistPreview(profile)
        fun meta(key:String)=metadata[key]?.jsonObject?.text("value").orEmpty()
        val license=plain(meta("LicenseShortName"));val credit=plain(meta("Artist")+" · "+meta("Credit"))
        if(license.isBlank()||credit.trim(' ','·').isBlank()||!Regex("(?i)(CC BY|CC0|public domain)").containsMatchIn(license))return ArtistPreview(profile)
        val imageUrl=safe(info.text("thumburl").ifBlank{info.text("url")})
        require(imageUrl.toHttpUrl().host in setOf("upload.wikimedia.org","thumb.wikimedia.org"))
        val source="https://commons.wikimedia.org/wiki/"+encode("File:"+filename).replace("+","%20")
        val bytes=fetch(imageUrl,true).bytes
        return ArtistPreview(profile.copy(imageUrl=imageUrl,imageCredit=credit,imageLicense=(license+" · "+meta("LicenseUrl")).take(2000),imageSourceUrl=source),bytes)
    }
    @Synchronized fun cover(candidate:ReleaseCandidate):CoverPreview {
        val releaseId=id(candidate.id)
        val response=json("https://coverartarchive.org/release/$releaseId")
        val image=response["images"]?.jsonArray.orEmpty().firstOrNull{it.jsonObject["front"]?.jsonPrimitive?.booleanOrNull==true}?.jsonObject?:error("This release has no front cover.")
        val thumbnail=image["thumbnails"]?.jsonObject
        val raw=thumbnail?.text("500")?.takeIf{it.isNotBlank()}?:thumbnail?.text("large")?.takeIf{it.isNotBlank()}?:image.text("image")
        val parsed=raw.toHttpUrl()
        val known=parsed.host=="coverartarchive.org"||parsed.host=="archive.org"||parsed.host.endsWith(".archive.org")
        require(known){"Unsupported cover host."}
        val url=safe(if(parsed.scheme=="http")parsed.newBuilder().scheme("https").port(443).build().toString()else raw)
        return CoverPreview(candidate,fetch(url,true).bytes,"https://musicbrainz.org/release/$releaseId",
            ("Cover Art Archive / Internet Archive · artwork rights remain with their owners. "+plain(image.text("comment"))).take(2000))
    }
}

class NetworkEnrichmentTransport:EnrichmentTransport {
    private val client=OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    override fun get(url:String,headers:Map<String,String>,limit:Int):EnrichmentResponse {
        val builder=Request.Builder().url(url);headers.forEach{(k,v)->builder.header(k,v)}
        return client.newCall(builder.build()).execute().use{response->
            val bytes=ByteArrayOutputStream()
            if(response.isSuccessful)response.body.byteStream().use{input->val buffer=ByteArray(16384);while(true){val n=input.read(buffer);if(n<0)break;require(bytes.size().toLong()+n<=limit){"Provider response is too large."};bytes.write(buffer,0,n)}}
            EnrichmentResponse(response.code,response.header("Content-Type").orEmpty(),bytes.toByteArray(),response.header("Retry-After"),response.header("Location"))
        }
    }
}
