#!/usr/bin/env python3
"""Login Flow v2, driven against a **real HTTP server** that speaks Nextcloud's side of it.

    python tools/web/test_login.py

⚠️ **Not a mocked client.** The failure modes here are protocol failures — a rebuilt poll URL, a
404 read as fatal, a credential acknowledged before it was stored — and a mock of `urllib` would
agree with every one of them. So a hand-rolled server implements the flow the way Nextcloud does,
including the parts that are easy to get wrong, and the assertions are made about what it
received. Same reasoning as `tools/m12/test_watch.py`, and the same reason `LoginFlowV2Test.kt`
exists on the Kotlin side.

The one thing not exercised is TLS: `require_secure` refuses `http://`, so the tests reach past it
deliberately and say so where they do.
"""

from __future__ import annotations

import http.server
import json
import os
import sys
import tempfile
import threading
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import config  # noqa: E402
import login  # noqa: E402

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


def section(title: str) -> None:
    print(f"\n{title}")


class FakeNextcloud(http.server.BaseHTTPRequestHandler):
    """Nextcloud's half of Login Flow v2, including the details that trip a client up."""

    # Class state, set per test.
    approve_after = 0          # how many polls answer 404 before the credential is handed over
    polls = 0
    seen_user_agents: list[str] = []
    poll_path = "/index.php/login/v2/poll"   # ⚠️ deliberately NOT reconstructible from the start URL
    login_name = "noah"
    uid = "noah.b"             # deliberately different: SPEC §0.1 #3
    app_password = "aaaaa-bbbbb-ccccc-ddddd-eeeee"

    def log_message(self, *args) -> None:  # noqa: A002, ANN002
        pass

    def _json(self, payload: dict, status: int = 200) -> None:
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        FakeNextcloud.seen_user_agents.append(self.headers.get("User-Agent", ""))
        if self.path.startswith("/ocs/v2.php/cloud/user"):
            if self.headers.get("OCS-APIRequest") != "true":
                self.send_error(400, "missing OCS-APIRequest")
                return
            if not (self.headers.get("Authorization") or "").startswith("Basic "):
                self.send_error(401, "no credential")
                return
            self._json({"ocs": {"data": {"id": FakeNextcloud.uid}}})
            return
        # Nextcloud answers 404 to a GET on the login-flow entry point; a client that GETs is wrong.
        self.send_error(404)

    def do_POST(self) -> None:  # noqa: N802
        FakeNextcloud.seen_user_agents.append(self.headers.get("User-Agent", ""))
        host = self.headers.get("Host")

        if self.path == "/index.php/login/v2":
            self._json({
                "poll": {"token": "poll-token-123", "endpoint": f"http://{host}{FakeNextcloud.poll_path}"},
                "login": f"http://{host}/index.php/login/flow/abc",
            })
            return

        if self.path == FakeNextcloud.poll_path:
            length = int(self.headers.get("Content-Length") or 0)
            form = urllib.parse.parse_qs(self.rfile.read(length).decode())
            if form.get("token", [""])[0] != "poll-token-123":
                self.send_error(404)
                return
            FakeNextcloud.polls += 1
            if FakeNextcloud.polls <= FakeNextcloud.approve_after:
                self.send_error(404)  # rule 2: "not ready" and "wrong" look identical
                return
            self._json({
                # The server's own idea of its URL, deliberately not what was typed.
                "server": f"http://{host}",
                "loginName": FakeNextcloud.login_name,
                "appPassword": FakeNextcloud.app_password,
            })
            return

        self.send_error(404)


def serve() -> tuple[http.server.HTTPServer, str]:
    httpd = http.server.HTTPServer(("127.0.0.1", 0), FakeNextcloud)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd, f"http://127.0.0.1:{httpd.server_address[1]}"


def main() -> int:  # noqa: C901
    global passed
    httpd, base = serve()
    login.POLL_INTERVAL_S = 0.01  # the flow, not the waiting

    # ⚠️ The https gate is switched off for the FLOW tests, and only for them.
    #
    # `require_secure` refuses `http://` before a socket is opened, which is correct and is what
    # makes this test server unreachable. The alternative — a self-signed certificate and a trust
    # store — would test TLS setup rather than the login protocol, which is what is interesting
    # here. So the gate is stubbed out for the protocol tests and exercised for real in the
    # "https is required" section below, against the unpatched function.
    #
    # Saying this out loud rather than leaving it in the diff: a test that quietly disables a
    # security check is the kind that makes a green tick mean less than it looks like.
    real_require_secure = login.require_secure
    login.require_secure = lambda url: None

    try:
        section("the flow, end to end")
        FakeNextcloud.polls = 0
        FakeNextcloud.approve_after = 3
        FakeNextcloud.seen_user_agents = []

        start = login.begin(base)
        check("begin() returns the browser URL and the poll endpoint",
              start.browser_url.endswith("/index.php/login/flow/abc") and start.poll_token == "poll-token-123",
              f"{start}")
        check(
            "⚠️ rule 1 — the poll endpoint is the server's, kept whole",
            start.poll_endpoint == f"{base}{FakeNextcloud.poll_path}",
            f"got {start.poll_endpoint}; rebuilding it from the start URL works against one "
            "server and 404s against another",
        )

        stored: list[login.Credential] = []
        credential = login.await_approval(start, persist=stored.append)

        check("⚠️ rule 2 — a 404 is 'not yet', so the flow survives 3 of them",
              FakeNextcloud.polls == 4, f"polled {FakeNextcloud.polls} times")
        check("the app password comes back", credential.app_password == FakeNextcloud.app_password)
        check(
            "⚠️ rule 3 — persist was called BEFORE the function returned, and twice",
            len(stored) == 2 and stored[0].app_password == FakeNextcloud.app_password,
            f"{len(stored)} call(s); the server deletes its row when it answers, so a crash "
            "between the response and the write loses the credential for good",
        )
        check("…the first write has no uid yet, the second does",
              stored[0].uid == "" and stored[1].uid == FakeNextcloud.uid,
              f"{stored[0].uid!r} then {stored[1].uid!r}")
        check(
            "⚠️ rule 6 — the uid is fetched and is NOT the login name",
            credential.uid == "noah.b" and credential.login_name == "noah",
            f"uid={credential.uid!r} login={credential.login_name!r}; the uid is the WebDAV path "
            "segment (SPEC §0.1 #3) and using loginName gives 404s on every file",
        )
        check("the server's own URL wins over what was typed",
              credential.server_url == base, credential.server_url)
        check(
            "⚠️ rule 4 — a human-readable User-Agent, which becomes the credential's name",
            all(agent == login.USER_AGENT for agent in FakeNextcloud.seen_user_agents),
            f"saw {set(FakeNextcloud.seen_user_agents)}; the default would list this as "
            "'python-urllib' in the user's security settings",
        )

        section("rule 2 — the client runs its own clock")
        FakeNextcloud.polls = 0
        FakeNextcloud.approve_after = 10_000  # never approves
        start = login.begin(base)
        ticks = [0.0]

        def fake_now() -> float:
            ticks[0] += 400.0  # three polls and the 20-minute deadline is past
            return ticks[0]

        timed_out = False
        try:
            login.await_approval(start, persist=lambda c: None, now=fake_now)
        except login.LoginError as error:
            timed_out = "allowed_user_agents" in str(error)
        check(
            "⚠️ it gives up on its own, and the message names the User-Agent restriction",
            timed_out,
            "rule 5: an admin PCRE makes the browser 403 while the client polls 404 forever, with "
            "no signal at all - so the timeout has to raise the possibility itself",
        )

        section("https is required")
        login.require_secure = real_require_secure  # the real one, from here on
        refused = False
        try:
            login.begin("http://cloud.example.com")
        except login.LoginError as error:
            refused = "https" in str(error)
        check("⚠️ a plain-HTTP server is refused before a socket is opened", refused,
              "this exchange carries an app password")
        check("a bare hostname becomes https://", login.normalise_server("cloud.example.com") == "https://cloud.example.com")
        check("http:// is kept, not silently upgraded",
              login.normalise_server("http://x.com") == "http://x.com",
              "rewriting it would make a typo look like it worked")

        section("what gets written to watcher.local.properties")
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "watcher.local.properties")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(
                    "# A comment that must survive.\n"
                    "nextcloud.url=https://old.example.com\n"
                    "nextcloud.password=old-password\n"
                    "assemblyai.key=keep-me\n"
                )
            config.save({"nextcloud.url": "https://new.example.com",
                         "nextcloud.password": "new-password",
                         "nextcloud.uid": "noah.b"}, path)
            written = open(path, encoding="utf-8").read()
            values = config.parse_properties(written)
            check("existing keys are updated", values["nextcloud.url"] == "https://new.example.com"
                  and values["nextcloud.password"] == "new-password")
            check("⚠️ an unrelated key is not destroyed", values.get("assemblyai.key") == "keep-me",
                  "the watcher's own settings live in this file too")
            check("⚠️ comments survive", "# A comment that must survive." in written,
                  "the file explains itself; rewriting it from a template would delete that")
            check("a new key is appended", values.get("nextcloud.uid") == "noah.b")
            check("the file still parses as it did", len(values) == 4, str(values))
    finally:
        login.require_secure = real_require_secure
        httpd.shutdown()
        httpd.server_close()

    print(f"\n{passed}/{passed + failed} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
