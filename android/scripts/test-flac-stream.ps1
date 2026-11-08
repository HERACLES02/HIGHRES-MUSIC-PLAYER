$ErrorActionPreference = 'Stop'
$androidRoot = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $androidRoot '.tools'
$cmake = Join-Path $tools 'sdk/cmake/3.22.1/bin/cmake.exe'
$build = Join-Path $tools 'decode-build'
$ninja = Join-Path $tools 'sdk/cmake/3.22.1/bin/ninja.exe'
$cc = (Get-Command gcc).Source.Replace('\','/')
$cxx = (Get-Command g++).Source.Replace('\','/')
& python (Join-Path $PSScriptRoot 'vendor-flac.py')
if ($LASTEXITCODE -ne 0) { throw 'Pinned FLAC source verification failed.' }
& $cmake -S (Join-Path $androidRoot 'native-usb/tests/decode-build') -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_C_COMPILER=$cc" "-DCMAKE_CXX_COMPILER=$cxx" -DCMAKE_BUILD_TYPE=Release
if ($LASTEXITCODE -ne 0) { throw 'Streaming FLAC test configuration failed.' }
& $cmake --build $build --target flac_stream_cli flac_stream_worker_test -j 4
if ($LASTEXITCODE -ne 0) { throw 'Streaming FLAC test build failed.' }
Push-Location (Split-Path -Parent $androidRoot)
try {
    & python (Join-Path $PSScriptRoot 'test-flac-stream.py') (Join-Path $build 'flac_stream_cli.exe')
    if ($LASTEXITCODE -ne 0) { throw 'Streaming FLAC tests failed.' }
    & python (Join-Path $PSScriptRoot 'test-flac-stream-worker.py') (Join-Path $build 'flac_stream_worker_test.exe')
    if ($LASTEXITCODE -ne 0) { throw 'Streaming FLAC worker tests failed.' }
} finally { Pop-Location }
