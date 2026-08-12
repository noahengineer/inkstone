<#
.SYNOPSIS
  Install (or remove) the M12 transcript watcher as a Windows Scheduled Task.

.DESCRIPTION
  Registers a task that runs `run-watcher.ps1 -Once` on a repeating interval, so recordings
  uploaded from the tablet are transcribed without anybody pressing anything.

      ./tools/m12/install-watcher-task.ps1              # install, every 15 minutes
      ./tools/m12/install-watcher-task.ps1 -Minutes 5   # more often
      ./tools/m12/install-watcher-task.ps1 -Uninstall   # remove it
      ./tools/m12/install-watcher-task.ps1 -Status      # is it there, when did it last run

  WHAT THIS COMMITS YOU TO, in plain terms:

  * **It spends money on its own.** Every recording it finds is uploaded to AssemblyAI and
    billed at roughly $0.23 per hour of audio. That is the point of automating it, but it is a
    standing authorisation rather than a one-off, so it is written here rather than buried.
  * **It sends classroom audio to a third party**, unattended. Everything the tablet uploads
    goes, including recordings nobody reviewed first.
  * **It only runs while this PC is on and you are logged in.** It is registered without stored
    credentials on purpose - a task that runs "whether the user is logged on or not" needs your
    Windows password cached by the scheduler, and this script will not ask for one. If you want
    it running around the clock, put it on the machine that hosts Nextcloud instead; the watcher
    is plain Python and needs only the three environment variables.

  The pass itself is safe to interrupt: the server is the only state, a recording with a
  transcript beside it is finished work, and the audio is trashed only after the transcript has
  been verified present (docs/M12-AUDIO.md section 8).

.PARAMETER Minutes
  How often to poll. Default 15. A lecture is not urgent and each pass costs one PROPFIND per
  folder, so there is no reason to hammer it.

.PARAMETER Uninstall
  Remove the task.

.PARAMETER Status
  Show whether the task exists, its schedule, and the last run time and result.
#>
param(
    [int]$Minutes = 15,
    [switch]$Uninstall,
    [switch]$Status
)

$ErrorActionPreference = "Stop"
$TaskName = "Inkstone transcript watcher"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$watcher = Join-Path $repo "tools\m12\run-watcher.ps1"
$logDir = Join-Path $repo ".watcher-logs"

if ($Status) {
    $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if (-not $task) { "Not installed."; exit 0 }
    $info = Get-ScheduledTaskInfo -TaskName $TaskName
    "Task      : $TaskName"
    "State     : $($task.State)"
    "Last run  : $($info.LastRunTime)"
    "Last result: $($info.LastTaskResult)  (0 = success)"
    "Next run  : $($info.NextRunTime)"
    if (Test-Path $logDir) {
        $latest = Get-ChildItem $logDir -Filter *.log | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($latest) { ""; "Latest log ($($latest.Name)):"; Get-Content $latest.FullName -Tail 15 }
    }
    exit 0
}

if ($Uninstall) {
    if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        "Removed '$TaskName'. Nothing else was changed; your transcripts and recordings are untouched."
    } else {
        "'$TaskName' was not installed."
    }
    exit 0
}

if (-not (Test-Path $watcher)) { Write-Error "Cannot find $watcher" }
if (-not (Test-Path (Join-Path $repo "watcher.local.properties"))) {
    Write-Error "No watcher.local.properties at $repo - the watcher has no credentials to use."
}
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

# One log per day, appended, so a failure three days ago is still readable. `-Once` because the
# scheduler owns the cadence: a crashed long-running process would silently stop polling forever,
# whereas a task that fails is retried on the next tick and shows a non-zero LastTaskResult.
$command = @"
`$ErrorActionPreference = 'Continue'
Set-Location '$repo'
`$stamp = Get-Date -Format 'yyyy-MM-dd'
`$log = Join-Path '$logDir' ("watcher-`$stamp.log")
"=== `$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ===" | Out-File -FilePath `$log -Append -Encoding utf8
& '$watcher' -Once *>&1 | Out-File -FilePath `$log -Append -Encoding utf8
"@
$encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))

$action = New-ScheduledTaskAction -Execute "powershell.exe" `
    -Argument "-NoProfile -NonInteractive -WindowStyle Hidden -EncodedCommand $encoded"

# Starts at the next whole interval and repeats effectively forever. `-AtLogOn` as well, so the
# first pass after a reboot does not wait a quarter of an hour.
$triggerNow = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
    -RepetitionInterval (New-TimeSpan -Minutes $Minutes)
$triggerLogon = New-ScheduledTaskTrigger -AtLogOn

$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -ExecutionTimeLimit (New-TimeSpan -Hours 2) `
    -MultipleInstances IgnoreNew

Register-ScheduledTask -TaskName $TaskName `
    -Action $action -Trigger @($triggerNow, $triggerLogon) -Settings $settings `
    -Description "Transcribes Inkstone lecture recordings found in Nextcloud (docs/M12-AUDIO.md section 16)." `
    -Force | Out-Null

"Installed '$TaskName' - every $Minutes minutes, and once at logon."
"Logs      : $logDir"
"Check it  : ./tools/m12/install-watcher-task.ps1 -Status"
"Remove it : ./tools/m12/install-watcher-task.ps1 -Uninstall"
