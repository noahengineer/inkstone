# M8 follow-up 2 — performance, the two-finger undo, and the stale selection outline

Three complaints from continued tablet use, verbatim:

> i really end up having quite a few performance problem its really ends up being quite bad
> also the 2 fingers to undo feels very inconsistent and if you undo something selected the
> selection outline does not update

Same pattern as `docs/M8-UX-FIXES.md`: every complaint was real, every one had a mechanism, and
the vaguest one ("performance") turned out to be several distinct mechanisms stacked. This records
what each was, what was fixed, and — for the biggest one — what was deliberately **not** fixed in
this pass and why.

---

## 1. "The 2 fingers to undo feels very inconsistent" — four gates, each with a hole

A legitimate two-finger tap had to survive four gates, and each could silently kill it. The
inconsistency was the *union* of four different failure conditions, none of them visible:

- ⚠️ **The palm guard swallowed the tap whenever the pen was near the glass.** The S Pen reports
  hover from ~1 cm out; `onGenericMotionEvent` feeds every hover event into `PalmGuard`, and
  `onPointerDown` consulted the guard *before* feeding the tap detector. Holding the pen over the
  page — the normal posture when tapping with the other hand, and inevitable right after writing —
  meant the detector never saw the fingers at all. This was the dominant cause. **Fix:** the tap
  detector is fed before the guard (`InkSurfaceView.onPointerDown`). The guard exists to stop a
  palm *panning* or *drawing*; a tap moves nothing and writes nothing, and the detector's own
  slop and timeout are what reject a palm — a palm slides or lingers, and two clean sub-slop
  contacts that lift inside 400 ms are a person asking for undo. The camera, the ruler and finger
  drawing stay behind the guard exactly as before.
- ⚠️ **Anchors were keyed by pointer *index*, which compacts when the first finger lifts.**
  A MOVE arriving in the 10–80 ms between the two lifts compared the surviving finger against the
  *other* finger's anchor — centimetres of "travel", far past slop — and disqualified the tap.
  Whether it fired depended on which finger lifted first (~50%) and on digitiser timing: the
  classic feels-random failure. Three fingers were worse (two lift gaps, ~2/3 of orders at risk),
  so redo failed more than undo. **Fix:** `MultiFingerTapDetector` keys anchors by pointer id and
  looks pointers up with `findPointerIndex`, the same discipline `CameraGestureTracker` already
  had. `MultiFingerTapDetectorTest` replays the exact lift orders.
- **The 300 ms timeout spanned the whole bracket, first DOWN to last UP.** Landing two fingers
  staggers by tens of milliseconds, and a person who has found the gesture flaky taps *slower* —
  so the deadline hit exactly the deliberate taps. **Fix:** the timeout re-latches on each
  POINTER_DOWN (now 400 ms from the *last* finger landing to the last lift). What separates a tap
  from a resting hand is how long the fingers stay down once they have all arrived.
- **The disqualification bookkeeping sat behind the palm-guard/camera gate in `onMove`**, so a
  two-finger *pan* whose moves the guard was rejecting was never disqualified — and a quick flick
  could lift into a *spurious* undo: the page did not move and the last stroke vanished. The
  misfires were bidirectional. **Fix:** `taps.onMove` runs before every gate; movement always
  disqualifies.
- Also fixed while in there: with finger drawing enabled, every move of a finger-drawn stroke
  stamped `PalmGuard` as *stylus* activity, blacking out all finger gestures (undo tap, pan) for
  300 ms after each stroke. `stylusGestureMove` now stamps the guard only when the live stroke is
  really a stylus's (`activeStrokeIsStylus`).

**Known limitation, deliberate:** with `fingerDrawingEnabled` on, a two-finger tap still draws a
dot with the first finger rather than undoing — the first finger starts a stroke before the second
lands, and converting a nascent finger stroke into a gesture when a second finger arrives (what
Samsung Notes does) is a behaviour change on the capture path that deserves its own pass. The
S Pen posture this device is for does not hit it.

## 2. "If you undo something selected the selection outline does not update"

`SelectionController` caches the selected ids and their box (`bounds`), refreshed only by its own
gestures — and `InkSurfaceView.applyOps`, the door every undo, redo and peer op comes through,
never told it the store changed. The overlay kept drawing the old box, `begin` *hit-tested the
next pen-down against the phantom box* (so dragging inside it moved the real objects at a
distance), the toolbar kept saying "N selected", and the colour picker stayed captured by the dead
selection — `InkToolbar` reroutes swatch taps to the selection while `selectionSize > 0`, and
recolouring dead ids is a silent no-op, so the pen colour could not be changed at all until the
phantom selection was cleared.

**Fix:** `SelectionController.onStoreChanged()` — prune ids the store no longer has, recompute the
box, fire `onChanged` only when membership or box actually changed (so an erase drag *near* a
selection costs nothing per sample). Called from the three doors that bypass the controller's own
gestures: `applyOps`, the eraser callback (a flipped tip erases whatever the toolbar says, so it
can delete a *selected* stroke), and `clearPage` (which now clears the selection outright, the
line `loadPage` already had). `applyAsGesture` also gained the `refreshBounds()` that `commit`
had and the restyle path lacked — thickening a selected stroke used to leave the dashed frame
snug round the old width.

## 3. "Quite a few performance problems" — what was found, fixed, and deferred

Fixed in this pass (all CPU-side, all mechanism-verified):

- **`PageLog.applyAll` rebuilt the entire folded page once per op, on the main thread**
  (`NoteEditor`'s work scope is `Dispatchers.Main.immediate`). A selection gesture emits one op
  per object, so moving a large lasso selection paid one full materialise-and-sort build *per
  selected object* per autosave tick. It now absorbs the batch and builds once (re-folding at
  most once); `PageFoldTest` pins batch-vs-per-op equivalence, including the late-op and
  stale-snapshot branches.
- **`FoldedPage.find` was a linear scan**, and five of `Undo.inverseOf`'s seven branches call it —
  O(gesture × page) id compares per undo computation. It answers from a lazily-built map now.
- **Page load tessellated every restyled stroke twice.** `dryStrokeFor` decoded with the captured
  brush (tessellation #1) then `restyle` rebuilt the mesh (tessellation #2), inline on the main
  thread inside the editor mutex, per page turn. The style now goes *into* the decode
  (`StrokeGeometryCodec.decode(geometry, brushes, style)`), and the captured-vs-style brush rule
  lives once on `InkBrushFactory`, shared with `DryStroke.restyle` so the two paths cannot drift.
- **Selection drags ran at input rate (~240 Hz), not frame rate, and fanned five callbacks into
  Compose per input event.** Per event: the alignment-guide search rebuilt a boxed snap-target
  list for every object on the page, every selected object was re-transformed (~13 allocations
  each), and `:app` recomputed `shapeCounts` (O(selection) + list alloc) and `soleImage` — for
  values that cannot change while the finger is down. Now: `extend` only records where the finger
  is; a Choreographer callback applies guides + preview once per drawn frame (flushed
  synchronously at `end`, so the committed matrix is the last previewed one); the snap targets
  are precomputed once per gesture (`AlignmentGuides.AlignmentTargets`); and the controller has a
  light `onPreview` callback (repaint only) beside the full `onChanged` (membership changes).
- **`pruneFullyErased`'s budget under-counted by ~100×.** It charged the *capsule count* while
  `isErasedAway` runs points × capsules, so one eraser pen-up after scribbling over a dense,
  heavily-erased area froze the UI inside the very bound built to prevent it. The budget is now
  denominated in point-tests, estimated by the object itself (`DryObject.eraseCoverageCost`) with
  the same cheap gates `isFullyErased` opens with, and a stroke too big for the remaining budget
  is skipped rather than ending the pass.
- **`StrokeStore.clear()` was O(N²)** (per-element `ArrayList.remove`); it bulk-clears now.
- **The eraser's "no allocation per sample" scratch was allocated twice per sample**
  (`LocalSweep` constructed inside `applyErase`); it is a store field now.

⚠️ **Deferred, and why — the dry layer itself.** Every pan/zoom frame re-records the whole
visible page (one `CanvasStrokeRenderer.draw` per visible object) and pays a
`canvas.saveLayer` + `DST_OUT` composite per partially-erased object per frame. That is the
structural cost that grows with page fullness and erase history, and the fix is a cached
render — a page-space RenderNode re-recorded on revision/zoom change and replayed under the
camera transform, plus a cached punched composite per erased object. It is deferred because it
is a rendering redesign with real visual-correctness risk (the antialiasing-feather uniform is
scale-dependent — see `DryStrokesView`'s KDoc for the device bug that class already records —
and a view-sized `LAYER_TYPE_HARDWARE` texture clips pans, so the naive version shows blank
margins). It needs its own pass with frame-time measurements on the tablet before and after.
Note for that pass: `saveLayer` bounds are already intersected with the clip by Skia, so
"intersect the layer with the viewport" buys nothing — the win is caching the composite, not
shrinking it.

Also deferred, smaller: `SetErasedRegion` re-copies a stroke's whole lifetime capsule history on
the UI thread at every erase pen-up (the history never coalesces and never shrinks); the lasso
settle allocates two `PagePoint`s per outline vertex of every candidate in one frame.

---

## 4. What was checked, and how

| Claim | Evidence |
|---|---|
| JVM suite green including new `PageFoldTest` batch tests | **879 tests, 0 failures**, `testDebugUnitTest` all modules, `--no-build-cache`, result-file timestamps checked (877 before this pass + the 2 new) |
| Tap detector survives both lift orders, staggered landings; rejects pans and rests | `MultiFingerTapDetectorTest` (7 tests) on emulator-5554 via `am instrument` |
| Selection prunes dead ids / box follows a `SetTransform` | `SelectionControllerShapeTest` (8 tests) on emulator-5554. ⚠️ The transform test's first version expected *old-box-plus-delta* and the store correctly failed it — `SetTransform` is an **absolute** register (rule 8), so the box lands where the new matrix says. The test now asserts that. |
| Undo of a selected shape drops the outline, grips and toolbar rows, end to end | adb walk on **emulator-5556**: draw-and-hold → `snapped to rectangle`, grips + "1 selected" + shape row on screen (screencap) → toolbar Undo → screencap shows clean page, dump (non-empty, 39 KB) has no "selected"/"1 shape" text, `0 strokes`, `rev 5`, Undo greyed / Redo lit |
| Plain-stroke undo unbroken by the dispatch reorder | same walk: freehand stroke → Undo → `0 strokes`, `rev 3` |
| Not checked: the physical Galaxy Tab | `adb devices` reports it `unauthorized`; definition-of-done #4 is unmet — the hover-swallow fix, the 400 ms timeout, and every drag-feel judgement still owe the tablet a hand. The two-finger gesture itself cannot be injected over `adb input` at all (single-pointer), so on-device it is the instrumented tests plus a thumb. |

Walk notes, for the next session that drives this over adb:

- **emulator-5554's screencap came back as a garbled framebuffer** (smeared tiles; the PNG parses
  fine, the *pixels* are wrong) after some minutes of uptime, and byte-identical PNGs for
  minutes-apart captures were the tell. The walk moved to emulator-5556. Same lesson as the 0-byte
  `uiautomator` dump: check the instrument before believing what it shows.
- **A synthetic draw-and-hold must repeat its final position.** `input stylus motionevent` sends
  one sample per invocation; a single MOVE to the hold point arrives as *travel*, the dwell
  detector answers `NOT_HOLDING`, and no timer is posted — the snap then never fires, exactly as
  `ShapeSnapDetector.sample`'s KDoc says. Two MOVEs at the hold point (±1 px) settle it; then the
  `sleep` covers the dwell and the timer path fires.
- The app **ANR'd once at cold launch** on emulator-5556 (`Reason: No response to onStartJob`,
  load average 8, CPU pressure 43% — the emulator was saturated installing + ML Kit model
  downloads). It recovered on "Wait", a matching trace exists from the day before, and no
  `ApplicationExitInfo` ANR is recorded. Reads as emulator load, but the startup path has never
  had the perf audit this pass gave the editor — the perf-app lens is still owed (see §3).
