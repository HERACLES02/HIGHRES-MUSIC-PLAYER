# M2 native connection diagnostic 0.3.0

The requested phone report has now been received. See
[NATIVE_PHONE_RESULTS.md](NATIVE_PHONE_RESULTS.md) for the physical results and
next gates. The instructions below document the completed test; no repeat is
needed now. Statements below about unverified restoration describe the original
build handoff; the new report confirms driver-active status after release, but
does not independently test restored sound/microphone/buttons.

This is the next Stage A phone test, following the reviewed 0.2.0 results in
[PHONE_TEST_RESULTS.md](PHONE_TEST_RESULTS.md). It investigates the failed volume
queries and native connection access. It is not a direct-audio streaming build.

## Phone steps

1. Install [releases/music-usb-probe-0.3.0.apk](releases/music-usb-probe-0.3.0.apk)
   as an **update** over Music USB Probe. Keep the current app/data; no uninstall
   or phone-setting change is needed. This is still the diagnostic package, not
   the future Local Music player.
2. Connect the M2, select its physical **Standard** preset, and open Music USB
   Probe. Select the cable and use **Inspect cable** to grant USB access if asked.
3. Tap **Run native M2 ownership check**. Read the confirmation and tap
   **Standard preset selected · Run check**. It will stop/release this app's normal
   player. Android cable audio and microphone may be interrupted briefly while
   the diagnostic temporarily claims the audio-control/playback interfaces.
   Do not run another audio app, disconnect, or force-stop the app during this
   check. USB requests have 500 ms timeouts; allow about 15 seconds.
4. When results appear, tap **Save report as JSON** and save
   `m2-native-preflight.json`. Send that JSON back, including any failed results.
   Do not repeat the normal WAV/FLAC matrix.
5. The check should finish silently and leave playback paused. If Android audio
   does not return, unplug/reconnect the M2. Optionally play one quiet normal
   fixture to check restoration; tell us if reconnection was needed. The new
   report contains driver state and release results; these need phone validation.

The original read-only probe and normal-route tests remain available. Native
errors are evidence, not a reason to keep rerunning the check or write volume.

## What is implemented

- Exact M2 identity/347-byte descriptor-layout guard before native access.
- One main-thread route gate: release the service-owned normal player/session
  before native work; reject new normal service sessions during the check.
- JNI with libusb 1.0.29, `NO_DEVICE_DISCOVERY` and `libusb_wrap_sys_device` using
  a duplicate of Android's permission-granted connection fd. Java retains its
  original fd; the duplicate, handle and libusb context are closed deterministically.
- Negotiated-speed query, active-configuration check, before/after driver state.
- UAC1 volume GET_CUR/MIN/MAX/RES on playback Feature Unit 7, left/right channels,
  first unclaimed, then after a successful AudioControl interface-0 claim.
- Explicitly confirmed auto-detach, claims limited to AudioControl 0 and playback
  2, playback alternate GET and endpoint sample-rate GET; release in reverse order
  and automatic driver-reattach requests. Capture interface 1 and HID 3 are never
  claimed. Detaching the shared audio driver may still interrupt cable microphone
  use; the UI explains this before starting.
- Return codes and names, supplementary errno observations, exact successful
  response bytes and decoded volume/rate values. Short responses remain raw
  failed/incomplete observations, without fabricated values. A kernel-driver
  result of 1 means active; GET results are byte counts.
- Exportable JSON, a private fallback `native-usb-last.json` saved when the worker
  completes, and bounded GET timeouts. The fallback is not an automatic upload or
  a guarantee of recovery from forced process termination.

There are **no audio transfers, volume writes, rate SETs, alternate SETs,
microphone reads, firmware writes or playback-clock claims**. Safe volume and
clocking validation remain false even if GETs succeed. Speed may remain unknown
if Android/kernel access cannot provide it. Rate GET may fail at alternate zero;
even a returned frequency is not evidence of a running stream.

Release errors and failure to restore kernel-driver state must be investigated.
Cleanup requests are implemented; restoration is not physically verified. Abrupt
process death cannot guarantee driver restoration; physical reconnection is the
recovery path. No root or device enumeration is used.

## Build and local tests

Existing Gradle, AGP, Kotlin and Media3 versions are unchanged. New native tools
are pinned to NDK 27.2.12479018 and CMake 3.22.1 under `.tools/sdk`. `toolchain.json`
pins Windows compiler/CMake/Ninja SHA-256 values. libusb v1.0.29 is vendored without
source modifications; `sources.json` pins the official tag archive hash and all
40 source/license file hashes. CMake follows upstream's Android source list and
configuration. The LGPL notice/license are packaged in the APK; source and build
instructions are included in this checkout. The JNI wrapper is a separate shared
library linked to `libusb-1.0.so`.

```powershell
# Existing tools:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-native.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build.ps1

# Fresh checkout missing native SDK packages, under previously authorized terms:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build.ps1 -Bootstrap -AcceptAndroidSdkLicense
```

Host boundary tests use an existing MinGW `g++`; no new host compiler is downloaded.
Eleven scenarios run the actual C++ diagnostic against fake libusb calls: normal
reads, init/wrap denial, wrong identity, unsupported auto-detach, busy control or
playback interface, release failure/retry, an injected exception, short response
and disconnected-device errors. Assertions check GET-only requests, no capture/HID
claims, signed volume values, reverse cleanup, fd closure and retention of the
original fd. A separate JSON parser validates the emitted simulated report.
These are not physical USB tests.

`build.ps1` verifies native source/tool hashes and existing fixtures, runs fixture
regressions and the 3,052-assertion parser suite, compiles arm64-v8a and armeabi-v7a,
runs Android lint, verifies APK signing and writes `.apk.sha256`. Run the separate
host test command when changing the native logic. Existing Gradle dependency
verification remains enabled. Native source/tool hashes are first-download trust
from the official distributors, not independently authenticated signatures.

No decoder, isochronous scheduler, feedback solution, direct volume setter or
daily-use player has been added. The next implementation depends on the physical
native report; successful connection/GET results alone do not pass Stage A.
