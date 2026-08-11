#!/usr/bin/env python3
"""M12's aligner: a lecture's transcript, anchored to the pages it was written on.

    python tools/m12/align.py --timeline r.timeline --transcript r.json --out r.md
    python tools/m12/align.py --timeline r.timeline --transcript r.json --pages pages.json

A **pure function over plain data**, deliberately: no network, no credentials, no PDF parsing.
Inputs are the sidecar this app already writes and AssemblyAI's documented response; the output is
the markdown that lands in Nextcloud. That is what makes the alignment testable before a key
exists, and it is the same reasoning that keeps `OutboxCoalescing` and `RemoteWalk` pure.


WHY THE UNIT IS A PAGE AND NOT A LINE
-------------------------------------
`docs/M12-AUDIO.md` §7 originally attached speech to a *recognised line* and emitted one fragment
per line. That was revised after the first real lesson (§15.6), for three reasons:

  * **Fragmenting destroys the explanation.** A worked example spanning eight lines over four
    minutes became eight slices with the reasoning cut across them — and the reasoning is the
    thing you wanted back.
  * **Recognition is blind to exactly what matters.** A diagram, an arrow, a crossing-out
    recognises to nothing. A text-only artifact is emptiest precisely where the page is richest.
  * **It was not buildable anyway.** `RecognisedLine` drops the stroke ids, so nothing joins the
    timeline to the recognised text; and above 500 strokes a page is flattened into a content
    stream with no per-stroke identity at all — which is the dense-lecture case M12 exists for.

So: the transcript stays **whole**, and the writing is an *annotation* into it. The note's PDF is
already in Nextcloud with its diagrams intact; a reader opens page 3 and reads the speech around
the span this file gives them. Nothing here reproduces the writing, because something better
already does.


COLLAPSING DEAD TIME — TRIMMED IN THE OUTPUT, NEVER IN THE AUDIO
----------------------------------------------------------------
A lesson contains stretches where nothing is said and nothing is written: a break, packing up,
waiting for a class to settle. Those are worth removing *from the document*.

⚠️ They are **not** worth removing from the audio before transcription, which is the tempting
version. Word timestamps come back relative to whatever was uploaded, so cutting the input shifts
every offset after the first cut and requires a cut-map to undo — and an error there mis-aligns a
whole lesson in a way that reads as entirely plausible. It would also risk deleting real speech on
an amplitude threshold ("quiet" in a lecture theatre is a teacher at a whiteboard), and §8 trashes
the original once transcribed, so the mistake would be permanent. The saving is a few tens of
dollars a year against a bill of about $112.

Collapsing here costs nothing and cannot mis-time anything: the timestamps are the API's own, and
the only thing dropped is a run of *nothing*. The signal is better than an audio threshold too,
because it uses something AssemblyAI does not have — **the timeline knows when the pen was
moving.** A stretch is dead only if there is no speech **and** no writing.
"""

import argparse
import json
import sys
from dataclasses import dataclass, field

# ⚠️ AssemblyAI mixes units: `words[].start/end` are MILLISECONDS, `audio_duration` is SECONDS.
# Named so that a conversion cannot be done by eye. Verified against the current API docs.
MS_PER_SECOND = 1000

# A run with neither speech nor ink longer than this is collapsed to one line. Thirty seconds is
# above the longest natural pause in speech and well below a real break; the measured lesson had
# 24 writing pauses over 15 s that were full of talking, and none of those are dead.
DEAD_RUN_MS = 30_000

# How far either side of a page's own writing to include speech, in milliseconds.
#
# ⚠️ §7's −15 s / +5 s were guesses for a *line*. A page spans minutes, so the asymmetry matters
# far less, but the direction is the same and for the same reason: **writing lags speech.** The
# measured lesson (§15.6) says the likelier error is these being too *short* — 24 pauses exceeded
# 15 s, the longest 340 s — so they are generous, and §15.6's rule is that any change which
# narrows them is suspect by default. Duplicated transcript costs kilobytes; omitted transcript
# cannot be recovered, because §8 deletes the audio.
PAGE_LEAD_IN_MS = 30_000
PAGE_LEAD_OUT_MS = 10_000


@dataclass
class Word:
    text: str
    start_ms: int
    end_ms: int
    speaker: str | None = None


@dataclass
class Stroke:
    object_id: str
    offset_ms: int
    page: str | None = None


@dataclass
class Gap:
    from_ms: int
    to_ms: int


@dataclass
class Timeline:
    strokes: list[Stroke] = field(default_factory=list)
    gaps: list[Gap] = field(default_factory=list)


def parse_timeline(text: str) -> Timeline:
    """Read `recordings/<id>.timeline`, matching `RecordingTimeline.read`'s rules exactly.

    Two of those rules are load-bearing and are easy to get subtly wrong here:

    * **Stop at the first unparseable line.** A process killed mid-append leaves a torn tail, and
      everything after it is by definition not there. Skipping rather than stopping would read
      whatever bytes happened to follow.
    * **Tolerate extra fields.** A newer build may add a column (a page id is the expected one).
      Requiring an exact count would make that newer file truncate here — the same forward-
      compatibility bug the Kotlin side had and fixed.
    """
    timeline = Timeline()
    for line in text.splitlines():
        if not line:
            continue
        parts = line.split(" ")
        if parts[0] == "#gap" and len(parts) >= 3:
            try:
                timeline.gaps.append(Gap(int(parts[1]), int(parts[2])))
            except ValueError:
                break
            continue
        if len(parts) >= 2:
            try:
                offset = int(parts[1])
            except ValueError:
                break
            # The third column is the page id when a build that writes it made the file. Older
            # recordings have two columns and align at whole-lesson granularity instead.
            page = parts[2] if len(parts) >= 3 else None
            timeline.strokes.append(Stroke(parts[0], offset, page))
            continue
        break
    return timeline


def parse_transcript(payload: dict) -> list[Word]:
    """Pull the flat `words[]` array out of an AssemblyAI transcript response.

    `words[]` is used rather than `utterances[]` because it is present with or without
    diarization, and carries `speaker` when diarization is on — so one code path covers both.
    """
    words: list[Word] = []
    for w in payload.get("words") or []:
        words.append(
            Word(
                text=w.get("text", ""),
                start_ms=int(w.get("start", 0)),
                end_ms=int(w.get("end", 0)),
                speaker=w.get("speaker"),
            )
        )
    return words


def dominant_speaker(words: list[Word]) -> str | None:
    """The speaker with the most speech time — a **heuristic** for "the teacher".

    ⚠️ `docs/M12-AUDIO.md` §13 spike 1 assumed diarization would identify the teacher. It does
    not: AssemblyAI returns anonymous labels (`"A"`, `"B"`) with no enrolment and no naming. The
    most talking in a lesson is a decent proxy and nothing more, which is why the label is *kept*
    in the output rather than used to filter: a student's question is often the reason a line was
    written, and §15.6's rule is to include more, not less.
    """
    if not words or all(w.speaker is None for w in words):
        return None
    totals: dict[str, int] = {}
    for w in words:
        if w.speaker is not None:
            totals[w.speaker] = totals.get(w.speaker, 0) + max(0, w.end_ms - w.start_ms)
    return max(totals, key=totals.get) if totals else None


def page_spans(timeline: Timeline) -> list[tuple[str, int, int]]:
    """`(page, first stroke, last stroke)` per page, in the order the pages were first written on.

    Returns an empty list for a recording whose timeline has no page column — an older file, or a
    lesson nobody wrote in. That is not an error: the transcript is still worth having whole, and
    [render] falls back to emitting it without page anchors.
    """
    spans: dict[str, list[int]] = {}
    order: list[str] = []
    for stroke in timeline.strokes:
        if stroke.page is None:
            continue
        if stroke.page not in spans:
            spans[stroke.page] = [stroke.offset_ms, stroke.offset_ms]
            order.append(stroke.page)
        else:
            spans[stroke.page][0] = min(spans[stroke.page][0], stroke.offset_ms)
            spans[stroke.page][1] = max(spans[stroke.page][1], stroke.offset_ms)
    return [(p, spans[p][0], spans[p][1]) for p in order]


def dead_runs(words: list[Word], timeline: Timeline, audio_ms: int) -> list[tuple[int, int]]:
    """Stretches with **no speech and no writing** — the thing worth collapsing in the output.

    Built from the union of both signals rather than from audio alone, which is the whole
    improvement: a silent stretch during which somebody was writing is not dead, and a stretch
    with talking but no writing is very much not dead.

    A marked `#gap` — the microphone was taken away — is deliberately **not** treated as dead. No
    audio was captured there, so the truthful thing to say is "nothing was recorded", not "nothing
    happened"; [render] labels the two differently.
    """
    busy: list[tuple[int, int]] = [(w.start_ms, w.end_ms) for w in words]
    busy += [(s.offset_ms, s.offset_ms) for s in timeline.strokes]
    busy += [(g.from_ms, g.to_ms) for g in timeline.gaps]
    if not busy:
        return [(0, audio_ms)] if audio_ms > DEAD_RUN_MS else []
    busy.sort()

    runs: list[tuple[int, int]] = []
    cursor = 0
    for start, end in busy:
        if start - cursor >= DEAD_RUN_MS:
            runs.append((cursor, start))
        cursor = max(cursor, end)
    if audio_ms - cursor >= DEAD_RUN_MS:
        runs.append((cursor, audio_ms))
    return runs


def clock(ms: int) -> str:
    total = max(0, ms) // MS_PER_SECOND
    return f"{total // 3600:02d}:{total % 3600 // 60:02d}:{total % 60:02d}"


def render(
    words: list[Word],
    timeline: Timeline,
    audio_ms: int,
    note_title: str,
    document_id: str,
    recorded_at: str,
    model: str,
) -> str:
    """The merged markdown — one whole transcript, annotated rather than fragmented."""
    teacher = dominant_speaker(words)
    spans = page_spans(timeline)
    dead = dead_runs(words, timeline, audio_ms)

    out: list[str] = []
    out.append("---")
    out.append(f"note: {note_title}")
    out.append(f"documentId: {document_id}")
    out.append(f"recorded: {recorded_at}")
    out.append(f"duration: {clock(audio_ms)}")
    out.append(f"recogniser: assemblyai/{model}")
    if teacher is not None:
        # Stated as a guess, in the artifact, because it is one. See [dominant_speaker].
        out.append(f"likelyTeacher: {teacher}   # heuristic: most speech time, not identification")
    out.append("---")
    out.append("")
    out.append(f"# {note_title}")
    out.append("")
    out.append(
        "The transcript below is complete and in order. Timestamps are from the start of the "
        "recording. The note's own PDF holds the writing and the diagrams — this file says *when* "
        "each page was written, so the two can be read together."
    )
    out.append("")

    if spans:
        out.append("## When each page was written")
        out.append("")
        for page, first, last in spans:
            out.append(f"- **page `{page}`** — written {clock(first)} to {clock(last)}")
        out.append("")
    else:
        out.append(
            "_No page information in this recording's timeline, so the transcript is not anchored "
            "to pages. Either nothing was written, or it predates the app recording page ids._"
        )
        out.append("")

    for gap in timeline.gaps:
        out.append(
            f"> ⚠️ **No audio captured between {clock(gap.from_ms)} and {clock(gap.to_ms)}** — "
            "something else took the microphone. The speech from that stretch is missing, not silent."
        )
    if timeline.gaps:
        out.append("")

    out.append("## Transcript")
    out.append("")
    out.extend(_transcript_body(words, dead, spans))
    return "\n".join(out) + "\n"


def _transcript_body(
    words: list[Word],
    dead: list[tuple[int, int]],
    spans: list[tuple[str, int, int]],
) -> list[str]:
    """Paragraphs, broken at dead runs, speaker changes and page boundaries."""
    lines: list[str] = []
    if not words:
        lines.append("_Nothing was transcribed. The recording may be silent._")
        return lines

    page_starts = {first: page for page, first, _ in spans}
    buffer: list[str] = []
    current_speaker: str | None = words[0].speaker
    paragraph_start = words[0].start_ms

    def flush() -> None:
        if not buffer:
            return
        who = f"**{current_speaker}:** " if current_speaker is not None else ""
        lines.append(f"**[{clock(paragraph_start)}]** {who}{' '.join(buffer)}")
        lines.append("")
        buffer.clear()

    dead_iter = list(dead)
    for word in words:
        # A stretch with neither speech nor ink: say so once and carry on. Nothing is cut from
        # the audio and no timestamp moves — only the document skips it.
        while dead_iter and dead_iter[0][1] <= word.start_ms:
            start, end = dead_iter.pop(0)
            flush()
            lines.append(f"_— {clock(start)} to {clock(end)}: nothing said and nothing written —_")
            lines.append("")
            paragraph_start = word.start_ms

        # A page turn is a natural break in the lesson, so it is a natural break in the text.
        for first, page in sorted(page_starts.items()):
            if paragraph_start < first <= word.start_ms:
                flush()
                lines.append(f"### page `{page}` — writing starts {clock(first)}")
                lines.append("")
                paragraph_start = word.start_ms

        if word.speaker != current_speaker:
            flush()
            current_speaker = word.speaker
            paragraph_start = word.start_ms

        if not buffer:
            paragraph_start = word.start_ms
        buffer.append(word.text)

    flush()
    return lines


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--timeline", required=True)
    ap.add_argument("--transcript", required=True, help="AssemblyAI transcript JSON")
    ap.add_argument("--out", help="write here instead of stdout")
    ap.add_argument("--title", default="Untitled")
    ap.add_argument("--document-id", default="unknown")
    ap.add_argument("--recorded-at", default="unknown")
    args = ap.parse_args()

    timeline = parse_timeline(open(args.timeline, encoding="utf-8").read())
    payload = json.load(open(args.transcript, encoding="utf-8"))
    words = parse_transcript(payload)

    # ⚠️ SECONDS here, milliseconds everywhere else. See MS_PER_SECOND.
    audio_ms = int(float(payload.get("audio_duration") or 0) * MS_PER_SECOND)
    # The model that actually ran, not the one requested: `speech_models` is a fallback list, so
    # the two differ. §7's front matter wants the truth.
    model = payload.get("speech_model_used") or payload.get("speech_model") or "unknown"

    markdown = render(words, timeline, audio_ms, args.title, args.document_id, args.recorded_at, model)
    if args.out:
        open(args.out, "w", encoding="utf-8").write(markdown)
        print(f"wrote {args.out}: {len(markdown):,} chars, {len(words):,} words, "
              f"{len(timeline.strokes):,} strokes, {len(page_spans(timeline))} page(s)")
    else:
        sys.stdout.write(markdown)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
