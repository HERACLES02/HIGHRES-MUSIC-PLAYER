# Local Music: first standalone player preview

**Current: 0.2.0-library-lyrics (version code 2).** Folder selection, cached covers,
WorkManager scanning, DataStore themes, synchronized/offline/LRCLIB lyrics and
reviewed app-only/embedded tag and cover edits are implemented. Use the
[0.2.0 scope and phone handoff](../PLAYER_0_2_HANDOFF.md). The sections below retain
the **0.1.0 historical checkpoint**; its read-only/missing-feature statements do
not describe 0.2.0. The build script now outputs `local-music-0.2.0.apk`.

First phone smoke check received: the user reports everything tried worked as
expected. The report supports real-song playback, pause/resume and seeking;
the final decoder input is stereo FLAC at 44.1 kHz, with no recorded errors.
See [PHONE_RESULTS.md](PHONE_RESULTS.md) for the limits of that evidence.

`com.localmusic.player`, display name **Local Music**, version
**0.1.0-normal-preview** (code 1), minimum Android 11 / API 30. This application
installs alongside Music USB Probe. It does not replace the probe or embed its
USB transport, diagnostic fixtures, Flask server or WebView.

## Implemented

- Compose/Material 3 Home, Library, Now Playing, Queue, Settings, song-details
  dialog and persistent mini-player, using the phone's light/dark theme.
- User-triggered, cancellable MediaStore scan of indexed music on mounted
  internal/SD storage. Existing source URIs retain app-owned UUIDs. Unchanged
  records skip writes. Completed volume queries can mark missing songs; unmounted
  storage and lost read permission retain identities, favorites and session
  references. A failed volume query does not declare all its files missing.
- Song search and sorting, album/artist groups, storage-volume groups, favorites
  and explicit Continue actions for unfinished positions beyond 15 seconds.
  Moved files are not automatically matched to older identities.
- One service-owned `PlaybackAuthority`, `ListeningQueue`, ExoPlayer and
  MediaLibrarySession. `NormalSessionPlayer` is a SimpleBasePlayer view of that
  same engine. The UI commands a MediaController; it creates no player or playback
  timer. The service publishes observed position once per second.
- Song taps start at zero. Play/Pause preserves position. Previous follows
  history or restarts after three seconds. Captured collection order, manual
  duplicates, Play Next priority, shuffle exhaustion and repeat rules use the
  previously tested Kotlin queue. A collection is capped at 5,000 songs and the
  manual queue at 1,000, with an explicit error rather than silently truncating
  the playback order. The queue display previews up to 60 entries per section.
- Room v2 library/session storage without destructive migration. Sessions are
  queued to one process-owned writer every ten seconds and on pause/transition;
  restore prepares the saved track paused. Damaged/unknown sessions retain their
  recovery evidence; an unknown version blocks new session writes with a visible
  warning. This is not a promise of zero loss on process death before a write.
- Media3 foreground notification/session, media commands and wake handling.
  Focus loss, noisy-output and wired/USB/Bluetooth removal handling pause without
  automatic resumption. These new-app behaviors still need phone acceptance.
- Settings → Save playback report exports the current service's bounded event
  journal, source/decoder input, phone build and available output capabilities.
  Reported capabilities are **not a measured active HAL/DAC rate**. Events are
  memory-only and do not survive a killed service/process in this preview.

All music files are read-only. The app requests music-read access and uses the
system document picker only to save an explicit diagnostic report. It has no
INTERNET, microphone, USB, or all-files permission. Normal volume uses Android's
media-volume controls; app effects, gain adjustment and speed processing are off.
Android/device processing remains unknown.

## Limits and next work

This is the first phone-testing checkpoint, not the complete personal release.
Direct USB stays unavailable here while its clocking/balance findings remain
open; the separate probe retains the experimental transport and its evidence.
No unconditional bit-perfect claim is made.

Normal format targets still require actual MP3, FLAC, PCM WAV, AAC/ALAC M4A,
Vorbis and Opus playback checks on the phone. Unsupported/corrupt files produce
an actionable playback error. Tracks are loaded individually by the coordinator;
gapless transitions are not implemented or promised in this preview.

Folder selection/sidecars, WorkManager scans, content observers, DataStore
preferences, playlists, full listening-history UI, embedded artwork/technical tag
reading, lyrics, tag editing/transactions, online lookups, processing controls,
sleep timer, widget and backup/export/restore are future increments. Storage
groups show volumes, not directory browsing. The scan reads MediaStore's music
index; files it has not indexed or marked as music may not appear. Source URI
ownership is reused during scans; provider-ID reuse/content reconciliation still
needs stronger identity checking before the full storage release.

Pause/close the probe's Direct test before trying Local Music. They are separate
applications; the final single-player Normal/Direct mode-switch coordinator has
not been built. Final release signing and backup are also still Stage F work.

## Build

After the existing local SDK/toolchain has been prepared with `android/build.ps1`:

```powershell
cd D:\music_player
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build-player.ps1
```

The offline script runs player-core and real Room tests, assembles/lints the
player, verifies frozen schemas, checks the APK signature and writes a checksum.
Dependency versions match the existing diagnostic toolchain and strict dependency
verification remains enabled. No new downloads or dependency upgrade was needed.

Artifact: `android/releases/local-music-0.1.0.apk`. This preview uses the existing
project-local debug certificate so preview updates can retain data. Keep
`android/.tools/debug.keystore` private and backed up; it is not part of app-data
exports and is ignored by Git, as are APKs, caches and build outputs. The final
personal release-key/data-migration handoff remains to be designed before changing
this signing identity.

See [verification](VERIFICATION.md) and the
[beginner phone check](../PLAYER_PHONE_CHECK.md).
