package com.localmusic.core

import java.util.UUID

@JvmInline value class TrackId(val value: UUID) { companion object { fun new() = TrackId(UUID.randomUUID()) } }
@JvmInline value class PlaylistId(val value: UUID) { companion object { fun new() = PlaylistId(UUID.randomUUID()) } }
@JvmInline value class EntryId(val value: UUID) { companion object { fun new() = EntryId(UUID.randomUUID()) } }

enum class Availability { AVAILABLE, MISSING, PERMISSION_LOST, VOLUME_UNMOUNTED, CORRUPT }
enum class SourceKind { MEDIA_STORE, DOCUMENT }
data class ContentStamp(val sizeBytes: Long, val modifiedMs: Long?, val sha256: String? = null) {
    init { require(sizeBytes >= 0); require(sha256 == null || sha256.matches(Regex("[a-fA-F0-9]{64}"))) }
}
data class TrackSource(val uri: String, val kind: SourceKind, val stamp: ContentStamp,
                       val folderGrantId: UUID? = null) { init { require(uri.isNotBlank()) } }
data class MusicTags(val title: String, val artists: List<String> = emptyList(), val albumArtist: String? = null,
                     val album: String? = null, val trackNumber: Int? = null, val discNumber: Int? = null,
                     val date: String? = null, val genre: String? = null, val comment: String? = null)
data class AudioProperties(val durationMs: Long, val mimeType: String, val sampleRateHz: Int?,
                           val channels: Int?, val meaningfulBits: Int?) { init { require(durationMs >= 0) } }
data class Track(val id: TrackId, val tags: MusicTags, val audio: AudioProperties,
                 val sources: List<TrackSource>, val availability: Availability, val addedAtMs: Long,
                 val favorite: Boolean = false)
data class Playlist(val id: PlaylistId, val name: String)
data class PlaylistEntry(val id: EntryId, val playlistId: PlaylistId, val trackId: TrackId, val rank: Long)
data class FolderGrant(val id: UUID, val treeUri: String, val persistedFlags: Int, val grantedAtMs: Long)
data class ArtworkOverride(val trackId: TrackId, val privateFile: String, val sourceUrl: String? = null,
                           val attribution: String? = null)
enum class LyricsOrigin { USER_SELECTED, NEIGHBORING_LRC, EMBEDDED }
data class SavedLyrics(val id: UUID, val trackId: TrackId, val originalText: String,
                       val origin: LyricsOrigin, val timingAdjustmentMs: Long = 0,
                       val providerId: String? = null, val sourceUrl: String? = null,
                       val instrumental: Boolean = false) {
    init { require(timingAdjustmentMs in -86_400_000L..86_400_000L) }
}
enum class IdentityKind { ARTIST, RELEASE }
data class ProviderIdentity(val localId: UUID, val kind: IdentityKind, val provider: String, val providerId: String,
                            val sourceUrl: String, val selectedAtMs: Long)

/** Moved-file candidates never silently merge identities, even when a hash agrees. */
sealed interface SourceMatch {
    data class Existing(val trackId: TrackId) : SourceMatch
    data class NeedsConfirmation(val candidates: Set<TrackId>) : SourceMatch
    data object NewTrack : SourceMatch
}
object SourceIdentity {
    fun match(sourceUri: String, known: Map<String, TrackId>, movedCandidates: Set<TrackId>): SourceMatch =
        known[sourceUri]?.let { SourceMatch.Existing(it) }
            ?: if (movedCandidates.isEmpty()) SourceMatch.NewTrack
            else SourceMatch.NeedsConfirmation(movedCandidates.toSet())
}
