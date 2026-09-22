#!/usr/bin/env python3
"""The MCP server, driven over a real pipe by a client that shares no code with it.

    python tools/web/test_mcp.py

⚠️ **Not an in-process call into `Server._handle`.** The failures this is here to catch are
transport failures — an unflushed stdout, a stray `print`, an indented message, a notification
that got answered — and every one of them is invisible to a test that calls the handler directly.
So this spawns the server as a subprocess, writes newline-delimited JSON into its stdin, and
asserts on what comes back out of its stdout, including that *every* line is one parseable JSON
object. Same reasoning as `tools/m12/test_watch.py` talking HTTP to a real socket rather than
mocking the client, and as M4's lesson that verifying a file with the library that wrote it
proves almost nothing.

The fixture is a mirror directory, so this needs no network and no credentials.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
SERVER = os.path.join(HERE, "mcp_server.py")
PULLED = os.path.join(REPO, "recordings-pulled")

PROTOCOL = "2025-11-25"

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


def build_mirror(root: str) -> str:
    """A Nextcloud-shaped tree built from the real 48-minute lesson in `recordings-pulled/`."""
    mirror = os.path.join(root, "mirror")
    directory = os.path.join(mirror, "Chemistry", ".inkstone-media", "doc-149")
    os.makedirs(directory)
    recording = "019fefb2-0000-7000-8000-000000000149"
    for extension in (".timeline", ".md"):
        shutil.copy(os.path.join(PULLED, f"document_149{extension}"), os.path.join(directory, recording + extension))
    # A stand-in for the audio: this suite never plays it, and copying 12 MB per run is waste.
    with open(os.path.join(directory, recording + ".opus"), "wb") as handle:
        handle.write(b"OggS-not-really")
    return mirror


class Client:
    """Newline-delimited JSON-RPC over the server's actual stdin/stdout."""

    def __init__(self, mirror: str, cache: str) -> None:
        self.process = subprocess.Popen(
            [sys.executable, SERVER, "--mirror", mirror, "--cache", cache],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            bufsize=1,
        )
        self.lines: list[str] = []
        self._next_id = 0

    def send(self, method: str, params: dict | None = None, notify: bool = False):
        message: dict = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            message["params"] = params
        if not notify:
            self._next_id += 1
            message["id"] = self._next_id
        self.process.stdin.write(json.dumps(message) + "\n")
        self.process.stdin.flush()
        if notify:
            return None
        line = self.process.stdout.readline()
        self.lines.append(line)
        return json.loads(line)

    def close(self) -> tuple[str, int]:
        self.process.stdin.close()
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.kill()
        return self.process.stderr.read(), self.process.returncode


def main() -> int:
    with tempfile.TemporaryDirectory() as root:
        mirror = build_mirror(root)
        client = Client(mirror, os.path.join(root, "cache"))

        # -- lifecycle ----------------------------------------------------
        hello = client.send(
            "initialize",
            {
                "protocolVersion": PROTOCOL,
                "capabilities": {},
                "clientInfo": {"name": "test_mcp", "version": "1.0.0"},
            },
        )
        result = hello.get("result", {})
        check("initialize answers", "result" in hello, json.dumps(hello)[:200])
        check(
            "⚠️ it echoes the client's protocol version, never a newer one",
            result.get("protocolVersion") == PROTOCOL,
            f"got {result.get('protocolVersion')!r}; the shipped Claude Code throws on a version "
            "it does not know, so echoing is what makes the connection work",
        )
        check("it declares a tools capability", "tools" in result.get("capabilities", {}))
        check("serverInfo carries a name and a version",
              bool(result.get("serverInfo", {}).get("name")) and bool(result.get("serverInfo", {}).get("version")))

        # A version we do not speak must fall back to one we do, not be echoed blindly.
        other = Client(mirror, os.path.join(root, "cache2"))
        fallback = other.send("initialize", {"protocolVersion": "1999-01-01", "capabilities": {}, "clientInfo": {"name": "x", "version": "1"}})
        check(
            "an unknown protocol version falls back to one we support",
            fallback["result"]["protocolVersion"] != "1999-01-01",
            f"got {fallback['result']['protocolVersion']!r}",
        )
        other.close()

        client.send("notifications/initialized", notify=True)

        # -- tools/list ---------------------------------------------------
        listing = client.send("tools/list")
        tools = listing.get("result", {}).get("tools", [])
        names = {t["name"] for t in tools}
        check("tools/list returns tools", len(tools) >= 8, f"got {len(tools)}")
        check(
            "every tool has a name and an object inputSchema",
            all(t.get("name") and t.get("inputSchema", {}).get("type") == "object" for t in tools),
            "the spec restricts inputSchema to type 'object' at the root",
        )
        check(
            "the tools an assistant needs are all present",
            {"list_lectures", "read_lecture", "speech_around", "search", "read_note", "index_notes"} <= names,
            f"missing: {{'list_lectures','read_lecture','speech_around','search','read_note','index_notes'}} - {names}",
        )
        check(
            "⚠️ index_notes is not marked read-only",
            next(t for t in tools if t["name"] == "index_notes")["annotations"]["readOnlyHint"] is False,
            "it downloads the whole library, which a client should be able to ask about",
        )

        # -- the tools actually work --------------------------------------
        lectures = client.send("tools/call", {"name": "list_lectures", "arguments": {}})
        text = lectures["result"]["content"][0]["text"]
        check("list_lectures finds the lesson", "doc-149" in text and "transcribed" in text, text[:200])
        check("list_lectures is not an error", lectures["result"].get("isError") is False)

        read = client.send(
            "tools/call",
            {"name": "read_lecture", "arguments": {"documentId": "doc-149", "recordingId": "019fefb2-0000-7000-8000-000000000149", "fromMs": 0, "toMs": 60000}},
        )
        body = read["result"]["content"][0]["text"]
        check("read_lecture returns the first minute", "sodium chloride" in body, body[:300])
        check("read_lecture labels the likely teacher as a guess", "heuristic" in body or "most likely" in body)

        around = client.send(
            "tools/call",
            {"name": "speech_around", "arguments": {"documentId": "doc-149", "recordingId": "019fefb2-0000-7000-8000-000000000149", "atMs": 78000}},
        )
        window = around["result"]["content"][0]["text"]
        check("speech_around returns the speech at that moment", "theoretically" in window.lower(), window[:300])

        shape = client.send(
            "tools/call",
            {"name": "lecture_timeline", "arguments": {"documentId": "doc-149", "recordingId": "019fefb2-0000-7000-8000-000000000149"}},
        )
        shape_text = shape["result"]["content"][0]["text"]
        check("lecture_timeline reports the stroke count", "867 strokes" in shape_text, shape_text[:300])
        check(
            "⚠️ it says the recording has no page information rather than implying it has",
            "No page information" in shape_text,
            "this fixture predates the page column; silently reporting zero pages would read as "
            "'nothing was written'",
        )

        found = client.send("tools/call", {"name": "search", "arguments": {"query": "percentage yield"}})
        found_text = found["result"]["content"][0]["text"]
        check("search finds speech", "hit(s)" in found_text and "percentage yield" in found_text.lower())

        # -- failure modes -------------------------------------------------
        unknown = client.send("tools/call", {"name": "no_such_tool", "arguments": {}})
        check(
            "⚠️ an unknown tool is a JSON-RPC error, not an isError result",
            "error" in unknown,
            "the spec reserves protocol errors for failures to *find* a tool",
        )

        # ⚠️ The dispatcher used `getattr(self, "_" + name)`, so the client's own string chose the
        # method — making every private helper on the class an undeclared, differently-shaped tool.
        for private in ("find", "speech", "init", "call"):
            answer = client.send("tools/call", {"name": private, "arguments": {}})
            check(
                f"⚠️ the helper '_{private}' is not callable as a tool",
                "error" in answer and "Unknown tool" in json.dumps(answer),
                "the callable set must be exactly what tools/list advertises",
            )

        missing = client.send(
            "tools/call",
            {"name": "read_lecture", "arguments": {"documentId": "nope", "recordingId": "nope"}},
        )
        check(
            "⚠️ a tool that ran and failed reports isError, not a protocol error",
            missing.get("result", {}).get("isError") is True,
            "so the model can see what went wrong and correct itself",
        )

        empty = client.send("tools/call", {"name": "search", "arguments": {"query": "   "}})
        check("an empty query is a tool error with advice", empty.get("result", {}).get("isError") is True)

        no_args = client.send("tools/call", {"name": "list_lectures"})
        check("tools/call with no arguments key is accepted", "result" in no_args, json.dumps(no_args)[:200])

        ping = client.send("ping")
        check("ping answers", ping.get("result") == {})

        client.send("notifications/cancelled", {"requestId": 1}, notify=True)
        after = client.send("ping")
        check("an unknown notification is never answered and does not desync the stream",
              after.get("result") == {} and after.get("id") == client._next_id)

        # -- the transport itself -----------------------------------------
        stderr, code = client.close()
        check("the server exits when stdin closes", code == 0, f"exit code {code}")
        check(
            "⚠️ every stdout line is exactly one parseable JSON object",
            all(_one_json_object(line) for line in client.lines),
            "block buffering, a stray print, or json.dumps(indent=2) all break this and all "
            "present as 'the server never connected'",
        )
        check("logging went to stderr, where it belongs", "inkstone MCP server ready" in stderr, stderr[-400:])

    print(f"\n{passed}/{passed + failed} passed")
    return 1 if failed else 0


def _one_json_object(line: str) -> bool:
    if not line.endswith("\n") or line.count("\n") != 1:
        return False
    try:
        return isinstance(json.loads(line), dict)
    except json.JSONDecodeError:
        return False


if __name__ == "__main__":
    raise SystemExit(main())
