# 61-minute Direct USB result, 2 November 2026

**Outcome: sustained 24/96 transport run completed successfully with no recorded
PCM/USB errors, restored settings, and no unexpected listening glitches noticed.**
Screen-off playback is observed across repeated long intervals; a continuous
60-minute screen-off interval is not established by this run. Direct mode's
remaining physical-format, channel-balance and clocking gates remain open.

## Evidence and listening

- [m2-direct-24-96000-61min.json](../m2-direct-24-96000-61min.json), SHA-256
  `d461024e3c73c0946c43637b3259e7c0763b259118d45c97a18348c58e7a9259`.
- [m2-background-stop (1).json](../m2-background-stop%20(1).json), SHA-256
  `ba2f08146e840eba8843d630bbf370192f6796399c704df5b9c09340676955cc`.
- Follow-up in chat after report review: **"No unexpected glitches noticed"**.
  The exported report's userObservation is blank; that original file is unchanged.
- Earlier mid-run chat: approximately 0.2-second mismatch between source-section
  label and heard audio, including sound while the label says silence. The label
  uses host transport position and is approximate; a display mismatch is not a
  physical clock/latency measurement or evidence of channel leakage. The label
  needs better presentation before the daily-use UI. Do not silently reinterpret
  that observation as a measured, fixed 200 ms audio latency.

App 0.7.0-background-check; OPPO CPH1937, API 30, CPH1937_11_F.17;
M2 Standard preset confirmed. Fixture channels-24-96000.wav, stereo packed
24-bit PCM at 96 kHz. Source metadata matches the pinned manifest; app reports
decoded PCM SHA verified. Initial level was increased to comfortable -20 dB;
the earlier low-level left-audibility and slight imbalance observation is not
resolved or erased by this stability result.

## Recorded result

Run UUID `a377e594-eca8-4381-aa2b-45a04d21e22d`, PID 29804. Start
09:04:36.409 UTC / 15:04:36 Bangladesh; final report 10:05:37.126 UTC /
16:05:37 Bangladesh. Native streaming wall time **3,660,025 ms**. No requested
stop, preparation error or recorded interruption; no pause or seek.

| Observation | Value |
| --- | --- |
| Requested duration / completed service intervals | 3,660 s / 3,660,000 |
| Completed bytes | 2,108,160,000 |
| Submitted / compared bytes | 2,108,192,256 / 2,108,192,256 |
| PCM mismatches / packet errors / transfer errors | 0 / 0 / 0 |
| Active queue-empty count / final pending | 0 / 0 |
| Cancelled transfers | 8 |
| Maximum callback gap | 59,145 us |
| Volume health checks | 365 |
| Final channel hardware readbacks | -20 dB / -20 dB |
| Delayed cleanup | false |
| Settings restored | true |

Completed bytes equal `96000 x 3660 x 2 x 3` exactly. Cancelled transfers appear
with the initial level adjustment; subsequent retained samples do not accumulate
more cancellations. Submitted bytes include queued/cancelled/retried data and
cannot be substituted for a bus capture or a simple uninterrupted source hash.
The 59 ms callback-gap statistic is a host observation, not a measured DAC dropout;
device-internal underruns are unavailable and the user noticed no unexpected glitches.

Cleanup reports original idle/rate/volume restoration, successful release of
playback/control interfaces, and both kernel drivers active afterward. Capture
and HID were not claimed. `configurationMatrixPassed=false` does not represent
eight failed formats: the configuration matrix was not requested in this stream
test. Conservative daily-use safety/clocking approval fields remain false.

## Screen-off and background observations

All 363 checkpoint samples were retained (none evicted); 344 are non-interactive,
19 interactive. The longest contiguous sequence of non-interactive samples spans
09:14:20.175 to 09:26:36.469 UTC, **736.294 s (12 min 16 s)**. Other non-interactive
spans are approximately 5:22, 2:21, 8:26, 10:58, 6:46 and 10:39. Sampling is
approximately every ten seconds; these are observed spans, not exact lock events.
The maximum checkpoint gap is 10.166 s. A continuously locked hour is unverified.

The matching background bundle reports current PID 29804,
batteryOptimizationExempt=true, backgroundRestricted=false, successful exit-history
read, and no same-PID exit during/after this run in the retained list. Its final
report and checkpoint UUID match the main report. Earlier `o-kill:6`, task-removal
and package-install exits belong to prior processes, not this completed run.

This is positive evidence after per-app power-setting changes. It does not prove
which setting prevented the earlier kill, or establish reliability under every
call, focus, storage, device-power or ColorOS condition.

## Acceptance and next task

The prior background/endurance blocker has a successful sustained-run counterpart;
do not mark Stage A fully complete. The next short physical gate is the remaining
rate/depth combinations, followed by rate-change/detach/reconnect and interruption
checks. Retain failures as well as successes. A fully continuous 60-minute
screen-off interval remains a separate unverified condition; do not claim it
passed or silently order another long run merely to replace this evidence.

Channel balance and safe comfortable volume remain open observations. Scheduling
is still experimental nominal pacing without feedback. Device render-clock/DSP,
internal buffer health, independent USB bus PCM, and end-to-end unchanged delivery
are not measured. No unconditional bit-perfect or daily-use Direct approval follows.

Review verification: parsed both originals; matched fixture metadata to the
pinned reference, native/live final counters, exact completed-byte arithmetic,
run UUID/PID association, restoration and exit chronology. Calculated screen-state
segments and checkpoint gaps. No app code, test audio or original report changed;
no new APK or physical test was performed during this review.
