"""Live progress of a training run, read off its train.log -- on Linux, macOS or Windows.

    python3 progress.py                      # runs/v1-3x256, 15 epochs
    python3 progress.py runs/v1-3x256 15

Read-only: it never touches the trainer. Ctrl-C to stop. (progress.ps1 is the PowerShell twin.)
"""

import json
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
LINE = re.compile(r"epoch (\d+) batch (\d+)/(\d+) loss (\S+) (\d+)s elapsed")


def bar(fraction, width=40):
    full = round(max(0.0, min(1.0, fraction)) * width)
    return "[" + "#" * full + "." * (width - full) + "]"


def main():
    run = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "runs", "v1-3x256")
    epochs = int(sys.argv[2]) if len(sys.argv) > 2 else 15
    log = os.path.join(run, "train.log")
    while True:
        lines = open(log, encoding="utf-8", errors="replace").read().splitlines() if os.path.exists(log) else []
        progress = [m for m in (LINE.search(l) for l in lines) if m][-2:]
        done = [json.loads(l) for l in lines if l.startswith('{"epoch"')]
        out = [f"Handwriting model training ({run})", ""]
        if progress:
            m = progress[-1]
            ep, b, n = int(m.group(1)), int(m.group(2)), int(m.group(3))
            spb = 1.8
            if len(progress) == 2:
                db = b - int(progress[0].group(2))
                ds = int(m.group(5)) - int(progress[0].group(5))
                if db > 0 and ds > 0:
                    spb = ds / db
            ef = b / n
            rf = ((ep - 1) + ef) / epochs
            out.append(f"Epoch {ep:2d} of {epochs}   {bar(ef)} {ef * 100:5.1f}%   ~{(n - b) * spb / 60:.0f} min left")
            out.append(f"Whole run       {bar(rf)} {rf * 100:5.1f}%   "
                       f"~{(((epochs - ep) * n) + (n - b)) * spb / 3600:.1f} h left")
            out.append("")
            out.append(f"batch {b}/{n}, loss {m.group(4)}, {spb:.2f} s per batch")
        else:
            out.append("No progress lines yet.")
        out += ["", "Finished epochs (NLL per point; lower is better; validation = writers never seen):"]
        out += [f"  epoch {d['epoch']:2d}: train {d['train']:7.3f}   validation {d['val']:7.3f}   {d['seconds'] / 60:.0f} min"
                for d in done]
        out += ["", time.strftime("Updated %H:%M:%S; refreshes every 15 s. Ctrl-C to stop.")]
        print("\033[2J\033[H" + "\n".join(out), flush=True)
        time.sleep(15)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
