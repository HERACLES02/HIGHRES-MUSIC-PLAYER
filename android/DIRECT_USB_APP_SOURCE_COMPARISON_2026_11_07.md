# Direct USB player source comparison, 7 November 2026

Scope: actual USB descriptor, scheduling, feedback, startup/control and teardown
code in public high-resolution-player implementations. These are source-level
findings and offline experiments, not physical acceptance of these apps or M2
output. Direct remains separate from Local Music. No external message, commit,
push, dependency installation or phone playback was performed.

## Results relevant to the M2

| Implementation | Inspected behavior | Meaning for our recorded M2 |
| --- | --- | --- |
| [TrueSample driver](https://github.com/devang-altf4/truesample-player/blob/cf23a00030cd2d27c91f667737f0ca9aa30256a4/usbaudio/src/main/cpp/UsbStreamer.cpp), start/feedback, volume/ownership methods; [pure packet scheduler](https://github.com/devang-altf4/truesample-player/blob/cf23a00030cd2d27c91f667737f0ca9aa30256a4/usbaudio/src/main/cpp/uac/Pcm.h) | Supports UAC1 and UAC2. Async output without an explicit endpoint logs that implicit feedback is unsupported and streams nominally. UAC1 rate SET follows alternate selection. Claims AudioControl and supports per-channel volume when master volume is absent. | Its actual parser identifies the same no-feedback topology and Feature Unit 7 left/right volume. Our nominal packet sequence matches its integer scheduler. It does not supply an additional synchronization signal for the M2. Retain our exact volume/rate readback guards. |
| [NeriPlayer synchronization policy](https://github.com/cwuom/NeriPlayer/blob/1ce32a4b368296014ab7c7758d3d898f33c3275c/modules/native/src/main/cpp/usb/exclusive/usb_streaming_sync_policy.h) and [candidate selection](https://github.com/cwuom/NeriPlayer/blob/1ce32a4b368296014ab7c7758d3d898f33c3275c/modules/native/src/main/cpp/usb/exclusive/usb_exclusive_device_selection.cpp) | UAC1 asynchronous candidates require supported explicit feedback; missing/implicit feedback is rejected. Selection invokes this policy before accepting a candidate. Separate modules handle explicit-feedback timing and transfer lifecycle. | Its policy rejects the M2's UAC1 async/no-feedback topology. This is an engineering refusal policy, not evidence that the hardware cannot play. The player may choose another output path; no runtime test was performed. |
| [decent-player USB library](https://github.com/Ma145/decent-player/blob/d039f9d6b1ec30901a5cd6edf72c29cde362a549/libs/decent-usb-audio-driver/src/main/jni/usb-audio-output.cpp), feedback reap/start and submission code | Reads/recycles feedback URBs, initializes nominal timing and updates it when feedback arrives. The inspected scheduling uses an 8,000-microframe/s timebase for its UAC2 path. | Useful reference for continuous feedback ownership, but not a drop-in full-speed UAC1 M2 solution. No endpoint exists in the recorded M2 playback alternate to feed this loop. Documentation/feedback-rate checks are not independent render-sample measurements. |
| [Tryptify USB driver](https://raw.githubusercontent.com/tryptz/Tryptify/main/app/src/main/cpp/usb/libusb_uac_driver.cpp), web-visible descriptor selection, startIsoPump, feedback allocation and cancellation | Supports UAC1 endpoint rate control; optional explicit-feedback transfers drive a fractional scheduler. With no feedback endpoint, scheduling uses its nominal seed. | Offers no demonstrated M2-specific clock signal. Relevant lessons are interval-aware packet sizing and feedback separation. Reviewed only the web-visible source, not an immutable downloaded snapshot or whole-app behavior. |
| [Flick release](FLICK_USB_REFERENCE_2026_11_07.md) | Earlier inspected nominal fallback and UAC1 hardware-volume ownership path. | Its loud-output phone observation and likely UAC1 ownership mismatch remain separate from our working diagnostic controls. Do not continue the listening comparison. |
| [Poweramp published USB notes](https://powerampapp.com/) | Developer describes UAC1 feedback discovery, nominal-rate fallback when feedback is absent, alternate-before-rate setup, and KTMicro/Samsung fixes. | Relevant documentation, not inspected implementation. The notes do not identify 31b2:0311, disclose the exact KTMicro fix, or establish its physical clock behavior. Our diagnostic already selects the alternate before setting/checking the rate. |

tsrossa was also screened as a candidate, but downloading its advertised main
branch returned HTTP 404. Its cached README is not being counted as inspected
driver code. No finding about its implementation follows.

No `31b2`/`0311`/`KTMicro` quirk match was found in the extracted native reference
code searched from TrueSample, NeriPlayer and decent-player. This bounded search
does not cover every branch, platform module, issue or historical version.

## Offline experiment using the saved descriptor

Built an isolated C++17 comparison against actual downloaded TrueSample parser/
scheduler code and NeriPlayer policy code. The comparison also calls our real
`PacketPcm`; it does not substitute a Python reimplementation.

- Input: exact 347-byte descriptor from `music-usb-probe (1).json`, SHA-256
  `0a6173002455ffed2bc84b2146b3968719ddaa26d62db0cd7723f70f597a1436`.
- TrueSample parser: UAC1, AudioControl 0, playback interface 2, alternates 1/2,
  async OUT 0x04, no explicit feedback, no UAC2 clock, stereo PCM16/packed PCM24
  and all four advertised rates. Playback Feature Unit 7 has master mute and
  left/right volume, with no master volume.
- Neri's actual policy returns `async_feedback_scheduler_unavailable` for both
  parsed playback alternates. This policy invocation is local code execution;
  it is not running NeriPlayer on the phone.
- All eight depth/rate combinations: 7,000 packets each (56,000 total), including
  a wrap of the six-second synthetic PCM source. Packet lengths match TrueSample
  exactly, remain within descriptor capacities and total exactly the nominal
  frames at every whole second. Every emitted byte matches the independent source
  position; equal channel samples remain equal in both slots.

Result: **PASS**. No USB handle, transfer, sound output or personal music was used.
This rules out a discrepancy with that reference's nominal packet arithmetic in
these cases. It does not measure DAC buffering, clock drift, internal processing,
channel attenuation or the physically rendered stream. Previous transfer tests
remain applicable to unchanged source; full upstream suites were not run.

Reproduce locally with the retained ignored snapshots:

```powershell
Set-Location D:\music_player
g++ -std=c++17 -O2 -Wall -Wextra android/.tools/usb-source-refs/compare_m2.cpp android/.tools/usb-source-refs/truesample-player-main/usbaudio/src/main/cpp/uac/UacDescriptors.cpp -o android/.tools/usb-source-refs/compare_m2.exe
if ($LASTEXITCODE -ne 0) { throw 'Reference comparison compilation failed' }
& android/.tools/usb-source-refs/compare_m2.exe android/.tools/usb-source-refs/m2-descriptors.bin
if ($LASTEXITCODE -ne 0) { throw 'Reference comparison failed' }
```

Reference files live under ignored `android/.tools/usb-source-refs`; the commands
are an investigation reproduction using those snapshots, not a new standard
build dependency or a test that downloads tools in a fresh checkout.

## Reuse decision and evidence still needed

TrueSample's `usbaudio` library has its own Apache-2.0 license, separate from the
GPL-3.0 application. NeriPlayer's inspected root license is GPL-3.0. decent-player
licenses `libs` under MIT, separate from its AGPL Felicity host. Tryptify's inspected
driver bears GPL-3.0-or-later. Keep component notices and terms if code is later
reused; none was copied into Local Music or diagnostic source in this increment.
Original licenses remain alongside downloaded reference files.

Concrete reusable patterns: pure descriptor/scheduler modules, speed/interval-aware
packet capacity checks, continuous nonblocking feedback when an endpoint exists,
explicit control-interface ownership, per-channel volume transactions, and refusal
reasons separating unsupported feedback from transfer failures. Our diagnostic
already has several of these protections. Avoid replacing it with a reference
whose fallback or readback policy weakens the existing guards.

The new source evidence supports two distinct policies: nominal fallback for an
experiment, or rejection until synchronization is supported. Neither supplies a
missing device-clock signal. There is still no evidence-backed M2-specific clock
fix or basis for channel compensation. A library advertised as high-resolution/
bit-perfect is not physical validation. Continue device-specific source/quirk
research when a concrete new lead appears; keep
[the clock/balance gates](DIRECT_USB_INVESTIGATION_2026_11_07.md) explicit.

## Snapshot identity

| Project | Revision | ZIP SHA-256 |
| --- | --- | --- |
| NeriPlayer | `1ce32a4b368296014ab7c7758d3d898f33c3275c` | `6652a0e932254cf01687c53c99725ffdb25a911a4a8e9764d04ff91bd014e849` |
| TrueSample | `cf23a00030cd2d27c91f667737f0ca9aa30256a4` | `2f1f5f434719bd3fa5833bec187d6609251fef517e80ca0e98f80941ba7d0ce6` |
| decent-player | `d039f9d6b1ec30901a5cd6edf72c29cde362a549` | `f9bf55687ce9146bf025d243f05ead5e3461fe5e094aa0ae1b04f05dd4e300d8` |

Revisions were taken from the downloaded GitHub ZIP comments; source/archive hashes
and metadata are retained locally. Only selected source, tests, docs and licenses
were extracted; no upstream build, install script or APK was run. Documentation
link/path/whitespace checks passed. No player/diagnostic APK rebuild was needed.
