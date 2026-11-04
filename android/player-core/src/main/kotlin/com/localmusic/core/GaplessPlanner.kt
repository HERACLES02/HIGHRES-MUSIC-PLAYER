package com.localmusic.core

data class GaplessPlan(val before: QueueSnapshot, val after: QueueSnapshot, val next: Selection) {
    fun matches(snapshot: QueueSnapshot): Boolean = before.copy(positionMs=0) == snapshot.copy(positionMs=0)
}
object GaplessPlanner {
    fun prepare(snapshot: QueueSnapshot): GaplessPlan? {
        val preview = ListeningQueue()
        if (!preview.restore(snapshot)) return null
        val next = preview.next(automatic = true) ?: return null
        return GaplessPlan(snapshot, preview.snapshot(), next)
    }
}
