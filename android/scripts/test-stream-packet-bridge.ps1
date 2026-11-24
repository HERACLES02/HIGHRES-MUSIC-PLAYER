param(
    [string]$ToolsRoot = (Join-Path (Split-Path -Parent $PSScriptRoot) '.tools'),
    [switch]$AndroidCompile,
    [switch]$Adapter,
    [switch]$DescriptorInput,
    [switch]$QuietFixture,
    [string]$AndroidSerial,
    [int]$AdbPort = 5037,
    [switch]$Memory,
    [switch]$RaceRepeats
)
$ErrorActionPreference = 'Stop'
$androidRoot = Split-Path -Parent $PSScriptRoot
$ToolsRoot = (Resolve-Path -LiteralPath $ToolsRoot).Path
$cmake = Join-Path $ToolsRoot 'sdk/cmake/3.22.1/bin/cmake.exe'
$ninja = Join-Path $ToolsRoot 'sdk/cmake/3.22.1/bin/ninja.exe'
$testTarget = if ($Adapter) { 'libusb_packet_adapter_test' } else { 'stream_packet_bridge_test' }
$evidenceFolder = if ($Adapter) { 'adapter-tests' } else { 'bridge-tests' }
if ($DescriptorInput -and -not $Adapter) { throw 'DescriptorInput requires the Adapter check.' }
if ($QuietFixture -and (-not $Adapter -or $DescriptorInput -or $Memory -or $RaceRepeats -or $AndroidSerial)) {
    throw 'QuietFixture requires Adapter; use host tests or AndroidCompile without other runtime switches.'
}
& python (Join-Path $PSScriptRoot 'vendor-libusb.py')
if ($LASTEXITCODE -ne 0) { throw 'Pinned libusb source verification failed.' }
& python (Join-Path $PSScriptRoot 'vendor-flac.py')
if ($LASTEXITCODE -ne 0) { throw 'Pinned FLAC source verification failed.' }
if ($AndroidCompile -or $AndroidSerial) {
    $toolchain = Join-Path $ToolsRoot 'sdk/ndk/27.2.12479018/build/cmake/android.toolchain.cmake'
    foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86_64')) {
        $build = Join-Path $ToolsRoot "bridge-android-$abi"
        & $cmake -S (Join-Path $androidRoot 'native-usb/tests/decode-build') -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_TOOLCHAIN_FILE=$toolchain" "-DANDROID_ABI=$abi" -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS=-Werror
        if ($LASTEXITCODE -ne 0) { throw "Bridge Android configuration failed: $abi" }
        & $cmake --build $build --target $testTarget -j 4
        if ($LASTEXITCODE -ne 0) { throw "Bridge Android compile/link failed: $abi" }
        Write-Host "PASS: bridge API-30 compile/link $abi; no runtime test."
    }
    if ($AndroidSerial) {
        $adb = Join-Path $ToolsRoot 'sdk/platform-tools/adb.exe'
        $abi = (& $adb -P $AdbPort -s $AndroidSerial shell getprop ro.product.cpu.abi).Trim()
        if ($LASTEXITCODE -ne 0 -or $abi -notin @('arm64-v8a', 'armeabi-v7a', 'x86_64')) { throw 'Unsupported or unavailable explicit Android target.' }
        $runtimeFolder = if ($Adapter) { 'adapter-android-runtime' } else { 'bridge-android-runtime' }
        $arguments = @((Join-Path $PSScriptRoot 'test-stream-packet-bridge.py'), (Join-Path $ToolsRoot "bridge-android-$abi/$testTarget"), (Join-Path $ToolsRoot $runtimeFolder), '--android', $AndroidSerial, '--adb', $adb, '--adb-port', $AdbPort)
        if ($Memory) { $arguments += '--memory' }
        if ($RaceRepeats) { $arguments += '--race-repeats' }
        if ($DescriptorInput) { $arguments += '--fd' }
        & python @arguments
        if ($LASTEXITCODE -ne 0) { throw 'Bridge Android runtime verification failed.' }
    }
    return
}
$build = Join-Path $ToolsRoot 'bridge-build'
$cc = (Get-Command gcc).Source.Replace('\','/')
$cxx = (Get-Command g++).Source.Replace('\','/')
& $cmake -S (Join-Path $androidRoot 'native-usb/tests/decode-build') -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_C_COMPILER=$cc" "-DCMAKE_CXX_COMPILER=$cxx" -DCMAKE_BUILD_TYPE=Release
if ($LASTEXITCODE -ne 0) { throw 'Bridge host configuration failed.' }
& $cmake --build $build --target $testTarget -j 4
if ($LASTEXITCODE -ne 0) { throw 'Bridge host build failed.' }
$driver = if ($QuietFixture) { 'test-quiet-flac-fixture.py' } else { 'test-stream-packet-bridge.py' }
if ($QuietFixture) { $evidenceFolder = 'quiet-fixture-tests' }
$arguments = @((Join-Path $PSScriptRoot $driver), (Join-Path $build "$testTarget.exe"), (Join-Path $ToolsRoot $evidenceFolder))
if ($Memory) { $arguments += '--memory' }
if ($RaceRepeats) { $arguments += '--race-repeats' }
if ($DescriptorInput) { $arguments += '--fd' }
& python @arguments
if ($LASTEXITCODE -ne 0) { throw 'Bridge host verification failed.' }
