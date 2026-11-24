param([string]$Serial, [switch]$InstallOnly)
# Generates player/src/main/baseline-prof.txt on a connected emulator or phone.
# Installs a separate "profilegen" copy of the player (com.localmusic.player.profilegen), never the real app.
# The device needs Android 13 or newer. Run with -InstallOnly first, then pick a music folder and play one song in the
# profilegen app ("Local Music" with a -profilegen version) before running without it.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $projectRoot '.tools'
$adb = Join-Path $tools 'sdk/platform-tools/adb.exe'
$adbArgs = if ($Serial) { @('-s', $Serial) } else { @() }
$env:ANDROID_USER_HOME = Join-Path $tools 'android-user'
Push-Location -LiteralPath $projectRoot
try {
    & (Join-Path $tools 'gradle-8.13/bin/gradle.bat') --offline --no-daemon --console=plain -g (Join-Path $tools 'gradle-cache') `
        ':player:assembleProfileGen' ':baselineprofile:assembleProfileGen'
    if ($LASTEXITCODE -ne 0) { throw 'Building the profilegen APKs failed.' }
    & $adb @adbArgs install -r 'player/build/outputs/apk/profileGen/player-profileGen.apk'
    if ($LASTEXITCODE -ne 0) { throw 'Installing the profilegen player failed.' }
    & $adb @adbArgs install -r 'baselineprofile/build/outputs/apk/profileGen/baselineprofile-profileGen.apk'
    if ($LASTEXITCODE -ne 0) { throw 'Installing the generator failed.' }
    if ($InstallOnly) { Write-Host 'Installed. Open the profilegen app, pick a music folder, play one song, then run again without -InstallOnly.'; return }
    # Installed with adb rather than Gradle's connected task, which uninstalls the app (and its picked folder) afterwards.
    $output = & $adb @adbArgs shell am instrument -w -e androidx.benchmark.enabledRules BaselineProfile `
        -e class com.localmusic.baselineprofile.BaselineProfileGenerator `
        com.localmusic.baselineprofile/androidx.test.runner.AndroidJUnitRunner
    $output | Write-Host
    if ($LASTEXITCODE -ne 0 -or -not (($output -join "`n") -match 'OK \(1 test\)')) { throw 'Profile generation failed; see the output above.' }
    $remoteDir = '/sdcard/Android/media/com.localmusic.baselineprofile'
    $remote = & $adb @adbArgs shell ls -t "$remoteDir" | Where-Object { $_ -match 'baseline-prof.*\.txt' } | Select-Object -First 1
    if (-not $remote) { throw "No baseline-prof file found in $remoteDir." }
    $pulled = Join-Path $projectRoot 'baselineprofile/build/baseline-prof.txt'
    & $adb @adbArgs pull "$remoteDir/$($remote.Trim())" $pulled
    if ($LASTEXITCODE -ne 0) { throw 'Pulling the profile failed.' }
    $target = Join-Path $projectRoot 'player/src/main/baseline-prof.txt'
    Copy-Item -LiteralPath $pulled -Destination $target -Force
    $rules = (Get-Content -LiteralPath $target | Measure-Object -Line).Lines
    $appRules = (Select-String -LiteralPath $target -Pattern 'Lcom/localmusic/' | Measure-Object).Count
    Write-Host "Baseline profile: $target ($rules rules, $appRules for com.localmusic)"
} finally { Pop-Location }
