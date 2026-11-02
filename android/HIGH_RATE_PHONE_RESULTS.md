# 24-bit/96 kHz phone follow-up, 2 November 2026

## Later steady-playback check

[m2-direct-24-96000-uninterrupted.json](../m2-direct-24-96000-uninterrupted.json)
records the same pinned 24/96 equal-tone fixture and phone/app/preset, with
60,000 completed intervals and 34,560,000 completed bytes in 60,030 ms wall time.
No pause or seek occurred. Submitted and compared bytes both total 34,589,952;
PCM mismatches, packet/transfer errors, active queue-empty count and final pending
transfers are zero. Seven cancellations occur before the first ten-second sample,
which shows both hardware channels at -20 dB; this is consistent with the initial
level adjustment. Five subsequent health checks and restoration are recorded.
Both kernel drivers report active after release; cleanup was not delayed.

Maximum callback gap is 28,067 us: the previous two-second gap did not recur.
This does not independently establish the cause of the earlier gap. Retained
progress samples at 08:25:48 through 08:26:28 UTC show interactive=false with
advancing completed audio; the earlier two samples are interactive=true. Thus
short screen-off playback is evidenced, not a full minute continuously locked.

User note: "no unexpected silence, crackling or dropout, just the persistent
issue of left side being a bit lower than right side". The short steady-playback
stability check passes on these observations. Acoustic balance remains unresolved
and this does not establish device clocking or end-to-end PCM delivery.

Review checks: parsed the original JSON, matched source metadata to the pinned
manifest, checked native/live final statistics agreement, exact completed-byte
arithmetic, zero errors/pause/seek, screen-off progress and restoration. No code
changed or new APK was built. The next physical gate is the existing 61-minute
24/96 screen-off selection, with no seek/pause and no level changes after initial
setup. Save the final JSON and note glitches, interruptions and left/right balance.
All-format checks and balance acceptance still remain before daily-use approval.

The following sections describe the earlier run with a seek.

Reviewed originals:

- [m2-direct-24-96000.json](../m2-direct-24-96000.json), SHA-256
  `1593f14dd1f05a6ff2ceb14c193fd2e6f406472b848aaa4a6a715726436529a7`.
- [music-normal-24-96000.json](../music-normal-24-96000.json), SHA-256
  `e5aacea9d3b0c20ba305ad47dc462e97eb05259e6adb86c8c6384a0dbe61563c`.

Both use app 0.6.0-channel-check on OPPO CPH1937/API 30,
CPH1937_11_F.17, Standard M2 preset. This is report review, not a new build or
an independently observed physical test.

## Direct USB

Fixture channels-24-96000.wav, stereo packed PCM24 at 96 kHz, requested 60 seconds.
Source metadata and SHA references exactly match the pinned channel manifest;
the app reports decoded PCM SHA verified.

| Observation | Recorded value |
| --- | --- |
| Wall time | 62,082 ms |
| Completed intervals / bytes | 60,000 / 34,560,000 |
| Submitted / compared bytes | 34,621,632 / 34,621,632 |
| PCM mismatches / packet errors / transfer errors | 0 / 0 / 0 |
| Active queue-empty count / final pending | 0 / 0 |
| Cancelled transfers / seeks / pauses | 14 / 1 / 0 |
| Maximum callback gap | 2,019,598 microseconds |
| Final hardware readbacks | -20 dB, both channels |
| Volume health checks | 6 |
| Settings restored / delayed cleanup | true / false |

Completed bytes equal 96,000 frames/s x 60 s x 6 bytes/frame exactly. Extra
submitted bytes are not equivalent to completed audio: cancellation/reconfiguration
occurred. Idle alternate, original rate and volumes were restored; interfaces
released and both kernel drivers reported active. The eight initial unclaimed
volume GET failures precede successful claims, as in earlier reports.

The maximum gap rises from 33,452 us to 2,019,598 us in the same retained progress
window that adds a seek and seven cancellations. Another seven cancellations
occurred earlier while the hardware level changed. The implementation explicitly
cancels/drains and reconfigures for these commands. Temporal association does not
prove the entire two-second gap was an intended seek interruption. There is no
timestamped command log or active-gap timeline in this report. Listening
clarification is pending; do not label this an uninterrupted minute.

User note: "Runs normally on -20dB with left side being a bit lower than right side".
Every retained progress sample has interactive=true: no screen-off evidence here.

## Normal route

Isolate service run `c043130b-8bb0-4dc8-b5ac-c6bd66380a66`, test
`e734bf65-3c6d-4473-99d1-abf3d355a6eb`, starting 08:13:10 UTC, from older cumulative
history. It uses the same channels-24-96000.wav, looping, software volume 1,
speed 1 and no app EQ/ReplayGain/crossfade. Recorded route is the M2; Android
AudioTrack is stereo PCM16 at 96 kHz. Physical USB bit depth/rate remain unknown.
No new playback/sink error or focus interruption is recorded.

User note: "this one seems normal on both left and right side". This newer note
must be retained alongside the earlier Normal 16/44.1 left-weak observation;
it does not establish that balance is identical across formats or gain settings.

## Decision and next task

Chat clarification before this test: the supplied 16/44.1 WAV also sounded a bit
left-quiet in another player; no other compatible earphones are available.
That particular finding is not unique to this APK, and no hardware fault has
been diagnosed. The new Direct 24/96 note still describes a slight difference
while Normal 24/96 sounds balanced; absolute route loudness is not matched.
Do not add speculative channel compensation.

The short high-rate transport/restore observations are positive. Clarify whether
the roughly two-second gap was audible only on Seek or happened unexpectedly.
If unexplained, use a one-minute 24/96 run with no seek/pause and no level changes
after the initial adjustment, retaining listening notes. Do not classify the
existing commanded run as uninterrupted endurance evidence. The subsequent
61-minute selection provides margin for the required 60-minute screen-off test;
it remains unperformed, as do the remaining all-format physical checks.

No report establishes end-to-end unchanged delivery, DAC clock stability, absence
of device DSP, or daily-use Direct USB readiness. Nominal pacing remains
experimental and Stage A is incomplete.

Verification performed: parsed originals; checked both source manifests against
the pinned channel manifest, final native/live statistics agreement, zero error
counters, exact completed-byte arithmetic and restoration flags. Inspected
native cancellation/seek and callback-gap code. No application source changed.
