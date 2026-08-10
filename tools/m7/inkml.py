"""IAMonDo InkML -> plain (x, y, t) strokes + line-level ground truth.

The one thing that has to be right: InkML trace values are DIFFERENCE ENCODED and the
mode is STICKY PER CHANNEL. A value prefixed `'` is a first difference, `"` a second
difference, and an unprefixed value continues in whatever mode that channel was last
put into. Read as absolutes they decode to noise that still looks like a trajectory.
"""
import re

# defusedxml, not the stdlib parser: these files are third-party research data and
# stdlib ElementTree resolves external entities by default.
import defusedxml.ElementTree as ET

TOKEN = re.compile(r"([\'\"])?\s*([-+]?(?:\d+\.?\d*|\.\d+))")


def decode_trace(text, channels=4):
    """-> list of tuples, one per point, each `channels` long, absolute."""
    toks = TOKEN.findall(text)
    if not toks:
        return []
    vals, modes, deltas = [0.0] * channels, ["a"] * channels, [0.0] * channels
    points, ch, started = [], 0, False
    cur = [0.0] * channels
    for prefix, num in toks:
        v = float(num)
        if prefix:
            modes[ch] = prefix
        m = modes[ch]
        if not started or m == "a":
            vals[ch] = v
            deltas[ch] = 0.0
        elif m == "'":
            deltas[ch] = v
            vals[ch] += v
        else:  # '"' second difference
            deltas[ch] += v
            vals[ch] += deltas[ch]
        cur[ch] = vals[ch]
        ch += 1
        if ch == channels:
            points.append(tuple(cur))
            cur = [0.0] * channels
            ch = 0
            started = True
    return points


def _ann(node, name):
    for a in node.findall("annotation"):
        if a.get("type") == name:
            return (a.text or "").strip()
    return None


def parse(path):
    """-> dict with strokes {id: [(x,y,t)]}, lines [{text, trace_ids}], page bounds."""
    root = ET.parse(path).getroot()
    strokes = {}
    for tr in root.findall("trace"):
        tid = tr.get("{http://www.w3.org/XML/1998/namespace}id") or tr.get("id")
        pts = decode_trace(tr.text or "")
        if pts:
            strokes[tid] = [(p[0], p[1], p[2]) for p in pts]

    lines = []

    def walk(node):
        t = _ann(node, "type")
        if t == "Textline":
            refs = []

            def collect(n):
                r = n.get("traceDataRef")
                if r:
                    refs.append(r.lstrip("#"))
                for c in n.findall("traceView"):
                    collect(c)

            collect(node)
            text = _ann(node, "transcription")
            if text:
                lines.append({"text": text, "traces": refs})
            return
        for c in node.findall("traceView"):
            walk(c)

    for tv in root.findall("traceView"):
        walk(tv)

    # The per-document canvasTransform. 380 of the 941 documents declare
    # <mapping type="identity"/> and the rest an affine that is a 180-degree rotation --
    # so assuming one of the two flips two fifths of the corpus upside down, which the
    # recogniser reports as bad handwriting rather than as an error.
    transform = None
    for defs in root.iter("canvasTransform"):
        for mapping in defs.iter("mapping"):
            if mapping.get("type") == "identity":
                transform = "identity"
            else:
                for matrix in mapping.iter("matrix"):
                    rows = [r.strip().split() for r in (matrix.text or "").split(",") if r.strip()]
                    if len(rows) >= 2:
                        transform = [[float(v) for v in rows[0]], [float(v) for v in rows[1]]]
        break

    bounds = _ann(root, "Page Bounds")
    return {
        "strokes": strokes,
        "lines": lines,
        "bounds": bounds,
        "writer": _ann(root, "authorId"),
        "transform": transform,
    }
