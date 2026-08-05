# Start here

Four files. This one tells you how to use the other three.

| File | What it is |
|---|---|
| **`SPEC.md`** | The technical specification. Architecture, file format, sync protocol, verified versions. |
| **`ROADMAP.md`** | Fourteen milestones with acceptance criteria and a ready-to-paste prompt for each. |
| **`CLAUDE.md`** | Repo rules Claude Code reads automatically every session. |
| `START-HERE.md` | This. |

---

## Setup (5 minutes)

```bash
mkdir inkstone && cd inkstone
git init
# copy SPEC.md, ROADMAP.md, CLAUDE.md into this folder
git add . && git commit -m "Spec and roadmap"
claude
```

Claude Code picks up `CLAUDE.md` automatically. That's the whole setup.

---

## The loop

1. **Fresh session.** `claude` in the project folder.
2. **Paste the milestone's Prompt block** from `ROADMAP.md`.
3. **Let it work.** It should end by telling you how to run the acceptance checks.
4. **Verify yourself** — especially the manual criteria. Don't take "done" on trust.
5. **Commit and tag:** `git commit -m "M1: ink engine" && git tag m1`
6. **New session** for the next milestone.

One milestone per session, always.

---

## How long this actually takes

**Milestone ≠ session.** M1 and M6 are each worth several sessions on their own, and M6's acceptance includes a literal calendar week of two-device use.

**M0–M6 is a several-months-of-evenings project** for one person, even with an agent doing the typing. Worth knowing up front — the middle sags around M4, and that's normal rather than a sign it's going wrong.

---

## The order, and where the risk is

```
M0   Skeleton + CI guardrails (Android AND iOS core)   ░ low
M1   Ink that feels right                              █ HIGHEST RISK
M2   Document model + storage                          ▓ the op model must be right
M3   Library + folders                                 ░ low
M4   PDF import + materialization                      ▓ unverified dep + coordinate math
M5   Nextcloud auth + upload                           ▓ auth is fiddly
M6   Nextcloud pull + conflicts                        █ SECOND HIGHEST
──────────────────────────────────────────────────────  ← usable app from here
M7   Recognition + search                              ▓ medium
M8a  Selection + transform                             ▓ medium
M8b  Shapes + connectors                               ▓ bigger than it looks
M8c  Text, images, tools                               ▓ medium
M9   Performance + hardening                           ▓ medium
M10  Polish                                            ░ low
M11  iPad readiness                                    ░ low
```

**M0–M6 is the real app** — writing that feels good, notes in your Nextcloud, works offline, syncs when it can, never loses anything. Everything after M6 makes it better, not viable.

---

## Three gates you must not wave through

**After M1 — the latency test.**
≤2 frames behind Samsung Notes at 240 fps capture, same device, same stroke. *But there's an exit condition:* if after three serious attempts you're within one frame, accept and move on. Samsung uses vendor-private low-latency paths that public APIs may not match, and deadlocking at milestone one would be the worst outcome.

**After M2 — the concurrency unit test.**
Two concurrent transforms on the same object must resolve last-writer-wins, not compound into a skew. This is a pure unit test at the op-fold level and takes ten minutes to write, but if `SetTransform` is carrying deltas instead of absolute matrices you will not find out until M6, by which point everything is built on it.

**After M6 — the merge matrix.**
All 21 rows of SPEC §13.2 as automated tests against a real Nextcloud in Docker, plus a real week of two-device use with deliberate airplane-mode toggling. Sync bugs that eat data are the kind you discover three months later, when the data is gone.

---

## What you're getting that Goodnotes doesn't do

**1. Your files are yours, in your Nextcloud, as ordinary PDFs.**
Every note is a real PDF that opens anywhere — with the lossless editable data embedded inside it, and the original imported PDF preserved byte-for-byte at the front via an incremental update. Goodnotes' `.goodnotes` format is undocumented encrypted binary; the request to open it has sat unanswered since 2020.

**2. Handwriting searchable everywhere, not just in the app.**
Goodnotes forces a choice on export — searchable handwriting *or* preserved structure, never both. Their own docs confirm it. Because your ink is born-digital you have the recognised text *and* exact stroke bounding boxes, so you can write a perfectly positioned invisible text layer. Result: `pdfgrep` finds your handwriting.

**3. Sync that can't lose data.**
Goodnotes' support docs say, about sync-corrupted notebooks: *"unfortunately there isn't much we can do here."* Here, two devices editing the same page offline both keep their work automatically with no prompt, and anything genuinely unmergeable becomes a conflicted copy rather than a loss.

---

## Things that will trip you up

All of these contradict what a model trained before mid-2026 would assume. They're in `CLAUDE.md` so Claude Code sees them every session — but you should know them too:

- **`androidx.ink` is stable now** (1.0.0, Dec 2025). Don't let anything write a custom ink engine.
- **The Samsung S Pen SDK is dead** — last release Sep 2019, and Samsung removed Bluetooth from the S Pen on the Tab S11. Plain Android `MotionEvent` covers everything. Add no Samsung dependency.
- **OkHttp and dav4jvm are JVM-only** and cannot live in `:core:*`. If they do, the iPad path dies silently and you find out at M11. This is why M0 puts an iOS link check in CI.
- **`EncryptedSharedPreferences` was deprecated in 2025.** Use Keystore AES-GCM directly.
- **Nextcloud's `loginName` is not the WebDAV username.** You need the UID from `/ocs/v2.php/cloud/user`. Most likely single bug in the whole project.
- **Never auto-retry a 401** against Nextcloud — its brute-force protection will lock out your IP. Real users have done this to their own servers.
- **PDF page size comes from CropBox with `/Rotate` applied**, not MediaBox. Scanned lecture PDFs routinely have both, and naive code puts annotations in the wrong place.
- **PDF ink annotations can't do variable stroke width.** Pressure has to go in an `/AP` appearance stream.
- **ML Kit's handwriting recogniser expects one line at a time.** Feed it a whole page and you get garbage.

---

## If a milestone goes sideways

**Claude Code proposes a different library version** → check SPEC §0.2 and ask it to cite the release page. If a coordinate genuinely doesn't resolve, it should report the real available version and stop, not substitute.

**M1 ink feels laggy** → check, in order: `requestUnbufferedDispatch()` on `ACTION_DOWN`, historical point consumption, whether anything heavy is on the ink path, and whether you're double-predicting (SPEC Appendix B #2). It's almost always one of those four.

**M4.0 spike says `androidx.pdf` can't write standard `/Ink`** → a real possible outcome, not a failure. Fall back to PdfBox-Android behind the `PdfWriter` interface, and accept the maintenance risk.

**Annotations land in the wrong place** → coordinate spaces, every time. SPEC §4.4. Check CropBox vs MediaBox and `/Rotate` first.

**Sync does something baffling** → usually ETags. Check for weak ETags (`W/"..."`) from a compressing proxy, which cause a permanent 412 loop.

**The spec is wrong about something** → it was written from research, not from having built this. Reality wins. Update `SPEC.md` in the same commit as the fix so the document doesn't drift from the code.

---

## Before M5

You'll need a Nextcloud to test against:

1. **A throwaway instance in Docker** for automated tests. Mocks won't reproduce weak ETags, fileid churn on trash-restore, or brute-force lockout.
2. **A test user whose login is an email address.** That's the case that catches the `loginName` vs UID bug — the one most likely to ship.

If you want to test the Nextcloud full-text-search criterion in M7, you'll also need the `fulltextsearch` app plus Elasticsearch or OpenSearch. That's not in a default install, so treat it as optional — `pdfgrep` is the criterion that matters.
