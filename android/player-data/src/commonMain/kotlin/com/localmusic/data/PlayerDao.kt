package com.localmusic.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface PlayerDao {
    @Upsert suspend fun putTrack(row: TrackRow)
    @Query("SELECT * FROM track WHERE id=:id") suspend fun track(id: String): TrackRow?
    @Query("SELECT id FROM track ORDER BY addedAtMs,id") suspend fun trackIds(): List<String>
    @Query("SELECT * FROM track ORDER BY addedAtMs,id") suspend fun allTracks(): List<TrackRow>
    @Query("SELECT * FROM track_source ORDER BY trackId,id") suspend fun allSources(): List<SourceRow>
    @Query("SELECT * FROM favorite") suspend fun allFavorites(): List<FavoriteRow>
    @Query("SELECT * FROM resume_state") suspend fun allResumes(): List<ResumeRow>
    @Query("UPDATE track SET availability=:availability WHERE id=:id") suspend fun availability(id: String, availability: String)
    @Query("DELETE FROM track WHERE id=:id") suspend fun deleteTrack(id: String)
    @Insert suspend fun insertSource(row: SourceRow)
    @Upsert suspend fun putSource(row: SourceRow)
    @Query("SELECT * FROM track_source WHERE uri=:uri") suspend fun source(uri: String): SourceRow?
    @Query("SELECT * FROM track_source WHERE trackId=:trackId ORDER BY id") suspend fun sources(trackId: String): List<SourceRow>
    @Upsert suspend fun putPlaylist(row: PlaylistRow)
    @Query("SELECT * FROM playlist WHERE id=:id") suspend fun playlist(id: String): PlaylistRow?
    @Query("SELECT * FROM playlist ORDER BY name COLLATE NOCASE, id") suspend fun playlists(): List<PlaylistRow>
    @Query("SELECT COUNT(*) FROM playlist_entry WHERE playlistId=:id") suspend fun entryCount(id: String): Int
    @Query("DELETE FROM playlist WHERE id=:id") suspend fun deletePlaylist(id: String)
    @Query("DELETE FROM playlist_entry WHERE playlistId=:id") suspend fun deletePlaylistEntries(id: String)
    @Insert suspend fun insertEntry(row: PlaylistEntryRow)
    @Query("SELECT * FROM playlist_entry WHERE id=:id") suspend fun entry(id: String): PlaylistEntryRow?
    @Query("DELETE FROM playlist_entry WHERE playlistId=:playlistId") suspend fun clearEntries(playlistId: String)
    @Query("SELECT * FROM playlist_entry WHERE playlistId=:playlistId ORDER BY rank") suspend fun entries(playlistId: String): List<PlaylistEntryRow>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun favorite(row: FavoriteRow)
    @Query("DELETE FROM favorite WHERE trackId=:id") suspend fun unfavorite(id: String)
    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE trackId=:id)") suspend fun isFavorite(id: String): Boolean
    @Upsert suspend fun putLyrics(row: LyricsRow)
    @Query("SELECT * FROM lyrics_version WHERE trackId=:id ORDER BY id") suspend fun lyrics(id: String): List<LyricsRow>
    @Query("SELECT * FROM lyrics_version WHERE id=:id") suspend fun lyric(id: String): LyricsRow?
    @Upsert suspend fun selectLyrics(row: LyricsSelectionRow)
    @Query("SELECT * FROM lyrics_selection WHERE trackId=:id") suspend fun selectedLyrics(id: String): LyricsSelectionRow?
    @Query("DELETE FROM lyrics_selection WHERE trackId=:id") suspend fun clearLyricsSelection(id: String)
    @Upsert suspend fun putSession(row: SessionRow)
    @Query("SELECT * FROM player_session WHERE slot=:slot") suspend fun session(slot: String): SessionRow?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun recordProblem(row: SessionProblemRow)
    @Query("SELECT * FROM session_problem ORDER BY savedAtMs,id") suspend fun problems(): List<SessionProblemRow>
    @Upsert suspend fun putResume(row: ResumeRow)
    @Query("SELECT * FROM resume_state WHERE trackId=:id") suspend fun resume(id: String): ResumeRow?
    @Query("DELETE FROM resume_state WHERE trackId=:id") suspend fun deleteResume(id: String)
    @Upsert suspend fun putGrant(row: FolderGrantRow)
    @Query("SELECT * FROM folder_grant ORDER BY id") suspend fun grants(): List<FolderGrantRow>
    @Upsert suspend fun putExtra(row: LibraryExtraRow)
    @Query("SELECT * FROM library_extra") suspend fun extras(): List<LibraryExtraRow>
    @Query("SELECT * FROM library_extra WHERE trackId=:id") suspend fun extra(id: String): LibraryExtraRow?
    @Upsert suspend fun putEdit(row: FileEditRow)
    @Query("SELECT * FROM file_edit ORDER BY createdAtMs DESC,id") suspend fun edits(): List<FileEditRow>
    @Query("SELECT * FROM file_edit WHERE id=:id") suspend fun edit(id: String): FileEditRow?
}
