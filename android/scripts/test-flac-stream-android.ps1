param([string]$Serial)
$ErrorActionPreference = 'Stop'
$androidRoot = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $androidRoot '.tools'
$cmake = Join-Path $tools 'sdk/cmake/3.22.1/bin/cmake.exe'
$ninja = Join-Path $tools 'sdk/cmake/3.22.1/bin/ninja.exe'
$toolchain = Join-Path $tools 'sdk/ndk/27.2.12479018/build/cmake/android.toolchain.cmake'
& python (Join-Path $PSScriptRoot 'vendor-flac.py')
if ($LASTEXITCODE -ne 0) { throw 'Pinned FLAC verification failed.' }
foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86_64')) {
    $build = Join-Path $tools "flac-stream-android-$abi"
    & $cmake -S (Join-Path $androidRoot 'native-usb/tests/decode-build') -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_TOOLCHAIN_FILE=$toolchain" "-DANDROID_ABI=$abi" -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS=-Werror
    if ($LASTEXITCODE -ne 0) { throw "FLAC Android configuration failed: $abi" }
    & $cmake --build $build --target flac_stream_cli flac_stream_memory_cli flac_stream_worker_test -j 4
    if ($LASTEXITCODE -ne 0) { throw "FLAC Android build failed: $abi" }
    Write-Host "PASS: Android API-30 compile/link $abi (not a runtime test)."
}
if ($Serial) {
    $adb = Join-Path $tools 'sdk/platform-tools/adb.exe'
    $abi = (& $adb -s $Serial shell getprop ro.product.cpu.abi).Trim()
    if ($LASTEXITCODE -ne 0 -or $abi -notin @('arm64-v8a', 'armeabi-v7a', 'x86_64')) { throw 'Unsupported or unavailable Android target.' }
    $binary = Join-Path $tools "flac-stream-android-$abi/flac_stream_cli"
    $remote = '/data/local/tmp/localmusic-flac-stream'
    & $adb -s $Serial shell mkdir -p $remote
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create isolated Android test directory.' }
    try {
        & $adb -s $Serial push $binary "$remote/flac_stream_cli"
        if ($LASTEXITCODE -ne 0) { throw 'Android test binary push failed.' }
        & $adb -s $Serial shell chmod 700 "$remote/flac_stream_cli"
        if ($LASTEXITCODE -ne 0) { throw 'Android test binary permissions failed.' }
        & python (Join-Path $PSScriptRoot 'test-flac-stream.py') $binary --android $Serial
        if ($LASTEXITCODE -ne 0) { throw 'Android FLAC runtime cases failed.' }
        $workerBinary = Join-Path $tools "flac-stream-android-$abi/flac_stream_worker_test"
        & python (Join-Path $PSScriptRoot 'test-flac-stream-worker.py') $workerBinary --android $Serial
        if ($LASTEXITCODE -ne 0) { throw 'Android FLAC worker runtime cases failed.' }
    } finally {
        # Exact, fixed directory containing only this experiment's generated files.
        & $adb -s $Serial shell rm -rf /data/local/tmp/localmusic-flac-stream
    }
}
