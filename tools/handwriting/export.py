"""Export a model to .ihwm, and the golden vectors Kotlin's parity tests read (§4.4, §7.3).

    .venv/Scripts/python -I export.py runs/v1-3x256/epoch012.pt \
        --out ../../app/src/main/assets/handwriting/base-v1.ihwm \
        --golden ../../app/src/test/resources/handwriting/base-v1.golden.json
    .venv/Scripts/python -I export.py --tiny       # the tiny model inside :core:handwriting's tests

The file (little-endian throughout):

    "IHWM"  u16 version  u16 0  u32 header-length  header (UTF-8 JSON)  zero padding to 4 bytes
    tensor data, each at the offset its header entry gives (from the start of the data)
    SHA-256 of every byte before it (32 bytes)

The header holds the architecture, the alphabet (index = position), the offsets' normalisation, the
default style vector, and the provenance: every dataset with its licence and SHA-256, every source
font with its licence and attribution, the git sha and the seed (D13). **An export refuses a
provenance whose licences are not all on the commercial allow-list**, before anything is written.

Weights are f16 and biases f32. The golden vectors are computed with the weights rounded to f16
exactly as the file holds them, so Kotlin's agreement to 1e-4 is a statement about its arithmetic,
not about rounding.

The file's SHA-256 (of the whole file, trailer included) is the model's identity; its first eight
hex digits are what `hw=…:n:<sha8>` carries.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import struct
import subprocess
import sys

import numpy as np
import torch

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)

import model as M  # noqa: E402
import prepare  # noqa: E402

MAGIC = b"IHWM"
VERSION = 1
FORMAT = "inkstone-hw-model"
TINY_KOTLIN = os.path.join(REPO, "core", "handwriting", "src", "commonTest", "kotlin", "dev", "inkstone", "core",
                           "handwriting", "neural", "TinyModelFixture.kt")


def check_provenance(datasets: list[dict]) -> None:
    """Every dataset and every one of its sources on the commercial allow-list, or LicenceError."""
    if not datasets:
        raise prepare.LicenceError("a model with no datasets has no provenance")
    for d in datasets:
        prepare.check_licences({"name": d.get("name"), "licence": d.get("licence"), "sources": d.get("sources")})


def f16_round(net: M.Synthesis) -> None:
    """Round every weight matrix to f16 in place -- what the file holds and Kotlin loads."""
    with torch.no_grad():
        for name, p in net.named_parameters():
            if p.dim() >= 2 and not name.startswith("style"):
                p.copy_(p.to(torch.float16).to(torch.float32))


def tensors_of(net: M.Synthesis, style: torch.Tensor):
    """(name, array, dtype) in file order; PyTorch's layout (out, in), gates in (i, f, g, o) order."""
    out = []
    for layer in range(net.arch.layers):
        w_ih, w_hh, b = net.layer_weights(layer)
        out += [(f"l{layer}.w_ih", w_ih, "f16"), (f"l{layer}.w_hh", w_hh, "f16"), (f"l{layer}.b", b, "f32")]
    out += [("window.w", net.window_layer.weight, "f16"), ("window.b", net.window_layer.bias, "f32")]
    out += [("out.w", net.output.weight, "f16"), ("out.b", net.output.bias, "f32")]
    out += [("style.default", style.reshape(-1), "f32")]
    return [(n, t.detach().numpy().astype(np.float32), d) for n, t, d in out]


def write_ihwm(path: str, header: dict, tensors) -> str:
    """Writes the file atomically and returns its SHA-256 (the model's identity)."""
    blobs, table, offset = [], [], 0
    for name, arr, dtype in tensors:
        data = arr.astype("<f2" if dtype == "f16" else "<f4").tobytes()
        table.append({"name": name, "shape": list(arr.shape), "dtype": dtype, "offset": offset, "length": len(data)})
        pad = (-len(data)) % 4
        blobs.append(data + b"\0" * pad)
        offset += len(data) + pad
    header = dict(header, tensors=table)
    head = json.dumps(header, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    prefix = MAGIC + struct.pack("<HHI", VERSION, 0, len(head)) + head
    prefix += b"\0" * ((-len(prefix)) % 4)
    body = prefix + b"".join(blobs)
    content = body + hashlib.sha256(body).digest()
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(content)
    os.replace(tmp, path)
    return hashlib.sha256(content).hexdigest()


def git_sha() -> str:
    try:
        return subprocess.run(["git", "-C", HERE, "rev-parse", "--short", "HEAD"], capture_output=True, text=True,
                              check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def header_of(net: M.Synthesis, meta: dict, style: torch.Tensor, datasets: list[dict], extra: dict) -> dict:
    a = net.arch
    return {
        "format": FORMAT,
        "version": VERSION,
        "arch": {"layers": a.layers, "cells": a.cells, "window": a.window, "mixtures": a.mixtures,
                 "style_dim": a.style_dim, "alphabet_size": a.A, "kappa_scale": a.kappa_scale},
        "alphabet": a.alphabet,
        "normalisation": {"mean": meta["norm_mean"], "std": meta["norm_std"]},
        "points_per_char": meta.get("points_per_char") or a.kappa_scale,
        "provenance": dict({"datasets": datasets, "git": git_sha()}, **extra),
    }


# --- Golden vectors -------------------------------------------------------------------------------------


@torch.no_grad()
def golden(net: M.Synthesis, text: str, x: torch.Tensor, style: torch.Tensor, steps: list[int], bias: float = 0.5):
    """A teacher-forced run over `x` (T, 3) with `text`, recording the state at `steps`.

    Uses `Synthesis.step`, the path sampling takes and Kotlin mirrors. At each recorded step:
    every layer's h and c, kappa, the window w, phi over 0..U, the raw outputs, and the mixture
    weights and sigmas after Graves's bias.
    """
    onehot, lens = M.onehot_texts([text], net.arch.alphabet)
    state = net.zero_state(1)
    records = []
    Mx = net.arch.mixtures
    for t in range(len(x)):
        y, state, phi = net.step(x[t:t + 1], onehot, lens, style, state)
        if t in steps:
            e, pi_hat, mx, my, sx_hat, sy_hat, rho_hat = M.split(y, Mx)
            records.append({
                "step": t,
                "h": [h[0].tolist() for h in state.h],
                "c": [c[0].tolist() for c in state.c],
                "kappa": state.kappa[0].tolist(),
                "w": state.w[0].tolist(),
                "phi": phi[0].tolist(),
                "y": y[0].tolist(),
                "pi_biased": torch.softmax(pi_hat * (1.0 + bias), dim=-1)[0].tolist(),
                "sigma_x_biased": torch.exp(sx_hat - bias)[0].tolist(),
            })
    return {"text": text, "inputs": x.tolist(), "bias": bias, "records": records}


def golden_input(meta: dict, text: str, n: int, seed: int) -> torch.Tensor:
    """A plausible normalised input sequence: a start token, then small offsets with a few pen lifts."""
    g = torch.Generator().manual_seed(seed)
    x = torch.randn(n, 3, generator=g) * 0.8
    x[:, 2] = (torch.rand(n, generator=g) < 0.1).float()
    x[0] = torch.tensor([0.0, 0.0, 1.0])
    return x


# --- The tiny model inside core's tests ------------------------------------------------------------------

TINY_ALPHABET = " abcd"
TINY_SOURCE = {"id": "tiny-test-font", "licence": "OFL-1.1", "sha256": "0" * 64, "source": "none",
               "attribution": "a test fixture, not a font"}


def tiny_model(seed: int = 7) -> M.Synthesis:
    torch.manual_seed(seed)
    arch = M.Arch(alphabet=TINY_ALPHABET, layers=2, cells=8, window=2, mixtures=2, style_dim=4, writers=3,
                  kappa_scale=3.0)
    net = M.Synthesis(arch)
    with torch.no_grad():
        for p in net.parameters():
            p.mul_(2.5)  # bigger than the default init, so every nonlinearity is exercised
    return net


def _kt_hex(data: bytes, name: str) -> list[str]:
    hexs = data.hex()
    chunks = [hexs[k:k + 96] for k in range(0, len(hexs), 96)]
    out = [f"    val {name}: ByteArray = hex("]
    out += [f'        "{c}" +' for c in chunks[:-1]]
    out += [f'        "{chunks[-1]}",', "    )"]
    return out


def _kt_doubles(values, indent=8) -> list[str]:
    pad = " " * indent
    return [pad[:-4] + "doubleArrayOf("] + [f"{pad}{float(v)!r}," for v in values] + [pad[:-4] + "),"]


TINY_ADAPTER_RANK = 2


def gradient_golden(net: M.Synthesis, text: str, x: torch.Tensor, style: torch.Tensor, seed: int) -> dict:
    """docs/HANDWRITING-SYNTHESIS.md §8.1 — PyTorch's autograd gradients for P2 and P3, on fixed data.

    The loss is the sum over steps of the per-point NLL (model.sequence_loss). Recorded: the targets;
    d loss / d style with no adapters (P2); a set of rank-2 adapters, every tensor random so that every
    gradient is non-zero; and, with them, d loss / d style and d loss / d each adapter tensor (P3).
    """
    g = torch.Generator().manual_seed(seed)
    T = x.shape[0]
    target = torch.randn(T, 3, generator=g) * 0.8
    target[:, 2] = (torch.rand(T, generator=g) < 0.15).float()
    onehot, lens = M.onehot_texts([text], net.arch.alphabet)
    a = net.arch
    top_in = 3 + a.cells + a.A + a.style_dim
    shapes = [(TINY_ADAPTER_RANK, top_in), (4 * a.cells, TINY_ADAPTER_RANK), (TINY_ADAPTER_RANK, a.cells),
              (4 * a.cells, TINY_ADAPTER_RANK), (TINY_ADAPTER_RANK, a.layers * a.cells),
              (a.outputs, TINY_ADAPTER_RANK)]
    adapters = [(torch.randn(*s, generator=g) * 0.3) for s in shapes]

    s1 = style.detach().clone().requires_grad_(True)
    loss1 = M.sequence_loss(net, x, target, onehot, lens, s1)
    loss1.backward()
    s2 = style.detach().clone().requires_grad_(True)
    ads = [t.clone().requires_grad_(True) for t in adapters]
    loss2 = M.sequence_loss(net, x, target, onehot, lens, s2, ads)
    loss2.backward()
    return {
        "targets": target.tolist(),
        "loss_style": float(loss1),
        "grad_style": s1.grad[0].tolist(),
        "adapters": [t.reshape(-1).tolist() for t in adapters],
        "loss_adapted": float(loss2),
        "adapted_grad_style": s2.grad[0].tolist(),
        "adapter_grads": [t.grad.reshape(-1).tolist() for t in ads],
    }


def export_tiny() -> None:
    net = tiny_model()
    f16_round(net)
    meta = {"norm_mean": [0.07, 0.005], "norm_std": [0.38, 0.36], "points_per_char": 3}
    style = net.style.weight.mean(dim=0, keepdim=True)
    good = [{"name": "tiny-test", "licence": "LicenseRef-Inkstone-Synthetic", "sources": [TINY_SOURCE]}]
    bad = [{"name": "tiny-test-nc", "licence": "LicenseRef-Inkstone-Synthetic",
            "sources": [dict(TINY_SOURCE, id="nc-corpus", licence="CC-BY-NC-SA-4.0")]}]
    tmp = os.path.join(HERE, "data", "tiny")
    os.makedirs(tmp, exist_ok=True)
    sha = write_ihwm(os.path.join(tmp, "tiny.ihwm"), header_of(net, meta, style, good, {"seed": 7}),
                     tensors_of(net, style))
    # The refusal fixture: written directly, since export refuses it (check_provenance) -- the point.
    write_ihwm(os.path.join(tmp, "tiny-nc.ihwm"), header_of(net, meta, style, bad, {"seed": 7}), tensors_of(net, style))
    data = open(os.path.join(tmp, "tiny.ihwm"), "rb").read()
    nc = open(os.path.join(tmp, "tiny-nc.ihwm"), "rb").read()
    text = "abc da"
    x = golden_input(meta, text, 14, 3)
    gold = golden(net, text, x, style, steps=[0, 1, 6, 13])
    lines = [
        "// GENERATED by tools/handwriting/export.py --tiny -- do not edit.",
        "package dev.inkstone.core.handwriting.neural",
        "",
        "/**",
        " * A two-layer, eight-cell model with a five-letter alphabet, random weights, written by",
        " * `export.py` exactly as it writes the shipped model, and the PyTorch reference's state on a fixed",
        " * input -- what [SynthesisNetworkGoldenTest] holds Kotlin's arithmetic to (§7.3, 1e-4).",
        " * [nonCommercial] is the same model whose header names a CC-BY-NC-SA source: D13's refusal.",
        " */",
        '@Suppress("LargeClass") // Generated data: a model file and PyTorch\'s numbers for it.',
        "internal object TinyModelFixture {",
        f'    const val SHA256: String = "{sha}"',
        f'    const val TEXT: String = "{text}"',
        f"    const val BIAS: Float = {gold['bias']}f",
        "",
    ]
    lines += _kt_hex(data, "bytes")
    lines.append("")
    lines += _kt_hex(nc, "nonCommercial")
    lines += ["", "    /** The teacher-forced inputs, (dx, dy, lift) per step, already normalised. */",
              "    val inputs: List<DoubleArray> = listOf("]
    for row in gold["inputs"]:
        lines += _kt_doubles(row, 12)
    grad = gradient_golden(net, text, x, style, seed=5)
    lines += ["    )", "",
              "    /** §8.1 — the targets for the gradient goldens: point t, normalised, per teacher-forced step. */",
              "    val gradTargets: List<DoubleArray> = listOf("]
    for row in grad["targets"]:
        lines += _kt_doubles(row, 12)
    lines += ["    )", "",
              "    /** Σ_t NLL over [inputs] → [gradTargets] with the default style, and its gradient by the style. */",
              f"    const val LOSS_STYLE: Double = {grad['loss_style']!r}", "",
              "    val gradStyle: DoubleArray = doubleArrayOf("]
    lines += [f"        {float(v)!r}," for v in grad["grad_style"]]
    lines += ["    )", "",
              "    /** P3: rank-2 adapters (ihA, ihB, hhA, hhB, outA, outB), random so that every gradient is non-zero. */",
              f"    const val ADAPTER_RANK: Int = {TINY_ADAPTER_RANK}", "",
              "    val adapters: List<DoubleArray> = listOf("]
    for t in grad["adapters"]:
        lines += _kt_doubles(t, 12)
    lines += ["    )", "",
              f"    const val LOSS_ADAPTED: Double = {grad['loss_adapted']!r}", "",
              "    val adaptedGradStyle: DoubleArray = doubleArrayOf("]
    lines += [f"        {float(v)!r}," for v in grad["adapted_grad_style"]]
    lines += ["    )", "", "    val adapterGrads: List<DoubleArray> = listOf("]
    for t in grad["adapter_grads"]:
        lines += _kt_doubles(t, 12)
    lines += ["    )", "", "    val records: List<Record> = listOf("]
    for r in gold["records"]:
        lines.append("        Record(")
        lines.append(f"            step = {r['step']},")
        for key in ("h", "c"):
            lines.append(f"            {key} = listOf(")
            for layer in r[key]:
                lines += _kt_doubles(layer, 20)
            lines.append("            ),")
        for key, kt in (("kappa", "kappa"), ("w", "w"), ("phi", "phi"), ("y", "y"), ("pi_biased", "piBiased"),
                        ("sigma_x_biased", "sigmaXBiased")):
            lines.append(f"            {kt} = doubleArrayOf(")
            lines += [f"                {float(v)!r}," for v in r[key]]
            lines.append("            ),")
        lines.append("        ),")
    lines += [
        "    )",
        "",
        "    class Record(",
        "        val step: Int,",
        "        val h: List<DoubleArray>,",
        "        val c: List<DoubleArray>,",
        "        val kappa: DoubleArray,",
        "        val w: DoubleArray,",
        "        val phi: DoubleArray,",
        "        val y: DoubleArray,",
        "        val piBiased: DoubleArray,",
        "        val sigmaXBiased: DoubleArray,",
        "    )",
        "",
        "    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) {",
        "        s.substring(2 * it, 2 * it + 2).toInt(HEX).toByte()",
        "    }",
        "",
        "    private const val HEX = 16",
        "}",
        "",
    ]
    os.makedirs(os.path.dirname(TINY_KOTLIN), exist_ok=True)
    with open(TINY_KOTLIN, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))
    print(f"tiny model {len(data)} bytes, sha256 {sha} -> {os.path.relpath(TINY_KOTLIN, REPO)}")


# --- The real model ----------------------------------------------------------------------------------------


def export_model(checkpoint: str, out: str, golden_out: str | None) -> str:
    ck = torch.load(checkpoint, weights_only=True, map_location="cpu")
    arch = M.Arch(**ck["arch"])
    net = M.Synthesis(arch)
    net.load_state_dict(ck["model"])
    net.eval()
    run = os.path.dirname(os.path.abspath(checkpoint))
    with open(os.path.join(run, "data.json"), encoding="utf-8") as f:
        meta = json.load(f)["meta"]
    datasets = meta["datasets"]
    check_provenance(datasets)
    f16_round(net)
    style = net.style.weight.mean(dim=0, keepdim=True)
    history = []
    full = os.path.join(run, "checkpoint.pt")
    if os.path.exists(full):
        history = torch.load(full, weights_only=True, map_location="cpu").get("history", [])
    extra = {"checkpoint": os.path.basename(checkpoint), "history": history, "seed": 1}
    sha = write_ihwm(out, header_of(net, meta, style, datasets, extra), tensors_of(net, style))
    if golden_out:
        text = "the cat"
        x = golden_input(meta, text, 60, 11)
        g = golden(net, text, x, style, steps=[0, 1, 17, 59])
        g["sha256"] = sha
        os.makedirs(os.path.dirname(os.path.abspath(golden_out)), exist_ok=True)
        with open(golden_out, "w", encoding="utf-8", newline="\n") as f:
            json.dump(g, f)
    size = os.path.getsize(out)
    print(f"{out}: {size} bytes ({size / 1048576:.2f} MiB), sha256 {sha}")
    return sha


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("checkpoint", nargs="?")
    ap.add_argument("--out")
    ap.add_argument("--golden")
    ap.add_argument("--tiny", action="store_true")
    a = ap.parse_args(argv)
    if a.tiny:
        export_tiny()
    else:
        if not a.checkpoint or not a.out:
            ap.error("a checkpoint and --out are required")
        export_model(a.checkpoint, a.out, a.golden)


if __name__ == "__main__":
    main()
