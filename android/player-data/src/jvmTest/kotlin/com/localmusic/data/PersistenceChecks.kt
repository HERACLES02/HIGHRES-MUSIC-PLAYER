package com.localmusic.data

import androidx.room.Room
import androidx.room.Transactor
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.localmusic.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

private var checks = 0
private fun expect(value: Boolean, message: String) { checks++; check(value) { message } }
private suspend fun fails(block: suspend () -> Unit) {
    var failed = false
    try { block() } catch (_: Exception) { failed = true }
    expect(failed, "Operation must reject invalid input")
}
private fun id(n: Int) = TrackId(UUID(0, n.toLong()))
private fun track(n: Int) = Track(id(n), MusicTags("Title $n", listOf("Artist", "Guest"), "Album Artist", "Album", 2, 1, "2026", "Genre", "Comment"),
    AudioProperties(100000, "audio/flac", 96000, 2, 24), listOf(TrackSource("content://music/$n", SourceKind.DOCUMENT, ContentStamp(1024, 1))), Availability.AVAILABLE, 1)
private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
private fun raw(path: Path, sql: String) { BundledSQLiteDriver().open(path.toString()).use { it.execSQL(sql) } }

private suspend fun persistence(path: Path) {
    var db = openPlayerDatabase(path.toString())
    var repo = PlayerRepository(db)
    repo.saveTrack(track(1)); repo.saveTrack(track(2))
    val playlist = Playlist(PlaylistId.new(), "Offline list")
    val entries = listOf(PlaylistEntry(EntryId.new(), playlist.id, id(1), 0), PlaylistEntry(EntryId.new(), playlist.id, id(1), 1))
    repo.savePlaylist(playlist, entries); repo.setFavorite(id(1), true)
    val lyrics = SavedLyrics(UUID.randomUUID(), id(1), "[offset:100]\n[00:01]Hello", LyricsOrigin.NEIGHBORING_LRC, 250)
    val alternate = lyrics.copy(id = UUID.randomUUID(), originalText = "", origin = LyricsOrigin.EMBEDDED, instrumental = true)
    repo.saveLyrics(lyrics); repo.saveLyrics(alternate, select = true)
    val grant = FolderGrant(UUID.randomUUID(), "content://tree/music", 3, 1); repo.saveFolderGrant(grant)
    val queue = ListeningQueue().apply { start(listOf(id(1), id(2)), 0); addToQueue(id(1)); addToQueue(id(1)); updatePosition(43000) }
    repo.saveSession(queue.snapshot(), 1)
    db.close()

    db = openPlayerDatabase(path.toString()); repo = PlayerRepository(db)
    expect(repo.track(id(1)) == track(1).copy(favorite = true), "All metadata/source fields survive disk reopen")
    expect(repo.tracks().map { it.id }.toSet() == setOf(id(1), id(2)), "Library enumeration keeps stable identities after reopen")
    expect(repo.continuePosition(id(1)) == 43000L && repo.continuePosition(id(2)) == null, "Only explicit Continue exposes an unfinished saved position")
    expect(repo.playlist(playlist.id) == playlist to entries, "Duplicate playlist entries and UUIDs survive reopen")
    expect(repo.selectedLyrics(id(1)) == alternate && repo.lyricVersions(id(1)).size == 2, "Selected lyric and alternatives survive reopen")
    repo.selectLyrics(id(1), null); expect(repo.selectedLyrics(id(1)) == lyrics, "Neighbor wins after clearing explicit choice")
    expect(repo.folderGrants() == listOf(grant), "Folder grant evidence survives reopen")
    val restored = repo.loadSession() as SessionLoad.Restored
    expect(!restored.queue.isPlaying && restored.queue.positionMs == 43000L, "Real disk session restores paused")
    expect(restored.queue.snapshot() == queue.snapshot(), "Serialized queue, manual duplicates and history round trip")
    expect(db.records().resume(id(1).value.toString())?.positionMs == 43000L, "Resume state persisted with session")
    repo.markUnavailable(id(1), Availability.VOLUME_UNMOUNTED)
    expect(repo.track(id(1))?.availability == Availability.VOLUME_UNMOUNTED, "Storage removal changes availability")
    expect(repo.tracks().any { it.id == id(1) && it.availability == Availability.VOLUME_UNMOUNTED }, "Library enumeration retains missing storage")
    expect(repo.playlist(playlist.id)?.second == entries && repo.track(id(1))?.favorite == true, "Unavailable file retains playlist/favorite")
    fails { db.records().deleteTrack(id(1).value.toString()) }
    fails { repo.savePlaylist(playlist.copy(name = "Broken"), listOf(entries.first().copy(trackId = id(99)))) }
    expect(repo.playlist(playlist.id) == playlist to entries, "Failed playlist write rolls back header and entries")
    fails { repo.saveTrack(track(3).copy(sources = track(1).sources)) }
    expect(repo.track(id(3)) == null && repo.track(id(1))?.sources == track(1).sources, "Source conflict rolls back new identity")
    val wrong = lyrics.copy(id = UUID.randomUUID(), trackId = id(2)); repo.saveLyrics(wrong)
    fails { repo.selectLyrics(id(1), wrong.id) }
    expect(repo.selectedLyrics(id(1)) == lyrics, "Cross-track selection rejected without losing previous version")
    fails { repo.saveLyrics(lyrics.copy(originalText = "silently replaced")) }
    expect(repo.lyricVersions(id(1)).first { it.id == lyrics.id }.originalText == lyrics.originalText, "Existing lyric original cannot be silently overwritten")
    queue.updatePosition(96000); repo.saveSession(queue.snapshot(), 2)
    expect((repo.loadSession() as SessionLoad.Restored).queue.positionMs == 0L, "Near-end persisted session restarts paused")
    db.close()
}

private suspend fun recovery(path: Path) {
    var db = openPlayerDatabase(path.toString()); var repo = PlayerRepository(db)
    expect(repo.loadSession() == SessionLoad.Empty, "Fresh database has no phantom session")
    repo.saveTrack(track(1))
    val queue = ListeningQueue().apply { start(listOf(id(1)), 0); updatePosition(20000) }
    repo.saveSession(queue.snapshot(), 10); queue.updatePosition(40000); repo.saveSession(queue.snapshot(), 20)
    db.close()
    raw(path, "UPDATE player_session SET payload='damaged' WHERE slot='current'")
    db = openPlayerDatabase(path.toString()); repo = PlayerRepository(db)
    val fallback = repo.loadSession() as SessionLoad.Restored
    expect(fallback.usedPrevious && fallback.queue.positionMs == 20000L && !fallback.queue.isPlaying, "Checksum corruption recovers previous generation paused")
    expect(db.records().session("current")?.payload == "damaged", "Recovery does not erase corrupted original")
    expect(db.records().problems().single().payload == "damaged", "Rejected evidence is retained")
    repo.loadSession(); expect(db.records().problems().size == 1, "Repeated recovery does not duplicate evidence")
    queue.updatePosition(60000); repo.saveSession(queue.snapshot(), 30)
    expect(db.records().session("previous")?.savedAtMs == 10L, "Saving after corruption retains last valid previous copy")
    expect(!(repo.loadSession() as SessionLoad.Restored).usedPrevious, "New valid session restores normally")
    val currentBefore = db.records().session("current")
    val previousBefore = db.records().session("previous")
    val resumeBefore = db.records().resume(id(1).value.toString())
    fails {
        db.useWriterConnection { connection ->
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                queue.updatePosition(70000); repo.saveSession(queue.snapshot(), 40)
                throw IllegalStateException("Simulated interruption before transaction commit")
            }
        }
    }
    expect(db.records().session("current") == currentBefore && db.records().session("previous") == previousBefore,
        "Interrupted session transaction rolls back both generations")
    expect(db.records().resume(id(1).value.toString()) == resumeBefore, "Interrupted transaction rolls back resume state too")
    // Valid checksum is not enough: a syntactically incomplete snapshot must also recover.
    val badJson = currentBefore!!.copy(payload = "{}", sha256 = digest("{}"))
    db.records().putSession(badJson)
    expect((repo.loadSession() as SessionLoad.Restored).usedPrevious, "Missing JSON fields recover instead of crashing")
    db.close()

    raw(path, "UPDATE player_session SET payload='damaged-all'")
    db = openPlayerDatabase(path.toString()); repo = PlayerRepository(db)
    expect(repo.loadSession() is SessionLoad.Unavailable, "Both corrupt generations surface recovery state, not empty success")
    expect(db.records().session("previous")?.payload == "damaged-all", "Both originals remain")
    db.close()
}

private suspend fun versions(path: Path) {
    var db = openPlayerDatabase(path.toString()); var repo = PlayerRepository(db)
    val q = ListeningQueue().apply { start(listOf(id(1)), 0) }
    repo.saveSession(q.snapshot(), 1)
    val original = db.records().session("current")!!
    db.records().putSession(original.copy(snapshotVersion = 99))
    expect(repo.loadSession() is SessionLoad.Unavailable, "Unknown session version blocks silent fallback")
    fails { repo.saveSession(q.snapshot(), 2) }
    expect(db.records().session("current")?.snapshotVersion == 99, "Unknown version not overwritten")
    db.close()
    raw(path, "PRAGMA user_version=99")
    fails { openPlayerDatabase(path.toString()) }
    BundledSQLiteDriver().open(path.toString()).use { connection ->
        connection.prepare("PRAGMA user_version").use { s -> s.step(); expect(s.getLong(0) == 99L, "Unsupported database not destructively recreated") }
    }
}

private suspend fun migration(path: Path) {
    val legacy = Room.databaseBuilder<LegacyDatabaseV1>(name = path.toString(), factory = { LegacyDatabaseV1_Impl() })
        .setDriver(BundledSQLiteDriver()).build()
    val d = legacy.seed(); val t = track(1)
    d.track(TrackRow(t.id.value.toString(), ModelCodec.tags(t.tags), ModelCodec.audio(t.audio), t.availability.name, t.addedAtMs))
    val pid = PlaylistId.new(); d.playlist(PlaylistRow(pid.value.toString(), "v1 playlist"))
    for (rank in 0..1) d.entry(PlaylistEntryRow(UUID.randomUUID().toString(), pid.value.toString(), t.id.value.toString(), rank.toLong()))
    d.favorite(FavoriteRow(t.id.value.toString()))
    val lid = UUID.randomUUID(); d.lyric(LyricsRow(lid.toString(), t.id.value.toString(), "v1 lyric", "USER_SELECTED", 123, null, null, false))
    d.selection(LyricsSelectionRow(t.id.value.toString(), lid.toString()))
    val q = ListeningQueue().apply { start(listOf(id(1)), 0); updatePosition(45000) }; val payload = SessionCodec.encode(q.snapshot())
    d.session(SessionRow("current", 1, payload, digest(payload), 1))
    d.resume(ResumeRow(t.id.value.toString(), 45000, false, 1))
    d.source(SourceRow(UUID.randomUUID().toString(), t.id.value.toString(), "content://legacy", "DOCUMENT", 1024, 1, null, null))
    d.grant(FolderGrantRow(UUID.randomUUID().toString(), "content://legacy-folder", 3, 1))
    legacy.close()
    var db = openPlayerDatabase(path.toString()); val repo = PlayerRepository(db)
    expect(repo.playlist(pid)?.second?.size == 2, "Migration preserves duplicate playlist entries")
    expect(repo.track(id(1))?.favorite == true && repo.track(id(1))?.tags == t.tags, "Migration preserves metadata/favorite")
    expect(repo.selectedLyrics(id(1))?.id == lid, "Migration preserves lyric selection")
    expect((repo.loadSession() as SessionLoad.Restored).queue.positionMs == 45000L, "Migration preserves paused session")
    expect(db.records().resume(t.id.value.toString())?.positionMs == 45000L && repo.folderGrants().size == 1, "Migration preserves resume/grant data")
    expect(repo.track(id(1))?.sources?.single()?.uri == "content://legacy", "Migration preserves source identity")
    expect(db.records().problems().isEmpty(), "Migration adds recovery table without phantom data")
    db.close(); db = openPlayerDatabase(path.toString())
    expect(PlayerRepository(db).playlist(pid)?.first?.name == "v1 playlist", "Migrated DB reopens successfully")
    db.close()
}

private suspend fun codecChecks() {
    val detail = LibraryExtras(folders = mapOf("content://one" to "primary:/Music/Album/"), canonical = mapOf("content://one" to "primary:Music/Album/song.flac"),
        artworkPath = "/cache/cover.jpg", artworkOverride = "", tagsOverride = MusicTags("বাংলা 🎵"), sidecarVersion = UUID.randomUUID().toString())
    expect(LibraryExtras.decode(detail.encode()) == detail, "Folder/cover/override/lyric pointers round trip with Unicode and explicit art removal")
    fails { LibraryExtras.decode(detail.encode().replace("\"version\":1", "\"version\":99")) }
    val q = ListeningQueue().apply { start(listOf(id(1), id(2)), 0); updatePosition(42000); addToQueue(id(1)); addToQueue(id(1)) }
    val encoded = SessionCodec.encode(q.snapshot())
    expect(SessionCodec.decode(encoded) == q.snapshot(), "JSON wire round trip")
    expect(!encoded.contains("isPlaying") && !encoded.contains("autoplay"), "Wire format never persists playback authority")
    for (bad in listOf("", "[]", "{}", "{bad", encoded.replace("\"version\":1", "\"version\":2"),
        encoded.replace("\"repeat\":\"OFF\"", "\"repeat\":\"bad\""), encoded.replace("\"cursor\":0", "\"cursor\":-5"))) {
        fails { SessionCodec.decode(bad) }
    }
    fails { SessionCodec.decode("x".repeat(SessionCodec.MAX_CHARS + 1)) }
}

private suspend fun migrationFrom2(path: Path) {
    val old = Room.databaseBuilder<LegacyDatabaseV2>(name = path.toString(), factory = { LegacyDatabaseV2_Impl() })
        .setDriver(BundledSQLiteDriver()).build()
    val t = track(1)
    old.seed().track(TrackRow(t.id.value.toString(), ModelCodec.tags(t.tags), ModelCodec.audio(t.audio), t.availability.name, t.addedAtMs))
    old.seed().favorite(FavoriteRow(t.id.value.toString()))
    val q = ListeningQueue().apply { start(listOf(t.id), 0); updatePosition(42000) }
    val payload = SessionCodec.encode(q.snapshot())
    old.seed().session(SessionRow("current", 1, payload, digest(payload), 1)); old.close()
    var db = openPlayerDatabase(path.toString()); var repo = PlayerRepository(db)
    expect(repo.track(t.id)?.favorite == true, "Installed v2 favorite survives v3 migration")
    expect((repo.loadSession() as SessionLoad.Restored).queue.positionMs == 42000L, "Installed v2 queue restores paused after migration")
    expect(repo.extras().isEmpty() && repo.edits().isEmpty(), "Migration starts new tables empty")
    repo.updateExtra(t.id) { it.copy(artworkOverride = "", tagsOverride = t.tags.copy(title = "App title")) }
    val before = repo.extra(t.id)
    fails { repo.saveAppOverrides(mapOf(t.id to MusicTags("Should roll back"), id(99) to MusicTags("Missing"))) }
    expect(repo.extra(t.id) == before, "App-only batch overrides roll back together on a missing identity")
    val now = 1L
    val edit = FileEditRow("edit-one", "batch", t.id.value.toString(), "content://source", "Song.flac", "NEEDS_RECOVERY", "/private/original.flac", "/private/staged.flac",
        "a".repeat(64), "b".repeat(64), "c".repeat(64), "{}", "{}", "{}", 0, now, now, "Interrupted write")
    repo.saveEdit(edit)
    val synchronous = db.useWriterConnection { connection -> connection.usePrepared("PRAGMA synchronous") { statement -> statement.step(); statement.getLong(0) } }
    expect(synchronous == 2L, "Edit journals use SQLite FULL synchronization before provider overwrites")
    db.close()
    db = openPlayerDatabase(path.toString()); repo = PlayerRepository(db)
    expect(repo.edit(edit.id) == edit, "Recovery journal and backup identities survive disk reopen")
    expect(repo.extra(t.id) == before && repo.track(t.id)?.tags == t.tags, "App-only edits remain separate from source metadata")
    db.close()
}

private suspend fun invalidDatabase(path: Path) {
    val original = "This is not a SQLite database".toByteArray()
    Files.write(path, original)
    fails { openPlayerDatabase(path.toString()) }
    expect(Files.readAllBytes(path).contentEquals(original), "Unrecognized database bytes retained without destructive recovery")
}

fun main() = runBlocking {
    val testRoot = Path.of("build", "persistence-tests").toAbsolutePath().normalize()
    Files.createDirectories(testRoot)
    val directory = Files.createTempDirectory(testRoot, "room-").toRealPath()
    check(directory.parent == testRoot.toRealPath()) { "Temporary test directory escaped the intended root" }
    try {
        providerChecks(); codecChecks(); persistence(directory.resolve("library.db")); recovery(directory.resolve("recovery.db"))
        versions(directory.resolve("versions.db")); migration(directory.resolve("migration.db"))
        migrationFrom2(directory.resolve("from-installed-v2.db")); invalidDatabase(directory.resolve("invalid.db"))
        println("Room persistence checks passed: $checks assertions using real disk SQLite and generated Room DAOs.")
    } finally {
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}
