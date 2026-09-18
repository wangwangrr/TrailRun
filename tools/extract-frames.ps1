# ---------------------------------------------------------------------------
# Extract frames from a video so the agent can "watch" it.   (ASCII only)
#
# Why PowerShell and not Node: this environment's sandbox blocks Node from
# capturing a child process's output through pipes (spawnSync -> EPERM).
# PowerShell's own pipelines are unaffected, so the work happens here.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\extract-frames.ps1 -Video <path> [-Mode scene|even] [-OutDir <dir>] [-Cols 4]
#
# Produces:
#   <OutDir>\f001.jpg ...        individual frames (scaled to 720px wide)
#   <OutDir>\sheet-01.jpg ...    contact sheets, 4x4 frames each, for quick review
# ---------------------------------------------------------------------------

# -Fps applies to even mode; 0 means auto (derive from a 60-frame cap).
# It exists because phone screen recordings are often visually static, in which
# case scene detection extracts ZERO frames and a manual sampling rate is needed.
param(
    [Parameter(Mandatory = $true)][string]$Video,
    [string]$OutDir = '.toolchain\frames',
    [ValidateSet('scene', 'even')][string]$Mode = 'scene',
    [int]$Cols = 4,
    [double]$Fps = 0
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Root = Split-Path -Parent $PSScriptRoot
$ffmpeg = Join-Path $Root '.toolchain\ffmpeg\ffmpeg.exe'
$ffprobe = Join-Path $Root '.toolchain\ffmpeg\ffprobe.exe'

if (-not (Test-Path $Video)) { throw "video not found: $Video" }
if (-not (Test-Path $ffmpeg)) { throw "ffmpeg not found: $ffmpeg" }
if (-not (Test-Path $ffprobe)) { throw "ffprobe not found: $ffprobe" }

$Video = (Resolve-Path $Video).Path
if (-not [System.IO.Path]::IsPathRooted($OutDir)) { $OutDir = Join-Path $Root $OutDir }
if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null

# ---------- 1. metadata ----------
$probeJson = & $ffprobe -v error -select_streams v:0 `
    -show_entries format=duration,size,format_name `
    -show_entries stream=width,height,avg_frame_rate,codec_name `
    -of json $Video 2>&1 | Out-String

$info = $probeJson | ConvertFrom-Json
$stream = $info.streams[0]
$duration = [double]$info.format.duration
$sizeMb = [double]$info.format.size / 1MB
$fpsParts = "$($stream.avg_frame_rate)".Split('/')
$fps = if ($fpsParts.Count -eq 2 -and [double]$fpsParts[1] -ne 0) {
    [double]$fpsParts[0] / [double]$fpsParts[1]
} else { 0 }

Write-Host '=== video info ===' -ForegroundColor Cyan
Write-Host ("  container/codec : {0} / {1}" -f $info.format.format_name, $stream.codec_name)
Write-Host ("  resolution      : {0}x{1}" -f $stream.width, $stream.height)
Write-Host ("  fps             : {0:N2}" -f $fps)
Write-Host ("  duration        : {0:N1} s" -f $duration)
Write-Host ("  size            : {0:N1} MB" -f $sizeMb)

# ---------- 2. extract ----------
$scale = 'scale=720:-2'

if ($Mode -eq 'scene') {
    # scene-change detection: a UI screenshot recording only "moves" when the user
    # taps something, so this picks out exactly the meaningful moments.
    # threshold 0.25 is well above animation noise but well below a screen switch.
    $vf = "$scale,select='gt(scene,0.25)'"
    $extra = @('-vsync', 'vfr')
    Write-Host ''
    Write-Host 'mode: scene-change (one frame per visual change)' -ForegroundColor Cyan
} else {
    $fpsOut = if ($Fps -gt 0) {
        $Fps
    } elseif ($duration -gt 0) {
        [Math]::Min(1.0, 60.0 / $duration)
    } else {
        1.0
    }
    $fpsText = $fpsOut.ToString('0.####', [Globalization.CultureInfo]::InvariantCulture)
    $vf = "$scale,fps=$fpsText"
    $extra = @()
    Write-Host ''
    Write-Host ("mode: even sampling ({0:N2} fps, approx {1} frames)" -f $fpsOut, [Math]::Ceiling($duration * $fpsOut)) -ForegroundColor Cyan
}

$pattern = Join-Path $OutDir 'f%03d.jpg'
Write-Host ("  filter: {0}" -f $vf) -ForegroundColor DarkGray
Write-Host ("  extra : {0}" -f ($extra -join ' ')) -ForegroundColor DarkGray
& $ffmpeg -hide_banner -loglevel error -y -i $Video -vf $vf @extra -q:v 3 $pattern 2>&1 |
    ForEach-Object { Write-Host "  $_" -ForegroundColor DarkYellow }

$frames = Get-ChildItem $OutDir -Filter 'f*.jpg' | Sort-Object Name
Write-Host ''
Write-Host ("extracted {0} frames -> {1}" -f $frames.Count, $OutDir) -ForegroundColor Green

if ($frames.Count -eq 0) {
    Write-Host 'no frames extracted - the video may have no scene changes;' -ForegroundColor Yellow
    Write-Host 'retry with -Mode even' -ForegroundColor Yellow
    exit 0
}

# ---------- 3. contact sheets ----------
$perSheet = $Cols * $Cols
$sheetCount = [Math]::Ceiling($frames.Count / $perSheet)
Write-Host ''
for ($s = 0; $s -lt $sheetCount; $s++) {
    $start = $s * $perSheet + 1
    $sheet = Join-Path $OutDir ("sheet-{0:D2}.jpg" -f ($s + 1))
    & $ffmpeg -hide_banner -loglevel error -y -start_number $start `
        -i (Join-Path $OutDir 'f%03d.jpg') -frames:v 1 `
        -vf "tile=${Cols}x${Cols}:margin=6:padding=4:color=white" -q:v 3 $sheet 2>&1 |
        ForEach-Object { Write-Host "  $_" -ForegroundColor DarkYellow }
    if (Test-Path $sheet) {
        $kb = [Math]::Round((Get-Item $sheet).Length / 1KB)
        $last = [Math]::Min($start + $perSheet - 1, $frames.Count)
        Write-Host ("  sheet-{0:D2}.jpg  frames {1}..{2}  {3} KB" -f ($s + 1), $start, $last, $kb) -ForegroundColor Green
    }
}

# ---------- 4. timeline estimate ----------
Write-Host ''
Write-Host '=== frame -> approximate timestamp ===' -ForegroundColor Cyan
if ($Mode -eq 'scene') {
    Write-Host '  (scene mode: frame index is NOT linear in time; estimates below are rough)'
}
$i = 0
foreach ($f in $frames) {
    $t = if ($frames.Count -gt 0) { $duration * ($i + 0.5) / $frames.Count } else { 0 }
    if ($i -lt 30) {
        # [int] cast matters: {1:D2} requires an integer, and [Math]::Floor returns a double
        $m = [int][Math]::Floor($t / 60)
        $sec = $t % 60
        Write-Host ("  {0}  ~ {1:D2}:{2:00.0}" -f $f.Name, $m, $sec)
    }
    $i++
}
if ($frames.Count -gt 30) { Write-Host ("  ... total {0} frames" -f $frames.Count) }
