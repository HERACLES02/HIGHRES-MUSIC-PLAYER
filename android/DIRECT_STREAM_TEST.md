# Direct USB fixture experiment: 0.5.0

Physical update: the first two reports are received. Transport counters and
submission fingerprints match the reference, but the user reports missing left
audio in both. See [STREAM_PHONE_RESULTS.md](STREAM_PHONE_RESULTS.md). Hold the
long run and use its targeted level check; the checklist below records the
original handoff and subsequent tests are conditional on working stereo.

This APK implements real native isochronous OUT streaming to the M2. It is a
diagnostic experiment, not the daily-use player or a validated clocking solution.
Use the first short listening checks below before attempting long runs.

## First phone check

1. Update Music USB Probe with
   [releases/music-usb-probe-0.5.0.apk](releases/music-usb-probe-0.5.0.apk).
   Connect M2, select its physical **Standard** preset, stop other audio apps,
   lower Android media volume, and initially keep earphones out of your ears.
2. Use **Inspect cable** if USB permission is needed. Open
   **Open experimental direct USB playback**. The page explicitly labels its
   nominal clocking as unvalidated; acknowledge the experiment before starting.
3. Keep **16-bit / 44100 Hz / WAV / 12 sec** selected. Tap **Play selected USB
   fixture**, read the confirmation and start. This now sends actual audio
   through libusb, without an Android AudioTrack or normal-player fallback.
4. The quiet six-second source pattern repeats twice: 2s left, 2s right, 2s both.
   Hardware starts at -40 dB or an already quieter original level; the source
   itself peaks at -42 dBFS. Check cautiously before putting earphones in. If
   needed, the page has explicit **hardware-only** -30/-20 dB buttons. Android
   volume buttons may not change direct volume. No software gain is applied.
5. When the run ends, enter notes about audibility, channel order, correct speed,
   glitches or silence. Tap **Save last stream report as JSON**. Name it
   `m2-direct-16-44100-wav.json`. Save before starting another run: the completed
   report slot is replaced by the next test.
6. If that check sounds correct, select **24-bit / 96000 Hz / FLAC / 12 sec**,
   repeat and save `m2-direct-24-96000-flac.json`. Replace the notes for this run.
   Return these two reports first. If the first test is wrong, stop and send its
   failure report; do not continue into a long listening run.
7. If restoration is not verified, or Android audio does not return afterward,
   unplug/reconnect M2 before listening again. Unplugging or a focus interruption
   stops the experiment; reconnecting does not start it automatically.

No PC, music-folder permission or internet connection is needed for these tests.

## Follow-on tests available in the same APK

After the short checks work, all eight rate/depth combinations are selectable in
both WAV and FLAC (16 files). Run each at 12 seconds and retain the reports and
failures. Configuration support from 0.4.0 alone is not a physical playback pass.

Use a separate **1 min** run to exercise **Pause**, **Resume**, **Seek 0s**,
**Seek 3s**, hardware levels and **Stop / release**. Pause cancels/drains pending
transfers and selects idle alternate zero; Resume explicitly reconfigures and
checks hardware volume. Pause retains the diagnostic's exclusive USB claims.
Calls/focus loss stop and release instead. Seeking uses the repeating six-second
fixture and does not reset the total test budget. Volume changes while paused
apply on Resume. Sample position is a host transport observation, not a measured
DAC render timestamp. Notification Stop is available while the screen is locked.

The **61 min** selection runs 3,660 nominal seconds to provide margin above the
minimum 60-minute target. Use **24-bit / 96000 Hz** only after shorter runs pass.
Lock the screen and leave the test uninterrupted; do not pause/seek/change levels
during that run. Return the JSON plus notes on any audible glitch and whether
the phone stayed locked. A long-run pass needs actual report/listening evidence;
the existence of this duration option is not that evidence. Check wall time,
interruptions, completed intervals, errors and user observations together.

If Android terminates the process or the test cannot finish, reopen the page and
use **Save latest in-progress / interrupted checkpoint**. Checkpoints are marked
incomplete and do not contain a fabricated final restoration result. Reconnect
the cable after a forced stop or uncertain cleanup. If Stop says it is draining,
do not start another test; the native owner retains in-flight buffers until their
callbacks arrive. Reconnect if cleanup stalls, and retain the checkpoint.

## Clocking policy and limits

The M2 descriptors advertise asynchronous OUT 0x04 without explicit feedback.
No supported device clocking arrangement has yet been established. This build
uses a deliberately bounded nominal-rate experiment: 1,000 service intervals
per second at measured USB full speed, with fractional frame accumulation.
For example, 44.1 kHz uses 44/45-frame packets, not a fixed rounded packet size.
Each payload stays within the pinned alternate's endpoint limit (388 or 582
bytes). There is no microphone capture, implicit-feedback stream or vendor
firmware command.

The [Linux v6.12 USB-audio endpoint source](https://github.com/torvalds/linux/blob/v6.12/sound/usb/endpoint.c)
illustrates nominal-rate packet scheduling when a synchronization source is not
attached. This informs the experiment; it is not evidence that the M2 accepts
this clocking policy. The examined
[implicit-feedback table](https://github.com/torvalds/linux/blob/v6.12/sound/usb/implicit.c)
did not identify this device. These are pinned source references, not a claim
about the phone's driver or every later kernel version.

USB completion can succeed while a DAC internally buffers, drops, repeats or
processes samples. No independent bus capture, device-internal underrun counter,
render-clock measurement or DSP bypass proof is available. Zero transport errors
do not resolve those unknowns. The app must not turn this experiment into a
daily-use Direct USB approval or an unconditional bit-perfect badge.

## Implemented safeguards and measurements

- Native PCM WAV reading and libFLAC 1.4.3 decoding; only the bundled six-second
  stereo 16/24-bit fixtures at the four advertised rates are accepted. Long user
  files and arbitrary music-library playback are not supported by this harness.
- Both encoded-file and independently pinned decoded-PCM SHA-256 checks before
  USB streaming. Native startup also rejects PCM exceeding the quiet-fixture
  amplitude ceiling. No resampling, software volume, EQ or ReplayGain is used.
- Existing exact M2 layout guard, granted-fd wrapping, normal-engine release,
  exclusive route gate, scoped claims, low hardware SET/readback and cleanup.
  Format changes are followed by renewed volume writes/readback before audio.
  During playback, channel levels are checked roughly every 10 seconds and a
  mismatched/failed read stops further streaming.
- Eight reusable asynchronous transfers, at most eight packets per transfer:
  approximately 64 ms of nominal queued payload. Stop/error cancels and drains
  before restoring controls or freeing transfer memory. A callback delayed beyond
  two seconds during cancellation is recorded; memory is retained until safe.
- Exact packet-byte comparisons to the decoded reference before submission,
  submitted/completed byte/frame counts, callback counts, a submission FNV-1a
  fingerprint (not cryptographic), error/short-packet/cancellation counts, host
  callback-gap observations and outstanding-transfer counts.
- Foreground service, wake lock, audio-focus stop behavior and USB-detach stop.
  The direct path creates no Android player. Direct notification/lock-screen
  MediaSession controls, cable buttons and microphone behavior are not implemented
  or claimed; the diagnostic notification supplies Stop.
- Periodic screen-interactive/native-stat snapshots, capped at 450 retained
  entries with evictions counted. Atomic private incomplete checkpoints and a
  completed report survive ordinary activity recreation. Export uses the system
  file picker, with a private pending-export fallback. No upload or network access.

`pendingTransfers` counts work whose callbacks the app has not retired; it is
not a DAC buffer-depth measurement. `queueEmptyDuringActive` and callback gaps
are app-side observations, not verified hardware underruns. Error codes include
libusb negatives, `-100-status` for transfer failure, `-200-status` for packet
failure/short completion, -300 for incomplete queue exhaustion, -301 for PCM
comparison failure and -400 for periodic volume-check failure. Phase values are
1 preparing, 2 streaming, 3 paused, 4 draining, 5 ended, 6 error.

Every new stream establishes low volume again; old reports do not arm future
audio. Cleanup restores the original idle/rate/levels before returning drivers,
but Android may reconfigure controls on reattachment. Failed or unknown restore
results require reconnection. `clockingValidated` and persistent
`safeHardwareVolumeEstablished` remain false; inspect actual runtime guards and
readbacks rather than treating those fields as end-to-end approval.

## Build and local verification

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-decode.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-stream.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-native.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build.ps1
```

The native decoder test builds the actual WAV/libFLAC code on this Windows host
and compares all 16 fixture outputs to pinned PCM hashes, checks signed extrema
and channels, and rejects 18 corrupt/unsupported inputs. The 22 transport tests
compare every simulated submitted byte and exercise all formats, queueing, stop,
pause/resume, seek, volume reconfiguration, allocation/submission/completion/event
failures and health-check failure. The 31 ownership/control tests include the
streaming-entry gate and restoration after a playback exception. Simulation does
not approve physical USB behavior.

libFLAC source tag 1.4.3, archive SHA-256 and 341 source/license file hashes are
pinned in `native-usb/flac-sources.json`. It is linked statically; its Xiph BSD
notice/license is packaged. Existing libusb, SDK/NDK/CMake and Gradle dependencies
remain pinned. Source/tool/fixture verification, 3,052 parser assertions, fixture
regressions, both ARM builds, lint and signature checks are required. The separate
host tests use the installed MinGW compiler; build/decoder caches remain ignored.

The next decision requires the physical streaming reports. Resolve glitches,
errors, clocking and restoration findings before claiming Stage A complete or
building the full player's Direct USB integration.
