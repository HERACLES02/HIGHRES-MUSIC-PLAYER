package com.localmusic.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

/** Refuse a newer on-disk DB before Room's lazy connection pool opens it. Never deletes or rewrites data. */
internal fun checkDatabaseVersion(path: String) {
    if (!File(path).exists()) return
    BundledSQLiteDriver().open(path).use { connection ->
        connection.prepare("PRAGMA user_version").use { statement ->
            check(statement.step()) { "Cannot read database version" }
            val version = statement.getLong(0)
            check(version in 0..3) { "Database version $version is newer than supported version 3; file retained" }
        }
    }
}
