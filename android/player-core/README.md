# Offline player foundations

Latest local evidence: [VERIFICATION.md](VERIFICATION.md) records 8,681 Kotlin
assertions, four SQLite schema tests, existing parser regressions and Android lint.

Pure Kotlin/JVM module for the future `com.localmusic.player` application. The
diagnostic `:app` does **not** depend on it. Nothing here replaces the installed
APK, creates an audio engine, contacts a provider, requests storage access or
changes the running USB test. Kotlin remains pinned at 2.3.10; the JVM plugin is
provided by the existing Kotlin Gradle plugin classpath. Four compiler scripting
components of that exact version have SHA-256 entries in Gradle verification
metadata. No runtime library beyond Kotlin/JDK is introduced.

Run from `D:\music_player` after the existing local tools are installed:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-player-core.ps1
```

The script runs the Kotlin `selfTest` harness and SQLite schema tests offline.
It does not build or install an APK. `:player-core:check` also runs the Kotlin
harness. Existing USB checks stay separate.

## Listening behavior

`ListeningQueue` is pure command state for a single future service coordinator.
The service must translate its selections into one engine's load/seek/play commands,
send position observations into `updatePosition`, and persist snapshots regularly
and on transitions. No UI player, wall-clock playback timer or second audio owner
is created here. Engine failures, focus loss and detach must be handled by that
coordinator; callers pause the queue and must not automatically play on reconnect.

- A fresh `start` captures the supplied order and starts at zero. Only a caller
  intentionally supplying `resumeMs` restores a saved position. `ResumeRules`
  rejects completed/end-within-five-seconds positions; Continue requires >15 s.
- Pause/Play preserves position. Previous after 3 s restarts; at/before 3 s it
  follows actual playback history. At the beginning of history it restarts the
  current item. It does not invent a previously unheard album song, even though
  the older web player has that fallback; the accepted Android plan requires
  actual history.
- Play Next prepends an entry and overrides forward history. Add to Queue appends
  after existing manual items, retaining forward-history replay. Manual items
  leave the collection frontier intact. Every occurrence has a UUID, so duplicate
  playlist/manual entries survive. Sequence changes require explicit commands;
  browsing an unrelated list cannot mutate the captured sequence.
- Shuffle consumes each collection occurrence once per cycle. If a manual item
  matches an unheard shuffle occurrence, one occurrence is consumed from that
  suffix, following the web regression scenario. Additional explicit manual
  duplicates remain valid. Repeat All creates another cycle and avoids a same-track
  shuffle boundary where alternatives exist; automatic Repeat One repeats current,
  while explicit Next can skip it. Repeat Off stops at exhaustion.
- Snapshot v1 includes collection/order/frontier, history, manual entries, heard
  identities, repeat/shuffle and position/completion. Restore copies state and
  always pauses. Unknown versions, bad indices, duplicate/conflicting identities
  or oversized snapshots fail before mutating live state. Track unavailability
  does not erase saved references: the future coordinator must surface it.

`QueueSnapshot` is an in-memory persistence contract, **not yet a JSON codec or a
Room repository**. Snapshot checks exercise object round trips, not process-death
or phone persistence. A serializer must validate UUIDs, enums and limits before
constructing it. History is bounded to 1,000 entries within a listening snapshot;
durable listening history belongs in its own table.

## Lyrics

`LrcParser` retains original text and metadata, supports BOM/CRLF, one-to-three
fractional digits, multiple leading timestamps, global file offset and sorted
lines. Unknown/malformed time tags remain text with bounded warnings; malformed
offsets retain the previous valid value. Oversized input fails explicitly rather
than silently discarding the rest. Empty timed lines can clear a previous lyric;
same-time translations remain separately highlightable.

`LyricsTimeline` takes **engine position** for highlighting and line-seek targets,
including backward seeks; it has no independent timer. Positive LRC `[offset:]`
makes lines earlier; a positive app timing adjustment deliberately delays them.
The two remain separate and the original text is not rewritten. This LRC
convention is also documented by the [paroles author](https://github.com/Clarkkkk/paroles#api).
Manual timestamp insertion uses millisecond engine position, not an adjusted
display time. Empty/plain/synchronized and explicitly instrumental states are
distinct. Enhanced word-level LRC and online/provider lookup are not implemented.

`LyricsSelection` chooses explicit selection, then neighboring LRC, then embedded
lyrics; it does not delete alternatives. `SavedLyrics` holds the per-track
adjustment and provenance. Android sidecar discovery, tag extraction, interactive
highlighting and export remain integration work.

## Data/storage contract

`Library.kt` supplies UUID track/playlist/entry IDs, source URI and content stamps,
availability, metadata/technical properties, folder grants, selected lyric
versions, artwork and provider identity records. `SourceIdentity` resolves known
source URIs to existing IDs; any moved-file candidate requires confirmation.
Missing/removed storage changes availability, not playlist/history identity.

[`schema/v1.sql`](schema/v1.sql) is an executable proposed **Room v1 schema**,
currently tested with Python's SQLite. It is not an installed Android database
or an exported Room schema. Its contracts include:

- Separate track sources/URI aliases and folder grants; repeat playlist entries
  have their own primary key. Explicit playlist deletion can cascade its entries;
  removing a referenced track is restricted.
- Favorites, per-track resume state, durable history and a singleton versioned
  session snapshot. Restore has no persisted autoplay flag.
- App-only artwork and independent lyric versions with a per-track selection;
  foreign keys reject selecting another track's lyrics.
- Artist/release provider identity mapping, with polymorphic local UUID ownership
  to be checked by the future repository when artist/release entities are added.
- Edit journals bind previews to content stamps and retain backup/staged paths,
  verification state, hashes and recovery details. Incomplete records have no
  automatic expiry. SQL alone does not implement a safe editing transaction.

The future Room implementation must export schema v1, add explicit migrations for
later versions and never use destructive fallback. Existing v1 fixture changes
before integration are design changes, not migrations of a released database.
Once integrated, freeze it and add migration fixtures that retain user data.
MediaStore/document aliases must be reconciled in one repository transaction;
cross-provider aliases are not inferred from filenames or MediaStore IDs. File
identity ambiguity must never silently merge tracks. Folder grants are permission
evidence, not a guarantee that an exported URI will work after restore.

Preferences will use DataStore: output mode, appearance and processing settings
stay separate from library rows. Direct processing restrictions, Media3 adapter,
WorkManager scans, file editing, actual Room persistence, provider caching and
the daily-use screens are still outstanding. No daily-use player APK is produced
by this module.
