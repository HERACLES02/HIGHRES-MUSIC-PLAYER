@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.localmusic.diagnostics

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject

/** One foreground playback authority for bundled diagnostic fixtures, never USB writes. */
class BaselineService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val handler = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var preset = "Unknown"
    private var armed = false
    private val runId = java.util.UUID.randomUUID().toString()
    private var testId: String? = null
    private var closed = false
    private val sample = object : Runnable {
        override fun run() {
            if (player.isPlaying) record("progress", JSONObject().put("interactive", getSystemService(android.os.PowerManager::class.java).isInteractive))
            handler.postDelayed(this, 10000)
        }
    }
    private val removed = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) {
            // Pause on any removed output; safety takes priority over automatic rerouting.
            if (devices.any { it.isSink }) interrupt("output_removed")
        }
        override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) { record("outputs_changed", outputs()) }
    }
    private val detach = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { interrupt("usb_detached") }
    }

    override fun onCreate() {
        super.onCreate()
        if (UsbDiagnosticGate.held) { stopSelf(); return }
        player = ExoPlayer.Builder(this, BaselineRenderers(this) { record("android_route_changed", it) }).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            volume = 1f // Fixture samples already quiet. Normal system volume still applies.
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) record("playback_state")
                }
                override fun onPlayerError(error: PlaybackException) { record("playback_error", JSONObject().put("code", error.errorCodeName).put("message", error.message)) }
                override fun onPlaybackSuppressionReasonChanged(reason: Int) {
                    if (reason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) interrupt("audio_focus_interruption")
                }
            })
            addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioInputFormatChanged(time: AnalyticsListener.EventTime, format: Format, reuse: DecoderReuseEvaluation?) {
                    record("decoder_input", JSONObject().put("sampleRateHz", format.sampleRate).put("channels", format.channelCount)
                        .put("pcmEncodingConstant", format.pcmEncoding).put("mime", format.sampleMimeType))
                }
                override fun onAudioDecoderInitialized(time: AnalyticsListener.EventTime, name: String, timestamp: Long, duration: Long) {
                    record("decoder", JSONObject().put("name", name))
                }
                override fun onAudioTrackInitialized(time: AnalyticsListener.EventTime, config: AudioSink.AudioTrackConfig) {
                    record("audio_track_configuration", JSONObject().put("sampleRateHz", config.sampleRate).put("encodingConstant", config.encoding)
                        .put("channelMask", config.channelConfig).put("bufferBytes", config.bufferSize).put("offload", config.offload)
                        .put("meaning", "Media3-created Android AudioTrack; physical USB stream remains unknown."))
                    record("available_outputs", outputs())
                }
                override fun onAudioUnderrun(time: AnalyticsListener.EventTime, size: Int, sizeMs: Long, sinceFeed: Long) {
                    record("android_audio_underrun", JSONObject().put("bufferBytes", size).put("sinceFeedMs", sinceFeed))
                }
                override fun onAudioSinkError(time: AnalyticsListener.EventTime, error: Exception) {
                    record("audio_sink_error", JSONObject().put("message", error.toString()))
                }
            })
        }
        session = MediaSession.Builder(this, player).setSessionActivity(PendingIntent.getActivity(this, 0,
            Intent(this, BaselineActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    if (controller.uid != applicationInfo.uid) {
                        if (!controller.isTrusted) return MediaSession.ConnectionResult.reject()
                        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                            .setAvailablePlayerCommands(Player.Commands.Builder().addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP,
                                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                                Player.COMMAND_GET_TIMELINE).build()).build()
                    }
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            .add(SessionCommand(START_FIXTURE, Bundle.EMPTY)).build()).build()
                }
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (command.customAction != START_FIXTURE) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                    val name = args.getString("fixture").orEmpty()
                    val valid = DiagnosticFixture.supported(name)
                    if (!valid || !args.getBoolean("quietConfirmed")) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    preset = args.getString("preset") ?: "Unknown"
                    testId = java.util.UUID.randomUUID().toString()
                    armed = true
                    player.pause()
                    player.repeatMode = if (args.getBoolean("loop")) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    player.setMediaItem(MediaItem.Builder().setMediaId(name).setUri("asset:///$name")
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(name).setArtist("Local diagnostic · Normal route").build()).build())
                    record("fixture_requested", JSONObject().put("source", name).put("physicalPresetUserReported", preset)
                        .put("signalPattern", DiagnosticFixture.pattern(name))
                        .put("sourceSampleRateHz", name.substringAfterLast('-').substringBefore('.').toInt())
                        .put("sourceMeaningfulBits", name.split('-')[1].toInt()).put("sourceChannels", 2)
                        .put("requestedOutput", "Default Media3 Android route; no preferred device or physical rate/bit depth forced.")
                        .put("durationMs", 6000).put("loop", args.getBoolean("loop")).put("fixturePeakDbfs", -42)
                        .put("softwarePlayerVolume", 1.0).put("requestedSpeed", 1.0).put("effects", "Default Media3 PCM path; no app EQ, ReplayGain or crossfade"))
                    player.prepare()
                    player.play()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, command: Int): Int {
                    if (command == Player.COMMAND_PLAY_PAUSE && !armed) return SessionError.ERROR_INVALID_STATE
                    return SessionResult.RESULT_SUCCESS
                }
            }).build()
        audio.registerAudioDeviceCallback(removed, handler)
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(detach, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(detach, filter)
        record("service_started", JSONObject().put("startsPaused", true))
        handler.post(sample)
        active = this
    }

    private fun interrupt(reason: String) {
        player.pause()
        armed = false // A new explicit fixture start is needed after detach/focus loss.
        record(reason, outputs())
    }

    private fun record(kind: String, values: JSONObject = JSONObject()) {
        BaselineEvidence.event(this, kind, values.put("fixture", player.currentMediaItem?.mediaId ?: JSONObject.NULL)
            .put("serviceRunId", runId).put("testId", testId ?: JSONObject.NULL)
            .put("positionMs", player.currentPosition).put("playing", player.isPlaying).put("state", player.playbackState))
    }

    private fun outputs(): JSONObject = JSONObject().put("availableOutputCapabilities", JSONArray().apply {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach {
            put(JSONObject().put("type", it.type).put("product", it.productName.toString())
                .put("sampleRatesHz", JSONArray(it.sampleRates.toList())).put("encodings", JSONArray(it.encodings.toList())))
        }
    }).put("meaning", "Available outputs and advertised capabilities; active physical route/rate not measured.")
        .put("systemMusicVolumeStep", audio.getStreamVolume(AudioManager.STREAM_MUSIC))

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (closed || !::session.isInitialized) null else session
    override fun onTaskRemoved(rootIntent: Intent?) { if (!::player.isInitialized || closed || !player.playWhenReady) stopSelf() }
    private fun shutdown() {
        if (closed || !::player.isInitialized) return
        closed = true
        player.pause()
        handler.removeCallbacks(sample)
        audio.unregisterAudioDeviceCallback(removed)
        unregisterReceiver(detach)
        record("service_stopped")
        session.release()
        player.release()
        active = null
    }
    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    companion object {
        const val START_FIXTURE = "com.localmusic.diagnostics.START_FIXTURE"
        private var active: BaselineService? = null
        fun releaseForNative() { active?.let { it.shutdown(); it.stopSelf() } }
    }
}
