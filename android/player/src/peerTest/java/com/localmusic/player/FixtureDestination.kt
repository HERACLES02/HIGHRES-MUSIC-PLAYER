package com.localmusic.player

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract as Docs
import android.system.Os
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray

private const val STORAGE_PROVIDER = "com.android.externalstorage.documents"
private const val FINISHED_NAME = "Local Music peer fixture.flac"

internal class FixtureDestination(
    private val resolver: ContentResolver, val tree: Uri, private val finishedName: String = FINISHED_NAME,
    private val folderId: String = Docs.getTreeDocumentId(tree),
) {
    init {
        require(tree.authority == STORAGE_PROVIDER && Docs.isTreeUri(tree) &&
            Docs.getTreeDocumentId(tree).substringAfter(':', "").isNotEmpty()) {
            "Choose a disposable folder under Android internal or SD card storage."
        }
    }

    private val root = Docs.buildDocumentUriUsingTree(tree, folderId)

    fun requireWritable() {
        check(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }) {
            "Choose the destination again to restore write access."
        }
    }

    private data class Document(val id: String, val name: String, val mime: String)

    private fun documents(): List<Document> {
        val children = Docs.buildChildDocumentsUriUsingTree(tree, folderId)
        val columns = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME, Docs.Document.COLUMN_MIME_TYPE)
        return requireNotNull(resolver.query(children, columns, null, null, null)) { "Could not read the destination." }.use { rows ->
            buildList {
                while (rows.moveToNext()) add(Document(rows.getString(0), rows.getString(1), rows.getString(2)))
            }
        }
    }

    private fun Document.uri() = Docs.buildDocumentUriUsingTree(tree, id)

    fun findFinished(): Uri? = documents().firstOrNull { it.name.equals(finishedName, ignoreCase = true) }?.uri()

    fun albumTrack(track: FixtureTrack): FixtureDestination {
        requireWritable()
        val folder = track.folder.substringAfterLast('\\').substringAfterLast('/')
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(JSONArray(listOf(track.user, track.folder)).toString().toByteArray()).take(6)
            .joinToString("") { "%02x".format(it) }
        val name = "${safeName(folder, "Album")} [$digest]"
        val album = documents().firstOrNull { it.name.equals(name, ignoreCase = true) }
        val id = if (album == null) {
            val created = checkNotNull(Docs.createDocument(resolver, root, Docs.Document.MIME_TYPE_DIR, name)) {
                "Could not create the album folder."
            }
            check(documentName(created) == name) { "Could not reserve the album folder name." }
            Docs.getDocumentId(created)
        } else {
            check(album.mime == Docs.Document.MIME_TYPE_DIR) { "A file occupies the album folder name." }
            album.id
        }
        val fileName = safeName(track.name.substringBeforeLast('.'), "Track") + "." +
            checkNotNull(track.format).name.lowercase()
        return FixtureDestination(resolver, tree, fileName, id)
    }

    fun publishAlbumTrack(staged: File, sha256: String, separate: Boolean): PublishedFixture? {
        val files = documents()
        val extension = finishedName.substringAfterLast('.')
        val existing = files.firstOrNull {
            it.mime != Docs.Document.MIME_TYPE_DIR && it.name.endsWith(".$extension", ignoreCase = true) &&
                matches(it.uri(), sha256)
        }
        if (existing != null) return PublishedFixture(existing.uri(), duplicate = true)
        val conflict = files.any { it.name.equals(finishedName, ignoreCase = true) }
        if (conflict && !separate) return null
        val name = if (conflict) {
            val base = finishedName.substringBeforeLast('.')
            generateSequence(2) { it + 1 }.map { "$base ($it).$extension" }
                .first { candidate -> files.none { it.name.equals(candidate, ignoreCase = true) } }
        } else finishedName
        return PublishedFixture(publishNew(staged, sha256, name), duplicate = false)
    }

    fun matches(uri: Uri, sha256: String): Boolean =
        resolver.openInputStream(uri)?.use { it.downloadDigest() == sha256 } == true

    fun publish(staged: File, sha256: String): Uri {
        requireWritable()
        findFinished()?.let {
            check(matches(it, sha256)) { "The destination already has a different file with that name. Choose another folder and retry." }
            return it
        }
        return publishNew(staged, sha256, finishedName)
    }

    private fun publishNew(staged: File, sha256: String, name: String): Uri {
        // The Android scanner ignores .part documents. Expose the audio extension only
        // after the local provider has written and verified every byte.
        val pending = checkNotNull(Docs.createDocument(resolver, root, "application/octet-stream", "peer-${UUID.randomUUID()}.part")) {
            "Could not create a staging document in the destination."
        }
        var renamed = false
        try {
            check(documentName(pending).endsWith(".part")) { "The destination changed the staging extension." }
            val descriptor = checkNotNull(resolver.openFileDescriptor(pending, "wt")) { "Could not open the staging document." }
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                val storage = Os.fstatvfs(output.fd)
                check(storage.f_bavail * storage.f_frsize >= staged.length()) {
                    "There isn’t enough free space in the download folder. Free space and tap Retry save."
                }
                staged.inputStream().use { it.copyTo(output) }
                output.fd.sync()
            }
            check(matches(pending, sha256)) { "The destination copy failed SHA-256 verification." }
            check(documents().none { it.name.equals(name, ignoreCase = true) }) {
                "A file appeared in the destination. Retry to check it."
            }
            val finished = checkNotNull(Docs.renameDocument(resolver, pending, name)) {
                "Could not finalize the fixture. Choose a writable folder and retry."
            }
            renamed = true
            check(documentName(finished) == name) {
                runCatching { Docs.deleteDocument(resolver, finished) }
                "The destination renamed the fixture because of a conflict. Choose another folder and retry."
            }
            return finished
        } finally {
            if (!renamed) runCatching { Docs.deleteDocument(resolver, pending) }
        }
    }

    private fun documentName(uri: Uri): String {
        return checkNotNull(resolver.query(uri, arrayOf(Docs.Document.COLUMN_DISPLAY_NAME), null, null, null)).use {
            check(it.moveToFirst()) { "Could not verify the destination name." }
            it.getString(0)
        }
    }

    private fun safeName(value: String, fallback: String) =
        value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(60).trim(' ', '.').ifEmpty { fallback }
}

internal data class PublishedFixture(val uri: Uri, val duplicate: Boolean)
