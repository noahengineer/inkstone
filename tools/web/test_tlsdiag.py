#!/usr/bin/env python3
"""The one relaxed certificate check, and the proof that it relaxes only what it claims.

    python tools/web/test_tlsdiag.py

⛔ `tlsdiag.relaxed_context()` clears `VERIFY_X509_STRICT`. That is a security-relevant change, so
it needs a test that would fail if it ever became something worse — `CERT_NONE`, `check_hostname
= False`, or a bundled CA.

## ⚠️ Why the obvious test is worthless on the machine this was written on

The first attempt connected to `self-signed.badssl.com` and asserted it was refused. It was
**accepted** — not because the context was broken, but because a Fortinet appliance intercepts
every TLS connection from that machine and re-signs it with a CA the machine trusts. badssl.com's
whole point is defeated by the middlebox: the "self-signed" site arrives with a valid trusted
certificate.

That is this repo's most repeated lesson in a new place — *the instrument was inside the thing
being measured*. Any test here that depends on a remote site presenting a **bad** certificate is
untrustworthy on an intercepted network, and there is no way for the test to know.

## What is tested instead

The property is checked **locally**, where no middlebox can interfere:

* the flags on the context itself (`CERT_REQUIRED`, `check_hostname`, and only the one flag off);
* a handshake against a context whose CA store deliberately does **not** contain the signer — if
  verification is live, that must fail for every host, and it does. That is a statement about
  the verification machinery rather than about any particular certificate, so an interception
  proxy cannot make it pass.
"""

from __future__ import annotations

import os
import socket
import ssl
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import tlsdiag  # noqa: E402

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


# A real, unrelated root (ISRG Root X1): a valid CA that signs nothing we will connect to.
UNRELATED_ROOT = """-----BEGIN CERTIFICATE-----
MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw
TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh
cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4
WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu
ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY
MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc
h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+
0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U
A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW
T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH
B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC
B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv
KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn
OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn
jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw
qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI
rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV
HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq
hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL
ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ
3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK
NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5
ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur
TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC
jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc
oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq
4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA
mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d
emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=
-----END CERTIFICATE-----"""


def main() -> int:
    section("the flags, checked locally — no network, nothing to intercept")
    relaxed = tlsdiag.relaxed_context()
    strict = tlsdiag.strict_context()

    check("⛔ verification stays REQUIRED", relaxed.verify_mode == ssl.CERT_REQUIRED,
          f"got {relaxed.verify_mode}; CERT_NONE here would accept any certificate at all")
    check("⛔ hostname checking stays on", relaxed.check_hostname is True,
          "without this, a valid certificate for any host is accepted for every host")
    check("the one flag under discussion is off",
          not (relaxed.verify_flags & ssl.VERIFY_X509_STRICT))
    check("…and it is on in the strict context, so the two really differ",
          bool(strict.verify_flags & ssl.VERIFY_X509_STRICT),
          "if Python ever stops enabling it by default, tlsdiag becomes a no-op and this test says so")
    check(
        "⚠️ ONLY that flag differs between the two contexts",
        (strict.verify_flags ^ relaxed.verify_flags) == ssl.VERIFY_X509_STRICT,
        f"strict={strict.verify_flags} relaxed={relaxed.verify_flags} — anything else in the "
        "difference is a relaxation nobody agreed to",
    )

    section("verification is genuinely enforced, proved without trusting the network")
    # ⚠️ The CA store deliberately lacks the signer. Every host must fail. An interception proxy
    # cannot make this pass, because it would have to produce a chain to a root we did not load.
    for host in ("nextcloud.shadow-chicken-server.com", "www.google.com"):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.verify_mode = ssl.CERT_REQUIRED
        context.check_hostname = True
        context.verify_flags &= ~ssl.VERIFY_X509_STRICT
        context.load_verify_locations(cadata=UNRELATED_ROOT)
        refused = False
        reachable = True
        try:
            with socket.create_connection((host, 443), timeout=15) as raw:
                with context.wrap_socket(raw, server_hostname=host):
                    refused = False
        except ssl.SSLCertVerificationError:
            refused = True
        except OSError:
            reachable = False
        if not reachable:
            print(f"  SKIP  {host} unreachable — inconclusive, not a pass")
            continue
        check(
            f"⛔ {host} is refused when its signer is not in the CA store",
            refused,
            "if this is accepted, clearing VERIFY_X509_STRICT has disabled verification itself",
        )

    section("the strict-only allowlist is narrow")
    check("a missing AKID is treated as a conformance complaint",
          tlsdiag._is_strict_only(Exception("certificate verify failed: Missing Authority Key Identifier")))  # noqa: SLF001
    for genuine in (
        "certificate verify failed: self-signed certificate in certificate chain",
        "certificate verify failed: certificate has expired",
        "certificate verify failed: Hostname mismatch, certificate is not valid for 'x'",
        "certificate verify failed: unable to get local issuer certificate",
    ):
        check(
            f"⛔ NOT retried: {genuine.split(':')[-1].strip()[:44]}",
            not tlsdiag._is_strict_only(Exception(genuine)),  # noqa: SLF001
            "retrying on any verification error would accept an attacker's certificate",
        )

    print(f"\n{passed}/{passed + failed} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
