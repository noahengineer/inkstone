#!/usr/bin/env python3
"""Outbound HTTPS to an address a stranger chose, without becoming their proxy.

The single-user reader talks to one Nextcloud: yours. The moment anybody can sign in with **any**
Nextcloud URL, this server makes HTTP requests to whatever they type — which is Server-Side Request
Forgery, and the reason a service like this gets its cloud credentials stolen. Someone signs up
with `http://169.254.169.254/` and the server fetches its own instance metadata for them.

Everything here was verified on CPython 3.14.5 rather than recalled, and then attacked.

## ⛔ Three things `ipaddress` gets wrong, each of which is a hole on its own

`is_private` is **not** an SSRF gate. Measured:

| Address | `is_private` | `is_global` | |
|---|---|---|---|
| `100.64.0.1` (CGNAT) | False | False | in neither table |
| `192.88.99.1` (6to4 relay) | False | **True** | |
| `fec0::1` (site-local) | False | **True** | |
| `224.0.0.1`, `ff05::1` (multicast) | False | **True** | needs `is_multicast` |
| `64:ff9b::7f00:1` (NAT64) | False | **True** | IANA really does say globally reachable — and it
  embeds `127.0.0.1` |
| `5f00::1` (SRv6) | False | **True** | IANA says otherwise; CPython lags the registry |

So the gate is an explicit CIDR table **plus** `is_global and not is_multicast and not
is_reserved`, and every embedded-IPv4 form is unwrapped and re-judged as IPv4.

⚠️ `::ffff:0:0:0/96` is the nastiest of those: `ipv4_mapped` returns **None** for it, so the usual
"decode the embedded v4" remediation does not see it either. It is the misclassification behind
CVE-2026-54272. Only `is_reserved` catches it, and only by luck — so it is in the table explicitly.

## ⛔ DNS rebinding, and why validating then fetching is not enough

`HTTPConnection.connect()` resolves the hostname **again**, so a name whose record changes between
the check and the connection wins. The fix is to pin: resolve once, validate **every** answer,
then connect to that exact IP while leaving `self.host` as the hostname so SNI and certificate
verification still happen against the name. `_create_connection` is a per-instance attribute, which
makes that a three-line override rather than a reimplementation of `connect()`.

⚠️ **Every answer must pass, not just the one we use.** A resolver returning
`[public, 127.0.0.1]` is exactly how a filter-and-pick-the-good-one implementation gets you
connected to the wrong one on a later query.

## ⛔ The bug that is not about IP addresses at all

Login Flow v2 hands back **three more URLs in a JSON body** — `poll.endpoint`, `login`, and, after
approval, `server`. A user supplies `https://evil.tld/`: a real domain, valid certificate, public
address, passes every check above. Their server then replies with
`poll.endpoint = "https://169.254.169.254/latest/meta-data/…"`, and the client POSTs there. It is
not a redirect, so redirect defences never fire; it is not the URL the user typed, so input
validation never fires.

Worse: the poll response's `server` field becomes the account's stored base URL, so **every
WebDAV request for the life of that account** goes to an internal host **carrying the stored
credential**. A one-shot SSRF becomes a durable authenticated internal client.

⭐ **So the rule is: every URL that arrives over the network is validated exactly as hard as the
one the user typed.** `login.py` calls `check_url` on all three. That is the largest hole here and
the least obvious.

## ⚠️ What this cannot do, and why egress controls are the real answer

Two residual risks cannot be closed by any in-process filter:

* **NAT64 with an operator-specific prefix.** `2600:1f14:…::a9fe:a9fe` is a globally-routable
  address that a provider's gateway translates to `169.254.169.254`. It is indistinguishable from
  a legitimate address by inspection.
* **Internal services on globally-routable addresses** — peer VMs with public IPs, a university
  /16, a colo estate. IP classification cannot see a trust boundary.

If this is ever exposed publicly, the filter below is **defence in depth, not the defence**. The
control that actually closes those is at the network: run the container in an egress-restricted
network with no route to link-local, no route to the VPC, and no route to the metadata address.
`docs/WEB-READER.md` §10 says so in the deployment instructions rather than only here.
"""

from __future__ import annotations

import http.client
import ipaddress
import socket
import ssl
import time
from urllib.parse import urlsplit

#: Only HTTPS, and only 443. Every widened port is an internal service (6379, 11211, 9200…), and a
#: self-hosted Nextcloud on `:8443` is the pressure that will be applied to this — widen it to an
#: explicit small set if you must, never to "any port above 1024".
ALLOWED_SCHEMES = frozenset({"https"})
ALLOWED_PORTS = frozenset({443})

MAX_REDIRECTS = 3
MAX_RESPONSE_BYTES = 32 * 1024 * 1024
DEFAULT_DEADLINE_S = 30.0

#: Headers dropped on **every** redirect, same-host or not. Cheaper to reason about than an origin
#: comparison — and `evil.com` versus `evil.com.` makes origin comparison a string-normalisation
#: problem, which is the class of bug this whole module is about.
SENSITIVE_HEADERS = frozenset({"authorization", "cookie", "proxy-authorization"})

_BLOCKED_V4 = [
    ipaddress.ip_network(c)
    for c in (
        "0.0.0.0/8",          # this-network / unspecified
        "10.0.0.0/8",         # RFC1918
        "100.64.0.0/10",      # ⛔ CGNAT — is_private False AND is_global False
        "127.0.0.0/8",        # loopback
        "169.254.0.0/16",     # ⛔ link-local, and 169.254.169.254 is cloud metadata
        "172.16.0.0/12",      # RFC1918
        "192.0.0.0/24",       # IETF protocol assignments
        "192.0.2.0/24",       # TEST-NET-1
        "192.31.196.0/24",    # AS112-v4
        "192.52.193.0/24",    # AMT
        "192.88.99.0/24",     # ⛔ 6to4 relay anycast — is_private False
        "192.168.0.0/16",     # RFC1918
        "198.18.0.0/15",      # benchmarking
        "198.51.100.0/24",    # TEST-NET-2
        "203.0.113.0/24",     # TEST-NET-3
        "224.0.0.0/4",        # ⛔ multicast — is_private False, is_global True
        "240.0.0.0/4",        # reserved
        "255.255.255.255/32", # broadcast
    )
]

_BLOCKED_V6 = [
    ipaddress.ip_network(c)
    for c in (
        "::/128",             # unspecified
        "::1/128",            # loopback
        "::ffff:0:0/96",      # v4-mapped (delegates to the v4 table)
        "::ffff:0:0:0/96",    # ⛔ SIIT v4-translated — `ipv4_mapped` returns None for this one
        "::/96",              # deprecated v4-compatible
        "64:ff9b::/96",       # ⛔ NAT64 well-known prefix — is_global True, embeds any v4
        "64:ff9b:1::/48",     # NAT64 local-use
        "100::/64",           # discard-only
        "2001::/32",          # Teredo (embeds v4)
        "2001:20::/28",       # ORCHIDv2
        "2001:30::/28",       # drone remote id
        "2001:db8::/32",      # documentation
        "2002::/16",          # 6to4 (embeds v4)
        "3fff::/20",          # documentation (RFC 9637)
        "5f00::/16",          # ⛔ SRv6 SIDs — is_global True in 3.14.5; IANA disagrees
        "fc00::/7",           # unique-local
        "fe80::/10",          # link-local
        "fec0::/10",          # ⛔ deprecated site-local — is_private False
        "ff00::/8",           # ⛔ multicast — is_private False, is_global True
    )
]

_NAT64 = (ipaddress.ip_network("64:ff9b::/96"), ipaddress.ip_network("64:ff9b:1::/48"))
_SIIT = ipaddress.ip_network("::ffff:0:0:0/96")
_V4_COMPAT = ipaddress.ip_network("::/96")


class Blocked(Exception):
    """A destination was refused.

    ⚠️ Callers must show the **same** message to an end user whatever the cause. "Connection
    refused" versus "TLS alert" versus "certificate mismatch" versus "timeout" is a port scanner,
    and a hostname-mismatch error additionally discloses the internal certificate's subject —
    internal hostnames leaked without completing a request.
    """


def check_ip(ip: ipaddress.IPv4Address | ipaddress.IPv6Address) -> None:
    """Refuse anything that is not a safe public destination."""
    if isinstance(ip, ipaddress.IPv6Address):
        # Unwrap every form that carries an IPv4 inside it and judge that too. `ipv4_mapped`
        # covers only one of the five.
        embedded = ip.ipv4_mapped or ip.sixtofour or (ip.teredo[1] if ip.teredo else None)
        if embedded is None and (any(ip in n for n in _NAT64) or ip in _SIIT or ip in _V4_COMPAT):
            embedded = ipaddress.ip_address(int(ip) & 0xFFFFFFFF)
        if embedded is not None:
            check_ip(embedded)

    for network in _BLOCKED_V4 if ip.version == 4 else _BLOCKED_V6:
        if ip in network:
            raise Blocked(f"{ip} is in blocked range {network}")

    # The catch-all, for registry entries newer than this table. `is_private` is deliberately not
    # consulted: see the module docstring's table.
    if not ip.is_global or ip.is_multicast or ip.is_reserved or ip.is_loopback:
        raise Blocked(f"{ip} is not a global unicast address")


def canonical_host(host: str) -> str:
    """The one ASCII string that is validated, resolved, sent as SNI and put in `Host:`.

    ⚠️ Canonicalising **once, up front** is what makes the text-level tricks structural rather
    than a list to keep up with. Measured transforms that would otherwise smuggle a destination
    past a check keyed on the original text:

        ０１７７.0.0.1   → idna → 0177.0.0.1 → glibc reads 0177 as octal → 127.0.0.1
        ⓁⓄⒸⒶⓁℋⓄⓈⓉ    → idna → localhost
        169.254.169.254．→ idna → trailing-dot form, which glibc resolves

    The first defeats a text-based octal check *and* `ip_address()` in one input.
    """
    host = host.strip().strip("[]").rstrip(".")
    if not host:
        raise Blocked("empty host")
    if any(ord(c) > 127 for c in host):
        try:
            host = host.encode("idna").decode("ascii")
        except UnicodeError:
            raise Blocked("that host name cannot be encoded as ASCII") from None
    return host.lower()


def check_url(url: str) -> tuple[str, str, int, str]:
    """Validate a URL and resolve it. Returns `(host, pinned_ip, port, path)`.

    ⭐ **Call this on every URL that arrives over the network, not only on user input.** A server
    the user nominated can hand back further URLs, and those are exactly as attacker-controlled as
    the one they typed. See the module docstring.
    """
    parts = urlsplit(url)
    if parts.scheme not in ALLOWED_SCHEMES:
        raise Blocked(f"only {'/'.join(sorted(ALLOWED_SCHEMES))} is allowed, not {parts.scheme!r}")
    if parts.username or parts.password:
        # No legitimate use here, and userinfo is a classic way to make a log line lie about
        # which host was contacted.
        raise Blocked("a URL with a username or password in it is refused")
    if not parts.hostname:
        raise Blocked("that URL has no host")

    port = parts.port or 443
    if port not in ALLOWED_PORTS:
        raise Blocked(f"port {port} is not allowed")

    host = canonical_host(parts.hostname)
    pinned = resolve_and_validate(host, port)
    path = parts.path or "/"
    if parts.query:
        path = f"{path}?{parts.query}"
    return host, pinned, port, path


def resolve_and_validate(host: str, port: int) -> str:
    """Resolve once, refuse if **any** answer is blocked, return the address to pin to."""
    try:
        check_ip(ipaddress.ip_address(host))
        return host  # a literal: judged directly, never resolved
    except ValueError:
        pass
    except Blocked:
        raise

    try:
        infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM, proto=socket.IPPROTO_TCP)
    except socket.gaierror:
        raise Blocked("that host name could not be resolved") from None

    addresses = [info[4][0] for info in infos]
    if not addresses:
        raise Blocked("that host name resolved to nothing")
    for address in addresses:
        check_ip(ipaddress.ip_address(address.split("%")[0]))
    return addresses[0]


class PinnedHTTPSConnection(http.client.HTTPSConnection):
    """`host` stays the **hostname**, so SNI and certificate verification are unchanged; only the
    TCP destination is replaced with an address already validated.

    ⚠️ Never reachable through `urllib.request`: a `ProxyHandler` would let a proxy do the
    resolution and the pin would mean nothing. This is used directly, and `http.client` never
    consults `https_proxy`.
    """

    def __init__(self, host: str, port: int, *, pinned_ip: str, context, timeout: float) -> None:  # noqa: ANN001
        super().__init__(host, port, context=context, timeout=timeout)
        self._pinned_ip = pinned_ip
        # A per-instance attribute in CPython's http/client.py, so replacing it is enough:
        # `connect()` and its `wrap_socket(server_hostname=self.host)` are untouched.
        self._create_connection = self._connect_pinned

    def _connect_pinned(self, address, timeout, source_address):  # noqa: ANN001
        return socket.create_connection((self._pinned_ip, address[1]), timeout, source_address)


def _context() -> ssl.SSLContext:
    """The default context, with verification on. ⛔ Never turn this off.

    Certificate verification is what stops every refusal above from being a readable oracle:
    `https://127.0.0.1.nip.io/` resolves to loopback, but whatever answers there presents its own
    certificate and the handshake fails. The product pressure to add `verify=False` "for
    self-signed instances" would delete that in one line. If self-signed must ever be supported,
    it has to be per-account pinning of a fingerprint the user confirmed — never `CERT_NONE`, and
    never a user-supplied CA trusted for every host.
    """
    return ssl.create_default_context()


def fetch(
    url: str,
    *,
    method: str = "GET",
    headers: dict[str, str] | None = None,
    body: bytes | None = None,
    deadline_s: float = DEFAULT_DEADLINE_S,
    max_bytes: int = MAX_RESPONSE_BYTES,
) -> tuple[int, dict[str, str], bytes]:
    """One request, with redirects handled here rather than by `urllib`.

    ⚠️ The deadline is **one wall clock covering DNS, TCP, TLS, the status line and the body** —
    not a socket timeout re-applied per read. A hostile server that drips `HTTP/1.1 100 Continue`
    every few seconds keeps `HTTPResponse.begin()` looping for ever, before a single body byte
    exists, so a body-only deadline never fires and the worker is gone.
    """
    started = time.monotonic()
    outgoing = dict(headers or {})
    seen = url

    for _ in range(MAX_REDIRECTS + 1):
        remaining = deadline_s - (time.monotonic() - started)
        if remaining <= 0:
            raise Blocked("that request took too long")

        host, pinned, port, path = check_url(seen)
        connection = PinnedHTTPSConnection(
            host, port, pinned_ip=pinned, context=_context(), timeout=remaining
        )
        try:
            # ⚠️ Origin-form target. `putrequest` builds `Host:` from the URL text when handed an
            # absolute one, which would source it from a string that was never validated.
            connection.request(method, path, body=body, headers=outgoing)
            response = connection.getresponse()
            status = response.status
            received = {k.lower(): v for k, v in response.getheaders()}

            if status in (301, 302, 303, 307, 308) and received.get("location"):
                target = received["location"]
                if target.startswith("//"):
                    # ⚠️ Protocol-relative. A `startswith("https://")` check reads this as a path,
                    # and `//169.254.169.254/` is then a scheme downgrade in one hop.
                    raise Blocked("that redirect is not allowed")
                if not target.lower().startswith("https://"):
                    raise Blocked("that redirect is not allowed")
                # Every hop: drop the credentials, then re-validate from scratch.
                outgoing = {k: v for k, v in outgoing.items() if k.lower() not in SENSITIVE_HEADERS}
                if status == 303:
                    method, body = "GET", None
                seen = target
                continue

            payload = _read_capped(response, max_bytes, started, deadline_s)
            return status, received, payload
        finally:
            connection.close()

    raise Blocked("too many redirects")


def _read_capped(response, max_bytes: int, started: float, deadline_s: float) -> bytes:  # noqa: ANN001
    """Read a body with a hard ceiling and the same wall clock.

    A `Content-Length` check is not enough: a chunked response can claim nothing and stream for
    ever, and a `Content-Encoding: gzip` body can be a decompression bomb — which is why the
    caller refuses a non-identity encoding rather than decoding it here.
    """
    chunks: list[bytes] = []
    total = 0
    while True:
        if time.monotonic() - started > deadline_s:
            raise Blocked("that response took too long")
        chunk = response.read1(64 * 1024)
        if not chunk:
            break
        total += len(chunk)
        if total > max_bytes:
            raise Blocked("that response is too large")
        chunks.append(chunk)
    return b"".join(chunks)
