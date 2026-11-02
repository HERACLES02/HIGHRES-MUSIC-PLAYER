package com.localmusic.player

import java.io.InputStream
import java.io.ByteArrayOutputStream

fun readLimited(input: InputStream, maximum: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(32768)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        require(output.size().toLong() + n <= maximum) { "File exceeds the supported size limit" }
        output.write(buffer, 0, n)
    }
    return output.toByteArray()
}
