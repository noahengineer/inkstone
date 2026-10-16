<#
    Train the handwriting model on another, faster Windows PC (docs/HANDWRITING-SYNTHESIS.md section 19.4).

        powershell -ExecutionPolicy Bypass -File tools\handwriting\train-on-pc.ps1

    What it does, in order, and nothing else:
      1. finds Python 3.12-3.14 (numpy 2.5.1 needs 3.12 or newer);
      2. makes a venv in %LOCALAPPDATA%\inkstone-hw\venv-pc -- outside OneDrive, because a CUDA build
         of PyTorch is several GB -- and leaves the system Python alone;
      3. installs exactly these, pinned (CLAUDE.md rule 3; resolved 2026-10-14):
           torch 2.14.1  -- +cu130 for an NVIDIA GPU of compute capability 7.5 or newer (RTX 20 series
                            onwards, RTX 50 included) on driver 580 or newer; +cu126 for an older NVIDIA
                            setup; the CPU build otherwise. From download.pytorch.org for the CUDA builds,
                            from PyPI for the CPU one;
           numpy 2.5.1, pillow 12.3.0 -- from PyPI;
         plus whatever those pull in (sympy, mpmath, networkx, jinja2, markupsafe, filelock, fsspec,
         setuptools, typing-extensions);
      4. resumes the run in runs\v1-3x256 from its last checkpoint, on the GPU when there is one,
         appending to the same train.log, so progress.ps1 shows it here or on the laptop.

    The data (data\prepared-v1) and the run (runs\v1-3x256) are in the OneDrive folder, so OneDrive
    brings them here and takes every new checkpoint back. WARNING: train on ONE machine at a time: two
    trainers writing one checkpoint corrupt each other's resume point.

    Ctrl-C stops it; running the script again resumes, losing at most -CheckpointMinutes of work.
#>
param(
    [int]$Epochs = 15,
    [string]$Run = 'runs\v1-3x256',
    [string]$Data = 'data\prepared-v1',
    [int]$Batch = 32,
    [int]$CheckpointMinutes = 5
)
# Not 'Stop': in Windows PowerShell 5.1 a native command's stderr (pip's notices, a Python warning)
# becomes an error record, and 'Stop' would end the script on the first one. Failures are checked.
$ErrorActionPreference = 'Continue'
Set-Location $PSScriptRoot

function Step($text) { Write-Host "== $text" -ForegroundColor Cyan }

# --- 0. The data and the run must have arrived through OneDrive ---------------------------------------
Step 'Checking the data and the checkpoint are here'
foreach ($need in @("$Data\prepared.npz", "$Data\prepared.json", "$Run\checkpoint.pt", "$Run\data.json")) {
    if (-not (Test-Path $need)) {
        throw "Missing $need. Let OneDrive finish syncing the 'Notes app' folder (or copy tools\handwriting\data and tools\handwriting\runs over), then run this again."
    }
}

# --- 1. Python 3.12-3.14 ------------------------------------------------------------------------------
Step 'Finding Python 3.12, 3.13 or 3.14'
$python = $null
foreach ($v in '3.14', '3.13', '3.12') {
    try {
        $out = & py "-$v" -c "import sys; print(sys.executable)" 2>$null
        if ($LASTEXITCODE -eq 0 -and $out) { $python = $out.Trim(); break }
    } catch { }
}
if (-not $python) {
    throw "No Python 3.12-3.14 found (via the 'py' launcher). Install one from python.org, or run: winget install Python.Python.3.13"
}
Write-Host "   $python"

# --- 2. A venv of its own -----------------------------------------------------------------------------
$venv = Join-Path $env:LOCALAPPDATA 'inkstone-hw\venv-pc'
$vpy = Join-Path $venv 'Scripts\python.exe'
if (-not (Test-Path $vpy)) {
    Step "Making $venv"
    New-Item -ItemType Directory -Force (Split-Path $venv) | Out-Null
    & $python -m venv $venv
    if (-not (Test-Path $vpy)) { throw "Could not make a venv at $venv" }
}

# --- 3. Which PyTorch build ---------------------------------------------------------------------------
Step 'Choosing the PyTorch build'
$tag = 'cpu'
$smi = Get-Command nvidia-smi -ErrorAction SilentlyContinue
if ($smi) {
    $gpu = & nvidia-smi --query-gpu=name,driver_version,compute_cap --format=csv,noheader 2>$null | Select-Object -First 1
    if ($gpu) {
        $parts = $gpu -split ',\s*'
        $driver = [double](($parts[1] -split '\.')[0])
        $cap = [double]$parts[2]
        Write-Host "   NVIDIA: $($parts[0]), driver $($parts[1]), compute capability $($parts[2])"
        $tag = if ($cap -ge 7.5 -and $driver -ge 580) { 'cu130' } else { 'cu126' }
    }
}
Write-Host "   torch 2.14.1+$tag"

$installed = & $vpy -c "import torch; print(torch.__version__)" 2>$null
if ($installed -ne "2.14.1+$tag") {
    Step 'Installing the pinned packages'
    & $vpy -m pip install --disable-pip-version-check --upgrade pip
    if ($tag -eq 'cpu') {
        & $vpy -m pip install --disable-pip-version-check 'torch==2.14.1'
    } else {
        & $vpy -m pip install --disable-pip-version-check "torch==2.14.1+$tag" --extra-index-url "https://download.pytorch.org/whl/$tag"
    }
    & $vpy -m pip install --disable-pip-version-check 'numpy==2.5.1' 'pillow==12.3.0'
    $check = & $vpy -c "import torch, numpy, PIL; print(torch.__version__)" 2>$null
    if ($check -ne "2.14.1+$tag" -and -not ($tag -eq 'cpu' -and $check -eq '2.14.1+cpu')) { throw "The install did not finish (torch says '$check'). Scroll up for pip's error." }
}
& $vpy -c "import torch; ok = torch.cuda.is_available(); print('   torch', torch.__version__, '| GPU:', torch.cuda.get_device_name(0) if ok else 'none, training on the CPU')"

# --- 4. Train ------------------------------------------------------------------------------------------
Step "Resuming $Run (to $Epochs epochs). Ctrl-C to stop; run this script again to resume."
$log = Join-Path $Run 'train.log'
& $vpy -I -u -W ignore train.py $Data --run $Run --layers 3 --cells 256 --epochs $Epochs --batch $Batch `
    --checkpoint-minutes $CheckpointMinutes --device auto --resume 2>&1 | Tee-Object -FilePath $log -Append
