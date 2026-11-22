package com.localmusic.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.localmusic.core.Availability
import com.localmusic.core.TrackId
import com.localmusic.player.FixtureSaveStatus.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal const val PEER_FOLDER_GRANT_FLAGS =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

internal fun ConnectivityManager.wifiNetwork(): Network? = activeNetwork?.takeIf {
    getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
}

internal data class PeerTransferState(
    val running: Boolean = false,
    val message: String = "",
    val pausable: Boolean = false,
    val libraryTrack: TrackId? = null,
    val fromSoulseekTab: Boolean = false,
    val album: FixtureAlbumDownload? = null,
    val activeTrack: Int? = null,
    val percent: Int? = null,
)

class PeerTransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var transfer: Job? = null
    private var pausing = false
    private var notifiedPercent: Int? = null
    private var fromSoulseekTab = false

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            if (state.pausable) {
                pausing = true
                state = state.copy(pausable = false)
                showProgress("Pausing the download.")
                transfer?.cancel()
            } else if (transfer == null) {
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        if (transfer != null) return START_NOT_STICKY
        val tree = intent?.data
        val album = intent?.getStringExtra(EXTRA_ALBUM)?.let { FixtureAlbumDownload.decode(JSONObject(it)) }
        val address = intent?.getStringExtra(EXTRA_ADDRESS)
        val sha256 = intent?.getStringExtra(EXTRA_HASH)
        if (tree == null || (album == null && (address == null || sha256 == null))) {
            state = PeerTransferState(message = "The download request was incomplete. Please retry.")
            stopSelf()
            return START_NOT_STICKY
        }
        fromSoulseekTab = intent.getBooleanExtra(EXTRA_SOULSEEK_TAB, false)

        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Controlled FLAC downloads", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIFICATION_ID, notification(CHECKING))
        transfer = scope.launch {
            val cpu = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${packageName}:peer-transfer")
                .apply { setReferenceCounted(false) }
            try {
                cpu.acquire(WAKE_LOCK_TIMEOUT_MS)
                if (album != null) {
                    val action = intent.getStringExtra(EXTRA_TRACK_ACTION)?.let(FixtureTrackAction::valueOf)
                    runAlbum(album, intent.getIntExtra(EXTRA_TRACK_INDEX, -1), action)
                    return@launch
                }
                val fixtureHash = checkNotNull(sha256)
                val selected = intent.getStringExtra(EXTRA_TRACK)?.let { FixtureTrack.decode(JSONObject(it)) }
                if (selected != null) withContext(Dispatchers.IO) {
                    PendingFixtureTransfer(tree, fixtureHash, selected, fromSoulseekTab).save(this@PeerTransferService)
                }
                val uri = fetchAndPublish(tree, checkNotNull(address), fixtureHash, selected)
                showProgress("Verified the FLAC. Scanning the Library…")
                val imported = importFixture(tree, uri)
                if (selected != null) withContext(Dispatchers.IO) {
                    PendingFixtureTransfer.clear(this@PeerTransferService, fromSoulseekTab)
                }
                state = PeerTransferState(
                    message = "Ready in Library. The FLAC was verified by SHA-256.",
                    libraryTrack = imported.takeIf { fromSoulseekTab },
                    fromSoulseekTab = fromSoulseekTab,
                )
            } catch (e: CancellationException) {
                if (pausing) state = state.copy(running = false, pausable = false, activeTrack = null,
                    message = "Download paused. Tap Resume to continue.")
                throw e
            } catch (e: Exception) {
                state = PeerTransferState(
                    message = "Download or import stopped. ${e.message ?: "Check the controlled peer and try again."}",
                    fromSoulseekTab = fromSoulseekTab,
                )
            } finally {
                if (cpu.isHeld) cpu.release()
                transfer = null
                if (isActive || pausing) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun importFixture(tree: Uri, uri: Uri): TrackId {
        val store = (application as MusicApplication).storage.await()
        if (store.state.value.folders.none { it.treeUri == tree.toString() }) {
            store.addFolder(tree, PEER_FOLDER_GRANT_FLAGS, scheduleScan = false)
        }
        // Wait for any active scan, even with automatic rescans disabled.
        store.scan()
        val imported = store.state.value.tracks.singleOrNull { track ->
            track.availability == Availability.AVAILABLE && track.sources.any { it.uri == uri.toString() }
        }
        return checkNotNull(imported) { "The file is saved. Retry to finish adding it to the Library." }.id
    }

    private suspend fun runAlbum(initial: FixtureAlbumDownload, index: Int, action: FixtureTrackAction?) {
        var album = initial
        suspend fun checkpoint(updated: FixtureAlbumDownload) = withContext(NonCancellable) {
            withContext(Dispatchers.IO) { updated.save(this@PeerTransferService) }
            album = updated
            state = state.copy(album = updated)
        }
        suspend fun update(index: Int, row: FixtureAlbumTrack) {
            val tracks = album.tracks.toMutableList()
            tracks[index] = row
            checkpoint(album.copy(tracks = tracks))
        }

        checkpoint(album)
        val indices = if (action == null) album.tracks.indices.filter { album.tracks[it].status == QUEUED }
            else listOf(index)
        for (position in indices) {
            val row = album.tracks[position]
            val track = row.track
            if (action == FixtureTrackAction.SKIP || action == FixtureTrackAction.DISCARD) {
                update(position, row.copy(status = SKIPPED, message = "Skipped. Existing files were kept."))
                withContext(Dispatchers.IO) {
                    val staged = SoulseekFixture.stagedFile(track, filesDir, row.legacySha256)
                    File(staged.parentFile, "${staged.name}.part").delete()
                    staged.delete()
                    staged.parentFile?.delete()
                }
                continue
            }
            state = state.copy(activeTrack = position, pausable = false, percent = null)
            var savePhase = action == FixtureTrackAction.RETRY_SAVE || action == FixtureTrackAction.SAVE_SEPARATE
            showProgress("Checking ${track.name}…")
            try {
                val (staged, verifiedSha256) = withContext(Dispatchers.IO) {
                    val file = if (savePhase) {
                        SoulseekFixture.stagedFile(track, filesDir, row.legacySha256).also { staged ->
                            if (row.verifiedSha256 == null) {
                                check(SoulseekFixture.verifiedCopy(track, staged, row.legacySha256)) {
                                    "The verified private copy is unavailable."
                                }
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main.immediate) {
                            state = state.copy(pausable = true)
                            showProgress("Waiting to download ${track.name}…")
                        }
                        SoulseekFixture.fetch(track, filesDir, row.legacySha256, connectivity::wifiNetwork) { text, percent ->
                            withContext(Dispatchers.Main.immediate) { showProgress("${track.name}: $text", percent) }
                        }
                    }
                    val digest = file.takeIf { it.isFile && it.length() == track.size }
                        ?.inputStream()?.use { it.fixtureDigest() }
                    check(digest != null && (row.verifiedSha256 == null || digest == row.verifiedSha256)) {
                        "The verified private copy is unavailable."
                    }
                    file to digest
                }
                savePhase = true
                state = state.copy(pausable = false)
                val verified = row.copy(verifiedSha256 = verifiedSha256)
                update(position, verified.copy(status = SAVING, message = "Verified. Saving to the download folder…"))
                showProgress("Saving ${track.name}…")
                val published = withContext(Dispatchers.IO) {
                    val destination = FixtureDestination(contentResolver, album.tree).albumTrack(track)
                    destination.publishAlbumTrack(staged, verifiedSha256, action == FixtureTrackAction.SAVE_SEPARATE)
                }
                if (published == null) {
                    update(position, verified.copy(status = CONFLICT, message = "A different file has this name."))
                    continue
                }
                showProgress("Scanning ${track.name} into the Library…")
                val imported = importFixture(album.tree, published.uri)
                update(position, verified.copy(status = if (published.duplicate) DUPLICATE else READY,
                    message = if (published.duplicate) "Already in Library. Exact duplicate skipped." else "Ready in Library.",
                    libraryTrack = imported))
                withContext(Dispatchers.IO) {
                    staged.delete()
                    staged.parentFile?.delete()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(position, album.tracks[position].copy(status = if (savePhase) SAVE_FAILED else DOWNLOAD_FAILED,
                    message = e.message ?: "Please retry."))
            } finally {
                state = state.copy(activeTrack = null, pausable = false, percent = null)
            }
        }
        state = state.copy(running = false, message = "")
    }

    private suspend fun fetchAndPublish(tree: Uri, address: String, sha256: String, selected: FixtureTrack?): Uri =
        withContext(Dispatchers.IO) {
            val destination = if (fromSoulseekTab && selected != null) {
                FixtureDestination(contentResolver, tree, selected.name)
            } else FixtureDestination(contentResolver, tree)
            destination.requireWritable()
            destination.findFinished()?.let {
                if (destination.matches(it, sha256)) return@withContext it
                check(selected == null) { "The destination already has a different fixture. Choose another folder." }
            }
            if (selected != null) {
                withContext(Dispatchers.Main.immediate) { state = state.copy(pausable = true) }
                val staged = SoulseekFixture.fetch(
                    selected, filesDir, sha256, connectivity::wifiNetwork, diagnostic = true,
                ) { message, percent ->
                    withContext(Dispatchers.Main.immediate) { showProgress(message, percent) }
                }
                withContext(Dispatchers.Main.immediate) { state = state.copy(pausable = false) }
                return@withContext runInterruptible {
                    destination.publish(staged, sha256).also { staged.delete() }
                }
            }
            val staged = File(filesDir, "peer-fixture-$sha256.part")
            fetchWithReconnect(ControlledPeer(address), staged, sha256)
            runInterruptible { destination.publish(staged, sha256).also { staged.delete() } }
        }

    private suspend fun fetchWithReconnect(peer: ControlledPeer, staged: File, sha256: String) {
        var interruptedAt: Long? = null
        while (true) {
            val previousBytes = staged.length()
            var lastUpdate = SystemClock.elapsedRealtime() - 1_000
            try {
                runInterruptible {
                    peer.fetch(staged, sha256) { received, total ->
                        val now = SystemClock.elapsedRealtime()
                        if (received == total || now - lastUpdate >= 1_000) {
                            lastUpdate = now
                            scope.launch {
                                showProgress("Downloading $received / $total bytes", (received * 100 / total).toInt())
                            }
                        }
                    }
                }
                return
            } catch (e: PeerConnectionInterrupted) {
                val savedBytes = staged.length()
                val now = SystemClock.elapsedRealtime()
                val since = if (savedBytes > previousBytes) now else interruptedAt ?: now
                interruptedAt = since
                check(now - since < RECONNECT_WINDOW_MS) {
                    "Could not reconnect. $savedBytes bytes saved. Tap Download or retry."
                }
                withContext(Dispatchers.Main.immediate) {
                    showProgress("Connection interrupted. $savedBytes bytes saved. Waiting for the peer…")
                }
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private fun showProgress(message: String, percent: Int? = null) {
        val next = state.copy(running = true, message = message, percent = percent)
        if (state == next) return
        state = next
        if (percent == null || percent != notifiedPercent) {
            notifiedPercent = percent
            notifications.notify(NOTIFICATION_ID, notification(message, percent))
        }
    }

    private fun notification(message: String, percent: Int? = null): Notification {
        val open = (if (fromSoulseekTab) Intent(this, MainActivity::class.java).setAction(SOULSEEK_DOWNLOADS)
            else Intent(this, PeerTestActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Controlled FLAC download")
            .setContentText(message)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent ?: 0, percent == null)
            .apply {
                if (state.pausable) {
                    val pause = Intent(this@PeerTransferService, PeerTransferService::class.java).setAction(ACTION_PAUSE)
                    addAction(Notification.Action.Builder(
                        null, "Pause", PendingIntent.getService(this@PeerTransferService, 0, pause, PendingIntent.FLAG_IMMUTABLE)
                    ).build())
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        state = PeerTransferState(message = "Android ended the download. Tap Resume to try again.",
            fromSoulseekTab = fromSoulseekTab)
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (transfer?.isActive == true) state = PeerTransferState(message = "Download stopped. Please retry.",
            fromSoulseekTab = fromSoulseekTab)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "peer-transfer"
        private const val NOTIFICATION_ID = 45416
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1_000L
        private const val RECONNECT_WINDOW_MS = 90_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val ACTION_PAUSE = "com.localmusic.player.peer.PAUSE"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_HASH = "sha256"
        private const val EXTRA_TRACK = "fixture-track"
        private const val EXTRA_SOULSEEK_TAB = "from-soulseek-tab"
        private const val EXTRA_ALBUM = "fixture-album"
        private const val EXTRA_TRACK_INDEX = "track-index"
        private const val EXTRA_TRACK_ACTION = "track-action"
        private const val CHECKING = "Checking the destination and fixture…"

        internal var state by mutableStateOf(PeerTransferState())
            private set

        internal fun pause(context: Context) {
            if (state.pausable) context.startService(Intent(context, PeerTransferService::class.java).setAction(ACTION_PAUSE))
        }

        internal fun start(
            context: Context, tree: Uri, address: String, sha256: String,
            track: FixtureTrack? = null, fromSoulseekTab: Boolean = false,
        ) {
            if (state.running) return
            context.startForegroundService(Intent(context, PeerTransferService::class.java).setData(tree)
                .putExtra(EXTRA_ADDRESS, address).putExtra(EXTRA_HASH, sha256)
                .putExtra(EXTRA_SOULSEEK_TAB, fromSoulseekTab)
                .putExtra(EXTRA_TRACK, track?.encode()?.toString()))
            state = PeerTransferState(running = true, message = CHECKING, fromSoulseekTab = fromSoulseekTab)
        }

        internal fun startAlbum(
            context: Context, album: FixtureAlbumDownload, index: Int = -1, action: FixtureTrackAction? = null,
        ) {
            if (state.running) return
            context.startForegroundService(Intent(context, PeerTransferService::class.java).setData(album.tree)
                .putExtra(EXTRA_SOULSEEK_TAB, true).putExtra(EXTRA_ALBUM, album.encode().toString())
                .putExtra(EXTRA_TRACK_INDEX, index).putExtra(EXTRA_TRACK_ACTION, action?.name))
            state = PeerTransferState(running = true, message = CHECKING, fromSoulseekTab = true, album = album)
        }
    }
}
