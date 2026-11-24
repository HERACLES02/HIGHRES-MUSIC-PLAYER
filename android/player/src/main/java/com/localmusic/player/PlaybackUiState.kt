package com.localmusic.player

import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject

/** The browse/navigation part of the service's session-extras "state" JSON. Position and sleep-remaining live in the fast clock. */
@Immutable
data class PlaybackSnapshot(
    val ready: Boolean = false,
    val duration: Long = 0,
    val playing: Boolean = false,
    val playWhenReady: Boolean = false,
    val error: String = "",
    val currentId: String = "",
    val collectionLabel: String = "Library",
    val collectionNumber: Int = 0,
    val collectionTotal: Int = 0,
    val clipping: Boolean = false,
    val manual: List<String> = emptyList(),
    val manualIds: List<String> = emptyList(),
    val manualCount: Int = 0,
    val sequence: List<String> = emptyList(),
    val sequenceIds: List<String> = emptyList(),
    val sequenceCount: Int = 0,
    val sleepMode: String = "OFF",
) {
    companion object {
        private fun JSONObject.strings(key: String): List<String> =
            optJSONArray(key)?.let { a: JSONArray -> List(a.length()) { a.getString(it) } }.orEmpty()
        fun from(json: JSONObject) = PlaybackSnapshot(
            ready = json.optBoolean("ready"), duration = json.optLong("duration"),
            playing = json.optBoolean("playing"), playWhenReady = json.optBoolean("playWhenReady"),
            error = json.optString("error"), currentId = json.optString("currentId"),
            collectionLabel = json.optString("collectionLabel", "Library"),
            collectionNumber = json.optInt("collectionNumber"), collectionTotal = json.optInt("collectionTotal"),
            clipping = json.optBoolean("clipping"),
            manual = json.strings("manual"), manualIds = json.strings("manualIds"), manualCount = json.optInt("manualCount"),
            sequence = json.strings("sequence"), sequenceIds = json.strings("sequenceIds"), sequenceCount = json.optInt("sequenceCount"),
            sleepMode = json.optString("sleepMode", "OFF"),
        )
    }
}

/** Keeps the fast playback clock separate from the browse/navigation snapshot. */
@Stable
class PlaybackUiState {
    var snapshot by mutableStateOf(PlaybackSnapshot());private set
    val position=mutableLongStateOf(0)
    val sleepRemaining=mutableLongStateOf(0)
    fun accept(value:JSONObject) {
        position.longValue=value.optLong("position")
        sleepRemaining.longValue=value.optLong("sleepRemaining")
        // mutableStateOf's structural policy ignores an equal snapshot, so an unchanged tick recomposes nothing.
        snapshot=PlaybackSnapshot.from(value)
    }
}
