package com.localmusic.core

/**
 * Pure sleep-timer rules shared by the service and UI. The clock is injected so tests use
 * an explicit monotonic timeline; the service wiring supplies a monotonic clock only
 * (never wall-clock time), so system clock jumps cannot skip or stretch the countdown.
 *
 * Two firing modes:
 *  - IMMEDIATE: a minute budget (1..480) expires at one fixed deadline; the countdown keeps
 *    running while playback is paused, so expiry does not depend on a song being loaded.
 *  - END_OF_TRACK: one flag consumed at the natural end of the currently playing track.
 *
 * Expiry only requests a pause; it never mutates the queue, history or saved session, and
 * setting a new timer always replaces the previous one. The timer is process-local state:
 * it is never persisted and does not survive an app or service restart.
 */
data class SleepState(val mode: Mode = Mode.OFF, val remainingMs: Long = -1L) {
    enum class Mode { OFF, IMMEDIATE, END_OF_TRACK }
}

class SleepTimer(private val clock: () -> Long) {
    private var deadline = 0L
    private var trackEndArmed = false
    private var ended = false

    val state: SleepState
        get() = when {
            ended || (!trackEndArmed && deadline == 0L) -> SleepState(SleepState.Mode.OFF)
            trackEndArmed -> SleepState(SleepState.Mode.END_OF_TRACK, remainingMs(clock()) ?: -1L)
            else -> SleepState(SleepState.Mode.IMMEDIATE, remainingMs(clock()) ?: -1L)
        }

    fun start(minutes: Int) {
        require(minutes in MIN_MINUTES..MAX_MINUTES) { "Sleep timer is limited to $MIN_MINUTES-$MAX_MINUTES minutes." }
        ended = false; trackEndArmed = false; deadline = clock() + minutes * 60_000L
    }

    fun startEndOfTrack() { ended = false; deadline = 0L; trackEndArmed = true }

    fun cancel() { deadline = 0L; trackEndArmed = false; ended = false }

    /** Remaining countdown in ms for IMMEDIATE (0 once at/past the deadline), or null otherwise. */
    fun remainingMs(now: Long): Long? {
        if (ended || trackEndArmed || deadline == 0L) return null
        return (deadline - now).coerceAtLeast(0)
    }

    /** True exactly once per IMMEDIATE deadline, incl. timers that expired while paused. */
    fun checkExpired(now: Long = clock()): Boolean {
        if (deadline == 0L || trackEndArmed) return false
        if (now < deadline) return false
        deadline = 0L; ended = true
        return true
    }

    /** True if an END_OF_TRACK timer is armed; [consumeTrackEnd] fires it once. */
    fun atTrackEnd(): Boolean = trackEndArmed && !ended
    fun consumeTrackEnd() { trackEndArmed = false; ended = true }

    companion object { const val MIN_MINUTES = 1; const val MAX_MINUTES = 480 }
}
