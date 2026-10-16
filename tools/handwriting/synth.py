"""Synthetic handwriting from licensed single-line fonts (docs/HANDWRITING-SYNTHESIS.md §7.2, §7.3).

    .venv/Scripts/python -I synth.py --writers 4000 --per-writer 12 --out data/synth-v1
    .venv/Scripts/python -I synth.py --corpus v2 --writers 5000 --per-writer 14 --seed 2 --out data/synth-v2

`--corpus` picks the alphabet and the text (texts.CORPORA). v1 is the corpus the shipped model was
trained on and is reproduced exactly; v2 adds maths (texts.NEW_V2), whose characters a writer's face
may lack and then borrows, scaled to the writer, from the Hershey symbol sets (mathglyphs.py).

Every synthetic *writer* is a font plus a style drawn at random: cap and digit height, width, a
small residual slant (the model works in the deslanted frame, §5.2), letter and word spacing, how
often letters are joined (a script face keeps its own joins, a print face sometimes gets a bridge),
whether i-dots and t-bars wait until the word is done, and how the hand moves -- a sigma-lognormal
timing whose pulses are perturbed per sample, so two samples of one letter differ the way a hand's
do (sigmalognormal.py).

Every output point knows which character of its text it belongs to. That is the ground truth v2
segmentation is measured against (§5.4).

The corpus manifest lists every font that was read, with its licence, attribution and SHA-256;
prepare.py refuses a corpus without one, and export.py copies it into the .ihwm header (D13).
"""

from __future__ import annotations

import argparse
import json
import math
import multiprocessing
import os
import random
import statistics
import subprocess
import sys
import time
from dataclasses import asdict, dataclass

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import fontskel  # noqa: E402
import mathglyphs  # noqa: E402
import sigmalognormal as sl  # noqa: E402
import texts  # noqa: E402

CORPUS_LICENCE = "LicenseRef-Inkstone-Synthetic"
FORMAT = "inkstone-hw-corpus-1"

# How often each face is a writer's face. The clean geometric sans faces are least like a hand.
FONT_WEIGHTS = {
    "hershey-futural": 0.5, "relief-singleline": 0.5, "hershey-scripts": 1.0, "hershey-cursive": 0.7,
    "ems-casualhand": 1.0, "ems-tech": 1.0, "ems-pancakes": 1.0, "ems-delight": 1.0, "ems-neato": 1.0,
    "ems-felix": 0.7, "ems-capitol": 0.8,
}

# Letters whose short second strokes a hand often adds after the whole word: dots and bars.
MARK_LETTERS = "ijtfx"
POINTS_PER_CHAR = 14
JOIN_TOLERANCE = 0.15


@dataclass
class Writer:
    id: int
    font: str
    width: float
    slant: float
    cap_height: float
    digit_height: float
    letter_gap: float
    word_gap: float
    keep_joins: float
    bridges: float
    delay_marks: float
    mu: float
    sigma: float
    gap: float
    penup: float
    shape_noise: float
    letter_jitter: float
    baseline_jitter: float
    drift: float


def make_writer(writer_id: int, fonts: dict, seed: int) -> Writer:
    rng = random.Random(seed * 1_000_003 + writer_id)
    ids = sorted(fonts)
    font = rng.choices(ids, weights=[FONT_WEIGHTS.get(i, 1.0) for i in ids], k=1)[0]
    script = fonts[font].entry.style == "script"
    return Writer(
        id=writer_id,
        font=font,
        width=rng.uniform(0.85, 1.2),
        slant=math.radians(rng.gauss(0.0, 3.0)),
        cap_height=rng.uniform(1.45, 2.05),
        digit_height=rng.uniform(1.3, 1.85),
        letter_gap=rng.uniform(0.04, 0.32),
        word_gap=rng.uniform(0.6, 1.3),
        keep_joins=rng.uniform(0.75, 1.0) if script else 0.0,
        bridges=0.0 if script else (rng.uniform(0.15, 0.7) if rng.random() < 0.3 else 0.0),
        delay_marks=rng.uniform(0.3, 1.0),
        mu=rng.uniform(-1.9, -1.4),
        sigma=rng.uniform(0.2, 0.34),
        gap=rng.uniform(0.3, 0.7),
        penup=rng.uniform(0.05, 0.2),
        shape_noise=rng.uniform(0.35, 1.0),
        letter_jitter=rng.uniform(0.01, 0.05),
        baseline_jitter=rng.uniform(0.01, 0.05),
        drift=rng.uniform(0.0, 0.08),
    )


def maths_preference(writer_id: int, seed: int) -> str:
    """Corpus v2: which family a writer borrows maths glyphs from when both have one. A stream of its
    own, so a v2 writer's style is the v1 writer's with the same id and seed, draw for draw."""
    return "hershey" if random.Random(seed * 1_000_003 + writer_id + 500_000_000_000).random() < 0.6 else "relief"


# --- Font metrics the writers rescale against -----------------------------------------------------


def _median_top(font, chars):
    tops = [max(p[1] for s in font.glyphs[c].strokes for p in s) for c in chars if c in font.glyphs and font.glyphs[c].strokes]
    return statistics.median(tops) if tops else None


class Face:
    """A loaded font plus the heights its capitals and digits are rescaled from."""

    def __init__(self, font):
        self.font = font
        self.entry = font.entry
        self.cap = _median_top(font, "ABDEFHIKLMNPRTUVWXZ") or 1.6
        self.digit = _median_top(font, "0123456789") or 1.5

    def glyph(self, ch):
        return self.font.glyphs.get(ch)


# --- Layout: text -> pen-down chains with a character label per vertex ----------------------------


@dataclass
class Chain:
    pts: list            # [(x, y)] in x-heights, y up
    labels: list         # character index per vertex
    mark: bool = False   # a dot or bar that may be written after the word


def _bounds(strokes):
    xs = [p[0] for s in strokes for p in s]
    return (min(xs), max(xs)) if xs else (0.0, 0.0)


def _place_letter(glyph, scale, w: Writer, rng, x0):
    """The glyph's strokes at x0, scaled, with this letter's own jitter (scale, lift, rotation)."""
    js = scale * (1.0 + rng.gauss(0.0, w.letter_jitter))
    lift = rng.gauss(0.0, w.baseline_jitter)
    rot = rng.gauss(0.0, math.radians(1.0))
    c, s = math.cos(rot), math.sin(rot)
    out = []
    for stroke in glyph.strokes:
        pts = []
        for x, y in stroke:
            x, y = x * js * w.width, y * js
            pts.append((x0 + x * c - y * s, lift + x * s + y * c))
        out.append(pts)
    return out


def _is_mark(ch, stroke, strokes):
    if ch not in MARK_LETTERS or len(strokes) < 2:
        return False
    longest = max(sl_length(s) for s in strokes)
    return sl_length(stroke) < longest and sl_length(stroke) < 0.75


def sl_length(s):
    return sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(s, s[1:]))


def _bridge(a, b):
    """A short curve from exit a to entry b, lifting a little as a connecting stroke does."""
    dx = b[0] - a[0]
    lift = 0.12 + 0.1 * max(0.0, b[1] - a[1])
    c1 = (a[0] + 0.35 * dx, a[1] + lift)
    c2 = (b[0] - 0.35 * dx, b[1] - 0.05)
    out = []
    for k in range(1, 6):
        t = k / 6
        u = 1 - t
        out.append((
            u ** 3 * a[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t ** 3 * b[0],
            u ** 3 * a[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t ** 3 * b[1],
        ))
    return out


def layout(text: str, w: Writer, face: Face, fallback: dict, rng, glyphs=None) -> list[Chain]:
    """Pen-down chains in writing order, words left to right, with joins and delayed marks applied.

    `glyphs` (a mathglyphs.Glyphs) resolves each character for corpus v2; without it every character
    resolves as corpus v1 always has, and v1's output is unchanged draw for draw.
    """
    script = face.entry.style == "script"
    chains: list[Chain] = []
    x = 0.0
    index = 0
    words = text.split(" ")
    for wi, word in enumerate(words):
        if not word:
            index += 1
            continue
        word_chains: list[Chain] = []
        marks: list[Chain] = []
        prev_body: Chain | None = None
        prev_right = None
        prev_own = True
        for k, ch in enumerate(word):
            ci = index + k
            if glyphs is None:
                f = face if face.glyph(ch) else fallback["script" if script else "print"]
                g = f.glyph(ch)
                own = f is face
            else:
                g, scale, own = glyphs.resolve(ch, w)
            if g is None or not g.strokes:
                x += 0.5 * w.width
                prev_body, prev_right = None, None
                continue
            if glyphs is None:
                scale = w.cap_height / f.cap if ch.isupper() else w.digit_height / f.digit if ch.isdigit() else 1.0
            if script and own:
                x0 = x
                x = x0 + g.advance * scale * w.width
            else:
                lo, hi = _bounds(g.strokes)
                gap = w.letter_gap if prev_right is not None else 0.0
                x0 = (prev_right if prev_right is not None else x) + gap - lo * scale * w.width
                x = x0 + hi * scale * w.width
            strokes = _place_letter(g, scale, w, rng, x0)
            if glyphs is not None and prev_right is not None:
                clear = mathglyphs.clearance(word[k - 1], ch, prev_own, own, w.letter_gap)
                if clear is not None:
                    need = prev_right + clear - min(p[0] for s in strokes for p in s)
                    if need > 0:
                        strokes = [[(px + need, py) for px, py in s] for s in strokes]
                        x += need
            prev_own = own
            prev_right = max(p[0] for s in strokes for p in s)
            body = [s for s in strokes if not _is_mark(ch, s, strokes)]
            letter_marks = [Chain(s, [ci] * len(s), True) for s in strokes if _is_mark(ch, s, strokes)]
            body_chains = [Chain(s, [ci] * len(s)) for s in body]
            joined = False
            if prev_body is not None and body_chains:
                entry = body_chains[0]
                exit_pt, entry_pt = prev_body.pts[-1], entry.pts[0]
                close = math.hypot(exit_pt[0] - entry_pt[0], exit_pt[1] - entry_pt[1])
                if script and close < JOIN_TOLERANCE and rng.random() < w.keep_joins:
                    prev_body.pts += entry.pts
                    prev_body.labels += entry.labels
                    joined = True
                elif (not script and ch.islower() and word[k - 1].islower() and exit_pt[1] < 0.5
                      and -0.1 < entry_pt[1] < 1.15 and -0.3 < entry_pt[0] - exit_pt[0] < 1.0
                      and rng.random() < w.bridges):
                    bridge = _bridge(exit_pt, entry_pt)
                    half = len(bridge) // 2
                    prev_body.pts += bridge + entry.pts
                    prev_body.labels += [ci - 1] * half + [ci] * (len(bridge) - half) + entry.labels
                    joined = True
            rest = body_chains[1:] if joined else body_chains
            word_chains += rest
            if rest:
                prev_body = rest[-1]
            elif not joined:
                prev_body = None
            if rng.random() < w.delay_marks:
                marks += letter_marks
            else:
                word_chains += letter_marks
        chains += word_chains + marks
        index += len(word) + 1
        gap = w.word_gap
        if glyphs is not None and any(c.isdigit() for c in word + "".join(words[wi + 1:wi + 2])):
            gap = max(gap, mathglyphs.NUMBER_GAP * w.digit_height)
        x = (prev_right if prev_right is not None else x) + gap
    # The writer's residual slant and a slow baseline drift, applied to the whole line.
    tan_s = math.tan(w.slant)
    phase = rng.uniform(0, 2 * math.pi)
    span = max(1.0, x)
    for c in chains:
        c.pts = [(px + py * tan_s, py + w.drift * math.sin(2 * math.pi * px / (1.5 * span) + phase)) for px, py in c.pts]
    return chains


# --- Motion: chains -> time-sampled points ----------------------------------------------------------

_ERF = np.frompyfunc(math.erf, 1, 1)


def _positions(comps, times, start):
    """sigmalognormal.position for many times at once (same closed form, vectorised)."""
    t0 = np.array([c.t0 for c in comps])
    D = np.array([c.D for c in comps])
    mu = np.array([c.mu for c in comps])
    sig = np.array([c.sigma for c in comps])
    ts = np.array([c.theta_s for c in comps])
    te = np.array([c.theta_e for c in comps])
    dt = times[:, None] - t0[None, :]
    safe = np.where(dt > 0, dt, 1.0)
    lam = 0.5 * (1.0 + _ERF((np.log(safe) - mu) / (sig * math.sqrt(2.0))).astype(float))
    lam = np.where(dt > 0, lam, 0.0)
    dth = te - ts
    straight = np.abs(dth) < sl.STRAIGHT
    r = np.where(straight, 0.0, D / np.where(straight, 1.0, dth))
    a = ts + dth * lam
    dx = np.where(straight, D * lam * np.cos(ts), r * (np.sin(a) - np.sin(ts)))
    dy = np.where(straight, D * lam * np.sin(ts), r * (np.cos(ts) - np.cos(a)))
    progress = (D * lam).sum(axis=1) / max(D.sum(), 1e-12)
    return start[0] + dx.sum(axis=1), start[1] + dy.sum(axis=1), progress


@dataclass
class Sample:
    text: str
    writer: int
    xy: np.ndarray      # (N, 2) float32, x-heights, y up
    eos: np.ndarray     # (N,) uint8: the pen lifts after this point
    chars: np.ndarray   # (N,) int16: the character of `text` each point belongs to


def animate(chains: list[Chain], w: Writer, rng, text: str, careful: str = "") -> Sample | None:
    """Chains -> time-sampled points. A chain holding a character of `careful` (corpus v2: the square
    brackets) is written with the writer slowing at every corner, as at a cusp; otherwise its short
    arms are rounded off in the overlap of two pulses and `[` is drawn as `(`."""
    timing = sl.Timing(mu=w.mu, sigma=w.sigma, gap=w.gap)
    slow = sl.Timing(mu=w.mu, sigma=w.sigma, gap=max(w.gap, math.exp(sl.CUSP_SIGMAS * w.sigma)))
    planned = []
    for c in chains:
        if len(c.pts) < 2 or sl_length(c.pts) < 1e-3:
            # A dot drawn as one point is still a pen-down: give it a tiny tick.
            x, y = c.pts[0]
            c = Chain([(x, y), (x + 0.02, y - 0.02)], [c.labels[0]] * 2, c.mark)
        t = slow if careful and any(text[i] in careful for i in set(c.labels)) else timing
        comps = sl.perturb(sl.plan(c.pts, t), rng, w.shape_noise)
        if not comps:
            continue
        planned.append((c, comps, sl.duration(comps)))
    if not planned:
        return None
    visible = sum(1 for ch in text if ch != " ")
    total = sum(d for _, _, d in planned)
    dt_out = total / max(POINTS_PER_CHAR * visible, len(planned) * 2)
    xs, ys, eos, labels = [], [], [], []
    for c, comps, dur in planned:
        t_begin = min(cc.t0 for cc in comps)
        n = max(2, int(dur / dt_out) + 1)
        times = t_begin + np.linspace(0.0, dur, n)
        px, py, prog = _positions(comps, times, c.pts[0])
        # Each point's character: the skeleton vertex at the same fraction of the way along.
        seg = np.hypot(np.diff([p[0] for p in c.pts]), np.diff([p[1] for p in c.pts]))
        cum = np.concatenate([[0.0], np.cumsum(seg)])
        frac = cum / max(cum[-1], 1e-12)
        idx = np.clip(np.searchsorted(frac, prog, side="right") - 1, 0, len(c.labels) - 1)
        xs.append(px)
        ys.append(py)
        e = np.zeros(n, dtype=np.uint8)
        e[-1] = 1
        eos.append(e)
        labels.append(np.array(c.labels)[idx])
    xy = np.stack([np.concatenate(xs), np.concatenate(ys)], axis=1).astype(np.float32)
    return Sample(text, w.id, xy, np.concatenate(eos), np.concatenate(labels).astype(np.int16))


# --- The corpus ---------------------------------------------------------------------------------------

_FACES: dict | None = None


def _faces():
    global _FACES
    if _FACES is None:
        _FACES = {f.entry.id: Face(f) for f in fontskel.load_all()}
    return _FACES


def fallback_faces(faces):
    return {"print": faces["hershey-futural"], "script": faces["hershey-scripts"]}


def write_sample(text: str, w: Writer, rng, glyphs=None) -> Sample | None:
    faces = _faces()
    careful = "" if glyphs is None else mathglyphs.CAREFUL
    return animate(layout(text, w, faces[w.font], fallback_faces(faces), rng, glyphs), w, rng, text, careful)


def glyphs_for(w: Writer, seed: int):
    """Corpus v2's resolver for writer `w`: its face, v1's fallbacks, and where its maths comes from."""
    faces = _faces()
    return mathglyphs.Glyphs(faces[w.font], fallback_faces(faces), mathglyphs.lenders(faces),
                             maths_preference(w.id, seed))


def _writer_job(args):
    writer_id, per_writer, seed = args[:3]
    corpus = args[3] if len(args) > 3 else "v1"
    faces = _faces()
    w = make_writer(writer_id, faces, seed)
    rng = random.Random(seed * 7_919 + writer_id)
    phrase = texts.CORPORA[corpus][1]
    glyphs = None if corpus == "v1" else glyphs_for(w, seed)
    out = []
    for _ in range(per_writer):
        s = write_sample(phrase(rng), w, rng, glyphs)
        if s is not None and len(s.xy) >= 4:
            out.append(s)
    return w, out


def git_sha() -> str:
    try:
        return subprocess.run(["git", "-C", HERE, "rev-parse", "--short", "HEAD"], capture_output=True, text=True,
                              check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def generate(writers: int, per_writer: int, out_dir: str, seed: int, workers: int, corpus: str = "v1") -> dict:
    if corpus not in texts.CORPORA:
        raise ValueError(f"unknown corpus {corpus}; one of {sorted(texts.CORPORA)}")
    os.makedirs(out_dir, exist_ok=True)
    faces = _faces()
    started = time.time()
    jobs = [(i, per_writer, seed) if corpus == "v1" else (i, per_writer, seed, corpus) for i in range(writers)]
    if workers > 1:
        with multiprocessing.Pool(workers) as pool:
            results = pool.map(_writer_job, jobs, chunksize=8)
    else:
        results = [_writer_job(j) for j in jobs]
    samples = [s for _, ss in results for s in ss]
    lengths = np.array([len(s.xy) for s in samples], dtype=np.int64)
    offsets = np.concatenate([[0], np.cumsum(lengths)])
    np.savez_compressed(
        os.path.join(out_dir, "corpus.npz"),
        xy=np.concatenate([s.xy for s in samples]),
        eos=np.concatenate([s.eos for s in samples]),
        chars=np.concatenate([s.chars for s in samples]),
        offsets=offsets,
        writer=np.array([s.writer for s in samples], dtype=np.int32),
        text=np.array([s.text for s in samples]),
    )
    used = sorted({w.font for w, _ in results} | {"hershey-futural", "hershey-scripts"})
    entries = {i: faces[i].entry for i in used}
    if corpus != "v1":
        # Every font a v2 writer may borrow from is a source, whether or not this run happened to.
        lent = mathglyphs.lenders(faces)
        entries |= {i: lent[i].entry for i in lent}
        used = sorted(entries)
    manifest = {
        "format": FORMAT,
        "name": os.path.basename(os.path.normpath(out_dir)),
        "licence": CORPUS_LICENCE,
        "licence_note": "Generated by tools/handwriting/synth.py from the fonts below; no third-party handwriting.",
        "sources": [
            {k: getattr(entries[i], k) for k in ("id", "licence", "sha256", "source", "attribution")}
            | {"file": os.path.relpath(entries[i].path, HERE).replace("\\", "/")}
            for i in used
        ],
        "alphabet": texts.CORPORA[corpus][0],
        "points_per_char": POINTS_PER_CHAR,
        "frame": "x-heights, y up, baseline 0, deslanted (NormalFrame, docs/HANDWRITING-SYNTHESIS.md §5.2)",
        "seed": seed,
        "writers": writers,
        "samples": len(samples),
        "points": int(lengths.sum()),
        "generator": {"git": git_sha(), "script": "tools/handwriting/synth.py", "seconds": round(time.time() - started, 1)},
        "writer_styles": [asdict(w) for w, _ in results],
    }
    if corpus != "v1":
        counts = {ch: 0 for ch in manifest["alphabet"] if ch != " "}
        for s in samples:
            for ch in s.text:
                if ch != " ":
                    counts[ch] += 1
        words = [word for s in samples for word in s.text.split(" ") if word]
        manifest["corpus"] = corpus
        manifest["maths_preference"] = {str(w.id): maths_preference(w.id, seed) for w, _ in results}
        manifest["stats"] = {
            "words": len(words),
            "maths_words": sum(texts.is_maths_word(x) for x in words),
            "visible_chars": sum(counts.values()),
            "char_counts": counts,
        }
    with open(os.path.join(out_dir, "corpus.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, indent=1, ensure_ascii=corpus == "v1")
    return manifest


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--writers", type=int, default=200)
    ap.add_argument("--per-writer", type=int, default=20)
    ap.add_argument("--out", default=os.path.join(HERE, "data", "synth"))
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) - 2))
    ap.add_argument("--corpus", default="v1", choices=sorted(texts.CORPORA))
    a = ap.parse_args(argv)
    m = generate(a.writers, a.per_writer, a.out, a.seed, a.workers, a.corpus)
    print(f"{m['samples']} samples, {m['points']} points, {m['writers']} writers in {m['generator']['seconds']} s -> {a.out}")
    if "stats" in m:
        st = m["stats"]
        c = st["char_counts"]
        print(f"words {st['words']}, numeric or mathematical {st['maths_words'] / st['words']:.1%}; "
              f"{m['points'] / st['visible_chars']:.2f} points per character")
        print("new characters: " + "  ".join(f"{ch} {c[ch]}" for ch in texts.NEW_V2))
        print("every character, rarest first: " + "  ".join(f"{ch} {n}" for ch, n in sorted(c.items(), key=lambda kv: kv[1])))


if __name__ == "__main__":
    main()
