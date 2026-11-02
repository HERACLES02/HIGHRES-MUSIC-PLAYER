# Diagnostic APK build status

Current separate player: **0.2.0-library-lyrics**, code 2. See
[scope, checks and phone gate](PLAYER_0_2_HANDOFF.md). New features are locally
tested; phone/provider acceptance is pending. Diagnostic artifacts below remain
separate and unchanged.

## Separate player: 0.1.0-normal-preview, 2 November 2026

First phone result now received: successful basic Normal playback, Pause/Play
and seek observations, supported by the report and user's success confirmation.
See [player phone results](player/PHONE_RESULTS.md). The broader pending phone
checks below remain separate; no new APK is required for this result.

`com.localmusic.player` now has its own installable **Local Music** APK. This does
not change the diagnostic artifact below. Player build, 8,681 core assertions,
61 real Room assertions, frozen schemas and signature pass. Lint has 0 errors,
6 warnings. New-app phone behavior remains pending. Artifact/checksum and limits:
[player verification](player/VERIFICATION.md); [phone steps](PLAYER_PHONE_CHECK.md).

## Current diagnostic: 0.10.0-media3-direct, 2 November 2026

Phone acceptance: user confirms lock-screen controls now work. The uploaded
report records Pause/Resume, explicit Stop, zero transport/PCM errors and verified
restoration. See [MEDIA3_PHONE_RESULTS.md](MEDIA3_PHONE_RESULTS.md). Build evidence
below is retained; its pending physical-check statement is the pre-test status.

- Artifact: `releases/music-usb-probe-0.10.0.apk`, 74,183,597 bytes, version code 10.
- SHA-256: `9925348200ea86b4a0ad528a33e3856bd660924d5cc3f73ac08d87fb737ed744`.
- `powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build.ps1`
  passes assembly, strict source/tool/dependency checks, 3,052 parser assertions,
  two original fixture regressions and one eight-variant channel regression.
  `scripts/test-direct-state.ps1` separately passes 31 controller-state assertions
  and is now included in the build script for subsequent builds.
- Lint: 0 errors, 14 warnings, 4 hints. The exported-service warning is retained:
  Media3 discovery is public, while session trust checks and private-intent nonce
  checks authorize commands. The bitmap KTX suggestion is also retained. No
  suppression was added. Two deprecated constructor warnings belong to the unused
  0.9.0 helper, not the running Media3 notification path.
- APK v2 signature and unchanged signing certificate verified; certificate SHA-256
  `51b163c3c29d8f91f65fbd03929ebb4a98aada4d32b601f2cae5a479374ec507`.
  aapt verifies application ID, version, min API 30 and target API 35.
- All six native libraries and all 24 fixture files are byte-identical to 0.9.0.
  No native-suite rerun: native transport/PCM code is unchanged.
- Implements a MediaLibraryService/session and SimpleBasePlayer adapter for the
  diagnostic Direct engine. No dependency upgrade or new manifest permission.
  Physical lock-screen visibility and Media3 remote controls remain pending.
  See [MEDIA3_DIRECT_CHECK.md](MEDIA3_DIRECT_CHECK.md) for evidence and phone steps.

## Current diagnostic: 0.9.0-media-card, 2 November 2026

- Artifact: `releases/music-usb-probe-0.9.0.apk`, 70,147,886 bytes, version code 9.
- SHA-256: `c2e01358e1acabb8703067feb190aac4de4a742ae79ebb60108062e6ebf53300`.
- Command: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build.ps1`.
- Final assembly, strict source/tool/dependency checks, parser 3,052 assertions,
  two original fixture regressions and one eight-variant channel regression pass.
  Lint: 0 errors, 13 warnings, 4 hints. Two Kotlin deprecation warnings for the
  compatible framework action-icon constructor are visible, not suppressed.
- APK v2 signature verifies with the same certificate SHA-256
  `51b163c3c29d8f91f65fbd03929ebb4a98aada4d32b601f2cae5a479374ec507`.
  aapt verifies application ID, version, min API 30 and target API 35.
- All six native shared libraries and all 24 WAV/FLAC fixture bytes match 0.8.0.
  No native source/dependency/permission changes or native-suite reruns.
- Direct's diagnostic session/style card and Pause/Resume/Stop forwarding compile;
  the Normal session is released before the Direct session is created. Session
  shutdown paths, inactive-engine command guards and public diagnostic metadata
  were source-reviewed. This is not physical validation of remote callbacks or
  lock-screen visibility. See [LOCK_SCREEN_MEDIA_CHECK.md](LOCK_SCREEN_MEDIA_CHECK.md).

## Current diagnostic: 0.8.0-notification-check, 2 November 2026

- Artifact: `releases/music-usb-probe-0.8.0.apk`, 70,542,452 bytes, version code 8.
- SHA-256: `bb92d69342c54626b2ca25355f110de1ce3fa424d96ad0cd5da0e2183a9f4065`.
- Build command: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build.ps1`.
- Final assembly, strict source/tool/dependency checks, two original fixture
  regressions, one eight-variant channel fixture regression and 3,052 parser
  assertions pass. Lint: 0 errors, 13 warnings, 4 hints; no suppression added.
- APK v2 signature verifies; certificate remains
  `51b163c3c29d8f91f65fbd03929ebb4a98aada4d32b601f2cae5a479374ec507`.
  aapt verifies package, version, min API 30 and target API 35.
- All six native shared libraries and all 24 WAV/FLAC fixtures are byte-identical
  to 0.7.0. No native suites were repeated for this notification/report-only update.
- No new dependencies. Manifest adds POST_NOTIFICATIONS with a guarded user-
  triggered runtime request only on Android 13+. It creates no prompt on the
  target Android 11 phone. No microphone, file-access or listener permission added.
- Phone notification visibility and Stop action are unverified. The update reads
  posting/channel state and posts a separate same-channel test card; it does not
  claim a fix. See [NOTIFICATION_CHECK.md](NOTIFICATION_CHECK.md).

Parallel Room persistence work: `:player-data` passes 58 real-database assertions,
Android compilation/lint and frozen-schema checks. No new diagnostic APK was
built or installed. See [persistence verification](player-data/VERIFICATION.md)
for migration, recovery, paused restoration and remaining phone-integration limits.

Parallel player-foundation work: `:player-core` and its proposed SQL schema pass
8,681 Kotlin assertions and four SQLite tests. Existing parser checks and Android
lint also pass with the new module configured. This produced no new APK; the
diagnostic still has no dependency on the player foundation. See
[player-core verification](player-core/VERIFICATION.md).

## Current: 0.7.0-background-check, 2 November 2026

Phone follow-up: the background-stop export now physically succeeds and exposes
the original attempt's correlated system resource-kill record. See
[BACKGROUND_PHONE_RESULTS.md](BACKGROUND_PHONE_RESULTS.md). This validates the
export, not an endurance fix. No new build was needed for report review.

- Artifact: `releases/music-usb-probe-0.7.0.apk`, 70,509,336 bytes, version code 7.
- SHA-256: `ab3ee925f94dd5ec3dc58266ecd5313763d40829e86a15b8ffbb6a2e7bba40bc`.
- APK v2 signature verifies; certificate SHA-256 remains
  `51b163c3c29d8f91f65fbd03929ebb4a98aada4d32b601f2cae5a479374ec507`.
  Package, version, min API 30 and target API 35 verified with aapt.
- Final build passes parser 3,052 assertions, two original fixture regressions,
  one eight-variant channel regression, pinned sources/tools, assembly and lint
  (0 errors, 11 warnings, 4 hints). No dependency verification changes required.
- All six packaged native libraries and all 24 WAV/FLAC fixtures are byte-identical
  to 0.6.0. Native suites were not repeated for this Kotlin/report-only change.
- Real JSON evidence checks establish the newer checkpoint/stale-final ordering,
  372.031 s saved span and 291.639 s observed screen-off span, with no recorded
  errors. Process-exit export is compiled/lint-checked, not yet phone-verified.

This update adds a read-only background-stop export with both saved reports,
timestamp ordering, own-app process-exit history and Android battery/background
restriction observations. New checkpoints/final reports include run UUID, PID,
start time and requested duration. Normal-report wording no longer claims native
streaming is unimplemented. No new dependencies or native changes.

Build command:
`powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build.ps1`.
Phone export instructions and interrupted-run evidence are in
[BACKGROUND_PHONE_RESULTS.md](BACKGROUND_PHONE_RESULTS.md).

Earlier build checkpoints follow.

## Current: 0.6.0-channel-check, 2 November 2026

- Artifact: `releases/music-usb-probe-0.6.0.apk`, 70,482,800 bytes, version code 6.
- SHA-256: `b847f8f6930975e92c8646c0f5b5224ba926aa4b1efefa79ae94cb27e70ae2d5`.
- APK v2 signature verified; certificate matches previous releases; ARM ABIs and
  packaged version checked. All native libraries are byte-identical to 0.5.0.
- New channel fixture regression: 1 test covers all 8 variants, exact equal
  active-channel samples, complete silence gaps, no simultaneous nonzero channels,
  quiet peaks and pinned hashes. Both original fixture regressions also pass.
- Actual native decoding matches all 24 WAV/FLAC reference hashes; existing signed
  extrema/channel checks and 18 corrupt-input rejections pass.
- All 24 packaged fixture hashes verified. Original 16 fixture hashes unchanged.
- Parser: 3,052 assertions pass; source/tool/dependency checks, assembly and lint
  pass (0 errors, 11 warnings, 4 hints). Existing native transport/control evidence
  is retained for the identical native libraries; those host suites were not
  rerun for this fixture/UI-only change.
- No phone attached through ADB. Equal-tone balance and the new comparison UI
  are not physically verified. No channel swap, compensation or default volume
  increase was implemented. Clocking and Stage A's long-run gate remain open.
- Instructions: [CHANNEL_CHECK.md](CHANNEL_CHECK.md). Evidence:
  [CHANNEL_VERIFICATION.json](CHANNEL_VERIFICATION.json).

Earlier checkpoint records follow.

Follow-up clarification: left is audible at -30/-20 dB and overlap was only in
the intentional both-channel section. Earlier categorical missing-left wording
is superseded. 0.6.0 adds a controlled equal-tone comparison; see
[CHANNEL_CHECK.md](CHANNEL_CHECK.md). Residual balance and clocking remain unverified.

Physical update: 0.5.0 reports are now reviewed in
[STREAM_PHONE_RESULTS.md](STREAM_PHONE_RESULTS.md). App-side submission/transport
checks passed, but missing-left listening results mean stereo playback has not
passed. Local build evidence below is unchanged; no new APK was built in review.

## Previous: 0.5.0-stream-experiment, 2 November 2026

- Artifact: `releases/music-usb-probe-0.5.0.apk`, 54,773,625 bytes, version code 5.
- SHA-256: `f9f4be7bf0511639e6a4dea671668d68ccc5ff898cf54f36e897e3af02f68bdd`.
- APK v2 signature verified; signing certificate matches previous builds.
- Both ARM JNI/libusb variants, the new stream/decoder JNI entry points, package
  ABIs/version, all 16 packaged fixture hashes and both library licenses checked.
- Native decoder: 16 exact WAV/FLAC PCM hashes, signed extrema/channel checks,
  and 18 corrupt/unsupported input cases passed against the actual native code.
- Transport/packet boundary tests: 22 scenarios passed, including exact bytes,
  all eight formats, queueing, cancellation, pause/seek, errors and volume health.
- Ownership/control boundary tests: 31 scenarios passed, including guarded
  streaming entry and restoration when a playback callback throws.
- Existing parser: 3,052 assertions; fixture regressions: 2 tests. Native source,
  tool and strict Gradle dependency checks passed. libFLAC 1.4.3 source added,
  archive/per-file checksums pinned and BSD license packaged.
- Assembly/lint passed: 0 errors, 11 warnings, 4 hints. Warnings remain pinned
  dependency versions, target, omitted ChromeOS ABI, backup rules, icon and KTX;
  hints suggest Compose integer-state helpers.
- No ADB phone attached. Native audio, foreground/locked behavior, live volume,
  pause/seek, interruptions, physical transport and clock stability are **not
  physically verified** by this build session.
- New code sends actual fixture PCM through asynchronous isochronous OUT, but
  nominal scheduling is an explicit unvalidated experiment. No microphone
  timing, firmware writes, normal-engine fallback or bit-perfect badge.
- User handoff: [DIRECT_STREAM_TEST.md](DIRECT_STREAM_TEST.md). Local evidence:
  [STREAM_VERIFICATION.json](STREAM_VERIFICATION.json).

Earlier checkpoint records follow; their pending/implemented statements apply
to those versions only.

Physical update: the 0.4.0 report passed its low-volume and format-control checks
with restoration. See [CONTROL_PHONE_RESULTS.md](CONTROL_PHONE_RESULTS.md).
Earlier pending-phone statements below describe the build session. Local build
evidence and artifact checksums remain unchanged; audio streaming is unverified.

## Previous: 0.4.0-controls, 2 November 2026

- Artifact: `releases/music-usb-probe-0.4.0.apk`, 53,875,360 bytes, version code 4.
- SHA-256: `c7f797e054e5a2d817b1d054f02cd186bf7d15abce27ee1092263644aeb477dc`.
- APK v2 signing verified; certificate matches prior diagnostic builds.
- Both ARM targets compile. Packaged JNI/libusb libraries, all 16 fixture hashes,
  license and package version/ABI declarations checked.
- Native host tests: 28 scenarios passed; simulated control report independently
  parsed, with eight distinct successful configuration readbacks and restoration.
- Existing parser: 3,052 assertions; fixture regressions: 2 tests. Source/tool
  checksum verification and strict Gradle dependency checks passed.
- Assembly and lint passed: 0 errors, 11 warnings, 1 hint (same categories as 0.3.0).
- No phone attached through ADB; 0.4.0 control writes/readback/restoration are not
  physically verified. No direct audio is submitted by this build.
- User instructions: [CONTROL_TEST.md](CONTROL_TEST.md). Machine-readable local
  evidence: [CONTROL_VERIFICATION.json](CONTROL_VERIFICATION.json).

Earlier checkpoint records follow.

Physical update, 2 November: the 0.3.0 report is received and reviewed in
[NATIVE_PHONE_RESULTS.md](NATIVE_PHONE_RESULTS.md). Its native-access/GET/driver
results supersede the original pending-phone statements below. The artifact
hashes and local-build records remain unchanged.

## Previous: 0.3.0-native-diagnostic, 2 November 2026

- Artifact: `releases/music-usb-probe-0.3.0.apk`, 53,806,928 bytes, version code 3.
- SHA-256: `f1afcaeb3a1720cc88397cb0e663bf037cf71d69fd6c48d495256bcd1ebdb111`.
- APK v2 signature verified. Signing certificate matches 0.1.0/0.2.0; update
  installation is supported by this checkout's retained debug key.
- Both arm64-v8a and armeabi-v7a JNI/libusb libraries compiled and are packaged.
  The expected JNI entry point is exported; package ABIs/version checked with AAPT.
- Eleven host native-boundary scenarios passed, including errors, short reads,
  fake disconnects, GET-only policy, cleanup ordering and file-descriptor ownership.
- Source (40 files), compiler/CMake/Ninja and Gradle dependency hashes verified.
  All 16 packaged audio fixture hashes and the packaged libusb license checked.
- Existing parser: 3,052 assertions; fixture regression: 2 tests. Build passed;
  lint has 0 errors, 11 warnings, 1 hint. New warning is omitted ChromeOS/x86_64
  support, intentional for this OPPO ARM diagnostic. Other warnings remain the
  preceding pinned-version/target/icon/backup/KTX warnings.
- No ADB phone is attached. Native runtime, actual link-speed result, claims,
  control reads and driver restoration are **not physically verified**.
- No direct audio, hardware-volume SET or playback-clock validation is implemented.
  Follow [NATIVE_USB_TEST.md](NATIVE_USB_TEST.md) for the next phone check.

The remainder records previous checkpoint evidence.

Update, 2 November: 0.2.0 phone reports are now available. Their physical results
are in [PHONE_TEST_RESULTS.md](PHONE_TEST_RESULTS.md). The statements about no
phone evidence below describe the build session, not the current evidence.

## Previous: 0.2.0-baseline

- Artifact: `releases/music-usb-probe-0.2.0.apk`; application ID remains
  `com.localmusic.diagnostics`, version code 2, min SDK 30, target SDK 35.
- SHA-256: `d6c9a15a577de0a500bc9b23f9c71ef597d69e655115241cbe93d0a553415a4d`.
- APK Signature Scheme v2 verified successfully; adjacent `.apk.sha256` generated.
- Size: 51,201,984 bytes (48.8 MiB). Signing-certificate SHA-256 matches 0.1.0,
  allowing an in-place update with this checkout's existing debug key.
- Packaged version/SDK values checked with AAPT. No INTERNET, microphone-recording
  or music-library permission; Media3 adds ACCESS_NETWORK_STATE. All 16 packaged
  fixtures and the bundled manifest match their pinned source hashes.
- `scripts/test-core.ps1`: 3,052 assertions passed, including the exact physical
  M2 descriptor bytes, Feature Units, endpoint controls and malformed inputs.
- `scripts/test_fixtures.py`: 2 tests passed (all 16 reference hashes and the
  signed/channel/quiet-level/meaningful-low-bit signal checks).
- `scripts/fixtures.py --generate --ffmpeg <local-executable>`: all 16 WAV/FLAC
  files decoded to exact reference PCM. This validates fixtures, **not** PCM
  submitted to a direct USB engine.
- `build.ps1`: assembly and Android lint passed with strict dependency checksums;
  lint has 0 errors, 10 warnings and 1 hint.
- Lint warnings concern pinned versions, target API, backup rules, launcher icon
  and a suggested KTX helper. The hint suggests `mutableIntStateOf`; no lint errors
  were suppressed.
- Dependency checksums generated once from configured Google/Maven Central/plugin
  repositories; subsequent normal build verified them. These are trust-on-first-use
  hashes, not independently authenticated publisher signatures.
- No phone is connected through ADB. New playback, background behavior, volume
  GET responses and route observations are **not physically verified**.
- Direct USB, libusb/libFLAC/JNI, low-volume SET/readback and the daily-use player
  are **not implemented**. See [STAGE_A.md](STAGE_A.md).

## Historical: original 0.1.0 probe

The physical report has since been received and inspected. The original pending
phone checklist below is retained as build history, not current status.

Build completed on 1 November 2026.

- Artifact: `releases/music-usb-probe-0.1.0.apk`
- Application ID: `com.localmusic.diagnostics`
- Display name: Music USB Probe
- Version: `0.1.0-probe`, version code 1
- Size: 26,106,086 bytes (24.9 MiB)
- Minimum Android SDK: 30 (Android 11)
- Compile SDK: 36; target SDK: 35
- Debug APK signature: verified successfully with APK Signature Scheme v2
- SHA-256: `32ed55dc5173732558d203907661aaffbcddcfea0f30714d7be78aa13b5a5536`

## Completed validation

- `:usb-core:selfTest`: passed 3,034 assertions, including 3,000 malformed-input cases.
- `:app:assembleDebug`: successful.
- `:app:lintDebug`: no errors; six warnings.
- Packaged manifest inspected: no internet, microphone-recording, or music-library
  permission. AndroidX adds a signature-protected internal receiver permission.
- PowerShell build script and source manifest parsed successfully.

The lint warnings concern the deliberately pinned library versions, target API,
Android 12+ data-extraction rules and the prototype's missing custom launcher
icon. They do not establish that the app has been tested on a phone.

## Still needs the physical phone/cable

- Install and launch on the OPPO A9 2020 / Android 11.
- Grant USB access to the attached KZ M2.
- Capture and export the actual descriptor/clock report.
- Check display, rotation, denied permission and cable disconnect/reconnect.
- Use that evidence to implement and validate direct USB audio streaming.

This APK is a read-only capability probe. It does not yet contain the music
player, direct USB audio engine, or a bit-perfect playback test.

The full beginner installation/export steps are in `README.md` in this folder.
