"""Raster contact sheets (PNG) for looking at strokes -- fonts, synthetic samples, model samples.

The SVG sheets sample.py writes are the record; these PNGs are what a person (or a reviewer
reading an image) looks at. Pillow only, no anti-aliasing tricks: a line is a line.
"""

from __future__ import annotations

from PIL import Image, ImageDraw, ImageFont


def draw_rows(rows, path, scale=40.0, pad=20, row_gap=1.4, width=None, label_width=180, line=2, font=None,
              label_chars=28):
    """rows: list of (label, strokes) where strokes are lists of (x, y) in x-heights, y UP.

    Each row is drawn on its own baseline; the image is as wide as the widest row. `font` is a Pillow
    font for the labels (Pillow's default has no maths symbols); None keeps the default.
    """
    measured = []
    for label, strokes in rows:
        xs = [p[0] for s in strokes for p in s] or [0.0]
        ys = [p[1] for s in strokes for p in s] or [0.0]
        measured.append((label, strokes, min(xs), max(xs), min(ys), max(ys)))
    top = max((m[5] for m in measured), default=1.0)
    bottom = min((m[4] for m in measured), default=0.0)
    row_h = (max(top, 1.6) - min(bottom, -0.8)) * scale * row_gap
    w = width or int(label_width + max((m[3] - min(0.0, m[2])) for m in measured) * scale + 2 * pad)
    h = int(len(rows) * row_h + 2 * pad)
    img = Image.new("RGB", (max(w, 200), max(h, 60)), "white")
    d = ImageDraw.Draw(img)
    font = font or ImageFont.load_default()
    for k, (label, strokes, x0, _, _, _) in enumerate(measured):
        base = pad + k * row_h + max(top, 1.6) * scale
        d.line([(label_width, base), (img.width - pad, base)], fill=(225, 225, 235))
        d.line([(label_width, base - scale), (img.width - pad, base - scale)], fill=(240, 228, 228))
        d.text((pad, base - scale), label[:label_chars], fill=(90, 90, 90), font=font)
        ox = label_width - min(0.0, x0) * scale
        for s in strokes:
            pts = [(ox + x * scale, base - y * scale) for x, y in s]
            if len(pts) == 1:
                d.ellipse([pts[0][0] - 1, pts[0][1] - 1, pts[0][0] + 1, pts[0][1] + 1], fill="black")
            else:
                d.line(pts, fill="black", width=line, joint="curve")
    img.save(path)
    return path
