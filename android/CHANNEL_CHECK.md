# Equal-tone channel comparison: 0.6.0

Status: locally built and verified; the user has postponed the phone comparison.
No equal-tone Normal/Direct result is available yet. Resume with the steps below.

Your follow-up establishes that left is audible at -30/-20 dB and the overlap
was only in the original both-channel section. The overlap was intentional.
The remaining question is balance, so this build adds a controlled comparison
instead of changing channel routing or compensating gain.

## What changed

- A new **Equal-tone channel comparison** uses identical 440 Hz sample sequences
  at identical source levels for each active channel. Unlike the original signal,
  it has **no both-channel section**.
- Six-second repeating pattern: **0-2s left; 2-3s silence; 3-5s right; 5-6s silence**.
  The tone edges fade over 20 ms and peak remains -42 dBFS.
- Available in both Normal mode and Direct USB, with the same encoded WAV files
  and hashes. There are eight WAV variants covering the existing depths/rates.
- Direct USB offers an approximate source-section label. It uses host transport
  position and can lag; it is not a measured DAC timestamp.
- A **Compare the same signal in Normal mode** button opens the normal screen
  with the channel comparison preselected. It does not start sound automatically.
- Reports identify the exact signal pattern and fixture. Original fixtures are
  retained with their unchanged hashes; their intentional both-channel interval
  is now explained explicitly.

USB scheduling, native decoder, hardware volume guards, channel order and
restoration are unchanged. No software balance adjustment, channel swap, default
volume increase or firmware write was added. Clocking remains experimental.

## Short phone comparison

1. Update with [releases/music-usb-probe-0.6.0.apk](releases/music-usb-probe-0.6.0.apk).
   Use M2's Standard preset, lower Android media volume, and stop other audio apps.
2. Open **experimental direct USB playback**. Leave **Equal-tone channel
   comparison** selected. Choose **16-bit / 44100 Hz / 1 min** and start cautiously.
3. It starts at the guarded low hardware level. Use hardware **-30 dB**, or
   **-20 dB only if needed**, then **Seek 0s**. Listen for the pattern above.
   Compare whether the two identical tones sound roughly balanced. There should
   be a full silent gap between them. Record if left remains noticeably softer.
4. Stop/release USB or let the minute finish. Save the report as
   `m2-equal-tone-direct.json`, including your notes and the level used.
   Reconnect before listening if restoration is not verified.
5. Tap **Compare the same signal in Normal mode**. Confirm low media volume and
   select **16-bit / 44100 Hz WAV** with the equal-tone option still selected.
   Repeat is preselected when arriving through that button. Adjust normal media
   volume cautiously to a comfortable level. Compare **left versus right within
   this route**, not absolute Normal loudness versus Direct loudness.
6. Record whether the same left/right difference exists in Normal mode, save
   `m2-equal-tone-normal.json`, and stop playback. Send both reports with your
   observation: equal in both, left softer only in Direct, or left softer in both.

The acoustic comparison is subjective, not a calibrated output-level measurement.
It helps isolate the finding without confusing different frequencies or the
original intentional overlap. Do not start the 61-minute run yet.

## Verification and build

```powershell
python android\scripts\channel_fixtures.py
python android\scripts\test_channel_fixtures.py
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-decode.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build.ps1
```

The new channel checks validate all eight pinned PCM hashes, identical left/right
active samples, exact silent intervals, no simultaneously nonzero channels,
the quiet peak bound, signed values and meaningful low 24-bit data. The actual
native decoder is checked against all 24 original/new WAV/FLAC PCM references,
plus the existing extrema and corrupt-input cases. Original reference files and
their manifest are not regenerated. The new deterministic generator needs only
Python; generated audio remains outside Git.

This is a fixture/UI/report change. Existing native transport and control tests
remain relevant, with no C++ transport change in this version. Physical equal-tone
balance, phone UI behavior and clocking are unverified until the new reports.
