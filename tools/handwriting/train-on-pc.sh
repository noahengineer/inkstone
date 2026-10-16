#!/usr/bin/env bash
# Train the handwriting model on a Linux PC (docs/HANDWRITING-SYNTHESIS.md section 19.4).
#
#     cd tools/handwriting && bash train-on-pc.sh            # v1: resume runs/v1-3x256 (as before)
#     cd tools/handwriting && bash train-v2-on-pc.sh         # v2 (maths): a fresh run, see that file
#
# RUN, DATA and EPOCHS may be set in the environment; FRESH=1 starts a run that has no checkpoint
# yet (running it again after a Ctrl-C resumes that run's own checkpoint). With none of them set,
# this is exactly the v1 resume it always was.
#
# What it does, in order, and nothing else:
#   1. finds Python 3.12-3.14 (numpy 2.5.1 needs 3.12 or newer);
#   2. makes a venv in ~/.local/share/inkstone-hw/venv -- the system Python is left alone;
#   3. installs exactly these, pinned (CLAUDE.md rule 3; resolved 2026-10-14):
#        torch 2.14.1 from download.pytorch.org --
#          +cu130 for an NVIDIA GPU of compute capability 7.5 or newer (RTX 20 series onwards, RTX 50
#                 included) on driver 580 or newer,
#          +cu126 for an older NVIDIA card or driver,
#          +rocm7.2 when ROCm is installed (an AMD GPU),
#          +cpu otherwise;
#        numpy 2.5.1 and pillow 12.3.0 from PyPI;
#      plus what those pull in (sympy, mpmath, networkx, jinja2, markupsafe, filelock, fsspec,
#      setuptools, typing-extensions, and the nvidia-* runtime wheels for a CUDA build);
#   4. resumes $RUN (runs/v1-3x256 by default) from its last checkpoint on the GPU when there is
#      one -- or, with FRESH=1 and no checkpoint yet, starts it -- appending to train.log,
#      checkpointing every 5 minutes and keeping every epoch's checkpoint (epochNNN.pt).
#
# Ctrl-C stops it; running it again resumes. Watch it with `python3 progress.py $RUN $EPOCHS` in
# another terminal. When you are done, bring $RUN back to the laptop (see the end of this output).
set -euo pipefail
cd "$(dirname "$0")"

EPOCHS="${EPOCHS:-15}"
RUN="${RUN:-runs/v1-3x256}"
DATA="${DATA:-data/prepared-v1}"
FRESH="${FRESH:-0}"
VENV="${XDG_DATA_HOME:-$HOME/.local/share}/inkstone-hw/venv"

step() { printf '\033[36m== %s\033[0m\n' "$1"; }

step "Checking the data and the checkpoint are here"
for need in "$DATA/prepared.npz" "$DATA/prepared.json"; do
  [ -f "$need" ] || { echo "Missing $need -- unpack the transfer tarball here first."; exit 1; }
done
if [ "$FRESH" = "1" ]; then
  if [ -f "$RUN/checkpoint.pt" ]; then
    echo "   $RUN already has a checkpoint: resuming it (delete $RUN to start again from nothing)."
  else
    echo "   $RUN has no checkpoint yet: starting a new run on $DATA."
  fi
else
  for need in "$RUN/checkpoint.pt" "$RUN/data.json"; do
    [ -f "$need" ] || { echo "Missing $need -- unpack inkstone-hw-train.tar.gz here first (or set FRESH=1 for a new run)."; exit 1; }
  done
fi
mkdir -p "$RUN"

step "Finding Python 3.12, 3.13 or 3.14"
PY=""
for cand in python3.14 python3.13 python3.12 python3; do
  if command -v "$cand" >/dev/null 2>&1 && "$cand" -c 'import sys; sys.exit(0 if (3,12) <= sys.version_info[:2] <= (3,14) else 1)'; then
    PY="$(command -v "$cand")"; break
  fi
done
if [ -z "$PY" ] && command -v uv >/dev/null 2>&1; then
  # Arch (and other rolling distros) may already be on Python 3.15, which torch 2.14.1 has no wheel for.
  # uv fetches a standalone 3.14 into its own cache and touches nothing else.
  step "No Python 3.12-3.14 on the system: fetching 3.14 with uv"
  uv python install 3.14
  PY="$(uv python find 3.14)"
fi
[ -n "$PY" ] || {
  echo "No Python 3.12-3.14 found."
  echo "  Arch: sudo pacman -S --needed uv   (then run this again; it fetches Python 3.14 itself)"
  echo "  Ubuntu/Debian: sudo apt install python3.12 python3.12-venv;  Fedora: sudo dnf install python3.13"
  exit 1
}
echo "   $PY ($("$PY" --version))"

if [ ! -x "$VENV/bin/python" ]; then
  step "Making $VENV"
  mkdir -p "$(dirname "$VENV")"
  "$PY" -m venv "$VENV" || { echo "Could not make a venv -- on Ubuntu/Debian: sudo apt install python3-venv (or python3.12-venv)"; exit 1; }
fi
VPY="$VENV/bin/python"

step "Choosing the PyTorch build"
TAG="cpu"
if command -v nvidia-smi >/dev/null 2>&1 && GPU="$(nvidia-smi --query-gpu=name,driver_version,compute_cap --format=csv,noheader 2>/dev/null | head -n 1)" && [ -n "$GPU" ]; then
  DRIVER="$(echo "$GPU" | cut -d, -f2 | tr -d ' ' | cut -d. -f1)"
  CAP="$(echo "$GPU" | cut -d, -f3 | tr -d ' ')"
  echo "   NVIDIA: $GPU"
  if [ "$DRIVER" -ge 580 ] && awk "BEGIN{exit !($CAP >= 7.5)}"; then TAG="cu130"; else TAG="cu126"; fi
elif [ -d /opt/rocm ] || command -v rocminfo >/dev/null 2>&1 \
  || { command -v lspci >/dev/null 2>&1 && lspci | grep -Ei 'VGA|3D|Display' | grep -qEi 'AMD|ATI'; }; then
  # The ROCm build of torch carries its own runtime; it needs only the kernel's amdgpu driver.
  echo "   AMD GPU found: using the ROCm build"
  TAG="rocm7.2"
fi
echo "   torch 2.14.1+$TAG"

HAVE="$("$VPY" -c 'import torch; print(torch.__version__)' 2>/dev/null || true)"
if [ "$HAVE" != "2.14.1+$TAG" ]; then
  step "Installing the pinned packages"
  "$VPY" -m pip install --disable-pip-version-check --upgrade pip
  "$VPY" -m pip install --disable-pip-version-check "torch==2.14.1+$TAG" --index-url "https://download.pytorch.org/whl/$TAG"
  "$VPY" -m pip install --disable-pip-version-check "numpy==2.5.1" "pillow==12.3.0"
fi
"$VPY" -c 'import torch; ok = torch.cuda.is_available(); print("   torch", torch.__version__, "| GPU:", torch.cuda.get_device_name(0) if ok else "none, training on the CPU")'

step "Training $RUN on $DATA (to $EPOCHS epochs). Ctrl-C to stop; run this again to resume."
echo "   Watch it from another terminal: cd \"$(pwd)\" && python3 progress.py $RUN $EPOCHS"
set +e
"$VPY" -I -u -W ignore train.py "$DATA" --run "$RUN" --layers 3 --cells 256 --epochs "$EPOCHS" --batch 32 \
  --checkpoint-minutes 5 --device auto --resume 2>&1 | tee -a "$RUN/train.log"
set -e

NAME="$(basename "$RUN")"
step "To bring the results back to the laptop"
echo "   tar czf ~/$NAME-from-pc.tar.gz -C \"$(pwd)\" $RUN"
echo "   then copy that file to the laptop and unpack it into tools/handwriting (it replaces $RUN)."
