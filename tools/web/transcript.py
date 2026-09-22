#!/usr/bin/env python3
"""Reading the merged transcript back — the inverse of `tools/m12/align.py`'s `render`.

`align.render` turns a timeline and an AssemblyAI response into one markdown document and puts
it in Nextcloud. That document is the artifact: it is what a person reads and what an assistant
reads, and once the audio is trashed (docs/M12-AUDIO.md §8) it is the **only** record of what was
said. This module turns it back into structure so the viewer can scrub it and the MCP server can
answer questions about it.

⚠️ **This parser is the reader that is not the writer.** M4 shipped PDFs that were valid,
parseable and blank in every viewer that was not the library that wrote them, and CLAUDE.md has
carried that lesson ever since. Re-reading `render`'s output with an independent parser is the
same check applied here — `test_transcript.py` round-trips a rendered document and asserts the
structure comes back, so a change to `render`'s wording that this cannot read fails a test rather
than silently emptying the website.

## Forward compatibility

A line this does not recognise becomes a `prose` segment rather than an error. A newer `align.py`
may add a heading or a note; the viewer should show it, not drop it and not crash. That is SPEC
§5.8's rule for the bundle, applied to the one other file this project generates.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

MS_PER_SECOND = 1000

# `**[00:12:04]** **A:** words…`  — the speaker half is absent when diarization was off.
_PARAGRAPH = re.compile(r"^\*\*\[(\d+:\d{2}:\d{2})\]\*\*\s+(?:\*\*([^*:]+):\*\*\s+)?(.*)$")

# `_— 00:13:58 to 00:15:11: nothing said and nothing written —_`
_DEAD = re.compile(r"^_[—-]\s*(\d+:\d{2}:\d{2})\s+to\s+(\d+:\d{2}:\d{2}):\s*nothing said and nothing written\s*[—-]_$")

# `### page ` + backticked id + ` — writing starts 00:04:11`
_PAGE_HEADING = re.compile(r"^###\s+page\s+`([^`]+)`\s*[—-]\s*writing starts\s+(\d+:\d{2}:\d{2})\s*$")

# `- **page `<id>`** — written 00:00:06 to 00:07:41`
_PAGE_BULLET = re.compile(r"^-\s+\*\*page\s+`([^`]+)`\*\*\s*[—-]\s*written\s+(\d+:\d{2}:\d{2})\s+to\s+(\d+:\d{2}:\d{2})\s*$")

# `> ⚠️ **No audio captured between 00:31:00 and 00:33:12** — …`
_GAP = re.compile(r"^>.*\*\*No audio captured between\s+(\d+:\d{2}:\d{2})\s+and\s+(\d+:\d{2}:\d{2})\*\*")

_NOTHING_TRANSCRIBED = "_Nothing was transcribed. The recording may be silent._"


def parse_clock(value: str) -> int:
    """`HH:MM:SS` back to milliseconds. The exact inverse of `align.clock`."""
    parts = [int(p) for p in value.split(":")]
    if len(parts) == 3:
        hours, minutes, seconds = parts
    elif len(parts) == 2:
        hours, minutes, seconds = 0, parts[0], parts[1]
    else:
        raise ValueError(f"not a timestamp: {value!r}")
    return ((hours * 60 + minutes) * 60 + seconds) * MS_PER_SECOND


def clock(ms: int) -> str:
    """Milliseconds to `HH:MM:SS`, identical to `align.clock` so the two agree on every value."""
    total = max(0, ms) // MS_PER_SECOND
    return f"{total // 3600:02d}:{total % 3600 // 60:02d}:{total % 60:02d}"


@dataclass(frozen=True)
class Segment:
    """One addressable thing in the transcript body.

    `kind` is one of:

    * `speech` — a paragraph. `at_ms` is when it starts, `speaker` may be None.
    * `dead` — a stretch with neither speech nor ink. `at_ms`..`to_ms`.
    * `page` — a page turn. `page` is the id, `at_ms` is when writing on it started.
    * `prose` — anything else, kept verbatim so a newer `align.py` is not silently truncated.
    """

    kind: str
    at_ms: int
    text: str = ""
    speaker: str | None = None
    to_ms: int | None = None
    page: str | None = None


@dataclass
class Transcript:
    front: dict[str, str] = field(default_factory=dict)
    title: str = ""
    intro: str = ""
    page_spans: list[dict] = field(default_factory=list)
    gaps: list[dict] = field(default_factory=list)
    segments: list[Segment] = field(default_factory=list)
    unanchored: bool = False
    """True when the document says it has no page information — an older recording."""
    empty: bool = False
    """True when nothing was transcribed. The recording may have been silent."""

    @property
    def duration_ms(self) -> int | None:
        raw = self.front.get("duration")
        if not raw:
            return None
        try:
            return parse_clock(raw)
        except ValueError:
            return None

    @property
    def document_id(self) -> str | None:
        return self.front.get("documentId")

    @property
    def speakers(self) -> list[str]:
        seen: list[str] = []
        for segment in self.segments:
            if segment.speaker and segment.speaker not in seen:
                seen.append(segment.speaker)
        return seen

    @property
    def word_count(self) -> int:
        return sum(len(s.text.split()) for s in self.segments if s.kind == "speech")

    def text(self) -> str:
        """Just the speech, in order — what a search index wants."""
        return "\n".join(s.text for s in self.segments if s.kind == "speech")


def _strip_trailing_comment(value: str) -> str:
    """Drop the `   # …` note `align.render` puts on `likelyTeacher`, and nothing else.

    ⚠️ Stripping at the first `#` anywhere truncates any value that legitimately contains one, and
    a note title is exactly such a value: `RemoteName.FORBIDDEN` in `:core:model` is
    ``\\/<>:"|?*`` and deliberately omits `#`, so `Exercise #4 - Straight lines.pdf` is a legal
    note name. That title reached the front matter verbatim, and this read it back as `Exercise`.

    `align` writes the comment with three spaces in front of it (`A   # heuristic: …`), so a run
    of two or more spaces is what separates a comment from a title that happens to contain a hash.
    A title would have to be written with two consecutive spaces before a `#` to be confused, and
    it would still only lose the tail of a display string.
    """
    return re.split(r"\s{2,}#", value, maxsplit=1)[0].strip()


def _front_matter(lines: list[str]) -> tuple[dict[str, str], int]:
    """Read a leading `---` block. Returns the mapping and the index of the line after it.

    ⚠️ Strips the trailing `# …` comment `render` puts on `likelyTeacher`. Keeping it would make
    the value read as `A   # heuristic: most speech time, not identification`, which is exactly
    the sort of thing that looks fine in a debug print and shows up wrong in the UI.
    """
    if not lines or lines[0].strip() != "---":
        return {}, 0
    front: dict[str, str] = {}
    for index in range(1, len(lines)):
        line = lines[index]
        if line.strip() == "---":
            return front, index + 1
        if ":" in line:
            key, value = line.split(":", 1)
            front[key.strip()] = _strip_trailing_comment(value)
    # No closing fence: treat the whole thing as body rather than swallowing the document.
    return {}, 0


def front_matter(markdown: str) -> dict[str, str]:
    """Just the `---` block, from however much of the document you happen to have.

    ⭐ This exists so a caller can answer *"how long is this lesson"* from a **byte range** rather
    than a download. `align.render` writes `duration` into the front matter (align.py:253), which
    it read off the Ogg container — so once a lecture is transcribed the honest length is in the
    first few hundred bytes of the `.md`, and the `.opus` it came from is in the Nextcloud trash.

    ⚠️ A truncated read has no closing `---`, and `_front_matter` answers `{}` for that rather
    than swallowing the rest of the document — so a range too short to reach the fence returns
    nothing at all instead of a half-read mapping. That is the safe direction, and the caller's
    job is to ask for enough.
    """
    return _front_matter(markdown.replace("\r\n", "\n").split("\n"))[0]


def parse(markdown: str) -> Transcript:
    """Structure a merged transcript. Never raises — an unreadable line becomes `prose`."""
    lines = markdown.replace("\r\n", "\n").split("\n")
    front, cursor = _front_matter(lines)
    out = Transcript(front=front)

    in_body = False
    intro_parts: list[str] = []

    for line in lines[cursor:]:
        stripped = line.strip()
        if not stripped:
            continue

        if not in_body:
            if stripped.startswith("# ") and not out.title:
                out.title = stripped[2:].strip()
                continue
            if stripped == "## Transcript":
                in_body = True
                continue
            if stripped.startswith("## "):
                continue
            bullet = _PAGE_BULLET.match(stripped)
            if bullet:
                out.page_spans.append(
                    {
                        "page": bullet.group(1),
                        "firstMs": parse_clock(bullet.group(2)),
                        "lastMs": parse_clock(bullet.group(3)),
                    }
                )
                continue
            gap = _GAP.match(stripped)
            if gap:
                out.gaps.append({"fromMs": parse_clock(gap.group(1)), "toMs": parse_clock(gap.group(2))})
                continue
            if "No page information in this recording" in stripped:
                out.unanchored = True
                continue
            intro_parts.append(stripped)
            continue

        if stripped == _NOTHING_TRANSCRIBED:
            out.empty = True
            continue

        paragraph = _PARAGRAPH.match(stripped)
        if paragraph:
            out.segments.append(
                Segment(
                    kind="speech",
                    at_ms=parse_clock(paragraph.group(1)),
                    speaker=paragraph.group(2),
                    text=paragraph.group(3).strip(),
                )
            )
            continue

        dead = _DEAD.match(stripped)
        if dead:
            out.segments.append(
                Segment(kind="dead", at_ms=parse_clock(dead.group(1)), to_ms=parse_clock(dead.group(2)))
            )
            continue

        heading = _PAGE_HEADING.match(stripped)
        if heading:
            out.segments.append(
                Segment(kind="page", at_ms=parse_clock(heading.group(2)), page=heading.group(1))
            )
            continue

        # Unrecognised. Anchor it to the last known moment so the viewer can still place it.
        previous = out.segments[-1].at_ms if out.segments else 0
        out.segments.append(Segment(kind="prose", at_ms=previous, text=stripped))

    out.intro = " ".join(intro_parts)
    return out


def search(transcript: Transcript, needle: str, context: int = 0) -> list[dict]:
    """Case-insensitive substring hits, each with its timestamp so a caller can jump to it.

    `context` is how many neighbouring speech segments to include either side, which is what makes
    a hit useful to a reader rather than merely locatable: a question and its answer are two
    segments, and returning only the one that matched is how a transcript search reads as though
    the teacher never replied.
    """
    if not needle.strip():
        return []
    lowered = needle.lower()
    speech = [(index, s) for index, s in enumerate(transcript.segments) if s.kind == "speech"]
    hits: list[dict] = []
    for position, (_, segment) in enumerate(speech):
        if lowered not in segment.text.lower():
            continue
        window = speech[max(0, position - context) : position + context + 1]
        hits.append(
            {
                "atMs": segment.at_ms,
                "at": clock(segment.at_ms),
                "speaker": segment.speaker,
                "text": segment.text,
                "context": [
                    {"atMs": s.at_ms, "at": clock(s.at_ms), "speaker": s.speaker, "text": s.text}
                    for _, s in window
                ]
                if context
                else [],
            }
        )
    return hits
