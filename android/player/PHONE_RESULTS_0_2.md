# Local Music 0.2.0 report review, 2 November 2026

User follow-up: explicitly confirms Undo worked and restored the original.
Undo is now user-confirmed on this phone. The original JSON's VERIFIED phase
remains unchanged; its missing UNDONE entry is a limitation of that export,
not evidence against the subsequent user observation. No repeat is requested.

Report: `local-music-normal (1).json`, SHA-256
`1e02db58197c4d17d296e7f3c5272d723751348b3c7556b1bd28936cb32e652f`.
App `0.2.0-library-lyrics`, OPPO CPH1937, API 30, build `CPH1937_11_F.17`.

- Library: 875 track identities, one granted folder, 1,738 source entries checked;
  scan reports zero read failures and zero uncertain matches. These counts alone
  do not independently establish complete deduplication or SD-card coverage.
- Saved session restored paused at 4,014 ms, current generation, autoplay=false.
- One FLAC file-edit journal reports VERIFIED: saved file reopened and verified.
  This is application-side provider-write evidence. The export does not include
  the original/staged/payload hashes for independent recomputation. It does not
  show Undo or recovery acceptance; the final phase is VERIFIED, not UNDONE.
- Current FLAC decoder input is 44.1 kHz/stereo. Final playback is active, position
  49,013 ms. Pause/resume observations include 84,470 → 84,474 ms; three seek
  requests appear. Final error and all retained event error fields are empty.
- The bounded journal retains 150 events from 15:34:56.067 to 15:42:43.585 UTC;
  earlier events may have been evicted. Every retained sample is interactive=true.
  This does not establish locked-screen/background behavior or absence of older
  errors. No retained detach/focus-loss observation is present.
- Historical process exit describes package installation, not an observed
  background-playback failure. Available M2 rates remain capability evidence,
  not a measured active output rate.

Artwork/lyrics visual correctness, online matching/cache behavior, interrupted-write recovery,
screen-off playback, calls, disconnect/reconnect and SD/revoked-grant handling
still require user observations or targeted evidence. No repeat of the basic scan
or the verified write is requested. No code/build change was needed for review.
