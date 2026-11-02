# Direct lock-screen media card: 0.9.0

Superseded handoff: the two 0.9.0 reports confirm a posted public media notification
and successful Stop, while the user reports no locked-screen card. Pause/Resume
callbacks were not recorded. Use the new **0.10.0** APK and instructions in
[MEDIA3_DIRECT_CHECK.md](MEDIA3_DIRECT_CHECK.md). The older handoff below is retained
as history; there is no need to reinstall 0.9.0 or repeat its report collection.

Phone follow-up: user says operation is normal but the lock-screen card still
does not appear. Other players, including Namida, show controls on this phone.
Treat this as an unresolved app/integration finding; do not mark 0.9.0 a lock-
screen fix or repeat the unavailable-settings instruction. The requested source
comparison is recorded in [NAMIDA_MEDIA_REVIEW.md](NAMIDA_MEDIA_REVIEW.md).
No 0.9.0 result JSON has been uploaded yet at this comparison point.

User confirms the lock-screen notification option is not available in the phone's
settings. The 0.8.0 shade notification and Stop action already have physical
acceptance; the locked-screen card is missing. This update addresses an app-side
capability gap instead of repeatedly directing the user to that missing setting.

## Implemented

- Direct's foreground notification uses framework **Notification.MediaStyle** with
  a service-owned **MediaSession** token, transport category, public visibility
  request, fixed diagnostic title/fixture metadata and compact Pause/Resume + Stop.
- Public metadata is only the bundled diagnostic fixture, not personal music.
  System/channel privacy choices still apply; visibility on ColorOS is not
  guaranteed by setting a flag or attaching a token.
- Framework callbacks and notification intents forward commands to the existing
  native owner. No Android AudioTrack, decoder or second audio engine is created.
- The Normal baseline session/player is released before Direct's session is
  created. The session is released on completion, stop, detach/focus interruption,
  failure or service destruction. Constructor initialization failure also releases
  its session. Native cleanup remains owned by the existing streaming service.
- Play only requests Resume for a currently running/paused native experiment.
  Missing/closing/stopped engines and inactive native phases reject the command.
  A stale media Play intent does not start a new test or request USB permission.
- Session position is the approximate repeating six-second source position from
  native observations; it is not measured DAC time. No independent playback timer,
  source attenuation or promise to fix the old source-section label mismatch is
  added. Session state uses the existing service observation ticker.
- Final reports include session release evidence and bounded pause/resume request
  records. Notification exports include requested visibility/category and whether
  Android's posted card contains a media-session token. Binder tokens and other
  apps' notifications are not exported.

This is a diagnostic framework session, not the future Media3 MediaLibraryService
and SimpleBasePlayer adapter. It adds no Next/Previous, session seek or software
volume controls. Hardware volume continues through the existing guarded buttons;
media/cable volume behavior is not claimed. Full player integration remains open.

## Phone handoff

Locally built artifact: 70,147,886 bytes, SHA-256
`c2e01358e1acabb8703067feb190aac4de4a742ae79ebb60108062e6ebf53300`.
Build/lint/parser/fixture checks pass; signature matches prior updates. Native
libraries and all fixtures are unchanged. Physical lock-screen acceptance is pending.

1. Install [music-usb-probe-0.9.0.apk](releases/music-usb-probe-0.9.0.apk) over the
   existing probe. Keep its data; no repeat of the hour-long run is requested.
2. Start an explicit **24-bit / 96000 Hz / equal-tone WAV / 1 min** run, with M2
   Standard and comfortable guarded hardware level.
3. Lock the phone. Look for **Direct USB test / Experimental Direct USB** and try
   Pause, Resume and Stop if offered. Report which controls appear and work;
   controls rendered by ColorOS may differ from the compact shade layout.
4. Save the final stream report as `m2-direct-media-card.json`. If the locked
   screen still has no card, unlock while audio continues and **Save notification
   diagnosis** as `m2-lockscreen-notification.json`, then Stop in-app. Return those
   observations/files before claiming lock-screen support.

No new dependencies, permissions, native source revisions, test PCM or hardware
volume policy are introduced. Local build/signature/fixture evidence is recorded
in [BUILD_STATUS.md](BUILD_STATUS.md). Media-card visibility and its remote controls
require physical verification; a clean compile cannot establish them.

Official API references: [MediaStyle](https://developer.android.com/reference/android/app/Notification.MediaStyle),
[public visibility request](https://developer.android.com/reference/android/app/Notification.Builder#setVisibility(int)).
Neither API overrides the user's system notification/privacy settings.
