package com.localmusic.player

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.security.MessageDigest

private const val REMOTE_PATH = "Fixture\\peer-tone.flac"
private const val CONTROL_PORT = 45416
private const val FILE_PORT = 45417
private const val TIMEOUT_MS = 10_000
internal const val MAX_FIXTURE_BYTES = 64L * 1024 * 1024
internal val fixtureHashPattern = Regex("[0-9a-f]{64}")

internal fun privatePeerAddress(value: String): InetAddress {
    val octets = value.split('.').map { it.toIntOrNull() ?: -1 }
    require(octets.size == 4 && octets.all { it in 0..255 } && octets.joinToString(".") == value) {
        "Enter the peer's private IPv4 address."
    }
    require(value == "127.0.0.1" || octets[0] == 10 ||
        (octets[0] == 172 && octets[1] in 16..31) || (octets[0] == 192 && octets[1] == 168)) {
        "Enter a private LAN address or 127.0.0.1 for USB forwarding."
    }
    return InetAddress.getByAddress(octets.map(Int::toByte).toByteArray())
}

internal fun InputStream.matchesFixture(sha256: String): Boolean {
    val input = DataInputStream(this)
    val magic = ByteArray(4)
    try { input.readFully(magic) }
    catch (_: EOFException) { return false }
    if (!magic.contentEquals("fLaC".toByteArray())) return false
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(magic)
    var size = magic.size.toLong()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        size += count
        if (size > MAX_FIXTURE_BYTES) return false
        digest.update(buffer, 0, count)
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    return actual == sha256
}

internal class ControlledPeer(host: String) {
    private val peer = privatePeerAddress(host)

    fun fetch(staged: File, sha256: String, onProgress: (received: Long, total: Long) -> Unit) {
        require(fixtureHashPattern.matches(sha256))
        if (staged.isFile && staged.inputStream().use { it.matchesFixture(sha256) }) return

        Socket().use { control ->
            control.connect(InetSocketAddress(peer, CONTROL_PORT), TIMEOUT_MS)
            control.soTimeout = TIMEOUT_MS
            ServerSocket().use { listener ->
                listener.reuseAddress = true
                listener.bind(InetSocketAddress(control.localAddress, FILE_PORT), 1)
                listener.soTimeout = TIMEOUT_MS
                val output = DataOutputStream(control.getOutputStream())
                output.frame(byteArrayOf(1) + wireString("local-music-fixture") + wireString("P") + intBytes(0))
                output.frame(intBytes(43) + wireString(REMOTE_PATH))

                val offer = DataInputStream(control.getInputStream()).frame()
                check(offer.int == 40 && offer.int == 1) { "Expected a Soulseek upload offer." }
                val token = offer.int
                check(offer.string() == REMOTE_PATH) { "The peer offered a different file." }
                val size = offer.long
                check(size in 4..MAX_FIXTURE_BYTES && !offer.hasRemaining()) { "Invalid fixture size." }
                output.frame(intBytes(41) + intBytes(token) + byteArrayOf(1))

                listener.accept().use { transfer ->
                    check(transfer.inetAddress == peer) { "The file connection came from a different address." }
                    transfer.soTimeout = TIMEOUT_MS
                    val input = DataInputStream(transfer.getInputStream())
                    val init = input.frame()
                    check(init.get().toInt() == 1 && init.string() == "fixture-uploader" &&
                        init.string() == "F" && init.int == 0 && !init.hasRemaining() &&
                        Integer.reverseBytes(input.readInt()) == token) { "The file connection did not match the offer." }
                    DataOutputStream(transfer.getOutputStream()).writeLong(0)
                    FileOutputStream(staged).use { destination ->
                        var remaining = size
                        val buffer = ByteArray(64 * 1024)
                        onProgress(0, size)
                        while (remaining > 0) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                            check(count >= 0) { "The peer disconnected before the fixture finished." }
                            destination.write(buffer, 0, count)
                            remaining -= count
                            onProgress(size - remaining, size)
                        }
                        destination.fd.sync()
                    }
                }
            }
        }
        check(staged.inputStream().use { it.matchesFixture(sha256) }) { "The downloaded fixture failed SHA-256 verification." }
    }
}

private fun intBytes(value: Int) = ByteBuffer.allocate(4).order(LITTLE_ENDIAN).putInt(value).array()

private fun wireString(value: String): ByteArray {
    val bytes = value.toByteArray(Charsets.UTF_8)
    return intBytes(bytes.size) + bytes
}

private fun DataOutputStream.frame(payload: ByteArray) {
    write(intBytes(payload.size))
    write(payload)
    flush()
}

private fun DataInputStream.frame(): ByteBuffer {
    val size = Integer.reverseBytes(readInt())
    check(size in 1..4096) { "Invalid Soulseek frame length." }
    val payload = ByteArray(size)
    readFully(payload)
    return ByteBuffer.wrap(payload).order(LITTLE_ENDIAN)
}

private fun ByteBuffer.string(): String {
    val size = int
    check(size in 0..remaining()) { "Invalid Soulseek string length." }
    val bytes = ByteArray(size)
    get(bytes)
    return bytes.toString(Charsets.UTF_8)
}
