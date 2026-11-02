package com.localmusic.player

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

class LibraryScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = (applicationContext as MusicApplication).storage.await()
        return try { store.scan(); Result.success() }
        catch (e: CancellationException) { store.scanProgress(store.state.value.scanned, "Scan stopped; completed entries are retained. Scan again to continue."); throw e }
        catch (e: Exception) { store.error("Scan failed: ${e.message}"); Result.failure() }
    }
}
