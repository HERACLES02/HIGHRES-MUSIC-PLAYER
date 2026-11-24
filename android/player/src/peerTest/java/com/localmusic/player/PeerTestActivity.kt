package com.localmusic.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class PeerTestActivity : ComponentActivity() {
    private val settings by lazy { getSharedPreferences("peer-fixture", MODE_PRIVATE) }
    private var folder by mutableStateOf<Uri?>(null)
    private var host by mutableStateOf("")
    private var hash by mutableStateOf("")
    private var status by mutableStateOf<String?>(null)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val folderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val tree = data?.data
        if (result.resultCode == RESULT_OK && tree != null) {
            try {
                FixtureDestination(contentResolver, tree)
                val flags = data.flags and PEER_FOLDER_GRANT_FLAGS
                require(flags == PEER_FOLDER_GRANT_FLAGS) { "Choose a folder with read and write access." }
                contentResolver.takePersistableUriPermission(tree, flags)
                folder = tree
                settings.edit().putString("folder", tree.toString()).apply()
                status = ""
            } catch (e: Exception) {
                status = e.message ?: "Could not retain folder access."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folder = settings.getString("folder", null)?.let(Uri::parse)
        host = settings.getString("host", "127.0.0.1").orEmpty()
        hash = settings.getString("hash", "").orEmpty()
        setContent {
            MaterialTheme {
                val transfer = PeerTransferService.state
                val sessionOwned = (application as MusicApplication).soulseekTab?.ownsSession == true
                val busy = transfer.running || sessionOwned
                val saved = if (busy) null else PendingFixtureTransfer.read(this)
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                            .verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Controlled FLAC download", style = MaterialTheme.typography.headlineSmall)
                        if (sessionOwned) Text("Go offline in the Soulseek tab to use transfer diagnostics.")
                        OutlinedTextField(host, { host = it }, enabled = !busy, singleLine = true,
                            label = { Text("Peer IPv4 address") })
                        OutlinedTextField(hash, { hash = it }, enabled = !busy, singleLine = true,
                            label = { Text("Fixture SHA-256") })
                        Text(folder?.lastPathSegment ?: "Choose a disposable destination folder.")
                        TextButton(onClick = {
                            folderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                                PEER_FOLDER_GRANT_FLAGS or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                            ))
                        }, enabled = !busy) { Text("Choose folder") }
                        Button(onClick = { folder?.let { download(it) } }, enabled = !busy && folder != null) {
                            Text("Download or retry")
                        }
                        if (saved != null) {
                            Text("${saved.track.name} from ${saved.track.user} is waiting for Resume.")
                            Button(onClick = { startTransfer(saved.tree, saved.sha256, saved.track) }) { Text("Resume") }
                        }
                        if (transfer.pausable && !transfer.fromSoulseekTab) {
                            Button(onClick = { PeerTransferService.pause(this@PeerTestActivity) }) { Text("Pause") }
                        }
                        val message = if (busy) transfer.message else status ?: transfer.message
                        if (message.isNotEmpty()) Text(message)
                        if (SoulseekFixture.available) {
                            FixtureSearch(enabled = !busy, canDownload = folder != null) { track ->
                                folder?.let { download(it, track) }
                            }
                        }
                        TextButton(onClick = { startActivity(Intent(this@PeerTestActivity, MainActivity::class.java)) },
                            enabled = !transfer.running) { Text("Open Library") }
                    }
                }
            }
        }
    }

    private fun download(tree: Uri, track: FixtureTrack? = null) {
        startTransfer(tree, hash.trim().lowercase(), track)
    }

    private fun startTransfer(tree: Uri, sha256: String, track: FixtureTrack?) {
        val address = host.trim()
        if (!fixtureHashPattern.matches(sha256)) {
            status = "Enter the 64-character SHA-256 printed by the fixture peer."
            return
        }
        status = null
        val started = try {
            PeerTransferService.start(this, tree, address, sha256, track)
            true
        } catch (_: IllegalStateException) {
            false
        }
        if (!started) {
            status = "Could not start the download. Open this screen and try again."
            return
        }
        settings.edit().putString("host", address).putString("hash", sha256).apply()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
