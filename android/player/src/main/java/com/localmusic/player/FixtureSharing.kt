package com.localmusic.player

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.provider.DocumentsContract as Docs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal data class FixtureSharedFile(val path: String, val size: Long) {
    companion object {
        fun decode(row: JSONObject) = FixtureSharedFile(row.getString("path"), row.getLong("size"))
    }
}

internal data class FixtureUpload(
    val user: String, val file: FixtureSharedFile, val sent: Long, val position: Long?, val privileged: Boolean,
)

internal data class FixtureShareStatus(
    val sharing: Boolean = false,
    val privilegeSeconds: Long? = null,
    val uploads: List<FixtureUpload> = emptyList(),
) {
    companion object {
        fun decode(row: JSONObject): FixtureShareStatus {
            val uploads = row.getJSONArray("uploads")
            return FixtureShareStatus(row.getBoolean("sharing"),
                row.optLong("privilegeSeconds", -1).takeIf { it >= 0 },
                List(uploads.length()) { index ->
                    val upload = uploads.getJSONObject(index)
                    FixtureUpload(upload.getString("user"), FixtureSharedFile.decode(upload), upload.getLong("sent"),
                        upload.optLong("position", -1).takeIf { it > 0 }, upload.getBoolean("privileged"))
                })
        }
    }
}

internal class FixtureSharing(
    private val app: Context,
    private val connectivity: ConnectivityManager,
    private val scope: CoroutineScope,
    private val online: () -> Boolean,
    private val onStopped: () -> Unit,
) {
    private data class Preview(val root: File, val files: List<FixtureSharedFile>) {
        fun discard() { root.parentFile?.deleteRecursively() }
    }

    private val actions = Mutex()
    private val staging = File(app.noBackupFilesDir, "peer-share-preview")
    private var serviceReady: CompletableDeferred<Unit>? = null
    private var network: Network? = null
    private var previousBinding: Network? = null
    private var preview by mutableStateOf<Preview?>(null)
    val files get() = preview?.files.orEmpty()
    val keepingSession: Boolean get() = serviceReady != null || network != null
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    var status by mutableStateOf(FixtureShareStatus())
        private set

    init {
        scope.launch {
            actions.withLock {
                withContext(Dispatchers.IO) {
                    // An abruptly ended process cannot discard its private copy.
                    staging.listFiles()?.forEach { it.deleteRecursively() }
                }
            }
        }
    }

    fun preview(tree: Uri) {
        if (busy || keepingSession) return
        runAction("Preparing share preview…") {
            val previous = preview
            preview = null
            preview = withContext(Dispatchers.IO) {
                previous?.discard()
                check(staging.exists() || staging.mkdir()) { "Could not create the private share preview." }
                val generation = Files.createTempDirectory(staging.toPath(), "share-").toFile()
                try {
                    val root = copyShareFolder(app.contentResolver, tree, generation)
                    val files = SoulseekFixture.sharePreview(root)
                    check(files.isNotEmpty()) { "There are no files to share in the selected folder." }
                    Preview(root, files)
                } catch (e: Exception) {
                    generation.deleteRecursively()
                    throw e
                }
            }
        }
    }

    fun start() {
        val approved = preview ?: return
        if (busy || keepingSession || !online()) return
        runAction("Turning sharing on…") {
            val wifi = checkNotNull(connectivity.wifiNetwork()) { "Connect to Wi-Fi to share." }
            val ready = CompletableDeferred<Unit>()
            serviceReady = ready
            try {
                app.startForegroundService(serviceIntent())
                check(withTimeoutOrNull(5_000) { ready.await(); true } == true) {
                    "Android could not start the sharing notification."
                }
                check(online() && connectivity.wifiNetwork() == wifi) { "The Wi-Fi session ended." }
                previousBinding = connectivity.boundNetworkForProcess
                check(connectivity.bindProcessToNetwork(wifi)) { "Could not keep uploads on Wi-Fi." }
                network = wifi
                SoulseekFixture.shareOn(approved.root)
                status = SoulseekFixture.shareStatus()
                check(status.sharing && connectivity.wifiNetwork() == wifi) { "The Wi-Fi session ended." }
            } catch (e: Exception) {
                stopLocked()
                throw e
            } finally {
                serviceReady = null
            }
        }
    }

    fun turnOff() {
        if (!busy && keepingSession) runAction("Turning sharing off…") { stopLocked() }
    }

    suspend fun stop() = actions.withLock { if (keepingSession) stopLocked() }

    private suspend fun stopLocked() = withContext(NonCancellable) {
        val bound = network
        network = null
        serviceReady = null
        try {
            SoulseekFixture.shareOff()
        } finally {
            val old = preview
            preview = null
            withContext(Dispatchers.IO) { old?.discard() }
            status = status.copy(sharing = false, uploads = emptyList())
            if (bound != null && !connectivity.bindProcessToNetwork(previousBinding)) {
                connectivity.bindProcessToNetwork(null)
            }
            previousBinding = null
            app.stopService(serviceIntent())
            onStopped()
        }
    }

    suspend fun refresh() = actions.withLock {
        val bound = network
        if (bound != null && connectivity.wifiNetwork() != bound) {
            stopLocked()
            message = "Sharing stopped when Wi-Fi disconnected."
            return@withLock
        }
        val current = SoulseekFixture.shareStatus()
        if (bound != null && !current.sharing) {
            stopLocked()
            message = "Sharing stopped when the Wi-Fi session ended."
        } else {
            status = current
        }
    }

    fun serviceStarted() {
        if (serviceReady?.complete(Unit) != true && network == null) app.stopService(serviceIntent())
    }

    fun serviceEnded() {
        serviceReady?.completeExceptionally(IllegalStateException("Android stopped the sharing notification."))
        if (network != null) scope.launch {
            try {
                stop()
                message = "Android stopped the sharing session."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Could not stop the share."
            }
        }
    }

    private fun serviceIntent() = Intent(app, PeerSharingService::class.java)

    private fun runAction(label: String, block: suspend () -> Unit) {
        busy = true
        message = label
        scope.launch {
            try {
                actions.withLock { block() }
                message = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Could not update sharing."
            } finally {
                busy = false
            }
        }
    }
}

private fun copyShareFolder(resolver: ContentResolver, tree: Uri, generation: File): File {
    require(tree.authority == "com.android.externalstorage.documents" && Docs.isTreeUri(tree)) {
        "Choose a folder under Internal storage."
    }
    val id = Docs.getTreeDocumentId(tree)
    require(id.startsWith("primary:") && id.length > "primary:".length) {
        "Choose a folder under Internal storage."
    }
    val rootUri = Docs.buildDocumentUriUsingTree(tree, id)
    val rootName = checkNotNull(resolver.query(rootUri, arrayOf(Docs.Document.COLUMN_DISPLAY_NAME),
        null, null, null)).use { row ->
        check(row.moveToFirst()) { "The selected folder is unavailable." }
        row.getString(0)
    }
    fun child(parent: File, name: String): File {
        require(name.isNotEmpty() && name != "." && name != ".." && name.none { it in "/\\\u0000" }) {
            "The selected folder contains an unsupported file or folder name."
        }
        return File(parent, name)
    }
    val root = child(generation, rootName)
    val pending = ArrayDeque(listOf(id to root))
    val columns = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME, Docs.Document.COLUMN_MIME_TYPE)
    while (pending.isNotEmpty()) {
        val (folderId, folder) = pending.removeFirst()
        check(folder.mkdir()) { "Could not prepare a folder for sharing." }
        val children = Docs.buildChildDocumentsUriUsingTree(tree, folderId)
        checkNotNull(resolver.query(children, columns, null, null, null)) { "Could not read the selected folder." }
            .use { rows ->
                while (rows.moveToNext()) {
                    val docId = rows.getString(0)
                    val file = child(folder, rows.getString(1))
                    if (rows.getString(2) == Docs.Document.MIME_TYPE_DIR) {
                        pending.addLast(docId to file)
                    } else if (!file.name.endsWith(".part")) {
                        check(file.createNewFile()) { "Two items have the same name in the selected folder." }
                        val uri = Docs.buildDocumentUriUsingTree(tree, docId)
                        checkNotNull(resolver.openInputStream(uri)) { "Could not read a file for the preview." }
                            .use { input -> file.outputStream().use { input.copyTo(it) } }
                    }
                }
            }
    }
    return root
}
