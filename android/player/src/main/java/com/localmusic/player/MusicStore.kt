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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.musicPreferences by preferencesDataStore("local-music-settings")
data class MusicPreferences(val appearance:String="System",val autoScan:Boolean=false,val homeGrid:Boolean=true,
    val audio:AudioSettings=AudioSettings(),val widgets:WidgetSettings=WidgetSettings(),val artists:Map<String,ArtistProfile> = emptyMap(),val artworkSize:String="Large")
data class LyricsChoice(val receipt: String, val candidate: LyricsCandidate)

data class LibraryState(val tracks: List<Track> = emptyList(), val continues: Map<TrackId, Long> = emptyMap(),
    val scanning: Boolean = false, val scanned: Int = 0, val message: String = "", val error: String = "",
    val details: Map<TrackId, LibraryExtras> = emptyMap(), val folders: List<FolderGrant> = emptyList(), val revision: Long = 0,
    val edits: List<FileEditRow> = emptyList(), val editBusy: Boolean = false,
    val playlists: List<Pair<Playlist, Int>> = emptyList(),
    val trackById: Map<TrackId, Track> = emptyMap(), val artworkPaths: Map<TrackId, String> = emptyMap())

class MusicApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val storage by lazy { scope.async { MusicStore(this@MusicApplication, scope).also { it.initialize() } } }
}

/** Owns database access, never an audio engine or playback position timer. */
class MusicStore(private val context: Context, private val scope: CoroutineScope) {
    val artistLookup by lazy{ArtistLookup(context,this)}
    private val editClaims = mutableMapOf<TrackId, Int>()
    private var editOperations = 0
    @Synchronized fun beginEdit() { editOperations++; mutable.update { it.copy(editBusy = true) } }
    @Synchronized fun endEdit() { editOperations = (editOperations - 1).coerceAtLeast(0); mutable.update { it.copy(editBusy = editOperations > 0) } }
    @Synchronized fun claimEdits(ids: Set<TrackId>) { for (id in ids) editClaims[id] = (editClaims[id] ?: 0) + 1 }
    @Synchronized fun releaseEdits(ids: Set<TrackId>) { for (id in ids) { val count = (editClaims[id] ?: 0) - 1; if (count <= 0) editClaims.remove(id) else editClaims[id] = count } }
    @Synchronized fun isEditing(id: TrackId?) = id != null && (editClaims[id] ?: 0) > 0
    private val choices = java.util.concurrent.ConcurrentHashMap<String, LyricsCandidate>()
    suspend fun searchLyrics(query:String,source:LyricsSource=LyricsSource.LRCLIB,artist:String="",title:String=""):List<LyricsChoice> = withContext(Dispatchers.IO) {
        val results=runInterruptible{when(source){LyricsSource.LRCLIB->lyricsSearch.search(query);LyricsSource.OVH->lyricsOvh.search(artist,title)}}
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
    private val lyricsOvh by lazy { LyricsOvhClient(object:LyricsCooldown{
        private val file=android.util.AtomicFile(java.io.File(context.filesDir,"lyrics-ovh-cooldown"))
        override fun read():Long=try{file.openRead().bufferedReader().use{it.readText().toLong()}}catch(_:java.io.FileNotFoundException){0}
        override fun write(untilMs:Long){val output=file.startWrite();try{output.write(untilMs.toString().toByteArray());file.finishWrite(output)}catch(e:Exception){file.failWrite(output);throw e}}
    }) }
    private val database = scope.async { openPlayerDatabase(context) }
    suspend fun repository() = PlayerRepository(database.await())
    private val mutable = MutableStateFlow(LibraryState())
    val state = mutable.asStateFlow()
    private sealed interface SessionTask {
        data class Save(val snapshot: QueueSnapshot): SessionTask
        data class Barrier(val result: CompletableDeferred<Unit>): SessionTask
    }
    @Volatile var maintenance = false
        private set
    fun setMaintenance(value: Boolean) { maintenance = value }
    private val saves = Channel<SessionTask>(Channel.UNLIMITED)
    val operationMutex = Mutex()
    private fun readPreferences(values:Preferences):BackupPreferences {
        val extended=values[stringPreferencesKey("listeningTools")]?.let(PreferenceCodec::decode)?:BackupPreferences()
        return extended.copy(appearance=values[stringPreferencesKey("appearance")]?:extended.appearance,
            autoScan=values[booleanPreferencesKey("autoScan")]?:extended.autoScan,homeGrid=values[booleanPreferencesKey("homeGrid")]?:extended.homeGrid)
    }
    val preferences = context.musicPreferences.data.catch { emit(emptyPreferences()) }.map { values ->
        val p=readPreferences(values);MusicPreferences(p.appearance,p.autoScan,p.homeGrid,p.audio,p.widgets,p.artists,p.artworkSize)
    }.stateIn(scope, SharingStarted.Eagerly, MusicPreferences())
    suspend fun setPreferences(appearance: String, autoScan: Boolean) {
        AppBackups(context,this).recoverPreferences()
        require(appearance in setOf("System", "Light", "Dark", "AMOLED"))
        context.musicPreferences.edit { it[stringPreferencesKey("appearance")] = appearance; it[booleanPreferencesKey("autoScan")] = autoScan }
    }
    suspend fun setHomeGrid(grid:Boolean){AppBackups(context,this).recoverPreferences();context.musicPreferences.edit{it[booleanPreferencesKey("homeGrid")]=grid}}
    suspend fun backupPreferences(): BackupPreferences {
        return readPreferences(context.musicPreferences.data.first())
    }
    private suspend fun updateTools(transform:(BackupPreferences)->BackupPreferences){
        AppBackups(context,this).recoverPreferences()
        context.musicPreferences.edit{values->
        val encoded=PreferenceCodec.encode(transform(readPreferences(values)));PreferenceCodec.decode(encoded)
        values[stringPreferencesKey("listeningTools")]=encoded
    }}
    suspend fun setAudio(value:AudioSettings)=updateTools{it.copy(audio=value)}
    suspend fun setWidgets(value:WidgetSettings)=updateTools{it.copy(widgets=value)}
    suspend fun setArtworkSize(value:String) {
        require(value in setOf("Compact","Large","Full width"))
        updateTools{it.copy(artworkSize=value)}
    }
    suspend fun setArtist(name:String,value:ArtistProfile?)=updateTools{old->
        val key=ArtistProfile.key(name);require(key.isNotBlank()&&key.length<=300)
        val profiles=if(value==null)old.artists-key else old.artists+(key to value)
        require(profiles.size<=100){"Saved artist limit is 100. Remove a saved match before adding another."}
        old.copy(artists=profiles)
    }
    suspend fun restorePreferences(value: BackupPreferences) {
        require(value.appearance in setOf("System", "Light", "Dark", "AMOLED"))
        context.musicPreferences.edit {
            it[stringPreferencesKey("appearance")] = value.appearance
            it[booleanPreferencesKey("autoScan")] = value.autoScan
            it[booleanPreferencesKey("homeGrid")] = value.homeGrid
            it[stringPreferencesKey("listeningTools")]=PreferenceCodec.encode(value)
        }
    }
    init {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { if (preferences.value.autoScan) requestScan() }
        }
        context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        scope.launch { preferences.map { it.autoScan }.distinctUntilChanged().collect { enabled -> if (enabled) requestScan() } }
        scope.launch {
            var saveFailure: Exception? = null
            for (task in saves) {
                if (task is SessionTask.Barrier) {
                    val failure = saveFailure
                    if (failure == null) task.result.complete(Unit) else task.result.completeExceptionally(failure)
                    continue
                }
                val snapshot = (task as SessionTask.Save).snapshot
                try {
                repository().saveSession(snapshot, System.currentTimeMillis())
                saveFailure = null
                val track = snapshot.history.getOrNull(snapshot.historyCursor)?.trackId
                if (track != null) {
                    val position = repository().continuePosition(track)
                    mutable.update { it.copy(continues = it.continues - track + if (position == null) emptyMap() else mapOf(track to position)) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { saveFailure = e; error("Session could not be saved: ${e.message}") }
            }
        }
    }
    fun persist(snapshot: QueueSnapshot) { if (!maintenance) saves.trySend(SessionTask.Save(snapshot)) }
    suspend fun flushSessionSaves() { val done = CompletableDeferred<Unit>(); saves.send(SessionTask.Barrier(done)); done.await() }
    fun error(text: String) { mutable.update { it.copy(error = text) } }
    fun clearError() { mutable.update { it.copy(error = "") } }
    suspend fun initialize() {
        AppBackups(context, this).recoverPreferences()
        FileEdits(context, this).inspectRecovery()
        refresh()
    }
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val repo = repository()
        val extras = repo.extras()
        val folders = repo.folderGrants()
        val selected = folders.mapTo(HashSet()) { it.id }
        val allTracks = repo.tracks().map { original ->
            val t = if (FolderScanPolicy.includes(original, selected)) original else original.copy(availability = Availability.MISSING)
            extras[t.id]?.let { detail ->
            t.copy(tags = TagPatch(detail.tagFields).apply(detail.tagsOverride ?: t.tags))
        } ?: t }
        val tracks = allTracks.filter { FolderScanPolicy.includes(it, selected) }
        val resumes = repo.continuePositions(tracks)
        val existingArtwork = extras.values.mapNotNull { it.artworkOverride ?: it.artworkPath }
            .filter { it.isNotBlank() }.distinct().filter { java.io.File(it).exists() }.toHashSet()
        val artworkPaths = extras.mapNotNull { (id, detail) ->
            (detail.artworkOverride ?: detail.artworkPath)?.takeIf { it in existingArtwork }?.let { id to it }
        }.toMap()
        val edits = repo.edits()
        val playlists = repo.playlists()
        mutable.update { it.copy(tracks = tracks, continues = resumes, details = extras, folders = folders, revision = it.revision + 1, edits = edits, playlists = playlists, trackById = allTracks.associateBy { it.id }, artworkPaths = artworkPaths) }
    }
    suspend fun favorite(id: TrackId) {
        val repo = repository(); val track = repo.track(id) ?: return
        repo.setFavorite(id, !track.favorite); refresh()
    }

    /** Playlist edits touch only playlist tables; skipping the full track refresh keeps reorders responsive. */
    suspend fun refreshPlaylists() {
        val playlists = repository().playlists()
        mutable.update { it.copy(playlists = playlists, revision = it.revision + 1) }
    }
    fun requestScan() {
        if (maintenance) return
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
    /** Validated on the IO refresh path; rendering and media ticks never stat artwork files. */
    fun artwork(id: TrackId): String? = state.value.artworkPaths[id]

    companion object {
        fun audioPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    }
}
