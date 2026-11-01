# First physical Direct USB audio results: 2 November 2026

## Follow-up clarification

The user now reports left is audible at hardware -30 and -20 dB, although softer
than right. Both sides become quieter at -30 than at -20, as expected from those
requested control levels. The user also confirmed overlap occurred **only** in
the original intentional both-channel section (seconds 4-6). That overlap is
expected, not evidence of channel leakage or a timing defect.

The original categorical missing-left description below is therefore historical
and superseded by this clarification. Residual balance remains unverified: the
original fixture uses different frequencies (left 440 Hz, right 660 Hz), which
does not provide a controlled equal-tone loudness comparison. No routing/gain
correction is justified yet. A same-frequency, gap-separated test in both Normal
and Direct routes is being prepared in 0.6.0; see CHANNEL_CHECK.md. Keep the
long test on hold until that comparison is clear. No new physical JSON for the
higher-level follow-up has been supplied.

Sources, preserved unchanged:

- `../m2-direct-16-44100-wav.json`, exported 1 November at 20:51:15 UTC.
- `../m2-direct-24-96000-flac.json`, exported at 20:52:34 UTC.

Both are 0.5.0-stream-experiment on OPPO CPH1937 / Android 11,
CPH1937_11_F.17, user-confirmed Standard preset. Local times are 2 November,
02:51 and 02:52 in Dhaka.

## Functional result: audibility clarified, balance pending

The initial notes in both reports described quiet sound and missing left audio.
Follow-up confirmed left works normally with the same cable/earphones and is
also audible in Direct at -30/-20 dB. Overlap occurred only during the intended
both-channel section. The unresolved finding is perceived left/right balance,
to be compared using the equal-tone fixture. This is not an established broken
earpiece or channel-routing defect. Do not approve Stage A or proceed to the
long run based on the transport counters alone.

## Transport and control observations

| Test | Frames submitted | Bytes submitted/completed/compared | Wall time |
| --- | --- | --- | --- |
| Stereo 16/44.1 WAV, 12 sec | 529,200 | 2,116,800 each | 12,010 ms |
| Stereo packed 24/96 FLAC, 12 sec | 1,152,000 | 6,912,000 each | 12,006 ms |

Both runs have:

- Exact encoded/native-decoded PCM checksums verified before streaming.
- `audioSubmitted=true`, 12,000 completed USB service intervals and 1,500 callbacks.
- Zero reported PCM mismatches, transfer errors, packet errors, app queue-empty
  events, cancellations and pending transfers at completion.
- No pause, seek, focus interruption or requested stop; no delayed cleanup.
- Both hardware channels read back -40 dB, including the periodic check.
- Verified original settings restoration and successful interface releases;
  both Android drivers report active afterward.
- Only the eight expected failed **unclaimed** volume GETs; no failed claimed
  controls or cleanup operations.

The two maximum host callback gaps are 16,801 and 17,951 microseconds. These are
app-side scheduling observations, not device-buffer or audible-underrun measures.
Both tests were screen-interactive; they provide no locked-screen or long-run
evidence. `configurationMatrixPassed=false` is expected because these stream runs
do not execute the separate eight-configuration silent-control matrix.

## Independent PCM/channel verification

The canonical WAV references were reopened independently of the native stream
code. Their PCM SHA-256 values match each report's source reference. FNV-1a was
then recomputed over **two complete reference loops** and compared to the actual
submission fingerprint:

| Reference | Independently computed and reported FNV-1a64 |
| --- | --- |
| 16/44.1, two loops | 12480063100677089829 |
| 24/96, two loops | 13859186721580611389 |

Both match. Reference sample analysis also confirms nonzero left-only audio in
seconds 0-2, nonzero right-only audio in seconds 2-4, and both in seconds 4-6.
Active left/right RMS values are effectively equal: approximately 182.62/182.63
sample units for 16-bit and 46,801.54/46,801.51 for 24-bit. Both channels are
present in the expected submitted PCM, including meaningful signed 24-bit values.

This narrows the investigation: there is no evidence of left-channel silencing
in the decoded/reference bytes or the application's submitted byte sequence.
It does not establish how the DAC renders those bytes, nor rule out a control,
format, device-routing or level-related issue. FNV is a noncryptographic diagnostic
fingerprint, not an independent physical bus capture.

## Historical initial level check (now superseded by equal-tone comparison)

The source is already quiet (-42 dBFS peak), and neither run used the available
hardware-level buttons: both stayed at -40 dB. Very low audibility is therefore
plausible, but it cannot by itself explain or dismiss the missing-left observation.

Before changing the stream layout or inventing a device quirk, request one short
controlled check in the **existing** APK: 16/44.1 WAV, 1 minute, cautiously select
hardware -30 dB (then -20 dB only if needed), and seek to 0s so the left-only
opening can be heard at that level. Record whether left becomes audible and
save the JSON to verify actual applied levels. Avoid a long run until stereo
playback works. The direct path's hardware buttons are relevant; raising Android
media volume alone need not affect direct USB.

If left remains missing at the verified higher diagnostic level, investigate
channel routing/control behavior with a deliberately labeled same-frequency
channel-identification test. Do not apply a speculative channel swap, gain change
or firmware write as a music-playback fix. Clocking remains unresolved separately;
zero transfer errors and roughly 12-second wall times do not establish sustained
device clock stability or internal DSP bypass.

This review changes documentation only. No new APK or playback fix has been
produced, and the first physical runs are explicitly retained as stereo failures.
