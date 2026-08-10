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
- ~~**Whole-stroke erase only**~~ — **revised in M1**: partial erase is the default, whole-stroke is a setting. SPEC §3.5 records why. The delete-and-add model is unchanged and still mandatory.
- Auto-return to the previous pen after erasing
- **The camera/viewport model from SPEC §4.5** — pinch zoom 10–1600%, pan, fit modes. Ink converts view→page space at the camera in effect when the stroke started.
- Minimal floating toolbar. **Don't design the toolbar here** — M10 owns it.

### Explicitly NOT in M1

- **No persistence.** Close the app, the drawing is gone. That's correct.
- ~~**No partial erase.**~~ **Reversed during M1's device test** — whole-stroke-only was rejected on contact with the tablet, so partial erase shipped in M1 as the default. **The prohibition that mattered is untouched: no in-place point-list mutation.** Splitting is `DeleteStroke + AddStroke(fragments)`, which is exactly how it was built, so M8a inherits it instead of replacing it.

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

> **The prompt above is kept verbatim as the record of what was asked.** One of
> its instructions did not survive contact with the tablet: whole-stroke erase
> was rejected on sight and M1 shipped partial erase as the default instead. The
> other half of that instruction — never mutate point lists in place — was
> followed exactly, so M8a still inherits `DeleteStroke + AddStroke(fragments)`.
> SPEC §3.5 and `docs/M1-INK.md` §2.4 record the reversal.

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

- [x] 50-page notebook, write on 20 pages, `adb shell am force-stop` mid-stroke → reopen → **at most the in-progress stroke lost** — *mechanism proven in `NoteStoreTest` (a kill mid-append and a corrupt record are both produced on demand via `FakeFileSystem.truncateNextAppendTo`); the force-stop on the tablet is still owed*
- [x] Undo/redo across 200 ops including an app restart — `UndoTest`, `NoteStoreTest.undo state survives a restart`
- [x] Round-trip: save → load → save → byte-identical — `BundleRoundTripTest`
- [x] Unknown op types and unknown JSON fields survive a round-trip untouched — `BundleRoundTripTest` (both halves of SPEC §5.8, and entries this build does not produce are carried through too)
- [ ] 200-page notebook → interactive first page in < 2 s — *the property it depends on is tested (`the cost of opening a note does not grow with what is drawn on its pages`); the timing on the tablet is owed*
- [x] Memory doesn't grow linearly with page count while scrolling — bounded page cache, `evicting a cached page loses nothing`
- [x] **Coordinate unit tests pass**, including a page with `/Rotate 90` and a non-zero-origin CropBox — `PdfCoordinatesTest`
- [x] **Concurrency unit test:** simulate SPEC §13.2 row 3 (two concurrent transforms) at the op-fold level and assert LWW, not a skewed compound — `PageFoldTest`, which also folds every permutation and asserts they agree

**Still owed, on the device:** the two timing/force-stop gates above, and a session of real
writing (CLAUDE.md's definition of done #4). `tools/send-to-tablet.ps1` builds and copies the
APK; measuring needs adb.

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

- [ ] 500 notes across 50 nested folders → library scrolls at 60fps — **tool built** (Measure → Fill library), numbers outstanding: needs `adb dumpsys gfxinfo`, and the tablet is `unauthorized`. Procedure in docs/M3-LIBRARY.md §10
- [ ] Thumbnails generate off the main thread; no jank while scrolling — same run, cold-cache pass. Rendering is off-thread and bounded to two concurrent renders, but "no jank" is a measurement
- [x] Delete → restore from trash → content intact — notes and whole folders, files-before-rows, `LibraryIndexTest` (instrumented) covers the cascade and the partial-restore case
- [x] Title search returns results as you type — Unicode-case-folded, searches every folder, `LibraryFilteringTest`

See [docs/M3-LIBRARY.md](docs/M3-LIBRARY.md) for the revision-keyed thumbnail invalidation, what the folder model owes M5, and the two places SPEC turned out to be wrong.

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

Automated (green — `./gradlew build`, **286 unit tests per variant** at this milestone's commit; the
"1068 tests" this line used to claim was Gradle's task count, see `docs/M5-NEXTCLOUD.md` §6):

- [x] **500 offline edits → exactly one upload** — `OutboxDrainerTest`, and `SqlSyncStoresTest` against real SQLite
- [x] **Note edited while its PUT is in flight → not marked SYNCED, re-enqueued** (`local_rev`) — `OutboxDrainerTest`
- [x] Revoke the app password → halt with **no retry loop**: one PUT, one confirmation GET, nothing else — `OutboxDrainerTest`
- [x] The UID test, against a mock instance whose login name is an email address — `LoginFlowV2Test`
- [x] A note titled with emoji and `/` sanitises correctly — `RemoteNameTest`, `NextcloudCapabilitiesTest`
- [x] The app password's name is `Inkstone/1.0 (Android)`, not "okhttp" — `LoginFlowV2Test`
- [x] Delete a bound note → the DELETE annihilates a pending UPLOAD and the binding goes; restore → re-uploads as a create — `OutboxCoalescingTest`, `OutboxDrainerTest`
- [x] **Download the uploaded PDF and verify the incremental update is intact** — done against a **live Nextcloud**: `PdfRoundTripSpikeTest` PASS, byte-identical. **SPEC Appendix B #4 is closed.**

Verified on a device against a real Nextcloud (emulator — see `docs/M5-NEXTCLOUD.md` §7):

- [x] Login works against a real Nextcloud; capabilities fetched and cached
- [x] Airplane mode → create a note → write 20 pages → bind to a folder → **note stays fully
      editable throughout**, and ~55 revisions of offline editing left **one** outbox row
- [x] Disable airplane mode → **uploads automatically within 9 s, no user action**
- [x] Force-stop with a full outbox → relaunch → outbox intact, drains
- [x] A note titled `Physics: Term 1 / 2 📘` synced to `Physics_ Term 1 _ 2 📘.pdf`

Still owed:

- [ ] Login against an instance where the user logs in with an **email address** (uid ≠ loginName).
      Covered by `LoginFlowV2Test` against a mock; the live account tested had uid == loginName.
- [ ] Revoke the app password server-side → check the Nextcloud log: **zero brute-force warnings**
- [ ] A confirmation pass on the **actual Galaxy Tab** (CLAUDE.md definition of done #4)

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

*What landed differently from the list above:* folder mirroring is a **pin** — one library folder
pointed at any remote path, deepest pin wins, everything beneath it follows (SPEC §9). `notify_push`
is deliberately not implemented at all rather than implemented as an optimisation; SPEC §7.6 allows
that, and it is not bundled with Nextcloud. Live re-render covers strokes and not page metadata
(below). Three things not in the scope list turned out to be prerequisites and are in: a per-install
`deviceId`, per-page compaction snapshots, and forward-compatible manifest carrying.

### Acceptance

**The bar:** all 21 rows of SPEC §13.2 as automated tests, against a real Nextcloud in Docker, then
six manual gates.

All 21 rows are written and every one of them runs in `./gradlew build` — **539 unit tests per
variant, 0 failures, 0 skipped** (`:core:model` 142, `:core:sync` 174, `:core:storage` 118, `:app`
62, `:android:pdf` 43, counted from `*/build/test-results/testDebugUnitTest/*.xml`; `build` runs the
same suite again as `testReleaseUnitTest`, so it executes 1,078 and there are 539). Re-measured
2026-08-10 from a run forced with `--rerun --no-build-cache`, because a `BUILD SUCCESSFUL` line
whose test tasks logged `UP-TO-DATE` or `FROM-CACHE` is not evidence about the suite —
`docs/M6-SYNC.md` §7.

*Automated* and *automated and run against a real Nextcloud* are different claims. **26 of the 27
matrix bodies run twice**, unchanged: once against a real HTTP/1.1 server on loopback that speaks
enough Nextcloud to keep the production transport, the real WebDAV client and the real multistatus
parser in the loop, and once against a real Nextcloud 32.0.13 in Docker. Of those 26, **24 assert
their own claims live**; rows 15 and 12b reach the server and then return at a trigger that needs a
shell on the instance. The 27th, row 19, has no server in it by design. Since `CountingProxy` the
live half also measures **request counts**, which is where SPEC §7's economy claims live. The
row-by-row table with a per-row `Live` column, and the three-bucket split of
*written* / *automated* / *automated and run live*, is `docs/M6-SYNC.md` §7.

Automated and green:

- [x] **Rows 1–8, 11, 13, 14, 17, 18, 19, 21** — the union and its clock sort, delete-beats-transform,
      the `deviceId` tiebreak, independent registers, both-add-a-page surviving a `materialize()`,
      two conflicting reorders converging, absolute `SetErasedRegion` beside a transform, snapshot
      staleness on a 3-page note, a move detected with zero GETs, 412 → reconcile → merge →
      re-upload, a remote delete that does not annihilate a pending UPLOAD, 500 saves → one PUT, the
      mid-flight `local_rev` guard, outbox durability across a reopen, and fileid churn on
      trash-restore — `app/src/test/…/sync/matrix/`
- [x] **Three rows SPEC does not list** — a two-device merge settles in ≤3 rounds and then costs one
      `PROPFIND` with no ETag movement, which is §7.6's ETag-ping-pong termination argument
      (`MergeMatrixConvergenceTest`); a rename crossing in both directions, which is §7.7 layer 2
      converging (`MergeMatrixTitleTest`); and an unmergeable file met on four polls leaving **one**
      copy and one dismissed row, which is layer 3 being bounded (`MergeMatrixConvergenceTest`). The
      last two exist because the review found layer 2 and layer 3 broken after the 21 rows were
      green — `docs/M6-SYNC.md` §5.7, §5.8
- [x] **Rows 9, 10, 12 in part.** Layer 3 on an unreadable pull, undo after a pull through a
      *reopened* session, and a cancelled PUT leaving the row and the remote file whole, are
      automated **and run live**. Their remaining halves are not: row 9's flattened-page (>500
      strokes) and recover-then-re-upload variants are not written at all; row 10's wet-ink half is
      instrumented rather than live (`WetInkRemoteMergeTest`, ticked below); row 12's mid-socket
      abort needs a sidecar.
- [x] **Row 19 is automated, and deliberately has no server in it.** It is `runTest`, not
      `matrixRow`: a file-backed SQLite database closed and reopened. That is the right shape for
      "queued work survives a process boundary", and the half a server could never supply — a kill
      *during* the write — is `CrashSafetyTest`'s upload kill, instrumented and green (below).
      Recorded explicitly because "all 21 rows run against a real Nextcloud" was previously written
      as though this row did.
- [x] **Rows 16 and 20 against the real server**, through an in-process counting reverse proxy
      (`CountingProxy`): row 16's weak validator is a genuine `W/"…"` rewrite in front of a real
      Nextcloud, and row 20's 401 is a genuine refusal of a deliberately wrong app password, costing
      exactly the two brute-force attempts it asserts. The proxy is also what makes every *request
      count* assertable live — rows 1, 11, 16, 17, 20 and the convergence row were previously
      printing "counts not observable" — `docs/M6-SYNC.md` §7
- [x] **Row 15 against an in-process switch** standing in for `occ maintenance:data-fingerprint`,
      which needs a shell on the instance. Row 12b's 507 likewise, which would mean filling the
      server's disk. The client-side behaviour is asserted; those two server-side triggers are not
      real, and on the live half **every assertion that is the point of either row is skipped** —
      the live run does the setup, prints `LIVE HALF SKIPPED`, and returns. Row 15's `GET == 0` is
      consequently the one request count in the suite the proxy did not make assertable, and its
      `pruned == 0` is vacuous in the matrix fixture (a run folder has no child collections), so
      that half rests on `ReconcilerWalkTest` instead.
- [x] **Four silent data-loss bugs found and fixed before the matrix was written**, each of which
      would have failed rows underneath every other row — `docs/M6-SYNC.md` §1
- [x] **The defects an adversarial review found *after* the matrix was green**, all fixed with a
      test that fails without the fix: a remote delete that made an open note permanently
      unrestorable, two paths that mass-trashed notes whose files were sitting on the server (the
      pass limit and a 404'd folder), a 401 ladder on WorkManager's backoff, no memory of "this
      remote file is not ours", two unserialized writers, layer 2 wired at neither end, and layer 3
      unbounded in both directions — `docs/M6-SYNC.md` §5.5–§5.8. Worth reading before the ticks
      above are trusted: every one of them sat behind a comment or a KDoc asserting the opposite
- [x] **Five the close-out audit found, four of them one decision expressed in two places** — a
      second door into the merge with no production caller (and no `mergeDocument` in front of the
      `NoteSession.merge` it called), `MergeReport.TooNew` behind a check no input could reach,
      `MaterializeWorker` publishing `document.pdf` from under an open editor and leaving a marker
      describing a bundle that no longer existed, a conflicted-copy name stamped from two clock
      readings, `PENDING_DOWNLOAD` set before the GET that had to succeed for it to be true, and a
      poll interval that a rotation reset. Each has a test that fails without the fix; row 10's JVM
      half now drives the door the host actually calls — `docs/M6-SYNC.md` §5.9
- [x] **`connectedDebugAndroidTest`, every module that has one, on `Test_Tablet(AVD) - 15`** (Pixel
      Tablet, API 35, x86_64):
      - `:core:database` — **47 tests, 0 failures.** `LibraryIndexTest` 15, `SqlSyncStoresTest` 9,
        `SqlReconcileStoresTest` 20 (including the three the review repairs added, which had until
        now compiled without ever executing) and the new `MigrationOnDeviceTest` 3.
      - `:app` — **6 tests, 0 failures**, in a source set that did not exist before this pass.
      - `:android:ink` — **15 tests, 10 failures, and every one is M1's**: nine
        `StrokeStoreEraseTest` cases die on `StrokeInput::orientation … Got: -0.00147393π` because
        the synthetic-ink fixture does not clamp to `[0, 2π)`, and
        `pressureAndTiltSurviveTheRoundTrip` fails an arm64-tuned float tolerance
        (`y at 1 expected:<415.70966> but was:<415.7085>`). Pre-existing, documented in CLAUDE.md
        since M5, **not** an M6 regression and deliberately not "fixed" by loosening M1's tolerances.
- [x] **SPEC §13.3 crash safety — four instrumented process kills, written for the first time.**
      `app/src/androidTest/…/CrashSafetyTest.kt` kills a real second process
      (`dev.inkstone:crashpoint`) at four named instants and asserts a **bound** at each: an
      unflushed stroke is the only loss during capture; the record that was mid-write is the only
      loss during a journal append, and the reader reports the tear rather than throwing; a kill
      between materialization's bundle write and its journal reset loses nothing **and doubles
      nothing**; and a kill while republishing leaves the queued UPLOAD with its attempts unspent
      and `document.pdf` byte-identical to the complete previous version. Also driven by hand
      against the real app: `am force-stop` immediately after a stroke reopened the note at the last
      autosaved revision, which is the same bound from the other side — `docs/M6-SYNC.md` §5.10
- [x] **SPEC §13.1's on-device migration test.** `MigrationOnDeviceTest` builds a **version-4**
      database with real rows on the device — one folder, one note, an account, a binding carrying a
      *weak* ETag, and a queued UPLOAD with two attempts spent — and opens it through the production
      driver so `Schema.migrate` runs `4.sqm` and `5.sqm` for real. Both existing instrumented
      suites open in-memory databases with `name = null`, which calls `Schema.create` and **never**
      `Schema.migrate`, so this path had run on a developer's machine and on no device, ever. The
      version-4 and version-5 fixtures were *generated* from the committed `1.db` plus `1.sqm`–
      `4.sqm`, not transcribed from the current `.sq`
- [x] **Row 10's wet half, on a device.** `WetInkRemoteMergeTest` draws a real stylus stroke, leaves
      it in progress, delivers a peer's `AddObject` through `applyOps(record = false)`, and asserts
      the camera does not move and the in-progress stroke still lands when the pen lifts. Its
      control drives the forbidden path — `loadPage` — and asserts the damage, so the first test
      cannot pass by accident. The peer's geometry is harvested from a stroke the test itself drew,
      so `SyntheticInk`'s x86_64 orientation bug cannot leak into it
- [x] **The M6 app driven end-to-end over `adb` on the emulator**, which is the check that catches
      "correct in every test, inert in the app": a folder created through the UI, *"Always sync to…"*
      reachable from its overflow menu, the folder's cloud badge appearing, a note created inside the
      pinned folder producing a `remoteBinding` at `Inkstone/Lectures/Untitled.pdf` and **MKCOL then
      UPLOAD** in the outbox at the right `local_rev` each, two strokes drawn with
      `input stylus swipe`, and the *"Needs attention"* screen rendering both a known kind and an
      unknown one — `docs/M6-SYNC.md` §5.10.
      ⚠️ **This tick rests on a transcript and cannot be re-checked.** `connectedAndroidTest`
      uninstalled the app afterwards, so `run-as dev.inkstone` now answers *"unknown package"* and the
      database rows quoted above are gone. It is the only M6 tick with no re-runnable artefact behind
      it; the unit suite, the instrumented XML under `build/outputs/` and the live matrix all have one.

Still owed:

- [ ] **Rows 15 and 12b's live triggers.** Both need a shell on the instance and there is no SSH key
      to the Pi: `occ maintenance:data-fingerprint` and a quota the test can fill. The in-process
      switch stands in and the live half skips loudly. Also still not automated: the mid-socket half
      of row 12 (an nginx `limit_rate` sidecar), and reading `nextcloud.log` to confirm row 20 logged
      no brute-force lockout.
      **Satisfied by:** an SSH key on the Pi so `occ=` can go in `nextcloud-test.local.properties`
      (`setDataFingerprint` then returns true and row 15's live half runs to its own `GET == 0`); a
      second Nextcloud user with a tiny quota, or a loopback filesystem the container's data
      directory sits on, for 12b; an nginx `limit_rate` sidecar in `tools/nextcloud-docker/` for 12;
      and `docker exec inkstone-nc grep -i bruteforce data/nextcloud.log` after row 20.

The six manual gates. **None has happened.** Five need a second physical device and the sixth needs
seven days of elapsed time, so nothing here can be closed from a workstation — what each one would
take is written out rather than left as "manual":

- [ ] Two devices, both offline, both write on the same page → reconnect → **both sets present, no prompt**
      **Satisfied by:** the APK on two tablets bound to one Nextcloud folder; airplane mode on both;
      a stroke on each in the same page; airplane mode off; both notes then showing both strokes and
      no dialog having appeared. **Automated stand-in:** row 1, live. What only the devices add is
      the real radio, the real WorkManager scheduling and a human confirming no prompt was shown.
- [ ] Two devices, both offline, both add a page → **both pages present**, neither orphaned
      **Satisfied by:** the same setup, *Add page* on each while offline, then a page count of 3 on
      both after reconnect. **Automated stand-in:** row 5, live, including surviving a `materialize`.
- [ ] Move a note in the Nextcloud **web UI** → detected as a move, no re-download, no duplicate
      **Satisfied by:** renaming or dragging the note's PDF in a browser and watching the library
      show one row at the new path with no download. **Automated stand-in:** row 11's live half,
      which does the MOVE with a real WebDAV client neither device owns. What is still missing is
      Nextcloud's *own* UI as the mover — it may set headers or trigger versioning that `raw.move`
      does not.
- [ ] Delete on the server while local has unsynced edits → local → trash, user notified, nothing lost
      **Satisfied by:** deleting the PDF in the web UI while a tablet holds an unsynced stroke, then
      finding the note in local trash, a *Needs attention* row naming it, and the stroke intact after
      Restore. **Automated stand-in:** row 14, live — and §5.5's first finding, which is what this
      gate would have caught had it been run: it required the note to be **open** at the time.
- [ ] Both devices online with the same page open → strokes appear live, camera doesn't jump, your
      in-progress stroke survives.
      **Satisfied by:** two tablets on one page, one person drawing while the other holds a stroke
      down. **Automated stand-in:** row 10's wet half on an AVD (ticked above) for the camera and the
      wet stroke; row 1 live for the merge. ⚠️ **This gate is expected to fail in one respect and
      the reason is known:** a peer's `SetPageMeta` or a `SetTransform` on an *existing* object does
      not render until a page switch (§8, and the `:android:ink` item below). New strokes do appear.
- [ ] **A full week of real daily use across two devices with deliberate airplane-mode toggling → zero data loss**
      **Satisfied by:** seven calendar days, both tablets in ordinary use, airplane mode toggled at
      arbitrary moments including mid-upload, ending with every note openable and no note in
      `Recovered` mode or holding a conflicted copy nobody asked for. Nothing substitutes: the whole
      point is elapsed time, real batteries, real Doze and real WorkManager deferral, none of which a
      test clock reproduces.
- [ ] **A pass on the actual Galaxy Tab** (CLAUDE.md definition of done #4). The emulator pass is
      done — instrumented suites, four process kills and an `adb`-driven walk through the app — and
      an emulator explicitly does **not** substitute: ink feel, latency and frame timing exist only
      on the tablet, and `:android:ink`'s suite is red on x86_64 for reasons that are green on arm64.
      **Satisfied by:** accepting the USB-debugging dialog on the tablet (it currently reports
      `unauthorized`), then `connectedDebugAndroidTest` for all three modules — `:android:ink`'s 10
      x86_64 failures should go green — plus `dumpsys gfxinfo` for the frame-rate gate and a session
      of actual handwriting. Until the dialog is accepted, `tools/send-to-tablet.ps1` copies the APK
      over MTP but no measurement is possible.
- [ ] **Guardrail 2, the iOS framework link.** `./gradlew build` on this machine runs
      `checkCoreIsPlatformFree` and the iOS **klib** compile; **25 `link*Ios*` tasks log `SKIPPED`**
      (5 `:core:*` modules × 5 link tasks) because Kotlin/Native cannot target Apple on Windows, and
      so do all five `iosSimulatorArm64Test` tasks — so the common tests are compiled for
      Kotlin/Native and never executed on it. Every figure in this section is a JVM result. The macOS
      `ios-core-link` job in `.github/workflows/ci.yml` is what proves the link.
      ⚠️ **It has never run, and cannot: `git remote -v` is empty.** There is no `origin`, so no CI
      has ever executed for this repository at all — the previous wording, "has not run for this
      branch", implied a remote that does not exist. Guardrail 2 has therefore been unproven since
      M0, not since M6. It matters more now than usual, M6 being the first milestone since M5 to add
      a `:core:*` edge (`:core:sync → :core:storage`).
      **Satisfied by:** a remote plus one push, or a macOS machine running `./gradlew linkIosCore`.
- [ ] **M6 is not committed.** `git status`: **54 modified files and 45 untracked paths**, 5,774
      insertions, all of it in the working tree only. `HEAD` is still `d6993e3` ("M5: notes upload
      themselves") on a branch named `m5-nextcloud-auth-upload`. Every figure in this section
      describes a tree that exists on one disk with no remote and no commit — which is also why none
      of it can be reviewed, bisected, or recovered from a lost working copy.
      **Satisfied by:** a branch named for M6, a commit, and — for anything above that says "CI" — a
      remote to push it to.
- [ ] **The `:android:ink` half of live re-render.** A peer's `SetPageMeta` folds correctly, reaches
      the bundle and does **not** appear on screen until the page is switched: `NoteSurface.setPageMeta`
      is a documented no-op on the real surface because `InkSurfaceView` can only change the paper
      through `loadPage`, which refits the camera and cancels the in-progress stroke — both of row
      10's forbidden outcomes. `StrokeStore.applyOps` also still drops `SetTransform` / `SetStyle` /
      `SetZOrder` on the floor, so a peer's transform of an existing object folds and does not
      render. Data-safe, visibly late.
- [ ] **SPEC §13.1's remaining gap: no bundle fixture is committed for any schema version**, so
      *"round-trip every schema version"* is currently a round trip of whatever the current build
      writes. This bullet previously also claimed there was no on-device migration test; that half
      is **closed** — `MigrationOnDeviceTest` runs `Schema.migrate` on a device, 3 tests green
      (ticked above). A committed **bundle** fixture is a different artefact from a database fixture
      and belongs to `:core:storage`.
      ⚠️ **`SPEC.md` §13.1 still asserts both halves are open** and needs the same correction; it was
      out of scope for this documentation pass.

Notes: `docs/M6-SYNC.md`.

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
