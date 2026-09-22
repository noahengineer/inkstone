#!/usr/bin/env python3
"""Reading `<recordingId>.timeline`, and turning it into something a person can look at.

The file itself is documented by its writer, `core/storage/.../RecordingTimeline.kt`:

```
<objectId> <offsetMs> [<pageId>]\n
#gap <fromMs> <toMs>\n            the microphone was taken away
```

⚠️ **The parse rules are copied from `tools/m12/align.py`'s `parse_timeline`, deliberately, and
must stay identical to it.** Two of them are load-bearing and easy to get subtly wrong:

* **Stop at the first unparseable line.** A process killed mid-append leaves a torn tail, and
  everything after it is by definition not there. *Skipping* rather than stopping would read
  whatever bytes happened to follow.
* **Tolerate extra fields.** A newer build may add a column. Requiring an exact count would make
  a newer file truncate here — the same forward-compatibility bug the Kotlin side had and fixed.

CLAUDE.md warns that a second implementation of something the project already speaks is where the
old bugs come back. The honest answer is to *import* `align.parse_timeline`, and [parse] does
exactly that whenever `tools/m12` is importable; the copy below is only a fallback for a checkout
without it. `test_timeline.py` asserts the two agree, so they cannot drift silently.

Everything after [parse] is new: the derived views the viewer draws — the writing histogram,
page spans, and the quiet stretches where the page holds nothing and the speech holds everything.
"""

from __future__ import annotations

import os
import sys
from dataclasses import dataclass, field

_M12 = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "m12")


@dataclass(frozen=True)
class Stroke:
    object_id: str
    offset_ms: int
    page: str | None = None


@dataclass(frozen=True)
class Gap:
    """A stretch where the microphone was taken away — a phone call, another recorder.

    Distinct from a *quiet* stretch: here the audio is missing, not merely silent, and the viewer
    must say so rather than letting it read as "nobody spoke".
    """

    from_ms: int
    to_ms: int


@dataclass
class Timeline:
    strokes: list[Stroke] = field(default_factory=list)
    gaps: list[Gap] = field(default_factory=list)
    truncated: bool = False
    """True when parsing stopped early — a torn tail, so the process was killed, not stopped."""

    @property
    def first_ms(self) -> int | None:
        return min((s.offset_ms for s in self.strokes), default=None)

    @property
    def last_ms(self) -> int | None:
        return max((s.offset_ms for s in self.strokes), default=None)


def _parse_fallback(text: str) -> Timeline:
    timeline = Timeline()
    lines = [line for line in text.splitlines() if line]
    for index, line in enumerate(lines):
        parts = line.split(" ")
        if parts[0] == "#gap" and len(parts) >= 3:
            try:
                timeline.gaps.append(Gap(int(parts[1]), int(parts[2])))
            except ValueError:
                timeline.truncated = True
                break
            continue
        if len(parts) >= 2:
            try:
                offset = int(parts[1])
            except ValueError:
                timeline.truncated = True
                break
            timeline.strokes.append(Stroke(parts[0], offset, parts[2] if len(parts) >= 3 else None))
            continue
        timeline.truncated = index < len(lines) - 1
        break
    return timeline


def parse(text: str) -> Timeline:
    """Prefer `tools/m12/align.py`'s parser; fall back to the copy above if it is not importable.

    The delegation is not ceremony. `align.parse_timeline` is the parser every transcript already
    in Nextcloud was produced with, so *agreeing with it* is the property that matters — and
    importing it means the two cannot drift.
    """
    if os.path.isdir(_M12):
        if _M12 not in sys.path:
            sys.path.insert(0, _M12)
        try:
            import align  # noqa: PLC0415

            parsed = align.parse_timeline(text)
            timeline = Timeline(
                strokes=[Stroke(s.object_id, s.offset_ms, s.page) for s in parsed.strokes],
                gaps=[Gap(g.from_ms, g.to_ms) for g in parsed.gaps],
            )
            # `align` does not report truncation, so recover it the only way available: a file
            # that parsed fully yields one record per non-empty line.
            records = len(timeline.strokes) + len(timeline.gaps)
            timeline.truncated = records < len([line for line in text.splitlines() if line])
            return timeline
        except Exception:  # noqa: BLE001 - a broken sibling tool must not break the reader
            pass
    return _parse_fallback(text)


# ---------------------------------------------------------------------------
# Derived views — what the viewer actually draws
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class PageSpan:
    """One page, and the window in the recording during which it was written on.

    `index` is the page's position in the order pages were **first written on**, which is not
    necessarily its position in the PDF — a page can be returned to. The PDF page number, when
    it is knowable at all, comes from the note's own manifest (`notepdf.py`), never from here.
    """

    page: str
    first_ms: int
    last_ms: int
    strokes: int
    index: int


def page_spans(timeline: Timeline) -> list[PageSpan]:
    """`(page, first, last)` per page, in the order the pages were first written on.

    Empty for a timeline with no page column — an older recording, or one where nothing was
    written. Callers must handle that: it is the common case for anything recorded before the
    page column existed, and the fixture in `recordings-pulled/` is exactly such a file.
    """
    order: list[str] = []
    spans: dict[str, list[int]] = {}
    counts: dict[str, int] = {}
    for stroke in timeline.strokes:
        if stroke.page is None:
            continue
        if stroke.page not in spans:
            spans[stroke.page] = [stroke.offset_ms, stroke.offset_ms]
            counts[stroke.page] = 0
            order.append(stroke.page)
        else:
            spans[stroke.page][0] = min(spans[stroke.page][0], stroke.offset_ms)
            spans[stroke.page][1] = max(spans[stroke.page][1], stroke.offset_ms)
        counts[stroke.page] += 1
    return [
        PageSpan(page, spans[page][0], spans[page][1], counts[page], index)
        for index, page in enumerate(order)
    ]


def activity(timeline: Timeline, duration_ms: int, buckets: int = 240) -> list[int]:
    """Strokes per equal-width bucket across the recording — the writing histogram.

    A bucket is a *count*, not a density, so a caller wanting a normalised height divides by the
    maximum itself.

    ⚠️ A stroke whose offset is past `duration_ms` is **clamped** into the last bucket rather than
    dropped. The measured lesson had none, but a clamped stroke is visible and a dropped one is
    not, and data that disappears without saying so is the failure this whole project is about.
    """
    if buckets <= 0:
        raise ValueError("buckets must be positive")
    out = [0] * buckets
    if duration_ms <= 0:
        return out
    for stroke in timeline.strokes:
        index = stroke.offset_ms * buckets // duration_ms
        out[min(max(index, 0), buckets - 1)] += 1
    return out


@dataclass(frozen=True)
class Quiet:
    """A stretch in which nothing was written. Not the same as nothing being *said*."""

    from_ms: int
    to_ms: int

    @property
    def length_ms(self) -> int:
        return self.to_ms - self.from_ms


def quiet_runs(timeline: Timeline, duration_ms: int, threshold_ms: int = 60_000) -> list[Quiet]:
    """Stretches longer than `threshold_ms` with no stroke in them.

    The measured 48-minute lesson (docs/M12-AUDIO.md §15.6) has a median gap between strokes of
    0.65 s and a longest of 340 s — nearly six minutes of listening without writing. The long ones
    are the interesting ones: they are where the page holds nothing and the speech holds
    everything, which is the case this feature exists for. The default threshold is a minute, so
    the viewer marks a handful rather than hundreds.
    """
    if not timeline.strokes:
        return [Quiet(0, duration_ms)] if duration_ms > threshold_ms else []
    runs: list[Quiet] = []
    previous = 0
    for stroke in timeline.strokes:
        if stroke.offset_ms - previous > threshold_ms:
            runs.append(Quiet(previous, stroke.offset_ms))
        previous = max(previous, stroke.offset_ms)
    if duration_ms - previous > threshold_ms:
        runs.append(Quiet(previous, duration_ms))
    return runs


def summarise(timeline: Timeline, duration_ms: int) -> dict:
    """The numbers the library list shows per recording, without loading the transcript."""
    spans = page_spans(timeline)
    return {
        "strokes": len(timeline.strokes),
        "pages": len(spans),
        "firstStrokeMs": timeline.first_ms,
        "lastStrokeMs": timeline.last_ms,
        "gaps": len(timeline.gaps),
        "gapMs": sum(g.to_ms - g.from_ms for g in timeline.gaps),
        "truncated": timeline.truncated,
        "quietRuns": len(quiet_runs(timeline, duration_ms)),
    }
