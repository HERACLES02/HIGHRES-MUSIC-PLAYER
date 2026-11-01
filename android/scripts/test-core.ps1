$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputPath = Join-Path $projectRoot '.tools\core-test-classes'
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'usb-core\src') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
& javac --release 17 -encoding UTF-8 -d $outputPath @sources
if ($LASTEXITCODE -ne 0) { throw 'Java parser compilation failed.' }
& java -cp $outputPath com.localmusic.usb.UsbDescriptorsTest
if ($LASTEXITCODE -ne 0) { throw 'USB parser checks failed.' }
