@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.os.Process
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.localmusic.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.*
import org.json.JSONObject
import com.localmusic.data.FileEditRow
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val playlistUi by viewModels<PlaylistUiState>()
    private var controller by mutableStateOf<MediaController?>(null)
    private var sessionState by mutableStateOf(JSONObject())
    private var connectionError by mutableStateOf("")
    private var permissionMessage by mutableStateOf("")
    private val pendingCommands = ArrayDeque<Pair<String, Bundle>>()
    private lateinit var future: ListenableFuture<MediaController>
    private val exportScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingReport: String? = null
    private var coverTargets = emptyList<String>()
    private var pickedCover by mutableStateOf<Uri?>(null)
    private var lyricTarget: Track? = null
    private var importedLyrics by mutableStateOf<Pair<Track, String>?>(null)
    private var pendingLyricExport: String? = null
    private var pendingWrites = emptyList<String>()
    private var pendingWriteAction: Pair<String, Bundle>? = null
    private var confirmRestore by mutableStateOf<Pair<FileEditRow, String?>?>(null)
    private val folderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data; val uri = data?.data
        if (result.resultCode == RESULT_OK && uri != null) try {
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)
            send(PlaybackService.ADD_FOLDER, Bundle().apply { putString("uri", uri.toString()); putInt("flags", flags) })
        } catch (e: Exception) { permissionMessage = "Could not retain folder access: ${e.message}" }
    }
    private val coverPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pickedCover = uri }
    private val playlistFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) playlistUi.readPlaylist(application as MusicApplication, uri)
    }
    private val playlistFileExport = registerForActivityResult(object : ActivityResultContracts.CreateDocument("text/plain") {
        override fun createIntent(context: android.content.Context, input: String): Intent = super.createIntent(context, input).apply {
            type = when (input.substringAfterLast('.')) {
                "m3u8" -> "application/vnd.apple.mpegurl"
                "m3u" -> "audio/x-mpegurl"
                else -> "audio/x-scpls"
            }
        }
    }) { uri ->
        playlistUi.savePlaylist(application as MusicApplication, uri)
    }
    private fun exportPlaylist(playlist: Playlist, format: PlaylistFileFormat) {
        exportScope.launch {
            val filename = playlistUi.prepareExport(application as MusicApplication, playlist, format) ?: return@launch
            try { playlistFileExport.launch(filename) }
            catch (e: Exception) { playlistUi.pendingExport.value = null; playlistUi.fileMessage.value = "Could not open the save picker: ${e.message}" }
        }
    }
    private fun confirmPlaylistImport() {
        val ui = playlistUi
        val preview = ui.importPreview.value ?: return
        val connected = controller ?: return
        if (!sessionState.optBoolean("ready") || ui.fileBusy.value) return
        val ids = preview.matches.mapNotNull { it.id }
        if ((ids.isEmpty() && preview.matches.isNotEmpty()) || ui.importName.value.isBlank()) return
        ui.fileBusy.value = true
        val call = try { connected.sendCustomCommand(SessionCommand(PlaybackService.PLAYLIST_IMPORT, Bundle.EMPTY), Bundle().apply {
            putString("name", ui.importName.value)
            putStringArrayList("ids", ArrayList(ids.map { it.value.toString() }))
        }) } catch (e: Exception) {
            ui.fileBusy.value = false; ui.fileMessage.value = "Could not submit import: ${e.message}"; return
        }
        call.addListener({
            try {
                if (call.get().resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS) {
                    ui.importPreview.value = null
                    ui.fileMessage.value = "Imported ${ids.size} entries into a new playlist; ${preview.matches.size - ids.size} skipped."
                } else ui.fileMessage.value = "Import could not complete. Check the library error and try again."
            } catch (e: Exception) { ui.fileMessage.value = "Import result unavailable. Check the playlist list before retrying." }
            finally { ui.fileBusy.value = false }
        }, ContextCompat.getMainExecutor(applicationContext))
    }
    private val lyricPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val track = lyricTarget
        if (uri != null && track != null) exportScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { requireNotNull(contentResolver.openInputStream(uri)).use { input ->
                    val bytes = readLimited(input, 400000)
                    when {
                        bytes.size > 1 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> String(bytes, Charsets.UTF_16LE)
                        bytes.size > 1 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> String(bytes, Charsets.UTF_16BE)
                        else -> String(bytes, Charsets.UTF_8)
                    }
                } }
                require(text.length <= 200000) { "Imported lyrics exceed the editor's 200,000-character limit." }
                importedLyrics = track to text
            } catch (e: CancellationException) { throw e } catch (e: Exception) { permissionMessage = "Could not import lyrics: ${e.message}" }
        }
    }
    private val lyricExport = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = pendingLyricExport; pendingLyricExport = null
        if (uri != null && text != null) exportScope.launch {
            try { withContext(Dispatchers.IO) { requireNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(text.toByteArray(Charsets.UTF_8)) } }; permissionMessage = "Original lyric text exported; the app timing adjustment stays separate." }
            catch (e: CancellationException) { throw e } catch (e: Exception) { permissionMessage = "Export failed: ${e.message}" }
        }
    }
    private val writePermission = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) pendingWriteAction?.let { send(it.first, it.second) }
        else permissionMessage = "Write permission was not granted. Prepared copies and originals are retained."
        pendingWrites = emptyList()
        pendingWriteAction = null
    }
    private fun commit(rows: List<FileEditRow>) {
        pendingWrites = rows.map { it.id }
        authorizeWrite(rows, PlaybackService.EDIT_COMMIT, Bundle().apply { putStringArrayList("edits", ArrayList(pendingWrites)) })
    }
    private fun authorizeWrite(rows: List<FileEditRow>, action: String, args: Bundle) {
        pendingWriteAction = action to args
        val shared = rows.map { Uri.parse(it.sourceUri) }.filter { it.authority == "media" && checkUriPermission(it, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED }
        try {
            if (shared.isNotEmpty()) writePermission.launch(IntentSenderRequest.Builder(MediaStore.createWriteRequest(contentResolver, shared).intentSender).build())
            else { send(action, args); pendingWrites = emptyList(); pendingWriteAction = null }
        } catch (e: Exception) { permissionMessage = "File-write authorization failed: ${e.message}" }
    }
    private fun reviewRecovery(row: FileEditRow) {
        val call = controller?.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.EDIT_RECOVERY_REVIEW, Bundle.EMPTY), Bundle().apply { putString("edit", row.id) }) ?: return
        call.addListener({
            val hash = runCatching { call.get().extras.getString("sha") }.getOrNull()
            if (hash != null) confirmRestore = row to hash else permissionMessage = "Recovery preview could not be verified. Check file access; the backup is retained."
        }, ContextCompat.getMainExecutor(this))
    }
    private val exportReport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val report = pendingReport; pendingReport = null
        if (uri != null && report != null) exportScope.launch {
            try {
                withContext(Dispatchers.IO) { requireNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(report.toByteArray(Charsets.UTF_8)) } }
                permissionMessage = "Playback report saved."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { permissionMessage = "Could not save report: ${e.message}" }
        }
    }
    private fun saveReport() {
        val request = controller?.sendCustomCommand(SessionCommand(PlaybackService.EXPORT, Bundle.EMPTY), Bundle.EMPTY) ?: return
        request.addListener({
            try {
                pendingReport = requireNotNull(request.get().extras.getString("report"))
                exportReport.launch("local-music-normal.json")
            } catch (e: Exception) { permissionMessage = "Could not prepare report: ${e.message}" }
        }, ContextCompat.getMainExecutor(this))
    }
    private val audioAccess = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) send(PlaybackService.SCAN) else permissionMessage = "Music access was denied. You can allow it and scan again."
    }
    private val notificationAccess = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionMessage = if (granted) "Playback notifications allowed." else "Notifications are blocked. Allow them in Android settings for playback controls."
    }
    private fun send(action: String, args: Bundle = Bundle.EMPTY) {
        val connected = controller
        if (connected == null || !sessionState.optBoolean("ready")) {
            if (pendingCommands.size < 20) pendingCommands.addLast(action to Bundle(args))
            else permissionMessage = "The library is still opening. Wait before sending more commands."
            return
        }
        connected.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString("writeAction")?.let { action ->
            savedInstanceState.getBundle("writeArguments")?.let { pendingWriteAction = action to it }
        }
        coverTargets = savedInstanceState?.getStringArrayList("coverTargets").orEmpty()
        pickedCover = savedInstanceState?.getString("pickedCover")?.let(Uri::parse)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) { readState(extras) }
                override fun onDisconnected(controller: MediaController) {
                    this@MainActivity.controller = null
                    connectionError = "Playback service disconnected. Close and reopen Local Music."
                }
            }).buildAsync()
        future.addListener({
            try { controller = future.get(); readState(controller!!.sessionExtras) }
            catch (e: Exception) { connectionError = "Could not connect playback: ${e.cause?.message ?: e.message}" }
        }, ContextCompat.getMainExecutor(this))
        setContent {
            var store by remember { mutableStateOf<MusicStore?>(null) }
            var openError by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                try { store = (application as MusicApplication).storage.await() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { openError = "Could not open library: ${e.message}" }
            }
            val preferences = store?.preferences?.collectAsState()?.value ?: MusicPreferences()
            val dark = when (preferences.appearance) { "Light" -> false; "Dark", "AMOLED" -> true; else -> isSystemInDarkTheme() }
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFFA6DFC5), secondary = Color(0xFFB5CCD2),
                background = if (preferences.appearance == "AMOLED") Color.Black else Color(0xFF101412),
                surface = if (preferences.appearance == "AMOLED") Color.Black else Color(0xFF101412))
                else lightColorScheme(primary = Color(0xFF245E4B), secondary = Color(0xFF3B6068))) {
                Surface(Modifier.fillMaxSize()) {
                    val library = store?.state?.collectAsState()?.value ?: LibraryState()
                    MusicScreen(playlistUi, ::confirmPlaylistImport, { playlistFilePicker.launch(arrayOf("*/*")) }, ::exportPlaylist, library, controller, sessionState,
                        listOf(connectionError, openError, permissionMessage).filter { it.isNotBlank() }.joinToString("\n"),
                        ::send, store, preferences, FeatureActions(
                            pickFolder = { folderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)) },
                            pickCover = { targets -> coverTargets = targets.map { it.id.value.toString() }; coverPicker.launch(arrayOf("image/*")) },
                            importLyrics = { track -> lyricTarget = track; lyricPicker.launch(arrayOf("*/*")) },
                            exportLyrics = { track, text -> pendingLyricExport = text; lyricExport.launch(track.tags.title.replace(Regex("[\\/:*?\"<>|]"), "_") + ".lrc") },
                            commit = ::commit, undo = { confirmRestore = it to null }, recovery = ::reviewRecovery), onScan = {
                            permissionMessage = ""
                            if (checkSelfPermission(MusicStore.audioPermission()) == PackageManager.PERMISSION_GRANTED || library.folders.isNotEmpty()) send(PlaybackService.SCAN)
                            else audioAccess.launch(MusicStore.audioPermission())
                        }, onExport = ::saveReport, onNotifications = {
                            if (Build.VERSION.SDK_INT >= 33) notificationAccess.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
                        })
                    importedLyrics?.let { (track, text) -> LyricsEditorDialog(track, text, sessionState.optLong("position"), ::send) { importedLyrics = null } }
                    pickedCover?.let { uri ->
                        var crop by remember(uri) { mutableStateOf(true) }
                        AlertDialog(onDismissRequest = { pickedCover = null }, title = { Text("Review cover artwork") }, text = {
                            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                                val imageModifier = if (crop) Modifier.fillMaxWidth().aspectRatio(1f) else Modifier.fillMaxWidth().height(220.dp)
                                AsyncImage(uri, "Selected cover", modifier = imageModifier.background(Color.Black), contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
                                Row { Checkbox(crop, { crop = it }); Text("Center square crop") }
                                Text("Resize to at most 1024 pixels. Choose app-only use or prepare a music-file preview.") }
                        }, confirmButton = { TextButton(onClick = { send(PlaybackService.APP_ART, Bundle().apply { putStringArrayList("ids", ArrayList(coverTargets)); putString("uri", uri.toString()); putBoolean("crop", crop) }); pickedCover = null }) { Text("Use in this app") } },
                            dismissButton = { Row { TextButton(enabled = !library.scanning, onClick = {
                                val batch = UUID.randomUUID().toString()
                                send(PlaybackService.EDIT_PREPARE, Bundle().apply { putString("batch", batch); putStringArrayList("ids", ArrayList(coverTargets)); putString("patch", "{}"); putInt("coverMode", 1); putString("coverUri", uri.toString()); putBoolean("crop", crop) })
                                pickedCover = null; permissionMessage = "Cover preview is being prepared. Open Settings → Edits and recovery to review it before writing."
                            }) { Text("Embed…") }; TextButton(onClick = { pickedCover = null }) { Text("Cancel") } } })
                    }
                    confirmRestore?.let { (row, hash) -> AlertDialog(onDismissRequest = { confirmRestore = null }, title = { Text("Restore original ${row.displayName}?") },
                        text = { Text(if (hash == null) "Undo restores the retained original. A conflicting newer file change will be rejected." else "This interrupted file will be replaced with the verified original backup. Any change since this recovery preview will be rejected.") },
                        confirmButton = { TextButton(onClick = { authorizeWrite(listOf(row), if (hash == null) PlaybackService.EDIT_UNDO else PlaybackService.EDIT_RECOVER,
                            Bundle().apply { putString("edit", row.id); if (hash != null) putString("sha", hash) }); confirmRestore = null }) { Text("Restore original") } },
                        dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } }) }
                }
            }
        }
    }
    private fun readState(bundle: Bundle) {
        sessionState = runCatching { JSONObject(bundle.getString("state") ?: "{}") }.getOrDefault(JSONObject())
        if (controller != null && sessionState.optBoolean("ready")) {
            val queued = pendingCommands.toList(); pendingCommands.clear()
            for ((action, args) in queued) send(action, args)
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        pendingWriteAction?.let { outState.putString("writeAction", it.first); outState.putBundle("writeArguments", it.second) }
        outState.putStringArrayList("coverTargets", ArrayList(coverTargets))
        outState.putString("pickedCover", pickedCover?.toString())
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { MediaController.releaseFuture(future); exportScope.cancel(); super.onDestroy() }
}

private fun time(ms: Long): String = "%d:%02d".format(ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)
private fun artist(track: Track) = track.tags.artists.joinToString(", ").ifBlank { "Unknown artist" }
private fun idBundle(track: Track) = Bundle().apply { putString("id", track.id.value.toString()) }
private fun collectionBundle(tracks: List<Track>, index: Int, resume: Boolean = false) = Bundle().apply {
    putStringArrayList("ids", ArrayList(tracks.map { it.id.value.toString() })); putInt("index", index); putBoolean("continue", resume)
}

@Composable
private fun MusicScreen(playlistUi: PlaylistUiState, confirmImport: () -> Unit, pickPlaylist: () -> Unit,
    exportPlaylist: (Playlist, PlaylistFileFormat) -> Unit, library: LibraryState, controller: MediaController?, playback: JSONObject, message: String,
    send: (String, Bundle) -> Unit, store: MusicStore?, preferences: MusicPreferences, actions: FeatureActions,
    onScan: () -> Unit, onNotifications: () -> Unit, onExport: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf("Home") }
    var search by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("Songs") }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var newest by rememberSaveable { mutableStateOf(false) }
    var details by remember { mutableStateOf<Track?>(null) }
    var editing by remember { mutableStateOf<List<Track>?>(null) }
    var nativeBatch by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<TrackId>()) }
    var licenses by remember { mutableStateOf(false) }
    var playlistDetail by playlistUi.detail
    var playlistDetailLoad by remember { mutableStateOf<Pair<PlaylistId, List<PlaylistEntry>>?>(null) }
    var playlistPicker by playlistUi.picker
    var playlistPickerName by playlistUi.pickerName
    var playlistNaming by playlistUi.naming
    var playlistCreating by playlistUi.creating
    var playlistNamingText by playlistUi.namingText
    var playlistDeleting by playlistUi.deleting
    var queueClearing by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    fun openPlaylistPicker(ids: List<TrackId>) { playlistPickerName = ""; playlistPicker = ids }
    fun playlistArgs(id: PlaylistId, entry: String? = null, shuffle: Boolean = false) = Bundle().apply {
        putString("id", id.value.toString()); entry?.let { putString("entry", it) }; putBoolean("shuffle", shuffle)
    }
    val ready = playback.optBoolean("ready") && controller != null
    val currentId = playback.optString("currentId")
    val current = library.tracks.firstOrNull { it.id.value.toString() == currentId }
    val filtered = remember(library.tracks, search, newest) {
        val found = library.tracks.filter { (it.tags.title + " " + artist(it) + " " + it.tags.album.orEmpty()).contains(search, true) }
        if (newest) found.sortedByDescending { it.addedAtMs } else found.sortedWith(compareBy<Track> { it.tags.title.lowercase() }.thenBy { it.id.value.toString() })
    }
    fun start(tracks: List<Track>, index: Int, resume: Boolean = false) { send(PlaybackService.START, collectionBundle(tracks, index, resume)) }
    Scaffold(bottomBar = {
        Column {
            if (current != null) Surface(tonalElevation = 4.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    TextButton(onClick = { screen = "Playing" }, modifier = Modifier.fillMaxWidth()) {
                        Text(current.tags.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { controller?.seekToPrevious() }, enabled = ready) { Text("Previous") }
                        Button(onClick = { if (playback.optBoolean("playWhenReady")) controller?.pause() else controller?.play() }, enabled = ready) {
                            Text(if (playback.optBoolean("playWhenReady")) "Pause" else "Play")
                        }
                        TextButton(onClick = { controller?.seekToNext() }, enabled = ready) { Text("Next") }
                    }
                }
            }
            NavigationBar {
                listOf("Home", "Library", "Playing", "Queue", "Playlists", "Settings").forEach { tab ->
                    NavigationBarItem(selected = screen == tab, onClick = { screen = tab },
                        icon = { Text(when (tab) { "Home" -> "⌂"; "Library" -> "♫"; "Playing" -> "▶"; "Queue" -> "≡"; "Playlists" -> "≣"; else -> "⚙" }) }, label = { Text(tab) })
                }
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("Local Music", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${library.tracks.size} songs · Normal playback", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = onScan, enabled = ready && !library.scanning) { Text("Scan") }
            }
            if (!ready && message.isBlank()) Text("Opening your library…", Modifier.padding(horizontal = 20.dp))
            if (message.isNotBlank()) Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            if (library.error.isNotBlank()) Row(Modifier.padding(horizontal = 16.dp)) {
                Text(library.error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { send(PlaybackService.CLEAR_ERROR, Bundle.EMPTY) }) { Text("Dismiss") }
            }
            if (playback.optString("error").isNotBlank()) Text(playback.optString("error"), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            if (library.scanning) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.padding(horizontal = 16.dp)) {
                    Text("Scanning… ${library.scanned} songs", Modifier.weight(1f))
                    TextButton(onClick = { send(PlaybackService.CANCEL_SCAN, Bundle.EMPTY) }) { Text("Cancel") }
                }
            }
            when (screen) {
                "Home" -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("Your music, offline", style = MaterialTheme.typography.headlineMedium) }
                    if (library.tracks.isEmpty()) item {
                        Text("Scan music stored on your phone or SD card. Your files stay on your device.")
                        Button(onClick = onScan, enabled = ready) { Text("Find my music") }
                    }
                    val continues = library.tracks.filter { it.id in library.continues && it.availability == Availability.AVAILABLE }
                    if (continues.isNotEmpty()) {
                        item { Section("Continue listening") }
                        items(continues.take(10), key = { "continue-${it.id}" }) { track ->
                            ListItem(headlineContent = { Text(track.tags.title) }, supportingContent = { Text("Saved at ${time(library.continues[track.id]!!)}") },
                                trailingContent = { TextButton(onClick = { start(listOf(track), 0, true) }, enabled = ready) { Text("Continue") } })
                        }
                    }
                    item { Section("Favorites") }
                    if (library.tracks.none { it.favorite }) item { Text("Use a song’s menu to add a favorite.") }
                    val favorites = library.tracks.filter { it.favorite }
                    items(favorites.take(10), key = { "favorite-${it.id}" }) { track ->
                        SongRow(track, ready, { start(favorites, favorites.indexOf(track)) }, send, addToPlaylist = { openPlaylistPicker(listOf(track.id)) }) { details = track }
                    }
                    item { Section("Recently added") }
                    val recent = library.tracks.sortedByDescending { it.addedAtMs }
                    items(recent.take(15), key = { "recent-${it.id}" }) { track -> SongRow(track, ready, { start(recent, recent.indexOf(track)) }, send, addToPlaylist = { openPlaylistPicker(listOf(track.id)) }) { details = track } }
                }
                "Library" -> {
                    OutlinedTextField(search, { search = it }, label = { Text("Search songs, artists or albums") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Songs", "Albums", "Artists", "Folders").forEach { name ->
                            FilterChip(selected = category == name, onClick = { category = name; group = null }, label = { Text(name) })
                        }
                        FilterChip(selected = newest, onClick = { newest = !newest }, label = { Text("Newest first") })
                    }
                    fun groupName(track: Track): String = when (category) {
                        "Albums" -> "${track.tags.album ?: "Unknown album"} · ${track.tags.albumArtist ?: artist(track)}"
                        "Artists" -> artist(track)
                        "Folders" -> library.details[track.id]?.folders?.values?.firstOrNull() ?: "Folder information needs a rescan"
                        else -> ""
                    }
                    if (category != "Songs" && group == null) {
                        val groups = filtered.groupBy(::groupName).toSortedMap()
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
                            items(groups.keys.toList()) { key -> ListItem(modifier = Modifier.clickable { group = key },
                                headlineContent = { Text(key) }, supportingContent = { Text("${groups[key]!!.size} songs") }) }
                            if (groups.isEmpty()) item { Text("No matching music. Scan or try another search.") }
                        }
                    } else {
                        if (group != null) TextButton(onClick = { group = null }) { Text("‹ All $category") }
                        val shown = if (category == "Songs") filtered else filtered.filter { groupName(it) == group }
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                            TextButton(onClick = { selecting = !selecting; selection = emptySet() }) { Text(if (selecting) "Done selecting" else "Select songs") }
                            if (selecting) TextButton(enabled = selection.size in 1..50, onClick = { editing = library.tracks.filter { it.id in selection } }) { Text("Edit ${selection.size} selected") }
                            if (selecting && selection.isNotEmpty()) TextButton(onClick = { openPlaylistPicker(selection.toList()) }) { Text("Add to playlist") }
                            if (category == "Albums" && group != null) TextButton(enabled = shown.size in 1..50, onClick = { editing = shown }) { Text("Edit album") }
                        }
                        if (selection.size > 50) Text("Use batches of at most 50 songs.", Modifier.padding(horizontal = 16.dp))
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
                            if (shown.isEmpty()) item { Text("No matching songs. Scan or try another search.") }
                            items(shown, key = { it.id.value.toString() }) { track -> SongRow(track, ready, { start(shown, shown.indexOf(track)) }, send,
                                art = store?.artwork(track.id), selected = if (selecting) track.id in selection else null,
                                toggle = { selection = if (track.id in selection) selection - track.id else selection + track.id },
                                addToPlaylist = { openPlaylistPicker(listOf(track.id)) }) { details = track } }
                        }
                    }
                }
                "Playing" -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Now playing", style = MaterialTheme.typography.headlineMedium)
                    if (current == null) Text("Choose a song from your library.") else {
                        CoverArt(store?.artwork(current.id), current.tags.title, Modifier.fillMaxWidth().height(220.dp))
                        Text(current.tags.title, style = MaterialTheme.typography.headlineSmall)
                        Text(artist(current)); Text(current.tags.album.orEmpty())
                        val duration = playback.optLong("duration").coerceAtLeast(1)
                        val position = playback.optLong("position").coerceIn(0, duration)
                        var dragged by remember(currentId) { mutableStateOf<Float?>(null) }
                        Slider(value = dragged ?: (position.toFloat() / duration), onValueChange = { dragged = it },
                            onValueChangeFinished = { dragged?.let { controller?.seekTo((it * duration).toLong()) }; dragged = null }, enabled = ready)
                        Text("${time(position)} / ${time(duration)}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = controller?.shuffleModeEnabled == true, onClick = { controller?.shuffleModeEnabled = controller?.shuffleModeEnabled != true }, label = { Text("Shuffle") })
                            val repeat = controller?.repeatMode ?: 0
                            FilterChip(selected = repeat != 0, onClick = { controller?.repeatMode = (repeat + 1) % 3 }, label = { Text("Repeat: ${when (repeat) { 1 -> "one"; 2 -> "all"; else -> "off" }}") })
                        }
                        TextButton(onClick = { sleepOpen = true }, enabled = ready) {
                            Text(when (playback.optString("sleepMode", "OFF")) {
                                "IMMEDIATE" -> "Sleep timer · ${time(playback.optLong("sleepRemaining", -1L))} left"
                                "END_OF_TRACK" -> "Sleep timer · after this track"
                                else -> "Sleep timer"
                            })
                        }
                        TextButton(onClick = { details = current }) { Text("Song details") }
                        OutlinedButton(onClick = { controller?.stop() }, enabled = ready) { Text("Stop playback") }
                        Text("Normal · Android audio output", fontWeight = FontWeight.Bold)
                        Text("Output sample rate is not measured. Effects are off.")
                        LyricsPanel(current, store, library.revision, position, controller, send, actions)
                    }
                }
                "Queue" -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Section("Play Next & manual queue") }
                    val manual = playback.optJSONArray("manual")
                    val manualIds = playback.optJSONArray("manualIds")
                    val editable = manualIds != null && manual != null && manualIds.length() == manual.length()
                    val count = playback.optInt("manualCount")
                    if (manual == null || manual.length() == 0) item { Text("Use a song’s menu to queue it. Duplicate songs are allowed.") }
                    // Keys bind each row to its occurrence identity, so an open menu follows its song when rows shift.
                    items(manual?.length() ?: 0, key = { manualIds?.optString(it).orEmpty().ifEmpty { "row-$it" } }) { index ->
                        val track = library.tracks.firstOrNull { it.id.value.toString() == manual!!.optString(index) }
                        val entryId = manualIds?.optString(index).orEmpty()
                        ManualQueueRow(index, manual?.length() ?: 0, track, ready && editable && entryId.isNotBlank(),
                            move = { target -> send(PlaybackService.QUEUE_MOVE, Bundle().apply { putString("entry", entryId); putInt("index", target) }) },
                            remove = { send(PlaybackService.QUEUE_REMOVE, Bundle().apply { putString("entry", entryId) }) })
                    }
                    if (count > 0) item { TextButton(enabled = ready, onClick = { queueClearing = true }) { Text("Clear queue") } }
                    item { Text("$count manual entries; only the first 60 can be edited here."); Section("Listening sequence") }
                    val sequence = playback.optJSONArray("sequence")
                    items(sequence?.length() ?: 0) { index -> Text("${index + 1}. ${library.tracks.firstOrNull { it.id.value.toString() == sequence!!.optString(index) }?.tags?.title ?: "Unavailable song"}") }
                    item { Text("${playback.optInt("sequenceCount")} remaining; showing up to 60. Browsing and sorting keep this sequence unchanged.") }
                }
                "Playlists" -> {
                    val transferBusy = playlistUi.fileBusy.value || playlistUi.pendingExport.value != null
                    TextButton(enabled = ready && !transferBusy && playlistUi.importPreview.value == null, onClick = pickPlaylist) { Text("Import playlist file") }
                    if (playlistUi.fileMessage.value.isNotBlank()) Text(playlistUi.fileMessage.value, modifier = Modifier.padding(horizontal = 16.dp))
                    val detail = playlistDetail
                    if (detail == null) {
                        Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Playlists", style = MaterialTheme.typography.headlineMedium)
                                TextButton(enabled = ready, onClick = { playlistNamingText = ""; playlistCreating = true })
                                    { Text("New playlist") }
                            }
                            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (library.playlists.isEmpty()) item {
                                    Text("No playlists yet. Use a song’s More menu → Add to playlist, or create one here. Repeated songs are allowed.")
                                }
                                items(library.playlists, key = { it.first.id.value.toString() }) { (playlist, count) ->
                                    PlaylistRow(playlist, count, { playlistDetail = playlist },
                                        { send(PlaybackService.PLAYLIST_START, playlistArgs(playlist.id)) },
                                        { send(PlaybackService.PLAYLIST_START, playlistArgs(playlist.id, shuffle = true)) },
                                        { playlistNaming = playlist; playlistCreating = false; playlistNamingText = playlist.name },
                                        { playlistDeleting = playlist })
                                }
                            }
                        }
                    } else {
                        val stored = library.playlists.firstOrNull { it.first.id == detail.id }?.first ?: detail
                        LaunchedEffect(stored.id, library.revision, store) {
                            val repository = store?.repository() ?: return@LaunchedEffect
                            val loaded = withContext(Dispatchers.IO) { repository.playlist(stored.id) }
                            if (loaded == null) { playlistDetail = null; playlistDetailLoad = null }
                            else playlistDetailLoad = stored.id to loaded.second
                        }
                        // Rows bind to the loaded playlist; a switch to another playlist renders them only after its own load.
                        val playlistDetailEntries = playlistDetailLoad?.takeIf { it.first == stored.id }?.second ?: emptyList()
                        Column(Modifier.weight(1f)) {
                            TextButton(onClick = { playlistDetail = null }) { Text("‹ All playlists") }
                            Text(stored.name, style = MaterialTheme.typography.headlineMedium)
                            TextButton(enabled = ready && !transferBusy, onClick = { playlistUi.exportTarget.value = stored }) { Text("Export playlist file") }
                            val count = playlistDetailEntries.size
                            val loadedHere = playlistDetailLoad?.takeIf { it.first == stored.id } != null
                            Text(when {
                                !loadedHere -> "Loading saved order…"
                                count == 0 -> "This playlist is empty. Use a song’s More menu → Add to playlist."
                                else -> "$count occurrence(s) in saved order · repeated songs allowed"
                            }, style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(enabled = ready && count > 0, onClick = { send(PlaybackService.PLAYLIST_START, playlistArgs(stored.id)) }) { Text("Play") }
                                TextButton(enabled = ready && count > 0, onClick = { send(PlaybackService.PLAYLIST_START, playlistArgs(stored.id, shuffle = true)) }) { Text("Play shuffled") }
                                TextButton(enabled = ready, onClick = { playlistNaming = stored; playlistCreating = false; playlistNamingText = stored.name }) { Text("Rename") }
                                TextButton(enabled = ready, onClick = { playlistDeleting = stored }) { Text("Delete") }
                            }
                            if (playlistDetailEntries.isNotEmpty())
                                Text("Editing this saved list never changes a sequence that is already playing. Missing songs stay in place.", style = MaterialTheme.typography.bodySmall)
                            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(playlistDetailEntries, key = { it.id.value.toString() }) { entry ->
                                    val index = playlistDetailEntries.indexOfFirst { it.id == entry.id }
                                    val track = library.tracks.firstOrNull { it.id == entry.trackId }
                                    PlaylistEntryRow(entry, index, count, track,
                                        play = { send(PlaybackService.PLAYLIST_START, playlistArgs(stored.id, entry.id.value.toString())) },
                                        move = { target -> send(PlaybackService.PLAYLIST_MOVE,
                                            Bundle().apply { putString("id", stored.id.value.toString()); putString("entry", entry.id.value.toString()); putInt("index", target) }) },
                                        remove = { send(PlaybackService.PLAYLIST_REMOVE,
                                            Bundle().apply { putString("id", stored.id.value.toString()); putString("entry", entry.id.value.toString()) }) })
                                }
                            }
                        }
                    }
                }
                "Settings" -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Section("Music access")
                    Text("Scan Android’s indexed music on internal storage and SD cards. Missing songs keep their saved references.")
                    Button(onClick = onScan, enabled = ready && !library.scanning) { Text("Allow access / scan music") }
                    OutlinedButton(onClick = actions.pickFolder, enabled = ready) { Text("Choose music folder / SD card") }
                    library.folders.forEach { folder -> Text("Granted: ${Uri.parse(folder.treeUri).lastPathSegment ?: "Music folder"}", style = MaterialTheme.typography.bodySmall) }
                    Row { Switch(checked = preferences.autoScan, onCheckedChange = { value -> send(PlaybackService.PREFERENCES, Bundle().apply { putString("appearance", preferences.appearance); putBoolean("autoScan", value) }) }); Text("Rescan automatically when indexed music changes") }
                    Text("Appearance", style = MaterialTheme.typography.titleLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("System", "Light", "Dark", "AMOLED").forEach { name -> FilterChip(selected = preferences.appearance == name, onClick = {
                            send(PlaybackService.PREFERENCES, Bundle().apply { putString("appearance", name); putBoolean("autoScan", preferences.autoScan) })
                        }, label = { Text(name) }) }
                    }
                    if (library.message.isNotBlank()) Text(library.message)
                    OutlinedButton(onClick = onNotifications) { Text("Playback notifications") }
                    OutlinedButton(onClick = onExport, enabled = ready) { Text("Save playback report") }
                    Section("Output")
                    Text("Normal playback is active. Direct USB is unavailable in this preview while clocking and listening-balance findings remain open.")
                    Text("Online lyrics search runs only when you request it; selected versions remain available offline.")
                    TextButton(onClick = { licenses = true }) { Text("Open-source notices") }
                    EditHistory(library, { nativeBatch = it }, actions)
                    Text("Playlists are saved with your chosen order, including repeated songs. App-data backup, processing controls, widget and artist biographies are not included yet.")
                    Section("About this preview")
                    Text("Local Music 0.6.0\nM3U/M3U8 and PLS playlist import/export. Playlist drafts stay open when you rotate. Sleep timer, queue editing, offline folders, covers, lyrics and reviewed tag edits. No account needed.")
                }
            }
        }
    }
    PlaylistFileDialogs(playlistUi, ready, confirmImport, exportPlaylist)
    details?.let { track -> AlertDialog(onDismissRequest = { details = null }, title = { Text(track.tags.title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(artist(track)); Text("Album: ${track.tags.album ?: "Unknown"}")
            Text("Duration: ${time(track.audio.durationMs)}\nFormat: ${track.audio.mimeType}\nAvailability: ${track.availability}")
            Text("Source: ${track.audio.sampleRateHz?.let { "$it Hz" } ?: "sample rate unknown"} · ${track.audio.meaningfulBits?.let { "$it-bit" } ?: "bit depth unknown"} · ${track.audio.channels ?: "?"} channel(s)")
            TextButton(onClick = { editing = listOf(track); details = null }) { Text("Edit tags…") }
            TextButton(onClick = { openPlaylistPicker(listOf(track.id)); details = null }) { Text("Add to playlist…") }
            TextButton(onClick = { actions.pickCover(listOf(track)); details = null }) { Text("Choose artwork…") }
            TextButton(onClick = { send(PlaybackService.APP_ART, Bundle().apply { putString("id", track.id.value.toString()); putBoolean("remove", true) }); details = null }) { Text("Remove cover in this app") }
            TextButton(enabled = !library.scanning, onClick = {
                val batch = UUID.randomUUID().toString(); nativeBatch = batch; details = null
                send(PlaybackService.EDIT_PREPARE, Bundle().apply { putString("batch", batch); putStringArrayList("ids", arrayListOf(track.id.value.toString())); putString("patch", "{}"); putInt("coverMode", 2) })
            }) { Text("Preview removal of embedded front cover…") }
        } }, confirmButton = { TextButton(onClick = { details = null }) { Text("Close") } }) }
    playlistPicker?.let { ids ->
        AlertDialog(onDismissRequest = { playlistPicker = null }, title = { Text(if (ids.size == 1) "Add to playlist" else "Add ${ids.size} songs to a playlist") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (library.playlists.isEmpty()) Text("Create your first playlist below.")
                library.playlists.forEach { (playlist, _) ->
                    TextButton(enabled = ready, onClick = { send(PlaybackService.PLAYLIST_ADD, Bundle().apply {
                        putString("id", playlist.id.value.toString()); putStringArrayList("ids", ArrayList(ids.map { it.value.toString() })) })
                        playlistPicker = null }) { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                OutlinedTextField(playlistPickerName, { playlistPickerName = it.take(Playlists.MAX_NAME_CHARS) },
                    label = { Text("Or create a new playlist") }, singleLine = true)
                Text("A repeated song is added again as its own entry. Each playlist keeps its own saved order.", style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(enabled = ready && playlistPickerName.isNotBlank(),
                onClick = { send(PlaybackService.PLAYLIST_CREATE, Bundle().apply {
                    putString("name", playlistPickerName); putStringArrayList("ids", ArrayList(ids.map { it.value.toString() })) })
                playlistPicker = null }) { Text("Create and add") } },
            dismissButton = { TextButton(onClick = { playlistPicker = null }) { Text("Cancel") } })
    }
    if (playlistCreating || playlistNaming != null) {
        val target = playlistNaming
        AlertDialog(onDismissRequest = { playlistCreating = false; playlistNaming = null },
            title = { Text(if (target == null) "New playlist" else "Rename playlist") },
            text = { Column { OutlinedTextField(playlistNamingText, { playlistNamingText = it.take(Playlists.MAX_NAME_CHARS) }, label = { Text("Playlist name") }, singleLine = true) } },
            confirmButton = { TextButton(enabled = ready && playlistNamingText.isNotBlank(), onClick = {
                if (target == null) send(PlaybackService.PLAYLIST_CREATE, Bundle().apply { putString("name", playlistNamingText) })
                else send(PlaybackService.PLAYLIST_RENAME, Bundle().apply { putString("id", target.id.value.toString()); putString("name", playlistNamingText) })
                playlistCreating = false; playlistNaming = null }) { Text(if (target == null) "Create" else "Rename") } },
            dismissButton = { TextButton(onClick = { playlistCreating = false; playlistNaming = null }) { Text("Cancel") } })
    }
    playlistDeleting?.let { target -> AlertDialog(onDismissRequest = { playlistDeleting = null }, title = { Text("Delete ${target.name}?") },
        text = { Text("The saved playlist and its ordered entries are deleted. Songs, favorites and other playlists stay untouched.") },
        confirmButton = { TextButton(enabled = ready, onClick = { send(PlaybackService.PLAYLIST_DELETE, Bundle().apply { putString("id", target.id.value.toString()) }); playlistDeleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { playlistDeleting = null }) { Text("Cancel") } }) }
    if (queueClearing) AlertDialog(onDismissRequest = { queueClearing = false }, title = { Text("Clear the queue?") },
        text = { Text("Pending next-up songs are removed. The song that is playing, its position, history and shuffle stay untouched.") },
        confirmButton = { TextButton(enabled = ready, onClick = { send(PlaybackService.QUEUE_CLEAR, Bundle.EMPTY); queueClearing = false }) { Text("Clear queue") } },
        dismissButton = { TextButton(onClick = { queueClearing = false }) { Text("Cancel") } })
    if (sleepOpen) AlertDialog(onDismissRequest = { sleepOpen = false }, title = { Text("Sleep timer") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (playback.optString("sleepMode", "OFF")) {
                "IMMEDIATE" -> "Pausing in ${time(playback.optLong("sleepRemaining", -1L))}. Playing something new keeps this countdown."
                "END_OF_TRACK" -> "Pausing after this track ends. Playing something else keeps it armed."
                else -> "Playback pauses when the countdown ends. The countdown keeps running while paused."
            })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 15, 30, 45, 60, 90, 120).forEach { minutes ->
                    FilterChip(selected = false, enabled = ready, onClick = { send(PlaybackService.SLEEP_SET, Bundle().apply { putInt("minutes", minutes) }); sleepOpen = false },
                        label = { Text(if (minutes == 1) "1 min" else "$minutes min") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = false, enabled = ready, onClick = { send(PlaybackService.SLEEP_END_TRACK, Bundle.EMPTY); sleepOpen = false },
                    label = { Text("After this track") })
                FilterChip(selected = false, enabled = ready, onClick = { send(PlaybackService.SLEEP_CANCEL, Bundle.EMPTY); sleepOpen = false },
                    label = { Text("Cancel timer") })
            }
            Text("Setting a new timer always replaces the old one. The timer is not saved across app restarts.", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = { sleepOpen = false }) { Text("Close") } },
        dismissButton = {})
    editing?.let { tracks -> TagEditorDialog(tracks, library, send, actions, { nativeBatch = it }) { editing = null } }
    nativeBatch?.let { batch -> NativePreviewDialog(batch, library, send, actions) { nativeBatch = null } }
    if (licenses) {
        val context = androidx.compose.ui.platform.LocalContext.current
        var notice by remember { mutableStateOf("Loading notices…") }
        LaunchedEffect(Unit) { notice = withContext(Dispatchers.IO) { listOf("NOTICE.txt", "COPYING.MPL", "COPYING.LGPL", "UTFCPP-LICENSE").joinToString("\n\n") { name -> context.assets.open(name).bufferedReader().use { it.readText() } } } }
        AlertDialog(onDismissRequest = { licenses = false }, title = { Text("Open-source notices") }, text = { Text(notice, Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { licenses = false }) { Text("Close") } })
    }
}

@Composable private fun Section(title: String) { Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp)) }

@Composable private fun PlaylistRow(playlist: Playlist, count: Int, open: () -> Unit, play: () -> Unit,
    shuffle: () -> Unit, rename: () -> Unit, delete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(modifier = Modifier.clickable(onClick = open), headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(if (count == 1) "1 song" else "$count songs") },
        trailingContent = { Box { TextButton(onClick = { menu = true }) { Text("More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Play") }, enabled = count > 0, onClick = { menu = false; play() })
                DropdownMenuItem(text = { Text("Play shuffled") }, enabled = count > 0, onClick = { menu = false; shuffle() })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; rename() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; delete() })
            } } })
}

@Composable private fun ManualQueueRow(index: Int, total: Int, track: Track?, editable: Boolean,
    move: (Int) -> Unit, remove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        leadingContent = { Text("${index + 1}.") },
        headlineContent = { Text(track?.tags?.title ?: "Unavailable song", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(if (track == null) "Not in the library yet; scan again or remove this entry."
            else artist(track) + if (track.availability != Availability.AVAILABLE) " · Unavailable" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Box { TextButton(onClick = { menu = true }, enabled = editable) { Text("More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Move up") }, enabled = index > 0, onClick = { menu = false; move(index - 1) })
                DropdownMenuItem(text = { Text("Move down") }, enabled = index < total - 1, onClick = { menu = false; move(index + 1) })
                DropdownMenuItem(text = { Text("Remove entry") }, onClick = { menu = false; remove() })
            } } })
}

@Composable private fun PlaylistEntryRow(entry: PlaylistEntry, index: Int, total: Int, track: Track?,
    play: () -> Unit, move: (Int) -> Unit, remove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(modifier = Modifier.clickable(enabled = track != null, onClick = play),
        leadingContent = { Text("${index + 1}.") },
        headlineContent = { Text(track?.tags?.title ?: "Unavailable song", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(if (track == null) "Not in the library yet; scan again or remove this entry."
            else artist(track) + if (track.availability != Availability.AVAILABLE) " · Unavailable" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Box { TextButton(onClick = { menu = true }) { Text("More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Play from here") }, enabled = track != null, onClick = { menu = false; play() })
                DropdownMenuItem(text = { Text("Move up") }, enabled = index > 0, onClick = { menu = false; move(index - 1) })
                DropdownMenuItem(text = { Text("Move down") }, enabled = index < total - 1, onClick = { menu = false; move(index + 1) })
                DropdownMenuItem(text = { Text("Remove entry") }, onClick = { menu = false; remove() })
            } } })
}

@Composable private fun SongRow(track: Track, ready: Boolean, play: () -> Unit, send: (String, Bundle) -> Unit,
    art: String? = null, selected: Boolean? = null, toggle: (() -> Unit)? = null, addToPlaylist: () -> Unit = {},
    details: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(modifier = Modifier.clickable(enabled = ready && (selected != null || track.availability == Availability.AVAILABLE), onClick = { if (selected != null) toggle?.invoke() else play() }),
        leadingContent = { if (selected != null) Checkbox(selected, { toggle?.invoke() }) else CoverArt(art, track.tags.title, Modifier.size(48.dp)) },
        headlineContent = { Text(track.tags.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(artist(track) + if (track.availability != Availability.AVAILABLE) " · Unavailable" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Box {
            TextButton(onClick = { menu = true }, enabled = ready) { Text("More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (track.favorite) "Remove favorite" else "Add favorite") }, onClick = { menu = false; send(PlaybackService.FAVORITE, idBundle(track)) })
                DropdownMenuItem(text = { Text("Play Next") }, onClick = { menu = false; send(PlaybackService.NEXT, idBundle(track)) })
                DropdownMenuItem(text = { Text("Add to Queue") }, onClick = { menu = false; send(PlaybackService.ADD, idBundle(track)) })
                DropdownMenuItem(text = { Text("Add to playlist…") }, onClick = { menu = false; addToPlaylist() })
                DropdownMenuItem(text = { Text("Details") }, onClick = { menu = false; details() })
            }
        } })
}
