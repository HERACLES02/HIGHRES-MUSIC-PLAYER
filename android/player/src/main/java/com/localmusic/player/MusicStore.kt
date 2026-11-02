package com.localmusic.player

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import androidx.work.*
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import com.localmusic.core.*
import com.localmusic.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.musicPreferences by preferencesDataStore("local-music-settings")
data class MusicPreferences(val appearance: String = "System", val autoScan: Boolean = false)
data class LyricsChoice(val receipt: String, val candidate: LyricsCandidate)

data class LibraryState(val tracks: List<Track> = emptyList(), val continues: Map<TrackId, Long> = emptyMap(),
    val scanning: Boolean = false, val scanned: Int = 0, val message: String = "", val error: String = "",
    val details: Map<TrackId, LibraryExtras> = emptyMap(), val folders: List<FolderGrant> = emptyList(), val revision: Long = 0,
    val edits: List<FileEditRow> = emptyList(), val editBusy: Boolean = false)

class MusicApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val storage by lazy { scope.async { MusicStore(this@MusicApplication, scope).also { it.initialize() } } }
}

/** Owns database access, never an audio engine or playback position timer. */
class MusicStore(private val context: Context, private val scope: CoroutineScope) {
    private val editClaims = mutableMapOf<TrackId, Int>()
    private var editOperations = 0
    @Synchronized fun beginEdit() { editOperations++; mutable.update { it.copy(editBusy = true) } }
    @Synchronized fun endEdit() { editOperations = (editOperations - 1).coerceAtLeast(0); mutable.update { it.copy(editBusy = editOperations > 0) } }
    @Synchronized fun claimEdits(ids: Set<TrackId>) { for (id in ids) editClaims[id] = (editClaims[id] ?: 0) + 1 }
    @Synchronized fun releaseEdits(ids: Set<TrackId>) { for (id in ids) { val count = (editClaims[id] ?: 0) - 1; if (count <= 0) editClaims.remove(id) else editClaims[id] = count } }
    @Synchronized fun isEditing(id: TrackId?) = id != null && (editClaims[id] ?: 0) > 0
    private val choices = java.util.concurrent.ConcurrentHashMap<String, LyricsCandidate>()
    suspend fun searchLyrics(query: String): List<LyricsChoice> = withContext(Dispatchers.IO) {
        val results = lyricsSearch.search(query)
        if (choices.size > 80) choices.clear()
        results.map { candidate -> val key = java.util.UUID.randomUUID().toString(); choices[key] = candidate; LyricsChoice(key, candidate) }
    }
    fun chosenLyrics(receipt: String): LyricsCandidate = requireNotNull(choices[receipt]) { "Search result expired. Search again before saving." }
    val lyricsSearch by lazy { LrclibClient(object : LyricsCooldown {
        private val file = android.util.AtomicFile(java.io.File(context.filesDir, "lyrics-search-cooldown"))
        override fun read(): Long = try { file.openRead().bufferedReader().use { it.readText().toLong() } } catch (_: java.io.FileNotFoundException) { 0 }
        override fun write(untilMs: Long) {
            val output = file.startWrite()
            try { output.write(untilMs.toString().toByteArray()); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
        }
    }) }
    private val database = scope.async { openPlayerDatabase(context) }
    suspend fun repository() = PlayerRepository(database.await())
    private val mutable = MutableStateFlow(LibraryState())
    val state = mutable.asStateFlow()
    private val saves = Channel<QueueSnapshot>(Channel.UNLIMITED)
    val operationMutex = Mutex()
    val preferences = context.musicPreferences.data.catch { emit(emptyPreferences()) }.map {
        MusicPreferences(it[stringPreferencesKey("appearance")] ?: "System", it[booleanPreferencesKey("autoScan")] ?: false)
    }.stateIn(scope, SharingStarted.Eagerly, MusicPreferences())
    suspend fun setPreferences(appearance: String, autoScan: Boolean) {
        require(appearance in setOf("System", "Light", "Dark", "AMOLED"))
        context.musicPreferences.edit { it[stringPreferencesKey("appearance")] = appearance; it[booleanPreferencesKey("autoScan")] = autoScan }
    }
    init {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { if (preferences.value.autoScan) requestScan() }
        }
        context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        scope.launch { preferences.map { it.autoScan }.distinctUntilChanged().collect { enabled -> if (enabled) requestScan() } }
        scope.launch {
            for (snapshot in saves) try {
                repository().saveSession(snapshot, System.currentTimeMillis())
                val track = snapshot.history.getOrNull(snapshot.historyCursor)?.trackId
                if (track != null) {
                    val position = repository().continuePosition(track)
                    mutable.update { it.copy(continues = it.continues - track + if (position == null) emptyMap() else mapOf(track to position)) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error("Session could not be saved: ${e.message}") }
        }
    }
    fun persist(snapshot: QueueSnapshot) { saves.trySend(snapshot) }
    fun error(text: String) { mutable.update { it.copy(error = text) } }
    fun clearError() { mutable.update { it.copy(error = "") } }
    suspend fun initialize() {
        FileEdits(context, this).inspectRecovery()
        refresh()
    }
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val repo = repository()
        if (context.checkSelfPermission(audioPermission()) != PackageManager.PERMISSION_GRANTED) {
            for (track in repo.tracks()) if (track.availability == Availability.AVAILABLE && track.sources.all { it.kind == SourceKind.MEDIA_STORE })
                repo.markUnavailable(track.id, Availability.PERMISSION_LOST)
        }
        val extras = repo.extras()
        val tracks = repo.tracks().map { t -> extras[t.id]?.let { detail ->
            t.copy(tags = TagPatch(detail.tagFields).apply(detail.tagsOverride ?: t.tags))
        } ?: t }
        val resumes = tracks.mapNotNull { t -> repo.continuePosition(t.id)?.let { t.id to it } }.toMap()
        val folders = repo.folderGrants()
        val edits = repo.edits()
        mutable.update { it.copy(tracks = tracks, continues = resumes, details = extras, folders = folders, revision = it.revision + 1, edits = edits) }
    }
    suspend fun favorite(id: TrackId) {
        val repo = repository(); val track = repo.track(id) ?: return
        repo.setFavorite(id, !track.favorite); refresh()
    }
    fun requestScan() {
        mutable.update { it.copy(message = "Library scan queued.") }
        WorkManager.getInstance(context).enqueueUniqueWork("local-music-library", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<LibraryScanWorker>().build())
    }
    fun cancelScan() { WorkManager.getInstance(context).cancelUniqueWork("local-music-library") }
    fun scanProgress(count: Int, text: String) { mutable.update { it.copy(scanned = count, message = text) } }
    suspend fun scan() = operationMutex.withLock {
        mutable.update { it.copy(scanning = true, scanned = 0, error = "") }
        try { LibraryScanner(context, this).run() }
        finally { mutable.update { it.copy(scanning = false) }; withContext(NonCancellable) { refresh() } }
    }
    suspend fun addFolder(uri: Uri, flags: Int) {
        require(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) { "Folder read access was not granted." }
        val repo = repository(); val old = repo.folderGrants().firstOrNull { it.treeUri == uri.toString() }
        repo.saveFolderGrant(FolderGrant(old?.id ?: java.util.UUID.randomUUID(), uri.toString(), flags, System.currentTimeMillis()))
        refresh(); requestScan()
    }
    fun artwork(id: TrackId): String? {
        val detail = state.value.details[id] ?: return null
        return (detail.artworkOverride ?: detail.artworkPath)?.takeIf { it.isNotBlank() && java.io.File(it).exists() }
    }
    companion object {
        fun audioPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    }
}
