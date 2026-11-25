package com.localmusic.core

import java.util.UUID

fun libraryFavoritesChecks() {
    fun verify(ok: Boolean, label: String) = check(ok) { "Library favorites: $label" }
    fun song(n: Long, availability: Availability = Availability.AVAILABLE, favorite: Boolean = false) = Track(
        TrackId(UUID(0, n)), MusicTags("Song $n", genre = "Jazz"), AudioProperties(1000, "audio/flac", 44100, 2, 16),
        emptyList(), availability, n, favorite)

    val songs = (1L..5L).map { song(it) }
    val target = songs[3].id
    val updated = LibraryFavorites.apply(songs, target, true)
    verify(updated.map { it.id } == songs.map { it.id }, "list order is kept")
    verify(updated[3].favorite && updated[3].copy(favorite = false) == songs[3], "only the favorite flag changes")
    verify((updated.indices - 3).all { updated[it] === songs[it] }, "other songs keep their instances")
    verify(LibraryFavorites.apply(updated, target, false) == songs, "un-favorite restores the song")
    verify(LibraryFavorites.apply(songs, TrackId(UUID(0, 99)), true) == songs, "an unknown song changes nothing")

    // The lookup can hold a different copy (hidden songs are marked missing there); keep that copy's other fields.
    val hidden = song(4, Availability.MISSING)
    val byId = songs.associateBy { it.id } + (target to hidden)
    val lookup = LibraryFavorites.apply(byId, target, true)
    verify(lookup[target] == hidden.copy(favorite = true), "lookup updates its own copy")
    verify(lookup.keys == byId.keys && (byId.keys - target).all { lookup[it] === byId[it] }, "lookup leaves other songs alone")
    verify(LibraryFavorites.apply(byId, TrackId(UUID(0, 99)), true) == byId, "lookup ignores an unknown song")
}
