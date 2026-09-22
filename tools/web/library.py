#!/usr/bin/env python3
"""The index: what notes and recordings exist, and everything cheap that can be said about them.

This is the layer both front ends sit on — the website (`server.py`) and the MCP server
(`mcp_server.py`). They differ in how they present things and not at all in what they know, which
is deliberate: an assistant answering a question about a lesson and a person scrubbing that lesson
should never disagree about what is in Nextcloud.

## The walk

The same walk `tools/m12/watch.py` does, with the same three rules, because they were each paid
for once already:

* **breadth-first, bounded at `MAX_DEPTH`** — an unbounded crawl of somebody's whole Nextcloud is
  what `RemoteWalk`'s rule 3 refuses to do on the device, and *"the user's Nextcloud contains their
  photo library"* does not stop being true here;
* **skip `is_self`**, never a name comparison — at the account root the collection's own name is
  the uid, which matches no child, so a name test re-walks the root as its own child to the bound;
* **stop at `.inkstone-media`**, whose children are the per-note directories.

One rule is different, and it is the reason this is not just a call into `watch.discover`:
**every recording is kept, including the ones that already have a transcript.** The watcher's job
is to find work, so a finished recording is invisible to it. This tool's job is to show you your
lessons, and a finished recording is the only kind worth reading.

## What is cheap and what is not

A listing is one PROPFIND per folder. A note's *title*, its page order and its recognised
handwriting all live inside the published PDF (`notepdf.py`), so learning them means downloading
it — which for a library of lecture notes is hundreds of megabytes. So:

* [scan] is the fast pass: names, sizes, folders, which recordings have transcripts. No PDF is
  downloaded. This is what the library page shows and what opens in about a second.
* [note_detail] downloads **one** PDF, on demand, and caches it against its ETag.
* [index_notes] is the explicit slow pass that fetches everything so handwriting becomes
  searchable. It reports progress and is never triggered by merely looking at a page.

⚠️ The cache is keyed on the **ETag**, which is why `nextcloud.py` now returns one. A cache keyed
on the path alone would serve a note's old title forever after a rename, and a cache keyed on
`size` would miss every edit that happened not to change the byte count.
"""

from __future__ import annotations

import hashlib
import json
import os
import time
from dataclasses import asdict, dataclass, field

import cachekey
import notepdf
import oggopus
import timeline as timeline_mod
import transcript as transcript_mod
from source import Entry, Source, SourceError

MEDIA_DIR = ".inkstone-media"
AUDIO_EXT = ".opus"
TIMELINE_EXT = ".timeline"
TRANSCRIPT_EXT = ".md"

# Three levels covers `Subject/Topic/.inkstone-media`. See the module docstring.
MAX_DEPTH = 3

# 34.3 kbit/s, measured over a real 48-minute lesson (docs/M12-AUDIO.md §15.3) rather than the
# 24 that was asked for. Only ever used to turn a byte count into "roughly how long is this",
# and labelled as an estimate everywhere it is shown.
BITS_PER_SECOND = 34_300

# Enough of a transcript to reach the closing `---`. `align.render` writes at most seven short
# keys (align.py:249-258) and the longest of them is a note title, so 2 KB is generous by an order
# of magnitude — and being generous is the right error, because `transcript.front_matter` answers
# `{}` rather than half a mapping when the fence is past the window.
FRONT_MATTER_BYTES = 2048


@dataclass
class Recording:
    """One lecture: its audio, its timeline, and its transcript, wherever each of them got to."""

    document_id: str
    recording_id: str
    directory: str
    folder: str
    audio_bytes: int = 0
    duration_ms: int = 0
    """How long the lesson is, read from the transcript's front matter. 0 when not known yet."""
    has_audio: bool = False
    has_timeline: bool = False
    has_transcript: bool = False
    transcript_etag: str = ""
    timeline_etag: str = ""
    note_title: str = ""
    note_path: str = ""

    @property
    def key(self) -> str:
        """Stable id for a URL. `documentId/recordingId` is unique by construction."""
        return f"{self.document_id}/{self.recording_id}"

    @property
    def audio_path(self) -> str:
        return f"{self.directory}/{self.recording_id}{AUDIO_EXT}"

    @property
    def timeline_path(self) -> str:
        return f"{self.directory}/{self.recording_id}{TIMELINE_EXT}"

    @property
    def transcript_path(self) -> str:
        return f"{self.directory}/{self.recording_id}{TRANSCRIPT_EXT}"

    @property
    def estimated_minutes(self) -> float:
        """⛔ This was `audio_bytes × 8 / bitrate` and nothing else, which is 0 for every
        transcribed lecture — i.e. for **every lecture in the steady state**, because
        `tools/m12/watch.py:206` trashes the recording once its transcript is verified. So the
        library showed a length only for the lessons you can do least with, and MCP's
        `list_lectures` promised *"how long it is"* and printed `approx 0 min` for all the
        finished ones.

        The transcript's own `duration` is the better answer anyway: `align` read it off the Ogg
        container's granule (M12), so it is measured rather than derived from a bitrate
        `MediaRecorder` treats as a hint — the same 42% error CLAUDE.md records.
        """
        if self.duration_ms > 0:
            return self.duration_ms / 60_000
        return self.audio_bytes * 8 / BITS_PER_SECOND / 60 if self.audio_bytes else 0.0

    @property
    def state(self) -> str:
        """What a person needs to know at a glance, in one word.

        `transcribed` — done, readable, the audio has been trashed as §8 intends.
        `awaiting`    — audio is up, the watcher has not run or has not finished.
        `partial`     — something is missing. Worth saying rather than hiding.
        """
        if self.has_transcript:
            return "transcribed"
        if self.has_audio and self.has_timeline:
            return "awaiting"
        return "partial"


@dataclass
class Note:
    """A published note — an ordinary PDF that happens to have a bundle inside it."""

    path: str
    name: str
    folder: str
    size: int = 0
    etag: str = ""
    document_id: str | None = None
    title: str = ""
    pages: int = 0
    is_inkstone: bool | None = None
    """None until the PDF has been opened. False for a stranger's PDF sitting in the same folder."""

    @property
    def display_title(self) -> str:
        return self.title or self.name.removesuffix(".pdf")


@dataclass
class Scan:
    notes: list[Note] = field(default_factory=list)
    recordings: list[Recording] = field(default_factory=list)
    folders: list[str] = field(default_factory=list)
    walked: int = 0
    took_ms: int = 0
    source: str = ""
    error: str = ""


class Cache:
    """A plain directory of JSON files, keyed by whatever the caller says identifies the value.

    Deliberately not a database. There is nothing here that cannot be recomputed by downloading
    the file again, so the worst a corrupt entry can cost is one re-fetch — and a cache whose
    failure mode is "do the work again" needs no integrity story at all.
    """

    def __init__(self, root: str) -> None:
        self.root = root
        os.makedirs(root, exist_ok=True)

    def _path(self, kind: str, key: str) -> str:
        # ⚠️ `cachekey.key`, not `sha256(joined_string)`. Callers pass `f"{path}|{etag}"`, and a
        # `|` inside a filename — which Nextcloud permits — moved the boundary and gave two
        # different files one key. See cachekey.py.
        return os.path.join(self.root, f"{kind}-{cachekey.key(kind, key)}.json")

    def get(self, kind: str, key: str) -> dict | None:
        path = self._path(kind, key)
        if not os.path.exists(path):
            return None
        try:
            with open(path, encoding="utf-8") as handle:
                return json.load(handle)
        except Exception:  # noqa: BLE001 - a bad cache entry is a cache miss, never an error
            return None

    def put(self, kind: str, key: str, value: dict) -> None:
        # Re-created on every write for the same reason `MediaCache` does it: the directory can be
        # removed while the process is running, and a cache must never fail permanently.
        os.makedirs(self.root, exist_ok=True)
        path = self._path(kind, key)
        # Write-temp-then-rename, the same rule the app follows everywhere (CLAUDE.md rule 10).
        # Cheap here, and it means a killed process leaves a missing entry rather than a truncated
        # one that parses as valid JSON with half a note's pages in it.
        temporary = f"{path}.tmp"
        try:
            with open(temporary, "w", encoding="utf-8") as handle:
                json.dump(value, handle)
            os.replace(temporary, path)
        except Exception:  # noqa: BLE001
            if os.path.exists(temporary):
                os.remove(temporary)

    def clear(self) -> int:
        removed = 0
        for name in os.listdir(self.root):
            if name.endswith(".json"):
                os.remove(os.path.join(self.root, name))
                removed += 1
        return removed


class Library:
    def __init__(self, source: Source, cache_dir: str, root: str = "") -> None:
        self.source = source
        self.root = root
        self.cache = Cache(cache_dir)
        self._scan: Scan | None = None
        self._scanned_at = 0.0

    # -- the fast pass ----------------------------------------------------

    def scan(self, *, force: bool = False, max_age_s: float = 60.0) -> Scan:
        """Walk the account. Cached in memory for `max_age_s` so a page reload is free."""
        if not force and self._scan is not None and time.time() - self._scanned_at < max_age_s:
            return self._scan
        started = time.time()
        result = Scan(source=self.source.label)
        try:
            self._walk(result)
        except SourceError as error:
            # Surfaced rather than raised: a 401 must reach the screen as advice, and a
            # half-walked library is still worth showing.
            result.error = str(error)
        result.took_ms = int((time.time() - started) * 1000)
        self._attach_titles(result)
        self._scan = result
        self._scanned_at = time.time()
        return result

    def _walk(self, result: Scan) -> None:
        frontier: list[tuple[str, int]] = [(self.root, 0)]
        seen: set[str] = set()
        while frontier:
            path, depth = frontier.pop(0)
            if path in seen:
                continue
            seen.add(path)
            result.walked += 1
            for entry in self.source.listing(path):
                if entry.is_collection:
                    if entry.name == MEDIA_DIR:
                        result.recordings.extend(self._recordings_under(entry.path, path))
                    elif depth < MAX_DEPTH and not entry.name.startswith("."):
                        result.folders.append(entry.path)
                        frontier.append((entry.path, depth + 1))
                elif entry.name.lower().endswith(".pdf"):
                    result.notes.append(
                        Note(
                            path=entry.path,
                            name=entry.name,
                            folder=path,
                            size=entry.size,
                            etag=entry.etag,
                        )
                    )
        result.folders.sort()

    def _recordings_under(self, media_dir: str, parent: str) -> list[Recording]:
        out: list[Recording] = []
        for note_dir in self.source.listing(media_dir):
            if not note_dir.is_collection:
                continue
            directory = note_dir.path
            entries = self.source.listing(directory)
            by_id: dict[str, Recording] = {}
            for entry in entries:
                if entry.is_collection:
                    continue
                for extension in (AUDIO_EXT, TIMELINE_EXT, TRANSCRIPT_EXT):
                    if not entry.name.endswith(extension):
                        continue
                    recording_id = entry.name[: -len(extension)]
                    record = by_id.setdefault(
                        recording_id,
                        Recording(
                            document_id=note_dir.name,
                            recording_id=recording_id,
                            directory=directory,
                            folder=parent,
                        ),
                    )
                    if extension == AUDIO_EXT:
                        record.has_audio = True
                        record.audio_bytes = entry.size
                    elif extension == TIMELINE_EXT:
                        record.has_timeline = True
                        record.timeline_etag = entry.etag
                    else:
                        record.has_transcript = True
                        record.transcript_etag = entry.etag
                    break
            out.extend(by_id.values())
        # UUIDv7 sorts by time, so this is chronological for anything the app wrote.
        out.sort(key=lambda r: r.recording_id)
        return out

    def _attach_titles(self, result: Scan) -> None:
        """Give each recording the best note name available without downloading anything.

        Three sources, in descending order of trustworthiness:

        1. a **cached** note whose `documentId` matches — the only one that is actually correct,
           because it came from the note's own manifest;
        2. the transcript's `note:` front matter, if that transcript is already cached;
        3. the sole sibling PDF, which is `watch.note_title`'s best-effort guess and is what the
           transcript's front matter was written from in the first place.

        ⚠️ Never fall back to matching on *filename*. M6 spent a milestone establishing that
        path-based identity breaks the moment somebody renames a file in the web UI, and a
        transcript found by sitting next to `Straight lines.pdf` is orphaned silently the moment
        that name changes. The `documentId` is the identity; a name is a convenience.
        """
        by_document: dict[str, Note] = {}
        for note in result.notes:
            cached = self.cache.get("note", f"{note.path}|{note.etag}")
            if not cached:
                continue
            note.document_id = cached.get("documentId")
            note.title = cached.get("title") or ""
            note.pages = int(cached.get("pages") or 0)
            note.is_inkstone = cached.get("isInkstone")
            if note.document_id:
                by_document[note.document_id] = note

        siblings: dict[str, list[Note]] = {}
        for note in result.notes:
            siblings.setdefault(note.folder, []).append(note)

        for record in result.recordings:
            match = by_document.get(record.document_id)
            if match is not None:
                record.note_title = match.display_title
                record.note_path = match.path
                continue
            cached = self.cache.get("transcript", f"{record.transcript_path}|{record.transcript_etag}")
            if cached and cached.get("note"):
                record.note_title = str(cached["note"])
            # ⚠️ The sole-sibling guess is only allowed when nothing **contradicts** it.
            #
            # `watch.note_title`'s heuristic is "one PDF beside the media folder must be the note",
            # which is fine when the PDF's identity is unknown. But once that PDF has been indexed
            # we know its `documentId`, and if it names a different document then this recording
            # demonstrably does not belong to it — a folder holding two notes where only one has
            # been opened would otherwise put lesson A's transcript under note B's title, and
            # `notePath` would send the PDF pane to the wrong scan. A known mismatch is stronger
            # evidence than a count of one.
            candidates = [
                note
                for note in siblings.get(record.folder, [])
                if note.document_id in (None, record.document_id)
            ]
            if len(candidates) == 1:
                record.note_path = candidates[0].path
                if not record.note_title:
                    record.note_title = candidates[0].display_title
            if not record.note_title:
                record.note_title = record.document_id

        for record in result.recordings:
            record.duration_ms = self._duration_of(record)

    def _duration_of(self, record: Recording) -> int:
        """How long a lecture is, cheaply enough to do for every one of them in a listing.

        Three sources, cheapest first:

        1. A transcript already parsed into the cache — free.
        2. **A byte range over the transcript's front matter** — a couple of hundred bytes rather
           than a whole document, keyed on the ETag like every other cached read. This is what
           makes the listing honest for a transcribed lecture without turning a scan into one
           download per lesson.
        3. Nothing. An untranscribed recording keeps the old bitrate estimate through
           `estimated_minutes`, and `/api/recording` still reads the Ogg container for a
           truthful figure when a person actually opens it.

        ⚠️ Never `read` the whole transcript here. `scan` runs on a timer and on every page load;
        pulling every `.md` in the account each time is exactly the directory-walk cost this tool
        exists to remove.
        """
        if not record.has_transcript:
            return 0

        cached = self.cache.get("transcript", f"{record.transcript_path}|{record.transcript_etag}")
        if cached and cached.get("durationMs"):
            return int(cached["durationMs"])

        key = f"{record.transcript_path}|{record.transcript_etag}"
        known = self.cache.get("duration", key)
        if known is not None:
            return int(known.get("ms") or 0)

        duration = 0
        try:
            head = self.source.read_range(record.transcript_path, 0, FRONT_MATTER_BYTES - 1)
            front = transcript_mod.front_matter(head.decode("utf-8", "replace"))
            raw = front.get("duration")
            if raw:
                duration = transcript_mod.parse_clock(raw)
        except Exception:
            # A source that cannot answer a range, a file that has gone, a fence past the window:
            # a missing length is a cosmetic loss and must never fail a listing.
            duration = 0
        self.cache.put("duration", key, {"ms": duration})
        return duration

    # -- one note ---------------------------------------------------------

    def within_root(self, path: str) -> bool:
        """Whether `path` is inside the `--root` this library was told to walk.

        ⚠️ `--root` was a *walk* bound and not a *reach* bound, which are different things and
        looked the same while nothing addressed a file directly. `note_detail` and `/media/note`
        take a path from a query string, so `--root Chemistry` still served any PDF in the
        account — the flag limited what was listed and not what could be fetched. Somebody who
        pointed the reader at one subject to keep the rest out of it got neither.

        Purely textual, because these are remote paths rather than filesystem ones: there is no
        symlink to resolve and no `..` that a server would honour. `RemoteWalk` compares the same
        way.
        """
        if not self.root:
            return True
        clean = "/".join(p for p in path.split("/") if p and p != ".")
        base = "/".join(p for p in self.root.split("/") if p and p != ".")
        return clean == base or clean.startswith(f"{base}/")

    def note_detail(self, path: str, *, with_text: bool = True) -> dict | None:
        """Open one published PDF and say what is inside it. Cached against its ETag."""
        if ".." in path.split("/") or not self.within_root(path):
            return None
        entry = self._entry_for(path)
        if entry is None:
            return None
        key = f"{path}|{entry.etag}"
        cached = self.cache.get("note", key)
        if cached is not None and (cached.get("hasText") or not with_text):
            return cached

        pdf = self.source.read(path)
        note = notepdf.read(pdf, with_text=with_text)
        payload: dict = {
            "path": path,
            "name": entry.name,
            "size": entry.size,
            "etag": entry.etag,
            "isInkstone": note is not None,
            "hasText": with_text,
        }
        if note is not None:
            payload.update(
                {
                    "documentId": note.document_id,
                    "title": note.title,
                    "schemaVersion": note.schema_version,
                    "pages": note.page_count,
                    "bundlePresent": note.bundle_present,
                    "bundleVerified": note.bundle_verified,
                    "imported": note.imported,
                    "createdMs": note.created_ms,
                    "modifiedMs": note.modified_ms,
                    "pageList": [
                        {"id": p.id, "number": p.number, "lines": p.lines, "text": p.text}
                        for p in note.pages
                        if not p.deleted
                    ],
                }
            )
        self.cache.put("note", key, payload)
        return payload

    def entry_for(self, path: str) -> Entry | None:
        """The listing entry for one file, or None — root-checked, for callers outside this class."""
        if ".." in path.split("/") or not self.within_root(path):
            return None
        return self._entry_for(path)

    def _entry_for(self, path: str) -> Entry | None:
        parent, _, name = path.rpartition("/")
        for entry in self.source.listing(parent):
            if entry.name == name and not entry.is_collection:
                return entry
        return None

    # -- one recording ----------------------------------------------------

    def recording_detail(self, document_id: str, recording_id: str) -> dict | None:
        """Everything the viewer draws: the transcript, the timeline, and the derived views."""
        scan = self.scan()
        record = next(
            (r for r in scan.recordings if r.document_id == document_id and r.recording_id == recording_id),
            None,
        )
        if record is None:
            return None

        payload: dict = {
            "documentId": record.document_id,
            "recordingId": record.recording_id,
            "directory": record.directory,
            "folder": record.folder,
            "noteTitle": record.note_title,
            "notePath": record.note_path,
            "state": record.state,
            "estimatedMinutes": round(record.estimated_minutes, 1),
        }

        parsed = None
        if record.has_transcript:
            parsed = self._transcript(record)
            payload["transcript"] = parsed

        duration_ms = 0
        if parsed and parsed.get("durationMs"):
            duration_ms = int(parsed["durationMs"])

        if record.has_timeline:
            raw = self._cached_text(record.timeline_path, record.timeline_etag)
            marks = timeline_mod.parse(raw)
            # ⚠️ The audio's own length, in descending order of honesty.
            #
            # 1. The transcript's `duration`, which the watcher read off the Ogg container.
            # 2. **The container itself**, via two small range reads — for a recording that has no
            #    transcript yet, which is exactly the state the audio is still present in.
            # 3. The last stroke, only when there is no audio and no transcript.
            #
            # (3) is a lie about the tail and the comment here used to say so while the code did
            # it anyway. The measured lesson's last stroke is at 2,907.8 s against 2,928.1 s of
            # audio, and 24 of its pauses run past fifteen seconds — so a lesson that ended with
            # the teacher still talking would appear to stop when the writing did, and every bar
            # in the histogram would be stretched to fill it. It is kept as a last resort because
            # a ribbon of roughly the right shape beats no ribbon, and `durationEstimated` tells
            # the caller which of the three it got.
            estimated = False
            if duration_ms <= 0 and record.has_audio:
                duration_ms = oggopus.duration_of(self.source, record.audio_path, record.audio_bytes) or 0
            if duration_ms <= 0:
                duration_ms = (marks.last_ms or 0) + 1
                estimated = True
            payload["durationEstimated"] = estimated
            payload["timeline"] = {
                **timeline_mod.summarise(marks, duration_ms),
                "activity": timeline_mod.activity(marks, duration_ms),
                "pageSpans": [
                    {
                        "page": s.page,
                        "firstMs": s.first_ms,
                        "lastMs": s.last_ms,
                        "strokes": s.strokes,
                        "index": s.index,
                    }
                    for s in timeline_mod.page_spans(marks)
                ],
                "quiet": [
                    {"fromMs": q.from_ms, "toMs": q.to_ms} for q in timeline_mod.quiet_runs(marks, duration_ms)
                ],
                "gaps": [{"fromMs": g.from_ms, "toMs": g.to_ms} for g in marks.gaps],
            }

        # ⭐ pageId -> the page's number in the published PDF.
        #
        # Without this both front ends printed a raw UUID — *"page 0199c4f2-7a3e-… · writing
        # starts 00:04:11"* — which is true, useless, and cannot be acted on. `notepdf` has known
        # the mapping since it was written (`manifest.json`'s `pages[{id, sortKey}]`) and nothing
        # asked it for it. One ETag-cached read of a PDF the lecture view is about to display
        # anyway turns every page marker into a page you can turn to.
        if record.note_path:
            try:
                note = self.note_detail(record.note_path, with_text=False)
            except SourceError:
                note = None
            if note:
                payload["pageNumbers"] = {
                    page["id"]: page["number"]
                    for page in note.get("pageList", [])
                    if page.get("number")
                }

        # ⚠️ The transcript's own page bullets, as the fallback when the timeline is missing.
        #
        # `align.render` writes a "When each page was written" section, `transcript.py` parses it,
        # and until now nothing read the result — so a recording whose `.timeline` had not
        # uploaded, or had been removed, lost its page anchoring entirely even though the
        # transcript beside it still said when each page was written. Second-resolution rather
        # than milliseconds (see `test_web.py`), which is why it is a fallback and not the source.
        if "timeline" not in payload and parsed and parsed.get("pageSpans"):
            payload["timeline"] = {
                "strokes": 0,
                "pages": len(parsed["pageSpans"]),
                "firstStrokeMs": None,
                "lastStrokeMs": None,
                "gaps": [],
                "gapMs": 0,
                "truncated": False,
                "quietRuns": 0,
                "activity": [],
                "pageSpans": [
                    {
                        "page": span["page"],
                        "firstMs": span["firstMs"],
                        "lastMs": span["lastMs"],
                        "strokes": 0,
                        "index": index,
                    }
                    for index, span in enumerate(parsed["pageSpans"])
                ],
                "quiet": [],
                "fromTranscript": True,
            }

        payload["durationMs"] = duration_ms
        return payload

    def _transcript(self, record: Recording) -> dict:
        key = f"{record.transcript_path}|{record.transcript_etag}"
        cached = self.cache.get("transcript", key)
        if cached is not None:
            return cached
        parsed = transcript_mod.parse(self.source.read_text(record.transcript_path))
        payload = {
            "note": parsed.front.get("note", ""),
            "documentId": parsed.document_id,
            "recorded": parsed.front.get("recorded", ""),
            "recogniser": parsed.front.get("recogniser", ""),
            "likelyTeacher": parsed.front.get("likelyTeacher"),
            "durationMs": parsed.duration_ms,
            "title": parsed.title,
            "unanchored": parsed.unanchored,
            "empty": parsed.empty,
            "speakers": parsed.speakers,
            "words": parsed.word_count,
            "segments": [asdict(s) for s in parsed.segments],
            "pageSpans": parsed.page_spans,
        }
        self.cache.put("transcript", key, payload)
        return payload

    def _cached_text(self, path: str, etag: str) -> str:
        key = f"{path}|{etag}"
        cached = self.cache.get("text", key)
        if cached is not None:
            return str(cached.get("text", ""))
        text = self.source.read_text(path)
        self.cache.put("text", key, {"text": text})
        return text

    # -- the slow pass ----------------------------------------------------

    def index_notes(self, *, progress=None) -> dict:
        """Download and cache every note PDF so handwriting becomes searchable.

        Explicit and never implicit: this is the only thing here that will move hundreds of
        megabytes, and a website that did it because somebody opened a page would be indistinguishable
        from a bug.
        """
        scan = self.scan(force=True)
        done = 0
        failed: list[str] = []
        inkstone = 0
        for note in scan.notes:
            try:
                detail = self.note_detail(note.path, with_text=True)
                if detail and detail.get("isInkstone"):
                    inkstone += 1
            except SourceError as error:
                failed.append(f"{note.path}: {error}")
            done += 1
            if progress is not None:
                progress(done, len(scan.notes), note.path)
        return {"notes": len(scan.notes), "indexed": done, "inkstone": inkstone, "failed": failed}

    # -- search -----------------------------------------------------------

    def search(self, needle: str, *, limit: int = 50, context: int = 1) -> dict:
        """Search transcripts and — for notes already indexed — recognised handwriting.

        ⚠️ Handwriting is only searched for notes whose PDF is **already cached**. Searching would
        otherwise download the whole library on a keystroke. `indexed` in the result says how many
        notes were actually looked at, so a caller can tell "no hits" from "nothing was searched" —
        a distinction this repo has been bitten by often enough to make it a field rather than a
        footnote.
        """
        scan = self.scan()
        hits: list[dict] = []
        searched_notes = 0

        for record in scan.recordings:
            if not record.has_transcript or len(hits) >= limit:
                continue
            payload = self.cache.get("transcript", f"{record.transcript_path}|{record.transcript_etag}")
            if payload is None:
                try:
                    payload = self._transcript(record)
                except SourceError:
                    continue
            for segment in payload.get("segments", []):
                if segment.get("kind") != "speech":
                    continue
                if needle.lower() not in str(segment.get("text", "")).lower():
                    continue
                hits.append(
                    {
                        "kind": "transcript",
                        "documentId": record.document_id,
                        "recordingId": record.recording_id,
                        "noteTitle": record.note_title,
                        "atMs": segment.get("at_ms"),
                        "at": transcript_mod.clock(int(segment.get("at_ms") or 0)),
                        "speaker": segment.get("speaker"),
                        "text": segment.get("text"),
                    }
                )
                if len(hits) >= limit:
                    break

        for note in scan.notes:
            payload = self.cache.get("note", f"{note.path}|{note.etag}")
            if payload is None or not payload.get("hasText"):
                continue
            # ⚠️ Counted **before** the limit check, not after it.
            #
            # The count was inside the `len(hits) >= limit` break, so a query that filled its limit
            # on transcripts alone left `notesSearched` at 0 and reported *every* note as
            # unindexed — telling the user to run an index that had already run, and telling a
            # model that handwriting had not been searched when it had. A field that exists to
            # distinguish "no hits" from "nothing was looked at" is worse than useless when it
            # itself reports a number that depends on how many hits came back first.
            searched_notes += 1
            if len(hits) >= limit:
                continue
            for page in payload.get("pageList", []):
                text = str(page.get("text") or "")
                if needle.lower() not in text.lower():
                    continue
                hits.append(
                    {
                        "kind": "handwriting",
                        "path": note.path,
                        "noteTitle": payload.get("title") or note.display_title,
                        "documentId": payload.get("documentId"),
                        "page": page.get("number"),
                        "text": _excerpt(text, needle),
                    }
                )
                if len(hits) >= limit:
                    break

        return {
            "query": needle,
            "hits": hits,
            "notesSearched": searched_notes,
            "notesTotal": len(scan.notes),
            "notesUnindexed": len(scan.notes) - searched_notes,
        }


def _excerpt(text: str, needle: str, width: int = 160) -> str:
    position = text.lower().find(needle.lower())
    if position < 0:
        return text[:width]
    start = max(0, position - width // 2)
    end = min(len(text), position + len(needle) + width // 2)
    return ("…" if start else "") + text[start:end].replace("\n", " ") + ("…" if end < len(text) else "")
