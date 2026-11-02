package com.localmusic.core

/** The journal must be durable before overwrite. A target represents one opened, seekable identity. */
interface GuardedWriteTarget : AutoCloseable {
    fun hash(): String
    fun overwrite(staged: java.io.File)
}
interface EditProofs {
    fun backupHash(): String
    fun stagedHash(): String
    fun openTarget(): GuardedWriteTarget
    fun reopenHash(): String
    suspend fun journal(phase: String, message: String)
}
object VerifiedWrite {
    suspend fun commit(original: String, stagedHash: String, staged: java.io.File, proofs: EditProofs) {
        require(original.matches(Regex("[a-f0-9]{64}")) && stagedHash.matches(Regex("[a-f0-9]{64}")))
        check(proofs.backupHash() == original) { "Original backup changed; no write performed" }
        check(proofs.stagedHash() == stagedHash) { "Prepared copy changed; preview again" }
        var overwriting = false
        try {
            proofs.openTarget().use { target ->
                check(target.hash() == original) { "Source changed after preview; no write performed" }
                proofs.journal("WRITING", "Backup verified; source overwrite starting")
                overwriting = true
                target.overwrite(staged)
            }
            check(proofs.reopenHash() == stagedHash) { "Saved file verification failed; original backup retained" }
            proofs.journal("VERIFIED", "Saved file reopened and verified")
        } catch (e: Exception) {
            proofs.journal(if (overwriting) "NEEDS_RECOVERY" else "PREPARED", e.message ?: "Write failed")
            throw e
        }
    }
}
