package com.localmusic.core

import java.util.UUID

fun libraryBrowseChecks() {
    fun verify(ok: Boolean, label: String) = check(ok) { "Library browse: $label" }
    fun song(n: Long, title: String, artists: List<String> = emptyList(), album: String? = null, albumArtist: String? = null,
             added: Long = n, trackNumber: Int? = null, uri: String = "content://music/$n",
             availability: Availability = Availability.AVAILABLE) = Track(
        TrackId(UUID(0, n)), MusicTags(title, artists, albumArtist, album, trackNumber),
        AudioProperties(1000, "audio/flac", 44100, 2, 16),
        listOf(TrackSource(uri, SourceKind.DOCUMENT, ContentStamp(1, 1))), availability, added)

    val blue = song(1, "Blue Train", listOf("John Coltrane"), "Blue Train", added = 30)
    val naima = song(2, "naima", listOf("John Coltrane"), "Giant Steps", added = 10)
    val so = song(3, "So What", listOf("Miles Davis", "Bill Evans"), "Kind of Blue", "Miles Davis", added = 20)
    val bare = song(4, "Untitled", added = 40, availability = Availability.MISSING)
    val ode = song(5, "ΟΔΟΣ Ελπίδας", listOf("Ελένη"), "Ünïcödé", added = 50)
    val songs = listOf(blue, naima, so, bare, ode)
    val folders = mapOf(blue.id to "Jazz/Coltrane", naima.id to "Jazz/Coltrane", so.id to "Jazz/Davis")
    val browse = LibraryBrowse(songs) { folders[it] }
    fun ids(list: List<Track>) = list.map { it.id }

    // Search keeps title, artist and album matching, ignoring case.
    verify(ids(browse.search("BLUE", BrowseOrder.TITLE)) == ids(listOf(blue, so)), "title and album match ignoring case")
    verify(ids(browse.search("coltrane", BrowseOrder.TITLE)) == ids(listOf(blue, naima)), "artist matches ignoring case")
    verify(ids(browse.search("bill evans", BrowseOrder.TITLE)) == ids(listOf(so)), "second artist matches")
    verify(ids(browse.search("giant", BrowseOrder.TITLE)) == ids(listOf(naima)), "album matches")
    verify(ids(browse.search("unknown artist", BrowseOrder.TITLE)) == ids(listOf(bare)), "songs without artists match the shown fallback")
    verify(browse.search("zzz", BrowseOrder.TITLE).isEmpty(), "no match returns nothing")
    verify(ids(browse.search("", BrowseOrder.NEWEST)).toSet() == ids(songs).toSet() && browse.search("", BrowseOrder.NEWEST).size == songs.size,
        "empty query returns everything")

    // Unicode and mixed case: the same per-letter case folding as String.contains(ignoreCase = true).
    verify(ids(browse.search("οδος", BrowseOrder.TITLE)) == ids(listOf(ode)), "Greek title matches in lower case")
    verify(ids(browse.search("ελπίδας", BrowseOrder.TITLE)) == ids(listOf(ode)), "Greek title matches its own case")
    verify(ids(browse.search("ÜNÏ", BrowseOrder.TITLE)) == ids(listOf(ode)), "accented album matches in upper case")
    verify(ids(browse.search("ελένη", BrowseOrder.TITLE)) == ids(listOf(ode)), "accented artist matches")

    // Orders.
    verify(ids(browse.search("", BrowseOrder.TITLE)) == ids(listOf(blue, naima, so, bare, ode)), "title A-Z ignores case")
    verify(ids(browse.search("", BrowseOrder.NEWEST)) == ids(listOf(ode, bare, blue, so, naima)), "newest first by added time")
    val twinB = song(0x20, "Same"); val twinA = song(0x10, "same")
    val twins = LibraryBrowse(listOf(twinB, twinA)) { null }
    verify(ids(twins.search("", BrowseOrder.TITLE)) == ids(listOf(twinA, twinB)), "equal titles break ties by song ID")

    // Group keys.
    verify(browse.groupKey(so, BrowseCategory.ALBUMS) == "Kind of Blue · Miles Davis", "album key prefers the album artist")
    verify(browse.groupKey(blue, BrowseCategory.ALBUMS) == "Blue Train · John Coltrane", "album key falls back to artists")
    verify(browse.groupKey(bare, BrowseCategory.ALBUMS) == "Unknown album · Unknown artist", "album key fallbacks")
    verify(LibraryBrowse.albumKey(so) == browse.groupKey(so, BrowseCategory.ALBUMS), "album key is the same outside a browse")
    verify(browse.groupKey(so, BrowseCategory.ARTISTS) == "Miles Davis, Bill Evans", "artist key joins artists")
    verify(browse.groupKey(bare, BrowseCategory.ARTISTS) == "Unknown artist", "artist key fallback")
    verify(browse.groupKey(naima, BrowseCategory.FOLDERS) == "Jazz/Coltrane", "folder key is the first folder")
    verify(browse.groupKey(bare, BrowseCategory.FOLDERS) == "Folder information needs a rescan", "folder key fallback")

    // Groups are sorted by key and keep the search order inside each group.
    val albums = browse.groups(browse.search("", BrowseOrder.NEWEST), BrowseCategory.ALBUMS)
    verify(albums.map { it.key } == albums.map { it.key }.sorted() && albums.size == 5, "albums sorted by key")
    val artists = browse.groups(browse.search("", BrowseOrder.NEWEST), BrowseCategory.ARTISTS)
    verify(artists.first { it.key == "John Coltrane" }.tracks == listOf(blue, naima), "artist group keeps search order")
    verify(browse.groups(songs, BrowseCategory.SONGS).isEmpty(), "songs view has no groups")
    verify(browse.groups(emptyList(), BrowseCategory.ALBUMS).isEmpty(), "no songs, no groups")

    // Folders play in track-number order, then by source.
    val t2 = song(10, "Second", trackNumber = 2, uri = "content://b")
    val none = song(11, "No number", uri = "content://a")
    val t1b = song(12, "First b", trackNumber = 1, uri = "content://z")
    val t1a = song(13, "First a", trackNumber = 1, uri = "content://y", availability = Availability.MISSING)
    val folderSongs = listOf(t2, none, t1b, t1a)
    val inFolder = LibraryBrowse(folderSongs) { "Album" }
    val folder = inFolder.groups(inFolder.search("", BrowseOrder.TITLE), BrowseCategory.FOLDERS).single()
    verify(folder.tracks == listOf(t1a, t1b, t2, none), "folder order is track number, then source, unnumbered last")

    // Available counts let the UI disable play and shuffle without counting while drawing.
    verify(folder.availableCount == 3, "available count skips missing songs")
    val gone = song(20, "Gone", listOf("Ghost"), availability = Availability.MISSING)
    val lost = song(21, "Lost", listOf("Ghost"), availability = Availability.PERMISSION_LOST)
    val ghosts = LibraryBrowse(listOf(gone, lost, blue)) { null }
    val byArtist = ghosts.groups(ghosts.tracks, BrowseCategory.ARTISTS).associateBy { it.key }
    verify(byArtist.getValue("Ghost").availableCount == 0 && byArtist.getValue("Ghost").tracks.size == 2, "a group of missing songs has none available")
    verify(byArtist.getValue("John Coltrane").availableCount == 1, "an available song is counted")
}
