package com.localmusic.diagnostics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Evidence only. Playback is owned exclusively by BaselineService. Main-thread access. */
object BaselineEvidence {
    private const val LIMIT = 1000
    var revision by mutableStateOf(0)
        private set
    private var root: JSONObject? = null

    private fun data(context: Context): JSONObject {
        root?.let { return it }
        val stored = context.getSharedPreferences("baseline-evidence", Context.MODE_PRIVATE).getString("report", null)
        return (runCatching { JSONObject(stored ?: "") }.getOrNull() ?: JSONObject()
            .put("schemaVersion", 1).put("events", JSONArray()).put("droppedEvents", 0)).also { root = it }
    }

    fun event(context: Context, kind: String, values: JSONObject = JSONObject()) {
        val data = data(context)
        val events = data.getJSONArray("events")
        if (events.length() >= LIMIT) {
            events.remove(0)
            data.put("droppedEvents", data.optInt("droppedEvents") + 1)
        }
        events.put(values.put("event", kind).put("at", Instant.now().toString()).put("elapsedRealtimeMs", SystemClock.elapsedRealtime()))
        context.getSharedPreferences("baseline-evidence", Context.MODE_PRIVATE).edit().putString("report", data.toString()).apply()
        revision++
    }

    fun snapshot(context: Context): String = JSONObject(data(context).toString())
        .put("exportedAt", Instant.now().toString()).put("appVersion", "0.6.0-channel-check")
        .put("fixtureManifest", JSONObject(context.assets.open("fixtures-manifest.json").bufferedReader().use { it.readText() }))
        .put("channelFixtureManifest", JSONObject(context.assets.open("channel-fixtures-manifest.json").bufferedReader().use { it.readText() }))
        .put("phone", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("androidRelease", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT).put("buildDisplay", Build.DISPLAY))
        .put("scope", "Normal Android route only. AudioTrack configuration is an app-side observation, not the physical USB stream.")
        .put("unavailableMeasurements", JSONArray(listOf("Physical USB rate and bit depth", "Bus sample delivery", "Cable internal DSP", "Direct USB transfer statistics")))
        .put("directUsb", "Unavailable: asynchronous clocking unresolved; safe hardware volume SET/readback and native streaming not implemented or tested.")
        .toString(2)

    fun latestOutput(context: Context): String {
        val events = data(context).getJSONArray("events")
        val found = mutableSetOf<String>()
        val lines = mutableListOf<String>()
        for (index in events.length() - 1 downTo 0) {
            val item = events.getJSONObject(index)
            val kind = item.optString("event")
            if (kind == "fixture_requested" || kind == "service_started") break
            if (kind in listOf("audio_track_configuration", "android_route_changed", "playback_error") && found.add(kind)) {
                lines.add(item.toString(2))
            }
        }
        return lines.joinToString("\n").ifBlank { "No current output observation yet. Earlier test results remain in the export." }
    }
}
