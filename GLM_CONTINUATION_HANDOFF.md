# Local Music: context and continuation plan for GLM / OpenCode

Prepared 3 November 2026 from the local checkout at `D:\music_player`.
This is an Android-first handoff. Read the startup prompt at the end.

## 1. What the user is building

A standalone, offline-first Android music player for personal use on an
**OPPO A9 2020 / CPH1937, Android 11 / API 30**, with a **KZ Acoustics M2
USB-C DAC/cable**. Phone firmware in the reviewed reports: `CPH1937_11_F.17`.
The app must work without the PC or a Flask server.

The intended finished app combines:

- A modern local library: songs, albums, artists, real folders, favorites,
  playlists, search, sorting, artwork and a usable queue.
- Reliable background playback and notification/lock-screen/media controls.
- Correct listening behavior, persistent sessions and explicit Resume.
- Local/synchronized lyrics, manual editing, explicit online search and caching.
- Safe tag and embedded-cover editing with preview, verification, Undo and recovery.
- Optional artist biographies/images and release-artwork matching, initiated by
  the user and cached for offline use.
- Separate **Normal** and **Direct USB** playback modes. Direct is an eventual
  high-resolution goal, with hardware feasibility and safety gates still open.
- A signed personal APK, update-safe storage, backup/restore and a simple guide.

The user is a coding beginner. Explain changes in plain language, give exact
PowerShell commands and short numbered phone checks. Finish one useful increment
at a time. Do not make the user repeat tests whose results are already accepted.

## 2. Three separate applications in this repository

| Project | Location / package | Current role |
| --- | --- | --- |
| Local Music | `android/player`, `com.localmusic.player` | Actual standalone Android app; continue here for daily-use features |
| Music USB Probe | `android/app`, `com.localmusic.diagnostics` | Separate hardware diagnostic app; experimental native Direct USB fixture transport |
| Flask web player | Root Python files, `static`, `templates` | Existing desktop/browser player; preserve its unrelated local work |

Do not replace the Android app with a WebView, Flask wrapper or a new framework.
Do not confuse diagnostic version 0.10.0 with player version 0.2.0.

## 3. Verified checkout and working-tree state

At preparation:

- Branch: `main`.
- HEAD: `712686f4ec60765b86292a11a8da8058315b0254`.
- Latest commit: `Add native player with folders, lyrics and safe metadata editing`.
- Earlier commits: `7f6ad37` player foundations/background diagnostics;
  `30c8e25` native USB diagnostics; `28c78a0` original Flask player.
- The working tree is **not clean**. Recheck before changing anything.

Existing tracked modifications are `README.md`, `app.py`, `library.py`,
`playlist.py`, `static/app.js`, `static/sw.js`, `templates/index.html`, and
`tests/conftest.py`. Existing untracked items include `.vscode`, `LEARNING.md`,
web management/queue/session modules and tests, and phone JSON reports.
These are pre-existing work, not disposable files.

Never use `git reset --hard`, `git clean`, blanket checkout/restore, or blanket
staging to make this checkout clean. Do not commit/push unless requested. If later
requested, stage only reviewed files, preserve other work, use the configured
`HERACLES02` identity, omit AI attribution and never force-push.

## 4. Current Android player: Local Music 0.2.0 → 0.3.0

Source: `android/player/build.gradle.kts`.

- Version after the Step 2 increment: `0.3.0-playlists`, version code `3`
  (was `0.2.0-library-lyrics`, version code `2`).
- Minimum API 30; target API 35; compile SDK 36.
- Compose/Material 3, Media3 1.8.0, Kotlin 2.3.10, AGP 8.13.2,
  Gradle 8.13, JVM target 17.
- Room 2.8.4, bundled SQLite 2.6.2, DataStore, WorkManager, Coil.
- Native TagLib 2.3.2/utfcpp with pinned sources and preservation evidence.
- Native tags ABIs: `arm64-v8a`, `armeabi-v7a`.
- NDK `27.2.12479018`, CMake `3.22.1`.

Use the checked-in versions. Do not upgrade dependencies casually or regenerate
verification metadata merely to make an error disappear.

### Implemented in 0.2.0 (untouched phone-accepted baseline)

- Home, Library, Now Playing, Queue, Settings, details and a persistent mini-player.
- MediaStore music discovery plus user-picked, persisted folder grants and
  recursive folder scanning; real directory groups; cancellable WorkManager scans.
- App UUID track identities, source aliases, content-stamp checks and conservative
  deduplication. Missing storage retains identities/favorites/session references.
  Ambiguous matches are skipped/counted; interactive moved-file reconciliation
  is not implemented.
- Metadata and cover caching, local cover selection/crop/resize, themes
  System/Light/Dark/AMOLED, optional indexed-music rescan defaulting off.
- Service-owned ExoPlayer, listening queue, MediaLibrarySession and controller.
  Session save/restore, favorites and Continue positions.
- Local embedded/neighboring LRC lyrics, paste/import/export, explicit LRCLIB
  search/preview/choice, alternative lyric versions and offline selected caching.
  Engine-position highlighting, tap-to-seek and timing adjustment.
- Single/selected/album tag editing, maximum 50 files per operation; only checked
  fields change. App-only changes remain distinct from embedding into music files.
- Guarded embedded editing for MP3, FLAC, M4A, Vorbis and Opus. WAV and other
  unverified containers remain read-only for embedding.
- Complete private original/staged copies; hashes bind the reviewed preview;
  audio/configuration and supported unrelated metadata/artwork checks; durable
  journals before overwrite; reopened saved-file verification and per-file results.
- Conflict-checked Undo, retained originals, recovery inspection/restoration and
  at least seven-day retention of completed-operation originals. Pending/recovery
  operations must not expire automatically.
- Playback/edit exclusion: close the edited current source and reopen paused at
  its logical position. Never silently resume audio after editing.
- Explicit playback report export, scan/edit summaries and available process-exit data.

Music files change only after the user reviews the prepared preview and presses
Write, with Android write authorization when needed. A readable URI is not proof
of write permission. No atomic replacement is assumed across document providers.

### 0.3.0-playlists: Step 2 increment, coded and locally verified (Nov 2026)

Saved-playlist management is implemented end to end and locally verified, but
NOT yet phone-accepted. Details in `android/player/VERIFICATION_0_3.md`; review
diff artifact in `android/playlist-increment.diff` (UTF-8, includes the new
`Playlists.kt`).

- Pure core: `Playlists.kt` (append/remove/move by occurrence EntryId,
  `requireName`, 200-char names, 5000-entry cap). Room schema stays version 3:
  only DAO queries were added, no migration.
- Repository/service/UI: create/list/rename/delete, add songs including
  duplicates, remove/reorder one occurrence, single-transaction
  create-with-songs, persistence across reopen, delete isolation; PLAYLIST_START
  captures the currently AVAILABLE occurrences in rank order (occurrence-ID
  addressing, optional shuffle, plays fresh). Playlist writes no longer wait
  behind the scan's `operationMutex`; UI detail rows survive playlist switches.
- A DeepSeek diff review (session `ses_e5eb404d0ffeOyFrNYjxRy7I4p`, re-run after
  fixes; earlier session `ses_e5eeb8c9fffelnex0nbLdn4251` contained the first
  findings) confirmed seven flagged fixes (availability filtering at playlist
  start, stale-detail rows, `operationMutex` scope, occurrence-ID addressing,
  single-transaction create, light `refreshPlaylists()`, name capping) and
  found no remaining blockers.

Checks actually run: 8,703 core assertions (22 new playlist-rule checks),
92 Room/SQLite persistence assertions plus 24 fixture-transport assertions,
frozen-schema verification, `git diff --check` clean. APK:
`android/releases/local-music-0.3.0.apk`, versionCode 3, SHA-256
`3d53b9545311145e15db9e63a5975958c74bb75db8c247909aaab577863ff4d7`, v2
signature verified, update-compatible with the installed 0.2 preview.

A test-harness note worth remembering: an early failure presented as a mystery
"playlists.db-wal locked" on Windows was actually a stale expectation failing
while its error was masked by the old cleanup code (the open Room connection
legitimately keeps the temp DB locked after an unhandled failure).
`PersistenceChecks.kt` now reports the primary failure before cleanup retries
deletion; do not reintroduce a masking finally block.

Deferred (non-blocking, review findings #8-#10 plus re-review notes): rotation
safety in playlist dialogs, six-tab bottom-bar fit on small screens,
`android/README.md` version notes, play-from-here on an unavailable row falls
back to the top rather than the next available occurrence, Play button gating
on count not readiness, global revision bump re-queries lyrics panels cheaply,
repository-level rollback assertion for create-with-songs, and
`savePlaylist(name, entries)` not applying `requireName` (no callers).

### Important gaps

- Saved playlists are implemented but still await phone acceptance (0.3.0);
  see the Step 2 outcome above.
- No complete durable listening-history feature/UI, app-data backup/restore,
  sleep timer, widget, processing controls or artist-biography/release-artwork lookup.
- Gapless transitions are not implemented: the coordinator currently loads songs
  individually. Do not call ordinary sequential playback gapless.
- Enhanced word-level Lyricsfile YAML is not implemented.
- Direct USB is intentionally unavailable in Local Music.
- Final personal release signing/key-backup handoff remains future work.

Inspect current source before implementing any item. Historical READMEs contain
older lists that incorrectly describe already completed 0.2 features as future work.

## 5. What has actually passed, and what has not

### Player local evidence recorded by the previous build

`android/player/VERIFICATION_0_2.md` records:

- 8,681 existing core assertions, 18 source-identity checks and 21 guarded-write/
  patch checks; 71 real Room/SQLite assertions; 24 lyrics-provider fixture checks.
- Seven native generated preservation cases across five containers, including
  FLAC 24/96 and ALAC, encoded packet/audio fingerprints, Unicode, multiple artists,
  mapped unrelated tags/artwork and malformed-input rejection.
- Frozen Room schemas verified, installed-v2 to v3 migration exercised;
  assembly/signature checks; lint 0 errors and 23 warnings.

These are **previous recorded results**, not tests rerun for this document.
The handoff preparation inspected source/docs/Git and recomputed the APK hashes.
It did not build, change app code, contact lyrics services or run phone tests.

### Player phone evidence already received

Read `android/player/PHONE_RESULTS.md` and `PHONE_RESULTS_0_2.md`.
The 0.2 report `local-music-normal (1).json` records:

- 875 track identities, one folder grant, 1,738 source entries checked,
  zero reported read failures and zero uncertain matches.
- A restored session paused at 4,014 ms with autoplay false.
- FLAC playback at stereo/44.1 kHz decoder input, pause/resume and seeks,
  no retained playback errors.
- One FLAC edit in VERIFIED state after reopening the saved source.
- The user subsequently explicitly confirmed that **Undo restored the original**.
  Treat that as accepted user evidence; do not demand the same basic Undo test
  again merely because this older export lacks an UNDONE event.

This does not establish every format/provider, complete deduplication, visual
lyrics/artwork quality, live lyrics matching/offline cache acceptance, interrupted
write recovery, long screen-off playback in this app, calls, or storage-removal
behavior. All retained 0.2 report samples were interactive=true.
Probe tests do not automatically validate the separate player.

### Existing APKs, checked during handoff preparation

| Artifact | SHA-256 |
| --- | --- |
| `android/releases/local-music-0.2.0.apk` | `dd48f7f9e81b49d352456d7d9bc7c8d3765f478561bc839bff14522e6af5d5b0` |
| `android/releases/local-music-0.3.0.apk` (Step 2 increment) | `3d53b9545311145e15db9e63a5975958c74bb75db8c247909aaab577863ff4d7` |
| `android/releases/music-usb-probe-0.10.0.apk` | `9925348200ea86b4a0ad528a33e3856bd660924d5cc3f73ac08d87fb737ed744` |

The preview uses `android/.tools/debug.keystore`. Preserve it privately and
preserve the application ID so installs update the existing player. Never advise
uninstalling/clearing data to work around a signature or migration problem.
Generated APKs, caches, toolchains and keys are ignored by Git.

## 6. Direct USB: substantial progress, still experimental

The diagnostic app is `0.10.0-media3-direct`, separate from Local Music.
Its native transport is fixture-oriented, not a finished arbitrary-song engine.

M2 evidence: USB `31b2:0311`, UAC1, playback interface 2, asynchronous OUT
endpoint `0x04`, full-speed physical link in native reports, stereo 16-bit/2-byte
or packed 24-bit/3-byte PCM at 44.1/48/88.2/96 kHz. No advertised 32-bit/192 kHz
playback. Playback Feature Unit 7 exposes hardware channel volume controls.
No associated explicit feedback endpoint is advertised.

Already established in the diagnostic app:

- Native connection/interface ownership, guarded hardware volume SET/readback,
  format control/readback, cleanup and restoration have physical evidence.
- App-side PCM checks and short transport evidence cover all eight advertised
  depth/rate combinations; user confirmed the six later listening runs normal,
  retaining the known approximate-label issue and balance finding.
- A 61-minute 24/96 run completed with no recorded PCM/USB errors, restored
  settings and no unexpected glitches noticed by the user. Many screen-off
  intervals were observed; a continuously locked hour was not established.
- Pause/resume/seek/Stop and detach/reconnect behavior were checked. Detach
  stopped audio without speaker fallback or automatic reconnect playback.
- The 0.10 diagnostic's lock-screen controls are user-confirmed working; its
  report corroborates Media3 Pause/Resume/Stop and clean restoration.

Open gates:

- Clock synchronization is not justified by nominal packet pacing and successful
  counters. Missing explicit feedback still requires engineering investigation.
- Slight left weakness/low-level audibility is reported in both routes in some
  comparisons, with a later high-rate comparison sounding balanced in Normal and
  slightly left-quiet in Direct. Cause is unresolved; no alternative compatible
  earphones were available. Do not invent channel swap/gain compensation.
- DAC/internal DSP, actual rendered PCM and physical bus integrity are not proven
  by app-side hashes or a device-rate display. Do not claim unconditional bit-perfect.
- Focus/call behavior and final integrated-engine acceptance need targeted checks.
- The source-position section label can differ from heard output. The user's
  approximate 0.2-second observation is not a measured fixed hardware latency.

Do not request another hour-long run or repeat accepted notification tests as a
default next step. Do not enable Direct in the actual player merely because the
diagnostic streams audio. Continue useful Normal-mode work while keeping this gate
explicit. Do not claim Stage A fully passed or roll back to saying nothing exists.

Useful evidence: `android/ENDURANCE_PHONE_RESULTS.md`, `FORMAT_PHONE_RESULTS.md`,
`TRANSPORT_CONTROLS_PHONE_RESULTS.md`, `DETACH_PHONE_RESULTS.md`,
`MEDIA3_PHONE_RESULTS.md`, `CHANNEL_PHONE_RESULTS.md`, `HIGH_RATE_PHONE_RESULTS.md`.
`STAGE_A.md` retains many chronological checkpoints; newest acceptance supersedes
older pending statements. Original JSON reports must remain unchanged.

## 7. Source map: where to work

All paths below are relative to `android/`.

| File/module | Responsibility |
| --- | --- |
| `player/.../MainActivity.kt` | Compose screens, document pickers, controller commands |
| `player/.../FeaturePanels.kt` | Lyrics/search/editor, artwork/tag previews, edit history UI |
| `player/.../PlaybackService.kt` | MediaLibraryService/session, trusted controllers, app commands |
| `player/.../PlaybackAuthority.kt` | Sole audio engine owner, queue transitions, focus/detach, position/reporting |
| `player/.../NormalSessionPlayer.kt` | SimpleBasePlayer adapter over that same authority |
| `player/.../MusicStore.kt` | App storage/state, single session-save writer, preferences, scan scheduling |
| `player/.../LibraryScanner.kt`, `LibraryScanWorker.kt` | MediaStore/SAF discovery, aliases, scanning |
| `player/.../FileEdits.kt` | Prepared writes, permissions/provider checks, verify/Undo/recovery |
| `player/.../NativeTags.kt`, `NativeMetadata.kt`, `LocalArtwork.kt` | JNI tags, mapped metadata, local covers |
| `player-core/src/main/kotlin/com/localmusic/core/ListeningQueue.kt` | Pure listening order, manual queue, history, shuffle/repeat |
| `player-core/src/main/kotlin/com/localmusic/core/Playlists.kt` | Pure saved-playlist rules: occurrence append/remove/move, name limits |
| `player-core/.../Lyrics.kt`, `Library.kt`, `ScanIdentity.kt`, `TagPatch.kt`, `VerifiedWrite.kt` | Pure domain rules and safety models |
| `player-core/src/main/java/com/localmusic/core/AudioPayloadDigest.java` | Encoded audio/configuration fingerprints |
| `player-data/src/commonMain/kotlin/com/localmusic/data/` | Room entities/DAO/database/migrations |
| `player-data/src/sharedMain/kotlin/com/localmusic/data/` | Repository, session codec, extras, LRCLIB client |
| `native-tags/` | Native metadata reader/writer, vendored sources/licenses/evidence |
| `app/`, `usb-core/`, `native-usb/` | Separate diagnostic UI/service, descriptor parser, native USB engine |

`player/...` abbreviates `player/src/main/java/com/localmusic/player/`.
For the other abbreviated Kotlin paths, use `rg --files` to locate the file.

Playback shape: Compose UI -> MediaController -> PlaybackService ->
PlaybackAuthority/ListeningQueue -> one ExoPlayer. NormalSessionPlayer publishes
the same state to Media3. MusicStore/PlayerRepository persist it. Preserve that
single authority; do not create another player or independent UI playback clock.
Current source observes engine position every 250 ms while playing and 1 s paused;
session snapshots are requested about every 10 s and on transitions.

Room is **version 3**, with explicit 1->2 and 2->3 migrations and 13 entities.
Old module READMEs still describe earlier v1/v2 scopes. Existing schema locks,
exports and migrations must remain unchanged; add a new migration only when needed.
Never use destructive fallback. The old `player-core/schema/v1.sql` is a historical
design fixture, not the current Room schema or an installed-database migration.

## 8. Behavior that must survive every increment

1. Browsing/searching does not replace the listening sequence.
2. Fresh song taps start at zero; Pause/Play preserves position; Continue is an
   explicit separate action. Restore always starts paused.
3. Previous after three seconds restarts; otherwise retraces actual listening history.
4. Manual queue entries have occurrence IDs, allow duplicates and outrank automatic
   next-up without destroying the underlying collection frontier.
5. Shuffle consumes a sequence without early repeats; repeat/manual/history rules
   remain consistent. Preserve bounded limits and explicit errors.
6. Focus loss/output removal pauses. Reconnect, focus gain, startup, scan completion
   and edit completion must not automatically start audio.
7. Missing files/grants do not silently erase tracks, playlists, favorites or sessions.
   Ambiguous identity reconciliation requires a user choice.
8. Original lyrics/provenance and alternatives remain. Use engine position for
   highlighting and seek; chosen version wins, then sidecar, then embedded.
9. Online search is explicit; no library-wide scraping, automatic uploads or
   automatic metadata writes. Retain source attribution/cache and provider limits.
10. Never weaken music-file write journals, verification, preview/Write separation,
    backup retention, conflict detection, permission checks or recoverability.

## 9. Ordered continuation plan

This is a proposed work sequence, not evidence that its steps are already done.
Implement one bounded increment, validate it, update this document and hand it off.

### Step 1: establish the baseline and finish the short 0.2 acceptance gaps

Read the current source and documents; inspect Git before editing. Build the
existing player once using its established script. Report any real failure rather
than changing dependency locks/tool versions without investigation.

Use a short checklist for remaining phone observations:

- Covers render correctly; local/sidecar lyrics highlight and line-seek correctly.
- If the user wants online lyrics: explicit search, choose the right recording,
  then confirm that chosen result works offline. Do not call the provider silently.
- Several minutes of locked-screen playback in **Local Music**, notification
  Pause/Play, detach/reconnect staying paused, reopen restoring paused.
- A practical focus/call check and missing/revoked/removed-storage check.
- Recovery of an interrupted edit only with disposable copied music and a
  controlled test plan; do not kill an actual write against the user's originals.

Do not repeat the accepted basic FLAC playback/edit/Undo or the probe's endurance
and notification tests. Existing 0.2 APK can cover unchanged behavior; a new APK
is needed only for an actual code change. Phone observations may arrive later;
continue independent Normal-mode features without pretending those gates passed.

### Step 2: recommended next coding increment -- saved playlists

Finish an end-to-end vertical feature using the existing UUID models and Room
transactional playlist support. Read `PlayerRepository.savePlaylist/playlist`,
the DAO and queue model before adding new storage.

Deliver:

- List/create/rename/delete named playlists, with clear empty states.
- Add current/selected songs to a chosen playlist; allow repeated song occurrences.
- Playlist details, remove a particular occurrence, reorder entries and persist order.
- Play from a selected occurrence; shuffle a captured playlist sequence using the
  existing queue rules. Browsing or editing the saved list does not silently change
  a currently captured listening sequence.
- Missing tracks remain visible/actionable and recoverable after storage returns.
- UI actions use the existing authorized command/storage boundary; no new audio owner.

Acceptance: duplicate entries survive restart; reorder/remove target occurrence
IDs rather than every matching track; delete affects only that playlist; failed
writes do not partially erase a list; playlist playback has the expected order;
restart remains paused. Reuse existing tables where possible. Add focused pure/
repository tests for new operations and migration tests if a schema change is needed.

### Step 3: queue editing and daily-use basics

Split this into separately reviewable increments so queue correctness is settled
before adding more controls:

1. Add pure `ListeningQueue` commands for removing one manual occurrence, moving
   an occurrence, and clearing manual entries. Decide and document what happens
   when the active occurrence is removed and what Clear Queue means while a song
   is playing. Preserve the active song/position unless the user explicitly asks
   to stop it. Keep occurrence IDs so duplicate tracks remain independently
   movable/removable. Reject invalid indices without partially changing state.
2. Add invariant-focused cases for active/inactive removal, duplicate tracks,
   Play Next order, forward history, shuffle frontier, repeat modes, snapshot and
   restore, empty queue, and bounded size. Then wire these commands through the
   existing service custom-command boundary and UI. Update the session snapshot
   after each successful operation; show a visible error on failed persistence.
3. Test service restart with reordered/removed entries, and verify that ordinary
   browsing does not mutate the captured listening collection. Ensure notification
   media controls and external Media3 clients cannot invoke app-only queue edits.
4. Add a sleep timer as its own service-owned feature: define end-of-track versus
   immediate stop, countdown visibility, cancel/reset behavior, process/service
   lifetime, and paused playback behavior before coding. Use monotonic elapsed
   time, not wall-clock time; service cleanup cancels it. Do not create an
   Activity-owned timer. Exercise expiry while foreground, background and paused.
5. Make one user-visible large-library/accessibility improvement per increment,
   based on current source and actual pain points: readable loading/error/empty
   states, useful scan progress/cancel, touch targets and screen-reader labels.
   Keep database/scan work off the UI thread and avoid rendering the whole library.

Acceptance: removal/reorder targets one occurrence; queue commands preserve
history, Play Next, shuffle/repeat and the active position according to the
documented rules; all state restores paused; sleep timer behaves as specified
while the app is backgrounded; controls are usable without hijacking playback.

### Step 4: backup/recovery and storage hardening

Treat this as a data format and recovery project, not “copy the database to a
file.” Do not implement until the user-visible contents and recovery boundaries
are written down.

1. Inventory app-owned data and classify each field as export, regenerate, or
   intentionally omit. Decide whether the snapshot includes saved playlists,
   favorites, app-only tag/artwork overrides, lyric text/selection/adjustment,
   preferences, resumable session and play history. Never include the audio files,
   private edit originals/staged files, credentials or signing key by accident.
2. Specify a versioned archive manifest with format version, per-entry lengths
   and hashes, safe filenames, size limits and supported-version behavior. Reject
   path traversal, duplicate/conflicting IDs, truncated entries and unknown newer
   formats before changing the live database.
3. Export through Android's save picker to a temporary archive and validate it
   before exposing success. Import through the open picker into staging; verify
   every entry and show a plain preview of counts, conflicts and unsupported data.
   Require the user to confirm before applying.
4. Define conflict policy explicitly. Never silently merge two track UUIDs based
   on filename, MediaStore ID or title. Offer skip/keep-existing/manual mapping
   where safe, and identify folders the user will need to grant again. Restored
   URI strings alone do not restore Android permissions.
5. Apply using a transaction or staged replacement with a recoverable checkpoint.
   On failure preserve the pre-import state and retain a clear error/recovery path.
   Test corrupt/truncated archives, unsupported versions, duplicate IDs,
   cancellation, storage loss, low space and simulated failure before commit.
6. Add migration tests only for schema changes; freeze old exports and schemas.
   Separately write a human-readable procedure for preserving the preview signing
   certificate and later release key. Key backup is manual and outside the app
   data archive; never embed or upload private keys.

Acceptance: round-trip a fixture dataset without losing IDs, duplicates, lyrics,
playlist order or selected options; invalid import never damages existing data;
folder permissions are clearly re-requested; the archive cannot be mistaken for
a backup of music files or edit originals.

### Step 5: verified gapless Normal playback

Current `PlaybackAuthority` handles a completed item and loads the next track;
this is not a gapless guarantee. Plan the engine change before modifying queue
semantics:

1. Write down which formats/transitions are in scope and what audible gap is
   acceptable. Begin with two adjacent local lossless files; decide separately
   what happens across unsupported formats, unavailable sources and decode errors.
2. Inspect Media3's current playlist/preload capabilities and the existing
   `NormalSessionPlayer` adapter. Prototype a service-only transition using the
   current `ListeningQueue` as the single order authority. Prevent both ExoPlayer
   auto-transition callbacks and the existing STATE_ENDED handler from consuming
   the next entry twice.
3. Keep the service and MediaSession queue/index/state in agreement with the
   captured collection, manual entries, Play Next, history, shuffle, repeat and
   restore. Browsing a different album must not replace the active sequence.
4. Add local deterministic fixtures with silence/tones around boundaries and
   record engine transition timing. Test normal transition, manual insertion near
   boundary, seek near end, Previous, repeat one/all/off, missing next file, decode
   failure and app pause/restore. Check mixed formats and sample rates explicitly.
5. Install an update on the target phone and listen through repeated album
   boundaries in foreground and screen-off conditions. Automated timing does not
   prove the user's DAC/earphones make the transition inaudible; record those
   observations as phone acceptance separately.

Only claim the tested format/transition scope. Crossfade is a later, optional
Normal-mode feature with its own two-engine/resource policy; it must not be called
gapless and must not be mixed with unchanged-PCM claims.

### Step 6: explicit artist/release-artwork enrichment

Implement this only after local playback/editing remains useful offline. Build a
provider client boundary and fixtures first; at implementation time verify the
official current API terms, attribution requirements, throttling and image rights
for MusicBrainz, Wikimedia/Wikidata and Cover Art Archive.

1. Add an explicit artist-info action and explicit “find cover” action. No startup
lookup, periodic background lookup, library-wide scan or silent request.
2. Search by local metadata, then show multiple candidate artists/releases with
   distinguishing fields, stable provider IDs, image attribution/source and a
   preview. Same-name artists and release editions must remain distinguishable.
3. Save only the user's chosen match and provenance. Allow manual correction and
   a local override; a wrong/no match must not change source tags, artwork or lyrics.
4. Cache selected metadata/images privately with bounded size, expiry/version and
   deletion behavior. Test cache hit/offline display, timeout, retry-after/rate
   limit, malformed responses, oversized images and provider failure.
5. Keep artwork app-only by default. To embed a chosen image in music, use the
   existing separate preview -> write -> verify/undo workflow and supported-file
   safety gates.

Acceptance: search sends only the user-requested query, chosen result is
attributable and corrected without source-file changes, and cached content remains
available offline. Provider tests use fixtures; live lookups are explicit user work.

### Step 7: Direct USB engineering, then integration only if justified

Keep this in its own branch-sized increments, separate from ordinary UI work.
Start with the actual code and newest reviewed reports, then create a short
question/evidence table for clocking, balance, physical format and DAC processing.
For each open question identify a feasible measurement, required equipment/person,
and what the result could establish. If available tools cannot observe a quantity,
say so rather than substituting a USB counter or capability field.

1. Review UAC1 async sink synchronization, endpoint descriptors and current native
   packet scheduler. Determine whether usable explicit/implicit feedback, device
   clock reporting or another standards-based clock signal is available. Do not
   ship nominal pacing as validated just because runs complete.
2. Revisit left/right balance without compensation. Confirm equal PCM fixture and
   equal hardware settings, distinguish the earphone/phone baseline from M2
   playback, and record the limitation if suitable alternate earphones or external
   measurement are unavailable. Never add a unilateral channel gain or swap by
   inference from subjective notes.
3. State separate evidence levels in reports: descriptor-advertised capability,
   requested format, decoded reference PCM, app-submitted PCM, USB transfers,
   Android route/configuration and physically rendered output. Only claim each
   level actually observed.
4. If an engineering path is supported, add pure scheduler tests and fault cases
   before a phone build. Cover fractional packet rates, source cursor across rate
   changes, cancellation/drain, buffer starvation, detach, ownership restoration,
   seek and error recovery. Avoid changing the diagnostic transport while a
   separate player feature is being implemented.
5. Build a stable service-level engine contract for Normal and experimental
   Direct. Integrate arbitrary-file decoding incrementally (WAV then verified
   FLAC) only after the clocking and safety decisions are documented. Preserve a
   single active owner and explicit mode selection; Direct errors must not reroute
   audio or resume playback without a clear user action.
6. Revalidate the combination on the target phone, including supported-rate
   switching, controls, detach, focus/call, screen-off and restoration. The old
   fixture evidence does not transfer automatically to the integrated player.

If gates can be satisfied, implement arbitrary-file decode/stream support and a
common playback-engine contract behind the existing service, then explicit
Normal/Direct transitions: pause, drain/release, acquire, verify, prepare and wait
for user playback intent. One engine at a time; no silent fallback. Handle native
rate changes, cancellation, seek, buffer starvation, unplug, focus and restoration.
Display source/requested/observed transport separately. Do not enable capture/HID
to borrow timing or send undocumented firmware commands.

If the hardware path cannot meet the goal, explain the evidence and discuss the
tradeoff with the user before changing that goal. Do not declare success or remove
Direct from scope on assumption.

### Step 8: personal release

Treat the personal release as a checklist with a release candidate, not a rename
of the debug APK:

1. Agree which planned features and physical gates are in scope. Make a known-
   limitations list, separating supported Normal behavior from any experimental
   Direct claims. Remove UI promises for features that are not complete.
2. Run the documented local build and all included validation. Review the full
   source diff, dependency/license changes, manifest permissions, frozen schemas,
   generated artifacts and signing configuration. Confirm unrelated Flask edits
   remain untouched.
3. Prepare and verify a private release-signing key and a recovery copy before
   using it. Decide the safe update path from debug-signed 0.2 preview to personal
   release; Android cannot update an installed app signed by a different key
   without a deliberate migration plan. Never silently replace the existing key.
4. On the OPPO, test update over existing data, library/playlist/session retention,
   restart, restore, folder-grant behavior, notification and locked-screen audio,
   detach/reconnect, call/alarm interruption, low storage and relevant audio
   formats. Test SD/revoked access if in actual use. Do not clear app data as a
   routine test shortcut.
5. Produce the APK, SHA-256 checksum, build/version record, plain install/update
   steps, user guide, backup/restore procedure and known limitations. Keep keys,
   real music, private reports and private backups out of the deliverable.
6. Deliver a locally reviewable artifact. Publishing, paid services, purchases,
   account changes and public distribution need a separate explicit request.

Release acceptance: the intended phone installs/updates with its data preserved,
the signed artifact and checksum match the record, recovery instructions are
usable, and all advertised claims have corresponding evidence.

## 10. Exact local commands

Run in PowerShell from the repository root:

```powershell
cd D:\music_player
git status --short
git log -4 --oneline
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build-player.ps1
```

The player build script checks pinned native tools/sources/tag evidence, runs
core and Room checks, builds/lints, verifies frozen schemas and the APK signature,
and writes `android/releases/local-music-<version>.apk` and its `.sha256` file
(currently `local-music-0.3.0.apk`). For a future version, intentionally update
version metadata and output names together. Do not overwrite a historical
verification note as if it tested new code; `VERIFICATION_0_2.md` and
`VERIFICATION_0_3.md` each document their own build.

Focused player checks:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-player-core.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-player-data.ps1
python android\scripts\verify-player-data.py
git diff --check
```

Do not run the full build plus these same focused suites repeatedly without a
new change/failure that justifies it. Inspect `build-player.ps1` for included checks.

Native tag writer/parser changes invalidate prior preservation evidence. Inspect
`scripts/test-tag-preservation.py` and `verify-tag-evidence.py`, the host tag-check
tool and local FFmpeg path before regenerating fixtures/evidence. Do not bless new
hashes without preservation tests. Never run these edits on the user's real library.

Only when changing the USB diagnostic:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build.ps1
```

Additional targeted suites exist: `test-core.ps1`, `test-native.ps1`,
`test-stream.ps1`, `test-decode.ps1`, `test-direct-state.ps1`, and fixture checks.
Inspect script scope; run suites appropriate to changed transport/control/decoder code.
Do not change transport source just to satisfy a UI task.

Tooling is project-local under `android/.tools`, with `android/local.properties`.
Normal player builds are offline. A fresh Git clone lacks ignored toolchains,
fixtures, APKs and signing key. If these are missing, follow `android/README.md`
bootstrap guidance and preserve/update-signing compatibility; never silently
generate a different key over the existing one. Downloads/paid services require
their applicable authorization. Do not upload keys, private reports or music files.

Flask is a separate project: root `.\run.ps1`, `http://127.0.0.1:5000`,
Python tests and `node --test tests/*.test.cjs`. It has no Node app build step.
Use its `README.md`/`LEARNING.md` if specifically asked to work on it.

## 11. Documents to read, in this order

1. This handoff, then `android/PLAYER_0_2_HANDOFF.md`.
2. `android/player/PHONE_RESULTS_0_2.md` and `VERIFICATION_0_2.md`.
3. Relevant current source files from the map above.
4. `ANDROID_PLAN.md` for the long-term product requirements, not current completion.
5. Relevant specific USB result files if touching Direct; latest acceptance takes
   precedence over older checkpoint paragraphs in `README.md`/`STAGE_A.md`.

Prefer current source and specific latest reviewed results to broad historical
future-work lists. Preserve a distinction between implemented, locally tested,
user-observed and independently measured. A blank JSON note does not cancel a
documented later user confirmation.

## 12. Copy-paste startup prompt for OpenCode

```text
Continue my existing Local Music Android app in D:\music_player.
Read GLM_CONTINUATION_HANDOFF.md first, then VERIFICATION_0_3.md and the
0.2 handoff, phone results and verification documents it names. Inspect
current source and git status before editing; preserve all pre-existing
Flask changes, untracked files, original phone reports and signing keys.

I am a coding beginner. Explain the result plainly, include exact PowerShell
commands and short phone checks. Do not rebuild the project from scratch.

The actual player is android/player (com.localmusic.player), currently
0.3.0-playlists with saved playlists coded and locally verified but not
yet phone-accepted. android/app is the separate USB diagnostic. Direct USB
remains gated by unresolved clocking/balance findings; keep developing
Normal-mode features. Do not repeat accepted probe endurance/lock-screen
tests, the basic FLAC write/Undo already confirmed by me, or the 0.2
playback checks.

First install/check the latest 0.3.0 APK on my phone and walk me through a
short saved-playlist phone acceptance (create with songs incl. duplicates,
rename, reorder one occurrence, delete, play captured order with restart
paused, missing file keeps placeholders). Then implement the next bounded
coding increment (Step 3 part 1: queue removal/reorder of one occurrence
with pure ListeningQueue commands first). Do not treat any queue work as
done before its engine/capture rules are reviewed.

Use focused regression tests, add migrations only if genuinely needed,
never alter frozen schemas or weaken write/recovery safeguards, and build
an update-compatible preview APK with checksum when the increment is ready.
Document changed files, checks actually run, remaining phone acceptance
and the next task. Do not commit/push, publish, purchase, upload private
files, clear app data or change signing identity without my instruction.

Work on this one increment rather than implementing the entire roadmap
in one pass. If a physical check is unavailable, report it honestly and
continue independent local work without claiming it passed.
```

Use the same prompt and handoff when switching models. Keep one active code
editor at a time and review its diff before another model starts making changes.
