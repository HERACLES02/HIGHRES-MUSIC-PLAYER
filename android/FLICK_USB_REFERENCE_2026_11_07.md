# Flick USB reference inspection, 7 November 2026

## Phone follow-up: listening comparison on hold

The user reports that the Play Store build displays Direct USB active, locks its
volume slider at 100% in bit-perfect mode, and ordinary music becomes loud.
Exact installed version and hardware-volume diagnostics are not yet available.
This is user-observed route/UI/loudness evidence, not an analog level measurement.
Do not continue the comparison below or use ordinary music to reproduce it.
Stop playback, then disconnect/reconnect the M2 before returning to another app.

Source hypothesis, not a confirmed cause in the installed build: in the pinned
`android_direct.rs`, `open_claimed_usb_handle` initially returns an empty claim
list, and hardware-volume discovery performs UAC1 range requests without itself
claiming AudioControl. Our M2 reports show volume GET failures before interface-0
ownership and successful controls after claiming it. This makes control ownership
worth checking against Flick's installed version and logs; topology detection,
other ownership paths and version differences must also be considered. The locked
slider alone cannot identify the failing request. No Flick patch is applied.

Safe follow-up needs no playback: read Flick's installed version from Android's
App info page (long-press Flick icon, App info, scroll to version), and inspect
any retained hardware-volume status/error. A Play Store installation identifies
the distribution channel, not the exact source revision inspected here.

### Matching release inspection

User subsequently supplied `0.23.-beta.3`, interpreted as the existing
`0.23.1-beta.3` release tag; the omitted digit has not independently been read
from the installed APK. Downloaded that public tag without executing its code.
Revision: `f35d87980e8a8892764668966d49a11b2f10c00e`.
Archive SHA-256: `b0c5155a16de1a89052403db691f5e4d05de9a4523e4b38f535bb3aad3c90833`.
Saved under ignored `android/.tools/flick-release-reference`.

The release source confirms a control-ownership mismatch worth investigating:

- `android_direct.rs:4314` calls hardware-volume discovery before the later
  interface claims. Discovery reads Feature Unit ranges without claiming its
  AudioControl interface (`build_hardware_volume_control_from_feature_unit`,
  around line 3557).
- `open_claimed_usb_handle`, around line 3219, starts with no interface claims
  unless reusing a previous lock. Idle-lock setup claims AudioStreaming interfaces,
  not AudioControl (around line 4070).
- Negotiation and backend setup claim AudioControl only under `Some(clock)`
  from UAC2 clock-source discovery (around lines 2451 and 4517). Our M2 has no
  such clock entity, so these branches do not establish interface-0 ownership.
- `volume_control.rs:25` already supports per-channel volume when master volume
  is absent. The M2's master-mute/per-channel-volume descriptor is therefore not
  by itself an unsupported control layout.
- `player_service.dart` disables volume when passthrough is active and hardware
  volume is not trusted. This is consistent with the reported locked slider.

This confirms the source's missing UAC1 control claim on the inspected path. It
does not prove the installed app's failed request or actual hardware attenuation:
we have no Flick runtime error log or APK-to-source verification. Earlier M2
reports' unclaimed GET failures/claimed successes make it a strong device-specific
hypothesis. If retained logs are available, look for hardware-volume discovery
failure and interface/unit/channel request errors; no replay is requested.

Reference: [release USB implementation](https://github.com/moss-apps/Flick/blob/f35d87980e8a8892764668966d49a11b2f10c00e/rust/src/uac2/android_direct.rs).
Any future correction would need to own the selected AudioControl interface before
volume discovery and preserve that ownership for runtime controls, with guarded
startup and cleanup tests. Do not add a transient claim/release while another
owner streams: ownership and driver reattachment must be managed together.
Our separate diagnostic already claims AudioControl 0 and playback 2 explicitly;
this finding does not justify changing its working control path or clock scheduler.
No upstream patch, issue submission, new APK or phone test was performed.

Inspected public source, not a running Flick APK or a physical M2 test.
Pinned revision: `c2c5765bfb2cf5f6d5503a2f8b9af1d8b0cb05d4` (GitHub ZIP comment).
Downloaded archive SHA-256:
`63500f4c25e6658f883997793931aae95c9ba4a7c66e45decf10400bb69652ce`.
Reference ZIP and selected source files are under ignored `android/.tools`.
No upstream code was executed, built or copied into application source.
The inspected [MIT license](https://github.com/moss-apps/Flick/blob/c2c5765bfb2cf5f6d5503a2f8b9af1d8b0cb05d4/LICENSE)
allows reuse with its required notice; no notice obligation is being silently
discarded by copying code in this increment.

## What it contributes

| Source at pinned revision | Observed behavior | Relevance to our M2 |
| --- | --- | --- |
| [android_direct.rs](https://github.com/moss-apps/Flick/blob/c2c5765bfb2cf5f6d5503a2f8b9af1d8b0cb05d4/rust/src/uac2/android_direct.rs), UAC1 sample-frequency SET/GET functions | Includes endpoint controls for UAC1 despite the module name. | UAC2 branding alone does not exclude the M2; source support does not establish runtime compatibility. |
| [endpoint.rs](https://github.com/moss-apps/Flick/blob/c2c5765bfb2cf5f6d5503a2f8b9af1d8b0cb05d4/rust/src/uac2/endpoint.rs), `select_explicit_feedback_endpoint` | Searches the selected interface alternate for linked or explicit feedback IN; excludes implicit-feedback audio from the usage-based fixed-point report search. | Our playback alternate contains only OUT 0x04, so it supplies no matching explicit report. Capture 0x84 on another interface does not enter this search. |
| [iso_packet_scheduler.rs](https://github.com/moss-apps/Flick/blob/c2c5765bfb2cf5f6d5503a2f8b9af1d8b0cb05d4/rust/src/uac2/iso_packet_scheduler.rs) | Starts with integer nominal accumulation. Valid feedback can update the fractional rate, with plausibility bounds and smoothing. | Useful if a real feedback signal exists; the arithmetic cannot supply a missing signal. |
| `android_direct.rs`, `run_iso_stream` (around lines 6021-6426) | Uses nonblocking feedback transfers when available; without a feedback slot, retains nominal timing and can log stream stabilization after successful transfers. | This is not an independently justified solution to the M2's clocking gate. Software stability/selected-rate verification is distinct from render-clock measurement. |
| [quirk.rs](https://github.com/moss-apps/Flick/blob/c2c5765bfb2cf5f6d5503a2f8b9af1d8b0cb05d4/rust/src/uac2/quirk.rs) and inspected USB module | No match found for 31b2, 0311, their decimal pair, KTMicro or KZ Acoustics. | No M2-specific clock workaround or measurement evidence found in this scope. This is not a claim about all project history/issues or other versions. |
| `android_direct.rs`, grouped hardware volume write (around lines 3730-3782) | Snapshots selected hardware channels, writes a common quantized target, attempts rollback after failed writes and refreshes readback. | Useful reference for future control transactions. Our diagnostic already checks both M2 channel values explicitly; no balance correction follows from this comparison. |

The next implementation should not import Flick's no-feedback nominal fallback
and call it synchronized. Nonblocking feedback handling and strict endpoint
association are useful future patterns, but no available clock-error source for
this exact M2 has been established. Keep the
[clock/balance evidence table](DIRECT_USB_INVESTIGATION_2026_11_07.md) open.

## Next short phone comparison: independent Direct USB path

This adds an independent Direct transport to the balance investigation. The earlier
other-player comparison did not establish that player's native Direct route.
No endurance rerun is needed. It is a listening comparison, not an analog gain or
clock measurement. The inspected main revision may differ from a release APK;
record the installed version and confirmed route.

Prepared local `android/releases/channels-24-96000.wav` (Git-ignored), copied byte-for-byte
from the current verified generated fixture. It contains identical active channel
samples: 0-2 s left, 2-3 s silence, 3-5 s right, 5-6 s silence, at -42 dBFS.
SHA-256: `2f7a3958b3af2f258571f008ac0e061f39a84b84cc0920c27e26764995ed5dac`.
No personal music was changed.

1. Transfer that WAV to the phone. If using USB file transfer from the PC, copy
   it before reconnecting the M2.
2. Use a build from [Flick's official releases](https://github.com/moss-apps/Flick/releases).
   Record its version. Close Music USB Probe and other audio apps before Flick
   takes ownership. Select the M2's Standard physical preset.
3. Start with low volume and earphones out. Disable EQ, normalization, crossfade
   and other processing. Choose Flick's Direct USB output and open the WAV. App
   UI can vary by release; confirm the active route from its status/diagnostics.
   Merely selecting a bit-perfect preference is not proof of the active route.
4. If Direct cannot be confirmed, or it fails, stop and record the route/error;
   ordinary Android playback must not be labeled a Direct comparison. Do not
   bypass a hardware-volume failure or raise volume to force a weak side audible.
5. If it starts safely, listen for two or three six-second loops at a comfortable
   level. Note left/right balance, glitches, installed version, active route,
   source/output format and hardware level if shown. Record any unavailable
   fields as unknown; the two apps' percentage sliders are not matched dB levels.
6. Stop Flick and release its USB output. Reconnect the M2 if ownership/restoration
   is uncertain. There is no need to rerun our diagnostic yet: compare this result
   with the accepted 24/96 Direct observation first.

Balanced Flick Direct would prioritize differences between the two software/control
paths for further investigation. Left-quiet Flick Direct would show the observation
also occurs with an independent Direct implementation. Neither result alone proves
a hardware fault, equal loudness, clock correctness or bit-perfect rendering.

## Verification limits

Read scheduler, explicit endpoint selection, stream feedback integration, UAC1
rate control, grouped volume handling, quirk entries and related endpoint/scheduler
tests. Did not perform a whole-project code review or execute upstream tests;
Rust tooling was not found on PATH. Verified fixture SHA-256 after copying and
checked local document links. Existing local software test results from the
preceding investigation still apply to unchanged diagnostic source. No new APK,
dependency change, commit or push.
