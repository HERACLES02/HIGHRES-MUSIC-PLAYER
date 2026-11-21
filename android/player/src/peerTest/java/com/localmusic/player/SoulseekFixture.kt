package com.localmusic.player

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class FixtureTrack(val user: String, val path: String, val size: Long) {
    val folder: String get() = path.substringBeforeLast('\\', path.substringBeforeLast('/', ""))
    val name: String get() = path.substringAfterLast('\\').substringAfterLast('/')

    fun encode() = JSONObject().put("user", user).put("path", path).put("size", size)

    companion object {
        fun decode(value: JSONObject) = FixtureTrack(
            value.getString("user"), value.getString("path"), value.getLong("size")
        )
    }
}

internal object SoulseekFixture {
    val available = try {
        System.loadLibrary("localmusic_peer_search")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    suspend fun search(query: String): List<FixtureTrack> = withContext(Dispatchers.IO) {
        tracks(request(JSONObject().put("operation", "search").put("query", query)))
    }

    suspend fun folder(track: FixtureTrack): List<FixtureTrack> = withContext(Dispatchers.IO) {
        tracks(request(track.encode().put("operation", "folder").put("folder", track.folder)))
    }

    fun fetch(track: FixtureTrack, files: File, sha256: String): File {
        require(fixtureHashPattern.matches(sha256))
        val identity = MessageDigest.getInstance("SHA-256").digest(track.encode().toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val directory = File(files, "peer-search-$sha256-$identity")
        check(directory.isDirectory || directory.mkdir()) { "Could not create private staging." }
        val stage = File(directory, track.name)
        if (stage.isFile && stage.length() == track.size && stage.inputStream().use { it.matchesFixture(sha256) }) {
            return stage
        }
        check(!stage.exists() || stage.delete()) { "Could not clear the invalid private copy." }
        request(track.encode().put("operation", "download").put("directory", directory.absolutePath))
        check(stage.isFile && stage.length() == track.size && stage.inputStream().use { it.matchesFixture(sha256) }) {
            "The selected FLAC failed size or SHA-256 verification."
        }
        return stage
    }

    private fun tracks(value: Any): List<FixtureTrack> = (value as JSONArray).let { rows ->
        List(rows.length()) { FixtureTrack.decode(rows.getJSONObject(it)) }.distinct()
    }

    @Synchronized
    private fun request(request: JSONObject): Any {
        check(available) { "Build the Peer Test Rust library first." }
        val response = JSONObject(nativeRequest(request.toString()))
        check(!response.has("error")) { response.getString("error") }
        return response.get("result")
    }

    private external fun nativeRequest(request: String): String
}
