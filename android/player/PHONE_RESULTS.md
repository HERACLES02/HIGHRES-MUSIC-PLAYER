# First Local Music phone results, 2 November 2026

The user reports that everything tried worked as expected. Treat this as a
successful first Normal-player smoke check, with the report supporting the
specific observations below. It is not blanket acceptance of every planned
format, storage, lifecycle or interruption case.

Report: `local-music-normal.json`, copied unchanged from the provided Downloads
file into the project root. SHA-256:
`d59cb9097fdcff68a0037bbb5810b269ecfabd6d0094e2afbfc58bf4735187a8`.

- App `0.1.0-normal-preview`, Normal mode, OPPO CPH1937, API 30,
  build `CPH1937_11_F.17`. Exported 13:30:28.131 UTC.
- Journal: 61 events, 13:28:28.071–13:30:22.698 UTC. Three distinct track IDs
  appear; indexed-song playback reaches READY/playing state.
- A fresh song start is recorded at 0 ms. Pause at 20,241 ms and Play at
  20,248 ms support position preservation. Two seeks and subsequent advancing
  playback are recorded. These are engine-position observations, not DAC timing.
- Final source is FLAC; decoder input reports 44,100 Hz, two channels and raw
  PCM encoding value 21. This is not a format matrix or an active output-rate
  measurement. The journal does not preserve source details for every earlier
  item, so do not claim all observed tracks had this same format.
- Final session is ready and paused at 63,791 ms, duration 217,710 ms.
  The final error field and all journal error fields are empty. A captured
  listening sequence exists, with 872 remaining entries reported; this is not
  an independent count of the whole music library.
- Available outputs include the M2 and its four advertised rates. The report
  does not measure the selected physical route or HAL/DAC output sample rate.

All journal events say `interactive=true`; this export does not independently
verify locked-screen/background duration. There are no recorded focus-loss,
detach or explicit-Continue events. The manual queue is empty at export.
Favorites, duplicate queue entries, shuffle/repeat exhaustion, retained data
after process restart, SD-card removal, denied/revoked access, calls and every
target codec still need targeted evidence before being individually marked
verified. The user's general success observation is retained without inventing
individual test outcomes or asking them to repeat this basic playback check.

No code/APK changes or new build were needed for this evidence update. Music
files remain read-only, Direct remains gated in this app, and the separate
diagnostic app's prior lock-screen success remains separate evidence.
