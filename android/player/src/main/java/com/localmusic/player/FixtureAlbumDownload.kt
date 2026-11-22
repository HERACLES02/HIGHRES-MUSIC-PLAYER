package com.localmusic.player

import android.content.Context
import android.net.Uri
import com.localmusic.core.TrackId
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal enum class FixtureSaveStatus {
    QUEUED, DOWNLOAD_FAILED, SAVING, SAVE_FAILED, CONFLICT, READY, DUPLICATE, SKIPPED;

    val finished: Boolean get() = this == READY || this == DUPLICATE || this == SKIPPED
}

internal data class FixtureAlbumTrack(
    val track: FixtureTrack,
    val legacySha256: String? = null,
    val status: FixtureSaveStatus = FixtureSaveStatus.QUEUED,
    val message: String = "",
    val libraryTrack: TrackId? = null,
    val verifiedSha256: String? = null,
) {
    fun encode() = JSONObject().put("track", track.encode()).put("sha256", legacySha256)
        .put("verifiedSha256", verifiedSha256).put("status", status.name).put("message", message).put("libraryTrack", libraryTrack?.value?.toString())

    companion object {
        fun decode(json: JSONObject) = FixtureAlbumTrack(
            decodeFixtureTrack(json.getJSONObject("track")), json.optString("sha256").takeIf(String::isNotEmpty),
            FixtureSaveStatus.valueOf(json.getString("status")), json.getString("message"),
            json.optString("libraryTrack").takeIf(String::isNotEmpty)?.let { TrackId(UUID.fromString(it)) },
            json.optString("verifiedSha256").takeIf(String::isNotEmpty),
        )
    }
}

internal data class FixtureAlbumDownload(val tree: Uri, val tracks: List<FixtureAlbumTrack>) {
    init {
        require(tracks.isNotEmpty() && tracks.all {
            it.track.user == tracks.first().track.user && it.track.folder == tracks.first().track.folder &&
                (it.legacySha256 == null || fixtureHashPattern.matches(it.legacySha256)) &&
                (it.verifiedSha256 == null || fixtureHashPattern.matches(it.verifiedSha256))
        })
    }

    val folder: String get() = tracks.first().track.folder
    val uploader: String get() = tracks.first().track.user

    fun encode() = JSONObject().put("tree", tree.toString()).put("tracks", JSONArray(tracks.map { it.encode() }))

    fun save(context: Context) {
        check(settings(context).edit().putString(KEY, encode().toString()).commit()) {
            "Could not save the album download."
        }
    }

    companion object {
        private const val KEY = "album-download"
        private fun settings(context: Context) = context.getSharedPreferences("controlled-soulseek", Context.MODE_PRIVATE)

        fun read(context: Context): FixtureAlbumDownload? =
            settings(context).getString(KEY, null)?.let { decode(JSONObject(it)) }
                ?: PendingFixtureTransfer.read(context, fromSoulseekTab = true)?.let {
                    FixtureAlbumDownload(it.tree, listOf(FixtureAlbumTrack(it.track, it.sha256)))
                }

        fun decode(json: JSONObject): FixtureAlbumDownload {
            val tracks = json.getJSONArray("tracks")
            return FixtureAlbumDownload(Uri.parse(json.getString("tree")),
                List(tracks.length()) { FixtureAlbumTrack.decode(tracks.getJSONObject(it)) })
        }
    }
}

internal enum class FixtureTrackAction { RETRY_SAVE, SAVE_SEPARATE, SKIP, RETRY_DOWNLOAD, DISCARD }
