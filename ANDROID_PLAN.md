# Personal Android music player plan

## Current implementation checkpoint

Parallel work authorized while the phone endurance test continues: pure Kotlin
`:player-core` now implements queue/resume/history/shuffle state and LRC parsing,
timing and selection. UUID library models and an executable proposed Room v1 SQL
schema establish the data contract. This module is not linked to the diagnostic
APK; Media3/UI/Room repository integration remains outstanding. See
[player-core README](android/player-core/README.md) for behavior, test commands
and explicit limits. This does not approve the unresolved Direct USB gates.

Latest postmortem: 0.7.0 successfully exported Android exit history. A resource
kill (reason 9, `o-kill:6`) at 08:37:09.807 UTC correlates with the original
endurance failure. Exact policy/resource trigger remains unknown. Next trial:
allow this app's background activity and exempt it from battery optimization,
then repeat endurance with richer run identifiers. No confirmed fix or endurance
pass yet; see [background review](android/BACKGROUND_PHONE_RESULTS.md).

Latest endurance attempt stopped with only a checkpoint (6 min 12 s of saved
progress, no recorded error, several minutes screen-off). The separately saved
final report belongs to the earlier minute. Cause is unknown. 0.7.0 adds Android
process-exit evidence and run identity; read the existing evidence first using
its new export, without restarting the hour-long test. See
[background review](android/BACKGROUND_PHONE_RESULTS.md).

Latest steady 24/96 minute: no seeks/pauses, zero recorded errors, screen-off
progress, restored settings and no user-reported glitches. Maximum callback gap
28.067 ms; the earlier two-second gap did not recur. Next is the 61-minute
screen-off experiment. Slight left weakness, clocking and full-format acceptance
remain unresolved; see [phone review](android/HIGH_RATE_PHONE_RESULTS.md).

Latest phone review: [24/96 follow-up](android/HIGH_RATE_PHONE_RESULTS.md)
records a minute's completed Direct PCM byte count, zero recorded errors and
restoration, with one seek and a roughly two-second callback gap requiring
listening clarification. Normal 24/96 sounds balanced to the user, Direct remains
slightly left-quiet. The supplied 16/44.1 WAV also sounds slightly left-quiet in
another player. Full-format and screen-off endurance acceptance remain open.

As of 2 November 2026, Stage A is in progress. The latest diagnostic is 0.7.0;
the daily-use player and stages B-F are not implemented. The accepted user handoff
and physical reports take precedence over the older research below.

Implemented: the read-only USB probe, Normal-route fixture playback, native
libusb ownership/control checks, guarded hardware-volume writes and restoration,
and experimental isochronous WAV/FLAC fixture playback. Source, build dependencies
and native tools are pinned. See [android/BUILD_STATUS.md](android/BUILD_STATUS.md)
for exact local checks and artifact hashes.

Physical evidence covers USB descriptors, Normal-route fixtures, native access,
volume/rate/alternate controls and two short Direct USB runs. Their application
PCM/transport counters pass. Follow-up clarified that left is audible at -30/-20
dB and the original fixture's both-channel overlap was intentional. The 0.6.0
equal-tone comparison now reproduces low-volume left weakness in both Normal
and Direct USB. Its one-minute Direct run records zero PCM/transfer errors and
verified restoration. The cause of imbalance, clocking and sustained playback
remain unvalidated. See [android/CHANNEL_PHONE_RESULTS.md](android/CHANNEL_PHONE_RESULTS.md).

0.6.0 adds equal-frequency, gap-separated channel comparison in both routes,
without a routing fix or balance compensation. The comparison was performed;
next isolate the balance finding using another earphone/cable combination where
available. Do not mark it passed or start the long run before resolving that finding.

The M2 advertises stereo 16/24-bit PCM at 44.1/48/88.2/96 kHz. No fixed Android
48 kHz limit or end-to-end bit-perfect result is assumed. Direct scheduling is
explicitly experimental nominal pacing without feedback/capture timing. The
accepted Direct USB validation gate remains in place before the full interface.

## 1. Target and decisions

- Phone: OPPO A9 2020, running Android 11 as reported by the user.
- Output: KZ M2 Dual Mic Type-C EQ Tuning Switch cable.
- Distribution: a signed APK installed personally, without a Play Store launch.
- Main purpose: offline music playback, editing, artist information, synced
  lyrics, and the best demonstrable audio path this particular hardware supports.
- Assume an unrooted phone. Rooting is not part of this plan.
- Build a native Kotlin application with Jetpack Compose and a local database.
- The app runs independently of the existing Flask app and PC.
- Internet is needed for optional artist/artwork/lyrics lookups, not for local playback.
- First prove the hardware/audio route. Then build the full interface around it.

Personal use lets us focus on one phone/cable combination and omit accounts,
subscriptions, analytics, servers, and store publishing. It does not remove
Android's storage rules or create audio capabilities the DAC does not have.

## 2. What the hardware research establishes

KZ describes the M2 as having a built-in DAC and selectable EQ presets. Its
published page does not state maximum sample rates or bit depths. Treat the
cable's physical report as evidence of advertised stereo 16/24-bit PCM at
44.1/48/88.2/96 kHz; streaming remains unverified. Its High-Resolution preset is described as a sound profile, so the label
alone does not establish a native-rate or bit-perfect playback path.
[KZ M2 product page](https://kz-audio.com/kz-m2.html)

With this USB cable, the relevant conversion endpoint is the cable's DAC,
rather than the phone's internal headphone DAC. Its supported formats and
internal processing matter alongside the phone's software and USB behavior.

Android 14 introduced preferred USB mixer controls, with optional bit-perfect
behavior. Those controls cannot be used as the solution for Android 11.
[Android USB mixer documentation](https://source.android.com/docs/core/audio/preferred-mixer-attr)

USB host access can be granted by the user. libusb documents an unrooted Android
approach using that granted USB connection's file descriptor. This is a possible
transport foundation, not a ready-made USB Audio Class player: the audio protocol,
clocking, streaming and device compatibility still need implementation and tests.
[Android USB host](https://developer.android.com/develop/connectivity/usb/host),
[libusb Android guidance](https://github.com/libusb/libusb/blob/master/android/README)

OPPO documents an OTG Connection setting for the A9 2020. Check the installed
ColorOS build and actual USB detection instead of assuming a menu path or setting
will behave identically on every build.
[OPPO A9/A5 OTG guidance](https://support.oppo.com/ke/answer/?aid=neu8708)

Not hearing a difference is not a measurement of the active audio format. The
plan verifies the path first; an audible improvement is not a release guarantee.

## 3. High-resolution playback: the central technical goal

Keep three separate concepts visible:

1. **Source:** what the file contains, such as stereo FLAC at 24-bit/96 kHz.
2. **Transport:** what decoded PCM the app actually sends to the output device.
3. **Device processing:** what the cable/DAC does after receiving those samples.

Reading a high-resolution file does not prove the output remains high-resolution.
Upsampling a 48 kHz file to 192 kHz does not recover additional source detail.
Matching sample rates alone does not prove unchanged sample values.

### Normal mode

- Android/Media3 playback for normal USB routing, phone speakers, the headphone
  jack, and Bluetooth where available.
- Audio focus, lock screen controls, cable buttons, notifications and interruptions.
- Optional EQ, ReplayGain/normalization, crossfade, balance and speed controls.
- Report source properties and output information that can actually be observed.
- No blanket bit-perfect claim for the phone's Android audio route.

### Direct USB mode

- A separate native USB Audio Class playback engine, validated on the OPPO/M2 pair.
- Decode lossless files to PCM and send a supported native configuration directly
  to the cable's playback endpoint, outside the normal Android mixer route.
- Preserve sample values and meaningful bit depth where supported. A 32-bit
  transport container can still carry only 24 meaningful source bits.
- Negotiate only formats the cable advertises and successfully accepts.
- Do not silently resample or truncate while claiming bit-perfect operation.
- Disable app EQ, normalization, crossfade, fades and speed changes in the
  unchanged-PCM mode. Lyrics and tag viewing can remain available.
- Use device volume controls if supported. Software volume scaling changes PCM
  samples and therefore cannot be presented as unchanged-PCM playback.
- Use the playback engine's clock for seek positions and lyric synchronization.
- Pause/release appropriately for interruptions; pause on unplug rather than
  automatically transferring music to the loudspeaker.
- Preserve system microphone/call/button behavior where possible and test it
  explicitly. Exclusive access may require returning to Normal mode for calls.

The cable's physical EQ can still alter the sound after USB transport. Standard
mode is the initial test preset, but must not be assumed to disable all internal
processing. The app cannot promise to bypass undocumented cable firmware DSP.

If the M2 supports only a lower-resolution format, software cannot turn it into
a higher-resolution DAC. The app remains useful; the original high-res bypass
goal would then require a different verified output device or a revised target.

## 4. Architecture

| Component | Proposed implementation | Purpose |
| --- | --- | --- |
| Interface | Kotlin, Jetpack Compose, Material design | Native touch UI and accessibility |
| Playback service | Media3 MediaLibraryService/MediaSession | One background playback owner and system controls |
| Normal playback | Media3 ExoPlayer | Standard device playback |
| Direct USB playback | C++/NDK USB engine and lossless decoder, behind a player adapter | Controlled PCM transport on Android 11 |
| Local library | Room/SQLite | Tracks, albums, artists, playlists, history, edit jobs and lyrics |
| Preferences | DataStore | Theme, playback preferences and output-device settings |
| Storage access | MediaStore plus user-selected folder access | Scan audio and manage adjacent artwork/LRC files |
| Tags/artwork | Evaluate TagLib through a small native bridge | Read and edit supported metadata containers |
| Online sources | Provider interfaces with local caches | Artist, artwork and lyrics lookups |

Media3 recommends hosting the player and media session in a service for playback
outside the app screen. The direct USB engine needs a compatible player adapter;
using ExoPlayer alone is not a promise of Android 11 USB bypass.
[Media3 background playback](https://developer.android.com/media/media3/session/background-playback)

TagLib supports metadata in MP3, FLAC, MP4 and several other formats. Its Android
bridge, writable content-URI handling, artwork operations and chosen format set
must be verified before adoption. Do not assume Python Mutagen can simply run
unchanged in the native Android app.
[TagLib documentation](https://taglib.org/api/)

Keep the first project structure small: one app with clear playback, storage,
editing and online-provider packages, plus native code where required. Pin
compatible stable tool/library versions when implementation starts; minimum
Android version is API 30, while compile/target settings are chosen separately.

## 5. Library and modern player experience

- Home with recently played, favorites, unfinished songs and recently added music.
- Library tabs for songs, albums, artists, folders and playlists.
- Search combined with sorting and format/tag filters.
- Persistent mini-player and a full now-playing screen with large artwork.
- Queue editing, Play next, Add to queue and a clear automatic Next up section.
- Browsing does not change the listening list.
- Play follows the displayed results; shuffle uses a sequence rather than early repeats.
- Song title/cover taps start fresh. Pause/Play retains the current position.
- Separate Resume actions; Previous restarts after three seconds, then goes back.
- Gapless playback as a verified requirement for supported lossless formats.
- Optional crossfade in Normal mode only; do not combine it with bit-perfect claims.
- Notification and lock screen controls, headset controls, interruption handling.
- Sleep timer, dark/light/AMOLED themes, adjustable text and accessible buttons.
- Playback state survives process death and reopening; opening the app stays paused
  unless the user explicitly requests playback through the app or a media control.
- Incremental scanning, progress, corrupt-file warnings and missing-file repair.
- App-owned track UUIDs mapped to source URIs; avoid making a pathname the permanent
  identity. Require confirmation for ambiguous moved-file matches.

## 6. Storage and safe tag/album-cover editing

Use MediaStore to discover indexed music and Android's folder picker for chosen
music folders, particularly for sidecar LRC files. Persist the granted folder
permissions. Android 11 restricts access to some folder roots and app directories;
the workflow must work within those rules.
[Android folder access](https://developer.android.com/training/data-storage/shared/documents-files)

For shared files, obtain the required write permission before applying edits.
Use Android's write-request flow where appropriate rather than assuming a readable
song is also writable. Runtime audio-library permission on Android 11 differs
from the newer READ_MEDIA_AUDIO permission used on newer Android releases.
[Android media storage](https://developer.android.com/training/data-storage/shared/media)

Editing workflow:

1. Choose one song, selected songs or an album.
2. Edit title, artist, album artist, album, track/disc number, date and genre.
3. Choose artwork from the device or a matched online release; crop/resize it.
4. Clearly choose **app-only artwork** or **embed artwork in the files**.
5. Preview all file changes, including differences between tracks in a batch.
6. Check permission, free space and whether the file changed since preview.
7. Stage changes using a temporary copy and a recoverable write journal. Validate
   the edited copy before replacing the original where the provider permits it.
8. Keep the original until the replacement is verified. Some document providers
   do not offer atomic replacement; document and test their recovery path.
9. Re-read the saved tags, refresh artwork caches and display per-file results.
10. Offer Undo for supported fields/artwork, with conflict checks and a storage budget.

Pause and close a file that is currently being edited, then reopen it and restore
the logical playback position. Preserve unrelated tags, multi-value artists,
existing artwork roles, Unicode and audio content. Do not rewrite the audio
encoding to change metadata. Raw AAC/WAV and other inconsistent tag containers
must have explicitly tested support or a clear app-only fallback.

## 7. Artist information and online cover art

- MusicBrainz for artist identities, credits and release matching.
- Use stable artist/release identifiers to reduce incorrect same-name matches.
- Wikimedia/Wikidata links for real biographies and suitable artist images.
- Cover Art Archive for artwork attached to matched releases/editions.
- Show candidate matches and allow manual correction and a local override.
- Keep biographies, artwork thumbnails and provider/source links cached offline.
- Respect attribution, media licensing, provider identification and rate limits.
- Network errors should leave local listening and editing available.

MusicBrainz permits non-commercial API use without an API key and requires an
identifying User-Agent and throttling. Its artist data is not itself a complete
biography/image service.
[MusicBrainz API](https://musicbrainz.org/doc/MusicBrainz_API),
[Wikimedia API](https://www.mediawiki.org/wiki/API:REST_API),
[Cover Art Archive API](https://musicbrainz.org/doc/Cover_Art_Archive/API)

## 8. Synced lyrics and in-app discovery

- Read existing neighboring `.lrc` files and supported embedded lyrics.
- Also support app-private lyric storage when the music folder is not writable.
- Full-screen synchronized lyrics, tap a line to seek and adjust timing offset.
- Search online from the song's menu, inspect candidate versions and preview
  synchronization before adding the selected result.
- Start with LRCLIB: title/artist/album/duration matching and manual keyword search.
- Cache chosen lyrics for offline use; save a sidecar LRC only when permission allows.
- Import/paste/edit LRC and insert timestamps using the actual playback position.
- Distinguish synced lyrics, plain text, instrumental tracks and no result.
- Never treat an instrumental result as a failed lookup or silently attach the
  wrong live/remastered version. Show the provider and chosen match.

LRCLIB exposes search plus plain/synced lyrics without a required API key. Identify
the client, throttle requests and honor Retry-After. Provider coverage is not a
guarantee that lyrics exist for every track.
[LRCLIB API](https://lrclib.net/docs)

## 9. Implementation milestones and acceptance gates

### Milestone 0 — OPPO/KZ hardware diagnostic APK

Deliver a small installable app that:

- Records Android/ColorOS version and identifies the actual connected M2 USB device.
- Requests USB access and inspects audio interfaces, endpoints and format descriptors.
- Lists sample rates, meaningful bit depths, channels, clock/feedback details and
  relevant hardware-volume capabilities. Save a local diagnostic report.
- Tests the physical EQ presets and cable buttons without sending undocumented
  firmware commands. Playback inspection must not require microphone recording.
- Establishes a normal-output baseline, then attempts a minimal direct USB stream.
- Starts with a known stereo lossless sample; tests 44.1/48 kHz and higher rates
  only when the cable advertises them. Test 16/24-bit where supported.
- Verifies screen-off playback, detach/reconnect and basic focus/call behavior.

**Gate:** choose the direct-engine approach and achievable resolution from measured
capabilities. Descriptor claims alone are not proof of successful playback.
If the bypass goal is impossible on the M2, record that before building the full UI.

### Milestone 1 — Installable offline player foundation

- Android project, reproducible build and signed personal APK.
- Folder/library selection, scan, Room database and basic now-playing UI.
- Normal-mode background playback, notification/lock screen/buttons and queue.
- Persisted session, fresh song clicks and correct Previous/Next/shuffle.

**Gate:** a complete local album plays with the screen off; navigation does not
change the listening list, and reopening does not unexpectedly start audio.

### Milestone 2 — Modern library and safe editing

- Complete browsing/search/sorting, playlists, favorites, sleep timer and themes.
- Tag and artwork preview/apply/undo, album-level editing and per-file failures.
- Internal storage and SD-card/folder permission tests.

**Gate:** editing preserves audio and unrelated metadata; denied permission, full
storage, interruption or a corrupt file does not damage other files.

### Milestone 3 — Online enrichment and lyrics

- Artist matching, cached biographies/images, release artwork selection.
- Local LRC, online search/preview/add, offsets, tap-to-seek and offline caching.

**Gate:** wrong/no match can be corrected without losing local tags or lyrics;
cached information remains usable in airplane mode.

### Milestone 4 — Integrated direct USB mode

- Integrate the Milestone 0 engine through the common service/player contract.
- Implement native-rate changes, exact PCM packing, seeking and buffer handling.
- One active playback engine at a time; explicit, predictable mode transitions.
- Disable processing that conflicts with unchanged PCM; validate hardware volume.
- Show source, requested format, negotiated transport, mode and any fallback.
- Pause/release for unplug and interruptions; recheck HID/buttons and microphone
  availability with system calls using this combined mic/DAC cable.

**Gate:** each claimed configuration plays correctly at the right speed without
truncation/dropouts. Compare decoded PCM with transmitted PCM test patterns.
Use digital loopback/known bit-perfect validation where equipment supports it.
A DAC rate display alone does not verify bit depth or internal processing.
Label transport validation separately from anything not observable inside the DAC.

### Milestone 5 — Personal daily-use release

- Real OPPO tests for long playback, ColorOS battery behavior, screen lock, calls,
  alarms, cable buttons, unplug/replug, low storage and process restarts.
- App/database backup/restore and documented signing-key backup.
- Optional import of current web playlists, likes and history after mapping the
  desktop paths to phone files; preview ambiguous matches rather than guessing.
- A signed APK, build instructions, short user guide and known-limitations list.

**Gate:** the app works independently of the PC, and an update preserves its data.

## 10. Reuse from the current project

Reuse behavior specifications and regression scenarios: browsing/listening
separation, queue priority, deterministic shuffle, Previous history, explicit
Resume, metadata preview/conflict checks, Undo and conservative artist matching.
Port these rules into Kotlin/native tests rather than embedding Flask and the
browser audio element as the Android playback engine.

Existing local files and exported playlist data are possible migration inputs.
They are not production Android storage paths or a drop-in database schema.

## 11. Scope, complexity and costs

- Core offline playback/UI: standard Android application work.
- Safe multi-format file editing: substantial, especially across document providers.
- Lyrics/artist enrichment: moderate integration work with matching/caching tests.
- Android 11 direct USB playback: the largest and most uncertain part; this is
  native audio/USB engineering, not a single high-res setting.
- Start with stereo PCM and FLAC/WAV for direct-engine validation. Add further
  decoding formats only after the path is reliable. DSD/DoP, MQA, streaming-service
  integrations and support for arbitrary phones/DACs are later scope.
- Default to open-source components with license review. Investigate a commercial
  USB engine only if an appropriate SDK is actually available and its price and
  terms are known. Buying another player app does not provide a reusable SDK.
- No purchases, paid services, device modification or publishing are part of
  approving this plan alone.

The first useful development task is the hardware diagnostic APK. It turns the
unknown OPPO/M2 capability into evidence and sets an honest target for the full app.
