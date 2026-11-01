$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDirectory = Join-Path $projectRoot '.tools/native-tests'
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$executable = Join-Path $outputDirectory 'diagnostic-test.exe'
& g++ -std=c++17 -Wall -Wextra ("-I" + (Join-Path $projectRoot 'native-usb/vendor/libusb/libusb')) (Join-Path $projectRoot 'native-usb/tests/diagnostic_test.cpp') -o $executable
if ($LASTEXITCODE -ne 0) { throw 'Native boundary test compilation failed.' }
$output = & $executable
if ($LASTEXITCODE -ne 0) { throw 'Native boundary tests failed.' }
$parsed = $output[0] | ConvertFrom-Json
if ($parsed.operations.Count -lt 20 -or $parsed.audioSubmitted -or $parsed.volumeWriteAttempted -or $parsed.captureOrHidClaimed) { throw 'Invalid ownership JSON report.' }
$controls = $output[1] | ConvertFrom-Json
if (-not $controls.configurationMatrixPassed -or -not $controls.settingsRestored -or -not $controls.lowVolumeControlVerified -or $controls.audioSubmitted) { throw 'Invalid control-test JSON report.' }
$cases = @($controls.operations | Where-Object { $_.operation -match '^alt[12]_rate(44100|48000|88200|96000)_exact$' })
if ($cases.Count -ne 8 -or @($cases.operation | Sort-Object -Unique).Count -ne 8 -or @($cases | Where-Object { $_.result -ne 0 }).Count -ne 0) { throw 'Incomplete simulated configuration matrix.' }
$output[1] | Set-Content -LiteralPath (Join-Path $outputDirectory 'simulated-controls-report.json') -Encoding UTF8
$output[0] | Set-Content -LiteralPath (Join-Path $outputDirectory 'simulated-report.json') -Encoding UTF8
Write-Host $output[-1]
