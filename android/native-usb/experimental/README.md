# Offline finite PCM source buffer

## Paused checkpoint

User requested a commit/push checkpoint before further decoder work. The finite
PCM queue results below are verified. A separate **unfinished** streaming FLAC
reader (`flac_stream_reader.hpp/.cpp`) and host-only `flac_stream_cli` target now
exist. Its baseline six-second 16/44.1 PCM comparison passes after a test-first
failure. It is not connected to the queue, USB or Android runtime.

Reader seeking/cancellation, corrupt/truncated data, EOF/MD5 scope, long tracks,
all eight formats and Android compile/runtime checks have **not** been verified.
Do not use its API or the single passing comparison as a finished decoder claim.
The minimal CLI currently accepts only an input file and writes PCM to stdout;
it has no seek/cancel command-line test modes yet. Do not play this unguarded
output through the DAC. It is for offline decoding only.

Resume with test cases for those behaviors before wiring a streaming producer.
libFLAC disables whole-stream MD5 checking after a seek; distinguish partial
decoding from an independently verified complete pass. Use the vendored 1.4.3 API
documentation; do not update dependencies to work around test failures.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-flac-stream.ps1
```

The script configures/builds only the host experimental target and runs the
baseline comparison using existing disposable generated fixtures. It does not
modify the old decoder or diagnostic APK. All hardware gates remain open.

## Verified queue foundation

`pcm_frame_queue.hpp` is a bounded interleaved stereo PCM queue for future
ordinary-track experiments. It is **not connected** to JNI, the diagnostic USB
engine, its build targets or Local Music. The existing six-second fixture decoder,
packetizer, low-volume guards and USB transport remain unchanged.

Supported source metadata is deliberately limited to the M2's eight advertised
16/24-bit, 44.1/48/88.2/96-kHz combinations. This does not validate active rendering.
Construction fixes format and storage size; the experimental storage ceiling is
1 MiB. The test pipe uses 4,096 frames (16 KiB at 16-bit, 24 KiB at 24-bit). Those
figures describe queue allocation, not total decoder/process memory or a measured
USB/device buffering requirement.

Contract:

- Producer `push` accepts complete frames atomically or returns Full without
  consuming any of the chunk. It does not grow storage or drop old frames.
- Consumer `pull` returns complete requested frames, or NeedData without changing
  the queue/output. It never manufactures silence to cover missing source data.
- `finish` is explicit. Once all decoder/integrity checks succeed, the producer
  marks EOF; the consumer can then receive a final short block and End. It never
  loops a source automatically. A short source block is not a USB packet policy.
- Stop, Failed and End are separate results. Stop/error discard unsubmitted
  queued source frames; this queue does not own or free in-flight USB buffers.
- Reset clears the source and advances its generation. Capture that token when
  launching a decoder job. Every write/read/terminal event checks it, so a delayed
  old job cannot modify the new source. A format change requires a new queue.
  Tokens do not keep the queue object alive: its owner must stop/join workers
  before destroying it.
- Operations are mutex-protected for one producer/consumer experiment. No file
  access or decoding happens under the queue lock. Real-time callback behavior,
  decoder cancellation and a full transport handoff have not been validated.

Already-copied consumer blocks and actual in-flight USB buffers still need owner/
generation checks and cancellation/drain in a future engine. This prototype is
not a complete seek, shutdown or mode-switch coordinator. Never treat source EOF
as permission to auto-start another track after interruption or an error.

## Verification

Run from `D:\music_player`:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-pcm-frame-queue.ps1
```

Passed 22 queue scenarios and 24 raw-pipe checks. Coverage includes exact bytes
and channel order at all eight formats, ring wrap, finite non-six-second lengths,
last partial block without padding/repetition, backpressure, starvation, invalid
frame/capacity requests, empty EOF, Stop/error, stale post-reset jobs, and actual
concurrent producer/consumer threads. The initial round-trip assertion failed
against a compiling nonfunctional skeleton before implementation; it passes now.

The test consumer copies blocks into separate output storage; it never submits
USB, opens an audio route, changes volume or accesses the user's music. Test
programs are under `../tests/pcm_frame_queue_*`; compiler outputs are ignored.
NDK API-30 compile-only checks also passed for ARM64 and ARMv7 with warnings
treated as errors. Those object builds are not Android runtime tests.

## Complete-track PC reference

Read-only full-track verification used two originals already identified by the
private music audit, with FFmpeg decoding outside Android. Each complete source
was independently decoded to a PCM SHA-256 reference, then decoded through the
compiled queue pipe and compared by complete byte count/hash:

| Format | Frames / duration | PCM bytes | Queue allocation |
| --- | --- | --- | --- |
| 16-bit / 44,100 Hz | 10,505,208 / 238.213333 s | 42,020,832 | 16,384 bytes |
| 24-bit / 96,000 Hz | 26,316,800 / 274.133333 s | 157,900,800 | 24,576 bytes |

Both complete PCM streams matched; each original's full file SHA-256, size and
modification time stayed unchanged. No PCM output was played or retained as a
full-track file. Private source/hash details are ignored under
`android/.tools/direct-integration/full-track-queue-20261107.json`. The local
helper `check_full_track_queue.py` refuses to overwrite that completed result.
This verifies the queue with complete PC-decoded songs. It is not a complete
Android native decoder, USB output check, clock measurement or bit-perfect proof.

Next bounded source increment: an independently testable streaming decoder/file
reader that feeds this queue, handles seek/error/cancellation and reports verified
EOF. Integrating that with USB still needs an explicit final-block/underflow policy
and a justified clocking arrangement. The documented hardware gates stay open.
