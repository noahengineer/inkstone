#!/usr/bin/env python3
"""The watcher, driven against a **real HTTP server** over a real socket.

    python tools/m12/test_watch.py

⚠️ **Not a mock of the client.** `FakeNextcloud` would have let every one of these tests pass
while the PROPFIND body, the multistatus parsing, the percent-encoding and the self-entry were all
wrong — which is exactly M4's *"verified the PDF by re-parsing it with the library that wrote it"*
and M6's *"a fixture more generous than production"*. So the production `Nextcloud` client talks
HTTP to a server that shares no code with it, and the assertions are made about the **server's own
storage** afterwards: a reader that is not the writer.

The one thing genuinely faked is AssemblyAI, because a test suite must not bill a card or ship a
classroom recording to a third party. It is faked at `transcribe.run` — the seam furthest from the
logic under test — so alignment, rendering, the PUT, the verify and the trash are all real.

Docker was not available on the machine this was written on, so the alternative was to test nothing
here at all. This is the same answer SPEC §13.2's harness reached for the same reason: hand-roll a
server rather than mock a client.
"""

import http.server
import json
import socket
import sys
import threading
import urllib.error
import urllib.parse

sys.path.insert(0, __file__.rsplit("watch", 1)[0].rstrip("test_"))

import align  # noqa: E402
import transcribe  # noqa: E402
import watch  # noqa: E402
from nextcloud import Nextcloud  # noqa: E402

UID = "inkstone"
PASSWORD = "app-password-for-tests"
DAV_PREFIX = f"/remote.php/dav/files/{UID}/"


class Store:
    """The server's own view of the world. Assertions read *this*, never the client."""

    def __init__(self):
        self.files: dict[str, bytes] = {}
        self.types: dict[str, str] = {}
        self.dirs: set[str] = {""}
        self.trash: dict[str, bytes] = {}
        self.reject_auth = False

    def add_dir(self, path: str) -> None:
        parts = path.strip("/").split("/")
        for i in range(len(parts)):
            self.dirs.add("/".join(parts[: i + 1]))

    def add_file(self, path: str, data: bytes, content_type: str = "application/octet-stream") -> None:
        # ⚠️ Guard the slash. `"Loose note.pdf".rsplit("/", 1)[0]` is the **file name**, so an
        # unguarded parent lookup registered every root-level file as a directory as well — and the
        # server then reported it as a collection, which no test with a nested fixture could see.
        if "/" in path:
            self.add_dir(path.rsplit("/", 1)[0])
        self.files[path] = data
        self.types[path] = content_type

    def children(self, path: str) -> list[str]:
        prefix = f"{path}/" if path else ""
        out = []
        for candidate in list(self.dirs) + list(self.files):
            if not candidate or not candidate.startswith(prefix):
                continue
            rest = candidate[len(prefix):]
            if rest and "/" not in rest:
                out.append(candidate)
        return sorted(set(out))


def make_handler(store: Store):
    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_args):
            pass

        def _authed(self) -> bool:
            if store.reject_auth:
                self._send(401, b"")
                return False
            return bool(self.headers.get("Authorization"))

        def _send(self, code: int, body: bytes, content_type: str = "text/plain") -> None:
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if body:
                self.wfile.write(body)

        def _path(self) -> str | None:
            raw = urllib.parse.unquote(urllib.parse.urlsplit(self.path).path)
            if not raw.startswith(DAV_PREFIX):
                return None
            return raw[len(DAV_PREFIX):].strip("/")

        def _propstat(self, path: str) -> bytes:
            is_dir = path in store.dirs
            # ⚠️ Percent-encoded, and with a space in a folder name in the fixture, because a
            # client that forgets to unquote passes every test written with tidy names.
            href = DAV_PREFIX + urllib.parse.quote(path)
            if is_dir and path:
                href += "/"
            body = f"<d:href>{href}</d:href><d:propstat><d:prop>"
            body += "<d:resourcetype><d:collection/></d:resourcetype>" if is_dir else "<d:resourcetype/>"
            if not is_dir:
                body += f"<d:getcontentlength>{len(store.files[path])}</d:getcontentlength>"
                body += f"<d:getcontenttype>{store.types.get(path, '')}</d:getcontenttype>"
            body += "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>"
            return f"<d:response>{body}</d:response>".encode()

        def do_PROPFIND(self):  # noqa: N802
            if not self._authed():
                return
            path = self._path()
            if path is None or (path not in store.dirs and path not in store.files):
                return self._send(404, b"")
            self.rfile.read(int(self.headers.get("Content-Length") or 0))

            entries = [path]
            if self.headers.get("Depth") == "1" and path in store.dirs:
                entries += store.children(path)
            xml = b'<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">'
            xml += b"".join(self._propstat(e) for e in entries)
            xml += b"</d:multistatus>"
            self._send(207, xml, "application/xml; charset=utf-8")

        def do_GET(self):  # noqa: N802
            if not self._authed():
                return
            if self.path.startswith("/ocs/"):
                payload = {"ocs": {"data": {"id": UID, "display-name": "Inkstone Test"}}}
                return self._send(200, json.dumps(payload).encode(), "application/json")
            path = self._path()
            if path is None or path not in store.files:
                return self._send(404, b"")
            self._send(200, store.files[path], store.types.get(path, "application/octet-stream"))

        def do_PUT(self):  # noqa: N802
            if not self._authed():
                return
            path = self._path()
            data = self.rfile.read(int(self.headers.get("Content-Length") or 0))
            if path is None:
                return self._send(404, b"")
            store.add_file(path, data, self.headers.get("Content-Type", ""))
            self._send(201, b"")

        def do_MKCOL(self):  # noqa: N802
            if not self._authed():
                return
            path = self._path()
            if path in store.dirs:
                return self._send(405, b"")
            store.add_dir(path)
            self._send(201, b"")

        def do_DELETE(self):  # noqa: N802
            if not self._authed():
                return
            path = self._path()
            if path is None or path not in store.files:
                return self._send(404, b"")
            # Nextcloud's DELETE is soft. Modelling that is the whole point of §8's ordering, so a
            # server that really destroyed the bytes would make the safety net untestable.
            store.trash[path] = store.files.pop(path)
            store.types.pop(path, None)
            self._send(204, b"")

    return Handler


class Server:
    def __enter__(self):
        self.store = Store()
        sock = socket.socket()
        sock.bind(("127.0.0.1", 0))
        self.port = sock.getsockname()[1]
        sock.close()
        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", self.port), make_handler(self.store))
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        return self

    def __exit__(self, *_):
        self.httpd.shutdown()

    def client(self) -> Nextcloud:
        return Nextcloud(f"http://127.0.0.1:{self.port}", UID, PASSWORD)


# --- the fake that stands in for AssemblyAI, and nothing else -------------------------------

def fake_transcript(words: list[tuple[str, int, int]], duration_s: float) -> dict:
    return {
        "audio_duration": duration_s,
        "speech_model_used": "universal",
        "words": [
            {"text": t, "start": s, "end": e, "speaker": "A", "confidence": 0.99}
            for t, s, e in words
        ],
    }


def install_fake_transcriber(payload):
    calls = []

    def run(audio: bytes, key: str, diarize: bool = True):
        calls.append(audio)
        return payload

    transcribe.run = run
    return calls


# --- fixture --------------------------------------------------------------------------------

DOC = "019fecef-0000-7000-8000-0000000000d1"
REC = "019fecef-0000-7000-8000-0000000000r1"
PAGE = "019fecef-0000-7000-8000-0000000000p1"
# A space in the folder name, so percent-encoding is exercised rather than assumed.
FOLDER = "School/Chemistry Y12"
MEDIA = f"{FOLDER}/{watch.MEDIA_DIR}/{DOC}"


def seed(store: Store, *, timeline: bool = True, recording_id: str = REC) -> None:
    store.add_file(f"{FOLDER}/Redox.pdf", b"%PDF-1.7 pretend", "application/pdf")
    store.add_file(f"{MEDIA}/{recording_id}.opus", b"OggS" + b"\0" * 200, "audio/ogg")
    if timeline:
        lines = f"019fecef-0000-7000-8000-000000000001 1200 {PAGE}\n"
        lines += f"019fecef-0000-7000-8000-000000000002 240500 {PAGE}\n"
        store.add_file(f"{MEDIA}/{recording_id}.timeline", lines.encode(), "text/plain")


# --- tests ----------------------------------------------------------------------------------

RESULTS = []


def test(name):
    def wrap(fn):
        RESULTS.append((name, fn))
        return fn
    return wrap


@test("a recording is found, transcribed, written back, and the audio trashed — in that order")
def _():
    with Server() as server:
        seed(server.store)
        # Both words after the page's first stroke at 1200 ms — the ordinary case, where you turn
        # to a page and *then* the teacher speaks. See the mid-sentence test below for the other.
        install_fake_transcriber(fake_transcript([("Redox", 1300, 1400), ("reactions", 1500, 2000)], 300.0))

        written = watch.pass_once(server.client(), "key", dry_run=False)

        assert written == 1, written
        transcript = f"{MEDIA}/{REC}.md"
        assert transcript in server.store.files, sorted(server.store.files)
        # Read the *server's* bytes, not the client's return value.
        body = server.store.files[transcript].decode("utf-8")
        assert "Redox reactions" in body, body[:400]
        assert f"documentId: {DOC}" in body
        assert server.store.types[transcript].startswith("text/markdown"), server.store.types[transcript]
        # §8: the audio is gone from the tree and recoverable from the trash.
        assert f"{MEDIA}/{REC}.opus" not in server.store.files
        assert f"{MEDIA}/{REC}.opus" in server.store.trash
        # The timeline is never touched — it is the alignment data and §8 only justified the audio.
        assert f"{MEDIA}/{REC}.timeline" in server.store.files


@test("the note's own title reaches the transcript, through a percent-encoded folder name")
def _():
    with Server() as server:
        seed(server.store)
        install_fake_transcriber(fake_transcript([("Hello", 0, 500)], 60.0))

        watch.pass_once(server.client(), "key", dry_run=False)

        body = server.store.files[f"{MEDIA}/{REC}.md"].decode("utf-8")
        assert "note: Redox" in body, body[:300]


@test("⚠️ a note in the account root, where the media directory has no parent folder")
def _():
    with Server() as server:
        media = f"{watch.MEDIA_DIR}/{DOC}"
        server.store.add_file("Loose note.pdf", b"%PDF-1.7", "application/pdf")
        server.store.add_file(f"{media}/{REC}.opus", b"OggS" + bytes(200), "audio/ogg")
        install_fake_transcriber(fake_transcript([("Rootward", 0, 500)], 60.0))

        assert watch.pass_once(server.client(), "key", dry_run=False) == 1
        body = server.store.files[f"{media}/{REC}.md"].decode("utf-8")
        assert "note: Loose note" in body, body[:300]


@test("the page column becomes a written-at span")
def _():
    with Server() as server:
        seed(server.store)
        install_fake_transcriber(fake_transcript([("Hello", 0, 500)], 300.0))

        watch.pass_once(server.client(), "key", dry_run=False)

        body = server.store.files[f"{MEDIA}/{REC}.md"].decode("utf-8")
        assert f"page `{PAGE}`" in body, body
        assert "00:00:01 to 00:04:00" in body, body


@test("a page turned mid-sentence marks the boundary where it really happened")
def _():
    """⚠️ The heading lands **between two words of one sentence**, and that is deliberate.

    The page marker is placed before the first word starting at or after the page's first stroke,
    so turning the page mid-sentence splits the sentence. It reads slightly worse and is exactly
    true; nudging it to the nearest sentence boundary would put speech on the wrong page, which is
    the one thing this file exists to get right.
    """
    with Server() as server:
        seed(server.store)
        install_fake_transcriber(fake_transcript([("Redox", 1000, 1400), ("reactions", 1500, 2000)], 300.0))

        watch.pass_once(server.client(), "key", dry_run=False)

        body = server.store.files[f"{MEDIA}/{REC}.md"].decode("utf-8")
        assert "Redox reactions" not in body, "the page boundary at 1200ms was not marked"
        redox = body.index("Redox")
        assert body.index(f"page `{PAGE}` — writing starts") > redox, body
        assert body.index("reactions") > body.index("writing starts"), body


@test("⚠️ renaming the note's PDF in the web UI does not orphan its recording")
def _():
    """ROADMAP M12: *"Rename the note's PDF in the Nextcloud web UI → its transcript is still found."*

    This holds because identity is the `documentId` **path segment**, never the neighbouring
    filename (RemoteMedia's KDoc). M6 spent a milestone learning that path-based identity does not
    survive contact with a browser, so the claim is worth an assertion rather than an argument.
    """
    with Server() as server:
        seed(server.store)
        install_fake_transcriber(fake_transcript([("Renamed", 0, 500)], 60.0))
        # What the web UI does: the PDF gets a new name, the media directory is untouched.
        server.store.files["School/Chemistry Y12/Redox and half-equations.pdf"] = (
            server.store.files.pop("School/Chemistry Y12/Redox.pdf")
        )
        server.store.types["School/Chemistry Y12/Redox and half-equations.pdf"] = "application/pdf"

        assert watch.pass_once(server.client(), "key", dry_run=False) == 1

        body = server.store.files[f"{MEDIA}/{REC}.md"].decode("utf-8")
        assert f"documentId: {DOC}" in body
        assert "note: Redox and half-equations" in body, body[:300]


@test("a second pass does nothing — the transcript is the idempotency marker")
def _():
    with Server() as server:
        seed(server.store)
        calls = install_fake_transcriber(fake_transcript([("Once", 0, 500)], 60.0))

        watch.pass_once(server.client(), "key", dry_run=False)
        again = watch.pass_once(server.client(), "key", dry_run=False)

        assert again == 0, again
        assert len(calls) == 1, f"transcribed {len(calls)} times — that is a repeated bill"


@test("⚠️ a failed transcription leaves the audio exactly where it was")
def _():
    with Server() as server:
        seed(server.store)
        install_fake_transcriber(None)  # AssemblyAI returned an error

        written = watch.pass_once(server.client(), "key", dry_run=False)

        assert written == 0
        assert f"{MEDIA}/{REC}.opus" in server.store.files, "a failed transcript cost the lecture"
        assert not server.store.trash
        assert f"{MEDIA}/{REC}.md" not in server.store.files, "a blank transcript was written"


@test("⚠️ a dry run finds the work and changes nothing")
def _():
    with Server() as server:
        seed(server.store)
        calls = install_fake_transcriber(fake_transcript([("x", 0, 1)], 60.0))

        assert watch.discover(server.client()), "found no work to report"
        watch.pass_once(server.client(), "", dry_run=True)

        assert not calls, "a dry run sent audio to a paid API"
        assert f"{MEDIA}/{REC}.opus" in server.store.files
        assert f"{MEDIA}/{REC}.md" not in server.store.files


@test("a lecture nobody wrote in still transcribes")
def _():
    with Server() as server:
        seed(server.store, timeline=False)
        install_fake_transcriber(fake_transcript([("Listening", 0, 900)], 120.0))

        assert watch.pass_once(server.client(), "key", dry_run=False) == 1
        body = server.store.files[f"{MEDIA}/{REC}.md"].decode("utf-8")
        assert "Listening" in body
        assert "not anchored" in body, "an empty timeline should say so rather than pretend"


@test("⚠️ two recordings in one note both get transcribed — the M12 coalescing trap, server-side")
def _():
    with Server() as server:
        second = "019fecef-0000-7000-8000-0000000000r2"
        seed(server.store)
        seed(server.store, recording_id=second)
        install_fake_transcriber(fake_transcript([("Both", 0, 400)], 60.0))

        assert watch.pass_once(server.client(), "key", dry_run=False) == 2
        assert f"{MEDIA}/{REC}.md" in server.store.files
        assert f"{MEDIA}/{second}.md" in server.store.files


@test("⚠️ a 401 halts the pass rather than retrying — CLAUDE.md rule 5")
def _():
    with Server() as server:
        seed(server.store)
        install_fake_transcriber(fake_transcript([("x", 0, 1)], 60.0))
        client = server.client()
        recordings = watch.discover(client)
        server.store.reject_auth = True

        try:
            watch.process(client, recordings[0], "key", dry_run=False)
            raise AssertionError("a 401 was swallowed")
        except urllib.error.HTTPError as error:
            assert error.code == 401, error.code


@test("the account root is walked once, not rediscovered as its own child")
def _():
    with Server() as server:
        seed(server.store)
        client = server.client()
        calls = []
        real = client.listing
        client.listing = lambda p, depth="1": (calls.append(p), real(p, depth))[1]

        watch.discover(client)

        assert len(calls) == len(set(calls)), f"listed a path twice: {calls}"
        assert "" in calls and FOLDER in calls, calls


@test("⚠️ a media folder deeper than the bound is skipped, and the walk terminates")
def _():
    with Server() as server:
        deep = "a/b/c/d/e"
        server.store.add_file(f"{deep}/{watch.MEDIA_DIR}/{DOC}/{REC}.opus", b"OggS", "audio/ogg")
        assert watch.discover(server.client()) == []


def main() -> int:
    # Windows' console is cp1252 and cannot print the ⚠️ in the test names above.
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8", errors="replace")

    failed = 0
    for name, fn in RESULTS:
        try:
            fn()
            print(f"  PASS  {name}")
        except Exception as error:  # noqa: BLE001
            failed += 1
            print(f"  FAIL  {name}\n        {type(error).__name__}: {error}")
    print(f"\n{len(RESULTS) - failed}/{len(RESULTS)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
