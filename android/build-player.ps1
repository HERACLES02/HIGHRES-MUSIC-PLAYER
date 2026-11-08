param([switch]$UpdateDependencyVerification, [switch]$OptimizedPreview)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$tools = Join-Path $projectRoot '.tools'
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'local.properties')) -or
    -not (Test-Path -LiteralPath (Join-Path $tools 'debug.keystore'))) {
    throw 'Run android\build.ps1 first to prepare the existing local SDK and diagnostic signing key.'
}
$env:ANDROID_USER_HOME = Join-Path $tools 'android-user'
Push-Location -LiteralPath $projectRoot
try {
    & python (Join-Path $projectRoot 'scripts/verify-native-tools.py')
    if ($LASTEXITCODE -ne 0) { throw 'Native tool verification failed.' }
    & python (Join-Path $projectRoot 'scripts/vendor-taglib.py')
    if ($LASTEXITCODE -ne 0) { throw 'TagLib source verification failed.' }
    & python (Join-Path $projectRoot 'scripts/verify-tag-evidence.py')
    if ($LASTEXITCODE -ne 0) { throw 'Tag-preservation evidence is missing or stale.' }
    $verificationArgs = @('--offline')
    if ($UpdateDependencyVerification) { $verificationArgs = @('--write-verification-metadata', 'sha256') }
    $variantTask = if ($OptimizedPreview) { 'Preview' } else { 'Debug' }
    $variantName = $variantTask.ToLowerInvariant()
    $buildTasks = @(':player-core:selfTest', ':player-data:selfTest', ":player:assemble$variantTask", ":player:lint$variantTask")
    if (-not $OptimizedPreview) { $buildTasks += ':player:assembleDebugAndroidTest' }
    & (Join-Path $tools 'gradle-8.13/bin/gradle.bat') @verificationArgs --no-daemon --console=plain -g (Join-Path $tools 'gradle-cache') @buildTasks
    if ($LASTEXITCODE -ne 0) { throw 'Player build or checks failed.' }
    & python (Join-Path $projectRoot 'scripts/verify-player-data.py')
    if ($LASTEXITCODE -ne 0) { throw 'Frozen Room schemas changed.' }
    $apkName = if ($OptimizedPreview) { 'local-music-0.13.6-optimized-preview.apk' } else { 'local-music-0.13.6-debug.apk' }
    $apk = Join-Path $projectRoot "releases/$apkName"
    Copy-Item -LiteralPath (Join-Path $projectRoot "player/build/outputs/apk/$variantName/player-$variantName.apk") -Destination $apk -Force
    if ($OptimizedPreview) {
        Copy-Item -LiteralPath (Join-Path $projectRoot 'player/build/outputs/mapping/preview/mapping.txt') -Destination ([IO.Path]::ChangeExtension($apk, 'mapping.txt')) -Force
    }
    & (Join-Path $tools 'sdk/build-tools/35.0.0/apksigner.bat') verify --verbose $apk
    if ($LASTEXITCODE -ne 0) { throw 'Player signature verification failed.' }
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$apk.sha256" -Value ($hash + '  ' + (Split-Path -Leaf $apk)) -Encoding ASCII
    Write-Host "Local Music preview: $apk"
    Write-Host "SHA256: $hash"
} finally { Pop-Location }
