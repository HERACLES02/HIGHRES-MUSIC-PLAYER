package com.localmusic.data

import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Same generated DAOs/schema as Android, using real disk SQLite for host tests. */
suspend fun openPlayerDatabase(path: String): PlayerDatabase = withContext(Dispatchers.IO) {
    checkDatabaseVersion(path)
    Room.databaseBuilder<PlayerDatabase>(name = path).configured()
}
