"""PNG contact sheets of synthetic handwriting, for looking at a corpus before training on it.

    .venv/Scripts/python -I corpussheets.py corpus data/synth-v2 --out runs/v2-maths-3x256/sheets/sample --n 40
    .venv/Scripts/python -I corpussheets.py corpus data/synth-v2 --match "\\d\\.\\d" --out .../decimals
    .venv/Scripts/python -I corpussheets.py write --corpus v2 --text "x = 0.25 + 1.75 - 3/8 = 1.625" "θ=30°" \
        --out .../maths --per-face 2

`corpus` draws samples already written (each row labelled with its writer's face); `write` writes the
given texts afresh with writers of every face, so a symbol can be compared across all of them.
"""

from __future__ import annotations

import argparse
import json
import os
import random
import re
import sys

import numpy as np
from PIL import ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import sheets  # noqa: E402
import synth  # noqa: E402


def label_font():
    for path in (r"C:\Windows\Fonts\arial.ttf", "/usr/share/fonts/TTF/DejaVuSans.ttf",
                 "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        if os.path.exists(path):
            return ImageFont.truetype(path, 12)
    return None


def strokes_of(xy, eos):
    out, cur = [], []
    for (x, y), lift in zip(xy.tolist(), eos.tolist()):
        cur.append((x, y))
        if lift:
            out.append(cur)
            cur = []
    if cur:
        out.append(cur)
    return out


def from_corpus(path, n, match, seed):
    z = np.load(os.path.join(path, "corpus.npz"))
    with open(os.path.join(path, "corpus.json"), encoding="utf-8") as f:
        styles = {w["id"]: w for w in json.load(f)["writer_styles"]}
    offs, xy, eos, text, writer = z["offsets"], z["xy"], z["eos"], z["text"], z["writer"]
    pattern = re.compile(match) if match else None
    idx = [k for k in range(len(text)) if pattern is None or pattern.search(str(text[k]))]
    random.Random(seed).shuffle(idx)
    rows = []
    for k in idx[:n]:
        a, b = int(offs[k]), int(offs[k + 1])
        font = styles[int(writer[k])]["font"]
        rows.append((f"{font[:14]}: {text[k]}", strokes_of(xy[a:b], eos[a:b])))
    return rows


def fresh(texts_, corpus, per_face, seed):
    faces = synth._faces()
    by_face = {}
    wid = 0
    while any(len(by_face.get(f, [])) < per_face for f in faces) and wid < 20000:
        w = synth.make_writer(wid, faces, seed)
        by_face.setdefault(w.font, [])
        if len(by_face[w.font]) < per_face:
            by_face[w.font].append(w)
        wid += 1
    rows = []
    for t in texts_:
        for font in sorted(by_face):
            for w in by_face[font]:
                glyphs = None if corpus == "v1" else synth.glyphs_for(w, seed)
                s = synth.write_sample(t, w, random.Random(w.id * 31 + len(t)), glyphs)
                tag = "" if glyphs is None else f"/{glyphs.prefer[0]}"
                rows.append((f"{font[:14]}{tag}: {t}", strokes_of(s.xy, s.eos)))
    return rows


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="mode", required=True)
    c = sub.add_parser("corpus")
    c.add_argument("path")
    c.add_argument("--n", type=int, default=40)
    c.add_argument("--match", default="")
    w = sub.add_parser("write")
    w.add_argument("--text", nargs="+", required=True)
    w.add_argument("--corpus", default="v2", choices=["v1", "v2"])
    w.add_argument("--per-face", type=int, default=1)
    for p in (c, w):
        p.add_argument("--out", required=True, help="path without extension; .png is added")
        p.add_argument("--seed", type=int, default=2)
        p.add_argument("--scale", type=float, default=26.0)
    a = ap.parse_args(argv)
    rows = (from_corpus(a.path, a.n, a.match, a.seed) if a.mode == "corpus"
            else fresh(a.text, a.corpus, a.per_face, a.seed))
    os.makedirs(os.path.dirname(os.path.abspath(a.out)) or ".", exist_ok=True)
    sheets.draw_rows(rows, a.out + ".png", scale=a.scale, label_width=330, font=label_font(), label_chars=52)
    print(f"{len(rows)} rows -> {a.out}.png")


if __name__ == "__main__":
    main()
