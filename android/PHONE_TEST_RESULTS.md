# OPPO/M2 normal-route results reviewed 2 November 2026

Sources, preserved unchanged:

- `../m2-preflight-standard.json`, captured 1 November 2026 at 18:04:08 UTC.
- `../m2-normal-baseline-standard.json`, exported at 18:09:51 UTC
  (2 November at 00:09:51 in Dhaka).

Both reports identify 0.2.0-baseline on OPPO CPH1937 / Android 11, build
CPH1937_11_F.17. Fixture requests record the user-selected Standard preset.
The baseline has 207 retained events, no dropped events, and 19 fixture requests.
The user's saved observation says: “All of the tests worked with locked screen,
and detach”. This observation is distinct from the automatically recorded facts.

## Normal-route evidence

All 16 distinct bundled fixtures have a run that reached playing, routed to
`USB-Audio - KZ Acoustics M2` (device type 22), and reached playback state 4
(ended). An initial 16/48 WAV run was superseded early; its later run completed.
There are no recorded playback errors, sink errors or Android underruns.

| Source format, WAV and FLAC | AudioTrack rate | AudioTrack encoding |
| --- | --- | --- |
| Stereo 16-bit / 44.1 kHz | 44,100 Hz | 2: PCM 16-bit |
| Stereo 16-bit / 48 kHz | 48,000 Hz | 2: PCM 16-bit |
| Stereo 16-bit / 88.2 kHz | 88,200 Hz | 2: PCM 16-bit |
| Stereo 16-bit / 96 kHz | 96,000 Hz | 2: PCM 16-bit |
| Stereo 24-bit / 44.1 kHz | 44,100 Hz | 2: PCM 16-bit |
| Stereo 24-bit / 48 kHz | 48,000 Hz | 2: PCM 16-bit |
| Stereo 24-bit / 88.2 kHz | 88,200 Hz | 2: PCM 16-bit |
| Stereo 24-bit / 96 kHz | 96,000 Hz | 2: PCM 16-bit |

The app-side AudioTrack is not fixed to 48 kHz in these tests. Its sample rate
matches each source rate. Every track configuration reports stereo channel mask
12, encoding 2 and offload false. The baseline path delivers 16-bit PCM to
AudioTrack even for the 24-bit sources. This characterizes this build's normal
playback path; it does not establish a phone-wide 16-bit limitation.

These are Android client-side configurations and route callbacks. Physical USB
rate, physical USB sample representation, sample delivery and internal cable
processing remain unmeasured. Capability encoding 4 in the USB device's supported
arrays must not be substituted for the active AudioTrack encoding or treated as
a physical bit-depth measurement.

## Screen lock, detach and restart

The repeated 16/44.1 FLAC run has playing progress with `interactive=false` at
18:08:28.009 and 18:08:38.054 UTC, followed by playing progress with the screen
interactive again at 18:08:48.087. This supports short screen-off playback,
alongside the user's observation. It does not establish a two-minute locked
test, long-term ColorOS background behavior, or the required 60-minute Direct
USB test.

At 18:08:53.533, `usb_detached` records playback paused around 4.0 seconds. At
18:08:54.459, `output_removed` also records paused playback. No playing event
appears between removal and the next explicit `fixture_requested` at
18:08:58.288. That explicit 24/96 FLAC request subsequently routes to the phone
speaker (type 2), not the M2. It is not an automatic restart and is not additional
evidence of M2 playback. No reconnect event/USB route after removal is present,
so reconnect behavior is not established by this export. Calls, media buttons
and notification actions are not individually identified in the recorded notes.

## UAC1 preflight evidence

Descriptors match the original 347-byte M2 report and have no parser warnings.
Playback still advertises asynchronous OUT 0x04 without an associated explicit
feedback endpoint. No measured link speed or successful clock/rate control
transaction is added by this report.

Feature Unit 7 advertises per-channel volume. All eight attempted requests
(GET_CUR/MIN/MAX/RES on channels 1 and 2) return -1, with null values.
`queriesSkipped=false` confirms that the code attempted the queries. No current
volume, limits or resolution were successfully retrieved. No volume writes
occurred and safe hardware volume remains unestablished.

The Java API's -1 does not identify the underlying cause. It does not prove that
hardware volume is unsupported. The probe did not claim the AudioControl
interface, and these reports do not distinguish ownership restrictions, USB
request failure, timeout or device behavior. Do not issue volume writes based on
these missing values.

## Stage decision and next implementation

A1 now has physical evidence for all 16 normal-route fixture playbacks, short
screen-off playback and detach pause. A2/A3/A4 remain open. The high-resolution
goal is unchanged; Direct USB feasibility has neither passed nor been disproved.

Next, implement the guarded native connection/ownership diagnostic with pinned
libusb and native tools. Stop/release normal playback first, use Android's granted
fd, retain actual libusb errors, discover negotiated link speed, and determine
the supported clocking arrangement. Retrieve and validate hardware volume under
the required ownership before attempting a low SET/readback. Interface claims
must be scoped, cleaned up and restored; do not acquire capture/HID for timing.
Do not expose direct audio before safe volume and scheduling are established.

There is no reason to ask the user to repeat the same normal fixtures now.
The next phone APK should resolve the new native diagnostic questions. Stage B
and the daily-use interface remain behind the accepted Direct USB gate.

This review changed documentation only. Original reports, audio code, APK and
local-build evidence were preserved. No new build or regression run is needed
for these documentation updates; the reports do not validate unimplemented
native code.
