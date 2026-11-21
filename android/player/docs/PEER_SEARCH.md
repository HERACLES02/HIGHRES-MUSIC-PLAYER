# Private Soulseek search in Local Music Peer Test

This increment for [issue #16](https://github.com/HERACLES02/HIGHRES-MUSIC-PLAYER/issues/16) builds on `5b87f2c`. The Android app owns the client session, search, folder request and track download. The Windows processes serve as the test coordinator and uploader. Every test account connects to the loopback coordinator on port 45415.

## Engine choice

The bridge pins [soulseek-rs 20.0.0 at `434dcc0`](https://github.com/michel/soulseek-rs/tree/434dcc0155ed87203cafcd1cf7b1a1073f88a63d). It builds with Rust and the existing Android NDK. A small JNI bridge hands file metadata to Kotlin, which owns the screen, foreground transfer service, verification, destination handoff and Library scan. The APK carries the engineâ€™s MIT copyright and permission notice. A 32-bit ARM test build of upstream fails on Androidâ€™s `u32` file-descriptor limit being compared with a `u64` constant. This increment packages the engine for ARM64. A narrow upstream change to normalize the integer widths would let us retry the 32-bit build. [Upstream connection code](https://github.com/michel/soulseek-rs/blob/434dcc0155ed87203cafcd1cf7b1a1073f88a63d/soulseek-rs-lib/src/client/connection.rs)

## Run the controlled test

From `D:\music_player`, build with the existing local Android toolchain and Rust 1.91 or newer. The first build fetches pinned Rust dependencies.

```powershell
rustup target add aarch64-linux-android
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android\scripts\build-peer-search.ps1
```

Use the [Windows Soulfind release](https://github.com/soulfind-dev/soulfind/releases) on a trusted test network. In one PowerShell window, run the unpacked `soulfind.exe --database peer-search.db --port 45415`. In a second window, start the uploader with a disposable directory containing two generated fixtures named `Amber.flac` and `Violet.flac`. Example fixture generation is in [the controlled-peer instructions](PEER_TEST.md).

```powershell
$fixture = 'android\.tools\peer-test\search-share'
& android\.tools\peer-test\bridge-target\debug\fixture-seeder.exe $fixture peer-fixture-a 45416
```

With one USB-debugging device selected, run the following in a third PowerShell window.

```powershell
$adb = 'D:\music_player\android\.tools\sdk\platform-tools\adb.exe'
& $adb install -r android\player\build\outputs\apk\peerTest\player-peerTest.apk
& $adb reverse tcp:45415 tcp:45415
& $adb reverse tcp:45416 tcp:45416
& $adb forward tcp:45417 tcp:45417
$fixture = 'android\.tools\peer-test\search-share'
(Get-FileHash -Algorithm SHA256 "$fixture\Amber.flac").Hash
```

1. Open **Local Music Peer Test**. Paste the hash for `Amber.flac` and choose a fresh disposable folder under **Internal storage â†’ Music**.
2. Under **Private Soulseek search**, search `Amber`. Confirm the search result gives the uploader and folder. Tap **Inspect peer-fixture-aâ€™s folder** and confirm `Violet.flac` also appears.
3. Tap **Download Amber.flac** and wait for **Ready in Library**. Confirm the destination contains one `Local Music peer fixture.flac` with the same SHA-256 as `Amber.flac`.
4. Stop both fixture processes with Ctrl+C and remove the forwards. With the phone offline, open the Library and play the imported track by its embedded title.

```powershell
& $adb reverse --remove tcp:45415
& $adb reverse --remove tcp:45416
& $adb forward --remove tcp:45417
```

The SHA-256 field supplies an independent check for the generated fixture. The Android service keeps the transfer in its private files until both size and SHA-256 match. The existing publisher copies through a `.part` file, checks the copy and renames it before requesting the existing Library scan. Each selection uses private staging keyed by uploader, remote path, size and fixture hash.

## Test Rust download recovery

Create a longer `Amber.flac` in a separate disposable share before starting the uploader. Peer Test limits the Rust download to 64 KiB/s so the transfer has time to be interrupted.

```powershell
$share = 'android\.tools\peer-test\recovery-share'
New-Item -ItemType Directory -Force $share | Out-Null
Copy-Item 'android\.tools\peer-test\search-share\Violet.flac' $share
ffmpeg -f lavfi -i 'anoisesrc=color=white:amplitude=0.02:sample_rate=48000:duration=65:seed=42' -ac 2 -c:a flac -metadata 'title=Local Music Rust Resume Tone' "$share\Amber.flac"
(Get-FileHash -Algorithm SHA256 "$share\Amber.flac").Hash
```

Start the uploader with `$share` in place of `$fixture`. Paste the printed hash in Peer Test, choose a fresh disposable destination, and start with Wi-Fi connected.

1. Search **Amber**, inspect the folder to find **Violet**, then tap **Download Amber.flac**. The three operations share one login.
2. While bytes are still arriving, lock the screen. Check that the download notification advances. Pause from the notification or return to Peer Test and tap **Pause**. Tap **Resume** and check the nonzero starting offset displayed with the download progress.
3. Turn Wi-Fi off while bytes are arriving. Compare the saved byte count in **Waiting for Wi-Fi** with the resume offset after Wi-Fi returns. The retry starts automatically.
4. While bytes are arriving again, force-stop only Peer Test, then reopen it.

   ```powershell
   & $adb shell am force-stop com.localmusic.player.peertest
   & $adb shell am start -n com.localmusic.player.peertest/com.localmusic.player.PeerTestActivity
   ```

5. Confirm the selected track waits for **Resume** and no transfer restarts by itself. Resume, wait for **Ready in Library**, and compare the delivered file's SHA-256 with the original.
6. Select the same download again with the same destination and hash. There should still be one finished file and one track in the Library.

The foreground service saves the selected uploader, exact remote path, size, hash and destination before starting the transfer. Rust stages incomplete bytes privately. Manual Pause and Wi-Fi interruption stop the active transfer before reusing its partial. Peer Test verifies the staged and published copies against the known fixture hash before exposing the track in the Library.

## Controlled Android result

On the ARM64 OPPO CPH1937, `Amber` returned separate uploader and folder groups for `peer-fixture-a` and `peer-fixture-b`. Opening the first uploaderâ€™s folder also showed `Violet.flac`. Selecting `Amber.flac` produced one 152,312-byte file identical to the original fixture. With the fixture shut down, USB forwards removed, airplane mode on and Wi-Fi off, the track appeared once in the Library as **Local Music Peer Tone**. Android playback advanced from 1,022 to 6,639 ms. The evidence is from the phoneâ€™s media session and file comparison. The user subsequently confirmed audible playback of the fixture.

The ARM64 Rust build, Peer Test APK assembly and signature, Peer Test lint, player-core and Room checks passed. The recovery steps above exercise the later Rust transfer changes separately.

## Controlled Rust recovery result

On the ARM64 OPPO CPH1937, search, folder inspection and the initial download shared one coordinator connection. In the first recovery build, the private file grew from 825,741 to 1,717,017 bytes and the notification went from 9% to 22% with the screen asleep and keyguard showing.

In the 64 KiB/s build, **Pause** closed the transfer and preserved 4,239,791 bytes. After restarting Wi-Fi, the app automatically resumed at 4,456,040 bytes. A force-stop preserved 4,619,865 bytes and the track waited for **Resume** after relaunch. It resumed at that offset and published one 7,175,535-byte FLAC with the same SHA-256 as the generated fixture. Selecting it again left one file and one matching Library row.

## Before a public login

Soulseek's rules expect third-party clients to implement search, wishlist, downloads, uploads, chat and recognition of privileges. This increment exercises the search and download path on the private coordinator. [Soulseek rules](https://www.slsknet.org/news/node/681)

| Capability | Remaining Android work |
| --- | --- |
| Accounts and connection | Secure credential storage and session lifecycle, plus a client minor version reserved with upstream. Test ordinary peer connectivity and server-brokered fallback on mobile networks when authorized. |
| Downloads | Verify uploader queue behavior and recovery with public peers. Replace the fixture's manual trusted hash and fixed destination name with a public-file integrity and conflict policy. |
| Uploads | Expose explicitly approved share folders through Android's storage permissions. Keep sharing initially off. Test serving files, the upload queue, and listener reachability. |
| Chat | Expose room and private messages, including reception and acknowledgement across reconnects. |
| Wishlist | Store searches and schedule them using the server-provided interval. |
| Privileges | Verify server-listed priority is applied to the phone's actual upload queue. |

Upstream documents engine support for uploads and priority queues, chat, and wishlist requests. It also documents interoperability gaps for obfuscated connections, locked file listings and firewalled distributed-search children. [Upstream protocol coverage](https://github.com/michel/soulseek-rs/blob/434dcc0155ed87203cafcd1cf7b1a1073f88a63d/docs/protocol-coverage.md)
