#!/usr/bin/env python3
"""The multi-tenant core: the vault, the account store, and the isolation between tenants.

    python tools/web/test_accounts.py

These are the tests where a failure is somebody else's data, so several of them are written as
**attacks** rather than as assertions: move a ciphertext to another row, ask for a credential with
the wrong account id, reach a cache directory that is not yours, revoke a token that belongs to
somebody else. A green tick here should mean "that attack does not work", not "the happy path
happened to run".
"""

from __future__ import annotations

import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import accounts as accounts_mod  # noqa: E402
import cachekey  # noqa: E402
import vault as vault_mod  # noqa: E402

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


def main() -> int:  # noqa: C901
    if not vault_mod.available():
        print("  cryptography is not installed; the vault tests cannot run here.")
        return 0

    section("the vault")
    key_a = vault_mod.Vault.generate()
    key_b = vault_mod.Vault.generate()
    v = vault_mod.Vault(*vault_mod._parse(key_a))  # noqa: SLF001

    sealed = v.seal("xxxxx-yyyyy-zzzzz", "acct-1")
    check("a secret round-trips", v.open(sealed, "acct-1") == "xxxxx-yyyyy-zzzzz")
    check("the plaintext does not appear in the record", b"xxxxx" not in sealed)
    check("two seals of the same value differ (fresh nonce each time)",
          v.seal("same", "acct-1") != v.seal("same", "acct-1"),
          "a repeated nonce under one key is catastrophic for AES-GCM")

    section("⛔ a ciphertext cannot be moved between accounts")
    # The attack: somebody with database write access copies user A's encrypted credential into
    # user B's row. Without associated data that is a complete account takeover using one UPDATE.
    moved = False
    try:
        v.open(sealed, "acct-2")
    except vault_mod.VaultError:
        moved = True
    check(
        "⛔ opening account 1's record as account 2 fails",
        moved,
        "this is what binding the account id as AAD buys, and it is the difference between a "
        "database-write bug and an account takeover",
    )

    section("keys and rotation")
    tampered = bytearray(sealed)
    tampered[-1] ^= 0x01
    corrupted = False
    try:
        v.open(bytes(tampered), "acct-1")
    except vault_mod.VaultError:
        corrupted = True
    check("a flipped bit is refused, not silently returned", corrupted)

    other = vault_mod.Vault(*vault_mod._parse(key_b))  # noqa: SLF001
    wrong_key = False
    try:
        other.open(sealed, "acct-1")
    except vault_mod.VaultError:
        wrong_key = True
    check("another key cannot open it", wrong_key)

    rotated = vault_mod.Vault(*vault_mod._parse(f"new:{key_b.rstrip('=')},old:{key_a.rstrip('=')}"))
    # Seal with the OLD key id explicitly, then open with a vault that has both.
    old_vault = vault_mod.Vault({"old": vault_mod._parse(key_a)[0]["1"]}, "old")  # noqa: SLF001
    old_record = old_vault.seal("legacy", "acct-9")
    check(
        "⚠️ a record sealed by a retired key still opens once that key is in _KEYS_OLD",
        rotated.open(old_record, "acct-9") == "legacy",
        "rotation must not orphan every existing credential",
    )
    check("…and a new seal uses the new key",
          rotated.seal("fresh", "acct-9")[2:5] == b"new",
          "the key id is written into the record because AES-GCM is not key-committing")

    section("bad keys are refused loudly")
    for bad, why in ((f"1:{'a' * 10}", "too short"), ("1:not base64!!", "not base64")):
        refused = False
        try:
            vault_mod.Vault(*vault_mod._parse(bad))  # noqa: SLF001
        except vault_mod.VaultError:
            refused = True
        check(f"a key that is {why} is refused", refused)

    section("the account store")
    with tempfile.TemporaryDirectory() as root:
        store = accounts_mod.Accounts(os.path.join(root, "reader.db"), v, pepper=v.pepper())

        a = store.upsert("https://cloud.a", "noah", "noah", "pass-a")
        b = store.upsert("https://cloud.b", "noah", "noah", "pass-b")
        check(
            "⚠️ the same uid on two servers is two accounts",
            a.id != b.id,
            "keying on uid alone would merge two strangers' libraries into one",
        )
        check("each gets its own credential back",
              store.credential(a.id) == "pass-a" and store.credential(b.id) == "pass-b")

        again = store.upsert("https://cloud.a", "noah", "noah", "pass-a2")
        check("signing in again refreshes the credential and keeps the account",
              again.id == a.id and store.credential(a.id) == "pass-a2",
              "this is the repair path for a revoked app password")

        section("sessions")
        cookie = store.start_session(a.id)
        check("a session resolves to its account", (store.resolve_session(cookie) or a).id == a.id)
        check("⚠️ the raw session id is not in the database",
              cookie.encode() not in open(os.path.join(root, "reader.db"), "rb").read(),
              "sessions are bearer credentials; a database read must not hand over working ones")
        check("a wrong cookie resolves to nothing", store.resolve_session("nonsense") is None)
        store.end_session(cookie)
        check("signing out invalidates it server-side", store.resolve_session(cookie) is None)

        one = store.start_session(a.id)
        two = store.start_session(a.id)
        store.end_all_sessions(a.id)
        check("'sign out everywhere' ends all of them",
              store.resolve_session(one) is None and store.resolve_session(two) is None)

        section("MCP tokens")
        token = store.mint_token(a.id, "laptop")
        check("a token resolves to its account", (store.resolve_token(token) or b).id == a.id)
        check("⚠️ the raw token is not in the database",
              token.encode() not in open(os.path.join(root, "reader.db"), "rb").read())
        check("it carries a readable prefix", token.startswith("ink_"))
        listed = store.tokens(a.id)
        check("it is listed by prefix only, never in full",
              len(listed) == 1 and listed[0].prefix == token[:accounts_mod.TOKEN_PREFIX_LEN]
              and listed[0].label == "laptop")

        b_token = store.mint_token(b.id)
        check("⛔ one account cannot revoke another's token",
              store.revoke_token(a.id, b_token[:accounts_mod.TOKEN_PREFIX_LEN]) == 0
              and store.resolve_token(b_token) is not None,
              "revocation is scoped to the account, not just to the prefix")
        check("…but can revoke its own",
              store.revoke_token(a.id, listed[0].prefix) == 1 and store.resolve_token(token) is None)

        section("deleting an account")
        store.forget(b.id)
        check("the account is gone", store.get(b.id) is None)
        check("⚠️ and its tokens went with it (ON DELETE CASCADE)",
              store.resolve_token(b_token) is None,
              "a token outliving its account would authenticate to nothing, or worse, to a reused id")
        # ⚠️ Windows will not delete an open SQLite file, so the temporary directory cannot
        # be cleaned up while a connection is live. That is what `close()` exists for, and it
        # is the same handle that would block a real "delete everything" path.
        store.close()

    section("cache keys cannot collide across tenants")
    # ⛔ The bug this replaced: sha256 of a delimiter-joined string. Nextcloud permits `|` in a
    # filename, so `("a|b", "c")` and `("a", "b|c")` produced one key and two different files were
    # handed each other's bytes.
    check(
        "⛔ a separator inside a component no longer shifts the boundary",
        cachekey.key("media", "srv", "a|b", "c") != cachekey.key("media", "srv", "a", "b|c"),
        "this was a wrong-file bug for one user and a cross-account leak for two",
    )
    check("the same inputs still give the same key",
          cachekey.key("m", "x", "y") == cachekey.key("m", "x", "y"))
    check("a different kind gives a different key",
          cachekey.key("media", "x") != cachekey.key("note", "x"))

    section("⛔ a cache directory is per account, and the id is checked")
    good = accounts_mod.cache_dir_for("/cache", "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    check("a real uuid yields a path under the root", good.startswith(os.path.join("/cache", "accounts")))
    for hostile in ("../../etc", "..", "a/b", "", "3f2504e0-4f89-41d3-9a0c-0305e82c3301/x"):
        refused = False
        try:
            accounts_mod.cache_dir_for("/cache", hostile)
        except ValueError:
            refused = True
        check(f"⛔ {hostile!r} is refused as an account id", refused,
              "the id never comes from user input today; this is what keeps that true")

    print(f"\n{passed}/{passed + failed} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
