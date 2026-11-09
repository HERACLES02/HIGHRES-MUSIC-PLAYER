package com.localmusic.data

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/** Makes portable artwork references on a copy; never repairs live library data. */
object BackupArtwork {
    data class Captured(val bundle: BackupBundle, val missingFiles: Int)
    fun capture(state: AppBackup, storage: File): Captured {
        val root = storage.canonicalFile.toPath()
        val assets = linkedMapOf<String, ByteArray>()
        val missing = linkedSetOf<String>()
        fun asset(path: String): String? {
            val file = File(path).canonicalFile
            require(file.toPath().startsWith(root)) { "Chosen artwork is outside app storage." }
            val bytes = try { Files.newInputStream(file.toPath()).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(32768)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(out.size().toLong() + n <= BackupArchive.MAX_ASSET) { "Artwork exceeds the supported size limit." }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            } } catch (_: NoSuchFileException) {
                missing += file.path
                return null
            }
            val name = "art/${BackupArchive.sha(bytes)}.img"
            assets[name] = bytes
            return name
        }
        val extras = state.extras.mapValues { (_, detail) ->
            val chosen = detail.artworkOverride
            detail.copy(artworkPath = null, artworkStamp = null,
                artworkOverride = if (chosen.isNullOrEmpty()) chosen else asset(chosen))
        }
        val artists = state.preferences.artists.mapValues { (_, profile) ->
            profile.copy(imagePath = profile.imagePath?.let(::asset))
        }
        return Captured(BackupBundle(state.copy(extras = extras,
            preferences = state.preferences.copy(artists = artists)), assets), missing.size)
    }
}
