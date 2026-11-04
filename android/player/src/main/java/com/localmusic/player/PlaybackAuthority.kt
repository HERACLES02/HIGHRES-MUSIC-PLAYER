@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.content.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.*
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import com.localmusic.core.*
import com.localmusic.data.SessionLoad
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Service-owned queue and sole audio engine. UI and system controllers use its session adapter. */
class PlaybackAuthority(private val context: Context, private val changed: () -> Unit) {
    val engine = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        setWakeMode(C.WAKE_MODE_LOCAL)
    }
    var queue = ListeningQueue(); private set
    var ready = false; private set
    private var store: MusicStore? = null
    private var persistenceBlocked = false
    private var destroyed = false
    private var generation = 0
    private var error = ""
    private var persistenceWarning = ""
    private val events = JSONArray()
    private var restoreEvidence = JSONObject().put("result", "opening")
    private var focusHeld = false
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener({ change -> if (change != AudioManager.AUDIOFOCUS_GAIN) pause("Audio interrupted. Press Play when ready.") }, handler).build()
    val artwork: ByteArray = run {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        requireNotNull(context.getDrawable(R.drawable.ic_local_music)).apply { setBounds(0, 0, 256, 256); draw(Canvas(bitmap)) }
        ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle(); it.toByteArray() }
    }
    val player = NormalSessionPlayer(this)
    val sleep = SleepTimer { android.os.SystemClock.elapsedRealtime() }
    val current: Track? get() = store?.state?.value?.tracks?.firstOrNull { it.id == queue.current?.trackId }
    val tracks: List<Track> get() = store?.state?.value?.tracks.orEmpty()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { pause("Output disconnected. Reconnect and press Play.") }
    }
    private val removed = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) {
            if (devices.any { it.isSink && it.type in setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO) }) pause("Output disconnected. Reconnect and press Play.")
        }
    }
    private var lastPersistAt = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (destroyed) return
            observePosition(); player.refresh(); changed()
            val now = SystemClock.elapsedRealtime()
            // Expiry is discovered by the service-owned tick (also while paused) and only pauses.
            if (sleep.checkExpired(now)) { event("sleep_timer_expired"); pause("Sleep timer finished.") }
            if (now - lastPersistAt >= 10000 && queue.current != null) { persist(); event("progress"); lastPersistAt = now }
            handler.postDelayed(this, if (engine.isPlaying) 250 else 1000)
        }
    }
    init {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(noisy, filter)
        audio.registerAudioDeviceCallback(removed, handler)
        engine.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { if (!destroyed) { event("engine_state"); this@PlaybackAuthority.player.refresh(); changed() } }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    val endedGeneration = generation
                    handler.post { if (!destroyed && endedGeneration == generation && engine.playbackState == Player.STATE_ENDED) advance(true) }
                }
            }
            override fun onPlayerError(cause: PlaybackException) {
                pause("Cannot play this file (${cause.errorCodeName}). Check permission, storage or file format; select another song or scan again.")
            }
        })
        handler.post(tick)
    }
    suspend fun initialize(value: MusicStore) {
        store = value
        when (val saved = withContext(Dispatchers.IO) { value.repository().loadSession() }) {
            is SessionLoad.Restored -> { queue = saved.queue; load(queue.selection, false)
                restoreEvidence = JSONObject().put("result", "restored_paused").put("savedAtMs", saved.savedAtMs).put("usedPrevious", saved.usedPrevious).put("positionMs", queue.positionMs).put("autoplay", false) }
            is SessionLoad.Unavailable -> { persistenceBlocked = true; persistenceWarning = "Saved session retained for recovery; new session saves are disabled: ${saved.reason}"; restoreEvidence = JSONObject().put("result", "unavailable").put("reason", saved.reason) }
            SessionLoad.Empty -> { restoreEvidence = JSONObject().put("result", "empty").put("autoplay", false) }
        }
        ready = true; player.refresh(); changed()
        event("session_opened_paused")
    }
    fun mediaItem(track: Track): MediaItem {
        val metadata = MediaMetadata.Builder().setTitle(track.tags.title).setArtist(track.tags.artists.joinToString(", "))
            .setAlbumTitle(track.tags.album).setIsPlayable(true).setIsBrowsable(false).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        val cover = store?.artwork(track.id)
        if (cover != null) metadata.setArtworkUri(android.net.Uri.fromFile(java.io.File(cover)))
        else metadata.setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        val sources = track.sources.sortedByDescending { it.kind == SourceKind.DOCUMENT }
        val source = sources.firstOrNull { it.kind == SourceKind.DOCUMENT && context.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && store?.state?.value?.folders?.any { folder -> folder.id == it.folderGrantId && folder.treeUri == permission.uri.toString() } == true
        } } ?: sources.firstOrNull { it.kind == SourceKind.MEDIA_STORE && context.checkSelfPermission(MusicStore.audioPermission()) == android.content.pm.PackageManager.PERMISSION_GRANTED }
            ?: sources.firstOrNull()
        return MediaItem.Builder().setMediaId(track.id.value.toString()).setUri(source?.uri ?: "").setMediaMetadata(metadata.build()).build()
    }
    fun start(ids: List<TrackId>, index: Int, resume: Long = 0, shuffled: Boolean? = null) {
        check(ready) { "The library is still opening." }
        require(ids.isNotEmpty() && ids.size <= 5000 && index in ids.indices) { "Choose a collection of up to 5,000 songs." }
        val known = tracks.associateBy { it.id }
        require(ids.all { known[it] != null }) { "A song no longer belongs to this library. Scan and try again." }
        require(known[ids[index]]?.availability == Availability.AVAILABLE) { "This file is unavailable. Check storage access and scan again." }
        check(store?.isEditing(ids[index]) != true) { "This file is being edited. Wait for the operation to finish." }
        observePosition(); persist()
        val selected = queue.start(ids, index, shuffled ?: queue.shuffle, resume, known[ids[index]]?.audio?.durationMs?.takeIf { it > 0 })
        load(selected, true)
        event(if (resume > 0) "explicit_continue" else "fresh_song_start")
    }
    fun play() {
        if (!ready || current == null) return
        if (store?.isEditing(queue.current?.trackId) == true) { pause("File editing is in progress. Press Play after it finishes."); return }
        if (current?.sources?.all { it.kind == SourceKind.MEDIA_STORE } == true && context.checkSelfPermission(MusicStore.audioPermission()) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pause("Music access was revoked. Allow access and scan again."); return
        }
        if (current?.availability != Availability.AVAILABLE) { pause("Song unavailable. Check access or storage and scan again."); return }
        error = ""
        if (engine.playerError != null || queue.completed || engine.playbackState == Player.STATE_IDLE) {
            queue.play(); load(queue.selection, true); return
        }
        // A sleep timer that stopped at the track's natural end leaves the engine just before
        // STATE_ENDED with completed unset; pressing Play must restart the current song from zero.
        if (engine.playbackState == Player.STATE_ENDED) {
            queue.updatePosition(0); queue.play(); load(queue.selection, true); return
        }
        if (takeFocus()) { queue.play(); engine.play() }
        event("play")
        update()
    }
    fun pause(message: String = "") {
        observePosition(); engine.pause(); queue.pause()
        if (focusHeld) { focusHeld = false; audio.abandonAudioFocusRequest(focus) }
        if (message.isNotBlank()) error = message
        event(if (message.isBlank()) "pause" else "interruption_pause")
        persist(); update()
    }
    fun stop() { pause(); engine.stop(); event("stop"); update() }
    private var fileClosedForEdit: TrackId? = null
    fun closeForEdit(ids: Set<TrackId>) {
        store?.claimEdits(ids)
        if (queue.current?.trackId !in ids) return
        pause(); engine.stop(); engine.clearMediaItems(); fileClosedForEdit = queue.current?.trackId; event("file_closed_for_edit")
    }
    fun reopenAfterEdit(ids: Set<TrackId>) {
        store?.releaseEdits(ids)
        if (destroyed || fileClosedForEdit == null || store?.isEditing(queue.current?.trackId) == true) return
        val same = fileClosedForEdit == queue.current?.trackId
        fileClosedForEdit = null
        if (same) { load(queue.selection, false); event("file_reopened_paused") }
    }
    fun seek(position: Long) {
        if (current == null) return
        val bound = current!!.audio.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = position.coerceIn(0, bound)
        engine.seekTo(target); queue.updatePosition(target); event("seek"); persist(); update()
    }
    fun previous() { observePosition(); val playing = queue.isPlaying; persist(); load(queue.previous(), playing) }
    fun advance(automatic: Boolean = false) {
        observePosition()
        // End-of-track expiry fires only at the natural end of the currently playing song,
        // never on a manual Next: pause in place instead of loading the next selection.
        if (automatic && sleep.atTrackEnd()) {
            sleep.consumeTrackEnd()
            event("sleep_timer_expired"); pause("Sleep timer finished at the end of this track.")
            return
        }
        val playing = queue.isPlaying
        // Save the completed song before moving away, so Home never offers it as Continue.
        if (automatic) {
            val old = queue.snapshot()
            store?.takeUnless { persistenceBlocked }?.persist(old.copy(completed = true))
        } else persist()
        val next = queue.next(automatic)
        if (next == null) { pause(); update() }
        else load(next, playing)
    }
    private fun load(selection: Selection?, play: Boolean) {
        if (selection == null) return
        if (store?.isEditing(selection.entry.trackId) == true) {
            pause("File editing is in progress. Wait until it finishes."); engine.stop(); engine.clearMediaItems(); fileClosedForEdit = selection.entry.trackId; return
        }
        fileClosedForEdit = null
        val track = current
        generation++
        if (track == null || track.availability != Availability.AVAILABLE || track.sources.isEmpty()) {
            engine.stop(); engine.clearMediaItems(); queue.pause(); error = "Saved song is unavailable. Scan again or select another song."
            persist(); update(); return
        }
        error = ""; engine.pause()
        engine.setMediaItem(mediaItem(track), selection.positionMs); engine.prepare()
        queue.pause()
        if (play && takeFocus()) { queue.play(); engine.play() }
        persist(); update()
    }
    private fun takeFocus(): Boolean {
        if (focusHeld) return true
        focusHeld = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focusHeld) error = "Audio focus unavailable. Stop other audio and press Play."
        return focusHeld
    }
    fun enqueue(id: TrackId, next: Boolean) {
        require(ready && tracks.any { it.id == id }) { "Song unavailable in this library." }
        require(queue.current != null) { "Start a song before adding to its queue." }
        require(queue.upcomingManual.size < 1000) { "The manual queue is full." }
        if (next) queue.playNext(id) else queue.addToQueue(id)
        persist(); update()
    }
    /** Removes one pending next-up occurrence by identity; the active song never changes. */
    fun removeFromQueue(entry: EntryId) {
        require(ready) { "The library is still opening." }
        queue.removeOccurrence(entry); persist(); update()
    }
    /** Moves one pending occurrence to a new next-up position (0 = play next). */
    fun moveInQueue(entry: EntryId, index: Int) {
        require(ready) { "The library is still opening." }
        queue.moveOccurrence(entry, index); persist(); update()
    }
    /** Clears pending next-up entries; safe while playing or paused. */
    fun clearQueue() {
        require(ready) { "The library is still opening." }
        queue.clearManualEntries(); persist(); update()
    }
    /** Sleep timer in minutes (1-480): pause at one fixed deadline on the monotonic clock. */
    fun sleepTimer(minutes: Int) {
        require(ready) { "The library is still opening." }
        sleep.start(minutes); event("sleep_timer_set"); update()
    }
    /** Sleep timer armed for the natural end of the currently playing track. */
    fun sleepTimerEndOfTrack() {
        require(ready) { "The library is still opening." }
        sleep.startEndOfTrack(); event("sleep_timer_track_end"); update()
    }
    fun sleepTimerCancel() {
        require(ready) { "The library is still opening." }
        sleep.cancel(); event("sleep_timer_cancel"); update()
    }
    fun shuffle(value: Boolean) { queue.setShuffle(value); persist(); update() }
    fun repeat(value: Int) { queue.repeat = when (value) { Player.REPEAT_MODE_ALL -> RepeatMode.ALL; Player.REPEAT_MODE_ONE -> RepeatMode.ONE; else -> RepeatMode.OFF }; persist(); update() }
    private fun observePosition() {
        if (engine.currentMediaItem?.mediaId == queue.current?.trackId?.value?.toString()) queue.updatePosition(engine.currentPosition.coerceAtLeast(0))
    }
    private fun persist() { if (ready && !persistenceBlocked && queue.current != null) store?.persist(queue.snapshot()) }
    private fun update() { player.refresh(); changed() }
    private fun event(name: String) {
        if (events.length() >= 150) events.remove(0)
        events.put(JSONObject().put("at", java.time.Instant.now().toString()).put("event", name)
            .put("trackId", queue.current?.trackId?.value?.toString()).put("positionMs", engine.currentPosition.coerceAtLeast(0))
            .put("engineState", engine.playbackState).put("playWhenReady", engine.playWhenReady).put("isPlaying", engine.isPlaying)
            .put("interactive", context.getSystemService(PowerManager::class.java).isInteractive).put("error", error))
    }
    fun report(): String {
        observePosition()
        val format = engine.audioFormat
        return JSONObject().put("schemaVersion", 1).put("appVersion", "0.6.0-playlist-files")
            .put("exportedAt", java.time.Instant.now().toString()).put("phone", JSONObject().put("model", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT).put("buildDisplay", Build.DISPLAY))
            .put("mode", "Normal").put("state", JSONObject(extras().getString("state")!!))
            .put("sessionRestore", restoreEvidence)
            .put("processId", android.os.Process.myPid())
            .put("historicalProcessExits", JSONArray(runCatching {
                context.getSystemService(android.app.ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 5).map {
                    JSONObject().put("reason", it.reason).put("description", it.description).put("timestamp", it.timestamp).put("importance", it.importance)
                }
            }.getOrDefault(emptyList<JSONObject>())))
            .put("source", current?.let { JSONObject().put("title", it.tags.title).put("mimeType", it.audio.mimeType)
                .put("durationMs", it.audio.durationMs).put("availability", it.availability.name) } ?: JSONObject.NULL)
            .put("decoderInputFormat", format?.let { JSONObject().put("sampleMimeType", it.sampleMimeType)
                .put("sampleRateHz", it.sampleRate).put("channelCount", it.channelCount).put("pcmEncoding", it.pcmEncoding) } ?: JSONObject.NULL)
            .put("availableOutputCapabilities", JSONArray(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map {
                JSONObject().put("type", it.type).put("product", it.productName.toString()).put("sampleRatesHz", JSONArray(it.sampleRates.toList()))
            })).put("measurementLimits", "Decoder input and available output capabilities are not a measured active HAL/DAC rate. Internal processing is unknown.")
            .put("processing", "No app EQ, ReplayGain, crossfade, balance or speed processing; Android audio path may process audio.")
            .put("events", JSONArray(events.toString())).toString(2)
    }
    fun extras(): Bundle {
        val snapshot = queue.snapshot()
        val json = JSONObject().put("ready", ready).put("position", queue.positionMs)
            .put("duration", engine.duration.takeIf { it > 0 } ?: current?.audio?.durationMs ?: 0)
            .put("playing", engine.isPlaying).put("playWhenReady", engine.playWhenReady)
            .put("error", listOf(persistenceWarning, error).filter { it.isNotBlank() }.joinToString("\n"))
            .put("currentId", queue.current?.trackId?.value?.toString().orEmpty())
            .put("manual", JSONArray(snapshot.manual.take(60).map { it.trackId.value.toString() }))
            .put("manualIds", JSONArray(snapshot.manual.take(60).map { it.id.value.toString() }))
            .put("manualCount", snapshot.manual.size)
            .put("sequence", JSONArray(snapshot.order.drop(snapshot.cursor + 1).take(60).map { it.trackId.value.toString() }))
            .put("sequenceCount", (snapshot.order.size - snapshot.cursor - 1).coerceAtLeast(0))
            .put("sleepMode", sleep.state.mode.name)
            .put("sleepRemaining", sleep.state.remainingMs)
        return Bundle().apply { putString("state", json.toString()) }
    }
    fun release() {
        if (destroyed) return
        pause(); destroyed = true; handler.removeCallbacksAndMessages(null)
        context.unregisterReceiver(noisy); audio.unregisterAudioDeviceCallback(removed)
        engine.release(); player.release()
    }
    companion object { fun id(raw: String) = TrackId(UUID.fromString(raw)) }
}
