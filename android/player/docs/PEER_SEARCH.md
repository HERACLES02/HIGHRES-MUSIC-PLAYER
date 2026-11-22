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

Soulfind can advertise the PC's LAN address even when the coordinator connection uses USB. Keep the phone and PC reachable on the private test LAN, or use loopback addresses in the coordinator's peer-address replies for the USB-only fixture setup.

1. Open **Peer Transfer Diagnostics**. Paste the hash for `Amber.flac` and choose a fresh disposable folder under **Internal storage → Music**.
2. Under **Private Soulseek search**, search `Amber`. Confirm the search result gives the uploader and folder. Tap **Inspect peer-fixture-aâ€™s folder** and confirm `Violet.flac` also appears.
3. Tap **Download Amber.flac** and wait for **Ready in Library**. Confirm the destination contains one `Local Music peer fixture.flac` with the same SHA-256 as `Amber.flac`.
4. Stop both fixture processes with Ctrl+C and remove the forwards. With the phone offline, open the Library and play the imported track by its embedded title.

```powershell
& $adb reverse --remove tcp:45415
& $adb reverse --remove tcp:45416
& $adb forward --remove tcp:45417
```

The SHA-256 field supplies an independent check for the generated fixture. The Android service keeps the transfer in its private files until both size and SHA-256 match. The existing publisher copies through a `.part` file, checks the copy and renames it before requesting the existing Library scan. Each selection uses private staging keyed by uploader, remote path, size and fixture hash.

<a id="download-one-track-from-the-soulseek-tab"></a>

## Download selected tracks from the Soulseek tab

Use the same ARM64 Peer Test APK, private coordinator, uploader, and ADB forwards above. The launcher entry **Local Music Peer Test** opens the regular player with the controlled Soulseek option. **Peer Transfer Diagnostics** opens the original transfer test screen.

1. In **Settings**, turn off **Rescan chosen folders when music changes** and turn on **Enable Soulseek tab**.
2. On Wi-Fi, open **Soulseek**, tap **Go online**, and search for `Amber`. Inspect the fixture uploader's folder and confirm it includes `Violet.flac`.
3. The inspected folder opens with zero selected. Choose one track or tap **Select all FLACs** for both. Check the count and total bytes, then tap **Continue to Downloads**.
4. Choose a fresh writable folder under **Internal storage → Music**, paste each selected fixture's SHA-256, and tap **Download**. Downloads shows progress for each track. Use its Library button as soon as a track is ready. Both files go in one album subfolder. Compare them with their respective fixture hashes.
5. Select **Go offline** from Soulseek when finished. The file remains available to the regular offline player.

This controlled build uses the generated fixture's SHA-256 to verify the private staging file and published copy. The coordinator address and test account are fixed in the engine. The regular daily-use APK has four tabs.

### Exercise save recovery

1. In a fresh disposable destination, temporarily rename that destination in the phone's Files app after choosing it. Download the selected fixtures. Wait for a save error and confirm that no partial FLAC appeared in the Library.
2. Restore the folder, pick it again if Android requires access, and stop both fixture processes. Tap **Go offline**, then **Retry save**. The verified private copy should reach the Library. Compare the saved file with the fixture.
3. Go online with the fixtures running and repeat the selection in the same destination. Confirm that exact duplicates are skipped and that the file count stays the same.
4. In a disposable album subfolder, arrange for a different file under each of the two expected names. Repeat the selection. Use **Skip** on one track and **Save separate copy** on the other. Compare the original hashes before and after both choices and compare the suffixed copy with its fixture.

The earlier single-track build was checked on the ARM64 OPPO CPH1937. The `Amber` search found the fixture uploader and folder inspection revealed `Violet.flac`. The saved 165,652-byte `Amber.flac` matched the source SHA-256 and its decoded audio matched the embedded FLAC checksum. **Open in Library** showed one matching song with automatic rescans off.

### Album and save-recovery result

On the ARM64 OPPO CPH1937, an `Amber` search and folder inspection exposed both fixtures. The selection advanced from zero to one to two tracks totaling 894,249 bytes. Both tracks reported transfer progress. Amber reached the Library while Violet was still queued, and both published files matched their source SHA-256 values. Repeating the selection left the same two files in the album.

With different files under both expected names, **Skip** left Amber's existing file in place and **Save separate copy** wrote `Violet (2).flac`. Both original hashes stayed the same. After a separate save failed because the selected folder was unavailable, Amber's private copy still matched its fixture hash. Restoring the folder and reopening the app with both fixture processes stopped allowed **Retry save** to import the verified copy into the Library.

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

1. In **Peer Transfer Diagnostics**, search **Amber**, inspect the folder to find **Violet**, then tap **Download Amber.flac**. The three operations share one login.
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

## Check folder sharing and privilege order

Use the private ARM64 Peer Test APK and a disposable folder containing generated FLAC files only. Put at least one file in a subfolder. Copy that folder under **Internal storage → Music** on the phone, keeping the same folder name. The Sharing tab previews the private copy, including nested paths and byte counts. **Enable sharing** publishes that exact copy for the current session. Turning sharing off, going offline, or losing Wi-Fi withdraws it. Pick and preview the folder again before enabling a new share.

Stop the other private coordinator before starting this test. The test runs its own loopback coordinator, which reports one day of privileges for the phone account and grants priority to two fixture peers. With the phone connected over USB for debugging, keep its Wi-Fi on and run the following from the repo root.

```powershell
$adb = 'D:\music_player\android\.tools\sdk\platform-tools\adb.exe'
$fixture = 'D:\path\to\disposable-fixture-folder'
& $adb install -r android\player\build\outputs\apk\peerTest\player-peerTest.apk
& $adb push $fixture /sdcard/Music/
& $adb reverse tcp:45415 tcp:45415
& $adb reverse tcp:45418 tcp:45418
& $adb forward tcp:45417 tcp:45417
python android\scripts\test-peer-sharing.py $fixture
```

Follow the prompts in **Soulseek → Sharing**. The peers check that the initial listing is empty and that the enabled listing matches the preview. They hold one active upload open while two ordinary requests and then two privileged requests arrive. The active upload must finish first. The waiting peers must receive the file in privilege order and in arrival order within each group. The test compares all five downloads with the source SHA-256. It then verifies that disabling sharing withdraws the listing, stops an accepted upload, and denies a new request for the withdrawn file.

The USB forwards allow deterministic protocol checks while Android enforces its Wi-Fi policy. On a private Wi-Fi network where both devices can reach each other, pass `--phone-host <phone-Wi-Fi-IP> --file-host <PC-Wi-Fi-IP>` to send the peer control and file connections directly over Wi-Fi. The coordinator still uses the USB reverse on port 45415.

On the OPPO CPH1937, the Sharing tab showed the nested fixture paths and the coordinator's reported privilege time. Controlled peers over USB forwards received five byte-identical uploads. In a screen-off run, the active transfer finished first, followed by the two privileged peers and then the two ordinary peers, in arrival order within each group.

A separate run verified that turning sharing off withdrew the listing, stopped an accepted upload, and kept a new request out of the queue. Disabling Wi-Fi also withdrew the listing and removed the foreground service and private copy. Restoring Wi-Fi kept sharing off. Force-stopping with sharing enabled left the next launch offline and cleared the leftover copy. The direct Wi-Fi connection attempt timed out on the test LAN.

```powershell
& $adb reverse --remove tcp:45415
& $adb reverse --remove tcp:45418
& $adb forward --remove tcp:45417
```

## Exchange private and room messages

Build and install the ARM64 Peer Test APK using the commands above. Start a fresh private Soulfind coordinator on port 45415 and use the USB reverse for that port. With Wi-Fi connected on the phone, run the fixture peer from the repository root.

```powershell
cargo run --locked --manifest-path android/peer-search/Cargo.toml --target-dir android/.tools/peer-test/bridge-target --bin fixture-chat
```

1. Open **Soulseek**, tap **Go online**, and stay on **Search**. Follow the fixture peer's prompt. **Chat (1 unread)** should appear. Open **Chat → Private**, tap **peer-fixture-chat (1 unread)**, and check that opening it clears the badge. Send `private reply`. The fixture peer checks the sender and text.
2. Open **Room**, enter `fixture-chat`, and tap **Join room**. When the room appears, switch to **Search** and lock the phone before letting the fixture peer send. Unlock the phone, check the unread badge, open the room, and send `room reply`. The fixture peer checks who sent it. The phone should show its own reply once when the server echoes it.
3. Tap **Go offline**, restart Peer Test, and reconnect. In **Chat → Private**, reopen **peer-fixture-chat** and check that both messages reappear.

The chat uses the existing Wi-Fi session. It keeps the latest 100 private messages in app-private storage. The public room is joined by name, and its messages are kept for the current session.

## Find a track through the wishlist

Use the ARM64 Peer Test build above. Stop the other private coordinator before starting the wishlist fixture.

```powershell
$adb = 'android\.tools\sdk\platform-tools\adb.exe'
& $adb reverse tcp:45415 tcp:45415
& $adb forward tcp:45417 tcp:45417
python android\scripts\test-peer-wishlist.py
```

1. Open **Soulseek → Wishlist**. Save `Amber` and `Violet`, then go online with Wi-Fi connected. The fixture announces a five-second interval. Its console shows the two searches alternating, one per interval. The phone shows **View 1 match** for each.
2. Open the `Amber` match and inspect the uploader's folder. This fixture sends search replies only, so the folder view falls back to the matching file. Select **Download Amber.flac**. The app opens Downloads with the track selected for review. Starting a download still requires the destination, fixture checksum, and explicit confirmation.
3. Edit `Amber` to a different term, then remove it. Go offline and restart Peer Test. Only `Violet` should remain. Search requests resume after **Go online**. The fixture console can also verify requests continue while the screen is locked during the active session.

Search terms are saved on the device. Matches are kept in memory for the active session. The fixture checks use USB forwards with Android's Wi-Fi policy enabled.

On the ARM64 OPPO CPH1937, the two terms alternated about 5.3 seconds apart. The coordinator received a request while Android reported the screen asleep and received none during the checked offline interval. Editing and removing one term left only `Violet` after a force-stop. Reconnecting returned its match. Selecting the `Amber` fixture opened Downloads with the checksum field empty and the download button disabled. A folder request waiting for a peer can delay a scheduled wishlist check.

## Before a public login

Soulseek's rules expect third-party clients to implement search, wishlist, downloads, uploads, chat and recognition of privileges. Use the private coordinator for the implemented features until the full-client verification and login version coordination are complete. [Soulseek rules](https://www.slsknet.org/news/node/681)

| Capability | Remaining Android work |
| --- | --- |
| Accounts and connection | Secure credential storage and session lifecycle, plus a client minor version reserved with upstream. Test ordinary peer connectivity and server-brokered fallback on mobile networks when authorized. |
| Downloads | Verify uploader queue behavior and recovery with public peers. Replace the fixture's manual trusted hash with an integrity check suitable for public files. |
| Uploads | Verify direct Wi-Fi listener reachability, brokered fallback and sharing alongside chat and wishlist checks in the same session. |
| Chat | Repeat private and room exchanges during the combined full-client verification. |
| Wishlist | Repeat the scheduled search and manual selection checks during combined full-client verification. |
| Privileges | Repeat the upload-priority check during the combined full-client verification. |

Upstream documents engine support for uploads and priority queues, chat, and wishlist requests. It also documents interoperability gaps for obfuscated connections, locked file listings and firewalled distributed-search children. [Upstream protocol coverage](https://github.com/michel/soulseek-rs/blob/434dcc0155ed87203cafcd1cf7b1a1073f88a63d/docs/protocol-coverage.md)
