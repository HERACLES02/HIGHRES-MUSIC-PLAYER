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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class ControlledSoulseekTab(private val app: Context) : SoulseekTab {
    private val settings = app.getSharedPreferences("controlled-soulseek", Context.MODE_PRIVATE)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var idle: Job? = null
    private var online by mutableStateOf(false)
    private var working by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var destination by mutableStateOf(settings.getString("destination", null)?.let(Uri::parse))
    private var chosen by mutableStateOf<FixtureTrack?>(null)
    private var visible = false
    val ownsSession: Boolean get() = online || working

    override var enabled by mutableStateOf(settings.getBoolean("enabled", false))
        private set

    override fun setVisible(visible: Boolean) {
        this.visible = visible
        idle?.cancel()
        if (!visible) {
            idle = scope.launch {
                snapshotFlow { !working && !PeerTransferService.state.running }.first { it }
                delay(30_000)
                if (online) goOffline()
            }
        }
    }

    private fun goOnline() = sessionAction("Connecting to the private fixture…") {
        check(connectivity.wifiNetwork() != null) { "Connect to Wi-Fi to go online." }
        SoulseekFixture.connect()
        online = true
    }

    private fun goOffline() {
        if (!online) return
        online = false
        sessionAction("Disconnecting…") {
            val transfer = snapshotFlow { PeerTransferService.state }
                .first { !it.running || (it.fromSoulseekTab && it.pausable) }
            if (transfer.running && transfer.fromSoulseekTab) {
                PeerTransferService.pause(app)
                snapshotFlow { PeerTransferService.state.running }.first { !it }
            }
            SoulseekFixture.disconnect()
        }
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
        val pending = remember(transfer) {
            if (transfer.running) null else PendingFixtureTransfer.read(app, fromSoulseekTab = true)
        }
        var downloads by rememberSaveable { mutableStateOf(downloadsRequest != 0 || pending != null) }
        LaunchedEffect(downloadsRequest) { if (downloadsRequest != 0) downloads = true }
        var hash by rememberSaveable { mutableStateOf("") }
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
        fun start(tree: Uri, sha256: String, track: FixtureTrack) {
            try {
                PeerTransferService.start(context, tree, "127.0.0.1", sha256, track, fromSoulseekTab = true)
                chosen = null
                message = ""
                if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (_: IllegalStateException) {
                message = "Could not start the download. Return to this screen and retry."
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!downloads, { downloads = false }, label = { Text("Search") })
                FilterChip(downloads, { downloads = true }, label = { Text("Downloads") })
            }
            if (online) {
                TextButton(enabled = !working, onClick = ::goOffline) { Text("Go offline") }
            } else {
                Button(enabled = !working && !transfer.running, onClick = { goOnline() }) { Text("Go online") }
                Text("Offline. Use Wi-Fi to connect to the private fixtures.")
            }
            if (message.isNotEmpty()) Text(message)
            if (downloads) {
                if (transfer.fromSoulseekTab && transfer.message.isNotEmpty()) Text(transfer.message)
                transfer.libraryTrack?.let { id ->
                    TextButton(onClick = { openInLibrary(id) }) { Text("Open in Library") }
                }
                if (transfer.fromSoulseekTab && transfer.pausable) {
                    TextButton(onClick = { PeerTransferService.pause(context) }) { Text("Pause") }
                }
                if (pending != null) {
                    Text("${pending.track.name} from ${pending.track.user} is waiting for Resume.")
                    Button(enabled = online && !working, onClick = {
                        start(pending.tree, pending.sha256, pending.track)
                    }) { Text("Resume") }
                } else if (!transfer.running) chosen?.let { track ->
                    Text("${track.name} · ${track.size} bytes")
                    Text("The Library will scan the selected folder and its subfolders.")
                    val tree = destination
                    if (tree != null) Text("Phone / ${DocumentsContract.getTreeDocumentId(tree).substringAfter(':')}")
                    TextButton(onClick = {
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
                    }) { Text("Choose download folder") }
                    OutlinedTextField(hash, { hash = it.take(128) }, Modifier.fillMaxWidth(), singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        label = { Text("Fixture SHA-256") })
                    val sha256 = hash.trim().lowercase()
                    Button(enabled = online && !working && tree != null && fixtureHashPattern.matches(sha256),
                        onClick = { if (tree != null) start(tree, sha256, track) }) { Text("Download ${track.name}") }
                } ?: run {
                    if (!transfer.fromSoulseekTab || transfer.message.isEmpty()) Text("Choose a track from Search.")
                }
            } else if (online) {
                FixtureSearch(enabled = !working && !transfer.running, canDownload = pending == null,
                    chooseTrack = true) { track ->
                    chosen = track
                    hash = ""
                    downloads = true
                }
            }
        }
    }
}
