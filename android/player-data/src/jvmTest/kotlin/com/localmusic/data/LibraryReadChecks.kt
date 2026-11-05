package com.localmusic.data

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.localmusic.core.*
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Count real SQLite SELECTs so growing libraries cannot reintroduce per-song reads. */
suspend fun libraryReadChecks(path: Path) {
    val selects = AtomicInteger()
    val base = BundledSQLiteDriver()
    val driver = object : SQLiteDriver by base {
        override fun open(fileName: String): SQLiteConnection {
            val connection = base.open(fileName)
            return object : SQLiteConnection by connection {
                override fun prepare(sql: String): SQLiteStatement {
                    val statement = connection.prepare(sql)
                    val read = sql.trimStart().startsWith("SELECT", ignoreCase = true)
                    return object : SQLiteStatement by statement {
                        private var counted = false
                        override fun step(): Boolean {
                            if (read && !counted) { selects.incrementAndGet(); counted = true }
                            return statement.step()
                        }
                        override fun reset() { counted = false; statement.reset() }
                    }
                }
            }
        }
    }
    val db = Room.databaseBuilder<PlayerDatabase>(name = path.toString()).setDriver(driver)
        .setQueryCoroutineContext(Dispatchers.IO).build()
    try {
        val repo = PlayerRepository(db)
        val fixtures = (1..200).map { n ->
            Track(TrackId(UUID(0, n.toLong())), MusicTags("Song $n", listOf("Artist")),
                AudioProperties(120000, "audio/flac", 44100, 2, 24),
                listOf(TrackSource("content://performance/$n", SourceKind.DOCUMENT, ContentStamp(1024, 1)),
                    TrackSource("content://performance/secondary/$n", SourceKind.MEDIA_STORE, ContentStamp(2048, 2))),
                if (n % 3 == 0) Availability.MISSING else Availability.AVAILABLE, n.toLong(), n % 2 == 0)
        }
        fixtures.forEach { repo.saveTrack(it) }
        val expected = fixtures.map { requireNotNull(repo.track(it.id)) }
        val dao = db.records()
        fixtures.forEachIndexed { index, track -> dao.putResume(ResumeRow(track.id.value.toString(),
            if (index % 3 == 0) 1000 else 30000, index % 5 == 0, 1)) }
        val expectedResume = fixtures.mapNotNull { track -> repo.continuePosition(track.id)?.let { track.id to it } }.toMap()
        repo.tracks() // Warm Room before measuring the library read itself.
        selects.set(0)
        val start = System.nanoTime()
        val loaded = repo.tracks()
        val elapsed = (System.nanoTime() - start) / 1_000_000
        val queryCount = selects.get()
        check(loaded == expected) { "Bulk library load changed metadata, sources, order, availability or saved favorites" }
        println("Library read profile: tracks=${loaded.size}, SELECTs=$queryCount, elapsedMs=$elapsed")
        check(queryCount in 1..4) { "Library enumeration used $queryCount SELECTs for 200 songs; expected at most 4" }
        selects.set(0)
        val resume = repo.continuePositions(loaded)
        check(resume == expectedResume) { "Bulk resume filtering changed completed/short-position behavior" }
        check(selects.get() == 1) { "Resume enumeration must use one SELECT" }
        println("Bulk resume check: ${resume.size} eligible entries, SELECTs=${selects.get()}")
    } finally { db.close() }
}
