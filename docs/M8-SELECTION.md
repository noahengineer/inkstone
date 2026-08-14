# M8a — Selection and transform

What was built, what the spec got wrong, and — per CLAUDE.md's definition of done #6 — **which
check was used for each feature**, because a green suite does not establish that anything is
connected.

---

## 1. The prompt's erase paragraph is superseded, and reading §3.5 is what says so

ROADMAP M8a asks for:

> Partial erase splits a stroke and must be expressed as `DeleteObject(original) +
> AddObject(fragment1) + AddObject(fragment2)`.

SPEC §3.5, revised twice during M1 **on the tablet**, says the opposite in as many words:

> M8a's "partial erase splits a stroke into fragments" is **superseded**. Nothing splits.

The prompt's own first instruction is *"Read SPEC.md §3.5"*, so the contradiction is discoverable
by following it. Splitting is worse on both of the counts M8a cares about:

- It **destroys the original stroke** where the area model keeps it (CLAUDE.md rule 1). Un-erasing
  becomes reconstruction rather than dropping capsules.
- The fragments are **new objects**, so a peer's concurrent `SetTransform` on the original lands on
  a tombstone and the two devices end up with different object counts. That is SPEC §13.2 row 7,
  failing — and row 7's `assertEquals(1, folded.objects.size, "…the erase fragmented it")` is the
  automated guard, which is why that assertion must not be weakened.

ROADMAP has been corrected. The prompt itself is kept verbatim with a warning above it rather than
edited, because the next milestone's prompt may have the same shape and the lesson is the reading
habit, not the sentence.

**What M8a actually owed erasing** is in §3 below, and it only exists because things can now move.

---

## 2. Where the decision lives, and why the ops are stamped in the ink module

Every op M8a writes is decided by `SelectionEdit` / `SelectionGesture` in **`:core:model`** —
platform-free, iOS-linkable, and reachable from a JVM test. That is CLAUDE.md's *"hoist the
decision into a pure function over plain data"*, and it is also what let SPEC §13.2's rows 3, 4 and
7 stop building their own ops (§4).

The *stamping* — minting the op id and the Lamport clock — happens in `:android:ink`, on the UI
thread, in `SelectionController`, and it was the one real design choice in the milestone.

The alternative considered was routing a finished gesture up to `NoteEditor` and building the ops
there against the fold, the way `setTemplate` does. It was rejected for the reason
`PageOpRecorder`'s KDoc already gives about strokes:

> Ids and clocks are assigned on the UI thread, at the moment of the edit. They have to be: the
> Lamport order is the order the user did things in, and assigning clocks on a worker would let two
> edits swap places depending on how the encoder was scheduled.

`NoteEditor.withPage` launches a coroutine. A lasso-move routed through it would take its clock
inside that coroutine, so a stroke drawn immediately afterwards could sort **before** the move —
silently, sometimes, and only under load.

The composition `gesture × current` therefore reads the current absolute matrix out of
`StrokeStore` rather than out of the fold. That is sound for exactly the reason erasing has relied
on since M1: the store is not a guess, it is the fold's own state, and `StrokeStore.applyOps` is
the single door a load, an undo, and a peer's ops all come through. `SetErasedRegion` already
carries the store's whole accumulated region as an absolute value. If anything ever changes the log
without telling the store, both features break together — which is the right coupling, because one
of them is visible.

**A drag previews and only the release writes.** `StrokeStore.previewTransform` moves the objects
per frame and emits nothing; `commit` runs once, composing the finished gesture against the
transforms captured when the finger went **down**. That is what "a resize writes the resulting
matrix, not a delta" means in practice, and why it composes against the start rather than against
the last frame.

---

## 3. The erased region moved into the object's own space

Before M8a every transform was the identity, so page space and an object's own space were two names
for the same numbers. Once a stroke can move they are not, and the old text was silently ambiguous.
Both readings fail without an error:

| If capsules were… | Symptom |
|---|---|
| page space | the hole stays behind when the stroke is dragged away |
| page space, and coverage tested there | the eraser passes through a moved stroke without touching it |

The eraser's swept segment is now mapped through the object's inverse transform before it becomes a
capsule, and the tip radius divided by the transform's linear scale (`sqrt(abs(det))`). **Nothing
migrates**: every capsule written before M8a belongs to an object at the identity, where the two
spaces agree exactly.

Two honest limits, both recorded in SPEC §3.5: the radius is approximate under a non-uniform scale
(a round tip becomes an ellipse and a capsule cannot describe one — bounded by the aspect ratio,
and destroying nothing since erasing destroys nothing), and a degenerate transform has no inverse,
which is why `SelectionEdit.transform` refuses to write one at all.

---

## 4. Rows 3 and 7 now test the app, and row 3 could not previously fail for its own reason

`MatrixOps.transform` / `transformAtCounter` / `setStyle` build their ops through
`SelectionEdit` — the same function `SelectionController` calls, with the same arguments. A row that
builds its own `SetTransform` is testing the row (CLAUDE.md: *"distrust a fixture that is more
generous than production"*).

**Row 3's gestures were `Matrix3.scale(s, s)`**, and a uniform scale is a scalar multiple of the
identity, so it **commutes with rotation**. The composed-instead-of-resolved failure the row exists
to catch would have produced a matrix identical to the correct answer. It is a squash
(`scale(2, 1)`) against a quarter-turn now — which do not commute — plus an `isSkewed()` assertion,
the signature SPEC §4.2's worked example predicts.

**Row 7** keeps its one-object invariant unweakened and gains the M8a question: the two devices'
capsules must be **byte-identical** after the merge. Neither re-expressed them when the transform
arrived, which is what "the region is in the object's own space" means for convergence — a
page-space region would have had to move, and nothing does that.

⚠️ **Row 7's erase is still hand-built and its KDoc says so.** The real capsules come from
`StrokeStore.eraseAlong`, which needs `androidx.ink`'s native coverage test, and there is no ink in
a JVM test. Row 7 proves the ops **converge**; it proves nothing about an eraser producing the
right region or about erasing a *moved* stroke. Those are `StrokeStoreSelectionTest`, on a device.

---

## 5. What was checked, and how

CLAUDE.md asks for the check used per feature, and for the distinction between *written*,
*automated*, *automated against the real dependency*, and *run on a device*. All four columns below
were actually run; none is a claim about a file existing.

| Feature | Unit (JVM) | Instrumented (emulator) | Driven through the UI over adb | Result read from |
|---|---|---|---|---|
| Lasso containment (majority rule, concave polygons) | `SelectionTest` ✅ | `StrokeStoreSelectionTest` ✅ | ✅ "2 selected" appeared | the toolbar's own row |
| Tap-select, and a tap into an erased hole selecting nothing | — | ✅ | ✅ | — |
| Move → one absolute `SetTransform` per object | `SelectionTest` ✅ | — | ✅ | `journal.ops`: two `"t":"xf"`, matrix `[1,0,35.86,0,1,58.63]` |
| Undo of a move | — | — | ✅ screen changed | `journal.ops`: two more `xf`, matrix back to identity |
| Resize (corner aspect-preserving, edge single-axis) | `SelectionTest` ✅ | — | ✅ right-centre grip | `journal.ops`: `[1.739,0,-264.53,0,1,0]` — x-only scale, left edge fixed |
| Rotate about the selection centre | `SelectionTest` ✅ | `StrokeStoreSelectionTest` (erase after rotation) ✅ | — | — |
| Recolour, per-object width preserved | `SelectionTest` ✅ | `StrokeStoreSelectionTest` ✅ | ✅ | `journal.ops`: 2 baseline `sty` (captured black) + 2 new `sty`, same width |
| **Undo of the first recolour** | — | — | ✅ screen changed | `journal.ops`: 2 `sty` back to `#1A1A1A` |
| Bring to front / send to back | `SelectionTest` ✅ | `StrokeStoreSelectionTest` (render order) ✅ | ✅ | `journal.ops`: two `zo`, keys `w` then `y` |
| A stroke drawn *after* a bring-to-front lands on top | — | ✅ | — | — |
| Duplicate | — | ✅ | ✅ 3 → 5 strokes, selection follows the copies | `journal.ops`: two `add` |
| Delete | `SelectionTest` ✅ | — | ✅ 5 → 3 strokes, action row disappears | `journal.ops`: two `del` |
| **Erasing a moved stroke, recorded in its own space** | — | ✅ coordinate-exact | — | — |
| Alignment guides + snap | `SelectionTest` ✅ | — | ✅ (the drag's two axes scaled differently — a snap) | — |
| Transform survives sync, merges LWW | SPEC §13.2 rows 3, 4, 7 ✅ | — | — | in-process Nextcloud + real transport |

**The emulator walk is the check that matters most**, and it is the one CLAUDE.md says catches all
six of M6's inert features. Drawing used `adb shell input stylus motionevent`, not plain `input`:
`PalmGuard` accepts only `TOOL_TYPE_STYLUS`, so an ordinary swipe draws nothing and says nothing.

One false alarm worth recording: a stroke drawn at *(1500, 1100)* did not appear, and the cause was
the **colour panel** still being open over that part of the screen — a screenshot settled it in
thirty seconds. When an adb-driven gesture does nothing, look at the screen before looking at the
code.

The walk was run **twice**: once against the first build, and again after the public API was
reshaped into `InkSelection` (seven methods on `InkSurfaceView` became one handle, to keep that
class under detekt's size limit). The second run is the one that matters — a refactor of the exact
seam a feature is reached through is precisely when it becomes unreachable, and the only thing that
would have shown it is pressing the buttons again.

### The numbers, and which half of each gate ran

- **JVM unit tests: 566 across the four modules that hold them** (`:core:model` 160, `:core:storage`
  148, `:core:sync` 187, `:app` 95), 0 failures. Per `testDebugUnitTest`; `build` runs the same
  tests again as `testReleaseUnitTest`, so "tests executed" is twice "tests written".
- ⚠️ **`--rerun` is a per-task option and does not apply to the tasks before it.** Passing it once
  at the end of a four-task command line left all four logging `UP-TO-DATE` — the exact
  green-build-that-ran-nothing CLAUDE.md warns about, met while trying to avoid it. It has to
  follow *each* task: `:core:model:testDebugUnitTest --rerun :app:testDebugUnitTest --rerun …`.
  The numbers above are from a run where all four logged neither `UP-TO-DATE` nor `FROM-CACHE`.
- **`detekt`, `spotlessCheck` and `checkCoreIsPlatformFree`: green.**
- **Guardrail 2 — the klib compile half only.** `compileKotlinIosSimulatorArm64` was forced and
  executed for `:core:model`, `:core:storage`, `:core:sync` and `:core:search`, so `Selection.kt`
  and `Alignment.kt` are genuinely platform-free. The **framework link** (`linkIosCore`) logs
  `SKIPPED` on Windows and is the macOS CI job's to prove; this is not a claim that it passed.
- ⚠️ **Definition of done #4 is NOT met.** `adb devices` lists `emulator-5554` and nothing else —
  there is no Galaxy Tab attached to this machine. Everything below was checked on the emulator,
  which CLAUDE.md is explicit does **not** substitute. Ink feel, latency and frame timing under a
  real selection drag are unmeasured.

### `:android:ink`'s instrumented suite: 26 tests, 10 failures, and none of them new

The 10 failures are the M1 `SyntheticInk` bug CLAUDE.md documents — `orientationRadians` computed
without clamping to `[0, 2π)`, which x86_64 rounding puts a thousandth outside and `androidx.ink`
rejects. All 10 are in `StrokeStoreEraseTest` and `StrokeGeometryCodecTest`, both of which build
geometry with `SyntheticInk`.

**All 11 of `StrokeStoreSelectionTest` pass**, because its strokes carry **no orientation and no
tilt** — there is nothing to be out of range. CLAUDE.md's advice is to build geometry by *drawing*,
which needs a window and a `MotionEvent` pump that lives in `:app`; avoiding the specific bad field
gets the same independence from inside this module.

---

## 6. Things a future milestone should not reintroduce

- **`StrokeStore` owns the top z key, not `PageOpRecorder`.** The recorder used to advance a
  counter of its own, which `SetZOrder` could silently overtake — so bringing a stroke to the front
  made the *next* stroke drawn land underneath it, on screen and in the published PDF's annotation
  order, with nothing to connect the two actions. The store is the only object that sees every
  writer of a key.
- **`NoteEditor.reconcile` must diff all four registers.** It compared presence and the erased
  region only. `SetTransform`, `SetStyle` and `SetZOrder` produce an **empty diff** there, so your
  own undo would have changed nothing on screen and a peer's move would have arrived invisibly —
  with every test green, because the log is right and only the screen is wrong. This is M6's
  `setPageMeta` bug, in the same method that was extended to fix it then.
- **`DryStroke` caches bounds twice, on purpose.** `bounds` is object space (the erased region, the
  eraser's coverage test, `isFullyErased`); `pageBounds` is page space (culling, the lasso, the tap
  test). Asking the wrong one is invisible until something is moved.
- **`InkStrokeOutliner` applies the transform to `contours` *and* `centreline`.** Doing one and not
  the other publishes a PDF whose appearance stream and whose `/InkList` disagree about where the
  stroke is — and Recovered mode reads `/InkList`, so a recovered note would put the ink back where
  it was before the user moved it.
- **The `PagePainter` object matrix goes into `strokeToScreenTransform` too.** It is not a drawing
  transform; its linear component sizes the antialiasing feather. Omit it and a stroke scaled to
  300% is feathered as though still at 100% — which reads as "the ink went blurry when I resized
  it" and does not look like a transform problem at all.
- **Z-order is within the highlighter band.** `StrokeStore` keeps ink and highlighters as two
  bands (SPEC §3.4 — a highlighter tints what it covers), and `(z, id)` orders *within* each. So
  "bring to front" cannot lift a pen stroke above a highlighter. That is a product rule, not an
  oversight, and it matches what the published PDF does.

---

## 7. Not done

> ⚠️ **Amended by the M8 follow-up audit (2026-08-13), which found this section incomplete in
> both directions.** Missing from it: the **rectangular marquee** — ROADMAP says "freehand AND
> rectangular", `Lasso.rectangle` existed with only test callers, and nothing here said so. It
> is wired now: a **Box** chip beside Select, read at gesture start, settled through the same
> `Lasso.rectangle` the chrome draws (`SelectionControllerShapeTest` covers it). Also fixed in
> the same pass: the second bullet below aged badly when M8b made **shapes** selectable —
> `restyle` read `strokeOf` and fell to `DEFAULT_STYLE` (recolouring a shape reset its width to
> 3 pt; set-width recoloured it black), and `duplicate` silently dropped shapes. Restyle now
> baselines off `DryShape.effectiveStyle()` via `objectOf`, duplicate routes shapes through
> `ShapeEdit.duplicate` ops, and the alignment snap tolerance is now divided by the camera
> scale as `Alignment.kt`'s KDoc always claimed the caller did.

- **Cut / copy / paste across pages or notes.** ROADMAP lists "cut/copy/duplicate/delete";
  duplicate and delete are done and are what the acceptance criteria name. A clipboard that
  survives a page switch needs somewhere to put geometry that is not the page's log, which is a
  storage decision and not a selection one.
- **Selecting images, text boxes and shapes.** The op path is type-agnostic — `SelectionEdit`
  works on `SelectedObject` and never asks what an object is — but nothing creates a non-stroke
  object until M8c, so there is nothing to select and no way to test that there is. `StrokeStore`
  is a stroke store; hit-testing an image is M8c's, and it is a few lines of rectangle containment
  when the objects exist. *(⚠️ Overtaken by M8b: shapes exist and are selectable — see the
  amendment above for the two action paths that had to catch up.)*
- **The rotate grip is on an axis-aligned box.** After a rotation the box is the bounding box of
  the rotated content, not a rotated box. Rotating 45° and back does not restore the box's size;
  the ink returns exactly, because the transform is absolute and composed.
