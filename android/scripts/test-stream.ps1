$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$output = Join-Path $projectRoot '.tools/stream-test.exe'
& g++ -std=c++17 -O2 -Wall -Wextra ("-I"+(Join-Path $projectRoot 'native-usb/vendor/libusb/libusb')) (Join-Path $projectRoot 'native-usb/tests/stream_test.cpp') -o $output
if ($LASTEXITCODE -ne 0) { throw 'Native streaming test compilation failed.' }
& $output
if ($LASTEXITCODE -ne 0) { throw 'Native streaming boundary tests failed.' }
