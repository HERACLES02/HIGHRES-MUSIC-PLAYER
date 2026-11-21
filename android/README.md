# Android projects

- [Local Music](player/README.md) is the standalone offline music player for Android 11 and later.
- [Music USB Probe](STAGE_A.md) is the separate USB audio diagnostic. It includes experimental Direct USB tests.

Run these commands from the repository root.

```powershell
# Prepare the Android tools and build Music USB Probe
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build.ps1

# Build a Local Music debug preview
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build-player.ps1
```

The Probe build requires Python and FFmpeg to generate its audio fixtures. See the [diagnostic setup and validation commands](STAGE_A.md#build-and-validation-commands). APKs are written to `android/releases/`. Signing keys and tool caches stay local.
