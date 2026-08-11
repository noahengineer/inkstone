#!/usr/bin/env python3
"""The bit of WebDAV M12's watcher needs, and nothing more (docs/M12-AUDIO.md §2).

Stdlib only. Five verbs — PROPFIND, GET, PUT, MKCOL, DELETE — because that is what the job is,
and a dependency on a DAV library would be a second implementation of rules `:core:sync` already
owns and gets right.

⚠️ **`DELETE` on Nextcloud is soft: the file lands in the server's trash.** That is the whole
safety net behind §8 — a mis-transcription is recoverable for as long as the server's retention
window lasts. Nothing here ever hard-deletes, and nothing here should ever grow the ability to.
"""

import base64
import json
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

DAV_NS = "{DAV:}"
OC_NS = "{http://owncloud.org/ns}"


def parse_multistatus(xml: bytes) -> ET.Element:
    """Parse a WebDAV multistatus, refusing any document that declares a DTD.

    ⚠️ `xml.etree.ElementTree` is not hardened against **entity-expansion** attacks — the
    "billion laughs" shape, where a few hundred bytes of nested entity definitions expand to
    gigabytes and take the process with them. It does not resolve *external* entities, so classic
    XXE file disclosure is not the exposure here; resource exhaustion is.

    The usual answer is `defusedxml`, and the reason it is not used is not laziness: these tools
    are stdlib-only so they run on a Pi with nothing installed, and the guard below is complete for
    this input rather than merely likely to help. **A WebDAV multistatus never legitimately
    contains a DTD or an entity declaration**, so refusing one outright removes the entire attack
    surface without a dependency and without a heuristic.

    "It is our own Nextcloud, so the XML is trustworthy" is the reasoning this would otherwise rest
    on, and it is the reasoning that ages worst — the server is reachable over a network, may be
    shared, and is exactly the thing an attacker who got that far would use next.
    """
    head = xml[:4096].lstrip()
    lowered = head.lower()
    if b"<!doctype" in lowered or b"<!entity" in lowered:
        raise ValueError("refusing an XML document that declares a DTD or entities")
    return ET.fromstring(xml)


class Nextcloud:
    """One account, addressed by its **uid** — not its login name (SPEC §0.1 #3)."""

    def __init__(self, base_url: str, uid: str, password: str, login: str | None = None):
        self.base = base_url.rstrip("/")
        self.uid = uid
        # ⚠️ Basic auth is built by hand rather than with `urllib`'s handler, for the reason
        # CLAUDE.md rule 5 gives on the app side: a challenge-response handler sends an
        # unauthenticated request first and takes a deliberate 401 every single time, which is
        # indistinguishable from a wrong password to a server counting failures for brute-force
        # protection. Attaching the header up front means one request and no false failures.
        token = base64.b64encode(f"{login or uid}:{password}".encode()).decode()
        self.auth = f"Basic {token}"

    def _dav(self, path: str) -> str:
        """⚠️ **Percent-encoded, per segment.** Without this a folder called `Chemistry Y12` raises
        `InvalidURL: URL can't contain control characters` before a socket is opened — and folders
        with spaces are the normal case, not the exotic one. `safe="/"` keeps the separators while
        encoding everything else, so `#`, `?` and `&` in a note's name cannot terminate the path
        or graft a query string onto it either.
        """
        quoted = urllib.parse.quote(path.lstrip("/"), safe="/")
        return f"{self.base}/remote.php/dav/files/{self.uid}/{quoted}"

    def _call(self, method: str, url: str, body: bytes | None = None, headers: dict | None = None):
        request = urllib.request.Request(url, data=body, method=method)
        request.add_header("Authorization", self.auth)
        for k, v in (headers or {}).items():
            request.add_header(k, v)
        return urllib.request.urlopen(request)

    def whoami(self) -> dict:
        """SPEC §7.2's confirmation call — also the cheapest proof the credential works at all."""
        url = f"{self.base}/ocs/v2.php/cloud/user?format=json"
        with self._call("GET", url, headers={"OCS-APIRequest": "true"}) as response:
            return json.loads(response.read())["ocs"]["data"]

    def listing(self, path: str, depth: str = "1") -> list[dict]:
        """`PROPFIND`. Returns `{href, name, is_collection, is_self, size, content_type}` per entry.

        Returns `[]` on 404 rather than raising: "that folder is not there" is an ordinary answer
        for a watcher polling for something that may not exist yet.

        ⚠️ **A `Depth: 1` listing includes the collection itself**, and it is flagged `is_self`
        rather than dropped. Dropping it here would be the tidier-looking API and would quietly
        break [exists], which asks with `Depth: 0` and gets *only* that entry — so "filter out the
        self entry" and "answer whether this exists" would be the same operation with opposite
        needs. A caller that walks folders skips `is_self`; a caller that probes one does not.
        """
        body = (
            '<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">'
            "<d:prop><d:resourcetype/><d:getcontentlength/><d:getetag/><d:getcontenttype/>"
            "<oc:fileid/></d:prop></d:propfind>"
        ).encode()
        try:
            with self._call(
                "PROPFIND", self._dav(path), body,
                {"Depth": depth, "Content-Type": "application/xml"},
            ) as response:
                xml = response.read()
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return []
            raise

        # The href the server will use for the resource that was asked about. Compared path-only
        # and unquoted, because a server is free to return an absolute URL, to percent-encode
        # differently from the request, and to add or drop the trailing slash.
        asked = urllib.parse.unquote(urllib.parse.urlsplit(self._dav(path)).path).rstrip("/")

        out = []
        for entry in parse_multistatus(xml).findall(f"{DAV_NS}response"):
            href = urllib.parse.unquote(entry.findtext(f"{DAV_NS}href") or "")
            props = entry.find(f"{DAV_NS}propstat/{DAV_NS}prop")
            collection = props is not None and props.find(f"{DAV_NS}resourcetype/{DAV_NS}collection") is not None
            out.append({
                "href": href,
                "name": href.rstrip("/").rsplit("/", 1)[-1],
                "is_collection": collection,
                "is_self": urllib.parse.urlsplit(href).path.rstrip("/") == asked,
                "size": int((props.findtext(f"{DAV_NS}getcontentlength") or 0) if props is not None else 0),
                "content_type": (props.findtext(f"{DAV_NS}getcontenttype") if props is not None else None),
            })
        return out

    def exists(self, path: str) -> bool:
        return bool(self.listing(path, depth="0"))

    def get(self, path: str) -> bytes:
        with self._call("GET", self._dav(path)) as response:
            return response.read()

    def put(self, path: str, data: bytes, content_type: str) -> None:
        self._call("PUT", self._dav(path), data, {"Content-Type": content_type}).close()

    def mkcol(self, path: str) -> None:
        """Idempotent: 405 means it is already there, which is success (SPEC §7.6)."""
        try:
            self._call("MKCOL", self._dav(path)).close()
        except urllib.error.HTTPError as error:
            if error.code != 405:
                raise

    def delete(self, path: str) -> None:
        """⚠️ Soft on Nextcloud — the file goes to the server's trash, never away. See the module doc."""
        self._call("DELETE", self._dav(path)).close()
