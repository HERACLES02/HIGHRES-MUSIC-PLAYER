# Stage A checkpoint and phone handoff

Latest clarification: left is audible at -30/-20 dB and the original overlap
occurred only during its intentional both-channel section. Balance remains to
be compared with equal tones. 0.6.0 adds the same gap-separated signal to Direct
and Normal routes; see [CHANNEL_CHECK.md](CHANNEL_CHECK.md). Do not treat the
older missing-left wording below as the final finding or start the long test yet.

Latest finding: 0.5.0 sends both reference channels in short physical USB runs,
but the user reports left missing despite working normal-route left. Functional
stereo playback has **not passed**. See
[STREAM_PHONE_RESULTS.md](STREAM_PHONE_RESULTS.md). The long test and Stage A
completion remain on hold pending this investigation.

Current: 0.5.0 implements actual experimental native isochronous WAV/FLAC fixture
streaming, with stop/drain, pause/seek, hardware volume and foreground reports.
See [DIRECT_STREAM_TEST.md](DIRECT_STREAM_TEST.md). Local tests are not physical
playback evidence; clocking and Stage A's long-run gate remain open.

Latest physical evidence: 0.4.0 passed the low hardware-volume and eight-case
format-control tests with restoration. See
[CONTROL_PHONE_RESULTS.md](CONTROL_PHONE_RESULTS.md). The next gate is actual
direct streaming with justified clocking and transport validation, not another
silent-control test. Stage A remains incomplete.

Current build: 0.4.0 adds the silent low-volume SET/readback and eight-case
rate/alternate test with restoration. See [CONTROL_TEST.md](CONTROL_TEST.md).
Physical control-write and streaming gates remain open until new evidence.

Latest physical checkpoint: the 0.3.0 native report now confirms successful
interface claims, volume GETs and active drivers after release. See
[NATIVE_PHONE_RESULTS.md](NATIVE_PHONE_RESULTS.md). Safe-volume writes, clocking
and streaming remain open; older statements below describe earlier checkpoints.

New checkpoint: 0.3.0 implements the guarded JNI/libusb connection and GET-only
ownership diagnostic. See [NATIVE_USB_TEST.md](NATIVE_USB_TEST.md). The older
statements below that native code is absent describe the 0.2.0 checkpoint.
Streaming, safe volume writes and clocking validation remain outstanding.

Update, 2 November: both requested phone reports were received and reviewed in
[PHONE_TEST_RESULTS.md](PHONE_TEST_RESULTS.md). A1 has physical normal-route
evidence; hardware volume GETs failed and the native Direct USB gates remain
open. The checkpoint table and test checklist below describe the original 0.2.0
handoff. Do not ask for the same normal-route test again before the next native
diagnostic APK is available.

The accepted handoff requires proving Direct USB before the daily-use interface.
This checkpoint delivers the normal-route baseline APK and read-only UAC1
preflight. It does not complete Stage A or reduce the high-resolution goal.

## Implemented and locally checked

| Part | Implementation | Physical status |
| --- | --- | --- |
| Existing USB probe | Preserved; no interface claims or USB writes | Original 0.1.0 report received |
| A1 baseline | Media3 1.8.0 ExoPlayer in one MediaSessionService | Awaiting OPPO test |
| Audio fixtures | Stereo 16/24-bit at 44.1/48/88.2/96 kHz, WAV and FLAC | Exact fixture PCM checked locally; listening pending |
| Observations | Source, decoder input, Android AudioTrack configuration, routed device callbacks, available capabilities | Awaiting Android reports |
| Background/interruption | Foreground media notification, wake lock, focus/noisy handling, output/USB removal pause, no automatic reconnect playback | Implemented; not phone-tested |
| Export | Bounded persistent JSON event journal with timestamps, run/test IDs, errors, underruns, progress/screen state, user notes and fixture hashes | No physical results yet |
| A3 preflight | UAC1 Feature Units and GET_CUR/MIN/MAX/RES for playback volume | Requests compiled; device responses pending |
| A2/A4 | Native streaming, PCM submission comparisons, all-format USB tests, 60-minute 24/96 run | Not implemented / not performed |

Normal playback uses Android's default Media3 PCM sink. The sink may convert
sample representation. Its recorded configuration is not the HAL or physical
USB format. No fixed 48 kHz limit is inferred. No EQ, gain, speed or crossfade
controls have been added. Fixture peaks are already -42 dBFS; player volume is
unity, with normal Android media volume still active. This diagnostic is not
the future `com.localmusic.player` application. No full library, editing,
lyrics or online lookup interface has been built ahead of the gate.

The report retains the latest 1,000 events, counts evictions and keeps the
journal across process restarts. An export is a snapshot, so export again after
adding observations. Reports are app-private until explicitly exported; there is
no network service or automatic upload. A service restart starts empty and
paused. The baseline does not implement the future player's session restoration.

## Exact Direct USB gates

The original 347-byte M2 fixture advertises UAC1 interface 2, asynchronous OUT
0x04, alternates 1/2, 16/24-bit stereo at the four rates. Both standard endpoint
descriptors have `bSynchAddress = 0`. No explicit feedback endpoint is present.
Both class-specific endpoint descriptors advertise sampling-frequency control.
`bcdUSB = 0x0200` does **not** establish negotiated link speed.

The [UAC1 specification](https://www.usb.org/sites/default/files/audio10.pdf)
describes explicit synchronization for asynchronous sinks. The report does not
identify a supported feedback arrangement for this device. This is an unresolved
clocking gate, **not proof that the cable cannot stream**. Nominal packet pacing
alone cannot be presented as a validated solution. Microphone capture must not
be enabled to borrow timing.

The parser identifies playback terminal 5 followed directly by Feature Unit 7
on AudioControl interface 0. Masks are `[1, 2, 2]`: master mute, left volume,
right volume. Capture Feature Unit 2 is separate. Full downstream topology is
not resolved by this preflight. The new probe reads signed 1/256-dB controls
without claiming interfaces or changing volume. Negative/short transfers remain
unknown. Even successful GETs do **not** establish safe volume: low SET_CUR plus
readback on both channels, rollback/cleanup and physical tests remain required.

Native libusb transport, negotiated-speed discovery, exclusive playback-interface
ownership, rate SET/readback, alternate changes, feedback-aware scheduling,
cancellation and native WAV/FLAC decoding remain to be implemented. No JNI/native
dependencies or undocumented DAC writes have been introduced. The Android
[libusb guidance](https://github.com/libusb/libusb/blob/master/android/README)
is the intended unrooted connection foundation, not a UAC playback engine.

There is no attached phone in this session. Physical results cannot be generated
locally. Do not start Stage B or label Direct USB available based on these tests.
If actual streaming fails, discuss that finding with the user before changing
the resolution goal.

## Numbered phone test (no PC needed for playback)

1. Copy `android/releases/music-usb-probe-0.2.0.apk` to the OPPO. Open it in Files
   and approve installation from that source if Android asks. Choose **Update**
   over the existing Music USB Probe. Do not uninstall it to fix a signature
   mismatch; keep the existing key/data and investigate the key first.
2. Connect the M2 and enable OPPO OTG if needed. Select the cable's **Standard**
   physical preset. Lower Android media volume and initially keep earphones out
   of your ears. The app cannot read the physical preset itself.
3. In Music USB Probe, use **Inspect cable**, grant USB permission, then **Save
   report as JSON**. Save as `m2-preflight-standard.json`. This adds hardware
   volume GET results and clocking fields. Permission denial remains safe.
4. Open **Open normal-route audio test**. Confirm lowered volume and preset.
   Start with **16-bit / 44100 Hz WAV**, then its **FLAC** version. Each signal
   lasts six seconds: two seconds left, two right, two both. Raise Android volume
   cautiously only if needed. Record speed, channel order, silence or errors in
   the notes field; tap **Record observation** after each result.
5. Repeat for **24-bit / 96000 Hz WAV and FLAC**, then the remaining rate/depth
   combinations. A file's source label is not a USB output measurement. Read
   Latest Android observations and retain errors as evidence.
6. Turn on **Repeat fixture** and start a fixture again. Lock the screen for at
   least two minutes; verify playback continues. Check notification Pause and
   Continue. Record screen-off and media-button results rather than assuming
   they work from successful compilation.
7. While the quiet fixture plays, remove the M2. Sound must pause, with no
   automatic playback after reconnecting. Select a fixture again explicitly.
   Check an audio-focus interruption/call if practical; playback should stay
   paused afterward. Record failures, including speaker rerouting or automatic
   restart. This is Normal-mode testing, not Direct-mode microphone validation.
8. Tap **Save baseline report as JSON**. Save `m2-normal-baseline-standard.json`.
   Return both JSON reports with your observations. Tap **Stop** when finished.
   If playback stops while locked, record when and how; no speculative ColorOS
   battery-setting changes are required before testing standard behavior.

The six-second loop is a baseline tool. It is not the required 60-minute direct
24/96 streaming test, which awaits the actual native transport.

## Build and validation commands

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-core.ps1
python android\scripts\test_fixtures.py
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build.ps1
```

`build.ps1` checks fixture hashes, runs fixture and parser regressions, assembles,
runs lint, verifies the debug APK signature and writes an adjacent `.apk.sha256`.
Existing tools stay pinned. Media3 dependencies are pinned at 1.8.0. Gradle
dependency verification is on; normal builds do not regenerate trusted hashes.
`-UpdateDependencyVerification` is only for an intentional dependency change
whose metadata diff will be reviewed. Nothing is committed automatically.

Fresh checkout: bootstrap the previously authorized Android tools as described
in README's original build section. Install Python 3 and use an existing local
FFmpeg executable (not downloaded by this project). Generated fixtures are
ignored by Git. To regenerate missing files and verify them against the pinned
manifest:

```powershell
python android\scripts\fixtures.py --ffmpeg 'C:\path\to\ffmpeg.exe'
python android\scripts\fixtures.py --decode --ffmpeg 'C:\path\to\ffmpeg.exe'
```

`--decode` compares every decoded WAV/FLAC byte with independently generated
reference PCM. Local generation used FFmpeg 8.1.1 (exact build in the manifest).
Different encoder builds may produce different FLAC file hashes despite identical
PCM. Inspect such a difference before explicitly using `--generate` to replace
the manifest; normal builds must not bless changed fixtures automatically.
The verification metadata uses first-download SHA-256 values, without publisher
signature verification. Build tools/caches, music fixtures, APKs and keys remain
outside Git. The debug key is not the final personal release-signing key.

## Next work after evidence collection

1. Inspect the baseline and volume reports. Keep failed formats and short/failed
   USB responses visible. Resolve the supported asynchronous clocking arrangement
   from specification and targeted device tests without capture or firmware writes.
2. Pin and checksum native libusb/libFLAC sources and NDK/CMake versions. Implement
   the guarded JNI experiment with deterministic fd/transfer cleanup and only the
   playback interface claimed. Establish safe hardware volume before test audio.
3. Implement native WAV/FLAC decode and exact submission comparisons, then all
   eight USB formats, detach/reconfigure tests and the 60-minute screen-off run.
4. Only after that gate passes, add separate `com.localmusic.player` / Local Music
   with Compose, MediaLibraryService and one common playback coordinator; Room
   migrations, DataStore, WorkManager, TagLib, OkHttp/serialization and Coil.
5. Stages B-F remain outstanding: offline library/queue and controls, safe edits,
   explicitly requested cached online lookups/lyrics, proven Direct USB integration,
   normal-mode effects/widget, user backup and persistent private release signing.
   Preserve all accepted queue, storage, edit-journal and recovery contracts in
   the handoff plan when those stages begin.
