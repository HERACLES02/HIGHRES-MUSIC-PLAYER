# Direct USB clock and balance investigation, 7 November 2026

First increment: source/report audit and local verification. Clock synchronization
and acoustic balance remain unresolved. No transport change or new APK is justified
by this evidence. Direct remains experimental in Music USB Probe, separate from
Local Music. No phone test was performed by the agent.

Subsequent resource check: the user confirms no recording equipment is available.
See [published-source follow-up and unsent manufacturer questions](M2_CLOCK_SOURCE_RESEARCH_2026_11_07.md).
No exact-firmware clock solution was established; physical balance remains open.

Broader follow-up: [other players' actual USB code and offline comparison](DIRECT_USB_APP_SOURCE_COMPARISON_2026_11_07.md).
TrueSample's parser agrees on the M2 topology; all eight nominal packet sequences
matched across 56,000 packets. NeriPlayer's policy rejects async UAC1 without
supported feedback. Neither observation establishes physical clock correctness.

PC follow-up: [first filtered Windows USB trace](M2_WINDOWS_USB_TRACE.md).
The same descriptor hash was verified on the PC. A short, uncontrolled Windows
stream selected alternate 2 then 96 kHz and requested 1,080 nominal 576-byte slots.
No isochronous IN stream was observed. All retained requests completed successfully;
this is host-policy evidence, not a controlled source, render-clock or balance test.
The capture is stopped. A subsequent [controlled Windows capture](M2_WINDOWS_CONTROLLED_RESULT_2026_11_07.md)
used a verified timed shutdown method: both complete equal-channel source streams
matched OUT submissions exactly at 16/44.1 and 24/96, with nominal packet sequences
and no observed feedback. This establishes a host-side reference, not a physical
clocking solution or analog balance measurement. No further phone check requested.

## Evidence and practical investigation paths

| Question | Established evidence | Unknown | How to investigate / limits |
| --- | --- | --- | --- |
| Explicit feedback | Exact 347-byte descriptor: playback interface 2, alternate 1/2, one isochronous OUT `0x04`, attributes `0x05`, interval 1, `bSynchAddress=0`. No accompanying synch IN endpoint. Native reports identify full speed. | Whether firmware has undocumented timing behavior or incorrectly describes an adaptive/synchronous sink. | Vendor documentation for this exact firmware, or a trace of a working driver plus render-clock measurements. Descriptor interpretation alone cannot select a firmware workaround. |
| Implicit feedback | Capture interface 1 has asynchronous IN `0x84`, mono 16/24-bit at 48 kHz only; no advertised playback association. | Whether capture and playback clocks are coupled, including the 44.1-kHz family; what ratio, if any, applies. | Requires documented clock topology or independent correlated clock measurements. Existing design excludes microphone timing; do not claim capture or add it as a workaround. A capture endpoint's existence does not establish a feedback source. |
| Other device clock information | UAC1 endpoint class attributes `0x01`: sampling-frequency control present, pitch control absent. No UAC2 clock entities. Rate SET/GET succeeds for all eight formats. | Live render rate, FIFO occupancy, dropped/repeated samples, internal SRC. | Vendor-supported telemetry or external electrical/digital measurements. GET_CUR returns a configured frequency, not a measured clock error. No supported live clock-error input was found in current code or descriptors. |
| Host scheduling / buffering | Integer fractional accumulation; 8 transfers of up to 8 one-ms packets; callback refill, cancellation/drain and periodic volume guards. | DAC FIFO health and render-clock synchronization. | Current fake-boundary tests establish payload order and failure handling. Host transfer completion and callback cadence cannot measure the DAC FIFO or its sample consumption. |
| Equal source channels | All eight pinned quiet channel fixtures have byte-identical active left/right samples, separated in time; 440 Hz, -42 dBFS, 20 ms fades. Native decode hashes and channel-order tests pass. | Physical rendered samples and analog channel gain. | A USB bus analyzer can observe bus payloads; an external stereo ADC with suitable input attenuation/load can compare analog outputs. Each measures a different boundary. |
| Equal hardware settings | Reviewed stream operations and final readbacks are equal for both channels, including 365 periodic checks in the 61-minute run. | Whether equal reported dB produces equal attenuation under the earphone load. | Measure left/right RMS at equal source level/settings and the same load. Swap measurement input channels and repeat to separate ADC input error. Software readbacks cannot calibrate analog gain. |
| Listening balance | Earlier 16/44.1 left weakness occurred in Normal, Direct and another player; later 24/96 Normal sounded balanced while Direct was slightly left-quiet. | Route gain, DAC analog behavior, earphone/contact/fit, hearing and low-level threshold effects. | Existing observations do not isolate a cause. No alternate compatible earphones or external calibrated capture are established as available. Preserve the findings; do not diagnose hardware failure or add compensation/swaps. |
| Stability / restoration | Accepted 61-minute 24/96 run, all-format short runs, controls, detach/restoration and Media3 controls; no unexpected glitches noticed in the long run. | Clock correctness, actual render format, DSP bypass, end-to-end unchanged samples. | Preserve accepted results. A repeat hour without a new observable would not answer these questions. |

## Source trace and clocking decision

Sources: [descriptor parser](usb-core/src/main/java/com/localmusic/usb/UsbDescriptors.java),
[packetizer](native-usb/packet_pcm.hpp), [transport](native-usb/stream_transport.hpp),
[native entry/configuration](native-usb/diagnostic.cpp),
[volume transaction](native-usb/control_test.hpp),
[Android descriptor guard](app/src/main/java/com/localmusic/diagnostics/NativeUsbProbe.kt).

Both raw descriptor exports, `music-usb-probe (1).json` and
`m2-preflight-standard.json`, hash to
`0a6173002455ffed2bc84b2146b3968719ddaa26d62db0cd7723f70f597a1436`,
matching the current Android guard. This verifies the inspected layout against
the saved exports; it is not a fresh enumeration of the attached DAC.
Playback terminal 5 advertises stereo left/right positions (`wChannelConfig=3`),
followed by Feature Unit 7 with master mute and separate channel volume.
There is no advertised playback mixer/processing unit in that path; internal
unadvertised DSP remains unknown. Capture uses its own terminal/Feature Unit.

The packetizer emits floor/ceiling nominal frames per millisecond:
44/45 at 44.1 kHz, 48 at 48 kHz, 88/89 at 88.2 kHz, 96 at 96 kHz.
The host controller schedules the USB service intervals; this is not a thread
sleeping for one millisecond. The 64 queued packet intervals provide about 64 ms
of host scheduling coverage. This is not a measurement of device buffer capacity.
Endpoint capacities are 388/582 bytes (97 stereo frames), leaving payload room
above 96 nominal frames, but packet capacity supplies no clock measurement.

The source cursor advances when packets are prepared. After cancel/drain, the
engine resumes from host-completed position; seek/reconfiguration resets its
fractional phase. That explains why reconfigured fractional-rate runs can differ
by a frame from uninterrupted nominal totals. Neither the UI position nor the
submission fingerprint is a DAC render timestamp or a continuous-output hash.

[UAC1 sections 3.7.2.2, 4.6.1 and 5.2.3.2.3](https://www.usb.org/sites/default/files/audio10.pdf)
define the asynchronous sink's synch path and its 3-byte 10.14 feedback value,
endpoint control bits, configured sampling frequency, and adaptive pitch control.
The M2's descriptors provide no such associated feedback path. This discrepancy
does not prove whether firmware drops samples, adapts internally, or describes
its synchronization type incorrectly. None of those hypotheses is established.

The pinned [Linux v6.12 implicit-feedback implementation](https://github.com/torvalds/linux/blob/v6.12/sound/usb/implicit.c)
contains explicit device quirks and a generic neighboring asynchronous-IN search;
it has no `31b2` match. That is a bounded source observation, not evidence about
the OPPO kernel or proof that no working driver can support the M2. Its heuristics
do not establish a shared clock for this hardware. Do not copy a neighboring-IN
heuristic into this player as physical validation.

An open-loop illustration: at 96 kHz, a hypothetical 100 ppm DAC/USB clock
difference accumulates 9.6 stereo frames/s, or 35,136 frames (366 ms) over 3,660 s.
This is arithmetic, not an estimate of this DAC's error. It demonstrates why
completed intervals and zero host errors cannot bound render drift. Increasing
the queue, timing callbacks, or periodically rereading the selected rate supplies
no missing feedback signal. No new synchronization method has been established.

## Balance investigation

[Pinned fixture checks](scripts/test_channel_fixtures.py) independently compare
the active left/right sequences and assert zero inactive channels, signed samples,
24-bit low bits and hashes. [Native decode checks](scripts/test-decode.py) compare
the real WAV/libFLAC decoder with independent hashes and distinct-channel signed
extrema. Transport tests check exact interleaved submitted bytes across all eight
formats. The current packetizer copies complete stereo frames without gain or
channel reordering. These local boundaries provide no evidence of a left-only
digital attenuation in the inspected path; they cannot rule out device processing.

`ControlTest::run` deliberately preserves an existing quieter channel instead
of raising it to -40 dB. Thus startup targets can be unequal on another session;
never infer equality merely from the nominal startup label. Explicit -30/-20 dB
commands validate and write the same target to both channels, then read back both.
The reviewed reports below actually record equal settings. This safety behavior
must not be removed just to force balance, and it does not explain an analog
imbalance when both channels read back equally.

Reparsed original JSON and matched each complete source object to the current
channel manifest; verified zero mismatch/packet/transfer/active-queue-empty/final-
pending counters, submitted=compared bytes and verified restoration:

| Export | Completed intervals / bytes | Final channel raw levels | Periodic volume checks |
| --- | --- | --- | --- |
| `m2-equal-tone-direct.json` | 60,000 / 10,583,996 | -5120 / -5120 (-20 dB) | 6 |
| `m2-direct-24-96000-uninterrupted.json` | 60,000 / 34,560,000 | -5120 / -5120 | 5 |
| `m2-direct-24-96000-61min.json` | 3,660,000 / 2,108,160,000 | -5120 / -5120 | 365 |
| `m2-direct-media3.json` (newest of these exports; requested Stop) | 25,471 / 14,671,296 | -5120 / -5120 | 2 |

Equal channel values also hold for the retained stream SET/readback/periodic
operation pairs, not just the final summary. The long report SHA-256 remains
`d461024e3c73c0946c43637b3259e7c0763b259118d45c97a18348c58e7a9259`.
Blank original notes remain blank; long-run listening acceptance comes from the
user's chat, as preserved in [ENDURANCE_PHONE_RESULTS.md](ENDURANCE_PHONE_RESULTS.md).
See [CHANNEL_PHONE_RESULTS.md](CHANNEL_PHONE_RESULTS.md),
[HIGH_RATE_PHONE_RESULTS.md](HIGH_RATE_PHONE_RESULTS.md),
[FORMAT_PHONE_RESULTS.md](FORMAT_PHONE_RESULTS.md) and
[MEDIA3_DIRECT_CHECK.md](MEDIA3_DIRECT_CHECK.md) for the accepted context.

## Next useful evidence, without another endurance run

Completed [read-only Android driver access preflight](USB_CLOCK_ACCESS_CHECK.md):
the user-run report confirms the correct CPH1937/API 30 target, but card-list and
PCM diagnostic reads are permission-denied. No stream diagnostic was obtained.
Do not request a live snapshot, wireless ADB setup, root or a repeat of this
preflight. Public driver/firmware research needs a verified build match; an
initial targeted search did not establish one. Physical clocking and analog
balance still require suitable measurement evidence. Flick comparison remains
on hold after the reported locked-volume and loud-output behavior.

Follow-up: [Flick source comparison and short independent-Direct listening steps](FLICK_USB_REFERENCE_2026_11_07.md).
Its inspected scheduler also uses nominal timing without feedback; no M2-specific
clock solution was found. A confirmed Flick Direct route can add an independent
software path to the balance comparison, without resolving physical clocking.

Clocking: the next discriminating input is exact-firmware vendor clock/feedback
documentation, or a trace of a working host driver correlated with independently
measured output timing. A desktop host software trace may reveal control requests
and packet-size policy, but it describes that host and does not prove the OPPO
path or physical rendering. A physical USB analyzer can observe the phone's bus;
an analog recorder can measure rendered timing/gain but cannot prove original
digital bit depth or bit-perfect samples. No such equipment is assumed available.
During the initial source/report increment, no vendor command, capture read,
driver installation or external message was made. Subsequently, approved USBPcap
installation and the first filtered PC capture were completed as documented above.
No vendor command or external message was sent.

Balance: a controlled analog comparison needs suitable stereo capture inputs,
attenuation and a known load; do not plug a headphone output into an unspecified
microphone input. Use the existing generated equal-tone fixture, identical hardware
levels, Standard preset and no effects; measure RMS in the middle of each active
section, excluding fades. Repeat with capture inputs exchanged. Report the measured
gain ratio and uncertainty, separately from listening. This can establish gain
imbalance under that load, not its internal cause. Until that measurement or a new
isolating observation exists, no additional phone listening repetition is requested.

## Verification performed for this increment

Run from `D:\music_player`; all commands below passed on the current source:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-core.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-stream.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-native.ps1
python -m unittest discover -s android\scripts -p test_channel_fixtures.py
python android\scripts\channel_fixtures.py
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\test-decode.ps1
```

Results: 3,052 parser assertions (including 3,000 malformed cases), 22 native
transport scenarios, 31 native ownership/control scenarios, one Python test
covering all eight equal-channel references, eight pinned WAV/hash checks,
24 native WAV/FLAC hashes plus signed-extrema/channel-order and 18 rejection cases.
These are software checks and report analysis. No new behavioral fix, dependency
change, APK build, lint run or physical measurement occurred. Existing Local Music
0.11.0 EQ edits and unrelated Flask edits remain outside this increment. The user's
Poweramp graphic import/audible-toggle acceptance does not validate parametric
filters or USB output; the 0.10.9 physical seek check remains pending.
