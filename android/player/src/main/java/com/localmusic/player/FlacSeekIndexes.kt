@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.*
import androidx.media3.extractor.flac.FlacExtractor
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.coroutines.coroutineContext

/** Private, regenerable FLAC frame locations; never writes the source. */
class FlacSeekIndexes(private val context: Context, @Volatile private var fileOperations: Mutex = Mutex()) {
    data class Point(val timeUs: Long, val position: Long)
    class Handle internal constructor(val key: String, val persistent: Boolean, val sourceId: String) {
        @Volatile var points: List<Point>? = null
            internal set
        @Volatile var status = "queued"
            internal set
        @Volatile var reason = "waiting_for_worker"
            internal set
        @Volatile var elapsedMs = 0L
            internal set
        @Volatile var seekStrategy = "none"
            internal set
        @Volatile var seekTargetUs = -1L
            internal set
        fun json(): JSONObject = JSONObject().put("sourceId", sourceId).put("status", status).put("reason", reason)
            .put("points", points?.size ?: 0).put("buildElapsedMs", elapsedMs)
            .put("lastSeekStrategy", seekStrategy).put("lastSeekTargetUs", seekTargetUs)
    }
    private class Request(val handle: Handle, val uri: Uri, val firstFrame: Long, val length: Long, val modified: Long?) {
        var job: Job? = null
    }
    private val directory = File(context.cacheDir, "flac-seek-v1")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val requests = linkedMapOf<String, Request>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var active: Request? = null
    private var windowSet = false
    private var currentUri: Uri? = null
    private var nextUri: Uri? = null
    private val handles = linkedMapOf<String, Handle>()
    @Volatile private var closed = false

    init {
        // Scheduling follows Media3 1.8.0 BasePreloadManager's managed-map / priority /
        // completion-advance lifecycle. Index jobs are not MediaSources, so its playback
        // preload manager is a reference rather than a replacement playback authority.
        // https://github.com/androidx/media/blob/1.8.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/preload/BasePreloadManager.java
        scope.launch {
            for (signal in wake) {
                while (true) {
                    ensureActive()
                    val request = synchronized(lock) {
                        requests.values.filter { it.job == null }.minByOrNull {
                            when (it.uri) { currentUri -> 0; nextUri -> 1; else -> 2 }
                        }?.also { request ->
                            active = request
                            request.job = scope.launch(start = CoroutineStart.LAZY) { runRequest(request) }
                        }
                    } ?: break
                    requireNotNull(request.job).start()
                    requireNotNull(request.job).join()
                    synchronized(lock) {
                        if (requests[request.handle.key] === request) requests.remove(request.handle.key)
                        if (active === request) active = null
                    }
                }
            }
        }
    }

    /** The service owns selection. Old restored/lookahead work cannot displace a new song. */
    fun setPlaybackWindow(current: Uri?, next: Uri? = null) {
        synchronized(lock) {
            windowSet = true; currentUri = current; nextUri = next
            val obsolete = requests.values.filter { it.uri != current && it.uri != next }
            obsolete.forEach { cancel(it, "playback_window_changed") }
            prioritizeCurrent()
        }
        wake.trySend(Unit)
    }

    private fun cancel(request: Request, why: String) {
        if (request.handle.points == null) { request.handle.status = "cancelled"; request.handle.reason = why }
        requests.remove(request.handle.key)
        request.job?.cancel()
    }

    /** Caller holds lock. Yield an in-progress lookahead job without losing its request. */
    private fun prioritizeCurrent() {
        val preferred = requests.values.firstOrNull { it.uri == currentUri && it.job == null } ?: return
        val running = active ?: return
        if (running.uri == preferred.uri || running.handle.points != null ||
            requests[running.handle.key] !== running || running.job?.isCompleted == true) return
        requests[running.handle.key] = Request(running.handle, running.uri, running.firstFrame, running.length, running.modified)
        running.handle.status = "queued"; running.handle.reason = "yield_to_current_song"
        running.job?.cancel()
    }

    /** Attach the store's existing edit/scan gate before any playback is prepared. */
    fun coordinateFileOperations(mutex: Mutex) { fileOperations = mutex }

    /** Called on the extractor loader, never the UI/audio render thread. */
    fun prepare(uri: Uri, firstFrame: Long, length: Long, info: ByteArray): Handle {
        val modified = modified(uri)
        val identity = "$uri|$firstFrame|$length|$modified|${digest(info.copyOfRange(8, 42))}"
        val key = digest(identity.toByteArray())
        // Without a trustworthy modification time, reuse only within this extractor.
        val persistent = modified != null && modified > 0
        val handle = synchronized(lock) {
            requests[key]?.let { return it.handle }
            if (persistent) handles[key]?.let {
                if (it.points != null || it.status in setOf("queued", "building")) return it
                handles.remove(key)
            }
            Handle(key, persistent, digest(uri.toString().toByteArray())).also {
                while (handles.size >= 8) {
                    val protected = listOfNotNull(currentUri, nextUri).mapNotNull { wanted ->
                        val id = digest(wanted.toString().toByteArray())
                        handles.values.lastOrNull { entry -> entry.sourceId == id }?.key
                    }
                    handles.remove(handles.keys.firstOrNull { old -> old !in protected } ?: handles.keys.first())
                }
                handles[key] = it
            }
        }
        readCache(handle, firstFrame, length)?.let {
            handle.points = it; handle.status = "cached"; handle.reason = "cache_hit"; return handle
        }
        synchronized(lock) {
            if (closed || (windowSet && uri != currentUri && uri != nextUri)) {
                handle.status = "ordinary seeking"
                handle.reason = if (closed) "manager_closed" else "outside_playback_window"
                return handle
            }
            // Retain requests independently of the single worker's capacity. Production
            // interest is current + next; eight entries bound standalone/test consumers.
            requests.values.filter { it.uri == uri && it.handle.key != key }.toList().forEach { cancel(it, "source_version_changed") }
            while (requests.size >= 8) {
                val victim = requests.values.firstOrNull { it !== active } ?: break
                cancel(victim, "superseded_request")
            }
            requests[key] = Request(handle, uri, firstFrame, length, modified)
            prioritizeCurrent()
        }
        wake.trySend(Unit)
        return handle
    }

    private suspend fun runRequest(request: Request) {
        val handle = request.handle
        var started = 0L
        try {
            delay(1000)
            handle.status = "building"; handle.reason = "waiting_for_file_operations"
            val scanContext = coroutineContext
            var fallback = "incomplete_index"
            val points = fileOperations.withLock {
                started = SystemClock.elapsedRealtime(); handle.reason = "reading_frames"
                scan(request.uri, { scanContext.ensureActive() }) { fallback = it }
            }
            coroutineContext.ensureActive()
            if (points == null) {
                handle.status = "ordinary seeking"; handle.reason = fallback
            } else if (points.first().position != request.firstFrame || modified(request.uri) != request.modified) {
                handle.status = "ordinary seeking"; handle.reason = "source_changed"
            } else {
                check(points.all { it.position in request.firstFrame until request.length })
                handle.points = points; handle.status = "ready"; handle.reason = "completed"
                writeCache(handle, points)
            }
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                // A yielded lookahead may already have a new queued request using this
                // handle. The old job must not mark that replacement cancelled.
                if (requests[handle.key] === request || !requests.containsKey(handle.key)) {
                    if (handle.points == null) {
                        handle.status = "cancelled"
                        if (handle.reason !in setOf("playback_window_changed", "source_version_changed", "superseded_request", "manager_closed")) handle.reason = "cancelled"
                    }
                }
            }
            throw cancelled
        } catch (cause: IOException) {
            handle.status = if (handle.points == null) "ordinary seeking" else "ready"
            handle.reason = if (handle.points == null) "read_failed:${cause.javaClass.simpleName}" else "memory_only_cache_write_failed"
        } catch (_: SecurityException) {
            handle.status = "ordinary seeking"; handle.reason = "permission_denied"
        } catch (_: IllegalArgumentException) {
            handle.status = "ordinary seeking"; handle.reason = "invalid_format"
        } catch (_: IllegalStateException) {
            handle.status = "ordinary seeking"; handle.reason = "invalid_frames_or_cache_write"
        } finally {
            if (started > 0) handle.elapsedMs = SystemClock.elapsedRealtime() - started
        }
    }

    /** Synchronous worker entry for deterministic generated-fixture tests. */
    fun build(uri: Uri): List<Point>? = scan(uri, { if (Thread.currentThread().isInterrupted) throw CancellationException() })

    private fun scan(uri: Uri, active: () -> Unit, fallback: (String) -> Unit = {}): List<Point>? {
        if (uri.scheme !in setOf("content", "file")) { fallback("unsupported_scheme"); return null }
        val source = DefaultDataSource.Factory(context).createDataSource()
        val extractor = FlacExtractor(FlacExtractor.FLAG_DISABLE_ID3_METADATA)
        val points = ArrayList<Point>()
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var input: DefaultExtractorInput? = null
        var position = 0L
        var nextTime = 0L
        var lastTime = -1L
        val discard = DummyTrackOutput()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by discard {
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, crypto: TrackOutput.CryptoData?) {
                    check(size > 0 && offset == 0 && timeUs > lastTime)
                    if (timeUs >= nextTime) {
                        check(points.size < MAX_POINTS)
                        points += Point(timeUs, position); nextTime = timeUs + 1_000_000
                    }
                    lastTime = timeUs; position += size
                }
            }
            override fun endTracks() = Unit
            override fun seekMap(map: SeekMap) { position = requireNotNull(input).position }
        })
        try {
            val length = source.open(DataSpec(uri))
            if (length !in 1..MAX_SOURCE_BYTES) { fallback("unknown_or_oversized_length"); return null }
            input = DefaultExtractorInput(source, 0, length)
            val holder = PositionHolder()
            while (true) {
                active()
                if (SystemClock.elapsedRealtime() > deadline) { fallback("scan_time_limit"); return null }
                when (extractor.read(requireNotNull(input), holder)) {
                    Extractor.RESULT_END_OF_INPUT -> break
                    Extractor.RESULT_SEEK -> { fallback("unexpected_scan_seek"); return null }
                }
            }
            return points.takeIf { it.isNotEmpty() && it.first().timeUs == 0L && position == length }
                .also { if (it == null) fallback("incomplete_or_trailing_data") }
        } finally { source.close(); extractor.release() }
    }

    private fun modified(uri: Uri): Long? = try {
        if (uri.scheme == "file") File(requireNotNull(uri.path)).lastModified().takeIf { it > 0 }
        else context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val doc = cursor.getColumnIndex("last_modified")
                val media = cursor.getColumnIndex("date_modified")
                when {
                    doc >= 0 && !cursor.isNull(doc) -> cursor.getLong(doc)
                    media >= 0 && !cursor.isNull(media) -> cursor.getLong(media) * 1000
                    else -> null
                }
            }
        }
    } catch (_: Exception) { null }

    private fun readCache(handle: Handle, first: Long, length: Long): List<Point>? {
        if (!handle.persistent) return null
        val file = File(directory, handle.key)
        return try {
            if (file.length() !in 40..MAX_CACHE_ENTRY_BYTES) return null
            val bytes = file.readBytes()
            val payload = bytes.copyOfRange(32, bytes.size)
            if (!MessageDigest.isEqual(bytes.copyOf(32), MessageDigest.getInstance("SHA-256").digest(payload))) return null
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readInt() != 1 || input.readUTF() != handle.key) return null
                val count = input.readInt()
                if (count !in 1..MAX_POINTS) return null
                val points = List(count) { Point(input.readLong(), input.readLong()) }
                if (input.available() != 0 || points.first() != Point(0, first) ||
                    points.any { it.timeUs < 0 || it.position !in first until length } ||
                    points.zipWithNext().any { (a, b) -> a.timeUs >= b.timeUs || a.position >= b.position }) return null
                file.setLastModified(System.currentTimeMillis())
                points
            }
        } catch (_: IOException) { null } catch (_: SecurityException) { null }
    }

    private fun writeCache(handle: Handle, points: List<Point>) {
        if (!handle.persistent || closed) return
        directory.mkdirs()
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(1); out.writeUTF(handle.key); out.writeInt(points.size)
                points.forEach { out.writeLong(it.timeUs); out.writeLong(it.position) }
            }
        }.toByteArray()
        val file = File(directory, handle.key)
        val temporary = File.createTempFile("index-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { out ->
                out.write(MessageDigest.getInstance("SHA-256").digest(payload)); out.write(payload); out.fd.sync()
            }
            if (!closed) check(temporary.renameTo(file))
            // Include abandoned temporary files in the budget as well as completed indexes.
            val files = directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
            var bytes = 0L
            files.forEachIndexed { index, cached ->
                bytes += cached.length()
                if (index >= 128 || bytes > 8 * 1024 * 1024) cached.delete()
            }
        } finally { temporary.delete() }
    }

    fun invalidate(handle: Handle) {
        handle.points = null; handle.status = "invalidated"; handle.reason = "frame_location_invalid"
        File(directory, handle.key).delete()
    }
    fun sourceStatus(uri: Uri?): JSONObject = synchronized(lock) {
        val sourceId = uri?.let { digest(it.toString().toByteArray()) }
        handles.values.lastOrNull { it.sourceId == sourceId }?.json()
            ?: JSONObject().put("sourceId", sourceId ?: JSONObject.NULL).put("status", "not_indexed").put("reason", "not_registered_or_embedded_table")
    }
    fun noteSeek(uri: Uri, strategy: String, targetUs: Long) = synchronized(lock) {
        val sourceId = digest(uri.toString().toByteArray())
        handles.values.lastOrNull { it.sourceId == sourceId }?.let { it.seekStrategy = strategy; it.seekTargetUs = targetUs }
        Unit
    }
    fun status(uri: Uri? = null): JSONObject = synchronized(lock) {
        val running = active
        JSONObject().put("policy", "Private FLAC indexes; original files unchanged")
            .put("jobs", requests.size + if (running != null && requests[running.handle.key] !== running) 1 else 0)
            .put("current", sourceStatus(uri ?: currentUri))
            .put("indexes", org.json.JSONArray(handles.values.map { it.json() }))
    }
    fun close() {
        synchronized(lock) {
            closed = true
            requests.values.toList().forEach { cancel(it, "manager_closed") }
            active?.let { if (it.handle.status in setOf("queued", "building")) { it.handle.reason = "manager_closed"; it.job?.cancel() } }
            active = null
        }
        scope.cancel(); wake.close()
    }

    companion object {
        private const val MAX_POINTS = 20_000
        private const val MAX_SOURCE_BYTES = 512L * 1024 * 1024
        private const val MAX_CACHE_ENTRY_BYTES = 400_128L
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
