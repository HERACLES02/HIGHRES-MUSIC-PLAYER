# Direct Media3 phone acceptance, 2 November 2026

The user confirms that the requested lock-screen check worked on 0.10.0.
`m2-direct-media3.json` corroborates Pause, Resume and Stop reaching the service-
owned native engine. This closes the previously missing lock-screen-controls
finding for this tested phone/app version, not for every Android device.

## Evidence

- Report SHA-256: `3b0eddb10bda0d2f5cab4b8674940da89946f0ab39be8ac2b77114b372c23d5c`.
- Run: `4999a70f-6834-4c50-b775-56fc760e35ab`, OPPO CPH1937, API 30,
  build `CPH1937_11_F.17`, app `0.10.0-media3-direct`.
- Fixture: `channels-24-96000.wav`, stereo packed 24-bit / 96 kHz;
  decoded reference SHA-256 verified. Standard preset is user-confirmed.
- Requested duration: 60 seconds. Actual native wall time: 27,582 ms, ended
  deliberately by `media3_notification_stop`. This is a controls test, not a
  prematurely failed endurance run or a completed one-minute playback run.
- Media3 Pause at 12:56:08.990 UTC and Resume at 12:56:11.025 UTC; native
  pause count 1. Session created/released; no session remains after cleanup.
- Compared and submitted PCM: 14,759,424 bytes each; PCM mismatches 0.
  Completed bytes: 14,671,296. The smaller completed count and 21 cancelled
  transfers are retained as expected pause/stop cancellation observations.
- Error code, packet errors, transfer errors, pending transfers and active queue-
  empty count all 0; cleanup not delayed; settings restoration verified.
- Final hardware level: -20 dB on each channel. No software attenuation added.

The blank free-text listening field does not erase the user's explicit chat
confirmation. Conversely, the report alone cannot prove lock-screen visibility.
All three ten-second progress samples say interactive=true, so the report does
not independently establish when the screen was locked or how long it stayed
locked. Acceptance combines the user's observation with recorded control events.

The released adapter's `lastNativePhase: 1` is a cleanup-view artifact: service
cleanup observes an empty snapshot with controls disabled. Native final phase is
5 (stopped), error 0. Do not interpret that adapter field as a restarted stream.
The generic native `safeHardwareVolumeEstablished: false` summary is not a new
failed volume test: this fixture path reports `lowVolumeControlVerified: true`
and exact channel set/readbacks. Retain both raw fields without rewriting them.

## Scope and next work

No APK or native code change was needed to record acceptance. Existing format,
61-minute, detach and manual-control results remain separate evidence. No repeat
of those tests is requested for this finding.

Direct remains an experimental nominal-rate fixture transport: missing-feedback
clocking, left/right listening balance and unknown DAC processing are not resolved
by media controls. Do not claim unconditional bit-perfect delivery or mark every
Stage A gate complete. The planned player can reuse this service/adapter pattern;
the complete Normal/Direct coordinator, file playback and daily-use UI remain
implementation work, with final Direct availability subject to its open gates.
