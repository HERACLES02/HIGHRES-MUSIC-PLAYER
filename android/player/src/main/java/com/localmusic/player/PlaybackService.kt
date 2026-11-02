@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.*
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import com.localmusic.core.*
import java.util.UUID
import org.json.JSONObject
import android.net.Uri
import kotlinx.coroutines.sync.withLock

class PlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var authority: PlaybackAuthority
    private var session: MediaLibrarySession? = null
    private var storage: MusicStore? = null
    private var scan: Job? = null
    private fun publish() { session?.setSessionExtras(authority.extras()) }
    override fun onCreate() {
        super.onCreate()
        authority = PlaybackAuthority(this, ::publish)
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaLibrarySession.Builder(this, authority.player, callbacks).setId("local-music")
            .setSessionActivity(activity).build()
        addSession(session!!)
        scope.launch {
            try {
                storage = (application as MusicApplication).storage.await()
                authority.initialize(storage!!)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { storage?.error("Could not open the library: ${e.message}") }
        }
    }
    private val callbacks = object : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val local = controller.uid == applicationInfo.uid
            if (!local && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            if (local) for (command in ACTIONS) commands.add(SessionCommand(command, Bundle.EMPTY))
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailablePlayerCommands(NormalSessionPlayer.allowedCommands())
                .setAvailableSessionCommands(commands.build()).build()
        }
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand,
            args: Bundle): ListenableFuture<SessionResult> {
            if (controller.uid != applicationInfo.uid || command.customAction !in ACTIONS)
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            val future = SettableFuture.create<SessionResult>()
            scope.launch {
                try {
                    val store = storage ?: error("Library is still opening. Try again shortly.")
                    check(authority.ready) { "Library is still opening." }
                    val result = Bundle()
                    when (command.customAction) {
                        START -> {
                            val ids = requireNotNull(args.getStringArrayList("ids")).map(PlaybackAuthority::id)
                            val index = args.getInt("index", 0)
                            val resume = if (args.getBoolean("continue")) store.repository().continuePosition(ids[index]) ?: 0 else 0
                            authority.start(ids, index, resume)
                        }
                        NEXT -> authority.enqueue(PlaybackAuthority.id(requireNotNull(args.getString("id"))), true)
                        ADD -> authority.enqueue(PlaybackAuthority.id(requireNotNull(args.getString("id"))), false)
                        FAVORITE -> store.favorite(PlaybackAuthority.id(requireNotNull(args.getString("id"))))
                        SCAN -> store.requestScan()
                        CANCEL_SCAN -> store.cancelScan()
                        ADD_FOLDER -> store.addFolder(Uri.parse(requireNotNull(args.getString("uri"))), args.getInt("flags"))
                        PREFERENCES -> store.setPreferences(args.getString("appearance") ?: "System", args.getBoolean("autoScan"))
                        LYRICS_SAVE -> {
                            val id = PlaybackAuthority.id(requireNotNull(args.getString("id")))
                            var text = args.getString("text").orEmpty()
                            args.getString("uri")?.let { uri -> text = withContext(Dispatchers.IO) {
                                contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                                    val bytes = readLimited(input, 2_000_000)
                                    when {
                                        bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> String(bytes, Charsets.UTF_16LE)
                                        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> String(bytes, Charsets.UTF_16BE)
                                        else -> String(bytes, Charsets.UTF_8)
                                    }
                                } ?: error("Cannot read lyrics")
                            } }
                            LrcParser.parse(text, args.getBoolean("instrumental"))
                            store.repository().saveLyrics(SavedLyrics(UUID.randomUUID(), id, text, LyricsOrigin.USER_SELECTED,
                                instrumental = args.getBoolean("instrumental")), select = true)
                            store.refresh()
                        }
                        LYRICS_SELECT -> {
                            store.repository().selectLyrics(PlaybackAuthority.id(requireNotNull(args.getString("id"))), args.getString("version")?.let(UUID::fromString)); store.refresh()
                        }
                        LYRICS_OFFSET -> {
                            val repo = store.repository(); val id = PlaybackAuthority.id(requireNotNull(args.getString("id")))
                            val selected = requireNotNull(repo.selectedLyrics(id)) { "Select lyrics first." }
                            val offset = args.getLong("offset")
                            require(offset in -LrcParser.MAX_ADJUSTMENT_MS..LrcParser.MAX_ADJUSTMENT_MS)
                            repo.updateExtra(id) { it.copy(lyricsAdjustmentMs = offset) }
                            repo.saveLyrics(selected.copy(timingAdjustmentMs = offset)); store.refresh()
                        }
                        LYRICS_PROVIDER -> {
                            val id = PlaybackAuthority.id(requireNotNull(args.getString("id")))
                            val choice = store.chosenLyrics(requireNotNull(args.getString("receipt")))
                            LrcParser.parse(choice.text, choice.instrumental)
                            store.repository().saveLyrics(SavedLyrics(UUID.randomUUID(), id, choice.text, LyricsOrigin.USER_SELECTED,
                                providerId = "lrclib:${choice.id}", sourceUrl = choice.sourceUrl, instrumental = choice.instrumental), select = true)
                            store.refresh()
                        }
                        APP_TAGS -> {
                            val raw = JSONObject(requireNotNull(args.getString("patch")))
                            val patch = TagPatch(raw.keys().asSequence().associateWith { raw.getString(it) })
                            val ids = requireNotNull(args.getStringArrayList("ids")).map(PlaybackAuthority::id)
                            require(ids.size in 1..50)
                            store.operationMutex.withLock { store.repository().saveAppPatch(ids, patch) }
                            store.refresh()
                        }
                        APP_ART -> {
                            val ids = args.getStringArrayList("ids")?.map(PlaybackAuthority::id)
                                ?: listOf(PlaybackAuthority.id(requireNotNull(args.getString("id"))))
                            require(ids.size in 1..50)
                            val path = if (args.getBoolean("remove")) "" else withContext(Dispatchers.IO) {
                                LocalArtwork(this@PlaybackService).import(Uri.parse(requireNotNull(args.getString("uri"))), args.getBoolean("crop", true))
                            }
                            store.operationMutex.withLock { for (id in ids) store.repository().updateExtra(id) { it.copy(artworkOverride = path, overridesVersion = it.overridesVersion + 1) } }; store.refresh()
                        }
                        EDIT_PREPARE -> {
                            check(!store.state.value.scanning) { "Wait for the scan to finish before preparing file edits." }
                            val ids = requireNotNull(args.getStringArrayList("ids")).map(PlaybackAuthority::id)
                            val raw = JSONObject(args.getString("patch") ?: "{}")
                            val patch = TagPatch(raw.keys().asSequence().associateWith { raw.getString(it) })
                            val coverPath = args.getString("coverUri")?.let { uri -> withContext(Dispatchers.IO) {
                                LocalArtwork(this@PlaybackService).import(Uri.parse(uri), args.getBoolean("crop", true))
                            } } ?: args.getString("coverPath").orEmpty()
                            authority.closeForEdit(ids.toSet())
                            store.beginEdit()
                            try {
                                store.operationMutex.withLock { FileEdits(this@PlaybackService, store).prepare(requireNotNull(args.getString("batch")), ids,
                                    patch, args.getInt("coverMode"), coverPath) }
                            } finally { authority.reopenAfterEdit(ids.toSet()); store.endEdit() }
                        }
                        EDIT_COMMIT -> {
                            val ids = requireNotNull(args.getStringArrayList("edits"))
                            require(ids.size in 1..50)
                            val tracks = ids.mapNotNull { store.repository().edit(it)?.trackId?.let(PlaybackAuthority::id) }.toSet()
                            authority.closeForEdit(tracks); store.cancelScan()
                            store.beginEdit()
                            try { store.operationMutex.withLock { FileEdits(this@PlaybackService, store).commit(ids) } }
                            finally { authority.reopenAfterEdit(tracks); store.endEdit() }
                        }
                        EDIT_DISCARD -> FileEdits(this@PlaybackService, store).discard(requireNotNull(args.getStringArrayList("edits")))
                        EDIT_UNDO, EDIT_RECOVER -> {
                            val id = requireNotNull(args.getString("edit")); val row = requireNotNull(store.repository().edit(id))
                            authority.closeForEdit(setOf(PlaybackAuthority.id(row.trackId))); store.cancelScan()
                            store.beginEdit()
                            try { store.operationMutex.withLock {
                                val edits = FileEdits(this@PlaybackService, store)
                                if (command.customAction == EDIT_UNDO) edits.undo(id) else edits.recover(id, requireNotNull(args.getString("sha")))
                            } } finally { authority.reopenAfterEdit(setOf(PlaybackAuthority.id(row.trackId))); store.endEdit() }
                        }
                        EDIT_RECOVERY_REVIEW -> result.putString("sha", FileEdits(this@PlaybackService, store).recoveryHash(requireNotNull(args.getString("edit"))))
                        CLEAR_ERROR -> store.clearError()
                        EXPORT -> result.putString("report", JSONObject(authority.report()).put("library", JSONObject()
                            .put("trackCount", store.state.value.tracks.size).put("folderCount", store.state.value.folders.size)
                            .put("scanMessage", store.state.value.message)).put("fileEdits", org.json.JSONArray(store.state.value.edits.map {
                                JSONObject().put("id", it.id).put("name", it.displayName).put("phase", it.phase).put("result", it.result)
                            })).toString(2))
                    }
                    future.set(SessionResult(SessionResult.RESULT_SUCCESS, result))
                } catch (e: CancellationException) { future.cancel(false); throw e }
                catch (e: Exception) {
                    storage?.error(e.message ?: "Command could not be completed.")
                    future.set(SessionResult(SessionError.ERROR_BAD_VALUE))
                }
            }
            return future
        }
        private fun root() = MediaItem.Builder().setMediaId("songs").setMediaMetadata(MediaMetadata.Builder()
            .setTitle("Local Music").setIsBrowsable(true).setIsPlayable(false).build()).build()
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(root(), params))
        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val item = if (mediaId == "songs") root() else authority.tracks.firstOrNull { it.id.value.toString() == mediaId }?.let(authority::mediaItem)
            return Futures.immediateFuture(if (item == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) else LibraryResult.ofItem(item, null))
        }
        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != "songs" || page < 0 || pageSize !in 1..100) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            val start = (page.toLong() * pageSize).coerceAtMost(authority.tracks.size.toLong()).toInt()
            val items = authority.tracks.drop(start).take(pageSize).map(authority::mediaItem)
            return Futures.immediateFuture(LibraryResult.ofItemList(items, params))
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        if (::authority.isInitialized) authority.release()
        session?.let { removeSession(it); it.release() }; session = null
        scope.cancel(); super.onDestroy()
    }
    companion object {
        const val START = "com.localmusic.player.START_COLLECTION"
        const val NEXT = "com.localmusic.player.PLAY_NEXT"
        const val ADD = "com.localmusic.player.ADD_QUEUE"
        const val FAVORITE = "com.localmusic.player.FAVORITE"
        const val SCAN = "com.localmusic.player.SCAN"
        const val CANCEL_SCAN = "com.localmusic.player.CANCEL_SCAN"
        const val CLEAR_ERROR = "com.localmusic.player.CLEAR_ERROR"
        const val EXPORT = "com.localmusic.player.EXPORT_REPORT"
        const val ADD_FOLDER = "com.localmusic.player.ADD_FOLDER"
        const val PREFERENCES = "com.localmusic.player.PREFERENCES"
        const val LYRICS_SAVE = "com.localmusic.player.LYRICS_SAVE"
        const val LYRICS_SELECT = "com.localmusic.player.LYRICS_SELECT"
        const val LYRICS_OFFSET = "com.localmusic.player.LYRICS_OFFSET"
        const val LYRICS_PROVIDER = "com.localmusic.player.LYRICS_PROVIDER"
        const val APP_TAGS = "com.localmusic.player.APP_TAGS"
        const val APP_ART = "com.localmusic.player.APP_ART"
        const val EDIT_PREPARE = "com.localmusic.player.EDIT_PREPARE"
        const val EDIT_COMMIT = "com.localmusic.player.EDIT_COMMIT"
        const val EDIT_DISCARD = "com.localmusic.player.EDIT_DISCARD"
        const val EDIT_UNDO = "com.localmusic.player.EDIT_UNDO"
        const val EDIT_RECOVER = "com.localmusic.player.EDIT_RECOVER"
        const val EDIT_RECOVERY_REVIEW = "com.localmusic.player.EDIT_RECOVERY_REVIEW"
        val ACTIONS = setOf(START, NEXT, ADD, FAVORITE, SCAN, CANCEL_SCAN, CLEAR_ERROR, EXPORT, ADD_FOLDER, PREFERENCES,
            LYRICS_SAVE, LYRICS_SELECT, LYRICS_OFFSET, LYRICS_PROVIDER, APP_TAGS, APP_ART, EDIT_PREPARE, EDIT_COMMIT, EDIT_DISCARD,
            EDIT_UNDO, EDIT_RECOVER, EDIT_RECOVERY_REVIEW)
    }
}
