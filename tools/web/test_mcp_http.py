#!/usr/bin/env python3
"""The MCP Streamable HTTP transport, driven over a real socket.

    python tools/web/test_mcp_http.py

`test_mcp.py` drives the same `Protocol` over a real pipe. This drives it over a real TCP
connection, with hand-built HTTP — no client library, because a client library would agree with
whatever this server does and prove nothing about the wire.

Every assertion below is a rule read off the specification for revision **2025-11-25**, and most
of them are rules whose obvious guess is wrong. They are written as the reasons rather than as
`assert response.status == 202`, because in six months the number will mean nothing and the reason
still will.
"""

from __future__ import annotations

import http.client
import json
import os
import shutil
import sys
import tempfile
import threading

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)

import auth as auth_mod  # noqa: E402
import library as library_mod  # noqa: E402
import mcp_http  # noqa: E402
import mcp_server  # noqa: E402
import server as server_mod  # noqa: E402
from source import MirrorSource  # noqa: E402

PULLED = os.path.join(REPO, "recordings-pulled")
RECORDING = "019fefb2-0000-7000-8000-000000000149"
TOKEN = "test-bearer-token"

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


def build_mirror(root: str) -> str:
    mirror = os.path.join(root, "mirror")
    directory = os.path.join(mirror, "Chemistry", ".inkstone-media", "doc-149")
    os.makedirs(directory)
    for extension in (".timeline", ".md"):
        shutil.copy(
            os.path.join(PULLED, f"document_149{extension}"),
            os.path.join(directory, RECORDING + extension),
        )
    with open(os.path.join(directory, RECORDING + ".opus"), "wb") as handle:
        handle.write(b"OggS" + b"\0" * 60)
    return mirror


def main() -> int:  # noqa: C901
    with tempfile.TemporaryDirectory() as root:
        mirror = build_mirror(root)
        lib = library_mod.Library(MirrorSource(mirror), os.path.join(root, "cache"))
        server_mod.Handler.library = lib
        server_mod.Handler.media = server_mod.MediaCache(os.path.join(root, "media"))
        server_mod.Handler.guard = auth_mod.Guard(mcp_token=TOKEN, secure_cookies=False)
        server_mod.Handler.protocol = mcp_server.Protocol(mcp_server.Tools(lib))
        httpd = server_mod.Server(("127.0.0.1", 0), server_mod.Handler)
        port = httpd.server_address[1]
        threading.Thread(target=httpd.serve_forever, daemon=True).start()

        def call(method: str, body=None, headers: dict | None = None, authed: bool = True):
            connection = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
            # `bytes` go through untouched, so a test can send something that is NOT JSON.
            # `json.dumps("not json at all")` produces a valid JSON *string*, which would have
            # exercised the wrong branch entirely.
            if body is None:
                payload = b""
            elif isinstance(body, bytes):
                payload = body
            else:
                payload = json.dumps(body).encode("utf-8")
            head = {
                "Host": f"127.0.0.1:{port}",
                # A real client advertises both, because either shape is a legal answer.
                "Accept": "application/json, text/event-stream",
                "Content-Type": "application/json",
                "Content-Length": str(len(payload)),
            }
            if authed:
                head["Authorization"] = f"Bearer {TOKEN}"
            head.update(headers or {})
            connection.request(method, mcp_http.ENDPOINT, body=payload, headers=head)
            response = connection.getresponse()
            raw = response.read()
            connection.close()
            return response.status, dict(response.getheaders()), raw

        def rpc(message, **kwargs):
            status, headers, raw = call("POST", message, **kwargs)
            parsed = json.loads(raw) if raw else None
            return status, headers, parsed

        try:
            section("authorisation")
            status, headers, _ = call("POST", {"jsonrpc": "2.0", "id": 1, "method": "ping"}, authed=False)
            check(
                "⛔ an unauthenticated call is 403, NOT 401 with a Bearer challenge",
                status == 403,
                f"got {status}. A 401 with WWW-Authenticate makes Claude Code treat this as an "
                "OAuth resource; it can then ignore a correct static bearer header, report itself "
                "connected, and expose synthetic auth tools instead of the real ones.",
            )
            check(
                "⛔ …and carries no WWW-Authenticate header, for the same reason",
                "WWW-Authenticate" not in headers,
                str(headers.get("WWW-Authenticate")),
            )

            status, _, _ = call("POST", {"jsonrpc": "2.0", "id": 1, "method": "ping"},
                                headers={"Authorization": "Bearer wrong"}, authed=False)
            check("a wrong token is refused", status == 403, str(status))

            section("no OAuth advertisement anywhere")
            for probe in (
                "/.well-known/oauth-protected-resource",
                "/.well-known/oauth-authorization-server",
                "/.well-known/openid-configuration",
            ):
                connection = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
                connection.request("GET", probe, headers={"Host": f"127.0.0.1:{port}"})
                response = connection.getresponse()
                response.read()
                connection.close()
                check(
                    f"⛔ {probe} is not served",
                    response.status in (401, 403, 404),
                    f"got {response.status}. Their mere PRESENCE is enough to start the client's "
                    "OAuth path, whatever the endpoint returns.",
                )

            section("the handshake")
            status, headers, answer = rpc({
                "jsonrpc": "2.0", "id": 1, "method": "initialize",
                "params": {"protocolVersion": "2025-11-25", "capabilities": {},
                           "clientInfo": {"name": "test", "version": "1"}},
            })
            check("initialize answers 200 with application/json",
                  status == 200 and headers.get("Content-Type") == "application/json",
                  f"{status} {headers.get('Content-Type')}")
            check("…echoing the client's protocol version",
                  answer["result"]["protocolVersion"] == "2025-11-25",
                  str(answer["result"].get("protocolVersion")))
            check("…and declaring tools", "tools" in answer["result"]["capabilities"])
            check(
                "no MCP-Session-Id is issued — this server is stateless",
                "MCP-Session-Id" not in headers,
                "sessions are optional, nothing here holds per-client state, and the next spec "
                "revision removes them from this transport entirely",
            )

            section("⚠️ a notification gets 202 and no body")
            status, headers, raw = call("POST", {"jsonrpc": "2.0", "method": "notifications/initialized"})
            check(
                "notifications/initialized answers 202 Accepted with an empty body",
                status == 202 and raw == b"",
                f"got {status} with {len(raw)} bytes. This is the SECOND message every client "
                "sends; answering 200-with-a-body here breaks every connection immediately.",
            )

            section("⚠️ the protocol version header")
            status, _, answer = rpc({"jsonrpc": "2.0", "id": 2, "method": "ping"},
                                    headers={"MCP-Protocol-Version": "2025-11-25"})
            check("a supported version is accepted", status == 200 and answer["result"] == {}, str(status))

            status, _, _ = rpc({"jsonrpc": "2.0", "id": 3, "method": "ping"})
            check(
                "⚠️ an ABSENT version header is fine — it means 2025-03-26, and must not be rejected",
                status == 200,
                f"got {status}. Rejecting the absent case refuses every request a client makes "
                "after initialize, which is the natural way to implement this backwards.",
            )

            status, _, _ = call("POST", {"jsonrpc": "2.0", "id": 4, "method": "ping"},
                                headers={"MCP-Protocol-Version": "1999-01-01"})
            check("a version we do not speak is 400", status == 400, str(status))

            section("⚠️ what Claude Code actually sends")
            # Measured against Claude Code 2.1.227, not assumed: it opens with this probe, and it
            # carries the NEXT revision's version before anything has been negotiated.
            status, headers, answer = rpc(
                {"jsonrpc": "2.0", "id": 99, "method": "server/discover"},
                headers={"MCP-Protocol-Version": "2026-07-28"},
            )
            check(
                "⚠️ the pre-initialize server/discover probe is answered, not 400'd",
                status == 200 and answer["error"]["code"] == -32601,
                f"{status} {json.dumps(answer)[:140]}. Enforcing the version header on the probe "
                "answers 400 and relies on the client falling back to initialize - which it does, "
                "but that is its kindness rather than a guarantee.",
            )

            # ⚠️ The client sends its headers LOWERCASE (`mcp-protocol-version`). HTTP field names
            # are case-insensitive, and an exact-case dict lookup would silently stop enforcing.
            status, _, _ = call(
                "POST", {"jsonrpc": "2.0", "id": 98, "method": "ping"},
                headers={"mcp-protocol-version": "1999-01-01"},
            )
            check(
                "⚠️ the version header is read case-insensitively, as the client sends it lowercase",
                status == 400,
                f"got {status} - a case-sensitive lookup would let this through and the check "
                "would be decorative",
            )

            section("⚠️ the response Content-Type, which is the commonest way this fails")
            status, headers, _ = rpc({"jsonrpc": "2.0", "id": 97, "method": "ping"})
            check(
                "every JSON answer is labelled application/json",
                headers.get("Content-Type") == "application/json",
                f"got {headers.get('Content-Type')!r}. Measured: a server whose body is perfect "
                "but labelled text/plain is refused with CLIENT_HTTP_UNEXPECTED_CONTENT before "
                "the body is even read.",
            )

            section("⚠️ GET and DELETE are refused with 405")
            status, _, _ = call("GET", None, headers={"Accept": "text/event-stream"})
            check(
                "GET is 405 — the spec's way of saying there is no server-initiated stream",
                status == 405,
                f"got {status}",
            )
            status, _, _ = call("DELETE", None)
            check("DELETE is 405 — there is no session to terminate", status == 405, str(status))

            section("the tools, over HTTP")
            status, _, answer = rpc({"jsonrpc": "2.0", "id": 5, "method": "tools/list"})
            names = {t["name"] for t in answer["result"]["tools"]}
            check("tools/list returns the same tools as stdio",
                  {"list_lectures", "read_lecture", "speech_around", "search"} <= names,
                  str(sorted(names)))

            status, _, answer = rpc({
                "jsonrpc": "2.0", "id": 6, "method": "tools/call",
                "params": {"name": "speech_around", "arguments": {
                    "documentId": "doc-149", "recordingId": RECORDING, "atMs": 132000}},
            })
            text = answer["result"]["content"][0]["text"]
            check("a tool call works over HTTP", "yield" in text.lower(), text[:120])
            check("…and is not an error", answer["result"]["isError"] is False)

            status, _, answer = rpc({
                "jsonrpc": "2.0", "id": 7, "method": "tools/call",
                "params": {"name": "no_such_tool", "arguments": {}},
            })
            check("an unknown tool is still a JSON-RPC error inside a 200",
                  status == 200 and "error" in answer, f"{status} {json.dumps(answer)[:120]}")

            section("malformed input")
            status, _, raw = call("POST", b"not json at all")
            parsed = json.loads(raw)
            check("a body that is not JSON is a JSON-RPC parse error with a null id",
                  parsed["error"]["code"] == -32700 and parsed["id"] is None,
                  json.dumps(parsed)[:160])

            status, _, raw = call("POST", [{"jsonrpc": "2.0", "id": 1, "method": "ping"}])
            parsed = json.loads(raw)
            check(
                "⚠️ a batch array is refused — batching was removed in 2025-06-18",
                parsed["error"]["code"] == -32600,
                json.dumps(parsed)[:160],
            )

            section("⚠️ Origin is checked only when it is present")
            status, _, _ = rpc({"jsonrpc": "2.0", "id": 8, "method": "ping"},
                               headers={"Origin": "https://evil.example.com"})
            check("a foreign Origin is refused", status == 403, str(status))
            status, _, _ = rpc({"jsonrpc": "2.0", "id": 9, "method": "ping"})
            check(
                "…but an ABSENT Origin is fine, because CLI clients send none",
                status == 200,
                f"got {status}. Requiring Origin passes a naive reading of 'validation is a MUST' "
                "and rejects every real connection.",
            )
        finally:
            httpd.shutdown()
            httpd.server_close()

    print(f"\n{passed}/{passed + failed} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
