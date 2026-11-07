# M2 Windows host USB trace

Latest increment: [controlled quiet-fixture result](M2_WINDOWS_CONTROLLED_RESULT_2026_11_07.md).
Reliable timed capture now works. Windows OUT submissions exactly matched both
generated equal-channel sources at 16/44.1 and packed 24/96; both packet sequences
were nominal, with no observed feedback stream. Physical clocking/balance gates
remain unresolved. The first-trace shutdown limitation below is historical; do
not reuse that CLI launch method.

## Current result: first trace, 7 November 2026

After the user restarted Windows, `sc.exe query USBPcap` reported **RUNNING**.
Windows PnP identity confirmed `VID_31B2&PID_0311`. Enumeration located that M2
on `\\.\USBPcap1`, device address **1**. The capture explicitly selected only
address 1, with descriptor injection; neither whole-hub nor new-device capture
was enabled. Re-enumerate before future captures: addresses can change.

Saved locally, Git-ignored:

- `android/releases/usb-clock/m2-windows-enumeration-20261107.pcap`
- 660,830 bytes; SHA-256
  `a6627223a93dda996f9f9249c1651b4b3ad3c7bad4e6d1c18c03cb950ab29e57`.
- Metadata-only analysis beside it: `m2-windows-enumeration-20261107.summary.json`.

The trace contains an incidental audio stream of about 1.08 seconds. **Its source
is unknown**; the agent did not initiate playback or identify a generated fixture
in this stream. Do not use its PCM for a source-to-output or balance comparison.
No listening or physical clock measurement was performed.

| Observation | Evidence | Meaning / limit |
| --- | --- | --- |
| Same advertised M2 layout | Injected device descriptor 18 bytes plus configuration 329 bytes hash to `0a6173002455ffed2bc84b2146b3968719ddaa26d62db0cd7723f70f597a1436`, exactly matching the saved Android descriptor | Confirms descriptor agreement across hosts; injected enumeration context is not a newly captured physical enumeration sequence |
| Windows selects playback first, then rate | `SET_INTERFACE` interface 2 alternate 2, then endpoint `SET_CUR` (`22 01`, value `0100`, index `0004`, data `00 77 01` = 96,000 Hz); eventually interface 2 alternate 0 | Standard UAC1 sequence, compatible with the current diagnostic's order; selected rate is not a measured render clock |
| Nominal requested payload sizes | 108 OUT submissions to `0x04`, 10 slots each, 622,080 payload bytes total; every slot is 576 bytes = 96 stereo frames at packed 24-bit | This short Windows segment uses nominal 96 kHz payload sizing; it does not demonstrate drift correction or prove nominal pacing sufficient |
| No observed timing input | No isochronous IN endpoint traffic and no additional vendor/clock requests in this trace | No explicit or implicit feedback stream was observed in this interval; cannot exclude another operating mode or internal firmware behavior |
| Host completion status | All 108 audio URBs and 1,080 completion slots report status zero; completion start frames advance by 10 for each 10-slot URB | Successful host completion, not physical rendering proof |
| Complete retained records | All 228 records parse without truncation; all 114 requests have matching completions, including final idle selection | Useful complete software trace; capture termination itself needed a forced process stop as described below |
| Balance and hardware levels | No channel-volume requests are present in this short stream; source PCM and initial hardware volume were uncontrolled | Cannot establish equal hardware settings, acoustic gain or channel correctness from this capture |

Windows OUT completion records here contain zero payload bytes and zero packet
length fields. Requested slot sizes above come from **submission offsets and
the captured submission payload**, not completion lengths. Zero completion
lengths must not be interpreted as silent output, lost audio or an underrun.
The local analysis helper initially tried to derive completion slot sizes and
produced invalid negative sizes; a failing check exposed that interpretation.
It now derives sizes only for submissions carrying payload. The original PCAP
was never modified. An independent record walk verified all request pairs and
zero statuses, accounting for reuse of IRP identifiers after completion.

### Historical first-capture shutdown and follow-up

USBPcapCMD's `q` command requires a real Windows console input handle. The agent's
headless terminal did not supply one, so sending `q` there did not stop capture.
The parent and elevated capture worker were stopped explicitly; stopping the
worker required Windows administrator elevation. Subsequently checked: **no
USBPcapCMD process remained**. No USB device reset was used. The file remained
complete and contained the audio stream's idle transition before capture ended.

Do not repeat that unattended launch method. Before another capture, use a real
interactive Windows console with working `q`, or validate a bounded controller
that can stop its own capture reliably. Then capture a known, generated quiet
fixture with explicitly selected M2 output/format and recorded equal hardware
settings. This would separate intentional playback from system sounds and allow
host-side channel-byte checks; it still cannot measure analog balance or prove
the M2's internal clock arrangement. No further phone action or endurance run is
requested for this increment.

Follow-up completed: the [controlled result](M2_WINDOWS_CONTROLLED_RESULT_2026_11_07.md)
documents a different timed-driver method and its verified shutdown, fixture
bytes, packet sequences and limitations.

Local verification commands (investigation helpers are Git-ignored):

```powershell
python android\.tools\usbpcap\summarize_trace.py android\releases\usb-clock\m2-windows-enumeration-20261107.pcap
python android\.tools\usbpcap\check_summary.py
```

Both passed after the helper correction. No application source changed, so no
APK rebuild or repeat of the accepted native/phone checks was needed.

## Original preparation and method

The user confirms the M2 can connect to the existing PC. This does not need an
audio recording interface. The purpose is to observe Windows' control requests
and isochronous transfer layout for the same M2. It cannot establish analog
balance, physical bus integrity, render-clock accuracy or unchanged DAC output.

## Preparation verified

Before installation, no USBPcap service/driver or Wireshark installation was found
in the standard locations, PATH and installed-program entries checked.

Downloaded the installer linked from the [official USBPcap site](https://desowin.org/usbpcap/):
`android/.tools/usbpcap/USBPcapSetup-1.5.4.0.exe`.
SHA-256: `87a7edf9bbbcf07b5f4373d9a192a6770d2ff3add7aa1e276e82e38582ccb622`.
Windows Authenticode result: **Valid**, signature verified; signer Tomasz Mon
(the certificate spells the surname Mon with a Polish accent), with a Symantec
SHA256 timestamp signer. This establishes the downloaded signature, not universal
driver compatibility with every Windows build.

Reviewed matching installer/CLI source tag 1.5.4.0, revision
`1a8893cf4b704a9812a82440ef2e476e194cdd65`, retained under ignored `.tools`.
The installer requests administrator privileges, installs the USBPcap filter driver
and command-line program, and registers the USB filter. Its optional USB-3 setup
initializes driver matching. This changes Windows outside the repository and
requires installation approval. A normal Windows restart is required afterward;
do not restart Windows or reset all USB devices automatically.

USBPcapCMD's no-argument interactive mode can offer to restart all USB devices if
the driver has not started. Do not accept that shortcut. It also defaults to
whole-hub capture in interactive mode: do not start a capture that way. Use explicit
M2-only selection below after installation/restart.

## Capture outline after installation and restart

1. Connect M2 to the PC with earphones kept out. Leave Flick/phone tests stopped.
2. Enumerate root-hub interfaces with `USBPcapCMD.exe --extcap-interfaces`, then
   inspect their `--extcap-config` output to identify the M2's hub/device address.
   Confirm the M2 identity before capture; addresses change on reconnection.
3. Capture only that address with `--devices`, not all devices or all newly
   connected devices. Inject the connected M2's descriptors. Use a fresh local
   output under ignored `android/releases/usb-clock/`.
4. Start a short playback of the existing generated quiet fixture through the
   Windows M2 output, after confirming the selected output and low Windows volume.
   No listening is required; keep earphones out. Stop after about 15-20 seconds.
   Use a real Windows console and stop with USBPcapCMD's `q` command. First verify
   shutdown works there; the agent's headless terminal did not support this.
   No ordinary music or microphone test is needed.
5. Review the control requests, alternate/format changes, OUT packet-length
   distribution and any IN timing stream. Windows may mix/resample the source;
   report the actual selected transport rather than assuming the source format.

Template only; hub N/device ADDRESS are unknown until enumeration:

```text
USBPcapCMD.exe -d \\.\USBPcapN --devices ADDRESS --inject-descriptors -s 1048576 -b 16777216 -o NEW_LOCAL_CAPTURE.pcap
```

The M2's single address filter excludes unrelated USB keyboard/storage devices.
Do not upload a whole-hub capture. No capture should be started until the actual
address and selected output are known. A new driver that fails to load remains a
recorded limitation; do not disable Windows signature enforcement or security
settings to force it.

## What analysis can answer

[USBPcap captures URBs](https://desowin.org/usbpcap/capture_limitations.html), not
individual physical bus transactions. It records OUT submission data and completion
information; IN payload arrives on completion. The [format specification](https://desowin.org/usbpcap/captureformat.html)
states that isochronous packet lengths/status are meaningful on the completion
side. Avoid treating zero/uninitialized submission lengths as a physical result.

Useful questions: Does Windows select the same alternate/rate? Does it send a
control sequence we do not? Does it open a capture/feedback endpoint? Are OUT
payload sizes nominal, variable or padded? If sizes vary, what documented input
drives that policy? A software timestamp or variable payload alone cannot prove
clock correction. A nominal result narrows the host-policy comparison; it does
not establish that nominal pacing correctly synchronizes this DAC.

## Historical installation checkpoint, before restart

User approved installation through the command approval prompt. The verified
installer ran with `/S` and returned exit code 0. Subsequently verified:

- `C:\Program Files\USBPcap\USBPcapCMD.exe`, file version 1.5.4.0 (56,448 bytes).
- `C:\Windows\System32\drivers\USBPcap.sys`, file version 1.5.4.0 (52,872 bytes).
- `sc.exe query USBPcap`: service exists, type KERNEL_DRIVER, state STOPPED.
- Explicit `USBPcapCMD.exe --help` returned the expected CLI/filter options.

At this checkpoint the driver had not started. The user subsequently restarted
Windows; driver start, M2 enumeration and first capture are now verified above.
No automatic Windows restart or USB-device reset was performed. No application
source or phone settings changed during preparation.
