<#
    Build the debug APK and copy it to the tablet's Download folder over MTP.

        powershell -ExecutionPolicy Bypass -File tools\send-to-tablet.ps1
        powershell -ExecutionPolicy Bypass -File tools\send-to-tablet.ps1 -NoBuild
        powershell -ExecutionPolicy Bypass -File tools\send-to-tablet.ps1 -Device 'Galaxy Tab S9'

    Then on the tablet: My Files -> Download -> tap the APK. Android asks once
    for permission to install unknown apps from My Files.

    WHY MTP AND NOT `adb install`
    -----------------------------
    `adb install` is one command and is the right tool *when USB debugging is
    already authorized*. It is not always: the "Allow USB debugging?" prompt
    only appears when the USB mode is File transfer (Samsung suppresses it in
    charging-only mode), it has to be re-accepted after a Revoke, and it cannot
    be accepted from the desktop. MTP needs none of that -- it is the same plain
    file copy Explorer does, and the tablet is already mounted for it.

    So this script is the fallback that always works, and it installs nothing:
    it puts a file in Download and stops. Use `adb install -r <apk>` instead when
    `adb devices` says `device` rather than `unauthorized`.

    NOTE: the measurements in docs/M1-INK.md section 4.1 still need adb, because
    `dumpsys gfxinfo` has no MTP equivalent. Getting the build onto the tablet
    and measuring it are two different problems; this only solves the first.

    TWO GOTCHAS, both of which fail SILENTLY
    ----------------------------------------
    1. `Folder.CopyHere` needs the *source* FolderItem to stay referenced for
       the duration. Build it inline -- `$dest.CopyHere($shell.NameSpace($dir).ParseName($f))`
       -- and the COM object is released before the transfer starts. The call
       returns success, no error is raised, and nothing is copied. Ask how that
       was discovered.
    2. `CopyHere` is asynchronous and reports nothing. The only way to know it
       finished is to poll the destination until the byte count matches. A file
       that appears at the right *name* but the wrong *size* is a torn copy, and
       Android will reject it with a parse error rather than say so.
#>

param(
    [string]$Device = '',
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$Apk = Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk'
$DestName = 'inkstone-debug.apk'

# ---------------------------------------------------------------------------
# 1. Build
# ---------------------------------------------------------------------------
if (-not $NoBuild) {
    # The README's headline trap: Oracle's javapath shim keeps a newer JDK ahead
    # of 21 on PATH, and Gradle 8.14's refusal to start does not say why.
    $javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
    # Through cmd, not `& $javaExe -version 2>&1`. `java -version` writes to
    # stderr, and Windows PowerShell wraps a native command's stderr in
    # ErrorRecords -- which $ErrorActionPreference='Stop' then throws on, so the
    # check fails on a *correct* JDK. cmd does the redirect before PowerShell
    # ever sees it.
    $version = cmd /c "`"$javaExe`" -version 2>&1" | Out-String
    if ($version -match '"(\d+)') {
        $major = [int]$Matches[1]
        if ($major -lt 17 -or $major -gt 24) {
            throw "Gradle 8.14 supports Java 17-24; found $major via $javaExe. Set JAVA_HOME to a JDK 21 -- see the README."
        }
    }

    Write-Host '== Building :app:assembleDebug ==' -ForegroundColor Cyan
    Push-Location $RepoRoot
    try {
        & .\gradlew.bat :app:assembleDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path $Apk)) {
    throw "No APK at $Apk. Run without -NoBuild."
}

$expectedBytes = (Get-Item $Apk).Length
Write-Host "APK: $expectedBytes bytes, built $((Get-Item $Apk).LastWriteTime)" -ForegroundColor Cyan

# ---------------------------------------------------------------------------
# 2. Find the tablet
# ---------------------------------------------------------------------------
$shell = New-Object -ComObject Shell.Application

# 0x11 is "This PC", which is where portable (MTP) devices are listed. They are
# not drive letters and have no filesystem path, so Get-ChildItem cannot see
# them -- the shell namespace is the only way in.
$candidates = @($shell.NameSpace(0x11).Items() | Where-Object { $_.IsFolder -and -not $_.Path.StartsWith('C:') -and $_.Path -notmatch '^[A-Z]:\\$' })

if ($Device) {
    $tablet = $candidates | Where-Object { $_.Name -like "*$Device*" } | Select-Object -First 1
    if (-not $tablet) {
        throw "No portable device matching '$Device'. Visible: $(($candidates | ForEach-Object { $_.Name }) -join ', ')"
    }
} else {
    if ($candidates.Count -eq 0) {
        throw 'No portable device found. Plug the tablet in and set its USB mode to File transfer (not Charging only).'
    }
    if ($candidates.Count -gt 1) {
        throw "More than one portable device: $(($candidates | ForEach-Object { $_.Name }) -join ', '). Pass -Device <name>."
    }
    $tablet = $candidates[0]
}

Write-Host "Device: $($tablet.Name)" -ForegroundColor Cyan

$storage = $tablet.GetFolder.Items() | Select-Object -First 1
if (-not $storage) { throw "$($tablet.Name) exposes no storage. Unlock the screen -- MTP hides the filesystem while locked." }

$downloadItem = $storage.GetFolder.Items() | Where-Object { $_.Name -eq 'Download' }
if (-not $downloadItem) { throw "No Download folder under $($storage.Name)." }
$dest = $downloadItem.GetFolder

# ---------------------------------------------------------------------------
# 3. Stage under the destination name
# ---------------------------------------------------------------------------
# CopyHere cannot rename in flight, so the source has to already be called what
# it should be called on the tablet. Staging also keeps the build output free of
# a second copy of a 75 MB file.
$stagingDir = Join-Path $env:TEMP 'inkstone-send'
if (-not (Test-Path $stagingDir)) { New-Item -ItemType Directory -Path $stagingDir | Out-Null }
$staged = Join-Path $stagingDir $DestName
Copy-Item $Apk $staged -Force

# Remove any previous copy first. An MTP overwrite prompts, and this script has
# no console to prompt on -- it would hang until the timeout.
$stale = $dest.Items() | Where-Object { $_.Name -eq $DestName }
if ($stale) {
    Write-Host "Removing previous $DestName on the tablet" -ForegroundColor DarkGray
    $stale.InvokeVerb('delete')
    Start-Sleep -Seconds 3
}

# ---------------------------------------------------------------------------
# 4. Copy -- see gotcha 1
# ---------------------------------------------------------------------------
$srcFolder = $shell.NameSpace($stagingDir)
if (-not $srcFolder) { throw "Could not open staging folder $stagingDir" }
$srcItem = $srcFolder.ParseName($DestName)
if (-not $srcItem) { throw "Staged file vanished: $staged" }

Write-Host "Copying to $($tablet.Name)\$($storage.Name)\Download\$DestName ..." -ForegroundColor Cyan
$dest.CopyHere($srcItem)

# ---------------------------------------------------------------------------
# 5. Wait for it -- see gotcha 2
# ---------------------------------------------------------------------------
$deadline = (Get-Date).AddMinutes(10)
$lastReported = -1
while ((Get-Date) -lt $deadline) {
    $landed = $dest.Items() | Where-Object { $_.Name -eq $DestName }
    if ($landed) {
        $size = [int64]$landed.ExtendedProperty('System.Size')
        if ($size -eq $expectedBytes) {
            Write-Host "OK  $DestName is on the tablet, $size bytes." -ForegroundColor Green
            Write-Host 'On the tablet: My Files -> Download -> tap it.' -ForegroundColor Green
            Remove-Item $staged -Force -ErrorAction SilentlyContinue
            exit 0
        }
        if ($size -ne $lastReported) {
            Write-Host ("  {0:N0} / {1:N0} bytes" -f $size, $expectedBytes) -ForegroundColor DarkGray
            $lastReported = $size
        }
    }
    Start-Sleep -Seconds 5
}

throw @"
Timed out after 10 minutes.

If the file never appeared at all, the copy was almost certainly dropped rather
than slow -- check the tablet is unlocked and its USB mode is File transfer, then
run again. If it appeared but stayed short of $expectedBytes bytes, delete it on
the tablet before retrying: a partial APK fails to install with a parse error
that does not mention truncation.
"@
