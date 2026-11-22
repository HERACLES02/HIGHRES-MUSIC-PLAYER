package com.localmusic.player

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.localmusic.core.TrackId
import com.localmusic.player.FixtureSaveStatus.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class ControlledSoulseekTab(private val app: Context) : SoulseekTab {
    private val settings = app.getSharedPreferences("controlled-soulseek", Context.MODE_PRIVATE)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var idle: Job? = null
    private var sessionWatch: Job? = null
    private var online by mutableStateOf(false)
    private var working by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var destination by mutableStateOf(settings.getString("destination", null)?.let(Uri::parse))
    private var chosen by mutableStateOf(emptyList<FixtureTrack>())
    private val hashes = mutableStateMapOf<FixtureTrack, String>()
    private var visible = false
    private val sharing = FixtureSharing(app, connectivity, scope, { online }) {
        if (!visible) setVisible(false)
    }
    private val chat = FixtureChat(app, connectivity, scope)
    private val wishlist = FixtureWishlist(settings)
    val ownsSession: Boolean get() = online || working

    fun sharingServiceStarted() = sharing.serviceStarted()
    fun sharingServiceEnded() = sharing.serviceEnded()

    override var enabled by mutableStateOf(settings.getBoolean("enabled", false))
        private set

    override fun setVisible(visible: Boolean) {
        this.visible = visible
        idle?.cancel()
        if (!visible) {
            idle = scope.launch {
                snapshotFlow { !working && !PeerTransferService.state.running }.first { it }
                delay(30_000)
                if (online && !sharing.keepingSession) goOffline()
            }
        }
    }

    private fun goOnline() = sessionAction("Connecting to the private fixture…") {
        check(connectivity.wifiNetwork() != null) { "Connect to Wi-Fi to go online." }
        SoulseekFixture.connect()
        online = true
        sessionWatch = scope.launch {
            while (true) {
                try {
                    sharing.refresh()
                    chat.refresh()
                    if (connectivity.wifiNetwork() != null) wishlist.refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = e.message ?: "Could not refresh the private session."
                }
                delay(750)
            }
        }
    }

    private fun goOffline() {
        if (!online) return
        online = false
        sessionAction("Disconnecting…") {
            sessionWatch?.cancelAndJoin()
            chat.endSession()
            wishlist.endSession()
            try {
                sharing.stop()
            } finally {
                disconnectTransfers()
            }
        }
    }

    private suspend fun disconnectTransfers() {
        val transfer = snapshotFlow { PeerTransferService.state }
            .first { !it.running || (it.fromSoulseekTab && it.pausable) }
        if (transfer.running && transfer.fromSoulseekTab) {
            PeerTransferService.pause(app)
            snapshotFlow { PeerTransferService.state.running }.first { !it }
        }
        SoulseekFixture.disconnect()
    }

    private fun sessionAction(status: String, action: suspend () -> Unit) {
        working = true
        message = status
        scope.launch {
            try {
                action()
                message = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "The private session could not complete the request."
            } finally {
                working = false
                if (!visible) setVisible(false)
            }
        }
    }

    @Composable
    override fun Settings() {
        SettingsCard("Soulseek") {
            SettingSwitch("Enable Soulseek tab", enabled, !working) { value ->
                settings.edit().putBoolean("enabled", value).apply()
                enabled = value
                if (!value) goOffline()
            }
            Text("Connect to the private test fixtures over Wi-Fi.")
        }
    }

    @Composable
    override fun Content(downloadsRequest: Int, openInLibrary: (TrackId) -> Unit) {
        val context = LocalContext.current
        val transfer = PeerTransferService.state
        val saved = remember(transfer.running, transfer.album) {
            transfer.album ?: FixtureAlbumDownload.read(app)
        }
        var section by rememberSaveable {
            mutableStateOf(if (downloadsRequest != 0 || saved?.tracks?.any { !it.status.finished } == true)
                "Downloads" else "Search")
        }
        LaunchedEffect(downloadsRequest) { if (downloadsRequest != 0) section = "Downloads" }
        val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            val tree = data?.data
            if (result.resultCode == android.app.Activity.RESULT_OK && tree != null) {
                try {
                    val folder = FixtureDestination(app.contentResolver, tree)
                    val internalFolder = DocumentsContract.getTreeDocumentId(tree).startsWith("primary:")
                    check(internalFolder) { "Choose a folder under Internal storage." }

                    val flags = data.flags and PEER_FOLDER_GRANT_FLAGS
                    check(flags == PEER_FOLDER_GRANT_FLAGS) { "Choose a folder with read and write access." }
                    app.contentResolver.takePersistableUriPermission(
                        tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    folder.requireWritable()
                    settings.edit().putString("destination", tree.toString()).apply()
                    destination = tree
                    message = ""
                } catch (e: Exception) {
                    message = e.message ?: "Could not keep access to the chosen folder."
                }
            }
        }
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val chooseShare = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let(sharing::preview)
            }
        }
        fun requestNotifications() {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        fun start(album: FixtureAlbumDownload, index: Int = -1, action: FixtureTrackAction? = null) {
            try {
                PeerTransferService.startAlbum(context, album, index, action)
                chosen = emptyList()
                hashes.clear()
                message = ""
                requestNotifications()
            } catch (_: IllegalStateException) {
                message = "Could not start the download. Return to this screen and retry."
            }
        }

        fun pickDestination() {
            try {
                chooseFolder.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents", "primary:Music"
                    ))
                    .addFlags(PEER_FOLDER_GRANT_FLAGS or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
            } catch (_: ActivityNotFoundException) {
                message = "The folder picker is unavailable."
            }
        }

        val chooseTracks: (List<FixtureTrack>) -> Unit = { tracks ->
            chosen = tracks
            hashes.clear()
            section = "Downloads"
        }
        val canDownload = saved?.tracks?.all { it.status.finished } != false

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayerActions {
                for (page in listOf("Search", "Downloads", "Sharing", "Chat", "Wishlist")) {
                    FilterChip(section == page, { section = page }, label = {
                        Text(if (page == "Chat") chatLabel(page, chat.privateUnread + chat.roomUnread) else page)
                    })
                }
            }
            if (online) {
                TextButton(enabled = !working, onClick = ::goOffline) { Text("Go offline") }
            } else {
                Button(enabled = !working && !transfer.running, onClick = { goOnline() }) { Text("Go online") }
                Text("Offline. Use Wi-Fi to connect to the private fixtures.")
            }
            if (message.isNotEmpty()) Text(message)
            if (section == "Chat") {
                FixtureChatPanel(chat, online && !working)
            } else if (section == "Wishlist") {
                FixtureWishlistPanel(wishlist, online && !working && !transfer.running, canDownload, chooseTracks)
            } else if (section == "Sharing") {
                SharingPanel(sharing, online && !working, pickFolder = {
                    try {
                        chooseShare.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                                "com.android.externalstorage.documents", "primary:Music"
                            ))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
                    } catch (_: ActivityNotFoundException) {
                        message = "The folder picker is unavailable."
                    }
                }, enable = {
                    sharing.start()
                    requestNotifications()
                })
            } else if (section == "Downloads") {
                val tree = destination
                if (tree != null) Text("Phone / ${DocumentsContract.getTreeDocumentId(tree).substringAfter(':')}")
                TextButton(enabled = !transfer.running, onClick = ::pickDestination) { Text("Choose download folder") }
                when {
                    chosen.isNotEmpty() -> {
                        Text("${chosen.size} selected ? ${chosen.sumOf { it.size }} bytes")
                        Text("The Library will scan the selected folder and its subfolders.")
                        chosen.forEach { track ->
                            OutlinedTextField(hashes[track].orEmpty(), { hashes[track] = it.take(128) },
                                Modifier.fillMaxWidth(), singleLine = true,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false),
                                label = { Text("${track.name} SHA-256") })
                        }
                        val ready = chosen.all { fixtureHashPattern.matches(hashes[it].orEmpty().trim().lowercase()) }
                        Button(enabled = online && !working && !transfer.running && tree != null && ready,
                            onClick = {
                                if (tree != null) start(FixtureAlbumDownload(tree, chosen.map {
                                    FixtureAlbumTrack(it, hashes.getValue(it).trim().lowercase())
                                }))
                            }) { Text("Download ${chosen.size} selected") }
                        TextButton(onClick = { chosen = emptyList(); hashes.clear(); section = "Search" }) {
                            Text("Back to Search")
                        }
                    }
                    saved != null -> {
                        val active = transfer.activeTrack
                        Text("${saved.folder.substringAfterLast('\\').substringAfterLast('/')} ? ${saved.uploader}")
                        if (active == null && transfer.fromSoulseekTab && transfer.message.isNotEmpty()) {
                            Text(transfer.message)
                        }
                        if (transfer.fromSoulseekTab && transfer.pausable) {
                            TextButton(onClick = { PeerTransferService.pause(context) }) { Text("Pause") }
                        }
                        if (!transfer.running && saved.tracks.any { it.status == QUEUED }) {
                            Button(enabled = online && !working, onClick = {
                                start(saved.copy(tree = tree ?: saved.tree))
                            }) { Text("Resume queued tracks") }
                        }
                        saved.tracks.forEachIndexed { index, row ->
                            val status = if (active == index) transfer.message
                                else row.message.ifEmpty { "Queued ? ${row.track.size} bytes" }
                            Text(row.track.name)
                            Text(status)
                            if (active == index) transfer.percent?.let { percent ->
                                LinearProgressIndicator(progress = { percent / 100f }, Modifier.fillMaxWidth())
                            }
                            row.libraryTrack?.let { id ->
                                TextButton(onClick = { openInLibrary(id) }) { Text("Open ${row.track.name} in Library") }
                            }
                            if (!transfer.running) {
                                fun act(action: FixtureTrackAction) =
                                    start(saved.copy(tree = tree ?: saved.tree), index, action)
                                when (row.status) {
                                    CONFLICT -> {
                                        TextButton(onClick = { act(FixtureTrackAction.SKIP) }) { Text("Skip") }
                                        TextButton(onClick = { act(FixtureTrackAction.SAVE_SEPARATE) }) {
                                            Text("Save separate copy")
                                        }
                                    }
                                    SAVING, SAVE_FAILED -> {
                                        TextButton(onClick = { act(FixtureTrackAction.RETRY_SAVE) }) { Text("Retry save") }
                                        TextButton(onClick = { act(FixtureTrackAction.DISCARD) }) { Text("Discard download") }
                                    }
                                    DOWNLOAD_FAILED -> {
                                        TextButton(enabled = online && !working, onClick = {
                                            act(FixtureTrackAction.RETRY_DOWNLOAD)
                                        }) { Text("Retry download") }
                                        TextButton(onClick = { act(FixtureTrackAction.DISCARD) }) { Text("Discard download") }
                                    }
                                    QUEUED -> TextButton(onClick = { act(FixtureTrackAction.DISCARD) }) {
                                        Text("Remove from queue")
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    }
                    else -> Text("Choose tracks from Search or Wishlist.")
                }
            } else if (online) {
                FixtureSearch(enabled = !working && !transfer.running, canDownload, chooseTracks)
            }
        }
    }
}

@Composable
private fun SharingPanel(
    sharing: FixtureSharing,
    online: Boolean,
    pickFolder: () -> Unit,
    enable: () -> Unit,
) {
    val status = sharing.status
    if (online) Text(when (val seconds = status.privilegeSeconds) {
        null -> "Waiting for the server's privilege status."
        0L -> "Account privileges: none."
        else -> "Account privileges: $seconds seconds remaining."
    })
    if (sharing.message.isNotEmpty()) Text(sharing.message)
    if (status.sharing) {
        Text("Sharing over Wi-Fi. One upload slot.")
        Button(enabled = !sharing.busy, onClick = sharing::turnOff) { Text("Turn sharing off") }
        if (status.uploads.isEmpty()) Text("No uploads in progress.")
        status.uploads.forEach { upload ->
            Text("${upload.user}${if (upload.privileged) " (privileged)" else ""}")
            Text(upload.file.path)
            Text(upload.position?.let { "Queued at position $it" } ?: "Uploading ${upload.sent} / ${upload.file.size} bytes")
        }
    } else {
        Text("Sharing is off.")
        TextButton(enabled = !sharing.busy, onClick = pickFolder) { Text("Choose share folder") }
        if (sharing.files.isNotEmpty()) {
            Text("Peers will see these ${sharing.files.size} files.")
            Button(enabled = online && !sharing.busy, onClick = enable) { Text("Enable sharing") }
        }
    }
    sharing.files.forEach { file -> Text("${file.path} • ${file.size} bytes") }
}
