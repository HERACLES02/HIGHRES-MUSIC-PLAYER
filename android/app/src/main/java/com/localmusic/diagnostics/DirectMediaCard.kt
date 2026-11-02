package com.localmusic.diagnostics

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/** Diagnostic transport controls only. Owns no player, decoder, audio route or volume processing. */
class DirectMediaCard(private val context: Context, fixture: String,
    private val pause: () -> Unit, private val resume: () -> Unit, private val stop: () -> Unit) {
    private val session = MediaSession(context, "LocalMusicDirectDiagnostic")
    private var released = false
    private var lastLayout = -1
    private var phase = 1
    private val open = PendingIntent.getActivity(context, 3, Intent(context, DirectTestActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    init {
        try {
        session.setCallback(object : MediaSession.Callback() {
            override fun onPause() { if (!released) pause() }
            override fun onPlay() { if (!released) resume() }
            override fun onStop() { if (!released) stop() }
        }, Handler(Looper.getMainLooper()))
        session.setSessionActivity(open)
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Direct USB test")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Local Music · quiet fixture")
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, fixture)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 6000).build())
        session.isActive = true
        update(JSONObject(), force = true)
        } catch (error: Exception) {
            released = true
            session.release()
            throw error
        }
    }
    private fun action(request: Int, name: String) = PendingIntent.getService(context, request,
        Intent(context, UsbStreamService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun notification(): Notification {
        val toggle = phase == 2 || phase == 3
        val builder = Notification.Builder(context, NotificationEvidence.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Experimental Direct USB")
            .setContentText("Quiet fixture test · clocking unvalidated").setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true).setOngoing(true)
        if (toggle) builder.addAction(Notification.Action.Builder(
            if (phase == 3) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
            if (phase == 3) "Resume" else "Pause", action(5, if (phase == 3) UsbStreamService.RESUME else UsbStreamService.PAUSE)).build())
        builder.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
            "Stop and release USB", action(2, UsbStreamService.STOP)).build())
        val style = Notification.MediaStyle().setMediaSession(session.sessionToken)
        if (toggle) style.setShowActionsInCompactView(0, 1) else style.setShowActionsInCompactView(0)
        return builder.setStyle(style).build()
    }

    /** Uses native engine observations; not a DAC render timestamp or a second playback timer. */
    fun update(stats: JSONObject, force: Boolean = false): Notification? {
        if (released) return null
        phase = stats.optInt("phase", 1)
        val playing = phase == 2
        val paused = phase == 3
        val state = when (phase) {
            2 -> PlaybackState.STATE_PLAYING
            3 -> PlaybackState.STATE_PAUSED
            5 -> PlaybackState.STATE_STOPPED
            6 -> PlaybackState.STATE_ERROR
            else -> PlaybackState.STATE_BUFFERING
        }
        val rate = UsbStreamService.state.fixture.substringAfterLast('-').substringBefore('.').toLongOrNull() ?: 96000
        val position = ((stats.optLong("positionFrames") * 1000 / rate) % 6000).coerceAtLeast(0)
        val actions = PlaybackState.ACTION_STOP or if (playing || paused)
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE else 0L
        session.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(state, position, if (playing) 1f else 0f).build())
        val layout = when { paused -> 3; playing -> 2; else -> 1 }
        if (!force && layout == lastLayout) return null
        lastLayout = layout
        return notification()
    }
    fun evidence() = JSONObject().put("created", true).put("released", released)
        .put("requestedVisibility", "PUBLIC").put("style", "framework MediaStyle")
        .put("lastNativePhase", phase).put("positionMeaning", "Approximate repeating source position; not measured DAC time")
    fun close() {
        if (released) return
        released = true
        session.isActive = false
        session.release()
    }
}
