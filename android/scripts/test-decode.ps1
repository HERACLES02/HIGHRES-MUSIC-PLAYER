$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$cmake = Join-Path $projectRoot '.tools/sdk/cmake/3.22.1/bin/cmake.exe'
$ninja = Join-Path $projectRoot '.tools/sdk/cmake/3.22.1/bin/ninja.exe'
$build = Join-Path $projectRoot '.tools/decode-build'
$cc = (Get-Command gcc).Source.Replace('\','/')
$cxx = (Get-Command g++).Source.Replace('\','/')
& $cmake -S (Join-Path $projectRoot 'native-usb/tests/decode-build') -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_C_COMPILER=$cc" "-DCMAKE_CXX_COMPILER=$cxx" -DCMAKE_BUILD_TYPE=Release
if ($LASTEXITCODE -ne 0) { throw 'Decoder test configuration failed.' }
& $cmake --build $build --target decode_cli -j 4
if ($LASTEXITCODE -ne 0) { throw 'Decoder test compilation failed.' }
& python (Join-Path $projectRoot 'scripts/test-decode.py') (Join-Path $build 'decode_cli.exe')
if ($LASTEXITCODE -ne 0) { throw 'Native decoder comparisons failed.' }
