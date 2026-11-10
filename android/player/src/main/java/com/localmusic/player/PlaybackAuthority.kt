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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import com.localmusic.core.*
import com.localmusic.data.SessionLoad
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Sole service-owned queue; a second decoder is used only for an explicit crossfade. */
class PlaybackAuthority(private val context: Context, private val changed: () -> Unit) {
    private val seekIndexes = FlacSeekIndexes(context)
    private val processors=java.util.IdentityHashMap<ExoPlayer,MusicAudioProcessor>()
    private val deckTracks=java.util.IdentityHashMap<ExoPlayer,TrackId>()
    private val outputProviders=java.util.IdentityHashMap<ExoPlayer,LocalAudioTrackProvider>()
    var engine = newEngine(false); private set
    private fun newEngine(attach:Boolean=true):ExoPlayer {
        val processor=MusicAudioProcessor()
        val output=LocalAudioTrackProvider(context)
        // Local files need less prefill after a seek than the generic one-second default.
        // Keep steady-state loading and starvation recovery unchanged.
        val loadControl=DefaultLoadControl.Builder().setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
            START_BUFFER_MS,DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS).build()
        val value=ExoPlayer.Builder(context,MusicRenderers(context,processor,output)).setLoadControl(loadControl)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context, LocalFlacExtractors(seekIndexes)))
            .build().apply{
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),false)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        processors[value]=processor
        outputProviders[value]=output
        if(attach)output.preferAndroidUsb=audioSettings.preferAndroidUsb
        if(attach)listen(value)
        return value
    }
    var queue = ListeningQueue(); private set
    var ready = false; private set
    private var store: MusicStore? = null
    private var persistenceBlocked = false
    private var destroyed = false
    private var retiringOutput = false
    private val direct = DirectPlayback(context,this)
    internal val outputDestroyed get()=destroyed
    internal val directOutput get()=direct.selected
    internal val directState get()=direct.view
    internal val directMatches get()=direct.matches
    val playWhenReady get()=if(direct.selected)queue.isPlaying && direct.view.phase !in setOf("end","closed","failed","paused","stopping") else engine.playWhenReady
    val isPlaying get()=if(direct.selected)queue.isPlaying && direct.view.phase=="running" && direct.view.pending>0 else engine.isPlaying
    internal val positionMs get()=if(direct.selected)direct.positionMs else engine.currentPosition
    private var generation = 0
    private var lookahead: GaplessPlan? = null
    private var lookaheadToken: String? = null
    private var reconciling = false
    private var audioSettings=AudioSettings()
    private var standby:ExoPlayer?=null
    private var standbyPlan:GaplessPlan?=null
    private var fadingOut:ExoPlayer?=null
    private var fadeWindow:FadeWindow?=null
    private var rejectedFade:EntryId?=null
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
    val current: Track? get() = queue.current?.trackId?.let { id ->
        store?.state?.value?.let { state -> state.trackById[id]?.takeUnless { state.details[id]?.hidden==true } }
    }
    val tracks: List<Track> get() = store?.state?.value?.tracks.orEmpty()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { pause("Output disconnected. Reconnect and press Play.") }
    }
    private val removed = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices:Array<out AudioDeviceInfo>) {
            if(ready&&audioSettings.preferAndroidUsb&&devices.any{it.isSink&&(it.type==AudioDeviceInfo.TYPE_USB_DEVICE||it.type==AudioDeviceInfo.TYPE_USB_HEADSET)}){
                pause("USB output connected. Press Play to use the preferred Android USB route.")
                engine.stop()
            }
        }
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) {
            if (devices.any { it.isSink && it.type in setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO) }) pause("Output disconnected. Reconnect and press Play.")
            if(audioSettings.preferAndroidUsb&&devices.any{it.isSink&&(it.type==AudioDeviceInfo.TYPE_USB_DEVICE||it.type==AudioDeviceInfo.TYPE_USB_HEADSET)})engine.stop()
        }
    }
    private var lastPersistAt = 0L
    private var seekDetails: JSONObject? = null
    private val tick = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (!ready) { handler.postDelayed(this, 1000); return }
            observePosition(); player.refresh(); changed()
            checkCrossfade()
            val now = SystemClock.elapsedRealtime()
            // Expiry is discovered by the service-owned tick (also while paused) and only pauses.
            if (sleep.checkExpired(now)) { event("sleep_timer_expired"); pause("Sleep timer finished.") }
            if (now - lastPersistAt >= 10000 && queue.current != null) { persist(); event("progress"); lastPersistAt = now }
            handler.postDelayed(this, if (isPlaying) 250 else 1000)
        }
    }
    init {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(noisy, filter)
        audio.registerAudioDeviceCallback(removed, handler)
        listen(engine)
        handler.post(tick)
    }
    private fun listen(target:ExoPlayer){
        target.addAnalyticsListener(object:AnalyticsListener {
            override fun onAudioPositionAdvancing(eventTime:AnalyticsListener.EventTime,playoutStartSystemTimeMs:Long) {
                if(target!==engine)return
                val pending=seekDetails?:return
                if(eventTime.realtimeMs<pending.getLong("requestedAtElapsedMs")||!engine.playWhenReady)return
                if(kotlin.math.abs(eventTime.currentPlaybackPositionMs-pending.getLong("targetPositionMs"))>2000)return
                pending.put("audioProgressAfterMs",eventTime.realtimeMs-pending.getLong("requestedAtElapsedMs"))
                pending.put("flacIndexAfterSeek",seekIndexes.sourceStatus(engine.currentMediaItem?.localConfiguration?.uri))
                event("seek_audio_progress",JSONObject(pending.toString()));seekDetails=null
            }
            override fun onAudioTrackInitialized(eventTime:AnalyticsListener.EventTime,audioTrackConfig:AudioSink.AudioTrackConfig) {
                if(target===engine)event("audio_output_initialized",outputProviders[target]?.startup?.json())
            }
            override fun onAudioUnderrun(eventTime:AnalyticsListener.EventTime,bufferSize:Int,bufferSizeMs:Long,elapsedSinceLastFeedMs:Long) {
                if(target===engine)event("audio_underrun",JSONObject().put("bufferSizeBytes",bufferSize)
                    .put("bufferSizeMs",bufferSizeMs).put("elapsedSinceLastFeedMs",elapsedSinceLastFeedMs))
            }
        })
        target.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!retiringOutput && !direct.selected && target===engine&&(reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT))
                    adoptLookahead(mediaItem)
            }
            override fun onEvents(player:Player,events:Player.Events){
                if(events.size()==1&&events.contains(Player.EVENT_VOLUME_CHANGED))return
                if(!destroyed&&target===engine){event("engine_state");this@PlaybackAuthority.player.refresh();changed()}
            }
            override fun onPlaybackStateChanged(state: Int) {
                if(target===engine&&state==Player.STATE_READY)seekDetails?.let { pending ->
                    if(!pending.has("readyAfterMs"))pending.put("readyAfterMs",SystemClock.elapsedRealtime()-pending.getLong("requestedAtElapsedMs"))
                }
                if (state == Player.STATE_ENDED&&target===engine) {
                    val endedGeneration = generation
                    handler.post { if (!destroyed && !retiringOutput && !direct.selected && target===engine&&endedGeneration==generation&&engine.playbackState==Player.STATE_ENDED) advance(true) }
                }
            }
            override fun onPlayerError(cause: PlaybackException) {
                if(target===engine)pause("Cannot play this file (${cause.errorCodeName}). Check permission, storage or file format; select another song or scan again.")
                else if(target===standby){rejectedFade=standbyPlan?.next?.entry?.id;discardStandby();event("crossfade_preparation_failed")}
                else if(target===fadingOut)finishFade()
            }
        })
    }
    suspend fun initialize(value: MusicStore) {
        store = value
        seekIndexes.coordinateFileOperations(value.operationMutex)
        audioSettings=value.preferences.value.audio
        outputProviders.values.forEach{it.preferAndroidUsb=audioSettings.preferAndroidUsb}
        when (val saved = withContext(Dispatchers.IO) { value.repository().loadSession() }) {
            is SessionLoad.Restored -> { queue = saved.queue; load(queue.selection, false)
                restoreEvidence = JSONObject().put("result", "restored_paused").put("savedAtMs", saved.savedAtMs).put("usedPrevious", saved.usedPrevious).put("positionMs", queue.positionMs).put("autoplay", false) }
            is SessionLoad.Unavailable -> { persistenceBlocked = true; persistenceWarning = "Saved session retained for recovery; new session saves are disabled: ${saved.reason}"; restoreEvidence = JSONObject().put("result", "unavailable").put("reason", saved.reason) }
            SessionLoad.Empty -> { restoreEvidence = JSONObject().put("result", "empty").put("autoplay", false) }
        }
        ready = true; update()
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
    fun start(ids: List<TrackId>, index: Int, resume: Long = 0, shuffled: Boolean? = null, origin: String? = null) {
        check(ready) { "The library is still opening." }
        require(ids.isNotEmpty() && ids.size <= 5000 && index in ids.indices) { "Choose a collection of up to 5,000 songs." }
        val known = tracks.associateBy { it.id }
        require(ids.all { known[it] != null }) { "A song no longer belongs to this library. Scan and try again." }
        require(known[ids[index]]?.availability == Availability.AVAILABLE) { "This file is unavailable. Check storage access and scan again." }
        check(store?.isEditing(ids[index]) != true) { "This file is being edited. Wait for the operation to finish." }
        observePosition(); persist()
        val selected = queue.start(ids, index, shuffled ?: queue.shuffle, resume, known[ids[index]]?.audio?.durationMs?.takeIf { it > 0 },
            origin?.replace(Regex("[\\p{Cntrl}]"), " ")?.take(200))
        load(selected, true)
        event(if (resume > 0) "explicit_continue" else "fresh_song_start")
    }
    fun play() {
        if (!ready || current == null) return
        if(direct.selected) {
            if(current?.availability!=Availability.AVAILABLE || store?.isEditing(queue.current?.trackId)==true){direct.normal("Song unavailable for Direct output.");return}
            error=""
            if(direct.view.phase=="end" || queue.completed){queue.play();load(queue.selection,true);return}
            if(takeFocus() && direct.play(true))queue.play()
            persist();update();return
        }
        if(retiringOutput)return
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
        if(direct.selected) {
            pauseDirectQueue(message)
            if(message.isNotBlank())direct.normal(message)else direct.play(false)
            return
        }
        seekDetails=null
        observePosition(); engine.pause(); queue.pause()
        finishFade()
        if (focusHeld) { focusHeld = false; audio.abandonAudioFocusRequest(focus) }
        if (message.isNotBlank()) error = message
        event(if (message.isBlank()) "pause" else "interruption_pause")
        persist(); update()
    }
    fun stop() { if(direct.selected){direct.normal();return};pause(); engine.stop(); event("stop"); update() }
    private var fileClosedForEdit: TrackId? = null
    fun closeForEdit(ids: Set<TrackId>) {
        require(!direct.selected && !retiringOutput){"Switch to Normal output before editing files."}
        store?.claimEdits(ids)
        finishFade();discardStandby()
        refreshLookahead()
        if (queue.current?.trackId !in ids) return
        seekIndexes.setPlaybackWindow(null)
        pause(); engine.stop(); engine.clearMediaItems(); fileClosedForEdit = queue.current?.trackId; event("file_closed_for_edit")
    }
    fun reopenAfterEdit(ids: Set<TrackId>) {
        store?.releaseEdits(ids)
        refreshLookahead()
        if (destroyed || fileClosedForEdit == null || store?.isEditing(queue.current?.trackId) == true) return
        val same = fileClosedForEdit == queue.current?.trackId
        fileClosedForEdit = null
        if (same) { load(queue.selection, false); event("file_reopened_paused") }
    }
    fun seek(position: Long) {
        if (!ready || current == null) return
        if(direct.selected) {
            val target=position.coerceIn(0,current!!.audio.durationMs)
            if(direct.seek(target)){queue.updatePosition(target);persist();update()}
            return
        }
        finishFade()
        val bound = current!!.audio.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = position.coerceIn(0, bound)
        val details=JSONObject().put("requestedAtElapsedMs",SystemClock.elapsedRealtime())
            .put("fromPositionMs",engine.currentPosition).put("targetPositionMs",target)
            .put("bufferedPositionBeforeMs",engine.bufferedPosition)
            .put("totalBufferedDurationBeforeMs",engine.totalBufferedDuration)
            .put("playingAtRequest",engine.playWhenReady)
            .put("flacIndexAtRequest",seekIndexes.sourceStatus(engine.currentMediaItem?.localConfiguration?.uri))
        seekDetails=details
        engine.currentMediaItem?.localConfiguration?.uri?.let { seekIndexes.noteSeek(it,"buffered_or_pending",target*1000) }
        engine.seekTo(target); queue.updatePosition(target); event("seek",details); persist(); update()
    }
    fun previous() { if (!ready) return; observePosition(); val playing = queue.isPlaying; persist(); load(queue.previous(), playing) }
    fun advance(automatic: Boolean = false) {
        if (!ready) return
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
        if(direct.selected){queue.pause();val start=play && takeFocus();if(direct.load(selection,start) && start)queue.play();persist();update();return}
        if(retiringOutput)return
        seekDetails=null
        finishFade();discardStandby();rejectedFade=null
        if (selection == null) { seekIndexes.setPlaybackWindow(null); return }
        if (store?.isEditing(selection.entry.trackId) == true) {
            seekIndexes.setPlaybackWindow(null)
            pause("File editing is in progress. Wait until it finishes."); engine.stop(); engine.clearMediaItems(); fileClosedForEdit = selection.entry.trackId; return
        }
        fileClosedForEdit = null
        val track = current
        generation++
        lookahead = null; lookaheadToken = null
        if (track == null || track.availability != Availability.AVAILABLE || track.sources.isEmpty()) {
            seekIndexes.setPlaybackWindow(null)
            engine.stop(); engine.clearMediaItems(); queue.pause()
            error = if(store?.state?.value?.details?.get(selection.entry.trackId)?.hidden==true) ""
                else "Saved song is unavailable. Scan again or select another song."
            persist(); update(); return
        }
        error = ""; engine.pause()
        deckTracks[engine]=track.id;configureDeck(engine,track.id)
        val item = mediaItem(track).buildUpon().setMediaId(selection.entry.id.value.toString()).build()
        seekIndexes.setPlaybackWindow(item.localConfiguration?.uri)
        engine.setMediaItem(item, selection.positionMs); engine.prepare()
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
    fun playQueueEntry(entry:EntryId) {
        require(ready){"The library is still opening."}
        val snapshot=queue.snapshot()
        val target=(snapshot.manual+snapshot.order.drop(snapshot.cursor+1)).firstOrNull{it.id==entry}
            ?: throw IllegalArgumentException("That queued entry no longer exists.")
        require(store?.state?.value?.trackById?.get(target.trackId)?.availability==Availability.AVAILABLE){"Song unavailable. Check access or storage and scan again."}
        observePosition();persist()
        load(queue.selectOccurrence(entry),true)
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
    fun shuffle(value: Boolean) { if (!ready) return; queue.setShuffle(value); persist(); update() }
    fun repeat(value: Int) { if (!ready) return; queue.repeat = when (value) { Player.REPEAT_MODE_ALL -> RepeatMode.ALL; Player.REPEAT_MODE_ONE -> RepeatMode.ONE; else -> RepeatMode.OFF }; persist(); update() }
    private fun observePosition() {
        if(direct.selected){if(direct.matches)queue.updatePosition(direct.positionMs);return}
        if (engine.currentMediaItem?.mediaId == queue.current?.id?.value?.toString() &&
            (lookaheadToken == null || engine.currentMediaItem?.localConfiguration?.tag != lookaheadToken)) queue.updatePosition(engine.currentPosition.coerceAtLeast(0))
    }
    private fun persist() { if (ready && !persistenceBlocked && queue.current != null) store?.persist(queue.snapshot()) }
    private fun update() { if(!direct.selected && !retiringOutput){configureDeck(engine,queue.current?.trackId);refreshLookahead()};player.refresh();changed() }
    fun libraryChanged() {
        if (!ready) return
        if (current?.availability == Availability.AVAILABLE && error in setOf(
                "Saved song is unavailable. Scan again or select another song.",
                "Song unavailable. Check access or storage and scan again.")) error = ""
        update()
    }

    /** Only the engine's next occurrence is prepared; the live queue is consumed at transition. */
    private fun refreshLookahead() {
        if(direct.selected || retiringOutput)return
        if (reconciling || destroyed || !ready || fadingOut!=null) return
        val snapshot = queue.snapshot()
        val plan = if (sleep.atTrackEnd() || fileClosedForEdit != null || engine.currentMediaItem?.mediaId != queue.current?.id?.value?.toString()) null
            else (lookahead?:standbyPlan)?.takeIf { it.matches(snapshot) } ?: GaplessPlanner.prepare(snapshot)
        val next = plan?.next?.entry?.trackId?.let { id -> tracks.firstOrNull { it.id == id && it.availability == Availability.AVAILABLE && it.sources.isNotEmpty() && store?.isEditing(id) != true } }
        val window=if(next==null)null else CrossfadeRules.window(audioSettings.effectiveCrossfadeSeconds,current?.audio?.durationMs?:0,next.audio.durationMs,queue.repeat,sleep.atTrackEnd())
        if(window!=null){
            reconciling=true
            try{
                lookahead=null;lookaheadToken=null
                val from=engine.currentMediaItemIndex+1
                if(from in 0 until engine.mediaItemCount)engine.removeMediaItems(from,engine.mediaItemCount)
                if(plan!=null&&next!=null&&rejectedFade!=plan.next.entry.id){
                    if(standbyPlan?.matches(snapshot)!=true){
                        discardStandby();standbyPlan=plan
                        val item=mediaItem(next).buildUpon().setMediaId(plan.next.entry.id.value.toString()).build()
                        seekIndexes.setPlaybackWindow(engine.currentMediaItem?.localConfiguration?.uri,item.localConfiguration?.uri)
                        standby=newEngine().also{deck->deckTracks[deck]=next.id;configureDeck(deck,next.id);deck.volume=0f;deck.setMediaItem(item);deck.prepare()}
                    }
                    else seekIndexes.setPlaybackWindow(engine.currentMediaItem?.localConfiguration?.uri,standby?.currentMediaItem?.localConfiguration?.uri)
                }else { discardStandby();seekIndexes.setPlaybackWindow(engine.currentMediaItem?.localConfiguration?.uri) }
            }finally{reconciling=false}
            return
        }
        discardStandby()
        if (plan == lookahead && next != null && engine.mediaItemCount > engine.currentMediaItemIndex + 1) {
            seekIndexes.setPlaybackWindow(engine.currentMediaItem?.localConfiguration?.uri,engine.getMediaItemAt(engine.currentMediaItemIndex+1).localConfiguration?.uri)
            return
        }
        reconciling = true
        try {
            lookahead = null; lookaheadToken = null
            val from = engine.currentMediaItemIndex + 1
            if (from in 0 until engine.mediaItemCount) engine.removeMediaItems(from, engine.mediaItemCount)
            if (plan != null && next != null) {
                val token = UUID.randomUUID().toString()
                lookahead = plan; lookaheadToken = token
                val item=mediaItem(next).buildUpon().setMediaId(plan.next.entry.id.value.toString()).setTag(token).build()
                seekIndexes.setPlaybackWindow(engine.currentMediaItem?.localConfiguration?.uri,item.localConfiguration?.uri)
                engine.addMediaItem(item)
            } else seekIndexes.setPlaybackWindow(engine.currentMediaItem?.localConfiguration?.uri)
        } finally { reconciling = false }
    }

    private fun adoptLookahead(item: MediaItem?) {
        if (destroyed || reconciling || !ready) return
        val plan = lookahead
        if (plan == null || item?.localConfiguration?.tag != lookaheadToken || !plan.matches(queue.snapshot()) || sleep.atTrackEnd()) {
            pause("The queue changed at the track boundary. Press Play to continue.")
            load(queue.selection, false)
            return
        }
        val playing = engine.playWhenReady
        store?.takeUnless { persistenceBlocked }?.persist(queue.snapshot().copy(completed = true))
        reconciling = true
        try {
            check(queue.restore(plan.after))
            if (playing) queue.play()
            generation++; lookahead = null; lookaheadToken = null
            if (engine.currentMediaItemIndex > 0) engine.removeMediaItems(0, engine.currentMediaItemIndex)
        } finally { reconciling = false }
        event("gapless_transition"); persist(); update()
    }
    private fun configureDeck(deck:ExoPlayer,id:TrackId?){
        if(id!=null)deckTracks[deck]=id
        processors[deck]?.configuration=DspConfiguration(audioSettings,ReplayGainInfo.parse(store?.state?.value?.details?.get(id)?.replayGainTags.orEmpty()))
    }
    fun audioSettingsChanged(value:AudioSettings){
        if(value==audioSettings)return
        if(direct.selected || retiringOutput) {
            audioSettings=value
            if(!directProcessingAllowed())direct.normal("Normal output restored paused for sound processing.")
            update();return
        }
        val routeChanged=value.preferAndroidUsb!=audioSettings.preferAndroidUsb
        val transportChanged=value.crossfadeSeconds!=audioSettings.crossfadeSeconds||routeChanged
        audioSettings=value
        if(transportChanged){finishFade();discardStandby();rejectedFade=null}
        outputProviders.values.forEach{it.preferAndroidUsb=value.preferAndroidUsb}
        for((deck,id)in deckTracks)configureDeck(deck,id)
        if(routeChanged&&ready&&fileClosedForEdit==null){
            reconciling=true
            try{seekDetails=null;reprepareForAudioRoute(engine)}finally{reconciling=false}
            event("android_usb_preference_changed",JSONObject().put("enabled",value.preferAndroidUsb))
        }
        if(ready)update()
    }
    private fun releaseDeck(deck:ExoPlayer){deck.pause();deck.release();processors.remove(deck);deckTracks.remove(deck);outputProviders.remove(deck)}
    private fun discardStandby(){val old=standby;standby=null;standbyPlan=null;if(old!=null)releaseDeck(old)}
    private fun finishFade(completed:Boolean=false){
        handler.removeCallbacks(fadeTick)
        val old=fadingOut;fadingOut=null;fadeWindow=null
        if(old!=null){releaseDeck(old);engine.volume=1f;event(if(completed)"crossfade_finished" else "crossfade_cancelled")}
    }
    private val fadeTick=object:Runnable{
        override fun run(){
            val outgoing=fadingOut?:return;val window=fadeWindow?:return
            if(destroyed)return
            if(engine.playbackState==Player.STATE_BUFFERING){pause("Crossfade interrupted by buffering. Press Play to continue.");return}
            val elapsed=engine.currentPosition.coerceAtLeast(0)
            outgoing.volume=window.outgoing(elapsed);engine.volume=window.incoming(elapsed)
            if(elapsed>=window.durationMs||outgoing.playbackState==Player.STATE_ENDED){finishFade(true);update()}
            else handler.postDelayed(this,25)
        }
    }
    private fun checkCrossfade(){
        if(direct.selected || retiringOutput)return
        val incoming=standby?:return;val plan=standbyPlan?:return
        if(!engine.isPlaying||incoming.playbackState!=Player.STATE_READY||fadingOut!=null||!plan.matches(queue.snapshot()))return
        val duration=engine.duration.takeIf{it>0}?:return
        val nextDuration=incoming.duration.takeIf{it>0}?:return
        val window=CrossfadeRules.window(audioSettings.effectiveCrossfadeSeconds,duration,nextDuration,queue.repeat,sleep.atTrackEnd())?:return
        if(duration-engine.currentPosition !in 1..window.durationMs)return
        reconciling=true
        try{
            store?.takeUnless{persistenceBlocked}?.persist(queue.snapshot().copy(completed=true))
            check(queue.restore(plan.after));queue.play()
            val previous=engine;engine=incoming;standby=null;standbyPlan=null
            seekIndexes.setPlaybackWindow(engine.currentMediaItem?.localConfiguration?.uri)
            fadingOut=previous;fadeWindow=FadeWindow(minOf(window.durationMs,(duration-previous.currentPosition).coerceAtLeast(1)))
            generation++;lookahead=null;lookaheadToken=null
            incoming.volume=0f;incoming.play()
        }finally{reconciling=false}
        event("crossfade_started");persist();update();handler.post(fadeTick)
    }

    fun suspendForRestore() {
        require(!direct.selected && !retiringOutput){"Switch to Normal output before restoring a backup."}
        pause(); ready = false; generation++; sleep.cancel()
        discardStandby()
        lookahead = null; lookaheadToken = null; engine.stop(); engine.clearMediaItems()
    }
    suspend fun reopenAfterRestore(value: MusicStore) {
        queue = ListeningQueue(); persistenceBlocked = false; persistenceWarning = ""
        initialize(value)
    }
    private fun event(name: String,details:JSONObject?=null) {
        if (events.length() >= 150) events.remove(0)
        events.put(JSONObject().put("at", java.time.Instant.now().toString()).put("event", name)
            .put("elapsedRealtimeMs",SystemClock.elapsedRealtime())
            .put("bufferedPositionMs",engine.bufferedPosition).put("totalBufferedDurationMs",engine.totalBufferedDuration)
            .put("details",details?:JSONObject.NULL)
            .put("trackId", queue.current?.trackId?.value?.toString()).put("positionMs", engine.currentPosition.coerceAtLeast(0))
            .put("engineState", engine.playbackState).put("playWhenReady", engine.playWhenReady).put("isPlaying", engine.isPlaying)
            .put("interactive", context.getSystemService(PowerManager::class.java).isInteractive).put("error", error))
    }
    /** Bounded read-only snapshot; no playback, queue or persistence operations. */
    fun audioInfo():JSONObject {
        if(direct.selected)return JSONObject().put("status","Native Direct USB · ${direct.view.phase}").put("outputMode","Native Direct USB")
            .put("source",current?.let{JSONObject().put("title",it.tags.title).put("quality",PlaybackPresentation.quality(it.audio)).put("channels",it.audio.channels?:JSONObject.NULL)})
            .put("nativeOutput",JSONObject().put("sampleRateHz",current?.audio?.sampleRateHz).put("bits",current?.audio?.meaningfulBits)
                .put("completedFrames",direct.view.completedFrames).put("pending",direct.view.pending).put("integrity",direct.view.integrity)
                .put("limits","USB completion counters are not measured DAC rendering. Clock synchronization and analog balance are unverified."))
        val track=current
        val pcm=processors[engine]?.lastPcm
        val status=when {
            !ready->"Opening library"
            track==null->"No song selected"
            engine.playerError!=null->"Playback error"
            engine.playbackState==Player.STATE_BUFFERING->"Buffering"
            engine.playbackState==Player.STATE_ENDED->"Finished"
            engine.isPlaying->"Playing"
            !engine.playWhenReady->"Paused"
            else->"Waiting for playback"
        }
        return JSONObject().put("capturedAt",java.time.Instant.now().toString()).put("status",status)
            .put("preferAndroidUsb",audioSettings.preferAndroidUsb).put("eqEnabled",audioSettings.eqEnabled)
            .put("preset",audioSettings.preset.name).put("replayGain",audioSettings.replayGain.name)
            .put("source",track?.let{JSONObject().put("title",it.tags.title).put("quality",PlaybackPresentation.quality(it.audio))
                .put("channels",it.audio.channels?:JSONObject.NULL)}?:JSONObject.NULL)
            .put("processing",pcm?.let{JSONObject().put("sampleRateHz",it.sampleRateHz).put("channelCount",it.channelCount)
                .put("inputEncoding",it.inputEncoding).put("outputEncoding",it.outputEncoding)}?:JSONObject.NULL)
            .put("client",outputProviders[engine]?.outputJson()?:JSONObject.NULL)
    }
    fun report(): String {
        observePosition()
        val format = engine.audioFormat
        return JSONObject().put("schemaVersion", 1).put("appVersion", BuildConfig.VERSION_NAME)
            .put("exportedAt", java.time.Instant.now().toString()).put("phone", JSONObject().put("model", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT).put("buildDisplay", Build.DISPLAY))
            .put("mode", if(direct.selected)"Direct USB" else "Normal").put("state", JSONObject(extras().getString("state")!!))
            .put("directUsb",direct.report())
            .put("gapless", JSONObject().put("transport", "Media3 next-item preparation").put("nextPrepared", lookahead != null)
                .put("limits", "Audible continuity depends on decoder and output compatibility; transport events do not prove physical gaplessness or bit-perfect output."))
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
            .put("processing",JSONObject().put("eqEnabled",audioSettings.eqEnabled).put("preset",audioSettings.preset.name)
                .put("replayGain",audioSettings.replayGain.name).put("crossfadeSeconds",audioSettings.effectiveCrossfadeSeconds).put("crossfading",fadingOut!=null)
                .put("preferAndroidUsb",audioSettings.preferAndroidUsb).put("savedCrossfadeSeconds",audioSettings.crossfadeSeconds)
                .put("pcm",processors[engine]?.lastPcm?.let{JSONObject().put("sampleRateHz",it.sampleRateHz).put("channelCount",it.channelCount)
                    .put("inputEncoding",it.inputEncoding).put("outputEncoding",it.outputEncoding)}?:JSONObject.NULL)
                .put("limits","Normal decoded PCM: Double EQ/ReplayGain, then float PCM into Media3. Float has about 24 significant binary bits, not 32-bit integer precision. Android may resample or change precision; no physical DAC or bit-perfect measurement."))
            .put("buffering",JSONObject().put("startAndSeekPrefillMs",START_BUFFER_MS)
                .put("starvationRecoveryPrefillMs",DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .put("limits","Prefill is media duration, not a guaranteed wall-clock seek latency. State events do not measure the time sound reaches the listener."))
            .put("outputStartup",outputProviders[engine]?.startup?.json()?:JSONObject.NULL)
            .put("normalPcmOutput",outputProviders[engine]?.outputJson()?:JSONObject.NULL)
            .put("flacSeekIndexes",seekIndexes.status(engine.currentMediaItem?.localConfiguration?.uri))
            .put("events", JSONArray(events.toString())).toString(2)
    }
    fun extras(): Bundle {
        val snapshot = queue.snapshot()
        val presentation = PlaybackPresentation.position(snapshot)
        val json = JSONObject().put("ready", ready).put("position", queue.positionMs)
            .put("duration", if(direct.selected)current?.audio?.durationMs?:0 else engine.duration.takeIf { it > 0 } ?: current?.audio?.durationMs ?: 0)
            .put("playing", isPlaying).put("playWhenReady", playWhenReady)
            .put("outputMode",if(direct.selected)"Direct USB" else "Normal").put("directUsbHardwareValidated",false)
            .put("error", listOf(persistenceWarning, error).filter { it.isNotBlank() }.joinToString("\n"))
            .put("currentId", queue.current?.trackId?.value?.toString().orEmpty())
            .put("collectionLabel", presentation.label)
            .put("collectionNumber", presentation.number ?: 0).put("collectionTotal", presentation.total ?: 0)
            .put("manual", JSONArray(snapshot.manual.take(60).map { it.trackId.value.toString() }))
            .put("manualIds", JSONArray(snapshot.manual.take(60).map { it.id.value.toString() }))
            .put("manualCount", snapshot.manual.size)
            .put("sequence", JSONArray(snapshot.order.drop(snapshot.cursor + 1).take(60).map { it.trackId.value.toString() }))
            .put("sequenceIds", JSONArray(snapshot.order.drop(snapshot.cursor + 1).take(60).map { it.id.value.toString() }))
            .put("sequenceCount", (snapshot.order.size - snapshot.cursor - 1).coerceAtLeast(0))
            .put("sleepMode", sleep.state.mode.name)
            .put("sleepRemaining", sleep.state.remainingMs)
        return Bundle().apply { putString("state", json.toString()) }
    }
    fun release() {
        if (destroyed) return
        pause(); destroyed = true; handler.removeCallbacksAndMessages(null)
        discardStandby();finishFade()
        seekIndexes.close()
        context.unregisterReceiver(noisy); audio.unregisterAudioDeviceCallback(removed)
        direct.destroy()
        if(!retiringOutput)releaseDeck(engine)
        player.release()
    }
    fun requestOutput(directUsb: Boolean): Boolean=if(directUsb)direct.request()else direct.normal()
    internal fun directProcessingAllowed()=!audioSettings.eqEnabled && audioSettings.replayGain==ReplayGainMode.OFF && audioSettings.crossfadeSeconds==0
    internal fun outputChanged(message: String?=null){if(!message.isNullOrBlank())error=message;update()}
    internal fun directPositionChanged(){if(direct.matches)queue.updatePosition(direct.positionMs);player.refresh();changed()}
    internal fun pauseDirectQueue(message: String="") {
        if(direct.matches)queue.updatePosition(direct.positionMs)
        queue.pause()
        if(focusHeld){focusHeld=false;audio.abandonAudioFocusRequest(focus)}
        if(message.isNotBlank())error=message
        persist();update()
    }
    internal fun retireNormalForDirect(): List<OutputRetirement> {
        val tickets=outputProviders.values.map{it.retirement}
        retiringOutput=true;pause();discardStandby();finishFade()
        processors.keys.toList().forEach(::releaseDeck)
        generation++;lookahead=null;lookaheadToken=null;seekIndexes.setPlaybackWindow(null)
        return tickets
    }
    internal fun restoreNormalAfterDirect(message: String?=null) {
        if(destroyed)return
        engine=newEngine();retiringOutput=false;generation++
        load(queue.selection,false)
        if(!message.isNullOrBlank())error=message
        update()
    }
    companion object {
        private const val START_BUFFER_MS=100
        fun id(raw:String)=TrackId(UUID.fromString(raw))
    }
}
