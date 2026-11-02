package com.localmusic.player

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import com.localmusic.core.*
import com.localmusic.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.UUID

/** Provider writes are deliberately non-atomic. Complete originals and durable journals are retained. */
class FileEdits(private val context: Context, private val store: MusicStore) {
    private val resolver get() = context.contentResolver
    private val root = File(context.filesDir, "edit-backups").apply { mkdirs() }
    private suspend fun repo() = store.repository()
    private fun path(value: String): File {
        val file = File(value).canonicalFile
        val parent = requireNotNull(file.parentFile)
        require(parent.parentFile == root.canonicalFile &&
            runCatching { UUID.fromString(parent.name) }.isSuccess &&
            (file.name.startsWith("original.") || file.name.startsWith("staged."))) { "Invalid edit backup path" }
        return file
    }
    suspend fun prepare(batch: String, ids: List<TrackId>, patch: TagPatch, coverMode: Int, coverPath: String): Unit = withContext(Dispatchers.IO) {
        require(ids.size in 1..50 && coverMode in 0..2)
        for (id in ids.distinct()) {
            currentCoroutineContext().ensureActive()
            val track = requireNotNull(repo().track(id))
            val source = track.sources.sortedByDescending { it.kind == SourceKind.DOCUMENT }.firstOrNull { source ->
                source.kind != SourceKind.DOCUMENT || resolver.persistedUriPermissions.any { p -> p.isReadPermission && store.state.value.folders.any { it.id == source.folderGrantId && it.treeUri == p.uri.toString() } }
            } ?: error("Song source unavailable")
            val uri = Uri.parse(source.uri)
            var name = track.tags.title
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
            val ext = name.substringAfterLast('.', "").lowercase()
            require(ext in setOf("mp3", "flac", "m4a", "ogg", "oga", "opus")) { "$name: embedded editing is not enabled for this container. Use app-only editing." }
            val editId = UUID.randomUUID().toString(); val directory = File(root, editId).apply { mkdirs() }
            val backup = File(directory, "original.$ext"); val staged = File(directory, "staged.$ext")
            val now = System.currentTimeMillis()
            val patchJson = JSONObject(patch.fields).put("_appBefore", JSONObject(repo().extra(id).encode())).toString()
            var row = FileEditRow(editId, batch, id.value.toString(), source.uri, name, "COPYING", backup.path, staged.path,
                "", "", "", patchJson, "{}", "{}", coverMode, now, now, "Preparing original backup")
            repo().saveEdit(row)
            try {
                check(StatFs(root.path).availableBytes > source.stamp.sizeBytes * 3 + 32L * 1024 * 1024) { "Insufficient space for an original backup and staged/verification copies" }
                resolver.openInputStream(uri)?.use { input -> backup.outputStream().use { out -> copy(input, out) } } ?: error("Cannot read original")
                FileOutputStream(backup, true).use { it.fd.sync() }
                syncDirectory(directory); syncDirectory(root)
                val original = sha(backup)
                val before = JSONObject(NativeTags.read(backup.path, ""))
                val payload = AudioPayloadDigest.hash(backup)
                backup.copyTo(staged)
                val nativeValues = patch.fields.map { (key, value) -> if (key == "ARTIST") patch.valuesFor(key).joinToString("\n") else value }
                NativeTags.edit(staged.path, patch.fields.keys.toTypedArray(), nativeValues.toTypedArray(), coverMode, coverPath, "image/jpeg")
                val after = JSONObject(NativeTags.read(staged.path, ""))
                fun retainedPictures(data: JSONObject, original: Boolean): List<String> {
                    val pictures = data.optJSONArray("pictureFingerprints") ?: org.json.JSONArray()
                    return (0 until pictures.length()).filter { index ->
                        if (coverMode == 0) true
                        else if (data.optBoolean("picturesHaveRoles", true)) pictures.getJSONObject(index).optString("type") != "Front Cover"
                        else !(index == 0 && (original || coverMode == 1))
                    }.map { pictures.getJSONObject(it).toString() }
                }
                check(retainedPictures(before, true) == retainedPictures(after, false)) { "Unrelated embedded artwork changed; original not touched" }
                check(AudioPayloadDigest.hash(staged) == payload) { "Encoded audio/configuration changed; original not touched" }
                for (key in listOf("durationMs", "sampleRateHz", "channels", "bits")) check(before.optLong(key) == after.optLong(key)) { "Audio properties changed" }
                val old = before.getJSONObject("properties"); val next = after.getJSONObject("properties")
                for (key in old.keys()) if (key !in patch.fields) check(old.getJSONArray(key).toString() == next.optJSONArray(key)?.toString()) { "Unrelated $key changed; original not touched" }
                for ((key, value) in patch.fields) {
                    val wanted = patch.valuesFor(key)
                    val actual = next.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                    check(actual == wanted) { "Staged $key did not match the preview" }
                }
                row = row.copy(phase = "PREPARED", originalSha = original, stagedSha = sha(staged), payloadSha = payload,
                    beforeJson = before.toString(), afterJson = after.toString(), result = "Preview verified; music file has not been changed")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { row = row.copy(phase = "FAILED_PREVIEW", result = e.message ?: "Preparation failed") }
            repo().saveEdit(row.copy(updatedAtMs = System.currentTimeMillis()))
        }
        store.refresh()
    }
    suspend fun commit(ids: List<String>) = withContext(Dispatchers.IO) {
        for (id in ids) {
            val row = requireNotNull(repo().edit(id)); require(row.phase == "PREPARED")
            withContext(NonCancellable) {
                try { write(row, row.originalSha, path(row.stagedPath), row.stagedSha); applyMetadata(row, false) }
                catch (e: Exception) { store.error("${row.displayName}: ${e.message}. Original backup retained.") }
            }
        }
        store.refresh(); store.requestScan()
    }
    private suspend fun write(row: FileEditRow, expected: String, staged: File, stagedHash: String, undo: Boolean = false) {
        val uri = Uri.parse(row.sourceUri)
        val original = path(row.backupPath)
        val proofs = object : EditProofs {
            override fun backupHash() = sha(original)
            override fun stagedHash() = sha(staged)
            override fun openTarget(): GuardedWriteTarget {
                val pfd = requireNotNull(resolver.openFileDescriptor(uri, "rw")) { "Provider does not support read/write access" }
                try { Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_SET) }
                catch (e: Exception) { pfd.close(); throw IOException("Provider is not seekable; no overwrite attempted", e) }
                try {
                    val stat = Os.fstatvfs(pfd.fileDescriptor)
                    val growth = (staged.length() - Os.fstat(pfd.fileDescriptor).st_size).coerceAtLeast(0)
                    check(stat.f_bavail * stat.f_frsize > growth + 1024 * 1024) { "Insufficient space on the destination volume" }
                } catch (e: Exception) { pfd.close(); throw IOException("Destination space could not be verified; no overwrite attempted", e) }
                return object : GuardedWriteTarget {
                    override fun hash(): String {
                        Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_SET)
                        return ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(pfd.fileDescriptor)).use(::sha)
                    }
                    override fun overwrite(staged: File) {
                        Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_SET)
                        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
                            output.channel.truncate(0)
                            staged.inputStream().use { input -> copy(input, output) }
                            output.flush(); output.fd.sync()
                        }
                    }
                    override fun close() { pfd.close() }
                }
            }
            override fun reopenHash(): String = requireNotNull(resolver.openInputStream(uri)).use(::sha)
            override suspend fun journal(phase: String, message: String) {
                val savedPhase = if (undo) when (phase) { "WRITING" -> "UNDO_WRITING"; "VERIFIED" -> "UNDONE"; "PREPARED" -> row.phase; else -> phase } else phase
                repo().saveEdit(row.copy(phase = savedPhase, result = message, updatedAtMs = System.currentTimeMillis()))
                if (phase == "NEEDS_RECOVERY") repo().markUnavailable(PlaybackAuthority.id(row.trackId), Availability.CORRUPT)
                if (phase == "VERIFIED") repo().markUnavailable(PlaybackAuthority.id(row.trackId), Availability.AVAILABLE)
            }
        }
        // Undo still verifies the retained backup separately before using the generic source guard.
        val adapter = object : EditProofs by proofs { override fun backupHash() = if (sha(original) == row.originalSha) expected else "invalid" }
        VerifiedWrite.commit(expected, stagedHash, staged, adapter)
    }
    suspend fun undo(id: String) = withContext(Dispatchers.IO) {
        val row = requireNotNull(repo().edit(id)); require(row.phase == "VERIFIED") { "Only verified edits can be undone." }
        write(row, row.stagedSha, path(row.backupPath), row.originalSha, undo = true)
        applyMetadata(row, true)
        repo().saveEdit(row.copy(phase = "UNDONE", result = "Original restored and reopened; no newer file change was overwritten", updatedAtMs = System.currentTimeMillis()))
        store.refresh(); store.requestScan()
    }
    suspend fun discard(ids: List<String>) {
        for (id in ids) {
            val row = repo().edit(id) ?: continue
            if (row.phase == "PREPARED") repo().saveEdit(row.copy(phase = "DISCARDED", result = "Preview canceled; source unchanged", updatedAtMs = System.currentTimeMillis()))
        }
        store.refresh()
    }
    suspend fun inspectRecovery() = withContext(Dispatchers.IO) {
        for (row in repo().edits()) if (row.phase in setOf("COPYING", "WRITING", "UNDO_WRITING", "NEEDS_RECOVERY")) {
            val phase = try {
                val current = resolver.openInputStream(Uri.parse(row.sourceUri))?.use(::sha)
                when { row.originalSha.isNotBlank() && current == row.originalSha -> if (row.phase == "UNDO_WRITING") "UNDONE" else "ABORTED"
                    row.stagedSha.isNotBlank() && current == row.stagedSha -> "VERIFIED"
                    row.phase == "COPYING" -> "FAILED_PREVIEW"
                    else -> "NEEDS_RECOVERY" }
            } catch (_: Exception) { if (row.phase == "COPYING") "FAILED_PREVIEW" else "NEEDS_RECOVERY" }
            repo().saveEdit(row.copy(phase = phase, result = "Recovered after interrupted operation: $phase", updatedAtMs = System.currentTimeMillis()))
            if (phase == "NEEDS_RECOVERY") repo().markUnavailable(PlaybackAuthority.id(row.trackId), Availability.CORRUPT)
        }
        val now = System.currentTimeMillis()
        for (row in repo().edits()) if (row.phase in setOf("VERIFIED", "UNDONE", "DISCARDED", "FAILED_PREVIEW", "ABORTED") &&
            now - row.updatedAtMs > 7L * 24 * 60 * 60 * 1000) {
            val backup = path(row.backupPath); val stage = path(row.stagedPath)
            val a = !backup.exists() || backup.delete(); val b = !stage.exists() || stage.delete()
            if (a && b) { backup.parentFile?.delete(); repo().saveEdit(row.copy(phase = "BACKUP_EXPIRED", result = "Completed operation's seven-day backup retention elapsed")) }
        }
    }
    suspend fun recoveryHash(id: String): String = withContext(Dispatchers.IO) {
        val row = requireNotNull(repo().edit(id)); require(row.phase == "NEEDS_RECOVERY")
        require(sha(path(row.backupPath)) == row.originalSha) { "Original backup failed verification" }
        requireNotNull(resolver.openInputStream(Uri.parse(row.sourceUri))).use(::sha)
    }
    suspend fun recover(id: String, expectedCurrent: String) = withContext(Dispatchers.IO) {
        val row = requireNotNull(repo().edit(id)); require(row.phase == "NEEDS_RECOVERY")
        write(row, expectedCurrent, path(row.backupPath), row.originalSha, undo = true)
        applyMetadata(row, true)
        store.refresh(); store.requestScan()
    }
    private suspend fun applyMetadata(row: FileEditRow, undo: Boolean) {
        val id = PlaybackAuthority.id(row.trackId); val track = repo().track(id) ?: return
        val file = path(if (undo) row.backupPath else row.stagedPath)
        val art = File.createTempFile("edited-art-", ".tmp", context.cacheDir)
        try {
            val metadata = JSONObject(NativeTags.read(file.path, art.path))
            val tags = NativeMetadata.tags(metadata, track.tags.copy(title = row.displayName.substringBeforeLast('.')))
            repo().saveTrack(track.copy(tags = tags))
            val cover = if (art.length() > 0) LocalArtwork(context).resize(art, false) else null
            val patch = JSONObject(row.patch)
            val before = patch.optJSONObject("_appBefore")?.let { LibraryExtras.decode(it.toString()) } ?: LibraryExtras()
            var appliedRevision = -1L
            repo().updateExtra(id) { old ->
                val editedKeys = patch.keys().asSequence().filter { it in TagPatch.KEYS }.toSet()
                val current = old.copy(artworkPath = cover, artworkStamp = null)
                if (!undo && old.overridesVersion == before.overridesVersion) {
                    appliedRevision = old.overridesVersion + 1
                    current.copy(tagFields = old.tagFields - editedKeys, artworkOverride = if (row.coverMode == 0) old.artworkOverride else null, overridesVersion = appliedRevision)
                } else if (undo && patch.optLong("_appAfterRevision", -1) >= 0 && old.overridesVersion == patch.optLong("_appAfterRevision")) {
                    current.copy(tagFields = before.tagFields, tagsOverride = before.tagsOverride,
                        artworkOverride = before.artworkOverride, overridesVersion = old.overridesVersion + 1)
                } else current
            }
            val saved = repo().edit(row.id)
            if (!undo && saved != null) repo().saveEdit(saved.copy(patch = patch.put("_appAfterRevision", appliedRevision).toString()))
        } finally { art.delete() }
    }
    companion object {
        private fun syncDirectory(directory: File) {
            check(directory.isDirectory)
            val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        }
        fun sha(file: File): String = file.inputStream().use(::sha)
        fun sha(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        private fun copy(input: InputStream, output: OutputStream) {
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
        }
    }
}
