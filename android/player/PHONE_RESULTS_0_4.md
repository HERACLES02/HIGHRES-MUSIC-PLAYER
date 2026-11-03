# Local Music 0.4.0 phone acceptance, 3 November 2026

User confirms: "Yes it works" for the manual-queue editing functions on the
phone after installing the `0.4.0-queue-edits` update over the existing
0.3 preview. This is user-observed acceptance evidence, covering the
checklist given with the release: remove one queued occurrence while a
duplicate of the same song stays queued, move up / move down, Clear queue
while a song is playing, and reopening to find the edited order restored
paused.

Evidence level and honesty notes (keeping the established distinction):

- No JSON report export accompanies this round, so there are no
  application-side counters, event samples or timestamps to recompute.
  Accept the statement as user confirmation, not measured evidence.
- The sub-results were given as an overall pass; no itemized per-step
  evidence (for example, whether the edited order was specifically checked
  after app restart versus just re-display) was itemized back. The restart
  retention of edited order is also covered locally by the 0.4 suite
  (remove+move session round-trips across disk reopen), so nothing is
  claimed here that the code does not also assert.
- Library retention across the 0.3 → 0.4 update is implied by the user
  exercising the queue on existing songs, but was not separately confirmed
  item by item (favorites, lyrics, playlists).

Previously accepted history is unchanged: 0.2
scan/write/undo/playback results and the 0.3 saved-playlist acceptance stay
accepted, and no re-run of those checks is requested. The 0.4.0 build
evidence (local suites 10,406 core + 93 Room + 24 fixture assertions,
frozen schema v3, APK SHA-256
`2fb5be3a25646cbbea952a58832ecda90356567946ddcbd4f39dcf470801d47f`, v2
signature, independent review + re-review verdicts) is recorded in
`VERIFICATION_0_4.md`.

The 0.3 open observation (missing-storage playlist placeholder behavior on
device) remains open and documentary. Review polish items (rotation-safe
dialogs, six-tab fit on narrow screens, play-from-here fallback on
unavailable rows, play-button readiness gating) stay deferred to later
increments.
