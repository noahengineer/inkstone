<#
.SYNOPSIS
  Runs M12's transcript watcher against your Nextcloud.

.DESCRIPTION
  Reads credentials from `watcher.local.properties` at the repo root - gitignored via
  `*.local.properties`, like the AssemblyAI key beside it - so nothing sensitive is ever typed
  into a shell history or committed.

  Create that file once:

      nextcloud.url=https://cloud.example.com
      nextcloud.uid=noah
      nextcloud.password=xxxxx-xxxxx-xxxxx-xxxxx-xxxxx

  WARNING: `nextcloud.password` must be an *app password*, not your account password. Nextcloud
  mints them under Settings -> Security -> "Create new app password". An app password can be
  revoked on its own, does not carry your second factor, and is what the tablet already uses
  (SPEC section 7.1).

  WARNING: `nextcloud.uid` is the *uid*, not the login name. They differ on plenty of installs;
  the watcher prints the one the server reports at startup, so run it once and check they agree.

  WARNING - KEEP EVERY EXECUTABLE LINE IN THIS FILE ASCII
  Windows PowerShell 5.1 decodes a BOM-less .ps1 as the system ANSI codepage, not UTF-8. An em
  dash inside a `Write-Error "..."` string then decodes as several cp1252 bytes, one of which
  ends the string early, and the file fails to parse: "The string is missing the terminator",
  two errors, before a single line runs. That is exactly how this launcher shipped broken the
  first time - written, never executed, and a `python`-vs-`py` bug hiding behind it.

  Non-ASCII inside this comment block is harmless. Non-ASCII in code is not. Adding a BOM would
  also fix it, but ASCII code lines survive any later re-save that drops one.

.PARAMETER Once
  One pass, then exit. This is the form to use from Task Scheduler.

.PARAMETER IntervalSeconds
  Poll interval when running continuously. Default 300. The real cadence is this plus however
  long a pass takes, because the sleep happens after the pass rather than on a fixed schedule.

.PARAMETER DryRun
  Find work and report it, with a cost estimate. Sends nothing to AssemblyAI, writes nothing,
  deletes nothing. Always the right first run.

.PARAMETER Yes
  Required for a real run that would spend more than about a pound. Without it the watcher stops
  and prints what it would have transcribed and what that would cost.

.EXAMPLE
  ./tools/m12/run-watcher.ps1 -DryRun -Once
  Safe first run: shows what it would transcribe and what that would cost.

.EXAMPLE
  ./tools/m12/run-watcher.ps1 -Once -Yes
  One real pass: transcribes everything found, writes the transcripts back, trashes the audio.
#>
param(
    [switch]$Once,
    [int]$IntervalSeconds = 300,
    [switch]$DryRun,
    [switch]$Yes
)

$ErrorActionPreference = "Stop"
$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$config = Join-Path $repo "watcher.local.properties"

if (-not (Test-Path $config)) {
    Write-Error @"
No $config

Create it with these three lines:

    nextcloud.url=https://your-nextcloud
    nextcloud.uid=your-uid
    nextcloud.password=your-app-password

It is gitignored. Mint the app password in Nextcloud under Settings -> Security ->
"Create new app password" - not your account password.
"@
}

$settings = @{}
foreach ($line in Get-Content $config) {
    $trimmed = $line.Trim()
    if ($trimmed -and -not $trimmed.StartsWith("#") -and $trimmed.Contains("=")) {
        $parts = $trimmed.Split("=", 2)
        $settings[$parts[0].Trim()] = $parts[1].Trim()
    }
}

foreach ($required in @("nextcloud.url", "nextcloud.uid", "nextcloud.password")) {
    if (-not $settings.ContainsKey($required)) { Write-Error "$config is missing $required" }
}

if (-not $settings["nextcloud.url"].StartsWith("https://")) {
    # SPEC section 14 is HTTPS-only and the reason is not ceremony: this request carries an app
    # password and downloads a classroom recording. There is deliberately no accept-anyway switch.
    Write-Error "nextcloud.url must be https:// - refusing to send an app password in the clear."
}

# Find an interpreter that actually runs. On a machine with Python installed from python.org but
# the Microsoft Store aliases still enabled, the bare name `python` resolves to a WindowsApps stub
# that fails with "The system cannot find the path specified" - while `py` works. Git Bash sees the
# real one and PowerShell does not, so this cannot be settled by testing in one shell.
$python = $null
foreach ($candidate in @("py", "python", "python3")) {
    $command = Get-Command $candidate -ErrorAction SilentlyContinue
    if (-not $command) { continue }
    try {
        $reported = & $candidate --version 2>&1
        if ($LASTEXITCODE -eq 0 -and "$reported" -match "Python 3\.(\d+)" -and [int]$Matches[1] -ge 10) {
            $python = $candidate
            break
        }
    } catch { }
}
if (-not $python) {
    Write-Error @"
No working Python 3.10+ found. Tried: py, python, python3.

If Python is installed but `python` opens the Microsoft Store, turn off the aliases in
Settings -> Apps -> Advanced app settings -> App execution aliases, or just use `py`.
"@
}

$env:INKSTONE_NC_URL = $settings["nextcloud.url"]
$env:INKSTONE_NC_UID = $settings["nextcloud.uid"]
$env:INKSTONE_NC_PASSWORD = $settings["nextcloud.password"]
if ($settings.ContainsKey("nextcloud.login")) { $env:INKSTONE_NC_LOGIN = $settings["nextcloud.login"] }

$watcher = Join-Path $PSScriptRoot "watch.py"
$watchArgs = @($watcher, "--interval", $IntervalSeconds)
if ($Once) { $watchArgs += "--once" }
if ($DryRun) { $watchArgs += "--dry-run" }
if ($Yes) { $watchArgs += "--yes" }

& $python @watchArgs
exit $LASTEXITCODE
