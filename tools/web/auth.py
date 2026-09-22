#!/usr/bin/env python3
"""Who is allowed to read your lessons, once this stops being a localhost tool.

`server.py`'s original argument was that on `127.0.0.1` the audience is programs already running
as this user — the same audience that can read `watcher.local.properties` anyway — so a token would
protect nothing. That argument is sound and it **does not survive being put on a server**. This
process holds a Nextcloud app password and hands out recordings of a room full of identifiable
people, so on a public address the question stops being "is this tidy" and becomes the whole
design.

## What this is, and what it deliberately is not

**It is:** two shared secrets, a session cookie, a rate limiter, and an origin check.

**It is not an accounts system.** No user table, no password reset, no registration — those are the
largest attack surface available and there is exactly one user. One passphrase, compared in
constant time, exchanged for an opaque session id.

## The two secrets are separate on purpose

| | `INKSTONE_WEB_PASSWORD` | `INKSTONE_MCP_TOKEN` |
|---|---|---|
| Used by | a person, in a browser | an MCP client, in a header |
| Lives | in your head / password manager | in a config file, on every machine you use Claude from |
| Exposure | typed, once a session | at rest, on several devices, possibly synced |

They have different blast radii, so rotating one must not force the other. Sharing one value would
mean a leaked MCP token — the one that sits in files — also logs somebody into the website.

## ⚠️ Why the browser gets a cookie and not a token in the URL

`?token=…` was fine for a loopback dev server and is not fine here. A URL leaks into browser
history, into `Referer` on any outbound link, into the reverse proxy's access log, and onto the
screen. The cookie is `HttpOnly` (JavaScript cannot read it, so an XSS bug cannot exfiltrate the
session), `Secure` (never sent over plain HTTP), and `SameSite=Strict` (another site cannot cause
your browser to make an authenticated request — the same class of attack the Host check below is
about, from a different direction).

## ⚠️ The rate limiter is not optional, and the irony is the point

CLAUDE.md rule 5 exists because *"auto-retrying credentials is the documented way real users get
IP-blocked by their own server"*. A login form with no limiter is that hazard rebuilt in our own
front door, aimed at us. Failed attempts are counted per client and answered with a growing delay;
successes clear the count.

## Sessions live in memory, and that is a feature

A restart logs you out. There is no session store to corrupt, no signing key to leak, and no
"revoke all sessions" button to write — `docker restart` is that button.
"""

from __future__ import annotations

import hmac
import os
import secrets
import threading
import time
from dataclasses import dataclass, field

#: ⭐ The `__Host-` prefix, when cookies are `Secure`.
#:
#: It is not decoration: the **browser enforces** it. A cookie whose name starts with `__Host-` is
#: rejected outright unless it is `Secure`, has `Path=/`, and carries **no `Domain`** — which makes
#: the insecure variants unrepresentable rather than merely discouraged, and in particular stops a
#: sibling subdomain from setting a session cookie for this one.
#:
#: The unprefixed name is used only when `Secure` is off, which is the loopback development case,
#: because a `__Host-` cookie over plain HTTP is silently discarded and the login would appear to
#: succeed and never stick.
COOKIE_NAME = "inkstone_session"
SECURE_COOKIE_NAME = "__Host-inkstone_session"

#: Host values that always pass, whatever `allowed_hosts` says. See [Guard.host_ok].
LOOPBACK_HOSTS = ("127.0.0.1", "localhost", "::1", "")

SESSION_TTL_S = 30 * 24 * 3600
"""A month. This is a reading tool for your own notes, not a bank; being logged out of your own
lecture archive every week is friction with no attacker on the other side of it."""

# The limiter. Deliberately gentle on a person who mistypes and steep on a program that does not.
LOCKOUT_AFTER = 5
LOCKOUT_BASE_S = 2.0
LOCKOUT_MAX_S = 300.0


@dataclass(frozen=True)
class Decision:
    """What the request may do. `challenge` is set when the caller should be told how to fix it."""

    ok: bool
    reason: str = ""
    status: int = 401
    challenge: str = ""
    """The `WWW-Authenticate` value, when one belongs on the response."""


ALLOW = Decision(ok=True)


@dataclass
class Guard:
    web_password: str = ""
    mcp_token: str = ""
    allowed_hosts: tuple[str, ...] = ()
    """Host header values this server answers to. Empty means loopback only."""
    secure_cookies: bool = True
    trust_proxy: bool = False
    """Whether to believe `X-Forwarded-For`. Only ever set this when a proxy you control is in
    front, because otherwise a client can forge its own address and defeat the rate limiter."""

    _sessions: dict[str, float] = field(default_factory=dict)
    _failures: dict[str, tuple[int, float]] = field(default_factory=dict)
    _lock: threading.Lock = field(default_factory=threading.Lock)

    # -- configuration ----------------------------------------------------

    @classmethod
    def from_env(cls, *, loopback: bool) -> Guard:
        hosts = tuple(
            h.strip().lower()
            for h in (os.environ.get("INKSTONE_ALLOWED_HOSTS") or "").split(",")
            if h.strip()
        )
        # `from_env` also accepts `INKSTONE_WEB_PASSWORD_FILE` / `INKSTONE_MCP_TOKEN_FILE`, so
        # these can be Docker secrets rather than environment variables. See `config.from_env`.
        from config import from_env  # noqa: PLC0415

        return cls(
            web_password=from_env("INKSTONE_WEB_PASSWORD"),
            mcp_token=from_env("INKSTONE_MCP_TOKEN"),
            allowed_hosts=hosts,
            # ⚠️ `Secure` cookies are not sent over plain HTTP, so a container behind a
            # TLS-terminating proxy must still set this (the browser's view is HTTPS) while a
            # loopback dev run must not, or the login silently never sticks.
            secure_cookies=os.environ.get("INKSTONE_INSECURE_COOKIES", "") != "1",
            trust_proxy=os.environ.get("INKSTONE_TRUST_PROXY", "") == "1",
        )

    @property
    def enforcing(self) -> bool:
        """True once either secret is set. With neither, this is the old loopback tool."""
        return bool(self.web_password or self.mcp_token)

    def describe(self) -> str:
        if not self.enforcing:
            return "open (loopback only)"
        parts = []
        if self.web_password:
            parts.append("web password")
        if self.mcp_token:
            parts.append("MCP token")
        return " + ".join(parts)

    # -- the checks -------------------------------------------------------

    def client_of(self, address: str, headers) -> str:  # noqa: ANN001
        """Who to count failures against.

        ⚠️ `X-Forwarded-For` is only believed when `trust_proxy` is set. It is a request header
        like any other, so a limiter that always trusts it lets a caller invent a fresh identity
        per attempt and is therefore no limiter at all.
        """
        if self.trust_proxy:
            forwarded = headers.get("X-Forwarded-For")
            if forwarded:
                return forwarded.split(",")[0].strip()
        return address

    def host_ok(self, headers) -> bool:  # noqa: ANN001
        """⚠️ DNS rebinding, and the MCP specification's own requirement for HTTP transports.

        A page on any website can point a hostname it controls at this server's address and then
        fetch it from the victim's browser. The bind address never stopped that; the `Host` header
        is what separates "somebody typed this in" from "a page reached in".

        Behind a proxy the Host is the public name, so `INKSTONE_ALLOWED_HOSTS` has to be set —
        and when it is, only those names are answered.
        """
        host = (headers.get("Host") or "").rsplit(":", 1)[0].strip("[]").lower()
        if host in LOOPBACK_HOSTS:
            # ⚠️ Loopback is ALWAYS accepted, even when `allowed_hosts` names a public site.
            #
            # This is not a hole: a browser sends the name it navigated to, so a rebinding attack
            # aimed at this server arrives claiming the attacker's own hostname, never `127.0.0.1`.
            # A request that says loopback came from something addressing loopback directly.
            #
            # It is also load-bearing. The container's HEALTHCHECK probes itself over 127.0.0.1,
            # and without this line setting INKSTONE_ALLOWED_HOSTS=notes.example.com makes every
            # probe a 403 — so the container reports unhealthy forever, for a reason that appears
            # nowhere except in a header nobody thinks to look at.
            return True
        return host in self.allowed_hosts if self.allowed_hosts else False

    def origin_ok(self, headers) -> bool:  # noqa: ANN001
        """Cross-origin requests are refused outright.

        Nothing here is meant to be embedded in another site, and `SameSite=Strict` already stops
        the cookie riding along — this is the second lock, and the one the MCP spec asks for by
        name. A request with no `Origin` (a normal navigation, curl, an MCP client) is fine; one
        naming somebody else's site is not.
        """
        origin = headers.get("Origin")
        if not origin:
            return True
        host = (headers.get("Host") or "").lower()
        try:
            from urllib.parse import urlsplit  # noqa: PLC0415

            netloc = urlsplit(origin).netloc.lower()
        except ValueError:
            return False
        if netloc == host:
            return True
        bare = netloc.rsplit(":", 1)[0].strip("[]")
        return bool(self.allowed_hosts) and bare in self.allowed_hosts

    def check(self, *, headers, address: str, for_mcp: bool) -> Decision:  # noqa: ANN001
        """The one gate every request goes through."""
        if not self.host_ok(headers):
            return Decision(
                False,
                "This server does not answer to that Host. Use its real name, or set "
                "INKSTONE_ALLOWED_HOSTS if it is behind a proxy.",
                status=403,
            )
        if not self.origin_ok(headers):
            # ⚠️ Name what was actually seen. A misconfigured reverse proxy — one that rewrites
            # `Host` to the upstream's name, which is nginx's *default* — produces exactly this
            # refusal, and a bare "cross-origin requests are refused" gives somebody nothing to
            # act on. The two values side by side make the mismatch obvious in one glance.
            return Decision(
                False,
                "Cross-origin requests are refused. "
                f"Origin was {headers.get('Origin')!r} and Host was {headers.get('Host')!r}; "
                "they must agree, or the Origin's host must be in INKSTONE_ALLOWED_HOSTS. "
                "If this is behind a proxy, it must pass the original Host header through.",
                status=403,
            )
        if not self.enforcing:
            return ALLOW

        # An MCP client sends a bearer token; it has no cookie jar and no login form.
        if self.mcp_token and self._bearer_ok(headers):
            return ALLOW
        if for_mcp:
            # ⛔ **403, and deliberately NOT 401 with a `WWW-Authenticate` challenge.**
            #
            # The MCP specification's own mechanism is a 401 carrying
            # `WWW-Authenticate: Bearer resource_metadata="…"`, which points a client at an OAuth
            # authorization server. This server has no OAuth and wants none: it has exactly one
            # user and one token, set as an environment variable.
            #
            # The reason this is not merely a preference is what Claude Code does with the
            # advertisement. A server that looks like an OAuth resource gets treated as one — and
            # a client that decides a server needs OAuth can **ignore a correctly configured
            # static bearer header**, report itself connected, and expose synthetic
            # `authenticate` tools instead of any of the real ones. That failure is worse than a
            # refusal because it looks like success.
            #
            # So: 403 (the spec's own code for "authenticated but not permitted", which is the
            # nearest honest fit), no `WWW-Authenticate`, and **no `/.well-known/oauth-*` routes
            # anywhere in this server** — their mere presence is enough to start the OAuth path.
            # Anyone adding an OAuth flow later must change all three together.
            return Decision(
                False,
                "This MCP endpoint needs an Authorization: Bearer <token> header. "
                "Set INKSTONE_MCP_TOKEN on the server and send the same value.",
                status=403,
            )

        if self.web_password and self._session_ok(headers):
            return ALLOW
        return Decision(False, "Sign in to read these notes.", status=401)

    def _bearer_ok(self, headers) -> bool:  # noqa: ANN001
        raw = headers.get("Authorization") or ""
        scheme, _, value = raw.partition(" ")
        if scheme.lower() != "bearer" or not value:
            return False
        return hmac.compare_digest(value.strip().encode("utf-8"), self.mcp_token.encode("utf-8"))

    def _session_ok(self, headers) -> bool:  # noqa: ANN001
        session = self.cookie_value(headers, self.cookie_name)
        if not session:
            return False
        with self._lock:
            expiry = self._sessions.get(session)
            if expiry is None:
                return False
            if expiry < time.time():
                del self._sessions[session]
                return False
        return True

    @staticmethod
    def cookie_value(headers, name: str) -> str:  # noqa: ANN001
        """One cookie, parsed by hand.

        `http.cookies.SimpleCookie` raises on a malformed jar, and a browser holding one unrelated
        broken cookie would then be unable to log in at all — a failure with no visible cause.
        """
        for part in (headers.get("Cookie") or "").split(";"):
            key, _, value = part.strip().partition("=")
            if key == name:
                return value
        return ""

    # -- signing in -------------------------------------------------------

    def attempt(self, password: str, client: str) -> tuple[bool, str, float]:
        """Try a password. Returns `(ok, session_id, retry_after_seconds)`."""
        with self._lock:
            count, until = self._failures.get(client, (0, 0.0))
            now = time.time()
            if now < until:
                return False, "", until - now

        ok = bool(self.web_password) and hmac.compare_digest(
            password.encode("utf-8"), self.web_password.encode("utf-8")
        )

        with self._lock:
            if not ok:
                count += 1
                delay = 0.0
                if count >= LOCKOUT_AFTER:
                    # Doubling, capped. Five free tries covers a mistyped passphrase; a program
                    # working through a list hits minutes per attempt within a dozen.
                    delay = min(LOCKOUT_BASE_S * (2 ** (count - LOCKOUT_AFTER)), LOCKOUT_MAX_S)
                self._failures[client] = (count, time.time() + delay)
                return False, "", delay
            self._failures.pop(client, None)
            session = secrets.token_urlsafe(32)
            self._sessions[session] = time.time() + SESSION_TTL_S
            self._sweep()
        return True, session, 0.0

    def sign_out(self, headers) -> None:  # noqa: ANN001
        session = self.cookie_value(headers, self.cookie_name)
        if session:
            with self._lock:
                self._sessions.pop(session, None)

    def _sweep(self) -> None:
        """Drop expired sessions. Called under the lock, on the rare path."""
        now = time.time()
        for key in [k for k, v in self._sessions.items() if v < now]:
            del self._sessions[key]

    @property
    def cookie_name(self) -> str:
        """`__Host-`-prefixed when the cookie will be `Secure`. See the constant's note."""
        return SECURE_COOKIE_NAME if self.secure_cookies else COOKIE_NAME

    def cookie_header(self, session: str, *, clear: bool = False) -> str:
        bits = [
            f"{self.cookie_name}={'' if clear else session}",
            # `Path=/` and no `Domain` are required by the `__Host-` prefix, and are correct
            # without it too — this service occupies its whole origin.
            "Path=/",
            "HttpOnly",
            "SameSite=Strict",
            f"Max-Age={0 if clear else SESSION_TTL_S}",
        ]
        if self.secure_cookies:
            bits.append("Secure")
        return "; ".join(bits)
