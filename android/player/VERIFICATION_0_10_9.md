# 0.10.9 index scheduling and source-specific diagnostics

Version `0.10.9-index-scheduling`, versionCode 24.

## What the phone report established

The user reports 0.10.8 still pauses on the affected SD-card FLAC. The new
`local-music-normal (1).json` identifies 0.10.8, Android 11 and four seeks with
638-872 ms readiness / 861-1092 ms audio-progress callbacks. Its ready indexes
cannot belong to this 163.385-second song: they have 165/200 points, exceeding
the song's maximum possible 164 at the scanner's minimum one-second spacing.
One entry instead has zero points and `ordinary seeking` after all jobs ended.

The report did not record the current-source association or the fallback
reason. It cannot distinguish queue rejection from a timeout, read failure or
source change. See [the follow-up](SEEK_FOLLOWUP_0_10_8.md) for the exact analysis.

## Confirmed defect and design revision

The pre-fix `flac-queue` regression reproduces a permanent lost request:
prepare three generated files while two slots are occupied, let the earlier
jobs finish, and the third song still has no index. It failed with
`Selected song permanently lost its index request when two slots were occupied`.
The original log is `android/.tools/seek-probe/index-queue-before.log`.

At the user's request, Auxio, Gramophone, VLC and upstream Media3 were examined.
Auxio also forwards seeks to Media3; its service/FFmpeg integration is not a
drop-in fix for our admission bug. The compatible reference is the managed-work,
priority and completion lifecycle in Apache-2.0 Media3 1.8.0 BasePreloadManager:
https://github.com/androidx/media/blob/1.8.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/preload/BasePreloadManager.java

Our coroutine worker adapts that lifecycle to private indexes; it does not
substitute a MediaSource preloader for the service's listening queue. No GPL
source, native decoder, new dependency or version/checksum update was imported.

- Requests are retained separately from worker capacity. One worker performs
  scans serially. Prepared sources no longer lose work because it is occupied.
- PlaybackAuthority explicitly supplies current and lookahead URI interest at
  selection, gapless preparation and crossfade handoff. Obsolete work is cancelled.
  An active lookahead yields to current-song work and remains queued for later.
  The worker joins the cancelled reader before starting its replacement.
- In production the desired set is current + next. An obsolete cancelling reader
  may briefly remain, so the report may show up to three jobs during a handoff.
  Standalone consumers are additionally bounded to eight requests. Duplicate
  keys share a handle. The eight-entry memory cache protects current/next data.
- Scans retain the existing file-edit mutex, cancellation, 15-second scan limit,
  512 MiB source limit, private cache bounds, source-change detection, exact-frame
  validation and ordinary-seek fallback. No source file is rewritten.
- Reports associate the current URI with an opaque source hash. Each handle has
  a state, reason, point count, build duration and last extractor seek strategy /
  target. Reasons distinguish queue/window cancellation, read failure, time limit,
  unsupported size/length, source changes and invalid/incomplete streams.
  Seek events include index snapshots before the request and after audio progress.
  `buffered_or_pending` means no extractor seek has been observed for that request;
  it must not be interpreted as proof an index was used. Raw URIs are not exposed
  by these new fields.

## Tests and scope

The expanded queue regression passes locally: occupied-worker requests survive;
current-song work preempts and later resumes lookahead; duplicates share work;
twelve rapid selections keep bounded work; and a failed file read is reported
against the actual selected source. Evidence: `index-queue-after.log` under
ignored `android/.tools/seek-probe/`.

An initial scheduler compilation failed because `ensureActive()` lost its scope
receiver when moved to a suspend method. It was corrected to
`coroutineContext.ensureActive()` before any passing runtime claim. The initial
failure was a build error, not evidence about phone latency.

The runner now includes `flac-queue` with the existing generated
index/cache/edit-lock and content-provider suites when `-IndexFixtureUri` is set.
Only emulator private ADB 5038 / serial 127.0.0.1:5561 is used. Generated audio
copies are used for mutations; supplied music remains read-only. No personal
phone was accessed. Root Flask work, signing identity and database schemas remain.

## Final verification and artifact

`android/releases/build-0.10.9.log`: full build passed in 3m17s, including
10,428 core assertions, data/recovery checks, 100 real-disk Room assertions,
assembly of app/test APKs, dependency/tool verification, frozen schemas and APK
signature verification. App lint: 0 errors / 42 warnings.

`android/releases/audio-0.10.9.log`: all six native suites passed on the final
APK: eight PCM/EQ cases, four artwork cases, supplied-song seeks and paused
seeking, generated exact-frame/cache/edit-lock tests, the expanded request and
priority regression, and generated content-provider playback/seeks. The supplied
song used its 155-point cached index and recorded 158-200 ms callbacks in this
run; that checks the available-index path, not why the phone lacked an index.
The standalone seek harness does not set service ownership, so its summary's
`current` field may be unregistered. The queue regression explicitly checks
source-specific reporting, and the real service passes its current engine URI.

The new queue regression failed before and passes after the scheduler change.
No changes were needed to packet parsing, decoded PCM processing, the output
sink, startup thresholds or the Direct USB diagnostic. Both APKs have identical
entry names and byte-identical packaged native libraries for both supported ABIs.
The supplied host song retains SHA-256
`a2a1e0a2dfca825877b15360faaf709db1b1e189dbb501e64c52be21b01b3b4f`.

Artifact: `android/releases/local-music-0.10.9.apk`, 49,389,565 bytes;
package `com.localmusic.player`, code 24, minSdk 30, targetSdk 35. SHA-256:
`484f060bb256437dca505a093063405ba69fc9c1fa04642b9a9fc1cc330d4a04`.
The adjacent `.apk.sha256` matches the artifact and assembly output.
Signer certificate SHA-256 is unchanged from 0.10.8:
`51b163c3c29d8f91f65fbd03929ebb4a98aada4d32b601f2cae5a479374ec507`.
At the time of verification, changes were local and uncommitted. Documentation link/path/content checks
and `git diff --check` complete the non-device verification.

## Phone check

Install `android/releases/local-music-0.10.9.apk` as an update, preserving data.
Select the affected song directly after opening the app, let it play 15-20 seconds,
then try large forward/backward seeks. Check a paused seek stays paused. Also
change songs a few times, return to the affected song and repeat.

If it remains slow, save Settings -> Save playback report immediately. The new
current-source state and reason are needed to distinguish a remaining scan/provider
failure from the reproduced scheduler bug. This update fixes that proven defect;
it does not establish that it was the only cause on the phone. Physical sound
latency, Android 11 SD-card acceptance, high-resolution and Direct USB are not
claimed by local tests. No commit or publication was requested.
