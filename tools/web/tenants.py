#!/usr/bin/env python3
"""One `Library` per signed-in account, and the limits that stop one person costing everyone else.

`server.py` in single-user mode holds one `Library` for the process. Multi-user cannot: every
request has to be served from **that requester's** Nextcloud, through **that requester's** cache
directory, and nothing else may be reachable from the code path that handles it.

## ⭐ The isolation rule, stated once

A request resolves to an `Account` **before** anything touches storage, and everything after that
point is reached through the `Tenant` built from it. There is no global library, no default
account, and no "current user" — the handler holds the tenant it was given and can express nothing
else. A cross-tenant read therefore needs somebody to pass the wrong account id in, not merely to
forget a prefix.

## Why the libraries are cached, and why that cache is bounded

Building a `Tenant` opens a SQLite-free `Library` and a `MediaCache`; the expensive part is the
in-memory scan, which is what makes a second page load instant. Keeping one per account for ever
would mean a process that has served a thousand people holds a thousand scans, so this is an LRU
with a hard ceiling — and eviction costs only the next scan.

⚠️ **Eviction must never delete a directory.** An evicted tenant is a forgotten *object*, not a
forgotten *user*; their cached files stay on disk and are found again next time. Conflating the two
would make a busy server silently re-download every library it evicted.

## Quotas exist because the cost is somebody else's to trigger

Each user makes this server download from *their* file server. Without a ceiling, one person with
a large Nextcloud fills the disk for everyone, and the failure lands on the wrong people. The quota
is per account and enforced where the bytes land.
"""

from __future__ import annotations

import os
import shutil
import threading
import time
from collections import OrderedDict
from dataclasses import dataclass

from accounts import Account, cache_dir_for
from library import Library

#: How many accounts' scans to keep warm. Small on purpose: a scan is cheap to redo and the memory
#: is not.
MAX_WARM_TENANTS = 24

#: Per-account ceiling for cached media (published PDFs and lecture audio). A term of lectures is
#: a few hundred megabytes; this is generous for one person and bounded for a hundred.
DEFAULT_QUOTA_BYTES = 2 * 1024 * 1024 * 1024


@dataclass
class Usage:
    bytes_used: int
    quota: int

    @property
    def full(self) -> bool:
        return self.bytes_used >= self.quota

    @property
    def fraction(self) -> float:
        return min(1.0, self.bytes_used / self.quota) if self.quota else 0.0


class Tenant:
    """Everything one account may reach. Holds no reference to any other account's anything."""

    def __init__(self, account: Account, library: Library, root: str, quota: int) -> None:
        self.account = account
        self.library = library
        self.root = root
        self.quota = quota
        self.touched_at = time.time()

    def usage(self) -> Usage:
        return Usage(_directory_size(self.root), self.quota)


class Tenants:
    """The registry. `for_account` is the only way to get a `Tenant`, and it needs an `Account`."""

    def __init__(self, cache_root: str, build_source, quota: int = DEFAULT_QUOTA_BYTES) -> None:  # noqa: ANN001
        self.cache_root = cache_root
        self.build_source = build_source
        self.quota = quota
        self._warm: OrderedDict[str, Tenant] = OrderedDict()
        self._lock = threading.Lock()

    def for_account(self, account: Account) -> Tenant:
        with self._lock:
            existing = self._warm.get(account.id)
            if existing is not None:
                existing.touched_at = time.time()
                self._warm.move_to_end(account.id)
                return existing

        # ⚠️ Built OUTSIDE the lock. Constructing a source can open a socket, and holding a global
        # lock across a network call would let one slow Nextcloud stall every other user's request
        # — a cross-tenant denial of service made out of a mutex.
        root = cache_dir_for(self.cache_root, account.id)
        os.makedirs(root, exist_ok=True)
        library = Library(self.build_source(account), os.path.join(root, "meta"))
        tenant = Tenant(account, library, root, self.quota)

        with self._lock:
            # Another thread may have built one meanwhile; keep whichever is already published so
            # two threads never hold different Library objects for one account.
            published = self._warm.get(account.id)
            if published is not None:
                return published
            self._warm[account.id] = tenant
            while len(self._warm) > MAX_WARM_TENANTS:
                # ⚠️ Forgetting the object, never the directory. See the module docstring.
                self._warm.popitem(last=False)
        return tenant

    def evict(self, account_id: str) -> None:
        with self._lock:
            self._warm.pop(account_id, None)

    def delete_everything(self, account_id: str) -> None:
        """Remove an account's cached data from disk. Used by "delete my account".

        Called **before** the row is deleted, so a crash in the middle leaves a row pointing at
        nothing — which re-fetches — rather than files that no row owns and nothing will ever
        clean up.
        """
        self.evict(account_id)
        root = cache_dir_for(self.cache_root, account_id)
        shutil.rmtree(root, ignore_errors=True)

    def warm_count(self) -> int:
        with self._lock:
            return len(self._warm)


def _directory_size(path: str) -> int:
    total = 0
    for base, _, names in os.walk(path):
        for name in names:
            try:
                total += os.path.getsize(os.path.join(base, name))
            except OSError:
                # A file removed while walking is not an error worth failing a page load over.
                continue
    return total
