#!/usr/bin/env python3
"""Encrypting other people's Nextcloud credentials at rest.

Every other file in `tools/web` is stdlib-only, and this one is not: it needs AES, which Python
does not have. That is a deliberate, contained exception — **hand-rolling a cipher would be far
worse than taking a dependency**, and this repo's own instinct says so already (`:core:sync`
hand-writes MD5 for a checksum and the comment beside it insists it "must never become a security
primitive"). The dependency is required only in multi-user mode; the single-user reader still runs
on a machine with nothing installed.

## The choices, and why each one

**`AESGCM`, not `Fernet`.** The library's own documentation recommends considering AESGCM for data
that fits in memory, and three specifics decide it here:

* ⭐ **Associated data.** AESGCM can bind a ciphertext to the row it belongs to; Fernet cannot. The
  account id is passed as AAD, so a credential blob **cannot be moved between accounts**. An
  attacker who can write to the database still cannot paste user A's encrypted password into user
  B's row and have it decrypt — which is otherwise a complete account takeover using nothing but
  an UPDATE.
* **Fernet embeds a plaintext timestamp** in every token — authenticated, but not concealed. In a
  credential store that is a free metadata leak about when somebody signed in.
* **Fernet is AES-128-CBC + HMAC**, despite its 32-byte key: it splits that into a 128-bit signing
  key and a 128-bit encryption key. Nothing wrong with it; it is simply not what "256-bit key"
  suggests to a reader.

**A 96-bit random nonce per record.** NIST's recommended size, and the library documents nonce
reuse under one key as catastrophic. Random nonces are safe below 2^32 encryptions per key; this
writes one per sign-in, so the ceiling is four billion sign-ins away and the counting problem does
not exist. (PyNaCl's XChaCha20-Poly1305 with a 24-byte nonce would remove even that argument, and
is the reasonable alternative if this ever needs one.)

**An explicit key id in every record.** AES-GCM is **not key-committing**, so "try each key until
one works" is not a sound way to support rotation — a wrong key produces an authentication failure
rather than a reliable signal, and relying on that is the kind of thing that works until it does
not. Each record says which key sealed it.

## ⚠️ What this actually buys, stated honestly

It protects **the volume, not the running service**. Someone who steals the disk, a backup, or a
volume snapshot gets ciphertext and nothing else — which is the realistic threat for a small
self-hosted box. Someone who compromises the *process* has the key in memory and can decrypt
everything, and no amount of at-rest encryption changes that.

Which is why the key must not live in the volume it protects. `INKSTONE_SECRET_KEY_FILE` pointing
at a Docker secret is the intended shape; the secret is mounted at `/run/secrets/…` and the data
sits on a named volume, so backing up the volume does not back up the key.
"""

from __future__ import annotations

import base64
import os
import secrets

#: Record layout: version(1) ‖ keyid_len(1) ‖ keyid ‖ nonce(12) ‖ ciphertext‖tag
VERSION = 1
NONCE_BYTES = 12
KEY_BYTES = 32
DEFAULT_KEY_ID = "1"


class VaultError(Exception):
    """Raised with a message meant for the operator, never for an end user."""


class Vault:
    """Seals and opens one secret at a time. Holds the key material and nothing else."""

    def __init__(self, keys: dict[str, bytes], primary: str) -> None:
        if primary not in keys:
            raise VaultError(f"the primary key id {primary!r} is not among the keys provided")
        self._keys = keys
        self._primary = primary
        self._aesgcm = _load_aesgcm()

    # -- configuration ----------------------------------------------------

    @classmethod
    def from_env(cls) -> Vault:
        """Read `INKSTONE_SECRET_KEY` (or `..._FILE`), plus optional retired keys.

        Formats accepted:

            <base64>                    one key, id "1"
            <id>:<base64>               one key with an explicit id
            <id>:<base64>,<id>:<b64>    several; the FIRST is the one new records use

        Retired keys that must still open old records go in `INKSTONE_SECRET_KEYS_OLD`, same
        format. Rotation is therefore: move the current key into `..._OLD`, put a new one in
        `INKSTONE_SECRET_KEY`, restart. Old records keep opening; new ones use the new key.
        """
        from config import from_env  # noqa: PLC0415

        raw = from_env("INKSTONE_SECRET_KEY")
        if not raw:
            raise VaultError(
                "Multi-user mode needs INKSTONE_SECRET_KEY (or INKSTONE_SECRET_KEY_FILE).\n\n"
                "It encrypts other people's Nextcloud credentials at rest. Generate one with:\n\n"
                "    python -c \"import base64,os;print(base64.urlsafe_b64encode(os.urandom(32)).decode())\"\n\n"
                "Keep it OUT of the data volume - a Docker secret is the intended home - or\n"
                "backing up the data backs up the key that protects it."
            )
        keys, primary = _parse(raw)
        for key_id, material in _parse(from_env("INKSTONE_SECRET_KEYS_OLD"))[0].items():
            keys.setdefault(key_id, material)
        return cls(keys, primary)

    @staticmethod
    def generate() -> str:
        """A fresh key, in the format `from_env` accepts."""
        return base64.urlsafe_b64encode(secrets.token_bytes(KEY_BYTES)).decode()

    # -- the operations ---------------------------------------------------

    def seal(self, plaintext: str, aad: str) -> bytes:
        """Encrypt, binding the result to `aad` — the account id. See the module docstring."""
        nonce = secrets.token_bytes(NONCE_BYTES)
        cipher = self._aesgcm(self._keys[self._primary])
        body = cipher.encrypt(nonce, plaintext.encode("utf-8"), aad.encode("utf-8"))
        key_id = self._primary.encode("utf-8")
        return bytes([VERSION, len(key_id)]) + key_id + nonce + body

    def open(self, record: bytes, aad: str) -> str:
        """Decrypt. Raises [VaultError] for anything that is not exactly right.

        ⚠️ A failure here means one of: the wrong key, a corrupted record, or a record that has
        been **moved to a different account**. All three are refusals, and none of them is
        recoverable by trying harder — so this never falls back to another key.
        """
        try:
            if not record or record[0] != VERSION:
                raise VaultError(f"unknown vault record version {record[:1]!r}")
            key_len = record[1]
            key_id = record[2 : 2 + key_len].decode("utf-8")
            nonce = record[2 + key_len : 2 + key_len + NONCE_BYTES]
            body = record[2 + key_len + NONCE_BYTES :]
        except (IndexError, UnicodeDecodeError) as error:
            raise VaultError("this vault record is malformed") from error

        material = self._keys.get(key_id)
        if material is None:
            raise VaultError(
                f"this record was sealed with key {key_id!r}, which is not loaded. "
                "If you rotated keys, the old one belongs in INKSTONE_SECRET_KEYS_OLD."
            )
        cipher = self._aesgcm(material)
        try:
            return cipher.decrypt(nonce, body, aad.encode("utf-8")).decode("utf-8")
        except Exception as error:  # noqa: BLE001 - InvalidTag and friends are all "no"
            raise VaultError(
                "this record did not decrypt: wrong key, corrupted data, or a record that "
                "belongs to a different account."
            ) from error

    def pepper(self) -> bytes:
        """A key for HMAC-ing session ids and tokens (`accounts.digest`).

        Derived from the primary key rather than configured separately, so an operator has one
        secret to look after — and derived with a **distinct label** so the pepper and the
        encryption key are unrelated values. Using the encryption key directly as an HMAC key is
        the kind of reuse that is fine until one of the two primitives is broken.
        """
        import hashlib  # noqa: PLC0415

        return hashlib.blake2b(
            self._keys[self._primary], digest_size=32, person=b"inkstone-pepper"
        ).digest()


def _parse(raw: str) -> tuple[dict[str, bytes], str]:
    keys: dict[str, bytes] = {}
    primary = DEFAULT_KEY_ID
    for index, part in enumerate((raw or "").split(",")):
        part = part.strip()
        if not part:
            continue
        key_id, _, encoded = part.rpartition(":")
        key_id = key_id or DEFAULT_KEY_ID
        try:
            material = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))
        except Exception as error:  # noqa: BLE001
            raise VaultError(f"key {key_id!r} is not valid base64") from error
        if len(material) != KEY_BYTES:
            raise VaultError(
                f"key {key_id!r} is {len(material)} bytes; it must be exactly {KEY_BYTES}. "
                "Generate one with Vault.generate()."
            )
        keys[key_id] = material
        if index == 0:
            primary = key_id
    return keys, primary


def _load_aesgcm():
    """Import the cipher, or refuse with an explanation.

    ⚠️ **There is deliberately no fallback.** A missing dependency must stop the service, never
    quietly downgrade to storing credentials in the clear — a "temporary" plaintext path is how a
    credential store ends up plaintext in production, and nobody would see it happen.
    """
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM  # noqa: PLC0415
    except ImportError as error:
        raise VaultError(
            "Multi-user mode needs the `cryptography` package to encrypt stored credentials.\n\n"
            "    pip install cryptography\n\n"
            "The container installs it already (tools/web/requirements.txt). This is the one "
            "place `tools/web` is not stdlib-only, and it refuses to run without it rather than "
            "storing other people's passwords in the clear."
        ) from error
    return AESGCM


def available() -> bool:
    """Whether encryption is possible at all. Used to refuse multi-user mode early and clearly."""
    try:
        _load_aesgcm()
    except VaultError:
        return False
    return True
