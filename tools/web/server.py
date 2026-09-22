#!/usr/bin/env python3
"""The website: a read-only reader for the notes and lectures in your Nextcloud.

    py tools/web/server.py --open                  # http://127.0.0.1:8765
    py tools/web/server.py --mirror ./synced       # a local folder instead of the live account

⚠️ **`py`, not `python`.** With Python installed from python.org and the Microsoft Store aliases
still enabled — the default — `python` and `python3` resolve to WindowsApps stubs that fail with
*"The system cannot find the path specified"*, while `py` works. `run-reader.cmd` picks a working
one for you; it exists because Windows also defaults the execution policy to Restricted, so the
`.ps1` launcher beside it will not run at all on an untouched machine.

Stdlib only, like everything else under `tools/`. `ThreadingHTTPServer` because a single-threaded
one deadlocks the moment the page asks for a PDF and the scan at the same time — `/media/note`
holds the only thread for as long as the download takes, and the UI simply stops. (This used to
say *audio*; the reason survived the audio, because a lecture note is megabytes too.)

## It binds to localhost, and that is a decision rather than a default

The process holds a Nextcloud **app password** and will hand out class recordings to anything that
can reach it. On `127.0.0.1` the audience is programs already running as this user, which is the
same audience that can read `watcher.local.properties` anyway, so a secret would protect nothing.

Binding anywhere else changes that completely, so `--host` refuses to start without both a secret
(`INKSTONE_WEB_PASSWORD` or `INKSTONE_MCP_TOKEN`) and `INKSTONE_ALLOWED_HOSTS`. `auth.py` is the
gate and `docs/WEB-READER.md` §9 is the hosted setup. Terminate TLS in front of this; it speaks
plain HTTP by design.

## Nothing here writes

There is no PUT, no DELETE and no MKCOL in this package (see `source.py`). Every route below is a
GET, apart from the two POSTs that only refresh caches. A reader that cannot mutate cannot lose
anything, which is CLAUDE.md rule 1 satisfied by construction rather than by care.
"""

from __future__ import annotations

import argparse
import http.server
import json
import mimetypes
import os
import posixpath
import re
import secrets
import socketserver
import sys
import threading
import urllib.parse
import webbrowser

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import auth  # noqa: E402
import cachekey  # noqa: E402
import config  # noqa: E402
import connect  # noqa: E402
import mcp_http  # noqa: E402
import mcp_server  # noqa: E402
from library import Library  # noqa: E402
from source import MirrorSource, NeedsReauth, NextcloudSource, Source, SourceError  # noqa: E402

STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")
DEFAULT_PORT = 8765

_RANGE = re.compile(r"^bytes=(\d*)-(\d*)$")


class MediaCache:
    """Whole files, on disk, keyed by path and ETag.

    Range requests are why this exists. A browser's built-in PDF viewer fetches a document in
    pieces rather than whole, and `nextcloud.py.get` reads a whole body into memory each time — so
    every range request would re-download the entire note without this.

    ⭐ It used to serve lecture audio too. It does not: `tools/m12` trashes a recording once its
    transcript is verified, so there is nothing to stream in the steady state.
    """

    def __init__(self, root: str) -> None:
        self.root = root
        os.makedirs(root, exist_ok=True)
        self._locks: dict[str, threading.Lock] = {}
        self._guard = threading.Lock()

    def _lock_for(self, key: str) -> threading.Lock:
        with self._guard:
            return self._locks.setdefault(key, threading.Lock())

    def path_for(self, source: Source, remote: str, etag: str) -> str:
        # ⛔ This key used to be `sha256(f"{source.label}|{remote}|{etag}")`, and it was wrong in
        # two ways at once. The joined string is not injective — a `|` in a filename shifts the
        # boundary — and `source.label` names the **server**, not the account, so two people on one
        # school Nextcloud with the same relative path and the same file size collided by
        # construction. In multi-user mode each account has its own cache directory as well; this
        # is what makes the key itself sound.
        key = cachekey.key("media", source.label, remote, etag)
        local = os.path.join(self.root, key)
        if os.path.exists(local):
            return local
        with self._lock_for(key):
            if os.path.exists(local):
                return local
            # ⚠️ Re-created on every write, not just in `__init__`. A cache directory that existed
            # at startup can be gone by the time somebody scrubs a lecture — disk cleanup, a sync
            # client, or a person emptying it by hand — and without this the whole media route
            # answers 500 with a `FileNotFoundError` about a `.tmp` path, which reads as a broken
            # server rather than a missing directory. A cache whose failure mode is "do the work
            # again" should never be able to fail permanently.
            os.makedirs(self.root, exist_ok=True)
            data = source.read(remote)
            temporary = f"{local}.tmp"
            with open(temporary, "wb") as handle:
                handle.write(data)
            os.replace(temporary, local)
        return local


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "InkstoneReader/1.0"
    protocol_version = "HTTP/1.1"

    library: Library
    media: MediaCache
    guard: auth.Guard
    connector: object = None
    """`connect.Connector` — the in-page Nextcloud sign-in. None in --mirror mode, where there is
    no Nextcloud to sign in to."""
    protocol: object = None
    """`mcp_server.Protocol`, shared by every request. Stateless — see `mcp_http`'s docstring."""

    # Reachable without signing in. The stylesheet and the script are not secret and the sign-in
    # page needs them — gating them would render the login form unstyled and inert. `/healthz` is
    # here for the container's own probe, which has no credentials and never will; see `_healthz`
    # for what it deliberately does NOT return.
    OPEN_PATHS = ("/signin", "/healthz", "/static/app.css", "/static/app.js", "/static/signin.html")

    # -- a client that goes away is not an error --------------------------
    #
    # ⚠️ There are THREE places a disconnect surfaces, and guarding only the obvious one leaves the
    # traceback looking fixed while it moves:
    #
    #   1. mid-write, streaming a file        -> handled in `_serve_file`
    #   2. writing the reply or an error      -> handled in `_dispatch`
    #   3. **reading the next request** on a kept-alive connection the client already dropped
    #      -> raised inside `http.server.handle_one_request`, before any code of ours runs, which
    #         is why the first fix appeared not to work.
    #
    # `handle` and `finish` cover (3) and the final flush. A browser reassigning an iframe's `src`,
    # closing a tab, or abandoning a half-fetched PDF produces all of these routinely.

    def handle(self) -> None:
        try:
            super().handle()
        except ConnectionError:
            self.close_connection = True

    def finish(self) -> None:
        try:
            super().finish()
        except ConnectionError:
            pass

    # -- plumbing ---------------------------------------------------------

    def log_message(self, fmt: str, *args) -> None:  # noqa: A002
        if self.server.verbose:  # type: ignore[attr-defined]
            sys.stderr.write(f"{self.address_string()} {fmt % args}\n")

    def _json(self, payload, status: int = 200, extra: dict | None = None) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        # This page reads a private Nextcloud. Keeping it out of shared caches and out of
        # other origins costs nothing and removes a whole class of accident.
        self.send_header("Cache-Control", "no-store")
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def _error(self, message: str, status: int = 400, kind: str = "error") -> None:
        self._json({"error": message, "kind": kind}, status)

    def _redirect(self, location: str, extra: dict | None = None) -> None:
        self.send_response(303)
        self.send_header("Location", location)
        self.send_header("Content-Length", "0")
        self.send_header("Cache-Control", "no-store")
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()

    def _body(self) -> bytes:
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            return b""
        return self.rfile.read(min(length, mcp_http.MAX_BODY_BYTES)) if length > 0 else b""

    # -- the gate ---------------------------------------------------------

    def _gate(self, route: str) -> bool:
        """Decide whether this request may proceed, answering it itself when not.

        ⚠️ An unauthenticated **browser** gets the sign-in page, not a 401 JSON blob. A person who
        opens a bookmark should see somewhere to type a passphrase; only a program should see a
        status code. `Accept` is what tells them apart.
        """
        if route in self.OPEN_PATHS:
            # Still Host- and Origin-checked: those are about who is asking, not about who they are.
            decision = self.guard.check(headers=self.headers, address=self.client_address[0], for_mcp=False)
            if not decision.ok and decision.status == 403:
                self._error(decision.reason, 403, "refused")
                return False
            return True

        for_mcp = route == mcp_http.ENDPOINT
        decision = self.guard.check(
            headers=self.headers, address=self.client_address[0], for_mcp=for_mcp
        )
        if decision.ok:
            return True

        wants_html = "text/html" in (self.headers.get("Accept") or "") and not for_mcp
        if wants_html and decision.status == 401:
            self._redirect(f"/signin?next={urllib.parse.quote(self.path, safe='')}")
            return False
        extra = {"WWW-Authenticate": decision.challenge} if decision.challenge else None
        self._json({"error": decision.reason, "kind": "unauthorised"}, decision.status, extra)
        return False

    # -- signing in -------------------------------------------------------

    def _signin_page(self, query: dict) -> None:
        if not self.guard.web_password:
            self._error(
                "No web password is set on this server, so there is nothing to sign in to. "
                "Set INKSTONE_WEB_PASSWORD, or reach it over loopback.",
                404,
            )
            return
        self._static("signin.html")

    def _signin(self) -> None:
        raw = self._body().decode("utf-8", errors="replace")
        form = urllib.parse.parse_qs(raw)
        password = (form.get("password") or [""])[0]
        # ⚠️ Only ever a path on this site. An absolute URL would make this an open redirect —
        # somewhere to send somebody immediately after they proved they trust this site.
        target = (form.get("next") or ["/"])[0]
        if not target.startswith("/") or target.startswith("//"):
            target = "/"

        client = self.guard.client_of(self.client_address[0], self.headers)
        ok, session, wait = self.guard.attempt(password, client)
        if not ok:
            if wait > 0:
                self._redirect(f"/signin?error=rate&wait={int(wait) + 1}")
            else:
                self._redirect(f"/signin?error=bad&next={urllib.parse.quote(target, safe='')}")
            return
        self._redirect(target, {"Set-Cookie": self.guard.cookie_header(session)})

    def _signout(self) -> None:
        self.guard.sign_out(self.headers)
        self._redirect("/signin", {"Set-Cookie": self.guard.cookie_header("", clear=True)})

    # -- MCP over HTTP ----------------------------------------------------

    def _mcp(self, method: str) -> None:
        if method == "GET":
            self._send_mcp(mcp_http.handle_get())
        elif method == "DELETE":
            self._send_mcp(mcp_http.handle_delete())
        else:
            self._send_mcp(mcp_http.handle_post(self.protocol, self._body(), self.headers))

    def _send_mcp(self, response) -> None:  # noqa: ANN001
        self.send_response(response.status)
        if response.content_type:
            self.send_header("Content-Type", response.content_type)
        self.send_header("Content-Length", str(len(response.body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if response.body:
            self.wfile.write(response.body)

    # -- dispatch ---------------------------------------------------------

    def do_GET(self) -> None:  # noqa: N802
        self._dispatch("GET")

    def do_POST(self) -> None:  # noqa: N802
        self._dispatch("POST")

    def do_DELETE(self) -> None:  # noqa: N802
        self._dispatch("DELETE")

    def _dispatch(self, method: str) -> None:  # noqa: C901
        parsed = urllib.parse.urlsplit(self.path)
        route = parsed.path
        query = urllib.parse.parse_qs(parsed.query)

        if not self._gate(route):
            return

        try:
            if route == mcp_http.ENDPOINT:
                self._mcp(method)
            elif route == "/signin":
                self._signin() if method == "POST" else self._signin_page(query)
            elif route == "/signout":
                self._signout()
            elif route == "/" or route == "/index.html":
                self._static("index.html")
            elif route.startswith("/static/"):
                self._static(route[len("/static/") :])
            elif route == "/healthz":
                self._healthz()
            elif route == "/api/nextcloud/begin":
                self._nextcloud_begin()
            elif route == "/api/nextcloud/poll":
                self._nextcloud_poll(query)
            elif route == "/api/health":
                self._health()
            elif route == "/api/scan":
                self._scan(query)
            elif route == "/api/recording":
                self._recording(query)
            elif route == "/api/note":
                self._note(query)
            elif route == "/api/search":
                self._search(query)
            elif route == "/api/index":
                self._index()
            elif route == "/media/note":
                self._note_pdf(query)
            elif route == "/media/transcript":
                self._transcript_raw(query)
            else:
                self._error("No such route.", 404, "not-found")
        except NeedsReauth as error:
            # ⚠️ 401 is terminal here, exactly as CLAUDE.md rule 5 requires. The page shows the
            # advice and stops; nothing anywhere retries the credential.
            self._error(str(error), 401, "reauth")
        except SourceError as error:
            self._error(str(error), 502, "source")
        except ConnectionError:
            # ⚠️ `ConnectionError`, not `BrokenPipeError`. They are SIBLINGS, not parent and child.
            #
            # POSIX gives EPIPE -> `BrokenPipeError` when the client goes away mid-write. Windows
            # gives WSAECONNABORTED (10053) -> `ConnectionAbortedError`, or 10054 ->
            # `ConnectionResetError`. Catching only the first meant a perfectly ordinary cancelled
            # download on Windows fell through to the generic handler below, which then tried to
            # write a 500 to the socket that had just closed — raising a SECOND time, escaping to
            # `socketserver`, and printing forty lines of traceback for a browser doing something
            # completely normal.
            #
            # A client cancelling is not an error. An iframe whose `src` is reassigned, a PDF
            # viewer that has the range it asked for, a closed tab: all of them land here.
            pass
        except Exception as error:  # noqa: BLE001
            # ⚠️ Reporting a failure must not be able to fail. If the client has gone, writing the
            # 500 raises too — and *that* exception escapes the handler entirely and is printed by
            # `socketserver` with both tracebacks stacked. The reply is best-effort; the log line
            # is what matters once nobody is listening.
            try:
                self._error(f"{type(error).__name__}: {error}", 500, "internal")
            except ConnectionError:
                pass

    # -- routes -----------------------------------------------------------

    def _static(self, relative: str) -> None:
        safe = posixpath.normpath("/" + relative).lstrip("/")
        target = os.path.join(STATIC_DIR, *safe.split("/"))
        if not os.path.isfile(target) or os.path.commonpath([STATIC_DIR, os.path.abspath(target)]) != STATIC_DIR:
            self._error("No such file.", 404, "not-found")
            return
        kind, _ = mimetypes.guess_type(target)
        with open(target, "rb") as handle:
            body = handle.read()
        self.send_response(200)
        self.send_header("Content-Type", kind or "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _nextcloud_begin(self) -> None:
        """Start the browser sign-in. The page opens the URL this returns."""
        if self.connector is None:
            self._error("This reader is showing a local folder, so there is nothing to sign in to.", 400)
            return
        try:
            payload = json.loads(self._body() or b"{}")
        except json.JSONDecodeError:
            self._error("Expected a JSON body with a url.", 400)
            return
        url = str(payload.get("url") or "").strip()
        if not url:
            self._error("Enter your Nextcloud address.", 400)
            return
        import login as login_mod  # noqa: PLC0415

        try:
            flow = self.connector.begin(url)
        except login_mod.LoginError as error:
            self._error(str(error), 400, "login")
            return
        self._json({"flowId": flow.flow_id, "browserUrl": flow.browser_url})

    def _nextcloud_poll(self, query: dict) -> None:
        if self.connector is None:
            self._error("Nothing to sign in to.", 400)
            return
        self._json(self.connector.poll(query.get("flowId", [""])[0]))

    def _healthz(self) -> None:
        """Liveness and whether the source is reachable. Unauthenticated, and minimal on purpose.

        ⚠️ `/api/health` cannot be the container's probe, for two reasons that each make the
        healthcheck useless on their own. It is behind the auth gate, so a probe with no
        credentials gets a 401 — and a probe that treats 401 as "fine" reports healthy no matter
        what the app is doing, which is a healthcheck that has been switched off without anybody
        deciding to. It also returns the account's uid and display name, which is more than an
        unauthenticated caller should learn.

        So this says one thing — can the reader still reach Nextcloud — and names nothing.
        """
        payload = {"ok": True}
        source = self.library.source
        if isinstance(source, NextcloudSource):
            try:
                source.whoami()
            except NeedsReauth:
                # ⚠️ Reported as alive-but-degraded, NOT unhealthy. An unhealthy container gets
                # restarted, and a restart on a dead credential means re-authenticating on every
                # restart — CLAUDE.md rule 5's brute-force lockout, rebuilt out of a restart
                # policy. A revoked password needs a person, so the container stays up and says so.
                payload = {"ok": False, "kind": "reauth"}
            except SourceError:
                payload = {"ok": False, "kind": "source"}
        self._json(payload, 200 if payload["ok"] else 503)

    def _health(self) -> None:
        payload = {"source": self.library.source.label, "ok": True}
        # ⚠️ TLS interception is reported to the page rather than only to a log. Somebody whose
        # school firewall is reading their schoolwork should be told once, in plain words.
        import tlsdiag  # noqa: PLC0415

        notices = tlsdiag.warnings()
        if notices:
            payload["tlsNotices"] = notices
        source = self.library.source
        if isinstance(source, NextcloudSource):
            try:
                who = source.whoami()
                payload["uid"] = who.get("id")
                payload["displayName"] = who.get("display-name")
            except NeedsReauth as error:
                payload.update({"ok": False, "kind": "reauth", "error": str(error)})
            except SourceError as error:
                payload.update({"ok": False, "kind": "source", "error": str(error)})
        self._json(payload)

    def _scan(self, query: dict) -> None:
        force = query.get("force", ["0"])[0] in ("1", "true")
        scan = self.library.scan(force=force)
        self._json(
            {
                "source": scan.source,
                "walked": scan.walked,
                "tookMs": scan.took_ms,
                "error": scan.error,
                "folders": scan.folders,
                "notes": [
                    {
                        "path": n.path,
                        "name": n.name,
                        "folder": n.folder,
                        "size": n.size,
                        "title": n.display_title,
                        "documentId": n.document_id,
                        "pages": n.pages,
                        "isInkstone": n.is_inkstone,
                    }
                    for n in scan.notes
                ],
                "recordings": [
                    {
                        "documentId": r.document_id,
                        "recordingId": r.recording_id,
                        "folder": r.folder,
                        "noteTitle": r.note_title,
                        "notePath": r.note_path,
                        "state": r.state,
                        "hasTimeline": r.has_timeline,
                        "hasTranscript": r.has_transcript,
                        # ⭐ `durationMs` is measured — `align` read it off the Ogg granule and
                        # wrote it into the transcript's front matter. `durationEstimated` says
                        # when it is not: an untranscribed recording still falls back to
                        # bytes ÷ bitrate, and a bitrate `MediaRecorder` treats as a hint was 42%
                        # out when M12 measured it. `hasAudio`/`audioBytes` used to be here and
                        # nothing read them; there is no audio route for a browser to want them
                        # for.
                        "durationMs": r.duration_ms,
                        "durationEstimated": r.duration_ms <= 0,
                        "estimatedMinutes": round(r.estimated_minutes, 1),
                    }
                    for r in scan.recordings
                ],
            }
        )

    def _recording(self, query: dict) -> None:
        document_id = query.get("documentId", [""])[0]
        recording_id = query.get("recordingId", [""])[0]
        if not document_id or not recording_id:
            self._error("documentId and recordingId are required.")
            return
        detail = self.library.recording_detail(document_id, recording_id)
        if detail is None:
            self._error("No such recording.", 404, "not-found")
            return
        self._json(detail)

    def _note(self, query: dict) -> None:
        path = query.get("path", [""])[0]
        if not path:
            self._error("path is required.")
            return
        detail = self.library.note_detail(path)
        if detail is None:
            self._error("No such note.", 404, "not-found")
            return
        self._json(detail)

    def _search(self, query: dict) -> None:
        needle = query.get("q", [""])[0].strip()
        if not needle:
            self._error("q is required.")
            return
        limit = min(int(query.get("limit", ["50"])[0] or 50), 500)
        self._json(self.library.search(needle, limit=limit))

    def _index(self) -> None:
        self._json(self.library.index_notes())

    # ⭐ There is no `_audio` handler, deliberately.
    #
    # `tools/m12/watch.py:206` trashes a recording once its transcript is written and
    # verified present on the server, so a lesson in the steady state has no audio — and a
    # route that served one existed for the gap between recording and transcription only.
    #
    # Removing it also removes the thing this server was least comfortable doing: handing
    # out recordings of a room full of identifiable people over HTTP. The transcript is the
    # artifact (M12 §1); the audio is an intermediate that the watcher consumes.

    def _note_pdf(self, query: dict) -> None:
        path = query.get("path", [""])[0]
        if not path.lower().endswith(".pdf"):
            self._error("path must name a .pdf", 400)
            return
        # `entry_for`, not `_entry_for`: the public one enforces `--root` and refuses `..`.
        entry = self.library.entry_for(path)
        if entry is None:
            self._error("No such note, or it is outside --root.", 404, "not-found")
            return
        local = self.media.path_for(self.library.source, path, entry.etag or str(entry.size))
        self._serve_file(local, "application/pdf")

    def _transcript_raw(self, query: dict) -> None:
        document_id = query.get("documentId", [""])[0]
        recording_id = query.get("recordingId", [""])[0]
        scan = self.library.scan()
        record = next(
            (r for r in scan.recordings if r.document_id == document_id and r.recording_id == recording_id),
            None,
        )
        if record is None or not record.has_transcript:
            self._error("No transcript for that recording.", 404, "not-found")
            return
        body = self.library.source.read(record.transcript_path)
        self.send_response(200)
        self.send_header("Content-Type", "text/markdown; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    # -- byte ranges ------------------------------------------------------

    def _serve_file(self, local: str, content_type: str) -> None:
        """Serve a file, honouring `Range`.

        ⚠️ **Ranges are still needed, and this docstring used to say they were for the audio.**
        There is no audio route any more, and a reader of that sentence would reasonably delete
        the ranges with it — so: the remaining consumer is `/media/note`, and a browser's built-in
        PDF viewer fetches a document in pieces rather than whole. Without `Accept-Ranges` it
        downloads every page of a lecture note before showing the first one, and some viewers
        refuse the document outright.
        """
        size = os.path.getsize(local)
        start, end = 0, size - 1
        status = 200
        requested = self.headers.get("Range")
        if requested:
            match = _RANGE.match(requested.strip())
            if match:
                first, last = match.group(1), match.group(2)
                if first:
                    start = int(first)
                    end = int(last) if last else size - 1
                elif last:
                    # `bytes=-500` means the *last* 500 bytes, not "up to byte 500". Getting this
                    # backwards serves the wrong half of the file and looks like corruption.
                    start = max(0, size - int(last))
                    end = size - 1
                if start >= size:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                end = min(end, size - 1)
                status = 206

        # ⚠️ A range whose end precedes its start is unsatisfiable, and answering it arithmetically
        # sends `Content-Length: -49`. The client then waits for a body that will never come and
        # the connection hangs — on a keep-alive connection that is the tab, not the request.
        # RFC 9110: an unsatisfiable range is a 416.
        if end < start:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{size}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        length = end - start + 1
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        with open(local, "rb") as handle:
            handle.seek(start)
            remaining = length
            while remaining > 0:
                chunk = handle.read(min(64 * 1024, remaining))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except ConnectionError:
                    # The client stopped listening part-way through — an iframe reassigned, a tab
                    # closed, a PDF viewer that has the pages it wanted. Stop writing and let the
                    # connection close; there is nothing to report and nobody to report it to.
                    return
                remaining -= len(chunk)


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    verbose = False

    def handle_error(self, request, client_address) -> None:  # noqa: ANN001
        """The last resort, so a dropped connection can never print forty lines.

        `socketserver`'s own implementation prints a full traceback for anything that escapes a
        handler. A client disconnecting is the single most common thing that does, and it is not a
        fault — so it gets one quiet line under `--verbose` and nothing otherwise. Everything else
        keeps the traceback, because everything else is a real bug and hiding it would be worse
        than the noise this is removing.
        """
        kind = sys.exc_info()[0]
        if kind is not None and issubclass(kind, ConnectionError):
            if self.verbose:
                sys.stderr.write(f"  {client_address[0]} disconnected mid-request\n")
            return
        super().handle_error(request, client_address)


def build_source(args) -> Source:  # noqa: ANN001
    if args.mirror:
        return MirrorSource(args.mirror)
    return NextcloudSource(config.load(args.config))


def normalise_flags(argv: list[str]) -> list[str]:
    """Accept PowerShell's `-Open` as well as argparse's `--open`.

    This repo's launchers are `.ps1` files, so their documented flags are `-Open`, `-Mirror`,
    `-Port` — and that is what a person types from muscle memory when they run the Python
    directly or through `run-reader.cmd`. argparse sees `-Open` as the bundled short flags
    `-O -p -e -n` and fails with something unhelpful about `-O`.

    Converting a single-dash **multi-character** token to its double-dash form makes both
    spellings work. Single-character flags (`-h`) are left alone, and so is anything that is not
    purely alphabetic, so a negative number or a bare `-` passes through untouched.
    """
    out: list[str] = []
    for token in argv:
        if (
            len(token) > 2
            and token[0] == "-"
            and token[1] != "-"
            and token[1:].replace("-", "").isalpha()
        ):
            out.append(f"--{token[1:].lower()}")
        else:
            out.append(token)
    return out


def build_parser() -> argparse.ArgumentParser:
    """The argument parser, separate from `main` so a test can inspect it without running a server."""
    parser = argparse.ArgumentParser(description="A local, read-only reader for Inkstone notes and lectures.")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument(
        # `--bindhost` is an alias because `run-reader.ps1` has to spell this `-BindHost`:
        # `$Host` is a PowerShell automatic variable and cannot be a parameter name. Somebody who
        # learned the flag from the launcher should not have to discover it is called something
        # else here.
        "--host",
        "--bindhost",
        default="127.0.0.1",
        dest="host",
        help="Bind address. A non-loopback address needs INKSTONE_ALLOWED_HOSTS and a secret set.",
    )
    parser.add_argument("--mirror", default=None, help="Read a local folder instead of the live Nextcloud.")
    parser.add_argument("--config", default=None, help="Path to watcher.local.properties.")
    parser.add_argument("--cache", default=None, help="Where to keep the cache. Defaults beside this file.")
    parser.add_argument("--root", default="", help="Only walk below this folder.")
    parser.add_argument("--open", action="store_true", help="Open a browser once the server is up.")
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument(
        "--login",
        action="store_true",
        help="Sign in to Nextcloud in your browser and store the credential, then exit. "
             "You are never asked for a password here.",
    )
    parser.add_argument("--url", default=None, help="With --login: the Nextcloud address, so it is not prompted for.")
    parser.add_argument(
        "--secrets-dir",
        default=None,
        help="With --login: also write secrets/nc_password, for the container in compose.yaml.",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    argv = normalise_flags(sys.argv[1:] if argv is None else argv)
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.login:
        return run_login(args)

    loopback = args.host in ("127.0.0.1", "::1", "localhost")
    guard = auth.Guard.from_env(loopback=loopback)

    # ⛔ The old `--token` flag is gone. It put a secret in a query string, which lands in browser
    # history, in `Referer`, and in the reverse proxy's access log — acceptable for a loopback dev
    # switch and not for the thing standing between the internet and a term of class recordings.
    # `auth.Guard` replaces it with a session cookie for people and a bearer token for MCP clients,
    # both from the environment so neither is ever on a command line (where `ps` can read it).
    if not loopback and not guard.enforcing:
        parser.error(
            "Refusing to bind to a non-loopback address with no authentication.\n\n"
            "This process holds a Nextcloud app password and serves class recordings to anything\n"
            "that can reach it. Set at least one of:\n\n"
            "    INKSTONE_WEB_PASSWORD   a passphrase, for the website\n"
            "    INKSTONE_MCP_TOKEN      a bearer token, for Claude over HTTP\n\n"
            "and INKSTONE_ALLOWED_HOSTS to the name it is reached by. See docs/WEB-READER.md."
        )
    if not loopback and not guard.allowed_hosts:
        parser.error(
            "Refusing to bind to a non-loopback address without INKSTONE_ALLOWED_HOSTS.\n\n"
            "Without it every request is checked against 'localhost', so nothing would work -\n"
            "and the check is what stops a hostile page reaching this server through your own\n"
            "browser. Set it to the name(s) this is served as, comma separated."
        )

    try:
        source = build_source(args)
    except config.ConfigError as error:
        sys.stderr.write(f"\n{error}\n\n")
        return 2
    except SourceError as error:
        sys.stderr.write(f"\n{error}\n\n")
        return 2

    cache_root = args.cache or os.path.join(os.path.dirname(os.path.abspath(__file__)), ".cache")
    library = Library(source, os.path.join(cache_root, "meta"), root=args.root)
    media = MediaCache(os.path.join(cache_root, "media"))

    Handler.library = library
    Handler.media = media
    Handler.guard = guard
    Handler.protocol = mcp_server.Protocol(mcp_server.Tools(library))

    if not args.mirror:
        def persist(credential) -> None:  # noqa: ANN001
            values = {
                config.KEY_URL: credential.server_url,
                config.KEY_PASSWORD: credential.app_password,
            }
            if credential.uid:
                values[config.KEY_UID] = credential.uid
            if credential.login_name and credential.login_name != credential.uid:
                values[config.KEY_LOGIN] = credential.login_name
            config.save(values, args.config)

        def rebuild(credential) -> None:  # noqa: ANN001
            """⚠️ Rebuild the source, not just the credential file.

            `NextcloudSource` latches shut on its first 401 so a re-scanning page cannot become a
            brute-force loop (rule 5). That latch is per-object, so writing a working password and
            carrying on would leave the running source still refusing — the sign-in would succeed
            and the page would go on showing the same error. Everything downstream of the source
            is rebuilt with it.
            """
            fresh = NextcloudSource(
                config.Credentials(
                    url=credential.server_url,
                    uid=credential.uid or credential.login_name,
                    password=credential.app_password,
                    login=credential.login_name or None,
                )
            )
            rebuilt = Library(fresh, os.path.join(cache_root, "meta"), root=args.root)
            Handler.library = rebuilt
            Handler.protocol = mcp_server.Protocol(mcp_server.Tools(rebuilt))

        Handler.connector = connect.Connector(persist, rebuild)

    server = Server((args.host, args.port), Handler)
    server.verbose = args.verbose
    url = f"http://{'127.0.0.1' if loopback else args.host}:{args.port}/"

    # ⚠️ ASCII only in anything written to a console. Windows PowerShell decodes this stream as
    # the system ANSI codepage, so an em dash arrives as a replacement character — the same class
    # of bug `run-watcher.ps1` carries a warning about for its own source. Comments may be UTF-8;
    # the bytes that reach a terminal may not.
    sys.stderr.write(f"\n  Inkstone reader - {source.label}\n")
    sys.stderr.write(f"  {url}\n")
    sys.stderr.write(f"  MCP endpoint: {url.rstrip('/')}{mcp_http.ENDPOINT}\n")
    sys.stderr.write(f"  Auth: {guard.describe()}\n")
    if not loopback:
        sys.stderr.write("  Reachable off this machine. Terminate TLS in front of it.\n")
    sys.stderr.write("  Read-only: this process never writes to Nextcloud. Ctrl-C to stop.\n\n")

    if args.open:
        threading.Timer(0.5, lambda: webbrowser.open(url)).start()

    _install_sigterm(server)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        sys.stderr.write("\n  stopped\n")
    return 0


def run_login(args) -> int:  # noqa: ANN001
    """`--login`: the browser sign-in, then write the credential where both tools read it."""
    import login  # noqa: PLC0415

    url = args.url
    if not url:
        try:
            url = input("  Your Nextcloud address (e.g. cloud.example.com): ").strip()
        except (EOFError, KeyboardInterrupt):
            sys.stderr.write("\n  cancelled\n")
            return 1
    if not url:
        sys.stderr.write("\n  No address given.\n")
        return 2

    written: list[str] = []

    def persist(credential) -> None:  # noqa: ANN001
        # ⚠️ Rule 3: this runs the instant the app password arrives, BEFORE the uid is known and
        # before `run` returns. The server deletes its copy when it answers, so a crash between
        # the response and this write leaves a credential that exists nowhere.
        values = {
            config.KEY_URL: credential.server_url,
            config.KEY_PASSWORD: credential.app_password,
        }
        # The uid is the WebDAV path segment and is empty on the first of the two calls; writing
        # an empty one would look like a configured account that cannot resolve a single path.
        if credential.uid:
            values[config.KEY_UID] = credential.uid
        if credential.login_name and credential.login_name != credential.uid:
            values[config.KEY_LOGIN] = credential.login_name
        written.append(config.save(values, args.config))

    try:
        credential = login.run(url, persist)
    except login.LoginError as error:
        sys.stderr.write(f"\n  {error}\n\n")
        return 2
    except KeyboardInterrupt:
        sys.stderr.write("\n  cancelled\n")
        return 1

    sys.stderr.write(f"  Saved to {written[-1] if written else '(nothing written)'}\n")

    if args.secrets_dir:
        # For the container: the password belongs in a file of its own, not an environment
        # variable (see compose.yaml). Written here so it never has to appear on a screen.
        os.makedirs(args.secrets_dir, exist_ok=True)
        target = os.path.join(args.secrets_dir, "nc_password")
        temporary = f"{target}.tmp"
        with open(temporary, "w", encoding="utf-8", newline="") as handle:
            handle.write(credential.app_password)  # no trailing newline, deliberately
        os.replace(temporary, target)
        try:
            os.chmod(target, 0o600)
        except OSError:
            pass  # Windows ACLs do not work this way; the file is in a gitignored directory.
        sys.stderr.write(f"  Wrote {target} for the container.\n")

    sys.stderr.write(
        "\n  This credential is listed in your Nextcloud under Settings > Security as\n"
        f'  "{login.USER_AGENT}". Deleting it there revokes this tool and nothing else.\n\n'
    )
    return 0


def _install_sigterm(server: Server) -> None:
    """Make `docker stop` take a moment rather than ten seconds.

    ⚠️ **Two separate traps, and each on its own is enough to break this.**

    **PID 1 discards signals it has no handler for.** That is a kernel rule, not a Python one: a
    process running as PID 1 — which a container's `CMD` is — does not get the default
    terminate-on-SIGTERM behaviour. And Python installs default handlers for only `SIGPIPE` and
    `SIGINT`, never `SIGTERM`, while `http.server` installs none at all. So a plain
    `CMD ["python", "server.py"]` **silently ignores `docker stop`**, waits out the ten-second
    grace period and is then SIGKILLed — which looks like a slow container rather than a bug.

    ⚠️ **`shutdown()` must not be called from the handler.** A signal handler runs on the main
    thread, which is the thread blocked inside `serve_forever()`, and `shutdown()` waits for that
    loop to notice — so calling it directly deadlocks until the SIGKILL arrives, turning a missing
    handler into a hung one. Dispatching to another thread is what makes it work.

    `--init`/tini is the usual advice for PID 1 and is aimed at a different problem: forwarding
    signals into a *multi-process* container and reaping zombies. This is one Python process, and
    a handler it installs itself is both smaller and more honest about when it is finished.
    """
    import signal  # noqa: PLC0415

    def stop(signum, _frame) -> None:  # noqa: ANN001
        sys.stderr.write(f"\n  signal {signum} - shutting down\n")
        threading.Thread(target=server.shutdown, daemon=True).start()

    for name in ("SIGTERM", "SIGINT"):
        handler = getattr(signal, name, None)
        if handler is not None:
            try:
                signal.signal(handler, stop)
            except (ValueError, OSError):
                # Not the main thread, or a platform that will not take it. The KeyboardInterrupt
                # path above still covers an interactive run.
                pass


if __name__ == "__main__":
    raise SystemExit(main())
