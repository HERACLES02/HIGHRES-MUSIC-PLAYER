# Persistence verification, 2 November 2026

Player integration follow-up: `android/build-player.ps1` now passes **61** real
Room assertions, adding read-only library enumeration, Continue lookup and
unavailable-track enumeration to the earlier 58. Frozen schema hashes remain
unchanged. This module is now consumed by the separate `:player` preview; the
diagnostic `:app` remains independent. Older evidence below is retained.

Run from the checkout root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-player-data.ps1
```

Result: **BUILD SUCCESSFUL**, **58 persistence assertions** using Room-generated
DAOs and real bundled SQLite files. Android target compiles; data-module lint
reports **No issues found**. KMP expect/actual constructor beta warnings remain
visible in Kotlin compilation; no warning is suppressed.

Final compatibility command from `android/` also passes:

```powershell
& .\.tools\gradle-8.13\bin\gradle.bat --offline --no-daemon --console=plain -g D:\music_player\android\.tools\gradle-cache :player-core:selfTest :usb-core:selfTest :app:lintDebug
```

Existing player tests: **8,681 assertions**. USB parser: **3,052 assertions**.
Diagnostic lint remains **0 errors, 11 warnings, 4 hints**.

Coverage includes disk close/reopen of metadata/sources, duplicate playlist IDs,
favorites, folder-grant records, lyric alternatives/selections/instrumental state
and serialized position/history/manual queues. Restoration is paused and resets
near-end positions. Missing-media references survive; referenced tracks cannot
be deleted. Failed playlist/source/lyric operations preserve original data.

Recovery tests damage the newest saved generation and recover the previous one
paused; checksum-valid malformed JSON also recovers. Rejected originals remain,
repeated recovery does not duplicate evidence, and saving after corruption keeps
a valid previous copy. Two damaged generations return unavailable without erasure.
A simulated exception before outer transaction commit rolls back both session
generations and resume state. This is not a phone-kill or power-cut experiment.

Unsupported session/database versions are not overwritten or destructively
recreated. Non-SQLite bytes are rejected and retained unchanged. A real Room v1
database is seeded, closed, migrated by the registered 1-to-2 code, read through
v2 DAOs and reopened: metadata/source identity, duplicate entries, favorite, lyric
choice, resume/session and folder records survive.

```powershell
python android\scripts\verify-player-data.py
```

Result: **2 frozen schema hashes match**. V2 adds only `session_problem`; all
10 original entities match v1. Git attributes preserve schema bytes. The older
proposed `player-core/schema/v1.sql` remains unchanged and its four SQLite tests
pass; that proposal is not the Room migration baseline.

New pinned Room/SQLite/KSP/JSON artifacts have SHA-256 verification metadata.
Existing Kotlin/AGP/coroutines versions remain. The script runs offline after
initial authorized downloads. Generated code, caches and temporary databases stay
under ignored build/tool directories; exported schemas and their lock are source.

The diagnostic app has no player-data dependency. Its existing 0.7.0 APK SHA-256
remains `ab3ee925f94dd5ec3dc58266ecd5313763d40829e86a15b8ffbb6a2e7bba40bc`.
No APK was built or installed. No phone, USB or audio tests were run by this
harness. Target-phone Room behavior, process-death restoration, Media3/UI wiring
and full library scanning remain integration work.
