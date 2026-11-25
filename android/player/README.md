# Local Music Android player

Current: **0.14.2**, version code 50, minimum Android 11 / API 30.
Package: `com.localmusic.player`. This standalone offline player is separate
from the Flask player and Music USB Probe.

## Audio features

- Graphic and parametric EQ, exact frequency/gain/Q entry, preset import/export,
  a labelled 48 kHz response preview and estimated headroom guidance.
- Double-precision EQ/ReplayGain followed by float PCM; no forced 16-bit step
  before DSP on the precision path. Float PCM has about 24 significant bits.
- Normal output by default. The optional Android USB preference tries supported
  stereo rates above 48 kHz; 44.1/48 kHz retain Normal buffering. Crossfade is
  suspended while the preference is enabled, preserving the saved choice.
- **Settings ? Audio controls & equalizer ? Audio info** shows source metadata,
  selected mode, last reported processing/client formats and routed device.
  Use Refresh for a new snapshot. Client format is not an active HAL/DAC measurement.

On the tested OPPO A9 2020 / Android 11 / KZ M2 setup, stereo 24-bit/96 kHz
FLAC playback opened a 96 kHz float Android USB output. Low-rate files used
Android's 48 kHz/PCM16 mixer. A small click when switching from 48 to 96 kHz
remains a known limitation; Normal mode was clean in the mixed-rate comparison.
This is not a claim of bit-perfect transport or measured physical DAC precision.

## Library and player controls

Song options offer **Remove from library...**, which keeps the file, and
**Delete file from phone...**, which reviews the exact filename/location before
permanent deletion. Restore hidden listings from **Settings > Removed songs**.
Restoring a listing cannot recover a deleted file. Playlist and queue references
are retained as unavailable; removing the current song stops playback.

Deletion requires write access to the chosen music folder and provider support.
The file is checked again at confirmation; changed files require a fresh review.
Pending tag writes or recovery must finish before deletion.

Now Playing supports pull-down dismissal over the retained browsing screen.
Queue and playlist entries can be reordered with drag handles or menu actions;
queue selection uses occurrence identities so repeated songs remain distinct.
Settings include saved theme accents. Home defers Library filtering/sorting until
that tab opens, and usable startup is reported separately from the launch logo.

## Build

From the repository root, after the existing Android tool setup:

```powershell
# Optimized personal preview
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build-player.ps1 -OptimizedPreview

# Debug build and instrumentation APK
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build-player.ps1
```

Both run core/data checks, assembly, lint and schema/signature verification.
Artifacts are `android/releases/local-music-0.14.2-optimized-preview.apk` and
`android/releases/local-music-0.14.2-debug.apk`. Install as an update without
clearing data. The optimized preview uses the existing local signing key and
retains native tag, Room and worker entry points. Its R8 mapping is saved beside
its APK. Internal debug instrumentation targets the debug build.

Generated APKs, local signing keys, reports, audio fixtures and tool caches stay
local. Native Direct USB remains a separate diagnostic with unresolved hardware
acceptance gates.

To assemble the unsigned GitHub beta APK from an Android tools setup:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build-player.ps1 -Beta
```

This runs the core and data tests, release assembly and lint, and schema
verification. It writes `android/releases/local-music-0.14.2-beta1-unsigned.apk`
with experimental Direct USB disabled. Sign it with the private release key
before installing it. Export a Local Music backup before replacing a preview
signed with a different key.
