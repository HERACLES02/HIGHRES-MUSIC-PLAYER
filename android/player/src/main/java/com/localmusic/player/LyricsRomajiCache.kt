package com.localmusic.player

import android.content.Context
import android.util.AtomicFile
import com.localmusic.core.LyricsRomaji
import com.localmusic.core.ParsedLyrics
import com.localmusic.core.RomajiLanguage
import com.localmusic.core.SavedLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The hidden romaji copy of each lyrics version: built on first need, then reused until the original text or the
 * romanizer version changes. It lives in no-backup storage because it can always be rebuilt from the original.
 */
class LyricsRomajiCache(context: Context) {
    private val folder = File(context.noBackupFilesDir, "lyrics-romaji")

    suspend fun lines(saved: SavedLyrics, lyrics: ParsedLyrics, language: RomajiLanguage): List<String> = withContext(Dispatchers.Default) {
        val file = AtomicFile(File(folder, "${saved.id}.txt"))
        runCatching { LyricsRomaji.decode(file.readFully().toString(Charsets.UTF_8), lyrics) }.getOrNull()?.let { return@withContext it }
        val romaji = LyricsRomaji.romanize(lyrics, language)
        // A copy that cannot be saved is simply rebuilt next time.
        runCatching {
            folder.mkdirs()
            val output = file.startWrite()
            try { output.write(LyricsRomaji.encode(lyrics, romaji).toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
            catch (e: Exception) { file.failWrite(output); throw e }
        }
        romaji
    }
}
