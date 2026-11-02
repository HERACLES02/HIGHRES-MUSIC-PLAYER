# Player preview verification, 2 November 2026

Phone follow-up: the user reports expected operation. The first uploaded player
report supports fresh-start, Pause/Play position preservation, seeking and FLAC
44.1 kHz/stereo playback with empty error fields. See [PHONE_RESULTS.md](PHONE_RESULTS.md).
The unverified-phone statements below describe the build checkpoint; targeted
background, storage, codec-matrix and interruption evidence is still outstanding.

Command from `D:\music_player`:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build-player.ps1
```

Final result: **BUILD SUCCESSFUL**, offline with strict Gradle dependency
verification. Kotlin core: **8,681 assertions**. Real disk Room: **61 assertions**
(previous 58 plus library enumeration, explicit Continue lookup and retention of
unavailable tracks). Two frozen Room schemas verify unchanged; no migration or
destructive fallback was introduced. Player lint completes; the data target
compiles and is analyzed as a dependency.
Player lint reports **0 errors, 6 warnings, 0 hints**. Warnings cover the fixed
target API, exported Media3 discovery service, newer-Android backup rules and
three KTX suggestions. No suppression was added. One Kotlin unnecessary-safe-call
warning remains; the data module's existing expect/actual beta warnings are not
suppressed.

APK: `releases/local-music-0.1.0.apk`, **39,847,131 bytes**.
SHA-256: `7a2d21459b40d361fe0d378ec4272078fc66fb9664f8d92304056db6b0e09dce`.
Package/version/min/target verified by aapt: `com.localmusic.player`, code 1,
`0.1.0-normal-preview`, API 30 / 35. APK v2 signature verifies; certificate SHA-256:
`51b163c3c29d8f91f65fbd03929ebb4a98aada4d32b601f2cae5a479374ec507`.
This is a debug-signed preview, not the final private release-signing handoff.

Archive inspection confirms no USB native engine or diagnostic WAV/FLAC assets.
The bundled native libraries belong to existing Compose/SQLite dependencies;
ABIs include arm64-v8a and armeabi-v7a (plus x86/x86_64). Manifest inspection
confirms music-read, foreground-playback/wake and notification permissions;
no INTERNET, microphone or all-files permission. Dependency-provided
ACCESS_NETWORK_STATE is present; no network lookup code is implemented.

The service accepts app-UID/trusted controller connections. Library-changing
custom commands are granted only to the app UID. The public browser exposes
paged library metadata (maximum 100 items/page); it does not grant arbitrary
media-item loading, tag writes or scan access to other applications. Standard
transport commands operate the service-owned queue/engine. Only that engine
requests focus and opens audio. These are source-review findings, not physical
security/lifecycle test results.

No phone was attached to adb. UI rendering, actual codec/file-provider behavior,
notification/lock-screen controls, background/call handling, route removal,
process restart and persistence under Android remain **unverified in this new
application**. Prior successful diagnostic app tests are retained separately and
are not substituted for this player checkpoint. See PLAYER_PHONE_CHECK.md.

Initial build errors (Kotlin wiring and offline lifecycle resolution) were fixed;
the player pins lifecycle 2.9.4 to match the diagnostic's existing cached version.
No SDK, dependency or native-source upgrade occurred. Existing Flask and
diagnostic work was preserved; no commit, push or phone install was performed.
