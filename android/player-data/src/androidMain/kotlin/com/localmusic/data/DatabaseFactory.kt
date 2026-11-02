package com.localmusic.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** No destructive fallback. An unreadable/newer database must surface an error. */
suspend fun openPlayerDatabase(context: Context): PlayerDatabase = withContext(Dispatchers.IO) {
    val path = context.getDatabasePath("local-music.db").absolutePath
    checkDatabaseVersion(path)
    Room.databaseBuilder<PlayerDatabase>(context.applicationContext, path).configured()
}
