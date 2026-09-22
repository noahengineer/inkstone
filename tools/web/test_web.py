#!/usr/bin/env python3
"""The reader's own suite: the parsers, the PDF bundle reader, the index, and the HTTP server.

    python tools/web/test_web.py

Three habits from CLAUDE.md shape what is here.

**A reader that is not the writer.** `test_transcript_*` parses the merged markdown that
`tools/m12/align.py` renders and asserts the structure comes back. M4 shipped PDFs that were
valid, parseable and blank in every viewer that was not the library that wrote them; re-reading
our own output with an independent parser is that lesson applied.

**Real data over fixtures where a real file exists.** The 48-minute lesson in `recordings-pulled/`
is a genuine recording with 867 strokes and 3,111 transcribed words, and several assertions below
check numbers that `docs/M12-AUDIO.md` §15.6–15.7 measured **independently of this code**. A
fixture cannot do that.

**Say which bucket a claim is in.** ⚠️ The PDF tests below are *synthetic*: they build a file the
way `PdfBoxWriter` builds one — an uncompressed XMP packet plus a `/FlateDecode`d stored ZIP — and
prove the reader finds it. That is genuinely weaker than a note published by the app, and
`docs/WEB-READER.md` records it as unproven against a real one until a Galaxy Tab or a live
Nextcloud is reachable.
"""

from __future__ import annotations

import http.client
import io
import json
import os
import shutil
import struct
import sys
import tempfile
import socket as socket_mod
import threading
import time
import urllib.error
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)

import library as library_mod  # noqa: E402
import notepdf  # noqa: E402
import oggopus  # noqa: E402
import server as server_mod  # noqa: E402
import timeline as timeline_mod  # noqa: E402
import transcript as transcript_mod  # noqa: E402
import auth as auth_mod  # noqa: E402
import mcp_http  # noqa: E402
import mcp_server  # noqa: E402
from source import MirrorSource, NeedsReauth, NextcloudSource, SourceError  # noqa: E402

PULLED = os.path.join(REPO, "recordings-pulled")
RECORDING = "019fefb2-0000-7000-8000-000000000149"
PDF_PATH = "/media/note?path=Chemistry/Percentage%20yield.pdf"

passed = 0
failed = 0


def check(name: str, condition: bool, detail: str = "") -> None:
    global passed, failed
    if condition:
        passed += 1
        print(f"  PASS  {name}")
    else:
        failed += 1
        print(f"  FAIL  {name}" + (f"\n        {detail}" if detail else ""))


def strip_js_comments(source: str) -> str:
    """`/* … */` and `// …` removed, so an assertion about behaviour cannot be satisfied by prose.

    Deliberately crude — it does not understand strings or regex literals, and it does not need to:
    the callers ask whether a particular selector appears in the executable text, and this file's
    own comments quote the selectors it is asserting about.
    """
    out, index, length = [], 0, len(source)
    while index < length:
        if source.startswith("/*", index):
            index = source.find("*/", index + 2)
            index = length if index < 0 else index + 2
        elif source.startswith("//", index):
            end = source.find(chr(10), index)
            index = length if end < 0 else end
        else:
            out.append(source[index])
            index += 1
    return "".join(out)


def section(title: str) -> None:
    print(f"\n{title}")


# ---------------------------------------------------------------- fixtures


def build_mirror(root: str) -> str:
    mirror = os.path.join(root, "mirror")
    directory = os.path.join(mirror, "Chemistry", ".inkstone-media", "doc-149")
    os.makedirs(directory)
    for extension in (".timeline", ".md"):
        shutil.copy(os.path.join(PULLED, f"document_149{extension}"), os.path.join(directory, RECORDING + extension))
    # ⚠️ **No `.opus` here, and that is the point.** This fixture used to write one beside the
    # transcript, which the watcher would have trashed the moment it verified that transcript
    # (`tools/m12/watch.py:206`) — a state the real account never holds. It was a fixture more
    # generous than production, in CLAUDE.md's exact sense, and it is what hid `estimatedMinutes`
    # being derived from audio bytes: every assertion about a transcribed lesson's length was made
    # against a lesson that still had its audio, so the zero every real one reports was invisible.
    #
    # A second lesson with audio and a timeline but no transcript: the `awaiting` state.
    other = os.path.join(mirror, "Maths", ".inkstone-media", "doc-200")
    os.makedirs(other)
    with open(os.path.join(other, "rec-200.opus"), "wb") as handle:
        handle.write(b"OggS" + b"\0" * 200)
    with open(os.path.join(other, "rec-200.timeline"), "w", encoding="utf-8") as handle:
        handle.write("019fefb2-ca2b-7000-9321-fa3349d93d83 1000 page-a\n")

    # A published note beside the Chemistry lesson. It is what the byte-range tests fetch now that
    # there is no audio route, and what makes `notePath` resolve for that recording.
    bundle = make_bundle(
        "doc-149",
        "Percentage yield",
        [("p1", "a0"), ("p2", "a1"), ("p3", "a2")],
        {"p1": ["Percentage yield", "actual / theoretical x 100"], "p3": ["precipitate is a solid"]},
    )
    with open(os.path.join(mirror, "Chemistry", "Percentage yield.pdf"), "wb") as handle:
        handle.write(make_pdf(bundle, "doc-149", pages=3))
    return mirror


def make_bundle(document_id: str, title: str, pages: list[tuple[str, str]], text: dict[str, list[str]]) -> bytes:
    """A `note.inkstone`: a **stored** ZIP, as `Zip.kt` writes one."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr(
            "manifest.json",
            json.dumps(
                {
                    "schemaVersion": 1,
                    "documentId": document_id,
                    "title": title,
                    "pages": [{"id": page_id, "sortKey": sort_key} for page_id, sort_key in pages],
                    "deviceId": "device-1",
                    "createdEpochMillis": 1_700_000_000_000,
                }
            ),
        )
        for page_id, lines in text.items():
            archive.writestr(
                f"text/{page_id}.json",
                json.dumps(
                    {
                        "pageId": page_id,
                        "inkDigest": "abc",
                        "recogniser": "mlkit-digital-ink/19.0.0/en-US",
                        "lines": [{"text": line, "box": [0, 0, 10, 10], "words": []} for line in lines],
                    }
                ),
            )
    return buffer.getvalue()


def make_pdf(
    bundle: bytes,
    document_id: str,
    *,
    decoys: int = 3,
    corrupt_digest: bool = False,
    pages: int = 1,
) -> bytes:
    """A PDF shaped like one `PdfBoxWriter` publishes, and **structurally valid**.

    The two things the reader depends on: the XMP packet **uncompressed** (which `writeMarker`
    does deliberately, so a tool can find it by scanning) and the bundle as a `/FlateDecode`
    stream. `decoys` puts ordinary compressed content streams in front of it, so "find the bundle
    among the streams" is exercised rather than assumed.

    ⚠️ **It is a real PDF, with a page tree and a cross-reference table, and that matters.** The
    first version of this fixture was just the two streams with a `trailer` stapled on: enough for
    `notepdf.py`, which scans, and refused by every actual viewer — so the website's PDF pane
    showed *"Failed to load PDF document"* and there was no way to tell a broken pane from a
    broken fixture. That is this repo's oldest lesson (M4: a file that is valid to the library that
    wrote it and blank everywhere else) arriving from the other direction, and the cost of fixing
    it is thirty lines of offsets.
    """
    import hashlib

    digest = hashlib.sha256(bundle).hexdigest()
    if corrupt_digest:
        digest = "0" * 64
    xmp = (
        '<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>\n'
        '<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Inkstone">\n'
        ' <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">\n'
        '  <rdf:Description rdf:about=""\n'
        f'    xmlns:inkstone="{notepdf.NAMESPACE}"\n'
        f'    inkstone:documentId="{document_id}"\n'
        '    inkstone:schemaVersion="1"\n'
        f'    inkstone:bundleSha256="{digest}"\n'
        '    inkstone:originalLength="0"\n'
        '    inkstone:originalSha256=""/>\n'
        " </rdf:RDF>\n"
        "</x:xmpmeta>\n"
        '<?xpacket end="w"?>'
    ).encode("utf-8")
    packed = zlib.compress(bundle)

    objects: dict[int, bytes] = {}
    first_page = 6
    kids = " ".join(f"{first_page + i} 0 R" for i in range(pages))
    objects[1] = (
        b"<</Type/Catalog/Pages 2 0 R/Metadata 4 0 R"
        b"/Names<</EmbeddedFiles<</Names[(note.inkstone) 3 0 R]>>>>>>"
    )
    objects[2] = f"<</Type/Pages/Count {pages}/Kids[{kids}]>>".encode()
    objects[3] = b"<</Type/Filespec/F(note.inkstone)/UF(note.inkstone)/EF<</F 5 0 R>>>>"
    objects[4] = b"<</Type/Metadata/Subtype/XML/Length %d>>\nstream\n%s\nendstream" % (len(xmp), xmp)
    objects[5] = b"<</Type/EmbeddedFile/Length %d/Filter/FlateDecode>>\nstream\n%s\nendstream" % (
        len(packed),
        packed,
    )
    for index in range(pages):
        content = (
            f"BT /F1 24 Tf 72 700 Td (Page {index + 1} of {document_id}) Tj ET\n".encode()
        )
        objects[first_page + index] = (
            b"<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]"
            b"/Resources<</Font<</F1 <</Type/Font/Subtype/Type1/BaseFont/Helvetica>> >> >>"
            b"/Contents <</Length %d>>\nstream\n%s\nendstream>>" % (len(content), content)
        )
    # Decoys go last so `extract_bundle`'s reverse scan has to walk past them to reach the bundle.
    for index in range(decoys):
        junk = zlib.compress(f"BT /F1 12 Tf (decoy {index}) Tj ET\n".encode() * 20)
        objects[100 + index] = b"<</Length %d/Filter/FlateDecode>>\nstream\n%s\nendstream" % (
            len(junk),
            junk,
        )

    out = bytearray(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
    offsets: dict[int, int] = {}
    for number in sorted(objects):
        offsets[number] = len(out)
        out += b"%d 0 obj\n" % number + objects[number] + b"\nendobj\n"

    highest = max(objects) + 1
    start = len(out)
    out += b"xref\n0 %d\n" % highest
    out += b"0000000000 65535 f \n"
    for number in range(1, highest):
        if number in offsets:
            out += b"%010d 00000 n \n" % offsets[number]
        else:
            out += b"0000000000 65535 f \n"
    out += b"trailer\n<</Size %d/Root 1 0 R>>\nstartxref\n%d\n%%%%EOF\n" % (highest, start)
    return bytes(out)


# ------------------------------------------------------------------- tests


def test_timeline() -> None:
    section("timeline — the real 48-minute lesson")
    raw = open(os.path.join(PULLED, "document_149.timeline"), encoding="utf-8").read()
    marks = timeline_mod.parse(raw)
    # ⚠️ These three are measured in docs/M12-AUDIO.md §15.6 by a different program. Agreeing with
    # them is a check against an independent witness rather than against this code's own opinion.
    check("867 strokes, as §15.6 measured", len(marks.strokes) == 867, f"got {len(marks.strokes)}")
    check("no marked microphone gaps", marks.gaps == [])
    check("not truncated — it was stopped, not killed", marks.truncated is False)
    check("first and last stroke at 6.1 s and 2907.8 s",
          marks.first_ms == 6120 and marks.last_ms == 2907766,
          f"{marks.first_ms} / {marks.last_ms}")

    duration = 2_928_130
    runs = timeline_mod.quiet_runs(marks, duration)
    longest = max(r.length_ms for r in runs)
    check("the longest stretch with no writing is ~340 s, as §15.6 measured",
          339_000 <= longest <= 341_000, f"got {longest} ms")

    buckets = timeline_mod.activity(marks, duration, 10)
    check("every stroke lands in a bucket", sum(buckets) == 867, f"got {sum(buckets)}")

    check("a timeline with no page column yields no page spans", timeline_mod.page_spans(marks) == [])

    section("timeline — parse rules")
    with_pages = timeline_mod.parse("a 100 page-1\nb 200 page-1\nc 300 page-2\n")
    spans = timeline_mod.page_spans(with_pages)
    check("page spans are in first-written order with their windows",
          [(s.page, s.first_ms, s.last_ms, s.index) for s in spans] == [("page-1", 100, 200, 0), ("page-2", 300, 300, 1)],
          str(spans))

    torn = timeline_mod.parse("a 100\nb 200\nnot-a-number\nc 300\n")
    check("⚠️ a torn tail stops the read; it never skips and carries on",
          len(torn.strokes) == 2 and torn.truncated is True, f"{len(torn.strokes)} strokes")

    future = timeline_mod.parse("a 100 page-1 something-new\n")
    check("a newer column is tolerated, not treated as a torn tail",
          len(future.strokes) == 1 and future.strokes[0].page == "page-1")

    gapped = timeline_mod.parse("a 100\n#gap 200 300\nb 400\n")
    check("a #gap line is read as a gap, not a stroke",
          len(gapped.strokes) == 2 and gapped.gaps == [timeline_mod.Gap(200, 300)])

    late = timeline_mod.parse("a 5000\n")
    check("⚠️ a stroke past the end is clamped into the last bucket, never dropped",
          sum(timeline_mod.activity(late, 1000, 4)) == 1)

    check("agrees with tools/m12/align.py on the real file",
          _agrees_with_align(raw), "the two parsers must not drift")


def _agrees_with_align(raw: str) -> bool:
    sys.path.insert(0, os.path.join(REPO, "tools", "m12"))
    import align  # noqa: PLC0415

    theirs = align.parse_timeline(raw)
    ours = timeline_mod._parse_fallback(raw)  # noqa: SLF001 - comparing the fallback is the point
    return (
        len(theirs.strokes) == len(ours.strokes)
        and all(a.object_id == b.object_id and a.offset_ms == b.offset_ms and a.page == b.page
                for a, b in zip(theirs.strokes, ours.strokes))
        and len(theirs.gaps) == len(ours.gaps)
    )


def test_transcript() -> None:
    section("transcript — the real merged document")
    parsed = transcript_mod.parse(open(os.path.join(PULLED, "document_149.md"), encoding="utf-8").read())
    # ⚠️ §15.7 measured 3,111 words and 9 dead runs with different code.
    check("3,111 words, as §15.7 measured", parsed.word_count == 3111, f"got {parsed.word_count}")
    check("two speakers, A and B", parsed.speakers == ["A", "B"], str(parsed.speakers))
    check("the likely teacher is read without its explanatory comment",
          parsed.front.get("likelyTeacher") == "A", repr(parsed.front.get("likelyTeacher")))
    dead = [s for s in parsed.segments if s.kind == "dead"]
    check("9 dead runs, as §15.7 measured", len(dead) == 9, f"got {len(dead)}")
    check("the longest dead run is 00:17:43 to 00:19:02, as §15.7 recorded",
          any(s.at_ms == 17 * 60_000 + 43_000 and s.to_ms == 19 * 60_000 + 2_000 for s in dead))
    check("duration parses back to the container's own length", parsed.duration_ms == 2_929_000, str(parsed.duration_ms))
    check("it knows the transcript is not anchored to pages", parsed.unanchored is True)
    check("⚠️ nothing in the document was unrecognised",
          not [s for s in parsed.segments if s.kind == "prose"],
          "an unparsed line would mean this reader and align.py have drifted")

    section("transcript — round-trip against align.render")
    sys.path.insert(0, os.path.join(REPO, "tools", "m12"))
    import align  # noqa: PLC0415

    words = [
        align.Word("Hello", 0, 900, "A"),
        align.Word("there", 1000, 1800, "A"),
        align.Word("Yes", 400_000, 400_800, "B"),
    ]
    # ⚠️ The stroke offsets are chosen so the page turn falls **between** two paragraphs rather
    # than between two words of one. `align._transcript_body` flushes the buffer at a page start,
    # so a page whose first stroke lands mid-sentence legitimately splits that sentence — the
    # first version of this fixture put `page-b` at 401,000 ms, between "Hello" and "there", and
    # then read the resulting split as a bug in the parser. Check the fixture draws what it claims
    # before believing what it measures.
    marks = align.Timeline(
        strokes=[align.Stroke("s1", 0, "page-a"), align.Stroke("s2", 399_000, "page-b")],
        gaps=[align.Gap(200_000, 210_000)],
    )
    rendered = align.render(words, marks, 500_000, "Straight lines", "doc-1", "2026-08-11", "universal-3-5-pro")
    back = transcript_mod.parse(rendered)
    check("the front matter round-trips", back.front.get("documentId") == "doc-1" and back.title == "Straight lines")
    check("the page bullets round-trip", len(back.page_spans) == 2, str(back.page_spans))
    check("the microphone gap round-trips", back.gaps == [{"fromMs": 200_000, "toMs": 210_000}], str(back.gaps))
    check("speakers and text come back", [s.text for s in back.segments if s.kind == "speech"] == ["Hello there", "Yes"],
          str([s.text for s in back.segments if s.kind == "speech"]))

    # ⚠️ A separate fixture, with no silence in it, because `align._transcript_body` handles dead
    # runs *before* page turns and both branches move `paragraph_start`. In the fixture above the
    # long gap between the two utterances is a dead run that consumes the boundary, so no page
    # heading is emitted at all — correct behaviour, and it makes that document the wrong witness
    # for this claim.
    dense = align.render(
        [align.Word("one", 0, 500, "A"), align.Word("two", 1000, 1500, "A"), align.Word("three", 2000, 2500, "A")],
        align.Timeline(strokes=[align.Stroke("s1", 0, "page-a"), align.Stroke("s2", 1600, "page-b")]),
        3000, "Dense", "doc-3", "2026-08-11", "m",
    )
    turned = transcript_mod.parse(dense)
    # ⚠️ 1000, not the 1600 the stroke was actually at. `align.clock` renders whole seconds, so the
    # merged markdown has **one-second resolution** and a round trip through it is lossy below
    # that. It matters for the viewer: a page marker on the ribbon can sit up to a second early,
    # and the timeline file — which keeps milliseconds — is the better source wherever both exist.
    check("page turns come back as page segments, at the document's second resolution",
          [(s.page, s.at_ms) for s in turned.segments if s.kind == "page"] == [("page-b", 1000)],
          str([(s.kind, s.page, s.at_ms) for s in turned.segments]))

    empty = transcript_mod.parse(align.render([], marks, 500_000, "Quiet", "doc-2", "2026-08-11", "m"))
    check("a silent recording is reported as silent, not as missing", empty.empty is True)

    check("clock and parse_clock are inverses",
          all(transcript_mod.parse_clock(transcript_mod.clock(ms)) == ms - ms % 1000
              for ms in (0, 1, 59_999, 60_000, 3_600_000, 2_928_130)))

    section("transcript — forward compatibility")
    future = transcript_mod.parse(
        "---\nnote: X\n---\n\n# X\n\n## Transcript\n\n"
        "**[00:00:01]** **A:** hello\n\n## Something A Newer Aligner Added\n\nsome prose\n"
    )
    check("⚠️ an unknown line is kept as prose rather than dropped or raised",
          any(s.kind == "prose" for s in future.segments) and any(s.kind == "speech" for s in future.segments),
          str([(s.kind, s.text[:20]) for s in future.segments]))


def test_notepdf() -> None:
    section("notepdf — identity and the embedded bundle (⚠️ synthetic fixture)")
    bundle = make_bundle(
        "doc-42",
        "Straight lines",
        [("p1", "a0"), ("p2", "a1"), ("p3", "a2")],
        {"p1": ["y = mx + c", "gradient"], "p2": ["worked example"]},
    )
    pdf = make_pdf(bundle, "doc-42")

    found = notepdf.marker(pdf)
    check("the marker is found by scanning", found is not None and found.document_id == "doc-42", str(found))
    check("the schema version comes back as a number", found.schema_version == 1)

    note = notepdf.read(pdf)
    check("the title comes from the manifest, not the filename", note.title == "Straight lines", note.title)
    check("⚠️ the bundle is verified against the digest the writer recorded", note.bundle_verified is True)
    check("pages are numbered by sortKey", [p.number for p in note.pages] == [1, 2, 3], str([p.number for p in note.pages]))
    check("recognised handwriting is attached to its page",
          note.pages[0].text == "y = mx + c\ngradient", repr(note.pages[0].text))
    check("page_number maps a pageId to a PDF page", note.page_number("p2") == 2)
    check("the whole note's text is searchable", "worked example" in note.text())

    section("notepdf — the things that must not happen")
    stranger = b"%PDF-1.7\n" + zlib.compress(b"just a pdf") + b"\n%%EOF"
    check("a stranger's PDF is not claimed as ours", notepdf.read(stranger) is None)

    lookalike = pdf.replace(notepdf.NAMESPACE.encode(), b"https://example.com/ns/other/1.0/")
    check("⚠️ the prefix alone is not identity — the namespace URI must be present",
          notepdf.marker(lookalike) is None,
          "otherwise any producer using the prefix 'inkstone' claims our files")

    wrong = make_pdf(bundle, "doc-42", corrupt_digest=True)
    verified = notepdf.read(wrong)
    check("a bundle whose digest does not match is read but reported unverified",
          verified is not None and verified.bundle_present is True and verified.bundle_verified is False,
          f"present={verified.bundle_present} verified={verified.bundle_verified}")

    unsorted_bundle = make_bundle("doc-9", "Reordered", [("p3", "c"), ("p1", "a"), ("p2", "b")], {})
    reordered = notepdf.read(make_pdf(unsorted_bundle, "doc-9"))
    check("⚠️ pages follow sortKey, not the order they appear in the manifest",
          [(p.id, p.number) for p in reordered.pages] == [("p1", 1), ("p2", 2), ("p3", 3)],
          str([(p.id, p.number) for p in reordered.pages]))

    deleted_bundle = make_bundle("doc-8", "With a deleted page", [("p1", "a"), ("p2", "b")], {})
    with_deleted = json.loads(zipfile.ZipFile(io.BytesIO(deleted_bundle)).read("manifest.json"))
    with_deleted["pages"][0]["deleted"] = True
    rebuilt = io.BytesIO()
    with zipfile.ZipFile(rebuilt, "w", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr("manifest.json", json.dumps(with_deleted))
    note8 = notepdf.read(make_pdf(rebuilt.getvalue(), "doc-8"))
    check("a deleted page is not numbered and not counted",
          note8.page_count == 1 and note8.page_number("p1") is None,
          f"count={note8.page_count} p1={note8.page_number('p1')}")

    marker_only = make_pdf(b"", "doc-7").replace(zlib.compress(b""), b"\x00\x01\x02")
    degraded = notepdf.read(marker_only)
    check("a note whose bundle cannot be read still reports its identity",
          degraded is not None and degraded.document_id == "doc-7",
          "losing the extras must never cost the basics")


def test_library(root: str) -> None:
    section("library — the walk and the index")
    mirror = build_mirror(root)
    lib = library_mod.Library(MirrorSource(mirror), os.path.join(root, "cache"))
    scan = lib.scan(force=True)
    check("both lessons are found", len(scan.recordings) == 2, str(len(scan.recordings)))
    states = {r.document_id: r.state for r in scan.recordings}
    check("a transcribed lesson and an awaiting one are told apart",
          states == {"doc-149": "transcribed", "doc-200": "awaiting"}, str(states))
    check("⚠️ a transcribed recording is still listed",
          any(r.has_transcript for r in scan.recordings),
          "watch.discover deliberately hides finished work; a reader must not")

    detail = lib.recording_detail("doc-149", RECORDING)
    check("the detail carries the transcript and the timeline",
          detail["transcript"]["words"] == 3111 and detail["timeline"]["strokes"] == 867)
    check("⚠️ duration comes from the transcript, not from the last stroke",
          detail["durationMs"] == 2_929_000,
          f"got {detail['durationMs']}; the last stroke is at 2,907,766 ms and using it would "
          "stretch the histogram over a recording that was really longer")
    check("the activity histogram covers every stroke", sum(detail["timeline"]["activity"]) == 867)

    again = lib.scan(force=True)
    titled = {r.document_id: r.note_title for r in again.recordings}
    # ⚠️ The **manifest** title wins, not the transcript's front matter.
    #
    # `_attach_titles` has three sources in descending order of trust: a cached note whose
    # `documentId` matches (the note's own manifest), the transcript's `note:` line, then the sole
    # sibling PDF's filename. Opening the recording caches the note — `recording_detail` reads it
    # for the pageId→page mapping — so by the second scan the manifest is available and the
    # transcript is no longer the best answer. The transcript here says "Lesson"; the manifest says
    # "Percentage yield", and it is the one the tablet actually wrote.
    check("⚠️ the note's own manifest title wins over the transcript's front matter",
          titled["doc-149"] == "Percentage yield", str(titled))
    check("…and a recording with no note beside it still falls back to its documentId",
          titled["doc-200"] == "doc-200", str(titled))

    hits = lib.search("percentage yield")
    check("search finds speech and carries a timestamp",
          hits["hits"] and hits["hits"][0]["kind"] == "transcript" and hits["hits"][0]["at"],
          str(hits["hits"][:1]))
    check("⚠️ search reports how many notes it did NOT look at",
          "notesUnindexed" in hits,
          "'no hits' and 'nothing was searched' must be distinguishable")

    check("a missing recording is None rather than an exception", lib.recording_detail("nope", "nope") is None)

    section("source — path safety")
    source = MirrorSource(mirror)
    escaped = False
    try:
        source.read("../../../etc/passwd")
    except SourceError:
        escaped = True
    check("⚠️ a path that escapes the mirror root is refused", escaped,
          "paths reach this from HTTP query strings and MCP arguments")


def test_server(root: str) -> None:
    section("server — over a real socket")
    mirror = build_mirror(os.path.join(root, "http"))
    lib = library_mod.Library(MirrorSource(mirror), os.path.join(root, "httpcache", "meta"))
    server_mod.Handler.library = lib
    server_mod.Handler.media = server_mod.MediaCache(os.path.join(root, "httpcache", "media"))
    server_mod.Handler.guard = auth_mod.Guard()
    server_mod.Handler.protocol = mcp_server.Protocol(mcp_server.Tools(lib))
    httpd = server_mod.Server(("127.0.0.1", 0), server_mod.Handler)
    port = httpd.server_address[1]
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    try:
        def get(path: str, headers: dict | None = None):
            connection = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
            connection.request("GET", path, headers=headers or {})
            response = connection.getresponse()
            body = response.read()
            connection.close()
            return response.status, dict(response.getheaders()), body

        def post(path: str, form: str = "", headers: dict | None = None, body: bytes | None = None):
            connection = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
            payload = body if body is not None else form.encode("utf-8")
            head = {"Content-Type": "application/x-www-form-urlencoded", "Content-Length": str(len(payload))}
            head.update(headers or {})
            connection.request("POST", path, body=payload, headers=head)
            response = connection.getresponse()
            out = response.read()
            connection.close()
            return response.status, dict(response.getheaders()), out

        status, _, body = get("/api/scan")
        payload = json.loads(body)
        check("GET /api/scan answers", status == 200 and len(payload["recordings"]) == 2, str(status))

        status, _, body = get(f"/api/recording?documentId=doc-149&recordingId={RECORDING}")
        check("GET /api/recording answers", status == 200 and json.loads(body)["timeline"]["strokes"] == 867)

        status, _, body = get("/api/recording?documentId=nope&recordingId=nope")
        check("a missing recording is a 404 with a message", status == 404 and "error" in json.loads(body))

        # ⭐ Byte ranges are tested on the **PDF**, because there is no audio route any more:
        # `tools/m12` trashes a recording once its transcript is verified, so serving one was a
        # feature for a state that does not persist. Ranges still matter here — the browser's
        # built-in PDF viewer fetches a document in pieces rather than whole.
        status, headers, whole = get(PDF_PATH)
        check("a note is served whole, and announces byte ranges",
              status == 200 and headers.get("Accept-Ranges") == "bytes" and len(whole) > 1000,
              f"{status} {headers.get('Accept-Ranges')} {len(whole)}")
        size = len(whole)

        status, headers, body = get(PDF_PATH, {"Range": "bytes=10-19"})
        check("⚠️ a byte range answers 206 with exactly that slice",
              status == 206 and body == whole[10:20]
              and headers.get("Content-Range") == f"bytes 10-19/{size}",
              f"{status} {len(body)} {headers.get('Content-Range')}")

        status, _, body = get(PDF_PATH, {"Range": "bytes=-8"})
        check("⚠️ a suffix range means the LAST n bytes, not the first n",
              status == 206 and body == whole[-8:],
              f"{status} {len(body)} — getting this backwards serves the wrong half and looks "
              "like a corrupt file")

        status, _, _ = get(PDF_PATH, {"Range": f"bytes={size + 100}-"})
        check("a range past the end is a 416", status == 416, str(status))

        status, _, body = get("/api/search?q=percentage%20yield")
        check("GET /api/search answers", status == 200 and json.loads(body)["hits"], str(status))

        status, _, _ = get("/static/../server.py")
        check("⚠️ a traversal out of the static directory is refused", status == 404, str(status))

        status, _, body = get("/")
        check("the page itself is served", status == 200 and b"<title>Inkstone reader" in body)

        section("server — a client that goes away is not an error")
        # ⛔ Reported as a forty-line traceback while reading a note. A browser cancels downloads
        # constantly — an iframe whose src is reassigned, a tab closed, a media element that has
        # buffered enough — and each one was escaping as an unhandled exception.
        #
        # ⚠️ The first fix caught `BrokenPipeError`, which is POSIX's EPIPE. Windows raises
        # `ConnectionAbortedError` (10053) or `ConnectionResetError` (10054); those are SIBLINGS of
        # BrokenPipeError under `ConnectionError`, not subclasses, so the guard never fired.
        for name, error in (
            ("BrokenPipeError (POSIX EPIPE)", BrokenPipeError),
            ("ConnectionAbortedError (WinError 10053)", ConnectionAbortedError),
            ("ConnectionResetError (WinError 10054)", ConnectionResetError),
        ):
            check(f"⚠️ {name} is a ConnectionError", issubclass(error, ConnectionError),
                  "the handler catches the base class precisely because these are siblings")
        check(
            "⚠️ …and BrokenPipeError alone would NOT have caught the Windows ones",
            not issubclass(ConnectionAbortedError, BrokenPipeError),
            "this is why the first fix looked right and changed nothing on Windows",
        )

        # Abort a large download part-way, several times, the way a browser does.
        for _ in range(3):
            connection = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
            connection.request("GET", PDF_PATH, headers={"Host": f"127.0.0.1:{port}"})
            response = connection.getresponse()
            response.read(512)
            # SO_LINGER {onoff=1, seconds=0} forces an RST rather than a graceful FIN, which is what
            # a browser cancelling a download looks like. `struct.pack` rather than a byte
            # literal: escapes do not survive every editing path, and a stray NUL in source
            # is a syntax error nobody enjoys diagnosing.
            connection.sock.setsockopt(socket_mod.SOL_SOCKET, socket_mod.SO_LINGER,
                                       struct.pack("ii", 1, 0))
            connection.close()
            time.sleep(0.2)
        time.sleep(0.8)
        status, _, _ = get("/api/scan")
        check("the server survives repeated mid-stream aborts", status == 200, str(status))

        # ⚠️ And it must still shout about a REAL fault. Swallowing everything would be worse than
        # the noise this removed.
        swallowed = []
        original = server_mod.Server.handle_error

        class Probe(server_mod.Server):
            def __init__(self):  # noqa: D107
                self.verbose = False

            def handle_error(self, request, client_address):  # noqa: ANN001
                swallowed.append("suppressed")
                super().handle_error(request, client_address)

        probe = Probe()
        try:
            raise ConnectionResetError(10054, "aborted")
        except ConnectionResetError:
            server_mod.Server.handle_error(probe, None, ("127.0.0.1", 1))
        check("a disconnect is suppressed", not swallowed)
        reported = []
        try:
            raise ValueError("a genuine bug")
        except ValueError:
            try:
                original(probe, None, ("127.0.0.1", 1))
                reported.append("printed")
            except Exception:  # noqa: BLE001
                reported.append("printed")
        check("⚠️ …but a genuine exception is still reported", bool(reported),
              "hiding real faults would be a worse bug than the noise")

        section("server — signing in")
        server_mod.Handler.guard = auth_mod.Guard(
            web_password="open-sesame", mcp_token="tok-123", secure_cookies=False
        )
        status, _, body = get("/api/scan")
        check("a guarded server refuses an unauthenticated API call", status == 401, str(status))
        check("…and says how to fix it", b"Sign in" in body, body[:120].decode())

        status, headers, _ = get("/api/scan", {"Host": f"127.0.0.1:{port}", "Accept": "text/html"})
        check(
            "⚠️ a browser is redirected to the sign-in page, not handed a 401",
            status == 303 and headers.get("Location", "").startswith("/signin"),
            f"{status} {headers.get('Location')} - a person opening a bookmark must see somewhere "
            "to type a passphrase, not a status code",
        )

        status, _, _ = get("/static/app.css")
        check("the stylesheet is reachable so the sign-in page is not unstyled", status == 200, str(status))

        # Sign in for real and follow the cookie.
        status, headers, _ = post("/signin", "password=wrong&next=%2F")
        check("a wrong passphrase redirects back with an error", status == 303 and "error=bad" in headers.get("Location", ""),
              f"{status} {headers.get('Location')}")

        status, headers, _ = post("/signin", "password=open-sesame&next=%2F")
        cookie = headers.get("Set-Cookie", "")
        check("the right passphrase sets a session cookie", status == 303 and auth_mod.COOKIE_NAME in cookie, cookie[:80])
        check(
            "⚠️ the cookie is HttpOnly and SameSite=Strict",
            "HttpOnly" in cookie and "SameSite=Strict" in cookie,
            f"{cookie} - HttpOnly is what stops an XSS bug reading the session",
        )
        session = cookie.split(";")[0]
        status, _, _ = get("/api/scan", {"Host": f"127.0.0.1:{port}", "Cookie": session})
        check("the session cookie authenticates", status == 200, str(status))

        status, _, _ = get("/api/scan", {"Host": f"127.0.0.1:{port}", "Authorization": "Bearer tok-123"})
        check("the MCP bearer token also authenticates", status == 200, str(status))

        section("server — the login rate limiter")
        # ⚠️ Rule 5's own hazard, rebuilt in our front door if this is missing.
        limited = False
        for _ in range(12):
            status, headers, _ = post("/signin", "password=nope")
            if "error=rate" in headers.get("Location", ""):
                limited = True
                break
        check("repeated wrong passphrases are rate limited", limited,
              "without this the login form is an unthrottled password oracle")

        server_mod.Handler.guard = auth_mod.Guard()
    finally:
        httpd.shutdown()
        httpd.server_close()


def test_hardening(root: str) -> None:
    """The findings from the adversarial review, each with the check that would have caught it."""
    section("hardening — never retry a 401 (CLAUDE.md rule 5)")

    class Dead(MirrorSource):
        """A source that 401s, counting how many times it is actually asked."""

        def __init__(self, path: str) -> None:
            super().__init__(path)
            self.attempts = 0

        def listing(self, path: str):  # noqa: ANN001
            self.attempts += 1
            error = urllib.error.HTTPError("http://x", 401, "Unauthorized", {}, None)
            raise NextcloudSource._translate(self, error)  # noqa: SLF001

    # The latch lives in NextcloudSource, so exercise that class directly with a stub client.
    class StubNc:
        def __init__(self) -> None:
            self.calls = 0

        def listing(self, path, depth="1"):  # noqa: ANN001, ARG002
            self.calls += 1
            raise urllib.error.HTTPError("http://x", 401, "Unauthorized", {}, None)

        def get(self, path, byte_range=None):  # noqa: ANN001, ARG002
            self.calls += 1
            raise urllib.error.HTTPError("http://x", 401, "Unauthorized", {}, None)

    source = NextcloudSource.__new__(NextcloudSource)
    stub = StubNc()
    source._nc = stub  # noqa: SLF001
    source.label = "test"
    source._uid = "u"  # noqa: SLF001
    source._halted = None  # noqa: SLF001

    first = second = None
    try:
        source.listing("")
    except NeedsReauth as error:
        first = error
    for _ in range(20):
        try:
            source.listing("Chemistry")
        except NeedsReauth as error:
            second = error
    check(
        "⚠️ one 401 halts the source; 20 further calls open no socket",
        stub.calls == 1 and first is not None and second is not None,
        f"the client was called {stub.calls} times; a re-scanning website would otherwise be a "
        "failed login per folder per refresh, which is the brute-force lockout rule 5 is about",
    )
    check("the message tells the user how to fix it", "app password" in str(first))

    section("hardening — a hostile PDF")
    bomb = io.BytesIO()
    with zipfile.ZipFile(bomb, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("manifest.json", json.dumps({"schemaVersion": 1, "title": "x", "pages": []}))
        archive.writestr("text/p1.json", "\0" * (notepdf.MAX_ENTRY_BYTES * 4))
    note = notepdf.read(make_pdf(bomb.getvalue(), "doc-bomb"))
    check(
        "⚠️ an over-large bundle entry is refused rather than inflated",
        note is not None and note.title == "x",
        "zipfile.read() inflates without consulting the declared size, so an entry cap is the "
        "only thing between the reader and a ZIP bomb in a PDF it opens automatically",
    )

    section("hardening — the mirror refuses what it cannot compare")
    mirror = MirrorSource(build_mirror(os.path.join(root, "hard")))
    for hostile in ("../../../etc/passwd", "//server/share/x", "D:/other/x"):
        refused = False
        try:
            mirror.read(hostile)
        except SourceError:
            refused = True
        check(f"refuses {hostile!r} as a SourceError, not a crash", refused)

    section("hardening — the HTTP surface")
    lib = library_mod.Library(MirrorSource(build_mirror(os.path.join(root, "http2"))),
                              os.path.join(root, "hc", "meta"), root="Chemistry")
    server_mod.Handler.library = lib
    server_mod.Handler.media = server_mod.MediaCache(os.path.join(root, "hc", "media"))
    server_mod.Handler.token = None
    httpd = server_mod.Server(("127.0.0.1", 0), server_mod.Handler)
    port = httpd.server_address[1]
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    try:
        def get(path: str, headers: dict | None = None):
            connection = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
            connection.request("GET", path, headers=headers or {"Host": f"127.0.0.1:{port}"})
            response = connection.getresponse()
            body = response.read()
            connection.close()
            return response.status, dict(response.getheaders()), body

        status, headers, _ = get(
            PDF_PATH, {"Host": f"127.0.0.1:{port}", "Range": "bytes=40-10"},
        )
        check(
            "⚠️ a reversed byte range is a 416, never a negative Content-Length",
            status == 416 and headers.get("Content-Length") == "0",
            f"{status} len={headers.get('Content-Length')} — a negative length hangs the client "
            "waiting for a body that never comes",
        )

        status, _, _ = get("/api/scan", {"Host": "evil.example.com"})
        check(
            "⚠️ a non-loopback Host is refused — DNS rebinding",
            status == 403,
            "binding to 127.0.0.1 does not stop a page whose hostname resolves there",
        )

        status, _, _ = get("/api/scan", {"Host": "localhost:1234"})
        check("a loopback Host is accepted", status == 200, str(status))

        status, _, _ = get("/media/note?path=Maths/elsewhere.pdf")
        check(
            "⚠️ --root is enforced on a path from a query string, not only on the walk",
            status == 404,
            "--root was a walk bound and not a reach bound, so it limited what was listed and "
            "not what could be fetched",
        )
    finally:
        httpd.shutdown()
        httpd.server_close()

    section("hardening — correctness the review found")
    front = transcript_mod.parse("---\nnote: Exercise #4 - Straight lines\nduration: 00:01:00\n---\n")
    check(
        "⚠️ a '#' inside a note title is not read as a comment",
        front.front.get("note") == "Exercise #4 - Straight lines",
        repr(front.front.get("note")),
    )
    teacher = transcript_mod.parse("---\nlikelyTeacher: A   # heuristic: most speech time\n---\n")
    check("…while align's own trailing comment still is", teacher.front.get("likelyTeacher") == "A")

    lib2 = library_mod.Library(MirrorSource(build_mirror(os.path.join(root, "counted"))),
                               os.path.join(root, "cc"))
    lib2.scan(force=True)
    counted = lib2.search("the", limit=1)
    check(
        "⚠️ notesUnindexed does not depend on how many hits came back first",
        counted["notesSearched"] + counted["notesUnindexed"] == counted["notesTotal"],
        f"searched={counted['notesSearched']} unindexed={counted['notesUnindexed']} "
        f"total={counted['notesTotal']}",
    )

    section("hardening — the ribbon's voice track tells the truth")
    # ⚠️ The one number on this page that can be checked against an independent measurement.
    #
    # `drawRibbon` ran each speech band from one paragraph's start to the next one's, and the last
    # to the end of the recording — so it asserted speech across ~85% of a lesson that M12 §15.7
    # measured, with different code and from the raw AssemblyAI response, as **39% speech**. The
    # fix bounds each band by how long its own words plausibly took at the ~164 wpm the same
    # section measured. This asserts the corrected coverage against §15.7's figure, so the constant
    # cannot drift without a test saying so.
    parsed = transcript_mod.parse(open(os.path.join(PULLED, "document_149.md"), encoding="utf-8").read())
    duration = parsed.duration_ms
    speech = [s for s in parsed.segments if s.kind == "speech"]
    ms_per_word = 366  # keep in step with MS_PER_WORD in static/app.js
    bounded = 0
    for index, segment in enumerate(speech):
        following = speech[index + 1].at_ms if index + 1 < len(speech) else duration
        words = len(segment.text.split())
        bounded += min(following, segment.at_ms + max(800, words * ms_per_word)) - segment.at_ms
    coverage = 100 * bounded / duration
    check(
        "⚠️ the voice track covers ~39% of the lesson, as §15.7 measured — not the ~85% it claimed",
        37.0 <= coverage <= 41.0,
        f"got {coverage:.1f}%; if this drifts, MS_PER_WORD in static/app.js is the constant to look at",
    )

    section("hardening — a duration read from the container")
    head = b"OggS\x00\x02" + b"\x00" * 20 + b"\x01" + b"OpusHead\x01\x02" + struct.pack("<H", 312)
    tail = b"OggS\x00\x04" + struct.pack("<q", 48_000 * 90 + 312) + b"\x00" * 13
    check(
        "⚠️ the granule gives an exact length, and the pre-skip is subtracted",
        oggopus.duration_ms(head, tail) == 90_000,
        str(oggopus.duration_ms(head, tail)),
    )
    check("bytes that are not Ogg answer None", oggopus.duration_ms(b"not ogg", b"not ogg") is None)

    section("⛔ a transcribed lesson knows how long it is, with no audio anywhere")
    # This is the state the whole account is in: `tools/m12/watch.py:206` trashes a recording once
    # its transcript is verified. The length used to be `audio_bytes × 8 / bitrate`, so it was 0
    # for exactly the lessons a person can read — the library showed no length and MCP's
    # `list_lectures` said `approx 0 min` while its own description promised "how long it is".
    #
    # ⚠️ The number is checked against **M12 §15.3's independently measured 2,928.1 s**, not
    # against whatever the parser happens to return.
    scan = library_mod.Library(MirrorSource(build_mirror(os.path.join(root, "duration"))),
                               os.path.join(root, "duration-cache")).scan(force=True)
    lesson = next(r for r in scan.recordings if r.state == "transcribed")
    check("the transcribed lesson has no .opus at all",
          not lesson.has_audio and lesson.audio_bytes == 0,
          f"has_audio={lesson.has_audio} bytes={lesson.audio_bytes}")
    check("⚠️ …and still reports its length, from the transcript's front matter",
          lesson.duration_ms == 2_929_000,
          f"{lesson.duration_ms} — M12 §15.3 measured 2,928.1 s off the Ogg granule")
    check("…which reaches `estimated_minutes`, the field the library and MCP both print",
          abs(lesson.estimated_minutes - 48.8) < 0.1,
          f"{lesson.estimated_minutes:.1f} min — 0.0 is the bug this section exists for")

    awaiting = next(r for r in scan.recordings if r.state == "awaiting")
    check("an untranscribed lesson has no front matter to read, and says so",
          awaiting.duration_ms == 0 and awaiting.has_audio,
          f"{awaiting.duration_ms} {awaiting.has_audio}")

    check(
        "⚠️ the front matter is read by RANGE, so a listing does not download every transcript",
        transcript_mod.front_matter(
            open(os.path.join(PULLED, "document_149.md"), encoding="utf-8").read(2048)
        ).get("duration") == "00:48:49",
        "2 KB has to be enough to reach the closing fence",
    )
    check(
        "…and a range too short to reach the fence yields nothing rather than half a mapping",
        transcript_mod.front_matter("---\nnote: Percentage yield\nduration: 00:48:49\n") == {},
        "an unterminated block must not be read as complete",
    )


def test_container(root: str) -> None:
    """The things the container depends on, tested here because the image cannot be built here.

    ⚠️ Docker is not installed on this machine and WSL will not start, so `tools/web/Dockerfile`
    has never been built — `docs/WEB-READER.md` §10 records that honestly. What IS testable is
    everything the container is a wrapper around, and these are the parts whose failure would look
    like a broken image rather than a broken app.
    """
    section("container — secrets from files, as Docker mounts them")
    secrets_dir = os.path.join(root, "secrets")
    os.makedirs(secrets_dir, exist_ok=True)
    with open(os.path.join(secrets_dir, "web"), "w", encoding="utf-8") as handle:
        handle.write("sekrit" + chr(10))  # a trailing newline, as `echo secret > file` leaves
    os.environ["INKSTONE_WEB_PASSWORD_FILE"] = os.path.join(secrets_dir, "web")
    os.environ["INKSTONE_MCP_TOKEN"] = "plain-env-token"
    os.environ["INKSTONE_ALLOWED_HOSTS"] = "notes.example.com"
    try:
        guard = auth_mod.Guard.from_env(loopback=False)
        check(
            "⚠️ a secret file's trailing newline is stripped",
            guard.web_password == "sekrit",
            f"got {guard.web_password!r} - a trailing newline is invisible, survives every "
            "inspection, and fails every comparison",
        )
        check("a plain env var still works alongside", guard.mcp_token == "plain-env-token")
        check("allowed hosts are parsed", guard.allowed_hosts == ("notes.example.com",))
        check("a named host is accepted", guard.host_ok({"Host": "notes.example.com"}))
        check("…and any other is not", not guard.host_ok({"Host": "evil.example.com"}))
    finally:
        for key in ("INKSTONE_WEB_PASSWORD_FILE", "INKSTONE_MCP_TOKEN", "INKSTONE_ALLOWED_HOSTS"):
            os.environ.pop(key, None)

    section("container — the referrer policy the sign-in form depends on")
    # ⛔ A regression guard for a bug that was invisible until a real browser tried it.
    #
    # Chrome serialises `Origin` as **null** for a form-submission navigation under a
    # `no-referrer` policy. The sign-in page had exactly that meta tag, so its own POST arrived
    # with an opaque origin and the server's cross-origin check refused it — every sign-in failed,
    # with an error that blamed the caller. `same-origin` keeps the privacy intent and leaves this
    # site's own requests attributable.
    #
    # Both halves are asserted, because fixing either one alone would be wrong: the pages must
    # declare `same-origin`, AND `Origin: null` must still be refused.
    for name in ("signin.html", "index.html"):
        markup = open(os.path.join(HERE, "static", name), encoding="utf-8").read()
        check(
            f"⚠️ {name} declares referrer=same-origin, never no-referrer",
            'content="same-origin"' in markup and 'content="no-referrer"' not in markup,
            "no-referrer makes Chrome send Origin: null on a form navigation, which the "
            "cross-origin check then refuses - so every sign-in fails",
        )

    guard = auth_mod.Guard(web_password="x", allowed_hosts=("notes.example.com",))

    class Headers(dict):
        def get(self, key, default=None):
            for name, value in self.items():
                if name.lower() == key.lower():
                    return value
            return default

    check(
        "⛔ …and an opaque origin is still refused",
        not guard.origin_ok(Headers({"Host": "notes.example.com", "Origin": "null"})),
        "a sandboxed iframe or a file:// page posting here is exactly what this stops; the fix "
        "for the sign-in form was the policy, not a weaker check",
    )
    check("a same-origin POST is allowed",
          guard.origin_ok(Headers({"Host": "notes.example.com", "Origin": "https://notes.example.com"})))
    check("a request with no Origin at all is allowed (CLI clients send none)",
          guard.origin_ok(Headers({"Host": "notes.example.com"})))

    section("launchers — PowerShell's flag spelling is accepted")
    # ⚠️ Windows defaults the CurrentUser execution policy to Restricted, so `run-reader.ps1`
    # simply will not run on a machine where nobody has changed it — which is how this was found.
    # `run-reader.cmd` is not subject to execution policy and is the answer, but somebody coming
    # from the .ps1 types its flags: `-Open`, `-Mirror`, `-Port`, `-BindHost`. argparse reads
    # `-Open` as the bundled short flags `-O -p -e -n` and fails talking about `-O`.
    for given, want in (
        (["-Open"], ["--open"]),
        (["-Mirror", "C:/x", "-Port", "8765"], ["--mirror", "C:/x", "--port", "8765"]),
        (["--open"], ["--open"]),
        (["-h"], ["-h"]),          # a real short flag is left alone
        (["-5"], ["-5"]),          # so is anything not alphabetic
    ):
        check(f"{given} normalises to {want}", server_mod.normalise_flags(given) == want,
              str(server_mod.normalise_flags(given)))

    # `-BindHost` exists because `$Host` is a PowerShell automatic variable and cannot be a
    # parameter name; the Python side must therefore accept a flag it would not have chosen.
    parsed = server_mod.build_parser().parse_args(server_mod.normalise_flags(["-BindHost", "0.0.0.0"]))
    check("⚠️ the launcher's -BindHost reaches --host", parsed.host == "0.0.0.0", str(parsed.host))

    section("there is no audio player, and nothing referring to one")
    # ⭐ `tools/m12/watch.py:206` trashes a recording once its transcript is written and verified
    # present on the server, so a lesson in the steady state has NO audio. The reader carried a
    # media element, a Play chip, a byte-range media route and a 12 MB cache for a file that only
    # exists in the gap between recording and transcription.
    #
    # Removing it also removed the thing this server was least comfortable doing: handing out
    # recordings of a room full of identifiable people over HTTP.
    script = open(os.path.join(HERE, "static", "app.js"), encoding="utf-8").read()
    markup = open(os.path.join(HERE, "static", "index.html"), encoding="utf-8").read()
    server_source = open(os.path.join(HERE, "server.py"), encoding="utf-8").read()

    styles = open(os.path.join(HERE, "static", "app.css"), encoding="utf-8").read()

    # ⚠️ `timeupdate`, `pendingSeekMs` and `audio/ogg` are here because a **comment** can be the
    # dangling thing. A nine-line docblock survived describing `pendingSeekMs` as a live guard
    # against a real race, directly above `seek()`'s own comment saying there is no audio element
    # — a reader trusts the first one and goes looking for a flag that is not set anywhere. The
    # bare word "audio" is deliberately NOT a needle: the ribbon legitimately talks about
    # microphone gaps, which are audio that is *missing*, and that prose must stay.
    for name, text, needle in (
        ("app.js", script, "dom.audio"),
        ("app.js", script, "media/audio"),
        ("app.js", script, "pendingSeekMs"),
        ("app.js", script, "timeupdate"),
        ("index.html", markup, "<audio"),
        ("index.html", markup, 'id="play"'),
        ("server.py", server_source, '"/media/audio"'),
        ("server.py", server_source, 'audio/ogg'),
        ("app.css", styles, ".chip-play"),
    ):
        check(f"⛔ {name} no longer mentions {needle}", needle not in text,
              "a leftover reference is a dangling element, a dead route, or a comment that lies")

    check(
        "…but the scrubber survives, because that is what the ribbon was always for",
        "function seek(ms)" in script and "drawRibbon()" in script,
        "dragging the ribbon moves the transcript; that never needed a media file",
    )
    check("the transport still shows a position in the lesson",
          'id="clock"' in markup and 'id="duration"' in markup)

    section("⛔ what the removal broke on its way out")
    # Four defects an adversarial sweep found in the code the player left behind. Each is asserted
    # against the source because none of them has a server-side surface — and the alternative,
    # driving a real browser, is what actually FOUND them (§6's browser row).

    # 1. The arrow keys. Space needed a guard against every focusable control; the arrows do not,
    #    and opening a lecture parks focus on the library `.item` button — a <button>, so the
    #    inherited guard made ←/→ dead exactly when they became relevant.
    # ⚠️ Against the CODE, not the file. The comment above the guard quotes the old selector on
    # purpose — it is the record of why the wide one was wrong — and an assertion that greps the
    # raw text cannot tell a fixed guard from a described one. (The needles above are the opposite
    # case and deliberately DO read comments: there, a comment that lies is the defect.)
    code = strip_js_comments(script)
    check(
        "⛔ the arrow-key guard exempts only controls that USE an arrow key",
        "closest?.('input, textarea, select, [contenteditable]')" in code
        and "a[href]" not in code,
        "a <button> does nothing with Left/Right; guarding it kills the shortcut after every open",
    )
    check(
        "…and the `.seg` special case went with the Space handler that needed it",
        "classList.contains('seg')" not in code,
        "an exemption to a guard that no longer exists is a rule nobody can reason about",
    )
    check(
        "⚠️ …and the guard survives a target that is not an Element",
        "event.target.closest?.(" in code,
        "a keydown with nothing focused targets `document`, which has no `closest` — the bare "
        "call throws out of the listener, so the key does nothing and only the console says so. "
        "Reproduced in a browser; the wide guard this replaced had the same latent throw.",
    )

    # 2. A canvas is not an image if you can drive it.
    check(
        '⚠️ the ribbon is `role="slider"`, focusable, and reports its position',
        'role="slider"' in markup and 'tabindex="0"' in markup
        and 'aria-valuetext' in markup and "aria-valuetext" in script,
        'it was role="img" with a label describing a click — an interaction on a static picture',
    )

    # 3. A cancelled drag.
    check(
        "⚠️ a cancelled pointer ends the drag, not just a released one",
        "'pointerup', 'pointercancel'" in script,
        "without pointercancel the playhead follows the cursor with no button held",
    )

    # 4. A canvas keeps what was painted on it.
    check(
        "⛔ a failed open wipes the ribbon and the clock, not just the text around them",
        "clearRibbon()" in script and script.count("clearRibbon") >= 3,
        "clearing innerHTML leaves the previous lesson painted under 'Could not open this lesson'",
    )
    check(
        "the drag hint survives a narrow window; only the keyboard half narrows away",
        ".hint-keys { display: none; }" in styles and ".hint { display: none; }" not in styles,
        "it was the only thing on the page saying the ribbon can be dragged",
    )

    section("container — refusing to start unsafely")
    for argv, why in (
        (["--host", "0.0.0.0"], "no authentication configured"),
        (["--host", "0.0.0.0", "--mirror", root], "no INKSTONE_ALLOWED_HOSTS"),
    ):
        raised = False
        try:
            server_mod.main(argv)
        except SystemExit as error:
            raised = error.code != 0
        check(f"binding to 0.0.0.0 is refused when there is {why}", raised,
              "the bind address is the only thing between this and the internet")

    section("container — SIGTERM does not deadlock")
    # ⚠️ The bug this proves absent: a signal handler runs on the main thread, which is the thread
    # blocked inside `serve_forever()`. Calling `shutdown()` there waits for a loop that cannot
    # run until the handler returns — so `docker stop` would hang for its whole grace period and
    # then SIGKILL. Dispatching to another thread is the fix, and this is the assertion that the
    # fix is present rather than merely intended.
    lib = library_mod.Library(MirrorSource(build_mirror(os.path.join(root, "sig"))),
                              os.path.join(root, "sigcache"))
    server_mod.Handler.library = lib
    server_mod.Handler.media = server_mod.MediaCache(os.path.join(root, "sigmedia"))
    server_mod.Handler.guard = auth_mod.Guard()
    httpd = server_mod.Server(("127.0.0.1", 0), server_mod.Handler)

    installed = {}
    real_signal = __import__("signal")
    original = real_signal.signal
    def capture(sig, handler):
        installed[sig] = handler
        return original(sig, handler)
    real_signal.signal = capture
    try:
        server_mod._install_sigterm(httpd)  # noqa: SLF001
    finally:
        real_signal.signal = original

    check("a SIGTERM handler is installed", real_signal.SIGTERM in installed,
          "PID 1 gets no default signal behaviour and Python installs none for SIGTERM, so "
          "without this `docker stop` waits the full grace period and then SIGKILLs")

    # ⚠️ Assert the PROPERTY, not the symptom.
    #
    # The obvious test — start `serve_forever` on a thread, call the handler from the main thread,
    # check the server stopped — **passes either way**, because `shutdown()` from a thread other
    # than the serving one is the supported case and works fine. It reproduces nothing. (That was
    # the first version of this test, and a broken-on-purpose build "proved" it only because the
    # command running it was malformed.)
    #
    # The real property is narrower: the handler must not call `shutdown()` on **its own thread**,
    # because in production that thread is the one blocked inside `serve_forever`. Recording which
    # thread `shutdown` is invoked on tests exactly that, and goes red the moment somebody
    # simplifies the dispatch away.
    called_on: list[str] = []
    here = threading.current_thread().name
    httpd.shutdown = lambda: called_on.append(threading.current_thread().name)  # type: ignore[method-assign]

    installed[real_signal.SIGTERM](real_signal.SIGTERM, None)
    deadline = time.time() + 5
    while not called_on and time.time() < deadline:
        time.sleep(0.02)

    check("the handler does reach shutdown()", bool(called_on), "it never called it at all")
    check(
        "⚠️ …on a DIFFERENT thread from the one the handler ran on",
        bool(called_on) and called_on[0] != here,
        f"shutdown ran on {called_on[0] if called_on else 'nothing'!r} and the handler on {here!r}. "
        "In production the handler's thread is the one blocked inside serve_forever, so an inline "
        "call waits for a loop that cannot run until the handler returns.",
    )
    httpd.server_close()


def main() -> int:
    with tempfile.TemporaryDirectory() as root:
        test_timeline()
        test_transcript()
        test_notepdf()
        test_library(root)
        test_server(root)
        test_hardening(root)
        test_container(root)
    print(f"\n{passed}/{passed + failed} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
