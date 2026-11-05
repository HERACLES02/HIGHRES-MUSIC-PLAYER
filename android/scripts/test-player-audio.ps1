param([string]$EmulatorSerial = '127.0.0.1:5561', [int]$AdbServerPort = 5038, [string]$ArtworkFixtureUri, [string]$SeekFixtureUri, [string]$IndexFixtureUri)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $projectRoot '.tools'
$adb = Join-Path $tools 'sdk/platform-tools/adb.exe'
$env:ANDROID_USER_HOME = Join-Path $tools 'android-user'
$deviceArgs = @('-P', "$AdbServerPort", '-s', $EmulatorSerial)
# Instrumentation stops the target app; restrict this runner to a virtual device.
$virtual = & $adb @deviceArgs shell getprop ro.kernel.qemu
if ($LASTEXITCODE -ne 0 -or "$virtual".Trim() -ne '1') { throw 'Select a running Android emulator, not a personal phone.' }
Push-Location -LiteralPath $projectRoot
try {
    & (Join-Path $tools 'gradle-8.13/bin/gradle.bat') --offline --no-daemon --console=plain -g (Join-Path $tools 'gradle-cache') ':player:assembleDebug' ':player:assembleDebugAndroidTest'
    if ($LASTEXITCODE -ne 0) { throw 'Audio regression test APK build failed.' }
    & $adb @deviceArgs install -r (Join-Path $projectRoot 'player/build/outputs/apk/debug/player-debug.apk')
    if ($LASTEXITCODE -ne 0) { throw 'Player install failed.' }
    & $adb @deviceArgs install -r (Join-Path $projectRoot 'player/build/outputs/apk/androidTest/debug/player-debug-androidTest.apk')
    if ($LASTEXITCODE -ne 0) { throw 'Audio regression test install failed.' }
    $result = & $adb @deviceArgs shell am instrument -r -w 'com.localmusic.player.test/com.localmusic.player.AudioProcessorChecks'
    $result | Write-Output
    if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'INSTRUMENTATION_RESULT: checksPassed=8' -or
        ($result -join "`n") -notmatch 'INSTRUMENTATION_CODE: -1') { throw 'Audio processor regression checks failed.' }
    $artArgs = @('shell', 'am', 'instrument', '-r', '-w', '-e', 'suite', 'artwork')
    $expected = 4
    if ($ArtworkFixtureUri) { $artArgs += @('-e', 'fixtureUri', $ArtworkFixtureUri); $expected = 5 }
    $artArgs += 'com.localmusic.player.test/com.localmusic.player.AudioProcessorChecks'
    $result = & $adb @deviceArgs @artArgs
    $result | Write-Output
    if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch "INSTRUMENTATION_RESULT: checksPassed=$expected" -or
        ($result -join "`n") -notmatch 'INSTRUMENTATION_CODE: -1') { throw 'Artwork regression checks failed.' }
    if ($SeekFixtureUri) {
        $result = & $adb @deviceArgs shell am instrument -r -w -e suite seek -e seekBuffer production -e fixtureUri $SeekFixtureUri 'com.localmusic.player.test/com.localmusic.player.AudioProcessorChecks'
        $result | Write-Output
        if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'INSTRUMENTATION_RESULT: checksPassed=1' -or
            ($result -join "`n") -notmatch 'INSTRUMENTATION_CODE: -1') { throw 'Native seek regression checks failed.' }
    }
    # IndexFixtureUri must identify disposable generated audio: its cache, provider copy
    # and file-change checks deliberately mutate only test-owned copies.
    if ($IndexFixtureUri) {
        foreach ($suite in @('flac-index', 'flac-queue', 'flac-content')) {
            $result = & $adb @deviceArgs shell am instrument -r -w -e suite $suite -e fixtureUri $IndexFixtureUri 'com.localmusic.player.test/com.localmusic.player.AudioProcessorChecks'
            $result | Write-Output
            if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'INSTRUMENTATION_RESULT: checksPassed=1' -or
                ($result -join "`n") -notmatch 'INSTRUMENTATION_CODE: -1') { throw "FLAC $suite regression failed." }
        }
    }
} finally { Pop-Location }
