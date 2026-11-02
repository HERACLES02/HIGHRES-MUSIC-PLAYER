package com.localmusic.data

import androidx.room.Database
import androidx.room.RoomDatabase

/** Installed 0.1.0 baseline. Its schema must match the already frozen PlayerDatabase/2.json. */
@Database(entities = [TrackRow::class, SourceRow::class, PlaylistRow::class, PlaylistEntryRow::class,
    FavoriteRow::class, LyricsRow::class, LyricsSelectionRow::class, SessionRow::class,
    SessionProblemRow::class, ResumeRow::class, FolderGrantRow::class], version = 2, exportSchema = true)
abstract class LegacyDatabaseV2 : RoomDatabase() { abstract fun seed(): LegacyDaoV1 }
