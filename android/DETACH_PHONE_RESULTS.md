# Direct USB detach/reconnect, 2 November 2026

**Functional outcome: pass for unplug-stop, no speaker fallback and no automatic
restart on reconnect**, based on the exported evidence and explicit user reply.
Restoration on the disconnected device remains unavailable, not claimed successful.

Evidence: [m2-direct-detach.json](../m2-direct-detach.json), 0.7.0-background-check,
OPPO CPH1937/API 30, CPH1937_11_F.17, M2 Standard preset. UUID
`c88d0933-6b80-45ea-a10f-80c8587e0303`, PID 29804; started 10:47:23.723 UTC,
final report 10:47:31.329 UTC. Requested 60 seconds at stereo packed PCM24/96 kHz,
channels-24-96000.wav. Source metadata matches the pinned manifest; app reports
decoded reference SHA verified.

The original report's listening notes are blank. In chat, the user answered
**"Yes—stopped, no speaker sound, no automatic restart"** to the explicit combined
unplug/reconnect question. Preserve that separately rather than editing the JSON.

## Disconnection and local cleanup

- interruptionReason **usb_detached**; native terminal phase 6, error **-4**
  (`LIBUSB_ERROR_NO_DEVICE`). No preparation error or PCM mismatches.
- Native wall time 7,048 ms; 6,995 completed nominal service intervals and
  4,029,120 completed bytes (exactly 6,995 x 96 x 6).
- 43 packet errors, 6 transfer errors, 8 cancellations, zero active queue-empty
  events, **zero final pending transfers**, cleanupDelayed=false.
- Submitted 4,087,296 bytes; compared 4,091,904 bytes. The difference is 4,608
  bytes, one 8-packet prepared transfer. PCM is compared before USB submission;
  a failed submission after unplug need not enter submittedBytes. Do not demand
  compared=submitted on this deliberately interrupted error path.
- No pause/seek; final reported hardware readbacks -20 dB on both channels are
  observations from before removal, not verified current levels after reconnect.
- Restore-rate/idle and release/driver-readback operations return no-device;
  settingsRestored=false. The original-volume restoration is blocked because
  safe idle/rate restoration cannot be verified with the device absent.

These error counts are retained as actual detach evidence, not relabeled zero
or treated as proof of corrupt source PCM. The report has only the initial
periodic checkpoint because removal occurred before ten seconds; it does not
provide a timestamped error timeline. Native stoppedByRequest=false is coherent
with a no-device terminal error; the USB broadcast reason records disconnection.
The local transfer queue drained without delayed cleanup. Hardware restoration
and driver state cannot be verified on an absent device. Reconnect before further
listening; a future daily-use UI should describe the disconnect clearly.

## Next check and remaining acceptance

Notification check is currently blocked: user reports no Direct notification in
the shade while audio was still playing. Code review confirms channel
`direct-usb-experiment` (visible name Direct USB experiment), IMPORTANCE_LOW,
foreground notification ID 505 titled Experimental Direct USB, with Stop/release
action. The notification is removed at run end, but the user explicitly checked
during active audio, so expiry alone does not explain this observation.

First inspect Music USB Probe's app notification allowance and this channel's
visibility in phone settings, including silent notification sections. Android 11
does not use Android 13's POST_NOTIFICATIONS runtime prompt. No app-wide/channel
state has been measured yet, so do not assert that notifications were disabled
or that ColorOS hid them. If already enabled and still absent during playback,
collect runtime notification state before claiming a fix. Reference:
[Android channel controls](https://developer.android.com/develop/ui/compose/notifications/channels).
Notification Stop remains unverified; no notification test pass is recorded.

Test notification **Stop and release USB** independently using the existing APK,
24/96 equal-tone WAV, 1 minute: start at a comfortable level, lock the screen,
open the notification shade and use Stop. Sound should stop, the app should show
the run ended, and the report should record notification_stop with cleanup.
Save `m2-direct-notification-stop.json` before another run.

Focus/call behavior and physical cable buttons/microphone behavior remain
unverified. No microphone recording is implemented. Format, controls and sustained
transport evidence do not settle channel balance, exact clocking/DSP/bus PCM or
the separate continuous-hour screen-lock condition. Stage A is still incomplete.

Review checks: parsed original, matched source and native/live counters, verified
explicit detach reason, no-device terminal error, zero pending/no cleanup delay,
completed-byte arithmetic and the one prepared-but-unsubmitted transfer difference.
No code, APK, original report or test signal changed during this review.
