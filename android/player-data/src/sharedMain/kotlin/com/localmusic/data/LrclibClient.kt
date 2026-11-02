package com.localmusic.data

import com.localmusic.core.LrcParser
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class LyricsCandidate(val id: Long, val title: String, val artist: String, val album: String,
    val durationSeconds: Double, val instrumental: Boolean, val plain: String?, val synchronized: String?) {
    val text: String get() = synchronized?.takeIf { it.isNotBlank() } ?: plain.orEmpty()
    val sourceUrl: String get() = "https://lrclib.net/api/get/$id"
}
interface LyricsCooldown { fun read(): Long; fun write(untilMs: Long) }
data class LyricsHttpResponse(val status: Int, val contentType: String, val body: String, val retryAfter: String? = null)
fun interface LyricsTransport { fun request(url: String, headers: Map<String, String>): LyricsHttpResponse }
private class OkHttpLyricsTransport : LyricsTransport {
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    override fun request(url: String, headers: Map<String, String>): LyricsHttpResponse {
        val builder = Request.Builder().url(url)
        headers.forEach { (key, value) -> builder.header(key, value) }
        return client.newCall(builder.build()).execute().use { response ->
            val body = ByteArrayOutputStream()
            if (response.isSuccessful) response.body.byteStream().use { input ->
                val buffer = ByteArray(32768)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(body.size() + n <= 2_000_000) { "Lyrics response is too large" }; body.write(buffer, 0, n) }
            }
            LyricsHttpResponse(response.code, response.header("Content-Type").orEmpty(), body.toString("UTF-8"), response.header("Retry-After"))
        }
    }
}

/** Only an explicit caller search reaches the network. Never reads/writes the music repository. */
class LrclibClient(private val cooldown: LyricsCooldown, private val transport: LyricsTransport = OkHttpLyricsTransport(),
    private val now: () -> Long = System::currentTimeMillis) {
    private var nextRequestAt = 0L
    @Synchronized fun search(query: String): List<LyricsCandidate> {
        require(query.isNotBlank() && query.length <= 300) { "Enter a query of 1–300 characters" }
        val time = now()
        val wait = maxOf(cooldown.read(), nextRequestAt) - time
        check(wait <= 0) { "LRCLIB asks you to wait ${((wait + 999) / 1000)} seconds before searching again." }
        val url = "https://lrclib.net/api/search?q=" + URLEncoder.encode(query.trim(), "UTF-8")
        nextRequestAt = time + 350
        val response = transport.request(url, mapOf("Accept" to "application/json",
            "User-Agent" to "LocalMusic/0.2.0 (+https://github.com/HERACLES02/HIGHRES-MUSIC-PLAYER)"))
        if (response.status == 429 || response.status == 503) {
            val seconds = response.retryAfter?.toLongOrNull()?.coerceAtLeast(1) ?: 30L
            val current = now()
            val room = (Long.MAX_VALUE - current).coerceAtLeast(0)
            val delay = if (seconds > room / 1000) room else seconds * 1000
            val jitterLimit = minOf(delay / 3, 30_000L, room - delay)
            val until = current + delay + kotlin.random.Random.nextLong(0, jitterLimit + 1)
            cooldown.write(until)
            error("LRCLIB is busy. Wait at least $seconds seconds; your saved lyrics are unchanged.")
        }
        check(response.status == 200) { "LRCLIB returned HTTP ${response.status}. Saved lyrics are unchanged." }
        check(response.contentType.startsWith("application/json", true)) { "LRCLIB returned an unexpected response. Saved lyrics are unchanged." }
        return decode(response.body)
    }
    companion object {
        fun decode(body: String): List<LyricsCandidate> {
            require(body.length <= 2_000_000)
            return Json.parseToJsonElement(body).jsonArray.take(50).mapNotNull { element ->
                runCatching {
                    val o = element.jsonObject
                    fun text(key: String) = o[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                    val id = o.getValue("id").jsonPrimitive.long; require(id >= 0)
                    val duration = o.getValue("duration").jsonPrimitive.double; require(duration.isFinite() && duration >= 0)
                    val plain = text("plainLyrics"); val synced = text("syncedLyrics")
                    require((plain?.length ?: 0) <= LrcParser.MAX_CHARS && (synced?.length ?: 0) <= LrcParser.MAX_CHARS)
                    LyricsCandidate(id, text("trackName") ?: text("name").orEmpty(), text("artistName").orEmpty(), text("albumName").orEmpty(), duration,
                        o["instrumental"]?.jsonPrimitive?.booleanOrNull ?: false, plain, synced)
                }.getOrNull()
            }
        }
    }
}
