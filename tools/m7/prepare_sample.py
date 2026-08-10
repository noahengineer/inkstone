"""Turn IAMonDo documents into the 20-page sample the M7 accuracy harness reads.

Output is one JSON file the instrumented test pushes to the device. Coordinates come
out in A4 page space: points (1/72"), origin top-left, y DOWN -- the same space
Inkstone persists ink in (SPEC 4.4).

Three things established by reading the data rather than the documentation, each of which
silently produces a plausible-looking wrong answer if missed:

  * InkML trace values are DIFFERENCE ENCODED and the mode is STICKY PER CHANNEL. See inkml.py.
  * The canvasTransform is PER DOCUMENT. 380 of the 941 documents declare identity, most of the
    rest a 180-degree affine, and some a 90-degree one. Assuming one rotation for the corpus
    renders two fifths of it upside down -- which a recogniser reports as bad handwriting, not
    as an error. Caught by drawing a page and looking at it.
  * The ground truth's line order is TRACEVIEW order, which groups by Textblock and is therefore
    not top-to-bottom on a page with a side note. The reference is re-sorted so that both arms
    are scored against the same string.
"""
import json
import sys
from pathlib import Path

from inkml import parse

A4_W_PT, A4_H_PT = 595.0, 842.0
MARGIN_PT = 20.0
def _corpus():
    if "--corpus" in sys.argv:
        return Path(sys.argv[sys.argv.index("--corpus") + 1])
    return Path(__file__).parent / "corpus" / "IAMonDo-db-1.0"


def page(path, text_only=True):
    doc = parse(path)
    if not doc["lines"]:
        return None

    wanted = {t for line in doc["lines"] for t in line["traces"]}
    ids = [i for i in doc["strokes"] if (i in wanted or not text_only)]
    pts = [p for i in ids for p in doc["strokes"][i]]
    if len(pts) < 200:
        return None

    # ⚠️ APPLY THE DOCUMENT'S OWN canvasTransform. 380 of the 941 IAMonDo documents declare
    # <mapping type="identity"/>, most of the rest a 180-degree affine, and at least one a 90
    # degree one. Assuming a single rotation for the corpus renders two fifths of it upside
    # down and some of it sideways -- and nothing reports that, because a recogniser handed
    # upside-down handwriting returns confident nonsense rather than an error. Caught by
    # drawing a page and looking at it.
    tf = doc["transform"]

    def apply(x, y):
        if tf is None or tf == "identity":
            return x, y
        return (tf[0][0] * x + tf[0][1] * y + tf[0][4], tf[1][0] * x + tf[1][1] * y + tf[1][4])

    mapped = [apply(p[0], p[1]) for p in pts]
    min_x = min(q[0] for q in mapped)
    min_y = min(q[1] for q in mapped)
    max_x = max(q[0] for q in mapped)
    max_y = max(q[1] for q in mapped)
    t0 = min(p[2] for p in pts)

    # A4, scaled from the written extent's aspect rather than from Page Bounds -- which is
    # stated in the pre-transform frame and so is transposed for the rotated documents.
    span_w = max(max_x - min_x, 1.0)
    span_h = max(max_y - min_y, 1.0)
    scale = min((A4_W_PT - 2 * MARGIN_PT) / span_w, (A4_H_PT - 2 * MARGIN_PT) / span_h)

    def px(p):
        x, y = apply(p[0], p[1])
        return [
            round((x - min_x) * scale + MARGIN_PT, 3),
            round((y - min_y) * scale + MARGIN_PT, 3),
            int(round((p[2] - t0) * 1000)),
        ]

    strokes = [{"id": i, "points": [px(p) for p in doc["strokes"][i]]} for i in ids]
    # Draw order is capture order, which is what a real note has.
    strokes.sort(key=lambda s: s["points"][0][2])

    # ⚠️ The ground truth's own line order is TRACEVIEW order, which groups by Textblock --
    # so on a page with a side note or a second block it is not top-to-bottom. Measured
    # across this sample it disagrees with page order on 211 of 452 adjacent pairs, i.e.
    # about half. Left alone that would charge both arms for the corpus's structure, and
    # would charge the segmented arm twice because it is the only one that emits an order
    # at all. So the reference is re-sorted top-to-bottom and BOTH arms are scored against
    # that same string. The disagreement count is kept and reported rather than hidden.
    placed = []
    for line in doc["lines"]:
        ys = [px(p)[1] for t in line["traces"] for p in doc["strokes"].get(t, [])]
        if ys:
            placed.append((sum(ys) / len(ys), line["text"]))
    inversions = sum(1 for a, b in zip(placed, placed[1:]) if b[0] < a[0] - 1.0)

    return {
        "id": Path(path).stem,
        "writer": doc["writer"],
        "widthPt": A4_W_PT,
        "heightPt": A4_H_PT,
        "groundTruth": [text for _, text in sorted(placed, key=lambda it: it[0])],
        "readingOrderInversions": inversions,
        "strokes": strokes,
    }


def main():
    text_only = "--all-strokes" not in sys.argv
    out = Path(sys.argv[sys.argv.index("--out") + 1]) if "--out" in sys.argv else Path("sample.json")

    # Writer-disjoint set 0, ONE PAGE PER WRITER. Taking the first 20 filenames instead
    # gives 001, 001a..001g -- twenty pages in one or two hands, which measures one
    # person's handwriting and calls it a corpus.
    corpus = _corpus()
    names = [n.strip() for n in (corpus / "0.set").read_text().split() if n.strip()]
    pages, tried, seen_writers = [], 0, set()
    for name in sorted(names):
        if len(pages) == 20:
            break
        path = corpus / (name if name.endswith(".inkml") else name + ".inkml")
        if not path.exists():
            continue
        tried += 1
        p = page(path, text_only)
        if p and p["writer"] not in seen_writers:
            seen_writers.add(p["writer"])
            pages.append(p)

    total_lines = sum(len(p["groundTruth"]) for p in pages)
    total_strokes = sum(len(p["strokes"]) for p in pages)
    total_chars = sum(len(l) for p in pages for l in p["groundTruth"])
    inversions = sum(p["readingOrderInversions"] for p in pages)
    out.write_text(json.dumps({"pages": pages}, ensure_ascii=False), encoding="utf-8")
    print(f"wrote {out} : {len(pages)} pages from {tried} tried, {len(seen_writers)} distinct writers")
    print(f"  {total_lines} ground-truth text lines, {total_strokes} strokes, {total_chars} reference characters")
    print(f"  reading-order inversions after the 180-degree fix: {inversions} of {total_lines - len(pages)} pairs")
    print(f"  text_only={text_only}")


if __name__ == "__main__":
    main()
