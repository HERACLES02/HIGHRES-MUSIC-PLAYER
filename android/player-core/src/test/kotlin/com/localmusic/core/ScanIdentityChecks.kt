package com.localmusic.core

fun scanIdentityChecks() {
    var checks = 0
    fun expect(value: Boolean) { checks++; check(value) { "Scan identity check $checks failed" } }
    val stamp = ContentStamp(12345, 1700000000123)
    val id = TrackId.new()
    val track = Track(id, MusicTags("Song"), AudioProperties(10000, "audio/flac", null, null, null),
        listOf(TrackSource("content://media/id/1", SourceKind.MEDIA_STORE, stamp)), Availability.AVAILABLE, 1)
    val key = "primary:Music/Album/song.flac"
    val aliases = mapOf(key to setOf(id))
    expect(LocalFileIdentity.key("external_primary", "Music/Album/song.flac") == key)
    expect(LocalFileIdentity.documentKey("com.android.externalstorage.documents", key) == key)
    expect(LocalFileIdentity.documentKey("other.provider", key) == null)
    for (path in listOf("", "Music/../song.flac", "Music//song.flac", "./song.flac", "bad\u0000.flac")) expect(LocalFileIdentity.key("primary", path) == null)
    expect(LocalFileIdentity.key("ABCD-1234", "Music/song.flac") == "abcd-1234:Music/song.flac")
    expect(LocalFileIdentity.sameStamp(stamp, stamp.copy(modifiedMs = 1700000000000)))
    expect(!LocalFileIdentity.sameStamp(stamp, stamp.copy(sizeBytes = 2)))
    expect(!LocalFileIdentity.sameStamp(stamp, stamp.copy(modifiedMs = null)))
    expect(LocalFileIdentity.match("content://document/1", stamp, key, listOf(track), aliases) == SourceMatch.Existing(id))
    expect(LocalFileIdentity.match("content://document/2", stamp, "primary:Other/song.flac", listOf(track), aliases) == SourceMatch.NewTrack)
    expect(LocalFileIdentity.match("content://document/1", stamp.copy(sizeBytes = 2), key, listOf(track), aliases) is SourceMatch.NeedsConfirmation)
    expect(LocalFileIdentity.match(track.sources[0].uri, stamp.copy(sizeBytes = 2), key, listOf(track.copy(availability = Availability.MISSING)), aliases) is SourceMatch.NeedsConfirmation)
    expect(LocalFileIdentity.match(track.sources[0].uri, stamp, key, listOf(track), aliases) == SourceMatch.Existing(id))
    expect(LocalFileIdentity.match("content://document/1", stamp, key, listOf(track), mapOf(key to setOf(id, TrackId.new()))) is SourceMatch.NeedsConfirmation)
    println("Scan identity checks passed: $checks assertions.")
}
