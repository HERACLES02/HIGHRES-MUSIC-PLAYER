# Local Music 0.4.0 local verification

Command: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build-player.ps1`.
Result: BUILD SUCCESSFUL (final run 1 min 36 s), offline with strict
dependency/source/tool verification, green after the review fixes.

Scope of this version: manual-queue editing (Step 3 part 1 of the continuation
plan). Room schema is untouched at version 3 — queue order already persists
inside the saved session snapshot, so no migration or new tables were needed.
The increment was independently reviewed (below); findings were fixed and the
fixed state is what this document claims.

## Feature code in this increment

- Pure rules in `player-core/.../ListeningQueue.kt`, documented in KDoc:
  `removeOccurrence` (removes one pending occurrence by EntryId; a duplicate of
  the same song keeps its own identity), `moveOccurrence` (retargets one
  occurrence to a new next-up position, 0 = play next; others keep their
  relative order; every identity is preserved), `clearManualEntries` (removes
  pending next-up entries only). Pending manual entries can never be the active
  occurrence (the active one always lives in history), so no edit can stop or
  replace the current song, and clearing while playing is safe. Unknown
  identities and out-of-range targets are rejected with `require` before any
  change (failed edits change nothing).
- Three new service commands in `PlaybackService.kt`
  (QUEUE_REMOVE/QUEUE_MOVE/QUEUE_CLEAR) inside the existing trusted-command
  boundary, mirroring the PLAYLIST_MOVE argument pattern (`entry` string, and
  `index` int for MOVE).
- Three new authority methods in `PlaybackAuthority.kt` (removeFromQueue,
  moveInQueue, clearQueue) following the `enqueue` pattern: `require(ready)`,
  mutate the pure queue, then `persist(); update()`.
- `PlaybackAuthority.extras()` now publishes `manualIds` (occurrence EntryIds,
  one per visible manual track id, same 60-row cap). Existing keys are
  unchanged, so nothing else needed to follow.
- `MainActivity.kt` Queue tab: manual rows are ListItems keyed by their
  occurrence id (an open row menu follows its row when playback shifts rows),
  each with a More menu (Move up / Move down / Remove entry) addressed by
  occurrence id, plus a "Clear queue" button (enabled when ready and the queue
  is non-empty) behind a confirm dialog whose confirm is also gated on ready.
  Move down is disabled at the last visible row so an entry cannot be moved
  past the editable 60-row window. The 60-row cap and the true count are stated
  on screen.

## Independent review and fixes

Reviewer model session `ses_e5e86d3dcffeWFGy4Da3kv7IIo` reviewed
`android/queue-increment.diff` against full source, before any phone use.
Findings and their resolutions:

- BLOCKER (fixed): the "Clear queue" confirm dialog was unreachable dead code —
  its state was declared and rendered but nothing set it. The button now exists
  (and existed nowhere else); re-review confirmed the trigger is single and
  gated.
- MINOR (fixed): with more than 60 entries, Move down on the last visible row
  could move an entry past the editable window. Now disabled there.
- MINOR (fixed): manual queue rows had no LazyColumn keys, so an open menu
  could re-target after rows shifted; rows are now keyed by occurrence id.
- MINOR (fixed): coverage assertions were thinner than claimed — the fuzz now
  proves every step that unknown ids are rejected with the snapshot unchanged,
  the rejection set covers unknown-id/negative-index/re-remove/remove-current/
  out-of-range cases, a move's full kept-identity list is asserted, and the
  disk-reopen test exercises an edited (remove+move) session.

Re-review session `ses_e5e7befa9ffeN49b1rlDI4YgIs` ("Re-review after fixes")
verified all four fixes against current source and found no new issues; verdict
"all fixes confirmed, no new issues". Ruled out by the review (explicitly):
stop/replace of the active song, duplicate identity loss, restore round-trip
breakage, restart retention loss, persist-vs-empty-current races, double-press
mis-edits, external-client invocation of queue commands, and playlist
regressions.

## Checks actually run today

- `player-core`: 10,406 core assertions — including the new invariant checks
  (remove keeps the duplicate and identities and leaves song/position alone;
  rejected edits change nothing; move/clear rules; clear-empty no-op) and the
  extended mixed-command fuzz (10-branch pattern with per-step rejection
  evidence) that must restore round-trip after 100 random steps × 40 seeds.
- `player-data`: 93 real Room/SQLite persistence assertions on disk SQLite —
  now including a queue edited by remove+move surviving reopen — plus 24
  LRCLIB fixture-transport assertions.
- Frozen schema verification: 3 frozen hashes; v1/v2 tables unchanged; v3 adds
  only `library_extra` and `file_edit`; installed-v2 fixture migrates.
- `git diff --check`: no whitespace errors (pre-existing LF/CRLF notices only).

## APK

`releases/local-music-0.4.0.apk`, 49,003,207 bytes, versionCode 4,
`0.4.0-queue-edits`, `com.localmusic.player`, min/target API 30/35, built from
`android/.tools/debug.keystore` as before (update-compatible with 0.3 and
earlier previews).
SHA-256:
`2fb5be3a25646cbbea952a58832ecda90356567946ddcbd4f39dcf470801d47f`.
Verified APK signatures: v1 false, v2 true, v3/v3.1/v4/SourceStamp false,
one signer (same scheme result as previous previews). This checksum supersedes
the two earlier local builds of the same increment.

Review diff artifact: `android/queue-increment.diff` (UTF-8, the eight edited
player files for this increment, regenerated after the review fixes).

## Not claimed

- No phone acceptance for queue editing yet (More-menu remove keeps duplicates,
  move up/down follows the row, clear keeps what is playing, edits survive
  queue re-display, restore after app restart keeps the edited order).
- No repeat/off UI or other Step 3 remainder (part 2 and later features) was
  touched.
- No gapless, backup/restore, history view, sleep-timer, Direct USB or release
  signing work happened in this increment.
- Deferred review notes from 0.3 remain open (non-blocking) and were not worked.

No commit/push; unrelated Flask work, reports and keys preserved.
