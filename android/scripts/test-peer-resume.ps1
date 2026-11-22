$ErrorActionPreference = 'Stop'
$android = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $android '.tools'
$gradleLib = Join-Path $tools 'gradle-8.13/lib'
$stdlib = Join-Path $gradleLib 'kotlin-stdlib-2.0.21.jar'
$output = Join-Path $tools 'peer-resume-checks.jar'
$transport = Join-Path $android 'player/src/main/java/com/localmusic/player/ControlledPeer.kt'

& java -cp "$gradleLib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath $stdlib -d $output $transport (Join-Path $PSScriptRoot 'PeerResumeChecks.kt')
if ($LASTEXITCODE -ne 0) { throw 'Peer transport check compilation failed.' }
& java -cp "$output;$stdlib" PeerResumeChecksKt (Join-Path $PSScriptRoot 'soulseek-fixture-peer.py')
if ($LASTEXITCODE -ne 0) { throw 'Peer transport checks failed.' }
