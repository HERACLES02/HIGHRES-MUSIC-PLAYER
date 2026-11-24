param(
    [string]$ToolsRoot = (Join-Path (Split-Path -Parent $PSScriptRoot) '.tools'),
    [string]$Serial,
    [int]$AdbPort = 5037
)
$ErrorActionPreference = 'Stop'
$androidRoot = Split-Path -Parent $PSScriptRoot
$ToolsRoot = (Resolve-Path -LiteralPath $ToolsRoot).Path
$cmake = Join-Path $ToolsRoot 'sdk/cmake/3.22.1/bin/cmake.exe'
$ninja = Join-Path $ToolsRoot 'sdk/cmake/3.22.1/bin/ninja.exe'
$ndk = Join-Path $ToolsRoot 'sdk/ndk/27.2.12479018'
$toolchain = Join-Path $ndk 'build/cmake/android.toolchain.cmake'
$evidence = Join-Path $ToolsRoot 'usb-backend-lifetime'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
foreach ($vendor in @('vendor-flac.py', 'vendor-libusb.py')) {
    & python (Join-Path $PSScriptRoot $vendor)
    if ($LASTEXITCODE -ne 0) { throw "Pinned source verification failed: $vendor" }
}
foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86_64')) {
    $build = Join-Path $ToolsRoot "usb-backend-lifetime-$abi"
    & $cmake -S (Join-Path $androidRoot 'native-usb') -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_TOOLCHAIN_FILE=$toolchain" "-DANDROID_ABI=$abi" -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS=-Werror -DBUILD_USB_BACKEND_LIFETIME_TEST=ON
    if ($LASTEXITCODE -ne 0) { throw "Real backend configuration failed: $abi" }
    & $cmake --build $build --target usb_backend_lifetime_test -j 4
    if ($LASTEXITCODE -ne 0) { throw "Real backend compile/link failed: $abi" }
    Write-Host "PASS: actual backend and adapter API30 compile/link $abi; not a runtime test."
}
$arguments = @((Join-Path $PSScriptRoot 'test-usb-backend-lifetime.py'), $ToolsRoot)
if ($Serial) { $arguments += @('--serial', $Serial, '--adb-port', $AdbPort) }
& python @arguments
if ($LASTEXITCODE -ne 0) { throw 'Real backend lifetime verification failed.' }
