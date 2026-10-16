"""Train the synthesis network on a prepared corpus (docs/HANDWRITING-SYNTHESIS.md §7.3).

    .venv/Scripts/python -I train.py data/prepared-v1 --run runs/v1 --layers 3 --cells 256 --epochs 30
    .venv/Scripts/python -I train.py data/prepared-v1 --run runs/v1 --resume

Runs on the CPU (Q4) or, with --device auto/cuda/xpu, a GPU -- `train-on-pc.ps1` sets that up on another
machine. Every file it writes holds CPU tensors, so a run trained on a GPU is exported and sampled
here unchanged. Checkpoints every --checkpoint-minutes and at each epoch's end, so a killed run
resumes where it stopped (`--resume`) having lost at most that long. The validation set is
writer-disjoint (prepare.py), so its loss is the loss on a hand the model has not seen. The curve
is written to <run>/curve.csv after every epoch and drawn to <run>/curve.png.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import random
import sys
import time

import numpy as np
import torch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import model as M  # noqa: E402


class Data:
    """A prepared set as per-sample tensors, and length-bucketed batches over it."""

    def __init__(self, path: str):
        with open(os.path.join(path, "prepared.json"), encoding="utf-8") as f:
            self.meta = json.load(f)
        z = np.load(os.path.join(path, "prepared.npz"))
        off, toff = z["offsets"], z["text_offsets"]
        xy, eos, text = z["offsets_xy"], z["eos"], z["text"]
        self.writer = z["writer"]
        self.is_val = z["is_val"]
        self.samples = []
        for k in range(len(off) - 1):
            a, b = int(off[k]), int(off[k + 1])
            pts = np.concatenate([xy[a:b], eos[a:b, None].astype(np.float32)], axis=1)
            self.samples.append((torch.from_numpy(pts), torch.from_numpy(text[toff[k]:toff[k + 1]].astype(np.int64))))
        self.A = len(self.meta["alphabet"])

    def indices(self, val: bool):
        return [i for i in range(len(self.samples)) if bool(self.is_val[i]) == val]

    def batches(self, idx, batch_size: int, rng: random.Random, shuffle: bool = True):
        """Batches of similar length, so padding wastes little of each step."""
        order = sorted(idx, key=lambda i: len(self.samples[i][0]))
        groups = [order[k:k + batch_size] for k in range(0, len(order), batch_size)]
        if shuffle:
            rng.shuffle(groups)
        return groups

    def collate(self, group):
        B = len(group)
        T = max(len(self.samples[i][0]) for i in group)
        U = max(len(self.samples[i][1]) for i in group)
        x = torch.zeros(B, T, 3)
        target = torch.zeros(B, T, 3)
        mask = torch.zeros(B, T)
        onehot = torch.zeros(B, U, self.A)
        lens = torch.zeros(B)
        for b, i in enumerate(group):
            pts, text = self.samples[i]
            n = len(pts)
            x[b, 0, 2] = 1.0
            x[b, 1:n] = pts[:n - 1]
            target[b, :n] = pts
            mask[b, :n] = 1.0
            onehot[b, torch.arange(len(text)), text] = 1.0
            lens[b] = len(text)
        writers = torch.tensor([int(self.writer[i]) for i in group], dtype=torch.long)
        return x, target, mask, onehot, lens, writers


def pick_device(name: str) -> torch.device:
    if name != "auto":
        return torch.device(name)
    if torch.cuda.is_available():
        return torch.device("cuda")
    if hasattr(torch, "xpu") and torch.xpu.is_available():
        return torch.device("xpu")
    return torch.device("cpu")


def on_cpu(value):
    """A state dict (or an optimizer's, nested) with every tensor moved to the CPU, for saving."""
    if torch.is_tensor(value):
        return value.detach().cpu()
    if isinstance(value, dict):
        return {k: on_cpu(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return type(value)(on_cpu(v) for v in value)
    return value


def evaluate(model, data, idx, batch_size, device):
    model.eval()
    total, points = 0.0, 0.0
    with torch.no_grad():
        for group in data.batches(idx, batch_size, random.Random(0), shuffle=False):
            x, target, mask, onehot, lens, writers = (t.to(device) for t in data.collate(group))
            # A validation writer was never in the table: use the mean style, as inference does.
            style = model.style.weight.mean(dim=0, keepdim=True).expand(len(group), -1)
            y, _, _ = model(x, onehot, lens, style)
            total += float(M.nll(y, target, mask, model.arch.mixtures)) * float(mask.sum())
            points += float(mask.sum())
    model.train()
    return total / max(points, 1.0)


def save(path, model, opt, epoch, batch_in_epoch, history, args):
    tmp = path + ".tmp"
    torch.save({"model": on_cpu(model.state_dict()), "opt": on_cpu(opt.state_dict()), "epoch": epoch,
                "batch": batch_in_epoch, "history": history, "arch": vars(model.arch), "args": vars(args)}, tmp)
    os.replace(tmp, path)  # atomic: a kill mid-save leaves the previous checkpoint (rule 10's habit)


def draw_curve(history, path):
    from PIL import Image, ImageDraw
    if not history:
        return
    w, h, pad = 900, 420, 50
    img = Image.new("RGB", (w, h), "white")
    d = ImageDraw.Draw(img)
    vals = [r["train"] for r in history] + [r["val"] for r in history]
    lo, hi = min(vals), max(vals)
    span = max(hi - lo, 1e-6)
    n = max(len(history) - 1, 1)
    def pt(i, v):
        return pad + (w - 2 * pad) * i / n, h - pad - (h - 2 * pad) * (v - lo) / span
    for key, colour in (("train", (40, 90, 200)), ("val", (200, 70, 40))):
        pts = [pt(i, r[key]) for i, r in enumerate(history)]
        if len(pts) > 1:
            d.line(pts, fill=colour, width=2)
        for p in pts:
            d.ellipse([p[0] - 3, p[1] - 3, p[0] + 3, p[1] + 3], fill=colour)
    d.text((pad, 10), f"NLL per point: train (blue) / val (red), epochs 1..{len(history)}; {lo:.3f}..{hi:.3f}", fill="black")
    img.save(path)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("data")
    ap.add_argument("--run", required=True)
    ap.add_argument("--layers", type=int, default=3)
    ap.add_argument("--cells", type=int, default=256)
    ap.add_argument("--window", type=int, default=10)
    ap.add_argument("--mixtures", type=int, default=20)
    ap.add_argument("--style-dim", type=int, default=32)
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--clip", type=float, default=5.0)
    ap.add_argument("--threads", type=int, default=0)
    ap.add_argument("--device", default="cpu", help="cpu, cuda, xpu, or auto (the best one present)")
    ap.add_argument("--max-batches", type=int, default=0, help="stop each epoch after this many (timing)")
    ap.add_argument("--checkpoint-minutes", type=float, default=10.0)
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--seed", type=int, default=1)
    args = ap.parse_args(argv)

    if args.threads:
        torch.set_num_threads(args.threads)
    device = pick_device(args.device)
    torch.manual_seed(args.seed)
    os.makedirs(args.run, exist_ok=True)
    data = Data(args.data)
    ckpt_path = os.path.join(args.run, "checkpoint.pt")
    start_epoch, skip, history = 0, 0, []
    if args.resume and os.path.exists(ckpt_path):
        ck = torch.load(ckpt_path, weights_only=True, map_location=device)
        arch = M.Arch(**ck["arch"])
        model = M.Synthesis(arch)
        model.load_state_dict(ck["model"])
        model.to(device)
        opt = torch.optim.Adam(model.parameters(), lr=args.lr)
        opt.load_state_dict(ck["opt"])
        for group in opt.param_groups:
            group["lr"] = args.lr  # a resumed run may continue at a lower rate
        start_epoch, skip, history = ck["epoch"], ck["batch"], ck["history"]
        print(f"resumed at epoch {start_epoch + 1}, batch {skip}")
    else:
        arch = M.Arch(alphabet=data.meta["alphabet"], layers=args.layers, cells=args.cells, window=args.window,
                      mixtures=args.mixtures, style_dim=args.style_dim, writers=int(data.writer.max()) + 1,
                      kappa_scale=float(data.meta.get("points_per_char") or 14))
        model = M.Synthesis(arch).to(device)
        opt = torch.optim.Adam(model.parameters(), lr=args.lr)
    params = sum(p.numel() for p in model.parameters())
    style_params = model.style.weight.numel()
    print(f"model {arch.layers}x{arch.cells}: {params} parameters ({params - style_params} without the writer table), "
          f"device {device}, threads {torch.get_num_threads()}")
    with open(os.path.join(args.run, "data.json"), "w", encoding="utf-8") as f:
        json.dump({"data": os.path.abspath(args.data), "meta": data.meta}, f, indent=1)

    train_idx, val_idx = data.indices(False), data.indices(True)
    last_save = time.time()
    for epoch in range(start_epoch, args.epochs):
        rng = random.Random(args.seed * 1000 + epoch)
        groups = data.batches(train_idx, args.batch, rng)
        if args.max_batches:
            groups = groups[:args.max_batches]
        t0 = time.time()
        total, points = 0.0, 0.0
        for k, group in enumerate(groups):
            if k < skip:
                continue
            x, target, mask, onehot, lens, writers = (t.to(device) for t in data.collate(group))
            y, _, _ = model(x, onehot, lens, model.style(writers))
            loss = M.nll(y, target, mask, arch.mixtures)
            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), args.clip)
            opt.step()
            total += loss.item() * float(mask.sum())
            points += float(mask.sum())
            if k % 50 == 0:
                el = time.time() - t0
                print(f"epoch {epoch + 1} batch {k + 1}/{len(groups)} loss {loss.item():.4f} "
                      f"{el:.0f}s elapsed, ~{el / (k + 1 - skip) * (len(groups) - k - 1):.0f}s left", flush=True)
            if time.time() - last_save > args.checkpoint_minutes * 60:
                save(ckpt_path, model, opt, epoch, k + 1, history, args)
                last_save = time.time()
        skip = 0
        seconds = time.time() - t0
        val = evaluate(model, data, val_idx, args.batch, device)
        row = {"epoch": epoch + 1, "train": total / max(points, 1.0), "val": val, "seconds": round(seconds, 1),
               "batches": len(groups)}
        history.append(row)
        print(json.dumps(row), flush=True)
        save(ckpt_path, model, opt, epoch + 1, 0, history, args)
        torch.save({"model": on_cpu(model.state_dict()), "arch": vars(arch)},
                   os.path.join(args.run, f"epoch{epoch + 1:03d}.pt"))
        last_save = time.time()
        with open(os.path.join(args.run, "curve.csv"), "w", newline="", encoding="utf-8") as f:
            wr = csv.DictWriter(f, fieldnames=list(row))
            wr.writeheader()
            wr.writerows(history)
        draw_curve(history, os.path.join(args.run, "curve.png"))


if __name__ == "__main__":
    main()
