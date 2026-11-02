$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$output = Join-Path $projectRoot '.tools/direct-state-test-classes'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$source = Join-Path $projectRoot 'app/src/main/java/com/localmusic/diagnostics/DirectPlaybackView.java'
$tests = Join-Path $projectRoot 'app/src/test/java/com/localmusic/diagnostics/DirectPlaybackViewTest.java'
& javac --release 17 -encoding UTF-8 -d $output $source $tests
if ($LASTEXITCODE -ne 0) { throw 'Direct controller-state compilation failed.' }
& java -cp $output com.localmusic.diagnostics.DirectPlaybackViewTest
if ($LASTEXITCODE -ne 0) { throw 'Direct controller-state checks failed.' }
