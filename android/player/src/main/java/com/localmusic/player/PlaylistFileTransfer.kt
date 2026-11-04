package com.localmusic.player

import android.net.Uri
import android.provider.OpenableColumns
import com.localmusic.core.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class PlaylistImportPreview(val matches: List<PlaylistFileMatch>, val titles: Map<TrackId, String>)
data class PlaylistExportDocument(val filename: String, val text: String)

/** All calls run on IO. Reads the selected playlist only; never opens its listed URLs or music files. */
object PlaylistFileTransfer {
    private fun roots(app: MusicApplication): Map<String, String> =
        app.getSystemService(android.os.storage.StorageManager::class.java).storageVolumes.mapNotNull { volume ->
            val key = if (volume.isPrimary) "primary" else volume.uuid?.lowercase() ?: return@mapNotNull null
            volume.directory?.absolutePath?.let { key to it }
        }.toMap()

    private fun localPath(key: String, roots: Map<String, String>): String? {
        val volume = key.substringBefore(':'); val path = key.substringAfter(':', "")
        return roots[volume]?.let { "${it.trimEnd('/')}/$path" }
    }

    fun displayName(app: MusicApplication, uri: Uri): String =
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "playlist.m3u8"

    fun read(app: MusicApplication, uri: Uri, library: LibraryState): Pair<String, PlaylistImportPreview> {
        val name = displayName(app, uri)
        val format = PlaylistFileFormat.entries.firstOrNull { name.endsWith(".${it.extension}", true) }
            ?: error("Choose an M3U, M3U8 or PLS file.")
        val bytes = requireNotNull(app.contentResolver.openInputStream(uri)).use { readLimited(it, PlaylistFiles.MAX_BYTES) }
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        require(format != PlaylistFileFormat.M3U8 || charset == Charsets.UTF_8) { "M3U8 files must use UTF-8." }
        val text = try { charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: java.nio.charset.CharacterCodingException) { error("Unsupported text encoding. Save the playlist as UTF-8 and try again.") }
        val roots = roots(app)
        val songs = library.tracks.map { track ->
            PlaylistFileSong(track.id, track.sources.map { it.uri } + library.details[track.id]?.canonical.orEmpty().values.mapNotNull { localPath(it, roots) })
        }
        val entries = PlaylistFiles.parse(text, format)
        val base = runCatching {
            val documentId = android.provider.DocumentsContract.getDocumentId(uri)
            LocalFileIdentity.documentKey(uri.authority, documentId)?.let { localPath(it, roots) }
        }.getOrNull()
        return name.substringBeforeLast('.').take(Playlists.MAX_NAME_CHARS) to
            PlaylistImportPreview(PlaylistFiles.match(entries, songs, base), library.tracks.associate { it.id to it.tags.title })
    }

    suspend fun export(app: MusicApplication, id: PlaylistId, format: PlaylistFileFormat): PlaylistExportDocument {
        val store = app.storage.await()
        val (playlist, entries) = store.repository().playlist(id) ?: error("The playlist was deleted.")
        val library = store.state.value
        val tracks = library.tracks.associateBy { it.id }
        val roots = roots(app)
        val files = entries.map { entry ->
            val track = tracks[entry.trackId] ?: error("A playlist song is missing from the library. Export cancelled; nothing was omitted.")
            val location = library.details[track.id]?.canonical.orEmpty().values.firstNotNullOfOrNull { localPath(it, roots) }?.let { path ->
                // URI encoding protects spaces, #, percent signs and Unicode in absolute file paths.
                java.net.URI("file", "", path, null).toASCIIString()
            } ?: track.sources.firstOrNull()?.uri ?: error("A song has no saved location. Export cancelled.")
            PlaylistFileEntry(location, track.tags.title, track.audio.durationMs)
        }
        val safeName = playlist.name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(100).ifBlank { "playlist" }
        return PlaylistExportDocument("$safeName.${format.extension}", PlaylistFiles.encode(files, format))
    }
}
