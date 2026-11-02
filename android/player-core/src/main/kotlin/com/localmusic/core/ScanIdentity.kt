package com.localmusic.core

/** Cross-provider paths are trusted only for Android's local ExternalStorageProvider. */
object LocalFileIdentity {
    fun key(volume: String, relativePath: String): String? {
        val v = volume.lowercase(java.util.Locale.ROOT).let { if (it == "external_primary") "primary" else it }
        if (v.isBlank() || v.contains('/') || v.contains(':')) return null
        val parts = relativePath.replace('\\', '/').trim('/').split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." || it.contains('\u0000') }) return null
        return "$v:${parts.joinToString("/")}"
    }
    fun documentKey(authority: String?, documentId: String): String? {
        if (authority != "com.android.externalstorage.documents" || ':' !in documentId) return null
        return key(documentId.substringBefore(':'), documentId.substringAfter(':'))
    }
    fun sameStamp(a: ContentStamp, b: ContentStamp): Boolean {
        if (a.sizeBytes != b.sizeBytes) return false
        if (a.sha256 != null && b.sha256 != null) return a.sha256.equals(b.sha256, true)
        return a.modifiedMs != null && b.modifiedMs != null && a.modifiedMs > 0 && b.modifiedMs > 0 && a.modifiedMs / 1000 == b.modifiedMs / 1000
    }
    fun match(uri: String, stamp: ContentStamp, canonical: String?, known: List<Track>, aliases: Map<String, Set<TrackId>>): SourceMatch {
        val direct = known.firstOrNull { t -> t.sources.any { it.uri == uri } }
        if (direct != null) {
            val old = direct.sources.first { it.uri == uri }
            return if (direct.availability != Availability.AVAILABLE && !sameStamp(old.stamp, stamp))
                SourceMatch.NeedsConfirmation(setOf(direct.id)) else SourceMatch.Existing(direct.id)
        }
        val candidates = canonical?.let { aliases[it] }.orEmpty()
        if (candidates.isEmpty()) return SourceMatch.NewTrack
        if (candidates.size == 1) {
            val track = known.firstOrNull { it.id in candidates }
            if (track != null && track.sources.any { sameStamp(it.stamp, stamp) }) return SourceMatch.Existing(track.id)
        }
        return SourceMatch.NeedsConfirmation(candidates)
    }
}
