# CLAUDE.md — Inkstone

Repo conventions and guardrails. Read `SPEC.md` for what to build and `ROADMAP.md` for the order.

---

## What this project is

A handwriting-first note-taking app for Android tablets (Samsung Galaxy Tab + S Pen), where notes are stored as ordinary PDFs in the user's Nextcloud, the app works fully offline, and data loss is treated as a bug of the highest severity.

---

## Non-negotiable rules

These exist because violating them causes damage that's expensive or impossible to undo.

### 1. Never lose user data

- **No hard deletes.** Everything goes to a trash with 30-day retention — local trash for local deletes, Nextcloud trash for remote.
- **Never resolve a sync conflict by discarding bytes.** If two versions can't merge, keep both as a conflicted copy.
- **Never overwrite a remote file without `If-Match`.** A 412 means someone else changed it; that is a conflict, not a retry.
- `assets/source.pdf` inside a bundle is **immutable**. An imported PDF's original bytes are preserved forever.
- Before any destructive local operation, the previous version goes to trash first.

### 2. `:core:*` must stay platform-free — and the iOS target is what proves it

`:core:model`, `:core:storage`, `:core:database`, `:core:sync`, `:core:search` may not import `android.*` or `androidx.*` (except `androidx.annotation`) **in a shared source set**, **and must keep linking for `iosSimulatorArm64`**.

(`androidMain` is exempt — it is where `actual` implementations live, and `:core:database`'s Android SQLite driver needs a `Context`. The import check skips `android*` source sets; the iOS link is what actually guards the rule. See SPEC §2.3.)

Both checks run in CI, and the second is the one that matters: **OkHttp and dav4jvm are JVM-only**, so they'd sail past the import check and silently kill the iPad path for ten milestones. `:core:sync` uses **Ktor**, and hand-rolls the PROPFIND XML that SPEC §7.3 provides verbatim.

If core needs a platform capability, define an interface in `:core:model` (see SPEC §2.2) and implement it in `:app`.

### 3. Never invent a version number

SPEC §0.2 has the pins, verified **2026-08-05**. Several contradict older tutorials and training data:

- `androidx.ink` is **stable at 1.0.0**, not alpha
- The Compose ink set is **incomplete** on the 1.0.0 line — `ink-authoring-compose:1.0.0` exists, `ink-rendering-compose:1.0.0` is a 404 — which is why Google's own docs pin a combination that doesn't resolve. Use the View-based stable line; do **not** move the ink stack to alpha to get the Compose artifacts
- **Room 3.0 is not stable** — use SQLDelight
- **`EncryptedSharedPreferences` was deprecated in 2025** — use Keystore AES-GCM directly
- The **Samsung S Pen SDK is dead** (last release Sep 2019; BLE removed on the Tab S11). No Samsung dependency.

If a coordinate doesn't resolve from a clean cache, **report the actual available version with a link to its release page and stop.** Don't substitute silently, don't invent.

### 4. Sync goes through the outbox

No code path performs a network write directly. Every remote mutation enqueues an outbox op, drained by WorkManager. Rows are **markers, never payloads** — never store note bytes in the outbox. Coalescing is an UPSERT on `(note_id, op_type)`. Full semantics in SPEC §7.6; implement all of them, not just a queue.

### 5. Never retry a 401

Nextcloud has brute-force protection on by default. A 401 halts the sync worker and flags `NEEDS_REAUTH`. Auto-retrying credentials is the documented way real users get IP-blocked by their own server.

### 6. No conflict dialogs

Conflicts resolve automatically and non-destructively, then appear in a "Needs attention" list. Never a modal. Never blocking sync. Nextcloud's official Android client uses the modal-from-notification pattern and has ~9 open bugs from it.

### 7. Nothing heavy on the ink path

`MotionEvent` → prediction → `InProgressStrokesView` does no allocation in a loop, no I/O, no serialization, no recognition. If a feature needs to run while the user is writing, it doesn't.

### 8. Ops are absolute, never deltas

`SetTransform`, `SetStyle`, `SetZOrder` carry **absolute values** as LWW registers. Deltas break silently under concurrency — SPEC §4.2 has the worked example where a concurrent scale, rotate, and undo produce a skewed object on both devices, consistently, with no error.

Corollary: **never mutate a stroke's point list in place.** M1 went further: partial erase never touches the stroke at all. It records the *area* the eraser swept beside it and subtracts that at draw time (SPEC §3.5, revised twice). Sample-based erasing cannot erase part of a line — only cut it — because it removes whole samples at full stroke width. The op this needs, `SetErasedRegion`, is **absolute** like every other register here.

### 9. Re-fold, never patch

When an incoming op has a Lamport clock lower than ops already applied, **re-fold the page from the last snapshot**. Appending it to already-applied state diverges. SPEC §4.3.

### 10. Atomic writes, always

Write-temp-then-rename for every file. The upload worker must never read a file mid-write — that produces a torn ZIP inside a PDF, which is the exact corruption class this design exists to prevent.

---

## Architecture

See SPEC §2.1 for the module graph. In short:

- `:core:*` — pure Kotlin, KMP, no Android
- `:android:*` — platform implementations (ink, pdf, ocr)
- `:app` — Compose UI, DI, WorkManager

**Dependency direction is one-way:** `:app` → `:android:*` → `:core:*`. A `:core` module never depends on an `:android` module. An `:android` module never depends on `:app`.

Third-party types don't leak across module boundaries. `dav4jvm` stays inside `:core:sync`. PdfBox types stay inside `:android:pdf` behind a `PdfWriter` interface. ML Kit types stay inside `:android:ocr`.

---

## Code conventions

**Language:** Kotlin only. No Java.

**UI:** Jetpack Compose + Material 3. The one exception is the ink surface, which wraps `InProgressStrokesView` in `AndroidView` (SPEC §3.2 explains why).

**Async:** Coroutines + Flow. `WorkManager` for anything that must survive process death.

**Identity:** UUIDv7 for all IDs, in value classes (`DocumentId`, `PageId`, `StrokeId`). **Never** derive identity from array position, title, or file path.

**Time:** Lamport clocks for ordering ops. `Instant` only for display. **Never order sync operations by wall clock** — device clocks disagree.

**Errors:** sealed result types across module boundaries. Exceptions only for genuine programming errors.

**Nullability:** no `!!` outside tests. If it can't be null, model it so.

---

## Testing

Required before a milestone is done:

- **`:core:storage`** — round-trip byte equality; forward compatibility (unknown ops and fields survive); a fixture per historical schema version, committed; an on-device migration test
- **`:core:sync`** — all **21 rows** of the SPEC §13.2 merge matrix, as automated tests
- **`:core:model`** — coordinate conversions, including `/Rotate 90` and non-zero-origin CropBox
- **Crash safety** — instrumented process kill during stroke capture, journal flush, materialization, and upload
- **Performance** — macrobenchmarks for cold start, large-notebook open, sustained draw frame rate

Sync tests run against a **real Nextcloud in Docker**, not a mock. Mocks won't reproduce weak ETags, fileid churn on trash-restore, or brute-force lockout.

---

## Definition of done

A milestone is done when:

1. Every acceptance criterion in `ROADMAP.md` passes — including the manual ones
2. New logic has tests
3. `./gradlew build` is green, including the `:core` guardrail
4. It has been used on the actual Galaxy Tab, not just an emulator — `tools/send-to-tablet.ps1` builds and copies the APK over MTP, which works when `adb install` can't because USB debugging is unauthorized. Measuring still needs adb; see the README.
5. `SPEC.md` has been updated if anything in it turned out to be wrong

---

## Working with me

**One milestone per session.** Don't start the next one.

**Spike before depending on the unverified.** SPEC Appendix A lists three genuinely unknown things. M4.0 (does `androidx.pdf` write standard `/Ink` annotations, and does pressure survive a round-trip?) is a real fork in the design — report the result before building on it.

**Say when the spec is wrong.** It was written from research, not from having built this. If reality disagrees, reality wins — tell me, and update `SPEC.md` in the same commit as the fix.

**Prefer boring.** This app's value is in reliability and ink feel, not cleverness. A well-tested obvious solution beats an elegant one.

**Don't gold-plate.** If it's in SPEC §12 (out of scope), it's out of scope. Note the idea and move on.

---

## Quick reference

| Thing | Where |
|---|---|
| Pinned versions | SPEC §0.2 |
| Module graph | SPEC §2.1 |
| Interfaces core needs from the platform | SPEC §2.2 |
| Ink input requirements | SPEC §3.3 |
| Erase model (area-based since M1) | SPEC §3.5 |
| Op vocabulary + absolute transforms | SPEC §4.2 |
| Convergence / re-folding | SPEC §4.3 |
| **Coordinate spaces** | SPEC §4.4 |
| Camera / viewport | SPEC §4.5 |
| Compaction rules | SPEC §4.7 |
| **Journal vs materialized PDF** | SPEC §5.1 |
| Published PDF + incremental update | SPEC §5.2 |
| Stroke geometry as opaque bytes | SPEC §5.4 |
| Invisible text layer | SPEC §5.5 |
| **Document identity + Recovered mode** | SPEC §5.6 |
| Thumbnail cache | SPEC §5.7 |
| PDF `/Ink` + `/AP` + density rule | SPEC §6.4 |
| Nextcloud auth | SPEC §7.1 |
| ETag rules | SPEC §7.4 |
| **Outbox semantics** | SPEC §7.6 |
| Conflict policy | SPEC §7.7 |
| Merge test matrix (21 rows) | SPEC §13.2 |
| Out of scope | SPEC §12 |
| Open spikes + owning milestone | SPEC Appendix B |

---

## Things known to be wrong elsewhere

Collected during research so they don't get reintroduced from stale training data:

- The Samsung S Pen SDK does **not** work on current Galaxy Tabs. Plain `MotionEvent` covers everything a note app needs.
- **`androidx.ink` 1.0.0 does not apply motion prediction.** It *takes* prediction as a parameter and tracks it separately from real input, so `input-motionprediction` is required, not redundant — and un-drawing the predicted tail is ink's job, not the app's. (M1 spike, SPEC §3.1.)
- **Much of `androidx.ink` 1.0.0's brush-authoring surface is `@RestrictTo(LIBRARY_GROUP)`** despite the library being stable: every `BrushFamily` constructor, `StockBrushes.pencilUnstable`, every `toImmutable()`, `StockTextureBitmapStore`, and the whole latency package. Lint fails the build on all of them. This is a standing pressure to move the ink stack to 1.1.0-alpha — **don't**. SPEC §3.4 and `docs/M1-INK.md` §2 record what M1 does instead.
- **`StockBrushes` is not used, and must not be reintroduced.** Its brushes taper by distance-along-stroke, which makes a stroke impossible to cut cleanly — every partial-erase fragment re-runs the taper. Inkstone's own families (`InkstoneBrushFamilies.kt`) forbid any behaviour driven by distance or time along the stroke. That constraint is what keeps erasing correct.
- **`androidx.ink:ink-storage` 1.0.0 cannot serialize a `Stroke`.** It offers `StrokeInputBatchSerialization` and `BrushFamilySerialization` and nothing else — no `Stroke.encode`, no `decodeStroke`, no container format. SPEC §5.4 said otherwise and has been corrected. M2's blob is a small container written by `StrokeGeometryCodec`: brush kind, colour, size, epsilon and input model in a fixed header, then the input batch. Do **not** serialize the `BrushFamily` — the families are code, and every constructor for one is `@RestrictTo(LIBRARY_GROUP)`.
- **A stroke's colour and width live in two places, and only one is the truth.** The geometry blob holds what it was captured with; `SetStyle` is an override that starts null. Duplicating capture-time style into a register would give two sources that drift.
- **`addToStroke` must be given the whole `MotionEvent`.** Ink reads pressure, tilt, orientation, tool type and every historical point off it. Hand-built `StrokeInput`s compile, draw, and silently drop all of them.
- **OkHttp and dav4jvm are JVM-only.** They cannot go in `:core:*`. Ktor can.
- **`EncryptedSharedPreferences` was deprecated in 2025.** Keystore AES-GCM directly.
- Nextcloud's `loginName` is **not** the WebDAV path segment. Fetch the UID from `/ocs/v2.php/cloud/user`.
- Nextcloud forbids only `\` and `/` by default — **not** the Windows character set. Read the real rules from capabilities.
- A file's ETag does **not** change when it moves. Same `oc:id` + same ETag + new path = a move.
- `notify_push` is **not** bundled and **not** commonly available. Poll by default; push is an optimisation only.
- Nextcloud **full-text search is not in a default install** — it needs the `fulltextsearch` app plus a search backend. `pdfgrep` is the reliable test.
- PDF `/Ink` **cannot** express variable stroke width. Pressure goes in an `/AP` appearance stream.
- PDF page size comes from **CropBox with `/Rotate` applied**, not MediaBox. Viewers display CropBox.
- ML Kit's recogniser assumes **one line of text**. Segment first or the output is garbage.
- ML Kit GenAI and Gemini Nano do **not** do handwriting recognition. There is no successor to Digital Ink Recognition.
- A PDF with ink annotations and no embedded bundle is **not necessarily ours** — it might be a stranger's Acrobat markup. That's why the XMP marker exists (SPEC §5.6).
