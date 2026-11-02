package com.localmusic.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.RoomDatabase

/** Frozen initial Room schema fixture, never a deployed app DB. Add migrations; do not edit this baseline. */
@Database(entities = [TrackRow::class, SourceRow::class, PlaylistRow::class, PlaylistEntryRow::class,
    FavoriteRow::class, LyricsRow::class, LyricsSelectionRow::class, SessionRow::class,
    ResumeRow::class, FolderGrantRow::class], version = 1, exportSchema = true)
abstract class LegacyDatabaseV1 : RoomDatabase() { abstract fun seed(): LegacyDaoV1 }

@Dao interface LegacyDaoV1 {
    @Insert suspend fun track(row: TrackRow)
    @Insert suspend fun source(row: SourceRow)
    @Insert suspend fun playlist(row: PlaylistRow)
    @Insert suspend fun entry(row: PlaylistEntryRow)
    @Insert suspend fun favorite(row: FavoriteRow)
    @Insert suspend fun lyric(row: LyricsRow)
    @Insert suspend fun selection(row: LyricsSelectionRow)
    @Insert suspend fun session(row: SessionRow)
    @Insert suspend fun resume(row: ResumeRow)
    @Insert suspend fun grant(row: FolderGrantRow)
}
