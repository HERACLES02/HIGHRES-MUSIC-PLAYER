package com.localmusic.core

import kotlin.random.Random

enum class RepeatMode { OFF, ALL, ONE }
data class QueueEntry(val id: EntryId, val trackId: TrackId)
data class Selection(val entry: QueueEntry, val positionMs: Long)
data class QueueSnapshot(val version: Int = 1, val collection: List<QueueEntry>, val order: List<QueueEntry>,
    val cursor: Int, val history: List<QueueEntry>, val historyCursor: Int, val manual: List<QueueEntry>,
    val heard: Set<EntryId>, val shuffle: Boolean, val repeat: RepeatMode, val positionMs: Long,
    val completed: Boolean)

object ResumeRules {
    fun position(savedMs: Long, durationMs: Long?, completed: Boolean = false): Long = when {
        completed || savedMs < 0 -> 0
        durationMs != null && (durationMs <= 0 || savedMs >= (durationMs - 5000).coerceAtLeast(0)) -> 0
        else -> savedMs
    }
    fun isContinueCandidate(savedMs: Long, durationMs: Long?, completed: Boolean = false): Boolean =
        savedMs > 15000 && position(savedMs, durationMs, completed) == savedMs
}

/** Pure command state, owned by the future playback service. No audio resources or timer. */
class ListeningQueue(private val random: Random = Random.Default) {
    private var collection = emptyList<QueueEntry>()
    private var order = emptyList<QueueEntry>()
    private var cursor = -1
    private var history = emptyList<QueueEntry>()
    private var historyCursor = -1
    private var manual = emptyList<QueueEntry>()
    private var heard = emptySet<EntryId>()
    var shuffle = false; private set
    var repeat = RepeatMode.OFF
    var positionMs = 0L; private set
    var completed = false; private set
    var isPlaying = false; private set
    val current: QueueEntry? get() = history.getOrNull(historyCursor)
    val selection: Selection? get() = current?.let { Selection(it, positionMs) }
    val upcomingManual: List<QueueEntry> get() = manual.toList()
    val listeningCollection: List<QueueEntry> get() = collection.toList()

    fun start(tracks: List<TrackId>, selectedIndex: Int, shuffled: Boolean = false,
              resumeMs: Long = 0, durationMs: Long? = null): Selection {
        require(selectedIndex in tracks.indices)
        collection = tracks.map { QueueEntry(EntryId.new(), it) }
        val selected = collection[selectedIndex]
        shuffle = shuffled
        order = if (shuffle) listOf(selected) + collection.filter { it.id != selected.id }.shuffled(random) else collection.toList()
        cursor = order.indexOf(selected); history = listOf(selected); historyCursor = 0
        manual = emptyList(); heard = setOf(selected.id)
        positionMs = ResumeRules.position(resumeMs, durationMs); completed = false; isPlaying = true
        return selection!!
    }

    fun pause() { isPlaying = false }
    fun play(): Selection? {
        if (completed) { positionMs = 0; completed = false }
        isPlaying = current != null
        return selection
    }
    fun updatePosition(valueMs: Long) { require(valueMs >= 0); positionMs = valueMs }
    fun playNext(track: TrackId) {
        manual = listOf(QueueEntry(EntryId.new(), track)) + manual
        history = history.take(historyCursor + 1) // explicit priority over forward history
    }
    fun addToQueue(track: TrackId) { manual = manual + QueueEntry(EntryId.new(), track) }

    /** Removes exactly one pending occurrence; a matching duplicate of the same song remains.
     *  Pending entries only: the active occurrence always lives in playback history, so this can
     *  never stop or replace the current song. Unknown identities are rejected without change. */
    fun removeOccurrence(occurrence: EntryId) {
        require(manual.any { it.id == occurrence }) { "That queued entry no longer exists." }
        manual = manual.filterNot { it.id == occurrence }
    }

    /** Moves one pending occurrence to its new next-up position (0 = play next, size - 1 = last);
     *  others keep their relative order and every occurrence keeps its identity. */
    fun moveOccurrence(occurrence: EntryId, index: Int) {
        require(index in manual.indices) { "That queued entry changed. Reload the queue and try again." }
        val from = manual.indexOfFirst { it.id == occurrence }
        require(from >= 0) { "That queued entry no longer exists." }
        val target = manual[from]
        val others = manual.filterNot { it.id == occurrence }
        manual = others.take(index) + target + others.drop(index)
    }

    /** Explicit clear of pending next-up entries. Safe while playing or paused: the active song,
     *  its position, recent/forward history and shuffle state are untouched, and the audible
     *  collection frontier continues afterward. */
    fun clearManualEntries() { manual = emptyList() }

    fun setShuffle(enabled: Boolean) {
        if (shuffle == enabled) return
        shuffle = enabled
        val prefix = order.filter { it.id in heard }
        val remaining = collection.filter { it.id !in heard }
        order = prefix + if (enabled) remaining.shuffled(random) else remaining
        cursor = prefix.lastIndex
    }

    fun previous(): Selection? {
        if (current == null) return null
        if (positionMs <= 3000 && historyCursor > 0) --historyCursor
        positionMs = 0; completed = false
        return selection
    }

    /** Automatic completion honors Repeat One; an explicit Next skips it. */
    fun next(automatic: Boolean = false): Selection? {
        if (current == null) return null
        if (automatic && repeat == RepeatMode.ONE) {
            positionMs = 0; completed = false
            return selection
        }
        if (historyCursor < history.lastIndex) {
            ++historyCursor; positionMs = 0; completed = false
            return selection
        }
        val candidate = if (manual.isNotEmpty()) {
            val item = manual.first(); manual = manual.drop(1)
            if (shuffle) {
                val duplicate = order.drop(cursor + 1).firstOrNull { it.trackId == item.trackId }
                if (duplicate != null) { order = order.filter { it.id != duplicate.id }; heard = heard + duplicate.id }
            }
            item
        } else {
            if (cursor + 1 >= order.size) {
                if (repeat != RepeatMode.ALL || collection.isEmpty()) {
                    isPlaying = false; if (automatic) completed = true
                    return null
                }
                val cycle = if (shuffle) collection.shuffled(random) else collection.toList()
                // Avoid repeating a track at the shuffle boundary when alternatives exist.
                val firstDifferent = cycle.indexOfFirst { it.trackId != current?.trackId }
                order = if (shuffle && firstDifferent > 0) cycle.drop(firstDifferent) + cycle.take(firstDifferent) else cycle
                cursor = -1; heard = emptySet()
            }
            order[++cursor].also { heard = heard + it.id }
        }
        history = (history.take(historyCursor + 1) + candidate).takeLast(1000)
        historyCursor = history.lastIndex; positionMs = 0; completed = false
        return selection
    }

    fun snapshot() = QueueSnapshot(collection = collection.toList(), order = order.toList(), cursor = cursor,
        history = history.toList(), historyCursor = historyCursor, manual = manual.toList(), heard = heard.toSet(),
        shuffle = shuffle, repeat = repeat, positionMs = positionMs, completed = completed)

    /** Reject malformed/unknown versions without partially changing the live state. Missing tracks remain referenced. */
    fun restore(saved: QueueSnapshot, currentDurationMs: Long? = null): Boolean {
        if (saved.version != 1 || saved.collection.size > 100000 || saved.order.size > 100000 ||
            saved.history.size > 1000 || saved.manual.size > 10000) return false
        val ids = saved.collection.map { it.id }
        val idSet = ids.toSet()
        val byId = saved.collection.associateBy { it.id }
        val orderIds = saved.order.map { it.id }
        val orderSet = orderIds.toSet()
        val pendingIds = saved.manual.map { it.id }.toSet()
        val identityMap = mutableMapOf<EntryId, TrackId>()
        val conflictingIdentity = (saved.collection + saved.history + saved.manual).any {
            val prior = identityMap.put(it.id, it.trackId)
            prior != null && prior != it.trackId
        }
        if (saved.positionMs < 0 || ids.size != idSet.size || conflictingIdentity ||
            orderIds.size != orderSet.size || !idSet.containsAll(orderIds) ||
            saved.order.any { byId[it.id] != it } ||
            idSet.any { it !in orderSet && it !in saved.heard } ||
            saved.cursor !in -1..saved.order.lastIndex || saved.historyCursor !in -1..saved.history.lastIndex ||
            (saved.history.isNotEmpty() && saved.historyCursor < 0) ||
            saved.heard.any { it !in idSet } || pendingIds.size != saved.manual.size ||
            pendingIds.any { it in idSet } || saved.history.any { it.id in pendingIds }) return false
        collection = saved.collection.toList(); order = saved.order.toList(); cursor = saved.cursor
        history = saved.history.toList(); historyCursor = saved.historyCursor; manual = saved.manual.toList()
        heard = saved.heard.toSet(); shuffle = saved.shuffle; repeat = saved.repeat
        positionMs = ResumeRules.position(saved.positionMs, currentDurationMs, saved.completed)
        completed = false; isPlaying = false
        return true
    }
}
