#!/usr/bin/env python3
"""The MCP **Streamable HTTP** transport, so Claude can reach this server from another machine.

`mcp_server.Protocol` is the protocol and knows nothing about transports; `mcp_server.Server` is
the stdio one. This is the other one. Both drive the same object, which is the whole reason
`Protocol` was split out — a second transport is exactly where a second, subtly disagreeing
implementation of version negotiation and the notification rule would otherwise appear.

## Targeting revision 2025-11-25, on purpose

The current specification is **2026-07-28**, and this deliberately does not target it: that
revision **removes sessions from Streamable HTTP entirely** and replaces the `initialize`
handshake, and the Claude Code build in this environment speaks up to `2025-11-25`. Same reasoning
as the stdio transport — build what the client can talk to, and write down why.

## The rules, each of which is a way this fails silently if guessed

Every one of these was read off the specification rather than recalled, and several are the
opposite of the obvious guess:

* **One endpoint, `POST` and `GET`.** `/mcp` is the spec's own example path.
* ⚠️ **A POST carrying a notification or a response gets `202 Accepted` with NO body.** Not a 200,
  and not an empty JSON object. `notifications/initialized` is the first thing a client sends after
  the handshake, so getting this wrong breaks every connection at the second message.
* **A POST carrying a request may be answered with a single `application/json` object.** SSE is the
  *other* permitted shape, not the required one — which is what makes a JSON-only server like this
  one conforming rather than a shortcut.
* ⚠️ **`GET` is refused with `405`**, which is the spec's designated way of saying "no server-
  initiated stream here". This server never needs to push, so there is nothing to open.
* ⚠️ **`MCP-Protocol-Version` absent means assume `2025-03-26` — it must NOT be rejected.** Only a
  value we do not support is a `400`. Rejecting the absent case would refuse the client's very
  first post-initialize request.
* ⚠️ **Origin is validated only when it is PRESENT.** A CLI client sends no `Origin` at all, so a
  server that requires one rejects every real connection while passing a naive reading of "Origin
  validation is a MUST". The 403 is for a header that is there and wrong.
* **No sessions.** They are optional, this server holds no per-client state, and the 2026 revision
  removes them — so `MCP-Session-Id` is never issued and never required. Nothing here may ever use
  a session as an authentication mechanism; the spec forbids it and the token is what authenticates.

## ⚠️ It is stateless, and that is load-bearing for correctness

Every tool call is answerable from the library index alone, so two requests from two machines
cannot interfere. `Protocol.handle` is called on a shared instance from `ThreadingHTTPServer`
threads; the only thing `initialize` would otherwise mutate is the negotiated version, which is
recomputed per request here so no thread can read another's.
"""

from __future__ import annotations

import json

# What a client may ask for. Mirrors `mcp_server.SUPPORTED_PROTOCOLS`; imported rather than
# duplicated so the two transports cannot drift.
from mcp_server import SUPPORTED_PROTOCOLS

ENDPOINT = "/mcp"

#: The revision to assume when the client sends no `MCP-Protocol-Version`. The spec names this
#: exact value, and the correct behaviour is to assume it rather than to reject.
ASSUMED_VERSION = "2025-03-26"

PROTOCOL_HEADER = "MCP-Protocol-Version"

JSON_RPC_PARSE_ERROR = -32700
JSON_RPC_INVALID_REQUEST = -32600

# A single JSON-RPC message is small. This is a guard against a client that streams for ever, not
# a limit anyone should hit: the largest real message is a `tools/call` with a few arguments.
MAX_BODY_BYTES = 4 * 1024 * 1024


class Response:
    """What the HTTP layer should send. Deliberately dumb — `server.py` does the writing."""

    __slots__ = ("status", "body", "content_type")

    def __init__(self, status: int, body: bytes = b"", content_type: str = "") -> None:
        self.status = status
        self.body = body
        self.content_type = content_type

    @classmethod
    def accepted(cls) -> Response:
        """⚠️ 202 with no body — the required answer to a notification or a response."""
        return cls(202)

    @classmethod
    def json(cls, payload: dict, status: int = 200) -> Response:
        return cls(
            status,
            json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8"),
            "application/json",
        )

    @classmethod
    def problem(cls, status: int, message: str) -> Response:
        """A transport-level refusal. Not a JSON-RPC error — those ride inside a 200."""
        return cls(status, json.dumps({"error": message}).encode("utf-8"), "application/json")


def check_version(headers) -> Response | None:  # noqa: ANN001
    """None when the request may proceed, otherwise the 400 to send.

    ⚠️ An **absent** header is fine and means `2025-03-26`. Only a present-but-unsupported value
    is a `400`. This is the rule most likely to be implemented backwards, and doing so refuses
    every request a client makes after `initialize`.
    """
    asked = headers.get(PROTOCOL_HEADER)
    if asked is None:
        return None
    if asked in SUPPORTED_PROTOCOLS or asked == ASSUMED_VERSION:
        return None
    return Response.problem(
        400,
        f"Unsupported {PROTOCOL_HEADER}: {asked!r}. "
        f"This server speaks {', '.join(SUPPORTED_PROTOCOLS)}.",
    )


#: ⚠️ The **pre-initialize probe**, and the reason the version check cannot come first.
#:
#: Claude Code opens a connection by POSTing `{"method": "server/discover", …}` carrying
#: `MCP-Protocol-Version: 2026-07-28` — the *next* revision, before anything has been negotiated.
#: That is the modern era's replacement for `initialize`, and this server does not implement it.
#:
#: Enforcing the version header on that probe answers `400`, and the client does then fall back to
#: a normal `initialize` and connect — that was measured, not assumed. But the fallback is the
#: client's kindness rather than a guarantee, and the spec's MUST is about a request operating
#: under a *negotiated* version, which a pre-negotiation probe by definition is not. So the probe
#: is answered with an ordinary JSON-RPC "I do not know that method", which is what tells a
#: dual-era client to use the legacy handshake, and it cannot be broken by a client that stops
#: falling back.
PRE_INIT_PROBE = "server/discover"


def handle_post(protocol, body: bytes, headers) -> Response:  # noqa: ANN001
    """One POSTed JSON-RPC message in, one HTTP response out."""
    if len(body) > MAX_BODY_BYTES:
        return Response.problem(413, "That message is too large.")

    try:
        message = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        # A parse failure has no id to answer against, so the JSON-RPC error carries a null id.
        return Response.json(
            {"jsonrpc": "2.0", "id": None, "error": {"code": JSON_RPC_PARSE_ERROR, "message": f"Parse error: {error}"}},
            status=400,
        )

    # ⚠️ Batching was removed in 2025-06-18: the body is a single message. An array is refused
    # rather than quietly iterated, because answering several messages down one response body has
    # no defined shape in this revision.
    if not isinstance(message, dict):
        return Response.json(
            {"jsonrpc": "2.0", "id": None, "error": {"code": JSON_RPC_INVALID_REQUEST, "message": "Expected a single JSON-RPC message, not a batch."}},
            status=400,
        )

    # The probe is answered before the version header is looked at — see [PRE_INIT_PROBE].
    if message.get("method") == PRE_INIT_PROBE:
        request_id = message.get("id")
        if request_id is None:
            return Response.accepted()
        return Response.json(
            protocol.error(
                request_id,
                -32601,
                f"Unknown method: {PRE_INIT_PROBE}. This server speaks the legacy lifecycle; "
                "send an initialize request.",
            )
        )

    version_problem = check_version(headers)
    if version_problem is not None:
        return version_problem

    # ⚠️ The 202 branch is chosen by **what the client sent**, not by server preference, and it
    # covers two shapes rather than one: a *notification* (no `id`) and a *response* (an `id` but
    # no `method`, answering a server-to-client request).
    #
    # Only the first falls out of `Protocol.handle` returning None. A posted response would reach
    # the method dispatch with `method` unset and come back as *"Unknown method: None"* — an error
    # answering a message that is not a question. This server never sends requests to a client, so
    # nothing should ever post one; handling it is three lines and means a client that does is not
    # met with nonsense.
    if "method" not in message:
        return Response.accepted()

    answer = protocol.handle(message)
    if answer is None:
        # A notification. 202, no body — see this module's docstring.
        return Response.accepted()
    return Response.json(answer)


def handle_get() -> Response:
    """⚠️ 405 — the spec's way of saying this endpoint offers no server-initiated stream."""
    return Response.problem(
        405,
        "This MCP endpoint does not open a server-to-client stream. POST your requests to it.",
    )


def handle_delete() -> Response:
    """405 as well: there is no session to terminate, because none is ever issued."""
    return Response.problem(405, "This MCP endpoint is stateless; there is no session to end.")
