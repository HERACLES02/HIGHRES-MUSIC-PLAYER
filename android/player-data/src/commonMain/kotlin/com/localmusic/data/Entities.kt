package com.localmusic.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "track")
data class TrackRow(@PrimaryKey val id: String, val tagsJson: String, val audioJson: String,
                    val availability: String, val addedAtMs: Long)

@Entity(tableName = "track_source", foreignKeys = [ForeignKey(entity = TrackRow::class,
    parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("trackId"), Index(value = ["uri"], unique = true)])
data class SourceRow(@PrimaryKey val id: String, val trackId: String, val uri: String,
                     val kind: String, val sizeBytes: Long, val modifiedMs: Long?, val sha256: String?,
                     val folderGrantId: String?)

@Entity(tableName = "playlist")
data class PlaylistRow(@PrimaryKey val id: String, val name: String)

@Entity(tableName = "playlist_entry", foreignKeys = [
    ForeignKey(entity = PlaylistRow::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = TrackRow::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("trackId"), Index(value = ["playlistId", "rank"], unique = true)])
data class PlaylistEntryRow(@PrimaryKey val id: String, val playlistId: String, val trackId: String, val rank: Long)

@Entity(tableName = "favorite", foreignKeys = [ForeignKey(entity = TrackRow::class,
    parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.RESTRICT)])
data class FavoriteRow(@PrimaryKey val trackId: String)

@Entity(tableName = "lyrics_version", foreignKeys = [ForeignKey(entity = TrackRow::class,
    parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("trackId"), Index(value = ["id", "trackId"], unique = true)])
data class LyricsRow(@PrimaryKey val id: String, val trackId: String, val originalText: String,
                     val origin: String, val timingAdjustmentMs: Long, val providerId: String?,
                     val sourceUrl: String?, val instrumental: Boolean)

@Entity(tableName = "lyrics_selection", foreignKeys = [ForeignKey(entity = LyricsRow::class,
    parentColumns = ["id", "trackId"], childColumns = ["versionId", "trackId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["versionId", "trackId"])])
data class LyricsSelectionRow(@PrimaryKey val trackId: String, val versionId: String)

@Entity(tableName = "player_session")
data class SessionRow(@PrimaryKey val slot: String, val snapshotVersion: Int, val payload: String,
                      val sha256: String, val savedAtMs: Long)

/** Added in v2: retain rejected sessions so recovery never silently destroys evidence. */
@Entity(tableName = "session_problem")
data class SessionProblemRow(@PrimaryKey val id: String, val slot: String, val payload: String,
                             val sha256: String, val savedAtMs: Long, val problem: String)

@Entity(tableName = "resume_state", foreignKeys = [ForeignKey(entity = TrackRow::class,
    parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.RESTRICT)])
data class ResumeRow(@PrimaryKey val trackId: String, val positionMs: Long, val completed: Boolean, val updatedAtMs: Long)

@Entity(tableName = "folder_grant", indices = [Index(value = ["treeUri"], unique = true)])
data class FolderGrantRow(@PrimaryKey val id: String, val treeUri: String, val persistedFlags: Int, val grantedAtMs: Long)

@Entity(tableName = "library_extra", foreignKeys = [ForeignKey(entity = TrackRow::class,
    parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.RESTRICT)])
data class LibraryExtraRow(@PrimaryKey val trackId: String, val payload: String)

@Entity(tableName = "file_edit", indices = [Index("trackId"), Index("batchId")])
data class FileEditRow(@PrimaryKey val id: String, val batchId: String, val trackId: String, val sourceUri: String,
    val displayName: String, val phase: String, val backupPath: String, val stagedPath: String,
    val originalSha: String, val stagedSha: String, val payloadSha: String, val patch: String,
    val beforeJson: String, val afterJson: String, val coverMode: Int, val createdAtMs: Long, val updatedAtMs: Long, val result: String)
