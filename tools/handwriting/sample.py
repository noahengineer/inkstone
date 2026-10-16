"""Contact sheets from a trained model (docs/HANDWRITING-SYNTHESIS.md §7.3, §11).

    .venv/Scripts/python -I sample.py runs/v1-3x256/epoch003.pt --out runs/v1-3x256/sheets/e003

For each sentence, N samples at each neatness (Graves's bias b), written as an SVG -- a renderer
that is not androidx.ink, which is M4's lesson -- and as a PNG for looking at. Each row is labelled
with whether the window reached the end of the text (`ok`) or the runaway guard stopped it (`RUN`).
"""

from __future__ import annotations

import argparse
import json
import os
import sys

import numpy as np
import torch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import model as M  # noqa: E402
import sheets  # noqa: E402

SENTENCES = ["the quick brown fox", "Electric charge is conserved", "buzzing jazz (42%)", "cation"]
BIASES = [0.0, 0.75, 1.5]


def load(path: str):
    ck = torch.load(path, weights_only=True, map_location="cpu")
    arch = M.Arch(**ck["arch"])
    net = M.Synthesis(arch)
    net.load_state_dict(ck["model"])
    net.eval()
    run = os.path.dirname(os.path.abspath(path))
    with open(os.path.join(run, "data.json"), encoding="utf-8") as f:
        meta = json.load(f)["meta"]
    return net, meta


def strokes_of(offsets: torch.Tensor, meta) -> list[list[tuple[float, float]]]:
    """Normalised model offsets -> pen-down polylines in x-heights, y up."""
    if len(offsets) == 0:
        return []
    mean = np.array(meta["norm_mean"])
    std = np.array(meta["norm_std"])
    o = offsets[:, :2].numpy() * std + mean
    pts = np.cumsum(o, axis=0)
    out, cur = [], []
    for (x, y), lift in zip(pts.tolist(), offsets[:, 2].tolist()):
        cur.append((x, y))
        if lift > 0.5:
            out.append(cur)
            cur = []
    if cur:
        out.append(cur)
    return out


def write_svg(rows, path, scale=30.0):
    lines, y = [], 40.0
    width = 0.0
    for label, strokes in rows:
        lines.append(f'<text x="8" y="{y:.1f}" font-size="11" fill="#666">{label}</text>')
        for s in strokes:
            pts = " ".join(f"{200 + x * scale:.2f},{y - py * scale:.2f}" for x, py in s)
            width = max(width, max(200 + x * scale for x, _ in s))
            lines.append(f'<polyline points="{pts}" fill="none" stroke="black" stroke-width="1.6" '
                         f'stroke-linecap="round" stroke-linejoin="round"/>')
        y += 3.2 * scale
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width + 40:.0f}" height="{y:.0f}">'
           f'<rect width="100%" height="100%" fill="white"/>' + "".join(lines) + "</svg>")
    with open(path, "w", encoding="utf-8") as f:
        f.write(svg)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("checkpoint")
    ap.add_argument("--out", required=True)
    ap.add_argument("--n", type=int, default=4)
    ap.add_argument("--sentences", nargs="*", default=SENTENCES)
    ap.add_argument("--biases", nargs="*", type=float, default=BIASES)
    a = ap.parse_args(argv)
    net, meta = load(a.checkpoint)
    style = net.style.weight.mean(dim=0, keepdim=True)
    rows = []
    finished = total = 0
    for bias in a.biases:
        for sentence in a.sentences:
            results = M.sample(net, [sentence] * a.n, style, bias=bias, seed=int(bias * 100) + len(sentence),
                              norm=(meta["norm_mean"], meta["norm_std"]))
            for k, (offs, done) in enumerate(results):
                finished += int(done)
                total += 1
                rows.append((f"b={bias} {'ok' if done else 'RUN'} {sentence[:18]}", strokes_of(offs, meta)))
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    write_svg(rows, a.out + ".svg")
    sheets.draw_rows(rows, a.out + ".png", scale=24, label_width=200)
    print(f"{finished}/{total} reached the end of their text -> {a.out}.svg, {a.out}.png")


if __name__ == "__main__":
    main()
