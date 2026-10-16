"""Where a corpus-v2 writer gets each character (docs/HANDWRITING-SYNTHESIS.md §7.2, §18.10 item 6).

Corpus v1 draws every character with the writer's face, falling back to Hershey futural (print
faces) or scripts (script faces) for one the face lacks. None of this module is used for v1, which
is reproduced draw for draw (test_pipeline.CorpusV1IsUnchanged).

For the characters v2 adds (texts.NEW_V2) it asks, in order:

  1. Does the face draw it with Hershey's doubled "complex" strokes? futural, scripts and cursive
     draw [ ] { } ~ as two parallel strokes for weight, which no hand does. Then the face's own
     glyph, made single-line (SINGLE_LINE).
  2. Does the face draw it properly? Then the face's own glyph, sized with the writer's digits.
     Not when it is a stand-in (NOT_OWN, `Lender.standins`): scripts' `^` slot is an up arrow, and
     six EMS faces file their letter x under ×.
  3. Otherwise it is borrowed: from the Hershey symbol sets (mathlow, greeks), futural, or Relief
     SingleLine, whichever family the writer prefers when both have it, so a borrowed `×` is not
     one shape across the whole corpus. A borrowed glyph is scaled to the writer's own metrics: an
     operator to their digit height, Δ and Ω to their cap height, π μ λ θ to their x-height.

v1's characters come from where v1 takes them, but v2 also mends what its sheets showed wrong with
them: Hershey scripts' doubled digits made single-line, the period set on the line, every symbol
sized with the writer's digits, Relief's split corners merged (MERGE), old-style digits made lining
(OLD_STYLE_DIGITS), and paper kept round points, digits and symbols (`clearance`, NUMBER_GAP).

Every glyph used here was looked at on a rendered sheet before it was listed (the sheets live under
runs/v2-maths-3x256/sheets/). Two are derived rather than read: θ, because Hershey's simplex θ is
the script ϑ, is Hershey's Θ narrowed -- the closed, barred θ school notes use; and ≈, which no
downloaded Hershey set draws, is futural's tilde twice.
"""

from __future__ import annotations

import math
import statistics

import fontskel
import texts
from fontskel import Glyph

SYMBOL_SETS = ("hershey-mathlow", "hershey-greeks")


def brace(strokes):
    """Hershey's doubled brace as one line: the two outer halves (which carry the end hooks) joined
    through the point of the middle stroke. The middle stroke alone has its ends curling the wrong way."""
    top, middle, bottom = strokes
    mean = sum(p[0] for p in middle) / len(middle)
    point = max(middle, key=lambda p: abs(p[0] - mean))
    return [list(top) + [point] + list(bottom)]


def one_bar(strokes):
    """Hershey's ≤ and ≥ are ≦ and ≧: an angle over two bars. School notes write one: keep the angle and
    the upper bar, lowered so the bar sits where the lower one did, on the line."""
    angle, upper, lower = strokes
    dy = lower[0][1] - upper[0][1]
    return [[(x, y + dy) for x, y in angle], [(x, y + dy) for x, y in upper]]


# Glyphs drawn doubled (or otherwise not as a hand writes them), rebuilt. None: drop any stroke that
# only retraces a longer one (within NEAR_UNITS), then join strokes whose ends meet. A tuple: drop
# exactly those strokes, where the automatic rule would keep the wrong one of a pair. A function:
# the strokes rebuilt by it.
SINGLE_LINE = {
    ("hershey-mathlow", "≤"): one_bar,
    ("hershey-mathlow", "≥"): one_bar,
    **{(face, ch): None for face in ("hershey-futural", "hershey-scripts", "hershey-cursive") for ch in "[]~"},
    **{(face, ch): brace for face in ("hershey-futural", "hershey-scripts", "hershey-cursive") for ch in "{}"},
    # Hershey scripts draws its digits and much of its punctuation in the doubled weight too -- so
    # corpus v1's scripts writers (about one in nine) wrote every digit as a pair of near-parallel
    # strokes. v2 writes them single-line; v1 is left as it was.
    **{("hershey-scripts", ch): None for ch in "0123456789()?!&\""},
    # The radical's descending stroke is drawn twice; keep the one that reaches the bottom vertex.
    ("hershey-mathlow", "√"): (0,),
}

# A face's own glyph that is not a fair copy of the character it is filed under, found on the sheets:
# scripts' `^` slot is an up arrow, EMS Capitol's `°` a looped flourish nearer θ than a degree sign,
# and EMS Casual Hand's `<` an angle sign (∠). Also never the face's own: a "symbol" that is one of
# the face's letters stroke for stroke -- six EMS faces file their letter x under × (`Lender.standins`).
NOT_OWN = {("hershey-scripts", "^"), ("ems-capitol", "°"), ("ems-casualhand", "<"),
           # Relief's waves are 0.13 x-heights deep, and once the motion model has smoothed them its
           # ≈ is two flat bars with hooks -- an `=` -- and its ~ a dash. Both come from Hershey's.
           ("relief-singleline", "≈"), ("relief-singleline", "~")}

# Who lends each new character, Hershey first. Relief SingleLine is a writer face too, so a Relief
# writer always uses its own; everyone else borrows.
LENDERS = {
    "×": ("hershey-mathlow", "relief-singleline"),
    "÷": ("hershey-mathlow", "relief-singleline"),
    "±": ("hershey-mathlow", "relief-singleline"),
    "√": ("hershey-mathlow", "relief-singleline"),
    "°": ("hershey-mathlow", "relief-singleline"),
    "≤": ("hershey-mathlow", "relief-singleline"),
    "≥": ("hershey-mathlow", "relief-singleline"),
    "≠": ("hershey-mathlow", "relief-singleline"),
    "∞": ("hershey-mathlow", "relief-singleline"),
    "≈": ("hershey-futural",),  # no Hershey set draws it: two of futural's tildes, stacked (APPROX_*)
    "π": ("hershey-greeks", "relief-singleline"),
    "θ": ("hershey-greeks",),
    "Δ": ("hershey-greeks",),
    "μ": ("hershey-greeks",),
    "Ω": ("hershey-greeks",),
    "λ": ("hershey-greeks",),
    **{ch: ("hershey-futural", "relief-singleline") for ch in "^*<>[]{}|_"},
    "~": ("hershey-futural",),
}

# How a borrowed glyph is sized against the writer.
SIZE = {**{ch: "digit" for ch in "×÷±√°≤≥≠≈∞^*<>[]{}|_~"}, "Δ": "cap", "Ω": "cap", "π": "x", "μ": "x", "λ": "x", "θ": "x"}

# In the font's own units, because the doubling is drawn in them: 1-2 Hershey units apart, which is
# 0.07-0.14 of futural's 14-unit x-height and twice that of the script faces' smaller one.
NEAR_UNITS = 2.6   # a stroke this close to a longer one, all along it, only retraces it
JOIN_UNITS = 2.4   # ends this close are one pen-down
THETA_WIDTH = 0.62
# ≈ is futural's (single-lined) tilde twice, this far (x-heights) above and below where the one sits.
APPROX_OFFSET = 0.19
# Vertices closer than this (x-heights) are one. Relief draws every sharp corner as two vertices a
# hair apart (0.005 at the point of `<`, 0.018 at the top of `4`), so one cusp became two turns, each
# under sigmalognormal.plan's 110° rule for waiting at a cusp: the pen sailed round both and drew a
# loop. Corpus v1's Relief writers wrote `4` as a `9` or a `)` that way. Merged, it is one cusp, and
# the planner stops at it as a hand does.
MERGE = 0.035

# Faces whose digits are old-style (3 4 5 7 9 hang below the line, 6 8 rise above it). Scaled by
# the median digit, as every face is, EMS Neato's hung 1.5 x-heights below the line and its 6 and 8
# stood 3.6 x-heights tall. v2 makes each digit lining: scaled whole to the face's digit height and
# set on the line. Felix is the same design, milder.
OLD_STYLE_DIGITS = ("ems-neato", "ems-felix")

# Between a word with a digit in it and the next, at least this many of the writer's digit heights:
# at v1's narrowest word gap (0.6 x-heights) `2.5 9.81 0.0375` was written `2.59.810.0375`.
NUMBER_GAP = 0.55

# A point keeps at least this much paper either side of it (x-heights), or more when the writer's
# letter gap is wider: v1's 0.04 gap put `2.5`'s point against the 5 and read `25`. The same 0.2 as
# the app's GlyphAssembly.clearance (docs §18.10, item 4).
POINTS = ".,"
POINT_CLEARANCE = 0.2

# Characters written slowing at every corner (synth.animate): at the motion model's ordinary pulse
# overlap a bracket's short arms round off and `[0, 1]` is drawn `(0, 1)`, and an angle that turns by
# less than sigmalognormal's 110° cusp -- EMS Casual Hand's `>` -- is drawn `)`.
CAREFUL = "[]<>≤≥^√"


# Any other pair with a digit, a symbol or a borrowed glyph in it: at least this much, or the
# writer's letter gap if wider. The motion model moves a stroke's ends by about a tenth of an
# x-height, so v1's narrowest gap (0.04) let `-12`'s minus run into the 1 and read `+2`.
SYMBOL_GAP = 0.12


def clearance(prev: str, ch: str, prev_own: bool, own: bool, letter_gap: float):
    """The least paper between two neighbours' ink in a v2 word, or None to leave v1's spacing alone.

    A point gets POINT_CLEARANCE. Any pair that is not two of the face's own letters -- a digit, a
    symbol, a borrowed glyph -- gets SYMBOL_GAP: a script face places by advance, and EMS Capitol's
    own ± has an advance narrower than its ink, so `±0.5` was drawn `⊕.5`.
    """
    if prev in POINTS or ch in POINTS:
        return max(letter_gap, POINT_CLEARANCE)
    if not (prev.isalpha() and ch.isalpha()) or not (prev_own and own):
        return max(letter_gap, SYMBOL_GAP)
    return None

assert set(LENDERS) == set(texts.NEW_V2), "every new character needs a lender"
assert set(SIZE) == set(texts.NEW_V2)


# --- Geometry -------------------------------------------------------------------------------------------


def _length(s):
    return sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(s, s[1:]))


def _seg_dist(p, a, b):
    ax, ay = b[0] - a[0], b[1] - a[1]
    n = ax * ax + ay * ay
    t = 0.0 if n == 0 else max(0.0, min(1.0, ((p[0] - a[0]) * ax + (p[1] - a[1]) * ay) / n))
    return math.hypot(p[0] - a[0] - t * ax, p[1] - a[1] - t * ay)


def _poly_dist(p, poly):
    if len(poly) == 1:
        return math.hypot(p[0] - poly[0][0], p[1] - poly[0][1])
    return min(_seg_dist(p, a, b) for a, b in zip(poly, poly[1:]))


def _resample(poly, step):
    out = [poly[0]]
    for a, b in zip(poly, poly[1:]):
        d = math.hypot(b[0] - a[0], b[1] - a[1])
        k = max(1, int(d / step))
        out += [(a[0] + (b[0] - a[0]) * i / k, a[1] + (b[1] - a[1]) * i / k) for i in range(1, k + 1)]
    return out


def _dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def single_line(strokes, drop=None, near=NEAR_UNITS / 14, join=JOIN_UNITS / 14):
    """A doubled glyph as one pen would draw it: retracing strokes dropped, meeting ends joined.

    `near` and `join` are in the strokes' units (x-heights); the defaults are Hershey futural's.
    """
    strokes = [list(s) for s in strokes]
    if drop is not None:
        kept = [i for i in range(len(strokes)) if i not in drop]
    else:
        alive = set(range(len(strokes)))
        # Shortest first; of two equal strokes the later one is the double.
        for i in sorted(range(len(strokes)), key=lambda i: (_length(strokes[i]), -i)):
            others = [strokes[j] for j in alive if j != i and _length(strokes[j]) >= _length(strokes[i])]
            if others and all(min(_poly_dist(p, o) for o in others) <= near for p in _resample(strokes[i], near / 3)):
                alive.discard(i)
        kept = sorted(alive)
    rest = [strokes[i] for i in kept]
    chains = []
    while rest:
        c = rest.pop(0)
        grown = True
        while grown:
            grown = False
            for k, s in enumerate(rest):
                if _dist(c[-1], s[0]) <= join:
                    c = c + s[1:]
                elif _dist(c[-1], s[-1]) <= join:
                    c = c + s[::-1][1:]
                elif _dist(c[0], s[-1]) <= join:
                    c = s[:-1] + c
                elif _dist(c[0], s[0]) <= join:
                    c = s[::-1][:-1] + c
                else:
                    continue
                rest.pop(k)
                grown = True
                break
        chains.append(c)
    return chains


def merged(stroke, eps):
    """`stroke` with any vertex closer than `eps` to the one kept before it dropped (two kept at least)."""
    out = [stroke[0]]
    for p in stroke[1:]:
        if _dist(p, out[-1]) >= eps:
            out.append(p)
    if len(out) == 1 and len(stroke) > 1:
        out.append(stroke[-1])
    return out


def narrowed(glyph: Glyph, char: str, factor: float) -> Glyph:
    """`glyph` squeezed horizontally about its own centre."""
    xs = [p[0] for s in glyph.strokes for p in s]
    mid = (min(xs) + max(xs)) / 2
    return Glyph(char, glyph.advance * factor, [[(mid + (x - mid) * factor, y) for x, y in s] for s in glyph.strokes])


def _top(glyphs, chars):
    tops = [max(p[1] for s in glyphs[c].strokes for p in s) for c in chars if c in glyphs and glyphs[c].strokes]
    return statistics.median(tops) if tops else None


# --- The lenders ------------------------------------------------------------------------------------------


class Lender:
    """A font that lends glyphs: its glyphs (cleaned where listed) and its digit and cap heights."""

    def __init__(self, font: fontskel.Font):
        self.id = font.entry.id
        self.entry = font.entry
        self.glyphs = dict(font.glyphs)
        unit = 1.0 / font.units_per_x_height
        for (fid, ch), drop in SINGLE_LINE.items():
            if fid == self.id and ch in self.glyphs:
                g = self.glyphs[ch]
                strokes = (drop(g.strokes) if callable(drop) else
                           single_line(g.strokes, drop, NEAR_UNITS * unit, JOIN_UNITS * unit))
                self.glyphs[ch] = Glyph(ch, g.advance, strokes)
        if "Θ" in self.glyphs and "θ" not in self.glyphs:
            self.glyphs["θ"] = narrowed(self.glyphs["Θ"], "θ", THETA_WIDTH)
        if self.id == "hershey-futural":
            t = self.glyphs["~"]
            self.glyphs["≈"] = Glyph("≈", t.advance, [[(x, y + dy) for x, y in s] for dy in (APPROX_OFFSET, -APPROX_OFFSET)
                                                      for s in t.strokes])
        if "." in self.glyphs and self.glyphs["."].strokes:
            # A decimal point sits on the line. Hershey futural floats its period 0.2 x-heights up and
            # cursive 0.33 -- a raised dot, which in a number reads as multiplication (2·5).
            g = self.glyphs["."]
            low = min(p[1] for s in g.strokes for p in s)
            self.glyphs["."] = Glyph(".", g.advance, [[(x, y - low) for x, y in s] for s in g.strokes])
        if self.id in OLD_STYLE_DIGITS:
            ref = _top(self.glyphs, "0123456789")
            for c in "0123456789":
                g = self.glyphs[c]
                xs = [p[0] for s in g.strokes for p in s]
                ys = [p[1] for s in g.strokes for p in s]
                k = ref / (max(ys) - min(ys))
                self.glyphs[c] = Glyph(c, g.advance * k, [[(min(xs) + (x - min(xs)) * k, (y - min(ys)) * k) for x, y in s]
                                                         for s in g.strokes])
        self.glyphs = {c: Glyph(c, g.advance, [merged(s, MERGE) for s in g.strokes]) for c, g in self.glyphs.items()}
        letters = [c for c in texts.ALPHABET if c.isalpha() and c in self.glyphs and self.glyphs[c].strokes]
        self.standins = {ch for ch in texts.NEW_V2 if ch in self.glyphs and self.glyphs[ch].strokes
                         and any(self.glyphs[ch].strokes == self.glyphs[c].strokes for c in letters)}
        self.digit = _top(self.glyphs, "0123456789") or 1.5
        upper = [c for c in self.glyphs if c.isupper()]
        self.cap = _top(self.glyphs, upper) or self.digit

    def glyph(self, ch):
        return self.glyphs.get(ch)


_LENDERS: dict | None = None
_OWN: dict = {}


def own_glyphs(face) -> Lender:
    """A writer face's own glyphs with its doubled ones made single-line, once per face per process."""
    if face.entry.id not in _OWN:
        _OWN[face.entry.id] = Lender(face.font)
    return _OWN[face.entry.id]


def lenders(faces: dict) -> dict:
    """Every font that may lend a v2 character: the symbol sets plus the faces named in LENDERS."""
    global _LENDERS
    if _LENDERS is None:
        sets = fontskel.load_symbols(SYMBOL_SETS)
        named = {fid for ids in LENDERS.values() for fid in ids}
        out = {sid: Lender(f) for sid, f in sets.items()}
        for fid in sorted(named - set(out)):
            out[fid] = own_glyphs(faces[fid])
        _LENDERS = out
    return _LENDERS


# --- The resolver -----------------------------------------------------------------------------------------


def symbol_scale(face, w) -> float:
    """A face's own symbol, sized with the writer: by their digits, or for a face with old-style digits
    (whose median digit is x-height tall, so its symbols were drawn against its capitals) by their caps."""
    if face.entry.id in OLD_STYLE_DIGITS:
        return w.cap_height / face.cap
    return w.digit_height / face.digit


class Glyphs:
    """One v2 writer's glyph for each character, with how to scale it and whether it is the face's own.

    `prefer` is "hershey" or "relief": the family this writer borrows from when both have a character.
    """

    def __init__(self, face, fallback: dict, lent: dict, prefer: str):
        self.face = face
        self.fallback = fallback
        self.lent = lent
        self.prefer = prefer
        self.script = face.entry.style == "script"
        self.own = own_glyphs(face)  # the face, with its doubled glyphs made single-line

    def lender_for(self, ch):
        ids = [i for i in LENDERS[ch] if self.lent[i].glyph(ch) is not None]
        preferred = [i for i in ids if i.startswith(self.prefer)]
        return self.lent[(preferred or ids)[0]]

    def resolve(self, ch, w):
        """(glyph or None, scale, own).

        v1's characters come from where corpus v1 takes them (the face, else its fallback), with three
        differences found on v2's sheets: doubled glyphs are made single-line, the period sits on the
        line, and every symbol -- anything not a letter or digit -- is scaled with the writer's digits
        rather than left at its face's proportions. v1 left Hershey scripts' `=` two x-heights wide
        beside digits shrunk to the writer's size.
        """
        if ch not in SIZE:
            f = self.face if self.face.glyph(ch) else self.fallback["script" if self.script else "print"]
            g = own_glyphs(f).glyph(ch)
            if g is None:
                return None, 1.0, f is self.face
            scale = (w.cap_height / f.cap if ch.isupper() else 1.0 if ch.isalpha() else
                     w.digit_height / f.digit if ch.isdigit() else symbol_scale(f, w))
            return g, scale, f is self.face
        key = (self.face.entry.id, ch)
        g = self.own.glyph(ch)
        if g is not None and g.strokes and key not in NOT_OWN and ch not in self.own.standins:
            scale = w.cap_height / self.face.cap if ch.isupper() else symbol_scale(self.face, w)
            return g, scale, True
        lender = self.lender_for(ch)
        size = SIZE[ch]
        scale = (w.digit_height / lender.digit if size == "digit" else
                 w.cap_height / lender.cap if size == "cap" else 1.0)
        return lender.glyph(ch), scale, False
