# First Local Music phone check

This installs the real player alongside the existing probe. No hour-long run is
requested. It plays your own music in **Normal** mode; Direct USB is still gated.

1. Stop any Direct test in Music USB Probe. Copy/open
   [local-music-0.1.0.apk](releases/local-music-0.1.0.apk) on the phone and install
   it. Open **Local Music**, not Music USB Probe. Keep both applications installed.
2. Tap **Find my music** or **Scan** and allow music-read access. Wait for the scan
   to finish. Check that songs from your internal storage/SD card appear. Search
   for one known song. Missing unindexed files/folder selection are a known limit.
3. Start that song at a comfortable **phone media volume**. Pause after about
   20 seconds, then Play: it should keep its position. Tap the song in Library:
   it should start at 0:00. Home's explicit **Continue** should restore a saved
   unfinished position (beyond 15 seconds and outside the final five seconds).
4. Use a song's **More** menu to favorite it and queue the same song twice. Check
   both entries appear in Queue. Try Previous/Next, seek, shuffle and repeat.
   Sorting or browsing another list should leave the active sequence intact.
5. Play with the screen locked for a few minutes. Check shade/lock-screen media
   controls. Switch to another app and return. Record any silence, crackle,
   crash, control failure or unwanted restart.
6. While playing through M2, unplug it. Playback should pause; reconnect should
   remain paused. Report any sound switching to the phone speaker. An incoming
   call/focus interruption also needs a later acceptance check; no automatic
   resumption is intended.
7. Pause, wait a couple of seconds for the save, dismiss Local Music from recent
   apps, then reopen it. The saved session should be paused, with favorites and
   queue retained. Press Play yourself.
8. Go to **Settings → Save playback report**. Save as
   **local-music-normal.json** and provide it with observations. Export before
   dismissing/killing the app if investigating a fault: this preview's report
   journal covers only the current service. Reports can include song titles.

Start with one familiar WAV/FLAC or MP3. Other format targets and all storage/call
conditions are separate acceptance checks, not established by installing an APK.
Music editing, lyrics, real folder picking and backup are not part of this preview.
