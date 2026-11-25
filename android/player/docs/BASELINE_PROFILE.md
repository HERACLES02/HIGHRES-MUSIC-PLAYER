# Baseline profile

A baseline profile lists the classes and methods the app uses during start-up, while scrolling Library and while opening Now Playing. Android compiles those ahead of time when the app is installed, so the first launches after an update don't run that code in the slower interpreter.

- The profile lives in `android/player/src/main/baseline-prof.txt`. The release build compiles it (rewritten for R8) into `assets/dexopt/baseline.prof` in the APK. `androidx.profileinstaller` installs it on the phone the first time the app starts.
- `android/baselineprofile` is the test module that records the profile. It drives a separate copy of the player called `com.localmusic.player.profilegen` (the `profileGen` build type: unminified, profileable, signed with the debug key). The real app and its data are never touched.
- Profiles hold class and method names only, so a profile recorded on the emulator works on the phone.

Run every command below in PowerShell from `D:\music_player`.

## 1. Measure "before" on the phone

Do this while the phone still has the old version installed. Connect the phone over USB with USB debugging on, make sure nothing is playing, then run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\measure-cold-start.ps1 -Label before
```

The script cold-starts the app 10 times. It records the median "initial display" time (first frame) and the median "fully drawn" time (library and playback session ready). It adds a row to `android/player/PHONE_RESULTS_BASELINE_PROFILE.md`. Unplug the phone afterwards so the next steps only see the emulator.

## 2. Start the emulator

The emulator needs Android 13 or newer. `LocalMusic_API_37` qualifies.

```powershell
$env:ANDROID_AVD_HOME = 'D:\music_player\android\.tools\avd'
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd LocalMusic_API_37
```

Wait for the home screen. Then, in a second PowerShell window, copy some test songs onto it. Use copies, not your only originals; 50 or more songs gives Library something to scroll:

```powershell
& D:\music_player\android\.tools\sdk\platform-tools\adb.exe push "C:\path\to\some\test\songs" /sdcard/Music/ProfileSongs
```

## 3. Install the profilegen app and set it up once

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\generate-baseline-profile.ps1 -InstallOnly
```

On the emulator, open the newly installed **Local Music**. Allow notifications if it asks. Pick the `Music/ProfileSongs` folder and wait for the scan. Then play any song and pause it. The mini player has to be showing, because the generator opens Now Playing from it.

## 4. Record the profile

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\generate-baseline-profile.ps1
```

This runs the journey several times: start the app, open Library, scroll it, open Now Playing. You'll see the app move on its own; don't touch the emulator. At the end it writes `android/player/src/main/baseline-prof.txt` and prints how many rules it holds. If both an emulator and the phone are connected, add `-Serial emulator-5554` (the name `adb devices` shows).

If it stops with "Library list not found" or "Mini player not found", repeat the setup in step 3 and run it again.

## 5. Build, sign, install, and measure "after"

Build the unsigned release APK (`:player:assembleRelease`), which now carries the profile. Then sign it:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File D:\music_player\android\scripts\sign-phone-apk.ps1
```

Install it on the phone over the existing app, open it once, close it, then reconnect over USB and run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\measure-cold-start.ps1 -Label after
```

Both runs first install the APK's shipped profile and compile the app from it with the phone's idle-time compile job (`cmd package bg-dexopt-job`). That way "before" and "after" differ only in the profile inside the APK. Compare the two rows in `PHONE_RESULTS_BASELINE_PROFILE.md`.

## When to regenerate

Regenerate after big changes to start-up, Home, Library or Now Playing code. An old profile doesn't break anything: rules for code that no longer exists are ignored, and new code just runs without a profile until the next one is recorded.
