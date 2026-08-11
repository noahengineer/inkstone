<#
.SYNOPSIS
  Runs M12's transcript watcher against your Nextcloud.

.DESCRIPTION
  Reads credentials from `watcher.local.properties` at the repo root — gitignored via
  `*.local.properties`, like the AssemblyAI key beside it — so nothing sensitive is ever typed
  into a shell history or committed.

  Create that file once:

      nextcloud.url=https://cloud.example.com
      nextcloud.uid=noah
      nextcloud.password=xxxx-xxxxx-xxxxx-xxxxx-xxxxx

  ⚠️ `nextcloud.password` must be an **app password**, not your account password. Nextcloud mints
  them under Settings → Security → "Create new app password". An app password can be revoked on
  its own, does not carry your second factor, and is what the tablet already uses (SPEC §7.1).

  ⚠️ `nextcloud.uid` is the **uid**, not the login name. They differ on plenty of installs; the
  watcher prints the one the server reports at startup, so run it once and check the two agree.

.PARAMETER Once
  One pass, then exit. This is the form to use from Task Scheduler.

.PARAMETER IntervalSeconds
  Poll interval when running continuously. Default 300.

.PARAMETER DryRun
  Find work and report it. Sends nothing to AssemblyAI, writes nothing, deletes nothing.

.EXAMPLE
  ./tools/m12/run-watcher.ps1 -DryRun -Once
  Safe first run: shows what it would transcribe and what that would cost.

.EXAMPLE
  ./tools/m12/run-watcher.ps1
  Runs continuously, polling every five minutes.
#>
param(
    [switch]$Once,
    [int]$IntervalSeconds = 300,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$config = Join-Path $repo "watcher.local.properties"

if (-not (Test-Path $config)) {
    Write-Error @"
No $config.

Create it with:

    nextcloud.url=https://your-nextcloud
    nextcloud.uid=your-uid
    nextcloud.password=your-app-password

It is gitignored. See the comment at the top of this script for how to mint an app password.
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
    # SPEC §14 is HTTPS-only and the reason is not ceremony: this request carries an app password
    # and downloads a classroom recording. There is deliberately no "accept anyway" switch.
    Write-Error "nextcloud.url must be https:// — refusing to send an app password in the clear."
}

$env:INKSTONE_NC_URL = $settings["nextcloud.url"]
$env:INKSTONE_NC_UID = $settings["nextcloud.uid"]
$env:INKSTONE_NC_PASSWORD = $settings["nextcloud.password"]
if ($settings.ContainsKey("nextcloud.login")) { $env:INKSTONE_NC_LOGIN = $settings["nextcloud.login"] }

$watcher = Join-Path $PSScriptRoot "watch.py"
$watchArgs = @($watcher, "--interval", $IntervalSeconds)
if ($Once) { $watchArgs += "--once" }
if ($DryRun) { $watchArgs += "--dry-run" }

& python @watchArgs
exit $LASTEXITCODE
