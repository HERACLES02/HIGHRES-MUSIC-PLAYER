package com.localmusic.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Read-only postmortem evidence. Never opens USB or starts playback. */
object StreamEvidence {
    const val VERSION = "0.10.0-media3-direct"

    private fun read(context: Context, name: String): JSONObject? = runCatching {
        context.openFileInput(name).bufferedReader().use { JSONObject(it.readText()) }
    }.getOrNull()

    fun bundle(context: Context): JSONObject {
        val completed = read(context, "native-stream-last.json")
        val checkpoint = read(context, "native-stream-incomplete.json")
        val checkpointAt = checkpoint?.optString("capturedAt").orEmpty()
        val completedAt = completed?.optString("exportedAt").orEmpty()
        val newerCheckpoint = checkpointAt.isNotBlank() &&
            (completedAt.isBlank() || Instant.parse(checkpointAt).isAfter(Instant.parse(completedAt)))
        val activity = context.getSystemService(ActivityManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val exits = JSONArray()
        var exitReadError: String? = null
        try {
            // API 30: only this app's own process history; no logs or trace files.
            activity.getHistoricalProcessExitReasons(context.packageName, 0, 10).forEach {
                exits.put(JSONObject().put("timestamp", Instant.ofEpochMilli(it.timestamp).toString())
                    .put("pid", it.pid).put("processName", it.processName)
                    .put("reasonCode", it.reason).put("status", it.status)
                    .put("importance", it.importance).put("description", it.description ?: JSONObject.NULL))
            }
        } catch (error: Exception) { exitReadError = error.javaClass.simpleName }
        return JSONObject().put("schemaVersion", 1).put("appVersion", VERSION)
            .put("exportedAt", Instant.now().toString())
            .put("currentPid", Process.myPid()).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("batteryOptimizationExempt", power.isIgnoringBatteryOptimizations(context.packageName))
            .put("backgroundRestricted", activity.isBackgroundRestricted)
            .put("lowMemoryKillReportingSupported", ActivityManager.isLowMemoryKillReportSupported())
            .put("latestEvidence", when {
                newerCheckpoint -> "checkpoint_newer_than_completed_report"
                completed != null -> "completed_report"
                checkpoint != null -> "checkpoint_only"
                else -> "no_saved_stream_evidence"
            })
            .put("completedReportAt", completedAt).put("checkpointAt", checkpointAt)
            .put("completedReport", completed ?: JSONObject.NULL)
            .put("checkpoint", checkpoint ?: JSONObject.NULL)
            .put("processExits", exits).put("processExitReadError", exitReadError ?: JSONObject.NULL)
            .put("notificationState", NotificationEvidence.snapshot(context))
            .put("interpretation", "A newer checkpoint without a matching final report is incomplete evidence, not proof of a battery kill. Exit records may be absent or unrelated; compare timestamps and process IDs. Installing an update may itself add an exit record. No automatic restart.")
    }

    fun summary(bundle: JSONObject): String = when (bundle.getString("latestEvidence")) {
        "checkpoint_newer_than_completed_report", "checkpoint_only" ->
            "Latest saved checkpoint: ${bundle.optString("checkpointAt")}. No newer final report. The completed-report button may export an older run. Save background-stop diagnosis."
        "completed_report" -> "Latest final report: ${bundle.optString("completedReportAt")}."
        else -> "No saved stream reports yet."
    }
}
