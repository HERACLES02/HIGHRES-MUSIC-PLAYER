# Direct USB integration readiness, 7 November 2026

Result: local prerequisite checks progressed; **integration is not ready**.
Clock synchronization and reported analog balance remain unresolved. In addition,
the current transport is a quiet, six-second fixture experiment, not an arbitrary
library-track engine. This report does not authorize or enable Direct in Local Music.

Source-buffer preparation: [offline finite PCM queue](native-usb/experimental/README.md)
now passes 22 queue scenarios, 24 pipe checks and two complete-track PC hash
comparisons with bounded queue allocation and unchanged originals. ARM64/ARMv7
API-30 compile-only checks passed. This is unconnected preparation; Android's
streaming file decoder, USB handoff and Normal/Direct coordinator do not yet exist.

Phone follow-up: [controlled focus report and user confirmation](FOCUS_PHONE_RESULTS_2026_11_07.md)
establish focus-loss shutdown, clean restoration and no automatic restart for
the tested Probe 0.7.0 / CPH1937 path. Preserve that acceptance; do not repeat the
timer or controlled test by default. This is not a 0.10 Media3 or integrated
Local Music test. The earlier manual-stop attempt and timer steps are historical.

## Tests performed in this increment

| Check | Result | Scope |
| --- | --- | --- |
| Media controller state | 31 assertions passed | Real pure-Java view rules; Stop/closing/missing owner disable controls, source-rate positions and loop boundaries. Does not run Android audio focus or notification delivery. |
| Native transport | 62 scenarios passed, up from 22 | Real transport/packetizer with simulated libusb. Existing all-format/byte/failure checks plus 40 new interruption cases. No DAC involved. |
| Regression negative control | New pre-start test failed when the early Stop guard was deliberately removed in an isolated copy | Confirms the new check detects loss of that behavior. Production transport was not mutated or fixed. |
| Music inventory | 835 files probed, zero metadata-read errors | Read-only first-audio-stream metadata from `D:\music` and `D:\FLACs`; not a full-file decode or playback of every track. |
| Representative native decode | 12 exact PCM comparisons passed | Six generated six-second excerpts, one for each supported FLAC format present in the inventory, each decoded as WAV and FLAC. Entire songs were not submitted to USB. |
| Original music preservation | Six representative originals' full SHA-256, size and modification time unchanged | Other inventory files were metadata-read only, with size/mtime checked before/after the probe. Nothing was played or written to the source directories. |

The 40 new transport cases cover all eight advertised rate/depth combinations:

- Stop already pending before configuration: no configuration or submission.
- Stop delivered during initial configuration: no audio submission.
- Stop while paused, alongside a late Resume: no restart.
- Stop during drained volume reconfiguration, alongside seek/volume commands:
  no restart; all pending buffers retired before freeing.
- Cancellation returns `LIBUSB_ERROR_NOT_FOUND` because completion is already
  queued: callbacks still drain safely, with no resubmission after Stop.

These are deterministic boundary/interruption tests. They do not establish real
Android callback ordering, call behavior, physical stop latency or the absence
of every concurrency race.

Changed tracked source: [stream_test.cpp](native-usb/tests/stream_test.cpp) only.
No runtime app/transport code, APK, EQ, Normal playback or persistence changed.

Commands actually run from `D:\music_player`:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-direct-state.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-stream.ps1
& android\.tools\sdk\cmake\3.22.1\bin\cmake.exe --build android\.tools\decode-build --target decode_cli -j 4
python android\.tools\audit_direct_music.py
python android\.tools\check_early_stop_regression.py
```

The native decode target was up to date (`ninja: no work to do`). New excerpt
comparisons used that target. Private inventory, excerpts, preservation hashes
and negative-control results are under ignored `android/.tools/direct-integration/`.
Excerpts are outside `.tools/fixtures`, so they are not diagnostic APK assets.
The audit refuses to overwrite its report; inspect existing results before reruns.

## Actual library formats found

| Codec | Rate | Channels | Meaningful bits reported | Files |
| --- | --- | --- | --- | --- |
| FLAC | 44,100 | 2 | 16 | 449 |
| FLAC | 44,100 | 2 | 24 | 177 |
| FLAC | 48,000 | 2 | 16 | 3 |
| FLAC | 48,000 | 2 | 24 | 103 |
| FLAC | 88,200 | 2 | 24 | 7 |
| FLAC | 96,000 | 2 | 24 | 79 |
| FLAC | 176,400 | 2 | 24 | 1 |
| FLAC | 192,000 | 2 | 24 | 14 |
| MP3 | 48,000 | 2 | Not defined for compressed source | 1 |
| Opus | 48,000 | 2 | Not defined for compressed source | 1 |

818 FLAC files have advertised M2-compatible rate/channel/depth metadata. This
is eligibility metadata, not acceptance of arbitrary-file Direct playback.
The 15 higher-rate files cannot be sent unchanged at their source rate through
this M2's advertised modes. A future strict native-rate path must report that
clearly; it must not silently resample or switch routes. The two lossy files need
a suitable software decoder; USB itself receives PCM, not MP3/Opus packets.
Existing Normal playback remains the available library path.

## Integration gates and retained evidence

| Gate | Current evidence | Remaining work |
| --- | --- | --- |
| USB ownership, safe channel writes/readback, restore, detach and manual/media controls | Earlier accepted diagnostic reports/listening; unchanged source | Reuse evidence. Do not repeat accepted checks just to accumulate passes. Final integrated coordinator will still need targeted acceptance. |
| Source/packet correctness | Earlier eight-format fixture/decoder tests; new interruption coverage; [controlled Windows reference](M2_WINDOWS_CONTROLLED_RESULT_2026_11_07.md) | Windows submission hashes do not establish physical rendering. |
| Clock synchronization | Nominal scheduler matches the short Windows reference; neither trace observed a feedback stream | Exact-firmware clock arrangement or suitable physical timing evidence. No justified feedback/control algorithm has been established. Another nominal run is not a solution. |
| Left/right balance | Equal source bytes and prior Android hardware readbacks; earlier slightly left-quiet Direct observation | Analog balance remains unmeasured. No recording equipment is available. No compensation or swaps are justified. |
| Focus/calls | Local rules pass; focus-loss shutdown/restoration and no restart accepted on Probe 0.7.0/CPH1937 | Real incoming call and final coordinator remain separate; 0.10 Media3 cleanup was not tested by this 0.7 report. Do not repeat accepted component checks by default. |
| Arbitrary tracks and Local Music ownership | Runtime remains six-second quiet fixtures; separate offline finite-source queue now passes bounded/full-track byte checks | Streaming Android decoder/reader, final-block and USB handoff policies, and one-authority Normal/Direct coordinator remain implementation/testing work. No integrated engine exists to acceptance-test now. |

The previous parser, ownership/control, decoder, eight-format and endurance
evidence is reused, not reported as newly rerun here. No full player build, lint,
phone installation or new endurance run was needed for test-only changes.

## Historical timer attempt: superseded by controlled focus request

No ADB device was attached when checked; no physical phone test was performed
during this readiness review. This check uses the existing Music USB Probe 0.10.0; no update or
Local Music change is needed. It does not test clock synchronization or balance.

1. Move M2 from the PC to the phone. Select **Standard**, keep earphones out,
   lower media volume, and stop other audio apps. In Music USB Probe use
   **Inspect cable** if it needs USB permission.
2. In the phone's **Clock** app, start a **30-second timer**. Immediately return
   to Music USB Probe's **Direct USB experiment**.
3. Select **24-bit**, **96000 Hz**, the checked **Equal-tone channel comparison**
   option, and **1 min**. Acknowledge the experimental notice, tap
   **Play selected USB fixture**, then **Start experimental audio**. Leave hardware
   volume low; no increase or listening comparison is needed.
4. When the timer rings, dismiss it and return to the probe. The expected
   focus-loss behavior is for Direct to end and release USB, staying stopped
   after dismissal. Do not press Resume or start another run before saving.
5. Note whether Direct stopped and stayed stopped. Tap **Save last stream report
   as JSON**, naming it `m2-direct-focus.json`. If it has not stopped, tap
   **Stop / release**, record that manual Stop was needed, then save. Reconnect
   M2 before listening if restoration is not verified.

Review the actual export for `interruptionReason: audio_focus_loss`, pending
transfers zero, cleanup and restoration, and released media session. Some Clock
apps may not request media focus: if that reason is absent, the timer check is
inconclusive about the service's focus callback. Do not label it a passed call
test, or repeat it blindly. A real incoming-call case remains separate.

No phone test can supply missing analog measurements merely by reporting clean
USB counters. The no-equipment limitation remains explicit; vendor questions
are [prepared but unsent](M2_VENDOR_CLOCK_QUESTIONS.txt).
