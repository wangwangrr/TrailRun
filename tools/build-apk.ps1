# ---------------------------------------------------------------------------
# TrailRun build runner  (ASCII only)
#
# Runs the actual Gradle build. Assumes bootstrap-android.ps1 already finished:
#   Android Studio (portable) : D:\app\Android\Android Studio
#   Android SDK               : D:\app\Android\Sdk
#   Gradle 8.7 (mirror)       : <root>\.toolchain\gradle-8.7
#
# Also generates the Gradle wrapper inside the project so that plain
# `gradlew.bat assembleDebug` works afterwards, on this machine and anywhere else.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1
# ---------------------------------------------------------------------------

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Root       = Split-Path -Parent $PSScriptRoot
$AppDir     = 'D:\app\Android'
$StudioDir  = Join-Path $AppDir 'Android Studio'
$JbrDir     = Join-Path $StudioDir 'jbr'
$javaExe    = Join-Path $JbrDir 'bin\java.exe'
$SdkDir     = Join-Path $AppDir 'Sdk'
$Toolchain  = Join-Path $Root '.toolchain'
$GradleHome = Join-Path $Root '.gradle-home'
$GradleZip  = Join-Path $Toolchain 'gradle-8.7-bin.zip'
$GradleDir  = Join-Path $Toolchain 'gradle-8.7'
$BuildLog   = Join-Path $Toolchain 'build.log'

function Step($m) { Write-Host ""; Write-Host "===== $m =====" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "  [OK] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  [!!] $m" -ForegroundColor Yellow }

$previousEap = $ErrorActionPreference

# ---------------------------------------------------------------------------
Step '1/4 prepare Gradle 8.7'
# ---------------------------------------------------------------------------
$gradleBat = Join-Path $GradleDir 'bin\gradle.bat'
if (Test-Path $gradleBat) {
    Ok 'Gradle already unpacked'
} else {
    if (-not (Test-Path $GradleZip)) { throw "missing $GradleZip" }
    Ok ("unpacking {0:N1} MB" -f ((Get-Item $GradleZip).Length / 1MB))
    Expand-Archive -Path $GradleZip -DestinationPath $Toolchain -Force
    if (-not (Test-Path $gradleBat)) { throw "gradle.bat not found after unpack: $gradleBat" }
    Ok 'Gradle unpacked'
}
$ErrorActionPreference = 'Continue'
$gVer = (& $gradleBat --version 2>&1 | Select-String -Pattern '^Gradle ' | Select-Object -First 1).Line
$ErrorActionPreference = $previousEap
Ok "$gVer"

# ---------------------------------------------------------------------------
Step '2/4 environment'
# ---------------------------------------------------------------------------
$env:JAVA_HOME        = $JbrDir
$env:ANDROID_HOME     = $SdkDir
$env:ANDROID_SDK_ROOT = $SdkDir
$env:GRADLE_USER_HOME = $GradleHome

foreach ($p in @($javaExe, (Join-Path $SdkDir 'platforms\android-34\android.jar'),
                 (Join-Path $SdkDir 'build-tools\34.0.0\aapt2.exe'))) {
    if (-not (Test-Path $p)) { throw "missing prerequisite: $p" }
}
Ok 'JDK / SDK / Gradle all present'

# ---------------------------------------------------------------------------
Step '3/4 generate Gradle wrapper into the project'
# ---------------------------------------------------------------------------
$gradlewBat = Join-Path $Root 'gradlew.bat'
$wrapperJar = Join-Path $Root 'gradle\wrapper\gradle-wrapper.jar'
if (Test-Path $wrapperJar) {
    Ok 'wrapper already generated'
} else {
    # Two things are needed for a working wrapper on this machine:
    #   1. the :wrapper task validates the distribution URL over the network, and
    #      services.gradle.org is unreachable here -- so point it at a mirror;
    #   2. copy gradle-wrapper.jar out of the unpacked 8.7 distribution.
    $mirrorUrl = 'https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip'
    Write-Host "  running: gradle wrapper --gradle-distribution-url $mirrorUrl"
    $ErrorActionPreference = 'Continue'
    Push-Location $Root
    & $gradleBat wrapper --gradle-version 8.7 --distribution-type bin `
        --gradle-distribution-url $mirrorUrl 2>&1 |
        Where-Object { $_ -match 'BUILD|wrapper|error|Error|What went wrong' } |
        ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
    Pop-Location
    $ErrorActionPreference = $previousEap

    if (-not (Test-Path $wrapperJar)) {
        $jarInDist = Join-Path $GradleDir 'lib\plugins\gradle-wrapper-8.7.jar'
        $outDir = Split-Path $wrapperJar -Parent
        if ((Test-Path $jarInDist) -and (Test-Path $outDir)) {
            Copy-Item $jarInDist $wrapperJar -Force
            Ok "wrapper jar copied from the distribution"
        }
    }

    if (Test-Path $wrapperJar) {
        Ok "wrapper created: $wrapperJar"
        $props = Join-Path $Root 'gradle\wrapper\gradle-wrapper.properties'
        if (Test-Path $props) {
            Ok 'distributionUrl ->'
            Get-Content $props | Where-Object { $_ -match 'distributionUrl' } |
                ForEach-Object { Write-Host "      $_" -ForegroundColor DarkGray }
        }
    } else {
        Warn 'wrapper generation failed; builds still work via the unpacked Gradle'
    }
}

# ---------------------------------------------------------------------------
Step '4/4 build debug APK'
# ---------------------------------------------------------------------------
$apkPath = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
if (Test-Path $BuildLog) { Remove-Item $BuildLog -Force }

Write-Host '  first build downloads AGP + Compose dependencies (300-800 MB, 10-30 min)'
Write-Host "  log: $BuildLog"
Write-Host ''

$ErrorActionPreference = 'Continue'
Push-Location $Root
& $gradleBat --project-dir $Root :app:assembleDebug --no-daemon --stacktrace 2>&1 |
    Tee-Object -FilePath $BuildLog
$code = $LASTEXITCODE
Pop-Location
$ErrorActionPreference = $previousEap

Write-Host ''
if ($code -eq 0 -and (Test-Path $apkPath)) {
    $mb = (Get-Item $apkPath).Length / 1MB
    Write-Host ('BUILD SUCCEEDED  ->  {0}  ({1:N2} MB)' -f $apkPath, $mb) -ForegroundColor Green
    $copy = Join-Path $AppDir 'TrailRun-debug.apk'
    Copy-Item $apkPath $copy -Force
    Write-Host "copied to: $copy" -ForegroundColor Green
    Write-Host ''
    Write-Host 'install on a connected phone:' -ForegroundColor Cyan
    Write-Host ("  `"{0}`" install -r `"{1}`"" -f (Join-Path $SdkDir 'platform-tools\adb.exe'), $apkPath) -ForegroundColor Cyan
} else {
    Write-Host "BUILD FAILED (exit code $code)" -ForegroundColor Red
    Write-Host "full log: $BuildLog" -ForegroundColor Red
    Write-Host ''
    Write-Host 'last 40 lines of the log:' -ForegroundColor Yellow
    Get-Content $BuildLog -Tail 40 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  $_" }
}
