#!/usr/bin/env python3
"""Reading an Inkstone note straight out of its published PDF — identity, pages, handwriting.

A note in Nextcloud is an ordinary PDF. Two things are hidden inside it that make this whole
tool worth having (SPEC §5.2, §5.3, §5.6):

* an **uncompressed XMP marker** in the catalogue's `/Metadata`, carrying the `documentId`,
  the schema version and the SHA-256 of the bundle;
* the **bundle**, `note.inkstone` — a stored ZIP, `/FlateDecode`d into the file via
  `/Names /EmbeddedFiles` — holding `manifest.json` (the real title and the page order) and
  `text/<pageId>.json` (M7's recognised handwriting, with a page-space box per word).

Those give the website a real title instead of a filename, a `pageId -> PDF page` mapping so a
recording's page markers can move the PDF, and — the big one — **searchable handwriting**, which
is what makes an assistant reading this cheaper than an assistant crawling WebDAV.

## Why this does not parse the PDF object graph

It scans instead, and that is a deliberate choice rather than a shortcut.

* The marker is written **uncompressed on purpose**, so that "a tool can find the packet by
  scanning the file rather than by parsing the object graph" (`PdfBoxWriter.writeMarker`). Scanning
  for it is the sanctioned route, not a workaround.
* A published note is an **incrementally updated** PDF (SPEC §5.2) whose original bytes are a
  verbatim prefix. Its cross-reference structure is therefore the most fragile thing in the file
  and the thing a scan does not need. A correct xref/object-stream parser would be several hundred
  lines that fail on exactly the files a scan handles.
* There is an **independent check available**: the marker carries `bundleSha256`, so the bytes a
  scan finds can be *proved* to be the right ones. That makes this a reader that verifies itself
  against something the writer recorded — the habit CLAUDE.md has been repeating since M4, when
  PDFs verified with the library that wrote them turned out to be blank everywhere else.

## Everything here degrades

Every function returns None rather than raising when the file is not ours, is a stranger's PDF,
or is a shape this does not understand. A note whose bundle cannot be read still appears in the
library with its filename — losing the extras must never cost the basics.
"""

from __future__ import annotations

import hashlib
import io
import json
import re
import zipfile
import zlib
from dataclasses import dataclass, field

NAMESPACE = "https://inkstone.dev/ns/pdf/1.0/"
PREFIX = "inkstone"
BUNDLE_NAME = "note.inkstone"
MANIFEST = "manifest.json"
TEXT_DIR = "text/"

_ZIP_MAGIC = b"PK\x03\x04"

# How much of a stream to inflate before deciding it is not the bundle. A local file header is
# 30 bytes plus the name, so four is plenty to see the signature.
_SNIFF_BYTES = 4

# ⚠️ Two caps, and both are load-bearing against a hostile PDF.
#
# `MAX_BUNDLE_BYTES` bounds the **inflated stream** — the ZIP as a whole. A real bundle is a few
# kilobytes; a very large notebook with many inserted images is still megabytes, so 64 MB is
# generous by two orders of magnitude and still small enough that a file claiming to be a note
# cannot exhaust memory. It was 256 MB, which is not a guard so much as a slower crash.
MAX_BUNDLE_BYTES = 64 * 1024 * 1024

# `MAX_ENTRY_BYTES` bounds each entry read **out of** that ZIP, and it is the one that actually
# mattered: `zipfile` reports a declared size from the central directory and `read()` inflates
# without consulting it, so a 1.2 KB PDF could hold a stored-ZIP bomb whose `manifest.json`
# inflates to hundreds of megabytes. A manifest for a 500-page notebook is well under a megabyte
# and a page's recognised text is smaller again.
#
# Note this is not a "someone will attack me" story: the reader opens **every PDF in the account**,
# and a Nextcloud is a place other people's files arrive. A cap costs nothing and removes the
# question.
MAX_ENTRY_BYTES = 8 * 1024 * 1024


def _attribute(xmp: str, name: str) -> str | None:
    match = re.search(rf'{PREFIX}:{re.escape(name)}\s*=\s*"([^"]*)"', xmp)
    return match.group(1) if match else None


@dataclass(frozen=True)
class Marker:
    """SPEC §5.6's identity marker — what says this PDF is a note of ours rather than a
    stranger's Acrobat markup."""

    document_id: str
    schema_version: int
    bundle_sha256: str = ""
    original_length: int = 0
    original_sha256: str = ""


def marker(pdf: bytes) -> Marker | None:
    """The XMP marker, found by scanning. None when this is not an Inkstone note.

    ⚠️ **The namespace URI must be present**, exactly as `InkstoneXmp.parse` requires it:
    without that check a file could be claimed by any producer that happened to use the prefix
    `inkstone`. The prefix alone is not identity.
    """
    try:
        text = pdf.decode("latin-1")
    except Exception:  # noqa: BLE001
        return None
    if NAMESPACE not in text:
        return None
    document_id = _attribute(text, "documentId")
    schema = _attribute(text, "schemaVersion")
    if not document_id or schema is None:
        return None
    try:
        version = int(schema)
    except ValueError:
        return None
    return Marker(
        document_id=document_id,
        schema_version=version,
        bundle_sha256=_attribute(text, "bundleSha256") or "",
        original_length=int(_attribute(text, "originalLength") or 0),
        original_sha256=_attribute(text, "originalSha256") or "",
    )


def _stream_starts(pdf: bytes) -> list[int]:
    """Byte offsets just past every `stream` keyword and its end-of-line.

    A false positive costs one failed inflate, which is why `endstream` is never looked for: the
    inflater stops at the end of the deflate data by itself, so the closing keyword — which can
    also appear inside binary data — is not needed to find the boundary.
    """
    starts: list[int] = []
    position = 0
    while True:
        found = pdf.find(b"stream", position)
        if found < 0:
            return starts
        after = found + len(b"stream")
        # PDF 32000-1 §7.3.8.1: CRLF or LF, never a bare CR. Bare CR is tolerated anyway,
        # because a file that gets this wrong is exactly the file a scan is here to survive.
        if pdf[after : after + 2] == b"\r\n":
            after += 2
        elif pdf[after : after + 1] in (b"\n", b"\r"):
            after += 1
        starts.append(after)
        position = after


def extract_bundle(pdf: bytes, expected_sha256: str | None = None) -> bytes | None:
    """The `note.inkstone` bytes, or None.

    Streams are tried **last first**: the bundle is written by an incremental update, so in a
    published note it is near the end of the file, and a lecture-sized PDF has hundreds of
    content streams before it.

    `expected_sha256` — the marker's `bundleSha256` — turns a plausible answer into a proved one.
    When it is given and nothing matches it, this returns None rather than a ZIP that merely
    looked right, because a wrong bundle read as right is the failure that would put another
    note's handwriting under this note's title.
    """
    view = memoryview(pdf)
    # ⚠️ At most ONE candidate is kept, not every one that inflated.
    #
    # Retaining them all meant a PDF with fifty ZIP-shaped streams held fifty inflated copies at
    # once — each bounded by MAX_BUNDLE_BYTES, so the *total* was fifty times a cap that was
    # supposed to be the ceiling. Since the fallback only ever wants the first plausible bundle,
    # keeping one is not a weaker answer, and it makes the memory bound the constant it claims.
    fallback: bytes | None = None
    for start in reversed(_stream_starts(pdf)):
        chunk = view[start:]
        try:
            sniffer = zlib.decompressobj()
            head = sniffer.decompress(chunk, _SNIFF_BYTES)
        except zlib.error:
            continue
        if head[:_SNIFF_BYTES] != _ZIP_MAGIC:
            continue
        try:
            body = zlib.decompressobj().decompress(chunk, MAX_BUNDLE_BYTES)
        except zlib.error:
            continue
        if not body.startswith(_ZIP_MAGIC):
            continue
        if expected_sha256:
            if hashlib.sha256(body).hexdigest() == expected_sha256:
                return body
            continue
        if _looks_like_bundle(body):
            return body
        if fallback is None:
            fallback = body

    if expected_sha256:
        # Nothing matched the digest the writer recorded. Say nothing rather than something
        # unverified: see this function's docstring.
        return None
    return fallback if fallback is not None and _looks_like_bundle(fallback) else None


def _looks_like_bundle(body: bytes) -> bool:
    try:
        with zipfile.ZipFile(io.BytesIO(body)) as archive:
            return MANIFEST in archive.namelist()
    except Exception:  # noqa: BLE001
        return False


def _read_entry(archive: zipfile.ZipFile, name: str) -> bytes | None:
    """One entry, never more than [MAX_ENTRY_BYTES]. None when it is absent or too big.

    Reads one byte past the cap and refuses on overflow, rather than silently truncating: half a
    JSON document is not a smaller JSON document, and a caller handed one would report a note with
    a missing title rather than a note it could not read.
    """
    try:
        info = archive.getinfo(name)
    except KeyError:
        return None
    if info.file_size > MAX_ENTRY_BYTES:
        return None
    with archive.open(info) as handle:
        data = handle.read(MAX_ENTRY_BYTES + 1)
    return None if len(data) > MAX_ENTRY_BYTES else data


@dataclass(frozen=True)
class Page:
    """One page of the note, in publication order.

    `number` is the 1-based page in the **published PDF**, derived as the position of this page
    among the note's non-deleted pages sorted by `sortKey`. That is what `PdfWriter` publishes, so
    it is the mapping a viewer needs to jump the PDF to the page a recording was written on.

    ⚠️ Derived, not recorded. A note whose manifest this build does not fully understand can still
    list its pages; it just may not be able to promise the number. `number` is None in that case
    rather than a guess.
    """

    id: str
    sort_key: str
    number: int | None
    deleted: bool = False
    text: str = ""
    lines: int = 0


@dataclass
class Note:
    """What a published PDF can tell us about the note inside it."""

    document_id: str | None = None
    title: str = ""
    schema_version: int | None = None
    pages: list[Page] = field(default_factory=list)
    created_ms: int | None = None
    modified_ms: int | None = None
    bundle_verified: bool = False
    """True when the bundle's SHA-256 matched the marker's — the writer's own check, re-run."""
    bundle_present: bool = False
    imported: bool = False
    """True when this note was made from somebody else's PDF, whose bytes are the file's prefix."""

    @property
    def page_count(self) -> int:
        return len([p for p in self.pages if not p.deleted])

    def text(self) -> str:
        return "\n".join(p.text for p in self.pages if p.text)

    def page_number(self, page_id: str) -> int | None:
        for page in self.pages:
            if page.id == page_id:
                return page.number
        return None


def read(pdf: bytes, *, with_text: bool = True) -> Note | None:
    """Everything readable about the note in these PDF bytes. None if it is not one of ours.

    `with_text=False` skips the `text/` entries, which is what the library listing wants: a
    note's title and page count are two small JSON reads, its recognised handwriting is every
    page in the notebook.
    """
    found = marker(pdf)
    if found is None:
        return None
    note = Note(
        document_id=found.document_id,
        schema_version=found.schema_version,
        imported=found.original_length > 0,
    )

    body = extract_bundle(pdf, found.bundle_sha256 or None)
    if body is None and found.bundle_sha256:
        # The digest is recorded but nothing matched it. Fall back to an unverified read rather
        # than showing nothing: a stripped or re-saved PDF is a real case (SPEC §5.6's whole
        # reason for the marker), and a note listed with its title beats a note that vanished.
        body = extract_bundle(pdf, None)
    if body is None:
        return note

    note.bundle_present = True
    note.bundle_verified = bool(found.bundle_sha256) and hashlib.sha256(body).hexdigest() == found.bundle_sha256

    try:
        with zipfile.ZipFile(io.BytesIO(body)) as archive:
            names = set(archive.namelist())
            if MANIFEST not in names:
                return note
            raw = _read_entry(archive, MANIFEST)
            if raw is None:
                return note
            manifest = json.loads(raw.decode("utf-8"))
            note.title = str(manifest.get("title") or "")
            note.created_ms = _as_int(manifest.get("createdEpochMillis"))
            note.modified_ms = _as_int(manifest.get("modifiedEpochMillis"))
            note.pages = _pages(manifest)
            if with_text:
                _attach_text(archive, names, note)
    except Exception:  # noqa: BLE001 - a bundle from a newer build must not break the listing
        return note
    return note


def _as_int(value: object) -> int | None:
    try:
        return int(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return None


def _pages(manifest: dict) -> list[Page]:
    raw = manifest.get("pages")
    if not isinstance(raw, list):
        return []
    entries: list[tuple[str, str, bool]] = []
    for item in raw:
        if not isinstance(item, dict):
            continue
        page_id = item.get("id")
        if not isinstance(page_id, str):
            continue
        entries.append((page_id, str(item.get("sortKey", "")), bool(item.get("deleted", False))))

    # `sortKey` is a fractional key (SPEC §4.2) — a string compared lexicographically, which is
    # the whole point of the representation: inserting between two pages never renumbers anything.
    # Sorting by anything else here, `id` most temptingly, would put pages in creation order and
    # be right for every note nobody has ever reordered.
    live = sorted([e for e in entries if not e[2]], key=lambda e: e[1])
    numbers = {page_id: index + 1 for index, (page_id, _, _) in enumerate(live)}
    return [
        Page(id=page_id, sort_key=sort_key, number=numbers.get(page_id), deleted=deleted)
        for page_id, sort_key, deleted in sorted(entries, key=lambda e: (e[2], e[1]))
    ]


def _attach_text(archive: zipfile.ZipFile, names: set[str], note: Note) -> None:
    """Fold M7's recognised handwriting onto the pages.

    The format is `PageTextCodec`'s: `{pageId, inkDigest, recogniser, lines:[{text, box, words}]}`.
    Only the text is taken here — the per-word boxes are what a *highlighting* viewer would need,
    and nothing draws on the page yet, so carrying them would be weight with no reader.
    """
    for index, page in enumerate(note.pages):
        entry = f"{TEXT_DIR}{page.id}.json"
        if entry not in names:
            continue
        raw = _read_entry(archive, entry)
        if raw is None:
            continue
        try:
            payload = json.loads(raw.decode("utf-8"))
        except Exception:  # noqa: BLE001
            continue
        lines = payload.get("lines")
        if not isinstance(lines, list):
            continue
        texts = [str(line.get("text", "")) for line in lines if isinstance(line, dict)]
        texts = [t for t in texts if t]
        note.pages[index] = Page(
            id=page.id,
            sort_key=page.sort_key,
            number=page.number,
            deleted=page.deleted,
            text="\n".join(texts),
            lines=len(texts),
        )
