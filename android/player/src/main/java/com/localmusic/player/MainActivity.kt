@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.*
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.toArgb
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
    private val backupUi by viewModels<BackupUiState>()
    private val listeningUi by viewModels<ListeningToolsUi>()
    private val documentUi by viewModels<DocumentUiState>()
    private val backupSavePicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) backupCommand(PlaybackService.BACKUP_EXPORT, Bundle().apply { putString("uri", uri.toString()) })
        else backupUi.busy = false
    }
    private val backupOpenPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backupCommand(PlaybackService.BACKUP_PREVIEW, Bundle().apply { putString("uri", uri.toString()) })
        else backupUi.busy = false
    }
    private fun backupCommand(action: String, args: Bundle) {
        val connected = controller
        if (connected == null || !sessionState.optBoolean("ready")) { backupUi.busy = false; backupUi.message = "Wait for the library to open, then try again."; return }
        backupUi.busy = true
        backupUi.message = when(action) {
            PlaybackService.BACKUP_EXPORT -> "Saving and verifying your backup…"
            PlaybackService.BACKUP_PREVIEW -> "Reading and validating the backup…"
            else -> "Restoring app data…"
        }
        val call = try { connected.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }
            catch (e: Exception) { backupUi.busy = false; backupUi.message = "Backup command could not start: ${e.message}"; return }
        call.addListener({
            try {
                val result = call.get()
                if (result.resultCode != androidx.media3.session.SessionResult.RESULT_SUCCESS) {
                    backupUi.message = result.extras.getString("message") ?: "Backup operation failed. Check Settings before trying again."
                } else when (action) {
                    PlaybackService.BACKUP_EXPORT -> backupUi.message = "Backup saved and read back successfully. Playback remains paused."
                    PlaybackService.BACKUP_PREVIEW -> {
                        backupUi.token = result.extras.getString("token"); backupUi.summary = result.extras.getString("summary").orEmpty()
                        backupUi.message = "Backup validated. Review the restore preview."
                    }
                    PlaybackService.BACKUP_RESTORE -> { backupUi.token = null; backupUi.message = "App data restored. Playback is paused. Allow music access and scan if any files are unavailable." }
                }
            } catch (e: Exception) { backupUi.message = "Result unavailable. Check the saved file or app data before retrying." }
            finally { backupUi.busy = false }
        }, ContextCompat.getMainExecutor(applicationContext))
    }
    private var controller by mutableStateOf<MediaController?>(null)
    private val playbackUi=PlaybackUiState()
    private val sessionState get()=playbackUi.snapshot
    private var connectionError by mutableStateOf("")
    private var permissionMessage by mutableStateOf("")
    private val pendingCommands = ArrayDeque<Pair<String, Bundle>>()
    private lateinit var future: ListenableFuture<MediaController>
    private val exportScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingReport: String?
        get() = documentUi.pendingReport
        set(value) { documentUi.pendingReport = value }
    private var coverTargets = emptyList<String>()
    private var pickedCover by mutableStateOf<Uri?>(null)
    private var lyricTarget: Track?
        get() = documentUi.lyricTarget
        set(value) { documentUi.lyricTarget = value }
    private var importedLyrics: Pair<Track, String>?
        get() = documentUi.importedLyrics
        set(value) { documentUi.importedLyrics = value }
    private var pendingLyricExport: String?
        get() = documentUi.pendingLyricExport
        set(value) { documentUi.pendingLyricExport = value }
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
        StartupTiming.mark("activity_create")
        val launch=StartupAppearance.read(this)
        setTheme(if(launch.appearance=="AMOLED")R.style.PlayerLaunchBlack
            else if(StartupAppearance.dark(this,launch.appearance))R.style.PlayerLaunchDark else R.style.PlayerLaunchLight)
        super.onCreate(savedInstanceState)
        fullyDrawnReporter.addOnReportDrawnListener { StartupTiming.mark("library_screen_drawn") }
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
            val initialAppearance=remember{launch}
            val preferences = store?.preferences?.collectAsState()?.value ?: initialAppearance
            val dark = when (preferences.appearance) { "Light" -> false; "Dark", "AMOLED" -> true; else -> isSystemInDarkTheme() }
            val colors = remember(dark, preferences.appearance,preferences.accent) { playerColorScheme(dark, preferences.appearance == "AMOLED",preferences.accent) }
            val library = store?.uiState?.collectAsState(initial=store?.state?.value ?: LibraryState())?.value ?: LibraryState()
            var entered by remember { mutableStateOf(false) }
            val startupError=listOf(connectionError,openError,permissionMessage,library.error,sessionState.optString("error")).any{it.isNotBlank()}
            val showLaunch=!entered && !startupError && (store==null || !sessionState.optBoolean("ready"))
            // The logo is only the first frame. Report usable content separately.
            ReportDrawnWhen { store!=null && sessionState.optBoolean("ready") && !startupError }
            SideEffect { if(!showLaunch)entered=true }
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) {
                    val barColor = if(showLaunch)ContextCompat.getColor(this,R.color.player_launch_background) else MaterialTheme.colorScheme.background.toArgb()
                    SideEffect {
                        @Suppress("DEPRECATION")
                        if (Build.VERSION.SDK_INT < 35) {
                            window.statusBarColor = barColor
                            window.navigationBarColor = barColor
                        }
                        WindowCompat.getInsetsController(window, window.decorView).apply {
                            isAppearanceLightStatusBars = !showLaunch && !dark
                            isAppearanceLightNavigationBars = !showLaunch && !dark
                        }
                    }
                    if(showLaunch)PlayerLaunchScreen() else MusicScreen(listeningUi, backupUi, playlistUi, ::confirmPlaylistImport, { playlistFilePicker.launch(arrayOf("*/*")) }, ::exportPlaylist, library, controller, sessionState,playbackUi,
                        listOf(connectionError, openError, permissionMessage).filter { it.isNotBlank() }.joinToString("\n"),
                        ::send, store, preferences, FeatureActions(
                            pickFolder = { folderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)) },
                            pickCover = { targets -> coverTargets = targets.map { it.id.value.toString() }; coverPicker.launch(arrayOf("image/*")) },
                            importLyrics = { track -> lyricTarget = track; lyricPicker.launch(arrayOf("*/*")) },
                            exportLyrics = { track, text -> pendingLyricExport = text; lyricExport.launch(track.tags.title.replace(Regex("[\\/:*?\"<>|]"), "_") + ".lrc") },
                            commit = ::commit, undo = { confirmRestore = it to null }, recovery = ::reviewRecovery,
                            createBackup = { try { backupUi.busy = true; backupSavePicker.launch("local-music-backup-${java.time.LocalDate.now()}.zip") }
                                catch(e: Exception) { backupUi.busy = false; backupUi.message = "Could not open the save picker: ${e.message}" } },
                            restoreBackup = { try { backupUi.busy = true; backupOpenPicker.launch(arrayOf("*/*")) }
                                catch(e: Exception) { backupUi.busy = false; backupUi.message = "Could not open the file picker: ${e.message}" } }, backupBusy = backupUi.busy), onScan = {
                            permissionMessage = ""
                            if (library.folders.isNotEmpty()) send(PlaybackService.SCAN)
                            else folderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
                        }, onExport = ::saveReport, onNotifications = {
                            if (Build.VERSION.SDK_INT >= 33) notificationAccess.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
                        })
                    BackupRestoreDialog(backupUi, restore = {
                        backupCommand(PlaybackService.BACKUP_RESTORE, Bundle().apply { putString("token", backupUi.token) })
                    }, cancel = { backupUi.token = null; backupUi.summary = ""; send(PlaybackService.BACKUP_CANCEL) })
                    importedLyrics?.let { (track, text) -> LyricsEditorDialog(track, text, playbackUi.position.longValue, ::send) { importedLyrics = null } }
                    pickedCover?.let { uri ->
                        var crop by rememberSaveable(uri.toString()) { mutableStateOf(true) }
                        AlertDialog(onDismissRequest = { pickedCover = null }, title = { Text("Review cover artwork") }, text = {
                            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                                val imageModifier = if (crop) Modifier.fillMaxWidth().aspectRatio(1f) else Modifier.fillMaxWidth().height(220.dp)
                                AsyncImage(uri, "Selected cover", modifier = imageModifier.background(Color.Black), contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
                                SettingCheckbox("Center square crop", crop) { crop = it }
                                Text("Resize to at most 1024 pixels. Choose app-only use or prepare a music-file preview.") }
                        }, confirmButton = { TextButton(onClick = { send(PlaybackService.APP_ART, Bundle().apply { putStringArrayList("ids", ArrayList(coverTargets)); putString("uri", uri.toString()); putBoolean("crop", crop) }); pickedCover = null }) { Text("Use in this app") } },
                            dismissButton = { Column { TextButton(enabled = !library.scanning, onClick = {
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
        playbackUi.accept(runCatching { JSONObject(bundle.getString("state") ?: "{}") }.getOrDefault(JSONObject()))
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
private fun MusicScreen(listeningUi:ListeningToolsUi,backupUi: BackupUiState, playlistUi: PlaylistUiState, confirmImport: () -> Unit, pickPlaylist: () -> Unit,
    exportPlaylist: (Playlist, PlaylistFileFormat) -> Unit, library: LibraryState, controller: MediaController?, playback: JSONObject, clock:PlaybackUiState, message: String,
    send: (String, Bundle) -> Unit, store: MusicStore?, preferences: MusicPreferences, actions: FeatureActions,
    onScan: () -> Unit, onNotifications: () -> Unit, onExport: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf("Home") }
    var dismissedByDrag by remember { mutableStateOf(false) }
    LaunchedEffect(screen){if(screen=="Playing")dismissedByDrag=false}
    var mainScreen by rememberSaveable { mutableStateOf("Home") }
    var toolsReturn by rememberSaveable{mutableStateOf("Settings")}
    var queueFromPlaying by rememberSaveable { mutableStateOf(false) }
    var homeSearch by rememberSaveable { mutableStateOf("") }
    var focusSearch by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val libraryScroll = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    var search by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("Songs") }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var newest by rememberSaveable { mutableStateOf(false) }
    var details by remember { mutableStateOf<Track?>(null) }
    var editingIds by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val editing = editingIds?.mapNotNull { id -> runCatching { library.trackById[PlaybackAuthority.id(id)] }.getOrNull() }?.takeIf { it.isNotEmpty() }
    var nativeBatch by rememberSaveable { mutableStateOf<String?>(null) }
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
    fun openPlaylistPicker(ids: List<TrackId>) { playlistPickerName = ""; playlistPicker = ids }
    fun playlistArgs(id: PlaylistId, entry: String? = null, shuffle: Boolean = false) = Bundle().apply {
        putString("id", id.value.toString()); entry?.let { putString("entry", it) }; putBoolean("shuffle", shuffle)
    }
    val ready = playback.optBoolean("ready") && controller != null
    val tileMin = 144.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.4f)
    val currentId = playback.optString("currentId")
    val current = runCatching { library.trackById[PlaybackAuthority.id(currentId)] }.getOrNull()
    // Keep Library's list cached across tabs, but do not prepare it for Home.
    val filtered by remember(library.tracks, search, newest) { lazy {
        val found = library.tracks.filter { (it.tags.title + " " + artist(it) + " " + it.tags.album.orEmpty()).contains(search, true) }
        if (newest) found.sortedByDescending { it.addedAtMs } else found.sortedWith(compareBy<Track> { it.tags.title.lowercase() }.thenBy { it.id.value.toString() })
    } }
    fun start(tracks: List<Track>, index: Int, resume: Boolean = false, origin: String = "Library · All songs") {
        send(PlaybackService.START, collectionBundle(tracks, index, resume).apply { putString("origin", origin.take(200)) })
        keyboard?.hide(); screen = "Playing"
    }
    fun playPlaylist(args: Bundle) { send(PlaybackService.PLAYLIST_START, args); keyboard?.hide(); screen = "Playing" }
    val secondary = screen in setOf("Playing","Queue","Audio controls","Audio info","Equalizer","Playback widget")
    val nested = secondary || (screen == "Playlists" && playlistDetail != null) || (screen == "Library" && (group != null || selecting))
    fun back() {
        when {
            screen=="Equalizer"->screen="Audio controls"
            screen=="Audio info"->screen="Audio controls"
            screen=="Audio controls"||screen=="Playback widget"->screen=toolsReturn
            screen == "Queue" && queueFromPlaying -> screen = "Playing"
            secondary -> screen = mainScreen
            screen == "Playlists" -> { playlistDetail = null; playlistDetailLoad = null }
            selecting -> { selecting = false; selection = emptySet() }
            else -> group = null
        }
    }
    BackHandler(enabled = nested) { back() }
    LaunchedEffect(screen, focusSearch) {
        if (screen == "Library" && focusSearch) { libraryScroll.scrollToItem(0); withFrameNanos { }; searchFocus.requestFocus(); keyboard?.show(); focusSearch = false }
    }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val useRail = configuration.screenWidthDp >= 600 && configuration.screenWidthDp > configuration.screenHeightDp
    val browseScreen=if(screen=="Playing")mainScreen else screen
    val browseSecondary=browseScreen in setOf("Queue","Audio controls","Audio info","Equalizer","Playback widget")
    val browseNested=browseSecondary || (browseScreen=="Playlists" && playlistDetail!=null) ||
        (browseScreen=="Library" && (group!=null || selecting))
    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize()) {
    if (useRail && !browseSecondary) PlayerRail(browseScreen) { tab -> keyboard?.hide(); screen = tab; mainScreen = tab }
    Scaffold(modifier = Modifier.weight(1f).imePadding(), topBar = {
        TopAppBar(title = { Text(when (browseScreen) {
            "Home" -> "Local Music"; "Library" -> "Your library"; "Queue" -> "Up next"; else -> browseScreen
        }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { if (browseNested) IconButton(onClick = { back() }) { Icon(PlayerGlyph.Back, "Back") } },
            actions = {
                if(!browseSecondary){
                if (screen != "Queue") IconButton(onClick = { keyboard?.hide(); queueFromPlaying = screen == "Playing"; screen = "Queue" }) { Icon(PlayerGlyph.Queue, "Open queue") }
                }
            })
    }, bottomBar = {
        Column(if (browseSecondary || useRail) Modifier.navigationBarsPadding() else Modifier) {
            if (current != null) CompactPlayer(current, store?.artwork(current.id),
                playback.optBoolean("playWhenReady"), ready,
                { if (playback.optLong("duration") > 0) clock.position.longValue.toFloat() / playback.optLong("duration") else 0f },
                open = { keyboard?.hide(); screen = "Playing" },
                toggle = { if (playback.optBoolean("playWhenReady")) controller?.pause() else controller?.play() },
                next = { controller?.seekToNext() })
            if (!browseSecondary && !useRail) PlayerNavigation(browseScreen) { tab -> keyboard?.hide(); screen = tab; mainScreen = tab }
        }
    }) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            if (!ready && message.isBlank()) Text("Opening your library…", Modifier.padding(horizontal = 20.dp))
            if (message.isNotBlank()) PlayerMessage(message)
            if (library.error.isNotBlank()) PlayerMessage(library.error) { send(PlaybackService.CLEAR_ERROR, Bundle.EMPTY) }
            if (playback.optString("error").isNotBlank()) PlayerMessage(playback.optString("error"))
            AnimatedContent(targetState=browseScreen,modifier=Modifier.weight(1f),label="Screen transition",
                transitionSpec={fadeIn(tween(170)) togetherWith fadeOut(tween(110))}) { displayedScreen ->
            Column(Modifier.fillMaxSize()) {
            when (displayedScreen) {
                "Home" -> Box(Modifier.weight(1f)) {
                    HomeLibraryScreen(library, store, preferences.homeGrid, homeSearch, ready,
                        onSearch = { homeSearch = it }, changeGrid = { grid -> send(PlaybackService.HOME_VIEW, Bundle().apply { putBoolean("grid", grid) }) },
                        scan = onScan, play = { tracks, index, resume, origin -> start(tracks, index, resume, origin) }, send = send,
                        addToPlaylist = { openPlaylistPicker(listOf(it.id)) }, details = { details = it },
                        playlistCard = { playlist, count -> PlaylistCard(playlist, count, store, library.revision,
                            { playlistDetail = playlist; mainScreen = "Playlists"; screen = "Playlists" },
                            { playPlaylist(playlistArgs(playlist.id)) }, { playPlaylist(playlistArgs(playlist.id, shuffle = true)) },
                            { playlistNaming = playlist; playlistCreating = false; playlistNamingText = playlist.name },
                            { playlistDeleting = playlist }) },
                        allPlaylists = { playlistDetail = null; mainScreen = "Playlists"; screen = "Playlists" },
                        allSongs = { category = "Songs"; group = null; newest = true; mainScreen = "Library"; screen = "Library" })
                }
                "Library" -> {
                    fun groupName(track: Track): String = when (category) {
                        "Albums" -> "${track.tags.album ?: "Unknown album"} · ${track.tags.albumArtist ?: artist(track)}"
                        "Artists" -> artist(track)
                        "Folders" -> library.details[track.id]?.folders?.values?.firstOrNull() ?: "Folder information needs a rescan"
                        else -> ""
                    }
                    val groups = remember(filtered, category, library.details) {
                        if (category == "Songs") emptyMap<String, List<Track>>() else filtered.groupBy(::groupName).toSortedMap()
                    }
                    val shown = remember(filtered, category, group, library.details) { if (category == "Songs") filtered else filtered.filter { groupName(it) == group } }
                    if (category == "Artists" && group != null && !selecting) {
                        val artistTracks = remember(filtered, group) { filtered.filter { groupName(it) == group } }
                        Box(Modifier.weight(1f)) { ArtistPage(group.orEmpty(), artistTracks, preferences.artists[ArtistProfile.key(group.orEmpty())], store, ready, listeningUi, send,
                            play = { items, index, shuffle ->
                                val chosen = items.getOrNull(index)?.id; val playable = items.filter { it.availability == Availability.AVAILABLE }
                                if (playable.isNotEmpty()) { send(PlaybackService.START, collectionBundle(playable, playable.indexOfFirst { it.id == chosen }.coerceAtLeast(0)).apply {
                                    putBoolean("shuffle", shuffle); putString("origin", "Artist · ${group.orEmpty()}".take(200))
                                }); screen = "Playing" }
                            }, details = { details = it }, addToPlaylist = { openPlaylistPicker(listOf(it.id)) },
                            addCollectionToPlaylist = { openPlaylistPicker(it.map { track -> track.id }) },
                            album = { track -> category = "Albums"; group = "${track.tags.album ?: "Unknown album"} · ${track.tags.albumArtist ?: artist(track)}" }) }
                    } else LazyColumn(Modifier.weight(1f), state = libraryScroll, contentPadding = PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "search") {
                            MusicSearchField(search,{search=it},"Search your library",Modifier.focusRequester(searchFocus))
                        }
                        item(key = "categories") { PlayerActions {
                            listOf("Songs", "Albums", "Artists", "Folders").forEach { name ->
                                FilterChip(selected = category == name, onClick = { category = name; group = null; selecting = false; selection = emptySet() }, label = { Text(name) })
                            }
                        } }
                        item(key = "sort") { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(countLabel(filtered.size, "song"), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { newest = !newest }) { Text(if (newest) "Newest first" else "Title A–Z") }
                        } }
                        if (category != "Songs" && group == null) {
                            items(groups.keys.toList(), key = { "group-$it" }) { key ->
                                val first = groups[key]?.firstOrNull()
                                val collection = groups.getValue(key)
                                val playable = collection.filter { it.availability == Availability.AVAILABLE }
                                val kind = category.removeSuffix("s")
                                val extras = when (category) {
                                    "Albums" -> buildList<Pair<String, () -> Unit>> {
                                        if (collection.size in 1..50) add("Edit album" to { editingIds = collection.map { it.id.value.toString() } })
                                        if (collection.size in 1..5000) add("Find cover" to { listeningUi.resetLookup(); listeningUi.coverTargets = collection })
                                    }
                                    "Artists" -> listOf<Pair<String, () -> Unit>>("Find artist" to { listeningUi.resetLookup(); listeningUi.artistTarget = key })
                                    else -> emptyList()
                                }
                                CollectionRow(if(category=="Folders")folderDisplayPath(key)else key, if (category == "Artists") preferences.artists[ArtistProfile.key(key)]?.imagePath else first?.let { store?.artwork(it.id) },
                                    collection.size, kind, ready && playable.isNotEmpty(), open = { group = key },
                                    play = { if (playable.isNotEmpty()) start(playable, 0, origin = "$kind: $key") },
                                    shuffle = { if (playable.isNotEmpty()) {
                                        send(PlaybackService.START, collectionBundle(playable, 0).apply { putBoolean("shuffle", true); putString("origin", "$kind: $key".take(200)) })
                                        keyboard?.hide(); screen = "Playing"
                                    } }, addToPlaylist = { openPlaylistPicker(collection.map { it.id }) }, extraActions = extras)
                            }
                            if (groups.isEmpty()) item { EmptyMusicState("No matching music", "Scan your library or try another search.") }
                        } else {
                            if (group != null) item(key = "collection") {
                                MarqueeText(if(category=="Folders")folderDisplayPath(group.orEmpty())else group.orEmpty(), style = MaterialTheme.typography.titleLarge)
                                TextButton(onClick = { group = null }) { Text("All $category") }
                            }
                            item(key = "selection") { PlayerActions {
                                TextButton(onClick = { selecting = !selecting; selection = emptySet() }) { Text(if (selecting) "Done selecting" else "Select songs") }
                                if (selecting) TextButton(enabled = selection.size in 1..50, onClick = { editingIds = library.tracks.filter { it.id in selection }.map { it.id.value.toString() } }) { Text("Edit ${selection.size} selected") }
                                if (selecting && selection.isNotEmpty()) TextButton(onClick = { openPlaylistPicker(selection.toList()) }) { Text("Add to playlist") }
                                if (category == "Albums" && group != null) {
                                    TextButton(enabled = shown.size in 1..50, onClick = { editingIds = shown.map { it.id.value.toString() } }) { Text("Edit album") }
                                    TextButton(enabled = ready && shown.size in 1..5000, onClick = { listeningUi.resetLookup(); listeningUi.coverTargets = shown }) { Text("Find cover") }
                                }
                            } }
                            if (selection.size > 50) item { Text("Use batches of at most 50 songs.") }
                            if (shown.isEmpty()) item { EmptyMusicState("No matching songs", "Scan your library or try another search.") }
                            items(shown, key = { "song-${it.id.value}" }) { track ->
                                SongRow(track, ready, { start(shown, shown.indexOf(track), origin = when (category) {
                                    "Albums" -> "Album · ${group.orEmpty()}"; "Folders" -> "Folder · ${group.orEmpty()}"; else -> "Library · All songs"
                                }) }, send, store?.artwork(track.id), if (selecting) track.id in selection else null,
                                    { selection = if (track.id in selection) selection - track.id else selection + track.id },
                                    { openPlaylistPicker(listOf(track.id)) }, { details = track })
                            }
                        }
                    }
                }
                "Queue" -> QueuePanel(library,store,current,playback,ready,send,
                    openPlaying={screen="Playing"},clear={queueClearing=true},modifier=Modifier.weight(1f))
                "Playlists" -> {
                    val transferBusy = playlistUi.fileBusy.value || playlistUi.pendingExport.value != null
                    val detail = playlistDetail
                    if (detail == null) LazyVerticalGrid(columns = GridCells.Adaptive(tileMin), modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item(span = { GridItemSpan(maxLineSpan) }) { PlayerActions {
                            FilledTonalButton(enabled = ready, onClick = { playlistNamingText = ""; playlistCreating = true }) { Text("New playlist") }
                            OutlinedButton(enabled = ready && !transferBusy && playlistUi.importPreview.value == null, onClick = pickPlaylist) { Text("Import file") }
                        } }
                        if (playlistUi.fileMessage.value.isNotBlank()) item(span = { GridItemSpan(maxLineSpan) }) { Text(playlistUi.fileMessage.value) }
                        if (library.playlists.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyMusicState("Your playlists start here", "Create a playlist or import one. Add songs from their options menu.")
                        }
                        items(library.playlists, key = { it.first.id.value.toString() }) { (playlist, count) ->
                            PlaylistCard(playlist, count, store, library.revision, { playlistDetail = playlist },
                                { playPlaylist(playlistArgs(playlist.id)) }, { playPlaylist(playlistArgs(playlist.id, shuffle = true)) },
                                { playlistNaming = playlist; playlistCreating = false; playlistNamingText = playlist.name }, { playlistDeleting = playlist })
                        }
                    } else {
                        val stored = library.playlists.firstOrNull { it.first.id == detail.id }?.first ?: detail
                        LaunchedEffect(stored.id, library.revision, store) {
                            val repository = store?.repository() ?: return@LaunchedEffect
                            val loaded = withContext(Dispatchers.IO) { repository.playlist(stored.id) }
                            if (loaded == null) { playlistDetail = null; playlistDetailLoad = null }
                            else playlistDetailLoad = stored.id to loaded.second
                        }
                        val entries = playlistDetailLoad?.takeIf { it.first == stored.id }?.second ?: emptyList()
                        val loadedHere = playlistDetailLoad?.first == stored.id
                        val entryList=rememberLazyListState()
                        fun moveEntry(id:String,index:Int) = send(PlaybackService.PLAYLIST_MOVE,Bundle().apply {
                            putString("id",stored.id.value.toString());putString("entry",id);putInt("index",index)
                        })
                        val entryOrder=rememberEntryReorder(entries.map{it.id.value.toString()},entryList,::moveEntry)
                        val entryMap=remember(entries){entries.associateBy{it.id.value.toString()}}
                        LazyColumn(Modifier.weight(1f),state=entryList, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { TextButton(onClick = { playlistDetail = null }) { Text("All playlists") } }
                            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                PlaylistCover(stored, store, library.revision, Modifier.size(88.dp))
                                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                    Text("PLAYLIST", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    MarqueeText(stored.name, style = MaterialTheme.typography.headlineSmall)
                                    Text(if (loadedHere) countLabel(entries.size, "song") else "Loading…", style = MaterialTheme.typography.bodyMedium)
                                }
                            } }
                            item { PlayerActions {
                                Button(enabled = ready && entries.isNotEmpty(), onClick = { playPlaylist(playlistArgs(stored.id)) }) { Icon(PlayerGlyph.Play, null); Spacer(Modifier.width(6.dp)); Text("Play") }
                                FilledTonalButton(enabled = ready && entries.isNotEmpty(), onClick = { playPlaylist(playlistArgs(stored.id, shuffle = true)) }) { Icon(PlayerGlyph.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle") }
                                OutlinedButton(enabled = ready && !transferBusy, onClick = { playlistUi.exportTarget.value = stored }) { Text("Export file") }
                                TextButton(enabled = ready, onClick = { playlistNaming = stored; playlistCreating = false; playlistNamingText = stored.name }) { Text("Rename") }
                                TextButton(enabled = ready, onClick = { playlistDeleting = stored }) { Text("Delete") }
                            } }
                            if (playlistUi.fileMessage.value.isNotBlank()) item { Text(playlistUi.fileMessage.value) }
                            if (loadedHere && entries.isEmpty()) item { EmptyMusicState("No songs yet", "Use a song's options menu to add it to this playlist.") }
                            if (entries.isNotEmpty()) item { Text("Saved order · repeated songs are kept", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            itemsIndexed(entryOrder.ids, key = { _, id -> id }) { index, id ->
                                val entry=entryMap[id] ?: return@itemsIndexed
                                val track = library.trackById[entry.trackId]
                                PlaylistEntryRow(entry, index, entries.size, track, art = track?.let { store?.artwork(it.id) },
                                    modifier=Modifier.animateItem(placementSpec=if(entryOrder.dragging==id)null else androidx.compose.animation.core.spring()).then(entryOrder.row(id)),
                                    handle={EntryDragHandle(id,index,entries.size,entryOrder,ready,::moveEntry)},
                                    play = { playPlaylist(playlistArgs(stored.id, entry.id.value.toString())) },
                                    move = { target -> send(PlaybackService.PLAYLIST_MOVE, Bundle().apply { putString("id", stored.id.value.toString()); putString("entry", entry.id.value.toString()); putInt("index", target) }) },
                                    remove = { send(PlaybackService.PLAYLIST_REMOVE, Bundle().apply { putString("id", stored.id.value.toString()); putString("entry", entry.id.value.toString()) }) })
                            }
                        }
                    }
                }
                "Audio controls"->Box(Modifier.weight(1f)){AudioControls(preferences.audio,ready,store,current,send,{screen="Equalizer"},{screen="Audio info"})}
                "Audio info"->Box(Modifier.weight(1f)){AudioInfoPanel(controller,currentId)}
                "Equalizer"->Box(Modifier.weight(1f)){EqualizerPanel(listeningUi,preferences.audio,ready,send)}
                "Playback widget"->Box(Modifier.weight(1f)){WidgetSettingsPanel(preferences.widgets,ready,current,current?.let{store?.artwork(it.id)},send)}
                "Settings" -> Box(Modifier.weight(1f)) {
                    SettingsPanel(library, preferences, ready, backupUi, actions, send,
                        audio = { toolsReturn = "Settings"; screen = "Audio controls" },
                        widgets = { toolsReturn = "Settings"; screen = "Playback widget" },
                        scan = onScan, notifications = onNotifications, report = onExport,
                        licenses = { licenses = true }, openEdit = { nativeBatch = it })
                }
            }
        }
    }
    }
    }
    }
    AnimatedVisibility(visible=screen=="Playing",enter=slideInVertically(tween(240)){it},
        exit=if(dismissedByDrag)ExitTransition.None else slideOutVertically(tween(220)){it}) {
        PlayerMotionFrame(true,{dismissedByDrag=true;back()},Modifier.fillMaxSize()) { _ ->
            Scaffold(modifier=Modifier.fillMaxSize().imePadding(),topBar={
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(48.dp).padding(horizontal=8.dp),
                    horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={back()}){Icon(PlayerGlyph.Back,"Back")}
                    Text("NOW PLAYING",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(48.dp))
                }
            }) { padding ->
                Box(Modifier.padding(padding).consumeWindowInsets(padding).navigationBarsPadding().fillMaxSize()) {
                    if (current == null) Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Choose a song from your library.")
                        Button(onClick = { screen = "Library"; mainScreen = "Library" }) { Text("Browse music") }
                    } else NowPlayingPanel(current, store?.artwork(current.id), playback, controller, ready, preferences.artworkSize, store, library.revision, send, actions,
                        openQueue = { queueFromPlaying = true; screen = "Queue" }, details = { details = current },
                        openAudio={toolsReturn="Playing";screen="Audio controls"},openArtist={category="Artists";search="";group=artist(current);mainScreen="Library";screen="Library"},clock=clock)
                }
            }
        }
    }
    }
    PlaylistFileDialogs(playlistUi, ready, confirmImport, exportPlaylist)
    ArtistLookupDialogs(listeningUi,store,ready,send)
    details?.let { track -> AlertDialog(onDismissRequest = { details = null }, title = { Text("Song details") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(track.tags.title, style = MaterialTheme.typography.titleMedium)
            Text(artist(track)); Text("Album: ${track.tags.album ?: "Unknown"}")
            ArtworkCredits(library.details[track.id])
            Text("Duration: ${time(track.audio.durationMs)}")
            Text("Source: ${PlaybackPresentation.quality(track.audio)}")
            Text(track.audio.channels?.let { countLabel(it, "channel") } ?: "Channel count unknown")
            Text("Status: ${track.availability.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }}")
            library.details[track.id]?.canonical?.values?.distinct()?.take(3)?.forEach { path ->
                val storage = if (path.substringBefore(':') == "primary") "Phone" else "Storage"
                Text("$storage / ${path.substringAfter(':').trimStart('/')}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { editingIds = listOf(track.id.value.toString()); details = null }) { Text("Edit tags…") }
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
                        playlistPicker = null }) { MarqueeText(playlist.name) }
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
    playlistDeleting?.let { target -> AlertDialog(onDismissRequest = { playlistDeleting = null }, title = { Text("Delete playlist?") },
        text = { Text("${target.name} and its saved order will be deleted. Your songs and other playlists stay.") },
        confirmButton = { TextButton(enabled = ready, onClick = { send(PlaybackService.PLAYLIST_DELETE, Bundle().apply { putString("id", target.id.value.toString()) }); playlistDeleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { playlistDeleting = null }) { Text("Cancel") } }) }
    if (queueClearing) AlertDialog(onDismissRequest = { queueClearing = false }, title = { Text("Clear the queue?") },
        text = { Text("Pending next-up songs are removed. The song that is playing, its position, history and shuffle stay untouched.") },
        confirmButton = { TextButton(enabled = ready, onClick = { send(PlaybackService.QUEUE_CLEAR, Bundle.EMPTY); queueClearing = false }) { Text("Clear queue") } },
        dismissButton = { TextButton(onClick = { queueClearing = false }) { Text("Cancel") } })
    editing?.let { tracks -> TagEditorDialog(tracks, library, send, actions, { nativeBatch = it }) { editingIds = null } }
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
    ListItem(modifier = Modifier.combinedClickable(onClick = open, onLongClickLabel = "Playlist options", onLongClick = { menu = true }), headlineContent = { MarqueeText(playlist.name) },
        supportingContent = { Text(if (count == 1) "1 song" else countLabel(count, "song")) },
        trailingContent = { Box { TextButton(onClick = { menu = true }) { Text("More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Play") }, enabled = count > 0, onClick = { menu = false; play() })
                DropdownMenuItem(text = { Text("Play shuffled") }, enabled = count > 0, onClick = { menu = false; shuffle() })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; rename() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; delete() })
            } } })
}

@Composable private fun PlaylistEntryRow(entry: PlaylistEntry, index: Int, total: Int, track: Track?, art: String? = null,
    modifier:Modifier=Modifier,handle:@Composable ()->Unit={},play: () -> Unit, move: (Int) -> Unit, remove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(modifier = modifier.combinedClickable(onClick = { if (track?.availability == Availability.AVAILABLE) play() }, onLongClickLabel = "Playlist entry options", onLongClick = { menu = true }),
        leadingContent = { CoverArt(art, track?.tags?.title ?: "Unavailable song", Modifier.size(48.dp)) },
        headlineContent = { MarqueeText(track?.tags?.title ?: "Unavailable song") },
        supportingContent = { Text(if (track == null) "Not in the library yet; scan again or remove this entry."
            else artist(track) + if (track.availability != Availability.AVAILABLE) " · Unavailable" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Row { Box { IconButton(onClick = { menu = true }) { Icon(PlayerGlyph.More, "Options for playlist entry ${index + 1}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Play from here") }, enabled = track?.availability == Availability.AVAILABLE, onClick = { menu = false; play() })
                DropdownMenuItem(text = { Text("Move up") }, enabled = index > 0, onClick = { menu = false; move(index - 1) })
                DropdownMenuItem(text = { Text("Move down") }, enabled = index < total - 1, onClick = { menu = false; move(index + 1) })
                DropdownMenuItem(text = { Text("Remove entry") }, onClick = { menu = false; remove() })
            } };handle() } })
}

@Composable private fun SongRow(track: Track, ready: Boolean, play: () -> Unit, send: (String, Bundle) -> Unit,
    art: String? = null, selected: Boolean? = null, toggle: (() -> Unit)? = null, addToPlaylist: () -> Unit = {},
    details: () -> Unit) {
    val menu = remember(track.id) { mutableStateOf(false) }
    ListItem(modifier = Modifier.combinedClickable(enabled = ready,
        onClick = { if (selected != null) toggle?.invoke() else if (track.availability == Availability.AVAILABLE) play() },
        onLongClickLabel = "Song options", onLongClick = { menu.value = true }),
        leadingContent = { if (selected != null) Checkbox(selected, { toggle?.invoke() }) else CoverArt(art, track.tags.title, Modifier.size(48.dp)) },
        headlineContent = { MarqueeText(track.tags.title) },
        supportingContent = { Text(artist(track) + if (track.availability != Availability.AVAILABLE) " · Unavailable" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { TrackMenu(track, ready, send, addToPlaylist, details, menu) })
}
