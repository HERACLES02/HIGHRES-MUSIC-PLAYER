package com.localmusic.core

import java.util.UUID

fun smartPlaylistsChecks() {
    var count = 0
    fun verify(ok: Boolean, label: String) { count++; check(ok) { "Smart playlists: $label" } }

    val now = 1_700_000_000_000L
    val dayMs = 24L * 60L * 60L * 1000L
    val thirtyDaysAgo = now - 30L * dayMs

    fun makeTrack(
        idNum: Long,
        title: String,
        addedAtMs: Long = now - 5 * dayMs,
        sampleRateHz: Int? = 44100,
        availability: Availability = Availability.AVAILABLE,
        favorite: Boolean = false,
        genre: String? = null
    ): Track {
        return Track(
            id = TrackId(UUID(0, idNum)),
            tags = MusicTags(title = title, genre = genre),
            audio = AudioProperties(
                durationMs = 180_000L,
                mimeType = "audio/flac",
                sampleRateHz = sampleRateHz,
                channels = 2,
                meaningfulBits = 16
            ),
            sources = emptyList(),
            availability = availability,
            addedAtMs = addedAtMs,
            favorite = favorite
        )
    }

    // 1. Recently added: 30-day boundary and unavailable exclusion
    val tExactBoundary = makeTrack(1, "Exact 30 Days", addedAtMs = thirtyDaysAgo)
    val tInsideBoundary = makeTrack(2, "Inside 29 Days", addedAtMs = thirtyDaysAgo + 1)
    val tOutsideBoundary = makeTrack(3, "Old 31 Days", addedAtMs = thirtyDaysAgo - 1)
    val tUnavailableRecent = makeTrack(4, "Unavailable Recent", addedAtMs = now - dayMs, availability = Availability.MISSING)
    val tNewest = makeTrack(5, "Newest Track", addedAtMs = now - 1000L)
    val tSameTimeA = makeTrack(6, "Same Time A", addedAtMs = now - 2 * dayMs)
    val tSameTimeB = makeTrack(7, "Same Time B", addedAtMs = now - 2 * dayMs)

    val recentPool = listOf(tOutsideBoundary, tExactBoundary, tInsideBoundary, tUnavailableRecent, tNewest, tSameTimeB, tSameTimeA)
    val recentResult = SmartPlaylists.recentlyAdded(recentPool, now)

    verify(recentResult.contains(tExactBoundary), "30-day boundary exactly included")
    verify(recentResult.contains(tInsideBoundary), "inside 30 days included")
    verify(!recentResult.contains(tOutsideBoundary), "outside 30 days excluded")
    verify(!recentResult.contains(tUnavailableRecent), "unavailable tracks excluded from recently added")
    verify(recentResult.first() == tNewest, "newest track sorted first")
    verify(recentResult.map { it.id.value.leastSignificantBits } == listOf(5L, 6L, 7L, 2L, 1L), "newest first then title/id tie breaker")

    // Empty list check
    verify(SmartPlaylists.recentlyAdded(emptyList(), now).isEmpty(), "empty pool yields empty recently added")

    // 2. Hi-res tracks: > 48000 Hz, unknown omitted, unavailable excluded, sorted by rate desc then title
    val tCd = makeTrack(10, "CD Track", sampleRateHz = 44100)
    val tDat = makeTrack(11, "DAT Track", sampleRateHz = 48000)
    val tHiRes88 = makeTrack(12, "Track 88.2k", sampleRateHz = 88200)
    val tHiRes96A = makeTrack(13, "Alpha 96k", sampleRateHz = 96000)
    val tHiRes96B = makeTrack(14, "Beta 96k", sampleRateHz = 96000)
    val tHiRes192 = makeTrack(15, "Ultra 192k", sampleRateHz = 192000)
    val tUnknownRate = makeTrack(16, "Unknown Rate", sampleRateHz = null)
    val tZeroRate = makeTrack(17, "Zero Rate", sampleRateHz = 0)
    val tUnavailableHiRes = makeTrack(18, "Unavailable 96k", sampleRateHz = 96000, availability = Availability.CORRUPT)

    val hiResPool = listOf(tCd, tDat, tHiRes88, tHiRes96B, tHiRes96A, tHiRes192, tUnknownRate, tZeroRate, tUnavailableHiRes)
    val hiResResult = SmartPlaylists.hiRes(hiResPool)

    verify(!hiResResult.contains(tCd), "44.1 kHz is not hi-res")
    verify(!hiResResult.contains(tDat), "48 kHz is not hi-res (>48 kHz required)")
    verify(!hiResResult.contains(tUnknownRate), "null rate omitted")
    verify(!hiResResult.contains(tZeroRate), "0 Hz omitted")
    verify(!hiResResult.contains(tUnavailableHiRes), "unavailable hi-res excluded")
    verify(hiResResult.contains(tHiRes88), "88.2 kHz included")
    verify(hiResResult.contains(tHiRes96A) && hiResResult.contains(tHiRes96B), "96 kHz included")
    verify(hiResResult.contains(tHiRes192), "192 kHz included")
    verify(
        hiResResult.map { it.id.value.leastSignificantBits } == listOf(15L, 13L, 14L, 12L),
        "hi-res sorted rate descending then title then id"
    )

    // 3. Favorites by genre: case-insensitive match, favorite change, unavailable excluded, ordering
    val tFavRock1 = makeTrack(20, "Bohemian", favorite = true, genre = "Rock")
    val tFavRock2 = makeTrack(21, "Another One", favorite = true, genre = "rock")
    val tFavRock3 = makeTrack(22, "Crazy", favorite = true, genre = " ROCK ")
    val tFavPop = makeTrack(23, "Pop Star", favorite = true, genre = "Pop")
    val tNonFavRock = makeTrack(24, "Radio", favorite = false, genre = "Rock")
    val tFavUnavailableRock = makeTrack(25, "Ghost Rock", favorite = true, genre = "Rock", availability = Availability.PERMISSION_LOST)
    val tFavNoGenre = makeTrack(26, "Untitled", favorite = true, genre = null)

    val genrePool = listOf(tFavRock1, tFavRock2, tFavRock3, tFavPop, tNonFavRock, tFavUnavailableRock, tFavNoGenre)

    val genres = SmartPlaylists.availableGenres(genrePool)
    verify(genres == listOf("Pop", "Rock"), "available genres distinct case-insensitive sorted: $genres")

    val rockFavorites = SmartPlaylists.genreFavorites(genrePool, "rock")
    verify(rockFavorites.size == 3, "case-insensitive genre match captures all 3 rock variants: ${rockFavorites.size}")
    verify(rockFavorites.contains(tFavRock1) && rockFavorites.contains(tFavRock2) && rockFavorites.contains(tFavRock3), "all favorite rock tracks included")
    verify(!rockFavorites.contains(tFavPop), "pop excluded from rock")
    verify(!rockFavorites.contains(tNonFavRock), "non-favorite rock excluded")
    verify(!rockFavorites.contains(tFavUnavailableRock), "unavailable favorite rock excluded")
    verify(!rockFavorites.contains(tFavNoGenre), "track without genre excluded")

    // Sorting by title
    verify(
        rockFavorites.map { it.tags.title } == listOf("Another One", "Bohemian", "Crazy"),
        "genre favorites sorted by title: ${rockFavorites.map { it.tags.title }}"
    )

    // Favorite change reflection: when favorite is toggled off, it disappears
    val toggledPool = genrePool.map { if (it.id == tFavRock1.id) it.copy(favorite = false) else it }
    val rockFavoritesAfterToggle = SmartPlaylists.genreFavorites(toggledPool, "Rock")
    verify(!rockFavoritesAfterToggle.contains(tFavRock1), "unfavoriting immediately removes track from live collection")
    verify(rockFavoritesAfterToggle.size == 2, "collection reflects live favorite count: ${rockFavoritesAfterToggle.size}")

    // Empty collection when no tracks match genre
    verify(SmartPlaylists.genreFavorites(genrePool, "Classical").isEmpty(), "empty when genre has no favorites")

    println("Smart playlists checks passed: $count assertions.")
}
