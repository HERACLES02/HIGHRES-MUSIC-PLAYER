# Music USB Probe: Stage A diagnostics

Latest: **0.6.0 equal-tone channel comparison**. Use
[CHANNEL_CHECK.md](CHANNEL_CHECK.md) to compare identical, non-overlapping 440 Hz
signals in Direct USB and Normal mode. This investigates balance without adding
a speculative routing or gain correction. Earlier versions are documented below.

Latest: **0.5.0 experimental direct USB fixture playback**. Follow
[DIRECT_STREAM_TEST.md](DIRECT_STREAM_TEST.md) for the APK and the first short
listening checks. This build sends actual native USB audio, with verified fixture
decoding and guarded hardware volume. Nominal clocking remains unvalidated.
The earlier notes below are historical checkpoints.

Latest: **0.4.0 silent volume/rate test**. Follow
[CONTROL_TEST.md](CONTROL_TEST.md) for the new APK and instructions. This adds
guarded control writes/readbacks and restoration; it sends no direct audio.
The checkpoint notes below describe older releases.

Current build: **0.3.0 native ownership diagnostic**. Use
[NATIVE_USB_TEST.md](NATIVE_USB_TEST.md) for the new APK and phone instructions.
It preserves the normal tests and read-only probe, and adds a confirmed native
connection/GET-only experiment. Direct audio is still disabled. The section
below describes the preceding 0.2.0 checkpoint.

Current APK: [releases/music-usb-probe-0.2.0.apk](releases/music-usb-probe-0.2.0.apk).
This updates the existing diagnostic app using the same local debug key. It
preserves the read-only probe and adds quiet offline WAV/FLAC fixtures, a Media3
foreground playback service, Android route observations, report export, and
read-only playback-volume queries. Direct USB streaming is **not implemented**.

**Start with [STAGE_A.md](STAGE_A.md)** for numbered phone instructions, current
checks, exact blockers and next tasks. [BUILD_STATUS.md](BUILD_STATUS.md) records
build evidence. The full daily-use player remains behind the Direct USB gate.
Existing Flask files have been preserved.

Build with the already installed tools:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build.ps1
```

Fresh checkouts also need Python 3 and local FFmpeg to regenerate bundled
fixtures; see STAGE_A.md. Generated audio, APKs, caches and keys stay out of Git.
The source-controlled fixture manifest pins the verified audio hashes, and
`gradle/verification-metadata.xml` pins build dependency hashes. Normal builds
verify these without updating them.

## Archived 0.1.0 documentation

The following describes the original read-only APK. Its no-playback and
permission statements apply to 0.1.0 only. Current instructions are above.

This is the first Android app for the OPPO A9 2020 / Android 11 / KZ M2 plan.
It inspects advertised USB playback capabilities and exports a local JSON report.
The native USB playback test is milestone 0B, after we have the actual report.

The first installable build is ready at
[releases/music-usb-probe-0.1.0.apk](releases/music-usb-probe-0.1.0.apk) (24.9 MiB).
See [BUILD_STATUS.md](BUILD_STATUS.md) for build verification and remaining
physical-device checks.

## What this version does

- Lists connected USB devices and requests Android's USB permission when needed.
- Reads USB Audio Class 1/2 streaming formats, meaningful bits versus container
  bytes, playback/capture direction, endpoint synchronization and feedback.
- Reads UAC1 advertised sample-rate lists/ranges.
- For UAC2, links straightforward terminal/clock paths and makes read-only
  GET_CUR/GET_RANGE clock requests in the active configuration.
- Keeps unresolved clock selectors/multipliers, unsupported UAC3, malformed
  descriptors and denied queries explicitly unknown.
- Shows microphone capabilities separately from playback capabilities.
- Exports raw descriptors, interpreted formats, Android-reported output
  capabilities and warnings as JSON, using Android's Save file picker.

It does not stream audio yet, bypass Android's mixer, alter cable EQ/firmware,
claim interfaces, write USB audio, record from the microphones, or prove audible
improvement/bit-perfect output. A supported descriptor is not a successful test
of playback. No internet or music-library permission is requested by this APK.
Device serial numbers are not collected.

## Build on this Windows PC

Java 17 or newer is needed; Java 18 was detected during preparation. Build tools
and caches use `android/.tools`, without modifying the global PATH. First setup
downloads several hundred MB; allow about 1–2 GB of disk space including caches.

Google requires agreement to the [Android SDK terms](https://developer.android.com/studio/terms)
for its SDK. The bootstrap script accepts the selected packages' terms only
when explicitly invoked with `-AcceptAndroidSdkLicense`.

After reviewing and accepting those terms:

```powershell
cd D:\music_player
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build.ps1 -Bootstrap -AcceptAndroidSdkLicense
```

The execution-policy option applies to this PowerShell process, not your global
Windows settings. Later builds, with tools already installed:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build.ps1
```

Expected output: `android/releases/music-usb-probe-0.1.0.apk`.
This diagnostic is debug-signed. Keep `.tools/debug.keystore` if you want future
debug builds to update the same installation. A permanent personal release key
will be prepared before the full player release. Never put keystores into Git.

The pinned build uses Gradle 8.13, AGP 8.13.2, Kotlin/Compose compiler 2.3.10,
Android SDK 36 and a minimum Android version of 11 (API 30). Compiling with a
newer SDK does not mean the app requires that version on your phone.

## Test the parser without installing Android tools

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-core.ps1
```

The pure Java tests cover UAC1/UAC2 formats, playback versus recording,
feedback endpoints, different configurations, malformed descriptors,
truncated rate tables, unsigned clock ranges, and 3,000 malformed-input cases.
These tests do not substitute for a physical USB-device test.

## Install and use on the phone

1. Copy the APK to your OPPO, for example with a USB cable or LocalSend.
2. Open it from Files. If Android asks, permit that source to install this APK.
3. Open **Music USB Probe**. No account or PC server is needed.
4. Connect the KZ M2. If it is missing, check OTG Connection in OPPO's Additional
   Settings; the exact setting can differ with ColorOS.
5. Tap **Refresh USB devices**, select the M2, then **Inspect cable**.
6. Allow Android's request to access that USB device. Denial is safe; retry from
   Inspect cable. Permission may be needed again after reconnecting.
7. Read Advertised playback formats. Unknown values should remain unknown.
8. Tap **Save report as JSON** and choose a location such as Documents.
9. Send the resulting JSON report back for the next implementation step.

For comparisons, you can save one report for each physical EQ preset, naming
the files yourself. The app cannot read the M2's preset LED or know that the
Standard preset bypasses all internal processing, so record which preset you
selected. Do not treat the preset's High-Resolution label as a format result.

## How the code fits together

- `app/src/main/java/com/localmusic/diagnostics/MainActivity.kt`: the native
  Compose screen, permission dialog and report saving.
- `ProbeViewModel.kt`: device selection, worker lifecycle and UI state.
- `UsbProbe.kt`: read-only USB inspection and the JSON report.
- `usb-core/.../UsbDescriptors.java`: bounds-checked descriptor parsing, kept
  independent of Android so it can be tested on this PC.

The next stage uses this report to choose a real direct USB playback prototype,
including isochronous transfer handling. That engine cannot be established from
the phone/cable marketing specifications alone.

Reference specifications:
[USB Audio 1](https://www.usb.org/sites/default/files/audio10.pdf),
[USB Audio 2](https://www.usb.org/sites/default/files/Audio2_with_Errata_and_ECN_through_Sep_14_2026.pdf),
[Android USB host](https://developer.android.com/develop/connectivity/usb/host).
