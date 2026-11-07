# Direct USB focus evidence, 7 November 2026

## Latest: focus-loss stop and no automatic restart accepted

New file `m2-direct-focused-controlled.json` (spelling retained), 18,215 bytes,
SHA-256 `a0873572e3b823cf5a3ea72788ad2fd5e6c9d830e839dbfde2c022916a943e3d`.
Run `3f02ba8f-6417-4180-9001-4939a3b1352d`, started
`2026-11-06T22:29:25.559Z`, exported `2026-11-06T22:29:30.804Z`.

User confirms **"Yes, it stayed stopped"** after the five-second focus test,
without pressing Stop. With the recorded `audio_focus_loss` event, this accepts
focus-loss shutdown and no automatic restart for **Probe 0.7.0 / CPH1937 Android
11**. Do not repeat this accepted component test merely to accumulate passes.
A real incoming call and final integrated coordinator are separate cases.

| Check | Recorded result |
| --- | --- |
| Identity | Probe `0.7.0-background-check`, CPH1937/API 30, CPH1937_11_F.17 |
| Signal | `channels-24-96000.wav`; source/decoded PCM match pinned manifest |
| Interruption | `audio_focus_loss`, `stoppedByRequest: true`; 60s requested, native wall elapsed 4,655 ms |
| Final transport | Phase 5, error/pending 0; PCM/packet/transfer errors and queue-empty 0; no delayed cleanup |
| Submitted / completed | 2,511,360 / 2,491,776 bytes, 4,326 completed intervals; six cancellations during drain |
| Hardware safety | Both exact SET/readbacks -10,240 raw (-40 dB), including pre-audio check |
| Restore/release | Exact restored rate/idle/channel checks, interfaces 2/0 released, both kernel-driver-after results 1 |
| Observation | User confirms it stayed stopped without manual Stop |

The report identifies 0.7.0 and has no Media3 `mediaCard`; do not relabel it as
0.10 or Local Music acceptance. Historical/current focus callbacks use the same
stop policy, but current Media3 cleanup was not measured here. The helper's own
grant/release text was not exported; Direct's recorded focus callback establishes
the event it actually received.

Retain `maxCallbackGapUs: 382629` as a host callback-gap observation. Its location
within playback/cleanup is unidentified; it is not a physical stop-latency or
DAC-clock measurement. No periodic volume check ran before this short test ended
(the interval is ten seconds); initial readbacks and final restore checks exist.
The generic `safeHardwareVolumeEstablished: false` remains in the raw report;
the fixture path separately reports `lowVolumeControlVerified: true` and exact
readbacks, as in earlier accepted exports. No report fields were rewritten.

Read-only check, run from `D:\music_player`:

```powershell
python android\.tools\direct-integration\verify_controlled_focus.py
```

Passed: immutable hash, pinned source, focus reason, consistent final stats,
zero error/pending counters, readbacks and restoration/releases. No APK rebuild
or repeat native/app suite was needed. Clocking, analog balance and ordinary-song
integration remain open.

## Earlier timer attempt: manual stop, inconclusive for focus

Reviewed the unchanged `m2-direct-focus.json`, 20,743 bytes, SHA-256
`e557996be5073518b72c183785706cd22acdea751903e33c415e037a6c33e63d`.
Run `50097395-b435-47a5-ab91-0f2928449be9`; started
`2026-11-06T22:20:32.774Z`, exported `2026-11-06T22:21:02.117Z`.

User clarification: **"I pressed Stop / release."** This is manual-stop evidence,
not a passed or failed audio-focus test. It does not establish whether Clock
requested focus. Do not repeat the same timer stimulus blindly.

| Field | Evidence |
| --- | --- |
| App/phone | `0.7.0-background-check`, CPH1937/API 30, CPH1937_11_F.17 |
| Fixture | `channels-24-96000.wav`; pinned source/PCM hashes match |
| Requested / actual | 60 seconds requested; native wall time 28,681 ms |
| Stop | `stoppedByRequest: true`, `interruptionReason: none`; user confirms manual Stop |
| Completion | Phase 5, error 0, pending 0; 28,670 completed intervals / 16,513,920 bytes |
| Errors | PCM mismatches, packet/transfer errors and active queue-empty all 0; no delayed cleanup |
| Cancellation | Seven cancelled transfers; 16,542,720 submitted bytes exceed completions during Stop/drain |
| Hardware levels | Both -10,240 raw (-40 dB), exact SET/readback and two periodic channel-pair checks |
| Cleanup | Restoration verified; idle/rate/channel exact checks pass, interfaces 2/0 released, both kernel-driver-after results 1 |
| Media3 | No `mediaCard` object; does not test the 0.10 Media3 service |
| Notes | Raw observation field blank; manual-Stop clarification retained separately here |

Historical 0.7 source at `7f6ad37` and current 0.10 source route focus loss through
`requestStop("audio_focus_loss")`. The in-app Stop button uses the direct native
command path, explaining a manual stop with no service interruption reason.
There is no evidence here of a broken focus callback. Current
`StreamEvidence.VERSION` and the accepted `m2-direct-media3.json` identify 0.10.0;
this supplied report identifies 0.7 instead. Do not assume it tested 0.10 or infer
why the phone exported that older version.

## Historical helper preparation, before the controlled report

Prepared the [silent focus helper](focus-probe/README.md). Its five-second
transient media-focus request replaces Clock. Meaningful evidence needs a helper
GRANTED result, `audio_focus_loss` in the probe export, cleanup/restoration and
user observation that Direct stayed stopped. A denied request is inconclusive;
a real incoming call remains separate.

Use the existing verified Probe 0.10 APK for the next run. Helper APK:
`android/releases/usb-focus-test-1.0.apk`, SHA-256
`f9309313eff40a15fdf99b045a7d9ab6f7d31dd3b3b0aa2f65bf2c3bf8c67afc`.
Java/dex compilation, packaging/alignment, v3 signature, identity and absence of
requested permissions passed. Both APKs verify with signer SHA-256
`51b163c3c29d8f91f65fbd03929ebb4a98aada4d32b601f2cae5a479374ec507`.

No local runtime check was possible: ADB 5037/5038 listed no targets and the
project SDK has no emulator. The agent installed nothing on the phone. Only the
new helper was built; existing app/transport suites were not repeated. The build
initially missed a PowerShell ZIP assembly load, then passed after loading it.
At that preparation checkpoint, runtime helper behavior and the phone result
were pending. The subsequent accepted Direct focus result is recorded above;
the helper's own on-screen grant/release text was not supplied in the export.

Music USB Probe, native transport and Local Music runtime code are unchanged.
Original reports and keys remain private. Clock synchronization, analog balance
and ordinary-song integration remain outside this manual-stop result's scope.
