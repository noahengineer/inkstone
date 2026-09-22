#!/usr/bin/env python3
"""Signing in to Nextcloud **from the web page**, rather than from a command line.

⛔ This file exists because of a mistake worth naming. `--login` was built as a CLI flag, and the
website — the only place a person actually is — kept telling them to *"mint a new app password:
Nextcloud → Settings → Security"*, which is precisely the manual step `--login` exists to remove.
The feature worked and was unreachable, and the page's own error message pointed away from it.

That is CLAUDE.md's definition-of-done #6 (*"a feature can be correct in every test and inert in
the app"*), committed one turn after writing the feature. The user's report was
*"does not give me an option to sign into nextcloud"* — which was exactly true.

## The shape

Login Flow v2 needs a browser and takes as long as a person takes, so it cannot be one request:

    POST /api/nextcloud/begin   {url}   -> {flowId, browserUrl}   the page opens browserUrl
    GET  /api/nextcloud/poll?flowId=…   -> {state: pending|done|failed}

One flow at a time, in memory, with the server's own deadline. There is no database here on
purpose: an abandoned sign-in should evaporate when the process restarts, and a half-finished one
is worth nothing.

## ⚠️ The latch has to be released, and that is the non-obvious half

`NextcloudSource` **latches shut on the first 401** (CLAUDE.md rule 5 — a re-scanning website with
a dead credential is a failed login per folder per refresh, which is how a user's own server
IP-blocks them). So a successful sign-in cannot simply write the new password and carry on: the
running source is still latched, still refusing, and the page would go on showing the same error
after a sign-in that actually worked.

[Flow.apply] therefore **rebuilds the source, the library and the MCP tools** and swaps them in.
A latch that could not be released would be a worse bug than the retry loop it prevents.
"""

from __future__ import annotations

import secrets
import threading
import time
from dataclasses import dataclass, field

import login


@dataclass
class Flow:
    """One in-progress sign-in."""

    flow_id: str
    server_url: str
    browser_url: str
    start: login.Start
    started_at: float
    state: str = "pending"          # pending | done | failed
    message: str = ""
    credential: login.Credential | None = None
    _lock: threading.Lock = field(default_factory=threading.Lock)


class Connector:
    """Runs the browser sign-in for the website. One flow at a time is enough for one person."""

    def __init__(self, persist, rebuild, verify=login.trust_any) -> None:  # noqa: ANN001
        self._persist = persist
        self._rebuild = rebuild
        self._verify = verify
        self._flow: Flow | None = None
        self._lock = threading.Lock()

    def begin(self, server_url: str) -> Flow:
        """Ask the server to start a login. Raises `login.LoginError` with a readable message."""
        normalised = login.normalise_server(server_url)
        login.require_secure(normalised)
        start = login.begin(normalised, self._verify)
        flow = Flow(
            flow_id=secrets.token_urlsafe(16),
            server_url=normalised,
            browser_url=start.browser_url,
            start=start,
            started_at=time.monotonic(),
        )
        with self._lock:
            self._flow = flow
        threading.Thread(target=self._run, args=(flow,), daemon=True).start()
        return flow

    def poll(self, flow_id: str) -> dict:
        with self._lock:
            flow = self._flow
        if flow is None or flow.flow_id != flow_id:
            return {"state": "failed", "message": "That sign-in is no longer running. Start again."}
        return {
            "state": flow.state,
            "message": flow.message,
            "browserUrl": flow.browser_url,
            "secondsLeft": max(0, int(login.LIFETIME_SECONDS - (time.monotonic() - flow.started_at))),
        }

    def _run(self, flow: Flow) -> None:
        """The waiting half, on its own thread so the page stays responsive."""
        try:
            credential = login.await_approval(
                flow.start,
                persist=lambda c: None,  # ⚠️ see below — the real write happens once, after
                verify=self._verify,
            )
        except login.LoginError as error:
            flow.state, flow.message = "failed", str(error)
            return
        except Exception as error:  # noqa: BLE001
            flow.state, flow.message = "failed", f"{type(error).__name__}: {error}"
            return

        # ⚠️ Rule 3 says persist *before* acknowledging, and this looks like it violates that. It
        # does not: `await_approval` has already returned, so the app password is in hand, and the
        # write below happens before anything is reported to the page. What is deliberately NOT
        # done is persisting the intermediate uid-less credential — the CLI path writes twice
        # because it may be killed between them, and here the two are microseconds apart inside
        # one function that cannot be interrupted by a user closing a terminal.
        try:
            self._persist(credential)
            self._rebuild(credential)
        except Exception as error:  # noqa: BLE001
            flow.state = "failed"
            flow.message = f"Signed in, but could not save the credential: {error}"
            return

        flow.credential = credential
        flow.state = "done"
        flow.message = f"Signed in as {credential.login_name}."
