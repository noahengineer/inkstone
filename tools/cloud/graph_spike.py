#!/usr/bin/env python3
"""P0 of docs/CLOUD-PROVIDERS.md: measure what Microsoft Graph really does, before any Kotlin depends on it.

Run this against a THROWAWAY personal Microsoft account (or at least one you do not mind a folder
named ``Inkstone-spike-<time>`` appearing in). It answers M-M1 .. M-M8 of the design doc:

  M-M1  does a stale If-Match on createUploadSession give 412 (id-addressed AND path-addressed)?
  M-M2  does conflictBehavior=fail on an existing name give 409 and leave the old bytes alone?
  M-M3  which delta endpoints exist (drive root / approot / a folder)?
  M-M4  does the eTag move on a metadata-only change?
  M-M5  is If-Match on move checked against the file or the folder?
  M-M6  is an unfinished upload session invisible (no torn remote file)?
  M-M7  what does delta report after a move and after a folder rename?
  M-M8  does If-Match on DELETE work, and does DELETE go to the recycle bin?

How it signs in: authorization-code + PKCE in YOUR browser, redirect to http://localhost:<port>/.
This script never sees your password. The tokens live in memory only: they are not printed, not
written to disk, and not included in the results file.

Requirements: Python 3.9+, standard library only. Azure app registration: see the doc, section 6.1;
the redirect URI ``http://localhost`` must be listed under "Mobile and desktop applications".

    py tools/cloud/graph_spike.py --client-id <Application (client) ID>
    py tools/cloud/graph_spike.py --client-id <id> --approot      # use Files.ReadWrite.AppFolder instead

The results go to ``graph-spike-results.json`` (override with --out). Nothing here is the app's
code: it is a measuring instrument, and its own mistakes are possible — read the raw statuses.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import secrets
import ssl
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer

GRAPH = "https://graph.microsoft.com/v1.0"
AUTHORITY = "https://login.microsoftonline.com/common/oauth2/v2.0"
FRAGMENT = 327_680  # 320 KiB — the documented multiple for upload-session ranges


# ---------------------------------------------------------------------------------------------
# TLS: Python 3.14's strict mode refuses chains every browser accepts (CLAUDE.md). The repo's own
# helper relaxes exactly one flag, only for that failure, and says so.
# ---------------------------------------------------------------------------------------------

def _context(host: str) -> ssl.SSLContext:
    here = os.path.dirname(os.path.abspath(__file__))
    sys.path.insert(0, os.path.join(here, "..", "web"))
    try:
        import tlsdiag  # type: ignore

        return tlsdiag.context_for(host)
    except Exception:  # noqa: BLE001 - the helper is optional; fall back to the default context
        return ssl.create_default_context()
    finally:
        sys.path.pop(0)


class Response:
    def __init__(self, status: int, headers: dict[str, str], body: bytes) -> None:
        self.status = status
        self.headers = {k.lower(): v for k, v in headers.items()}
        self.body = body

    def json(self) -> dict:
        try:
            return json.loads(self.body.decode("utf-8")) if self.body else {}
        except ValueError:
            return {}

    def error_code(self) -> str | None:
        err = self.json().get("error")
        if isinstance(err, dict):
            return err.get("code")
        return err if isinstance(err, str) else None


def http(method: str, url: str, token: str | None = None, headers: dict[str, str] | None = None,
         body: bytes | None = None, json_body: dict | None = None, retries_429: int = 3) -> Response:
    """One request. Never raises on a non-2xx. A 429 is waited out (this is a spike, not the app)."""
    hdrs = dict(headers or {})
    if token:
        hdrs["Authorization"] = f"Bearer {token}"
    data = body
    if json_body is not None:
        data = json.dumps(json_body).encode("utf-8")
        hdrs.setdefault("Content-Type", "application/json")
    host = urllib.parse.urlsplit(url).hostname or ""
    for attempt in range(retries_429 + 1):
        req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
        try:
            with urllib.request.urlopen(req, context=_context(host), timeout=60) as resp:
                return Response(resp.status, dict(resp.headers), resp.read())
        except urllib.error.HTTPError as err:
            reply = Response(err.code, dict(err.headers), err.read())
            if err.code == 429 and attempt < retries_429:
                time.sleep(min(int(reply.headers.get("retry-after", "5")), 60))
                continue
            return reply
    raise AssertionError("unreachable")


# ---------------------------------------------------------------------------------------------
# Sign-in: PKCE in the user's own browser. No password ever reaches this process.
# ---------------------------------------------------------------------------------------------

def sign_in(client_id: str, scopes: str) -> str:
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(16)
    box: dict[str, str] = {}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802 - http.server's name
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            box.update({k: v[0] for k, v in query.items()})
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            self.wfile.write(b"Signed in. You can close this tab and return to the terminal.")

        def log_message(self, *args: object) -> None:  # silence: the URL carries the auth code
            return

    server = HTTPServer(("127.0.0.1", 0), Handler)
    redirect = f"http://localhost:{server.server_port}/"
    threading.Thread(target=server.handle_request, daemon=True).start()

    url = AUTHORITY + "/authorize?" + urllib.parse.urlencode({
        "client_id": client_id, "response_type": "code", "redirect_uri": redirect,
        "response_mode": "query", "scope": scopes, "state": state,
        "code_challenge": challenge, "code_challenge_method": "S256", "prompt": "select_account",
    })
    print("Opening your browser to sign in to Microsoft. If it does not open, paste this URL:\n" + url + "\n")
    webbrowser.open(url)
    for _ in range(300):
        if box:
            break
        time.sleep(1)
    server.server_close()
    if box.get("state") != state or "code" not in box:
        raise SystemExit(f"Sign-in did not complete: {box.get('error')} {box.get('error_description')}")

    reply = http("POST", AUTHORITY + "/token", body=urllib.parse.urlencode({
        "client_id": client_id, "grant_type": "authorization_code", "code": box["code"],
        "redirect_uri": redirect, "code_verifier": verifier, "scope": scopes,
    }).encode(), headers={"Content-Type": "application/x-www-form-urlencoded"})
    if reply.status != 200:
        raise SystemExit(f"Token exchange failed: HTTP {reply.status} {reply.error_code()}")
    return reply.json()["access_token"]


# ---------------------------------------------------------------------------------------------
# A tiny driver around the Graph calls the app will make.
# ---------------------------------------------------------------------------------------------

class Drive:
    def __init__(self, token: str, base: str) -> None:
        self.token = token
        self.base = base  # "/me/drive/root" or "/me/drive/special/approot"

    def req(self, method: str, path: str, **kw) -> Response:
        url = path if path.startswith("http") else GRAPH + path
        return http(method, url, self.token, **kw)

    def mkdir(self, parent_id: str | None, name: str) -> Response:
        under = f"{self.base}/children" if parent_id is None else f"/me/drive/items/{parent_id}/children"
        return self.req("POST", under, json_body={
            "name": name, "folder": {}, "@microsoft.graph.conflictBehavior": "fail"})

    def upload(self, target: str, data: bytes, if_match: str | None = None,
               behavior: str | None = None, stop_before_last: bool = False) -> tuple[Response, Response | None]:
        """createUploadSession at ``target`` (".../items/{id}" or ".../root:/path:") then ranged PUTs.

        Returns (session-creation response, final PUT response or None). With ``stop_before_last`` the
        final fragment is withheld, which is how M-M6 looks at an unfinished session.
        """
        headers = {"If-Match": if_match} if if_match else {}
        item: dict = {}
        if behavior:
            item["@microsoft.graph.conflictBehavior"] = behavior
        session = self.req("POST", target + "/createUploadSession", headers=headers, json_body={"item": item})
        if session.status != 200:
            return session, None
        upload_url = session.json()["uploadUrl"]
        total = len(data)
        last: Response | None = None
        offsets = list(range(0, total, FRAGMENT)) or [0]
        for index, start in enumerate(offsets):
            end = min(start + FRAGMENT, total) - 1
            is_last = index == len(offsets) - 1
            if is_last and stop_before_last:
                break
            # The upload URL is pre-authorised: sending the bearer token to it is wrong and unneeded.
            last = http("PUT", upload_url, headers={
                "Content-Length": str(end - start + 1),
                "Content-Range": f"bytes {start}-{end}/{total}"}, body=data[start:end + 1])
            if last.status not in (200, 201, 202):
                break
        return session, last

    def content(self, item_id: str) -> bytes | None:
        reply = self.req("GET", f"/me/drive/items/{item_id}/content")
        return reply.body if reply.status == 200 else None

    def meta(self, item_id: str) -> dict:
        return self.req("GET", f"/me/drive/items/{item_id}").json()


def summarise(r: Response | None) -> dict:
    if r is None:
        return {"status": None}
    out: dict = {"status": r.status}
    code = r.error_code()
    if code:
        out["error"] = code
    return out


# ---------------------------------------------------------------------------------------------
# The measurements. Each is independent and records raw statuses beside the verdict.
# ---------------------------------------------------------------------------------------------

def measure(d: Drive, root_id: str, results: dict) -> None:
    payload_a = b"A" * 1000
    payload_b = b"B" * 1000
    payload_c = b"C" * 1000

    # --- M-M1 / M-M2 ------------------------------------------------------------------------
    m1: dict = {}
    s, f = d.upload(f"/me/drive/items/{root_id}:/probe.bin:", payload_a, behavior="fail")
    m1["create"] = {"session": summarise(s), "final": summarise(f)}
    item = f.json() if f is not None else {}
    item_id, etag1 = item.get("id"), item.get("eTag")
    m1["etag_after_create_present"] = bool(etag1)
    if item_id:
        s, f = d.upload(f"/me/drive/items/{item_id}", payload_b, if_match=etag1)
        m1["update_with_current_etag"] = {"session": summarise(s), "final": summarise(f)}
        etag2 = (f.json().get("eTag") if f is not None else None) or d.meta(item_id).get("eTag")
        m1["etag_changed_by_update"] = etag2 != etag1

        s, f = d.upload(f"/me/drive/items/{item_id}", payload_c, if_match=etag1)  # STALE
        m1["update_with_STALE_etag_by_id"] = {"session": summarise(s), "final": summarise(f)}
        m1["bytes_untouched_after_stale_by_id"] = d.content(item_id) == payload_b

        s, f = d.upload(f"/me/drive/items/{root_id}:/probe.bin:", payload_c, if_match=etag1, behavior="replace")
        m1["update_with_STALE_etag_by_PATH"] = {"session": summarise(s), "final": summarise(f)}
        m1["bytes_untouched_after_stale_by_path"] = d.content(item_id) == payload_b

        reply = d.req("PUT", f"/me/drive/items/{item_id}/content", headers={
            "If-Match": etag1, "Content-Type": "application/octet-stream"}, body=payload_c)
        m1["simple_PUT_with_STALE_etag"] = summarise(reply)
        m1["bytes_untouched_after_simple_put"] = d.content(item_id) == payload_b
        m1["VERDICT_rule1_holds_via_upload_session"] = (
            m1["update_with_STALE_etag_by_id"]["session"]["status"] == 412 and m1["bytes_untouched_after_stale_by_id"])
    results["M-M1"] = m1

    m2: dict = {}
    s, f = d.upload(f"/me/drive/items/{root_id}:/probe.bin:", payload_c, behavior="fail")
    m2["create_over_existing_with_fail"] = {"session": summarise(s), "final": summarise(f)}
    m2["old_bytes_untouched"] = bool(item_id) and d.content(item_id) == payload_b
    m2["VERDICT"] = s.status in (409, 412) and m2["old_bytes_untouched"]
    results["M-M2"] = m2

    # --- M-M3 -----------------------------------------------------------------------------
    m3: dict = {}
    m3["drive_root_delta_token_latest"] = summarise(d.req("GET", "/me/drive/root/delta?token=latest"))
    m3["approot_delta_token_latest"] = summarise(d.req("GET", "/me/drive/special/approot/delta?token=latest"))
    m3["folder_delta_token_latest"] = summarise(d.req("GET", f"/me/drive/items/{root_id}/delta?token=latest"))
    results["M-M3"] = m3

    # --- M-M4 -----------------------------------------------------------------------------
    m4: dict = {}
    if item_id:
        before = d.meta(item_id)
        again = d.meta(item_id)
        m4["etag_stable_across_two_gets"] = before.get("eTag") == again.get("eTag")
        m4["hash_keys_present"] = sorted((before.get("file", {}).get("hashes") or {}).keys())
        patch = d.req("PATCH", f"/me/drive/items/{item_id}", json_body={"description": "spike touch"})
        m4["description_patch"] = summarise(patch)
        after = d.meta(item_id)
        m4["etag_moved_on_metadata_only_change"] = after.get("eTag") != before.get("eTag")
        m4["ctag_moved_on_metadata_only_change"] = after.get("cTag") != before.get("cTag")
        m4["content_hash_unchanged"] = (before.get("file", {}).get("hashes")
                                         == after.get("file", {}).get("hashes"))
    results["M-M4"] = m4

    # --- M-M5 / M-M7 / M-M8 -------------------------------------------------------------------
    folder_a = d.mkdir(root_id, "A").json()
    folder_b = d.mkdir(root_id, "B").json()
    a_id, b_id = folder_a.get("id"), folder_b.get("id")
    delta_token = d.req("GET", "/me/drive/root/delta?token=latest").json().get("@odata.deltaLink")

    s, f = d.upload(f"/me/drive/items/{a_id}:/moving.bin:", payload_a, behavior="fail")
    mv = f.json() if f is not None else {}
    mv_id, mv_etag = mv.get("id"), mv.get("eTag")
    m5: dict = {}
    if mv_id and b_id:
        d.req("PATCH", f"/me/drive/items/{mv_id}", json_body={"description": "make the file etag stale"})
        stale = d.req("PATCH", f"/me/drive/items/{mv_id}", headers={"If-Match": mv_etag},
                      json_body={"parentReference": {"id": b_id}})
        m5["move_with_STALE_FILE_etag"] = summarise(stale)
        m5["file_still_in_A_after_stale_move"] = d.meta(mv_id).get("parentReference", {}).get("id") == a_id
        current = d.meta(mv_id).get("eTag")
        good = d.req("PATCH", f"/me/drive/items/{mv_id}", headers={"If-Match": current},
                     json_body={"parentReference": {"id": b_id}})
        m5["move_with_CURRENT_file_etag"] = summarise(good)
        m5["same_id_after_move"] = good.json().get("id") == mv_id
        m5["etag_after_move_changed"] = good.json().get("eTag") != current
    results["M-M5"] = m5

    m7: dict = {}
    if a_id and delta_token:
        rename = d.req("PATCH", f"/me/drive/items/{b_id}", json_body={"name": "B-renamed"})
        m7["folder_rename"] = summarise(rename)
        time.sleep(5)  # delta lags a little on personal drives
        pages, url = [], delta_token
        for _ in range(5):
            reply = d.req("GET", url)
            pages.append(reply.json())
            url = reply.json().get("@odata.nextLink")
            if not url:
                break
        seen = [i for p in pages for i in p.get("value", [])]
        m7["items_in_delta"] = [{"id_is_file": i.get("id") == mv_id, "id_is_folder_B": i.get("id") == b_id,
                                 "name": i.get("name"), "has_parent_id": bool(i.get("parentReference", {}).get("id")),
                                 "has_parent_path": "path" in (i.get("parentReference") or {}),
                                 "deleted": "deleted" in i, "eTag_present": "eTag" in i} for i in seen]
        m7["file_reported_after_folder_rename"] = any(i.get("id") == mv_id for i in seen)
        m7["folder_B_reported"] = any(i.get("id") == b_id for i in seen)
    results["M-M7"] = m7

    # --- M-M6 -----------------------------------------------------------------------------
    m6: dict = {}
    big = bytes(range(256)) * (3 * FRAGMENT // 256) + b"tail"  # four ranges: three full, one short
    s, partial = d.upload(f"/me/drive/items/{root_id}:/unfinished.bin:", big, behavior="fail", stop_before_last=True)
    m6["new_file_unfinished_session"] = summarise(partial)
    visible = d.req("GET", f"/me/drive/items/{root_id}:/unfinished.bin:")
    m6["new_file_visible_before_final_fragment"] = visible.status == 200
    if item_id:
        meta = d.meta(item_id)
        s, partial = d.upload(f"/me/drive/items/{item_id}", big, if_match=meta.get("eTag"), stop_before_last=True)
        m6["update_unfinished_session"] = summarise(partial)
        m6["old_bytes_still_served_before_final_fragment"] = d.content(item_id) == payload_b
    m6["VERDICT_no_torn_remote_file"] = (m6.get("new_file_visible_before_final_fragment") is False
                                         and m6.get("old_bytes_still_served_before_final_fragment") is True)
    results["M-M6"] = m6

    # --- M-M8 -----------------------------------------------------------------------------
    m8: dict = {}
    if item_id:
        current = d.meta(item_id).get("eTag")
        stale_delete = d.req("DELETE", f"/me/drive/items/{item_id}", headers={"If-Match": "\"stale-etag\""})
        m8["delete_with_STALE_etag"] = summarise(stale_delete)
        m8["file_survives_stale_delete"] = d.meta(item_id).get("id") == item_id
        good_delete = d.req("DELETE", f"/me/drive/items/{item_id}", headers={"If-Match": current})
        m8["delete_with_CURRENT_etag"] = summarise(good_delete)
        m8["gone_from_folder_afterwards"] = d.req("GET", f"/me/drive/items/{item_id}").status == 404
        # Whether it is in the recycle bin is not observable through v1.0 on personal drives without
        # a second call; say so rather than guess.
        m8["recycle_bin_checked"] = False
    results["M-M8"] = m8


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--client-id", required=True)
    parser.add_argument("--approot", action="store_true", help="use Files.ReadWrite.AppFolder and the app folder")
    parser.add_argument("--out", default="graph-spike-results.json")
    parser.add_argument("--keep", action="store_true", help="leave the spike folder in place")
    args = parser.parse_args()

    scopes = ("Files.ReadWrite.AppFolder" if args.approot else "Files.ReadWrite") + " offline_access User.Read"
    token = sign_in(args.client_id, scopes)
    base = "/me/drive/special/approot" if args.approot else "/me/drive/root"
    d = Drive(token, base)

    me = d.req("GET", "/me/drive")
    results: dict = {
        "run_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "scopes": scopes,
        "drive_type": me.json().get("driveType"),
        "drive_status": me.status,
    }
    if me.status != 200:
        print("Could not read /me/drive:", me.status, me.error_code())
        return 1

    spike = d.mkdir(None, "Inkstone-spike-" + time.strftime("%Y%m%d-%H%M%S", time.gmtime()))
    results["spike_folder"] = summarise(spike)
    root_id = spike.json().get("id")
    if not root_id:
        print("Could not create the spike folder:", spike.status, spike.error_code())
        return 1
    try:
        measure(d, root_id, results)
    finally:
        if not args.keep:
            # DELETE goes to the recycle bin (the doc says so); nothing here is hard-deleted.
            results["cleanup"] = summarise(d.req("DELETE", f"/me/drive/items/{root_id}"))

    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(results, handle, indent=2, sort_keys=True)
    print(json.dumps(results, indent=2, sort_keys=True))
    print(f"\nWritten to {args.out}. No token is in it.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
