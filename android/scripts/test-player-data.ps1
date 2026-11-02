$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    & (Join-Path $projectRoot '.tools/gradle-8.13/bin/gradle.bat') --offline --no-daemon --console=plain -g (Join-Path $projectRoot '.tools/gradle-cache') ':player-data:selfTest' ':player-data:compileDebugKotlinAndroid' ':player-data:lintDebug'
    if ($LASTEXITCODE -ne 0) { throw 'Room persistence checks failed.' }
    & python (Join-Path $projectRoot 'scripts/verify-player-data.py')
    if ($LASTEXITCODE -ne 0) { throw 'Frozen Room schema checks failed.' }
} finally { Pop-Location }
