# Inkstone — Build Roadmap

Fourteen milestones. Each produces something that runs and can be judged. **Do not start a milestone before its predecessor's acceptance criteria pass** — the criteria exist because skipping them is how this kind of project quietly becomes unfixable.

Read `SPEC.md` first. This file assumes it.

---

## Expectations

**Milestone ≠ session.** M1 and M6 are each worth several sessions. M0–M6 is a **several-months-of-evenings project** for one person, even with an agent doing the typing — M6's acceptance criteria include a literal calendar week of two-device use.

The middle sags around M4. That's normal. M6 is where it starts being the thing you actually wanted.

---

## How to use this with Claude Code

1. Paste the milestone's **Prompt** block into a fresh session
2. Let it work — it should end by running the acceptance checks
3. **You** verify independently, especially the manual criteria
4. Commit and tag (`git tag m1`), then start a fresh session

One milestone per session. A half-built ink engine sitting next to a half-built sync engine is worse than either alone.

---

## Milestone map

| # | Milestone | Ships | Risk |
|---|---|---|---|
| M0 | Skeleton + guardrails | Empty app that builds, for Android **and iOS core** | Low |
| **M1** | **Ink that feels right** | A page you can write on | 🔴 **Highest** |
| M2 | Document model + storage | Notebooks that survive a crash | 🟡 Medium — the op model must be right |
| M3 | Library + organisation | Folders, search, trash | Low |
| M4 | PDF import + materialization | Annotate a PDF, export a real PDF | 🟡 Unverified dependency + coordinate math |
| M5 | Nextcloud auth + upload | Bind a note, upload it, offline queue | 🟡 Auth is fiddly |
| M6 | Nextcloud pull + conflicts | Two-way sync that can't lose data | 🔴 **Second highest** |
| — | — | *usable app from here* | |
| M7 | Recognition + search | Searchable handwriting, in-app and in the PDF | Medium |
| M8a | Selection + transform | Lasso, move, resize, restyle, z-order | Medium |
| M8b | Shapes + connectors | Shape snapping, attached connectors | 🟡 Bigger than it looks |
| M8c | Text, images, tools | Text boxes, images, ruler, laser, Elements | Medium |
| M9 | Performance + hardening | Fast on a 500-page notebook, on mid-range hardware | Medium |
| M10 | Polish | The details that make it feel finished | Low |
| M11 | iPad readiness | Core links for iOS | Low |

*(M8 was originally one milestone. Connectors that reroute on move are a diagramming engine; text boxes are layout + IME + PDF text export; Elements is its own storage feature. Three milestones is honest.)*

---

## M0 — Skeleton and guardrails

**Goal:** an empty app that builds for Android *and* links a core framework for iOS, with the architectural rules already enforced. Boring on purpose. This is what makes M11 possible instead of a rewrite.

### Scope

- Gradle multi-module per SPEC §2.1; version catalog with the **exact** pins from SPEC §0.2
- **KMP with `androidTarget()` AND `iosSimulatorArm64()` on every `:core:*` module from day one**, even with an empty `iosMain`
- `:app` — Compose, Material 3, Hilt, one empty screen
- SQLDelight in `:core:database` with an empty schema
- Ktor in `:core:sync` (`ktor-client-core` in commonMain, `-okhttp` in androidMain, `-darwin` in iosMain) — **no OkHttp or dav4jvm in commonMain**
- The platform interfaces from SPEC §2.2, defined in `:core:model` with stub implementations in `:app`
- **Guardrail 1:** Gradle task failing the build if any `:core:*` imports `android.*`/`androidx.*` (except `androidx.annotation`)
- **Guardrail 2:** `./gradlew :core:sync:linkDebugFrameworkIosSimulatorArm64` in CI
- GitHub Actions: build + test + both guardrails
- ktlint/spotless, detekt baseline

### Acceptance

- [ ] `./gradlew build` green from a clean clone
- [ ] **`./gradlew :core:sync:linkDebugFrameworkIosSimulatorArm64` succeeds.** This is the guardrail that actually matters — the import check would happily stay green with OkHttp in core.
- [ ] Add `import android.content.Context` to a `:core:model` file → build fails clearly. Remove it.
- [ ] Add `implementation(libs.okhttp)` to `:core:sync` commonMain → **iOS link fails**. Remove it.
- [ ] **Every coordinate resolves from a clean Gradle cache.** If one doesn't, report the actual available version with a link to its release page and stop — do not substitute silently.
- [ ] CI green on a pushed branch

### Prompt

```
Read SPEC.md, then implement Milestone M0 from ROADMAP.md.

Set up the Gradle multi-module skeleton per SPEC §2.1 with a version catalog
using the exact pins from SPEC §0.2. Do not upgrade or substitute any version —
they were verified 2026-08-05 and several differ from what older tutorials say.
If a coordinate does not resolve from a clean cache, tell me the actual available
version with a link to the release page and stop.

Two guardrails, both required, both due now:
1. A Gradle task failing the build on android.*/androidx.* imports in :core:*
2. iosSimulatorArm64 as a real target on EVERY :core:* module

Guardrail 2 is the important one. SPEC §0.1 #4 explains why: OkHttp and dav4jvm
are JVM-only, and if either lands in :core:sync the iPad path is dead — but the
import check would stay green the whole time. Prove both guardrails by adding a
violation, showing the failure, and removing it.

Note SPEC §0.2: do NOT use EncryptedSharedPreferences (deprecated 2025). Stub the
CredentialStore interface now; implement it with Keystore AES-GCM in M5.

No app functionality. An empty Compose screen is correct for M0.
```

---

## M1 — Ink that feels right 🔴

**The milestone that decides whether the project is worth continuing.** Everything else is tractable engineering.

### Scope

- `:android:ink` wrapping `androidx.ink` 1.0.0 (View-based, in `AndroidView` — SPEC §3.2)
- Front-buffered rendering via `InProgressStrokesView`
- Every row of SPEC §3.3's input table
- **Spike (SPEC Appendix B #2): does `androidx.ink` already predict internally?** Test with `input-motionprediction` on and off. Double prediction causes visible overshoot on direction changes. Report which you kept.
- Four brushes per SPEC §3.4; colour picker with hex + wheel + 12 slots
- **Whole-stroke erase only** — see below
- Auto-return to the previous pen after erasing
- **The camera/viewport model from SPEC §4.5** — pinch zoom 10–1600%, pan, fit modes. Ink converts view→page space at the camera in effect when the stroke started.
- Minimal floating toolbar. **Don't design the toolbar here** — M10 owns it.

### Explicitly NOT in M1

- **No persistence.** Close the app, the drawing is gone. That's correct.
- **No partial erase.** All three erasers delete whole strokes whose geometry intersects. **Do not implement in-place point-list mutation** — SPEC §3.5 explains that splitting a stroke is `DeleteStroke + AddStroke(fragments)` in the op model, and it lands in M8a. Mutating points now means rewriting it later.

### Acceptance

- [ ] **≤ 2 frames behind Samsung Notes** at 240 fps capture, same device, same stroke (SPEC §3.6)
- [ ] Sustained 60fps (120 on a 120Hz panel) with 5,000 dry strokes on-page, per `dumpsys gfxinfo`
- [ ] Palm resting on the screen produces zero stray marks in 20 consecutive attempts
- [ ] Flip the S Pen → erases. Barrel button → erases. Release → previous pen.
- [ ] Pressure visibly varies width on Fountain; tilt visibly affects Pencil
- [ ] Fast strokes are smooth curves, not polygons (tests historical point consumption)
- [ ] No predicted-stroke artifacts after a stroke completes
- [ ] **Zoomed to 400%, ink lands exactly under the pen tip** (tests the view→page conversion)

**Exit condition:** if after three serious attempts you're within one frame of Samsung Notes, **accept and proceed**. Samsung uses vendor-private low-latency paths that public APIs may not match. Don't deadlock at milestone one.

### Prompt

```
Read SPEC.md §3 and §4.4–4.5, then implement Milestone M1.

Highest-risk milestone. Goal: ink indistinguishable from Samsung Notes on a
Galaxy Tab with S Pen.

Use androidx.ink 1.0.0 STABLE, View-based, in AndroidView — NOT the 1.1.0-alpha
Compose modules. SPEC §3.2 notes Google's own Compose sample pins a version
combination that does not resolve.

Every row of SPEC §3.3 is mandatory. The two most commonly skipped and most
damaging are historical point consumption and requestUnbufferedDispatch — miss
either and the ink feels wrong without an obvious cause.

Run the SPEC Appendix B #2 spike: determine whether androidx.ink already applies
motion prediction. If it does, drop input-motionprediction — double prediction
produces visible overshoot. Tell me which you kept and why.

Two scope limits that matter for later milestones:
- Whole-stroke erase only. Do NOT mutate point lists in place. SPEC §3.5.
- Implement the camera model (SPEC §4.5) now. Ink is captured in view space and
  converted to page space at the camera in effect when the stroke started.
  Getting this wrong is invisible until M4 puts annotations in the wrong place.

No persistence in this milestone.

Finish by telling me exactly how to run the acceptance measurements, including
the dumpsys gfxinfo command and what numbers pass or fail.
```

---

## M2 — Document model and storage

**Goal:** multi-page notebooks that never lose work to a crash. **The op model must be right here** — extending the op vocabulary after sync is live is the classic "and then everything broke" moment.

### Scope

- `:core:model` — all types from SPEC §4.1, **the complete op vocabulary from SPEC §4.2**
- **`SetTransform` carries an ABSOLUTE matrix, not a delta.** SPEC §4.2 explains why deltas break silently under concurrency.
- Lamport clocks with the exact comparator and the `max+1` initialisation rule (SPEC §4.1)
- Fractional sort keys for page order (SPEC §4.3)
- Re-fold-from-snapshot when an out-of-order op arrives (SPEC §4.3) — **never patch in place**
- The coordinate functions from SPEC §4.4, **unit-tested**
- `:core:storage` — the bundle (SPEC §5.3), with **stroke geometry as opaque bytes** (SPEC §5.4)
- `:android:ink` encodes/decodes geometry blobs via `ink-storage`
- **The journal + materialize model from SPEC §5.1** — autosave appends to `journal.ops` every ~2 s; the PDF is *not* rewritten on autosave (M4 adds actual PDF materialization; for M2 the materialized artifact is just the bundle)
- **Atomic writes everywhere** — write-temp-then-rename
- `local_rev` bumped on every committed change
- Undo/redo as new ops (SPEC §4.6), local-only
- Page management: add, delete, reorder, duplicate
- Templates: blank, ruled, grid, dot grid, Cornell
- Lazy page loading
- Compaction per SPEC §4.7 — causal-stability horizon, snapshot-as-cache

### Acceptance

- [ ] 50-page notebook, write on 20 pages, `adb shell am force-stop` mid-stroke → reopen → **at most the in-progress stroke lost**
- [ ] Undo/redo across 200 ops including an app restart
- [ ] Round-trip: save → load → save → byte-identical
- [ ] Unknown op types and unknown JSON fields survive a round-trip untouched
- [ ] 200-page notebook → interactive first page in < 2 s
- [ ] Memory doesn't grow linearly with page count while scrolling
- [ ] **Coordinate unit tests pass**, including a page with `/Rotate 90` and a non-zero-origin CropBox
- [ ] **Concurrency unit test:** simulate SPEC §13.2 row 3 (two concurrent transforms) at the op-fold level and assert LWW, not a skewed compound

### Prompt

```
Read SPEC.md §4 and §5.1–5.4, then implement Milestone M2.

Three decisions in this milestone cannot be undone later without rewriting sync:

1. THE COMPLETE OP VOCABULARY (SPEC §4.2). Define all of it now — AddObject,
   DeleteObject, SetTransform, SetStyle, SetZOrder, SetTextContent, SetPageMeta —
   even though M8 is the first user. Adding ops after sync is live breaks things.

2. SetTransform CARRIES AN ABSOLUTE MATRIX, NOT A DELTA. SPEC §4.2 gives the
   worked example: with deltas, a concurrent scale and rotate plus an undo
   produces a skewed object, consistently and silently, on both devices.
   Same for SetStyle and SetZOrder — LWW registers, absolute values.

3. STROKE GEOMETRY IS AN OPAQUE BYTE BLOB (SPEC §5.4), produced by ink-storage
   in :android:ink and passed through :core:* uninterpreted. Do NOT serialize
   stroke points as JSON — at ~70 bytes/point with 200Hz sampling you'd be
   parsing megabytes per page against a 2-second open target.

Also implement the journal model from SPEC §5.1: autosave appends ops to
journal.ops every ~2s. Do not rewrite the whole document on autosave.
All file writes are write-temp-then-atomic-rename.

Include the coordinate functions from SPEC §4.4 with unit tests covering
/Rotate 90 and a non-zero-origin CropBox. Both are common in scanned PDFs and
both break naive implementations.
```

---

## M3 — Library and organisation

**Goal:** manage hundreds of notebooks without it becoming a mess.

### Scope

- Library: grid/list, thumbnails, sort by name/modified/created
- **Thumbnails in a local cache dir (SPEC §5.7), never read from inside the bundle** — opening 500 PDFs to render a library is the difference between 60fps and unusable
- Page thumbnails for the reorder UI
- Folders with arbitrary nesting, colour, icon
- Favourites, title/folder search
- Rename, move, duplicate, multi-select
- **Trash, 30-day retention. No hard delete anywhere in the UI.**

### Acceptance

- [ ] 500 notes across 50 nested folders → library scrolls at 60fps
- [ ] Thumbnails generate off the main thread; no jank while scrolling
- [ ] Delete → restore from trash → content intact
- [ ] Title search returns results as you type

### Prompt

```
Read SPEC.md §5.7 and §9, then implement Milestone M3.

Thumbnails live in a local cache dir keyed by documentId, invalidated on save.
Never read a thumbnail from inside the bundle inside the PDF — rendering a
500-note library would mean opening 500 PDFs and extracting 500 ZIPs, and the
60fps target is unreachable that way. Add page thumbnails too; M2's reorder UI
and M7's search-jump both need them.

Hard rule: no hard delete anywhere in the UI. Trash with 30-day retention.

Folder structure must be designed to mirror a Nextcloud folder tree in M5.
```

---

## M4 — PDF import and materialization

**Goal:** import a PDF, annotate it, and produce a PDF that opens correctly anywhere — and reopens losslessly here.

### M4.0 — Spike first

SPEC Appendix B #1. Answer both before building on it:

1. Does `androidx.pdf` 1.0.0-alpha19's `EditablePdfViewerFragment` write **standard `/Ink` annotations** readable by Acrobat, or a private representation?
2. Does variable stroke width survive a save → reload cycle?

**Method:** minimal test app, draw a pressure-varying stroke, save, inspect with `qpdf --qdf` / `mutool show`, open in Acrobat.

**Both yes** → use it for annotation. **Either no** → rendering only; write annotations via PdfBox-Android behind a `PdfWriter` interface. **Report the result before continuing.**

### Scope

- PDF import; original bytes become the base of `document.pdf`, **never rewritten**
- Page size from **CropBox with `/Rotate` applied** (SPEC §4.4)
- Render source pages under the ink layer
- **Materialize as a PDF incremental update** (SPEC §5.2) — original bytes stay verbatim at the front, and the bundle carries no second copy
- `/Ink` annotations + `/AP` appearance streams with variable width (SPEC §6.4)
- **Annotation density rule (SPEC §6.4):** ≤500 strokes/page → one annotation per stroke with `/NM` = ObjectId; >500 → single flattened overlay
- Invisible text layer scaffolding (populated in M7)
- **XMP identity marker + `/NM` per annotation** (SPEC §5.6)
- **Three-way open detection** — Normal / Recovered / Foreign
- **Recovered mode as REPLACE, never union** (SPEC §5.6) — this prevents stroke duplication on the next sync
- Embedded bundle via `/Names /EmbeddedFiles`

### Acceptance

- [ ] Import a 200-page PDF, annotate 10 pages, export → correct in **Acrobat, Chrome, and macOS Preview**
- [ ] Pressure-varying strokes render correctly in all three (validates `/AP`)
- [ ] Reopens in Inkstone with **zero fidelity loss**
- [ ] **A source PDF with `/Rotate 90` puts annotations in the right place**
- [ ] **A source PDF whose CropBox ≠ MediaBox puts annotations in the right place**
- [ ] The original PDF bytes are recoverable byte-identically after any number of edit cycles
- [ ] Strip the embedded file with `qpdf` → **Recovered mode** triggers, warns, replaces (does not union)
- [ ] **Recovered document syncs without duplicating strokes** (SPEC §13.2 row 9)
- [ ] A PDF annotated in Acrobat with no XMP marker opens as **Foreign**, not Recovered
- [ ] A 2,000-stroke page uses the flattened path and opens without hanging Preview

### Prompt

```
Read SPEC.md §4.4, §5.1–5.2, §5.6 and §6, then implement Milestone M4.

START WITH THE M4.0 SPIKE (SPEC Appendix B #1) and report the result before
building on it. It determines whether we use androidx.pdf or PdfBox-Android for
writing annotations.

Three things this milestone must get right:

1. MATERIALIZE AS A PDF INCREMENTAL UPDATE (SPEC §5.2). Appending an update
   section preserves the original bytes verbatim at the front of the file, which
   means the bundle does NOT need a second copy of source.pdf. Without this a
   20 MB lecture PDF becomes 40 MB+ before a single stroke.

2. COORDINATE SPACES (SPEC §4.4). Page size comes from CropBox with /Rotate
   applied — not MediaBox. Include acceptance tests with a /Rotate 90 page and a
   non-zero-origin CropBox. Both are common in scanned lecture PDFs and both
   break naive implementations.

3. DOCUMENT IDENTITY AND RECOVERED MODE (SPEC §5.6). Write an XMP marker
   (documentId, schemaVersion, bundleSha256) and /NM = ObjectId on every
   annotation. Without the marker you cannot tell "our file, mangled" from "a
   stranger's PDF". And Recovered mode must REPLACE, never union — a naive
   recovered import assigns new ObjectIds, and if it then syncs, the other
   device's grow-only union duplicates every stroke. That is exactly the silent
   corruption this whole design exists to prevent.

Also apply the annotation density rule from SPEC §6.4 — 5,000 per-stroke
annotations will hang Preview and Chrome.
```

---

## M5 — Nextcloud, part 1: auth and upload

**Goal:** bind a note to a Nextcloud location and have it upload itself, including after being written entirely offline.

### Scope

- `:core:sync` with **Ktor only** — hand-roll the PROPFIND XML from SPEC §7.3. No OkHttp, no dav4jvm in commonMain.
- **Login Flow v2** — all six rules from SPEC §7.1
- **Fetch the UID** from `/ocs/v2.php/cloud/user` (SPEC §0.1 #3 — the most likely bug in the project)
- `CredentialStore` implemented with **Keystore AES-GCM**, not `EncryptedSharedPreferences`
- Capabilities fetch and cache
- Remote folder browser (PROPFIND `Depth:1`)
- **"Save to Nextcloud…"** binding flow (SPEC §7.6)
- **Outbox with the full semantics from SPEC §7.6** — op types `UPLOAD`/`MOVE`/`DELETE`/`MKCOL`, UPSERT coalescing on `(note_id, op_type)`, markers not payloads, backoff inheritance, DELETE annihilating pending UPLOAD, 30 s minimum inter-upload interval
- `local_rev` check before marking SYNCED
- Upload with `If-Match`; weak-ETag normalisation
- Filename sanitisation from runtime capabilities (SPEC §7.8)
- **401 handling per SPEC §7.2 — halt, never retry-loop**
- **Spike (SPEC Appendix B #4):** does the incremental-update PDF survive Nextcloud storage and round-trip intact?

### Acceptance

- [ ] Login works against a real Nextcloud, **including an instance where the user logs in with an email address** (the UID test)
- [ ] Airplane mode → create a note → write 20 pages → bind to a folder → **note stays fully editable throughout**
- [ ] Disable airplane mode → uploads automatically
- [ ] **500 offline edits → exactly one upload**
- [ ] **Note edited while its PUT is in flight → not marked SYNCED, re-enqueued** (`local_rev`)
- [ ] Force-stop with a full outbox → relaunch → outbox intact, drains
- [ ] Delete a bound note → local trash + remote DELETE; restore → re-uploads
- [ ] Revoke the app password server-side → re-login prompt, **no retry loop** (check the Nextcloud log — zero brute-force warnings)
- [ ] A note titled with emoji and `/` syncs to a correctly sanitised filename
- [ ] The app password shows a readable name in Nextcloud's security settings, not "okhttp"
- [ ] **Download the uploaded PDF and verify the incremental update is intact**

### Prompt

```
Read SPEC.md §7.1–7.6, §7.8–7.9 and §14, then implement Milestone M5.

:core:sync uses KTOR ONLY. No OkHttp, no dav4jvm in commonMain — SPEC §0.1 #4.
The PROPFIND XML you need is in SPEC §7.3 verbatim; hand-rolling it is a small
bounded amount of code and it keeps the iOS target alive.

Two things will bite you if written from memory:

1. loginName from Login Flow v2 is NOT the WebDAV path segment. Fetch the UID
   from /ocs/v2.php/cloud/user → ocs.data.id. Test against an instance where the
   user logs in with an email address.

2. Nextcloud has brute-force protection on by default. A 401 halts the sync
   worker and requires re-auth. Never auto-retry credentials — that is the
   documented cause of real users IP-blocking themselves.

Implement the FULL outbox semantics from SPEC §7.6, not just a queue: UPSERT
coalescing on (note_id, op_type), rows as markers never payloads, backoff
inherited on coalesce, DELETE annihilating a pending UPLOAD, MOVE draining
before UPLOAD, and a 30-second minimum inter-upload interval. And mark SYNCED
only if note.local_rev still equals the rev that was uploaded — otherwise the
note changed mid-flight and that work becomes invisible to sync forever.

Use Keystore AES-GCM for credentials. NOT EncryptedSharedPreferences — it was
deprecated in 2025 (SPEC §14).

Run the SPEC Appendix B #4 spike: upload an incremental-update PDF, download it,
verify it round-trips intact.
```

---

## M6 — Nextcloud, part 2: pull, merge, conflicts 🔴

**Goal:** two-way sync where you cannot lose data no matter what you do to it.

### Scope

- Reconciler per SPEC §7.6 — root ETag first, prune by directory ETag
- **Move detection** — same `oc:id` + same ETag + different href
- Download and merge of remote edits
- **The three merge layers from SPEC §7.7**
- Fractional-key page-order merge — **pages missing from the winning order are appended, never dropped**
- **Live editing during a remote change** — re-fold, preserve camera and in-progress stroke, don't touch the local undo stack
- "Needs attention" list — **no modal dialogs, ever**
- Remote deletion → local trash
- `nc:data-fingerprint` → full reconcile
- Polling honouring `core.pollinterval`; `notify_push` as optimisation only
- Folder mirroring both directions

### Acceptance

**All 21 rows of SPEC §13.2 as automated tests, against a real Nextcloud in Docker.** Then manually:

- [ ] Two devices, both offline, both write on the same page → reconnect → **both sets present, no prompt**
- [ ] Two devices, both offline, both add a page → **both pages present**, neither orphaned
- [ ] Move a note in the Nextcloud **web UI** → detected as a move, no re-download, no duplicate
- [ ] Delete on the server while local has unsynced edits → local → trash, user notified, nothing lost
- [ ] Both devices online with the same page open → strokes appear live, camera doesn't jump, your in-progress stroke survives
- [ ] **A full week of real daily use across two devices with deliberate airplane-mode toggling → zero data loss**

### Prompt

```
Read SPEC.md §4.3, §7.4, §7.6, §7.7 and §13.2, then implement Milestone M6.

The contract: the user cannot lose data, no matter what.

Three rules with no exceptions:
1. Never resolve a conflict by discarding bytes.
2. Never show a modal dialog to resolve a conflict. Nextcloud's own Android
   client does this and has ~9 open bugs from it. Resolve automatically and
   non-destructively; surface it in a list afterwards.
3. Never block sync on user input.

The convergence mechanism is total-order state-machine replication (SPEC §4.3),
not append-and-hope: when an incoming op has a clock lower than ops already
applied, you must RE-FOLD from the last snapshot. Never patch in place.

Page order merges by fractional key. A page missing from the winning order is
APPENDED, never dropped — a plain LWW list silently loses one device's new page
while orphaning its file.

Implement ALL 21 ROWS of SPEC §13.2 as automated tests against a real Nextcloud
in Docker, not a mock. Mocks won't reproduce weak ETags, fileid churn on
trash-restore, or brute-force lockout. Rows 2, 3, 5, 7, 9, 10 and 18 are the
ones that catch design bugs rather than coding bugs — do not skip them. Show me
them passing.
```

---

## M7 — Handwriting recognition and search

**Goal:** find any note by what you wrote in it — inside the app and in any PDF viewer.

### Scope

- `:android:ocr` — ML Kit Digital Ink Recognition 19.0.0
- **Line segmentation before recognition** (SPEC §8.2) — the biggest accuracy lever
- Model download UI with size and progress; **graceful degradation when unavailable**
- Debounced background recognition (SPEC §8.3)
- `:core:search` — FTS5 over recognised text + titles
- Results → note + page + page-space bounding box; tap to scroll and highlight
- **Populate the invisible PDF text layer** (SPEC §5.5) from recognised text + stroke boxes

### Acceptance

- [ ] Write a page, search a word from it → correct result, correct page, highlighted in place
- [ ] Recognition never drops a frame while writing
- [ ] Delete the language model → app works fully, search degrades to titles, message is clear
- [ ] **`pdfgrep` finds handwritten words in an exported PDF** ← the hard criterion
- [ ] Line segmentation measurably beats whole-page-as-one-blob on a 20-page sample — **show both numbers**
- [ ] *(Optional, environment-dependent)* Nextcloud full-text search finds it. ⚠️ This requires the `fulltextsearch` app plus Elasticsearch/OpenSearch plus a PDF extractor — **not present in a default install.** Set that up first if you want to test it; don't treat its absence as a failure.

### Prompt

```
Read SPEC.md §8 and §5.5, then implement Milestone M7.

The detail most implementations get wrong: ML Kit's recogniser assumes a SINGLE
LINE of text. Passing a whole page as one Ink object produces garbage. Segment
strokes into lines first (SPEC §8.2) and size each WritingArea to its line.
Measure segmented vs unsegmented accuracy on a real 20-page handwriting sample
and show me both numbers.

ML Kit is a single point of dependency with no viable alternative — ML Kit GenAI
does not do handwriting, and image OCR discards the temporal signal that makes
recognition work. So the app must work fully when models are unavailable; search
just degrades to titles.

The payoff is SPEC §5.5. Because our ink is born-digital we have both the
recognised text AND exact stroke bounding boxes, so we can emit a perfectly
positioned invisible text layer (3 Tr) with no rasterise-then-OCR round trip.

Hard acceptance criterion is pdfgrep finding handwritten words in the exported
PDF. Nextcloud full-text search is a nice-to-have — it needs the fulltextsearch
app plus a search backend, which a default install does not have.
```

---

## M8a — Selection and transform

### Scope

- **Lasso** — freehand and rectangular; selects ink, images, text boxes, shapes
- Actions: cut/copy/duplicate/delete, resize, rotate, change colour, bring-to-front/send-to-back
- Alignment guides on move (dashed = centre, solid = edge)
- **Partial/precision erase** — splits a stroke, expressed as `DeleteObject + AddObject(fragments)` per SPEC §3.5. Never in-place mutation.
- All of it emitting the ops defined in M2 — `SetTransform` (absolute), `SetStyle`, `SetZOrder`

### Acceptance

- [ ] Lasso selects mixed content and every action works on the mixed selection
- [ ] Partial erase splits a stroke; both fragments are independently selectable and erasable
- [ ] **Transform survives a sync round-trip and merges LWW** (SPEC §13.2 rows 3, 7)
- [ ] Works identically on a PDF-backed page and a template page

### Prompt

```
Read SPEC.md §3.5, §4.2 and §4.6, then implement Milestone M8a.

Everything here emits ops already defined in M2 — do not add new op types.
SetTransform carries an ABSOLUTE matrix; a resize writes the resulting matrix,
not a delta.

Partial erase splits a stroke and must be expressed as
DeleteObject(original) + AddObject(fragment1) + AddObject(fragment2).
Never mutate a stroke's point list in place — it would break op-log convergence.

Add sync round-trip tests for transform and partial-erase (SPEC §13.2 rows 3, 7).
```

---

## M8b — Shapes and connectors 🟡

**Bigger than it looks.** Connectors that stay attached and reroute when a shape moves is a small diagramming engine.

### Scope

- Draw-and-hold shape snapping: line, rectangle, ellipse, triangle, polygon, arrow
- Fill colour with opacity; rounded corners
- **Connectors that attach to shapes, stay attached, and reroute on move or resize** — elbow and curved
- Snap-to-other-shapes for endpoints

### Acceptance

- [ ] Shape snapping is predictable — no fighting it to draw a freehand curve
- [ ] Connectors stay attached and reroute when a connected shape is moved or resized
- [ ] Deleting a shape leaves its connectors in a defined state (detached, not dangling or crashing)
- [ ] Connector attachment survives a sync round-trip

---

## M8c — Text, images, and tools

### Scope

- **Text boxes** — font, size, colour, alignment, lists; IME handling; exported as real PDF text
- **Images** — insert from gallery/camera, crop (rectangular and freehand), rotate, **and flip** *(Goodnotes still can't flip images; it's a long-standing open request and it's a matrix negation)*
- **Ruler** — snap strokes to its edge, exact angle entry
- **Laser pointer** — dot and fading trail
- **Elements** — save any selection as a reusable stamp, organised in collections *(the most-praised Goodnotes power feature)*

### Acceptance

- [ ] Text boxes export as selectable, searchable PDF text — not as an image
- [ ] Image flip works and survives a round-trip
- [ ] A saved Element re-inserts fully editable with formatting preserved
- [ ] Every tool works identically on PDF-backed and template pages

---

## M9 — Performance and hardening

### Scope

- Profile against SPEC §10's full table
- 500-page PDF-backed notebook: page virtualisation, bitmap recycling, bounded stroke cache
- Compaction tuning
- LeakCanary; fix every leak
- Battery Historian, 24 h
- Baseline profiles, lazy DI graph
- Macrobenchmark suite in CI
- Crash-safety instrumentation (SPEC §13.3)
- **On-device schema migration test** and **library rebuild from a folder of PDFs** (SPEC §13.1)
- Full a11y pass: TalkBack, large text, contrast

### Acceptance

- [ ] Every row of SPEC §10 passes on the target device
- [ ] **And on a mid-range 4 GB tablet** — the explicit anti-goal in SPEC §10
- [ ] Zero leaks in a 30-minute LeakCanary session
- [ ] 24 h background sync isn't a measurable drain
- [ ] Macrobenchmarks in CI, failing on regression
- [ ] Delete the SQLite DB → library rebuilds correctly from the PDFs on disk

---

## M10 — Polish

### Scope

- **Toolbar:** customisable, reorderable, dockable to any edge. **Every tool hideable and movable** *(Goodnotes pins Lasso and Audio immovably; it's a persistent top complaint — just don't do that)*
- **Per-note** biometric lock; locked notes excluded from thumbnails and search
- Quick-note: home screen widget + `Intent.ACTION_CREATE_NOTE` for lock-screen launch
- **Hover preview** (`AXIS_DISTANCE`) — deferred here from M1
- Dark theme, dynamic colour, per-notebook paper colour
- Onboarding: three screens max, skippable
- Empty states, error states, a genuinely useful sync-status screen
- Settings: stylus sensitivity curve, palm rejection level, default template, default Nextcloud folder
- Release build: signing config, keystore, ProGuard/R8 rules verified against reflection in serialization

### Acceptance

- [ ] Every single tool can be hidden or moved
- [ ] Biometric lock works per-note; locked notes absent from thumbnails and search
- [ ] Widget creates and opens a note in under 1 s
- [ ] A first-time user can create a note, bind it to Nextcloud, and write — without instructions
- [ ] A signed release build runs correctly with R8 enabled

---

## M11 — iPad readiness

**Goal:** prove the shared core works. Not an iOS app.

### Scope

- Verify `iosArm64` / `iosSimulatorArm64` still link (they've been in CI since M0)
- SQLDelight native driver, Ktor Darwin engine, exercised
- A minimal Swift harness that opens a bundle written by the Android app and prints the page count and op count
- Document what iOS still needs: rendering (PencilKit/Metal), input, PDF writing, OCR, and a stroke-geometry decoder (SPEC §5.4's accepted cost)

### Acceptance

- [ ] `./gradlew :core:sync:linkDebugFrameworkIosSimulatorArm64` succeeds
- [ ] The Swift harness reads a real bundle produced by the Android app
- [ ] A merge test runs on iOS and produces the same result as on Android
- [ ] A written doc listing exactly what an iOS app would still need

---

## Working agreements

**One milestone per session.** Fresh context each time.

**Never skip acceptance criteria.** M1's, M2's concurrency test, M4's coordinate tests, and M6's 21 rows are load-bearing.

**Commit and tag at each milestone** (`m0`, `m1`, …) so you can bisect.

**When Claude Code proposes a version bump**, check SPEC §0.2 and ask it to cite the release page. A hallucinated version string is the most common way this goes wrong.

**When the spec turns out to be wrong**, update `SPEC.md` in the same commit as the fix. A spec that drifts from the code is worse than no spec.

**The four spikes in SPEC Appendix B each have an owning milestone.** Don't let them get glossed over — M4.0 in particular is a real fork in the design.
