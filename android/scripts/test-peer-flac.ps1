$ErrorActionPreference = 'Stop'
$android = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $android '.tools'
$cmake = Join-Path $tools 'sdk/cmake/3.22.1/bin/cmake.exe'
$ninja = Join-Path $tools 'sdk/cmake/3.22.1/bin/ninja.exe'
$build = Join-Path $tools 'peer-test/flac-host'
$cc = (Get-Command gcc).Source.Replace('\','/')
$cxx = (Get-Command g++).Source.Replace('\','/')
& $cmake -S (Join-Path $android 'peer-search/flac') -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_C_COMPILER=$cc" "-DCMAKE_CXX_COMPILER=$cxx" -DCMAKE_BUILD_TYPE=Release
if ($LASTEXITCODE -ne 0) { throw 'Peer FLAC check configuration failed.' }
& $cmake --build $build
if ($LASTEXITCODE -ne 0) { throw 'Peer FLAC check build failed.' }
& python (Join-Path $PSScriptRoot 'test-peer-flac.py') (Join-Path $build 'peer-flac-check.exe')
if ($LASTEXITCODE -ne 0) { throw 'Peer FLAC checks failed.' }
