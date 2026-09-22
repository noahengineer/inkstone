#!/usr/bin/env python3
"""Why a TLS handshake failed, and whether refusing it is protecting anybody.

⛔ **This file relaxes a certificate check, so read the reasoning before changing it.**

## What happened

Python 3.14's `ssl.create_default_context()` enables **`VERIFY_X509_STRICT`**, which enforces
RFC 5280 conformance on every certificate in the chain. Connecting to a perfectly ordinary
Nextcloud then failed with:

    certificate verify failed: Missing Authority Key Identifier

The chain turned out to be **two certificates**, not the usual three:

    [0] leaf   CN=*.shadow-chicken-server.com   issuer: …support@fortinet.com   no AKID
    [1] root   self-signed                      …support@fortinet.com           no AKID, no SKID

That is a **Fortinet device terminating TLS**, with its CA installed in the machine's trust store.
Either the user's own FortiGate sits in front of their Nextcloud, or a network appliance is
inspecting the traffic. Either way the connection is not end-to-end to Nextcloud, and that device
sees everything — including the app password the login flow is about to carry.

## Why the strict check is relaxed rather than honoured

Because refusing here protects nobody, and pretending otherwise would be the worse error.

* The CA is **already trusted by the operating system**. Every browser, `curl`, the Nextcloud
  desktop client and the Android app on that machine accept this chain today. Being the one
  program that refuses does not remove the interception; it removes the tool.
* The strict check is a **conformance** test, not a trust test. A missing Authority Key Identifier
  does not mean the certificate is forged — it means whoever generated it skipped an extension.
  Chrome, Firefox and OpenSSL's own defaults do not enforce it.
* The things that *do* mean "somebody is lying to you" — an untrusted issuer, a hostname mismatch,
  an expired certificate — are **still enforced**. [relaxed_context] clears exactly one flag.

⛔ **What is NOT done here, and must never be:** `CERT_NONE`, `check_hostname = False`, or trusting
a user-supplied CA. Those turn every refusal in `safefetch.py` into a readable oracle and would
make the credential exchange interceptable by anything on the path, not merely by a device the
machine's administrator already trusts.

## ⭐ The point is to say so, not to paper over it

Relaxing silently would be the actual mistake. [describe] reports what it found, and the reader
surfaces it: *"TLS to your Nextcloud is terminated by a device, not by Nextcloud itself."* A person
whose school firewall is reading their files should be told, once, in plain words — and a person
who owns that firewall will recognise it and stop worrying.
"""

from __future__ import annotations

import re
import socket
import ssl
import threading
from dataclasses import dataclass

#: Verification failures that are **conformance** complaints from `VERIFY_X509_STRICT` rather than
#: evidence of an untrustworthy peer. Only these are retried; everything else stands.
#:
#: ⚠️ Deliberately a small, explicit list. "Retry on any verification error" would silently accept
#: a self-signed certificate from an attacker, which is the whole thing this must not do.
STRICT_ONLY_FAILURES = (
    "missing authority key identifier",
    "missing subject key identifier",
    "invalid or inconsistent certificate extension",
    "authority and subject key identifier mismatch",
)


@dataclass(frozen=True)
class Diagnosis:
    ok: bool
    relaxed: bool = False
    """True when the handshake only succeeded with `VERIFY_X509_STRICT` cleared."""
    reason: str = ""
    issuer: str = ""
    chain_length: int = 0
    intercepted: bool = False
    """True when the chain looks like TLS termination by a local appliance rather than a public CA."""

    def warning(self) -> str:
        """One paragraph for a person, or empty when there is nothing to say."""
        if not self.relaxed:
            return ""
        who = f" by {self.issuer}" if self.issuer else ""
        if self.intercepted:
            return (
                f"TLS to this server is terminated{who}, not by Nextcloud itself — the certificate "
                "chain is signed by a certificate authority installed on this machine rather than "
                "a public one. That device can read everything on this connection, including your "
                "app password. If that is your own firewall, this is expected; if you did not set "
                "it up, whoever did can read your files."
            )
        return (
            f"This server's certificate{who} is not RFC-compliant (it is missing an Authority Key "
            "Identifier), so Python's strict check refused it. The certificate is trusted and the "
            "hostname matches, so the connection was allowed — but the server's certificate is "
            "worth fixing."
        )


def strict_context() -> ssl.SSLContext:
    """The platform default: full verification, including RFC 5280 conformance."""
    return ssl.create_default_context()


def relaxed_context() -> ssl.SSLContext:
    """Full verification **except** the conformance flag.

    ⚠️ `verify_mode` stays `CERT_REQUIRED` and `check_hostname` stays True. One flag is cleared,
    and the difference between this and `create_default_context()` is whether a certificate is
    rejected for a missing extension — not whether it is rejected for being untrusted, expired, or
    for the wrong host.
    """
    context = ssl.create_default_context()
    context.verify_flags &= ~ssl.VERIFY_X509_STRICT
    assert context.verify_mode == ssl.CERT_REQUIRED, "verification must stay on"
    assert context.check_hostname, "hostname checking must stay on"
    return context


def _is_strict_only(error: Exception) -> bool:
    text = str(error).lower()
    return any(reason in text for reason in STRICT_ONLY_FAILURES)


def _issuer_of(chain: list[bytes]) -> tuple[str, bool]:
    """A readable issuer for the top of the chain, and whether it looks like a local appliance.

    Parsing DER properly needs a library this project does not have, so this scrapes printable
    strings out of the root certificate — enough to name the vendor in a warning, and never used
    for a trust decision.
    """
    if not chain:
        return "", False
    root = chain[-1]
    words = [w.decode("ascii", "replace") for w in re.findall(rb"[\x20-\x7e]{4,}", root)]
    text = " ".join(words)
    # A public CA chain is normally three certificates; a two-certificate chain whose root is the
    # direct issuer of the leaf is the shape an interception proxy produces.
    appliance = any(
        vendor in text.lower()
        for vendor in ("fortinet", "fortigate", "zscaler", "bluecoat", "netskope", "palo alto",
                       "sophos", "sonicwall", "mcafee web gateway", "forcepoint", "mitmproxy",
                       "charles proxy", "fiddler", "kaspersky", "eset", "bitdefender", "avast")
    )
    name = ""
    for candidate in words:
        if "@" in candidate or "CA" in candidate or "Inc" in candidate or "Ltd" in candidate:
            # ⚠️ Trim DER framing off the end. These strings are scraped out of binary, so a
            # printable run usually carries a stray length byte or two after the value — which is
            # why the first version told the user their traffic was intercepted by
            # "support@fortinet.com0". Right, and it looks like a bug in the warning.
            name = candidate.strip().rstrip("0123456789").strip(" .,;:-")
            break
    return name, appliance or len(chain) <= 2


def describe(host: str, port: int = 443, timeout: float = 15.0) -> Diagnosis:
    """Handshake once and report. Never raises for a TLS problem — it returns one."""
    try:
        with socket.create_connection((host, port), timeout=timeout) as raw:
            with strict_context().wrap_socket(raw, server_hostname=host):
                return Diagnosis(ok=True)
    except ssl.SSLCertVerificationError as error:
        if not _is_strict_only(error):
            # A genuine trust failure. It stands.
            return Diagnosis(ok=False, reason=str(error))
        strict_reason = str(error)
    except OSError as error:
        return Diagnosis(ok=False, reason=f"{type(error).__name__}: {error}")

    # Strict refused it for a conformance reason. Try again without that one flag, and look.
    try:
        with socket.create_connection((host, port), timeout=timeout) as raw:
            with relaxed_context().wrap_socket(raw, server_hostname=host) as tls:
                chain = list(tls.get_verified_chain() or [])
    except OSError as error:
        return Diagnosis(ok=False, reason=f"{type(error).__name__}: {error}")

    issuer, intercepted = _issuer_of(chain)
    return Diagnosis(
        ok=True,
        relaxed=True,
        reason=strict_reason,
        issuer=issuer,
        chain_length=len(chain),
        intercepted=intercepted,
    )


# ---------------------------------------------------------------------------
# The per-host decision, remembered
# ---------------------------------------------------------------------------

_lock = threading.Lock()
_decided: dict[str, Diagnosis] = {}


def context_for(host: str) -> ssl.SSLContext:
    """The strictest context that actually connects to `host`.

    Strict first, always. The relaxed one is only ever returned for a host that has been observed
    to fail strict verification for a conformance reason **and** to pass full verification
    otherwise — so a host that is simply untrusted keeps failing, as it should.
    """
    with _lock:
        known = _decided.get(host)
    if known is None:
        known = describe(host)
        with _lock:
            _decided[host] = known
    return relaxed_context() if known.ok and known.relaxed else strict_context()


def warnings() -> list[str]:
    """Everything worth telling the user about the connections made so far."""
    with _lock:
        return [d.warning() for d in _decided.values() if d.warning()]


def forget(host: str | None = None) -> None:
    with _lock:
        if host is None:
            _decided.clear()
        else:
            _decided.pop(host, None)
