# Direct USB controls check, 2 November 2026

Evidence: [m2-direct-controls.json](../m2-direct-controls.json), app
0.7.0-background-check on OPPO CPH1937/API 30, build CPH1937_11_F.17, M2 Standard.
Run UUID `8edad7a5-599c-43eb-8116-e312d819cebd`, PID 29804. Fixture is the pinned
channels-24-96000.wav, stereo packed PCM24/96 kHz. Source metadata and decoded
reference hash verification agree with prior runs.

User confirmation in chat: **"all of the controls worked as expected"** after
the requested pause/resume, Seek 0s/3s and Stop/release sequence. The original
report's userObservation is blank and is retained unchanged. This supports
functional control acceptance for this diagnostic route and fixture, not full
daily-use MediaSession or physical button support.

| Observation | Recorded value |
| --- | --- |
| Requested duration / native wall time | 60 s / 58,619 ms |
| End condition | stoppedByRequest=true |
| Completed service intervals | 52,237 |
| Completed bytes | 30,088,512 |
| Submitted / compared bytes | 30,440,448 / 30,440,448 |
| Pause count / seek count | 3 / 8 |
| Cancelled transfers | 86 |
| PCM mismatches / packet errors / transfer errors | 0 / 0 / 0 |
| Active queue-empty count / final pending | 0 / 0 |
| Maximum callback gap | 18,527 us |
| Final hardware channel readbacks | -20 dB / -20 dB |
| Delayed cleanup / settings restored | false / true |

Progress includes phase 3 (paused) at two retained checkpoints and subsequent
active phases. Completed bytes equal 52,237 x 96 x 6 exactly. Pause/reconfiguration
and explicit Stop mean this is not a failed attempt to complete 60 seconds or an
uninterrupted reference hash. Cancellation counts reflect drained transfers during
commands; they are separate from zero recorded USB errors. The source-section
label remains approximate and was excluded as an accurate audio timing reference.

Final operations verify restoration of original idle/rate/volume settings,
release of playback/control interfaces and both kernel drivers active afterward.
Native/live final statistics agree. `interruptionReason=none` is not used to
infer absence of Stop: the 0.7.0 direct native UI stop command does not populate
that string; the authoritative native stoppedByRequest flag records it.

## Next physical check

Use the existing APK at 24/96, equal-tone WAV, 1 minute. After initial comfortable
level selection, unplug M2 from the phone while audio plays. The experiment must
stop, with no speaker fallback. Reconnect without pressing Play; it must remain
silent. Save `m2-direct-detach.json` before starting another test, plus observations.

A disconnected device may make rate/volume restoration and driver readbacks
unavailable and produce no-device/cancellation results. Retain that evidence;
do not equate a disconnected-device restoration failure with a failed cleanup
on a still-connected device. Reconnect M2 before subsequent listening when
restoration is unverified. No automatic resumption is allowed.

Focus/call, notification controls and reconnect behavior remain unverified until
their own reports/listening observations. The short eight-format and sustained
results do not close balance, continuous-hour lock, clocking/DSP or end-to-end
unchanged-delivery limits. Stage A remains incomplete.

Review verification: parsed the original, matched the pinned source, checked
native/live counters, exact interval-byte arithmetic, pause/seek counts, requested
stop, restoration and driver readbacks. No source code, original report, APK or
test audio changed; no additional physical test was run during review.
