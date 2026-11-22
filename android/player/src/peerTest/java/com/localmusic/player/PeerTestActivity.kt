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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localmusic.core.PeerSearchOptions
import com.localmusic.core.collectPeerSearchTracks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
                val sessionOwned = (application as PeerTestApplication).soulseekTab?.ownsSession == true
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

@androidx.compose.runtime.Composable
internal fun FixtureSearch(
    enabled: Boolean,
    canDownload: Boolean,
    chooseTracks: ((List<FixtureTrack>) -> Unit)? = null,
    wishlistMatches: List<FixtureTrack>? = null,
    options: PeerSearchOptions? = null,
    onOptions: (PeerSearchOptions) -> Unit = {},
    onFolderOpened: suspend () -> Unit = {},
    download: ((FixtureTrack) -> Unit)? = null,
) {
    val expanded = options != null
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<FixtureTrack>()) }
    var listing by remember { mutableStateOf<List<FixtureTrack>?>(null) }
    var inspected by remember { mutableStateOf<List<FixtureTrack>?>(null) }
    var selected by remember { mutableStateOf(emptySet<FixtureTrack>()) }
    var pending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf(false) }
    var limit by remember(options) { mutableStateOf(PEER_SEARCH_PAGE_SIZE) }
    var folderLimit by remember { mutableStateOf(PEER_SEARCH_PAGE_SIZE) }
    val canSearch = enabled && !pending
    fun load(label: String, operation: suspend () -> Unit) {
        pending = true
        message = label
        scope.launch {
            try {
                operation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "The controlled operation failed."
            } finally {
                pending = false
            }
        }
    }

    val collected = wishlistMatches ?: results
    fun inspect(track: FixtureTrack) {
        folderLimit = PEER_SEARCH_PAGE_SIZE
        load("Requesting the folder from ${track.user}…") {
            try {
                val folderTracks = SoulseekFixture.folder(track, diagnostic = chooseTracks == null, expanded = expanded)
                    .filter { it.user == track.user && it.folder == track.folder }
                listing = if (expanded) collectPeerSearchTracks(listOf(track) + folderTracks) else folderTracks
                message = "${track.user} · ${track.folder}"
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalStateException) {
                val matches = collected.filter { it.user == track.user && it.folder == track.folder }
                inspected = if (expanded) listOf(track) + matches.filterNot { it == track } else matches
                message = "Folder unavailable. Choose a search match from ${track.user}."
            }
            if (expanded) onFolderOpened()
        }
    }

    if (wishlistMatches == null && (!expanded || (listing == null && inspected == null))) {
        if (!expanded) Text("Private Soulseek search", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(query, { query = it }, singleLine = true, enabled = canSearch,
            label = { Text("Artist, album, or song") })
        Button(enabled = canSearch && query.isNotBlank(), onClick = {
            results = emptyList()
            listing = null
            inspected = null
            selected = emptySet()
            searched = false
            limit = PEER_SEARCH_PAGE_SIZE
            load(if (expanded) "Collecting results…" else "Searching the private fixture…") {
                results = SoulseekFixture.search(query.trim(), diagnostic = chooseTracks == null, expanded = expanded)
                searched = true
                message = if (expanded) "" else if (results.isEmpty()) "No FLAC matches." else "Choose an uploader’s folder."
            }
        }) { Text(if (expanded) "Search" else "Search FLAC") }
    }
    if (message.isNotEmpty()) Text(message)
    val tracks = listing ?: inspected
    if (tracks == null) {
        if (options != null) {
            if (searched) PeerSearchResults(collected, options, onOptions, limit,
                onLoadMore = { limit += PEER_SEARCH_PAGE_SIZE }, enabled = canSearch, inspect = ::inspect)
        } else {
            collected.groupBy { it.user to it.folder }.forEach { (key, matches) ->
                Text("${key.first} · ${key.second}")
                matches.forEach { Text("${it.name} · ${it.size} bytes") }
                TextButton(enabled = canSearch, onClick = { inspect(matches.first()) }) {
                    Text("Inspect ${key.first}’s folder")
                }
            }
        }
    } else {
        TextButton(enabled = canSearch, onClick = {
            listing = null
            inspected = null
            selected = emptySet()
            message = if (expanded) "" else "Choose an uploader’s folder."
        }) { Text("Back to results") }
        if (tracks.isEmpty()) Text(if (expanded) "This folder has no downloadable tracks."
            else "This folder has no downloadable FLAC tracks.")
        if (chooseTracks != null && listing != null && tracks.isNotEmpty()) {
            if (expanded) {
                Text("${selected.size} selected · ${selected.sumOf { it.size }} bytes")
                Button(enabled = canSearch && canDownload && selected.isNotEmpty(),
                    onClick = { chooseTracks(tracks.filter { it in selected }) }) { Text("Continue to Downloads") }
            }
            TextButton(enabled = canSearch && canDownload, onClick = {
                selected = if (selected.size == tracks.size) emptySet() else tracks.toSet()
            }) { Text(if (selected.size == tracks.size) "Clear selection" else if (expanded) "Select all tracks" else "Select all FLACs") }
        }
        val visibleTracks = if (expanded) tracks.take(folderLimit) else tracks
        visibleTracks.forEach { track ->
            if (chooseTracks != null && listing != null) {
                val checked = track in selected
                androidx.compose.material3.FilterChip(
                    selected = checked, enabled = canSearch && canDownload,
                    onClick = { selected = if (checked) selected - track else selected + track },
                    label = {
                        if (expanded) Column(Modifier.padding(vertical = 4.dp)) {
                            Text(track.name, style = MaterialTheme.typography.bodyMedium)
                            TrackDetails(track)
                        } else Text("${track.name} · ${track.size} bytes")
                    },
                    leadingIcon = {
                        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = null)
                    },
                )
            } else {
                if (expanded) {
                    Text(track.name, style = MaterialTheme.typography.bodyMedium)
                    TrackDetails(track)
                } else Text("${track.name} · ${track.size} bytes")
                Button(enabled = canSearch && canDownload, onClick = {
                    if (chooseTracks != null) chooseTracks(listOf(track)) else download?.invoke(track)
                }) { Text("Download ${track.name}") }
            }
        }
        if (visibleTracks.size < tracks.size) {
            TextButton(onClick = { folderLimit += PEER_SEARCH_PAGE_SIZE }) { Text("Load more tracks") }
        }
        if (!expanded && chooseTracks != null && listing != null && tracks.isNotEmpty()) {
            Text("${selected.size} selected · ${selected.sumOf { it.size }} bytes")
            Button(enabled = canSearch && canDownload && selected.isNotEmpty(),
                onClick = { chooseTracks(tracks.filter { it in selected }) }) { Text("Continue to Downloads") }
        }
    }
}
