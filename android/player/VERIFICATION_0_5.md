# Local Music 0.5.0 local verification

Command: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build-player.ps1`.
Result: BUILD SUCCESSFUL in 2 min 16 s, offline with strict
dependency/source/tool verification.

Scope of this version: the service-owned sleep timer with countdown display
(Step 3 item 4 of the continuation plan, Normal Mode only). Room schema is
untouched at version 3; nothing persisted is added or changed — the timer is
process-local by design. No session snapshot or store changes.

## Documented behavior (before coding, unchanged during it)

- Two modes, chosen in the Playing tab's Sleep timer dialog:
  - IMMEDIATE: 15/30/45/60/90/120-minute presets; pausing happens at one fixed
    deadline (monotonic `SystemClock.elapsedRealtime`; 1–480 minute bound).
    The countdown keeps running while paused — expiry does not depend on a
    loaded song. Playing/starting other songs does not reset it.
  - END_OF_TRACK: pauses at the natural end of the track currently playing;
    manual Next/Previous do not consume it; starting another song keeps it
    armed. Unknown through engine STATE_ENDED → automatic advance only.
- Expiry only pauses (with a visible reason string); it does not clear the
  queue, position, history, session or error state. Expiry is single-fire and
  self-inactive afterwards.
- Setting a new timer (either mode) replaces any previous one; an explicit
  Cancel button is in the dialog. Countdown shows on the Playing tab button
  ("Sleep timer · mm:ss left" / "after this track").
- The timer is not persisted: it does not survive process/service restart and
  a restored session starts with no timer (documented in the dialog text and
  UI note); the service destroy path cancels all main-handler work regardless.

## Feature code in this increment

- New pure module `player-core/.../SleepTimer.kt`: `SleepTimer` with an
  injected clock, `SleepState` snapshot, `start` (bounds-checked, reject-
  without-change), `startEndOfTrack`, `cancel`, `remainingMs`, `checkExpired`
  (single-fire, works while paused), `atTrackEnd`/`consumeTrackEnd`.
- Three service commands (SLEEP_SET with `minutes`, SLEEP_END_TRACK,
  SLEEP_CANCEL) in the trusted-command boundary; three authority methods in
  the `enqueue` pattern with `require(ready)` before any mutation.
- Expiry hooks: the service-owned tick (250 ms playing / 1 s paused) checks
  minute deadlines and calls only `pause(message)`; `advance(automatic)` checks
  the end-of-track flag and pauses in place instead of loading the next
  selection (manual skips unaffected).
- `extras()` publishes `sleepMode` and `sleepRemaining` (additive state keys);
  `report()` appVersion updated.
- UI: Playing tab sleep button shows status/countdown; dialog with presets,
  end-of-track, cancel timer; all presses gated on `ready`.

## Checks actually run today

- `player-core`: 10,424 assertions — 18 new sleep-timer invariants: boundary
  minutes (0/481 rejected without change; 1/480 accepted), exact-deadline
  firing (not one millisecond early; single-fire; fires while paused), reset
  replaces previous deadline, cancel-before-expiry inert, end-of-track has no
  minute deadline and ignores minute-expiry checks, track end consumed once,
  minute setting replaces an armed end-of-track flag. Prior invariant suites
  unchanged and green.
- `player-data`: 93 Room/SQLite + 24 fixture assertions (unchanged; no data
  edits).
- Frozen schema verification: 3 frozen hashes pass; v1/v2 unchanged; v3 adds
  only `library_extra`/`file_edit`; installed-v2 fixture migrates.
- `git diff --check`: no whitespace errors (pre-existing LF/CRLF notices).

## APK

`releases/local-music-0.5.0.apk`, 48,034,689 bytes, versionCode 5,
`0.5.0-sleep-timer`, `com.localmusic.player`, min/target API 30/35, built
from `android/.tools/debug.keystore` as before (update-compatible with 0.4
and earlier previews).
SHA-256:
`a8f349fc0ae5fdbb9ffc5b2a9b72844db52e86bb17cde0c617708f57a515973d`.
Verified APK signatures: v1 false, v2 true, v3/v3.1/v4/SourceStamp false,
one signer (same scheme result as previous previews).

Review diff artifact: `android/sleep-increment.diff` (UTF-8, the seven edited
player files plus the new untracked `SleepTimer.kt` as an intent-to-add
section).

## Phone acceptance, 3 November 2026

- User confirms the sleep timer worked on the phone. Marked accepted for this
  increment. No exported diagnostic report or itemized observations accompanied
  the confirmation, so individual timer modes and lifecycle cases are not
  claimed as independently measured.

## Remaining limits

- End-of-track expiry with repeat modes was reasoned through (flag consumes at
  the automatic advance that repeat would otherwise perform) but is not
  separately machine-tested end-to-end on device.
- No repeat/default UI polish (Step 3 item 5, one daily-use improvement per
  increment), gapless, backup/restore, history view, Direct USB or release
  signing work happened in this increment. Deferred 0.3 review notes remain
  deferred.

No commit/push; unrelated Flask work, reports and keys preserved.
