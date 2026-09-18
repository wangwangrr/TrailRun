# ---------------------------------------------------------------------------
# TrailRun algorithm verification  (ASCII only - do not add non-ASCII chars)
#
# Windows PowerShell 5.1 reads BOM-less .ps1 files with the system ANSI codepage
# (GBK on this machine). Non-ASCII characters get mangled and can merge lines,
# breaking the script. Keep this file pure ASCII.
#
# Compiles the pure-Kotlin core algorithms (no Android dependencies) and runs
# their self-tests on the desktop JVM. Catches geometry/parsing bugs that would
# otherwise only show up on a phone.
#
# Usage: powershell -ExecutionPolicy Bypass -File tools\verify-algorithms.ps1
# ---------------------------------------------------------------------------

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Root      = Split-Path -Parent $PSScriptRoot
$JbrDir    = 'D:\app\Android\Android Studio\jbr'
$javaExe   = Join-Path $JbrDir 'bin\java.exe'
$Cache     = Join-Path $Root '.gradle-home\caches\modules-2\files-2.1'
$OutDir    = Join-Path $Root '.toolchain\verify-out'
$SrcCore   = Join-Path $Root 'app\src\main\java\com\trailrun\mockgps\core'
$SrcVerify = Join-Path $Root 'tools\verify'

function Step($m) { Write-Host ""; Write-Host "===== $m =====" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "  [OK] $m" -ForegroundColor Green }
function Fail($m) { Write-Host "  [!!] $m" -ForegroundColor Red }

if (-not (Test-Path $javaExe)) { throw "JDK not found: $javaExe" }

function Find-Jar([string]$pattern) {
    $f = Get-ChildItem $Cache -Recurse -Filter $pattern -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $f) { throw "jar not found in Gradle cache: $pattern" }
    return $f.FullName
}

Step '1/3 assemble classpath'
$compilerJars = @(
    (Find-Jar 'kotlin-compiler-embeddable-1.9.24.jar'),
    (Find-Jar 'kotlin-stdlib-1.9.24.jar'),
    (Find-Jar 'kotlin-script-runtime-1.9.24.jar'),
    (Find-Jar 'kotlin-reflect-1.6.10.jar'),
    (Find-Jar 'trove4j-1.0.20200330.jar'),
    (Find-Jar 'annotations-13.0.jar')
)
$compilerCp = $compilerJars -join ';'
Ok "$($compilerJars.Count) jars"

$stdlibJar = Find-Jar 'kotlin-stdlib-1.9.24.jar'

# PlaceSearch declares a suspend fun, so the coroutines runtime is needed to compile it.
$coroutinesJar = Find-Jar 'kotlinx-coroutines-core-jvm-1.7.3.jar'
Ok 'kotlinx-coroutines ready'

# PlaceSearch uses org.json (bundled in Android, must be supplied on the JVM).
# Test-only dependency; it does not enter the app's dependency set.
$jsonJar = Join-Path $Root '.toolchain\json.jar'
if (-not (Test-Path $jsonJar)) {
    Write-Host '  downloading org.json (test only, 78KB)...'
    $eapLocal = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & node (Join-Path $Root 'tools\dl.mjs') `
        'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' `
        $jsonJar '50000' | Out-Null
    $ErrorActionPreference = $eapLocal
}
if (Test-Path $jsonJar) { Ok 'org.json ready' } else { Fail 'org.json missing' }

Step '2/3 compile core algorithms + tests'
if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Path $OutDir | Out-Null

$sources = @(
    (Join-Path $SrcCore 'PathSimplify.kt'),
    (Join-Path $SrcCore 'RouteSimulator.kt'),
    (Join-Path $SrcCore 'PlaceSearch.kt'),
    (Join-Path $SrcCore 'OsmEndpoints.kt'),
    (Join-Path $SrcCore 'AppPassword.kt'),
    (Join-Path $SrcVerify 'PathSimplifyTest.kt'),
    (Join-Path $SrcVerify 'RouteSimulatorTest.kt'),
    (Join-Path $SrcVerify 'PlaceSearchTest.kt'),
    (Join-Path $SrcVerify 'TileProviderTest.kt'),
    (Join-Path $SrcVerify 'PasswordTest.kt')
) | Where-Object { Test-Path $_ }

$compileCp = $stdlibJar
if (Test-Path $jsonJar) { $compileCp = $stdlibJar + ';' + $jsonJar }
$compileCp = $compileCp + ';' + $coroutinesJar

$eap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $javaExe -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    @sources -cp $compileCp -d $OutDir -nowarn 2>&1 |
    Where-Object { $_ -match ' error' } |
    ForEach-Object { Write-Host "    $_" -ForegroundColor DarkYellow }
$compileCode = $LASTEXITCODE
$ErrorActionPreference = $eap

if ($compileCode -ne 0) {
    Fail "compilation failed (exit $compileCode)"
    exit 1
}
Ok "compiled $($sources.Count) files"

Step '3/3 run tests'
$runCp = $OutDir + ';' + $stdlibJar
if (Test-Path $jsonJar) { $runCp = $runCp + ';' + $jsonJar }
$runCp = $runCp + ';' + $coroutinesJar
$tests = @(
    'com.trailrun.mockgps.core.PathSimplifyTest',
    'com.trailrun.mockgps.core.RouteSimulatorTest',
    'com.trailrun.mockgps.core.PlaceSearchTest',
    'com.trailrun.mockgps.core.TileProviderTest',
    'com.trailrun.mockgps.core.PasswordTest'
)
$failed = 0
foreach ($t in $tests) {
    Write-Host ""
    Write-Host "--- $t" -ForegroundColor Cyan
    $eap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $javaExe -cp $runCp $t
    if ($LASTEXITCODE -ne 0) { $failed++ }
    $ErrorActionPreference = $eap
}

Write-Host ""
if ($failed -eq 0) {
    Write-Host 'ALL ALGORITHM TESTS PASSED' -ForegroundColor Green
    exit 0
} else {
    Write-Host "$failed test class(es) FAILED" -ForegroundColor Red
    exit 1
}
