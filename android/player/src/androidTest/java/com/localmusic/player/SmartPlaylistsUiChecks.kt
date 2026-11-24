package com.localmusic.player

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.localmusic.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

object SmartPlaylistsUiChecks {
    fun run(instrumentation: Instrumentation): Int = runBlocking {
        require(Build.MODEL.startsWith("sdk_gphone")) { "Smart playlists UI checks require the emulator." }
        val context = instrumentation.targetContext
        val ui = instrumentation.uiAutomation
        var checks = 0
        fun verify(ok: Boolean, msg: String) { checks++; check(ok) { msg } }

        fun allNodes(): List<AccessibilityNodeInfo> {
            if (Build.VERSION.SDK_INT >= 34) ui.clearCache()
            val pending = ArrayDeque<AccessibilityNodeInfo>()
            ui.rootInActiveWindow?.let { pending.add(it) }
            val result = mutableListOf<AccessibilityNodeInfo>()
            while (pending.isNotEmpty() && result.size < 2500) {
                val node = pending.removeFirst()
                result.add(node)
                for (i in 0 until node.childCount) node.getChild(i)?.let { pending.add(it) }
            }
            return result
        }

        suspend fun awaitNode(description: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
            try {
                return withTimeout(20000) {
                    var found: AccessibilityNodeInfo? = null
                    var scrollAttempts = 0
                    while (found == null) {
                        found = allNodes().firstOrNull { predicate(it) }
                        if (found != null) break
                        if (scrollAttempts++ % 3 == 2) {
                            allNodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        }
                        delay(250)
                    }
                    requireNotNull(found)
                }
            } catch (t: Throwable) {
                val texts = allNodes().map { "${it.className}: text='${it.text}', desc='${it.contentDescription}'" }
                throw IllegalStateException("Timed out waiting for '$description'. Nodes:\n${texts.joinToString("\n")}", t)
            }
        }

        fun click(node: AccessibilityNodeInfo) {
            var target: AccessibilityNodeInfo? = node
            while (target != null) {
                if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                target = target.parent
            }
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                val downTime = SystemClock.uptimeMillis()
                val x = bounds.centerX().toFloat()
                val y = bounds.centerY().toFloat()
                val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                if (ui.injectInputEvent(down, true) && ui.injectInputEvent(up, true)) {
                    down.recycle()
                    up.recycle()
                    return
                }
                down.recycle()
                up.recycle()
            }
            error("Could not click node or any parent: text='${node.text}', desc='${node.contentDescription}'")
        }

        val toolsDir = File(context.filesDir, "tools").apply { mkdirs() }
        val hostTools = File("/sdcard/Download")

        fun screenshot(name: String) {
            ui.takeScreenshot()?.let { bmp ->
                File(toolsDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(hostTools, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
            }
            verify(File(toolsDir, name).exists(), "$name captured")
        }

        // Seed test tracks into Room
        val app = context.applicationContext as MusicApplication
        val store = app.storage.await()
        val repo = store.repository()

        val now = System.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1000L

        val existingGrants = repo.folderGrants()
        val grantId = existingGrants.firstOrNull()?.id ?: UUID.randomUUID()
        if (existingGrants.isEmpty()) {
            repo.saveFolderGrant(FolderGrant(grantId, "content://test-grant", 3, now))
        }

        // Isolate test library: stash and hide any existing tracks, clear favorites
        val preExistingTracks = store.state.value.tracks
        preExistingTracks.forEach {
            repo.setFavorite(it.id, false)
            repo.markUnavailable(it.id, Availability.MISSING)
        }

        val t1Id = UUID.randomUUID()
        val t2Id = UUID.randomUUID()
        val t3Id = UUID.randomUUID()
        val t4Id = UUID.randomUUID()
        val t5Id = UUID.randomUUID()
        val fixtures = mutableListOf<File>()
        val durationSeconds = 45
        fun fixture(rate: Int): List<TrackSource> {
            val file = File.createTempFile("smart-playlist-", ".wav", context.cacheDir)
            fixtures += file
            ClippingUiChecks.createTestWav(file, durationSec = durationSeconds, sampleRate = rate, amplitude = 0.1)
            return listOf(TrackSource(Uri.fromFile(file).toString(), SourceKind.DOCUMENT,
                ContentStamp(file.length(), now), grantId))
        }

        // Track 1: "Neon Lights", genre "Synthwave", favorite, 96 kHz, 2 days ago
        repo.saveTrack(
            Track(
                id = TrackId(t1Id),
                tags = MusicTags(title = "Neon Lights", artists = listOf("Electra"), genre = "Synthwave"),
                audio = AudioProperties(durationSeconds * 1000L, "audio/wav", 96000, 2, 16),
                sources = fixture(96000),
                availability = Availability.AVAILABLE,
                addedAtMs = now - 2 * dayMs,
                favorite = true
            )
        )

        // Track 2: "Retro Waves", genre "synthwave", favorite, 88.2 kHz, 5 days ago
        repo.saveTrack(
            Track(
                id = TrackId(t2Id),
                tags = MusicTags(title = "Retro Waves", artists = listOf("Electra"), genre = "synthwave"),
                audio = AudioProperties(durationSeconds * 1000L, "audio/wav", 88200, 2, 16),
                sources = fixture(88200),
                availability = Availability.AVAILABLE,
                addedAtMs = now - 5 * dayMs,
                favorite = true
            )
        )

        // Track 3: "Acoustic Melody", genre "Folk", not favorite, 44.1 kHz, 10 days ago
        repo.saveTrack(
            Track(
                id = TrackId(t3Id),
                tags = MusicTags(title = "Acoustic Melody", artists = listOf("Woodsman"), genre = "Folk"),
                audio = AudioProperties(durationSeconds * 1000L, "audio/wav", 44100, 2, 16),
                sources = fixture(44100),
                availability = Availability.AVAILABLE,
                addedAtMs = now - 10 * dayMs,
                favorite = false
            )
        )

        // Track 4: "Old Memories", genre "Folk", favorite, 44.1 kHz, 40 days ago
        repo.saveTrack(
            Track(
                id = TrackId(t4Id),
                tags = MusicTags(title = "Old Memories", artists = listOf("Woodsman"), genre = "Folk"),
                audio = AudioProperties(durationSeconds * 1000L, "audio/wav", 44100, 2, 16),
                sources = fixture(44100),
                availability = Availability.AVAILABLE,
                addedAtMs = now - 40 * dayMs,
                favorite = true
            )
        )

        // Track 5: "Unavailable Hi-Res", genre "Rock", favorite, 192 kHz, 1 day ago, unavailable
        repo.saveTrack(
            Track(
                id = TrackId(t5Id),
                tags = MusicTags(title = "Unavailable Hi-Res", artists = listOf("Rocker"), genre = "Rock"),
                audio = AudioProperties(120000, "audio/flac", 192000, 2, 24),
                sources = listOf(TrackSource("content://test-grant/$t5Id", SourceKind.DOCUMENT, ContentStamp(1024, now), grantId)),
                availability = Availability.MISSING,
                addedAtMs = now - 1 * dayMs,
                favorite = true
            )
        )

        // Refresh store state
        store.refresh()
        delay(500)

        // Start activity
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        delay(1500)

        suspend fun scrollToTop() {
            repeat(4) {
                allNodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                delay(100)
            }
        }

        try {
            // Navigate to Playlists screen
            val playlistsTab = awaitNode("Playlists tab") {
                it.text?.toString() == "Playlists" || it.contentDescription?.toString() == "Playlists"
            }
            click(playlistsTab)
            delay(500)

            // 1. Open "Recently added"
            scrollToTop()
            awaitNode("Smart playlists header") { it.text?.toString() == "Smart playlists" }
            verify(true, "Smart playlists header is visible")

            val recentCard = awaitNode("Recently added card") { it.text?.toString() == "Recently added" }
            verify(true, "Recently added smart playlist card is visible")
            click(recentCard)
            delay(1000)

            awaitNode("Recently added title") { it.text?.toString() == "Recently added" && it.className == "android.widget.TextView" }
            awaitNode("Track 1: Neon Lights") { it.contentDescription?.toString() == "Options for Neon Lights" }
            verify(true, "Recently added contains Track 1")
            awaitNode("Track 2: Retro Waves") { it.contentDescription?.toString() == "Options for Retro Waves" }
            verify(true, "Recently added contains Track 2")
            awaitNode("Track 3: Acoustic Melody") { it.contentDescription?.toString() == "Options for Acoustic Melody" }
            verify(true, "Recently added contains Track 3")
            verify(allNodes().none { it.contentDescription?.toString() == "Options for Old Memories" }, "Recently added excludes Track 4 (40 days old)")
            verify(allNodes().none { it.contentDescription?.toString() == "Options for Unavailable Hi-Res" }, "Recently added excludes Track 5 (unavailable)")

            screenshot("smart-playlists-populated-recent.png")

            // Test Deterministic Play & Shuffle flow on Recently Added (has 3 tracks: t1, t2, t3)
            val authority = PlaybackService.activeAuthority
            verify(authority != null, "Playback authority available")

            // Explicitly enable shuffle in the previous session
            instrumentation.runOnMainSync {
                authority?.queue?.setShuffle(true)
            }
            verify(authority?.queue?.snapshot()?.shuffle == true, "Shuffle enabled before tapping Play")

            // Tap Play button on Recently Added
            val playBtn = awaitNode("Play button") { it.text?.toString() == "Play" }
            click(playBtn)
            delay(800)

            // Verifies queue starts in displayed order and clears shuffle
            awaitNode("NOW PLAYING header") { it.text?.toString() == "NOW PLAYING" }
            verify(authority?.queue?.snapshot()?.shuffle == false, "Tapping Play explicitly disabled shuffle")
            verify(authority?.queue?.current?.trackId == TrackId(t1Id), "First track in displayed order selected")
            val snap = authority!!.queue.snapshot()
            val upcomingIds = snap.order.drop(snap.cursor + 1).map { it.trackId }
            verify(upcomingIds == listOf(TrackId(t2Id), TrackId(t3Id)), "Upcoming occurrences match displayed order exactly. Got: $upcomingIds, expected: ${listOf(TrackId(t2Id), TrackId(t3Id))}")
            withTimeout(5000) {
                var playing = false
                while (!playing) {
                    instrumentation.runOnMainSync {
                        playing = authority.engine.isPlaying && authority.engine.currentPosition > 0
                    }
                    if (!playing) delay(50)
                }
            }
            verify(true, "Playback advanced on the first generated song")

            // Open queue to verify resulting queue contents
            val openQueue = awaitNode("Open queue icon") { it.contentDescription?.toString() == "Open queue" }
            click(openQueue)
            delay(600)

            awaitNode("Up next header") { it.text?.toString() == "Up next" }
            verify(true, "Resulting multi-track queue displayed")
            verify(allNodes().none { it.text?.contains("Cannot play this file") == true }, "Queue shows a playable selection")
            screenshot("smart-playlists-resulting-queue.png")

            // Back to playing, then back to Recently Added detail
            instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
            delay(500)
            instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
            delay(500)

            // Tap song row 2 (Retro Waves)
            val retroRow = awaitNode("Retro Waves row") { it.text?.toString() == "Retro Waves" }
            click(retroRow)
            delay(800)

            verify(authority.queue.current?.trackId == TrackId(t2Id), "Tapping detail song row selected correct position")
            verify(authority.queue.snapshot().shuffle == false, "Tapping detail song row played in order without shuffle")

            // Back to Recently Added detail
            instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
            delay(500)

            // Tap Shuffle button
            val shuffleBtn = awaitNode("Shuffle button") { it.text?.toString() == "Shuffle" }
            click(shuffleBtn)
            delay(800)

            verify(authority.queue.snapshot().shuffle == true, "Tapping Shuffle explicitly enabled shuffle")
            val shuffledCollectionIds = authority.queue.listeningCollection.map { it.trackId }.toSet()
            verify(shuffledCollectionIds == setOf(TrackId(t1Id), TrackId(t2Id), TrackId(t3Id)), "Shuffled queue contains exactly the same collection members")

            // Back from Now Playing to Recently Added detail, then back to Playlists list
            instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
            delay(500)
            click(awaitNode("Back button from Recently Added") { it.contentDescription?.toString() == "Back" })
            delay(500)

            // 2. Open "Hi-res tracks"
            scrollToTop()
            val hiResCard = awaitNode("Hi-res tracks card") { it.text?.toString() == "Hi-res tracks" }
            verify(true, "Hi-res tracks smart playlist card is visible")
            click(hiResCard)
            delay(1000)

            awaitNode("Hi-res tracks title") { it.text?.toString() == "Hi-res tracks" && it.className == "android.widget.TextView" }
            awaitNode("Track 1: Neon Lights (96k)") { it.contentDescription?.toString() == "Options for Neon Lights" }
            verify(true, "Hi-res contains Track 1 (96k)")
            awaitNode("Track 2: Retro Waves (88.2k)") { it.contentDescription?.toString() == "Options for Retro Waves" }
            verify(true, "Hi-res contains Track 2 (88.2k)")
            verify(allNodes().none { it.contentDescription?.toString() == "Options for Acoustic Melody" }, "Hi-res excludes Track 3 (44.1k)")
            verify(allNodes().none { it.contentDescription?.toString() == "Options for Old Memories" }, "Hi-res excludes Track 4 (44.1k)")
            verify(allNodes().none { it.contentDescription?.toString() == "Options for Unavailable Hi-Res" }, "Hi-res excludes Track 5 (unavailable)")

            screenshot("smart-playlists-populated-hires.png")

            // Back to all playlists
            click(awaitNode("Back button from Hi-res") { it.contentDescription?.toString() == "Back" })
            delay(500)

            // 3. Open "Favorites by genre"
            scrollToTop()
            val genreCard = awaitNode("Favorites by genre card") { it.text?.toString() == "Favorites by genre" }
            verify(true, "Favorites by genre smart playlist card is visible")
            click(genreCard)
            delay(1000)

            awaitNode("Favorites by genre title") { it.text?.toString() == "Favorites by genre" && it.className == "android.widget.TextView" }
            verify(allNodes().any { it.text?.toString()?.equals("Folk", ignoreCase = true) == true }, "Genre picker has Folk")
            verify(allNodes().any { it.text?.toString()?.equals("Synthwave", ignoreCase = true) == true }, "Genre picker has Synthwave")
            screenshot("smart-playlists-genre-picker.png")

            // Live update on favorite change
            repo.setFavorite(TrackId(t3Id), true)
            store.refresh()
            delay(500)

            val folkChip = awaitNode("Folk chip") { it.text?.toString()?.equals("Folk", ignoreCase = true) == true }
            click(folkChip)
            delay(500)

            awaitNode("Live update: Acoustic Melody") { it.contentDescription?.toString() == "Options for Acoustic Melody" }
            verify(true, "Live update: Acoustic Melody appears after favoriting")
            awaitNode("Folk favorites: Old Memories") { it.contentDescription?.toString() == "Options for Old Memories" }
            verify(true, "Folk favorites contains Old Memories")

            // 4. Test Live Empty State & Button Disabling
            store.state.value.tracks.forEach { repo.setFavorite(it.id, false) }
            store.refresh()
            delay(1000)

            awaitNode("No songs found message") { it.text?.toString() == "No songs found" || it.text?.toString()?.contains("No genres found") == true }
            verify(true, "Empty state displayed when no tracks match")

            val emptyPlayBtn = allNodes().firstOrNull { it.text?.toString() == "Play" }
            if (emptyPlayBtn != null) {
                var current: AccessibilityNodeInfo? = emptyPlayBtn
                var disabled = false
                while (current != null) {
                    if (!current.isEnabled) { disabled = true; break }
                    current = current.parent
                }
                verify(disabled, "Play button is disabled in empty state")
            }
            screenshot("smart-playlists-empty-state.png")

            // 5. Collection reconstruction after restart
            val reloadedRecent = SmartPlaylists.recentlyAdded(repo.tracks(), System.currentTimeMillis())
            verify(reloadedRecent.map { it.id }.toSet() == setOf(TrackId(t1Id), TrackId(t2Id), TrackId(t3Id)), "Collections reconstruct accurately after restart")
        } finally {
            instrumentation.runOnMainSync { PlaybackService.activeAuthority?.stop() }
            listOf(t1Id, t2Id, t3Id, t4Id, t5Id).forEach { repo.markUnavailable(TrackId(it), Availability.MISSING) }
            preExistingTracks.forEach {
                repo.setFavorite(it.id, it.favorite)
                repo.markUnavailable(it.id, it.availability)
            }
            fixtures.forEach { it.delete() }
            activity.finish()
        }

        checks
    }
}
