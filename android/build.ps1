param(
    [switch]$Bootstrap,
    [switch]$AcceptAndroidSdkLicense,
    [string]$FfmpegPath,
    [switch]$UpdateDependencyVerification
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$projectRoot = $PSScriptRoot
$toolsPath = Join-Path $projectRoot '.tools'
$downloadsPath = Join-Path $toolsPath 'downloads'
$sdkPath = Join-Path $toolsPath 'sdk'
$gradlePath = Join-Path $toolsPath 'gradle-8.13\bin\gradle.bat'
$sdkManagerPath = Join-Path $toolsPath 'command-line-tools\cmdline-tools\bin\sdkmanager.bat'
New-Item -ItemType Directory -Force -Path $downloadsPath | Out-Null
$env:ANDROID_USER_HOME = Join-Path $toolsPath 'android-user'
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null

function Get-VerifiedArchive([string]$uri, [string]$name, [string]$expectedHash) {
    $archivePath = Join-Path $downloadsPath $name
    if (-not (Test-Path -LiteralPath $archivePath)) {
        Write-Host "Downloading $name from its official distributor..."
        $partialPath = "$archivePath.download"
        Invoke-WebRequest -UseBasicParsing -Uri $uri -OutFile $partialPath -TimeoutSec 600
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $partialPath).Hash
        if ($hash -ne $expectedHash) { throw "Checksum mismatch for $name. The archive was not executed or extracted." }
        Move-Item -LiteralPath $partialPath -Destination $archivePath -Force
    }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash -ne $expectedHash) {
        throw "Cached archive checksum mismatch for $name. Inspect it before retrying."
    }
    return $archivePath
}

if (-not (Test-Path -LiteralPath $gradlePath)) {
    if (-not $Bootstrap) { throw 'Build tools are missing. Read android/README.md before running with -Bootstrap.' }
    $gradleArchive = Get-VerifiedArchive 'https://services.gradle.org/distributions/gradle-8.13-bin.zip' 'gradle-8.13-bin.zip' '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78'
    Write-Host 'Extracting Gradle...'
    Expand-Archive -LiteralPath $gradleArchive -DestinationPath $toolsPath -Force
}

$platformPath = Join-Path $sdkPath 'platforms\android-36\android.jar'
$buildToolsPath = Join-Path $sdkPath 'build-tools\35.0.0\aapt2.exe'
if (-not (Test-Path -LiteralPath $platformPath) -or -not (Test-Path -LiteralPath $buildToolsPath)) {
    if (-not $Bootstrap -or -not $AcceptAndroidSdkLicense) {
        throw 'Android SDK installation requires explicit agreement to https://developer.android.com/studio/terms . After reviewing, run with -Bootstrap -AcceptAndroidSdkLicense.'
    }
    if (-not (Test-Path -LiteralPath $sdkManagerPath)) {
        $sdkArchive = Get-VerifiedArchive 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' 'commandlinetools-win-13114758_latest.zip' '98b565cb657b012dae6794cefc0f66ae1efb4690c699b78a614b4a6a3505b003'
        Write-Host 'Extracting Android command-line tools...'
        Expand-Archive -LiteralPath $sdkArchive -DestinationPath (Join-Path $toolsPath 'command-line-tools') -Force
    }
    Write-Host 'Installing the selected SDK packages. The explicit license switch permits acceptance of their SDK terms.'
    @('y', 'y', 'y') | & $sdkManagerPath "--sdk_root=$sdkPath" 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK setup failed.' }
}

# Paths and dependency caches stay inside this checkout; no system PATH edits.
if (-not (Test-Path -LiteralPath (Join-Path $sdkPath 'ndk/27.2.12479018/source.properties')) -or
    -not (Test-Path -LiteralPath (Join-Path $sdkPath 'cmake/3.22.1/source.properties'))) {
    if (-not $Bootstrap -or -not $AcceptAndroidSdkLicense) {
        throw 'Native tools missing. Run build.ps1 -Bootstrap -AcceptAndroidSdkLicense to install pinned NDK 27.2.12479018 and CMake 3.22.1 under the already authorized SDK terms.'
    }
    @('y', 'y') | & $sdkManagerPath "--sdk_root=$sdkPath" 'ndk;27.2.12479018' 'cmake;3.22.1'
    if ($LASTEXITCODE -ne 0) { throw 'Native Android tool installation failed.' }
}
$sdkProperty = $sdkPath.Replace('\', '/')
Set-Content -LiteralPath (Join-Path $projectRoot 'local.properties') -Value "sdk.dir=$sdkProperty" -Encoding ASCII
$debugKeyPath = Join-Path $toolsPath 'debug.keystore'
if (-not (Test-Path -LiteralPath $debugKeyPath)) {
    # Java prints its properties to stderr even on success. Windows PowerShell
    # wraps redirected stderr as error records, so do not treat that as failure.
    $ErrorActionPreference = 'Continue'
    try { $javaSettings = (& java -XshowSettings:properties -version 2>&1 | Out-String) }
    finally { $ErrorActionPreference = 'Stop' }
    $javaHomeMatch = [regex]::Match($javaSettings, 'java\.home\s*=\s*([^\r\n]+)')
    if (-not $javaHomeMatch.Success) { throw 'Could not locate the Java keytool for APK signing.' }
    $keyToolPath = Join-Path $javaHomeMatch.Groups[1].Value.Trim() 'bin\keytool.exe'
    Write-Host 'Generating a project-local Android debug signing key...'
    & $keyToolPath -genkeypair -keystore $debugKeyPath -storepass android -keypass android -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000 -storetype JKS -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'Debug signing-key generation failed.' }
}
Push-Location -LiteralPath $projectRoot
try {
    & python (Join-Path $projectRoot 'scripts/vendor-libusb.py')
    if ($LASTEXITCODE -ne 0) { throw 'Native source verification failed.' }
    & python (Join-Path $projectRoot 'scripts/vendor-flac.py')
    if ($LASTEXITCODE -ne 0) { throw 'FLAC source verification failed.' }
    & python (Join-Path $projectRoot 'scripts/verify-native-tools.py')
    if ($LASTEXITCODE -ne 0) { throw 'Native tool verification failed.' }
    $fixtureArgs = @((Join-Path $projectRoot 'scripts/fixtures.py'))
    if ($FfmpegPath) { $fixtureArgs += @('--ffmpeg', $FfmpegPath) }
    & python @fixtureArgs
    if ($LASTEXITCODE -ne 0) { throw 'Fixture generation/verification failed.' }
    & python (Join-Path $projectRoot 'scripts/test_fixtures.py')
    if ($LASTEXITCODE -ne 0) { throw 'Fixture regression tests failed.' }
    & python (Join-Path $projectRoot 'scripts/channel_fixtures.py')
    if ($LASTEXITCODE -ne 0) { throw 'Channel fixture verification failed.' }
    & python (Join-Path $projectRoot 'scripts/test_channel_fixtures.py')
    if ($LASTEXITCODE -ne 0) { throw 'Channel pattern tests failed.' }
    $verificationArgs = @()
    if ($UpdateDependencyVerification) { $verificationArgs = @('--write-verification-metadata', 'sha256') }
    & $gradlePath --no-daemon --console=plain @verificationArgs -g (Join-Path $toolsPath 'gradle-cache') ':usb-core:selfTest' ':app:assembleDebug' ':app:lintDebug'
    if ($LASTEXITCODE -ne 0) { throw 'Android build or checks failed. See the output above.' }
    $releasePath = Join-Path $projectRoot 'releases'
    New-Item -ItemType Directory -Force -Path $releasePath | Out-Null
    $apkPath = Join-Path $releasePath 'music-usb-probe-0.6.0.apk'
    Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk') -Destination $apkPath -Force
    & (Join-Path $sdkPath 'build-tools/35.0.0/apksigner.bat') verify --verbose $apkPath
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $apkHash = Get-FileHash -Algorithm SHA256 -LiteralPath $apkPath
    Set-Content -LiteralPath "$apkPath.sha256" -Value ($apkHash.Hash.ToLowerInvariant() + '  ' + (Split-Path -Leaf $apkPath)) -Encoding ASCII
    $apkHash
    Write-Host "Installable diagnostic APK: $apkPath"
} finally {
    Pop-Location
}
