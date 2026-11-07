# Silent USB focus test helper

Follow-up: [controlled Direct focus-loss result](../FOCUS_PHONE_RESULTS_2026_11_07.md)
and the user's no-restart observation are accepted for Probe 0.7.0 on CPH1937.
The steps below document the method; do not repeat the accepted component check
by default. They do not establish current Media3/integrated-service acceptance.

Separate app: `com.localmusic.focusprobe`, label **USB Focus Test**. Its button
requests `AUDIOFOCUS_GAIN_TRANSIENT` while the activity is visible, abandons the
same request after five seconds, and releases it on leaving the foreground or
destruction. It plays no sound and contains no player, USB or file-access code.
It requests no permissions and has no third-party dependencies. Launching it
does not request focus; an explicit button tap is required.

**GRANTED** means Android granted the helper's request, not that Direct stopped.
Inspect the probe's report and observe whether it stays stopped. This tests
transient media focus, not an incoming call, DAC clocking or analog balance.

Build from `D:\music_player`, with installed SDK/JDK and the existing private
debug key. No downloads, new signing key or Gradle dependencies are needed:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\focus-probe\build.ps1
```

Output: ignored `android/releases/usb-focus-test-1.0.apk` and `.apk.sha256`.
Java/dex compilation, packaging, alignment, signature, package identity and
no-permission checks passed. No local phone/emulator runtime test was possible.
The helper does not replace Music USB Probe or Local Music.

## Phone steps

1. Install `android/releases/music-usb-probe-0.10.0.apk` as an **update** to Music
   USB Probe. Install `android/releases/usb-focus-test-1.0.apk` as a separate app.
   Do not uninstall the probe, clear data, or change Local Music.
2. Connect M2 to the phone, select **Standard**, keep earphones out and leave
   hardware volume low. In the probe, use Inspect cable if permission is needed.
   Start equal-tone WAV, **24-bit / 96000 Hz / 1 min**, accepting its experimental
   notice. This check should end early; it does not require a full minute.
3. Switch to **USB Focus Test**, tap **Request focus for 5 seconds** once, and
   wait for **GRANTED**, followed by its release/abandon result. Note any denial
   or error rather than treating it as a Direct failure.
4. Return to Music USB Probe. Direct should have ended by itself and remain
   stopped. Do not press Stop unless it is still running; if you must, say so.
5. Before another run, use **Save last stream report as JSON** and name it
   `m2-direct-focus-controlled.json`. Record whether Direct stopped on its own,
   stayed stopped, and whether the helper reported GRANTED.

If restoration is unverified, reconnect M2 before listening. No ordinary music,
Clock alarm, recording equipment or endurance run is needed.

For wired ADB installation, connect the phone to the PC first:

```powershell
cd D:\music_player
& .\android\.tools\sdk\platform-tools\adb.exe devices
& .\android\.tools\sdk\platform-tools\adb.exe install -r .\android\releases\music-usb-probe-0.10.0.apk
& .\android\.tools\sdk\platform-tools\adb.exe install -r .\android\releases\usb-focus-test-1.0.apk
```

The phone must be listed with USB debugging already authorized. If several
devices are listed, select the intended phone before installing. Alternatively
copy both APKs to the phone and open them there. Then disconnect it from the PC
and attach M2; wireless ADB/root is not needed.

Reference: [Android audio focus](https://developer.android.com/media/optimize/audio-focus).
The previous timer attempt recorded no focus event; this helper supplies an
explicit request without assuming a Clock app will request media focus.
