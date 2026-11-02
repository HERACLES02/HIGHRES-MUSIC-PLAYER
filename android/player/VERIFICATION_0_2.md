# Local Music 0.2.0 local verification

Command: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build-player.ps1`.
Result: BUILD SUCCESSFUL, offline with strict dependency/source/tool verification.

- Kotlin: 8,681 existing core assertions, 18 identity checks and 21 guarded-write/
  patch checks. Room: 71 real SQLite assertions, including installed-v2→v3
  migration and FULL journal synchronization. LRCLIB fixture transport: 24
  assertions, without live provider calls.
- Seven native generated cases across five containers, including FLAC 24/96
  and ALAC: exact encoded packets, SHA-256 audio/configuration fingerprints,
  all nine fields, Unicode, multiline comments, multiple artists, front-cover
  operations, other-artwork preservation, extension-free reads and malformed input.
  Runtime picture checks compare role/MIME/description/length/FNV fingerprints;
  original/staged/saved file verification uses SHA-256.
- Three frozen schemas verify. V1/v2 tables remain unchanged; v3 adds extras and
  edit journals. TagLib's 572 unmodified source/license files are pinned.
- Lint: 0 errors, 23 warnings, 0 hints. Remaining warnings concern fixed target,
  dependency updates, exported discovery, backup rules and KTX suggestions.
  Existing Kotlin beta/safe-call and native codecvt deprecations remain visible.

APK: `releases/local-music-0.2.0.apk`, 55,764,875 bytes, code 2,
`0.2.0-library-lyrics`, `com.localmusic.player`, min/target API 30/35.
SHA-256: `dd48f7f9e81b49d352456d7d9bc7c8d3765f478561bc839bff14522e6af5d5b0`.
APK v2 signature verifies with the existing preview certificate:
`51b163c3c29d8f91f65fbd03929ebb4a98aada4d32b601f2cae5a479374ec507`.

Archive inspection verifies two tag-library ABIs and packaged license notices;
no diagnostic USB engine or audio fixtures. Manifest includes music-read,
playback/wake, notification and explicit-search internet access. WorkManager
contributes boot/network-state permissions. No microphone/all-files permission,
automatic playback, or scheduled online lyrics lookup.

No new phone/UI/provider/background acceptance is claimed. This remains an
updateable debug-signed preview. See [phone steps](../PLAYER_0_2_HANDOFF.md).
Direct remains gated. No commit/push; unrelated work was preserved.
