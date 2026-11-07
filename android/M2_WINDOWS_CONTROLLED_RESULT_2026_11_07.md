# Controlled Windows M2 USB capture, 7 November 2026

This increment establishes an independent **host-side** reference. It does not
resolve the M2's physical clock synchronization or analog balance. Direct stays
experimental in the separate USB Probe; no Local Music or transport source changed.

## Method

Fresh USBPcap enumeration located KZ Acoustics M2 at USBPcap1/address 1; Windows
PnP confirmed VID `31b2`, PID `0311`. The capture filter selected only address 1,
with no whole-hub or newly connected-device capture. No microphone was opened.
This capture did not inject descriptors; the earlier trace already matched the
347-byte Android descriptor hash. Addresses must be verified again after reconnect.

The original CLI's console-key shutdown did not work in the agent's background
session. Two disposable GUI console probes reproduced the failure without opening
the DAC. That approach was abandoned. A local helper now uses the installed
USBPcap driver's public API from matching 1.5.4.0 source: set snapshot/buffer,
start a single-address filter, read with overlapped I/O, stop filtering at a fixed
deadline, drain/cancel reads and close its own handles. This controls the capture
driver, not DAC firmware. It starts no elevated capture worker. No device reset
or driver reinstallation was needed.

A two-second idle test returned exit 0 and a valid 24-byte PCAP header after
2,141 ms. The controlled capture then stopped itself after 12,125 ms. A subsequent
shutdown-wrapper check returned helper/wrapper exit 0 after 2,125 ms, again with
only a PCAP header. No capture/playback/probe processes remained afterward.

Playback used an independently written local WASAPI helper that requires exactly
one active output named KZ Acoustics M2 and has no default-output fallback. It
reads endpoint settings, refuses muted/unequal stereo settings, and never writes
volume. Windows reported stereo, unmuted, hardware-support mask 3 and both channel
levels **-22.3514 dB** before each stream. These are Windows API values; the trace
contains no USB hardware-volume GET/readback. Do not label them fresh UAC readbacks.

The disposable fixtures contain three seconds of identical left/right 997 Hz
samples, about -72 dBFS peak, including silent ends and short fades. The helper
also checks equal channel bytes and a quiet sample limit before opening a stream.
It requests exclusive format support and playback at the source rate. Packed
24-bit was accepted with `WAVEFORMATEXTENSIBLE`; the simple `WAVEFORMATEX` query
was rejected. This API-format distinction is not a DAC capability limitation.
See Microsoft's [format initialization documentation](https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudioclient-initialize).

Fixtures and helpers are under ignored `android/.tools`; the user's music was
never opened or changed. No listening result or physical measurement is claimed.

## Retained evidence

All artifacts below are local and Git-ignored under `android/releases/usb-clock/`:

- `m2-controlled-20261107.pcap`: 2,467,806 bytes, SHA-256
  `0628c64ac53c027b3ab4f6c4d7b18efe6b7f607435415a1d85ad24960f86ad60`.
- `m2-controlled-20261107.verified.json`: exact-source comparison and per-format
  packet evidence; `.summary.json` retains general control/endpoint metadata.
- `m2-controlled-20261107.capture.log`, `.capture-errors.log`, `.play16.log`,
  `.play24.log`: native capture readiness/clean stop and playback/settings logs.
- `m2-timed-selftest-20261107.*` and `m2-timed-wrapper-check-20261107.*`:
  bounded idle shutdown evidence.

The initial controlled experiment's PowerShell wrapper returned exit 1 despite
both native playback logs reporting success and the native capture reporting
clean stop. Its precise wrapper-level cause was not established. The revised
wrapper retains the process handle, explicitly logs exit status, and exits 0 on
success; the subsequent idle wrapper test verified both statuses were 0. The
original controlled capture was not repeated or overwritten to hide that status.
Its contents were verified independently as follows.

## Results and limits

| Check | 16-bit / 44.1 kHz | Packed 24-bit / 96 kHz |
| --- | --- | --- |
| Windows controls | Interface 2 alternate 1, then endpoint rate `44 ac 00`; idle alternate 0 afterward | Interface 2 alternate 2, then endpoint rate `00 77 01`; idle alternate 0 afterward |
| Submitted source | 132,300 stereo frames / 529,200 bytes | 288,000 stereo frames / 1,728,000 bytes |
| Requested slots | 3,000: 2,700 at 176 bytes and 300 at 180 bytes | 3,000 at 576 bytes |
| Sequence | Nine 44-frame slots, then one 45-frame slot, repeating | 96 frames every slot |
| Nominal scheduler comparison | Exact sequence match | Exact sequence match |
| Captured OUT submission PCM | Byte-for-byte match to entire generated source | Byte-for-byte match to entire generated source |
| Left/right bytes | Identical throughout | Identical throughout |
| Completion status | 300 audio URBs / 3,000 slots, all zero status | 300 audio URBs / 3,000 slots, all zero status |

All 1,212 records were complete and all 606 requests had matching completions,
including control requests. Only bus 1/address 1 was present. Audio completion
start frames advanced by 10 for each ten-slot URB within each stream. No
isochronous IN, feedback, vendor command or extra clock-control transaction was
observed. Requested slot lengths come from OUT submission offsets/payload, not
the zero length fields of OUT completions. The matching public USBPcap header
explicitly says packet length is unused for isochronous OUT.

PCM SHA-256 values, verified against the complete generated source bytes:

- 16/44.1: `00b3738ff3034f66a50dab6484d16ed3a06214d97986f1a655111c9b314a8f4b`.
- 24/96: `a890650e89027b30f6f9b4f9ec4833ed1fb4e48bd519ec28556cb7458f5e31d4`.

**Clocking:** Windows' short-stream host policy matches the current diagnostic's
nominal packet sizing, including fractional-rate phase. It supplies no new clock
error signal or feedback algorithm to implement. It does not establish whether
the M2 adapts internally, drops/repeats samples, or describes its synchronization
type inaccurately. Host completion timing and WASAPI buffer consumption do not
measure the DAC oscillator. No synchronization fix is justified by this capture.

**Balance:** This independently verifies equal digital channels at the Windows
submission boundary. Together with prior Android fixture/transport/readback
evidence, it provides no basis for a channel swap or compensation patch. The PC
test cannot establish the Android DAC's analog channel gain, earphone/contact
behavior or hearing/fit effects. The Windows endpoint API values are a separate
evidence level from native UAC readbacks and physical RMS measurements.

USBPcap observes [host URBs rather than physical bus transactions](https://desowin.org/usbpcap/capture_limitations.html).
Exact captured submission bytes do not prove physically transmitted or rendered
samples, internal DSP bypass, render-clock accuracy or end-to-end bit-perfectness.

## Verification and remaining gate

Run from `D:\music_player`:

```powershell
python android\.tools\usbpcap\make_quiet_fixtures.py
python android\.tools\usbpcap\analyze_controlled.py
python android\.tools\usbpcap\check_controlled_analyzer.py
```

All passed. The analyzer accepted the exact source and rejected three disposable
trace copies: a changed PCM byte, a truncated record, and nonzero USB status.
Fixture generation checks equality/hashes and refuses existing fixture PCM changes.
Helpers compiled with MinGW `-Wall -Wextra`; no APK or native app code changed,
so unchanged app suites/builds were not repeated. Documentation links/whitespace
were checked separately.

No further phone listening or endurance run is requested. Without recording
equipment, analog gain remains unmeasured. The next discriminating clock input
would be exact-firmware vendor information or suitable physical timing evidence;
another nominal software trace would not resolve that gate. The unsent vendor
questions remain in [M2_VENDOR_CLOCK_QUESTIONS.txt](M2_VENDOR_CLOCK_QUESTIONS.txt).
No external message was sent and no new equipment is assumed available.
