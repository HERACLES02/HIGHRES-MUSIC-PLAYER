package com.localmusic.player

import android.content.Context
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Original metadata-only FLAC: no personal songs or network fixtures. */
object ArtworkChecks {
    fun run(context: Context, fixtureUri: String? = null): Int {
        val directory = File(context.cacheDir, "artwork-regression").apply { mkdirs() }
        val song = File(directory, "fixture.flac")
        val extracted = File(directory, "extracted.png")
        val image = ByteArrayOutputStream()
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xff409080.toInt())
            check(compress(Bitmap.CompressFormat.PNG, 100, image))
            recycle()
        }
        val png = image.toByteArray()
        val streamInfo = ByteBuffer.allocate(34).apply {
            putShort(4096); putShort(4096); put(ByteArray(6))
            putLong((44100L shl 44) or (1L shl 41) or (15L shl 36) or 44100L)
            put(ByteArray(16))
        }.array()
        val comment = "TITLE=Artwork fixture".toByteArray()
        val comments = ByteBuffer.allocate(4 + 4 + 4 + comment.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0); putInt(1); putInt(comment.size); put(comment)
        }.array()
        val picture = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(3); out.writeInt(9); out.writeBytes("image/png"); out.writeInt(0)
                out.writeInt(2); out.writeInt(2); out.writeInt(32); out.writeInt(0)
                out.writeInt(png.size); out.write(png)
            }
        }.toByteArray()
        try {
            song.outputStream().use { out ->
                out.write("fLaC".toByteArray())
                for ((type, data) in listOf(0 to streamInfo, 4 to comments, 134 to picture)) {
                    out.write(byteArrayOf(type.toByte(), (data.size shr 16).toByte(), (data.size shr 8).toByte(), data.size.toByte()))
                    out.write(data)
                }
            }
            fun verify(path: String, descriptor: Int? = null) {
                extracted.delete()
                val metadata = JSONObject(if (descriptor == null) NativeTags.read(path, extracted.path)
                    else NativeTags.readDescriptor(descriptor, extracted.path))
                check(metadata.getInt("sampleRateHz") == 44100) { "Native sample rate missing for $path" }
                check(metadata.getInt("bits") == 16) { "Native bit depth missing for $path" }
                check(metadata.getInt("pictureCount") == 1) { "Embedded cover missing for $path" }
                check(extracted.isFile && extracted.readBytes().contentEquals(png)) { "Embedded PNG not extracted for $path" }
                val cached = File(LocalArtwork(context).resize(extracted, false))
                check(BitmapFactory.decodeFile(cached.path)?.let { bitmap ->
                    val valid = bitmap.width == 2 && bitmap.height == 2
                    bitmap.recycle(); valid
                } == true) { "Cached cover is not decodable" }
                cached.delete()
            }
            verify(song.path)
            ParcelFileDescriptor.open(song, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                verify("private descriptor", fd.fd)
                check(fd.statSize == song.length()) { "Native reader closed the caller's descriptor" }
            }
            val resolver = context.contentResolver
            val mediaUri = checkNotNull(resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "local-music-artwork-regression.flac")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/flac")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/LocalMusicRegression/")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }))
            try {
                resolver.openOutputStream(mediaUri, "w")!!.use { output -> song.inputStream().use { it.copyTo(output) } }
                resolver.openFileDescriptor(mediaUri, "r")!!.use { fd ->
                    verify("generated MediaStore descriptor", fd.fd)
                    check(fd.statSize == song.length()) { "Native reader closed the media descriptor" }
                }
            } finally { resolver.delete(mediaUri, null, null) }
            var rejected = false
            try { NativeTags.readDescriptor(-1, extracted.path) }
            catch (_: java.io.IOException) { rejected = true }
            check(rejected) { "Invalid descriptor was accepted" }
            if (fixtureUri != null) {
                val copy = File(directory, "provider-copy.flac")
                context.contentResolver.openInputStream(Uri.parse(fixtureUri))!!.use { input ->
                    copy.outputStream().use { input.copyTo(it) }
                }
                fun probe(path: String, stage: String, descriptor: Int? = null) {
                    val metadata = try { JSONObject(if (descriptor == null) NativeTags.read(path, extracted.path)
                        else NativeTags.readDescriptor(descriptor, extracted.path)) }
                    catch (error: Exception) { throw IllegalStateException("Metadata probe failed: $stage", error) }
                    check(metadata.getInt("sampleRateHz") == 44100) { "Sample fixture rate missing: $stage" }
                    check(metadata.getInt("pictureCount") > 0 && extracted.length() > 0) { "Sample fixture cover missing: $stage" }
                }
                probe(copy.path, "cached copy with extension")
                ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    probe("/proc/self/fd/${fd.fd}", "cached copy through descriptor")
                }
                context.contentResolver.openFileDescriptor(Uri.parse(fixtureUri), "r")!!.use { fd ->
                    probe("", "media provider descriptor", fd.fd)
                    check(fd.statSize == copy.length()) { "Native reader closed the caller's descriptor" }
                    val cached = File(LocalArtwork(context).resize(extracted, false))
                    check(cached.isFile) { "Sample fixture cover was not cached" }
                }
                copy.delete()
            }
            return if (fixtureUri == null) 4 else 5
        } finally {
            song.delete(); extracted.delete(); File(directory, "provider-copy.flac").delete(); directory.delete()
        }
    }
}
