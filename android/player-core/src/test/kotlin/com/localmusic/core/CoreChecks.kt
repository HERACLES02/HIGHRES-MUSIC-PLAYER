package com.localmusic.core

import java.util.UUID
import kotlin.random.Random

private var checks = 0
private fun expect(value: Boolean, message: String) { checks++; check(value) { message } }
private fun track(n: Int) = TrackId(UUID(0, n.toLong()))
private val tracks = (1..4).map(::track)
private fun queue(index: Int = 0, shuffle: Boolean = false) = ListeningQueue(Random(42)).apply { start(tracks, index, shuffle) }
private fun failure(block: () -> Unit) { var failed = false; try { block() } catch (_: IllegalArgumentException) { failed = true }; expect(failed, "Invalid input must be rejected") }

private fun queueChecks() {
    val q = queue()
    q.updatePosition(42000); q.pause(); expect(!q.isPlaying && q.play()?.positionMs == 42000L, "Pause/Play preserves position")
    expect(q.start(tracks, 0).positionMs == 0L, "Tap starts fresh")
    expect(q.start(tracks, 0, resumeMs = 42000, durationMs = 100000).positionMs == 42000L, "Explicit resume")
    for (saved in listOf(95000L, 99999, 100000, Long.MAX_VALUE)) expect(ResumeRules.position(saved, 100000) == 0L, "Final five seconds restart")
    expect(ResumeRules.position(94999, 100000) == 94999L, "Before final five seconds resumes")
    expect(ResumeRules.position(20000, 100000, true) == 0L, "Completed restarts")
    expect(!ResumeRules.isContinueCandidate(15000, 100000), "15-second boundary")
    expect(ResumeRules.isContinueCandidate(15001, 100000), "Beyond 15 seconds candidate")
    expect(!ResumeRules.isContinueCandidate(96000, 100000), "Finished-near-end excluded")
    expect(!ResumeRules.isContinueCandidate(20000, 100000, true), "Completed excluded")
    q.start(tracks, 0); q.next(); q.updatePosition(3001)
    expect(q.previous()?.entry?.trackId == track(2), "Previous after 3s restarts")
    q.updatePosition(3000); expect(q.previous()?.entry?.trackId == track(1), "Previous at 3s uses history")
    expect(q.next()?.entry?.trackId == track(2), "Forward history retraces")
    val mutable = tracks.toMutableList(); q.start(mutable, 0); mutable.reverse()
    expect(q.next()?.entry?.trackId == track(2), "Browsing order changes cannot mutate active list")
    q.start(tracks, 0); q.addToQueue(track(9)); q.addToQueue(track(9)); q.playNext(track(8))
    val entries = q.upcomingManual
    expect(entries.map { it.trackId } == listOf(track(8), track(9), track(9)), "Manual priority and duplicates")
    expect(entries.map { it.id }.toSet().size == 3, "Manual entries have independent identity")
    expect(q.next()?.entry?.trackId == track(8), "Play Next first")
    expect(q.next()?.entry?.trackId == track(9), "First duplicate")
    expect(q.next()?.entry?.trackId == track(9), "Second duplicate")
    expect(q.next()?.entry?.trackId == track(2), "Manual leaves collection frontier")
    expect(q.previous()?.entry?.trackId == track(9), "History includes manual songs")
    q.playNext(track(7)); expect(q.next()?.entry?.trackId == track(7), "Explicit priority after Previous")
    q.start(tracks, 0)
    q.addToQueue(track(9)); q.addToQueue(track(9)); q.addToQueue(track(3))
    val pending = q.upcomingManual
    val kept = pending[0]; val doomed = pending[1]; val after = pending[2]
    val activeBefore = q.current to q.positionMs
    q.removeOccurrence(doomed.id)
    val afterRemove = q.upcomingManual
    expect(afterRemove.map { it.trackId } == listOf(track(9), track(3)) && afterRemove[0].id == kept.id,
        "Removing one occurrence keeps the duplicate and every remaining identity")
    expect(q.current == activeBefore.first && q.positionMs == activeBefore.second, "Removing a pending entry leaves the active song and position alone")
    val stable = q.snapshot()
    failure { q.removeOccurrence(doomed.id) }
    failure { q.removeOccurrence(q.current!!.id) }
    failure { q.moveOccurrence(after.id, 2) }
    failure { q.moveOccurrence(EntryId.new(), 0) } // move-target from-guard
    failure { q.moveOccurrence(after.id, -1) }
    expect(q.snapshot() == stable, "Rejected queue edits change nothing")
    q.moveOccurrence(after.id, 0)
    expect(q.upcomingManual.map { it.trackId } == listOf(track(3), track(9)) && q.upcomingManual[0].id == after.id,
        "Move retargets one occurrence by identity; others keep relative order")
    expect(q.upcomingManual.map { it.id } == listOf(after.id, kept.id), "Move preserves every kept identity")
    q.clearManualEntries()
    expect(q.upcomingManual.isEmpty() && q.current == activeBefore.first && q.positionMs == activeBefore.second,
        "Clear queue removes only pending entries; playing or paused, the active song stays")
    expect(q.next()?.entry?.trackId == track(2), "After clearing, next follows the collection frontier")
    q.clearManualEntries()
    expect(q.upcomingManual.isEmpty(), "Clearing an empty queue is a safe no-op")
    val dup = ListeningQueue(Random(0)); dup.start(listOf(track(1), track(1), track(2)), 0)
    val first = dup.current!!; expect(dup.next()?.entry?.trackId == track(1) && dup.current?.id != first.id, "Collection duplicate occurrences preserved")
    repeat(100) { seed ->
        val shuffled = ListeningQueue(Random(seed)); shuffled.start(tracks, 2, true)
        val visited = mutableListOf(shuffled.current!!.trackId)
        while (true) { val next = shuffled.next() ?: break; visited += next.entry.trackId }
        expect(visited.size == tracks.size && visited.toSet() == tracks.toSet(), "Shuffle exhausts without repeats seed=$seed")
        expect(!shuffled.isPlaying, "Repeat off stops")
        val last = shuffled.current!!.trackId; shuffled.repeat = RepeatMode.ALL
        expect(shuffled.next()?.entry?.trackId != last, "Shuffle cycle boundary avoids immediate repeat")
    }
    val shuffled = queue(shuffle = true); shuffled.playNext(track(3)); shuffled.next()
    val remaining = mutableListOf<TrackId>(); while (true) remaining += shuffled.next()?.entry?.trackId ?: break
    expect(track(3) !in remaining && remaining.toSet() == setOf(track(2), track(4)), "Manual shuffle entry consumes unheard occurrence")
    val toggle = queue(index = 2); toggle.setShuffle(true)
    val rest = mutableListOf<TrackId>(); while (true) rest += toggle.next()?.entry?.trackId ?: break
    expect(rest.toSet() == setOf(track(1), track(2), track(4)), "Enabling shuffle includes earlier unplayed songs")
    q.start(tracks, 0); q.repeat = RepeatMode.ONE; q.addToQueue(track(9)); q.updatePosition(99000)
    expect(q.next(automatic = true)?.entry?.trackId == track(1) && q.positionMs == 0L, "Automatic Repeat One")
    expect(q.next()?.entry?.trackId == track(9), "Explicit Next bypasses Repeat One")
    q.repeat = RepeatMode.ALL; q.start(tracks, 3); expect(q.next()?.entry?.trackId == track(1), "Repeat All ordered cycle")
    q.repeat = RepeatMode.OFF; q.start(tracks, 3); expect(q.next(true) == null && q.completed && !q.isPlaying, "Completion at exhaustion")
    expect(q.play()?.positionMs == 0L, "Playing completed song restarts")
    q.start(tracks, 0, true); q.next(); q.addToQueue(track(9)); q.updatePosition(43000)
    val saved = q.snapshot(); val restored = ListeningQueue(Random(3)); expect(restored.restore(saved, 100000), "Snapshot accepted")
    expect(!restored.isPlaying && restored.positionMs == 43000L, "Restore paused with position")
    expect(restored.snapshot() == saved, "Snapshot round trip preserves sequence/history/manual")
    expect(restored.next()?.entry == q.next()?.entry, "Restored next agrees")
    val before = restored.snapshot()
    expect(!restored.restore(saved.copy(version = 99)) && before == restored.snapshot(), "Unknown version rejected atomically")
    expect(!restored.restore(saved.copy(cursor = 999)), "Invalid cursor rejected")
    expect(!restored.restore(saved.copy(order = saved.order + saved.order.first())), "Duplicate order ID rejected")
    expect(!restored.restore(saved.copy(manual = listOf(saved.collection.first()))), "Pending identity cannot alias collection")
    expect(!restored.restore(saved.copy(history = listOf(saved.history.first().copy(trackId = track(99))))), "Conflicting entry identity rejected")
    val ended = saved.copy(positionMs = 96000)
    expect(restored.restore(ended, 100000) && restored.positionMs == 0L && !restored.isPlaying, "Restore near-end restarts paused")
    repeat(40) { seed ->
        val rng = Random(seed)
        val model = ListeningQueue(Random(seed)); model.start(tracks, rng.nextInt(4), seed % 2 == 0)
        repeat(100) {
            when (rng.nextInt(10)) {
                0 -> model.next(rng.nextBoolean())
                1 -> model.previous()
                2 -> model.setShuffle(!model.shuffle)
                3 -> model.playNext(track(rng.nextInt(1, 8)))
                4 -> model.addToQueue(track(rng.nextInt(1, 8)))
                5 -> model.repeat = RepeatMode.entries[rng.nextInt(3)]
                6 -> { model.updatePosition(rng.nextLong(0, 50000)); model.pause() }
                7 -> {
                    val clean = model.snapshot()
                    expect(runCatching { model.removeOccurrence(EntryId.new()) }.exceptionOrNull() is IllegalArgumentException,
                        "Fuzz: unknown queued id is rejected seed=$seed")
                    expect(model.snapshot() == clean, "Fuzz: rejected remove changes nothing")
                    model.upcomingManual.firstOrNull()?.let { model.removeOccurrence(it.id) }
                }
                8 -> {
                    val clean = model.snapshot()
                    expect(runCatching { model.moveOccurrence(EntryId.new(), rng.nextInt(0, 3)) }.exceptionOrNull() is IllegalArgumentException,
                        "Fuzz: unknown move target is rejected seed=$seed")
                    expect(model.snapshot() == clean, "Fuzz: rejected move changes nothing")
                    model.upcomingManual.firstOrNull()?.let { model.moveOccurrence(it.id, rng.nextInt(model.upcomingManual.size)) }
                }
                9 -> if (model.upcomingManual.isNotEmpty()) model.clearManualEntries()
            }
            val copy = ListeningQueue(Random(seed))
            expect(copy.restore(model.snapshot()), "Every reachable mixed-command state can be restored seed=$seed")
            val expected = model.snapshot().let { it.copy(positionMs = ResumeRules.position(it.positionMs, null, it.completed), completed = false) }
            expect(copy.snapshot() == expected && !copy.isPlaying, "Mixed-command snapshot round trip (completed restores at zero)")
        }
    }
    failure { q.start(emptyList(), 0) }; failure { q.updatePosition(-1) }
}

private fun lyricChecks() {
    val parsed = LrcParser.parse("\uFEFF[ar:Artist]\r\n[ti:Title]\r\n[00:01.2][00:03.045]Hello\r\n[00:02]World\n[offset:+200]")
    expect(parsed.lines.map { it.timestampMs } == listOf(1200L, 2000, 3045), "Fractions, multiple tags and stable sort")
    expect(parsed.metadata["ar"] == listOf("Artist") && parsed.fileOffsetMs == 200L, "Metadata and global offset")
    val timeline = LyricsTimeline(parsed, 100)
    expect(timeline.activeIndices(1099).isEmpty(), "Before first lyric")
    expect(timeline.activeIndices(1100) == listOf(0), "Positive file offset earlier; app delay later")
    expect(timeline.seekPosition(0) == 1100L, "Tap line agrees with highlight timing")
    expect(timeline.activeIndices(2000) == listOf(1) && timeline.activeIndices(4000) == listOf(2), "Engine position drives lines and backwards seek")
    expect(timeline.activeIndices(1200) == listOf(0), "Reverse seek has no stale timer state")
    val ties = LyricsTimeline(LrcParser.parse("[00:01]a\n[00:01.000]translation\n[00:02]"))
    expect(ties.activeIndices(1000) == listOf(0, 1), "Same timestamp keeps alternatives/translations")
    expect(ties.lyrics.lines[ties.activeIndices(2000).single()].text.isEmpty(), "Timed blank clears lyric")
    val malformed = LrcParser.parse("[00:99]bad\n[00:03.1234]bad fraction\n[offset:no]\n[offset:100]\n[offset:999999999999999999999999]\n[00:04]good")
    expect(malformed.lines.size == 1 && malformed.fileOffsetMs == 100L && malformed.warnings.size == 4, "Malformed stamps/offsets safe; valid data retained")
    expect(malformed.plainLines.size == 2, "Malformed timestamp text preserved")
    expect(LrcParser.parse("Words\n[Chorus]\nবাংলা lyrics").kind == LyricsKind.PLAIN, "Plain text and Unicode")
    expect(LrcParser.parse("").kind == LyricsKind.EMPTY, "Empty state")
    expect(LyricsTimeline(LrcParser.parse("", true)).activeIndices(2000).isEmpty(), "Explicit instrumental state")
    val negative = LyricsTimeline(LrcParser.parse("[offset:-500]\n[00:01]a"))
    expect(negative.seekPosition(0) == 1500L, "Negative file offset delays")
    expect(LyricsTimeline(LrcParser.parse("[offset:5000]\n[00:01]a")).seekPosition(0) == 0L, "Negative seek clamped")
    expect(LrcParser.stamp(61234, "text") == "[01:01.234]text", "Manual stamp from engine position")
    expect(LrcParser.parse(LrcParser.stamp(61234, "text")).lines.single().timestampMs == 61234L, "Stamp parse round trip")
    expect(parsed.originalText.startsWith('\uFEFF'), "Original text immutable")
    failure { LrcParser.parse("x".repeat(LrcParser.MAX_CHARS + 1)) }
    failure { LyricsTimeline(parsed, Long.MAX_VALUE) }
    failure { LrcParser.stamp(-1, "bad") }
    failure { LrcParser.parse("\n".repeat(LrcParser.MAX_LINES)) }
    val zero = LrcParser.parse("[00:00.001]one\n[00:00.01]ten\n[00:00.1]hundred")
    expect(zero.lines.map { it.timestampMs } == listOf(1L, 10, 100), "All fractional precisions")
    expect(LrcParser.parse("[offset:100]\n[00:03]line\n[offset:200]").fileOffsetMs == 200L, "Last valid global offset applies")
    repeat(300) { seed ->
        val random = Random(seed)
        val text = (1..random.nextInt(1, 300)).joinToString("") { "[]:.-+0123456789abc\n"[random.nextInt(20)].toString() }
        val result = LrcParser.parse(text)
        expect(result.lines.zipWithNext().all { it.first.timestampMs <= it.second.timestampMs }, "Fuzz retains ordered nonnegative stamps")
    }
}

private fun playlistChecks() {
    val pid = PlaylistId.new()
    val same = PlaylistEntry(EntryId.new(), pid, track(1), 0)
    val sameDup = PlaylistEntry(EntryId.new(), pid, track(1), 1)
    val other = PlaylistEntry(EntryId.new(), pid, track(2), 2)
    val tail = PlaylistEntry(EntryId.new(), pid, track(4), 3)
    val base = listOf(same, sameDup, other, tail)
    expect(Playlists.normalized(base) == base, "Stored order and ranks pass normalization unchanged")
    failure { Playlists.normalized(listOf(same, same.copy(rank = 1))) }
    failure { Playlists.normalized(listOf(same, sameDup.copy(playlistId = PlaylistId.new()))) }
    expect(Playlists.normalized(listOf(same.copy(rank = 5), other.copy(rank = -2))).map { it.id } ==
        listOf(other.id, same.id), "Ranks normalize to playback order regardless of stored values")
    val appended = Playlists.append(pid, base, listOf(track(2), track(1), track(2)))
    expect(appended.size == 7 && appended.take(4) == base, "Append adds occurrences without touching existing identity/order")
    expect(appended.drop(4).map { it.trackId } == listOf(track(2), track(1), track(2)) &&
        appended.drop(4).map { it.id }.toSet().size == 3, "Appended duplicate songs keep independent occurrence identities")
    expect(Playlists.append(pid, emptyList(), listOf(track(1))).size == 1, "Append into empty playlist")
    failure { Playlists.append(pid, base, emptyList()) }
    failure { Playlists.append(pid, base, (1..Playlists.MAX_ENTRIES).map { track(it) }) }
    val removed = Playlists.remove(base, sameDup.id)
    expect(removed.map { it.id } == listOf(same.id, other.id, tail.id) && removed.any { it.trackId == track(1) },
        "Remove targets one occurrence; the matching duplicate stays")
    failure { Playlists.remove(base, EntryId.new()) }
    failure { Playlists.remove(emptyList(), same.id) }
    expect(Playlists.move(base, other.id, 0).map { it.trackId } == listOf(track(2), track(1), track(1), track(4)),
        "Move repositions the occurrence and keeps the rest in order")
    expect(Playlists.move(base, tail.id, 1).map { it.id } == listOf(same.id, tail.id, sameDup.id, other.id),
        "Backwards move inserts before the target position")
    expect(Playlists.move(base, same.id, base.lastIndex).map { it.id } == listOf(sameDup.id, other.id, tail.id, same.id),
        "Forward move inserts after the target position")
    expect(Playlists.move(base, same.id, 2).map { it.id } == listOf(sameDup.id, other.id, same.id, tail.id),
        "In-list move shifts later entries without a gap")
    failure { Playlists.move(base, same.id, -1) }
    failure { Playlists.move(base, same.id, base.size) }
    failure { Playlists.move(base, EntryId.new(), 0) }
    expect(Playlists.requireName("  Untitled mix  ") == "Untitled mix", "Playlist names trim on save")
    failure { Playlists.requireName("   ") }
    failure { Playlists.requireName("x".repeat(Playlists.MAX_NAME_CHARS + 1)) }
}

private fun sleepTimerChecks() {
    var now = 1_000_000L
    val base = SleepTimer { now }
    val off = base.state
    expect(off.mode == SleepState.Mode.OFF && off.remainingMs == -1L, "Fresh sleep timer is off")
    failure { base.start(0) }; failure { base.start(481) }
    expect(base.state == off, "Rejected minutes change nothing")
    base.start(480)
    expect(base.remainingMs(now) == 480 * 60_000L, "Upper boundary accepts 480 minutes")
    base.cancel()
    expect(base.state.mode == SleepState.Mode.OFF, "Cancel clears the timer before expiry")
    expect(!base.checkExpired(), "Cancelled timer cannot fire")

    val timer = SleepTimer { now }
    timer.start(2)
    expect(timer.remainingMs(now + 60_000) == 60_000L, "Countdown decreases with the clock and ignores play state")
    now += 119_999
    expect(!timer.checkExpired() && timer.remainingMs(now) == 1L, "Timer is not early by one millisecond")
    now += 1
    expect(timer.checkExpired(), "Timer fires exactly at the deadline")
    expect(!timer.checkExpired() && timer.state.mode == SleepState.Mode.OFF, "Expiry is single-fire")
    timer.start(1); now += 3_600_000
    expect(timer.remainingMs(now) == 0L, "Remaining clamps to zero at or past the deadline")
    expect(timer.checkExpired(), "Expiry runs while paused; a very late tick (Doze gap) still fires once")
    expect(timer.state.mode == SleepState.Mode.OFF && timer.state.remainingMs == -1L, "After expiry the state is fully off")
    timer.start(1); timer.start(3)
    expect(timer.remainingMs(now) == 3 * 60_000L, "Setting again replaces the previous deadline")

    val trackTimer = SleepTimer { now }
    trackTimer.start(1); trackTimer.startEndOfTrack()
    expect(trackTimer.state.mode == SleepState.Mode.END_OF_TRACK && trackTimer.remainingMs(now) == null,
        "End-of-track mode has no minute deadline")
    expect(trackTimer.atTrackEnd() && !trackTimer.checkExpired(), "Minute-expiry checks ignore end-of-track mode")
    trackTimer.consumeTrackEnd()
    expect(!trackTimer.atTrackEnd() && trackTimer.state.mode == SleepState.Mode.OFF, "Track end is consumed once")
    trackTimer.consumeTrackEnd()
    expect(!trackTimer.atTrackEnd(), "Second consume is inert")
    trackTimer.startEndOfTrack()
    expect(trackTimer.atTrackEnd() && trackTimer.state.mode == SleepState.Mode.END_OF_TRACK,
        "Re-arming end-of-track after an expiry revives the timer")
    trackTimer.cancel()
    expect(!trackTimer.atTrackEnd() && trackTimer.state == SleepState(SleepState.Mode.OFF),
        "Cancel works on an armed end-of-track timer")
    val replaced = SleepTimer { now }
    replaced.startEndOfTrack(); replaced.start(30)
    expect(replaced.state.mode == SleepState.Mode.IMMEDIATE && replaced.remainingMs(now) == 30 * 60_000L,
        "Minutes setting replaces an armed end-of-track flag")
}

private fun identityChecks() {
    val id = track(1); val uri = "content://media/external/audio/media/42"
    expect(SourceIdentity.match(uri, mapOf(uri to id), emptySet()) == SourceMatch.Existing(id), "Source dedup keeps UUID")
    expect(SourceIdentity.match("content://moved", emptyMap(), setOf(id)) is SourceMatch.NeedsConfirmation, "Moved match needs confirmation")
    expect(SourceIdentity.match("new", emptyMap(), setOf(id, track(2))) is SourceMatch.NeedsConfirmation, "Ambiguous move never auto merges")
    expect(SourceIdentity.match("new", emptyMap(), emptySet()) == SourceMatch.NewTrack, "Unknown source gets new identity")
    val pid = PlaylistId.new(); val a = PlaylistEntry(EntryId.new(), pid, id, 0); val b = PlaylistEntry(EntryId.new(), pid, id, 1)
    expect(a.id != b.id && a.trackId == b.trackId, "Playlist duplicates independently addressable")
    val lyrics = SavedLyrics(UUID.randomUUID(), id, "plain", LyricsOrigin.EMBEDDED)
    val sidecar = lyrics.copy(id = UUID.randomUUID(), origin = LyricsOrigin.NEIGHBORING_LRC)
    val explicit = lyrics.copy(id = UUID.randomUUID(), origin = LyricsOrigin.USER_SELECTED)
    expect(LyricsSelection.choose(explicit, sidecar, lyrics) == explicit, "Explicit lyrics wins")
    expect(LyricsSelection.choose(null, sidecar, lyrics) == sidecar, "Neighbor before embedded")
    expect(LyricsSelection.choose(null, null, lyrics) == lyrics, "Embedded fallback")
    expect(LyricsSelection.choose(null, null, null) == null, "No selection state")
    failure { ContentStamp(-1, null) }; failure { ContentStamp(2, null, "bad hash") }
}

fun main() {
    queueChecks(); lyricChecks(); identityChecks(); playlistChecks(); sleepTimerChecks(); scanIdentityChecks(); verifiedWriteChecks()
    println("Player core checks passed: $checks assertions (queue/resume/history/shuffle/sleep, LRC/timing/fuzz, playlists, identities).")
}
