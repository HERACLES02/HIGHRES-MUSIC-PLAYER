package com.localmusic.player

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import com.localmusic.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Portable app data only. No operation writes to a music source URI. */
class AppBackups(private val context: Context, private val store: MusicStore) {
    private val pending = AtomicFile(File(context.filesDir, "backup-restore-pending"))
    private fun json(p:BackupPreferences)=JSONObject(PreferenceCodec.encode(p))
    private fun prefs(p:JSONObject)=PreferenceCodec.decode(p.toString())
    suspend fun recoverPreferences() = withContext(Dispatchers.IO + NonCancellable) {
        val record = try { pending.openRead().bufferedReader().use { JSONObject(it.readText()) } }
            catch (_: java.io.FileNotFoundException) { return@withContext }
        val committed = store.repository().hasRestoreReceipt(record.getString("receipt"))
        store.restorePreferences(prefs(record.getJSONObject(if (committed) "target" else "original")))
        pending.delete()
    }
    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val state = store.repository().captureBackup(store.backupPreferences())
        val captured = BackupArtwork.capture(state, context.filesDir)
        val bundle = captured.bundle
        val bytes = ByteArrayOutputStream().also { BackupArchive.write(bundle, it) }.toByteArray()
        BackupArchive.read(bytes.inputStream())
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Cannot open backup destination." }.use { it.write(bytes) }
        val saved = requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot verify saved backup." }.use { BackupArchive.read(it) }
        check(saved.state == bundle.state && saved.assets.keys == bundle.assets.keys) { "Saved backup verification failed." }
        captured.missingFiles
    }
    suspend fun preview(uri: Uri): BackupBundle = withContext(Dispatchers.IO) {
        val bundle = requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open backup." }.use { BackupArchive.read(it) }
        for (bytes in bundle.assets.values) validateImage(bytes)
        bundle
    }
    private fun validateImage(bytes: ByteArray) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth in 1..4096 && options.outHeight in 1..4096 && options.outMimeType in setOf("image/png", "image/jpeg", "image/webp")) { "Backup contains unsupported artwork." }
    }
    suspend fun restore(bundle: BackupBundle) = withContext(Dispatchers.IO + NonCancellable) {
        recoverPreferences()
        val receipt = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "restored-art/$receipt").apply { check(mkdirs()) }
        val paths = bundle.assets.mapValues { (name, bytes) ->
            validateImage(bytes)
            val file = File(directory, name.substringAfter('/'))
            java.io.FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
            file.absolutePath
        }
        val artists=bundle.state.preferences.artists.mapValues{(_,p)->p.copy(imagePath=p.imagePath?.let{paths.getValue(it)})}
        val original=store.backupPreferences()
        val target=PreferenceCodec.mergeRestore(original,bundle.state.preferences.copy(artists=artists))
        val record = JSONObject().put("receipt",receipt).put("original",json(original)).put("target",json(target))
        val output = pending.startWrite()
        try { output.write(record.toString().toByteArray(Charsets.UTF_8)); pending.finishWrite(output) }
        catch (e: Exception) { pending.failWrite(output); throw e }
        try {
            store.restorePreferences(target)
            store.repository().restoreBackup(bundle.state, paths, receipt)
        } finally {
            // A transaction receipt decides the outcome even if a cancellation or IO exception happened at commit.
            recoverPreferences()
        }
    }
}
