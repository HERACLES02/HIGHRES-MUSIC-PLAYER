# 0.10.8 phone follow-up and source comparison

Continuation: after this design/source review and the user's request to reuse
existing repository work, the compatible upstream scheduling pattern was adapted
in 0.10.9. See [0.10.9 verification](VERIFICATION_0_10_9.md). Statements below
about an intentionally failing regression describe the pre-fix checkpoint.

## Phone result: issue persists

The user replaced `local-music-normal (1).json` with a 0.10.8 report exported
2026-11-05T19:46:08.944Z from CPH1937, SDK 30. It is current phone evidence;
older notes about a report with this same filename describe a different export.
Do not treat 0.10.8 as phone-accepted.

All four seeks target the same 163.385-second FLAC:

| Target ms | Readiness ms | Audio-progress callback ms |
| --- | ---: | ---: |
| 126516 | 638 | 861 |
| 41376 | 841 | 1065 |
| 149591 | 872 | 1092 |
| 29175 | 775 | 1011 |

The first seek was about 19.4 seconds after selecting the song, so this was
not simply a seek immediately after starting it. There are no recorded
playback errors or audio-underrun events. Software timing is not physical
sound-arrival timing.

Index summary: zero jobs; ready indexes with 165 and 200 points; one entry
with `ordinary seeking` and zero points. Since index points are at least one
second apart, a 163.385-second source can have at most 164 points. Neither
ready index represents this song. The supplied matching file produced 155
points in the previous local tests. The report lacks per-source association
and a failure reason, so it cannot establish *why* indexing was unavailable.

## Confirmed scheduling defect

`FlacSeekIndexes.prepare` immediately rejects a request if two requests are
pending. That handle gets `ordinary seeking`. The existing extractor retains
it forever and nothing retries it when earlier jobs finish.

The new `flac-queue` instrumentation suite uses three distinct disposable
copies of generated audio. A shared edit mutex holds the first two jobs while
the third song is prepared. After releasing the mutex, both earlier jobs
produce 177 points, but the third still has no index with zero pending jobs.
The regression fails with:

`Selected song permanently lost its index request when two slots were occupied.`

Evidence: `android/.tools/seek-probe/index-queue-before.log`; test build:
`index-queue-build-before.log`. Test APK assembly passed. The emulator's qemu
property was checked; only private ADB 5038 / serial 127.0.0.1:5561 was used.
Production code and the packaged 0.10.8 APK have not changed in this follow-up.
The test is intentionally red until the scheduling design is corrected.
This reproduces a defect matching the report's outcome, but the phone report
does not distinguish queue rejection from a timeout/read failure.

## Existing source investigated at the user's request

- Auxio `ExoPlaybackStateHolder.kt` uses Media3 ExoPlayer and forwards seeking
  to `player.seekTo`. Its renderer factory includes an FFmpeg audio renderer
  and a platform MediaCodec renderer. It is GPL-3.0-or-later. This is an
  established playback reference, not evidence that copying its seek handler
  would fix our index-admission defect.
  https://github.com/OxygenCobalt/Auxio/blob/dev/app/src/main/java/org/oxycblt/auxio/playback/service/ExoPlaybackStateHolder.kt
- Gramophone's `GramophonePlaybackService.kt` also uses Media3 and custom
  extractor/media-source factories. It is GPL-3.0-or-later. Its service source
  was read; the custom extractor/media-source implementation could not be
  retrieved through the available web fetches, so no FLAC-specific fix from
  those classes has been verified.
  https://github.com/FoedusProgramme/Gramophone/blob/beta/app/src/main/java/org/akanework/gramophone/logic/GramophonePlaybackService.kt
- VLC's FLAC demuxer uses seek points/byte estimates and frame timestamp
  refinement, integrated with VLC streams and packetizers. The inspected module
  is LGPL-2.1-or-later C code, not a drop-in Kotlin/Media3 component.
  https://github.com/videolan/vlc/blob/master/modules/demux/flac.c
- Media3 1.8.0 `BasePreloadManager` keeps managed sources separately from its
  priority queue, recalculates priorities on invalidation and advances after
  completion/error. It is Apache-2.0 and matches our pinned engine version.
  This is a useful scheduling reference. It manages source preloading, not
  private FLAC indexes; direct substitution would be incorrect.
  https://github.com/androidx/media/blob/1.8.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/preload/BasePreloadManager.java

No external code/dependency was imported, no licence was changed and no
checksums/pins were refreshed. The existing fallback already adapts upstream
Media3 FLAC binary-search code with its AOSP licence notice.

## Proposed next design

Use the upstream priority/lifecycle pattern to revise index scheduling:

1. Track current-song and lookahead interest explicitly. Keep bounded work,
   prioritize the current song, cancel obsolete work and retain a pending
   current request when capacity is occupied. Avoid an unbounded job per song.
2. Preserve existing parser, source-file safeguards, shared edit mutex and
   ordinary-seek fallback. Keep the listening queue/service ownership intact.
3. Add per-current-source index state, admission/failure reason and actual
   seek strategy to reports. A report must distinguish queue-full rejection,
   scan timeout, unsupported length, source changes and read/parse failure.
4. Make the queue regression green and cover rapid song changes, cancellation,
   deduplication and bounded work; rerun exact-packet/cache/edit-lock/provider
   tests before building another APK. Phone acceptance remains separate.

The earlier prefill adjustment (0.9.2), Android 12+ startup adjustment (0.10.7)
and private indexing (0.10.8) have not resolved the phone complaint. The
systematic-debugging skill requires reviewing the design after three failed
fixes; this follow-up completes investigation and comparison before a fourth
production change. The user has proposed reviewing/reusing existing source;
the recommendation is the compatible upstream scheduling pattern above.
