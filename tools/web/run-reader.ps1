<#
.SYNOPSIS
  Opens the Inkstone reader - a local website for the notes and lectures in your Nextcloud.

.DESCRIPTION
  Reads credentials from `watcher.local.properties` at the repo root - the same file the M12
  transcript watcher uses, gitignored via `*.local.properties`. If you have already run
  `tools/m12/run-watcher.ps1`, there is nothing new to configure.

  The reader is READ-ONLY. It issues PROPFIND and GET and nothing else: there is no PUT, no
  DELETE and no MKCOL anywhere in `tools/web`. It cannot change or lose anything in Nextcloud.

  WARNING: `nextcloud.password` must be an *app password*, not your account password. Nextcloud
  mints them under Settings -> Security -> "Create new app password".

  WARNING: the server binds to 127.0.0.1 by default and holds an app password while it runs.
  `-Host` refuses any other address unless you also pass `-Token`, and one shared token over
  plain HTTP is thin protection - do not put this on a network you do not trust.

  WARNING - KEEP EVERY EXECUTABLE LINE IN THIS FILE ASCII
  Windows PowerShell 5.1 decodes a BOM-less .ps1 as the system ANSI codepage, not UTF-8. An em
  dash inside a `Write-Error "..."` string then decodes as several cp1252 bytes, one of which
  ends the string early, and the file fails to parse before a single line runs. That is exactly
  how `run-watcher.ps1` shipped broken the first time. Non-ASCII in this comment block is fine.

.PARAMETER Port
  Defaults to 8765.

.PARAMETER Mirror
  Read a local folder instead of the live Nextcloud - a Nextcloud desktop-client sync folder, or
  anything with the same shape. Needs no credentials and works with no network.

.PARAMETER Open
  Launch a browser once the server is up.

.EXAMPLE
  ./tools/web/run-reader.ps1 -Open
  The usual way to start it.

.EXAMPLE
  ./tools/web/run-reader.ps1 -Mirror "C:\Users\me\Nextcloud"
  Read a synced folder offline, with no credentials at all.
#>
param(
    [int]$Port = 8765,
    [string]$Mirror,
    [string]$BindHost = "127.0.0.1",
    [string]$Token,
    [string]$Root = "",
    [switch]$Open,
    [switch]$Verbose
)

$ErrorActionPreference = "Stop"
$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$config = Join-Path $repo "watcher.local.properties"

if (-not $Mirror -and -not (Test-Path $config)) {
    Write-Error @"
No $config

The reader uses the same credentials as the M12 transcript watcher. Create that file with:

    nextcloud.url=https://your-nextcloud
    nextcloud.uid=your-uid
    nextcloud.password=your-app-password

It is gitignored. Mint the app password in Nextcloud under Settings -> Security ->
"Create new app password" - not your account password.

Or read a local folder instead and skip credentials entirely:

    ./tools/web/run-reader.ps1 -Mirror "C:\path\to\a\Nextcloud\sync\folder"
"@
}

# Find an interpreter that actually runs. On a machine with Python from python.org but the
# Microsoft Store aliases still enabled, the bare name `python` resolves to a WindowsApps stub
# that fails with "The system cannot find the path specified" - while `py` works. Git Bash sees
# the real one and PowerShell does not, so this cannot be settled by testing in one shell.
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

$server = Join-Path $PSScriptRoot "server.py"
$serverArgs = @($server, "--port", $Port, "--host", $BindHost)
if ($Mirror)  { $serverArgs += @("--mirror", $Mirror) }
if ($Token)   { $serverArgs += @("--token", $Token) }
if ($Root)    { $serverArgs += @("--root", $Root) }
if ($Open)    { $serverArgs += "--open" }
if ($Verbose) { $serverArgs += "--verbose" }

& $python @serverArgs
exit $LASTEXITCODE
