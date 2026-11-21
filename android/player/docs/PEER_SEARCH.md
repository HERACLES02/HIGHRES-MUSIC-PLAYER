# Private Soulseek search in Local Music Peer Test

This increment for [issue #16](https://github.com/HERACLES02/HIGHRES-MUSIC-PLAYER/issues/16) builds on `5b87f2c`. The Android app owns the client session, search, folder request and track download. The Windows processes serve as the test coordinator and uploader. Every test account connects to the loopback coordinator on port 45415.

## Engine choice

The bridge pins [soulseek-rs 20.0.0 at `434dcc0`](https://github.com/michel/soulseek-rs/tree/434dcc0155ed87203cafcd1cf7b1a1073f88a63d). It builds with Rust and the existing Android NDK. A small JNI bridge hands file metadata to Kotlin, which owns the screen, foreground transfer service, verification, destination handoff and Library scan. The APK carries the engine’s MIT copyright and permission notice. A 32-bit ARM test build of upstream fails on Android’s `u32` file-descriptor limit being compared with a `u64` constant. This increment packages the engine for ARM64. A narrow upstream change to normalize the integer widths would let us retry the 32-bit build. [Upstream connection code](https://github.com/michel/soulseek-rs/blob/434dcc0155ed87203cafcd1cf7b1a1073f88a63d/soulseek-rs-lib/src/client/connection.rs)

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

1. Open **Local Music Peer Test**. Paste the hash for `Amber.flac` and choose a fresh disposable folder under **Internal storage → Music**.
2. Under **Private Soulseek search**, search `Amber`. Confirm the search result gives the uploader and folder. Tap **Inspect peer-fixture-a’s folder** and confirm `Violet.flac` also appears.
3. Tap **Download Amber.flac** and wait for **Ready in Library**. Confirm the destination contains one `Local Music peer fixture.flac` with the same SHA-256 as `Amber.flac`.
4. Stop both fixture processes with Ctrl+C and remove the forwards. With the phone offline, open the Library and play the imported track by its embedded title.

```powershell
& $adb reverse --remove tcp:45415
& $adb reverse --remove tcp:45416
& $adb forward --remove tcp:45417
```

The SHA-256 field supplies an independent check for the generated fixture. The Android service keeps the transfer in its private files until both size and SHA-256 match. The existing publisher copies through a `.part` file, checks the copy and renames it before requesting the existing Library scan. Each selection uses private staging keyed by uploader, remote path, size and fixture hash.

## Controlled Android result

On the ARM64 OPPO CPH1937, `Amber` returned separate uploader and folder groups for `peer-fixture-a` and `peer-fixture-b`. Opening the first uploader’s folder also showed `Violet.flac`. Selecting `Amber.flac` produced one 152,312-byte file identical to the original fixture. With the fixture shut down, USB forwards removed, airplane mode on and Wi-Fi off, the track appeared once in the Library as **Local Music Peer Tone**. Android playback advanced from 1,022 to 6,639 ms. The evidence is from the phone’s media session and file comparison. Listening acceptance is still pending.

The ARM64 Rust build, Peer Test APK assembly and signature, Peer Test lint, player-core and Room checks passed. Public-network verification and interruption testing for the Rust transfer path are separate from this result.

## Before a public login

Soulseek's rules expect third-party clients to implement search, wishlist, downloads, uploads, chat and recognition of privileges. This increment exercises the search and download path on the private coordinator. [Soulseek rules](https://www.slsknet.org/news/node/681)

| Capability | Remaining Android work |
| --- | --- |
| Accounts and connection | Secure credential storage and session lifecycle, plus a client minor version reserved with upstream. Test ordinary peer connectivity and server-brokered fallback on mobile networks when authorized. |
| Downloads | Verify queue state, cancellation, Wi-Fi policy and interrupted transfer behavior with the Rust engine. Replace the fixture's manual trusted hash and fixed destination name with a public-file integrity and conflict policy. |
| Uploads | Expose explicitly approved share folders through Android's storage permissions. Keep sharing initially off. Test serving files, the upload queue, and listener reachability. |
| Chat | Expose room and private messages, including reception and acknowledgement across reconnects. |
| Wishlist | Store searches and schedule them using the server-provided interval. |
| Privileges | Verify server-listed priority is applied to the phone's actual upload queue. |

Upstream documents engine support for uploads and priority queues, chat, and wishlist requests. It also documents interoperability gaps for obfuscated connections, locked file listings and firewalled distributed-search children. [Upstream protocol coverage](https://github.com/michel/soulseek-rs/blob/434dcc0155ed87203cafcd1cf7b1a1073f88a63d/docs/protocol-coverage.md)
