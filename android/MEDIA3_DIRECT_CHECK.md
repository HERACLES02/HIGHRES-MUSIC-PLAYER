# Direct Media3 integration: 0.10.0

Phone result received: the user confirms the lock-screen check worked. The report
records Media3 Pause/Resume and notification Stop, zero recorded transport/PCM
errors and clean restoration. See [MEDIA3_PHONE_RESULTS.md](MEDIA3_PHONE_RESULTS.md).
The handoff and pending-verification statements below describe the pre-test
checkpoint; this specific phone acceptance is now recorded.

## Finding from the 0.9.0 phone reports

`m2-lockscreen-notification.json` was exported during active audio on the OPPO
CPH1937, Android 11, build `CPH1937_11_F.17`. App notifications were enabled;
channel importance was 2. ID 505 was ongoing, PUBLIC, category transport, with
two actions and a media-session token. The user's locked screen still had no
card. Posting and token presence therefore do not establish visible controls.

`m2-direct-media-card.json`, run `b0e3482f-3c0d-4668-842b-2567bf9ad77f`, records
38,828 ms, notification Stop, zero transfer/packet errors, zero pending transfers
and restored settings. The framework session was created and released. Its
media-control-request array is empty, so this report does not validate remote
Pause/Resume. The prior shade Stop observation remains valid.

## Implemented change

The Direct diagnostic now uses Media3 1.8.0's `MediaLibraryService` and one
`MediaLibrarySession`. `DirectSessionPlayer` adapts the existing service-owned
native engine through `SimpleBasePlayer`; it creates no ExoPlayer, AudioTrack or
decoder. Normal playback is released before creating this session. The library
exposes only the current bundled fixture, with media identity and local fallback
artwork. The app has its own icon and audio category.

Media3 supplies the media notification and system session. Pause/Resume forward
to the native controls; Stop drains and releases USB. No seek, software volume,
speed, fresh-play, or arbitrary media-item commands are offered. Reported position
is an approximate repeating source position, not measured DAC rendering time.

The exported service is discoverable through standard Media3/legacy browser
actions. Session connections accept the app UID or trusted system controllers.
Private fixture-start/control intents require a process-local random nonce;
exporting the service does not authorize another app to start diagnostic sound.
There is no idle session, media-button receiver or playback-resumption handler.
Disconnect, focus loss and Stop prevent stale commands from resuming the engine.

The old 0.9.0 `DirectMediaCard` helper remains as unused source; the running Direct
service no longer creates it. The transport, decoders, fixture PCM and hardware
volume policy are unchanged. No new dependency or permission is introduced.

This implements part of the planned Direct service integration, while retaining
the diagnostic app separately. It is not the full library/player UI or the final
shared Normal/Direct coordinator. Room remains independent of this APK.

## Short phone check

1. Install [music-usb-probe-0.10.0.apk](releases/music-usb-probe-0.10.0.apk) over
   the existing probe; keep its data.
2. With M2 in Standard, start **24-bit / 96000 Hz / equal-tone WAV / 1 min**.
   Use the existing guarded hardware buttons to select a comfortable level.
3. Lock the phone and check for **Direct USB test**. Try Pause, Resume and Stop
   wherever offered, including the shade. Note any missing or ineffective control.
4. Save the final stream report as `m2-direct-media3.json`.
5. If the lock-screen card is absent, unlock while audio continues, save
   **notification diagnosis** as `m2-lockscreen-media3.json`, then Stop.

No repeat of the hour-long run is needed for this check. Lock-screen visibility,
remote controls, and ColorOS lifecycle behavior remain physically unverified for
0.10.0. Successful compilation and a posted notification cannot close that gate.

## Local verification

Run `powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\build.ps1`.
The build also runs `scripts/test-direct-state.ps1`: 31 pure state assertions for
preparing, playing, paused, draining, terminal/stale states, stop/detach guards,
all four source rates, six-second wrap, and large-position overflow bounds.
Build, lint, signature and APK byte comparisons are recorded in BUILD_STATUS.md.
