#!/usr/bin/env python3
"""How a cache key is built, in the one place that gets to decide.

⛔ **The bug this file exists to remove.** Both caches used to key on a delimiter-joined string —
`sha256(f"{label}|{path}|{etag}")` and `sha256(f"{path}|{etag}")`. Joining with a separator and
hashing the result is **not an injective encoding**: the parts `("a|b", "c")` and `("a", "b|c")`
produce the identical string and therefore the identical digest. Nextcloud forbids only `\\` and
`/` in a filename, so `|` is a perfectly ordinary character to have in one, and two different
files could be handed each other's cached bytes.

In single-user mode that is a wrong-file bug. Once other people share the instance it is a
**cross-account data leak**, and the media cache was worse than the metadata one: its key used the
*server* URL, so two students on one school Nextcloud with the same relative path and the same file
size — the same handout, synced by both — collided by construction rather than by bad luck.

## The fix, and why it is the encoding rather than a longer key

Each part is **length-prefixed** before hashing. `len(part)` cannot itself be confused for content,
so the concatenation can be parsed back apart in principle, which is exactly the property that
makes distinct inputs produce distinct digests. No separator is used at all, so no character in a
filename is special any more.

⚠️ This is not the only defence, and it is not the main one. Per-account **directories**
(`accounts.cache_dir_for`) are what actually keeps tenants apart; this makes collisions impossible
*within* an account too, and means a future caller who forgets the account id gets a wrong answer
rather than somebody else's file.
"""

from __future__ import annotations

import hashlib

#: 128 bits of the digest, hex-encoded. Long enough that an accidental collision is not a thing
#: that happens; short enough to keep a filename readable in a directory listing.
KEY_LENGTH = 32


def key(*parts: str | bytes) -> str:
    """A collision-resistant key over an ordered list of components.

    Every component is hashed **with its length**, so no component's content can be mistaken for
    a boundary between two others. Compare the joined-string version this replaced, where a `|`
    inside a filename shifted the boundary.
    """
    digest = hashlib.sha256()
    for part in parts:
        raw = part.encode("utf-8") if isinstance(part, str) else part
        digest.update(len(raw).to_bytes(8, "big"))
        digest.update(raw)
    return digest.hexdigest()[:KEY_LENGTH]
