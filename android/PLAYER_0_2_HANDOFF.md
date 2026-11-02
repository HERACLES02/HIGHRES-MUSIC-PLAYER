# Local Music 0.2.0: folders, artwork, lyrics and editing

First phone report received: folder scanning, paused session restoration, playback
controls and one VERIFIED FLAC write are recorded. The user subsequently confirms
Undo restored the original. Visual/background and interrupted-write recovery
acceptance remain separate. See [phone review](player/PHONE_RESULTS_0_2.md).

Install `releases/local-music-0.2.0.apk` as an **update to Local Music**.
Checksum, signing and build evidence: [verification](player/VERIFICATION_0_2.md).
Do not uninstall or clear its data. Music USB Probe remains a separate app.
Direct USB stays gated in Local Music; no transport/clocking acceptance changed.

## Implemented

- Persisted system-folder grants for internal/SD music and recursive folder scans.
  Real folder groups, MediaStore/document alias deduplication using local paths
  and content stamps, retained UUIDs/history/favorites for missing storage.
  Uncertain matches are skipped and counted, never silently merged; an interactive
  moved-file reconciliation screen is still future work.
- WorkManager runs cancellable scans. Metadata/artwork caches skip unchanged
  sources; optional automatic indexed-music rescanning defaults off. DataStore
  persists scanning and System/Light/Dark/AMOLED appearance choices.
- TagLib reads embedded metadata, source properties and covers. Coil displays
  cached covers. Local image selection previews center cropping and resizing
  (maximum 1024 pixels), with app-only use or a separate embedding preview.
- Lyrics from embedded text, neighboring LRC in granted folders, paste/import
  and explicit LRCLIB search. Multiple versions remain; explicit choice wins,
  then the current sidecar, then embedded lyrics. Selected results cache privately.
  Highlighting uses service-observed engine position, tap-to-seek, track timing
  adjustment and manual timestamp insertion. Original text stays unchanged.
  Lyrics export uses the system save picker; choose the music folder and matching
  basename to create a neighboring sidecar. Word-level Lyricsfile YAML is not
  implemented; ordinary LRC/plain-text/instrumental results are supported.
- Single, selected-song and album tag editing, up to 50 files per operation.
  Only checked fields change; empty checked values explicitly clear a field.
  App-only overrides remain distinct from source-file metadata.
- Embedded edits for MP3, FLAC, M4A, Vorbis and Opus after local preservation tests.
  WAV and other containers remain read-only for embedded edits. Preparation creates
  complete private originals and edited copies, verifies source tags, unrelated
  mapped tags/artwork and encoded audio/configuration before offering Write.
- Source full-file hashes bind the preview. SQLite FULL-synchronized journals
  precede provider overwrite; saved files are reopened and checked. No atomic
  replacement is assumed. Providers must support seekable read/write access and
  destination-space checks; unsupported providers fail without a planned overwrite.
- Originals remain at least seven days after completion. Startup maintenance
  expires completed backups, preserving pending/recovery operations. Undo refuses
  newer file changes. Interrupted-write recovery compares originals/staged hashes
  and offers guarded restoration of the retained original.
- Files being edited cannot start playback. A current file closes for edits and
  reopens at its logical position paused. Reports now include scan/edit results,
  paused-session restore evidence and available own-app process-exit reasons.

Music files change only after the user reviews a prepared file preview and presses
Write (plus Android authorization where required). LRCLIB sends a query only when
Search is pressed; there are no library-wide online lookups or automatic metadata
writes. Rate limits and selected-result provenance are retained. No account,
analytics, backend, microphone or all-files permission is used.

## Local checks and physical limits

Build: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build-player.ps1`.
Existing core: 8,681 assertions; source identity: 18; guarded write/patch: 21;
Room: 71; provider fixtures: 24. Native preservation tests cover seven generated
cases across five containers, including FLAC 24/96 and ALAC, multi-artists,
multiline comments, all nine fields, non-front artwork, encoded packet equality,
payload SHA-256/configuration fingerprints and five malformed-container rejections.
There were no live lyrics-provider calls in these fixture tests.

TagLib 2.3.2/utfcpp sources, licenses and checksums are pinned. Native writer/parser
changes invalidate preservation evidence. Android NDK/CMake tools and dependency
checksums are checked. Room v3 adds only extras and edit journals; frozen v1/v2
tables are unchanged, and an installed-v2 migration is exercised on real SQLite.
The existing debug certificate preserves preview updateability; final release-key
and app-data backup handoff remains future work. No commit/push was performed.

No 0.2.0 phone/UI/provider acceptance is claimed. Native metadata/IO and lifecycle
behavior need the checks below; prior diagnostic/0.1 results remain separate.
App-data backup, playlists, processing controls, widget, gapless transitions and
artist-information lookups remain later work. Unindexed/unusual provider files
can require manual rescanning. Search/network errors retain selected lyrics.

## Short phone check

1. Update **Local Music**, then choose **Settings → Choose music folder / SD card**.
   Choose a Music subfolder and grant access. Wait for scanning to finish. Check
   real folder groups, covers, no duplicate songs, favorites and the paused session.
2. Play a familiar song and check embedded/neighboring lyrics. Try line seeking,
   timing adjustment, paste/import and selecting a cached version offline. Optional:
   Search LRCLIB, preview a matching recording and save only your chosen version.
3. Start with a **copy of one music file** for the first embedding test. More →
   Details → Edit tags → check one field → Preview. Compare app-only use with
   Embed in files. Review the actual file preview before Write; allow Android's
   per-file permission when asked. Verify the result, then Settings → Edits and
   recovery → Undo. Leave backups/app data intact until acceptance is complete.
4. Try a local cover in app-only mode, then a separate embedding preview on the
   test copy. Batch/album edits can follow after the single-file path works.
5. Check a few minutes of locked-screen playback, Pause/Play, unplug/reconnect
   remaining paused, and paused restore after dismissing/reopening the app.
   Calls, revoked grants and SD removal still need targeted physical acceptance.
6. Save **Settings → Save playback report** as `local-music-0.2-check.json` with
   observations. No repeat of the hour-long USB test is needed.
