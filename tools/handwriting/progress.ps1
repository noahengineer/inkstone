<#
    Live progress of a training run, read off its train.log (docs/HANDWRITING-SYNTHESIS.md §19.4).

        powershell -ExecutionPolicy Bypass -File tools\handwriting\progress.ps1
        powershell -ExecutionPolicy Bypass -File tools\handwriting\progress.ps1 -Run tools\handwriting\runs\v1-3x256 -Epochs 15

    Read-only: it never touches the trainer. Ctrl-C to stop.
#>
param(
    [string]$Run = (Join-Path $PSScriptRoot 'runs\v1-3x256'),
    [int]$Epochs = 15,
    [int]$EverySeconds = 15
)

function Bar([double]$fraction, [int]$width = 40) {
    # 1.0 and 0.0, not 1 and 0: with an int first, PowerShell picks Math.Min(int, int) and rounds 0.42 to 0.
    $f = [Math]::Max(0.0, [Math]::Min(1.0, $fraction))
    $full = [int][Math]::Round($f * $width)
    return '[' + ('#' * $full) + ('.' * ($width - $full)) + ']'
}

$log = Join-Path $Run 'train.log'
while ($true) {
    $lines = if (Test-Path $log) { Get-Content $log } else { @() }
    $progress = $lines | Select-String 'epoch (\d+) batch (\d+)/(\d+) loss (\S+) (\d+)s elapsed' | Select-Object -Last 2
    $epochsDone = @($lines | Select-String '^\{"epoch"')
    Clear-Host
    Write-Host "Handwriting model training  ($Run)" -ForegroundColor Cyan
    Write-Host ''
    if ($progress.Count -ge 1) {
        $m = $progress[-1].Matches[0]
        $ep = [int]$m.Groups[1].Value
        $b = [int]$m.Groups[2].Value
        $n = [int]$m.Groups[3].Value
        $spb = 1.8
        if ($progress.Count -eq 2) {
            $p0 = $progress[0].Matches[0]
            $db = $b - [int]$p0.Groups[2].Value
            $ds = [int]$m.Groups[5].Value - [int]$p0.Groups[5].Value
            if ($db -gt 0 -and $ds -gt 0) { $spb = $ds / $db }
        }
        $epochFraction = $b / $n
        $runFraction = (($ep - 1) + $epochFraction) / $Epochs
        $epochLeft = ($n - $b) * $spb / 60
        $runLeft = ((($Epochs - $ep) * $n) + ($n - $b)) * $spb / 3600
        Write-Host ("Epoch {0,2} of {1}   {2} {3,5:N1}%   ~{4:N0} min left" -f $ep, $Epochs, (Bar $epochFraction), ($epochFraction * 100), $epochLeft)
        Write-Host ("Whole run       {0} {1,5:N1}%   ~{2:N1} h left" -f (Bar $runFraction), ($runFraction * 100), $runLeft)
        Write-Host ''
        Write-Host ("batch {0}/{1}, loss {2}, {3:N2} s per batch" -f $b, $n, $m.Groups[4].Value, $spb)
    } else {
        Write-Host 'No progress lines yet.'
    }
    Write-Host ''
    Write-Host 'Finished epochs (NLL per point; lower is better, validation is writers never seen):'
    foreach ($e in $epochsDone) {
        $j = $e.Line | ConvertFrom-Json
        Write-Host ("  epoch {0,2}: train {1,7:N3}   validation {2,7:N3}   {3:N0} min" -f $j.epoch, $j.train, $j.val, ($j.seconds / 60))
    }
    $alive = Get-CimInstance Win32_Process -Filter "Name='python.exe'" | Where-Object { $_.CommandLine -match 'train.py' }
    Write-Host ''
    if ($alive) { Write-Host 'Trainer: running' -ForegroundColor Green } else { Write-Host 'Trainer: NOT running' -ForegroundColor Yellow }
    Write-Host ("Updated {0:HH:mm:ss}; refreshes every {1} s. Ctrl-C to stop." -f (Get-Date), $EverySeconds) -ForegroundColor DarkGray
    Start-Sleep $EverySeconds
}
