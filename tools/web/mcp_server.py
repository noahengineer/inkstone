#!/usr/bin/env python3
"""An MCP server over the same index the website uses, so an assistant can read your lessons.

    claude mcp add inkstone -- py "<repo>/tools/web/mcp_server.py"

The point of this file is narrow and worth stating: **an assistant crawling WebDAV to answer a
question about a lesson is doing an enormous amount of work to get a poor answer.** It cannot see
which PDFs are notes, it cannot read the handwriting inside one, it has to download a whole
transcript to find one sentence, and it has no idea which recording belongs to which note. Every
tool below exists to replace a directory crawl with a single answer.

## The protocol, checked rather than remembered

CLAUDE.md rule 3 — never invent a version number — applies to a wire protocol at least as much as
to a Maven coordinate, so these were read off the specification and off the shipped client rather
than recalled:

* **The current MCP specification is `2026-07-28`, and this must not target it.** That revision
  removes the `initialize` handshake in favour of a `server/discover` call, and the Claude Code
  build in this environment (2.1.218) supports only
  `2025-11-25 · 2025-06-18 · 2025-03-26 · 2024-11-05 · 2024-10-07`. The spec's own compatibility
  matrix is explicit that a legacy client meeting a modern-only server **fails with no
  fall-forward**. So this implements the legacy lifecycle, which a modern client is still required
  to accept (`initialize` selects legacy semantics).
* **Echo the client's requested version when it is one we speak.** The shipped client runs
  `if (!SUPPORTED.includes(result.protocolVersion)) throw` — so hardcoding even a *newer real*
  version in the response is a hard connection failure rather than a downgrade.
* **stdio framing is newline-delimited JSON. There is no `Content-Length` header** — that is LSP's
  framing, not this one, and it is the single most common thing to get wrong from memory.

## ⚠️ stdout carries the protocol and nothing else

The most likely way a hand-written Python MCP server fails is that its stdout is not exclusively
one flushed, single-line JSON message per write. Three independent causes, all presenting as
"the server never connected":

1. **Block buffering.** Python's stdout is block-buffered when it is a pipe — which it always is
   here — so a small `InitializeResult` sits in an 8 KB buffer and the client times out.
2. **Stray output.** One `print`, one library banner, one traceback on stdout corrupts the stream
   permanently.
3. **Pretty-printing.** `indent=2` puts newlines inside a message, so one message parses as
   several broken ones.

[_send] is the only thing in this file that writes to stdout, it never indents, and it flushes
every time. Logging goes to stderr, which the specification explicitly leaves free and which
Claude Desktop captures to `%APPDATA%\\Claude\\logs\\mcp-server-inkstone.log`.

## It cannot write

Same as the website: no PUT, no DELETE, no MKCOL exists anywhere in this package. An assistant
given these tools can read a lesson and cannot lose one.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import config  # noqa: E402
import transcript as transcript_mod  # noqa: E402
from library import Library  # noqa: E402
from source import MirrorSource, NeedsReauth, NextcloudSource, SourceError  # noqa: E402

NAME = "inkstone"
VERSION = "1.0.0"

# Newest first. `initialize` echoes the client's request when it appears here.
SUPPORTED_PROTOCOLS = ["2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"]
DEFAULT_PROTOCOL = SUPPORTED_PROTOCOLS[0]

# JSON-RPC. Reserved for protocol failures; a tool that ran and failed reports `isError` instead.
INVALID_PARAMS = -32602
METHOD_NOT_FOUND = -32601
INTERNAL_ERROR = -32603

log = logging.getLogger(NAME)

# A transcript of a 48-minute lesson is ~20 kB. Whole lessons are frequently the right answer, but
# an unbounded read of a term of them is not, so a single response is capped and says when it cut.
MAX_CHARS = 24_000


def tool_definitions() -> list[dict]:
    return [
        {
            "name": "list_lectures",
            "title": "List recorded lessons",
            "description": (
                "Every recorded lesson in the Nextcloud, with which note it belongs to, how long it "
                "is, and whether it has been transcribed yet. Start here — it is one call and it "
                "replaces walking the whole account over WebDAV."
            ),
            "inputSchema": {
                "type": "object",
                "properties": {
                    "folder": {"type": "string", "description": "Only lessons under this folder, e.g. 'Chemistry'."}
                },
            },
            "annotations": {"readOnlyHint": True, "openWorldHint": False},
        },
        {
            "name": "read_lecture",
            "title": "Read a lesson's transcript",
            "description": (
                "The transcript of one lesson, with a timestamp and speaker on every paragraph. "
                "Use fromMs/toMs to read one stretch rather than the whole hour. Stretches where "
                "nothing was said and nothing was written are marked rather than omitted."
            ),
            "inputSchema": {
                "type": "object",
                "properties": {
                    "documentId": {"type": "string"},
                    "recordingId": {"type": "string"},
                    "fromMs": {"type": "integer", "description": "Start of the window, ms from the start of the recording."},
                    "toMs": {"type": "integer", "description": "End of the window, ms."},
                },
                "required": ["documentId", "recordingId"],
            },
            "annotations": {"readOnlyHint": True, "openWorldHint": False},
        },
        {
            "name": "speech_around",
            "title": "What was said around a moment",
            "description": (
                "The speech surrounding one moment in a lesson — what the teacher was saying while "
                "a particular thing was being written. This is the question the recordings exist to "
                "answer: the page ends up holding a formula, and the explanation around it is here."
            ),
            "inputSchema": {
                "type": "object",
                "properties": {
                    "documentId": {"type": "string"},
                    "recordingId": {"type": "string"},
                    "atMs": {"type": "integer", "description": "The moment, ms from the start of the recording."},
                    "beforeMs": {
                        "type": "integer",
                        "description": (
                            "How far back to reach. Defaults to 15000. Writing lags speech, so the "
                            "window is deliberately longer backwards than forwards."
                        ),
                    },
                    "afterMs": {"type": "integer", "description": "How far forward to reach. Defaults to 5000."},
                },
                "required": ["documentId", "recordingId", "atMs"],
            },
            "annotations": {"readOnlyHint": True, "openWorldHint": False},
        },
        {
            "name": "lecture_timeline",
            "title": "When a lesson was written in",
            "description": (
                "The shape of a lesson: which pages were written on and when, how much writing "
                "happened across it, the long stretches with no writing, and any gap where the "
                "microphone was taken away. Useful for finding the part of a lesson worth reading."
            ),
            "inputSchema": {
                "type": "object",
                "properties": {"documentId": {"type": "string"}, "recordingId": {"type": "string"}},
                "required": ["documentId", "recordingId"],
            },
            "annotations": {"readOnlyHint": True, "openWorldHint": False},
        },
        {
            "name": "search",
            "title": "Search lessons and handwriting",
            "description": (
                "Search every transcript, and the recognised handwriting of every note that has "
                "been indexed. Transcript hits carry a timestamp; handwriting hits carry a page. "
                "The result says how many notes were NOT searched, so 'no hits' can be told apart "
                "from 'nothing was looked at'."
            ),
            "inputSchema": {
                "type": "object",
                "properties": {
                    "query": {"type": "string"},
                    "limit": {"type": "integer", "description": "Maximum hits. Defaults to 30."},
                },
                "required": ["query"],
            },
            "annotations": {"readOnlyHint": True, "openWorldHint": False},
        },
        {
            "name": "list_notes",
            "title": "List notes",
            "description": "Every PDF in the account, with its title and page count once it has been indexed.",
            "inputSchema": {
                "type": "object",
                "properties": {"folder": {"type": "string"}},
            },
            "annotations": {"readOnlyHint": True, "openWorldHint": False},
        },
        {
            "name": "read_note",
            "title": "Read a note's handwriting",
            "description": (
                "Open one published note and return its handwriting as text, page by page, from "
                "the recognition the tablet did. Downloads the PDF once and caches it."
            ),
            "inputSchema": {
                "type": "object",
                "properties": {"path": {"type": "string", "description": "The note's path, as given by list_notes."}},
                "required": ["path"],
            },
            "annotations": {"readOnlyHint": True, "openWorldHint": False},
        },
        {
            "name": "index_notes",
            "title": "Index every note for search",
            "description": (
                "Download every note PDF once so its handwriting becomes searchable. Slow and "
                "bandwidth-heavy — it moves the whole library — so it is never done automatically. "
                "Run it once, then search is instant."
            ),
            "inputSchema": {"type": "object", "properties": {}},
            # ⚠️ Not read-only: it does not change Nextcloud, but it downloads the entire library.
            # `readOnlyHint` is what a client uses to decide whether to ask permission, and "this
            # will pull several hundred megabytes" is exactly a thing worth being asked about.
            "annotations": {"readOnlyHint": False, "destructiveHint": False, "openWorldHint": False},
        },
    ]


_CALLABLE = frozenset(tool["name"] for tool in tool_definitions())


class Tools:
    def __init__(self, library: Library) -> None:
        self.library = library

    def call(self, name: str, arguments: dict) -> str:
        # ⚠️ An allowlist built from the **declared** tools, not `getattr(self, "_" + name)`.
        #
        # The name comes from the client, so `getattr` made every private method on this class a
        # callable tool: `_find` and `_speech` are helpers with different signatures, and
        # `tools/call` with `{"name": "find"}` reached `_find(arguments)` and raised — answering a
        # protocol error for a tool that is not supposed to exist, and doing it from a code path
        # nobody had considered. It also means the set of things a client can invoke drifts from
        # the set `tools/list` advertises the moment somebody adds a helper.
        #
        # Deriving the allowlist from `tool_definitions()` keeps the two identical by construction
        # rather than by discipline.
        if name not in _CALLABLE:
            raise KeyError(name)
        return getattr(self, f"_{name}")(arguments)

    # -- helpers ----------------------------------------------------------

    def _find(self, arguments: dict):
        document_id = arguments.get("documentId")
        recording_id = arguments.get("recordingId")
        detail = self.library.recording_detail(str(document_id), str(recording_id))
        if detail is None:
            raise ValueError(
                f"No recording {document_id}/{recording_id}. Call list_lectures to see what exists."
            )
        return detail

    @staticmethod
    def _speech(detail: dict) -> list[dict]:
        t = detail.get("transcript")
        return [s for s in (t or {}).get("segments", []) if s.get("kind") == "speech"]

    # -- tools ------------------------------------------------------------

    def _list_lectures(self, arguments: dict) -> str:
        folder = arguments.get("folder")
        scan = self.library.scan()
        rows = [r for r in scan.recordings if not folder or r.folder == folder or r.folder.startswith(f"{folder}/")]
        if not rows:
            return (
                "No recorded lessons found."
                + (f" (filtered to folder {folder!r})" if folder else "")
                + "\nRecordings live in <folder>/.inkstone-media/<documentId>/."
            )
        lines = [f"{len(rows)} recorded lesson(s):", ""]
        for r in rows:
            # ⛔ This said `approx {estimated_minutes:.0f} min` for every row, and that number is
            # derived from the `.opus`'s byte count — which `tools/m12` trashes once a transcript
            # is verified. So the description's promise of "how long it is" was answered
            # `approx 0 min` for every *transcribed* lesson, which is all of them in the steady
            # state. A transcribed lesson now reports the length `align` measured off the Ogg
            # container; only one still awaiting transcription is labelled an estimate.
            length = (
                transcript_mod.clock(r.duration_ms)
                if r.duration_ms > 0
                else f"~{r.estimated_minutes:.0f} min (estimated from the audio's size)"
                if r.estimated_minutes
                else "length unknown"
            )
            lines.append(
                f"- {r.note_title}  [{r.state}]\n"
                f"    documentId={r.document_id}  recordingId={r.recording_id}\n"
                f"    folder={r.folder or '(root)'}  {length}"
                + (f"  note={r.note_path}" if r.note_path else "")
            )
        if scan.error:
            lines.append(f"\n⚠ The walk did not finish: {scan.error}")
        return "\n".join(lines)

    def _read_lecture(self, arguments: dict) -> str:
        detail = self._find(arguments)
        t = detail.get("transcript")
        if not t:
            return (
                f"{detail['noteTitle']} has no transcript yet (state: {detail['state']}).\n"
                "The audio and its timeline are in Nextcloud; tools/m12 turns them into one."
            )
        start = int(arguments.get("fromMs") or 0)
        end = int(arguments.get("toMs") or detail.get("durationMs") or 0) or None

        head = [
            f"# {t.get('note') or detail['noteTitle']}",
            f"recorded {t.get('recorded', 'unknown')} · {transcript_mod.clock(detail.get('durationMs') or 0)}"
            f" · {t.get('words', 0)} words · {t.get('recogniser', 'unknown recogniser')}",
        ]
        if t.get("likelyTeacher"):
            head.append(
                f"speaker {t['likelyTeacher']} talks most, so is most likely the teacher — a "
                "heuristic, not an identification."
            )
        if t.get("unanchored"):
            head.append("This recording has no page information, so nothing below is tied to a page.")
        head.append("")

        body: list[str] = []
        for segment in t.get("segments", []):
            at = int(segment.get("at_ms") or 0)
            if at < start or (end is not None and at > end):
                continue
            if segment.get("kind") == "speech":
                who = f"{segment['speaker']}: " if segment.get("speaker") else ""
                body.append(f"[{transcript_mod.clock(at)}] {who}{segment.get('text', '')}")
            elif segment.get("kind") == "dead":
                body.append(
                    f"[{transcript_mod.clock(at)}] — nothing said and nothing written until "
                    f"{transcript_mod.clock(int(segment.get('to_ms') or at))} —"
                )
            elif segment.get("kind") == "page":
                body.append(f"[{transcript_mod.clock(at)}] — writing moves to page {segment.get('page')} —")

        for gap in (detail.get("timeline") or {}).get("gaps", []):
            body.insert(
                0,
                f"⚠ No audio between {transcript_mod.clock(gap['fromMs'])} and "
                f"{transcript_mod.clock(gap['toMs'])} — the microphone was taken. "
                "That speech is missing, not silent.",
            )

        if not body:
            return "\n".join(head) + f"\nNothing in the window {start}–{end} ms."
        text = "\n".join(head + body)
        if len(text) > MAX_CHARS:
            text = text[:MAX_CHARS] + (
                f"\n\n… truncated at {MAX_CHARS} characters. Read a narrower window with fromMs/toMs."
            )
        return text

    def _speech_around(self, arguments: dict) -> str:
        detail = self._find(arguments)
        at = int(arguments.get("atMs") or 0)
        # ⚠️ −15 s / +5 s, and the asymmetry is the design rather than a rounding. Writing lags
        # speech: the teacher says the thing, you start writing partway through and finish after
        # they have moved on, so the window has to reach further back than forward
        # (docs/M12-AUDIO.md §7). Measured against a real lesson, the likelier error is that it is
        # too short, so both ends are generous and callers may widen them.
        before = int(arguments.get("beforeMs") if arguments.get("beforeMs") is not None else 15_000)
        after = int(arguments.get("afterMs") if arguments.get("afterMs") is not None else 5_000)
        low, high = max(0, at - before), at + after

        speech = self._speech(detail)
        if not speech:
            return f"{detail['noteTitle']} has no transcript, so nothing is known about {transcript_mod.clock(at)}."

        window = [s for s in speech if low <= int(s.get("at_ms") or 0) <= high]
        if not window:
            # Falling back to the nearest paragraph rather than answering "nothing" — during a long
            # silence the honest answer is the last thing that was said, plus how long ago it was.
            before_it = [s for s in speech if int(s.get("at_ms") or 0) <= at]
            if not before_it:
                return f"Nobody had spoken yet at {transcript_mod.clock(at)}."
            last = before_it[-1]
            silence = (at - int(last.get("at_ms") or 0)) // 1000
            return (
                f"Nothing was said within the window around {transcript_mod.clock(at)}.\n"
                f"The last thing said was {silence // 60}m {silence % 60}s earlier:\n\n"
                f"[{transcript_mod.clock(int(last['at_ms']))}] "
                f"{(last.get('speaker') + ': ') if last.get('speaker') else ''}{last.get('text', '')}"
            )

        lines = [
            f"Around {transcript_mod.clock(at)} in “{detail['noteTitle']}” "
            f"(window {transcript_mod.clock(low)}–{transcript_mod.clock(high)}):",
            "",
        ]
        for s in window:
            who = f"{s['speaker']}: " if s.get("speaker") else ""
            lines.append(f"[{transcript_mod.clock(int(s['at_ms']))}] {who}{s.get('text', '')}")
        return "\n".join(lines)

    def _lecture_timeline(self, arguments: dict) -> str:
        detail = self._find(arguments)
        marks = detail.get("timeline")
        if not marks:
            return f"{detail['noteTitle']} has no timeline file, so nothing is known about when it was written."
        duration = detail.get("durationMs") or 0
        lines = [
            f"“{detail['noteTitle']}” — {transcript_mod.clock(duration)}",
            f"{marks['strokes']} strokes, first at {transcript_mod.clock(marks['firstStrokeMs'] or 0)}, "
            f"last at {transcript_mod.clock(marks['lastStrokeMs'] or 0)}",
        ]
        if marks.get("truncated"):
            lines.append("⚠ The timeline has a torn tail — the app was killed rather than stopped.")
        if marks.get("pageSpans"):
            lines.append("")
            lines.append("Pages, in the order they were first written on:")
            for span in marks["pageSpans"]:
                lines.append(
                    f"  {span['index'] + 1}. page {span['page']} — "
                    f"{transcript_mod.clock(span['firstMs'])} to {transcript_mod.clock(span['lastMs'])}"
                    f" ({span['strokes']} strokes)"
                )
        else:
            lines.append("")
            lines.append(
                "No page information: this recording predates the app writing a page id per stroke, "
                "so the writing cannot be tied to particular pages."
            )
        if marks.get("quiet"):
            lines.append("")
            lines.append("Stretches with no writing at all — listening, or working:")
            for quiet in marks["quiet"]:
                length = (quiet["toMs"] - quiet["fromMs"]) // 1000
                lines.append(
                    f"  {transcript_mod.clock(quiet['fromMs'])} – {transcript_mod.clock(quiet['toMs'])}"
                    f"  ({length // 60}m {length % 60}s)"
                )
        if marks.get("gaps"):
            lines.append("")
            for gap in marks["gaps"]:
                lines.append(
                    f"⚠ No audio {transcript_mod.clock(gap['fromMs'])} – {transcript_mod.clock(gap['toMs'])}: "
                    "the microphone was taken away. That speech is missing, not silent."
                )
        return "\n".join(lines)

    def _search(self, arguments: dict) -> str:
        query = str(arguments.get("query") or "").strip()
        if not query:
            raise ValueError("query is required and must not be empty.")
        limit = int(arguments.get("limit") or 30)
        found = self.library.search(query, limit=limit)
        if not found["hits"]:
            note = ""
            if found["notesUnindexed"]:
                note = (
                    f"\n\n{found['notesUnindexed']} of {found['notesTotal']} notes have not been "
                    "indexed, so their handwriting was not searched. Run index_notes to include them."
                )
            return f"Nothing matched “{query}”.{note}"
        lines = [f"{len(found['hits'])} hit(s) for “{query}”:", ""]
        for hit in found["hits"]:
            if hit["kind"] == "transcript":
                who = f" {hit['speaker']}:" if hit.get("speaker") else ""
                lines.append(
                    f"- said in “{hit['noteTitle']}” at {hit['at']}{who} {hit['text']}\n"
                    f"    documentId={hit['documentId']}  recordingId={hit['recordingId']}  atMs={hit['atMs']}"
                )
            else:
                lines.append(
                    f"- written in “{hit['noteTitle']}”, page {hit.get('page')}: {hit['text']}\n"
                    f"    path={hit['path']}"
                )
        if found["notesUnindexed"]:
            lines.append(
                f"\n{found['notesUnindexed']} of {found['notesTotal']} notes are not indexed, so "
                "their handwriting was not searched. Run index_notes to include them."
            )
        return "\n".join(lines)

    def _list_notes(self, arguments: dict) -> str:
        folder = arguments.get("folder")
        scan = self.library.scan()
        rows = [n for n in scan.notes if not folder or n.folder == folder or n.folder.startswith(f"{folder}/")]
        if not rows:
            return "No PDFs found." + (f" (filtered to folder {folder!r})" if folder else "")
        lines = [f"{len(rows)} PDF(s):", ""]
        for n in rows:
            known = "" if n.is_inkstone is None else (" — Inkstone note" if n.is_inkstone else " — not an Inkstone note")
            pages = f", {n.pages} pages" if n.pages else ""
            lines.append(f"- {n.display_title}{pages}{known}\n    path={n.path}")
        lines.append("\nCall read_note with a path to read its handwriting.")
        return "\n".join(lines)

    def _read_note(self, arguments: dict) -> str:
        path = str(arguments.get("path") or "")
        if not path:
            raise ValueError("path is required.")
        detail = self.library.note_detail(path)
        if detail is None:
            raise ValueError(f"No note at {path!r}. Call list_notes to see what exists.")
        if not detail.get("isInkstone"):
            return (
                f"{path} is a PDF but was not written by Inkstone, so it carries no handwriting "
                "recognition. Its identity marker is absent."
            )
        lines = [
            f"# {detail.get('title') or path}",
            f"{detail.get('pages', 0)} pages · documentId={detail.get('documentId')}"
            + ("  · bundle verified against the marker's digest" if detail.get("bundleVerified") else "")
            + ("  · imported PDF, the original bytes are kept verbatim" if detail.get("imported") else ""),
            "",
        ]
        wrote = False
        for page in detail.get("pageList", []):
            if not page.get("text"):
                continue
            wrote = True
            lines.append(f"## Page {page.get('number')}")
            lines.append(page["text"])
            lines.append("")
        if not wrote:
            lines.append(
                "No recognised text. Handwriting recognition runs on the tablet a few seconds "
                "after the pen stops, so a note written on an older build has none."
            )
        text = "\n".join(lines)
        if len(text) > MAX_CHARS:
            text = text[:MAX_CHARS] + f"\n\n… truncated at {MAX_CHARS} characters."
        return text

    def _index_notes(self, arguments: dict) -> str:
        result = self.library.index_notes()
        lines = [
            f"Indexed {result['indexed']} of {result['notes']} PDFs. "
            f"{result['inkstone']} are Inkstone notes; their handwriting is now searchable."
        ]
        if result["failed"]:
            lines.append("")
            lines.append(f"{len(result['failed'])} could not be read:")
            lines.extend(f"  {f}" for f in result["failed"][:20])
        return "\n".join(lines)


class Protocol:
    """MCP itself, with **no transport in it at all**.

    [handle] takes one decoded JSON-RPC message and returns the response to send back, or `None`
    for a notification (which must never be answered). That signature is the whole point: stdio
    writes the return value to stdout, and the HTTP transport puts it in a response body, and
    neither knows anything the other does not.

    Splitting this out is not tidiness. A second transport is the classic place for a second
    implementation to appear and quietly disagree with the first — CLAUDE.md's *"a second client
    for a protocol the project already speaks is where the old bugs come back"*, pointed inward.
    Version negotiation, the tool allowlist, the error-vs-isError distinction and the
    notification rule are subtle enough that having them twice would guarantee it. `test_mcp.py`
    drives stdio and `test_mcp_http.py` drives HTTP, and both are asserting the same object.
    """

    def __init__(self, tools: Tools) -> None:
        self.tools = tools
        self.protocol = DEFAULT_PROTOCOL

    @staticmethod
    def result(request_id, result: dict) -> dict:
        return {"jsonrpc": "2.0", "id": request_id, "result": result}

    @staticmethod
    def error(request_id, code: int, message: str) -> dict:
        return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}

    def handle(self, message: dict) -> dict | None:
        method = message.get("method")
        request_id = message.get("id")
        params = message.get("params") or {}

        # A notification has no id, and MUST NOT be answered — including an unknown one.
        if request_id is None:
            log.debug("notification %s", method)
            return None

        try:
            if method == "initialize":
                return self.result(request_id, self._initialize(params))
            if method == "ping":
                return self.result(request_id, {})
            if method == "tools/list":
                return self.result(request_id, {"tools": tool_definitions()})
            if method == "tools/call":
                return self.result(request_id, self._call(params))
            return self.error(request_id, METHOD_NOT_FOUND, f"Unknown method: {method}")
        except Exception as error:  # noqa: BLE001
            log.exception("failed handling %s", method)
            return self.error(request_id, INTERNAL_ERROR, f"{type(error).__name__}: {error}")

    def _initialize(self, params: dict) -> dict:
        asked = params.get("protocolVersion")
        # Echo what the client asked for when we speak it. Answering with a version the client
        # does not know is a hard failure on its side, not a downgrade.
        self.protocol = asked if asked in SUPPORTED_PROTOCOLS else DEFAULT_PROTOCOL
        client = (params.get("clientInfo") or {}).get("name", "unknown")
        log.info("initialize from %s, protocol %s (asked %s)", client, self.protocol, asked)
        return {
            "protocolVersion": self.protocol,
            "capabilities": {"tools": {}},
            "serverInfo": {"name": NAME, "title": "Inkstone notes and lectures", "version": VERSION},
            "instructions": (
                "Handwritten notes and recorded lessons from an Inkstone Nextcloud. "
                "list_lectures and list_notes are the cheap starting points. To find out what a "
                "teacher said while something was written, use speech_around. Handwriting search "
                "only covers notes that have been indexed; search says how many were not."
            ),
        }

    def _call(self, params: dict) -> dict:
        name = params.get("name")
        arguments = params.get("arguments") or {}
        if not isinstance(name, str):
            raise ValueError("tools/call requires a tool name.")
        try:
            text = self.tools.call(name, arguments)
        except KeyError:
            # ⚠️ Failing to *find* a tool is a protocol error; a tool that ran and failed is not.
            # Raising here reaches `_handle`, which answers with a JSON-RPC error.
            raise LookupError(f"Unknown tool: {name}") from None
        except NeedsReauth as error:
            # A 401 is reported to the model, not retried. CLAUDE.md rule 5: retrying is how a
            # user's own server IP-blocks them.
            return self._tool_error(str(error))
        except (SourceError, ValueError) as error:
            # An error the model can act on — a wrong id, an unreachable server. The specification
            # is explicit that these belong in the result so the model can self-correct.
            return self._tool_error(str(error))
        return {"content": [{"type": "text", "text": text}], "isError": False}

    @staticmethod
    def _tool_error(message: str) -> dict:
        return {"content": [{"type": "text", "text": message}], "isError": True}


class Server(Protocol):
    """The stdio transport. Everything protocol-shaped lives in [Protocol]."""

    # -- the wire ---------------------------------------------------------

    @staticmethod
    def _send(message: dict) -> None:
        """The ONLY thing in this file that writes to stdout. One line, no indent, flushed."""
        sys.stdout.write(json.dumps(message, separators=(",", ":"), ensure_ascii=False) + "\n")
        sys.stdout.flush()

    def serve(self) -> int:
        log.info("inkstone MCP server ready on stdio")
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                log.warning("ignoring a line that is not JSON")
                continue
            # Batching was removed in 2025-06-18 and is never required, but a top-level array
            # must not crash the reader.
            for one in message if isinstance(message, list) else [message]:
                if isinstance(one, dict):
                    answer = self.handle(one)
                    if answer is not None:
                        self._send(answer)
        # "Servers SHOULD exit promptly when stdin is closed" — the primary graceful-shutdown
        # signal, and the only portable one.
        log.info("stdin closed; exiting")
        return 0

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="MCP server for Inkstone notes and lectures.")
    parser.add_argument("--mirror", default=None, help="Read a local folder instead of the live Nextcloud.")
    parser.add_argument("--config", default=None, help="Path to watcher.local.properties.")
    parser.add_argument("--cache", default=None)
    parser.add_argument("--root", default="")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args(argv)

    logging.basicConfig(
        stream=sys.stderr,
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    try:
        source = MirrorSource(args.mirror) if args.mirror else NextcloudSource(config.load(args.config))
    except (config.ConfigError, SourceError) as error:
        # Configuration failures go to stderr and take the process with them. Reporting them over
        # the protocol is not possible: nothing has initialised yet.
        log.error("%s", error)
        return 2

    cache_root = args.cache or os.path.join(os.path.dirname(os.path.abspath(__file__)), ".cache")
    library = Library(source, os.path.join(cache_root, "meta"), root=args.root)
    log.info("source: %s", source.label)
    return Server(Tools(library)).serve()


if __name__ == "__main__":
    raise SystemExit(main())
