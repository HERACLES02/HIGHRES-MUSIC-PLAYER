package com.localmusic.player

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract as Docs
import java.io.File
import java.util.UUID

private const val STORAGE_PROVIDER = "com.android.externalstorage.documents"
private const val FINISHED_NAME = "Local Music peer fixture.flac"

internal class FixtureDestination(
    private val resolver: ContentResolver, val tree: Uri, private val finishedName: String = FINISHED_NAME,
) {
    init {
        require(tree.authority == STORAGE_PROVIDER && Docs.isTreeUri(tree) &&
            Docs.getTreeDocumentId(tree).substringAfter(':', "").isNotEmpty()) {
            "Choose a disposable folder under Android internal or SD card storage."
        }
    }

    private val rootId = Docs.getTreeDocumentId(tree)
    private val root = Docs.buildDocumentUriUsingTree(tree, rootId)

    fun requireWritable() {
        check(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }) {
            "Choose the destination again to restore write access."
        }
    }

    fun findFinished(): Uri? {
        val children = Docs.buildChildDocumentsUriUsingTree(tree, rootId)
        val columns = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME)
        requireNotNull(resolver.query(children, columns, null, null, null)) { "Could not read the destination." }.use { rows ->
            while (rows.moveToNext()) {
                if (rows.getString(1).equals(finishedName, ignoreCase = true)) {
                    return Docs.buildDocumentUriUsingTree(tree, rows.getString(0))
                }
            }
        }
        return null
    }

    fun matches(uri: Uri, sha256: String): Boolean =
        resolver.openInputStream(uri)?.use { it.matchesFixture(sha256) } == true

    fun publish(staged: File, sha256: String): Uri {
        requireWritable()
        findFinished()?.let {
            check(matches(it, sha256)) { "The destination already has a different file with that name. Choose another folder and retry." }
            return it
        }

        // The Android scanner ignores .part documents. Expose the .flac name only
        // after the local provider has written and verified every byte.
        val pending = checkNotNull(Docs.createDocument(resolver, root, "application/octet-stream", "peer-${UUID.randomUUID()}.part")) {
            "Could not create a staging document in the destination."
        }
        var renamed = false
        try {
            check(documentName(pending).endsWith(".part")) { "The destination changed the staging extension." }
            val descriptor = checkNotNull(resolver.openFileDescriptor(pending, "wt")) { "Could not open the staging document." }
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                staged.inputStream().use { it.copyTo(output) }
                output.fd.sync()
            }
            check(matches(pending, sha256)) { "The destination copy failed SHA-256 verification." }
            check(findFinished() == null) { "A file appeared in the destination. Retry to check it." }
            val finished = checkNotNull(Docs.renameDocument(resolver, pending, finishedName)) {
                "Could not finalize the fixture. Choose a writable folder and retry."
            }
            renamed = true
            check(documentName(finished) == finishedName) {
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
}
