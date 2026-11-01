# M2 silent volume/rate test: 0.4.0

The physical report has now passed this control test. See
[CONTROL_PHONE_RESULTS.md](CONTROL_PHONE_RESULTS.md). The instructions below
document the completed run; no repeat is needed now. Streaming and clocking
validation remain outstanding.

The 0.3.0 report proved native access and hardware-volume GETs. This next APK
tests actual control writes and readback, without sending audio. The daily-use
player remains behind the accepted Direct USB gate.

## Phone instructions

1. Install [releases/music-usb-probe-0.4.0.apk](releases/music-usb-probe-0.4.0.apk)
   as an update. Connect M2, select its physical **Standard** preset, lower Android
   media volume and keep earphones out of your ears. Stop other audio apps.
2. Open Music USB Probe. Select M2 and use **Inspect cable** if USB permission is
   needed. Tap **Run silent volume / rate test**. Read and accept the confirmation.
3. Let the check finish without disconnecting or force-stopping the app. It
   should be silent. It temporarily changes hardware volume, selects both
   playback alternates and checks all four rates, then attempts restoration.
   Allow up to 30 seconds; errors stop further test configurations.
4. Tap **Save report as JSON**, save `m2-controls-test.json`, and send it back.
   Include failed results; do not repeat the normal fixture matrix.
5. If the app says settings restoration was not verified, **unplug/reconnect the
   M2 before playing audio**. Also reconnect if normal Android audio does not
   return. Note whether reconnection was needed. Playback will not start by itself.

The original GET-only ownership check and read-only descriptor probe remain
separate actions. Use the new **silent volume / rate** button for this test.

## Transaction and report interpretation

The existing exact-descriptor/identity guard, permission-granted fd, normal-player
release gate, scoped claims and cleanup are retained. Control writes require both
AudioControl 0 and playback 2 to be claimed and link speed to report full speed.
No microphone/HID claim, capture, firmware command or audio transfer is introduced.

Before writing anything, the transaction requires idle alternate 0, a readable
original endpoint rate in the advertised set, and complete CUR/MIN/MAX/RES reads
for both volume channels. Ranges must support at least -40 dB, a positive step
and no positive-gain maximum. Invalid/short snapshots cause no writes.

Each channel is set to a legal hardware step at -40 dB or lower, preserving an
already quieter or silent original setting. Exact SET byte counts and matching
GET_CUR values are required on both channels before any configuration test.
Alternate 1 (16-bit stereo) and alternate 2 (packed 24-bit stereo) are each checked
at 44.1, 48, 88.2 and 96 kHz. Each alternate and rate change gets an exact readback;
both channel levels are read after every change to detect a hardware-level reset.
No isochronous audio packets are submitted. A configured rate is not a measured
physical stream, and this test cannot solve the missing-feedback clocking gate.

Cleanup first restores the original endpoint frequency on a known active
alternate, returns to alternate zero, and checks frequency again while idle.
Only after the required idle/rate checks pass does it restore the original
channel levels with readback. Both channel restorations are attempted even if
one fails. A failed idle/rate recovery blocks the original-volume restoration;
the report marks failure rather than presenting a safe or restored state.
Partial write attempts are treated as potentially changing hardware and enter
cleanup. Exception unwinding also attempts restoration before interface release.
Abrupt process death or disconnection cannot guarantee restoration; reconnect
before listening after a failure. Original levels are restored while no app
audio is being submitted; the test does not leave a permanent volume preference.

Native report fields:

- `controlTestRequested`: whether this action was selected.
- `volumeWriteAttempted`, `sampleRateWriteAttempted`, `alternateWriteAttempted`:
  indicate attempted mutations, including calls that fail or return short.
- `lowVolumeControlVerified`: both initial low-level SET/readbacks passed during
  this run. It is not a persistent safety permission for future audio.
- `configurationMatrixPassed`: all eight alternate/rate cases and subsequent
  low-volume checks passed. A first failure stops remaining cases.
- `settingsRestored`: required original settings were checked before interface
  release, or no changes were made. Inspect the matrix/low-level results too.
- `safeHardwareVolumeEstablished` and `clockingValidated` remain false: no
  future audio route is armed by this silent test.

Android's driver may change controls again when reattached. Driver-after results
remain separate from the app's pre-release restore results and do not prove
audible normal playback. An exception report or absent restoration field is
inconclusive and requires reconnection. Successful libusb return values remain
authoritative even if supplementary errno contains a stale value.

Requests follow the UAC1 Feature Unit and endpoint control definitions in
[USB Audio 1.0](https://www.usb.org/sites/default/files/audio10.pdf), sections
5.2.2.4 and 5.2.3.2. No vendor-specific writes are used.

## Verification and remaining work

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-native.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build.ps1
```

The host tests now cover 28 boundary scenarios. New cases include the real
reported M2 ranges, all eight configurations, invalid/short snapshots, rounded
rate readbacks, partial volume writes, changed volume after alternate selection,
failed rate/idle restoration, preserved mute/quieter originals and an exception
after the first write. Assertions reject increased test volume except verified
original-level restoration while idle; no audio API is present in this test.
These simulations do not validate the phone's physical behavior.

Build tools/dependencies remain pinned. The build checks native source/tool and
fixture hashes, runs the parser and fixture regressions, builds both ARM targets,
runs lint, verifies signing, and writes an adjacent APK checksum. The separate
native host test command uses the existing MinGW compiler. Earlier reports and
APK versions are retained.

After the physical report: resolve any write/readback or restoration failures,
then implement the direct PCM streaming experiment with a justified clocking
arrangement. Exact packing/submission checks, all-format listening and the
60-minute 24/96 screen-off run remain required before full-player integration.
