# ---------------------------------------------------------------------------
# TrailRun build bootstrap  (ASCII only on purpose)
#
# Why ASCII: Windows PowerShell 5.1 reads BOM-less .ps1 files with the system
# ANSI codepage (GBK here). Non-ASCII bytes get mangled and can even decode into
# stray quote characters, which breaks parsing. Keep this file pure ASCII.
#
# Why extraction instead of the installer:
#   android-studio-*.exe is an NSIS package that requests administrator rights.
#   Running it silently returned exit code 1223 (UAC declined), so instead we
#   extract the same payload with 7-Zip. Android Studio does not need a registry
#   entry to run, so a portable extraction works and needs no elevation.
#
# Stage 1 (foreground, ~5 minutes):
#   1. extract Android Studio to D:\app\Android\Android Studio (portable)
#   2. unpack Android SDK command-line tools to D:\app\Android\Sdk
#   3. verify the bundled JDK can reach the network over TLS (do this EARLY:
#      the sandbox blocks the Windows cert store, so schannel fails while the
#      JDK's own cacerts work -- if this step fails nothing else will)
#   4. sdkmanager: platform-tools, platforms;android-34, build-tools;34.0.0
#   5. write local.properties + user environment variables
#   6. start the long Gradle build as a DETACHED process
# ---------------------------------------------------------------------------

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# --- paths ---
$Root       = Split-Path -Parent $PSScriptRoot
$AppDir     = 'D:\app\Android'
$StudioDir  = Join-Path $AppDir 'Android Studio'
$SdkDir     = Join-Path $AppDir 'Sdk'
$Toolchain  = Join-Path $Root '.toolchain'
$GradleHome = Join-Path $Root '.gradle-home'
$Installer  = Join-Path $Toolchain 'android-studio.exe'
$CmdlineZip = Join-Path $Toolchain 'cmdline-tools.zip'
$BuildLog   = Join-Path $Toolchain 'build.log'
$RunnerDir  = Join-Path $Toolchain 'runner'
$ExtractDir = Join-Path $Toolchain 'studio-extract'
$SevenZip   = 'D:\app\7-Zip\7z.exe'
$PsExe      = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'

function Step($m) { Write-Host ""; Write-Host "===== $m =====" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "  [OK] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  [!!] $m" -ForegroundColor Yellow }

$sw = [System.Diagnostics.Stopwatch]::StartNew()

# ---------------------------------------------------------------------------
Step '1/7 prepare directories'
# ---------------------------------------------------------------------------
New-Item -ItemType Directory -Force -Path $AppDir, $SdkDir, $GradleHome, $Toolchain, $RunnerDir | Out-Null
Ok "install root: $AppDir"
$freeGB = (Get-Item $AppDir).PSDrive.Free / 1GB
Ok ("free space on D: {0:N1} GB" -f $freeGB)
if ($freeGB -lt 8) { Warn 'less than 8 GB free; the build may run out of space' }

# ---------------------------------------------------------------------------
Step '2/7 extract Android Studio (portable, no admin needed)'
# ---------------------------------------------------------------------------
$studioExe = Join-Path $StudioDir 'bin\studio64.exe'
if (Test-Path $studioExe) {
    Ok 'Android Studio already present, skipping extraction'
} else {
    if (-not (Test-Path $Installer)) { throw "installer not found: $Installer" }
    if (-not (Test-Path $SevenZip))  { throw "7-Zip not found: $SevenZip" }
    Ok ("extracting {0:N1} MB with 7-Zip (takes a few minutes)" -f ((Get-Item $Installer).Length / 1MB))

    if (Test-Path $ExtractDir) { Remove-Item $ExtractDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $ExtractDir | Out-Null

    & $SevenZip x $Installer ('-o' + $ExtractDir) -y -bso0 -bsp0 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "7-Zip extraction failed with code $LASTEXITCODE" }

    # the payload usually lands in a top-level folder; find wherever studio64.exe is
    $found = Get-ChildItem -Path $ExtractDir -Recurse -Filter 'studio64.exe' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $found) { throw "studio64.exe not found inside the extracted payload" }
    $payloadRoot = Split-Path -Parent (Split-Path -Parent $found.FullName)
    Ok "payload root: $payloadRoot"

    if (Test-Path $StudioDir) { Remove-Item $StudioDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $StudioDir) | Out-Null
    Move-Item -Path $payloadRoot -Destination $StudioDir -Force
    Remove-Item $ExtractDir -Recurse -Force -ErrorAction SilentlyContinue

    if (-not (Test-Path $studioExe)) { throw "studio64.exe still missing at $studioExe" }
}
Ok "Android Studio -> $StudioDir"

$JbrDir  = Join-Path $StudioDir 'jbr'
$javaExe = Join-Path $JbrDir 'bin\java.exe'
if (-not (Test-Path $javaExe)) { throw "bundled JDK missing: $javaExe" }
Ok "bundled JDK -> $JbrDir"

# ---------------------------------------------------------------------------
Step '3/7 PREFLIGHT: can the bundled JDK do HTTPS?'
# ---------------------------------------------------------------------------
# This is the single most important early check. The sandbox blocks access to the
# Windows credential store, so anything using schannel (curl, Invoke-WebRequest)
# fails with SEC_E_NO_CREDENTIALS. The JDK ships its own cacerts bundle, so it
# should work -- but if it does not, sdkmanager and Gradle cannot download
# anything and there is no point continuing.
$env:JAVA_HOME = $JbrDir
# NOTE: `java -version` writes its banner to stderr. With $ErrorActionPreference
# set to Stop, PowerShell 5.1 turns native stderr output into a terminating
# error, so relax it for the duration of these calls.
$previousEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaBanner = (& $javaExe -version 2>&1 | Out-String).Trim()
$ErrorActionPreference = $previousEap
Ok "java: $javaBanner"

$probeFile = Join-Path $Toolchain 'JdkTlsProbe.java'
$probeSrc = @'
import java.net.*;
import java.io.*;
public class JdkTlsProbe {
  public static void main(String[] a) throws Exception {
    String[] urls = {
      "https://dl.google.com/android/repository/repository2-3.xml",
      "https://repo.maven.apache.org/maven2/"
    };
    for (String u : urls) {
      try {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(15000);
        c.setRequestMethod("HEAD");
        System.out.println("OK   " + c.getResponseCode() + "  " + u);
      } catch (Exception e) {
        System.out.println("FAIL " + e.getClass().getSimpleName() + ": " + e.getMessage() + "  " + u);
      }
    }
  }
}
'@
[System.IO.File]::WriteAllText($probeFile, $probeSrc, [System.Text.Encoding]::ASCII)

Write-Host '  probing HTTPS from the JDK...'
$ErrorActionPreference = 'Continue'
$probeOut = & $javaExe $probeFile 2>&1
$ErrorActionPreference = $previousEap
$probeOut | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
$tlsOk = ($probeOut -join "`n") -match 'OK\s+200'
if (-not $tlsOk) {
    Warn 'the bundled JDK could not reach the network over TLS.'
    Warn 'sdkmanager and Gradle cannot download dependencies in that state.'
    Warn 'Set an HTTP(S) proxy for the JDK and rerun, e.g. -Dhttps.proxyHost/-Dhttps.proxyPort.'
    throw 'TLS preflight failed'
}
Ok 'JDK HTTPS works -- downloads will succeed'

# ---------------------------------------------------------------------------
Step '4/7 unpack Android SDK command-line tools'
# ---------------------------------------------------------------------------
$cmdlineRoot = Join-Path $SdkDir 'cmdline-tools'
$sdkManager  = Join-Path $cmdlineRoot 'latest\bin\sdkmanager.bat'
if (Test-Path $sdkManager) {
    Ok 'cmdline-tools already present'
} else {
    if (-not (Test-Path $CmdlineZip)) { throw "archive not found: $CmdlineZip" }
    $tmp = Join-Path $Toolchain 'cmdline-extract'
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive -Path $CmdlineZip -DestinationPath $tmp -Force
    $inner = Join-Path $tmp 'cmdline-tools'
    if (-not (Test-Path $inner)) { throw 'unexpected archive layout' }
    New-Item -ItemType Directory -Force -Path $cmdlineRoot | Out-Null
    Move-Item -Path $inner -Destination (Join-Path $cmdlineRoot 'latest') -Force
    Remove-Item $tmp -Recurse -Force
    if (-not (Test-Path $sdkManager)) { throw 'sdkmanager unpack failed' }
    Ok 'cmdline-tools ready'
}

# ---------------------------------------------------------------------------
Step '5/7 install SDK components'
# ---------------------------------------------------------------------------
$env:ANDROID_HOME     = $SdkDir
$env:ANDROID_SDK_ROOT = $SdkDir
$env:GRADLE_USER_HOME = $GradleHome

# pre-seed licence hashes so sdkmanager never blocks on an interactive prompt
$licDir = Join-Path $SdkDir 'licenses'
New-Item -ItemType Directory -Force -Path $licDir | Out-Null
$lic1 = "`n24333f8a63b6825ea9c5514f83c2829b004d1fee`n8933bad161af4178b1185d1a37fbf41ea5269c55`nd56f5187479451eabf01fb78af6dfcb131a6481e`n"
$lic2 = "`n84831b9409646a918e30573bab4c9c91346d8abd`n"
[System.IO.File]::WriteAllText((Join-Path $licDir 'android-sdk-license'), $lic1)
[System.IO.File]::WriteAllText((Join-Path $licDir 'android-sdk-preview-license'), $lic2)
Ok 'SDK licence hashes written'

$pkgs = @('platform-tools', 'platforms;android-34', 'build-tools;34.0.0')
Write-Host ("  installing: " + ($pkgs -join ', ') + "  (about 300-600 MB)")
$ErrorActionPreference = 'Continue'
& $sdkManager --sdk_root=$SdkDir --install @pkgs 2>&1 |
    Where-Object { $_ -match 'Warning|Error|error|Installing|done|Unzipping|100%' } |
    ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
$sdkCode = $LASTEXITCODE
$ErrorActionPreference = $previousEap
if ($sdkCode -ne 0) { Warn "sdkmanager exit code $sdkCode (checking for the files anyway)" }

$required = @(
    (Join-Path $SdkDir 'platforms\android-34\android.jar'),
    (Join-Path $SdkDir 'build-tools\34.0.0\aapt2.exe'),
    (Join-Path $SdkDir 'platform-tools\adb.exe')
)
foreach ($f in $required) {
    if (-not (Test-Path $f)) { throw "SDK component missing: $f" }
}
Ok 'all SDK components present'

# ---------------------------------------------------------------------------
Step '6/7 write project + environment configuration'
# ---------------------------------------------------------------------------
$sdkForProps = $SdkDir -replace '\\', '/'
[System.IO.File]::WriteAllText((Join-Path $Root 'local.properties'), "sdk.dir=$sdkForProps`n")
Ok "local.properties -> $SdkDir"

[Environment]::SetEnvironmentVariable('ANDROID_HOME', $SdkDir, 'User')
[Environment]::SetEnvironmentVariable('ANDROID_SDK_ROOT', $SdkDir, 'User')
[Environment]::SetEnvironmentVariable('JAVA_HOME', $JbrDir, 'User')
Ok 'user environment variables set: ANDROID_HOME / ANDROID_SDK_ROOT / JAVA_HOME'

$gLib = Join-Path $StudioDir 'plugins\gradle\lib'
$gJar = Get-ChildItem $gLib -Filter 'gradle-launcher-*.jar' -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($gJar) {
    Ok ("Gradle " + ($gJar.BaseName -replace 'gradle-launcher-', '') + " (bundled with Android Studio)")
} else {
    # Android Studio 2024.1+ no longer ships a runnable Gradle distribution; it only
    # bundles gradle-api-*.jar for its own plugin. The real distribution is fetched
    # separately by tools\build-apk.ps1 from a mirror (services.gradle.org and
    # GitHub Releases are both unreachable from this network).
    $gradleBat = Join-Path $Toolchain 'gradle-8.7\bin\gradle.bat'
    if (Test-Path $gradleBat) {
        Ok 'Gradle 8.7 present in .toolchain (from mirror)'
    } else {
        Warn 'Gradle 8.7 not unpacked yet; tools\build-apk.ps1 will handle it'
    }
}

# ---------------------------------------------------------------------------
Step '7/7 start the Gradle build as a detached process'
# ---------------------------------------------------------------------------
# The real work lives in build-apk.ps1 (unpack Gradle, generate the wrapper, build).
# It is launched detached because the first build downloads hundreds of MB and can
# take 10-30 minutes, which must not block this script.
$apkPath = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
$buildScript = Join-Path $PSScriptRoot 'build-apk.ps1'
if (-not (Test-Path $buildScript)) { throw "missing $buildScript" }

$runnerPs1 = Join-Path $RunnerDir 'run-build.ps1'
$innerText = @(
    '$ErrorActionPreference = ''Continue''',
    '& ''' + $buildScript + ''' *>&1 | Out-File -FilePath ''' + $BuildLog + ''' -Encoding utf8',
    'Set-Content -Path ''' + (Join-Path $Toolchain 'build.exitcode') + ''' -Value $LASTEXITCODE'
) -join "`r`n"
[System.IO.File]::WriteAllText($runnerPs1, $innerText, [System.Text.Encoding]::ASCII)

# -EncodedCommand (base64 UTF-16LE) sidesteps every quoting and codepage problem
$bootstrap = '& ''' + $runnerPs1 + ''''
$encoded = [Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes($bootstrap))

$proc = Start-Process -FilePath $PsExe `
    -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encoded `
    -WindowStyle Hidden -PassThru
Ok "build started in detached process (PID $($proc.Id))"
Ok "log file: $BuildLog"

$sw.Stop()
Write-Host ""
Write-Host ('=' * 64) -ForegroundColor Cyan
Write-Host ("stage 1 finished in {0:N1} minutes" -f $sw.Elapsed.TotalMinutes) -ForegroundColor Cyan
Write-Host "Android Studio : $StudioDir" -ForegroundColor Cyan
Write-Host "Android SDK    : $SdkDir" -ForegroundColor Cyan
Write-Host "APK target     : $apkPath" -ForegroundColor Cyan
Write-Host "build log      : $BuildLog" -ForegroundColor Cyan
Write-Host ('=' * 64) -ForegroundColor Cyan
