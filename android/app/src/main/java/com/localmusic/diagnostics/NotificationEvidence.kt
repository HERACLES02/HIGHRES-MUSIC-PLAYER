package com.localmusic.diagnostics

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Only this app's notification settings/posted IDs. No listener permission or other apps' notifications. */
object NotificationEvidence {
    const val CHANNEL = "direct-usb-experiment"
    const val STREAM_ID = 505
    private const val TEST_ID = 506
    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
    fun ensureChannel(context: Context) {
        manager(context).createNotificationChannel(NotificationChannel(CHANNEL, "Direct USB experiment", NotificationManager.IMPORTANCE_LOW))
    }
    fun snapshot(context: Context): JSONObject {
        val result = JSONObject().put("capturedAt", Instant.now().toString())
            .put("meaning", "Android's own-app notification state, not proof the shade displayed it. No USB connection or playback is started.")
        try {
            val service = manager(context)
            val channel = service.getNotificationChannel(CHANNEL)
            val active = JSONArray()
            service.activeNotifications.forEach {
                active.put(JSONObject().put("id", it.id).put("channelId", it.notification.channelId)
                    .put("flags", it.notification.flags).put("actionCount", it.notification.actions?.size ?: 0)
                    .put("visibility", it.notification.visibility).put("category", it.notification.category ?: JSONObject.NULL)
                    .put("hasMediaSessionToken", it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
                    .put("ongoing", it.isOngoing).put("postedAt", Instant.ofEpochMilli(it.postTime).toString()))
            }
            result.put("notificationsEnabled", service.areNotificationsEnabled())
                .put("channelExists", channel != null)
                .put("channelImportance", channel?.importance ?: JSONObject.NULL)
                .put("channelLockscreenVisibility", channel?.lockscreenVisibility ?: JSONObject.NULL)
                .put("channelGroupBlocked", channel?.group?.let { service.getNotificationChannelGroup(it)?.isBlocked } ?: JSONObject.NULL)
                .put("activeNotifications", active)
                .put("directNotificationPresent", (0 until active.length()).any { active.getJSONObject(it).optInt("id") == STREAM_ID })
                .put("testNotificationPresent", (0 until active.length()).any { active.getJSONObject(it).optInt("id") == TEST_ID })
                .put("runtimePermissionApplicable", Build.VERSION.SDK_INT >= 33)
                .put("runtimePermissionGranted", if (Build.VERSION.SDK_INT >= 33) context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else JSONObject.NULL)
                .put("readError", JSONObject.NULL)
        } catch (error: Exception) { result.put("readError", error.javaClass.simpleName + ": " + error.message) }
        val attempt = context.getSharedPreferences("notification-evidence", Context.MODE_PRIVATE).getString("lastTestAttempt", null)
        result.put("lastTestAttempt", attempt?.let { JSONObject(it) } ?: JSONObject.NULL)
        return result
    }
    fun postTest(context: Context): String {
        val attempt = JSONObject().put("at", Instant.now().toString()).put("notificationId", TEST_ID)
        val outcome = try {
            ensureChannel(context)
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                attempt.put("notifyCallReturned", false).put("error", "Notification runtime permission not granted")
                context.getSharedPreferences("notification-evidence", Context.MODE_PRIVATE).edit().putString("lastTestAttempt", attempt.toString()).apply()
                return "Notification permission is needed before posting the test. Save notification diagnosis."
            }
            val open = PendingIntent.getActivity(context, 4, Intent(context, DirectTestActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            manager(context).notify(TEST_ID, Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Music USB Probe notification check")
                .setContentText("Test notification only; no audio. Tap to return to the probe.")
                .setContentIntent(open).setAutoCancel(true).build())
            attempt.put("notifyCallReturned", true)
            "Test notification requested. Check the shade, then save notification diagnosis. A returned notify call does not guarantee visibility."
        } catch (error: Exception) {
            attempt.put("notifyCallReturned", false).put("error", error.javaClass.simpleName + ": " + error.message)
            "Notification request failed. Save notification diagnosis."
        }
        context.getSharedPreferences("notification-evidence", Context.MODE_PRIVATE).edit().putString("lastTestAttempt", attempt.toString()).apply()
        return outcome
    }
    fun clearTest(context: Context) { manager(context).cancel(TEST_ID) }
    fun channelSettings(context: Context) = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
    fun report(context: Context) = JSONObject().put("schemaVersion", 1).put("appVersion", StreamEvidence.VERSION)
        .put("exportedAt", Instant.now().toString()).put("phone", JSONObject().put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT).put("buildDisplay", Build.DISPLAY))
        .put("streamActiveInThisProcess", UsbStreamService.state.busy).put("streamFixture", UsbStreamService.state.fixture)
        .put("mediaSession", JSONObject(UsbStreamService.mediaSessionState))
        .put("notificationState", snapshot(context))
}
