"""v2 segmentation -- letters from the attention window -- measured against synthetic ground truth (§5.4).

    .venv/Scripts/python -I segment.py runs/v1-3x256/epoch006.pt --samples 400 --sheet runs/v1-3x256/segment

For each held-out sample (the validation writers, never trained on), the network is run
teacher-forced over the ink with its text; phi(t, u) is where the window was when point t was
written. A monotonic Viterbi pass -- the same rule as Kotlin's `Alignment.letters` -- turns that
into one character per point, and every point is scored against the character synth.py drew it
for.

Three numbers, because one would flatter:
  - accuracy over every point;
  - accuracy over the points written in order -- an i-dot or t-bar added after the word is where a
    monotonic path is wrong by construction (synth.py delays them on purpose, as hands do);
  - the same for a naive baseline that shares the points out evenly over the letters, which is what
    v2 has to beat to be worth anything.

A synthetic witness is a generous one (M7's lesson). The real-ink check is the user's own words, on a
sheet, by eye -- `HandwritingSegmentationReport` in :app does that through Kotlin.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import random
import sys

import numpy as np
import torch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import model as M  # noqa: E402
import sample as S  # noqa: E402
import sheets  # noqa: E402


def viterbi(phi: np.ndarray) -> np.ndarray:
    """Kotlin's `Alignment.letters`: the monotonic path maximising summed log attention, a -> ... -> last."""
    n, u = phi.shape
    if n < u:
        return np.minimum(np.arange(n) * u // max(n, 1), u - 1)
    logp = np.log(np.maximum(phi, 1e-12))
    score = np.full((n, u), -np.inf)
    came = np.zeros((n, u), dtype=bool)
    score[0, 0] = logp[0, 0]
    for i in range(1, n):
        stay = score[i - 1]
        step = np.concatenate([[-np.inf], score[i - 1, :-1]])
        came[i] = step > stay
        score[i] = np.maximum(stay, step) + logp[i]
        score[i, min(u, i + 1):] = -np.inf
    out = np.zeros(n, dtype=np.int64)
    k = u - 1
    for i in range(n - 1, -1, -1):
        out[i] = k
        if i > 0 and came[i, k]:
            k -= 1
    return out


def in_order(labels: np.ndarray) -> np.ndarray:
    """Points written in reading order: not part of a stroke that goes back to an earlier letter."""
    best = -1
    out = np.zeros(len(labels), dtype=bool)
    for i, c in enumerate(labels):
        out[i] = c >= best
        best = max(best, c)
    return out


def measure(checkpoint: str, prepared: str, n: int, seed: int = 1):
    net, meta = S.load(checkpoint)
    z = np.load(os.path.join(prepared, "prepared.npz"))
    off, toff = z["offsets"], z["text_offsets"]
    xy, eos, chars, text = z["offsets_xy"], z["eos"], z["chars"], z["text"]
    alphabet = meta["alphabet"]
    val = [k for k in range(len(off) - 1) if bool(z["is_val"][k])]
    random.Random(seed).shuffle(val)
    style = net.style.weight.mean(dim=0, keepdim=True)
    totals = {"all": [0, 0], "ordered": [0, 0], "naive_all": [0, 0], "naive_ordered": [0, 0]}
    shown = []
    with torch.no_grad():
        for k in val[:n]:
            a, b = int(off[k]), int(off[k + 1])
            t = "".join(alphabet[c] for c in text[toff[k]:toff[k + 1]])
            pts = np.concatenate([xy[a:b], eos[a:b, None].astype(np.float32)], axis=1)
            x = torch.zeros(1, len(pts), 3)
            x[0, 0, 2] = 1.0
            x[0, 1:] = torch.from_numpy(pts[:-1])
            hot, lens = M.onehot_texts([t], alphabet)
            _, _, phi = net(x, hot, lens, style, keep_phi=True)
            p = phi[0, :, :len(t)].numpy()
            p = p / np.maximum(p.sum(axis=1, keepdims=True), 1e-12)
            got = viterbi(p)
            truth = chars[a:b].astype(np.int64)
            naive = np.minimum(np.arange(len(truth)) * len(t) // len(truth), len(t) - 1)
            order = in_order(truth)
            for key, guess, mask in (("all", got, None), ("ordered", got, order),
                                     ("naive_all", naive, None), ("naive_ordered", naive, order)):
                m = np.ones(len(truth), dtype=bool) if mask is None else mask
                totals[key][0] += int((guess[m] == truth[m]).sum())
                totals[key][1] += int(m.sum())
            if len(shown) < 24:
                shown.append((t, pts, got, truth))
    result = {k: round(v[0] / max(v[1], 1), 4) for k, v in totals.items()}
    result["points"] = totals["all"][1]
    result["samples"] = min(n, len(val))
    return result, shown, meta


PALETTE = [(200, 40, 40), (40, 110, 200), (30, 150, 60), (200, 120, 0), (130, 50, 170), (0, 150, 160)]


def draw(shown, meta, path):
    """Each sample twice: coloured by its true letters, then by v2's -- the same colour is the same letter."""
    from PIL import Image, ImageDraw
    mean, std = np.array(meta["norm_mean"]), np.array(meta["norm_std"])
    scale, row = 28, 120
    img = Image.new("RGB", (1500, row * len(shown) + 20), "white")
    d = ImageDraw.Draw(img)
    for r, (text, pts, got, truth) in enumerate(shown):
        p = np.cumsum(pts[:, :2] * std + mean, axis=0)
        base = 20 + r * row + 70
        d.text((5, base - 60), text[:30], fill=(80, 80, 80))
        for col, labels in ((0, truth), (1, got)):
            ox = 220 + col * 640
            for i in range(1, len(p)):
                if pts[i - 1, 2] > 0.5:
                    continue
                c = PALETTE[int(labels[i]) % len(PALETTE)]
                d.line([(ox + p[i - 1, 0] * scale, base - p[i - 1, 1] * scale), (ox + p[i, 0] * scale, base - p[i, 1] * scale)],
                       fill=c, width=2)
        d.text((220, 20 + r * row + 2), "truth", fill=(150, 150, 150))
        d.text((860, 20 + r * row + 2), "v2 (attention + Viterbi)", fill=(150, 150, 150))
    img.save(path)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("checkpoint")
    ap.add_argument("--prepared", default=os.path.join(HERE, "data", "prepared-v1"))
    ap.add_argument("--samples", type=int, default=400)
    ap.add_argument("--sheet")
    a = ap.parse_args(argv)
    result, shown, meta = measure(a.checkpoint, a.prepared, a.samples)
    print(json.dumps(result))
    if a.sheet:
        draw(shown, meta, a.sheet + ".png")
        with open(a.sheet + ".json", "w", encoding="utf-8") as f:
            json.dump(result, f, indent=1)


if __name__ == "__main__":
    main()
