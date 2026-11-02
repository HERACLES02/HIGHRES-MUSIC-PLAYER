# Missing Direct notification: 0.8.0 diagnostic

## Direct Stop result and remaining lock-screen issue

[m2-notification-during-playback.json](../m2-notification-during-playback.json)
is actually a completed native stream report, despite its filename, not an
active NotificationManager snapshot. It records 0.8.0, channels-24-96000.wav,
interruptionReason **notification_stop**, stoppedByRequest=true, 10,130 ms native
wall time, 10,105 completed intervals, zero PCM/packet/transfer errors, zero final
pending transfers and verified restoration. User confirms **"it worked but there
is no notification on locked scree"**. Notification posting/action in the shade
now has a functional pass; locked-screen visibility is still unresolved.

No conclusion is drawn about lock-screen settings from the earlier -1000 channel
value or from this stream report, which has no notification-state block. First
use **Open Direct notification settings** and inspect any **Show on lock screen**
or **Lock screen notifications** option. Device-wide filtering of silent lock-
screen notifications can also affect a low-importance channel. Exact ColorOS
menu labels and current choices have not been measured; do not claim they are off.
References: [Android lock-screen notification controls](https://developer.android.com/develop/ui/compose/notifications#lockscreen),
[Android notification settings](https://support.google.com/android/answer/9079661).

The diagnostic notification is a basic ongoing Stop card, without a Direct
MediaSession/media-style transport panel. Full lock-screen Play/Pause/Next and
headset control integration belongs to the common player session; it is not
implemented or validated by the current Stop-card test. Continue treating missing
lock-screen visibility as an open finding, rather than declaring all notification
or lock-screen acceptance complete. No new code/build was performed in this review.

## First phone result received

[m2-notification-diagnosis.json](../m2-notification-diagnosis.json), exported
2 November 2026 at 11:03:06.851 UTC, confirms app 0.8.0 on the target API 30
phone. Notifications are enabled; Direct channel exists with importance 2 (low).
Android lists test notification ID 506 on `direct-usb-experiment`, flags 16,
ongoing=false, zero actions, posted 11:02:51.333 UTC. The notify call returned
normally and state reads have no error. Runtime permission is not applicable.
User explicitly confirms **"Yes, I saw the test card"**. Ordinary same-channel
test-card posting and visible rendering are therefore physically confirmed.

Direct notification ID 505 is absent, but streamActiveInThisProcess=false and
streamFixture is empty. That absence is expected in this no-audio check and does
not establish a foreground notification bug. Lock-screen visibility is exported
as raw -1000 and channel-group state null; neither is used to infer a shade block.

Next: clear test card 506; start an explicit one-minute Direct run. Look for
**Experimental Direct USB**, distinct from the ordinary test card. If visible,
use its Stop/release action and save the stream report. If still absent, save
notification diagnosis while audio is still playing (rename to
`m2-notification-during-playback.json`), then Stop in-app. The active export must
be checked for live stream state and notification 505 before further conclusions.
No new APK or long playback run is required. Earlier handoff remains below.

User reports no Direct notification in the shade while test audio is playing,
and confirms the app-wide notification switch is enabled. Notification Stop is
unverified. A channel-specific block or platform rendering/filtering behavior
is not established; app-wide allowance alone does not report those states.

0.8.0 adds a targeted test and own-app state export, **not a claimed visibility
fix**. The existing Direct channel ID, importance, notification title/Stop action
and service ownership remain. No native library, PCM fixture, volume policy or
USB scheduling change is introduced.

## New controls in the Direct screen

- **Post test notification** requests an ordinary card on the same Direct channel,
  ID 506, with a distinct title. It starts no service, USB connection or test audio.
- **Clear test notification** cancels only 506, never foreground notification 505.
- **Open Direct notification settings** opens the specific channel page. Creating
  an existing channel does not reset its user-selected settings or importance.
- **Save notification diagnosis** exports `m2-notification-diagnosis.json`, including
  this app's actual global allowance, channel existence/importance, channel-group
  block state, lock-screen visibility, active notification IDs/flags/action counts,
  and the last test-post attempt/result. It also identifies whether this process
  has an active Direct run. It does not read other apps' notification contents.
- The existing background-stop bundle also includes the current notification
  snapshot, so an active Direct run can be correlated with posted ID 505 later.

Android's active-notification list is evidence of posting state, not proof that
the phone shade displayed the card. A normal return from notify does not imply
visibility or permission. Read errors are exported explicitly. The last test-post
attempt is saved privately; no automatic polling or notification spam is added.

The manifest now declares POST_NOTIFICATIONS for Android 13+. Its runtime prompt
is requested only by the user's Post test notification action when applicable;
denial is respected and export remains available. **Android 11 has no such runtime
prompt**, and the runtime permission is not an explanation of this phone's issue.
No network, storage, microphone or notification-listener permission is added.

## Phone handoff: no audio run yet

Locally built APK: 70,542,452 bytes. SHA-256
`bb92d69342c54626b2ca25355f110de1ce3fa424d96ad0cd5da0e2183a9f4065`.
Assembly/parser/fixture checks pass; lint has 0 errors. Signing certificate
matches prior versions. Native libraries and fixtures are unchanged.

1. Install [music-usb-probe-0.8.0.apk](releases/music-usb-probe-0.8.0.apk) as an
   update, keeping app data. No USB cable or earphones are needed for this check.
2. Open Direct USB, tap **Post test notification**, then look in the fully expanded
   shade for **Music USB Probe notification check**.
3. Return to the app without tapping that notification if possible (tapping it
   auto-dismisses the test card). Tap **Save notification diagnosis**.
4. Return `m2-notification-diagnosis.json` and whether you saw the card. Do not
   start another hour-long test. Use Clear test notification afterward if needed.

If the test card appears, a subsequent short active-audio export can check ID 505.
If a blocked channel is measured, use the specific settings shortcut and let the
user choose the setting. If Android lists the card but the shade hides it, retain
that distinction and investigate platform display behavior. No channel is silently
recreated under a new ID to bypass user settings.

Official references: [notification channels](https://developer.android.com/develop/ui/compose/notifications/channels),
[NotificationManager active notifications](https://developer.android.com/reference/android/app/NotificationManager#getActiveNotifications()),
[Android 13+ permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

Build and verification evidence belongs in [BUILD_STATUS.md](BUILD_STATUS.md).
Phone visibility and Stop-action acceptance require actual observations after
installation; compilation or a manager snapshot cannot approve them alone.
