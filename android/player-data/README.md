# Room persistence for Local Music

This isolated module connects the Kotlin player models to a real Room database.
It builds Android (minimum API 30) and JVM targets from shared entities, DAOs,
migrations and repository code. **The USB diagnostic app does not depend on it.**
No APK update, phone install, USB/audio call, network lookup or change to the
ongoing endurance test is performed by these persistence tests.

## Implemented

- `PlayerRepository` saves/reloads track metadata and technical properties,
  source URIs/content stamps, folder-grant evidence, playlists with duplicate
  entries, favorites, lyric alternatives/selections/adjustments and sessions.
- Track/playlist/entry IDs remain app UUIDs. Known source URI conflicts abort
  the entire save instead of silently merging track IDs. Unavailable storage
  changes availability and retains playlists/favorites/session references.
- Playlist replacement is one immediate Room transaction: header and all entries
  roll back together if any track/identity constraint fails. Upserts update rows
  without SQLite REPLACE's implicit parent deletion.
- Original lyric text and provenance are immutable for an existing version ID.
  Import different text under a new UUID and explicitly select it; alternatives
  remain. Timing adjustment can change independently. Selection rejects a version
  from another track; no explicit selection falls back to neighboring LRC, then
  embedded lyrics. Instrumental state persists separately.
- Session wire format v1 includes collection/order/history/manual identities,
  position, shuffle/repeat and completion. It has **no autoplay field**. JSON,
  size, UUID, version and queue invariants are validated before restore.
- A session save atomically rotates a valid current copy to previous, writes a
  SHA-256-checked new current copy and updates per-track resume state. Invalid
  current copies do not overwrite a known-good previous generation. Rejected
  originals are also retained in `session_problem` with their error.
- Loading validates the current session and then the previous generation if
  current is damaged. Recovered queues always start **paused**; final-five-second
  and completed positions reset using the known track duration. A missing track
  does not erase references or start another song. Unknown session versions
  return an explicit unavailable state and block overwriting the current copy.
- If neither generation is valid, report unavailable and retain evidence; do not
  silently replace it with an empty session. SQLite/database failures propagate
  to the caller. Cancellation and SQL errors are not mislabeled as corrupt JSON.

There is no audio player inside this module. The future MediaLibraryService
coordinator must call `saveSession` regularly and before/after transitions, handle
errors visibly, and issue playback commands only after explicit user action.
This API does not yet implement that coordinator, screens, DataStore preferences,
WorkManager scanning or audio-focus handling.

## Database versions and recovery limits

Room **v1** is an initial migration test baseline, never installed on the phone.
Room **v2** adds only `session_problem`. Both schemas are exported and pinned in
`schema-lock.json`; `PlayerMigrations.from1To2` is registered in both factories.
The disk migration test creates a real v1 Room database, seeds it, closes it,
opens it with v2 and checks data through generated v2 DAOs. Opening it again
also succeeds. No destructive fallback is configured.

The earlier `player-core/schema/v1.sql` is a historical design fixture, **not**
the Room baseline or a migration input. The current Room scope has 11 tables,
with metadata/technical fields in versioned JSON. Artwork/provider identities,
scan aliases, durable full listening history and file-edit journals remain future
storage integrations; their proposed tables have not been silently created or
claimed as implemented. Future versions must add explicit migrations and tests;
do not change frozen schema bytes or the v1 fixture to hide a migration change.

Both factories are suspend functions; version preflight runs on IO and rejects a
newer on-disk database without deleting it. Tests also reject non-SQLite bytes
without replacing the original. This is **not a repair tool for physical SQLite
corruption**, a music-file backup, or proof of process-kill/power-loss behavior
on the phone. Recovery tests simulate failures before transaction commit and
logical session corruption on the host. Room/SQLite provides transactional disk
behavior; Android storage/process behavior still needs phone integration tests.

Folder grants are saved as evidence only. Restored URIs may still need permission
or a reselected folder. This module neither grants permissions nor embeds tags
or artwork into audio files. Session checksums detect accidental changes; they
are not an authentication mechanism. Recovery records are retained until a future
explicit cleanup/export UI; no automatic deletion of rejected originals occurs.

## Build and test

From `D:\music_player`, using the existing project-local tools:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-player-data.ps1
```

The script runs real disk Room/JVM checks, compiles the Android target, runs its
lint checks and verifies frozen schema hashes, offline after initial dependency
setup. Temporary database files stay under `player-data/build/persistence-tests`;
the test validates its temporary root before cleanup. No APK is built or installed.

Pinned additions: Room **2.8.4**, bundled SQLite **2.6.2**, KSP **2.3.4**, Kotlinx
JSON **1.9.0**. Existing Kotlin **2.3.10**, AGP **8.13.2** and coroutines **1.10.2**
remain. New artifacts have SHA-256 entries in Gradle verification metadata;
generated schema bytes have Git text conversion disabled. KMP Room constructors
produce Kotlin expect/actual beta warnings; those are not suppressed.

Architecture references: [Room KMP setup](https://developer.android.com/kotlin/multiplatform/room),
[Room 2.8 release notes](https://developer.android.com/jetpack/androidx/releases/room#2.8.4),
[SQLite KMP drivers](https://developer.android.com/kotlin/multiplatform/sqlite).
This code uses the pinned Room 2 APIs; the live setup documentation also covers
newer Room 3 APIs, which were not adopted here.
