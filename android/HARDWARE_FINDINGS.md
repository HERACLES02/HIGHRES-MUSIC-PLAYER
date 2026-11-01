# OPPO A9 2020 / KZ M2: first physical-device report

Follow-up: left is audible at -30/-20 dB; overlap was only the intentional
both-channel section. The remaining balance observation needs a same-frequency
comparison. See [CHANNEL_CHECK.md](CHANNEL_CHECK.md) for 0.6.0. Older missing-left
notes below are superseded by this clarification, not an established routing fault.

Latest physical audio finding: both 0.5.0 short direct runs completed with exact
app-side PCM/byte counts and restoration, but the user reports **missing left
audio in both**, while normal-route left works. Stereo playback has not passed.
See [STREAM_PHONE_RESULTS.md](STREAM_PHONE_RESULTS.md); do not start the long run
until this finding is resolved.

0.5.0 now implements actual native fixture audio, with explicitly experimental
nominal clocking. It is locally tested only; no new physical playback result has
been received. See [DIRECT_STREAM_TEST.md](DIRECT_STREAM_TEST.md).

Latest: [CONTROL_PHONE_RESULTS.md](CONTROL_PHONE_RESULTS.md) records successful
0.4.0 low-volume writes/readbacks, all eight rate/alternate controls, unchanged
low-level readbacks during changes, and verified restoration. No direct audio
or asynchronous clocking validation has been performed yet.

Latest physical update: [NATIVE_PHONE_RESULTS.md](NATIVE_PHONE_RESULTS.md) records
the received 0.3.0 report. Native ownership and volume reads succeed; full-speed
USB is measured and both Android drivers report active after release. Safe
volume writes, asynchronous clocking and direct streaming remain unverified.

Native 0.3.0 is now built and locally checked; see
[NATIVE_USB_TEST.md](NATIVE_USB_TEST.md). No new native physical results exist yet.

Update, 2 November: the 0.2.0 normal-route and preflight reports have now been
reviewed. See [PHONE_TEST_RESULTS.md](PHONE_TEST_RESULTS.md). All 16 fixtures
completed via the M2; AudioTrack matched source rates but used 16-bit PCM.
Short screen-off playback and detach pause are recorded. All eight hardware
volume GET requests failed; Direct USB and safe hardware volume remain unproven.
The original findings below describe the first read-only report only.

Report inspected: `../music-usb-probe (1).json`.
Captured: 1 November 2026, 16:40:53 UTC.

## Observed evidence

- Phone reports OPPO CPH1937, Android 11 / SDK 30.
- USB product identifies itself as KTMicro / KZ Acoustics M2.
- USB vendor/product IDs: `31b2:0311`; active configuration: 1.
- All 347 raw descriptor bytes were consumed; the report contains no parser warnings.
- The raw descriptors were also decoded independently of the app's Java parser,
  confirming the playback sample formats and rate lists.

| Playback interface | Alternate | Channels | Meaningful bits | Bytes/sample | Advertised rates |
| --- | --- | --- | --- | --- | --- |
| 2 | 1 | 2 | 16 | 2 | 44.1, 48, 88.2, 96 kHz |
| 2 | 2 | 2 | 24 | 3 | 44.1, 48, 88.2, 96 kHz |

Playback uses isochronous OUT endpoint `0x04`, with asynchronous synchronization.
The 24-bit alternate advertises a raw maximum packet size of 582 bytes. There is
no explicit feedback endpoint in these descriptors. Investigate the clock/feedback
arrangement before implementing an asynchronous streaming scheduler; do not assume
fixed packet pacing is sufficient. Actual USB link speed/transport timing still
needs to be established.

Interface 1 is microphone capture: mono 16-bit or 24-bit at 48 kHz. These capture
rates are separate from, and do not set the limit of, music playback. Interface 3
is HID, which may be relevant for the cable's media buttons.

Android's USB-headset output capability entry lists stereo and the same four
sample rates. Its encoding constant is 4. That is an Android capability report,
not a measurement of the active file-to-DAC stream or proof of a physical bit depth.

## What this establishes

The cable advertises stereo PCM up to 24-bit/96 kHz in this configuration. It does
not advertise 32-bit or 192 kHz playback here. Do not enable unsupported modes
based on marketing labels or increase output resolution merely by upsampling.

No test audio was played by this probe. Successful native-rate streaming,
unchanged PCM delivery, cable-internal DSP behavior and audible improvement are
not established. The physical EQ preset used for this capture was not recorded.

## Consequences for milestone 0B

1. Start with known stereo WAV/FLAC samples at 16-bit/44.1 kHz and 24-bit/96 kHz.
2. Establish a standard Android playback baseline on the actual phone/cable;
   distinguish supported properties, requested stream properties and observed
   output properties. Do not infer a fixed 48 kHz limit from this report.
3. Build the native direct-USB comparison against configuration 1, streaming
   interface 2, alternate 2, OUT endpoint 0x04, with packed 24-bit PCM.
4. Determine sample-rate-control support and clock synchronization from the
   relevant descriptors/requests and physical tests. Preserve the HID/microphone
   interfaces where possible; pause on cable removal and release for interruptions.
5. Validate supported formats without claiming that a descriptor, source-file label
   or matching sample-rate indicator alone proves end-to-end bit-perfect playback.
