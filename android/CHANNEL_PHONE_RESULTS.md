# Equal-tone phone results reviewed 2 November 2026

Later follow-up: the user confirms the supplied 16/44.1 WAV is also a bit quieter
on the left in another player; no alternative compatible earphones are available.
The requested comparison is complete. A newer 24/96 Direct/Normal pair is reviewed
in [HIGH_RATE_PHONE_RESULTS.md](HIGH_RATE_PHONE_RESULTS.md); its Normal note says
both channels seem normal, while Direct remains slightly left-quiet. The sections
below retain the earlier comparison and original next-step rationale.

Evidence: [Direct report](../m2-equal-tone-direct.json) and
[Normal report](../m2-equal-tone-normal.json), app 0.6.0-channel-check on OPPO
CPH1937, Android 11/API 30, build CPH1937_11_F.17. Standard M2 preset was
user-confirmed. Exports are dated 1 November UTC / 2 November Bangladesh time.

## Listening result

Direct note: "All good except the sound only works for left on -30dB and -20dB
and the sound is lower compared to right".

Normal note: "All good but left side doesn't work on low volume, the volume has
to be turend high to listen to left side".

Both notes concern the same channels-16-44100.wav fixture: identical 440 Hz
active samples, left then right, separated by silence. The low-volume left
audibility problem is therefore present in both routes. This does not identify
the cause or establish a defective cable/earphone. It does not support treating
the problem as isolated to the native Direct engine. Subjective channel balance
has not passed; no channel swap or compensating software gain is justified.

## Direct transport observations

- Requested 60 seconds at stereo 16-bit/44.1 kHz; wall time 60,046 ms,
  60,000 completed service intervals. All retained progress samples are
  screen-interactive; this is not screen-off endurance evidence.
- Decoded PCM SHA verified against the fixture reference. Submitted and compared
  bytes both 10,603,220; PCM mismatches, packet errors, transfer errors and active
  queue-empty counts all zero. Final pending transfers zero; no delayed cleanup.
- Both channel hardware levels read back equally at -40, -30 and -20 dB;
  six volume health checks. Fourteen cancelled transfers accompany the two
  hardware-level changes. The implementation cancels/drains before reconfiguring
  volume; these cancellations are separate from transfer errors.
- Completed bytes 10,583,996, versus 10,584,000 for an uninterrupted nominal
  minute. Preserve this four-byte/one-stereo-frame difference: packet fractional
  phase resets on reconfiguration, and queued cancelled submissions need not
  complete. Do not treat the aggregate as an uninterrupted full-minute hash.
- Restoration verified: idle alternate 0, original 96 kHz rate, original 0 dB
  channel levels, interfaces released, both kernel drivers active afterward.

These are application/transport observations. Device buffer health, physical bus
PCM, render clock and internal DSP remain unmeasured. Experimental nominal
scheduling without feedback has not become validated clocking.

## Normal route observations

The export contains older sessions. This review isolates the new service run
`d21cf87f-3d4d-414b-a680-b52c60ba958c` from 21:28 UTC onward: three 16/44.1 requests
and one 24/96 request, all equal-tone WAV. Recorded routes identify KZ Acoustics
M2 (Android device type 22). AudioTrack uses stereo PCM16 at the respective
44.1/96 kHz source rate; physical USB format remains unknown. No new audio-focus
interruption is recorded. A detach at 21:29:23 records playback paused; another
explicit fixture request follows reconnection.

The export's root `directUsb` string is an outdated static Normal-report label
from before native streaming was implemented, not a current native test result.
Older screen-lock notes and focus events are not evidence from this comparison.

## Next gate

User follow-up: other music players sound normal with this hardware. This is
relevant counterevidence to a hardware explanation; no hardware fault is established.
Ordinary music and the diagnostic's deliberately quiet -42 dBFS tone are different
inputs. First compare the exact same fixture in another player, using the same
M2 preset and Android media-volume setting and with player effects off.

The handoff file [channels-16-44100.wav](releases/channels-16-44100.wav) is copied
byte-for-byte from the generated reference and verified against both the pinned
manifest and the asset inside the 0.6.0 APK. Transfer it to the phone and open it
in the player that sounds normal. Its pattern is left 0-2s, silence 2-3s, right
3-5s, silence 5-6s. Compare within each route at a comfortable level; do not
increase volume just to make the weaker side audible.

If the same file is balanced in the other player, investigate the diagnostic
Normal route/settings before asking for more hardware substitutions. If it is
also left-weak there, the finding is not unique to this APK; the low-level signal
and common output path still need isolation. Neither outcome alone identifies
the exact cause. No new APK or gain compensation is required for this comparison.

Keep the long 24/96 screen-off test on hold until the balance observation is
understood. All-format listening, interruption/reconnect tests, sustained
clocking and 60-minute playback remain Stage A gates before the full player.

Review verification: parsed both original JSON exports, isolated the recent
Normal session, compared fixture metadata and native counters, and inspected
volume reconfiguration/packet-phase code. No application code changed and no
new build or phone test was performed during this review.
