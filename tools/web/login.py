#!/usr/bin/env python3
"""Signing in to Nextcloud the way the app does — type a URL, log in in your browser, done.

    py tools/web/server.py --login

You are not asked for a password here and this tool never sees one. You type your server's
address, your browser opens on your own Nextcloud's login page, you sign in exactly as you always
do — including a second factor, if you use one — and Nextcloud hands this tool a credential of its
own. That credential lands in `watcher.local.properties`, which the M12 transcript watcher already
reads, so one sign-in serves both tools.

## Why not just take a username and a password

Because what comes out of this is better than what you would have typed, in four ways that all
matter later:

* **It works with two-factor authentication.** An account password is *refused* for WebDAV on any
  account with 2FA enabled — so the simpler-looking route is the one that breaks for the people
  most careful about their accounts.
* **It can be revoked on its own.** It appears in Settings → Security as "Inkstone Reader"; press
  the bin and this tool stops working and nothing else does. Revoking your account password means
  changing your password everywhere.
* **It carries no second factor and cannot change your account.** A leaked file costs you file
  access, not the account.
* ⚠️ **A wrong password is worse than an inconvenience here.** Nextcloud counts failed logins and
  throttles by IP (CLAUDE.md rule 5). A stored *account* password that goes stale — because you
  changed it — is a background process failing to log in as *you*, repeatedly. A stale app password
  fails as a credential nobody is using.

This is SPEC §7.1, and it is the same flow `LoginFlowV2.kt` runs on the tablet. This file is the
Python end of it.

## ⚠️ The six rules, and why each one exists

Copied from `LoginFlowV2.kt`'s KDoc rather than rediscovered, because every one of them was paid
for once already:

1. **Never rebuild the poll URL.** The server builds it with `linkToRouteAbsolute`, so
   `/index.php` may or may not appear in it. [Start.poll_endpoint] is POSTed to **verbatim**;
   reconstructing it works against your server and 404s against somebody else's.
2. **404 is overloaded.** "Not ready", "invalid" and "expired" are indistinguishable, so the
   client runs its own [LIFETIME_SECONDS] clock rather than waiting for a signal that does not
   exist.
3. **Persist before acknowledging the 200.** The server deletes its row the moment it answers, so
   a crash between the response and the write is *unrecoverable* — you would be left with an app
   password in your security settings that nothing holds. [await_approval] therefore calls
   `persist` **before** it returns, and calls it twice: once the instant the password arrives with
   an empty uid, and again once the uid is known.
4. **A human-readable User-Agent.** It becomes the app password's display name in your security
   settings. Leaving the default there is how other clients end up listed as "python-urllib".
5. ⚠️ **An admin can restrict `core.login_flow_v2.allowed_user_agents`** with a PCRE. No match and
   the browser 403s while the client polls 404 for twenty minutes with no signal at all — so the
   timeout message names that possibility outright.
6. **The uid is not the login name.** `/ocs/v2.php/cloud/user` → `ocs.data.id` is the WebDAV path
   segment (SPEC §0.1 #3). They are the same on many installs and different on plenty.
"""

from __future__ import annotations

import base64
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from dataclasses import dataclass

#: Rule 4. This string is what you will see in Settings → Security beside the credential, so it is
#: written for a person reading that list months from now, not for a log.
USER_AGENT = "Inkstone Reader"

#: Rule 2 — the server-side lifetime of a login-flow token, run by the client because the server
#: gives no signal. Matches `LoginFlowV2.LIFETIME_SECONDS`.
LIFETIME_SECONDS = 1200
POLL_INTERVAL_S = 3.0

_NOT_FOUND = 404


class LoginError(Exception):
    """Something went wrong, with a message meant to be printed straight to a person."""


@dataclass(frozen=True)
class Start:
    server_url: str
    browser_url: str
    poll_endpoint: str
    poll_token: str


@dataclass(frozen=True)
class Credential:
    server_url: str
    login_name: str
    uid: str
    app_password: str


def normalise_server(raw: str) -> str:
    """Trim, drop a trailing slash, and add `https://` when no scheme was typed.

    ⚠️ `http://` is **kept, not rewritten**, so that [require_secure] can refuse it with an
    explanation. Silently upgrading it would make a typo look like it worked and a genuine
    plain-HTTP server fail with something unrelated.
    """
    trimmed = raw.strip().rstrip("/")
    if not trimmed:
        return trimmed
    if trimmed.lower().startswith(("https://", "http://")):
        return trimmed
    return f"https://{trimmed}"


#: ⛔ How hard to check a URL that came back **from the server** rather than from the user.
#:
#: Login Flow v2 hands back three more URLs — `poll.endpoint`, `login`, and, after approval, the
#: `server` field that becomes the account's permanent base URL. Every one of them is exactly as
#: attacker-controlled as the address the user typed, and none of them arrives as a redirect, so
#: no redirect defence fires.
#:
#: The attack, in full: a user signs up with `https://evil.tld/` — a real domain with a valid
#: certificate on a public address, which passes every check there is. Their server answers with
#: `poll.endpoint = "https://169.254.169.254/latest/meta-data/iam/security-credentials/"` and this
#: client POSTs there. Worse, setting `server` to an internal host makes **every WebDAV request for
#: the life of that account** go there carrying the stored credential — a one-shot SSRF turned into
#: a durable authenticated internal client.
#:
#: `single_user` is the default because a person's own Nextcloud may legitimately be on a LAN
#: address or a non-standard port, and refusing that would break the tool for its first user.
#: Multi-user mode passes `safefetch.check_url`, which refuses every private destination.
def trust_any(url: str) -> None:
    """The single-user policy: it is your own server, so only the scheme is checked."""
    require_secure(normalise_server(url))


def require_secure(server_url: str) -> None:
    if not server_url.lower().startswith("https://"):
        raise LoginError(
            f"{server_url} is not https://.\n\n"
            "Refusing to run a login over plain HTTP: this exchange carries an app password, and "
            "anything on the path could read it. The app refuses this at the same point (SPEC "
            "§7.1) and there is deliberately no override."
        )


def _open(url: str, data: bytes | None, headers: dict[str, str], timeout: float = 30.0):
    request = urllib.request.Request(url, data=data, method="POST")
    request.add_header("User-Agent", USER_AGENT)  # rule 4
    for key, value in headers.items():
        request.add_header(key, value)
    return urllib.request.urlopen(request, timeout=timeout, context=_tls_for(url))


def _tls_for(url: str):
    """The strictest TLS context that can actually reach this host — see `tlsdiag`.

    Python 3.14 turns on `VERIFY_X509_STRICT`, which refuses a certificate chain for a missing
    Authority Key Identifier. That is a conformance complaint rather than a trust failure, and it
    makes the tool unusable behind any TLS-terminating appliance whose CA the machine already
    trusts. `tlsdiag` clears that one flag for such a host and **nothing else**; an untrusted,
    expired or wrong-hostname certificate still fails.
    """
    import tlsdiag  # noqa: PLC0415

    host = urllib.parse.urlsplit(url).hostname or ""
    return tlsdiag.context_for(host) if host else None


def begin(server_url: str, verify=trust_any) -> Start:  # noqa: ANN001
    """`POST <server>/index.php/login/v2`. The one login URL legitimately constructed."""
    require_secure(server_url)
    url = f"{server_url}/index.php/login/v2"
    verify(url)
    try:
        # ⚠️ POST with **no body at all**. Nextcloud answers 404 to a GET here, and sending a body
        # is what makes some reverse proxies answer 411.
        with _open(url, None, {"Accept": "application/json"}) as response:
            payload = json.loads(response.read())
    except urllib.error.HTTPError as error:
        raise LoginError(
            f"The server refused to start a login (HTTP {error.code}).\n"
            f"Check that {server_url} is really your Nextcloud's address."
        ) from error
    except Exception as error:  # noqa: BLE001
        raise LoginError(f"Could not reach {server_url}: {error}") from error

    try:
        poll = payload["poll"]
        # ⛔ Both of these came from the server, so both are checked as hard as the user's own
        # input. `poll.endpoint` is where this client is about to POST; `login` is where the
        # user's browser is about to be sent.
        verify(poll["endpoint"])
        verify(payload["login"])
        return Start(
            server_url=server_url,
            browser_url=payload["login"],
            poll_endpoint=poll["endpoint"],  # rule 1 — kept whole, never rebuilt
            poll_token=poll["token"],
        )
    except (KeyError, TypeError) as error:
        raise LoginError(f"{server_url} answered something that is not a Nextcloud login: {payload!r}") from error


def poll_once(start: Start, verify=trust_any) -> Credential | None:  # noqa: ANN001
    """One poll. None means "not yet" — which is *all* a 404 can be taken to mean (rule 2)."""
    body = urllib.parse.urlencode({"token": start.poll_token}).encode()
    try:
        with _open(
            start.poll_endpoint, body, {"Content-Type": "application/x-www-form-urlencoded"}
        ) as response:
            payload = json.loads(response.read())
    except urllib.error.HTTPError as error:
        if error.code == _NOT_FOUND:
            return None
        raise LoginError(f"The server answered HTTP {error.code} while waiting for approval.") from error
    except Exception:  # noqa: BLE001
        # ⚠️ Deliberately swallowed. A dropped connection halfway through is not a failure of the
        # login — the token is still live on the server — and reporting "connection reset" to
        # somebody who is mid-approval in a browser is noise about something that fixed itself.
        # The client-side deadline is what ends this loop.
        return None

    # ⛔ The `server` field becomes this account's PERMANENT base URL, so it is the highest-value
    # of the three: an internal host here is authenticated SSRF on every future request, for ever.
    settled = normalise_server(payload["server"])
    verify(settled)
    return Credential(
        # ⚠️ The **server's** idea of its own URL wins. It is what the app password was issued
        # against, and it can differ from what was typed — a trailing `/nextcloud`, or a canonical
        # host behind a redirect. Which is exactly why it is verified above rather than trusted.
        server_url=settled,
        login_name=payload["loginName"],
        uid="",
        app_password=payload["appPassword"],
    )


def fetch_uid(credential: Credential) -> str | None:
    """Rule 6 — `/ocs/v2.php/cloud/user` → `ocs.data.id`, the WebDAV path segment.

    Returns None when the server could not be asked, which is recoverable: the credential has
    already been written and the uid can be filled in later.
    """
    token = base64.b64encode(
        f"{credential.login_name}:{credential.app_password}".encode()
    ).decode()
    url = f"{credential.server_url}/ocs/v2.php/cloud/user?format=json"
    request = urllib.request.Request(url, method="GET")
    request.add_header("Authorization", f"Basic {token}")
    request.add_header("OCS-APIRequest", "true")
    request.add_header("User-Agent", USER_AGENT)
    try:
        with urllib.request.urlopen(request, timeout=30, context=_tls_for(url)) as response:
            return json.loads(response.read())["ocs"]["data"]["id"]
    except Exception:  # noqa: BLE001
        return None


def await_approval(start: Start, persist, on_wait=None, now=time.monotonic, verify=trust_any) -> Credential:  # noqa: ANN001
    """Poll until approved or the clock runs out.

    ⚠️ `persist` is called **before this returns**, and twice — see rule 3. A caller that only
    honours the second call has put back the window in which the app password exists on the server
    and nowhere else.
    """
    deadline = now() + LIFETIME_SECONDS
    while True:
        credential = poll_once(start, verify)
        if credential is not None:
            persist(credential)  # rule 3: write it the instant it arrives, uid still empty
            uid = fetch_uid(credential)
            if uid is None:
                # Not fatal. The password is safely stored; the uid can be resolved later.
                return credential
            resolved = Credential(credential.server_url, credential.login_name, uid, credential.app_password)
            persist(resolved)
            return resolved

        if now() >= deadline:
            raise LoginError(
                "Timed out waiting for approval.\n\n"
                "If you did approve it in the browser, the likeliest cause is that this server "
                "restricts which clients may use the login flow "
                "(`core.login_flow_v2.allowed_user_agents`). The browser shows a 403 in that case "
                f'while this side just waits — ask the administrator to allow "{USER_AGENT}".'
            )
        if on_wait is not None:
            on_wait(int(deadline - now()))
        time.sleep(POLL_INTERVAL_S)


def run(server_url: str, persist, open_browser: bool = True, out=sys.stderr, verify=trust_any) -> Credential:  # noqa: ANN001
    """The whole flow, with the messages a person needs while it is happening."""
    normalised = normalise_server(server_url)
    require_secure(normalised)

    out.write(f"\n  Asking {normalised} to start a login...\n")
    start = begin(normalised, verify)

    out.write("\n  Your browser should open. Sign in there as you normally would.\n")
    out.write("  If it does not open, go to:\n\n")
    out.write(f"    {start.browser_url}\n\n")
    out.write("  Waiting for you to approve it. Ctrl-C to give up.\n")

    if open_browser:
        try:
            webbrowser.open(start.browser_url)
        except Exception:  # noqa: BLE001
            pass  # The URL is printed above; a headless machine is a normal case here.

    ticks = {"n": 0}

    def waiting(seconds_left: int) -> None:
        ticks["n"] += 1
        if ticks["n"] % 10 == 0:  # every ~30s
            out.write(f"  still waiting ({seconds_left // 60} min left)...\n")
            out.flush()

    credential = await_approval(start, persist, on_wait=waiting, verify=verify)

    out.write(f"\n  Signed in as {credential.login_name}")
    if credential.uid and credential.uid != credential.login_name:
        # ⚠️ Worth showing: SPEC §0.1 #3, and the difference is invisible until a path is wrong.
        out.write(f" (uid {credential.uid}, which is what WebDAV paths use)")
    elif not credential.uid:
        out.write(" — but the uid could not be fetched; it will be resolved on the next run")
    out.write(".\n")
    return credential
