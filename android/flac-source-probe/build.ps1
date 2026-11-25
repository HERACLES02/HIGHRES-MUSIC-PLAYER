$ErrorActionPreference = 'Stop'
$androidRoot = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $androidRoot '.tools'
$sdk = Join-Path $tools 'sdk'
$buildTools = Join-Path $sdk 'build-tools/35.0.0'
$platform = Join-Path $sdk 'platforms/android-36/android.jar'
$key = Join-Path $tools 'debug.keystore'
$work = Join-Path $tools 'flac-source-probe-build'
$classes = Join-Path $work 'classes'
$dex = Join-Path $work 'dex'
$assets = Join-Path $work 'assets'
if (-not (Test-Path -LiteralPath $key)) { throw 'Existing debug key missing.' }
New-Item -ItemType Directory -Force -Path $classes,$dex,$assets | Out-Null
& python (Join-Path $androidRoot 'scripts/vendor-flac.py')
if ($LASTEXITCODE -ne 0) { throw 'Pinned FLAC verification failed.' }
& python (Join-Path $PSScriptRoot 'make-fixture.py') $assets
if ($LASTEXITCODE -ne 0) { throw 'Generated fixture reference comparison failed.' }
$cmake = Join-Path $sdk 'cmake/3.22.1/bin/cmake.exe'
$ninja = Join-Path $sdk 'cmake/3.22.1/bin/ninja.exe'
$toolchain = Join-Path $sdk 'ndk/27.2.12479018/build/cmake/android.toolchain.cmake'
foreach ($abi in @('arm64-v8a','armeabi-v7a','x86_64')) {
    $native = Join-Path $tools "flac-stream-android-$abi"
    & $cmake -S (Join-Path $androidRoot 'native-usb/tests/decode-build') -B $native -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_TOOLCHAIN_FILE=$toolchain" "-DANDROID_ABI=$abi" -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS=-Werror
    if ($LASTEXITCODE -ne 0) { throw "Probe native configure failed: $abi" }
    & $cmake --build $native --target flac_source_probe flac_source_owner_test -j 4
    if ($LASTEXITCODE -ne 0) { throw "Probe native build failed: $abi" }
}
$policyChecks = Join-Path $work 'policy-checks'
New-Item -ItemType Directory -Force -Path $policyChecks | Out-Null
& javac --release 8 -encoding UTF-8 -d $policyChecks (Join-Path $PSScriptRoot 'src/com/localmusic/flacsourceprobe/LifecyclePolicy.java') (Join-Path $PSScriptRoot 'test/com/localmusic/flacsourceprobe/LifecycleChecks.java')
if ($LASTEXITCODE -ne 0) { throw 'Lifecycle policy test compilation failed.' }
& java -cp $policyChecks com.localmusic.flacsourceprobe.LifecycleChecks
if ($LASTEXITCODE -ne 0) { throw 'Lifecycle policy checks failed.' }
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName)
& javac --release 8 -encoding UTF-8 -classpath $platform -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'File probe Java compilation failed.' }
$compiled = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName)
& (Join-Path $buildTools 'd8.bat') --lib $platform --min-api 30 --output $dex @compiled
if ($LASTEXITCODE -ne 0) { throw 'File probe DEX compilation failed.' }
$unsigned = Join-Path $work 'unsigned.apk'
& (Join-Path $buildTools 'aapt2.exe') link -I $platform --manifest (Join-Path $PSScriptRoot 'AndroidManifest.xml') -o $unsigned
if ($LASTEXITCODE -ne 0) { throw 'File probe packaging failed.' }
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::Open($unsigned,[IO.Compression.ZipArchiveMode]::Update)
try {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,(Join-Path $dex 'classes.dex'),'classes.dex',[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    foreach ($name in @('24-96000.flac','24-96000.pcm','16-44100.flac','16-44100.pcm','long-24-96000.flac','long.pattern','lifecycle-fixtures.json')) {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,(Join-Path $assets $name),"assets/$name",[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
    foreach ($abi in @('arm64-v8a','armeabi-v7a','x86_64')) {
        $library = Join-Path $tools "flac-stream-android-$abi/libflac_source_probe.so"
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$library,"lib/$abi/libflac_source_probe.so",[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally {$archive.Dispose()}
$aligned = Join-Path $work 'aligned.apk'
& (Join-Path $buildTools 'zipalign.exe') -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'File probe alignment failed.' }
$apk = Join-Path $work 'flac-source-probe.apk'
& (Join-Path $buildTools 'apksigner.bat') sign --ks $key --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $apk $aligned
if ($LASTEXITCODE -ne 0) { throw 'File probe signing failed.' }
& (Join-Path $buildTools 'apksigner.bat') verify --verbose $apk
if ($LASTEXITCODE -ne 0) { throw 'File probe signature check failed.' }
$badging = & (Join-Path $buildTools 'aapt2.exe') dump badging $apk
if ($LASTEXITCODE -ne 0 -or ($badging -join "`n") -notmatch "name='com.localmusic.flacsourceprobe'") { throw 'Wrong probe package.' }
if (($badging -join "`n") -match 'uses-permission') { throw 'Probe must request no broad permissions.' }
Write-Host "Probe APK: $apk"
Get-FileHash -LiteralPath $apk -Algorithm SHA256
