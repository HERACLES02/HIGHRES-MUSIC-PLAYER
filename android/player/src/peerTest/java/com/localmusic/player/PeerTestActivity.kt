package com.localmusic.player

import android.content.Intent
import android.net.Uri
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
import androidx.lifecycle.lifecycleScope
import com.localmusic.core.Availability
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PeerTestActivity : ComponentActivity() {
    private val settings by lazy { getSharedPreferences("peer-fixture", MODE_PRIVATE) }
    private var folder by mutableStateOf<Uri?>(null)
    private var host by mutableStateOf("")
    private var hash by mutableStateOf("")
    private var status by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private val folderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val tree = data?.data
        if (result.resultCode == RESULT_OK && tree != null) {
            try {
                FixtureDestination(contentResolver, tree)
                val flags = data.flags and GRANT_FLAGS
                require(flags == GRANT_FLAGS) { "Choose a folder with read and write access." }
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
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                            .verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Controlled FLAC download", style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(host, { host = it }, enabled = !busy, singleLine = true,
                            label = { Text("Peer IPv4 address") })
                        OutlinedTextField(hash, { hash = it }, enabled = !busy, singleLine = true,
                            label = { Text("Fixture SHA-256") })
                        Text(folder?.lastPathSegment ?: "Choose a disposable destination folder.")
                        TextButton(onClick = {
                            folderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                                GRANT_FLAGS or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                            ))
                        }, enabled = !busy) { Text("Choose folder") }
                        Button(onClick = { folder?.let(::download) }, enabled = !busy && folder != null) {
                            Text("Download or retry")
                        }
                        if (status.isNotEmpty()) Text(status)
                        TextButton(onClick = { startActivity(Intent(this@PeerTestActivity, MainActivity::class.java)) },
                            enabled = !busy) { Text("Open Library") }
                    }
                }
            }
        }
    }

    private fun download(tree: Uri) {
        val address = host.trim()
        val sha256 = hash.trim().lowercase()
        if (!fixtureHashPattern.matches(sha256)) {
            status = "Enter the 64-character SHA-256 printed by the fixture peer."
            return
        }
        busy = true
        status = "Checking the destination and fixture…"
        settings.edit().putString("host", address).putString("hash", sha256).apply()
        lifecycleScope.launch {
            try {
                transferLock.withLock {
                    val uri = runInterruptible(Dispatchers.IO) {
                        val destination = FixtureDestination(contentResolver, tree)
                        destination.requireWritable()
                        destination.findFinished()?.takeIf { destination.matches(it, sha256) }?.let { return@runInterruptible it }
                        val staged = File(filesDir, "peer-fixture-$sha256.part")
                        ControlledPeer(address).fetch(staged, sha256)
                        destination.publish(staged, sha256).also { staged.delete() }
                    }
                    status = "Verified the FLAC. Scanning the Library…"
                    val store = (application as MusicApplication).storage.await()
                    if (store.state.value.folders.none { it.treeUri == tree.toString() }) {
                        store.addFolder(tree, GRANT_FLAGS)
                    }
                    // A direct scan waits for any scan already in progress and
                    // works with automatic rescans disabled.
                    store.scan()
                    check(store.state.value.tracks.count { track ->
                        track.availability == Availability.AVAILABLE && track.sources.any { it.uri == uri.toString() }
                    } == 1) { "The verified file is saved. Retry to finish adding it to the Library." }
                    status = "Ready in Library. The FLAC was verified by SHA-256."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Download or import stopped. ${e.message ?: "Check the controlled peer and try again."}"
            } finally {
                busy = false
            }
        }
    }

    private companion object {
        const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val transferLock = Mutex()
    }
}
