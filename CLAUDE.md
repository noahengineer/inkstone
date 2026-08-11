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

### ⚠️ Before you decide a device check is impossible, run `adb devices`

**There is usually an emulator already attached.** Check first, every time, and check *early* —
before planning the milestone's verification, not after writing the code.

This is written at the top because M6 got it wrong for most of a session. The information was
already in this file (definition of done #4, below) and in the README, and it was still read as
"the tablet is unavailable, therefore no device verification is possible" — so a reconciler, a
folder-pinning feature and a PDF fix were all shipped having never run on anything. Two of the
three were **inert in the app while green in every test**, which is precisely the failure an
emulator catches in twenty minutes and a JVM suite cannot catch at all.

The tablet and the emulator answer different questions. Do not substitute either way:

| | Emulator | Physical Galaxy Tab |
|---|---|---|
| `connectedDebugAndroidTest` | ✅ | ✅ (only if USB debugging is authorized) |
| Driving the app end-to-end over `adb` | ✅ | ✅ |
| Crash safety — `am force-stop` mid-write | ✅ | ✅ |
| On-device schema migration | ✅ | ✅ |
| "Is this feature actually reachable?" | ✅ **use it for this** | ✅ |
| "Did the screen actually change?" | ✅ compare `screencap` framebuffers | ✅ |
| Two devices as two sync peers | ✅ a second AVD (see below) | one device only |
| Ink feel, latency, frame timing | ❌ | ✅ **only here** |
| `:android:ink`'s instrumented suite | ❌ ten fail on x86_64 (see below) | ✅ |
| Definition of done #4 | ❌ does not substitute | ✅ |

`adb devices` reporting the tablet as `unauthorized` does **not** mean no device is available —
that is the case `tools/send-to-tablet.ps1` exists for, and an emulator is usually attached
alongside it.

**Two peers is a second AVD, and cloning it has one hard rule: copy `config.ini` and nothing
else.** `applicationId` has no debug suffix, so a second install needs a second device. But
`DeviceIdentity` is a file beside `notes/`, minted once per install — so copying a snapshot or
`userdata-qemu.img` gives both "peers" the **same `deviceId`**, which collapses SPEC §4.1's
tiebreak: concurrent ops at the same counter compare equal, `Register.write` keeps the incumbent,
and the two diverge permanently with every op present and no error. That is the bug `DeviceIdentity`
exists to prevent, reintroduced by the test setup. Read `files/device.id` off both and confirm they
differ before believing anything a pair of them tells you. `tools/emulator-ui.py` drives either.

**Neither peer can sign in to the plain-HTTP test container, and no manifest or resource change
fixes it.** Three gates, not one: the network-security config; `LoginFlowV2.begin`'s `isSecure`
check on the typed URL, which lives in `commonMain` and refuses `http://` before a socket is
opened; and the app password, which only the login flow can write into the Keystore. A `src/debug`
NSC overlay clears the first and buys nothing. Two-peer work needs an **HTTPS** Nextcloud, and the
sign-in is the user's step either way — the app never sees the password (SPEC §7.1).

### Required before a milestone is done

- **`:core:storage`** — round-trip byte equality; forward compatibility (unknown ops and fields survive); a fixture per historical schema version, committed; an on-device migration test
- **`:core:sync`** — all **21 rows** of the SPEC §13.2 merge matrix, as automated tests
- **`:core:model`** — coordinate conversions, including `/Rotate 90` and non-zero-origin CropBox
- **Crash safety** — instrumented process kill during stroke capture, journal flush, materialization, and upload. Written in M6: `app/src/androidTest/…/CrashSafetyTest.kt`. ⚠️ **The kill has to happen in a process the test is not in.** An instrumented test runs *inside* `dev.inkstone`, so killing that process takes the assertions with it, and no external `am force-stop` can be aimed at the instant between two writes. The subject is a debug-only `Service` in `dev.inkstone:crashpoint` (`app/src/debug/…/CrashPointService.kt`) that does the writing and then SIGKILLs itself. It cannot live in `src/androidTest`: a component declared there belongs to `dev.inkstone.test`, which has its own uid and therefore its own `filesDir`. Assert a **bound**, never just "it still opens".
- **Run the instrumented tests at least once per milestone that adds one.** `check` compiles them; it does not dex or run them, so a dex-illegal test name or a missing `androidx.test:runner` sits there looking like coverage. An emulator is enough — see the README. ⚠️ `connectedAndroidTest` **uninstalls the app afterwards**, so any `adb`-driven walk through the app has to come after it, not before, or the data it set up is gone.
- **An `ActivityScenario` host activity also has to be in the app's own package.** `Instrumentation.startActivitySync` refuses one declared in the test APK: *"Intent in process dev.inkstone resolved to different process dev.inkstone.test"*. `app/src/debug/…/InkHostActivity.kt` is where row 10's wet half gets its window.
- **Performance** — macrobenchmarks for cold start, large-notebook open, sustained draw frame rate

Sync tests run against a **real Nextcloud in Docker**, not a mock. Mocks won't reproduce weak ETags, fileid churn on trash-restore, or brute-force lockout.

---

## Definition of done

A milestone is done when:

1. Every acceptance criterion in `ROADMAP.md` passes — including the manual ones
2. New logic has tests
3. `./gradlew build` is green, including guardrail 1 (`checkCoreIsPlatformFree`) and the iOS **klib compile**. It does **not** include guardrail 2's framework link off macOS — `linkIosCore` and every `link*IosSimulatorArm64` task log `SKIPPED` on Windows, and the macOS CI job is what proves them. "Green including both guardrails" is not a claim a Windows build supports; say which half ran.

   ⚠️ **A green `build` routinely executes zero tests, and then it is evidence about nothing.** Found in M6's closing audit, three times in a row: `BUILD SUCCESSFUL in 18s` with every `test*UnitTest`, `detekt`, `spotlessCheck` and `checkCoreIsPlatformFree` logging `UP-TO-DATE`. Deleting `build/test-results` does **not** force them — the second attempt returned `:app:testDebugUnitTest FROM-CACHE`, restored from the Gradle build cache without running a method. **Before quoting a build line as evidence, check that its test tasks are neither `UP-TO-DATE` nor `FROM-CACHE`**; force with `--no-build-cache` plus `--rerun` per test task. This bites hardest on tests that reach outside the build — the live merge matrix talks to a real Nextcloud, and an up-to-date check will skip it without a word.
4. It has been used on the actual Galaxy Tab, not just an emulator — `tools/send-to-tablet.ps1` builds and copies the APK over MTP, which works when `adb install` can't because USB debugging is unauthorized. Measuring still needs adb; see the README.

   **Get there via an emulator, though.** The tablet is the *last* check, not the only one. An AVD gives adb for free, so it can run the instrumented tests, and the app can be driven end-to-end from the command line — `uiautomator dump` to find a control, `input tap` to press it, `cmd connectivity airplane-mode` to cut the network, `run-as … cat databases/inkstone.db` to read the result. ⚠️ **Drawing needs `adb shell input stylus`**, not plain `input`: `PalmGuard` accepts only `TOOL_TYPE_STYLUS`, so an ordinary swipe draws nothing and says nothing. M5 found three real bugs this way in twenty minutes, and its whole JVM suite — 286 unit tests per variant at the time — had caught none of them. See the README's *Testing on an emulator*.
5. `SPEC.md` has been updated if anything in it turned out to be wrong

6. **Every feature the milestone claims has been reached the way a user reaches it.** A green suite does not establish that a feature is connected to anything, and in this codebase that is the single most common way a milestone ships broken. M6 shipped four features that were correct in every test and **inert in the app** — folder pinning bound nothing, `enqueueReconcile(noteId)` had no caller, the LWW title had no production writer *and* no production reader, and `applyRemoteOps` had six green tests and no caller for the whole milestone. M3 and M4 each had one. In all six cases the tests passed and the user would have seen nothing happen.

   Four checks catch it, and they are cheap. Do all four, per feature, and say which one you used:

   - **Drive it over `adb`** on the emulator, through the UI, and read the result out of `databases/inkstone.db` with `run-as`. This is the only check that catches all six. Twenty minutes.
   - **Grep for the production caller of every new public entry point**, transitively, and stop at something a user can press. "It has tests" is not a caller; a test *is* the caller, which is exactly the illusion.
   - **Distrust a fixture that is more generous than production.** M6's rename helper called `session.rename` directly while production renamed through the library; `FakeMergeGate` returned one id for every conflicted copy, so "one copy per conflict" could not be written against it. Build harnesses out of production objects wherever possible, and where a test can only reach a seam, say so in the test's own KDoc so the green tick does not imply more.
   - **Verify output with a reader that is not the writer.** M4's PDFs were checked by re-parsing them with PdfBox, the library that wrote them; they were valid, parseable, and blank in every other viewer.

   Corollary: a checklist tick belongs to a *claim*, not to a file existing. Say which of *written*, *automated*, *automated and run against the real dependency*, and *run on a device* each one is — `docs/M6-SYNC.md` §7 does this per row, and the three buckets were not the same set.

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
| **Undo — per page, and why** | SPEC §4.6 |
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
| **M6 pull/merge/conflict decisions + the 21-row status table** | `docs/M6-SYNC.md` |
| **M7 recognition/search decisions + the segmented-vs-blob numbers** | `docs/M7-RECOGNITION.md` |
| **M12 lecture recording — capture built, alignment still designed** | `docs/M12-AUDIO.md` (§15 is what exists) |

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
- ⚠️ **`deviceId` is per *install*, and it was per *note* for four milestones** (M6). Reading it from `note.document.deviceId` gives every device the id of whichever one *created* the note, which collapses SPEC §4.1's tiebreak: two concurrent ops at the same counter compare **equal**, `Register.write` keeps the incumbent, the incumbent is whichever the local fold saw first, and the two devices diverge **permanently with every op present and no error**. That is SPEC §13.2 row 3, and both M2 and M5 wrote the note-derived id down as temporary without fixing it. It is now `DeviceIdentity` in a file beside `notes/` — a file, not a DB row, because SPEC §13.1's recovery story deletes the database and a re-minted identity re-opens every tie the device ever took part in.
- **When a default argument can lose data, delete the parameter** (M6). `NoteSession.apply(pageId, ops, undoable = true)` put a *peer's* strokes on the local undo stack whenever a caller forgot the third argument — persisted to `undo.json`, so the user's next two-finger tap deleted a collaborator's work across a restart. `UndoStack` could not enforce it; it was a property of the call site. Three named methods (`apply` / `replay` / `merge`) made "forgot an argument" unreachable. Same reasoning removed `close()`'s `syncState` default, which every caller was taking and which made SPEC §4.7's most important refusal dead code.
- **A merge must carry the *peer's* unknown bundle entries and manifest members, or materializing deletes them** (M6). `materialize` read `carried` from the local reader only, and `BundleReader` rebuilt the manifest from a fixed field list. Both drops are invisible by definition — the lost data is exactly what this build cannot render (SPEC §5.8).
- **A compaction snapshot is per *page*, and `compactIfDue` loops pages** (M6). One path per document meant every page but the last overwrote the previous one, so `readSnapshot` answered null for all of them — harmless (a missing snapshot is a re-fold) until SPEC §13.2 row 8, which then passed or failed on which page the test picked. Also: the causal-stability horizon is the oldest op *being folded*, never `document.createdEpochMillis` — that made the gate pass for ops seconds old on any note over 30 days, which is every notebook it exists to protect.
- ⚠️ **A feature can be correct in every test and inert in the app.** Three times in M6: folder pinning recorded its mapping and bound nothing; `enqueueReconcile(noteId)` existed on both sides of the boundary with no caller; the PDF `/Resources` were checked by re-parsing with the library that wrote them. All green. The gap each time was the absence of anything that ran the **real path** — and `LibraryRepository` cannot be constructed in a JVM test at all, because its `PdfGateway` needs `androidx.ink`'s native code. Hoist the *decision* into a pure function over plain data so a test can at least assert the decision was made.
- ⚠️ **A `= Unit` override with a recording fake in front of it is the most convincing form of that bug — and it can hide a second one.** `InkScreen`'s `NoteSurface.setPageMeta` was `= Unit` for all of M6 with a KDoc explaining why; `FakeSurface.setPageMeta` recorded the call, and a test asserted the recording. Every assertion was true of the editor and false of the app. Two lessons. First, the stub was **not only a sync gap**: the undo of a *local* template change takes the same path, so "pick a paper, press undo" did nothing on screen, on one device, by hand — a stub reached from one caller is nearly always reached from another. Second, **a stub that swallows a write can be the only thing hiding a corruption**: the undo stack had no page dimension, so the inverse `SetPageMeta` was being applied to *whichever page was on screen*, and implementing `setPageMeta` without fixing that would have made a wrong-page write visible instead of merely silent. Before implementing a stub, ask what has been writing into the void.
- **An entry on an undo stack must name the page it happened on** (M6 follow-up). `UndoEntry` carried only `forward`/`inverse`, `UndoStack.undo` popped the document-wide top, and `NoteSession.undo(pageId)` replayed it onto the page the *caller* named. Change page 1's paper, turn to page 2, press undo: page 2's paper changes, is journaled and is uploaded, and pressing undo again pops the next entry rather than putting it right. A stroke undo mis-fired identically but silently — an inverse `DeleteObject` for a page-1 stroke is a tombstone for an object page 2 never had, which the fold accepts by design (SPEC §4.2). The stack is per page now and `canUndo`/`canRedo` take one, which also makes the button honest. Same shape as the `undoable` boolean above: the wrong call was *representable*, so eventually it was made.
- **`session.flush()` is half of saving; `library.onSaved` is the other half** (M6 follow-up). It is the only route to `thumbnails.invalidate` **and** `sync.noteChanged`, and `saveLocked`'s call to it is gated on `hasUnflushedWork` — which a bare `flush()` has just cleared. So every structural edit that flushed on its own (add page, delete, move, duplicate, change paper) left the library tile's page count stale for the whole session and **enqueued no upload**: the change reached the server only if the user later drew a stroke, or closed the note. Structural edits are exactly the ones a user makes and then puts the tablet down. `NoteEditor.flushAndReport` is now the only way to flush.
- ⚠️ **"Only publish through the gate" was a KDoc, and the upload path did not read it** (M6 gate 5). `NoteSessionHost` exists to be the single writer of a note an editor may hold — a per-note mutex plus a registry of live editors. `MaterializeWorker` routed through it and carried the warning. **`OutboxDrainer` was injected with the raw materializer and called it directly before every PUT**, so for an *open* note it published from outside the gate and then recorded `lastSyncedRev` from bytes that did not contain the editor's work. After that `SyncEngine.noteChanged` reads `localRev <= lastSyncedRev` and enqueues nothing — the device goes **permanently silent for that work while the note reads `SYNCED`**. Two emulators on a real server found it in one afternoon; 28 green matrix rows did not, because `TwoDevices.Device.edit` materializes before every `noteChanged` and so no row can model an open note. The fix is two types instead of one — `DocumentMaterializer` is the gate and has exactly one implementation, `PdfPublisher` is the raw one and only the host and the editor may hold it — because a parameter that can only be given the safe thing needs no KDoc. Corollary: **a `Boolean` return is not enough for a publish.** The drainer needs the revision the *bytes* were built from, and `NoteEditorHandle.materialize()` answering true/false is what let a wrong revision be recorded.
- **A setter that moves the camera is a trap wherever it is called from.** `InkSurfaceView.pageSize`'s setter called `resetCamera(FIT_WIDTH)` unconditionally, so *every* write to it was a camera move — `loadPage` wanted one and nothing else did, and "nothing else" came to include a peer's paper change arriving mid-stroke. The refit is explicit in `loadPage` now and `setPageMeta` re-leashes instead. ⚠️ `Camera.leashedTo` is **not** safe before layout: it builds a `coerceIn` range from the viewport extent, and a zero viewport inverts that range and throws. `resetCamera` and `onSizeChanged` both guard on `width > 0 && height > 0`; anything new that leashes must too.
- **`:app`'s unit tests are the only source set that sees `:core:sync`, `:core:storage` and `:android:pdf` together**, which is why SPEC §13.2's matrix lives there (M6). Two things make it work: `unitTests.isReturnDefaultValues = true`, because OkHttp's platform detection reads `android.os.Build.VERSION.SDK_INT`; and `JdbcSqliteDriver(IN_MEMORY)`, so the real `Sql*` stores are under test rather than a `LinkedHashMap` fake. **`com.sun.net.httpserver` does not exist on that classpath** — it is `android.jar` plus `java.*`, no `jdk.httpserver` — so a stand-in server is hand-rolled over `ServerSocket`, which has the side benefit of keeping the production transport and the real multistatus parser in the loop.
- **OkHttp and dav4jvm are JVM-only.** They cannot go in `:core:*`. Ktor can — but **Ktor's own types must not leave `:core:sync` either** (M5). `:app` is Android-only, so nothing would complain if it started holding an `HttpClient` and later made a request outside the outbox. `NextcloudTransport` is opaque outside the module, so there is nothing in `:app` to make a request *with*.
- **There is no digest in common Kotlin**, and `java.security.MessageDigest` is JVM-only. SHA-256 (`:core:storage`, SPEC §5.6) and MD5 (`:core:sync`, for SPEC §7.5's `OC-Checksum`, which Nextcloud *verifies*) are both hand-written against published test vectors. MD5 is a transport integrity check and must never become a security primitive.
- **A backtick test-function name may not contain a comma — and in an instrumented test, not an apostrophe either.** Kotlin/Native rejects the comma (`Name contains illegal characters: ","`) and **D8 rejects both** (`Method name '…' cannot be represented in dex format`). The JVM compiler accepts them all, so neither surfaces until `compileTestKotlinIosArm64` or `dexBuilderDebugAndroidTest` runs. Keep instrumented test names to `[A-Za-z0-9 _-]`. Found in M5, twelve names deep.
- ⚠️ **`androidx.test:runner` is NOT transitive from `androidx.test.ext:junit`** — and `testInstrumentationRunner` names a class from it. Without the dependency every instrumented test in the module dies with `ClassNotFoundException: androidx.test.runner.AndroidJUnitRunner` before a single test runs. `:core:database` and `:android:ink` were both missing it and nobody knew, because **`check` compiles instrumented tests but does not dex or run them** — the guard M3 added catches a broken *signature*, not a broken *classpath*. Run `connectedDebugAndroidTest` against something at least once per milestone that adds instrumented tests.
- ⚠️ **Gradle's `N actionable tasks` line is not a test count.** `./gradlew build` currently ends with `1068 actionable tasks: N executed, M up-to-date`, and that 1,068 was read as a test count in M5 — then quoted as *"1068 JVM tests"* in M5's notes, in this file, in `ROADMAP.md` and twice in M6's, as evidence for how much JVM testing misses. The suite was **286** per variant when M5 shipped and is **539** now. The real number is `tests=` summed over `*/build/test-results/testDebugUnitTest/*.xml`, and it is **per variant**: `build` runs the same tests again as `testReleaseUnitTest`, so "tests executed" is twice "tests written". Always say which task a figure belongs to.
- **`:android:ink`'s instrumented tests fail on an x86_64 emulator and pass on the arm64 tablet.** Not a logic bug: M1's synthetic-ink fixture computes orientations without clamping to `[0, 2π)`, and x86_64 rounding puts them a thousandth outside, which `androidx.ink` rejects (`StrokeInput::orientation … Got: -0.00147393π`). One assertion also has an arm64-tuned float tolerance. Found in M5; **not fixed, because it is M1's code**. Anything measuring ink still needs the real tablet regardless (CLAUDE.md definition of done #4). Confirmed again in M6: **15 tests, 10 failures**, all of them these two causes. Any new ink test should build its geometry by *drawing* — capture a stroke and read it back with `drainOps()` — rather than through `SyntheticInk`, or it inherits the bug.
- ⚠️ **A synthetic `MotionEvent` needs `SystemClock.uptimeMillis()`, and every event of one gesture needs the *same* `downTime`.** Ink measures a stroke's elapsed time from `downTime`; a wall-clock time or a fresh `downTime` on the UP gives elapsed times `StrokeInput` rejects, and the stroke then **never finishes** — no exception, no log, `strokeCount` simply stays where it was. Cost half an hour in M6 before the test was made to assert that the surface consumed the ACTION_DOWN, which separates "the event never arrived" from "the stroke never landed". `MotionEvent.obtain`'s short overloads also cannot set a tool type, and a tool type is the only thing `PalmGuard` looks at.
- ⚠️ **The app could not start in a secondary process, and nothing in production would ever have found it** (M6). `Application.onCreate` runs in *every* process; WorkManager's initializer is a `ContentProvider`, which Android creates only in the **default** one. So field-injecting `WorkManagerScheduler` into `InkstoneApplication` — eager, by definition — built `WorkManager` in a process where it was not initialized and died before that process ran a line of its own code. Found by SPEC §13.3's crash harness, which needs a second process precisely so that killing it is survivable. Fixed with a `Provider` and a default-process guard; also a small cold-start win, since the graph is no longer partly built before the first screen.
- **A weak ETag has to be stored as a flag beside the value, not recomputed.** SPEC §7.3: returning `W/"…"` in `If-Match` is a permanent 412 loop, and "was this weak" cannot be recovered from the stripped string. `ETag(value, weak)` carries it and `asIfMatch` returns **null** for a weak one, so the trap is unrepresentable rather than remembered.
- **"Was this ever uploaded" cannot be read from `oc:id` and the ETag alone** (M5). A reverse proxy that strips `OC-ETag`/`OC-FileId` leaves a *successful* upload looking un-uploaded, and the next PUT sends `If-None-Match: *` against a file that exists — a permanent 412. Keep a local upload timestamp too.
- ⚠️ **A note the reconciler *downloaded* must not be bound with the "Save to Nextcloud…" path** (M6). That path always enqueues MKCOL + UPLOAD, and the fresh binding has no ETag and no `oc:id`, so `everUploaded` is false and the next PUT sends `If-None-Match: *` **at the file it was created from** — a permanent 412, a day-long park, and the note flagged `CONFLICTED` seconds after arriving byte-identical to the server's copy. Worse, a park is inherited by coalescing, so the next real edit never uploads either. `bindPulled` binds at the version downloaded and enqueues nothing.
- ⚠️ **A 412's park has to be lifted explicitly by the merge that resolves it** (M6). Coalescing *inherits* `next_attempt_at` — correctly, because resetting it per save is a hot retry loop — so the `noteChanged` after a merge inherits the day-long park and the union never uploads. A backoff is about the transport; a park is about a conflict. And SPEC §7.6's 412 → reconcile hop needs a *caller*: the drainer deliberately has no scheduler, so it reports the 412'd note ids and the worker asks.
- ⚠️ **`INSERT OR REPLACE` resets any column the statement omits to its DEFAULT.** It deletes the conflicting row and inserts a new one. `remoteBinding.lastSeenRemoteAt` was reset to 0 — *"never seen"* — by every ordinary binding write, which would have made the next reconcile pass read **the whole library as deleted on the server** and trash it. Carry such a value forward through a subquery inside the upsert, and let exactly one statement change it.
- **A pruned subtree looks exactly like a deleted one.** "Known `oc:id` absent remotely" is only decidable for a directory the pass actually *walked*, so intersect with the walked set and require `everUploaded` (M6). Cache a directory's ETag only once everything beneath it finished, or an unmerged download prunes that subtree on every later pass and the note silently stops syncing while the poll reports success.
- **A remote-side delete is not `noteTrashed`.** That enqueues a DELETE, and a DELETE **annihilates the pending UPLOAD** — which destroys the only queued route a note's unsynced edits had to the server, on a path whose comments all read correct (M6, SPEC §13.2 row 14). There is also nothing to delete; the file is already in the server's trash.
- ⚠️ **There is no FTS5 on Android, and SPEC §8.4 said to use it** (M7). The system SQLite is built
  with `SQLITE_ENABLE_FTS3` and `FTS4` and not `FTS5` — API 35, SQLite 3.44.3, four years after
  FTS5 landed upstream. (Room ships `@Fts3`/`@Fts4` and no `@Fts5` for the same reason.) **iOS's
  SQLite does have it**, so a `:core:*` module using it compiles, links, passes on the simulator
  and fails on every Android device — Guardrail 2 pointing the wrong way. `FtsAvailabilityTest`
  asserts it at runtime rather than leaving it remembered. And it would not have been enough
  anyway: SPEC §8.4 wants a page-space box per hit, no FTS module stores geometry, so the per-word
  table has to exist regardless — and then answers prefix queries by itself.
- ⚠️ **The ML Kit digital-ink package is `com.google.mlkit.vision.digitalink.recognition`, not
  `com.google.mlkit.vision.digitalink`** (M7). Google's own guide and every tutorial written from
  it import the shorter one, which does not exist in 19.0.0; the result types live in a third
  package shipped in a separate artifact. `WritingArea` is **width and height only — no origin**,
  so ink must be translated so the line's top-left is at zero; passing page coordinates compiles
  and returns plausible-looking text. `fromLanguageTag` returns **null** for an unsupported tag
  despite being declared `throws`. All read off the AAR with `javap`.
- **A recogniser wants strokes in WRITING order, not left-to-right** (M7). SPEC §8.2 said
  left-to-right and has been corrected: a `t` is crossed and an `i` dotted after the word is
  finished, so sorting by x is precisely SPEC §8.1's *"out-of-order strokes degrade results"*,
  applied on purpose. Measured on 20 pages: word recall 0.378 against 0.313.
- **Clustering strokes into lines needs vertical overlap AND baseline proximity** (M7). Overlap
  must be normalised by the *shorter* box or an `o` beside an `l` starts its own line — and that
  same normalisation scores 1.0 for an `o` sitting inside an `f`'s descender from the line above,
  so one pair chains two lines and union-find chains the third. And the gap that separates a word
  space from a column gutter is a fraction of the **line** height, not the stroke height; the two
  differ by about three times, which is the difference between "words in a line" and "letters in
  a word". Both found by measuring, not by reading: 304 lines and 632 lines respectively, where
  there were 472.
- ⚠️ **A synthetic fixture is the worst possible witness for a geometric heuristic** (M7). The
  rotated-text detector passed its unit test and flagged **105** false positives on 20 real pages,
  because a page of ordinary prose leaves a scattering of strokes that happen not to overlap their
  neighbours and any four of them chain into a "run" once transposed. The fixture had nothing else
  on the page. Same lesson as *"distrust a fixture more generous than production"*, in the one
  place a fixture is least like real ink.
- ⚠️ **Feeding a recogniser upside-down handwriting is not an error, it is a bad score** (M7).
  Two fifths of the IAMonDo corpus declares an identity `canvasTransform` and the rest a 180°
  affine; assuming one of them flipped 40% of the sample and cost the segmented arm more than half
  its accuracy, reported as nothing at all. Found by rendering a page to a PNG and *looking at it*.
  When a measurement is worse than it should be and nothing is failing, draw the input.
- **`EncryptedSharedPreferences` was deprecated in 2025.** Keystore AES-GCM directly.
- Nextcloud's `loginName` is **not** the WebDAV path segment. Fetch the UID from `/ocs/v2.php/cloud/user`.
- Nextcloud forbids only `\` and `/` by default — **not** the Windows character set. Read the real rules from capabilities.
- A file's ETag does **not** change when it moves. Same `oc:id` + same ETag + new path = a move.
- ⚠️ **…but "new path" means a path the walk actually *listed*, and the walk is deliberately narrow** (M7). Roots are the account's default folder, the parent of every binding and the pinned folders — because `Depth:1` from `/` crawls the user's photo library — so a note moved in the **web UI** into a folder outside that set appears in no listing, its old directory is walked and *settled* without it, and SPEC §7.6 step 3's fourth branch concludes a **remote deletion**: the note went to the local trash while its file sat on the server one folder away. SPEC §13.2 row 11 passed for two milestones because its fixture moves the file within a walked folder. The answer is not a wider walk but a different question: **`SEARCH` on `/remote.php/dav/` resolves an `oc:fileid` to its current href** (SPEC §7.4 has the verified body, the two endpoints that do not work, and why the URL is the DAV root and not `files/<uid>/`). Three rules travel with it — one search per *absent candidate* and never per file; a non-207 means *"cannot tell"* and must leave the pass's own conclusion untouched, because a server with no search backend must not be able to delete notes; and a hit is only followed once its `oc:id` confirms it, or a mis-derived fileid repaths a note onto a stranger's file and the next PUT lands on it.
- `notify_push` is **not** bundled and **not** commonly available. Poll by default; push is an optimisation only.
- **`nc:data-fingerprint` is not retrievable over WebDAV.** On Nextcloud 32.0.13 it is a **404 propstat** on `/remote.php/dav/files/<uid>/`, on `/remote.php/webdav/` and on `/remote.php/dav/`, and it is absent from both capabilities and `status.php` — **even immediately after `occ maintenance:data-fingerprint` reports having set it.** So SPEC §7.6 step 4's backup-restore safety net has no trigger in production. Keep requesting the property (harmless, and a later version may answer) but do not build on the answer; the periodic unpruned walk (`syncCursor.lastFullAtEpochMillis`) is what actually covers a restore. And **absent must never be read as changed** — the comparison needs both sides non-null, or every poll against every real Nextcloud becomes an unpruned walk. Measured in M6 against a live server, which is the only way this was ever going to surface.
- Nextcloud **full-text search is not in a default install** — it needs the `fulltextsearch` app plus a search backend. `pdfgrep` is the reliable test.
- **`androidx.pdf` does not write `/Ink` annotations, and cannot.** (M4.0 spike, SPEC §6.1.) `androidx.pdf.ink` converts every stroke into a **`/Stamp`** holding a filled path, because the framework type it targets — `android.graphics.pdf.component.PdfAnnotationType` — has only `UNKNOWN`, `FREETEXT`, `HIGHLIGHT`, `STAMP`. There is no ink annotation in the platform API. It also **cannot set `/NM`** (annotation identity there is the integer index on the page — the thing rule "identity is never array position" exists to prevent), and its save is a full PDFium re-serialisation via `PdfRenderer.write`, which makes SPEC §5.2's incremental update impossible. **`androidx.pdf` is not a dependency of this project.** Don't reintroduce it for rendering either: it would force compileSdk 37 or an ext-19 platform package for one bitmap call, and framework `PdfRenderer` does the job from plain `android-36`.
- **PdfBox-Android 2.0.27.0 has no `PDAnnotationInk` and does not bundle `xmpbox`** (M4). SPEC §6.3's table said otherwise and has been corrected. `/Ink` is written at the COS level and the XMP marker as a raw `/Metadata` stream; both work, neither has a typed helper.
- **PDFBox's `saveIncremental` writes only objects marked `setNeedToBeUpdated(true)` — including ones you just created — and the mark must be on the whole parent chain.** A marked page hanging off an unmarked `/Pages` node is never reached. The output is a valid PDF, opens everywhere, has the right page count, and contains no ink. Found in M4 by a test that parsed the output; see `PdfBoxWriter.markParentChain`.
- ⚠️ **A *page* content stream has no `/Resources` — it resolves names through the page dictionary.** Only a form XObject (an annotation's `/AP /N`) carries its own. M4 put them on the stream in both cases, so the flattened overlay's `/GS… gs` and the invisible text layer's font were **undefined names in every file this app published** (found in M6). PDF.js continues with the default graphics state, so a translucent highlighter came out fully opaque over the writing; a stricter reader abandons the rest of the stream, so the overlay's ink is not drawn at all. And `/Resources` is **inheritable**, so writing a fresh one on the page *shadows* one a source PDF kept on a `/Pages` node — copy the inherited dictionary down, and merge into a copy, because source resource dictionaries are shared between pages.
- ⚠️ **Flattening the page tree drops whatever the pages were inheriting.** `/Resources`, `/MediaBox`, `/CropBox` and `/Rotate` are the four inheritable page attributes, and reordering pages cuts the intermediate `/Pages` node out of the `/Parent` chain — leaving a page with **no size at all**, so a viewer refuses it or falls back to US Letter while the ink sits on paper of a different size (M6). Copy them down *before* rewriting `/Parent`, because what a page inherits is read through the chain you are about to shorten.
- **Verifying a PDF by re-parsing it with the library that wrote it proves almost nothing.** Both `/Resources` bugs above were green under PdfBox, `PdfReader` found the ink, `detectOpenMode` said `Normal` and the page count was right. The file was valid, parseable, and blank. `PdfViewerCompatTest` asks the other question — what does a renderer that is *not* PdfBox require — and that is what found them.
- **`PdfRendererPreV` needs S extension level ≥ 13, not just API 30.** It ships in the mainline PDF module (M4). Check at runtime.
- PDF `/Ink` **cannot** express variable stroke width. Pressure goes in an `/AP` appearance stream.
- **An erased stroke cannot be subtracted with even-odd or reversed-winding fills.** Both re-fill wherever two eraser capsules overlap, which is what every scribbled erase produces. The subtraction happens in `:android:ink` via Skia's `Path.op(DIFFERENCE)` and what crosses into `:android:pdf` is what is left (M4, SPEC §3.5).
- PDF page size comes from **CropBox with `/Rotate` applied**, not MediaBox. Viewers display CropBox.
- ML Kit's recogniser assumes **one line of text**. Segment first or the output is garbage.
- ⚠️ **`MediaRecorder` writing MPEG-4 finalises its index on `stop()`** — kill the process at minute
  50 and the file is large, complete-looking and unplayable by anything. Ogg is a stream of
  self-describing pages, so a truncated file is simply a shorter recording. M12 measured it:
  `am force-stop` mid-recording left **1,154 complete Ogg pages, zero CRC failures, 23.03 seconds
  recoverable and a zero-byte torn tail**, verified by `tools/verify-ogg-opus.py` — which parses the
  container by hand precisely because asking `MediaRecorder` whether its own output is readable is
  the M4 mistake again.
- ML Kit GenAI and Gemini Nano do **not** do handwriting recognition. There is no successor to Digital Ink Recognition.
- A PDF with ink annotations and no embedded bundle is **not necessarily ours** — it might be a stranger's Acrobat markup. That's why the XMP marker exists (SPEC §5.6).
- ⚠️ **`WebDavClient.put` hard-coded `Content-Type: application/pdf` for two milestones** (M12).
  Nothing noticed, because everything this app uploaded really was a PDF. A recording's `.opus`
  announced as a PDF stores perfectly, reads back byte-identical, and passes every assertion about
  its bytes — the only component that can tell is one that **selects by declared type**, which is
  exactly what M12's server half is. Same shape as M4's PDFs, which were valid, parseable and blank
  in every viewer that was not the library that wrote them. The rule generalises: **when a new kind
  of file starts going through an old path, check what the old path says it is** — and assert it off
  the wire, not off the code.
- **`MediaRecorder` treats `setAudioEncodingBitRate` as a hint, and Opus is VBR** (M12). Asked for
  24 kbit/s it produced ~34, so a lecture hour is ~15 MB and not the ~11 MB `docs/M12-AUDIO.md` §4
  budgeted. The consequence that mattered was not the storage: a recovered recording's duration was
  being *estimated* from bytes ÷ bitrate and came out **42 % high**. A container states its own
  length — `OggOpus.durationMillis` reads the last Ogg page's granule through
  `FileSystem.readRange` — and a number shown to a user should never be a guess when the file
  knows.
- ⚠️ **SQLDelight's migration verifier does not model an index being dropped with its table** (M12).
  A `.sqm` may `DROP TABLE` and `ALTER TABLE … RENAME TO` — both parse, which was the open question
  before `7.sqm`, since every previous migration was `CREATE TABLE` / `CREATE INDEX` / `ADD COLUMN`
  only. But the `CREATE INDEX` recreating the index afterwards fails the build with *"Duplicate
  index name"*, naming an index whose table is two statements dead. An explicit `DROP INDEX` first
  is a no-op on a real database and is what makes the file compile. Also worth knowing: the verifier
  diffs **DDL**, so an `INSERT … SELECT` that copied nothing would pass it silently —
  `MigrationOnDeviceTest` asserting a fixture row survives *with its `attempts`* is what covers that.
- ⚠️ **`ViewModel.onCleared` cannot suspend** (M12), which is why M12's `RecordingController` is a
  process-wide singleton with its own scope. `ViewModel.clear()` cancels `viewModelScope` **before**
  invoking `onCleared`, so anything needing I/O there has no scope to run in. `NoteSessionHost`
  documents the same trap for `unregister`; it bites harder here because *"the user left the note"*
  is `onCleared` and nothing else — `onStop` fires for a screen timeout, and hanging a recorder off
  it costs the rest of the lecture the first time the screen dims.
- **Android lint's `ForegroundServiceType` check does not find the manifest entry for an
  `@AndroidEntryPoint` service** (M12), though the merged manifest carries it. Suppressed, and then
  **checked on the device rather than argued about**: `dumpsys activity services` reporting
  `isForeground=true types=0x00000080` is what proves `startForeground(..., TYPE_MICROPHONE)` was
  accepted. Suppressing a lint error that is really right is how a crash ships; the device is the
  authority.
- **`MediaRecorder(Context)` is API 31 and this project's `minSdk` is 30.** The context-taking
  constructor exists for privacy attribution; on 30 the no-argument one is the only one there is.
- ⚠️ **A second client for a protocol the project already speaks is where the old bugs come back —
  diff it against the first one** (M12). `tools/m12/nextcloud.py` is a stdlib WebDAV client for the
  transcript watcher, and it shipped three bugs that `:core:sync` had already got right years
  earlier: it never percent-encoded the DAV path (so a folder named `Chemistry Y12` raised
  `InvalidURL` before a socket was opened — `NextcloudUrls.encodePath` does it per segment); it
  matched the `Depth: 1` **self-entry by name**, which at the account root is the *uid* and so
  matches no child, walking the root as its own child to the depth bound (`RemoteWalk` compares
  `it.path == here`); and it split on `/<MEDIA_DIR>/` for the parent folder, which matches nothing
  for a note in the root. All three were found by a **real HTTP server** in the test
  (`tools/m12/test_watch.py`), and none of them by reading. The rule: a mock of a client agrees
  with every mistake that client makes, so test a client against a server — and before writing the
  second implementation, read the first.
- ⚠️ **A `Depth: 1` listing includes the collection itself, and the self-entry must be *flagged*,
  not dropped** (M12). Dropping it in the listing helper is the tidier API and silently breaks the
  existence probe, which asks with `Depth: 0` and receives precisely that one entry — so "filter
  out self" and "does this exist" become the same operation with opposite needs.
- ⚠️ **`readBytes` throws; it does not return empty — so an `isEmpty()` "missing file" guard is
  unreachable in production and the throw eats the whole pass** (M12, found on the first real
  device). `performMediaUpload` read a recording with `fs.readBytes` and tested `audio.isEmpty()`,
  which reads exactly like a missing-file check. `AndroidFileSystem.readBytes` is
  `File.readBytes()` → `FileNotFoundException`; only `FakeFileSystem` ever answered empty, so
  `MEDIA_MISSING` could not fire and the throw escaped `performMediaUpload`, `drain` **and**
  `OutboxDrainWorker` — from 14 lines *above* `mkcolRecursive`, so not even the media directory
  appeared on the server. No park, no note state, no `DrainReport`, nothing on screen, and every
  later "Sync now" repeating it exactly. Two rules: **write it as
  `if (exists) readBytes else ByteArray(0)`** (the same line the timeline beside it already used),
  and **`drain` must try/catch per row** — an unmodelled throw took every row queued behind it,
  including the note's own DELETEs.
- ⚠️ **A park had no lifter for any op type but `UPLOAD`, and "Sync now" could not lift it** (M12).
  `unparkUpload` filtered `it.opType == OutboxOpType.UPLOAD`, so a `MEDIA_UPLOAD` parked for 24 h
  was structurally unreachable — `due()` filters on `next_attempt_at`, coalescing *inherits* it, and
  the only caller of the lifter is a completed reconcile, which a lecture with no note edit behind
  it never triggers. Every press opened no socket at all. `SyncEngine.syncNowRequested` lifts parks
  across the outbox, and the reasoning that keeps it safe is narrow: **coalescing's inheritance is
  unchanged** (resetting a park per save is a hot retry loop against the user's own server), and a
  person pressing a button is the one signal in the system that is *not* a loop. If that method ever
  gains a caller which is not a user gesture, the argument is void.
- ⚠️ **"Sync now" must reconcile what is owed, not just drain what is queued** (M12). It was
  `scheduler.enqueueOutboxDrain()` plus a hard-coded `"Syncing…"` that nothing ever cleared — so it
  looked identical after a successful sync and after doing nothing, and it could repair neither of
  the two ways a recording gets stuck. The Sync screen counts the **recording** table
  (`state='READY' AND uploadedAt=0`), which knows nothing about the outbox, so a lecture whose row
  was never written showed in the header forever while the only repair was `bind` / `noteRestored` /
  `resumeAccount` / `noteChanged` — i.e. *draw a stroke in that note and save*, and finishing a
  lecture is the archetypal moment somebody puts the tablet down. Any button whose two states are
  indistinguishable is a button that cannot be debugged from the outside.
- ⚠️ **Release the foreground service *after* the work that must survive, not before** (M12).
  `RecordingController.stopLocked` called `foreground.stop()` before `recordings.finish` and
  `sync.recordingCaptured`, dropping the process's importance in exactly the window where the
  lecture is on disk but not yet queued. A kill there yields READY / `uploadedAt = 0` / **no outbox
  row** — a recording the app can see and no button in the app could send. The service is worth a
  hundred milliseconds; the lecture is not repeatable.
