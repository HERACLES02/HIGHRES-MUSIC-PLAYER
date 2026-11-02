# Remaining six stereo format runs, 2 November 2026

Listening follow-up received: user says **"it was normal, just the mismatch of
silent and left and right text"**. Record normal playback for all six, retaining
the previously known approximate-label mismatch and balance observation. The
blank original report notes remain unchanged. Statements below requesting
confirmation describe the earlier report-only review.

All six requested 12-second WAV experiments completed with zero recorded
PCM/packet/transfer errors and verified restoration. The requested alternate
and sample rate read back correctly. Together with the prior 16/44.1 and 24/96
reports, transport observations now cover every advertised stereo depth/rate.
The six exported listening-note fields are blank; listening confirmation is
requested separately and must not be inferred from counters.

All use 0.7.0-background-check on OPPO CPH1937/API 30, CPH1937_11_F.17, M2 Standard
preset confirmed, native WAV decoding and gap-separated equal 440 Hz signals.
Native configuration is stereo 16-bit alternate 1 or packed 24-bit alternate 2
at the selected rate. The physical link remains full-speed.

| Source | Wall time | Completed bytes | Difference from nominal 12 s | Final hardware level |
| --- | --- | --- | --- | --- |
| [16/48](../m2-direct-16-48000.json) | 12,029 ms | 2,304,000 | 0 | -20 dB, both |
| [16/88.2](../m2-direct-16-88200.json) | 12,027 ms | 4,233,596 | -4 bytes / one stereo frame | -20 dB, both |
| [16/96](../m2-direct-16-96000.json) | 12,021 ms | 4,608,000 | 0 | -20 dB, both |
| [24/44.1](../m2-direct-24-44100.json) | 12,029 ms | 3,175,194 | -6 bytes / one stereo frame | -20 dB, both |
| [24/48](../m2-direct-24-48000.json) | 12,020 ms | 3,456,000 | 0 | -30 dB, both |
| [24/88.2](../m2-direct-24-88200.json) | 12,037 ms | 6,350,394 | -6 bytes / one stereo frame | -20 dB, both |

Each records 12,000 completed service intervals, no seek/pause, no requested stop,
no preparation error or focus interruption, zero active queue-empty events,
zero final pending transfers and no delayed cleanup. Submitted and compared byte
totals agree, with zero PCM mismatches; encoded/decoded reference hashes are
verified by the app and reported metadata matches the pinned local manifest.
Both kernel drivers report active after playback/control release.

All six include seven cancellations with hardware-level reconfiguration. These
are counted separately from errors. Fractional packet phase is reset when the
engine seeks its source cursor during reconfiguration; aggregate counts at
44.1/88.2 kHz can differ by one stereo frame from an uninterrupted nominal run.
Retain those differences instead of claiming exact nominal completion for every
case. No reference comparison reports a PCM mismatch, but a submitted-byte
comparison includes cancelled/retried queued data and is not an independent bus
or DAC render capture. Callback gaps range from 14.078 to 21.193 ms.

## Combined advertised-format coverage

| Stereo depth | 44.1 kHz | 48 kHz | 88.2 kHz | 96 kHz |
| --- | --- | --- | --- | --- |
| 16-bit | Earlier short/minute WAV reports | New short WAV | New short WAV | New short WAV |
| 24-bit | New short WAV | New short WAV | New short WAV | Earlier short WAV/FLAC and 61-minute WAV |

See [CHANNEL_PHONE_RESULTS.md](CHANNEL_PHONE_RESULTS.md),
[HIGH_RATE_PHONE_RESULTS.md](HIGH_RATE_PHONE_RESULTS.md) and
[ENDURANCE_PHONE_RESULTS.md](ENDURANCE_PHONE_RESULTS.md) for the earlier cases
and their listening observations. These do not imply every FLAC container/rate
has physically played: decoding references cover all local fixtures, while phone
Direct FLAC evidence currently includes the earlier 24/96 short run.

## Remaining gates

Confirm the six listening outcomes (speed/glitches, retaining known balance
observations). Next physical checks are controls and interruption handling:
pause/resume/seek/stop, USB detach/reconnect, focus/calls and notification controls.
Separate rate runs demonstrate reconfiguration after release, not seamless
in-stream rate switching. No new APK is needed for the existing diagnostic controls.

Channel balance, comfortable hardware-volume acceptance, a continuously locked
hour, device clocking/internal buffers/DSP and end-to-end bus delivery remain
unresolved or unmeasured. Nominal pacing remains experimental; neither all-format
transport coverage nor the previous clean sustained run alone approves daily-use
Direct USB or an unconditional bit-perfect claim.

Review checks: parsed the six originals, compared references and final/live
counters, verified format/rate readback and restored driver state, checked byte
arithmetic and retained rounding/cancellation differences.
[FORMAT_PHONE_VERIFICATION.json](FORMAT_PHONE_VERIFICATION.json) preserves per-file
SHA-256 values and counter summaries. Original files are unchanged; no code, build
or physical test was performed during review.
