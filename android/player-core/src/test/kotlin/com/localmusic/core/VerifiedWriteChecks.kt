package com.localmusic.core

import java.io.File
import kotlin.coroutines.*

fun verifiedWriteChecks() {
    var count = 0
    fun expect(ok: Boolean) { count++; check(ok) { "Verified write check $count failed" } }
    fun sync(block: suspend () -> Unit): Throwable? {
        var done = false; var error: Throwable? = null
        block.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { done = true; error = result.exceptionOrNull() }
        })
        check(done); return error
    }
    val original = "a".repeat(64); val staged = "b".repeat(64)
    class Proof(var current: String = original) : EditProofs {
        var backup = original; var prepared = staged; var writes = 0; var open = 0
        var failWrite = false; var failReopen = false; var failJournal = false
        val phases = mutableListOf<String>()
        override fun backupHash() = backup
        override fun stagedHash() = prepared
        override fun openTarget(): GuardedWriteTarget {
            open++
            return object : GuardedWriteTarget {
                override fun hash() = current
                override fun overwrite(staged: File) {
                    check(phases.last() == "WRITING"); writes++; current = "partial"
                    if (failWrite) error("Interrupted provider write")
                    current = prepared
                }
                override fun close() = Unit
            }
        }
        override fun reopenHash() = if (failReopen) "different" else current
        override suspend fun journal(phase: String, message: String) { if (phase == "WRITING" && failJournal) error("Journal disk full"); phases += phase }
    }
    val normal = Proof()
    expect(sync { VerifiedWrite.commit(original, staged, File("unused"), normal) } == null)
    expect(normal.current == staged && normal.phases == listOf("WRITING", "VERIFIED") && normal.writes == 1)
    val changed = Proof("newer file")
    expect(sync { VerifiedWrite.commit(original, staged, File("unused"), changed) } != null)
    expect(changed.writes == 0 && changed.current == "newer file" && changed.phases == listOf("PREPARED"))
    val badBackup = Proof().apply { backup = "changed" }
    expect(sync { VerifiedWrite.commit(original, staged, File("unused"), badBackup) } != null)
    expect(badBackup.open == 0 && badBackup.writes == 0)
    val badStage = Proof().apply { prepared = "changed" }
    expect(sync { VerifiedWrite.commit(original, staged, File("unused"), badStage) } != null)
    expect(badStage.open == 0 && badStage.writes == 0)
    val interrupted = Proof().apply { failWrite = true }
    expect(sync { VerifiedWrite.commit(original, staged, File("unused"), interrupted) } != null)
    expect(interrupted.current == "partial" && interrupted.backup == original && interrupted.phases.last() == "NEEDS_RECOVERY")
    val reopened = Proof().apply { failReopen = true }
    expect(sync { VerifiedWrite.commit(original, staged, File("unused"), reopened) } != null)
    expect(reopened.phases.last() == "NEEDS_RECOVERY" && reopened.backup == original)
    val journal = Proof().apply { failJournal = true }
    expect(sync { VerifiedWrite.commit(original, staged, File("unused"), journal) } != null)
    expect(journal.writes == 0 && journal.current == original)
    val tags = MusicTags("Old", listOf("A", "B"), album = "Keep", comment = "Keep comment", trackNumber = 2)
    expect(TagPatch(mapOf("TITLE" to "New")).apply(tags) == tags.copy(title = "New"))
    expect(TagPatch(mapOf("COMMENT" to "")).apply(tags).comment == null)
    expect(TagPatch(emptyMap()).apply(tags) == tags)
    expect(TagPatch(mapOf("ARTIST" to "বাংলা\nSinger 🎵")).apply(tags).artists == listOf("বাংলা", "Singer 🎵"))
    expect(runCatching { TagPatch(mapOf("TRACKNUMBER" to "-3")) }.isFailure)
    expect(TagPatch(mapOf("COMMENT" to "Line one\nLine two")).valuesFor("COMMENT") == listOf("Line one\nLine two"))
    expect(TagPatch(mapOf("ARTIST" to "Singer A\r\n Singer B ")).apply(tags).artists == listOf("Singer A", "Singer B"))
    println("Verified edit transaction and patch checks passed: $count assertions.")
}
