$ErrorActionPreference = 'Stop'
$androidRoot = Split-Path -Parent $PSScriptRoot
$output = Join-Path $androidRoot '.tools/pcm-frame-queue-test.exe'
& g++ -std=c++17 -O2 -Wall -Wextra -pthread (Join-Path $androidRoot 'native-usb/tests/pcm_frame_queue_test.cpp') -o $output
if ($LASTEXITCODE -ne 0) { throw 'PCM frame queue test compilation failed.' }
& $output
if ($LASTEXITCODE -ne 0) { throw 'PCM frame queue tests failed.' }
$cli = Join-Path $androidRoot '.tools/pcm-frame-queue-cli.exe'
& g++ -std=c++17 -O2 -Wall -Wextra -pthread (Join-Path $androidRoot 'native-usb/tests/pcm_frame_queue_cli.cpp') -o $cli
if ($LASTEXITCODE -ne 0) { throw 'PCM frame queue pipe test compilation failed.' }
& python (Join-Path $PSScriptRoot 'test-pcm-frame-queue.py') $cli
if ($LASTEXITCODE -ne 0) { throw 'PCM frame queue pipe checks failed.' }
