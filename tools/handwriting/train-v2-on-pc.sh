#!/usr/bin/env bash
# Train corpus v2 -- v1's alphabet plus maths -- from scratch on the Linux PC
# (docs/HANDWRITING-SYNTHESIS.md section 18.10 item 6; corpus v2 is texts.py's ALPHABET_V2 / phrase_v2).
#
#     cd tools/handwriting && bash train-v2-on-pc.sh
#
# Unpack transfer/inkstone-hw-train-v2.tar.gz into the PC's tools/handwriting first (the folder the
# v1 tarball went into is fine: nothing of v1's is replaced but train.py, model.py, prepare.py,
# progress.py, requirements.txt and train-on-pc.sh, all of which still run v1). Then this runs
# train-on-pc.sh with:
#   RUN=runs/v2-maths-3x256  DATA=data/prepared-v2  FRESH=1  EPOCHS=15
# i.e. 3 layers x 256 cells (as v1), batch 32, a checkpoint every 5 minutes, every epoch's checkpoint
# kept as epochNNN.pt (the app picks an epoch by its gate, not by validation loss). There is no
# checkpoint to resume the first time; after a Ctrl-C, running this again resumes the v2 run's own.
# The same venv as v1's is reused when it is already there.
set -euo pipefail
cd "$(dirname "$0")"
export RUN="runs/v2-maths-3x256"
export DATA="data/prepared-v2"
export FRESH=1
export EPOCHS="${EPOCHS:-15}"
exec bash train-on-pc.sh
