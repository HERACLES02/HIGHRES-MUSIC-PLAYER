package com.localmusic.player

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Docs
import android.provider.MediaStore
import com.localmusic.core.*
import com.localmusic.data.LibraryExtras
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Enumerates only explicitly selected folders and their subfolders; it never edits music. */
class LibraryScanner(private val context: Context, private val store: MusicStore) {
    private lateinit var tracks: MutableMap<TrackId, Track>
    private lateinit var extras: MutableMap<TrackId, LibraryExtras>
    private val seen = mutableSetOf<String>()
    private val completed = mutableSetOf<String>()
    private var scanned = 0
    private var failed = 0
    private var ambiguous = 0
    private var blocked = emptySet<String>()
    private val resolver get() = context.contentResolver
    suspend fun run(): Unit = withContext(Dispatchers.IO) {
        val repo = store.repository()
        val chosenFolders = repo.folderGrants()
        if (chosenFolders.isEmpty()) {
            store.scanProgress(0, "Choose a music folder to start scanning.")
            return@withContext
        }
        tracks = repo.tracks().associateBy { it.id }.toMutableMap()
        extras = repo.extras().toMutableMap()
        blocked = repo.edits().filter { it.phase in setOf("WRITING", "UNDO_WRITING", "NEEDS_RECOVERY") }.map { it.trackId }.toSet()
        for (folder in chosenFolders) {
            ensureActive()
            try { documents(folder); completed += "folder:${folder.id}" }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed++ }
        }
        val volumes = MediaStore.getExternalVolumeNames(context)
        val grants = resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        val folders = chosenFolders.associateBy { it.id }
        val selected = folders.keys
        for (track in tracks.values) {
            ensureActive()
            if (track.id.value.toString() in blocked) continue
            if (!FolderScanPolicy.includes(track, selected)) {
                repo.markUnavailable(track.id, Availability.MISSING)
                continue
            }
            if (track.sources.any { it.uri in seen }) { repo.markUnavailable(track.id, Availability.AVAILABLE); continue }
            val relevant = track.sources.filter { it.kind == SourceKind.DOCUMENT && it.folderGrantId in selected }
            fun scope(s: TrackSource) = "folder:${s.folderGrantId}"
            val unavailable = if (relevant.all { scope(it) in completed }) Availability.MISSING
                else if (relevant.all { folders[it.folderGrantId]?.treeUri !in grants }) Availability.PERMISSION_LOST
                else if (relevant.all {
                    val uri = Uri.parse(it.uri)
                    val volume = runCatching { Docs.getDocumentId(uri).substringBefore(':').lowercase() }.getOrNull()
                    uri.authority == "com.android.externalstorage.documents" && volume !in volumes.map { v -> if (v == "external_primary") "primary" else v.lowercase() }
                }) Availability.VOLUME_UNMOUNTED
                else null
            if (unavailable != null) repo.markUnavailable(track.id, unavailable)
        }
        store.scanProgress(scanned, "$scanned entries in chosen folders checked; $failed could not be read; $ambiguous uncertain matches kept for review.")
    }
    private data class Doc(val id: String, val name: String, val mime: String, val size: Long, val modified: Long?)
    private fun children(tree: Uri, parent: String): List<Doc> {
        val result = mutableListOf<Doc>()
        val projection = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME, Docs.Document.COLUMN_MIME_TYPE, Docs.Document.COLUMN_SIZE, Docs.Document.COLUMN_LAST_MODIFIED)
        requireNotNull(resolver.query(Docs.buildChildDocumentsUriUsingTree(tree, parent), projection, null, null, null)).use { c ->
            while (c.moveToNext()) {
                check(result.size < 100_000) { "Folder contains too many entries" }
                result += Doc(c.getString(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3).coerceAtLeast(0), c.getLong(4).takeIf { it > 0 })
            }
        }
        return result
    }
    private suspend fun documents(folder: FolderGrant) {
        val tree = Uri.parse(folder.treeUri)
        require(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }) { "Reselect folder" }
        val root = Docs.getTreeDocumentId(tree)
        val pending = ArrayDeque<Pair<String, String>>().apply { add(root to root) }
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (parent, display) = pending.removeFirst()
            if (!visited.add(parent)) continue
            require(visited.size <= 10000) { "Too many nested folders" }
            val entries = children(tree, parent)
            val sidecars = entries.filter { it.name.endsWith(".lrc", true) }.associateBy { it.name.substringBeforeLast('.').lowercase() }
            for (doc in entries) {
                currentCoroutineContext().ensureActive()
                if (doc.mime == Docs.Document.MIME_TYPE_DIR) { pending += doc.id to "$display/${doc.name}"; continue }
                if (doc.name.substringAfterLast('.').lowercase() !in setOf("mp3", "flac", "wav", "m4a", "ogg", "oga", "opus", "aac")) continue
                val uri = Docs.buildDocumentUriUsingTree(tree, doc.id).toString()
                val sidecar = sidecars[doc.name.substringBeforeLast('.').lowercase()]?.let { Docs.buildDocumentUriUsingTree(tree, it.id).toString() }
                accept(TrackSource(uri, SourceKind.DOCUMENT, ContentStamp(doc.size, doc.modified), folder.id), MusicTags(doc.name.substringBeforeLast('.')),
                    AudioProperties(0, doc.mime, null, null, null), LocalFileIdentity.documentKey(tree.authority, doc.id),
                    LocalFileIdentity.documentKey(tree.authority, doc.id)?.substringBeforeLast('/') ?: display, sidecar)
            }
        }
    }
    private suspend fun accept(source: TrackSource, fallbackTags: MusicTags, fallbackAudio: AudioProperties, key: String?, folder: String, sidecar: String?) {
        val repo = store.repository()
        val aliases = extras.flatMap { (id, value) -> value.canonical.values.map { it to id } }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        val match = LocalFileIdentity.match(source.uri, source.stamp, key, tracks.values.toList(), aliases)
        if (match is SourceMatch.NeedsConfirmation) { ambiguous++; return }
        val id = (match as? SourceMatch.Existing)?.trackId ?: TrackId.new()
        if (id.value.toString() in blocked) return
        val prior = tracks[id]
        var detail = extras[id] ?: LibraryExtras()
        val stamp = "${source.stamp.sizeBytes}:${source.stamp.modifiedMs}"
        var tags = prior?.tags ?: fallbackTags; var audio = prior?.audio ?: fallbackAudio
        var embeddedPending: String? = null
        if (detail.artworkStamp != stamp || detail.artworkPath?.let { !File(it).exists() } == true || !detail.replayGainScanned) {
            val art = File.createTempFile("embedded-", ".tmp", context.cacheDir)
            try {
                val read = resolver.openFileDescriptor(Uri.parse(source.uri), "r")?.use { pfd -> JSONObject(NativeTags.readDescriptor(pfd.fd, art.path)) }
                    ?: error("Cannot read song")
                val p = read.getJSONObject("properties")
                fun values(k: String): List<String> = p.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                fun value(k: String) = values(k).firstOrNull()
                tags = NativeMetadata.tags(read, fallbackTags.copy(title = key?.substringAfter(':')?.substringAfterLast('/')?.substringBeforeLast('.') ?: fallbackTags.title))
                audio = AudioProperties(read.optLong("durationMs").coerceAtLeast(0), fallbackAudio.mimeType,
                    read.optInt("sampleRateHz").takeIf { it > 0 }, read.optInt("channels").takeIf { it > 0 }, read.optInt("bits").takeIf { it > 0 })
                val cover = if (art.length() > 0) runCatching { LocalArtwork(context).resize(art, false) }.getOrNull() else null
                val gainKeys=listOf("REPLAYGAIN_TRACK_GAIN","REPLAYGAIN_ALBUM_GAIN","REPLAYGAIN_TRACK_PEAK","REPLAYGAIN_ALBUM_PEAK")
                detail = detail.copy(artworkPath=cover,artworkStamp=stamp,replayGainTags=gainKeys.mapNotNull{k->value(k)?.takeIf{it.length<=100}?.let{k to it}}.toMap(),replayGainScanned=true)
                val lyrics = value("LYRICS") ?: value("USLT") ?: value("SYNCEDLYRICS") ?: value("UNSYNCEDLYRICS")
                if (!lyrics.isNullOrBlank() && lyrics.length <= LrcParser.MAX_CHARS) {
                    // Saved only after the track row exists below.
                    embeddedPending = lyrics
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed++; detail = detail.copy(artworkStamp = stamp) }
            finally { art.delete() }
        }
        val track = Track(id, tags, audio, (prior?.sources.orEmpty().filter { it.uri != source.uri } + source), Availability.AVAILABLE,
            prior?.addedAtMs ?: System.currentTimeMillis(), prior?.favorite ?: false)
        repo.saveTrack(track); tracks[id] = track; seen += source.uri
        detail = detail.copy(folders = detail.folders + (source.uri to folder), canonical = detail.canonical + if (key == null) emptyMap() else mapOf(source.uri to key), sidecarUri = sidecar ?: detail.sidecarUri)
        repo.saveExtra(id, detail); extras[id] = detail
        embeddedPending?.let { text -> saveDiscoveredLyrics(id, text, LyricsOrigin.EMBEDDED, source.uri) }
        if (sidecar != null) try {
            val text = resolver.openInputStream(Uri.parse(sidecar))?.use { input ->
                val bytes = readLimited(input, 2_000_000); String(bytes, Charsets.UTF_8)
            }
            if (text != null) saveDiscoveredLyrics(id, text, LyricsOrigin.NEIGHBORING_LRC, sidecar)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { failed++ }
        scanned++; if (scanned % 10 == 0) store.scanProgress(scanned, "Scanning music and artwork…")
    }
    private suspend fun saveDiscoveredLyrics(id: TrackId, text: String, origin: LyricsOrigin, source: String) {
        LrcParser.parse(text)
        val repo = store.repository()
        val exists = repo.lyricVersions(id).firstOrNull { it.origin == origin && it.originalText == text && it.sourceUrl == source }
        val version = exists?.id ?: UUID.randomUUID()
        if (exists == null) repo.saveLyrics(SavedLyrics(version, id, text, origin, sourceUrl = source))
        repo.updateExtra(id) { if (origin == LyricsOrigin.NEIGHBORING_LRC) it.copy(sidecarVersion = version.toString()) else it.copy(embeddedVersion = version.toString()) }
        extras[id] = repo.extra(id)
    }
}
