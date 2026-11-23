package com.localmusic.core

fun peerSearchChecks() {
    val crowd = (1..115).map { PeerSearchTrack("peer", "Album\\Song %03d.flac".format(it), 24_800_000) }
    val flac = PeerSearchTrack("other", "Album\\A.flac", 45_000_000, sampleRate = 44_100, bitDepth = 16)
    val known = PeerSearchTrack("other", "Album\\Z.mp3", 8_200_000, bitrate = 320)
    val unknown = known.copy(user = "another", bitrate = null)
    val otherSize = unknown.copy(size = 8_300_000)
    val live = known.copy(path = "Album\\Z LIVE.mp3")
    val collected = collectPeerSearchTracks(crowd + listOf(flac, known.copy(bitrate = null), known, unknown, otherSize, live))
    check(collected.size == 120 && known in collected) {
        "A repeat can supply quality. Different uploaders and sizes survive."
    }
    val options = PeerSearchOptions()
    val matches = options.matching(collected)
    val first = matches.take(30)
    val more = matches.take(60)
    check(first.size == 30 && matches.size == 120 && flac in first)
    check(more.size == 60 && more.take(30) == first)
    check(unknown in matches) { "Unknown quality is visible by default." }
    check(options.copy(nameContains = "live  z").matching(collected) == listOf(live)) {
        "Every typed word must appear in the file name, in any order and any case."
    }
    check(options.copy(nameContains = "album").matching(collected).isEmpty()) { "Only the file name is searched." }
    check(options.copy(nameContains = "  ").matching(collected).size == 120) { "A blank box keeps every result." }
    val mp3 = options.copy(format = PeerAudioFormat.MP3, nameContains = "z.mp3", minMb = "8.2", maxMb = "8.2",
        quality = "320 kbps", includeUnknown = true, sort = PeerSearchSort.SIZE_ASC)
    check(mp3.matching(collected).take(30) == listOf(known, unknown)) {
        "Filters search beyond the first page and include Unknown only when requested."
    }
    check(mp3.copy(includeUnknown = false).matching(collected) == listOf(known))
    check(mp3.copy(quality = "Unknown").matching(collected) == listOf(unknown))
    check(options.copy(sort = PeerSearchSort.SIZE_DESC).matching(collected).first() == flac)
    check(options.copy(minMb = "10", maxMb = "5").invalidSizeRange)
    check(flac.quality == "16-bit / 44.1 kHz")
    println("Peer search checks passed")
}
