package com.localmusic.player

import android.content.Context
import android.net.Network
import android.net.Uri
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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

    suspend fun connect() = withContext(Dispatchers.IO) {
        request(JSONObject().put("operation", "connect"))
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        request(JSONObject().put("operation", "stop").put("disconnect", true))
    }

    suspend fun search(query: String): List<FixtureTrack> = withContext(Dispatchers.IO) {
        tracks(request(JSONObject().put("operation", "search").put("query", query)))
    }

    suspend fun wishlistPoll(queries: List<String>): Pair<String, List<FixtureTrack>>? =
        withContext(Dispatchers.IO) {
            val result = request(JSONObject().put("operation", "wishlistPoll").put("queries", JSONArray(queries)))
            (result as? JSONObject)?.let { it.getString("query") to tracks(it.getJSONArray("tracks")) }
        }

    suspend fun folder(track: FixtureTrack): List<FixtureTrack> = withContext(Dispatchers.IO) {
        tracks(request(track.encode().put("operation", "folder").put("folder", track.folder)))
    }

    suspend fun sharePreview(root: File): List<FixtureSharedFile> = withContext(Dispatchers.IO) {
        val rows = shareRequest("sharePreview", root) as JSONArray
        List(rows.length()) { FixtureSharedFile.decode(rows.getJSONObject(it)) }
    }

    suspend fun shareOn(root: File) = withContext(Dispatchers.IO) { shareRequest("shareOn", root) }

    suspend fun shareOff() = withContext(Dispatchers.IO) { shareRequest("shareOff") }

    suspend fun shareStatus(): FixtureShareStatus = withContext(Dispatchers.IO) {
        FixtureShareStatus.decode(shareRequest("shareStatus") as JSONObject)
    }

    private fun shareRequest(operation: String, root: File? = null) =
        request(JSONObject().put("operation", operation).put("root", root?.absolutePath))

    suspend fun chatPoll(): JSONObject? = withContext(Dispatchers.IO) {
        request(JSONObject().put("operation", "chatPoll")) as? JSONObject
    }

    suspend fun chatAction(operation: String, generation: Long, name: String, text: String? = null) =
        withContext(Dispatchers.IO) {
            request(JSONObject().put("operation", operation).put("generation", generation)
                .put("name", name).put("text", text))
        }

    fun stagedFile(track: FixtureTrack, files: File, sha256: String): File {
        require(fixtureHashPattern.matches(sha256))
        require(track.name.endsWith(".flac", ignoreCase = true) && track.name.none { it == '\u0000' || it == ':' })
        val identity = MessageDigest.getInstance("SHA-256").digest(track.encode().toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(files, "peer-search-$sha256-$identity"), track.name)
    }

    fun verifiedCopy(track: FixtureTrack, stage: File, sha256: String): Boolean =
        stage.isFile && stage.length() == track.size && stage.inputStream().use { it.matchesFixture(sha256) }

    suspend fun fetch(
        track: FixtureTrack,
        files: File,
        sha256: String,
        wifi: () -> Network?,
        progress: suspend (String, Int?) -> Unit,
    ): File {
        val stage = stagedFile(track, files, sha256)
        val directory = stage.parentFile!!
        check(directory.isDirectory || directory.mkdir()) { "Could not create private staging." }
        if (verifiedCopy(track, stage, sha256)) return stage
        check(!stage.exists() || stage.delete()) { "Could not clear the invalid private copy." }
        val partial = File(directory, "${track.name}.part")
        val download = track.encode().put("operation", "download").put("directory", directory.absolutePath)
        val poll = JSONObject().put("operation", "poll")
        for (attempt in 0..3) {
            val waitDeadline = SystemClock.elapsedRealtime() + STALL_MS
            while (wifi() == null) {
                progress("Waiting for Wi-Fi. ${partial.length()} bytes saved.", null)
                check(SystemClock.elapsedRealtime() < waitDeadline) { "Wi-Fi is unavailable. Tap Resume when connected." }
                delay(POLL_MS)
            }
            if (attempt > 1) delay(1_000L * attempt)
            val network = wifi() ?: continue
            var keepSession = false
            var sizeChanged = false
            try {
                request(download)
                var lastBytes = partial.length()
                var lastMovement = SystemClock.elapsedRealtime()
                while (wifi() == network) {
                    val update = request(poll) as JSONObject
                    val offset = if (update.isNull("offset")) null else update.getLong("offset")
                    when (update.getString("status")) {
                        "complete" -> {
                            keepSession = true
                            break
                        }
                        "sizeChanged" -> {
                            sizeChanged = true
                            break
                        }
                    }
                    val received = partial.length()
                    if (received != lastBytes) {
                        lastBytes = received
                        lastMovement = SystemClock.elapsedRealtime()
                    }
                    check(SystemClock.elapsedRealtime() - lastMovement < STALL_MS) {
                        "The uploader stopped responding."
                    }
                    if (offset == null) {
                        val position = update.optLong("queuePosition", 0)
                        val waiting = if (position > 0) "Queued by ${track.user} at position $position."
                            else "Waiting for ${track.user}."
                        progress("$waiting $received bytes saved.", null)
                    } else {
                        val resumed = if (offset > 0) " (resumed at $offset)" else ""
                        progress(
                            "Downloading $received / ${track.size} bytes$resumed",
                            (received * 100 / track.size).toInt(),
                        )
                    }
                    delay(POLL_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                if (attempt == 3) throw e
            } finally {
                withContext(NonCancellable) {
                    request(JSONObject().put("operation", "stop").put("disconnect", !keepSession))
                }
            }
            if (sizeChanged || keepSession || stage.exists()) {
                if (!sizeChanged && verifiedCopy(track, stage, sha256)) return stage
                check(!partial.exists() || partial.delete()) { "Could not clear the invalid partial." }
                check(!stage.exists() || stage.delete()) { "Could not clear the invalid private copy." }
                throw IOException("The selected FLAC failed size or SHA-256 verification.")
            }
        }
        error("Connection interrupted. ${partial.length()} bytes saved. Tap Resume.")
    }

    private const val STALL_MS = 90_000L
    private const val POLL_MS = 250L

    private fun tracks(value: Any): List<FixtureTrack> = (value as JSONArray).let { rows ->
        List(rows.length()) { FixtureTrack.decode(rows.getJSONObject(it)) }.distinct()
    }

    private fun request(request: JSONObject): Any {
        check(available) { "Build the Peer Test Rust library first." }
        val response = JSONObject(nativeRequest(request.toString()))
        check(!response.has("error")) { response.getString("error") }
        return response.get("result")
    }

    private external fun nativeRequest(request: String): String
}

internal data class PendingFixtureTransfer(
    val tree: Uri, val sha256: String, val track: FixtureTrack, val fromSoulseekTab: Boolean = false,
) {
    fun save(context: Context) {
        val json = JSONObject().put("tree", tree.toString()).put("sha256", sha256).put("track", track.encode())
            .put("fromSoulseekTab", fromSoulseekTab)
        check(settings(context).edit().putString(key(fromSoulseekTab), json.toString()).commit()) {
            "Could not save the download for Resume."
        }
    }

    companion object {
        private const val KEY = "pending-rust-transfer"
        private fun key(fromSoulseekTab: Boolean) = if (fromSoulseekTab) "pending-soulseek-tab-transfer" else KEY

        private fun settings(context: Context) = context.getSharedPreferences("peer-fixture", Context.MODE_PRIVATE)

        fun read(context: Context, fromSoulseekTab: Boolean = false): PendingFixtureTransfer? =
            settings(context).getString(key(fromSoulseekTab), null)?.let { saved ->
            val json = JSONObject(saved)
            PendingFixtureTransfer(Uri.parse(json.getString("tree")), json.getString("sha256"),
                FixtureTrack.decode(json.getJSONObject("track")), json.optBoolean("fromSoulseekTab"))
        }

        fun clear(context: Context, fromSoulseekTab: Boolean = false) {
            check(settings(context).edit().remove(key(fromSoulseekTab)).commit()) {
                "Could not clear the saved download."
            }
        }
    }
}
