# Local Music 0.3.0 local verification

Command: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build-player.ps1`.
Result: BUILD SUCCESSFUL in 1 min 45 s, offline with strict dependency/source/tool
verification, run three times green for the data suite during this increment.

Scope of this version: saved-playlist management (Step 2 of the continuation
plan). Room schema is unchanged at version 3 — only new queries were added to
the existing DAO, so the frozen-schema verification passes with no migration.

## Feature code in this increment

- New pure core `player-core/.../Playlists.kt`: normalized entry
  append/remove/move over occurrence IDs, `requireName` (trim, 200-character
  limit after UI capping), `MAX_ENTRIES = 5000`.
- Repository ops in `player-data` sharedMain: `playlists()`, single-transaction
  `createPlaylist(name, trackIds)`, `renamePlaylist`, `deletePlaylist`,
  `addPlaylistEntries`, `removePlaylistEntry`, `movePlaylistEntry`; all
  multi-row mutations stay inside one IMMEDIATE writer transaction.
- New DAO queries only (playlists summary, entryCount, playlist/entry deletes);
  frozen schema untouched.
- Seven service commands in `PlaybackService.kt`
  (PLAYLIST_CREATE/RENAME/DELETE/ADD/REMOVE/MOVE/START) inside the existing
  trusted-command boundary; playlist writes no longer wait behind the scan's
  `operationMutex`.
- `PlaybackAuthority.start` gained an optional shuffle flag; PLAYLIST_START
  captures the occurrences that are actually `AVAILABLE` at press time, in
  rank order, and addresses a start entry by occurrence EntryId, not index.
  Playlist playback starts fresh (`resume = 0`).
- `MusicStore`: `LibraryState.playlists` summary list, a light
  `refreshPlaylists()` that re-reads only playlist summaries, and the existing
  revision reload key.
- `MainActivity.kt`: a Playlists tab (six-item bottom navigation), detail view
  for saved order, create/rename/delete dialogs, add via song multi-select and
  the details dialog, per-occurrence row actions (remove/reorder/play-from-here),
  stale-detail protection when switching playlists, and name length capping.

## Checks actually run today

- `player-core`: 8,703 core assertions (including 22 new pure playlist-rule
  checks), 18 scan-identity checks, 21 edit-transaction/patch checks.
- `player-data`: 92 real Room/SQLite persistence assertions on disk SQLite with
  generated DAOs — including the new playlist suite (create with/without songs,
  duplicate occurrences with distinct ranks and identities, occurrence remove/
  move/rename, single-transaction create-with-songs, reopen persistence,
  delete-isolation, and rejection cases for an empty name and missing
  playlist/occurrence) — plus 24 LRCLIB fixture-transport assertions.
- Frozen schema verification: 3 frozen hashes; v1/v2 tables unchanged; v3 adds
  only `library_extra` and `file_edit`; installed-v2 fixture migrates.
- `git diff --check`: no whitespace errors (pre-existing LF/CRLF notices only).
- Repeated data-suite runs: three consecutive green runs after the last fix.

## Test-harness change to know about (honest note)

While bringing the suite green, a stale expectation in the new playlist suite
("Delete affects only that playlist" written before a third playlist was added)
failed, but its real error was masked by the cleanup code raising a Windows
file exception because the open Room connection kept the temporary database
locked. The harness was hardened so this cannot recurse:
`PersistenceChecks.main` now records and reports the primary failure before
cleanup, and cleanup retries deletion briefly and lists leftover files instead
of silently masking. The stale expectation was corrected; the product playlist
code was never the cause. "Room persistence checks passed: 92 assertions" is
the plain pass marker.

## APK

`releases/local-music-0.3.0.apk`, 48,934,599 bytes, versionCode 3,
`0.3.0-playlists`, `com.localmusic.player`, min/target API 30/35, built from
`android/.tools/debug.keystore` as before (update-compatible with 0.2).
SHA-256:
`3d53b9545311145e15db9e63a5975958c74bb75db8c247909aaab577863ff4d7`.
Verified APK signatures: v1 false, v2 true, v3/v3.1/v4/SourceStamp false,
one signer (same scheme result as previous previews).

Review diff artifact: `android/playlist-increment.diff` (UTF-8, includes the
new untracked `Playlists.kt` as a labeled new-file section).

## Not claimed

- No phone acceptance for playlists yet (create/rename/delete, duplicates,
  reorder, play captured order, missing-storage behavior on device).
- No gapless, backup/restore, history, sleep-timer, Direct USB or release
  signing work happened in this increment.
- Deferred review notes (non-blocking, for a later increment): rotation-save
  behavior in playlist dialogs, bottom-bar fit on small screens with six tabs,
  `android/README.md` version notes, play-from-here on an unavailable row falls
  back to the top instead of the next available occurrence, the row Play button
  gating on count rather than readiness, the global revision bump causing a
  cheap lyrics re-query, a repository-level rollback assertion for create-with-
  songs, and `savePlaylist` not applying `requireName` (it has no callers).

No commit/push; unrelated Flask work, reports and keys preserved.
