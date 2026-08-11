#!/usr/bin/env python3
"""⭐ M12's server half: watch Nextcloud, transcribe what arrives, write the transcript back.

    python tools/m12/watch.py --once            # one pass, then exit
    python tools/m12/watch.py --interval 300    # poll every five minutes
    python tools/m12/watch.py --once --dry-run  # find work, change nothing

This is the thing §2 describes and the reason the app holds no API key:

    tablet ──uploads──► Nextcloud ──watched by this──► transcript back into Nextcloud
                                                        │
                                            audio → Nextcloud's trash, after

⚠️ **THE ORDERING IS THE SAFETY NET, NOT AN OPTIMISATION** (§8). The transcript is written **and
verified present on the server** before the audio is touched, and the audio is *trashed*, never
deleted. The same reasoning as `materialize` writing the bundle before resetting the journal: a
crash between the two steps must leave a **duplicate, never a hole**. If AssemblyAI mishears
`y = mx + c` as *"why equals MX plus sea"*, the lecture is otherwise gone for good.

WHAT IT WILL NOT DO
-------------------
* **Never hard-delete.** `DELETE` on Nextcloud is soft; that is the whole recovery window.
* **Never retry a 401** (CLAUDE.md rule 5). Nextcloud's brute-force protection is on by default
  and keyed by IP, so a polling loop with a stale password is how a device gets its own server to
  block it. One 401 stops the pass.
* **Never transcribe twice.** A recording whose `.md` already exists is skipped, so a crash
  anywhere in a pass costs at most one repeated download — not a repeated bill.
* **Never touch the `.timeline`.** It is small, it is the alignment data, and §8 only ever
  justified deleting the *audio*.

CREDENTIALS, all from the environment so a container needs no files:
    INKSTONE_NC_URL        https://cloud.example/
    INKSTONE_NC_UID        the account's uid — NOT the login name (SPEC §0.1 #3)
    INKSTONE_NC_PASSWORD   an app password, created in Nextcloud's own UI
    ASSEMBLYAI_API_KEY     falls back to assemblyai.local.properties at the repo root
"""

import argparse
import os
import sys
import time
import urllib.error

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import align  # noqa: E402
import transcribe  # noqa: E402
from nextcloud import Nextcloud  # noqa: E402

MEDIA_DIR = ".inkstone-media"
AUDIO_EXT = ".opus"
TIMELINE_EXT = ".timeline"
TRANSCRIPT_EXT = ".md"

# How deep below the account root to look for a media directory. Three levels covers
# `Subject/Topic/.inkstone-media`, and bounding it is deliberate: an unbounded crawl of somebody's
# whole Nextcloud is exactly what `RemoteWalk`'s rule 3 refuses to do on the device, and the
# reasoning ("the user's Nextcloud contains their photo library") does not stop being true here.
MAX_DEPTH = 3


class Recording:
    """One `.opus` found on the server, and where everything about it lives."""

    def __init__(self, directory: str, recording_id: str, size: int):
        self.directory = directory
        self.id = recording_id
        self.size = size
        self.audio = f"{directory}/{recording_id}{AUDIO_EXT}"
        self.timeline = f"{directory}/{recording_id}{TIMELINE_EXT}"
        self.transcript = f"{directory}/{recording_id}{TRANSCRIPT_EXT}"
        # `<folder>/.inkstone-media/<documentId>` — the note's identity is the path segment, never
        # the filename beside it (§6). That is also what makes this survive the note being renamed.
        self.document_id = directory.rsplit("/", 1)[-1]


def discover(nc: Nextcloud, root: str = "") -> list[Recording]:
    """Every recording on the server that has no transcript yet.

    Walks breadth-first to [MAX_DEPTH], descending into ordinary folders and stopping at a
    `.inkstone-media`, whose children are the per-note directories.
    """
    found: list[Recording] = []
    frontier = [(root, 0)]
    seen: set[str] = set()
    while frontier:
        path, depth = frontier.pop(0)
        if path in seen:
            continue
        seen.add(path)
        for entry in nc.listing(path):
            # ⚠️ `is_self`, not a name comparison. At the account root the collection's own name is
            # the *uid*, which matches nothing and so would be walked as though it were a child —
            # rediscovering the root one level down, and again, to the depth bound.
            if entry["is_self"] or not entry["is_collection"]:
                continue
            name = entry["name"]
            child = f"{path}/{name}".lstrip("/")
            if name == MEDIA_DIR:
                found.extend(_recordings_under(nc, child))
            elif depth < MAX_DEPTH and not name.startswith("."):
                frontier.append((child, depth + 1))
    return found


def _recordings_under(nc: Nextcloud, media_dir: str) -> list[Recording]:
    out: list[Recording] = []
    for note_dir in nc.listing(media_dir):
        if note_dir["is_self"] or not note_dir["is_collection"]:
            continue
        directory = f"{media_dir}/{note_dir['name']}"
        entries = nc.listing(directory)
        names = {e["name"] for e in entries}
        for entry in entries:
            if entry["is_self"] or entry["is_collection"] or not entry["name"].endswith(AUDIO_EXT):
                continue
            recording_id = entry["name"][: -len(AUDIO_EXT)]
            # Idempotency, and the reason a crash costs nothing: a recording that already has its
            # transcript is finished work, whatever state anything else is in.
            if f"{recording_id}{TRANSCRIPT_EXT}" in names:
                continue
            out.append(Recording(directory, recording_id, entry["size"]))
    return out


def note_title(nc: Nextcloud, recording: Recording) -> str:
    """The note's own name, for the transcript's front matter.

    ⚠️ Best-effort by design. The path gives a `documentId` and a parent folder, not a filename,
    and matching the id properly would mean downloading each PDF and reading `manifest.json` out of
    its embedded bundle. A wrong *title* costs a cosmetic line; downloading every PDF in a folder on
    every pass costs the user's bandwidth forever. So: one sibling PDF means that is the note; more
    than one is ambiguous and the id is used instead, which is at least never wrong.
    """
    # ⚠️ Split on the segment, not on `/<MEDIA_DIR>/`. A note in the account **root** has its
    # media at `.inkstone-media/<documentId>` with no leading slash (RemoteMedia.directory), so the
    # slash-wrapped form matches nothing and hands back the media path itself as the "parent".
    parent = recording.directory.rsplit(MEDIA_DIR, 1)[0].rstrip("/")
    pdfs = [
        e["name"] for e in nc.listing(parent)
        if not e["is_self"] and not e["is_collection"] and e["name"].endswith(".pdf")
    ]
    return pdfs[0][:-4] if len(pdfs) == 1 else recording.document_id


def process(nc: Nextcloud, recording: Recording, api_key: str, dry_run: bool) -> bool:
    """One recording, end to end. Returns True if a transcript was written."""
    minutes = recording.size * 8 / 34_300 / 60
    print(f"  {recording.id} — {recording.size:,} bytes ~ {minutes:.1f} min ~ ${minutes / 60 * 0.23:.2f}")
    if dry_run:
        print("    (dry run — nothing sent, nothing written)")
        return False

    audio = nc.get(recording.audio)
    # A lecture nobody wrote in is ordinary; an absent timeline is empty, not an error.
    timeline_text = nc.get(recording.timeline).decode("utf-8") if nc.exists(recording.timeline) else ""

    print("    transcribing…")
    payload = transcribe.run(audio, api_key)
    if payload is None:
        # ⚠️ Deliberately not retried and not fatal to the pass. §11: an empty or failed transcript
        # is a "Needs attention" outcome, never a blank file written over a real lecture — and the
        # audio stays exactly where it is, so nothing is lost by giving up here.
        print("    FAILED — leaving the audio in place and moving on")
        return False

    words = align.parse_transcript(payload)
    audio_ms = int(float(payload.get("audio_duration") or 0) * align.MS_PER_SECOND)
    markdown = align.render(
        words=words,
        timeline=align.parse_timeline(timeline_text),
        audio_ms=audio_ms,
        note_title=note_title(nc, recording),
        document_id=recording.document_id,
        recorded_at=recording.id,
        model=payload.get("speech_model_used") or payload.get("speech_model") or "unknown",
    )

    nc.put(recording.transcript, markdown.encode("utf-8"), "text/markdown; charset=utf-8")

    # ⚠️ §8, and the order is the whole point: **verify it is really there** before touching the
    # audio. A PUT that returned 2xx and a file that exists are not the same claim — a proxy, a
    # quota, or a half-written body can separate them — and the difference is a lecture.
    if not nc.exists(recording.transcript):
        print("    transcript did not appear on the server — audio left untouched")
        return False

    print(f"    wrote {recording.transcript} ({len(markdown):,} chars, {len(words):,} words)")
    nc.delete(recording.audio)  # soft: into Nextcloud's trash, where it can be recovered
    print("    audio moved to the server's trash")
    return True


def pass_once(nc: Nextcloud, api_key: str, dry_run: bool) -> int:
    recordings = discover(nc)
    if not recordings:
        print("nothing to do")
        return 0
    print(f"{len(recordings)} recording(s) awaiting a transcript")
    done = 0
    for recording in recordings:
        try:
            if process(nc, recording, api_key, dry_run):
                done += 1
        except urllib.error.HTTPError as error:
            if error.code == 401:
                # CLAUDE.md rule 5. Stop the whole pass — do not try the next one with the same
                # credential, which is what turns one refusal into a brute-force lockout.
                print("401 — halting this pass. Check the app password.", file=sys.stderr)
                raise SystemExit(2)
            print(f"    HTTP {error.code} on {recording.id}; leaving it for the next pass", file=sys.stderr)
    return done


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--once", action="store_true")
    ap.add_argument("--interval", type=int, default=300, help="seconds between passes")
    ap.add_argument("--dry-run", action="store_true", help="find work, send nothing, change nothing")
    args = ap.parse_args()

    url = os.environ.get("INKSTONE_NC_URL")
    uid = os.environ.get("INKSTONE_NC_UID")
    password = os.environ.get("INKSTONE_NC_PASSWORD")
    if not (url and uid and password):
        raise SystemExit("Set INKSTONE_NC_URL, INKSTONE_NC_UID and INKSTONE_NC_PASSWORD.")

    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    api_key = "" if args.dry_run else transcribe.api_key(repo_root)

    nc = Nextcloud(url, uid, password, login=os.environ.get("INKSTONE_NC_LOGIN"))
    who = nc.whoami()
    print(f"connected as {who.get('id')} ({who.get('display-name')}) at {url}")

    if args.once:
        pass_once(nc, api_key, args.dry_run)
        return 0
    while True:
        try:
            pass_once(nc, api_key, args.dry_run)
        except SystemExit:
            raise
        except Exception as failure:  # noqa: BLE001 — a watcher that dies on one bad pass is useless
            print(f"pass failed: {failure}", file=sys.stderr)
        time.sleep(args.interval)


if __name__ == "__main__":
    raise SystemExit(main())
