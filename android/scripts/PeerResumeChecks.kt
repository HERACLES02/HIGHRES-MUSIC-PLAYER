import com.localmusic.player.ControlledPeer
import java.io.BufferedReader
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

fun main(args: Array<String>) {
    val directory = Files.createTempDirectory("peer-resume-checks").toFile()
    try {
        val fixture = File(directory, "original.flac")
        val changed = File(directory, "changed.flac")
        val staged = File(directory, "download.part")
        val bytes = "fLaC".toByteArray() + ByteArray(16_380) { (it % 251).toByte() }
        fixture.writeBytes(bytes)
        changed.writeBytes(bytes.copyOf().apply { this[lastIndex] = 0 })
        check(!changed.readBytes().contentEquals(bytes))
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        withPeer(args.single(), fixture, "--truncate") { log ->
            val result = runCatching { ControlledPeer("127.0.0.1").fetch(staged, digest) { _, _ -> } }
            check(result.isFailure) { "The interrupted transfer reported success." }
            check(log.readLine() == "Requested offset 0")
            check(log.readLine().contains("from offset 0"))
        }
        val partial = staged.readBytes()
        check(partial.size == bytes.size / 2 && partial.contentEquals(bytes.copyOf(partial.size)))

        withPeer(args.single(), changed) { log ->
            val result = runCatching { ControlledPeer("127.0.0.1").fetch(staged, digest) { _, _ -> } }
            check(result.exceptionOrNull()?.message?.contains("different fixture") == true) {
                "The peer's same-size replacement was not rejected at the offer: ${result.exceptionOrNull()}"
            }
            check(log.readLine().startsWith("Transfer stopped:"))
            check(staged.readBytes().contentEquals(partial)) { "The replacement changed the saved partial." }
        }

        withPeer(args.single(), fixture) { log ->
            var firstProgress: Long? = null
            ControlledPeer("127.0.0.1").fetch(staged, digest) { received, _ ->
                if (firstProgress == null) firstProgress = received
            }
            check(log.readLine() == "Requested offset ${partial.size}")
            check(log.readLine().endsWith("from offset ${partial.size}")) { "The peer did not receive the saved offset." }
            check(firstProgress == partial.size.toLong())
            check(staged.readBytes().contentEquals(bytes)) { "The resumed file differs from the fixture." }
        }
        println("Peer checks passed: interruption retained bytes, changed offer rejected, and exact offset resumed.")
    } finally {
        directory.deleteRecursively()
    }
}

private fun withPeer(script: String, fixture: File, vararg flags: String, checkTransfer: (BufferedReader) -> Unit) {
    val process = ProcessBuilder("python", script, fixture.path, *flags).redirectErrorStream(true).start()
    try {
        val log = process.inputStream.bufferedReader()
        val first = log.readLine()
        check(first?.startsWith("Listening on ") == true) { first + "\n" + log.readText() }
        check(log.readLine().startsWith("Fixture SHA-256: "))
        checkTransfer(log)
    } finally {
        process.destroyForcibly().waitFor()
    }
}
