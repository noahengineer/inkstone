#!/usr/bin/env python3
"""Where the reader's bytes come from: a live Nextcloud, or a folder on disk.

Two implementations behind one small interface, and the split earns its keep three times:

* **`NextcloudSource`** wraps `tools/m12/nextcloud.py`. It does **not** reimplement WebDAV.
  CLAUDE.md is explicit that *"a second client for a protocol the project already speaks is where
  the old bugs come back"* — M12's own watcher shipped three bugs that `:core:sync` had got right
  years earlier, all found by pointing it at a real server. That client already percent-encodes
  per segment, flags the `Depth: 1` self-entry instead of dropping it, sends a real User-Agent so
  a Cloudflare-fronted server does not 403 everything, and refuses an XML document declaring a
  DTD. Every one of those is a bug this file does not get to have.

* **`MirrorSource`** reads the same layout off a local directory. That is what makes the whole
  tool testable without a network, runnable on a plane, and — the reason it exists at all —
  developable while the server is unreachable.

* Neither can **write**. There is no PUT, no DELETE, no MKCOL anywhere in this package. This is a
  reader, and a reader that cannot mutate cannot lose anything (CLAUDE.md rule 1). If a later
  version needs to write, it should route through the outbox like everything else does, not grow
  a verb here.
"""

from __future__ import annotations

import os
import sys
import urllib.parse
from dataclasses import dataclass

_M12 = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "m12")
if _M12 not in sys.path:
    sys.path.insert(0, _M12)


@dataclass(frozen=True)
class Entry:
    """One thing in a directory listing. The field names match `nextcloud.py`'s dict keys."""

    name: str
    path: str
    is_collection: bool
    size: int = 0
    etag: str = ""
    content_type: str = ""


def _tls_context_for(url: str):
    """The TLS policy every WebDAV call uses. See `tlsdiag` for why one flag is relaxed.

    Kept as a named module function rather than a lambda so it is greppable, testable, and appears
    in a traceback by name — a TLS decision should never be anonymous.
    """
    import tlsdiag  # noqa: PLC0415

    host = urllib.parse.urlsplit(url).hostname or ""
    return tlsdiag.context_for(host) if host else None


class SourceError(Exception):
    """Something went wrong reaching the data. Carries a message meant for a person."""


class NeedsReauth(SourceError):
    """A 401.

    ⚠️ **Never retried, anywhere in this package.** Nextcloud has brute-force protection on by
    default, and CLAUDE.md rule 5 records that auto-retrying credentials is the documented way
    real users get their own server to IP-block them. The reader stops, says the credential needs
    replacing, and waits for a person.
    """


class Source:
    """Read-only access to a tree of files. Paths are relative, `/`-separated, no leading slash."""

    label: str = "source"

    def listing(self, path: str) -> list[Entry]:
        raise NotImplementedError

    def read(self, path: str) -> bytes:
        raise NotImplementedError

    def read_text(self, path: str) -> str:
        return self.read(path).decode("utf-8", errors="replace")

    def read_range(self, path: str, first: int, last: int) -> bytes:
        """Bytes `first`..`last` inclusive, HTTP's meaning of a range.

        The default reads the whole file and slices, which is correct but wasteful; a source that
        can do better overrides it. Reading an hour of audio to learn its length is exactly what
        `oggopus.duration_of` exists to avoid.
        """
        return self.read(path)[first : last + 1]

    def exists(self, path: str) -> bool:
        raise NotImplementedError


class NextcloudSource(Source):
    """The live account, over WebDAV, through `tools/m12/nextcloud.py`."""

    def __init__(self, credentials) -> None:  # noqa: ANN001 - config.Credentials, kept loose to avoid a cycle
        import nextcloud as nextcloud_mod  # noqa: PLC0415
        import tlsdiag  # noqa: PLC0415
        from nextcloud import Nextcloud  # noqa: PLC0415

        # Install the TLS policy for every WebDAV call this client makes. See `tlsdiag`: it is
        # strict first and relaxes exactly one RFC-conformance flag, per host, only when that is
        # the sole reason a handshake failed.
        if nextcloud_mod.ssl_context_for is None:
            nextcloud_mod.ssl_context_for = _tls_context_for

        self._nc = Nextcloud(
            credentials.url, credentials.uid, credentials.password, credentials.login
        )
        self.label = f"nextcloud:{credentials.url}"
        self._uid = credentials.uid
        self._halted: NeedsReauth | None = None

    def _check_halted(self) -> None:
        """⛔ CLAUDE.md rule 5, and the reason it needs a **latch** rather than a policy.

        "Never retry a 401" is easy to read as "do not loop on one request", and the code did
        satisfy that: every call let the 401 out, and nothing wrapped it in a retry. But the
        *website* re-scans — a page load, a Refresh press, the 60-second cache expiring — and each
        scan is a fresh PROPFIND per folder with the same dead credential. A revoked app password
        therefore produced one failed login per folder per refresh, forever, which is precisely
        the brute-force counter the rule exists to stay away from. The retry loop was the user,
        and the loop was invisible from inside any single call.

        So the first 401 halts this source. Nothing reaches the network again until the process is
        restarted with a working credential, which is the same shape as the app's `NEEDS_REAUTH`
        flag: a person, not a timer, is what clears it.
        """
        if self._halted is not None:
            raise self._halted

    def whoami(self) -> dict:
        """SPEC §7.2's confirmation call — the cheapest proof the credential works at all."""
        self._check_halted()
        try:
            return self._nc.whoami()
        except Exception as error:  # noqa: BLE001
            raise self._translate(error) from error

    def listing(self, path: str) -> list[Entry]:
        self._check_halted()
        try:
            raw = self._nc.listing(path)
        except Exception as error:  # noqa: BLE001
            raise self._translate(error) from error
        out: list[Entry] = []
        for item in raw:
            # ⚠️ `is_self`, not a name comparison. At the account root the collection's own name
            # is the *uid*, which matches nothing, so a name test walks the root as its own child
            # to the depth bound. `watch.py` carries the same warning; it cost a real bug there.
            if item.get("is_self"):
                continue
            name = item["name"]
            out.append(
                Entry(
                    name=name,
                    path=f"{path}/{name}".lstrip("/"),
                    is_collection=bool(item["is_collection"]),
                    size=int(item.get("size") or 0),
                    etag=str(item.get("etag") or ""),
                    content_type=str(item.get("content_type") or ""),
                )
            )
        return out

    def read(self, path: str) -> bytes:
        self._check_halted()
        try:
            return self._nc.get(path)
        except Exception as error:  # noqa: BLE001
            raise self._translate(error) from error

    def read_range(self, path: str, first: int, last: int) -> bytes:
        self._check_halted()
        try:
            return self._nc.get(path, (first, last))
        except Exception as error:  # noqa: BLE001
            raise self._translate(error) from error

    def exists(self, path: str) -> bool:
        self._check_halted()
        try:
            return bool(self._nc.listing(path, depth="0"))
        except NeedsReauth:
            raise
        except Exception:  # noqa: BLE001
            return False

    def _translate(self, error: Exception) -> SourceError:
        code = getattr(error, "code", None)
        if code == 401:
            halt = NeedsReauth(
                "Nextcloud answered 401 Unauthorized.\n\n"
                "The app password in watcher.local.properties has been revoked or expired.\n"
                "Mint a new one: Nextcloud -> Settings -> Security -> "
                '"Create new app password", then put it in nextcloud.password.\n\n'
                "This reader has stopped talking to the server and will not try again until it "
                "is restarted. Nextcloud counts failed logins and will block this IP."
            )
            self._halted = halt
            return halt
        if code == 403:
            return SourceError(
                "Nextcloud answered 403 Forbidden. If the server sits behind Cloudflare this is "
                "usually its bot filter rather than a permissions problem - see the User-Agent "
                "note in tools/m12/nextcloud.py."
            )
        return SourceError(f"{type(error).__name__}: {error}")


class MirrorSource(Source):
    """The same layout, read off a local directory.

    Used for tests, for offline reading, and for the `--mirror` mode that points the website at a
    folder somebody has already synced with the Nextcloud desktop client.
    """

    def __init__(self, root: str) -> None:
        self.root = os.path.abspath(root)
        if not os.path.isdir(self.root):
            raise SourceError(f"Not a directory: {self.root}")
        self.label = f"mirror:{self.root}"

    def _resolve(self, path: str) -> str:
        """Join, then refuse anything that escaped the root.

        ⚠️ `..` in a path is not hypothetical here: paths reach this from HTTP query strings and
        from MCP tool arguments, both of which are outside input. `commonpath` after
        `abspath` is the check that actually holds, because it compares the *resolved* location
        rather than looking for a substring somebody can spell another way.
        """
        target = os.path.abspath(os.path.join(self.root, *[p for p in path.split("/") if p]))
        try:
            inside = os.path.commonpath([self.root, target]) == self.root
        except ValueError:
            # ⚠️ `commonpath` **raises** for paths on different drives and for a UNC path against
            # a drive-letter path — both of which an absolute segment produces on Windows
            # (`\\\\server\\share\\x`, `D:/x`). Letting that out as a ValueError turns a refusal
            # into a 500 with a stack trace, which reads as a crash rather than as the guard
            # working. Different roots cannot be inside each other, so this is a refusal.
            inside = False
        if not inside:
            raise SourceError(f"Path escapes the mirror root: {path!r}")
        return target

    def listing(self, path: str) -> list[Entry]:
        target = self._resolve(path)
        if not os.path.isdir(target):
            return []
        out: list[Entry] = []
        for name in sorted(os.listdir(target)):
            full = os.path.join(target, name)
            is_dir = os.path.isdir(full)
            stat = os.stat(full)
            out.append(
                Entry(
                    name=name,
                    path=f"{path}/{name}".lstrip("/"),
                    is_collection=is_dir,
                    size=0 if is_dir else stat.st_size,
                    # A mirror has no ETag, so synthesise a change token from the two things a
                    # local file does have. The cache only needs "did this change", not the
                    # server's exact opaque string.
                    etag="" if is_dir else f"{stat.st_mtime_ns:x}-{stat.st_size:x}",
                )
            )
        return out

    def read(self, path: str) -> bytes:
        target = self._resolve(path)
        if not os.path.isfile(target):
            raise SourceError(f"No such file: {path}")
        with open(target, "rb") as handle:
            return handle.read()

    def read_range(self, path: str, first: int, last: int) -> bytes:
        target = self._resolve(path)
        if not os.path.isfile(target):
            raise SourceError(f"No such file: {path}")
        with open(target, "rb") as handle:
            handle.seek(max(0, first))
            return handle.read(max(0, last - first + 1))

    def exists(self, path: str) -> bool:
        return os.path.exists(self._resolve(path))
