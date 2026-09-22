#!/usr/bin/env python3
"""Where the web reader gets its Nextcloud credentials, and nothing else.

Stdlib only, like everything else under `tools/`. The rules here are not new — they are the
same three the M12 watcher already enforces (`tools/m12/run-watcher.ps1`), moved into Python so
this tool needs no PowerShell launcher to run:

* credentials come from `watcher.local.properties` at the repo root, which is gitignored by
  the `*.local.properties` pattern;
* `nextcloud.url` must be `https://`, with no accept-anyway switch, because the request carries
  an app password and downloads a classroom recording;
* the password must be an **app password**, not the account password.

Environment variables win over the file, so this also runs from Task Scheduler or a container
with nothing on disk. The names match the watcher's exactly (`INKSTONE_NC_*`) so one export
serves both tools.
"""

from __future__ import annotations

import os
from dataclasses import dataclass

CONFIG_NAME = "watcher.local.properties"

KEY_URL = "nextcloud.url"
KEY_UID = "nextcloud.uid"
KEY_PASSWORD = "nextcloud.password"
KEY_LOGIN = "nextcloud.login"

ENV_URL = "INKSTONE_NC_URL"
ENV_UID = "INKSTONE_NC_UID"
ENV_PASSWORD = "INKSTONE_NC_PASSWORD"
ENV_LOGIN = "INKSTONE_NC_LOGIN"


class ConfigError(Exception):
    """Raised with a message meant to be printed straight to a person."""


@dataclass(frozen=True)
class Credentials:
    url: str
    uid: str
    password: str
    login: str | None = None

    def redacted(self) -> str:
        """A form safe to log. Never `repr` these — the password is a real secret."""
        who = f"{self.login} (uid {self.uid})" if self.login else self.uid
        return f"{self.url} as {who}"


def repo_root(start: str | None = None) -> str:
    """The repo root, found by walking up for the marker file the whole project agrees on."""
    here = os.path.abspath(start or os.path.dirname(os.path.abspath(__file__)))
    while True:
        if os.path.exists(os.path.join(here, "SPEC.md")):
            return here
        parent = os.path.dirname(here)
        if parent == here:
            # Not inside the repo: fall back to two levels up from this file, which is what the
            # layout `tools/web/config.py` implies. Better than raising, because the tool is
            # perfectly usable from environment variables alone.
            return os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
        here = parent


def parse_properties(text: str) -> dict[str, str]:
    """A Java-ish `.properties` subset: `key=value`, `#` comments, blank lines.

    Deliberately the same three rules `run-watcher.ps1` implements, so a file that works for one
    tool works for the other. `split("=", 1)` and not `split("=")`, because an app password may
    not contain `=` today but a URL with a query string certainly could.
    """
    out: dict[str, str] = {}
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        out[key.strip()] = value.strip()
    return out


def from_env(name: str) -> str:
    """An environment variable, or the contents of the file `<NAME>_FILE` names.

    ⭐ The `_FILE` convention is what lets a container take a **Docker secret** instead of an
    environment variable, and it is worth the six lines because the two have genuinely different
    exposure. An environment variable is readable through `docker inspect`, through
    `/proc/<pid>/environ`, and by every child process — which is why Docker's own guidance is to
    prefer secrets for credentials. A secret is a file with an owner.

    Both are supported because the plain variable is what makes a five-minute `docker run` work,
    and being unable to start quickly is its own kind of failure. The file wins when both are set:
    somebody who has gone to the trouble of mounting a secret meant it.
    """
    path = os.environ.get(f"{name}_FILE")
    if path:
        try:
            with open(path, encoding="utf-8") as handle:
                # `.strip()`, because `echo secret > file` leaves a newline and a trailing newline
                # in a password is invisible, survives every inspection, and fails every compare.
                return handle.read().strip()
        except OSError as error:
            raise ConfigError(f"{name}_FILE is set to {path!r} but it could not be read: {error}") from error
    return os.environ.get(name, "")


def save(values: dict[str, str], config_path: str | None = None) -> str:
    """Update `watcher.local.properties` in place, keeping everything else in it.

    Called by the login flow. Three properties, each of which matters:

    * **Comments and unknown keys survive.** The file ships with a paragraph of explanation above
      each setting, and the M12 watcher may add keys this tool knows nothing about. Rewriting it
      from a template would silently delete both.
    * **A key that is already present is edited where it stands**, so the comment above it still
      describes the line below it. Only genuinely new keys are appended.
    * **Write-temp-then-rename**, like everything else in this project (CLAUDE.md rule 10). This
      file holds the credential to a term of lectures; a half-written one after a crash would be a
      sign-in that has to be done again for no reason.

    @return the path written.
    """
    path = config_path or os.path.join(repo_root(), CONFIG_NAME)
    lines: list[str] = []
    if os.path.exists(path):
        with open(path, encoding="utf-8") as handle:
            lines = handle.read().splitlines()

    remaining = dict(values)
    out: list[str] = []
    for line in lines:
        stripped = line.strip()
        if stripped and not stripped.startswith("#") and "=" in stripped:
            key = stripped.split("=", 1)[0].strip()
            if key in remaining:
                out.append(f"{key}={remaining.pop(key)}")
                continue
        out.append(line)

    if remaining:
        if out and out[-1].strip():
            out.append("")
        out.append("# Written by `--login` (tools/web/login.py).")
        out.extend(f"{key}={value}" for key, value in remaining.items())

    temporary = f"{path}.tmp"
    with open(temporary, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(out).rstrip("\n") + "\n")
    os.replace(temporary, path)
    return path


def load(config_path: str | None = None) -> Credentials:
    """Environment first, then the properties file. Raises [ConfigError] with advice."""
    settings: dict[str, str] = {}
    path = config_path or os.path.join(repo_root(), CONFIG_NAME)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as handle:
            settings = parse_properties(handle.read())

    url = from_env(ENV_URL) or settings.get(KEY_URL, "")
    uid = from_env(ENV_UID) or settings.get(KEY_UID, "")
    password = from_env(ENV_PASSWORD) or settings.get(KEY_PASSWORD, "")
    login = from_env(ENV_LOGIN) or settings.get(KEY_LOGIN) or None

    missing = [
        name
        for name, value in ((KEY_URL, url), (KEY_UID, uid), (KEY_PASSWORD, password))
        if not value
    ]
    if missing:
        raise ConfigError(
            f"Missing {', '.join(missing)}.\n\n"
            f"Create {path} with:\n\n"
            f"    {KEY_URL}=https://your-nextcloud\n"
            f"    {KEY_UID}=your-uid\n"
            f"    {KEY_PASSWORD}=your-app-password\n\n"
            "It is gitignored. Mint the app password in Nextcloud under\n"
            'Settings -> Security -> "Create new app password" - not your account password.'
        )

    if not url.startswith("https://"):
        raise ConfigError(
            f"{KEY_URL} must be https:// - refusing to send an app password in the clear.\n"
            f"Got: {url}"
        )

    return Credentials(url=url.rstrip("/"), uid=uid, password=password, login=login)
