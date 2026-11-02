package com.localmusic.data

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

@Database(entities = [TrackRow::class, SourceRow::class, PlaylistRow::class, PlaylistEntryRow::class,
    FavoriteRow::class, LyricsRow::class, LyricsSelectionRow::class, SessionRow::class,
    SessionProblemRow::class, ResumeRow::class, FolderGrantRow::class, LibraryExtraRow::class, FileEditRow::class], version = 3, exportSchema = true)
@ConstructedBy(PlayerDatabaseConstructor::class)
abstract class PlayerDatabase : RoomDatabase() { abstract fun records(): PlayerDao }

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object PlayerDatabaseConstructor : RoomDatabaseConstructor<PlayerDatabase> {
    override fun initialize(): PlayerDatabase
}

object PlayerMigrations {
    val from2To3 = object : Migration(2, 3) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("CREATE TABLE IF NOT EXISTS `library_extra` (`trackId` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`trackId`), FOREIGN KEY(`trackId`) REFERENCES `track`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
            connection.execSQL("CREATE TABLE IF NOT EXISTS `file_edit` (`id` TEXT NOT NULL, `batchId` TEXT NOT NULL, `trackId` TEXT NOT NULL, `sourceUri` TEXT NOT NULL, `displayName` TEXT NOT NULL, `phase` TEXT NOT NULL, `backupPath` TEXT NOT NULL, `stagedPath` TEXT NOT NULL, `originalSha` TEXT NOT NULL, `stagedSha` TEXT NOT NULL, `payloadSha` TEXT NOT NULL, `patch` TEXT NOT NULL, `beforeJson` TEXT NOT NULL, `afterJson` TEXT NOT NULL, `coverMode` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, `result` TEXT NOT NULL, PRIMARY KEY(`id`))")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_file_edit_trackId` ON `file_edit` (`trackId`)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_file_edit_batchId` ON `file_edit` (`batchId`)")
        }
    }
    val from1To2 = object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("CREATE TABLE IF NOT EXISTS `session_problem` (`id` TEXT NOT NULL, `slot` TEXT NOT NULL, `payload` TEXT NOT NULL, `sha256` TEXT NOT NULL, `savedAtMs` INTEGER NOT NULL, `problem` TEXT NOT NULL, PRIMARY KEY(`id`))")
        }
    }
}

fun RoomDatabase.Builder<PlayerDatabase>.configured(): PlayerDatabase =
    setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(PlayerMigrations.from1To2, PlayerMigrations.from2To3).build()
