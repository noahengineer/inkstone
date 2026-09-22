#!/usr/bin/env python3
"""The container's `HEALTHCHECK`. Exit 0 healthy, 1 unhealthy.

A file rather than a `python -c` one-liner in the Dockerfile, because the one-liner needs a
Dockerfile line continuation inside a JSON string to stay under a sane line length — which works,
and is unreadable, and takes the whole build down if it is ever edited slightly wrong.

⚠️ **Exit 1 for unhealthy. Never exit 2** — Docker reserves it, and using it is documented as
undefined behaviour rather than a stronger "very unhealthy".

The image carries no `curl` and no `wget`; it does carry a Python interpreter, which is the whole
reason this is possible without adding a package.

## Why `/healthz` and not `/`

`/` proves the process is up. `/healthz` proves it can still reach **Nextcloud** — it makes
SPEC §7.2's confirmation call. A container whose app password has been revoked serves a perfectly
good page that can list nothing, and reporting that as healthy is how the failure stays invisible
for a week.

⚠️ One deliberate exception: a **401** is reported healthy-but-degraded rather than unhealthy, and
the reason is CLAUDE.md rule 5. Restarting the container on a dead credential means the reader
re-authenticates on every restart, and Docker's restart policy would turn that into a loop of
failed logins against the user's own server — the brute-force lockout, rebuilt out of a health
probe. The credential needs a person, so the container stays up and says so on its own screen.
"""

from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request

URL = "http://127.0.0.1:8765/healthz"
TIMEOUT_S = 4

HEALTHY = 0
UNHEALTHY = 1


def main() -> int:
    try:
        request = urllib.request.Request(URL, headers={"Host": "127.0.0.1"})
        with urllib.request.urlopen(request, timeout=TIMEOUT_S) as response:
            if response.status != 200:
                print(f"health returned HTTP {response.status}", file=sys.stderr)
                return UNHEALTHY
            payload = json.loads(response.read())
    except urllib.error.HTTPError as error:
        # 503 is `/healthz` itself saying the source is unreachable — the body says which kind.
        if error.code == 503:
            try:
                payload = json.loads(error.read())
            except Exception:  # noqa: BLE001
                payload = {}
            if payload.get("kind") == "reauth":
                print("Nextcloud credential needs replacing; serving, but nothing will list.",
                      file=sys.stderr)
                return HEALTHY
            print(f"source unreachable: {payload.get('kind', 'unknown')}", file=sys.stderr)
            return UNHEALTHY
        print(f"healthz returned HTTP {error.code}", file=sys.stderr)
        return UNHEALTHY
    except Exception as error:  # noqa: BLE001
        print(f"health unreachable: {type(error).__name__}: {error}", file=sys.stderr)
        return UNHEALTHY

    if payload.get("ok"):
        return HEALTHY
    if payload.get("kind") == "reauth":
        print("Nextcloud credential needs replacing; serving, but nothing will list.", file=sys.stderr)
        return HEALTHY
    print(f"source unhealthy: {payload.get('error')}", file=sys.stderr)
    return UNHEALTHY


if __name__ == "__main__":
    raise SystemExit(main())
