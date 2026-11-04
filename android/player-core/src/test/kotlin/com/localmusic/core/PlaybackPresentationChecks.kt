package com.localmusic.core

import java.util.UUID
import kotlin.random.Random

fun playbackPresentationChecks() {
    var count = 0
    fun verify(ok: Boolean, name: String) { count++; check(ok) { "Playback presentation: $name" } }
    val a = TrackId(UUID(0, 1)); val b = TrackId(UUID(0, 2))
    val q = ListeningQueue(Random(4))
    verify(PlaybackPresentation.position(q.snapshot()).number == null, "empty session has no invented position")
    q.start(listOf(a, b, a), 2)
    verify(PlaybackPresentation.position(q.snapshot()).let { it.number == 3 && it.total == 3 }, "duplicate occurrence has its own collection rank")
    q.start(listOf(a, b, a), 0); q.next()
    verify(PlaybackPresentation.position(q.snapshot()).number == 2, "next advances collection rank")
    q.updatePosition(0); q.previous()
    verify(PlaybackPresentation.position(q.snapshot()).number == 1, "previous returns to prior occurrence")
    q.setShuffle(true)
    val shuffled = q.snapshot()
    verify(PlaybackPresentation.position(shuffled).number == shuffled.collection.indexOfFirst { it.id == q.current!!.id } + 1, "shuffle identifies original collection rank")
    q.start(listOf(a, b), 0); q.addToQueue(a); q.addToQueue(a); q.next()
    verify(PlaybackPresentation.position(q.snapshot()).let { it.label == "Queue" && it.number == 1 && it.total == 2 }, "manual occurrence uses manual queue")
    q.next()
    verify(PlaybackPresentation.position(q.snapshot()).let { it.number == 2 && it.total == 2 }, "duplicate manual entries advance independently")
    q.next()
    verify(PlaybackPresentation.position(q.snapshot()).let { it.number == 2 && it.total == 2 && it.label != "Queue" }, "collection resumes after manual queue")
    val restored = ListeningQueue(); restored.restore(q.snapshot())
    verify(PlaybackPresentation.position(restored.snapshot()) == PlaybackPresentation.position(q.snapshot()), "paused restore retains occurrence position")
    verify(!restored.isPlaying, "presentation never resumes audio")
    verify(PlaybackPresentation.quality(AudioProperties(0,"audio/flac",96000,2,24)) == "FLAC · 24-bit / 96 kHz", "known source quality")
    verify(PlaybackPresentation.quality(AudioProperties(0,"audio/flac",44100,2,16)).contains("44.1 kHz"), "fractional kHz remains precise")
    verify(PlaybackPresentation.quality(AudioProperties(0,"audio/flac",null,null,null)) == "FLAC · details unknown", "unknown source stays unknown")
    verify(!PlaybackPresentation.quality(AudioProperties(0,"audio/mpeg",48000,2,null)).contains("bit"), "lossy bit depth is not invented")
    q.start(listOf(a,b,a),2,origin="Playlist · My mix")
    verify(PlaybackPresentation.position(q.snapshot()).label == "Playlist · My mix", "captured name is shown")
    val copy = ListeningQueue(); copy.restore(q.snapshot())
    verify(copy.snapshot().origin == "Playlist · My mix" && !copy.isPlaying, "origin restores paused")
    val before=copy.snapshot()
    verify(!copy.restore(before.copy(origin="x".repeat(201))) && copy.snapshot()==before, "overlong origin rejects without mutation")
    println("Playback presentation checks passed: $count assertions.")
}
