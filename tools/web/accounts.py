#!/usr/bin/env python3
"""Who has signed in, and the isolation between them.

The single-user reader has no notion of an account: there is one Nextcloud, one credential, one
cache. This is what has to exist before other people can use the same instance, and almost all of
it is about making it **structurally impossible** for one person's notes to reach another's screen
rather than merely unlikely.

## ⭐ Nextcloud is the identity provider, and there are no local passwords

A person proves who they are by completing Login Flow v2 against their own Nextcloud (`login.py`).
That is the entire authentication story: there is no user table with password hashes, no
registration form, no password reset, and no "forgot your password" email — the three things a
small service is most likely to get wrong, absent by construction.

An account is therefore keyed by **(server_url, uid)**. Not by uid alone: two different Nextclouds
can both have a `noah`, and collapsing them would join two strangers' libraries into one.

## ⚠️ What is stored, and what is deliberately not

| | Stored as | Why |
|---|---|---|
| Nextcloud app password | **AES-GCM ciphertext** (`vault.py`) | It is a live credential to somebody's private files. A stolen disk without the key yields nothing. |
| Session id | **HMAC-SHA256 of it**, keyed by a server pepper | The cookie value is never written down, and a stolen database is not offline-verifiable without the key. |
| MCP token | **HMAC-SHA256 of it**, plus a short public prefix | Same reason. The prefix lets a user tell two tokens apart in a list without the server knowing either. |
| The user's Nextcloud *account* password | **never seen** | Login Flow v2 means it never reaches this process at all. |

⛔ **The stored credential is not read-only, whatever this code does.** A Nextcloud app password is
**unscoped**: it carries full access to the account, including writing and deleting files. Every
line of `tools/web` is a reader and cannot mutate anything — but that is a property of *this code*,
not of the credential it holds. If this server is compromised, the attacker has write access to
every signed-in user's Nextcloud, not merely read. Nothing in the design can change that; only
Nextcloud growing scoped tokens could. It is the single most important sentence in this file.

⚠️ **Hashing session ids and tokens is not theatre.** They are bearer credentials: anything holding
one *is* the user. A backup, a log line, an SQL-injection read, or a stray `SELECT *` in a debug
session would otherwise be an account takeover rather than an information disclosure. They are
high-entropy random strings, so a plain SHA-256 is the right primitive — a password KDF would be
protecting against a guessing attack that cannot happen against 256 bits of `secrets`.

## ⚠️ Isolation is a directory, not a key prefix

The single-user cache is keyed on `(path, etag)`. Sharing that between tenants would be a
cross-account data leak the first time two people have a file at the same path with the same ETag —
which, for two students at one school syncing the same handout, is not a hypothetical.

Namespacing the *key* would fix that, and it is the weaker fix: it survives only as long as every
future caller remembers to include the tenant. Each account gets its **own directory** instead, so
a leak needs a path-traversal bug rather than a forgotten prefix, and the account id is a UUID that
never comes from user input.

## Threading

`ThreadingHTTPServer` means concurrent access. SQLite is used with one connection per thread
(`threading.local`), WAL mode, and `check_same_thread=True` left at its default — sharing a
connection across threads is the classic way to get `ProgrammingError` under load, and a pool is
more machinery than a service of this size needs.
"""

from __future__ import annotations

import hashlib
import hmac
import os
import secrets
import sqlite3
import threading
import time
import uuid
from dataclasses import dataclass

SCHEMA_VERSION = 1

#: How long a browser session lasts with no activity, and its absolute ceiling.
#:
#: Both, because idle-only lets a stolen cookie live for ever as long as it is used, and
#: absolute-only signs out somebody in the middle of reading.
#:
#: ⚠️ These are **longer than OWASP's numbers** (15–30 minutes idle, 4–8 hours absolute for a
#: workday application) and that is a decision rather than an oversight. This is a reading tool for
#: a person's own notes, opened a few times a week; a half-hour idle timeout would sign somebody
#: out between two lessons and teach them to write the passphrase down, which is a worse outcome
#: than a longer window.
#:
#: They are still much shorter than the single-user version's 30 days with no idle limit at all,
#: because what sits behind the session changed: a Nextcloud app password is **unscoped** — full
#: account access including write — so a stolen session is not merely a reading session.
SESSION_IDLE_S = 7 * 24 * 3600
SESSION_ABSOLUTE_S = 30 * 24 * 3600

#: Shown to the user once, then only its prefix is ever displayed again.
TOKEN_BYTES = 32
TOKEN_PREFIX_LEN = 8

_SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS account (
    id           TEXT PRIMARY KEY,
    server_url   TEXT NOT NULL,
    uid          TEXT NOT NULL,
    login_name   TEXT NOT NULL,
    secret       BLOB NOT NULL,
    created_at   INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL,
    disabled     INTEGER NOT NULL DEFAULT 0,
    -- ⚠️ (server_url, uid), never uid alone: two Nextclouds can each have a `noah`, and one
    -- account row for both would merge two strangers' libraries.
    UNIQUE (server_url, uid)
);

CREATE TABLE IF NOT EXISTS session (
    id_hash    BLOB PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    seen_at    INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS session_by_account ON session(account_id);

CREATE TABLE IF NOT EXISTS token (
    id_hash      BLOB PRIMARY KEY,
    account_id   TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    prefix       TEXT NOT NULL,
    label        TEXT NOT NULL DEFAULT '',
    created_at   INTEGER NOT NULL,
    last_used_at INTEGER
);
CREATE INDEX IF NOT EXISTS token_by_account ON token(account_id);
"""


def digest(value: str, pepper: bytes = b"") -> bytes:
    """Keyed hash of a bearer credential.

    A plain SHA-256 is enough to stop a database read yielding working credentials, and it is what
    this did first. **HMAC with a server-side pepper is strictly better**: the pepper lives in the
    environment rather than the volume, so a stolen database is not even *offline verifiable* —
    an attacker holding the table cannot test a candidate token against it without also holding
    the key.

    Deliberately not a password KDF. These are 256-bit values from `secrets`; bcrypt or Argon2
    would be protecting against a guessing attack that cannot happen, and paying for it on **every
    API request**, which is the documented anti-pattern for high-entropy secrets.

    The pepper defaults to empty so the single-user and test paths need no key ceremony; a
    multi-user deployment always supplies one.
    """
    if pepper:
        return hmac.new(pepper, value.encode("utf-8"), hashlib.sha256).digest()
    return hashlib.sha256(value.encode("utf-8")).digest()


@dataclass(frozen=True)
class Account:
    id: str
    server_url: str
    uid: str
    login_name: str
    created_at: int
    last_seen_at: int
    disabled: bool = False

    @property
    def label(self) -> str:
        """What to show a person. Never includes anything secret."""
        host = self.server_url.split("://", 1)[-1]
        return f"{self.login_name} at {host}"


@dataclass(frozen=True)
class TokenInfo:
    prefix: str
    label: str
    created_at: int
    last_used_at: int | None


class Accounts:
    """The store. Every method that returns data takes an account id, so a caller cannot
    accidentally ask a question that spans tenants."""

    def __init__(self, path: str, vault, pepper: bytes = b"") -> None:  # noqa: ANN001 - vault.Vault
        self.path = path
        self.vault = vault
        self.pepper = pepper
        os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
        self._local = threading.local()
        with self._write() as connection:
            connection.executescript(_SCHEMA)
            connection.execute(
                "INSERT OR IGNORE INTO meta(key, value) VALUES ('schema', ?)", (str(SCHEMA_VERSION),)
            )

    # -- plumbing ---------------------------------------------------------

    @property
    def _connection(self) -> sqlite3.Connection:
        """One connection per thread.

        ⚠️ `ThreadingHTTPServer` hands each request its own thread, and sharing one sqlite3
        connection across them raises `ProgrammingError` under exactly the concurrency a real user
        produces (the page fires several fetches at once). A connection per thread is the smallest
        correct answer; a pool is more machinery than this needs.
        """
        connection = getattr(self._local, "connection", None)
        if connection is None:
            connection = sqlite3.connect(self.path, timeout=10.0)
            connection.row_factory = sqlite3.Row
            # WAL so a reader is never blocked by the writer — the common case here is many reads
            # (session lookups, one per request) and rare writes.
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute("PRAGMA foreign_keys=ON")
            connection.execute("PRAGMA busy_timeout=10000")
            self._local.connection = connection
        return connection

    def _write(self):
        return self._connection

    # -- accounts ---------------------------------------------------------

    def upsert(self, server_url: str, uid: str, login_name: str, app_password: str) -> Account:
        """Record a completed sign-in, creating the account or refreshing its credential.

        Called from the login flow, so it is also the *repair* path: a user whose app password was
        revoked signs in again and the new one replaces the old, with everything else — their
        tokens, their cache — untouched.
        """
        now = int(time.time())
        connection = self._write()
        with connection:
            existing = connection.execute(
                "SELECT id FROM account WHERE server_url = ? AND uid = ?", (server_url, uid)
            ).fetchone()
            # ⚠️ The account id is decided BEFORE the credential is sealed, because it **is** the
            # associated data. That binding is what stops an attacker with database write access
            # from copying one user's encrypted credential into another user's row: the ciphertext
            # simply will not open under a different account id.
            account_id = existing["id"] if existing is not None else str(uuid.uuid4())
            secret = self.vault.seal(app_password, account_id)
            if existing is not None:
                connection.execute(
                    "UPDATE account SET secret = ?, login_name = ?, last_seen_at = ? WHERE id = ?",
                    (secret, login_name, now, account_id),
                )
            else:
                connection.execute(
                    "INSERT INTO account(id, server_url, uid, login_name, secret, created_at, last_seen_at)"
                    " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (account_id, server_url, uid, login_name, secret, now, now),
                )
        return self.get(account_id)  # type: ignore[return-value]

    def get(self, account_id: str) -> Account | None:
        row = self._connection.execute(
            "SELECT * FROM account WHERE id = ?", (account_id,)
        ).fetchone()
        return _account(row) if row else None

    def credential(self, account_id: str) -> str | None:
        """The decrypted app password. The only place plaintext exists, and only in memory."""
        row = self._connection.execute(
            "SELECT secret, disabled FROM account WHERE id = ?", (account_id,)
        ).fetchone()
        if row is None or row["disabled"]:
            return None
        return self.vault.open(row["secret"], account_id)

    def touch(self, account_id: str) -> None:
        connection = self._write()
        with connection:
            connection.execute(
                "UPDATE account SET last_seen_at = ? WHERE id = ?", (int(time.time()), account_id)
            )

    def forget(self, account_id: str) -> None:
        """Delete the account, its sessions and its tokens.

        ⚠️ The **cache directory is not removed here** — that is the caller's job, because this
        class must not know about the filesystem layout, and because a half-deleted account is
        better than a delete that failed partway and left the row gone but the files present. The
        server deletes the directory first and the row second, so a crash leaves a row pointing at
        nothing (harmless, re-fetches) rather than files nobody owns.
        """
        connection = self._write()
        with connection:
            connection.execute("DELETE FROM account WHERE id = ?", (account_id,))

    def count(self) -> int:
        return self._connection.execute("SELECT COUNT(*) AS n FROM account").fetchone()["n"]

    def close(self) -> None:
        """Close this thread's connection.

        ⚠️ Needed for more than tidiness on Windows, where an open handle **prevents the file
        being deleted** — which is how this was found, by a temporary directory that would not
        clean up. The same applies to a real "delete everything" path, and to a shutdown that
        wants the WAL checkpointed rather than left beside the database.

        Only this thread's connection: there is one per thread by design, and reaching across to
        close another thread's while it is mid-query is worse than leaving it to process exit.
        """
        connection = getattr(self._local, "connection", None)
        if connection is not None:
            connection.close()
            self._local.connection = None

    # -- sessions ---------------------------------------------------------

    def start_session(self, account_id: str) -> str:
        """Mint a session and return the cookie value. Only its hash is stored."""
        raw = secrets.token_urlsafe(32)
        now = int(time.time())
        connection = self._write()
        with connection:
            connection.execute(
                "INSERT INTO session(id_hash, account_id, created_at, seen_at, expires_at)"
                " VALUES (?, ?, ?, ?, ?)",
                (digest(raw, self.pepper), account_id, now, now, now + SESSION_ABSOLUTE_S),
            )
        return raw

    def resolve_session(self, raw: str) -> Account | None:
        """The account behind a cookie, or None. Slides the idle window as a side effect."""
        if not raw:
            return None
        now = int(time.time())
        row = self._connection.execute(
            "SELECT s.account_id, s.seen_at, s.expires_at FROM session s WHERE s.id_hash = ?",
            (digest(raw, self.pepper),),
        ).fetchone()
        if row is None:
            return None
        # ⚠️ Both windows. Idle-only lets a stolen cookie live for ever as long as it is used;
        # absolute-only signs out somebody who is in the middle of reading.
        if now > row["expires_at"] or now - row["seen_at"] > SESSION_IDLE_S:
            self.end_session(raw)
            return None
        connection = self._write()
        with connection:
            connection.execute(
                "UPDATE session SET seen_at = ? WHERE id_hash = ?", (now, digest(raw, self.pepper))
            )
        return self.get(row["account_id"])

    def end_session(self, raw: str) -> None:
        connection = self._write()
        with connection:
            connection.execute("DELETE FROM session WHERE id_hash = ?", (digest(raw, self.pepper),))

    def end_all_sessions(self, account_id: str) -> int:
        """"Sign out everywhere". The button every service should have and most do not."""
        connection = self._write()
        with connection:
            cursor = connection.execute("DELETE FROM session WHERE account_id = ?", (account_id,))
        return cursor.rowcount

    def sweep(self) -> int:
        now = int(time.time())
        connection = self._write()
        with connection:
            cursor = connection.execute(
                "DELETE FROM session WHERE expires_at < ? OR seen_at < ?",
                (now, now - SESSION_IDLE_S),
            )
        return cursor.rowcount

    # -- MCP tokens -------------------------------------------------------

    def mint_token(self, account_id: str, label: str = "") -> str:
        """A bearer token for the MCP endpoint. Returned once and never recoverable.

        The value carries a readable prefix so a person can match it to the row in their list
        without the server having to keep the secret half.
        """
        raw = f"ink_{secrets.token_urlsafe(TOKEN_BYTES)}"
        connection = self._write()
        with connection:
            connection.execute(
                "INSERT INTO token(id_hash, account_id, prefix, label, created_at)"
                " VALUES (?, ?, ?, ?, ?)",
                (digest(raw, self.pepper), account_id, raw[:TOKEN_PREFIX_LEN], label, int(time.time())),
            )
        return raw

    def resolve_token(self, raw: str) -> Account | None:
        if not raw:
            return None
        row = self._connection.execute(
            "SELECT account_id FROM token WHERE id_hash = ?", (digest(raw, self.pepper),)
        ).fetchone()
        if row is None:
            return None
        connection = self._write()
        with connection:
            connection.execute(
                "UPDATE token SET last_used_at = ? WHERE id_hash = ?", (int(time.time()), digest(raw, self.pepper))
            )
        return self.get(row["account_id"])

    def tokens(self, account_id: str) -> list[TokenInfo]:
        rows = self._connection.execute(
            "SELECT prefix, label, created_at, last_used_at FROM token WHERE account_id = ?"
            " ORDER BY created_at",
            (account_id,),
        ).fetchall()
        return [
            TokenInfo(r["prefix"], r["label"], r["created_at"], r["last_used_at"]) for r in rows
        ]

    def revoke_token(self, account_id: str, prefix: str) -> int:
        """Revoke by prefix, **scoped to the account** so one user cannot revoke another's."""
        connection = self._write()
        with connection:
            cursor = connection.execute(
                "DELETE FROM token WHERE account_id = ? AND prefix = ?", (account_id, prefix)
            )
        return cursor.rowcount


def _account(row: sqlite3.Row) -> Account:
    return Account(
        id=row["id"],
        server_url=row["server_url"],
        uid=row["uid"],
        login_name=row["login_name"],
        created_at=row["created_at"],
        last_seen_at=row["last_seen_at"],
        disabled=bool(row["disabled"]),
    )


def cache_dir_for(root: str, account_id: str) -> str:
    """Each account's own directory. See the module docstring on why this is not a key prefix.

    ⚠️ `account_id` is a UUID this process minted; it never comes from user input, so it cannot
    contain a separator. Asserted rather than assumed, because the day it stops being true is the
    day this becomes a path traversal into another tenant's cache.
    """
    if not _SAFE_ID(account_id):
        raise ValueError(f"refusing an account id that is not a plain UUID: {account_id!r}")
    return os.path.join(root, "accounts", account_id)


def _SAFE_ID(value: str) -> bool:  # noqa: N802 - reads as a predicate at the call site
    try:
        return str(uuid.UUID(value)) == value
    except (ValueError, AttributeError, TypeError):
        return False
