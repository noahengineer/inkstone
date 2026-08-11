#!/usr/bin/env python3
"""Send one recording to AssemblyAI and save the transcript JSON (M12, docs/M12-AUDIO.md §2).

    python tools/m12/transcribe.py lecture.opus --out lecture.json

⚠️ **This spends money and sends a classroom to a third party.** Both are the point of §2 — the
app never holds a key and never talks to AssemblyAI, the server does — but neither should ever
happen by accident, so this refuses to run without an explicit `--yes` when the audio is long
enough to cost more than a few cents.

CREDENTIALS
-----------
`ASSEMBLYAI_API_KEY` first, then `assemblyai.local.properties` at the repo root. Never a literal
in this file, and `.gitignore` excludes every `*.local.properties` — §2's *"an API key shipped in
an APK is extractable"* argument applies just as well to a git history, which keeps things after
they are deleted.

THE FLOW, AND THE THREE THINGS THE DOCS ACTUALLY SAY
----------------------------------------------------
Three calls: `POST /v2/upload` with the raw bytes, `POST /v2/transcript` with the returned
`upload_url`, then poll `GET /v2/transcript/{id}` until `completed` or `error`.

  * **Ogg/Opus is accepted directly.** No transcoding — everything is resampled to 16 kHz server
    side, so converting first would cost quality for nothing. This is what makes §4's container
    choice free rather than merely tolerable.
  * **`speaker_labels` requires `punctuate`.** A documented hard dependency; turning punctuation
    off to simplify parsing silently takes diarization with it.
  * **A webhook carries `{transcript_id, status}` and not the transcript**, so the `GET` is
    required either way. Polling is therefore not the lazy option, it is the whole option.

⚠️ `speech_models` is a *fallback list*, so the model that ran is not necessarily the one asked
for. The response's `speech_model_used` is what goes in the artifact's front matter.

Stdlib only, like `verify-ogg-opus.py` — `urllib` is enough for three requests and it keeps this
runnable on a Pi with nothing installed.
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = "https://api.assemblyai.com/v2"

# Costs about $0.23/hour. Anything over this needs `--yes`; below it, a mistake is pennies.
CONFIRM_ABOVE_BYTES = 2 * 1024 * 1024

POLL_SECONDS = 10
# Generous: a long lecture takes minutes, and giving up early would leave a paid-for transcript
# stranded server-side with nothing holding its id.
POLL_TIMEOUT_SECONDS = 60 * 60


def api_key(repo_root: str) -> str:
    key = os.environ.get("ASSEMBLYAI_API_KEY")
    if key:
        return key.strip()
    path = os.path.join(repo_root, "assemblyai.local.properties")
    if os.path.isfile(path):
        for line in open(path, encoding="utf-8"):
            line = line.strip()
            if line.startswith("assemblyai.apiKey="):
                return line.split("=", 1)[1].strip()
    raise SystemExit(
        "No API key. Set ASSEMBLYAI_API_KEY, or put assemblyai.apiKey=… in "
        "assemblyai.local.properties at the repo root (it is gitignored)."
    )


def post(url: str, key: str, body: bytes | None, content_type: str | None) -> dict:
    headers = {"authorization": key}
    if content_type:
        headers["content-type"] = content_type
    request = urllib.request.Request(url, data=body, headers=headers, method="POST")
    with urllib.request.urlopen(request) as response:
        return json.loads(response.read())


def get(url: str, key: str) -> dict:
    request = urllib.request.Request(url, headers={"authorization": key})
    with urllib.request.urlopen(request) as response:
        return json.loads(response.read())


def run(audio: bytes, key: str, diarize: bool = True) -> dict | None:
    """Upload, queue, poll. Returns the completed transcript, or **None** on a failure.

    Bytes rather than a path, because the watcher's copy arrives over WebDAV and never touches
    the disk — a lecture is somebody's classroom, and not spilling it into a temp directory on
    whatever machine this runs on is free.

    ⚠️ **None is not an exception, and the difference is deliberate.** A failed or timed-out
    transcript is §11's *"Needs attention"* — the caller's correct response is to leave the audio
    exactly where it is and move on, which is what makes a bad transcription cost one retry
    instead of a lecture. Raising here would tempt a caller into a retry loop against an API that
    bills per call.
    """
    upload = post(f"{BASE}/upload", key, audio, "application/octet-stream")

    body = {"audio_url": upload["upload_url"], "punctuate": True}
    if diarize:
        # ⚠️ Requires punctuate, which is set above. Returns anonymous "A"/"B" — it does not and
        # cannot identify the teacher; see align.dominant_speaker.
        body["speaker_labels"] = True
    transcript_id = post(f"{BASE}/transcript", key, json.dumps(body).encode(), "application/json")["id"]
    print(f"    queued {transcript_id}")

    deadline = time.time() + POLL_TIMEOUT_SECONDS
    while time.time() < deadline:
        result = get(f"{BASE}/transcript/{transcript_id}", key)
        status = result.get("status")
        if status == "completed":
            return result
        if status == "error":
            # ⚠️ Do not retry here. A failed transcript is a "Needs attention" row in §11's sense,
            # not a transport hiccup, and a retry loop against a paid API is its own hazard.
            print(f"    FAILED: {result.get('error')}", file=sys.stderr)
            return None
        print(f"    {status}…")
        time.sleep(POLL_SECONDS)

    print(f"    timed out; transcript {transcript_id} may still complete server-side", file=sys.stderr)
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("audio")
    ap.add_argument("--out", required=True)
    ap.add_argument("--yes", action="store_true", help="confirm spending on a long recording")
    ap.add_argument("--no-diarization", action="store_true")
    args = ap.parse_args()

    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    key = api_key(repo_root)

    size = os.path.getsize(args.audio)
    # 34.3 kbit/s measured on a real lesson (§15.3), not the 24 that was requested.
    hours = size * 8 / 34_300 / 3600
    cost = hours * 0.23
    print(f"{args.audio}: {size:,} bytes ~ {hours * 60:.1f} min ~ ${cost:.2f}")
    if size > CONFIRM_ABOVE_BYTES and not args.yes:
        print("Refusing without --yes: this uploads a recording to a third party and bills a card.")
        return 2

    print("uploading…")
    result = run(open(args.audio, "rb").read(), key, diarize=not args.no_diarization)
    if result is None:
        return 1

    open(args.out, "w", encoding="utf-8").write(json.dumps(result, indent=1))
    words = result.get("words") or []
    speakers = sorted({w.get("speaker") for w in words if w.get("speaker")})
    print(f"done: {len(words):,} words, speakers {speakers or 'none'}, "
          f"model {result.get('speech_model_used') or 'unreported'}")
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
