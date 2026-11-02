$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$gradlePath = Join-Path $projectRoot '.tools/gradle-8.13/bin/gradle.bat'
$cachePath = Join-Path $projectRoot '.tools/gradle-cache'
Push-Location -LiteralPath $projectRoot
try {
    & $gradlePath --offline --no-daemon --console=plain -g $cachePath ':player-core:selfTest'
    if ($LASTEXITCODE -ne 0) { throw 'Player core checks failed.' }
    & python (Join-Path $projectRoot 'scripts/test-player-schema.py')
    if ($LASTEXITCODE -ne 0) { throw 'Player schema checks failed.' }
} finally { Pop-Location }
