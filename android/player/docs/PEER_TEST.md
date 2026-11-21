# Local Music controlled-peer test

[Issue #17](https://github.com/HERACLES02/HIGHRES-MUSIC-PLAYER/issues/17) covers one generated FLAC. The test APK is **Local Music Peer Test**, package `com.localmusic.player.peertest`. It has its own settings, folder grants, and Library.

## Transfer engine

| Candidate | Android packaging | License and notices | Controlled endpoint and byte offset |
| --- | --- | --- | --- |
| Soulseek.NET | The .NET Standard library would need a .NET Android runtime and an integration with the Kotlin app, or a separately packaged native bridge. | GPLv3 with additional terms, including a unique minor version when logging in. The project includes a NOTICE file. | `ConnectAsync(address, port, …)` can use a local coordination server. `DownloadAsync` accepts a byte offset and total size. [Client interface](https://github.com/jpdillingham/Soulseek.NET/blob/10f2c464c610edd9a1d11cf52f5601c24d374dad/src/ISoulseekClient.cs) · [License](https://github.com/jpdillingham/Soulseek.NET/blob/10f2c464c610edd9a1d11cf52f5601c24d374dad/LICENSE) · [Notice](https://github.com/jpdillingham/Soulseek.NET/blob/10f2c464c610edd9a1d11cf52f5601c24d374dad/NOTICE) |
| soulseek-rs | The Rust library could be built with the NDK for each supported ABI and called through JNI. That adds a Rust build, per-ABI artifacts and bridge maintenance. | MIT. Retain the copyright and license notice when distributing the library. | `ClientSettings.server_address` can point to a local server. The download worker uses existing partial-file length to resume and sends the offset as eight little-endian bytes. [Client settings](https://github.com/michel/soulseek-rs/blob/434dcc0155ed87203cafcd1cf7b1a1073f88a63d/soulseek-rs-lib/src/client/mod.rs) · [Download worker](https://github.com/michel/soulseek-rs/blob/434dcc0155ed87203cafcd1cf7b1a1073f88a63d/soulseek-rs-lib/src/peer/download_peer.rs) · [License](https://github.com/michel/soulseek-rs/blob/434dcc0155ed87203cafcd1cf7b1a1073f88a63d/LICENSE) |

The APK uses a small Kotlin implementation of the documented direct peer transfer. The uploader and downloader know each other's endpoints, so the fixture needs no coordination server, account, or login. The app sends `PeerInit (P)` and `QueueUpload`, accepts the `TransferRequest`, and receives `PeerInit (F)`, the transfer token and the raw FLAC on the file connection. It sends a zero byte offset. This keeps the experiment on the existing Kotlin toolchain and adds no bundled third-party transfer code. Of the two libraries, soulseek-rs has the closer packaging fit for a future full client. Resumption after a dropped connection is tracked in issue #19. [Soulseek peer protocol](https://nicotine-plus.org/doc/SLSKPROTOCOL.html#example-flow-for-downloading-a-file)

## Build and try it

Run from `D:\music_player` with the existing Android toolchain, Python and FFmpeg.

```powershell
Push-Location android
try {
    $env:ANDROID_USER_HOME = Join-Path (Get-Location) '.tools/android-user'
    & .tools\gradle-8.13\bin\gradle.bat --offline --no-daemon --console=plain -g .tools/gradle-cache :player:assemblePeerTest
    if ($LASTEXITCODE -ne 0) { throw 'Peer test APK build failed.' }
} finally { Pop-Location }

New-Item -ItemType Directory -Force android\.tools\peer-test | Out-Null
ffmpeg -v error -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=12' -metadata 'title=Local Music Peer Tone' -c:a flac android\.tools\peer-test\peer-tone.flac

$adb = 'D:\music_player\android\.tools\sdk\platform-tools\adb.exe'
& $adb install -r android\player\build\outputs\apk\peerTest\player-peerTest.apk
& $adb reverse tcp:45416 tcp:45416
& $adb forward tcp:45417 tcp:45417
python android\scripts\soulseek-fixture-peer.py android\.tools\peer-test\peer-tone.flac
```

With one USB-debugging device connected, the forwarding commands use loopback at both ends. For a private LAN test, start the script with `--bind` and the PC's private IPv4 address, enter that address on the phone, and use the same LAN on both devices. The peer listens on TCP 45416 and opens the file connection to TCP 45417 on the phone.

1. Open **Local Music Peer Test**. Enter `127.0.0.1` for USB or the private LAN address, and paste the SHA-256 printed by the script.
2. Choose a new empty subfolder under **Internal storage → Music** and tap **Download or retry**. The built-in Android storage provider is used so publication is a rename within the same folder.
3. Wait for **Ready in Library**. Repeat **Download or retry**, tap **Open Library**, then select the **Library** tab. There should be one **Local Music Peer Tone** and one **Local Music peer fixture.flac** in the chosen folder. Automatic rescans are off by default in a fresh test install.
4. Stop the peer with Ctrl+C, remove the forwards with the commands below, and disconnect the phone from the network. Set a comfortable volume and play the generated tone via the regular Library.

```powershell
& $adb reverse --remove tcp:45416
& $adb forward --remove tcp:45417
```

For a failure check, run the peer with `--truncate` in a fresh disposable folder. A premature disconnect must leave the folder without a playable FLAC. For a finalization failure check, put a different disposable file named `Local Music peer fixture.flac` in an unscanned disposable folder. The transfer should report the conflict and preserve the file. Select another empty folder and retry with the peer stopped. The app can publish the verified private copy, then request the usual Library scan.

## Switch apps and lock the phone (issue #18)

Use the same APK, forwarding commands and folder picker. Stop any previous fixture peer with Ctrl+C, then generate and serve a distinct tone. At 16 KiB/s this gives roughly 45 seconds to check the notification and lock the screen.

```powershell
ffmpeg -v error -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=60' -metadata 'title=Local Music Lock Test Tone' -c:a flac android\.tools\peer-test\lock-tone.flac
python android\scripts\soulseek-fixture-peer.py android\.tools\peer-test\lock-tone.flac --kib-per-second 16
```

1. Use a fresh disposable folder and paste the SHA-256 printed by the peer. Tap **Download or retry** and allow notifications if Android prompts. If notifications were previously blocked, allow them under **Settings → Apps → Local Music Peer Test → Notifications**.
2. Open another app while the download is in progress. Pull down the notification shade and record the byte count for **Controlled FLAC download**. Lock the screen for about ten seconds, then wake it without unlocking and record the new count if your lock screen shows notification details. If it hides details, keep the phone locked and run the two measurements below.
3. Let the download finish. Reopen **Local Music Peer Test** and wait for **Ready in Library**. Stop the peer, remove the forwards if used, and put the phone offline. Open the Library and check that **Local Music Lock Test Tone** appears once and plays at a comfortable volume.

For measurements over USB, paste the SHA-256 printed by the peer. Start the commands while the phone is locked and the notification is still showing a transfer. The two counts should increase while the screen stays off.

```powershell
$fixtureSha = '<paste the 64-character SHA-256 here>'
$part = "files/peer-fixture-$fixtureSha.part"
& $adb shell run-as com.localmusic.player.peertest stat -c %s $part
Start-Sleep -Seconds 10
& $adb shell run-as com.localmusic.player.peertest stat -c %s $part
```

### Emulator results

The issue #17 baseline stopped at 94,208 staged bytes after exiting the transfer screen and putting the emulator to sleep. Android had no transfer notification. On the issue #18 build, Android API 37 switched to Settings and stayed asleep with the keyguard showing for both measurements. Staged bytes increased from 159,744 to 299,008 while the notification advanced from 19% to 39% of the 728,597-byte file.

The transfer finished and released its wake lock. With ADB forwarding removed, airplane mode on and Wi-Fi disabled, retry left one file with the original SHA-256 and one Library entry. Android's media session reported that the tone was playing at 4,795 ms. The Peer Test APK build, signature verification, lint, player-core and Room checks passed.

### Physical phone acceptance

Pending. Record the two byte counts, whether the progress notification appeared, and whether the single Library entry played offline.
