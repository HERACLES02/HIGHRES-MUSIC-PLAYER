# M2 native preflight: physical results reviewed 2 November 2026

Source: `../m2-native-preflight.json`, preserved unchanged. Captured
1 November 2026 at 19:56:06 UTC / 2 November at 01:56:06 in Dhaka.
Version 0.3.0-native-diagnostic, OPPO CPH1937, Android 11, build
CPH1937_11_F.17. Standard preset was confirmed by the user in the test UI.

## Results

| Observation | Reported result |
| --- | --- |
| libusb initialization without enumeration | Success |
| Wrap Android's granted USB fd | Success |
| Device/configuration guards | Passed |
| Negotiated link speed | Enum 2: USB full speed, 12 Mbps |
| Android driver before claims, control/playback | Active / active |
| All 8 volume GETs without a claim | LIBUSB_ERROR_IO; supplementary errno 16 (EBUSY) |
| Claim AudioControl interface 0 | Success |
| All 8 volume GETs after that claim | Exact 2-byte successful responses |
| Claim playback interface 2 | Success |
| Current playback alternate GET | 0, idle |
| Endpoint sample-rate GET | 48,000 Hz |
| Release playback, then control | Both successful |
| Android driver after release, control/playback | Active / active |

Both channels report identical hardware-volume attributes:

| Attribute | Raw signed 1/256 dB | Converted |
| --- | --- | --- |
| Current | 0 | 0 dB |
| Minimum | -12,800 | -50 dB |
| Maximum | 0 | 0 dB |
| Resolution | 128 | 0.5 dB |

0 dB is no attenuation; it is not muted. These are GET results, not verified
effects of volume writes. A low SET_CUR and exact readback on both channels
remain necessary before direct test audio. No safe-volume result is established
by this report alone.

Successful GETs include supplementary errno 11 (EAGAIN). This does not make
them failures: libusb returned the exact requested byte counts and valid data.
The native report explicitly states that libusb results are authoritative and
errnoAfterCall is supplementary. The Android NDK's errno definitions confirm
16 as EBUSY and 11 as EAGAIN.

The failed unclaimed requests followed by successful claimed requests strongly
support Android driver/interface ownership as the reason the earlier read-only
volume probe could not obtain values. Hardware-volume support is now backed by
successful reads rather than descriptors alone.

## Limits and gate decision

Native fd wrapping, temporary ownership, hardware-control reads and return to
active Android drivers now have physical evidence on the unrooted target phone.
Reported active drivers do not independently establish restored audible normal
playback, microphone or cable-button behavior; those observations were not
included in this JSON.

The 48 kHz endpoint value was read while alternate 0 was idle. It is not a
running-stream measurement, a maximum rate, or proof of an Android 48 kHz limit.
Full-speed USB identifies the link used for future scheduling calculations; it
does not validate transport scheduling or asynchronous clock synchronization.

All report flags for audio submission, volume writes, sample-rate writes,
alternate writes and capture/HID claims are false. No direct PCM, clocking,
rate-setting or safe-volume SET/readback test was performed. The missing explicit
feedback arrangement is still unresolved. Stage A remains open and the
high-resolution goal is unchanged.

## Next implementation

1. Use these physical volume ranges to build the guarded no-audio low-volume
   SET/readback test on both channels, including failure and cleanup behavior.
   Stop on an invalid range, failed/short response or mismatched readback.
2. Validate sample-rate SET/readback and alternate selection with restoration.
   Derive endpoint timing from the measured full-speed link and descriptor
   interval/packet limits. Resolve the supported asynchronous clocking arrangement
   before treating any scheduler as viable. Do not use microphone capture for
   timing or silently assume fixed packets are sufficient.
3. Only then enable quiet native test PCM with bounded isochronous transfers,
   exact PCM comparisons and exportable transport diagnostics. All eight formats
   and the 60-minute 24/96 screen-off test remain outstanding.

No repeat of this ownership/GET-only test is needed now. No new APK or audio code
was produced during this report review. The original reports and local-build
verification artifacts were preserved.
