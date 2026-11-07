# Android Output Probe

Separate diagnostic app `com.localmusic.outputprobe`; it does not replace
Local Music or Music USB Probe. It has no permissions, network, recording or
music-file access and uses Android AudioTrack rather than raw USB.

The user explicitly starts five foreground-only, muted zero-PCM tests on a
single connected KZ Acoustics M2. Each lasts at most 12 seconds and releases
its AudioTrack afterward. Leaving the screen or tapping Stop cancels the run.
Device preference is per track; system volume and USB settings are unchanged.

| Case | Stereo 96 kHz client | Buffer | Device preference | Performance request |
| --- | --- | --- | --- | --- |
| 0 | Float | At least 250 ms | Default | None |
| 1 | Float | At least 250 ms | M2 | None |
| 2 | Float | Aligned platform minimum | M2 | None |
| 3 | Float | Aligned platform minimum | M2 | Low latency |
| 4 | PCM16 control | Aligned platform minimum | M2 | None |

The helper also queries system direct-playback support. On Android 11 that
hint and client getters do not establish the active USB mixer/HAL format.
Validation used separate active-phase AudioFlinger/audio-policy snapshots.
On the tested phone/M2, case 2 opened an Android 96 kHz float DIRECT stream;
the larger-buffer and low-latency cases used a 48 kHz PCM16 HAL route.
See [player verification](../player/VERIFICATION_0_12.md) for the subsequent
integrated-player result and its remaining mixed-rate click.

## Build

From the repository root after the existing Android tool setup:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\output-probe\build.ps1
```

The build runs 20 Java buffer checks, compiles/packages the app, uses the
existing local debug key, verifies its signature/package and refuses unexpected
permissions. Output is local `android/releases/android-output-probe-1.0.apk`.
No dependency or replacement signing key is downloaded/generated.

The debuggable helper writes its own `files/report.json` and run/case marker
`files/phase.txt`. When connected over authorized ADB, these can be read using
`run-as com.localmusic.outputprobe`; no elevated app permission is required.
Reports contain client formats, routing IDs, support hints and completion/errors.
They do not prove nonzero music quality, physical DAC clocking, analog balance
or bit-perfect output. Raw reports and generated APKs stay outside Git.
