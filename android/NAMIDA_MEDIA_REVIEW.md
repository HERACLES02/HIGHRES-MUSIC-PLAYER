# Namida comparison: lock-screen integration

Follow-up: the 0.9.0 reports have now been inspected. Android reported an active,
public transport notification with a session token; Stop completed cleanly.
The locked-screen card remains absent according to the user. The resulting
Media3 service/adapter implementation and 0.10.0 handoff are documented in
[MEDIA3_DIRECT_CHECK.md](MEDIA3_DIRECT_CHECK.md). Sections below retain the
source-review checkpoint; their references to reports not yet uploaded are
historical, not the current evidence status.

Reviewed 2 November 2026 at the user's request. Namida and other players show
lock-screen controls on this phone; the Direct diagnostic does not. This is a
useful working reference, not evidence that the phone lacks media controls.

## Sources inspected

- Namida public main revision `10123d91e2527682efc72d877603a121d85d8fbe`:
  [manifest](https://github.com/namidaco/namida/blob/10123d91e2527682efc72d877603a121d85d8fbe/android/app/src/main/AndroidManifest.xml),
  [audio handler](https://github.com/namidaco/namida/blob/10123d91e2527682efc72d877603a121d85d8fbe/lib/base/audio_handler.dart).
- Namida's public dependency declaration uses the MSOB7YY audio_service fork's
  `minor` branch. Inspected that branch at
  `56075c2656003afe31ed68a694ba4ae6fb2f105a`:
  [native AudioService](https://github.com/MSOB7YY/audio_service/blob/56075c2656003afe31ed68a694ba4ae6fb2f105a/audio_service/android/src/main/java/com/ryanheise/audioservice/AudioService.java).
- Your installed Namida version/dependency revision is unknown. No committed
  pubspec.lock was available at the reviewed Namida revision; the fork snapshot
  is a branch comparison, not a proven match to your installed binary.

Sources were read/downloaded into the ignored `.tools/reviews/namida` cache,
never executed or imported into the app. No Flutter/audio_service dependency,
third-party source copy or unrelated Namida permissions were introduced.

## Concrete differences

| Area | Namida public implementation | Direct diagnostic 0.9.0 |
| --- | --- | --- |
| Service discovery | MediaBrowserService action and media-button receiver in manifest | Plain private streaming service; no media-browser endpoint |
| Session | AudioService extends MediaBrowserServiceCompat and publishes its MediaSessionCompat token through the service | Platform MediaSession attached to an ongoing notification |
| Metadata | Stable media item ID, metadata and artwork, with a fallback image | Fixed fixture title/artist/duration; no media ID or artwork |
| Notification | Compat MediaStyle, session token and public visibility | Framework MediaStyle, session token and public visibility |
| Channel | Low importance | Low importance |
| App identity | Audio app category and own icon declared | No audio app category or explicit application icon |

Namida's handler supplies fallback artwork when a song image is unavailable.
The native fork updates session state/metadata and notification together and
maps local playback volume controls to the music stream. These are comparison
facts, not recommendations to change Direct hardware volume semantics.

## Interpretation and implementation direction

The 0.9.0 session/style/public-visibility change was incomplete system integration
compared with this working reference. Channel importance and public visibility
already agree; changing those flags again has no source-backed justification.
The differences above identify integration work but do **not** prove which field
or service behavior determines this ColorOS lock-screen result. A MediaBrowser
endpoint and artwork are not universally required just to display a standard
media notification. No undocumented OPPO allowlist or missing permission is
claimed from the comparison.

The existing accepted architecture is still appropriate: use a service-owned
Media3 MediaLibraryService/MediaSession and expose Direct through a SimpleBasePlayer
adapter, maintaining the one-engine gate and no automatic resume. This provides
the discoverable service/session path without adding Flutter or a second Android
audio engine. Keep metadata, current media identity, state/actions, notification
and lifecycle coherent through that session. Add proper app identity and a local
fallback image as ordinary app completeness, not a guaranteed lock-screen fix.

An existing cheaper comparison is also available: the diagnostic Normal route
already uses Media3's service/session. Its locked-screen behavior has not been
reported. A short Normal-vs-Direct comparison can isolate service/session-specific
behavior within the same installed package before another speculative APK.

For actual Direct state, 0.9.0 already exports notification ID 505, media-session
token presence, category and visibility while audio runs. No 0.9.0 media-card or
active-notification JSON has been uploaded at this review point. Its final stream
report can additionally confirm session creation/release and callback requests.
Use that evidence to verify runtime integration instead of inferring visibility
from source alone. No new long endurance test is indicated by this source review.

No app code or APK changed in this review. Lock-screen acceptance remains open;
the prior clean PCM/transport, controls, detach and shade-Stop results are retained.
