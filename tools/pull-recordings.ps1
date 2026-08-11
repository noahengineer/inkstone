<#
    Copy every recording off a device and check each one (M12, docs/M12-AUDIO.md §9).

        powershell -ExecutionPolicy Bypass -File tools\pull-recordings.ps1
        powershell -ExecutionPolicy Bypass -File tools\pull-recordings.ps1 -Out D:\lectures

    WHY THIS EXISTS
    ---------------
    A recording lives in `/data/data/dev.inkstone/files/recordings/`, which is app-private.
    MTP cannot see it -- `send-to-tablet.ps1` works in the other direction only -- so until a
    note is bound to Nextcloud and the outbox has drained, the **only** copy of a lecture is
    on the tablet. That is a poor place for the one thing this app holds that cannot be
    re-made (§9: "you cannot re-record a lecture"), and it is the first thing to fix after
    recording something real.

    So this needs adb, and adb needs USB debugging authorized:
      * plug the tablet in,
      * set the USB mode to **File transfer** (Samsung suppresses the debugging prompt in
        charging-only mode),
      * accept "Allow USB debugging?" on the tablet.

    `run-as` works because the installed build is the debug one. It would not on a release
    build, which is worth knowing before this is ever the documented answer for a user.

    WHAT IT CHECKS
    --------------
    Every .opus is passed to `verify-ogg-opus.py`, which parses the container by hand rather
    than asking the encoder whether its own output is readable -- CLAUDE.md's standing rule,
    and the reason M4's PDFs were valid, parseable and blank. The reported duration comes from
    the last Ogg page's granule, so it is the real length and not bytes-over-bitrate, which
    measured 42% high.
#>

param(
    [string]$Out = '',
    [string]$Serial = '',
    [string]$Package = 'dev.inkstone'
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
if (-not $Out) { $Out = Join-Path $RepoRoot 'recordings-pulled' }

$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "No adb at $adb. Install platform-tools or edit this script." }

$serialArgs = if ($Serial) { @('-s', $Serial) } else { @() }

# `adb devices` lists unauthorized devices too, and the difference is the whole point.
$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_.Trim() }
if (-not $devices) {
    throw @"
No device. Plug the tablet in, set its USB mode to File transfer (not Charging only), and
accept the "Allow USB debugging?" prompt on the tablet itself -- it cannot be accepted from here.
"@
}
$unauthorized = $devices | Where-Object { $_ -match 'unauthorized' }
if ($unauthorized -and -not ($devices | Where-Object { $_ -match "`tdevice" })) {
    throw @"
The device is attached but UNAUTHORIZED, so its private storage cannot be read:
  $($unauthorized -join "`n  ")
Accept the "Allow USB debugging?" prompt on the tablet. If it never appears, switch the USB
mode to File transfer and re-plug; Samsung suppresses the prompt in charging-only mode.
"@
}

New-Item -ItemType Directory -Force -Path $Out | Out-Null

$listing = & $adb @serialArgs shell "run-as $Package ls files/recordings/ 2>/dev/null"
$names = @($listing -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if (-not $names) { throw "No recordings under files/recordings/ for $Package." }

Write-Host "$($names.Count) file(s) on the device" -ForegroundColor Cyan

foreach ($name in $names) {
    $target = Join-Path $Out $name
    Write-Host "  pulling $name" -ForegroundColor DarkGray
    # exec-out, not `shell ... > file`: shell mangles binary on some hosts, and an .opus that
    # arrives one byte different is a file that still looks fine until something decodes it.
    & $adb @serialArgs exec-out run-as $Package cat "files/recordings/$name" |
        Set-Content -Path $target -Encoding Byte
}

Write-Host ""
Write-Host "== Checking each recording ==" -ForegroundColor Cyan
Get-ChildItem $Out -Filter *.opus | ForEach-Object {
    & python (Join-Path $PSScriptRoot 'verify-ogg-opus.py') $_.FullName
    $timeline = [IO.Path]::ChangeExtension($_.FullName, '.timeline')
    if (Test-Path $timeline) {
        $lines = @(Get-Content $timeline | Where-Object { $_.Trim() })
        $gaps = @($lines | Where-Object { $_.StartsWith('#gap') })
        Write-Host "  timeline: $($lines.Count - $gaps.Count) stroke(s), $($gaps.Count) gap(s)" -ForegroundColor Green
    } else {
        Write-Host "  timeline: none -- nothing was written during this recording" -ForegroundColor DarkGray
    }
    Write-Host ""
}

Write-Host "Copied to $Out" -ForegroundColor Green
