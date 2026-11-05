@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.*
import androidx.media3.extractor.flac.FlacExtractor
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

/** Generated audio only: compare indexed locations with independently extracted frames. */
object FlacIndexChecks {
    data class Packet(val timeUs: Long, val position: Long, val size: Int, val hash: String)

    /** Reproduce selecting a third song while the restored track/lookahead occupy both slots. */
    fun queueRegression(context: Context, fixture: String): String {
        val directory = File(context.cacheDir, "index-queue-${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
        val files = (0..2).map { File(directory, "generated-$it.flac") }
        val edits = Mutex(locked = true)
        val indexes = FlacSeekIndexes(context, edits)
        try {
            val source = DefaultDataSource.Factory(context).createDataSource()
            try {
                source.open(DataSpec(Uri.parse(fixture)))
                files.first().outputStream().use { out ->
                    val buffer = ByteArray(65536)
                    while (true) { val n = source.read(buffer, 0, buffer.size); if (n < 0) break; out.write(buffer, 0, n) }
                }
            } finally { source.close() }
            files.first().copyTo(files[1]); files.first().copyTo(files[2])
            var firstFrame = 4L
            val info = ByteArray(42)
            java.io.RandomAccessFile(files.first(), "r").use { input ->
                input.readFully(info)
                while (true) {
                    input.seek(firstFrame)
                    val type = input.readUnsignedByte()
                    val size = (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
                    firstFrame += 4 + size
                    if (type and 128 != 0) break
                }
            }
            val prepared = files.map { indexes.prepare(Uri.fromFile(it), firstFrame, it.length(), info) }
            val selected = prepared.last()
            val admitted = indexes.status().toString()
            edits.unlock()
            val deadline = SystemClock.elapsedRealtime() + 40_000
            while (indexes.status().getInt("jobs") > 0) {
                check(SystemClock.elapsedRealtime() < deadline) { "Earlier index jobs failed to finish" }
                SystemClock.sleep(20)
            }
            check(prepared.take(2).all { it.points != null }) { "Expected the first two generated-file jobs to complete: ${indexes.status()}" }
            check(selected.points != null) {
                "Selected song permanently lost its index request when two slots were occupied. " +
                    "At admission: $admitted; after earlier jobs completed: ${indexes.status()}"
            }
            prepared.forEach { File(context.cacheDir, "flac-seek-v1/${it.key}").delete() }
            indexes.close()
            val priorities = FlacSeekIndexes(context, edits)
            try {
                edits.lockForTest()
                val currentUri = Uri.fromFile(files[2]); val lookaheadUri = Uri.fromFile(files[1])
                priorities.setPlaybackWindow(currentUri, lookaheadUri)
                val ahead = priorities.prepare(lookaheadUri, firstFrame, files[1].length(), info)
                val waitingUntil = SystemClock.elapsedRealtime() + 5000
                while (ahead.status != "building") {
                    check(SystemClock.elapsedRealtime() < waitingUntil); SystemClock.sleep(10)
                }
                val now = priorities.prepare(currentUri, firstFrame, files[2].length(), info)
                check(priorities.prepare(currentUri, firstFrame, files[2].length(), info) === now) { "Duplicate index request was not shared" }
                edits.unlock()
                val currentUntil = SystemClock.elapsedRealtime() + 20_000
                while (now.points == null) {
                    check(SystemClock.elapsedRealtime() < currentUntil) { "Current song was not prioritized: ${priorities.status(currentUri)}" }
                    SystemClock.sleep(10)
                }
                check(ahead.points == null) { "Lookahead work delayed the current song" }
                while (priorities.status().getInt("jobs") > 0) {
                    check(SystemClock.elapsedRealtime() < currentUntil); SystemClock.sleep(10)
                }
                check(ahead.points != null) { "Yielding to current song lost the lookahead request" }
                edits.lockForTest()
                files.forEach { check(it.setLastModified(it.lastModified() + 5000)) }
                var latest: FlacSeekIndexes.Handle? = null
                repeat(12) { index ->
                    val chosen = files[index % files.size]
                    val chosenUri = Uri.fromFile(chosen)
                    priorities.setPlaybackWindow(chosenUri)
                    latest = priorities.prepare(chosenUri, firstFrame, chosen.length(), info)
                    check(priorities.status().getInt("jobs") <= 3) { "Rapid selections exceeded bounded work" }
                }
                edits.unlock()
                val rapidUntil = SystemClock.elapsedRealtime() + 20_000
                while (latest?.points == null || priorities.status().getInt("jobs") != 0) {
                    check(SystemClock.elapsedRealtime() < rapidUntil) { "Latest rapid selection lost: ${priorities.status()}" }
                    SystemClock.sleep(10)
                }
                val missing = Uri.fromFile(File(directory, "missing.flac"))
                priorities.setPlaybackWindow(missing)
                val failed = priorities.prepare(missing, firstFrame, files[0].length(), info)
                val failUntil = SystemClock.elapsedRealtime() + 5000
                while (priorities.status().getInt("jobs") > 0) {
                    check(SystemClock.elapsedRealtime() < failUntil); SystemClock.sleep(10)
                }
                val report = priorities.status(missing).getJSONObject("current")
                check(report.getString("sourceId") == failed.sourceId && report.getString("reason").startsWith("read_failed:")) {
                    "Report did not identify the current source's actual failure: $report"
                }
            } finally { priorities.close(); if (edits.isLocked) edits.unlock() }
            return "PASS: occupied worker retains requests; current song preempts lookahead; duplicates share work; obsolete/rapid selections bounded; per-source failure reason recorded"
        } finally {
            indexes.close()
            if (edits.isLocked) edits.unlock()
            files.forEach { it.delete() }; directory.delete()
        }
    }

    private fun Mutex.lockForTest() = runBlocking { lock() }

    fun packets(context: Context, uri: Uri): List<Packet> {
        val source = DefaultDataSource.Factory(context).createDataSource()
        val extractor = FlacExtractor()
        val packets = mutableListOf<Packet>()
        val buffer = ByteArrayOutputStream()
        var framePosition = 0L
        var input: DefaultExtractorInput? = null
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
                override fun format(format: Format) = Unit
                override fun sampleData(reader: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean, part: Int): Int =
                    error("Unexpected direct sample read")
                override fun sampleData(data: ParsableByteArray, length: Int, part: Int) {
                    val bytes = ByteArray(length); data.readBytes(bytes, 0, length); buffer.write(bytes)
                }
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, crypto: TrackOutput.CryptoData?) {
                    val bytes = buffer.toByteArray(); check(bytes.size == size && offset == 0)
                    packets += Packet(timeUs, framePosition, size, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                    framePosition += size; buffer.reset()
                }
            }
            override fun endTracks() = Unit
            override fun seekMap(map: SeekMap) { framePosition = requireNotNull(input).position }
        })
        try {
            val length = source.open(DataSpec(uri)); input = DefaultExtractorInput(source, 0, length)
            val holder = PositionHolder()
            while (extractor.read(requireNotNull(input), holder) != Extractor.RESULT_END_OF_INPUT) {
                check(holder.position == 0L) { "Unexpected seek in sequential extraction" }
            }
            return packets
        } finally { source.close(); extractor.release() }
    }

    fun run(context: Context, fixture: String): String {
        val uri = Uri.parse(fixture)
        val expected = packets(context, uri)
        check(expected.size > 100 && expected.last().timeUs > 150_000_000) { "Use a generated three-minute FLAC" }
        val worker = FlacSeekIndexes(context)
        val built = try { worker.build(uri) } finally { worker.close() }
        check(!built.isNullOrEmpty()) { "Index-free FLAC did not get a private seek index" }
        for (point in built) {
            check(expected.any { it.timeUs == point.timeUs && it.position == point.position }) {
                "Index position is not an actual FLAC frame: $point"
            }
        }
        check(built.size in 100..200 && built.first().timeUs == 0L)
        return "Private index: ${built.size} verified frame positions; ${expected.size} reference packets\n" + integration(context, uri, expected)
    }

    private fun integration(context: Context, fixture: Uri, expected: List<Packet>): String {
        // All mutations are on this disposable generated fixture, never the supplied song.
        val file = File(context.cacheDir, "seek-index-generated.flac")
        val source = DefaultDataSource.Factory(context).createDataSource()
        try {
            source.open(DataSpec(fixture))
            file.outputStream().use { out ->
                val buffer = ByteArray(65536)
                while (true) { val count = source.read(buffer, 0, buffer.size); if (count < 0) break; out.write(buffer, 0, count) }
            }
        } finally { source.close() }
        val originalHash = file.readBytes().let { MessageDigest.getInstance("SHA-256").digest(it) }
        val uri = Uri.fromFile(file)
        val indexes = FlacSeekIndexes(context)
        val data = DefaultDataSource.Factory(context).createDataSource()
        val extractor = LocalFlacExtractors(indexes).createExtractors(uri, emptyMap()).first { candidate ->
            val sniffSource = DefaultDataSource.Factory(context).createDataSource()
            try {
                val size = sniffSource.open(DataSpec(uri))
                candidate.sniff(DefaultExtractorInput(sniffSource, 0, size))
            } finally { sniffSource.close() }
        }
        var map: SeekMap? = null
        val actual = mutableListOf<Pair<Long, String>>()
        val bytes = ByteArrayOutputStream()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
                override fun format(format: Format) = Unit
                override fun sampleData(reader: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean, part: Int): Int = error("Direct sample read")
                override fun sampleData(buffer: ParsableByteArray, length: Int, part: Int) {
                    val chunk = ByteArray(length); buffer.readBytes(chunk, 0, length); bytes.write(chunk)
                }
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, crypto: TrackOutput.CryptoData?) {
                    check(bytes.size() == size)
                    actual += timeUs to MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
                    bytes.reset()
                }
            }
            override fun endTracks() = Unit
            override fun seekMap(value: SeekMap) { map = value }
        })
        var input = DefaultExtractorInput(data, 0, data.open(DataSpec(uri)))
        val holder = PositionHolder()
        fun read() {
            val result = extractor.read(input, holder)
            if (result == Extractor.RESULT_SEEK) {
                data.close(); val remaining = data.open(DataSpec.Builder().setUri(uri).setPosition(holder.position).build())
                input = DefaultExtractorInput(data, holder.position, holder.position + remaining)
            }
            check(result != Extractor.RESULT_END_OF_INPUT)
        }
        fun seekAndCompare(target: Long, indexed: Boolean) {
            val point = requireNotNull(map).getSeekPoints(target).first
            if (indexed) check(expected.any { it.position == point.position && it.timeUs == point.timeUs }) { "Seek map still estimates the frame location" }
            actual.clear(); bytes.reset(); data.close()
            val remaining = data.open(DataSpec.Builder().setUri(uri).setPosition(point.position).build())
            input = DefaultExtractorInput(data, point.position, point.position + remaining)
            extractor.seek(point.position, target)
            var iterations = 0
            while (actual.size < 3) { check(++iterations < 2000); read() }
            for ((time, hash) in actual) check(expected.any { it.timeUs == time && it.hash == hash }) { "Seek changed frame content/timestamp at $time" }
            check(actual.first().first <= target && target - actual.first().first < 1_100_000)
        }
        try {
            while (map == null) read()
            // Ordinary seeking remains correct before the delayed private index exists.
            seekAndCompare(83_000_000, false)
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (indexes.status().toString().let { !it.contains("ready") && !it.contains("cached") }) {
                check(SystemClock.elapsedRealtime() < deadline) { "Index did not finish: ${indexes.status()}" }
                SystemClock.sleep(20)
            }
            for (target in listOf(147_000_000L, 17_000_000L, 83_456_789L, 0L)) seekAndCompare(target, true)
            check(MessageDigest.isEqual(originalHash, MessageDigest.getInstance("SHA-256").digest(file.readBytes()))) { "Indexing changed source bytes" }
            val first = expected.first().position
            val info = ByteArray(42).also { header -> java.io.DataInputStream(file.inputStream()).use { it.readFully(header) } }
            val cached = indexes.prepare(uri, first, file.length(), info)
            check(cached.points != null)
            val cacheFile = File(context.cacheDir, "flac-seek-v1/${cached.key}")
            val cacheDeadline = SystemClock.elapsedRealtime() + 5000
            while (!cacheFile.exists()) { check(SystemClock.elapsedRealtime() < cacheDeadline); SystemClock.sleep(10) }
            FlacSeekIndexes(context).let { reopened ->
                try { check(reopened.prepare(uri, first, file.length(), info).status == "cached") { "Index did not survive reopening" } }
                finally { reopened.close() }
            }
            cacheFile.writeBytes(byteArrayOf(1, 2, 3))
            FlacSeekIndexes(context).let { corrupted ->
                try { check(corrupted.prepare(uri, first, file.length(), info).points == null) { "Corrupt cache accepted" } }
                finally { corrupted.close() }
            }
            check(file.setLastModified(file.lastModified() + 5000))
            check(indexes.prepare(uri, first, file.length(), info).key != cached.key) { "File change reused old index" }
            // A real metadata insertion moves every audio frame while retaining all audio.
            val original = file.readBytes()
            var end = 4; var lastHeader: Int
            while (true) {
                lastHeader = end
                val size = ((original[end + 1].toInt() and 255) shl 16) or
                    ((original[end + 2].toInt() and 255) shl 8) or (original[end + 3].toInt() and 255)
                end += 4 + size
                if (original[lastHeader].toInt() and 128 != 0) break
            }
            val header = original.copyOfRange(0, end)
            header[lastHeader] = (header[lastHeader].toInt() and 127).toByte()
            file.outputStream().use { out ->
                out.write(header); out.write(byteArrayOf(0x81.toByte(), 0, 0, 64)); out.write(ByteArray(64))
                out.write(original, end, original.size - end)
            }
            val shifted = indexes.prepare(uri, first + 68, file.length(), info)
            check(shifted.key != cached.key && shifted.points == null) { "Changed FLAC reused original locations" }
            indexes.close()
            SystemClock.sleep(1100)
            check(shifted.points == null && indexes.status().getInt("jobs") == 0) { "Cancelled indexing published results" }
            val edits = Mutex(locked = true)
            val coordinated = FlacSeekIndexes(context, edits)
            try {
                val held = coordinated.prepare(uri, first + 68, file.length(), info)
                // The indexer must not read while a file edit owns the shared operation lock.
                runBlocking { delay(3000) }
                check(held.points == null) { "Indexing read a file while its edit lock was held" }
                edits.unlock()
                val deadline = SystemClock.elapsedRealtime() + 20_000
                while (held.points == null) {
                    check(SystemClock.elapsedRealtime() < deadline) { "Index did not resume after the edit lock released" }
                    SystemClock.sleep(20)
                }
            } finally { if (edits.isLocked) edits.unlock(); coordinated.close() }
            return "PASS: fallback and live indexed seeks preserve exact frame payloads/timestamps; source unchanged; cache reopen, corruption, modification invalidation, cancellation and file-edit locking"
        } finally { data.close(); extractor.release(); indexes.close(); file.delete() }
    }

    fun withContentFixture(context: Context, fixture: String, block: (String) -> String): String {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, "local-music-generated-seek.flac")
            put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/flac")
            put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Music/LocalMusicSeekRegression")
            put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
        try {
            val source = DefaultDataSource.Factory(context).createDataSource()
            try {
                source.open(DataSpec(Uri.parse(fixture)))
                requireNotNull(resolver.openOutputStream(uri)).use { out ->
                    val buffer = ByteArray(65536)
                    while (true) { val count = source.read(buffer, 0, buffer.size); if (count < 0) break; out.write(buffer, 0, count) }
                }
            } finally { source.close() }
            resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            return block(uri.toString())
        } finally { resolver.delete(uri, null, null) }
    }
}
