"""A licensed corpus -> the arrays training reads (docs/HANDWRITING-SYNTHESIS.md §7.3).

    .venv/Scripts/python -I prepare.py data/synth-v1 --out data/prepared-v1

Any corpus in synth.py's format is accepted -- synthetic, or later a licensed or consented real one
(§7.2's upgrade paths) -- and every one must carry its licences. **A corpus with no licence entry,
or with a source whose licence is not on the commercial allow-list, is refused (D13).** The
provenance travels on into the prepared set and from there into the .ihwm header (export.py).

What it writes:
  - each sample as model input: offsets from the previous point (the first from the frame's
    origin), normalised to mean 0 and standard deviation 1 (Graves §4), plus the pen-lift bit;
  - its text as indices into the alphabet, and each point's character (for v2 segmentation);
  - a writer-disjoint validation split, so a validation loss means "a hand it has not seen".
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import fontskel  # noqa: E402

# A corpus's own licence: ours (synthetic) or the user's own writing, plus anything a font may carry.
CORPUS_LICENCES = frozenset({"LicenseRef-Inkstone-Synthetic", "LicenseRef-User-Own"}) | fontskel.COMMERCIAL_LICENCES
FORMAT = "inkstone-hw-prepared-1"


class LicenceError(fontskel.LicenceError):
    pass


def check_licences(manifest: dict) -> list[dict]:
    """The corpus's provenance, or LicenceError naming what is missing or not allowed."""
    licence = manifest.get("licence")
    if not licence:
        raise LicenceError(f"corpus {manifest.get('name', '?')} has no licence entry")
    if licence not in CORPUS_LICENCES:
        raise LicenceError(f"corpus {manifest.get('name', '?')}: licence {licence} is not on the commercial allow-list")
    sources = manifest.get("sources")
    if not sources:
        raise LicenceError(f"corpus {manifest.get('name', '?')} lists no sources")
    for s in sources:
        if not s.get("licence"):
            raise LicenceError(f"source {s.get('id', '?')} of {manifest.get('name', '?')} has no licence entry")
        if s["licence"] not in CORPUS_LICENCES:
            raise LicenceError(f"source {s.get('id', '?')}: licence {s['licence']} is not on the commercial allow-list")
        if not s.get("sha256"):
            raise LicenceError(f"source {s.get('id', '?')} has no sha256")
    return sources


def load_corpus(path: str):
    meta_path = os.path.join(path, "corpus.json")
    if not os.path.exists(meta_path):
        raise LicenceError(f"{path} has no corpus.json, so no licence entry")
    with open(meta_path, encoding="utf-8") as f:
        manifest = json.load(f)
    check_licences(manifest)
    data = np.load(os.path.join(path, "corpus.npz"))
    return manifest, data


def offsets_of(xy: np.ndarray) -> np.ndarray:
    """Offset to each point from the one before; the first from the frame's origin."""
    out = np.empty_like(xy)
    out[0] = xy[0]
    out[1:] = xy[1:] - xy[:-1]
    return out


def prepare(corpus_dirs: list[str], out_dir: str, val_fraction: float = 0.05, seed: int = 1) -> dict:
    manifests, xs, eos, chars, texts, writers, lengths = [], [], [], [], [], [], []
    alphabet = None
    writer_base = 0
    for d in corpus_dirs:
        manifest, data = load_corpus(d)
        if alphabet is None:
            alphabet = manifest["alphabet"]
        elif manifest["alphabet"] != alphabet:
            raise ValueError(f"{d}: alphabet differs from the first corpus")
        manifests.append(manifest)
        # An .npz inflates the whole array on every subscript: read each one exactly once.
        offs, all_xy, all_eos, all_chars = data["offsets"], data["xy"], data["eos"], data["chars"]
        all_text, all_writer = data["text"], data["writer"]
        for k in range(len(offs) - 1):
            a, b = int(offs[k]), int(offs[k + 1])
            xs.append(offsets_of(all_xy[a:b].astype(np.float64)))
            eos.append(all_eos[a:b])
            chars.append(all_chars[a:b])
            texts.append(str(all_text[k]))
            writers.append(int(all_writer[k]) + writer_base)
            lengths.append(b - a)
        writer_base = max(writers) + 1
    index = {ch: i for i, ch in enumerate(alphabet)}
    unique_writers = sorted(set(writers))
    rng = random.Random(seed)
    val_writers = set(rng.sample(unique_writers, max(1, int(len(unique_writers) * val_fraction))))
    is_val = np.array([w in val_writers for w in writers])
    train_offsets = np.concatenate([x for x, v in zip(xs, is_val) if not v])
    mean = train_offsets.mean(axis=0)
    std = train_offsets.std(axis=0)
    norm = [((x - mean) / std).astype(np.float32) for x in xs]
    text_idx = [np.array([index[c] for c in t], dtype=np.int16) for t in texts]
    offsets = np.concatenate([[0], np.cumsum(lengths)]).astype(np.int64)
    text_offsets = np.concatenate([[0], np.cumsum([len(t) for t in text_idx])]).astype(np.int64)
    os.makedirs(out_dir, exist_ok=True)
    np.savez(
        os.path.join(out_dir, "prepared.npz"),
        offsets_xy=np.concatenate(norm),
        eos=np.concatenate(eos).astype(np.uint8),
        chars=np.concatenate(chars).astype(np.int16),
        offsets=offsets,
        text=np.concatenate(text_idx),
        text_offsets=text_offsets,
        writer=np.array(writers, dtype=np.int32),
        is_val=is_val,
    )
    meta = {
        "format": FORMAT,
        "alphabet": alphabet,
        "norm_mean": [float(mean[0]), float(mean[1])],
        "norm_std": [float(std[0]), float(std[1])],
        "points_per_char": manifests[0].get("points_per_char"),
        "samples": len(xs),
        "train_samples": int((~is_val).sum()),
        "val_samples": int(is_val.sum()),
        "writers": len(unique_writers),
        "val_writers": sorted(val_writers),
        "datasets": [
            {"name": m["name"], "licence": m["licence"], "sources": m["sources"], "generator": m.get("generator")}
            for m in manifests
        ],
    }
    with open(os.path.join(out_dir, "prepared.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, indent=1)
    return meta


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("corpus", nargs="+")
    ap.add_argument("--out", required=True)
    ap.add_argument("--val", type=float, default=0.05)
    a = ap.parse_args(argv)
    m = prepare(a.corpus, a.out, a.val)
    print(f"{m['samples']} samples ({m['train_samples']} train / {m['val_samples']} val), "
          f"{m['writers']} writers, offsets mean {m['norm_mean']} std {m['norm_std']} -> {a.out}")


if __name__ == "__main__":
    main()
