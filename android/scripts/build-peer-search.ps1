param([switch]$PublicTest, [switch]$NativeOnly)

$ErrorActionPreference = 'Stop'
$android = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $android '.tools'
$ndkBin = Join-Path $tools 'sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin'
$rustTargetDir = Join-Path $tools 'peer-test/bridge-target'
$manifest = Join-Path $android 'peer-search/Cargo.toml'
$jniLibs = Join-Path $tools 'peer-test/jniLibs'
$ndk = Join-Path $tools 'sdk/ndk/27.2.12479018'
$cmake = Join-Path $tools 'sdk/cmake/3.22.1/bin/cmake.exe'
$ninja = Join-Path $tools 'sdk/cmake/3.22.1/bin/ninja.exe'
$flacBuild = Join-Path $tools 'peer-test/flac-arm64'

& python (Join-Path $PSScriptRoot 'vendor-flac.py')
if ($LASTEXITCODE -ne 0) { throw 'Pinned FLAC source verification failed.' }
& $cmake -S (Join-Path $android 'peer-search/flac') -B $flacBuild -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" `
    "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a `
    -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
if ($LASTEXITCODE -ne 0) { throw 'Peer FLAC Android configuration failed.' }
& $cmake --build $flacBuild
if ($LASTEXITCODE -ne 0) { throw 'Peer FLAC Android build failed.' }

$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = Join-Path $ndkBin 'aarch64-linux-android30-clang.cmd'
& cargo rustc --locked --manifest-path $manifest --target-dir $rustTargetDir --release --lib --target aarch64-linux-android -- -C 'link-arg=-Wl,-z,max-page-size=16384'
if ($LASTEXITCODE -ne 0) { throw 'Rust Android ARM64 build failed.' }
$destination = Join-Path $jniLibs 'arm64-v8a'
New-Item -ItemType Directory -Force $destination | Out-Null
Copy-Item -LiteralPath (Join-Path $flacBuild 'liblocalmusic_peer_flac.so') -Destination $destination -Force
Copy-Item -LiteralPath (Join-Path $rustTargetDir 'aarch64-linux-android/release/liblocalmusic_peer_search.so') -Destination $destination -Force

if ($NativeOnly) { return }

& cargo build --locked --manifest-path $manifest --target-dir $rustTargetDir --bin fixture-seeder
if ($LASTEXITCODE -ne 0) { throw 'Private fixture seeder build failed.' }

$env:ANDROID_USER_HOME = Join-Path $tools 'android-user'
Push-Location -LiteralPath $android
try {
    $assembleTask = if ($PublicTest) { ':player:assemblePeerPublicTest' } else { ':player:assemblePeerTest' }
    & (Join-Path $tools 'gradle-8.13/bin/gradle.bat') --offline --no-daemon --console=plain -g (Join-Path $tools 'gradle-cache') $assembleTask
    if ($LASTEXITCODE -ne 0) { throw 'Peer Test APK build failed.' }
} finally { Pop-Location }
