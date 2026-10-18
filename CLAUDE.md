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
- An imported PDF's **original bytes are preserved forever** — as the verbatim prefix of `document.pdf`, guarded by `originalLength` truncate-rebase and `originalSha256` verification (docs/M4-PDF.md §7.3). There is no `assets/source.pdf` and never was (`Bundle.kt` says so); this bullet named a mechanism that was designed out in M4, and the invariant is what matters.
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
| `:android:ink`'s instrumented suite | ✅ (110/110 since `SyntheticInk.turn`) | ✅ |
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
⚠️ **A serial is a boot-order port, not an AVD.** Resolve it with `adb -s <serial> emu avd name`
before every command. On 2026-10-13 `emulator-5554` had quietly become another session's
Test_Tablet_B, and a command aimed at Test_Tablet took B out of airplane mode. The README's
*Testing on an emulator* has that, and the second-AVD Wi-Fi fix.

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

   ⚠️ **`--rerun` is a *per-task* option and silently ignores the tasks before it** (M8a).
   `gradlew :a:testDebugUnitTest :b:testDebugUnitTest --rerun` reruns **neither** — both log
   `UP-TO-DATE`, which is the green-build-that-ran-nothing this section is about, met while trying
   to avoid it. It has to follow each task: `:a:testDebugUnitTest --rerun :b:testDebugUnitTest
   --rerun`. (`--rerun-tasks` is global but reruns the whole graph, which on a OneDrive-synced
   checkout hits `classes.jar` file locks.) Always read the `> Task …` lines back.

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
| **Shapes + where a connector's attachment lives** | SPEC §4.8 |
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
| **M8a selection/transform decisions + the per-feature check table** | `docs/M8-SELECTION.md` |
| **M8b connector format decided + what is still not built** | `docs/M8-CONNECTORS.md` (§6 is the remainder) |
| **M8 follow-up — snap rotation-invariance, image drag, the crop tool** | `docs/M8-UX-FIXES.md` |
| **M8 follow-up 2 — two-finger undo, selection staleness, perf (and the deferred dry-layer cache)** | `docs/M8-PERF-UNDO.md` |
| **M8 follow-up 3 — shape point handles, the 3-point curve, arc snapping, and the three decisions the first session reversed** | `docs/M8-SHAPE-POINTS.md` (SPEC §4.8.9) |
| **M8 follow-up 4 — a text box is resized by its frame, and `EDIT TEXT` is the way back in** | `docs/M8-TEXT-FRAME.md` (SPEC §4.9.2) |
| **M8 follow-up 5 — the ruler threw on every ruled stroke, and covered the line it ruled** | `docs/M8-RULER.md` |
| **M8 follow-up 6 — undo: the press burst, the erased stroke that never came back, the frozen open, and the recogniser undo waited for** | `docs/M8-UNDO-LAG.md` (§4 open path, §5 recognition) |
| **M8 follow-up 7 — a small shape could not be drawn: the dwell radius was the sampling spacing, and the travel gate counted in lumps** | `docs/M8-SMALL-SHAPES.md` (§4 is the size floor, SPEC §4.8.7) |
| **M8 follow-up 8 — a saved Element never reached the open panel, so there was nothing to arm and nothing to stamp** | `docs/M8-ELEMENTS.md` (§6 is the emulator's own traps) |
| **M8 follow-up 9 — snap to grid, and the selection bar's Circle / Arrow / Grid buttons** | `docs/M8-GRID-SNAP.md` (§6 is the per-claim table, §8 the buttons) |
| **Elements follow-up — handwriting in Elements, drag-and-drop from the panel, and knockout (an object that hides what is under it)** | `docs/M8-ELEMENTS-KNOCKOUT.md` (SPEC §4.9.4, §4.9.6) |
| **The Element editor — knockout areas, a pivot that snaps to the grid, and updating placed copies** | `docs/M8-ELEMENT-EDITOR.md` (SPEC §4.9.7; §8 is why the bounds and areas are objects, §10 why a copy is one thing and the grid leads) |
| **M8 follow-up 10 — an axis-aligned line could not be moved, and a scribbled-over stroke was never pruned** | `docs/M8-LINE-MOVE-ERASE-PRUNE.md` (§3 is what was measured and what was not) |
| **M12 lecture recording — capture built, alignment still designed** | `docs/M12-AUDIO.md` (§15 is what exists) |
| **M10 redesign — the design system, the Dynamic Dock, and why there is no backdrop blur** | `docs/M10-DESIGN.md` |
| **M10 follow-up — the editor held in portrait: the scroll nobody could see, and three layout fixes that were reverted** | `docs/M10-PORTRAIT.md` |
| **Writing on a PDF that already lives on Nextcloud — the third binding, and why the first upload is owed at once** |  (§6 is the sharp edge) |
| **Writing on a PDF that already lives on Nextcloud — the third binding, and why the first upload is owed at once** | `docs/REMOTE-PDF.md` (§6 is the sharp edge) |
| **The paper as a value, ink that stops at its edge, and the infinite canvas** | `docs/INFINITE-CANVAS.md` (SPEC §4.5) |
| **Choosing an upload path with no network — the browser's cache, and the table it must not be** | `docs/OFFLINE-FOLDERS.md` (§2 the two caches, §5 the per-claim table, §6 unverified) |
| **Slow, stuttering, and a crash on two big one-page notes — torn reads, Back losing ink, UI-thread folds, and the debug build** | `docs/PERF-CRASH-2026-09.md` (§5 per-claim table, §6 found and not fixed) |
| **Photos drawn as crossed boxes, 852-character z keys, 39,000 dots — the physics note at 3× the size** | `docs/IMAGES-PERF-2026-10.md` (§4 per-claim table, §6 found and not fixed) |
| **The note stays loaded — `onStop` saves, only a finishing stop materializes, `largeHeap`, and why leaving for the Element editor destroyed the note** | `docs/NOTE-STAYS-LOADED.md` (§3 before/after, §4 per-claim table, §5 found and not fixed) |
| **The reader — a website and an MCP server over your Nextcloud** | `docs/WEB-READER.md` (§2 Login Flow v2 in Python, §6 per-claim buckets, §7 unproven, §9 hosting it with Docker) |
| **The built-in calculator — an fx-991ZA PLUS II rebuilt from scratch in `:core:calc`, its window and face in `:app` `calculator/`; drag its answer off the LCD onto the page** | `docs/CALCULATOR.md` (§2 decisions D1–D7, §3 the firmware guesses, §6 the answer drag; research in `docs/calculator/research/`) |
| **Saved toolbar preferences that stay, and the layout editor — move, resize and reshape (row / column / grid) any bar, hide chips, carry a chip into another bar or onto the page, size each panel** | `docs/CUSTOM-LAYOUT.md` (§1 the stale-seed bug, §3 what the emulator found, §4 per-claim table) |
| **The editor's chrome rearranged — tools in the top-left corner with the pen's row along the top, one right-hand column every panel stacks in without overlapping (`PanelDock`), a dockable/resizable calculator, two-line contextual cards, and the `ui.version` migration; round one (side rails) and why it was rejected** | `docs/UI-RAILS.md` (§1 round one, §4 the panel column, §6 per-claim table) |
| **Saved layouts (Default + the user's, switched in the editor), every panel draggable/resizable/dockable (`PanelWindow`), the column's side and order, brush-menu and one-row style switches, and snapping (`BarSnap`)** | `docs/LAYOUTS.md` (§1 how the Default stays untouched, §3 the two emulator finds, §6 per-claim table) |
| **Handwriting synthesis (M13) — typed text dropped onto a page in your own hand: the design D1–D15, the pen ledger, the profile on disk, and why the model's training data must allow commercial use** | `docs/HANDWRITING-SYNTHESIS.md` (§12 phases, §14 the user's answers, §16 phase 2 and §18 phase 3 per-claim status; `hw` is SPEC §4.10) |
| **Line segmentation version 2 — columns and mind maps read, marks that attach instead of linking, and the search hit that opened its note somewhere else** | `docs/LINE-SEGMENTATION-V2.md` (§3 what was measured, §5 per-claim table, §7 unverified; SPEC §8.2) |
| **Handwritten maths — the Write panel's *Maths* switch: stacked fractions, roots, scripts and stretched brackets, laid out from runs the gate read** | `docs/HANDWRITING-MATHS.md` (§2 the notation, §3 the layout, §5 what the sheet found, §6 per-claim table) |
| **Scrolling through the pages like a PDF — a setting in the paper menu; neighbours are pictures, one page stays live, and a scroll writes no op** | `docs/PAGE-SCROLL.md` (§1 why pictures, §3 decisions, §5 per-claim table, §6 what is unverified; SPEC §4.5) |

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
- ⛔ **`SyntheticInk` generated an out-of-range orientation for one stroke in sixteen, and this file blamed the CPU for four milestones.** The entry here used to read *"`:android:ink`'s instrumented tests fail on an x86_64 emulator and pass on the arm64 tablet … x86_64 rounding puts them a thousandth outside … not fixed, because it is M1's code"*. Every clause of that was wrong, and the tablet says so: **13 of 108 failed on a Galaxy Tab S9 FE+**, with the identical `Got: -0.00147393π` the emulator reports. The cause is arithmetic, not hardware — `generate` draws `direction` uniformly from `[0, TAU)` and bends it by `curl ∈ [-0.8, 0.8]`, and `angle` is monotonic in `t`, so a stroke escapes exactly when `direction + curl` leaves the range: `E|curl| / TAU` = **6.4%**, on every CPU, decided by the *seed*. It is fixed now (`SyntheticInk.turn`), and the suite is **110 / 110 green on the tablet**.

  ⚠️ **The tell was in the numbers, and nobody checked the magnitude against the hypothesis.** "Rounding" was offered for a discrepancy of ~4e-3 rad when float32 epsilon near 6.28 is ~5e-7 — four orders of magnitude out. A plausible-sounding cause that is quantitatively impossible is still impossible: when you name a mechanism, compute what it would produce and compare.

  ⚠️ **And "it is M1's code" is not a reason, it is the bug's hiding place.** Two production callers had never worked: `InkScreen.loadSyntheticPage` (default seed, first escape at stroke 6 of 5,000) and the library's `MEASURE → Fill library` button (120 strokes/note ⇒ 99.96% per note), neither of which catches the throw. ROADMAP M3's frame-rate gate could not be reached through the button that exists to reach it. Definition-of-done #6 again.

  ⚠️ **A fixture that throws hides whatever its tests assert.** All 12 `StrokeStoreEraseTest` bodies had never executed, and one of them — `highlightersRenderBeforePens` — asserted the **pre-M4** rule that the highlighter draws *behind* ink. SPEC §3.4 was corrected from the tablet in three attempts after *"you can't highlight your own writing"*; the stale test would have argued for reverting it. When a suite starts failing at construction, treat every assertion behind it as unverified, not as passing.
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
- ⚠️ **A milestone's own prompt can be superseded by the section it tells you to read** (M8a).
  ROADMAP M8a asks for partial erase as `DeleteObject + AddObject(fragment) + AddObject(fragment)`;
  SPEC §3.5, revised twice in M1 **on the tablet**, says *"M8a's 'partial erase splits a stroke into
  fragments' is superseded. Nothing splits."* — and the prompt opens with *"Read SPEC.md §3.5"*.
  Implementing the prompt fails SPEC §13.2 row 7, whose `assertEquals(1, folded.objects.size)` is
  the automated guard against exactly it. The habit that catches this is reading the referenced
  section **before** the instruction that references it, not after.
- ⚠️ **When a coordinate space is only ever the identity, two different spaces have the same
  numbers — and the code says the wrong one for free** (M8a). Erase capsules were "in page space"
  for four milestones and were really in the *object's* space; nothing could tell, because nothing
  could move. The moment M8a's selection tool could, both readings failed silently: a page-space
  region leaves the hole behind when the stroke is dragged away, and a page-space coverage test
  passes straight through the moved stroke. **Before adding the thing that makes two spaces differ,
  grep for every place that names either one.** Nothing migrated here — every existing capsule
  belongs to an object at the identity, where the two agree — but only by luck of ordering.
- ⚠️ **An undo entry with no inverse used to go on the stack, and pressing undo consumed it doing
  nothing** (M8a). `UndoStack.commit` skipped entries empty in *both* directions; `Undo.inverseOf`
  returns null for a `PageOp.Unknown`, for a delete of an object that has not arrived, and — the
  one that made it a real bug — for the **first `SetStyle` on any stroke**, because the style
  register starts unset and SPEC §4.2's `SetStyle` cannot carry "unset". So *"change the colour,
  press undo"* did nothing while eating the press. `commit` tests `isUndoable` now, and
  `:android:ink` writes the capture-time style into the register first (it is the only module that
  can read it off the brush) so the recolour has something to invert to. **Neither fix touches the
  op format** — making `style` nullable would break every older build, since `OpCodec` reads it
  with `getValue`.
- ⚠️ **A private counter that hands out fractional keys goes stale the moment another op can write
  one** (M8a). `PageOpRecorder` advanced its own `lastZ` per stroke, which was right until
  `SetZOrder` existed: bring a stroke to the front and the recorder's counter is now *below* the
  top of the page, so **the next stroke the user draws lands underneath it** — on screen, in the
  PDF's `/Annots` order, and on every peer, with nothing connecting the two actions. The maximum
  belongs to whatever sees *every* writer, which is `StrokeStore`.
- ⚠️ **"Hold still" cannot be detected from input samples alone, and the version that can't is the
  one that works on your hand** (M8b). Android delivers `ACTION_MOVE` when the digitiser reports a
  change, so a stylus held genuinely still can stop producing events altogether — and holding still
  is exactly what draw-and-hold asks the user to do. A dwell check that only runs inside `onMove`
  therefore fires for a trembling hand and never for a steady one: intermittent, hardware-dependent,
  and it demos perfectly. `ShapeSnapDetector.sample` returns *milliseconds remaining* rather than a
  boolean so the caller can `postDelayed` the fallback and cancel it on movement. The emulator walk
  exercises the timer path specifically, by holding through a `sleep 1.5` with no events at all —
  which is also the only way `adb shell input motionevent` can express a hold.
- ⚠️ **A register that only one object kind can carry is a register three copy paths will drop**
  (M8b). `SetTextContent` was a text box's field for six milestones: `PageFolder.materialise`
  applied it only to a `TextBoxObject`, `Undo.inverseOf` cast the target to one, and
  `duplicateNote` / `duplicatePage` / `conflictedCopyOf` each relied on the documented invariant
  that *"a fold collapses **every** register onto the payload, so `AddObject` is the only op
  carrying an `ObjectId` a re-identification pass could miss"*. The moment a shape's fill and a
  connector's attachment moved into that register (SPEC §4.8) all four were wrong at once, and
  every one of them failed **silently**: a duplicated diagram came back as unfilled boxes joined by
  straight lines, its connectors naming the *original's* ids and so resolving as detached — defined,
  non-crashing and visibly wrong — while "change a fill, press undo" ate the press and did nothing.
  Copying is one shared `copiedObjectOps` now. The habit that catches this is grepping for
  everything that *copies an object*, not everything that reads the new field.
- ⚠️ **An affine map has three degrees of freedom, so "move this corner and pin the other three" has
  no solution — and solving it anyway ships as *resizing*** (M8 follow-up 3). Shape point handles
  were built on the observation that three point correspondences determine an affine, which is true
  and gave the right answer for a triangle. A rectangle has **four** corners, so the nearest a matrix
  could do was scale about the opposite corner; the user's first sentence about it was *"editing the
  points is just resizeing the shape from those points rather then actually editing the shape"*. The
  KDoc arguing that the restriction was a feature is the expensive kind: it explains why the wrong
  thing is right. **Count the degrees of freedom against the handles the UI offers before choosing
  the representation** — closed shapes now store a vertex list, and only a segment (two ends, which a
  similarity expresses exactly) still edits its placement.
- ⚠️ **A geometric feature can be independent of the property it is meant to measure, and then no
  amount of tuning is the fix** (M8 follow-up). `ShapeSnap` scored a shape by the fraction of its
  **axis-aligned** box it filled. For a square that is `1/(1 + k·sin 2θ)` — a function of how the
  user was holding the tablet, sweeping the whole feature range — so a rectangle at 7° scored
  *ellipse* and one at 45° scored *triangle at confidence 1.000*. A **circle's** axis-aligned box is
  the same at every angle, so the ellipse was the one kind that could be measured at all, and the
  user's report was the exact truth: *"i can only do ovels/circles"*. The fix is the **minimum-area**
  box (`OrientedBounds`), against which all three ratios are rotation-invariant by construction. The
  habit that catches this: before tuning a threshold, ask what the feature is a function of and
  write that function down — `1/(1+k·sin2θ)` has no fix in the constants.
- ⚠️ **Two thresholds beside it were also read as their own constants and are not.** The accepted
  fill half-width is `FILL_WINDOW × (1 − DEFAULT_CONFIDENCE)`, not `FILL_WINDOW`; a line's
  straightness bar was `MIN + confidence × (1 − MIN)` = 0.9685, not the 0.93 its constant names.
  **A confidence gate downstream of a ramp silently narrows every window the ramp feeds.**
- ⚠️ **A dwell's own samples must not join the gesture it is classifying** (M8 follow-up). Half a
  second of tremor at 240 Hz piles ~130 points on one spot; `ShapeSnap` divides by `pathLength`
  twice, so holding still sank a line below its straightness bar *and* pulled an open arc's end-gap
  ratio under the closure threshold — i.e. **holding longer made the answer worse**, which is the
  opposite of what the gesture promises.
- ⚠️ **An eraser and a tap disagree about the inside of a hollow shape, and sharing one predicate
  makes a snapped shape nearly unselectable** (M8 follow-up). `ShapeHit`'s `filled` rule is right for
  the eraser — you can drag one through a hollow box, which is what its emptiness looks like — and
  wrong for a tap, where "tap the shape" means the shape and not its perimeter. A snapped shape has
  no fill, so the interior test was skipped and the user had to land within 8 dp of a hairline.
  `DryObject.tappedAt` is the split; only `DryShape` overrides it.
- ⚠️ **A new object kind silently opted out of erasing, because applying a register on load is a
  convention and not a contract** (M8 follow-up 3). `DryShape`'s constructor never called
  `setErased(obj.erased.toRegion())`; `DryBox`'s init and `dryStrokeFor` both end in exactly that
  line. So the eraser recorded, journaled and folded a correct absolute `SetErasedRegion` — rule 8's
  machinery working perfectly — and the shape reloaded **whole**, in the app and in the published
  PDF, because the one consumer that turns the register into a path runs at construction and a shape
  skipped it. Nothing a test would naturally assert was false. Two habits: when a report names two
  verbs (*"erase or delete"*), measure them separately before believing either — delete was correct
  here and checking it first is what aimed the search; and grep for the **sibling** implementations
  of the invariant, not for the symptom. Same shape as `SetTextContent` being dropped by three copy
  paths, and both were found by reading what the *other* kinds already do.
- ⚠️ **A tool that "owns touch" with a full-screen overlay makes every gesture it does not implement
  *unreachable*, not merely missing** (M8 follow-up). `EditorTool.IMAGE` put a Compose overlay in
  front of `InkSurfaceView` and offered a tap, so while the one tool that knew about images was
  active the surface received **no events at all** — an image was the only object on the page that
  could not be dragged, and the fix was to stop owning touch rather than to implement a drag.
  Corollary: `onSelectionChanged` carried an `Int`, so *"an image is selected"* was unrepresentable
  in `:app` and a contextual bar had nothing to key off.
- ⚠️ **A tap was reclassified as a lasso after 3.0 page-points of *path*** (M8 follow-up), because
  the guard counted **samples** (`points.size < 3`, one appended per 1.5 pt) while the slop beside it
  measured **net displacement** in dp. The tighter, differently-dimensioned test always bound first,
  so the 8 dp slop was decorative and a hand tremor while pressing selected nothing *and* cleared the
  selection. When two guards on one decision measure different quantities, only the tighter one is
  real — check which.
- ⚠️ **`Lasso.selects` takes a *majority* of the points an object offers, so an object that offers
  four is nearly unlassoable** (M8 follow-up). A `DryBox` emitted only its corners, so a lasso drawn
  *inside* a photo enclosed none of them while the identical gesture over handwriting worked — a
  stroke offers every outline vertex. Sample edges, not corners.
- ⚠️ **`SetFormat` alone does not crop a picture, it zooms it** (M8 follow-up). The renderer scales
  whatever the crop selects to fill the object's unchanged box, so a crop written as one op returns a
  tall sliver stretched back out to a square — silently discarding the aspect ratio insert-time
  downscaling preserved. A crop is `SetFormat` **+** an absolute `SetTransform`, as one gesture, and
  the frame must be recomputed from the *whole-source* frame every time (`ImageEdit.uncroppedFrame`)
  or it ratchets inward and can never be widened again.
- ⚠️ **Never key a `pointerInput` on the value the gesture is editing** (M8 follow-up).
  `.pointerInput(id, camera, session.region)` reads naturally and tears the handler down **mid-drag**
  on the first delta it reports, so the thing being dragged moves one frame and stops. It looks
  exactly like a hit-test bug. `rememberUpdatedState` and read the value at the down.
- ⛔ **A shape smaller than about 36 pt could not be drawn at all, and the reason was that one
  constant was doing two jobs** (M8 follow-up 7). `ShapeSnapDetector` kept the dwell's own samples
  out of the gesture by recording a point **only when the pen left the dwell window** — correct
  about the dwell, and it made `DEFAULT_HOLD_RADIUS_PT` the *sampling resolution* as well. So the
  classifier saw a polyline with 6 pt vertices however small the shape was, `ShapeSnap.MIN_POINTS`
  needs five of them, and a **line under ~36 pt was refused outright** (0/12 at 24 pt, 1/12 at
  30 pt over a noisy fixture). A closed shape hid it for a milestone, because what gets sampled
  there is the *perimeter* — an 18 pt circle already had three times the points a 24 pt line did,
  which is why the report was about lines *"and circle or whatever"* rather than about both
  equally. Two jobs, two constants: points every 3 pt, the tail since the last window exit
  discarded at classification. ⚠️ **And finer is not better** — a line is scored by
  `chord / pathLength`, which grows with the digitiser's jitter as the spacing shrinks: at 1.5 pt a
  hand with ±1 pt of jitter scores 0.44 against a bar of 0.93, so halving it again *stops a shaky
  line being a line*. Both directions are the same bug, and it is this file's oldest one.
- ⚠️ **A gate that counts in lumps is a size limit nobody wrote down** (M8 follow-up 7, second
  report). `ShapeSnapDetector.travel` only grows when the pen *leaves* the 6 pt dwell window, so it
  is a multiple of that radius and never counts the final leg: a 15 pt line exits at 6 and at 12,
  finishes 3 pt later, and is measured as **12** against a floor of 16. Everything from about 12 to
  21 pt was refused **by the dwell**, before the classifier was asked — which presents as *"the hold
  is not being detected"* rather than as a size limit, and sent the first session's diagnosis
  to the classifier, where a 12 pt line's straightness is a perfectly healthy 0.93–1.00. The habit
  that catches it: when a feature fails below some size, **find out which gate said no before
  touching any of them**. Counting the outcomes per gate (dwell / classifier / wrong-kind) over a
  size sweep took ten minutes and pointed at the one gate nobody suspected.
- ⚠️ **A running total summed inside the branch that *records* something loses whatever happened
  after the last record** (M8 follow-up 7). `ShapeSnapDetector` accumulated path length as
  `pendingTravel += step` inside the `if (worth a sample)` branch — which reads perfectly naturally
  and undercounts by up to one sampling spacing at every dwell-window exit. It is the one value in
  that file with a threshold on it, and **every fixture passed**, because a fixture chooses its own
  sample positions and lands them on the spacing by construction; a 12 pt line drawn through the app
  over `adb` did not snap. Sum per *sample*, record per *spacing*, and keep the two statements
  apart. Two of that milestone's four defects were found only by the device walk and neither by 12
  seeded fixtures.
- ⚠️ **A confidence bar downstream of a ramp — the fourth instance in the same file** (M8
  follow-up 7), after `FILL_WINDOW`, `LINE_CERTAIN` and `ARC_MAX_RESIDUAL`. `openCandidate` ramped
  `0 → 1` across `[0.93, 0.96]` and `recognise` drops anything under 0.55, so the effective floor
  was **0.9465** rather than the 0.93 the constant names. Ramp down **to the bar**, never to zero,
  so everything a check accepts also clears it by construction. Note how it surfaced: appending the
  pen's resting tip made the last segment of every gesture tremor-length, which is exactly where a
  narrow ramp is least forgiving — **a fix in one place moves a threshold in another**, and that is
  the argument for normalising every ramp the same way instead of tuning them.
- ⚠️ **`MIN_POINTS` is a length in disguise wherever sampling is uniform in arc length** (M8
  follow-up 7). `MIN_POINTS × spacing` *is* a size floor — 30 pt at M8b's spacing — stated in a unit
  nobody would look for it in, so lowering the two constants that *say* "points" left it standing.
  A count belongs to what the arithmetic downstream needs (a hull, enough steps to see a bow); the
  size floor belongs in the constant measured in points. Same family as the erase capsules that
  were *"in page space"* while nothing could move: a quantity was being expressed in the wrong
  space, and it read correctly right up until something else changed.
- ⚠️ **A snapped segment ended at the last sample that was drawing, not where the pen stopped**
  (M8 follow-up 7). A `LINE` is placed by its two endpoints (SPEC §4.8.9), and the classified run
  ends one dwell radius behind the tip by construction — so every snapped line came out up to 6 pt
  short, which at 30 pt is a fifth of it and at 120 pt is invisible. The fix is one sample, appended
  at classification. The general shape: **when a buffer is deliberately truncated for one
  consumer's sake, check what the other consumers read off its ends.**
- ⚠️ **A shape can be drawn entirely underneath its own selection handles** (M8 follow-up 7). A
  snapped shape is selected immediately, and `SelectionOverlayView` draws two 13 dp vertex squares
  and a 12 dp bend ring — about 18 pt of chrome at fit width. A 30 pt line was three-fifths covered
  by them and a 24 pt one had nothing visible in between: a filled square, a ring and a filled
  square, which is a glyph rather than a line. *"A line that does not have the proper points … this
  weird image like thing."* Handles are scaled to the closest pair's spacing now. ⚠️ **The touch
  target was deliberately left alone**: what you can see is a drawing question and what you can hit
  is a finger question, and the fix for the first must not shrink the second. Note also that the
  emulator could not answer this — the ink surface renders garbage on x86_64 — but the *chrome* is
  an ordinary View, so the handle-to-shape ratio measured off a screenshot is exact.

- ⚠️ **A shape's matrix is concatenated onto the canvas, so a non-uniform scale stretches the
  *pen* as well as the shape** (M8 follow-up 9). `PagePainter.paintObject` concatenates and then
  strokes with a paint of one width — deliberately, so a move is a matrix and never a rewrite of the
  geometry — which makes *"make this a perfect circle"* written as `scale(0.75, 1.5)` a circle twice
  as thick at the top as at the sides: *"the line sizes get messed up"*. Anything that changes a
  shape's **proportions** must write its outline (`ShapeRecord.points`) rather than its scale; only
  a rigid change (a rotation, a translation) is safe to put in the matrix. The same fact means an
  edge-handle resize has always thinned the ink on one axis, which is a property of resizing and not
  of any button.
- ⚠️ **A one-shot action whose *feedback* is gated on a mode is a button that appears to do
  nothing** (M8 follow-up 9). The selection bar's `Grid` chip snapped a shape onto the lattice once;
  the chrome that draws that lattice on paper with none of its own is gated on the grid **mode**, so
  with the mode off the shape jumped onto lines nobody could see — *"it just snaps the points to an
  invisible grid"*. It is the mode's toggle now. Before shipping a one-shot beside a mode, ask what
  the user sees afterwards, not what the op says.
- ⛔ **A fix's positive control can be the bug with a different object underneath** (2026-09-25).
  *"Start drawing your line on a stroke … goes into an image like selection"* was fixed by refusing
  a snapped **line** as a connector anchor, and `ShapeSnapBindingTest` kept *"a line drawn from
  inside a box still attaches to it"* as its control — so the refusal could not pass vacuously.
  Three days later the same report came back, and the control was the reproduction: a line started
  inside a box (or on handwriting inside one) was re-routed to the box's edge and given a
  zero-height frame of stacked grips. A control is a claim about what the user wants; **check it
  against the user's own words, not only against the code's design.** A drawn line now binds both
  ends to two different shapes or neither (SPEC §4.8.9).
- ⚠️ **A geometric threshold measured against a *polyline* is measured against the instrument, not
  the gesture** (M8 follow-up 3). The arc→curve classifier scores how far the ink strays from the
  curve it would become. `adb shell input stylus motionevent` sends one event per invocation, so a
  13-sample arc is a coarse polygon and scored 0.0545 against a 0.05 limit — refused, while the same
  arc at 25 samples scored 0.0434 and snapped. Two device attempts were read as *"the feature is
  broken"*. A real stylus samples at 240 Hz; **match the sample count to the hand, not to the
  patience of the shell**, and when a threshold fails on the device, compute the feature over the
  exact points you sent before touching the constant.
- ⚠️ **`send-to-tablet.ps1` reported success and copied nothing, because a dirty build's name and
  size both repeat** (M8 follow-up 3). Its skip is *"same `$DestName`, same byte count ⇒ already
  landed"*, and `inkstone-<sha>-dirty.apk` is the same name for **every** uncommitted build off one
  commit — then two genuinely different builds came out at exactly **98,078,118 bytes**, eighteen
  minutes apart. It printed `OK … is already on the tablet` and left the older APK in place. The
  name now carries eight hex of the APK's SHA-256 when the tree is dirty, so it changes exactly when
  the bytes do. **Two habits stand independently of the fix**: a size match is not a content match,
  and after any upload, list the destination and read the *modified time* back — `$dl.GetDetailsOf($item, 3)`
  over the MTP shell namespace is what caught this.
- ⚠️ **One nullable carrying two meanings is a bug waiting for the second caller** (M8 follow-up 3,
  second session). `ShapePoints.bend` returned null for *"the drag straightened the line"* and
  `DryShape.previewBend` read null as *"stop previewing"* — so flattening a curve fell back to the
  **record's** bend and sprang the shape back to its original arc with the finger still down. The
  KDoc asserted the two never needed telling apart, and was simply wrong about what the function
  returned. The fix is a two-field result (`BendDrag`: a non-null `preview`, a nullable `stored`)
  plus a separate `endShapePreview`, so the collision is unrepresentable rather than remembered —
  the same move as `NoteSession.apply`'s deleted `undoable` flag.
- ⚠️ **When widening a threshold twice does not fix a classifier, the *feature* is wrong** (M8
  follow-up 3). *"Far too easy to make a circle"*, then *"still cant make a square, its way to biased
  to a circel"*. Draw-and-hold scored the fraction of its box a gesture filled — a fact about *ideal*
  shapes, whose rectangle and ellipse targets are 0.215 apart, while a hand moves the measurement
  further than that (rounding removes area; bowing an edge or overshooting a corner *enlarges the
  box*). The fix was a different measurement: **corner concentration**, the share of a gesture's
  turning held by its largest few samples, which is ~1 for a square and ~0.13 for a circle. Two
  independent tests are OR-ed because they fail in *different* circumstances — overshoot craters the
  fill and leaves corners sharp, rounding does the reverse. 30 noisy trials each: squares 30/30
  cornered, circles **0/30**.
- ⚠️ **Resampling by arc length is the sampling-rate-independent way to measure turning, but the
  span and the guard both have to be right** (M8 follow-up 3). Turning between *adjacent* resampled
  points counts every vertex of a coarse polyline as a corner — a 12-point circle hands half its
  turning to its top six samples and reads as *cornered*, and every `adb shell input stylus` gesture
  is that coarse. Measure over a span (1/24 of the perimeter either side) instead. And a guard of
  `if (count < SAMPLES) return 0f` reads *"too few points"* as *"no corners"*, which silently made
  every closed shape on the emulator an ellipse — resampling **interpolates**, so fewer input points
  than output samples was never a problem.
- ⚠️ **Check the fixture before believing the measurement** (M8 follow-up 3). A rounded-square
  fixture interpolated its straight edges toward the next corner's *centre* rather than the next
  arc's *start*, cutting the corners off: it reported a fill of **0.799** at an 18% rounding — an
  oval's number — against a true 0.972, and a classifier threshold was very nearly moved to
  accommodate a shape that only existed in the test. M7's *"a synthetic fixture is the worst possible
  witness for a geometric heuristic"* has a corollary: when a fixture's number is surprising, verify
  the fixture draws what it claims before tuning anything.
- ⚠️ **Two classifier candidates for one gesture must be separated by their *features*, not by
  comparing confidences** (M8 follow-up 3). Adding an arc candidate beside the line one broke
  straight lines within a day: a line's confidence ramps across a 0.03-wide window and so falls off
  a cliff (0.83 at a 60° bow, 0.30 at 70°), while an arc's is nearly flat at ~0.78 — so at 60° the
  line won by **0.03**, and any real hand tipped it. Two ramps normalised differently are not
  comparable just because both are called "confidence". `ARC_MIN_SAGITTA` is now *derived* from
  `LINE_MIN_STRAIGHTNESS` (`sin(x)/x = 0.93` ⇒ sagitta 0.169 ⇒ 0.17) so the candidates are disjoint,
  and the test asserts the **property** against the public constant rather than the number, so the
  pair cannot drift apart.
- ⚠️ **A target at the edge of a feature's range gets half a window, and every error pushes it the
  same way** (M8 follow-up 3, second session). Draw-and-hold scores the fraction of its box a
  gesture fills; a rectangle's target is **1.0**, which is the maximum the feature can produce, so
  rounded corners, bowed edges and corner overshoot all move a square *toward* the ellipse and
  nothing moves it back — and in fill 0.86–0.89 the rectangle fell below the shared confidence bar
  while the ellipse cleared it. *"Its far to easy to make a circle."* When a classifier's target sits
  at a bound of its own feature, give it a one-sided allowance or the window is half the size the
  constant says.
- ⚠️ **A confidence bar downstream of a ramp narrows every window that feeds it — this has now bitten
  the same file three times** (M8b's `FILL_WINDOW`, M8's `LINE_CERTAIN`, M8 follow-up 3's
  `ARC_MAX_RESIDUAL`). `ShapeSnap.recognise` drops anything under `DEFAULT_CONFIDENCE = 0.55`, so a
  candidate that ramps `1 → 0` across its own tolerance is really being held to **45%** of it: a
  perfectly good 140° arc scored 0.30 and was refused by a limit it was well inside. Ramp down *to
  the bar*, not to zero, so everything a check accepts also clears it by construction.
- ⚠️ **Draw-and-hold cannot be driven from the shell without a *stationary* sample, and the failure
  looks exactly like a broken feature** (M8 follow-up 3). Two things, both cheap and neither a bug.
  `ShapeSnapDetector.sample` schedules the dwell timer only when a sample lands **inside** the hold
  radius, so a gesture whose last event is a large `MOVE` answers `NOT_HOLDING`, schedules nothing,
  and the `sleep 1.5` after it waits on a timer that was never set. And `ShapeSnap.recognise` wants
  **six** points, which a four-`MOVE` gesture does not have. The recipe is
  `DOWN …; 10× MOVE …; MOVE <1pt away>; sleep 1.6; UP`. Two walk attempts were read as *"the snap
  is broken"* before this was worked out; the same shape as the `screencap`/`uiautomator` note
  below — the instrument was wrong, not the feature.
- ⚠️ **`uiautomator dump` piped through `adb shell cat` can yield a 0-byte file, and every grep
  against it then "passes"** (M8 follow-up). Three device measurements in a row reported *"nothing is
  selected"* while the screen plainly showed a selection, and one of them was briefly believed to be
  a real regression. Check the artifact is non-empty before believing what it does not contain;
  `screencap` was the instrument that worked.
- ⛔ **You could write beside the page, and no PDF reader would ever draw it.** Free panning (SPEC
  §4.5, revised in M1) puts the empty space next to the paper one finger-drag away, and it took ink
  exactly as the paper did: captured, converted at a **negative page-space coordinate**, journaled,
  put on the undo stack and synced. It drew on screen, because the dry layer clips to the viewport
  and not to the page — and an annotation outside the CropBox is not rendered by anything. Ink the
  app showed you and every other program dropped, with nothing anywhere saying so, reachable since
  M1. The cause is the one CLAUDE.md keeps finding: **the page's rectangle was assumed rather than
  said** — `[0, 0, width, height]`, in six files, with nothing enforcing it. It is `PageCanvas` now,
  the pen refuses a stroke that begins off it, and the dry layer clips to it so the screen agrees
  with the file. Reproduced *before* the fix by drawing beside the paper on an emulator and reading
  the `add` op out of `journal.ops`; the instrumented test that covers it was then checked by
  disabling the gate and watching it go red, because a gate test that passes either way is worse
  than none.
- ⭐ **A value derived from the content converges where a register has to be merged.** An infinite
  page's *size* is not journaled: it is `stored.grownToInclude(contentBounds)`, computed the same
  way by the editor, the thumbnail and the publisher. That is not a shortcut — a last-writer-wins
  size register is exactly what could resolve one device's way and leave a **peer's ink outside the
  box** after a merge, and the derivation cannot, because the peer's strokes are simply in the fold.
  It also costs no op per stroke and leaves nothing that can disagree with the ink. The general
  rule: before adding a register, ask whether the value is already implied by the objects — and if
  it is, whether anything but a renderer needs it written down.
- ⚠️ **Enlarging a PDF page moves the corner page space is measured from.** Page space starts at the
  box's *displayed* top-left (SPEC §4.4), so growing the box to reach a canvas that has grown
  upwards slides **every annotation already on the page** by the amount it grew — an imported
  lecture whose ink walks off the writing the first time somebody scribbles above it.
  `PdfPageGeometry.origin` cancels it exactly, and `PageCanvasPdfTest` asserts the identity for all
  four `/Rotate` values because the quarter turns read the two box edges an unrotated page does not.
  ⚠️ And the same growth applied twice is a page of the wrong size: a *generated* page is created at
  the canvas's extent already, so unioning it with the canvas as well published a 1008 pt canvas as
  a **1404 pt page** with a slab of blank paper down one side. Caught by reading the published
  `/MediaBox` back with a regex over the file's own syntax; every assertion made with PDFBox agreed
  with itself, which is this repo's oldest lesson arriving for the fifth time.
- ⚠️ **`-0.0 + 0.0` is `+0.0`, and `Offset(-0.0f)` != `Offset(0.0f)`.** Adding an offset and taking
  it away again is exact in floating point for every value except that one — so a camera resting at
  exactly `-0.0` came back a *different value* to `equals` and failed
  `PageMetaLiveChangeTest`'s *"the camera must not move under the pen"* on the device. The fix is a
  short circuit when the offset is zero, which is not an optimisation: it is what keeps every page
  that behaved a certain way before a change from going near the new arithmetic at all.
- ⛔ **An imported PDF drew white paper, because the editor asked *which note is this* one
  statement too early** (REMOTE-PDF follow-up). `NoteEditor.openOrCreate` ends `bindSurface()` →
  `display()` → `publish()`; `display` is what calls `NoteSurface.loadPage`, and `:app`'s adapter
  answers that by launching a request for the page's source PDF — which read
  `NoteEditorState.documentId`, and **only `publish` writes it**. Null there means *"this page has
  no PDF behind it"*, which is a legitimate answer for a blank page, so the scan was silently
  replaced by white paper. Nothing retried, because the source page is fetched once per `loadPage`.
  A race, always losable, and a note adopted from Nextcloud lost it **every time** — that open
  follows a burst of index, binding and outbox writes, which is enough to change which coroutine
  runs first. Three things travel with it. **The user's own diagnosis was the whole fix**: *"if i
  add a page then go back to the first page i can see it properly"* named the second `loadPage` and
  therefore the ordering, after an hour of theories about the download, the rasterizer and the
  publisher — when a symptom has a reproducible *cure*, work out what the cure does before
  hypothesising about the cause. **A state flow is what the screen has been told, not what the
  object is**: anything asked *during* an open must read the object (`NoteEditor.openDocumentId`),
  and this is the same family as the `= Unit` stub — a value that is null for a plausible reason
  cannot be distinguished from one that is null by accident. And **the library thumbnail rendered
  the page perfectly throughout**, from the same file with the same rasterizer, which is what made
  it look like a data bug rather than a timing one.
- ⚠️ **`nextcloud.py`'s `listing()` requested `d:getetag` and `oc:fileid` and then threw both
  away** (the reader). The PROPFIND body had always asked for them; the returned dict had six keys
  and neither was among them. Nothing noticed for two milestones because the watcher's idempotency
  is *"is there a `.md` beside it"* and it never needs a change token — so the cost only appeared
  when something wanted to **cache**, and a reader keyed on path alone serves a renamed note's old
  title forever while one keyed on `size` misses every edit that did not change the byte count.
  The general shape: **a field that is fetched but unused is not free — it is a capability nobody
  can see**, and the next caller reimplements it or does without. Two keys added; `test_watch.py`'s
  17 checks pass unchanged.
- ⚠️ **`${CLAUDE_PROJECT_DIR}` is not expanded inside `.mcp.json`.** Claude Code sets it in the
  **spawned server's** environment, which is after variable expansion has already happened, so an
  entry using it fails with *"Missing environment variables: CLAUDE_PROJECT_DIR"* — and the server
  then never starts, which reads as a broken server rather than a broken path. A repo-relative path
  works. Found by running `claude mcp list` rather than by trusting the documented expansion, and
  that command is the cheap check for any `.mcp.json` change.
- ⚠️ **The current MCP specification is `2026-07-28` and this environment's Claude Code cannot
  speak it.** Build 2.1.218 supports `2025-11-25 · 2025-06-18 · 2025-03-26 · 2024-11-05 ·
  2024-10-07`, and the spec's own matrix says a legacy client meeting a modern-only server **fails
  with no fall-forward**. Worse and easier to do by accident: a server that implements the legacy
  `initialize` but answers with a hardcoded newer version is *also* a hard failure, because the
  client runs `if (!SUPPORTED.includes(result.protocolVersion)) throw`. **Echo the version the
  client asked for** when it is one you speak. This is rule 3 — never invent a version number —
  applied to a wire protocol, and it was caught by reading the spec and the shipped client rather
  than by recalling either.
- ⚠️ **An MCP server's stdout carries the protocol and nothing else, and Python's is block-buffered
  on a pipe.** A small `InitializeResult` written with `print` sits in an 8 KB buffer and the client
  times out; one stray `print`, one `logging.basicConfig(stream=sys.stdout)` or one
  `json.dumps(indent=2)` corrupts the stream. All three present identically as *"the server never
  connected"*. One writer, `separators=(",", ":")`, flush every message, logging to stderr — and a
  test that asserts **every line the process emitted** is one parseable JSON object, because that is
  the only assertion that catches all three.
- ⛔ **"Never retry a 401" is a property of the *system*, not of a call site — and the retry loop
  can be the user** (the reader). Every call in `tools/web` let its 401 out and nothing wrapped one
  in a retry, so rule 5 read as satisfied. But a website re-scans: on load, on Refresh, and when
  its cache expires, each scan being one PROPFIND per folder. A revoked app password was therefore
  one failed login **per folder per refresh, forever** — exactly the brute-force lockout the rule
  exists to avoid, assembled out of calls that were each individually compliant. The fix is a
  **latch**: the first 401 halts the source until a person restarts it, which is what the app's own
  `NEEDS_REAUTH` flag is. When a rule forbids a loop, ask what the whole system does, not what one
  function does.
- ⚠️ **`removeAttribute('src')` does not stop a playing `<audio>` element.** Every engine gates the
  media load algorithm on the attribute being *present*, so removing it is a no-op on playback;
  `pause()` then `load()` is what stops it. Found by review and then **reproduced in the browser**
  — the element was still playing two seconds later — because "the spec says so" and "this build
  does so" are different claims. The failure it caused was ordinary rather than exotic: a
  transcribed recording has no audio by design (M12 §8), so *"open a second lecture"* normally hit
  the branch that cleared `src`, and the first lecture kept talking behind a disabled Play button.
- ⚠️ **A document-level key handler must exempt anything natively keyboard-operable.** Binding
  Space to play, with `preventDefault`, took Space from **every button on the page** — and
  `preventDefault` makes it a replacement rather than an addition, so Tab-to-a-button-and-press-Space
  did the other thing. `event.target.closest('input, textarea, select, button, a[href],
  [contenteditable]')` is the guard. Its sibling: gate the handler on the **view being visible**,
  not on the model being loaded, or the transport drives a lecture that is not on screen.
- ⚠️ **A geometric feature drawn from timestamps must be bounded by something, or it asserts the
  gaps** (the reader). The lesson ribbon ran each transcript paragraph's band to the *next*
  paragraph's start and the last one to the end of the recording — painting **85 %** of a lesson as
  speech that M12 §15.7 had independently measured as **39 %** speech. A transcript records when a
  paragraph *began* and says nothing about when it ended, so every silence became talking. Bounding
  each band by its own word count at the measured 164 wpm gives 38.9 %. **The check that settled it
  was an independently measured number in a doc**, not the drawing looking better.
- ⚠️ **A cache key that omits the thing that changes is a cache that serves last week's answer** —
  and the field to key on may be fetched already and thrown away. See the `nextcloud.py` `getetag`
  note above. Its mirror image: **a counter incremented inside a `break` is a counter that depends
  on unrelated results.** `notesUnindexed` was counted after the hit-limit check, so a query that
  filled its limit on other material reported every note as unsearched — the one field whose entire
  job is telling "no hits" apart from "nothing was looked at".
- ⚠️ **`zipfile.read()` inflates without consulting the declared size.** A tool that opens files it
  did not create — and this repo's reader opens *every PDF in the account* — needs a per-entry cap,
  not just a cap on the whole archive. A 1.2 KB PDF held a bundle whose one JSON entry inflated to
  830 MB. Related, and easy to miss: a guard that keeps **every** candidate it inflated has a bound
  of *N* × the per-item cap, not the cap.
- ⚠️ **Binding to `127.0.0.1` is not a threat model.** Any web page can point a hostname it controls
  at loopback and read a local server through the user's own browser (DNS rebinding). The `Host`
  header is the check that distinguishes "somebody typed this in" from "a page reached in".
- ⚠️ **`kill %1` has no job table across tool invocations, and a "restarted" server that answers is
  not evidence it restarted** (the reader). A stale process kept port 8765 for forty minutes; the
  new one silently failed to bind. **CSS and JS edits still appeared**, because those are read from
  disk per request, so the page visibly picked up changes while the Python did not — which is the
  trap, and it very nearly produced a wrong diagnosis of a working feature. Check the listening
  process's **start time**, not that the port answers:
  `Get-NetTCPConnection -LocalPort … | Get-Process | Select StartTime`.
- ⚠️ **`BrokenPipeError` does not catch a Windows client disconnect — they are siblings.**
  POSIX raises `BrokenPipeError` (EPIPE) when the peer goes away mid-write; Windows raises
  `ConnectionAbortedError` (10053) or `ConnectionResetError` (10054), and all three are children of
  `ConnectionError` rather than of each other. A guard naming only the first looks correct, reads
  correct, and never fires on Windows. Catch `ConnectionError`.
- ⚠️ **A disconnect surfaces in THREE places, and guarding one moves the traceback rather than
  removing it** (the reader). (1) mid-write while streaming a file; (2) writing the reply — including
  writing the 500 *about* (1), which raises a second time and is what stacks two tracebacks; and
  (3) **reading the next request** on a kept-alive connection, inside
  `http.server.handle_one_request`, before any of our code runs. Only after fixing (1) and (2) did
  (3) appear, from a different line, looking like the fix had not worked. Override `handle`,
  `finish` and `handle_error`, and keep the full traceback for anything that is not a
  `ConnectionError` — swallowing real faults is worse than the noise being removed.
- ⚠️ **Reassigning `iframe.src` reloads it even when only the `#fragment` differs.** Moving a PDF
  viewer to another page that way re-downloads the whole file and cancels the in-flight request —
  which is what produced the disconnect traceback above, once per press. Same origin, so set
  `frame.contentWindow.location.hash` instead and assign `src` only for a genuinely different
  document.
- ⛔ **Python 3.14 turned on `VERIFY_X509_STRICT` by default, and it refuses chains every browser
  accepts.** `ssl.create_default_context()` now enforces RFC 5280 conformance, so a certificate
  whose CA lacks an **Authority Key Identifier** fails with *"certificate verify failed: Missing
  Authority Key Identifier"* — which reads like a trust failure and is a conformance complaint.
  Chrome, Firefox and curl do not enforce it. The fix is to clear **that one flag**, per host, only
  when it is the sole reason the handshake failed (`tools/web/tlsdiag.py`); `CERT_REQUIRED` and
  `check_hostname` stay on, and an untrusted, expired or wrong-host certificate still fails.
  ⚠️ Never "retry on any verification error" — that accepts an attacker's self-signed certificate.
  The retry list is four named conformance strings.
- ⛔ **badssl.com proves nothing on an intercepted network, and the test will pass while being
  meaningless.** Checking the relaxation with `self-signed.badssl.com` returned **accepted** — not
  because verification was off, but because a TLS-inspecting appliance re-signs *every* connection
  with a CA the machine trusts, so the deliberately-bad site arrives with a good certificate. Any
  test that needs a remote host to present a *bad* certificate is untrustworthy on such a network
  and cannot detect that it is. Test the machinery instead: same flags, a CA store that does not
  contain the signer, and assert every host is refused. That is a statement about verification
  rather than about a certificate, and no middlebox can make it pass. The instrument was inside the
  thing being measured — this file's oldest lesson, in a new place.
- ⚠️ **A two-certificate chain is a middlebox.** A public chain is normally leaf → intermediate →
  root. Leaf plus a self-signed root that is also the leaf's direct issuer means TLS is being
  terminated locally — and the credential, the files and the recordings are all readable by that
  device. Worth **telling the user** rather than only working around: they may not know, and if it
  is their own firewall they will recognise it immediately.
- ⛔ **A feature built as a CLI flag is inert for anyone using the GUI — and the error message can
  point *away* from it** (the reader). `--login` was added so nobody would have to mint a Nextcloud
  app password by hand; one turn later the website was still printing *"mint a new one: Settings →
  Security"* with nothing to press, because the sign-in lived on a command line the user was never
  going to type. The report was *"does not give me an option to sign into nextcloud"*, which was
  exactly right. Definition-of-done #6 again, and the tell was there in the code: the 401 handler's
  own text described a manual workaround for a problem the codebase had already solved. **When a
  feature removes a manual step, grep the error messages that recommend that step.**
- ⚠️ **A latch needs a release, and the release is not "write the new credential"** (the reader).
  `NextcloudSource` latches shut on its first 401 so a re-scanning page cannot become a
  brute-force loop. That latch is per *object*, so a successful re-authentication that only wrote
  the password to disk would leave the running source still refusing — the sign-in would work and
  the page would show the same error. The source, the library and the MCP tools are all rebuilt.
  Any guard that can trip permanently needs a tested path back.
- ⚠️ **A `.ps1` launcher does not run on an untouched Windows machine.** The `CurrentUser`
  execution policy defaults to Restricted, so `run-reader.ps1` failed with *"running scripts is
  disabled on this system"* the first time its user tried it — while every check during
  development passed, because this harness's PowerShell runs with `Process` scope `Bypass`. That
  asymmetry is the trap: the tool that writes the script is exempt from the rule that stops the
  person using it. A `.cmd` shim is not subject to execution policy and needs no
  `Set-ExecutionPolicy` decision from the user; `tools/web/run-reader.cmd` is the pattern, and
  `run-watcher.ps1` and `send-to-tablet.ps1` have the same exposure.
  ⚠️ And **`python` is not the interpreter to invoke** — with the Microsoft Store aliases enabled
  (the default) `python` and `python3` resolve to WindowsApps stubs that fail with *"The system
  cannot find the path specified"*, while `py` works. Measured, not assumed. A launcher must
  **run** each candidate rather than trusting `where`/`Get-Command`, which report the stub as a
  perfectly good match — `run-watcher.ps1` already knew this and the knowledge did not travel.
- ⛔ **`no-referrer` makes Chrome send `Origin: null` on a form-submission navigation**, so a page
  that sets that policy and then POSTs its own login form is refused by its own cross-origin check
  — with an error blaming the caller. Found in the reader's sign-in page, which failed for **every**
  attempt in real Chrome. `same-origin` keeps the whole privacy intent and leaves same-site requests
  attributable. ⚠️ The fix is the policy and **not** a laxer Origin check: `Origin: null` from a
  sandboxed iframe is exactly what that check is for, and weakening it to make a login work would
  trade a broken feature for a hole. Note also how it was diagnosed — the in-app browser pane failed
  first and *"the pane sandboxes the page"* was the obvious reading, which is this file's most
  repeated misdiagnosis; **real Chrome failing identically** is what made it a bug.
- ⚠️ **A container's healthcheck can be broken in both directions by one route choice.** The reader
  probed `/api/health`: that route is behind the auth gate, so a probe with no credentials gets a
  401 — and treating 401 as "fine" reports healthy whatever the app is doing, which is a
  healthcheck switched off without anyone deciding to. Meanwhile `INKSTONE_ALLOWED_HOSTS` made the
  probe's own `Host: 127.0.0.1` a **403**, so the other reading reports unhealthy forever. A probe
  needs its own open, minimal route, and loopback Hosts must always pass — a rebinding attack
  arrives claiming the *attacker's* hostname, never `127.0.0.1`.
- ⚠️ **`removeAttribute('src')` does not stop a playing `<audio>` element** — every engine gates the
  media load algorithm on the attribute being present. `pause()` then `load()` does. Verified in a
  browser rather than argued from the spec.
- ⚠️ **The `hidden` attribute loses to any author rule that sets `display`.** It is a UA-stylesheet
  `display: none`, so `.pane { display: flex }` silently defeated `element.hidden = true` and left
  an empty "The page" panel on screen for every recording with no PDF beside it — which looks like
  a data bug and is a specificity one. Any stylesheet that toggles visibility with `hidden` needs
  `[hidden] { display: none !important; }`.
- ⛔ **An upload path could only be chosen with a network, and nothing about choosing one needs
  one.** `SyncEngine.bind` writes three rows and enqueues MKCOL then UPLOAD without opening a
  socket — the MKCOL is unconditional *precisely* so the folder need not exist yet — so binding,
  pinning and setting the default folder were all offline-safe already. The single step that was
  not was **seeing the folder names**: the browser is one `PROPFIND Depth:1` per level and it kept
  nothing, so offline it drew an empty list and a red line, and a decision that costs nothing
  became impossible to make. The user's report was the whole diagnosis: *"it does not remember the
  file structure of the server."* The habit: **when a feature is gated on the network, check which
  of its steps actually make a request** — here it was one out of five, and the fix was a cache of
  names rather than anything about sync. ⚠️ The new `browsedFolder` table is **not**
  `remoteDirectory`: that one is the reconciler's promise that a directory was *walked to
  completion*, and a row it did not earn stops a subtree syncing or gets a note trashed. Nothing
  that decides what syncs may read the browser's cache. `docs/OFFLINE-FOLDERS.md`.
- ⚠️ **"There is nothing in here" and "I have never looked in here" are different sentences, and
  a cache that cannot tell them apart says the alarming one.** An offline picker whose `listedAt`
  is 0 draws an empty list; rendered as *"no folders"* it tells a user their Nextcloud is empty.
  Same family as the `= Unit` stub and the null `documentId`: **a value that is empty for a
  plausible reason cannot be distinguished from one that is empty by accident** unless something
  beside it records which. The decision is a pure function (`OfflineBrowsing.notice`) for the
  reason this file keeps giving — `SyncViewModel` cannot be built in a JVM test, so the *decision*
  is hoisted where one can reach it and the composable keeps nothing but a `Text`.
- ⛔ **A reader that re-opens a file by path for every read sees two files when a writer renames
  over it** (2026-09-24, the tablet's only crash). `FileByteSource` called `fs.readRange(path, …)`
  per read, so a `Zip.Directory` built from one `note.inkstone` read its entries out of the next —
  *"Bundle entry 'pages/….ink' has no local header"* — while the note's own editor materialized it.
  Rule 10 makes every write a rename, so **every multi-read reader must hold one descriptor**
  (`FileSystem.openRead`). And pinning is not free: a session that no longer fails when another
  writer replaces its file will happily write over that file, so `NoteStore.materialize` checks
  `reader.isCurrent()` (inode) first. ⚠️ That guard shipped inert for an hour — the interface
  defaults answer *"cannot tell, so yes"* and only `AndroidFileSystem` can say otherwise. A guard
  whose platform half is a default is a JVM test and nothing else.
- ⛔ **An exception escaping a `LaunchedEffect` kills the process, and a `LaunchedEffect` runs on the
  UI thread** — so a library tile that folded a note (`Thumbnails.cover` → `foldCoverPage`) did 6–8 MB
  of CRC, JSON and folding on the main thread, and the first torn read took the app down. Anything a
  composition effect calls that can throw or read a file must switch dispatcher and catch
  (`Thumbnails.orPlaceholder`), rethrowing only cancellation.
- ⛔ **`Activity.onStop` → `viewModelScope.launch { save }` loses ink on Back.** Back runs `onStop` and
  then clears the view model, which cancels the scope; the save's first suspension is the surface
  drain, which is destructive, so the strokes it had just taken were dropped. Reproduced twice on the
  emulator. The close-time save runs in `NoteSessionHost`'s own scope under the note's mutex now, and
  the editor keeps a detached surface until something drains it. Same family as M12's
  *"`ViewModel.onCleared` cannot suspend"* — it is the scope, not the callback, that dies.
- ⚠️ **The tablet had only ever run the debug APK**, which Android never compiles ahead of time. On
  the emulator, same code and data, release was 3.5–4× faster on the paths the user complained about
  (library 16.6 s → 4.6 s). `send-to-tablet.ps1` sends a debug-key-signed release build by default now;
  `-DebugBuild` for when `run-as` on the tablet is needed.
- ⚠️ **`androidx.ink`'s `StrokeInputBatch.decode` allocates a 32 KB buffer per stroke**
  (`DecompressedBytes`, read with `javap`). Rebuilding a 2,000-stroke page is ~69 MB of large-object
  garbage — which is what *"GC freed 40MB … 957(40MB) LOS objects"* every few hundred ms was. Count the
  full-page rebuilds (open, cover, recognition, publish) before blaming anything else for GC churn.
- ⚠️ **A stand-in whose KDoc says "moving this is a change of import" is a feature shipping with the
  stand-in's limits until somebody makes the move** (Elements follow-up). M8c's `:app`
  `ElementLibrary` said exactly that and skipped every pen stroke; `:core:storage`'s finished,
  tested library — which holds handwriting — had **no caller** for four milestones, so the user's
  *"the elements thing does not work too well"* was a hand-drawn icon saving as nothing. Grep for the
  real implementation's callers, not for the stand-in's TODO. `docs/M8-ELEMENTS-KNOCKOUT.md` §1.
- ⚠️ **`DST_OUT` on the dry layer's own canvas punches through the paper.** Background and objects
  share one canvas, so a knockout has to cut inside a `saveLayer` of the objects alone — and the
  append tail, drawn straight onto the view over the recording, must never carry one
  (`StrokeStore.noteAppendedOnTop` refuses it). SPEC §4.9.6.
- ⛔ **A stand-in reached the tablet through the one constructor no test called** (the calculator,
  2026-10-11). `:core:calc`'s key machine was built in parallel with its evaluator, against an
  integer-only stand-in, and `Calculator()` — the public constructor, the only one the app uses —
  still built that stand-in after the real evaluator landed. 556 tests were green: every machine test
  ran on documented fakes through the *internal* constructor, and every evaluator test ran without
  the machine. The tablet showed **Math ERROR for `100 ÷ 1,80`**. Two habits: when a seam exists so
  parts can be built in parallel, the wiring that removes the stand-in is a deliverable with its own
  test, and **at least one test must go through exactly the entry point production uses** — here
  `e2e/*` drive the public `Calculator()` with the guide's own key sequences.
- ⛔ **A singleton's launch-time snapshot is not a seed for the second screen** (2026-10-12).
  `EditorPreferences` read the `setting` table once and every editor seeded from that read, so the
  second note opened in a process showed the launch-time palette — and `snapshotFlow`'s *first*
  emission, which is the seed itself, wrote it back over the user's choice 400 ms later. *"The
  favorite colors does not stay like that and the pencil size."* The codec was right and its test was
  green; the test that would have caught it was named in that test's KDoc and did not exist. Seed from
  the **newest** value (`EditorPreferences.current`), re-adopt on `ON_START`, and when a KDoc names a
  test as the one that covers the rest, check that it exists. `docs/CUSTOM-LAYOUT.md` §1.
- ⛔ **Compose restarts a `pointerInput` gesture when the density under it changes.** A resize grip
  inside the `CompositionLocalProvider(LocalDensity …)` it was resizing cancelled its own drag on the
  first frame: the bar flickered and stayed the size it was, while *moving* the same bar — which
  changes no density — worked. Anything that changes a scale must be driven from outside that scale.
- ⚠️ **A drag that moves its own node to another parent cancels itself.** Compose disposes a node
  whose parent changes, and the gesture coroutine in its `pointerInput` goes with it — so a chip that
  trades places *live* works inside one row and dies on the first bar boundary. Describe the drop
  while dragging (a picture under the finger, a caret where it lands) and change the tree once, on
  lift (`ChromeDrop`). Within one parent, `key()` moves the node and the gesture survives.
- ⚠️ **Chrome 12 dp from a screen edge is inside the system Back gesture.** A drag that starts there
  and moves inward is Back, not your gesture — logcat says `startBackNavigation` at the instant it
  begins. Handles that are dragged need `Modifier.systemGestureExclusion()`.
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
- ⚠️ **A backdrop blur over the note editor is impossible, not expensive** (M10). `Modifier.blur`
  blurs a composable's *own* content; blurring a backdrop means reading the pixels underneath, and on
  this screen those pixels are not the app's to read — `InProgressStrokesView` front-buffers through
  a `SurfaceView` that SurfaceFlinger composites over the window (SPEC §3.2, the same fact that stops
  wet ink carrying a blend mode). A blur pass would sample everything *except* the ink. What produces
  the "frosted glass" look at these values is the **translucency**, not the blur: a 65 %-opaque panel
  over a near-black canvas with a hairline edge is what the reference design actually reads as.
  `docs/M10-DESIGN.md` §2.
- ⚠️ **`enableEdgeToEdge()`'s default picks system-bar icon colour from the *device's* day/night
  setting, not the app's theme** (M10). `SystemBarStyle.auto` is the default, and this app is
  dark-only — so on a tablet in light mode it gave dark status-bar icons over a near-black canvas: an
  invisible clock and battery, on every screen, in the configuration most tablets ship in. Both
  activities pass `SystemBarStyle.dark(...)` explicitly. Its sibling is `windowBackground`, which is
  the colour the *framework* paints between the launcher tap and the first Compose frame — left at
  `DayNight`'s default it flashed full-screen white before a near-black editor, and no amount of
  Compose theming reaches it.
- ⚠️ **A dot grid drawn as a loop of `drawCircle` is ~7,000 draw calls per frame** (M10), behind the
  ink surface, which is exactly where rule 7 says nothing heavy may go. A 24 dp grid over a
  2560 × 1600 tablet is 107 × 67 dots. `rememberDotGridBrush` bakes one tile into an `ImageBitmap`
  and repeats it through an `ImageShader`: one textured rectangle, no per-frame allocation.
- ⛔ **`androidx.ink` REFUSES a singular `motionEventToWorldTransform`, and the ruler had been
  handing it one since M8c** (M8 follow-up 5). `InProgressShapesView.strokeUnitLengthCm` inverts it
  to work out how many centimetres a stroke unit is — which this app needs, because
  `InkstoneBrushFamilies` gives the fountain pen an `INPUT_SPEED_IN_CENTIMETERS_PER_SECOND`
  behaviour — and throws `IllegalArgumentException: motionEventToWorldTransform must be invertible`
  out of `startStroke`. So switching the straight edge on **stopped the pen working entirely**: not
  a stroke that failed to snap, a stroke that never began, on the ACTION_DOWN. `RulerController`'s
  KDoc had argued the singular matrix was *"safe here only because `strokeToWorldTransform` is the
  identity, so nothing ever inverts it"* — true about *our* code and false about the library's, and
  `RulerAndLaserTest`'s KDoc had already named it as the one unverified thing and said *"the
  emulator walk is what closes it."* **The walk never happened, and a named unverified assumption
  is not a check.** The snap projects a pooled copy of the `MotionEvent` now (`MotionEvent.transform`
  moves every historical sample and leaves pressure and tilt alone) and ink gets the ordinary
  camera. ⚠️ The near-singular `εI + (1−ε)P` is **not** the fix: `strokeUnitLengthCm` averages the
  row norms of the *inverse*, so any ε small enough to straighten a line reports a stroke unit tens
  of times too long — the condition number is the error.
- ⚠️ **The ruler could not be grabbed while the pen was in your hand, for the same reason the
  two-finger undo could not be tapped** (M8 follow-up 5). `RulerController.begin` sat behind
  `PalmGuard`, and the S Pen refreshes the guard from ~1 cm of hover — which is the *only* posture a
  straight edge is used in. The grab is in front of the guard now, exactly as the tap detector
  already was; `onMove` had been given the exception years earlier and only the grab was left
  behind. When a note says *"X, Y and Z stay behind the guard, exactly as before"*, check that every
  one of them can still be reached with a pen in the other hand.
- ⚠️ **A tool's chrome must not cover the thing the tool produces.** The ruler's translucent body was
  drawn centred on the line strokes snap to, so ruled ink landed in the middle of a 44 dp band — over
  the line and over whatever was being ruled against (*"otherwise it covers it"*). The KDoc had
  considered a one-sided edge and rejected it because *"chrome that disagreed with the geometry by
  half the slab's width would make every ruled stroke land visibly beside the guide"* — sound, and it
  had a third answer: **move the body, keep the line.** Corollary: the **grab** has to move with the
  chrome, and one constant has to feed both (`InkRuler.bodyWidthDp`), or you get a ruler you can see
  and cannot pick up — undiagnosable at 12 % alpha.
- ⚠️ **An unbounded `launch` per press turns one slow operation into a burst of unwanted ones**
  (M8 follow-up 6). Every two-finger undo tap was its own `work.launch { serial.withLock { … } }`,
  and nothing reached the screen until a press got the lock and ran all the way to its own
  `reconcile` — so taps made while the editor was busy looked dead and then landed together, several
  gestures past where the user meant to stop. The cost per press was the other half: undo opened
  with a full `saveLocked`, and `session.undo` leaves its inverse in `pending`, so `hasUnflushedWork`
  was true on the *next* press and the flush never amortised. **`NoteStore.saveUndo` is O(the whole
  stack)** — it re-encodes both stacks, every geometry blob included — which is why *erasing* made
  the lag worse: an undone erase carries a whole absolute region, exactly as
  `UndoStack.DEFAULT_MAX_DEPTH` warns. Presses coalesce into one batch now, and undo drains the
  surface without flushing (the autosave owns durability, and an undo lost to a kill leaves the ink
  **present**, which is the safe direction). ⚠️ Do **not** net undos against redos in a burst: with
  two entries on the stack, five undos then two redos returns to the start while a net three does
  not, and putting an overshoot right is this bug's own aftermath.
- ⛔ **Erasing a stroke away and pressing undo did not bring it back — for as long as pruning has
  existed** (M8 follow-up 6). `pruneFullyErased` drops a fully erased stroke off the page and
  deliberately emits **no op**, so the object is still in the fold; `NoteEditor.reconcile` therefore
  sees it in *both* folds and emits a bare `SetErasedRegion` rather than an `AddObject`; and
  `StrokeStore.applyRegister` opened with `byId[target] ?: return false`, having just removed that
  id. Correct op, correct journal, correct fold, one consumer that could not act on it — and the
  stroke stayed gone until the page reloaded. Intermittent, because `PRUNE_BUDGET` decides whether a
  given stroke actually left the page. `prunedByErase` holds what the prune took and the un-erase
  reaches it. **A "render-only" removal is only render-only if something can put it back** — the
  same family as `DryShape` never calling `setErased` on load, and found the same way: grep every
  *consumer* of a register, not just its writer.
- ⛔ **Opening a note ran the whole open on the UI thread, and no amount of fixing what happens
  *during* an open helps until that is true** (M8 follow-up 6). `NoteEditor.work` is
  `Dispatchers.Main.immediate` deliberately — it is what serialises the session — so `openOrCreate`'s
  journal read, CRC, op decode and `NoteStore.loadUndo` all sat there, and `InkSurfaceView.loadPage`
  natively tessellated every object in one unyielding loop on top of that. `Skipped 190 frames!` and
  `Displayed +3s632ms` on a 1,187-object note (docs/M8-PERF-RENDER-CACHE.md §5). A two-finger undo
  tapped in that window was **not slow — the thread it needed was gone**, which is why the
  press-coalescing fix above did not make it feel fixed. Split into `PreparedPage.of` (pure
  construction, any thread) and `StrokeStore.install` (list appends, UI thread). ⭐ **The question
  that blocked this for a session — "can `androidx.ink` build a `Stroke` off the main thread?" — was
  already answered in the repo**: `InkPageThumbnailRenderer` has done exactly that on
  `Dispatchers.Default` for every library thumbnail since M3. Before treating a threading question
  as unknown, grep for whether some other feature already relies on the answer.
- ⚠️ **A page whose objects are still being built must refuse the pen, and must say it is loading**
  (M8 follow-up 6). Until `install` runs the store is empty and `topZ` is null, so a stroke drawn
  then takes a base z key and the page's whole contents install *above* it — M8a's `lastZ` bug
  recreated by a loading screen, which is the trap docs/M8-PERF-RENDER-CACHE.md §5 names. Refusing
  costs nothing that existed: the load blocked the UI thread before, so input in that window already
  went nowhere. And an app whose subject is not losing work must never show a page as **blank** while
  it loads — that reads as *"my note is empty"*, which is the most alarming thing it could say.
- ⚠️ **`kotlinx-coroutines-debug` appends ` @coroutine#N` to the thread name, so any test asserting
  on `Thread.currentThread().name` by equality passes vacuously** (M8 follow-up 6). A test written to
  prove the open had moved off the editor's thread compared against a `ThreadFactory` name, never
  matched, and **passed against the deliberately broken build**. Match by prefix. It was found only
  by reverting the fix and checking the test went red — which is the whole argument for that step:
  the instrument was wrong in the direction that looks like success.
- ⛔ **Undo waited for the handwriting recogniser, and the storage layer had already been written
  so that it did not have to** (M8 follow-up 6, §5). `NoteEditor.recogniseNow` was
  `serial.withLock { … }` around a whole `NoteRecogniser` pass — fold, sample, and **ML Kit, one
  call per line of handwriting**. SPEC §8.3 fires that three seconds after the pen stops, which is
  the moment a person stops drawing and reaches for undo, so every press queued and the whole batch
  landed at once when it finished. Meanwhile `NoteSession.recognised` says in its own KDoc *"the ink
  can and does change while it runs"* and returns **false** when the digest moved, and
  `NoteRecogniser.readPage` re-reads through `recognisedText` for the same reason — **both guards
  were unreachable under that lock**, guarding a situation the code prevented. The lock was strictly
  more than the design asked for and the cost was the editor. `SessionGate` splits it: session access
  through the gate, the ML Kit call outside it. ⚠️ A `Mutex` is never re-entrant, so a gate handed
  out from inside its own `withLock` deadlocks for good. **When a lock is held across a slow call,
  read what the callee already promises about concurrency before assuming the lock is load-bearing**
  — here the defensive code downstream was the evidence that it was not.
- ⚠️ **A test whose assertion needs the very lock it is about can only hang, never fail** (M8
  follow-up 6). The first version of the recognition test asserted through `editor.foldedPage`,
  which takes `serial` — so against the pre-fix code the assertion blocked and the reproduction
  reported itself as *"the test body did not run to completion"* after a minute. Assert on something
  outside the lock (`FakeSurface.applied` is a plain field) and the same bug fails in six seconds
  saying what is wrong. Same family as the thread-name instrument bug above, and found the same way.
- ⚠️ **A KDoc arguing that a restriction is honest is worth re-reading when a user complains about
  it** (M8 follow-up 4). `DryTextBox` said a resize scales a text box *"exactly as it scales a
  photo"*, and left the wrap width on the first-write-wins payload so it could never change. The
  analogy is where it fails: scaling a photo **is** resizing it, because nothing inside a bitmap
  could re-flow, while a paragraph has a line-breaking algorithm inside it and every text tool a user
  has met puts that algorithm on the resize handle. So *"widen this label to fit one more word"*
  returned the same words, larger, on the same number of lines. The frame is `SetFormat` keys `w`/`h`
  now (absolute, LWW) — and the read side had to move with it in **four** places at once: the wrap
  width, the silhouette a lasso tests, the rectangle a tap tests, and the published PDF's layout. A
  layer left on `widthPt` draws the box at one size and measures it at another, which is this file's
  *"selectable with a lasso but not with a tap"* reached through a stale extent rather than a second
  corner list. Same family as the affine-degrees-of-freedom and `roundsCorners` entries below: **the
  KDoc that explains why the wrong thing is right is the expensive kind.**
- ⚠️ **"This kind can't do that" is a claim about the *representation*, and it is worth re-deriving
  before writing it into a KDoc** (M10). `ShapeGeometry.roundsCorners` answered *only a rectangle*
  for six milestones, arguing that *"an equilateral apex and a 15° sliver want visibly different
  radii to look equally rounded, and picking one makes the sliver disappear"*. True, and it does not
  follow: a fillet is **the largest circle up to that radius which fits between the two edges**, so
  clamping the tangent length per corner rounds each one by as much as it has room for and nothing
  disappears. The same reasoning had a second home — `outline` also dropped the radius for an
  explicit vertex list — so *"a shape can't do this"* silently became true of the one shape a user
  had just dragged into place. Same family as the affine-degrees-of-freedom entry below: **the KDoc
  that explains why the restriction is a feature is the expensive kind.** And the cost is not only
  the missing capability — `:app` gated its `ROUND` chip on "is a shape selected", so the chip was
  *shown* for an ellipse and did nothing when pressed. A capability answered in one place is read in
  two; grep for both before believing either.
- ⚠️ **`input tap` cannot select on the ink surface either — it needs `input stylus tap`** (M10).
  The `PalmGuard` note above is written about *drawing*, and a tap reads like a different thing. It
  is not: three `input tap`s on the interior of a plainly-visible triangle selected nothing, which
  looks exactly like the hit-test bug `DryShape.tappedAt` exists to prevent, and `input stylus tap`
  at the identical coordinate selected it first time. When a device check says a feature is broken,
  suspect the instrument before the feature — this is the fourth time in this file.
- ⚠️ **A toolbar that follows the selection must not follow the *drag*** (M10).
  `SelectionController` deliberately fires `onChanged` at the end of a gesture and `onPreview` per
  frame, because recomputing membership per input event fanned hundreds of recompositions a second
  into Compose. A contextual bar keyed on `onChanged` therefore snaps to the new position when the
  drag ends — which is both the cheap behaviour and the better one. Keying it on the preview would
  have re-run `shapeCounts` (O(selection), allocating) at 240 Hz for a value that cannot change while
  the finger is down.
- ⚠️ **A layout is only verified in the orientation you verified it in, and a hidden chip is
  *absent* from `uiautomator dump`** (M10 follow-up, portrait). Every M10 check was made on a tablet
  in landscape — 1,280 dp — and three of its floating bars overflow at 800 dp, hiding their own last
  controls behind a sideways drag nothing suggests. The instrument that makes this cheap is the dump
  rather than a screenshot: a control beyond the end of a scrolling pill is **not in the XML at
  all**, so `grep`ping the dump in each orientation answers *"can a user reach this"* in a way that
  looking cannot. ⚠️ **But read the dump carefully before deciding what kind of bug it is.** The
  dock and the record chip *abut* at x=1401 — they touch, they do not overlap, and every chip keeps
  its own hit target. That was fixed twice as if it were a reachability bug: once by making the two
  siblings in a `Row`, which moved the dock **63 dp in landscape**, and once by reserving the chip's
  measured width symmetrically, which pushed `RULER` off the end of a scrolling bar in portrait —
  trading a chip that *looks* cramped for a chip that is *not there*. Both reverted. An appearance
  problem fixed as a reachability problem is this repo's usual failure running backwards.
- ⚠️ **A second row of bottom chrome has nowhere to go that is not on the paper** (M10 follow-up).
  Stacking the page bar onto its own line is the obvious answer to a centre zone squeezed to 188 dp,
  and it was built, tried both ways round, and rejected on sight both times — *"the thing that has
  ADD DUPLICATE and DELETE is moved up and i dont like that"*, then *"its not like how it was at
  all"*. The version that kept the page bar on the bottom line was the **worse** of the two, which is
  the part worth remembering: it pushes history and zoom *up onto the page*, so two pills are cut in
  half by the paper's bottom edge, and the bottom chrome's whole premise is that it sits below the
  document. When a zone is defined by what it sits below, it has exactly one row to spend.
- ⛔ **The ink surface renders *garbage* on an x86_64 emulator, and it is convincing garbage** (M10
  follow-up). Screenshots of a page showed white blocks in a drifting diagonal lattice, checkerboard
  patches and — decisively — fragments of the **Google logo**: an uninitialised buffer being
  composited for `InProgressStrokesView`'s front-buffered `SurfaceView`. It is stable across
  captures, survives `am force-stop` and survives a device reboot, so every test for "is this
  transient" says no; and it varies between builds because the garbage is whatever was in memory,
  so the first clean screenshot of a session reads as a *baseline* and the next one as a regression
  you just introduced. Two builds went into chasing it, and what settled it was reverting the change
  and finding the artefact **worse** on the untouched build. This is the same territory as
  *"`:android:ink`'s instrumented tests fail on x86_64 and pass on the tablet"*: use the emulator to
  ask whether a control is reachable, and **never to judge how ink or paper looks**. When a
  screenshot suggests a rendering regression, revert-and-compare before reading a single line of
  drawing code.
- ⛔ **A saved Element could not be added to a note, and the half that was broken was the *panel*,
  not the stamp** (M8 follow-up 8). `ObjectLayers` reloaded `elements/index.json` on a
  `LaunchedEffect` keyed on `capturedArea` — which `onSaveElement` clears on its **first line**,
  before the coroutine that writes the file has run — so the panel re-read the index it already had.
  Then nothing moved again, because the other half of the key, `elementsOpen`, was set to `true`
  *after* the save and `true` was what it already was. The panel went on saying *"Nothing saved
  yet"*, and a tool whose two states are "drag to capture" and "tap to stamp what is armed" has, with
  nothing armed, no way to put anything on a page. ⚠️ **A closed panel hid it entirely** — saving
  with the library shut does flip `elementsOpen`, so the flow that works is the one nobody uses, and
  the flow that fails is *open the library, see it is empty, capture something to put in it*. Two
  habits: **key a reload on the state that changes when the work finishes, never when it starts**;
  and when a feature "does nothing", find out which of *produce* and *display* is broken before
  touching either — stamping here was correct all along and confirmed on the emulator before a line
  was changed.
- ⚠️ **A callback that carries both a live preview and the final value wants its two branches written
  separately** (M8 follow-up 8). `PageOverlay.gestureFor` drew `onPreview(box)` on *every* call from
  `boxGesture`, and `boxGesture` ends with `onBox(null, false)` — clear — followed by
  `onBox(box, true)` — report. So the outline was re-armed one statement after being cleared, and a
  blue capture rectangle stayed on the paper over the diagram it had been drawn round until the note
  was closed. The preview belongs to the gesture; the reported value belongs to the caller.
- ⛔ **A shape's bounding box was its *centre line*, so an axis-aligned line had no draggable area
  at all** (M8 follow-up 10). `DryShape.boundsOf` measured `outline.flattened()`, and a straight
  segment's outline is **two points** — so the page box of a horizontal line had height exactly 0
  and a vertical one width exactly 0, while a **diagonal** one is a proper rectangle. Then
  `SelectionController.begin` chose `Mode.MOVE` with `containsWithin(x, y, 0f)` — **zero slop** —
  which no degenerate box can satisfy, so the press became a lasso: the line did not move *and* the
  lasso cleared the selection. The user's report is the exact shape of it: *"if i draw a line
  vertically instead of diagonally or horizontally there seems to be problems"*. Two things hid it
  for four milestones. A box a hairline too small still **culls** correctly, so until a *gesture*
  was decided by `pageBounds` an exactly-zero extent was indistinguishable from rounding. And
  `tappedAt` and `hitBy` both already add `widthPt / 2` — `boundsOf` was the one sibling that did
  not, which is this file's oldest habit arriving again: **grep for the other implementations of an
  invariant, not for the symptom.** ⚠️ And the regression test **passed against the broken build**
  first: it grabbed the line at its exact centre coordinate, and `xMin <= x && x <= xMax` with
  `xMin == xMax` is *true* for a press on the hairline — the fixture was the only thing in the
  system that could hit one, which a finger cannot. Grab beside the centre line, and check the test
  goes red both ways.
- ⛔ **`pruneFullyErased`'s "not pruning yet" was "never pruning"** (M8 follow-up 10).
  `pruneIfAffordable` skipped a candidate whose estimate exceeded the remaining budget, and the
  estimate is `inputs × 3 × capsules` against `PRUNE_BUDGET = 200_000`. The region is **append-only**
  (`SetErasedRegion` is absolute, rule 8, and nothing compacts it) and `inputs` is fixed, so a
  stroke once over the bar is over it on **every future pass, forever** — and the bar is low: a
  200-sample stroke needs ~333 capsules, one second of scribbling at 240 Hz is ~240. A skip is only
  a deferral if the cost can *fall*. **When a budget rations work by an estimate, ask whether the
  estimate can ever decrease; if it cannot, a skip is a permanent exclusion and needs a forced
  candidate per pass.** The cost is not the prune: a fully erased stroke is invisible and still buys
  a `saveLayer` + `DST_OUT` in every page record, and `DryStrokesView` re-records the whole page
  after any change of scale — so it is billed again on every pinch, which is *"when there are a lot
  of erases when you zoom in and out it seem to lag"*.
- ⚠️ **You cannot pinch from `adb`, and the zoom chips are not the same code path** (M8 follow-up
  10). `/dev/input` is root-only on a Play emulator image (`adbd cannot run as root in production
  builds`), so `sendevent` multitouch is unavailable and `input` is single-pointer. Driving zoom
  with the `FIT PAGE` / `100%` chips changes the scale but delivers none of the ~60 events/second a
  real pinch does. A dose-response over the erase load — same 36 strokes, 6 → 330 scrubs, journal
  15 KB → 216 KB — reported **zero dropped frames** at every level, which is a statement about the
  instrument and the page size (36 objects against the 1,121 the re-record cost was measured at),
  not about the bug. **A performance claim that only reproduces on a real page needs the tablet;
  say so rather than reporting the emulator's silence as a refutation.**
- ⚠️ **The emulator's USB-debugging dialog ignores `input tap` and is keyboard-only** (M8 follow-up
  8). `input keyevent 61` to move focus, `66` to press; the cycle has **three** entries — checkbox,
  Cancel, Allow — so two tabs from the checkbox is *Allow* and three is back to the start. Pressing
  *Cancel* by miscounting revokes the key and the next connection prompts again, which presents as
  "the dialog will not go away" and can eat twenty minutes. Related: `dev.inkstone` **ANRs on
  startup** on a cold emulator (*"failed to complete startup"*, killed at ~20 s, no stack trace,
  ML Kit and Firebase dex verification in the log); `adb shell cmd package compile -m speed -f
  dev.inkstone` fixes it and is worth running before reading it as an app bug.
- ⛔ **A cache budget written as "about N entries" is a claim about an entry's size — check it**
  (2026-10, `docs/IMAGES-PERF-2026-10.md` §2). `PageAssetCache` held *"48 MB — about a dozen images
  at M8c's 2,048 px cap"*; a 2,048 × 1,536 photograph is 12 MiB decoded, so it held **four**, and on
  the physics note seven of eleven slides drew the crossed missing-picture box. A cache that also
  re-decodes everything on every revision turns a too-small budget into a thrash. The picture cache
  now decodes only what is drawn, never evicts the page on screen, and downsamples a page that does
  not fit rather than showing part of it (`PictureCache`).
- ⚠️ **Anything that loops over "the paper" is unbounded since the canvas became infinite.**
  `TemplateBackground.drawDots` was one `drawCircle` per dot, unculled, budgeted at *"a Letter page …
  about 1,400"*; the physics note's grown paper is 5,364 × 2,376 pt — 39,000 circles per dry-layer
  re-record, 14× the cost of every object on screen. It is one shaded rectangle now. Grep for loops
  over `page.widthPt` / `heightPt` before believing a per-page cost is small.
- ⚠️ **Appending above the top fractional key must step, not bisect.** Bisection towards the end of
  the alphabet grows a key by a character every ~6 strokes — linear in a page's lifetime, quadratic in
  its log — and the physics note's keys reached 852 characters (2.2 MB of a 14.8 MB page). Stepping
  costs a character per ~61. An all-`z` key cannot be undercut, so long keys already on disk stay long
  (SPEC §4.3).
- ⛔ **A stopped activity is *destroyed* whenever a GC leaves the heap above ¾ of its limit, and a
  materialize in `onStop` was exactly that** (2026-10, `docs/NOTE-STAYS-LOADED.md`). ActivityThread's
  GC watcher calls `releaseSomeActivities` after any collection that ends above `3/4 × maxMemory()`
  with an activity recently changed, and the system destroys every stopped activity with saved
  state: `wm_destroy_activity … low-mem`. It is not the low-memory killer and not a crash. It is an
  ordinary destroy, so the view model is cleared, the editor closed, and coming back reopens the
  whole note. The old `onStop` materialized whenever there was unsaved work, which put a whole-bundle
  encode in the very window the note had become a candidate. On the emulator, one stroke and Home
  took the heap from 96 to 149 MB against a 144 MB line, the note was destroyed 1 ms after the GC,
  and coming back died with `OutOfMemoryError`: the destroyed editor's queued materialize still held
  its copy of the note while the new one loaded. Opening the physics note alone peaked at 143–163 MB
  over two opens, and at 163 the *library* behind it was destroyed. **`low-mem` in that event means the app's own
  heap, not the device's RAM** — read the reason before blaming the killer. Now: `largeHeap`, a
  materialize only on a finishing stop, none in *Update them all*, and the host's own sessions off
  the UI thread.
- ⚠️ **A pass-through that does not change thread runs on whatever thread called it, and a Compose
  scope is the UI thread.** `NoteSessionHost.countElementCopies` and `updateElementCopies` opened and
  folded a whole note in their own-session branch with no `withContext`. Every other caller of that
  pattern is a WorkManager job, so it had always run on a worker. The Element editor's Done called
  it from a `rememberCoroutineScope`, and the tablet's `OutOfMemoryError` landed on
  `AndroidUiDispatcher`. A suspend function that does heavy work must pick its own dispatcher. Never
  trust the caller to have picked one.
- ⚠️ **Building a page's strokes in parallel did not shorten the open on the emulator**, and the
  `DecompressedBytes` entry above is why: its 32 KB per stroke goes through ART's large-object
  allocator under one lock, so four workers spent most of the phase waiting on it and the collector
  (~6 CPU-seconds over a 9–11 s phase). `PreparedPage.build` is kept — it is exact
  (`PreparedPageParallelTest`) — but "parallel" is not a speed claim until it is measured on the
  tablet's release build.
- ⚠️ **Ink reports a stroke finished after the pen lifts, and the next stroke can begin in
  between.** It sometimes reports two strokes in one call. So anything latched at the down event
  "for the live stroke" may belong to the wrong stroke by the time `onStrokesFinished` reads it.
  `InkSurfaceView` kept the start time, the ruler flag and the pen kind in single fields, and the
  KDoc said *"this surface never has two at once"*. That is true of *live* and false of
  *finished*. As a result:
  - M12's timeline and the handwriting ledger got the next stroke's start time;
  - a stroke finishing after a quick change of pen could be stored as the next pen's kind.

  Found by reading the ledger after three `input stylus swipe`s: two strokes at the same
  millisecond. All three are now kept per stroke, keyed by ink's own stroke id (`StrokeStarts`).
  The undo bracket has the same shape, and its KDoc already accepts that two quick strokes may
  share one entry.
- ⚠️ **The library index cannot tell you a note is gone; only the files can** (handwriting
  synthesis). The learner withdrew a note's learned words once the index no longer listed the note.
  But the index is a cache, rebuilt from `notes/` when it is lost (SPEC §13.1), and an empty or
  half-rebuilt index lists nothing. A lost database would have withdrawn every word. The per-note
  watermarks would then have gone on saying those notes were done, so nothing would have learned
  them again. It reads `notes/` and the local trash now (`Departures`). Two rules generalise:
  - anything that deletes or withdraws because something is *absent* must ask the source of truth,
    not a cache of it;
  - forgetting a thing must forget the bookkeeping that would stop it coming back.

  Same family as *"a pruned subtree looks exactly like a deleted one"*.
- ⚠️ **Two reads of the same strokes agreeing is self-consistency, not correctness** (handwriting
  synthesis). The harvester learned a word when M7's line read and a fresh word-level read agreed.
  Both read the same strokes, so a drawing the recogniser takes for a word is taken for it twice. On
  the first real harvest, 16 of 18 learned "words" were drawings: zigzags as `m`, wavy scribbles as
  `M`, an arrow as `b`. Every test passed, because the fake recogniser read only real words. What
  separated them was the recogniser's *own* doubt. For writing it really reads, its other guesses
  repeat the answer; for a drawing they scatter (`mm` came with `mmm`, `umm`, `mmu`, `um`). So
  `Harvester.MIN_ECHOES` asks for two echoes. Before treating two signals as independent
  confirmation, ask whether they share an input. Here they shared all of it.
- ⚠️ **A median over "lines" is a median over whatever the segmenter called a line** (handwriting
  synthesis phase 3). D15 sizes a dropped phrase from the writing around it, and on the physics note
  **173 of 417** segmented lines were subscripts, arrowhead labels and dots — so a phrase dropped
  beside a 4.5 pt paragraph came out at **1.85 pt**, and every unit test passed, because a fixture's
  lines are all writing. `PageWriting.writingLines` keeps a line only with two strokes, 1.5 x-heights
  of width and 0.45 of the **stroke-weighted** median x-height (a plain median is outvoted by the marks
  it is meant to exclude). Before averaging a measurement over a segmenter's output, draw what it
  segmented.
- ⚠️ **A read-back gate reads a word, not a baseline** (handwriting synthesis phase 3, on the tablet).
  Every generated word is read by ML Kit before it is drawn, and on the tablet's 136-word profile
  every word passed first time while a `p` floated a whole descender above the line and a `t` ran into
  the next one. Both came from one learned word whose line's frame did not fit it. Legible is not the
  same as right, and a gate only rules out the first failure. The second needs its own check
  (`LetterFit`: does each lower-case letter sit where its kind belongs?). And the emulator's 19-word
  profile could not show it, because none of those words happened to be misframed. **A small profile
  is a fixture, with a fixture's generosity.**
- ⚠️ **A Python edit script on Windows writes CRLF unless told not to.** `open(p, 'w')` translates
  every `\n`, so a one-line edit makes `spotlessCheck` flag the whole file — 19 files in one go in
  phase 3, three of them files another session was working in — while `git diff` shows nothing, because git normalises
  line endings. Open with `newline=''` and keep whatever the file had.
- ⚠️ **ML Kit's model download does not fail offline — it waits.** `RemoteModelManager.download`
  stayed pending for minutes in airplane mode and completed 45 s after it was switched off, with no
  error and no bytes reported at any point. So *"the request is in flight"* is not *"it is
  downloading"*, and a moving bar over it promises progress that is not happening. The panel asks
  `NetworkStatus` and says *waiting for an internet connection* instead (`docs/M7-RECOGNITION.md`
  §5.1). Its sibling: **with animations off, an indeterminate bar draws nothing but its track** —
  *Remove animations*, and every AVD here, which run at animator scale 0. Let the words carry a
  state; the bar is decoration.
- ⚠️ **A press whose follow-up runs after a long `await` must not live in `viewModelScope`.** The
  model download did, so backing out mid-download kept ML Kit's transfer (it is Play Services') and
  lost the step after it — queueing the library's first read — while the panel had already gone.
  Same family as Back losing ink and *"`ViewModel.onCleared` cannot suspend"*: the scope dies, not
  the callback. `RecognitionModelController` is process-scoped for this.
- ⛔ **A guard that reads state before it queues drops whatever arrives mid-open — and a one-page
  note cannot show it.** `NoteEditor.withPage` returned when `session` was null, while the editor's
  own `init` promised that anything arriving during the open *"queues behind the open"*. A search
  hit's page turn is sent from `NoteEditorViewModel.init`, so it was always mid-open and always
  dropped: every hit in a multi-page note opened on page 1, with its box on empty paper. Both device
  checks of the hit had used one-page notes, where *"stayed on page 1"* and *"turned to the hit's
  page"* are the same pixels. When a feature selects among N things, check it with N > 1.
  (`docs/LINE-SEGMENTATION-V2.md` §4.1.)
- ⛔ **A segmenter that knew it could not read a page skipped it, and nothing said the page was
  unread** (line segmentation version 2). M7's `LineSegmenter` saw columns on the user's physics mind
  map and returned `MULTI_COLUMN`: the main note of the library had no searchable word for two
  milestones, filed as *"M7 recognises nothing on the physics note"*. A page it *failed* to see
  columns on was worse, read straight across the gutter, because the clustering had no horizontal
  limit. **A rule meant to keep one small stroke attached is a chaining path in union-find**: the
  mark rule that kept an i-dot on its letter joined a descender from one line to an ascender from
  the next. Strokes like that attach as leaves now, after the lines exist.
  `docs/LINE-SEGMENTATION-V2.md`.
- ⚠️ **Per-page recall is the wrong instrument for line segmentation.** ML Kit reads a line fused
  across two columns in stroke order and mostly gets the words. Version 1's fused chemistry page
  found 48 of 56 known words and version 2 found 49. What fusing got wrong was *where*: `WordBoxes`
  spread the words over both columns, so the hit highlight and the PDF's text layer were in the wrong
  place. Measure the lines (draw them) and the boxes, not only the words.
- ⛔ **A camera move made before a refit is undone by it** (search). `InkSurfaceView.highlight`
  centred the camera on a hit when the hit arrived. That was while the note was still opening, and
  the first layout and the page load both refit to the page's width afterwards. So every tapped
  search hit opened the right note at its top, with the word off screen. The KDoc above it said a
  hit *"is the one caller most likely to be early"*. Same family as REMOTE-PDF's *"one statement too
  early"*: when a call's own documentation names the race, check the code wins it.
- ⚠️ **A debug receiver's coroutine is frozen a few seconds after its broadcast returns.** The
  cached-apps freezer suspends every thread (`cat /proc/<pid>/task/*/wchan` says `do_freezer_trap`).
  The work then neither finishes nor fails, which looked exactly like an ML Kit deadlock at a random
  line. A locked tablet freezes it at once. The debug receivers queue an expedited `DebugTaskWorker`
  instead, and must wait for the enqueue to be written, or the process is frozen first and no job ever
  appears. And WorkManager's jobs live in a namespace: `cmd jobscheduler run -f -n
  androidx.work.systemjobscheduler dev.inkstone <id>`, or *"Could not find job"*.
- ⚠️ **An emulator screenshot can be a frozen frame, and so can the console's own** (handwritten
  maths). `adb exec-out screencap` and `adb emu screenrecord screenshot` both returned the app's
  launch screen for minutes, while `uiautomator dump` showed the Write panel live and the app logged
  every action. The tell is the status-bar clock: it read 10:14 in every capture, against a device
  clock of 12:07. Two presses of the power key (`input keyevent 26` twice) made it draw again. Before
  believing a screenshot, compare its clock with `adb shell date`.
