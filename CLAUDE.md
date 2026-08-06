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

Two corollaries, both found in M5. **The HTTP client owns no retry policy** — Ktor's `basic` auth provider is challenge-response and would produce a deliberate 401 per call, so the `Authorization` header is attached by hand. And **a WorkManager job that halted on a 401 returns `success`, not `retry`** — WorkManager's backoff would otherwise *be* the credential retry loop. The one permitted follow-up is SPEC §7.2's single `GET /ocs/v2.php/cloud/user`; if that cannot be *reached*, conclude nothing, because treating unreachable as "credentials dead" signs the user out every time they walk into a lift.

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
- **Run the instrumented tests at least once per milestone that adds one.** `check` compiles them; it does not dex or run them, so a dex-illegal test name or a missing `androidx.test:runner` sits there looking like coverage. An emulator is enough — see the README.
- **Performance** — macrobenchmarks for cold start, large-notebook open, sustained draw frame rate

Sync tests run against a **real Nextcloud in Docker**, not a mock. Mocks won't reproduce weak ETags, fileid churn on trash-restore, or brute-force lockout.

---

## Definition of done

A milestone is done when:

1. Every acceptance criterion in `ROADMAP.md` passes — including the manual ones
2. New logic has tests
3. `./gradlew build` is green, including the `:core` guardrail
4. It has been used on the actual Galaxy Tab, not just an emulator — `tools/send-to-tablet.ps1` builds and copies the APK over MTP, which works when `adb install` can't because USB debugging is unauthorized. Measuring still needs adb; see the README.

   **Get there via an emulator, though.** The tablet is the *last* check, not the only one. An AVD gives adb for free, so it can run the instrumented tests, and the app can be driven end-to-end from the command line — `uiautomator dump` to find a control, `input tap` to press it, `cmd connectivity airplane-mode` to cut the network, `run-as … cat databases/inkstone.db` to read the result. ⚠️ **Drawing needs `adb shell input stylus`**, not plain `input`: `PalmGuard` accepts only `TOOL_TYPE_STYLUS`, so an ordinary swipe draws nothing and says nothing. M5 found three real bugs this way in twenty minutes, none of which 1068 JVM tests had caught. See the README's *Testing on an emulator*.
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
| **M4.0 spike result + PDF decisions** | `docs/M4-PDF.md` |
| **M5 Nextcloud decisions + spike #4 status** | `docs/M5-NEXTCLOUD.md` |

---

## Things known to be wrong elsewhere

Collected during research so they don't get reintroduced from stale training data:

- The Samsung S Pen SDK does **not** work on current Galaxy Tabs. Plain `MotionEvent` covers everything a note app needs.
- **`androidx.ink` 1.0.0 does not apply motion prediction.** It *takes* prediction as a parameter and tracks it separately from real input, so `input-motionprediction` is required, not redundant — and un-drawing the predicted tail is ink's job, not the app's. (M1 spike, SPEC §3.1.)
- **Much of `androidx.ink` 1.0.0's brush-authoring surface is `@RestrictTo(LIBRARY_GROUP)`** despite the library being stable: every `BrushFamily` constructor, `StockBrushes.pencilUnstable`, every `toImmutable()`, `StockTextureBitmapStore`, and the whole latency package. Lint fails the build on all of them. This is a standing pressure to move the ink stack to 1.1.0-alpha — **don't**. SPEC §3.4 and `docs/M1-INK.md` §2 record what M1 does instead.
- **`StockBrushes` is not used, and must not be reintroduced.** Its brushes taper by distance-along-stroke, which makes a stroke impossible to cut cleanly — every partial-erase fragment re-runs the taper. Inkstone's own families (`InkstoneBrushFamilies.kt`) forbid any behaviour driven by distance or time along the stroke. That constraint is what keeps erasing correct.
- **`androidx.ink:ink-storage` 1.0.0 cannot serialize a `Stroke`.** It offers `StrokeInputBatchSerialization` and `BrushFamilySerialization` and nothing else — no `Stroke.encode`, no `decodeStroke`, no container format. SPEC §5.4 said otherwise and has been corrected. M2's blob is a small container written by `StrokeGeometryCodec`: brush kind, colour, size, epsilon and input model in a fixed header, then the input batch. Do **not** serialize the `BrushFamily` — the families are code, and every constructor for one is `@RestrictTo(LIBRARY_GROUP)`.
- **A stroke's colour and width live in two places, and only one is the truth.** The geometry blob holds what it was captured with; `SetStyle` is an override that starts null. Duplicating capture-time style into a register would give two sources that drift.
- **`CanvasStrokeRenderer.create(forcePathRendering, textureStore)` is `@RestrictTo(LIBRARY_GROUP)`** — the one-argument overload is not. Found in M3, when thumbnails needed path rendering for a software canvas. The renderer decides that per draw call anyway, so use the public overload.
- **`InProgressStrokesView` front-buffers through a `SurfaceView`, so wet ink cannot carry a blend mode.** On API 33+ it uses `CanvasInProgressStrokesRenderHelperV33`, which SurfaceFlinger composites over the window — and a separate surface only alpha-blends. Any brush whose *dry* rendering uses a blend mode will visibly change appearance at the moment the stroke lands. The escape is `useHighLatencyRenderHelper` (`@RestrictTo(LIBRARY_GROUP)`, like the rest of this library's interesting surface), which moves the wet layer onto HWUI where `setLayerType(LAYER_TYPE_HARDWARE, paint)` applies an xfermode — at the cost of the low-latency path. **No brush currently uses a blend mode**, so nothing pays this; the highlighter tried and reverted (SPEC §3.4). Note also that it is read once when the render helper is built, so setting it after `eagerInit()` does nothing.
- **Over dark ink, "multiply on top" and "drawn behind" are the same pixels.** Multiply cannot lighten, so a yellow highlighter multiplied over black writing leaves the writing exactly black — which is what drawing behind it looks like. Two hours of M4 went into a difference that did not exist. If a z-order or blend change is supposed to be visible, check it against a *light-coloured* stroke, where the two actually differ.
- **`javap` on the `$annotations` synthetic does not tell you whether a Kotlin property is `@RestrictTo`.** It carries the `@Metadata` string table and nothing else; the annotation sits on the getter/setter. Lint is the authority here, and it disagreed with a clean `javap` reading during M4. Check by compiling, not by reading the class file.
- **`addToStroke` must be given the whole `MotionEvent`.** Ink reads pressure, tilt, orientation, tool type and every historical point off it. Hand-built `StrokeInput`s compile, draw, and silently drop all of them.
- **OkHttp and dav4jvm are JVM-only.** They cannot go in `:core:*`. Ktor can — but **Ktor's own types must not leave `:core:sync` either** (M5). `:app` is Android-only, so nothing would complain if it started holding an `HttpClient` and later made a request outside the outbox. `NextcloudTransport` is opaque outside the module, so there is nothing in `:app` to make a request *with*.
- **There is no digest in common Kotlin**, and `java.security.MessageDigest` is JVM-only. SHA-256 (`:core:storage`, SPEC §5.6) and MD5 (`:core:sync`, for SPEC §7.5's `OC-Checksum`, which Nextcloud *verifies*) are both hand-written against published test vectors. MD5 is a transport integrity check and must never become a security primitive.
- **A backtick test-function name may not contain a comma — and in an instrumented test, not an apostrophe either.** Kotlin/Native rejects the comma (`Name contains illegal characters: ","`) and **D8 rejects both** (`Method name '…' cannot be represented in dex format`). The JVM compiler accepts them all, so neither surfaces until `compileTestKotlinIosArm64` or `dexBuilderDebugAndroidTest` runs. Keep instrumented test names to `[A-Za-z0-9 _-]`. Found in M5, twelve names deep.
- ⚠️ **`androidx.test:runner` is NOT transitive from `androidx.test.ext:junit`** — and `testInstrumentationRunner` names a class from it. Without the dependency every instrumented test in the module dies with `ClassNotFoundException: androidx.test.runner.AndroidJUnitRunner` before a single test runs. `:core:database` and `:android:ink` were both missing it and nobody knew, because **`check` compiles instrumented tests but does not dex or run them** — the guard M3 added catches a broken *signature*, not a broken *classpath*. Run `connectedDebugAndroidTest` against something at least once per milestone that adds instrumented tests.
- **`:android:ink`'s instrumented tests fail on an x86_64 emulator and pass on the arm64 tablet.** Not a logic bug: M1's synthetic-ink fixture computes orientations without clamping to `[0, 2π)`, and x86_64 rounding puts them a thousandth outside, which `androidx.ink` rejects (`StrokeInput::orientation … Got: -0.00147393π`). One assertion also has an arm64-tuned float tolerance. Found in M5; **not fixed, because it is M1's code**. Anything measuring ink still needs the real tablet regardless (CLAUDE.md definition of done #4).
- **A weak ETag has to be stored as a flag beside the value, not recomputed.** SPEC §7.3: returning `W/"…"` in `If-Match` is a permanent 412 loop, and "was this weak" cannot be recovered from the stripped string. `ETag(value, weak)` carries it and `asIfMatch` returns **null** for a weak one, so the trap is unrepresentable rather than remembered.
- **"Was this ever uploaded" cannot be read from `oc:id` and the ETag alone** (M5). A reverse proxy that strips `OC-ETag`/`OC-FileId` leaves a *successful* upload looking un-uploaded, and the next PUT sends `If-None-Match: *` against a file that exists — a permanent 412. Keep a local upload timestamp too.
- **`EncryptedSharedPreferences` was deprecated in 2025.** Keystore AES-GCM directly.
- Nextcloud's `loginName` is **not** the WebDAV path segment. Fetch the UID from `/ocs/v2.php/cloud/user`.
- Nextcloud forbids only `\` and `/` by default — **not** the Windows character set. Read the real rules from capabilities.
- A file's ETag does **not** change when it moves. Same `oc:id` + same ETag + new path = a move.
- `notify_push` is **not** bundled and **not** commonly available. Poll by default; push is an optimisation only.
- Nextcloud **full-text search is not in a default install** — it needs the `fulltextsearch` app plus a search backend. `pdfgrep` is the reliable test.
- **`androidx.pdf` does not write `/Ink` annotations, and cannot.** (M4.0 spike, SPEC §6.1.) `androidx.pdf.ink` converts every stroke into a **`/Stamp`** holding a filled path, because the framework type it targets — `android.graphics.pdf.component.PdfAnnotationType` — has only `UNKNOWN`, `FREETEXT`, `HIGHLIGHT`, `STAMP`. There is no ink annotation in the platform API. It also **cannot set `/NM`** (annotation identity there is the integer index on the page — the thing rule "identity is never array position" exists to prevent), and its save is a full PDFium re-serialisation via `PdfRenderer.write`, which makes SPEC §5.2's incremental update impossible. **`androidx.pdf` is not a dependency of this project.** Don't reintroduce it for rendering either: it would force compileSdk 37 or an ext-19 platform package for one bitmap call, and framework `PdfRenderer` does the job from plain `android-36`.
- **PdfBox-Android 2.0.27.0 has no `PDAnnotationInk` and does not bundle `xmpbox`** (M4). SPEC §6.3's table said otherwise and has been corrected. `/Ink` is written at the COS level and the XMP marker as a raw `/Metadata` stream; both work, neither has a typed helper.
- **PDFBox's `saveIncremental` writes only objects marked `setNeedToBeUpdated(true)` — including ones you just created — and the mark must be on the whole parent chain.** A marked page hanging off an unmarked `/Pages` node is never reached. The output is a valid PDF, opens everywhere, has the right page count, and contains no ink. Found in M4 by a test that parsed the output; see `PdfBoxWriter.markParentChain`.
- **`PdfRendererPreV` needs S extension level ≥ 13, not just API 30.** It ships in the mainline PDF module (M4). Check at runtime.
- PDF `/Ink` **cannot** express variable stroke width. Pressure goes in an `/AP` appearance stream.
- **An erased stroke cannot be subtracted with even-odd or reversed-winding fills.** Both re-fill wherever two eraser capsules overlap, which is what every scribbled erase produces. The subtraction happens in `:android:ink` via Skia's `Path.op(DIFFERENCE)` and what crosses into `:android:pdf` is what is left (M4, SPEC §3.5).
- PDF page size comes from **CropBox with `/Rotate` applied**, not MediaBox. Viewers display CropBox.
- ML Kit's recogniser assumes **one line of text**. Segment first or the output is garbage.
- ML Kit GenAI and Gemini Nano do **not** do handwriting recognition. There is no successor to Digital Ink Recognition.
- A PDF with ink annotations and no embedded bundle is **not necessarily ours** — it might be a stranger's Acrobat markup. That's why the XMP marker exists (SPEC §5.6).
