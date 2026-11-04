package com.localmusic.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import java.io.File
import java.security.MessageDigest

class LocalArtwork(private val context: Context) {
    fun import(uri:Uri,crop:Boolean,durable:Boolean=true,preview:Boolean=false):String {
        val raw = File.createTempFile("art-", ".tmp", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> raw.outputStream().use { output ->
                val buffer = ByteArray(32768); var total = 0
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 16 * 1024 * 1024) { "Choose an image smaller than 16 MB." }; output.write(buffer, 0, n) }
            } } ?: error("Cannot read this image")
            return resize(raw,durable,crop,preview)
        } finally { raw.delete() }
    }
    fun resize(raw:File,durable:Boolean,crop:Boolean=false,preview:Boolean=false):String {
        require(!preview||!durable)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(raw.path, bounds)
        require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384 && bounds.outWidth.toLong() * bounds.outHeight <= 64_000_000) { "Unsupported or excessively large image" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val decoded = requireNotNull(BitmapFactory.decodeFile(raw.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        val orientation = runCatching { androidx.exifinterface.media.ExifInterface(raw.path).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val matrix = Matrix().apply { when (orientation) {
            2 -> postScale(-1f, 1f); 3 -> postRotate(180f); 4 -> postScale(1f, -1f)
            5 -> { postRotate(90f); postScale(-1f, 1f) }; 6 -> postRotate(90f)
            7 -> { postRotate(-90f); postScale(-1f, 1f) }; 8 -> postRotate(-90f)
        } }
        val oriented = if (orientation == 1) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        var working = oriented
        if (crop) {
            val edge = minOf(oriented.width, oriented.height)
            working = Bitmap.createBitmap(oriented, (oriented.width - edge) / 2, (oriented.height - edge) / 2, edge, edge)
        }
        val ratio = minOf(1f, 1024f / maxOf(working.width, working.height))
        val scaled = Bitmap.createScaledBitmap(working, (working.width * ratio).toInt().coerceAtLeast(1), (working.height * ratio).toInt().coerceAtLeast(1), true)
        val opaque = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply { drawColor(Color.BLACK); drawBitmap(scaled, 0f, 0f, null) }
        val directory = File(if(durable)context.filesDir else context.cacheDir,if(preview)"artist-previews" else if(durable)"artwork-overrides" else "embedded-art").apply{mkdirs()}
        val temp = File.createTempFile("image-", ".tmp", directory)
        try {
            temp.outputStream().use { check(opaque.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
            val hash = MessageDigest.getInstance("SHA-256").digest(temp.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            val target = File(directory, "$hash.jpg")
            if (!target.exists()) check(temp.renameTo(target)) { "Cannot save artwork" }
            return target.absolutePath
        } finally {
            temp.delete()
            listOf(decoded, oriented, working, scaled, opaque).distinct().forEach { if (!it.isRecycled) it.recycle() }
        }
    }
}
