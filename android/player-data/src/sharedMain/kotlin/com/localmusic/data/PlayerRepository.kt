package com.localmusic.data

import androidx.room.Transactor
import androidx.room.useWriterConnection
import com.localmusic.core.*
import java.security.MessageDigest
import java.util.UUID

sealed interface SessionLoad {
    data object Empty : SessionLoad
    data class Restored(val queue: ListeningQueue, val savedAtMs: Long, val usedPrevious: Boolean) : SessionLoad
    data class Unavailable(val reason: String) : SessionLoad
}

/** One future service owns session writes. Every multi-row mutation is an immediate Room transaction. */
class PlayerRepository(private val database: PlayerDatabase) {
    suspend fun captureBackup(preferences: BackupPreferences): AppBackup = transaction {
        val tracks = tracks()
        val session = when (val saved = loadSession()) {
            SessionLoad.Empty -> null
            is SessionLoad.Restored -> saved.queue.snapshot()
            is SessionLoad.Unavailable -> error("Saved session needs recovery before backup: ${saved.reason}")
        }
        AppBackup(tracks, playlists().map { (playlist, _) ->
            BackupPlaylist(playlist, requireNotNull(playlist(playlist.id)).second)
        }, tracks.flatMap { lyricVersions(it.id) }, tracks.mapNotNull { t ->
            dao.selectedLyrics(t.id.value.toString())?.let { t.id to uuid(it.versionId) }
        }.toMap(), extras(), tracks.mapNotNull { dao.resume(it.id.value.toString()) }, folderGrants(), session, preferences)
    }
    suspend fun restoreBackup(state: AppBackup, assets: Map<String, String>, receipt: String) {
        BackupArchive.validate(state)
        val referenced = BackupArchive.artworkReferences(state)
        require(assets.keys == referenced && assets.values.all { it.isNotBlank() }) { "Restored artwork is incomplete." }
        require(receipt.length in 1..200)
        transaction {
            check(loadSession() !is SessionLoad.Unavailable) { "Saved session needs recovery before restore." }
            for (track in state.tracks) for (source in track.sources) {
                val owner = dao.source(source.uri)?.trackId
                require(owner == null || owner == track.id.value.toString()) { "A backup source belongs to a different song in this library. Restore into the original library or a fresh installation." }
            }
            for (playlist in state.playlists) for (entry in playlist.entries) {
                val existing = dao.entry(entry.id.value.toString())
                require(existing == null || (existing.playlistId == playlist.playlist.id.value.toString() && existing.trackId == entry.trackId.value.toString())) {
                    "A playlist occurrence belongs to a different playlist or song."
                }
            }
            val folders = folderGrants().associateBy { it.treeUri }.toMutableMap()
            val folderIds = folderGrants().associateBy { it.id }
            val remapped = mutableMapOf<UUID, UUID>()
            for (folder in state.folders) {
                val existing = folders[folder.treeUri]
                if (existing != null) remapped[folder.id] = existing.id
                else {
                    require(folderIds[folder.id] == null) { "A folder identity refers to a different folder." }
                    val restored = folder.copy(persistedFlags = 0)
                    saveFolderGrant(restored); folders[folder.treeUri] = restored; remapped[folder.id] = folder.id
                }
            }
            for (track in state.tracks) {
                if (dao.track(track.id.value.toString()) == null) saveTrack(track.copy(availability = Availability.MISSING,
                    sources = track.sources.map { s -> s.copy(folderGrantId = s.folderGrantId?.let { remapped[it] ?: it }) }))
                setFavorite(track.id, track.favorite)
            }
            // Immutable text/provenance conflicts abort the entire transaction, including rows above.
            for (lyrics in state.lyrics) saveLyrics(lyrics)
            for (track in state.tracks) {
                selectLyrics(track.id, state.selections[track.id])
                val detail = state.extras[track.id] ?: LibraryExtras()
                val override = detail.artworkOverride?.let { if (it.isEmpty()) "" else assets.getValue(it) }
                val old = extra(track.id)
                saveExtra(track.id, detail.copy(artworkOverride = override, artworkPath = old.artworkPath, artworkStamp = old.artworkStamp))
            }
            for (playlist in state.playlists) persistPlaylist(playlist.playlist, playlist.entries)
            state.session?.let { saveSession(it, System.currentTimeMillis()) }
            for (track in state.tracks) dao.deleteResume(track.id.value.toString())
            for (resume in state.resumes) dao.putResume(resume)
            dao.putSession(SessionRow("restore_receipt", 1, receipt, hash(receipt), System.currentTimeMillis()))
        }
    }
    suspend fun hasRestoreReceipt(receipt: String): Boolean = dao.session("restore_receipt")?.let {
        it.payload == receipt && it.sha256 == hash(receipt)
    } == true
    private val dao get() = database.records()
    private suspend fun <T> transaction(block: suspend () -> T): T = database.useWriterConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { block() }
    }
    private fun uuid(value: String) = UUID.fromString(value)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    suspend fun saveTrack(track: Track) = transaction {
        require(track.sources.map { it.uri }.distinct().size == track.sources.size)
        val id = track.id.value.toString()
        dao.putTrack(TrackRow(id, ModelCodec.tags(track.tags), ModelCodec.audio(track.audio), track.availability.name, track.addedAtMs))
        for (source in track.sources) {
            val old = dao.source(source.uri)
            require(old == null || old.trackId == id) { "URI already belongs to another track; confirm matching first" }
            dao.putSource(SourceRow(old?.id ?: UUID.randomUUID().toString(), id, source.uri, source.kind.name,
                source.stamp.sizeBytes, source.stamp.modifiedMs, source.stamp.sha256, source.folderGrantId?.toString()))
        }
        if (track.favorite) dao.favorite(FavoriteRow(id)) else dao.unfavorite(id)
    }
    suspend fun track(id: TrackId): Track? = transaction {
        val key = id.value.toString()
        dao.track(key)?.let { row -> Track(id, ModelCodec.tags(row.tagsJson), ModelCodec.audio(row.audioJson),
            dao.sources(key).map { TrackSource(it.uri, SourceKind.valueOf(it.kind), ContentStamp(it.sizeBytes, it.modifiedMs, it.sha256), it.folderGrantId?.let(::uuid)) },
            Availability.valueOf(row.availability), row.addedAtMs, dao.isFavorite(key)) }
    }
    suspend fun markUnavailable(id: TrackId, availability: Availability) {
        dao.availability(id.value.toString(), availability.name)
    }
    /** Read-only library enumeration; unavailable tracks retain their identity and references. */
    suspend fun tracks(): List<Track> = dao.trackIds().mapNotNull { track(TrackId(uuid(it))) }
    suspend fun continuePosition(id: TrackId): Long? {
        val saved = dao.resume(id.value.toString()) ?: return null
        val duration = track(id)?.audio?.durationMs?.takeIf { it > 0 }
        return saved.positionMs.takeIf { ResumeRules.isContinueCandidate(it, duration, saved.completed) }
    }
    suspend fun setFavorite(id: TrackId, favorite: Boolean) {
        if (favorite) dao.favorite(FavoriteRow(id.value.toString())) else dao.unfavorite(id.value.toString())
    }
    suspend fun savePlaylist(playlist: Playlist, entries: List<PlaylistEntry>) = transaction {
        persistPlaylist(playlist, entries)
    }
    private suspend fun persistPlaylist(playlist: Playlist, entries: List<PlaylistEntry>) {
        require(entries.all { it.playlistId == playlist.id })
        require(entries.map { it.id }.distinct().size == entries.size)
        require(entries.map { it.rank }.distinct().size == entries.size)
        val key = playlist.id.value.toString()
        dao.putPlaylist(PlaylistRow(key, playlist.name)); dao.clearEntries(key)
        for (entry in entries) dao.insertEntry(PlaylistEntryRow(entry.id.value.toString(), key, entry.trackId.value.toString(), entry.rank))
    }
    suspend fun playlist(id: PlaylistId): Pair<Playlist, List<PlaylistEntry>>? = transaction {
        val key = id.value.toString()
        dao.playlist(key)?.let { row -> Playlist(id, row.name) to dao.entries(key).map {
            PlaylistEntry(EntryId(uuid(it.id)), id, TrackId(uuid(it.trackId)), it.rank)
        } }
    }

    /** Summaries with entry counts; created/deleted playlists refresh through storage.refresh() */
    suspend fun playlists(): List<Pair<Playlist, Int>> = dao.playlists().map {
        Playlist(PlaylistId(uuid(it.id)), it.name) to dao.entryCount(it.id)
    }

    suspend fun createPlaylist(name: String, trackIds: List<TrackId> = emptyList()): Playlist = transaction {
        val playlist = Playlist(PlaylistId.new(), Playlists.requireName(name))
        dao.putPlaylist(PlaylistRow(playlist.id.value.toString(), playlist.name))
        if (trackIds.isNotEmpty()) persistPlaylist(playlist, Playlists.append(playlist.id, emptyList(), trackIds))
        playlist
    }

    suspend fun renamePlaylist(id: PlaylistId, name: String) = transaction {
        require(dao.playlist(id.value.toString()) != null) { "The playlist was already deleted." }
        dao.putPlaylist(PlaylistRow(id.value.toString(), Playlists.requireName(name)))
    }

    suspend fun deletePlaylist(id: PlaylistId) = transaction {
        dao.deletePlaylistEntries(id.value.toString()); dao.deletePlaylist(id.value.toString())
    }

    /** Reads one playlist; call only inside an open writer transaction. */
    private suspend fun playlistInTransaction(id: PlaylistId): Pair<Playlist, List<PlaylistEntry>> {
        val key = id.value.toString()
        val row = dao.playlist(key) ?: error("The playlist was already deleted.")
        return Playlist(id, row.name) to dao.entries(key).map {
            PlaylistEntry(EntryId(uuid(it.id)), id, TrackId(uuid(it.trackId)), it.rank)
        }
    }

    suspend fun addPlaylistEntries(id: PlaylistId, trackIds: List<TrackId>) = transaction {
        val (playlist, entries) = playlistInTransaction(id)
        persistPlaylist(playlist, Playlists.append(playlist.id, entries, trackIds))
    }

    suspend fun removePlaylistEntry(id: PlaylistId, occurrence: EntryId) = transaction {
        val (playlist, entries) = playlistInTransaction(id)
        persistPlaylist(playlist, Playlists.remove(entries, occurrence))
    }

    suspend fun movePlaylistEntry(id: PlaylistId, occurrence: EntryId, index: Int) = transaction {
        val (playlist, entries) = playlistInTransaction(id)
        persistPlaylist(playlist, Playlists.move(entries, occurrence, index))
    }
    suspend fun saveLyrics(value: SavedLyrics, select: Boolean = false) = transaction {
        require(value.originalText.length <= LrcParser.MAX_CHARS)
        val key = value.id.toString(); val track = value.trackId.value.toString()
        val old = dao.lyric(key)
        require(old == null || old.trackId == track) { "Lyric identity cannot move between tracks" }
        require(old == null || (old.originalText == value.originalText && old.origin == value.origin.name &&
            old.providerId == value.providerId && old.sourceUrl == value.sourceUrl && old.instrumental == value.instrumental)) {
            "Create a new lyric version for changed text or provenance; existing originals are retained"
        }
        dao.putLyrics(LyricsRow(key, track, value.originalText, value.origin.name, value.timingAdjustmentMs,
            value.providerId, value.sourceUrl, value.instrumental))
        if (select) dao.selectLyrics(LyricsSelectionRow(track, key))
    }
    suspend fun selectLyrics(track: TrackId, version: UUID?) = transaction {
        val key = track.value.toString()
        if (version == null) dao.clearLyricsSelection(key)
        else {
            require(dao.lyric(version.toString())?.trackId == key) { "Lyrics belong to a different/missing track" }
            dao.selectLyrics(LyricsSelectionRow(key, version.toString()))
        }
    }
    suspend fun lyricVersions(track: TrackId): List<SavedLyrics> = dao.lyrics(track.value.toString()).map {
        SavedLyrics(uuid(it.id), track, it.originalText, LyricsOrigin.valueOf(it.origin), it.timingAdjustmentMs, it.providerId, it.sourceUrl, it.instrumental)
    }
    suspend fun selectedLyrics(track: TrackId): SavedLyrics? = transaction {
        val variants = lyricVersions(track)
        val selected = dao.selectedLyrics(track.value.toString())?.versionId
        val detail = dao.extra(track.value.toString())?.let { LibraryExtras.decode(it.payload) }
        variants.firstOrNull { it.id.toString() == selected }
            ?: variants.firstOrNull { it.id.toString() == detail?.sidecarVersion }
            ?: variants.firstOrNull { it.id.toString() == detail?.embeddedVersion }
            ?: variants.firstOrNull { it.origin == LyricsOrigin.NEIGHBORING_LRC }
            ?: variants.firstOrNull { it.origin == LyricsOrigin.EMBEDDED }
    }
    suspend fun saveFolderGrant(value: FolderGrant) = dao.putGrant(FolderGrantRow(value.id.toString(), value.treeUri, value.persistedFlags, value.grantedAtMs))
    suspend fun folderGrants(): List<FolderGrant> = dao.grants().map { FolderGrant(uuid(it.id), it.treeUri, it.persistedFlags, it.grantedAtMs) }
    suspend fun extras(): Map<TrackId, LibraryExtras> = dao.extras().associate { TrackId(uuid(it.trackId)) to LibraryExtras.decode(it.payload) }
    suspend fun extra(track: TrackId): LibraryExtras = dao.extra(track.value.toString())?.let { LibraryExtras.decode(it.payload) } ?: LibraryExtras()
    suspend fun saveExtra(track: TrackId, value: LibraryExtras) { dao.putExtra(LibraryExtraRow(track.value.toString(), value.encode())) }
    suspend fun updateExtra(track: TrackId, transform: (LibraryExtras) -> LibraryExtras) = transaction {
        val old = dao.extra(track.value.toString())?.let { LibraryExtras.decode(it.payload) } ?: LibraryExtras()
        dao.putExtra(LibraryExtraRow(track.value.toString(), transform(old).encode()))
    }
    suspend fun edits(): List<FileEditRow> = dao.edits()
    suspend fun edit(id: String): FileEditRow? = dao.edit(id)
    suspend fun saveEdit(row: FileEditRow) = database.useWriterConnection { connection ->
        // Journal commits must reach durable storage before a provider overwrite can start.
        connection.usePrepared("PRAGMA synchronous=FULL") { it.step() }
        dao.putEdit(row)
    }
    suspend fun saveAppPatch(ids: List<TrackId>, patch: TagPatch) = transaction {
        for (id in ids) {
            val key = id.value.toString(); require(dao.track(key) != null)
            val old = dao.extra(key)?.let { LibraryExtras.decode(it.payload) } ?: LibraryExtras()
            dao.putExtra(LibraryExtraRow(key, old.copy(tagFields = old.tagFields + patch.fields, overridesVersion = old.overridesVersion + 1).encode()))
        }
    }
    suspend fun saveAppArtwork(ids:List<TrackId>,path:String,source:String,credit:String)=transaction{
        require(ids.isNotEmpty()&&ids.size<=5000&&source.startsWith("https://")&&credit.length<=2000)
        require(ids.all{dao.track(it.value.toString())!=null}){"A song is no longer in the library."}
        for(id in ids){val old=extra(id);saveExtra(id,old.copy(artworkOverride=path,artworkSourceUrl=source,artworkCredit=credit,overridesVersion=old.overridesVersion+1))}
    }
    suspend fun saveAppOverrides(values: Map<TrackId, MusicTags>) = transaction {
        for ((id, tags) in values) {
            val key = id.value.toString()
            require(dao.track(key) != null)
            val old = dao.extra(key)?.let { LibraryExtras.decode(it.payload) } ?: LibraryExtras()
            dao.putExtra(LibraryExtraRow(key, old.copy(tagsOverride = tags).encode()))
        }
    }

    private fun decode(row: SessionRow): QueueSnapshot {
        if (row.snapshotVersion != 1) throw UnsupportedSessionVersion(row.snapshotVersion)
        require(row.payload.length <= SessionCodec.MAX_CHARS) { "Session payload too large" }
        require(row.sha256 == hash(row.payload)) { "Session checksum mismatch" }
        return SessionCodec.decode(row.payload)
    }
    private suspend fun preserveProblem(row: SessionRow, error: Exception) {
        val identity = "${row.slot.length}:${row.slot}:${row.savedAtMs}:${row.payload.length}:${row.payload}:${row.sha256}"
        dao.recordProblem(SessionProblemRow(hash(identity), row.slot, row.payload,
            row.sha256, row.savedAtMs, error.message?.take(500) ?: error.javaClass.simpleName))
    }

    suspend fun saveSession(snapshot: QueueSnapshot, savedAtMs: Long) {
        require(savedAtMs >= 0)
        val payload = SessionCodec.encode(snapshot)
        transaction {
            val old = dao.session("current")
            if (old != null) {
                val valid = try {
                    decode(old); true
                } catch (error: UnsupportedSessionVersion) { throw error }
                catch (error: IllegalArgumentException) { preserveProblem(old, error); false }
                if (valid) dao.putSession(old.copy(slot = "previous"))
            }
            dao.putSession(SessionRow("current", 1, payload, hash(payload), savedAtMs))
            val current = snapshot.history.getOrNull(snapshot.historyCursor)
            if (current != null && dao.track(current.trackId.value.toString()) != null)
                dao.putResume(ResumeRow(current.trackId.value.toString(), snapshot.positionMs, snapshot.completed, savedAtMs))
        }
    }

    suspend fun loadSession(): SessionLoad = transaction {
        var found = false
        for (slot in listOf("current", "previous")) {
            val row = dao.session(slot) ?: continue
            found = true
            val snapshot = try {
                decode(row)
            } catch (error: UnsupportedSessionVersion) {
                return@transaction SessionLoad.Unavailable(error.message ?: "Unsupported session")
            } catch (error: IllegalArgumentException) { preserveProblem(row, error); continue }
            val key = snapshot.history.getOrNull(snapshot.historyCursor)?.trackId?.value?.toString()
            val duration = key?.let { dao.track(it) }?.let { ModelCodec.audio(it.audioJson).durationMs }
            val queue = ListeningQueue()
            require(queue.restore(snapshot, duration))
            check(!queue.isPlaying)
            return@transaction SessionLoad.Restored(queue, row.savedAtMs, slot == "previous")
        }
        if (found) SessionLoad.Unavailable("No valid session generation; original records retained") else SessionLoad.Empty
    }
}
