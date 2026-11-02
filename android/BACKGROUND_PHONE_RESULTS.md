# Interrupted endurance attempt, 2 November 2026

## Follow-up: 0.7.0 exit-history export received

[m2-background-stop.json](../m2-background-stop.json), exported 08:59:13.674 UTC,
successfully includes this app's process-exit history (processExitReadError=null).
The original attempt correlates with the following same-package exit:

- 08:37:09.807 UTC / 14:37:09.807 Bangladesh time, PID 7656.
- reasonCode **9**, Android `REASON_EXCESSIVE_RESOURCE_USAGE` (system resource kill).
- description **`o-kill:6`**, status 0, importance 125.
- 6.993 seconds after the original checkpoint's capturedAt, 379.026 seconds
  (6 min 19 s) after its first saved sample. The old checkpoint lacks PID/run ID;
  this correlation is by package and time, not a verified run-ID match.

This is evidence of system termination, rather than a recorded USB failure or
Java/native crash for that exit. Do not decode undocumented `o-kill:6` as a
specific battery policy, conclude high CPU from the category alone, or claim a
proven app fix. The triggering threshold/resource is not exposed by this bundle.
API definition: [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo#REASON_EXCESSIVE_RESOURCE_USAGE).

Current Android observations are batteryOptimizationExempt=false,
backgroundRestricted=false, lowMemoryKillReportingSupported=false. The second
flag does not establish absence of vendor power-management rules; the first is
a reason to try a per-app exemption, not proof of what caused the kill.

Other exit entries belong to different events: 08:51:34 `remove task` and
08:58:40 `installPackageLI`. Keep these distinct from the 08:37 resource kill.

The bundle also contains a **newer, separate** 0.6.0 playback attempt from
08:53:36.879 through a final report at 08:58:01.072. It requested 3,660 seconds,
ran 263,710 ms wall time, has stoppedByRequest=true, one seek, zero recorded
PCM/packet/transfer errors and verified restoration. interruptionReason is
`none`: the old UI's direct native Stop command does not annotate this field,
so native stoppedByRequest must be considered too. No assumption is made about
which action initiated it. This newer run replaced the saved files before the
0.7.0 export, which correctly labels latestEvidence=completed_report. Original
uploaded files remain the evidence for the first resource-killed attempt.

## Next controlled phone check

Keep 0.7.0 installed. In Music USB Probe's App info / Battery usage, enable
**Allow background activity** if available. In the phone's battery-optimization
list, exclude only Music USB Probe using **Don't optimize / No restrictions**.
Menu names vary on ColorOS versions. These are a controlled configuration trial,
not a guarantee that vendor resource enforcement or an app resource issue is fixed.
Microsoft's [OnePlus/Oppo background guidance](https://support.microsoft.com/en-us/defender/web-protection-keeps-turning-off-on-my-android-device)
describes these settings for another persistent background app on Android 13;
the exact Android 11 menu path is not independently confirmed here.

Then repeat 61 minutes at 24/96 with initial comfortable hardware-level adjustment
only, no seek/pause, and the screen locked. Export the final report if it finishes;
if it stops, immediately export the background-stop diagnosis before another run.
New 0.7.0 run IDs/PIDs and requested duration will support better correlation.
Endurance remains unpassed; acoustic balance and clocking remain open.

Review verification: parsed original/new files; checked the 6.993-second exit
correlation and 379.026-second start-to-exit span; confirmed the newer saved run's
checkpoint matches its final progress, stoppedByRequest and restoration, and
separated update/task-removal exits. No code changed or new APK was built for
this review. The earlier sections below document the prior evidence state.

Evidence: [m2-direct-stream-incomplete.json](../m2-direct-stream-incomplete.json)
and [m2-direct-stream.json](../m2-direct-stream.json). The user reports that the
intended hour-long test stopped unexpectedly; elapsed time is unknown to them.

## What the files establish

The incomplete checkpoint is newer than the final report. It contains 38 saved
samples for channels-24-96000.wav, from 08:30:50.781 to 08:37:02.812 UTC: **372.031
seconds of wall time (6 min 12 s)**. Its capturedAt is 08:37:02.814 UTC.
The final saved transport sample reports 371,626 completed nominal intervals,
214,056,576 completed bytes, phase 2 (active), seven pending transfers and no
recorded PCM mismatch, packet/transfer error or active queue-empty event.
Maximum callback gap is 46,584 us. No seeks or pauses are recorded.

Samples are screen-interactive initially, then non-interactive from 08:32:11.173
through the last checkpoint: approximately 4 min 52 s of observed screen-off
progress. A screen lock therefore did not immediately stop playback. These are
last saved observations, not an exact stop time: progress after the final saved
checkpoint is unknown. This 0.6.0 partial schema has no requested duration,
process ID, run ID, stop reason or verified restoration result.

The separately exported final report is dated 08:26:29.007 UTC and requested
only 60 seconds. Its transport result and progress match the earlier
[uninterrupted minute](../m2-direct-24-96000-uninterrupted.json); it is not a final
report for the later endurance attempt. Exporting an older successful result
does not establish cleanup or success for the newer interrupted run.

An abruptly killed/crashed process or a blocked finalization path is consistent
with the missing final report. Battery restrictions, low memory, native crash,
or other causes are not established. No automatic restart or speculative
battery exemption is added. Endurance acceptance has not passed.

## Diagnostic update: 0.7.0-background-check

- New **Save background-stop diagnosis** exports the saved checkpoint, saved
  final report and their dates together; a newer checkpoint is explicitly marked.
- Reads this app's own Android API 30 historical process-exit records, including
  time, PID, platform reason code, status and description. No other app history,
  microphone data, logcat or crash trace contents are collected. Empty/error
  results are retained rather than interpreted as success.
- Exports current battery-optimization exemption and Android background-restricted
  flags as observations, not conclusions about ColorOS-specific policy.
- New stream checkpoints include run UUID, PID, start time, requested duration
  and any recorded stop reason. Final reports carry matching run identifiers.
- No audio engine, PCM, hardware-volume policy, foreground service/wake-lock
  behavior or dependencies changed. This adds diagnosis, not a claimed fix.

Android retains only a bounded recent exit history. Installing an APK update can
add an exit entry of its own; correlate times/PIDs, and do not equate an update
or generic kill signal with the earlier failure. References:
[ActivityManager API](https://developer.android.com/reference/android/app/ActivityManager#getHistoricalProcessExitReasons(java.lang.String,%20int,%20int)),
[ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo).

## Phone handoff

Locally verified APK: 70,509,336 bytes, SHA-256
`ab3ee925f94dd5ec3dc58266ecd5313763d40829e86a15b8ffbb6a2e7bba40bc`.
Build/parser/fixture checks and lint pass (0 errors); signature matches prior
updates. Native libraries and all fixtures are unchanged. Process-exit collection
still needs this phone export to establish what the OS retained.

1. Install [music-usb-probe-0.7.0.apk](releases/music-usb-probe-0.7.0.apk) as an
   update to Music USB Probe. Do not uninstall or clear app data: the saved
   evidence must remain available.
2. Open Direct USB and tap **Save background-stop diagnosis**, without starting
   another playback test. Save `m2-background-stop.json` and return it.
3. If restoration of the interrupted run was not verified, unplug/reconnect M2
   before using audio again. The update never resumes playback automatically.

Do not repeat the hour-long test before reviewing this bundle. If Android has
not retained a useful exit record, the richer new checkpoints support a targeted
follow-up; they cannot retroactively supply a reason for the old checkpoint.
