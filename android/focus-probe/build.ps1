$ErrorActionPreference = 'Stop'
$androidRoot = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $androidRoot '.tools'
$sdk = Join-Path $tools 'sdk'
$buildTools = Join-Path $sdk 'build-tools/35.0.0'
$platform = Join-Path $sdk 'platforms/android-36/android.jar'
$key = Join-Path $tools 'debug.keystore'
$work = Join-Path $tools 'focus-probe-build'
$classes = Join-Path $work 'classes'
$dex = Join-Path $work 'dex'
if (-not (Test-Path -LiteralPath $key)) { throw 'Existing debug key missing; no replacement will be generated.' }
New-Item -ItemType Directory -Force -Path $classes,$dex | Out-Null
& javac --release 8 -encoding UTF-8 -classpath $platform -d $classes (Join-Path $PSScriptRoot 'src/com/localmusic/focusprobe/MainActivity.java')
if ($LASTEXITCODE -ne 0) { throw 'Focus helper Java compilation failed.' }
$compiled = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName)
& (Join-Path $buildTools 'd8.bat') --lib $platform --min-api 30 --output $dex @compiled
if ($LASTEXITCODE -ne 0) { throw 'Focus helper dex compilation failed.' }
$unsigned = Join-Path $work 'unsigned.apk'
& (Join-Path $buildTools 'aapt2.exe') link -I $platform --manifest (Join-Path $PSScriptRoot 'AndroidManifest.xml') -o $unsigned
if ($LASTEXITCODE -ne 0) { throw 'Focus helper packaging failed.' }
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
try {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $dex 'classes.dex'), 'classes.dex', [IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally { $archive.Dispose() }
$aligned = Join-Path $work 'aligned.apk'
& (Join-Path $buildTools 'zipalign.exe') -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'Focus helper alignment failed.' }
$apk = Join-Path $androidRoot 'releases/usb-focus-test-1.0.apk'
& (Join-Path $buildTools 'apksigner.bat') sign --ks $key --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $apk $aligned
if ($LASTEXITCODE -ne 0) { throw 'Focus helper signing failed.' }
& (Join-Path $buildTools 'apksigner.bat') verify --verbose $apk
if ($LASTEXITCODE -ne 0) { throw 'Focus helper signature check failed.' }
$badging = & (Join-Path $buildTools 'aapt2.exe') dump badging $apk
if ($LASTEXITCODE -ne 0 -or ($badging -join "`n") -notmatch "name='com.localmusic.focusprobe'") { throw 'Focus helper identity check failed.' }
if (($badging -join "`n") -match 'uses-permission') { throw 'Unexpected permission in silent focus helper.' }
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath "$apk.sha256" -Value "$hash  usb-focus-test-1.0.apk" -Encoding ASCII
Write-Output "Focus test APK: $apk"
Write-Output "SHA256: $hash"
