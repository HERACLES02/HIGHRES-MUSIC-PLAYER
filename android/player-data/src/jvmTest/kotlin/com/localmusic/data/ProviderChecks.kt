package com.localmusic.data

import kotlinx.serialization.json.*

fun providerChecks() {
    var checks = 0
    fun expect(value: Boolean) { checks++; check(value) { "Provider check $checks failed" } }
    val json = buildJsonArray {
        add(buildJsonObject { put("id", 1); put("trackName", "Test recording"); put("artistName", "Test Artist"); put("albumName", "Album"); put("duration", 120.5)
            put("instrumental", false); put("plainLyrics", "Own test line"); put("syncedLyrics", "[00:01.250]Own test line") })
        add(buildJsonObject { put("id", 2); put("duration", 120); put("instrumental", true); put("plainLyrics", JsonNull); put("syncedLyrics", JsonNull) })
        add(buildJsonObject { put("id", -1); put("duration", 0) })
    }.toString()
    val records = LrclibClient.decode(json)
    expect(records.size == 2 && records[0].text.startsWith("[00:01.250]"))
    expect(records[1].instrumental && records[1].text.isEmpty())
    expect(records[0].sourceUrl == "https://lrclib.net/api/get/1")
    expect(runCatching { LrclibClient.decode("{invalid") }.isFailure)
    expect(runCatching { LrclibClient.decode("x".repeat(2_000_001)) }.isFailure)
    var until = 0L
    val cooldown = object : LyricsCooldown { override fun read() = until; override fun write(untilMs: Long) { until = untilMs } }
    var time = 10000L; var calls = 0; var response = LyricsHttpResponse(200, "application/json; charset=utf-8", json)
    val transport = LyricsTransport { url, headers ->
        calls++; expect(url.startsWith("https://lrclib.net/api/search?q=")); expect(headers["User-Agent"]?.startsWith("LocalMusic/") == true)
        expect(url.contains("%26")); response
    }
    val client = LrclibClient(cooldown, transport) { time }
    expect(client.search("A&B বাংলা").size == 2 && calls == 1)
    expect(runCatching { client.search("A&B") }.isFailure && calls == 1)
    time += 1000; response = LyricsHttpResponse(429, "text/html", "Not JSON", "120000")
    expect(runCatching { client.search("A&B") }.isFailure)
    expect(until >= time + 120000000L)
    val restarted = LrclibClient(cooldown, transport) { time }
    val beforeCalls = calls
    expect(runCatching { restarted.search("A&B") }.isFailure && calls == beforeCalls)
    time = until + 1; response = LyricsHttpResponse(200, "text/html", "Not JSON")
    expect(runCatching { restarted.search("A&B") }.isFailure)
    time += 1000; response = LyricsHttpResponse(503, "text/plain", "Busy", "2")
    expect(runCatching { restarted.search("A&B") }.isFailure && until >= time + 2000)
    println("LRCLIB fixture-transport checks passed: $checks assertions (no live provider calls).")
}
