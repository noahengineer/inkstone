"""Single-line font skeletons, licence-checked, in the normalised frame (docs/HANDWRITING-SYNTHESIS.md §7.2).

A skeleton is a glyph as the pen would draw it: a list of open polylines. Every font is read only
through `fonts/LICENCES.json`, which names its file, its SHA-256 and a licence; a file that is not
listed, has been changed, or carries a licence outside COMMERCIAL_LICENCES is refused (D13).

Glyphs come out in the same frame Kotlin's NormalFrame uses (§5.2): x along the baseline from the
glyph's own origin, y UP from the baseline, one x-height as the unit. A font's declared metrics are
not trusted -- EMS fonts say x-height 300 and draw `a` up to 624 -- so baseline and x-height are
measured from the font's own lowercase letters.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
import statistics
from dataclasses import dataclass, field

# Font files are downloaded data: parse them without external entities or entity expansion.
import defusedxml.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
FONTS = os.path.join(HERE, "fonts")

# Licences whose terms allow the font to be used to make training data for a model sold in a
# commercial product. An allow-list, so an unknown licence is refused rather than guessed at.
COMMERCIAL_LICENCES = frozenset({
    "OFL-1.1",
    "LicenseRef-Hershey",
    "CC0-1.0",
    "MIT",
    "Apache-2.0",
    "CC-BY-4.0",
})

# Letters with neither ascender nor descender: what the baseline and x-height are measured on.
X_HEIGHT_LETTERS = "acemnorsuvwxz"


class LicenceError(Exception):
    """A font (or corpus) whose licence entry is missing, wrong, or not commercially usable."""


@dataclass(frozen=True)
class FontEntry:
    id: str
    path: str
    format: str
    style: str
    sha256: str
    licence: str
    licence_file: str
    source: str
    attribution: str
    # "face": a writer's face (synth.py draws whole texts with it). "symbols": a set that only lends
    # characters a face lacks (corpus v2's maths), read through `charmap` and never a writer's face.
    role: str = "face"
    # (slot, character) pairs: the character a .jhf slot actually draws, as identified by looking at
    # a rendered sheet of the file. Only listed slots are read; empty means every slot is what it says.
    charmap: tuple = ()
    # Another entry whose lowercase letters measure this one's baseline and x-height: a Greek set has
    # no Latin letters to measure, and every Hershey simplex set shares one coordinate system.
    metrics_from: str = ""


ROLES = ("face", "symbols")


@dataclass
class Glyph:
    """One character: where the pen goes (open polylines) and how far the next one starts."""

    char: str
    advance: float
    strokes: list[list[tuple[float, float]]] = field(default_factory=list)


@dataclass
class Font:
    entry: FontEntry
    glyphs: dict[str, Glyph]
    units_per_x_height: float


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def manifest(fonts_dir: str = FONTS) -> list[FontEntry]:
    """Every listed font, each checked: present, unchanged, and under an allowed licence."""
    path = os.path.join(fonts_dir, "LICENCES.json")
    if not os.path.exists(path):
        raise LicenceError(f"no licence manifest at {path}")
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    out = []
    for item in raw.get("fonts", []):
        for key in ("id", "file", "format", "sha256", "licence", "licence_file", "attribution"):
            if not item.get(key):
                raise LicenceError(f"font entry {item.get('id', '?')} has no {key}")
        if item["licence"] not in COMMERCIAL_LICENCES:
            raise LicenceError(f"{item['id']}: licence {item['licence']} is not on the commercial allow-list")
        file_path = os.path.join(fonts_dir, item["file"])
        licence_path = os.path.join(fonts_dir, item["licence_file"])
        if not os.path.exists(file_path):
            raise LicenceError(f"{item['id']}: {item['file']} is missing")
        if not os.path.exists(licence_path):
            raise LicenceError(f"{item['id']}: its licence text {item['licence_file']} is missing")
        actual = sha256_of(file_path)
        if actual != item["sha256"]:
            raise LicenceError(f"{item['id']}: {item['file']} has sha256 {actual}, the manifest says {item['sha256']}")
        role = item.get("role", "face")
        if role not in ROLES:
            raise ValueError(f"{item['id']}: role {role} is not one of {ROLES}")
        charmap = tuple((slot, char) for slot, char in item.get("charmap", {}).items())
        for slot, char in charmap:
            if len(slot) != 1 or len(char) != 1 or ord(char) > 0xFFFF:
                raise ValueError(f"{item['id']}: charmap {slot!r} -> {char!r} is not one character to one BMP character")
        if role == "symbols" and not charmap:
            raise ValueError(f"{item['id']}: a symbol set must say which slot draws which character (charmap)")
        out.append(FontEntry(
            item["id"], file_path, item["format"], item.get("style", "print"), item["sha256"],
            item["licence"], item["licence_file"], item.get("source", ""), item["attribution"],
            role=role, charmap=charmap, metrics_from=item.get("metrics_from", ""),
        ))
    return out


# --- Hershey .jhf ----------------------------------------------------------------------------------


def _jhf_records(text: str) -> list[str]:
    """One string per glyph, re-joining the 72-column wrap some distributions use."""
    records = []
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        line = lines[i].rstrip("\n")
        if not line.strip():
            i += 1
            continue
        count = int(line[5:8])
        need = 8 + 2 * count
        while len(line) < need and i + 1 < len(lines):
            i += 1
            line += lines[i].rstrip("\n")
        records.append(line[:need])
        i += 1
    return records


def _jhf_glyph(record: str) -> tuple[float, float, list[list[tuple[float, float]]]]:
    """(left, right, strokes) in Hershey units, y DOWN as the format has it."""
    data = record[8:]
    left = ord(data[0]) - ord("R")
    right = ord(data[1]) - ord("R")
    strokes: list[list[tuple[float, float]]] = []
    current: list[tuple[float, float]] = []
    for k in range(2, len(data) - 1, 2):
        pair = data[k:k + 2]
        if pair == " R":
            if current:
                strokes.append(current)
            current = []
            continue
        current.append((float(ord(pair[0]) - ord("R")), float(ord(pair[1]) - ord("R"))))
    if current:
        strokes.append(current)
    return left, right, strokes


def _load_jhf(entry: FontEntry) -> dict[str, tuple[float, list[list[tuple[float, float]]]]]:
    """char -> (advance, strokes with x from the glyph origin and y UP), in font units."""
    with open(entry.path, encoding="ascii") as f:
        records = _jhf_records(f.read())
    out = {}
    for k, rec in enumerate(records):
        char = chr(32 + k)
        left, right, strokes = _jhf_glyph(rec)
        out[char] = (float(right - left), [[(x - left, -y) for x, y in s] for s in strokes])
    return out


# --- SVG fonts -------------------------------------------------------------------------------------

_TOKEN = re.compile(r"[MmLlHhVvCcSsQqTtZzAa]|[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")
CURVE_STEPS = 12


def _cubic(p0, p1, p2, p3, steps=CURVE_STEPS):
    out = []
    for k in range(1, steps + 1):
        t = k / steps
        u = 1.0 - t
        out.append((
            u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0],
            u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1],
        ))
    return out


def _quad(p0, p1, p2, steps=CURVE_STEPS):
    out = []
    for k in range(1, steps + 1):
        t = k / steps
        u = 1.0 - t
        out.append((u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0], u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1]))
    return out


def svg_path(d: str) -> list[list[tuple[float, float]]]:
    """An SVG path's subpaths as polylines, curves flattened. Arcs are refused, not guessed."""
    tokens = _TOKEN.findall(d)
    strokes: list[list[tuple[float, float]]] = []
    current: list[tuple[float, float]] = []
    x = y = 0.0
    start = (0.0, 0.0)
    last_ctrl = None
    cmd = None
    i = 0

    def num():
        nonlocal i
        v = float(tokens[i])
        i += 1
        return v

    while i < len(tokens):
        tok = tokens[i]
        if re.fullmatch(r"[A-Za-z]", tok):
            cmd = tok
            i += 1
        elif cmd is None:
            raise ValueError(f"path data starts with a number: {d[:40]}")
        rel = cmd.islower()
        c = cmd.upper()
        if c == "M":
            if len(current) > 1:
                strokes.append(current)
            nx, ny = num(), num()
            x, y = (x + nx, y + ny) if rel else (nx, ny)
            current = [(x, y)]
            start = (x, y)
            cmd = "l" if rel else "L"
            last_ctrl = None
        elif c == "L":
            nx, ny = num(), num()
            x, y = (x + nx, y + ny) if rel else (nx, ny)
            current.append((x, y))
            last_ctrl = None
        elif c == "H":
            nx = num()
            x = x + nx if rel else nx
            current.append((x, y))
            last_ctrl = None
        elif c == "V":
            ny = num()
            y = y + ny if rel else ny
            current.append((x, y))
            last_ctrl = None
        elif c in "CS":
            if c == "C":
                c1 = (num(), num())
                if rel:
                    c1 = (x + c1[0], y + c1[1])
            else:
                c1 = (2 * x - last_ctrl[0], 2 * y - last_ctrl[1]) if last_ctrl else (x, y)
            c2 = (num(), num())
            end = (num(), num())
            if rel:
                c2 = (x + c2[0], y + c2[1])
                end = (x + end[0], y + end[1])
            current += _cubic((x, y), c1, c2, end)
            last_ctrl = c2
            x, y = end
        elif c in "QT":
            if c == "Q":
                c1 = (num(), num())
                if rel:
                    c1 = (x + c1[0], y + c1[1])
            else:
                c1 = (2 * x - last_ctrl[0], 2 * y - last_ctrl[1]) if last_ctrl else (x, y)
            end = (num(), num())
            if rel:
                end = (x + end[0], y + end[1])
            current += _quad((x, y), c1, end)
            last_ctrl = c1
            x, y = end
        elif c == "Z":
            # A single-line font should not close paths; when one does, the pen goes back to start.
            current.append(start)
            x, y = start
            last_ctrl = None
        else:
            raise ValueError(f"unsupported path command {cmd}")
    if len(current) > 1:
        strokes.append(current)
    return strokes


def _load_svg(entry: FontEntry) -> dict[str, tuple[float, list[list[tuple[float, float]]]]]:
    """char -> (advance, strokes), in font units, y up (SVG fonts are y up with baseline 0)."""
    tree = ET.parse(entry.path)
    root = tree.getroot()
    ns = ""
    if root.tag.startswith("{"):
        ns = root.tag.split("}")[0] + "}"
    font = root.find(f".//{ns}font")
    if font is None:
        raise ValueError(f"{entry.id}: no <font> element")
    default_adv = float(font.get("horiz-adv-x", "500"))
    out = {}
    for g in font.findall(f"{ns}glyph"):
        uni = g.get("unicode")
        if uni is None or len(uni) != 1:
            continue
        adv = float(g.get("horiz-adv-x", default_adv))
        d = g.get("d", "")
        out[uni] = (adv, svg_path(d) if d.strip() else [])
    return out


# --- Normalising -----------------------------------------------------------------------------------


def _measure(raw) -> tuple[float, float]:
    """(baseline, x-height) in font units: the median bottom and median top of X_HEIGHT_LETTERS."""
    bottoms, tops = [], []
    for ch in X_HEIGHT_LETTERS:
        if ch not in raw or not raw[ch][1]:
            continue
        ys = [p[1] for s in raw[ch][1] for p in s]
        bottoms.append(min(ys))
        tops.append(max(ys))
    if len(tops) < 5:
        raise ValueError("too few x-height letters to measure the font")
    baseline = statistics.median(bottoms)
    return baseline, statistics.median(tops) - baseline


def _raw(entry: FontEntry):
    raw = _load_jhf(entry) if entry.format == "jhf" else _load_svg(entry)
    if entry.charmap:
        raw = {char: raw[slot] for slot, char in entry.charmap if slot in raw}
    return raw


def load(entry: FontEntry, metrics: tuple[float, float] | None = None) -> Font:
    """`metrics` is (baseline, x-height) in font units, for a set measured on another entry's letters."""
    raw = _raw(entry)
    baseline, xh = metrics or _measure(raw)
    glyphs = {}
    for ch, (adv, strokes) in raw.items():
        norm = [[(x / xh, (y - baseline) / xh) for x, y in s] for s in strokes]
        norm = [s for s in norm if len(s) >= 2 and _length(s) > 1e-6] or ([] if ch == " " else norm)
        glyphs[ch] = Glyph(ch, adv / xh, norm)
    return Font(entry, glyphs, xh)


def _length(s) -> float:
    return sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(s, s[1:]))


def load_all(fonts_dir: str = FONTS) -> list[Font]:
    """Every writer face (role "face"): what corpus v1 was made from, unchanged by symbol sets."""
    return [load(e) for e in manifest(fonts_dir) if e.role == "face"]


def load_symbols(required, fonts_dir: str = FONTS) -> dict[str, Font]:
    """The symbol sets named in `required`, each checked exactly as a face is (allow-list, SHA-256).

    A set that is missing from the manifest is refused rather than read from disk anyway: a file
    nobody recorded a licence for is never used, whatever is in the folder (D13).
    """
    entries = {e.id: e for e in manifest(fonts_dir)}
    out = {}
    for sid in required:
        e = entries.get(sid)
        if e is None:
            raise LicenceError(f"symbol set {sid} is not in the licence manifest")
        if e.role != "symbols":
            raise LicenceError(f"{sid} is listed as a {e.role}, not a symbol set")
        metrics = None
        if e.metrics_from:
            ref = entries.get(e.metrics_from)
            if ref is None:
                raise LicenceError(f"{sid} takes its metrics from {e.metrics_from}, which is not in the manifest")
            metrics = _measure(_raw(ref))
        out[sid] = load(e, metrics)
    return out
