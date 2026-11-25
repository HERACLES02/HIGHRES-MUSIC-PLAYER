param([Parameter(Mandatory)][string]$Label, [int]$Runs = 10, [string]$Serial, [string]$Package = 'com.localmusic.player')
# Measures cold start of the installed player and appends the medians to player/PHONE_RESULTS_BASELINE_PROFILE.md.
# Run once with the old APK (-Label before) and once after installing the new one (-Label after), on the same phone.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $projectRoot '.tools/sdk/platform-tools/adb.exe'
$adbArgs = if ($Serial) { @('-s', $Serial) } else { @() }

function Get-Median([double[]]$values) {
    $sorted = $values | Sort-Object
    if ($sorted.Count % 2) { $sorted[[int][math]::Floor($sorted.Count / 2)] }
    else { ($sorted[$sorted.Count / 2 - 1] + $sorted[$sorted.Count / 2]) / 2 }
}

$version = (& $adb @adbArgs shell dumpsys package $Package | Select-String 'versionName=' | Select-Object -First 1).ToString().Trim()
if (-not $version) { throw "$Package is not installed on the connected device." }
# Same compile state for before and after: write the APK's shipped profile, then run the idle-time compile job on it.
# The idle job, not `cmd package compile`, which some phones (ColorOS 11) refuse from adb.
& $adb @adbArgs shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE "$Package/androidx.profileinstaller.ProfileInstallReceiver" | Out-Null
Start-Sleep -Seconds 3
$dexopt = & $adb @adbArgs shell cmd package bg-dexopt-job $Package
if ($LASTEXITCODE -ne 0 -or -not (($dexopt -join "`n") -match 'Success')) { throw "Compiling the app failed: $dexopt" }

$initial = @(); $full = @()
for ($i = 1; $i -le $Runs; $i++) {
    & $adb @adbArgs logcat -c
    $start = & $adb @adbArgs shell am start -W -S -n "$Package/.MainActivity"
    $total = ($start | Select-String 'TotalTime:\s*(\d+)').Matches | ForEach-Object { [double]$_.Groups[1].Value }
    Start-Sleep -Seconds 4
    # reportFullyDrawn (ReportDrawnWhen) fires once the library and playback session are ready.
    $drawn = & $adb @adbArgs logcat -d -s ActivityTaskManager:I | Select-String "Fully drawn $Package.*\+(?:(\d+)s)?(\d+)ms" | Select-Object -Last 1
    $fullMs = if ($drawn) { [double]$drawn.Matches[0].Groups[1].Value * 1000 + [double]$drawn.Matches[0].Groups[2].Value } else { $null }
    Write-Host "Run $i`: initial display ${total} ms, fully drawn $(if ($null -ne $fullMs) { "$fullMs ms" } else { 'not reported' })"
    if ($total) { $initial += $total }
    if ($null -ne $fullMs) { $full += $fullMs }
}
& $adb @adbArgs shell am force-stop $Package
if (-not $initial) { throw 'No start times were reported.' }

$device = (& $adb @adbArgs shell getprop ro.product.model).Trim()
$medianInitial = Get-Median $initial
$medianFull = if ($full) { Get-Median $full } else { 'n/a' }
$results = Join-Path $projectRoot 'player/PHONE_RESULTS_BASELINE_PROFILE.md'
if (-not (Test-Path -LiteralPath $results)) {
    Set-Content -LiteralPath $results -Encoding UTF8 -Value @(
        '# Baseline profile cold-start results', '',
        'Recorded by `android/scripts/measure-cold-start.ps1` (median of cold starts after the background compile job).', '',
        '| Label | Date | Device | Version | Runs | Initial display (ms) | Fully drawn (ms) |',
        '|---|---|---|---|---|---|---|')
}
Add-Content -LiteralPath $results -Encoding UTF8 -Value "| $Label | $(Get-Date -Format 'yyyy-MM-dd') | $device | $($version -replace 'versionName=', '') | $($initial.Count) | $medianInitial | $medianFull |"
Write-Host "Median initial display: $medianInitial ms; median fully drawn: $medianFull ms. Saved to $results"
