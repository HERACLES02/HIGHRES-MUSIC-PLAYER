# Music USB Probe diagnostic guide

Music USB Probe (`com.localmusic.diagnostics`) runs on Android 11 and later.
It inspects USB audio devices and plays bundled test signals through either
Android's Normal output or an experimental Direct USB route. For listening to
your music library, see [Local Music](player/README.md).

## Build and validation commands

Run these PowerShell commands from the repository root with Java 17, Python 3
and FFmpeg available. If FFmpeg is outside your PATH, pass its executable path
as `-FfmpegPath` to the build script.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build.ps1
```

For a fresh checkout with missing Android tools, review the
[Android SDK terms](https://developer.android.com/studio/terms) before running:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build.ps1 -Bootstrap -AcceptAndroidSdkLicense
```

The build uses pinned tools and dependency checksums, checks fixture hashes,
runs the USB parser, fixture and Direct controller-state checks, assembles and
lints the app, and verifies the APK signature. Use the APK path printed under
`android/releases/`. Its SHA-256 checksum is in the adjacent `.apk.sha256`
file. Install it as an update if the installed copy uses the same signing key.
Keep the existing app and its data if Android reports a signature mismatch.

To run the parser and Direct controller-state checks separately:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-core.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-direct-state.ps1
```

## Run a diagnostic

- **Inspect cable** reads USB descriptors. Grant USB permission when prompted.
  Use **Save report as JSON** to export the inspection.
- **Open normal-route audio test** plays a bundled fixture through Media3 and
  Android's audio output. Use **Save baseline report as JSON** to capture the
  source format, observed route and AudioTrack configuration. Those fields
  describe Android's side of the playback path.
- **Open experimental direct USB playback** sends a bundled WAV or FLAC fixture
  over native USB on the supported KZ M2. Select the cable's **Standard** preset
  and start at the guarded low hardware level. Use **Save last stream report as
  JSON** before beginning another run, which replaces the previous result.
  Reconnect the cable if settings restoration could not be verified.

Direct playback uses nominal packet pacing. The M2 advertises an asynchronous
playback endpoint without explicit feedback. Host-side counters and PCM checks
can verify what the app submitted. DAC clocking, analog channel balance and
bit-perfect output require separate evidence.

## Confirmed Direct USB media controls

On 2 November 2026, the tester confirmed that the lock-screen media controls
worked in Music USB Probe 0.10.0 (`0.10.0-media3-direct`). The setup was an
OPPO CPH1937 running Android 11 (API 30, build `CPH1937_11_F.17`), a KZ M2 in
its Standard preset, and the equal-tone stereo 24-bit / 96 kHz WAV fixture
(`channels-24-96000.wav`).

The exported report recorded Media3 Pause and Resume and a deliberate Stop from
the notification. The 60-second test was stopped after about 28 seconds.
The app drained pending USB transfers and verified settings restoration. It
recorded zero PCM mismatches, USB packet errors or transfer errors.

Lock-screen visibility comes from the tester's observation. All three screen
samples in the report showed the phone as interactive. The report verifies the
control events and cleanup for this run, while the amount of time spent locked
is unknown. This result applies to the tested phone and app version.
