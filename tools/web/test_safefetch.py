#!/usr/bin/env python3
"""The SSRF defence, tested as an attacker rather than as a user.

    python tools/web/test_safefetch.py

Every case below is an address or a URL somebody would supply **on purpose**. A pass means the
attack does not work; there are almost no happy-path assertions here, because the happy path is
"a normal Nextcloud URL works" and that is what every other suite already exercises.

⚠️ The addresses are grouped by *why* they are dangerous rather than by family, because the
grouping is the lesson: six of them are `is_private == False`, and four of those are
`is_global == True`. A gate written as `if ip.is_private: refuse` passes all ten.
"""

from __future__ import annotations

import ipaddress
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import safefetch  # noqa: E402

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


def blocked(address: str) -> bool:
    try:
        safefetch.check_ip(ipaddress.ip_address(address))
    except safefetch.Blocked:
        return True
    return False


#: `(address, why it matters)`. The comment is the point of the test.
HOSTILE = [
    ("169.254.169.254", "cloud instance metadata — the classic SSRF prize"),
    ("127.0.0.1", "loopback"),
    ("10.0.0.1", "RFC1918"),
    ("192.168.1.1", "RFC1918"),
    ("172.16.0.1", "RFC1918"),
    ("100.64.0.1", "⛔ CGNAT — is_private False AND is_global False, in neither table"),
    ("192.88.99.1", "⛔ 6to4 relay anycast — is_private False"),
    ("224.0.0.1", "⛔ IPv4 multicast — is_private False, is_global True"),
    ("198.18.0.1", "benchmarking range"),
    ("0.0.0.0", "unspecified"),
    ("255.255.255.255", "broadcast"),
    ("::1", "IPv6 loopback"),
    ("fc00::1", "unique-local"),
    ("fe80::1", "link-local"),
    ("fec0::1", "⛔ deprecated site-local — is_private False, is_global True"),
    ("ff05::1", "⛔ IPv6 multicast — is_global True"),
    ("5f00::1", "⛔ SRv6 — is_global True in CPython 3.14; IANA says otherwise"),
    ("64:ff9b::a9fe:a9fe", "⛔ NAT64 well-known prefix wrapping the metadata address"),
    ("::ffff:169.254.169.254", "v4-mapped metadata"),
    ("::ffff:0:a9fe:a9fe", "⛔ SIIT v4-translated — `ipv4_mapped` returns None for this form"),
    ("::169.254.169.254", "deprecated v4-compatible"),
    ("2002:a9fe:a9fe::", "6to4 wrapping the metadata address"),
    ("2001:0:53aa:64c:0:ffff:5e1e:5e1e", "Teredo, which embeds an IPv4"),
    ("2001:db8::1", "documentation range"),
]

#: Addresses that must keep working, or the service refuses every real Nextcloud.
LEGITIMATE = [
    ("1.1.1.1", "an ordinary public address"),
    ("93.184.216.34", "an ordinary public address"),
    ("2606:4700::1111", "an ordinary public IPv6 address"),
]

#: ⚠️ `192.0.0.9` and `192.0.0.10` (PCP and NAT64 anycast) are genuinely `is_global == True` —
#: real global addresses carved out of `192.0.0.0/24` — and this **still blocks them**, because
#: the whole `/24` is IETF protocol assignments and nobody serves a Nextcloud from one. That is a
#: deliberate over-block rather than an oversight: the cost of refusing them is zero and the cost
#: of a hole in a protocol range is not. The first version of this file listed `192.0.0.9` as
#: "must allow" on the strength of it being globally routable, which is true and irrelevant.


def main() -> int:  # noqa: C901
    section("⛔ addresses that must never be reachable")
    for address, why in HOSTILE:
        check(f"{address} — {why}", blocked(address))

    section("…and addresses that must still work")
    for address, why in LEGITIMATE:
        check(f"{address} — {why}", not blocked(address),
              "blocking this would refuse a real Nextcloud")

    section("⚠️ the name is canonicalised once, before anything is decided")
    for given, want, why in (
        ("０１７７.0.0.1", "0177.0.0.1", "fullwidth digits fold to octal, which glibc reads as 127.0.0.1"),
        ("ⓁⓄⒸⒶⓁℋⓄⓈⓉ", "localhost", "circled letters NFKC-fold to a real hostname"),
        ("127.0.0.1.", "127.0.0.1", "a trailing dot resolves but is not an IP literal to `ipaddress`"),
        ("[::1]", "::1", "brackets are stripped"),
        ("EXAMPLE.com", "example.com", "case is folded"),
    ):
        got = safefetch.canonical_host(given)
        check(f"{given!r} -> {want!r} — {why}", got == want, f"got {got!r}")

    check(
        "⚠️ …and the canonical form is then judged, so the fullwidth trick is caught",
        blocked(safefetch.canonical_host("１２７.0.0.1")) if _is_literal("127.0.0.1") else True,
        "the point of canonicalising first is that no decision is keyed on the original text",
    )

    section("URLs that must be refused outright")
    for url, why in (
        ("http://example.com/", "plain HTTP — the credential would be readable in transit"),
        ("ftp://example.com/", "urllib's redirect handler still permits ftp; this does not"),
        ("https://user:pw@example.com/", "userinfo makes a log line lie about the destination"),
        ("https://example.com:6379/", "Redis. Every widened port is an internal service"),
        ("https://example.com:22/", "SSH"),
        ("https://169.254.169.254/", "metadata by literal address"),
        ("https:///nopath", "no host at all"),
    ):
        refused = False
        try:
            safefetch.check_url(url)
        except safefetch.Blocked:
            refused = True
        check(f"{url} — {why}", refused)

    section("⛔ the second-order SSRF: URLs that arrive FROM the server")
    # A user signs up with a real domain, valid certificate, public address — passing every check
    # above — and their server answers the login-flow start with these. Neither is a redirect, so
    # no redirect defence fires; neither is the URL the user typed, so input validation does not
    # fire either. This is the hole that `login.verify` closes.
    for url, why in (
        ("https://169.254.169.254/latest/meta-data/iam/security-credentials/",
         "`poll.endpoint` — this client is about to POST here"),
        ("https://10.0.0.5/", "the `server` field, which becomes the account's PERMANENT base URL"),
        ("https://[::1]/", "loopback by the bracketed form"),
    ):
        refused = False
        try:
            safefetch.check_url(url)
        except safefetch.Blocked:
            refused = True
        check(f"{why}", refused,
              "an internal host in the `server` field is authenticated SSRF on every future "
              "request, for the life of the account")

    section("⚠️ every resolved address must pass, not just the first")
    # A resolver returning [public, 127.0.0.1] is how a filter-and-pick-the-good-one implementation
    # gets connected to the wrong one on a later query. `resolve_and_validate` refuses the host.
    real_getaddrinfo = safefetch.socket.getaddrinfo
    safefetch.socket.getaddrinfo = lambda *a, **k: [  # noqa: ARG005
        (2, 1, 6, "", ("93.184.216.34", 443)),
        (2, 1, 6, "", ("127.0.0.1", 443)),
    ]
    try:
        mixed_refused = False
        try:
            safefetch.resolve_and_validate("split-horizon.example", 443)
        except safefetch.Blocked:
            mixed_refused = True
        check("⛔ a host resolving to one public AND one private address is refused",
              mixed_refused,
              "picking the acceptable one is winnable on a later lookup")
    finally:
        safefetch.socket.getaddrinfo = real_getaddrinfo

    section("the pinned connection keeps the hostname for TLS")
    connection = safefetch.PinnedHTTPSConnection(
        "nextcloud.example.com", 443, pinned_ip="93.184.216.34",
        context=safefetch._context(), timeout=5,  # noqa: SLF001
    )
    check(
        "⚠️ `host` stays the NAME, so SNI and certificate verification are unchanged",
        connection.host == "nextcloud.example.com",
        "connecting with host=IP would make the certificate check compare against the address, "
        "which is how pinning usually breaks TLS instead of DNS rebinding",
    )
    check("…while the socket goes to the validated address",
          connection._pinned_ip == "93.184.216.34")  # noqa: SLF001
    check("verification is on and not overridable here",
          safefetch._context().verify_mode == safefetch.ssl.CERT_REQUIRED)  # noqa: SLF001
    connection.close()

    section("sensitive headers are dropped on redirect")
    check("Authorization, Cookie and Proxy-Authorization are all in the drop set",
          {"authorization", "cookie", "proxy-authorization"} <= safefetch.SENSITIVE_HEADERS,
          "urllib strips only content-length and content-type, with no host comparison at all - "
          "which leaks the credential to whatever the redirect names")

    print(f"\n{passed}/{passed + failed} passed")
    return 1 if failed else 0


def _is_literal(value: str) -> bool:
    try:
        ipaddress.ip_address(value)
    except ValueError:
        return False
    return True


if __name__ == "__main__":
    raise SystemExit(main())
