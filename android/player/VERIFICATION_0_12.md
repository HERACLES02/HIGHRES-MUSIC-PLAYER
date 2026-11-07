# Local Music 0.12.3 verification

Version `0.12.3-audio-info`, code 30. Generated APK:
`android/releases/local-music-0.12.3.apk`.

## Included changes

EQ response/headroom controls and exact values; supported graphic and
parametric preset persistence/import; float PCM after Double EQ/ReplayGain;
optional Android USB routing above 48 kHz; Normal buffering at lower rates;
and the read-only Audio Info panel. Mode changes retain source, position and
play/pause intent. The USB preference suspends crossfade without losing its
saved value. No Room schema or raw Direct USB integration change.

## Local checks

The full player build passed core/data checks, app/test-APK assembly, lint
(0 errors, 42 warnings), pinned native tools, tag preservation evidence,
frozen Room schemas and APK signature/identity checks. Relevant totals:

- 10,428 main core assertions, 42 EQ response assertions, 15 USB policy/buffer
  assertions and 25 audio-effects assertions.
- 41 EQ JSON assertions, backup coverage and 100 real-disk Room assertions.
- Earlier precision-path emulator runs passed eight original PCM/EQ cases,
  35 precision/sink cases, four artwork cases and real forward/backward/paused
  seeks. Muted AudioTrack observations retained low 24-bit PCM values after
  Media3's existing startup ramp and preserved gapless trimming.

The 0.12.3 display increment did not change DSP/output behavior. Its full
build and lint were rerun; unchanged emulator/audio tests were not repeated.
APK SHA-256:
`ff9e6c3ebfedde5e568861e1f4be0043de9053265da580237fa63d7d7437bad4`.

## Phone evidence and limits

On CPH1937 / Android 11 / KZ M2, AudioFlinger and audio policy matched the
active player client to USB outputs with the following behavior:

| Source/client | Active Android output |
| --- | --- |
| Stereo 96 kHz float from the 24-bit/96 kHz source | DIRECT, 96 kHz float processing/HAL |
| 44.1/48 kHz float clients | MIXER, 48 kHz PCM16 processing/HAL |

The user accepted EQ toggling, seeking and unplug/reconnect behavior. No
silence/crackling was noticed during the initial single-rate music run.
The lower-rate 44.1 ? 48 kHz transition became clean after retaining Normal
buffering there. A click entering 96 kHz from 48 kHz remains, including with
EQ off and generated zero-PCM files. The silent capture has valid before/after
snapshots for that boundary but no completed final fourth phase. The evidence
points toward output reconfiguration, without identifying a physical component.
The current modes were retained; no fixed-rate resampling was added.

Audio Info navigation, layout, Refresh and back/reopen behavior were inspected
over ADB while playback remained paused. Refresh returned a new timestamp;
selected mode and last client/device reports stayed separate. The user confirmed
the panel works. Empty-library fallbacks were reviewed without clearing phone data.

App tests, client getters and Android HAL snapshots do not measure analog
balance, USB wire precision, physical DAC clocking or bit-perfect output.
Audio Info explicitly labels those measurements as unavailable. Private reports,
UI captures and generated audio remain outside the repository.
