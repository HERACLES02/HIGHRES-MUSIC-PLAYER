# Direct USB investigation continuation checkpoint

Paused at the user's request for a scoped commit/push. Direct remains experimental
and separate from Local Music. No deployment, integration or new daily-use APK.

The remote main branch deleted GLM_CONTINUATION_HANDOFF.md in a8f30ea. That deletion
is respected; the original dirty checkout keeps its local copy. This focused note
preserves the USB context without restoring that deleted file in the repository.
Existing uncommitted EQ/player and Flask work is outside this checkpoint.

Resume from the experimental README and relevant evidence below. Do not repeat
accepted phone tests or infer physical correctness from host submissions. The
new streaming FLAC reader is unfinished and has only its baseline decode test.
Its seek/cancel/integrity/long-track/Android checks are the next work, with no USB
wiring until the documented hardware gates are resolved. Do not delegate.

## 6. Direct USB: substantial progress, still experimental

Publication workspace note: origin/main contains a8f30ea deleting this handoff.
Keep this local file, but do not restore it remotely. The scoped USB checkpoint
is being published from branch usb-checkpoint-20261107 in
android/.tools/usb-checkpoint-worktree, with android/DIRECT_USB_CONTINUATION.md
as its published continuation. Original main/worktree stays at e3e621b with EQ,
Flask and other local work preserved; do not blindly pull/reset it on resumption.

Paused publication checkpoint requested by user: USB investigation, offline queue
and unfinished streaming-FLAC source are being committed separately from existing
uncommitted EQ/player and Flask work. No integration or new daily-use APK.
`flac_stream_reader.hpp/.cpp` and host-only `flac_stream_cli` exist; baseline
six-second 16/44.1 byte comparison passed after a test-first failure. Seeking,
cancellation, corruption/MD5/EOF cases, long tracks, all formats and Android
compilation/runtime remain unverified. CLI currently supports only input-file
decoding. Resume by adding those tests, not by wiring this reader into USB.
See experimental README; preserve the verified queue/phone/PC evidence below.

Offline source foundation: `android/native-usb/experimental/README.md` and
`pcm_frame_queue.hpp`. A new bounded, mutex-protected stereo PCM queue distinguishes
NeedData/End/Stop/Failed and rejects stale generation writes/reads after reset.
It is not wired to JNI, CMake runtime targets, USB or Local Music; existing
six-second and quiet-volume guards are unchanged. Test-first initial round-trip
failed before implementation; final `test-pcm-frame-queue.ps1` passes 22 scenarios
plus 24 raw-pipe checks. API30 ARM64/ARMv7 compile-only checks passed. Two complete
PC-decoded songs (16/44.1 238.213s and 24/96 274.133s) matched full PCM references
through 16/24KiB queue allocations; originals' hashes/size/mtime unchanged, no
playback. Private evidence under ignored .tools/direct-integration. No new APK
or phone check; next source work is streaming decoder/reader/cancel/seek, not
claiming an ordinary-song USB engine exists. Hardware clock/balance gates remain.

Accepted controlled focus result: `m2-direct-focused-controlled.json` (actual
filename), SHA a0873572e3b823cf5a3ea72788ad2fd5e6c9d830e839dbfde2c022916a943e3d.
It records audio_focus_loss, phase5, error/pending0, exact both-channel -40dB
readbacks, restored settings and released interfaces/drivers. User confirms it
stayed stopped without manual Stop after the five-second focus test. Accept for
tested Probe 0.7.0/CPH1937 Android11; report still says 0.7 and has no Media3 card,
so do not claim 0.10/integrated-service coverage. No repeat timer/focus test by
default. Real-call/final coordinator are separate. See
`android/FOCUS_PHONE_RESULTS_2026_11_07.md`. Raw report unchanged; no runtime/APK
changes for review. Clocking, analog balance and full-song engine remain open.

Focus-attempt review: `android/FOCUS_PHONE_RESULTS_2026_11_07.md`. New
`m2-direct-focus.json` identifies Probe 0.7.0, not 0.10; it stopped after 28.7s
with reason none. User confirms pressing Stop / release. Native errors/pending
zero, both levels -40dB, restoration/releases verified; focus remains inconclusive.
Do not repeat Clock test. Prepared separate, silent `android/focus-probe` helper
(`com.localmusic.focusprobe`), explicit transient media-focus request for five
seconds, release on deadline/background/destroy, no permissions or audio/USB/file
access. `android/releases/usb-focus-test-1.0.apk` compile/package/signature/identity
checks passed; phone runtime untested. Read helper README for exact test using
existing verified Probe 0.10 update and export m2-direct-focus-controlled.json.
No phone/emulator connected, no installs by agent; no existing app runtime change.

Integration-prerequisite testing: `android/DIRECT_USB_INTEGRATION_READINESS_2026_11_07.md`.
Expanded native transport tests from 22 to 62 scenarios (40 new Stop/startup,
paused/reconfiguration and queued-completion cancellation cases across all eight
formats); all passed. Controller state passed 31 assertions. An isolated removal
of the early Stop guard was caught by the new test; runtime source unchanged.
User pointed to music on D:. Read-only metadata audit of D:/music and D:/FLACs
found 835 files, zero probe errors; six representative FLAC formats produced 12
exact native WAV/FLAC excerpt comparisons, with originals' full hashes unchanged.
Private results/excerpts stay in ignored .tools/direct-integration, outside APK
assets. Fifteen tracks exceed the M2's advertised 96k maximum; MP3/Opus also need
a future decoder path. Ordinary-song transport and Normal/Direct coordination
are not implemented: current engine enforces six-second quiet fixtures. No phone
was attached through ADB. The readiness note gives one short timer-focus check
using existing Probe 0.10.0, distinct from real-call acceptance. Hardware clocking
and analog balance remain open; no integration, APK, runtime, EQ or music edits.

Latest PC result: `android/M2_WINDOWS_CONTROLLED_RESULT_2026_11_07.md`. A timed,
single-address USBPcap driver helper now stops cleanly without console input.
The 12-second capture contains two controlled three-second WASAPI-exclusive
fixtures: 16/44.1 and packed 24/96, -72 dBFS peak, identical channels. Every
captured OUT submission byte matched its complete generated source; both 3,000-
slot sequences matched nominal scheduling, no IN/feedback or extra clock controls
were observed. All 1,212 records / 606 request pairs were complete and zero-status.
Windows endpoint API reported equal channel levels; no hardware UAC volume GET
was captured and no volume was changed. Native helpers stopped; no app source,
APK, phone settings, music files, commit or push changed. The first controlled
PowerShell wrapper returned 1 despite native success logs; capture independently
verified, revised status-logging wrapper subsequently passed an idle shutdown test.
Do not repeat the headless CLI method below. Physical clock synchronization and
analog balance remain unresolved; no channel compensation or integration allowed.

Current PC trace checkpoint: `android/M2_WINDOWS_USB_TRACE.md`. User restarted
Windows after approved signed USBPcap 1.5.4.0 installation; driver now RUNNING.
M2 VID31b2/PID0311 was confirmed on USBPcap1/address1. A filtered first trace is
saved under ignored `android/releases/usb-clock/`; hash/details are in that note.
It contains an uncontrolled ~1.08-second Windows audio stream: alternate2 then
96kHz, 1,080 nominal 576-byte submission slots, no observed isochronous IN traffic.
All 228 records are complete and all 114 requests have matching zero-status
completions. This does not prove clock synchronization, balance or bit-perfectness.
Headless `q` input failed; capture parent/worker were explicitly stopped and no
USBPcapCMD remained. Do not repeat that unattended method. A controlled fixture
capture was subsequently completed using the timed helper above. No device reset, new APK
or application-source change; clocking and physical balance gates remain open.

Broader app-source comparison: `android/DIRECT_USB_APP_SOURCE_COMPARISON_2026_11_07.md`.
Actual TrueSample parser/scheduler and NeriPlayer policy were exercised offline
with our saved M2 descriptor. All eight formats / 56,000 nominal packets matched;
equal-channel source bytes remained equal across a source loop. Neri's policy
rejects this async/no-feedback topology; TrueSample uses nominal fallback.
decent-player and Tryptify source and Poweramp published USB notes were also
inspected. No new M2 synchronization signal or clock/balance fix was established;
do not infer physical output from these software/reference comparisons.

7 November source/report audit: see
`android/DIRECT_USB_INVESTIGATION_2026_11_07.md` for the evidence table, clocking
decision and balance measurement paths. Descriptor hash still matches the native
guard; capture IN 0x84 is not an established shared-clock feedback source. Local
parser, transport, controls, equal-channel fixtures and native decode checks passed.
No transport fix or new APK; physical clocking/balance gates remain open. Do not
repeat accepted hour-long playback or add channel compensation to replace missing
measurements.

Subsequent user-run driver-access preflight:
`android/releases/usb-clock/usb-clock-20261106T210726Z-103b2958.json` confirms
CPH1937/API 30 but permission-denied `/proc/asound/cards` and `/proc/asound/pcm`.
See `android/USB_CLOCK_ACCESS_CHECK.md`; do not repeat or set up wireless ADB/root
to retry. Flick comparison is on hold after user-observed locked volume and loud
output. No new physical clocking/balance evidence or integration approval follows.

The diagnostic app is `0.10.0-media3-direct`, separate from Local Music.
Its native transport is fixture-oriented, not a finished arbitrary-song engine.

M2 evidence: USB `31b2:0311`, UAC1, playback interface 2, asynchronous OUT
endpoint `0x04`, full-speed physical link in native reports, stereo 16-bit/2-byte
or packed 24-bit/3-byte PCM at 44.1/48/88.2/96 kHz. No advertised 32-bit/192 kHz
playback. Playback Feature Unit 7 exposes hardware channel volume controls.
No associated explicit feedback endpoint is advertised.

Already established in the diagnostic app:

- Native connection/interface ownership, guarded hardware volume SET/readback,
  format control/readback, cleanup and restoration have physical evidence.
- App-side PCM checks and short transport evidence cover all eight advertised
  depth/rate combinations; user confirmed the six later listening runs normal,
  retaining the known approximate-label issue and balance finding.
- A 61-minute 24/96 run completed with no recorded PCM/USB errors, restored
  settings and no unexpected glitches noticed by the user. Many screen-off
  intervals were observed; a continuously locked hour was not established.
- Pause/resume/seek/Stop and detach/reconnect behavior were checked. Detach
  stopped audio without speaker fallback or automatic reconnect playback.
- The 0.10 diagnostic's lock-screen controls are user-confirmed working; its
  report corroborates Media3 Pause/Resume/Stop and clean restoration.

Open gates:

- Clock synchronization is not justified by nominal packet pacing and successful
  counters. Missing explicit feedback still requires engineering investigation.
- Slight left weakness/low-level audibility is reported in both routes in some
  comparisons, with a later high-rate comparison sounding balanced in Normal and
  slightly left-quiet in Direct. Cause is unresolved; no alternative compatible
  earphones were available. Do not invent channel swap/gain compensation.
- DAC/internal DSP, actual rendered PCM and physical bus integrity are not proven
  by app-side hashes or a device-rate display. Do not claim unconditional bit-perfect.
- Focus/call behavior and final integrated-engine acceptance need targeted checks.
- The source-position section label can differ from heard output. The user's
  approximate 0.2-second observation is not a measured fixed hardware latency.

Do not request another hour-long run or repeat accepted notification tests as a
default next step. Do not enable Direct in the actual player merely because the
diagnostic streams audio. Continue useful Normal-mode work while keeping this gate
explicit. Do not claim Stage A fully passed or roll back to saying nothing exists.

Useful evidence: `android/ENDURANCE_PHONE_RESULTS.md`, `FORMAT_PHONE_RESULTS.md`,
`TRANSPORT_CONTROLS_PHONE_RESULTS.md`, `DETACH_PHONE_RESULTS.md`,
`MEDIA3_PHONE_RESULTS.md`, `CHANNEL_PHONE_RESULTS.md`, `HIGH_RATE_PHONE_RESULTS.md`.
`STAGE_A.md` retains many chronological checkpoints; newest acceptance supersedes
older pending statements. Original JSON reports must remain unchanged.
