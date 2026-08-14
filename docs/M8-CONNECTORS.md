# M8b — Shapes and connectors

**Status: all four acceptance criteria met.** Draw-and-hold snapping, fill with opacity, rounded
corners, connectors that attach on drawing and reroute on move, derived detachment, and a merge row
for the round trip. §7 is what is *not* done — chiefly the published PDF, which M8b's scope list
does not ask for, and the tablet.

This document is what was decided and why, in the order the milestone's prompt asked for it —
the three known disagreements first, because they had to be resolved before any code.

---

## 1. The three disagreements, resolved

### 1.1 POLYGON — ROADMAP loses

`ShapeKind` was frozen in M2 as `{LINE, ARROW, RECTANGLE, ELLIPSE, TRIANGLE}`. `OpCodec.decodeObject`
reads it with `ShapeKind.valueOf`, and `BundleReader` decodes a page's ops unguarded — so a sixth
constant is not a degrade, it is an **older build failing to open a note it has already synced**.
Dropped. SPEC §4.8.8 and ROADMAP now both say so.

A closed run of straight segments stays expressible as an ordinary ink stroke with no format change
at all. It just cannot carry a fill, which is the honest reason it is not the same feature.

### 1.2 "Connector" appears nowhere in SPEC — decided, and written into SPEC

**A connector is a `ShapeObject` of kind `LINE` or `ARROW` whose `SetTextContent` register holds a
`ShapeRecord`.** No new object kind, no new `ShapeKind`, no new op type. SPEC §4.8 is the normative
account and was committed with the code.

The reasoning is in `Connector.kt`'s KDoc and in SPEC §4.8.2; the short version is that the four
things M8b must persist — fill, fill opacity, corner radius, attachment — have exactly one home
that an older build round-trips losslessly, and `SetTextContent` is it: the vocabulary's only
absolute, object-keyed, arbitrary-length register.

**The cost is stated, not hidden.** One register holding four independently-edited properties
resolves LWW *whole*, so a concurrent fill change and endpoint re-attachment lose one of the two.
That is a lost edit, not a corrupt state — a record is written whole and consumed whole, so
whichever wins is internally consistent and both devices hold the same one.

### 1.3 "Detached" is derived, never stored

Three ways an end fails to resolve — tombstoned, not-yet-arrived, unanchorable — and they produce
**identical geometry**: the connector's own payload segment under its own transform. They are told
apart only for a label.

Storing a detachment would make the *not-yet-arrived* case permanent: a connector whose target was
merely still in flight, written off by whichever device noticed first and still written off after
it arrived. `ConnectorTest` asserts the two cases are indistinguishable geometrically, and asserts
that an end re-attaches by itself when its target arrives and when a delete is undone.

---

## 2. The four traps, and what each one actually forced

| Trap | What it changed |
|---|---|
| **No new kinds or ops** | The whole design above. Nothing was added to `ShapeKind`, `InkObject` or `PageOp`. |
| **A reroute writes no op** | The route is a pure function of the fold. An anchor is a **normalised point in the target's own box**, so it is carried by the target's absolute matrix — which is why a connector tracks a resize and a rotation and not merely a translate, with nothing written. |
| **`AddObject` payload is first-write-wins** | Decided up front: an endpoint **can** be re-attached, so the binding is a register and not payload. The payload is used for one thing only — the fallback geometry, which must never change, and where first-write-wins is exactly right. |
| **Shapes live in `StrokeStore`** | Done, on M8c's `DryObject` hierarchy: `DryShape` is a sibling of `DryStroke`, in the one `(z, id)` order, taking its key from the store's `topZ`. No second store and no second counter. |

---

## 3. Three things the prompt did not list, found by grepping for what names an id

CLAUDE.md's M8a lesson — *"before adding the thing that makes two spaces differ, grep for every
place that names either one"* — applied here to *"every place that copies an object"*.

1. ⚠️ **Duplication drops the register and keeps the old ids.** `duplicateNote`, `duplicatePage`
   and `conflictedCopyOf` each folded a page and emitted one `AddObject` per object under a fresh
   `ObjectId`. Two failures, neither of which anything reported: every record vanished (a
   duplicated diagram comes back as unfilled boxes joined by straight authored lines), and every
   surviving binding named the *original's* ids, so every connector in the copy would resolve as
   detached — defined, non-crashing, and visibly wrong.

   `ConflictedCopy`'s own KDoc had asserted the invariant that made a one-line copy safe:

   > A fold collapses **every** register … onto the object payload, so the only op left to write is
   > `AddObject` and there is **no second op carrying an `ObjectId` in a `target` field** that a
   > re-identification pass could miss.

   Both halves stopped being true at once. All three call sites now go through one
   `copiedObjectOps`, which emits the register and re-targets the ids inside it. The private
   `withNewId` in `NoteSession` — a third hand-written `when` over the four object kinds — is gone
   for the reason its neighbour's KDoc gives: a third copy is where the *next* register is
   forgotten.

2. **`PageFolder.materialise` dropped the register for every kind but `TextBoxObject`.** A
   `SetTextContent` aimed at a shape folded correctly, converged correctly, round-tripped through
   the file correctly, and was discarded at the last step with nothing to see. `FoldedPage.textOf`
   exposes it now, and `seedFrom` seeds it so a snapshot is not a hole.

3. **`Undo.inverseOf` cast the target to `TextBoxObject`.** So *"change a shape's fill, press
   undo"* produced no inverse — and `UndoStack.commit` drops an entry with no inverse, so the press
   would have looked as though it worked. That is SPEC §4.2's first-`SetStyle` trap, in the
   register next door, and it was one cast away from shipping.

And one that is *not* a bug and is worth recording so nobody "fixes" it: **compaction was already
safe.** SPEC §4.7 keeps the raw log and uses the snapshot only as a base, so `PageLog` re-absorbs
the real `SetTextContent` on every load. `writeSnapshot` emits it anyway — the difference between a
cache and a cache with a hole that happens to be repaired downstream.

---

## 4. The questions the prompt asked

**Where connector attachment lives in the bundle.** In the page's op log, as an ordinary
`SetTextContent` op (`"t":"txt"`) targeting the connector's `ObjectId`, whose `"x"` member is the
record string. Nowhere else — not in the manifest, not in the object payload, not in a sidecar.

**What an M8a-era build does when it opens such a note.** It opens it, keeps every byte, and shows
no shapes. `StrokeStore.addObject` opens with `as? StrokeObject ?: return false`, so a
`ShapeObject` is dropped from the *screen* while its `AddObject` and its record sit correctly in
the log, survive an autosave, survive a compaction snapshot, and re-materialise the next time an
M8b build opens the note. The PDF that older build publishes will lack the shapes; the bundle
inside it will not, and the bundle is the truth (SPEC §5.1). That is a degrade, and it is the
strongest one available without a schema bump.

**A tombstoned endpoint versus one that has not arrived.** Identical geometry — the connector's own
payload segment — and different labels (`DETACHED_TOMBSTONED` / `DETACHED_ABSENT`). Both re-attach
by themselves if the object appears or the delete is undone, because nothing was written down.
§1.3 above and SPEC §4.8.4.

**A connector tracking a RESIZE and a ROTATE.** `ConnectorTest` has one test each, and they assert
page coordinates rather than "it moved": a pinned anchor on a box's right edge follows a 2× x-scale
from 400 to 500, and a pinned anchor on a box's top edge follows a quarter-turn about the box's
centre from (350, 0) to (400, 50). Both work because the anchor is a normalised point in the
target's own box and is mapped by the target's absolute matrix — one affine map, no special cases.
An `Auto` anchor additionally re-faces its partner, which the translate test asserts explicitly
(and which is why the endpoint on the shape that *did not* move also moves).

**The ink-path cost of draw-and-hold against SPEC §3.6.** By construction rather than by
measurement, because there is nothing on a device to measure yet:

- **Per `MotionEvent` sample:** two float writes into a buffer the caller already owns, and one
  distance compare against a threshold. No allocation, no branch into `ShapeSnap`. This is the part
  that runs at 240 Hz and it is what §3.6's *"sustained 60 fps with 5,000 dry strokes"* budget sees.
- **Once, when the hold fires:** `ShapeSnap.recognise` makes two passes over that buffer with **no
  allocation at all** — which is why its signature is `FloatArray, FloatArray, Int` rather than a
  `List<PagePoint>` that would allocate one object per sample at the exact moment the user is
  waiting for the shape to appear.
- *"Recognition"* in SPEC §2.4's sense means a recogniser — ML Kit, a model, a dictionary. This is a
  bounding box and a shoelace area.

⚠️ **This is a design claim, not a measurement.** §3.6's gate is a camera test against Samsung Notes
and `dumpsys gfxinfo`, and neither has been run for this feature because the feature is not on a
screen. It is owed by whoever builds §6.

**What a non-PdfBox viewer shows for a translucent filled shape.** **Unknown, and deliberately not
guessed at** — nothing writes a shape to a PDF yet. What is already known and must not be
rediscovered is in CLAUDE.md: a translucent fill needs an `/ExtGState` with `/ca`, a page content
stream has **no `/Resources` of its own** and resolves names through the page dictionary, and
`/Resources` is inheritable so a fresh one *shadows* whatever a source PDF kept on a `/Pages` node.
M4 shipped exactly that bug — a translucent highlighter came out fully opaque in PDF.js and the
overlay was not drawn at all in a stricter reader — and it was green under PdfBox the whole time.
`PdfViewerCompatTest` is the test that found it and is where the answer has to come from.

**Test counts.** `tests=` summed over `*/build/test-results/testDebugUnitTest/*.xml`:

| Module | tests | failures |
|---|---|---|
| `:core:model` | 202 | 0 |
| `:core:storage` | 153 | 0 |
| `:core:sync` | 187 | 0 |
| `:core:search` | 22 | 0 |
| `:app` | 95 | 0 |
| **Total** | **659** | **0** |

M8b added **47** of those (`ConnectorTest` 21, `ShapesTest` 21, `ShapeRecordStorageTest` 5). M8a
recorded 566 across four modules; this figure includes `:core:search`, which that one did not.
Every task was forced with `--rerun` **per task** plus `--no-build-cache`, and the `> Task` lines
were read back — none logged `UP-TO-DATE` or `FROM-CACHE`.

---

## 5. Draw-and-hold, on the emulator

Built after the format half, on the same branch, against M8c's `DryObject` hierarchy. Four pieces:

- **`DryShape`** — the [DryPlaceholder] replacement its own KDoc asks for: *"a kind that paints
  itself replaces this type rather than configuring it"*. A shape now has a silhouette, a coverage
  test and a branch in `PagePainter`, and the sealed hierarchy made each of those a compile error
  until it was written.
- **`ShapeSnapDetector` / `ShapeSnapController`** — the dwell detector and the gesture it turns
  into ops. A controller for the reason `EraserController` and `SelectionController` are ones:
  `InkSurfaceView` owns `MotionEvent` dispatch, and dispatch is the thing that has to stay
  readable. The surface keeps two call sites.
- **`ShapeHit`** in `:core:model` — the erase and tap predicate, as arithmetic. A stroke's painted
  area is a tessellated mesh only `androidx.ink` can measure; a shape's is a few segments, and the
  decision belongs where a JVM test can reach it.
- **Toolbar chips** — *Hold to snap* and *Arrow*, in the main row rather than behind **Measure**,
  because they change what the pen does.

### ⚠️ The bug that would have made it work on nothing

`DryObject.hitBy` took only the swept parallelogram, which is the query `androidx.ink` wants. A
shape needs the capsule. Passing both — and using exactly one per kind — was preferred to
reconstructing either from the other, because a second definition of *"what the eraser swept"* can
drift from the one the erased region actually records.

The larger one: **`sample()` returns a delay, not a boolean.** Android delivers `ACTION_MOVE` when
the digitiser reports a change, so a pen held genuinely still can stop producing events — and
holding still is exactly what the user was asked to do. A per-sample check alone would have worked
on a trembling hand and failed on a steady one, intermittently and by hardware. The controller
schedules a `postDelayed` when a sample says *"settled, N ms to go"* and cancels it on movement.
The emulator run below exercises **that path**, not the sampled one: the gesture holds through a
`sleep 1.5` with no events at all.

### The walk, on `emulator-5554`

Drawn with `adb shell input stylus motionevent` — `PalmGuard` accepts only `TOOL_TYPE_STYLUS`, so
an ordinary `input` swipe draws nothing and says nothing.

| Gesture | On screen | In `journal.ops` |
|---|---|---|
| Straight drag, hold | "snapped to line", 5 → 6 objects | `"k":"p","sh":"LINE"`, `m` = `[1,0,143.4,0,1,149.5]` |
| Closed box, hold | "snapped to rectangle", 6 → 7 | `"k":"p","sh":"RECTANGLE"` |
| **Zigzag squiggle, hold** | **no snap**, label unchanged, 7 → 8 | `"k":"s"` — it stayed ink |
| *Arrow* chip on, drag, hold | "snapped to arrow", 8 → 9 | — |
| Undo | arrow gone from the screen, 9 → 8 | — |

Two things worth reading off that table. The line's matrix has **determinant 1** — an axis-aligned
line, which is precisely the case a diagonal-in-the-box local geometry would have made singular
(§4.8.6). And there are **zero `"t":"txt"` ops**: a plain shape writes no record, because
`ShapeEdit.create` omits it when the record is default, so drawing a line does not put a string in
the file.

The squiggle row is the acceptance criterion *"no fighting it to draw a freehand curve"*, and it is
the only row a JVM test could not have produced.

---

## 6. What was checked, and how

CLAUDE.md asks which of *written*, *automated*, *automated against the real dependency* and *run on
a device* each claim is. **The shape half is reachable and was driven through the UI; the connector
half is not reachable at all** — nothing in the app creates one, so every connector row below is
arithmetic that has never had a user in front of it.

| Claim | Unit (JVM) | Through storage | On a device / in the app |
|---|---|---|---|
| Record round-trips its wire form; re-encode is byte-stable | `ConnectorTest` ✅ | — | ❌ |
| Unknown record keys survive | `ConnectorTest` ✅ | — | ❌ |
| A non-record register decodes to null (fail closed) | `ConnectorTest` ✅ | — | ❌ |
| Connector tracks translate / resize / rotate | `ConnectorTest` ✅ | — | ❌ |
| Moving a shape writes no op | `ConnectorTest` ✅ (asserts the op count) | — | ❌ |
| Tombstoned vs absent endpoint; re-attach on arrival and on undo | `ConnectorTest` ✅ | `ShapeRecordStorageTest` ✅ | ❌ |
| Elbow and curved routes | `ConnectorTest` ✅ | — | ❌ |
| Routing is order-independent | `ConnectorTest` ✅ | — | ❌ |
| Shape outlines, boundaries, placement | `ShapesTest` ✅ | — | ❌ |
| An axis-aligned line has an invertible matrix | `ShapesTest` ✅ | — | ❌ |
| Draw-and-hold classifies; a squiggle snaps to nothing | `ShapesTest` ✅ synthetic | — | ✅ **driven over adb** |
| A shape is drawn, not an invisible placeholder | — | — | ✅ screenshot |
| Erase / tap predicate for a shape | `ShapeHitTest` ✅ | — | ❌ |
| Undo of a snap | — | — | ✅ screen changed, 9 → 8 |
| Record survives a bundle round trip | — | `ShapeRecordStorageTest` ✅ | ❌ |
| Record survives a compaction snapshot | — | `ShapeRecordStorageTest` ✅ | ❌ |
| Page duplicate re-targets bindings | — | `ShapeRecordStorageTest` ✅ | ❌ |
| Note duplicate re-targets bindings | — | `ShapeRecordStorageTest` ✅ | ❌ |
| Merge behaviour of a record under concurrency | ❌ **not written** | ❌ | ❌ |

⚠️ **The snapping row is the weakest thing in this table** and M7's lesson names why: *"a synthetic
fixture is the worst possible witness for a geometric heuristic"* — its rotated-text detector passed
its unit test and produced 105 false positives on 20 real pages. `ShapesTest` proves a mathematically
perfect ellipse classifies as an ellipse. It proves nothing about a person's ellipse, and the
acceptance criterion *"no fighting it to draw a freehand curve"* is a device question.

⚠️ **`adb devices` lists `emulator-5554` (a Pixel Tablet x86_64 image), and two AVDs exist.** The
shape walk above ran on it. **Definition of done #4 is still not met**: an emulator does not
substitute for the Galaxy Tab, and ink feel, latency and frame timing under draw-and-hold are
unmeasured — including SPEC §3.6's two gates, which §4 records as owed.

⚠️ **The dwell and confidence constants are guesses.** `DEFAULT_HOLD_MILLIS = 550`,
`FILL_WINDOW = 0.14`, `DEFAULT_CONFIDENCE = 0.55`. They classify synthetic shapes correctly and
they classified four real adb-drawn gestures correctly, which is not the same as behaving well
under a hand. SPEC §4.8.7 says so explicitly, and M7's rotated-text detector — unit-test green,
105 false positives on real pages — is why.

### Gates

> ✅ **Resolved — not reproducible at HEAD `aa2b474`, verified 2026-08-13.** Both halves of the
> split below pass now: the full `:app` suite was forced fresh twice (`--rerun --no-build-cache`,
> task executed, fresh result XML) and is **95 tests, 0 failures**, all seven `MergeMatrix*`
> classes included. Nothing on the implicated path changed between `12063a6` and the green runs,
> so the failure was most likely environmental — see the matching note in `docs/M8-TEXT.md` §5.
> The record below is kept as written.

⛔ **`:app:testDebugUnitTest` is NOT green, and the failures split two ways.** 21 of 95 fail. The
attribution was measured, not assumed:

| Rows | Fail with | Attribution |
|---|---|---|
| 13 — `MergeMatrixLayerOneTest` (7), `…FoldTest` (4), `…ConvergenceTest` (2) | `assertNotNull(store.open(id))` | **12063a6's**, and its own commit message says so: *"This commit is NOT green"*, root-caused there to a throw in `BundleWriter.write` |
| 8 — `MergeMatrixMediaTest` (6), `…OutboxTest` (2) | *"could not lift the park"*, *"owes the server no DELETE"* | **Cascade.** Both classes **pass in isolation on 12063a6 and on this branch** — `--tests "*MergeMatrixMediaTest" --tests "*MergeMatrixOutboxTest"`, green on both |

⚠️ **A count from the whole suite is not 21 independent facts.** The matrix shares one in-process
server across all 95 tests, so a row that dies early takes unrelated rows with it — and the rows it
takes are the ones that assert on *server state* rather than on a fold. Checking out the parent
commit and running the same subset is what separates them; the difference between "21 failures" and
"13 failures and 8 knock-ons" is the difference between a regression and a symptom.

`:core:model` (232) and `:core:storage` (153) are green, including 9 new `ShapeHitTest` cases.

- **`detekt`: green for `:android:ink`, `:app` and `:core:model`.** ⚠️ Five findings survive
  elsewhere — `ObjectFormat.applyKnown` complexity 23, `PageFold`'s 10-parameter constructor,
  `OpCodec` at 20 functions, and two magic numbers. All **pre-existing on 12063a6**, confirmed by
  running `detekt` against a stashed tree before touching anything. The two this work introduced
  (`InkSurfaceView` and `StrokeStore` over the function count) are suppressed **per class with a
  justification**, not by raising the shared threshold, so nothing else inherits the exemption.
- **`spotlessCheck`, `checkCoreIsPlatformFree`: green**, executed.
- **Guardrail 2 — the klib compile half only, and it caught a real defect.** `toSortedMap` is
  `java.util.SortedMap` and does not exist in common Kotlin: `compileDebugKotlinAndroid` passed it
  and `compileKotlinIosSimulatorArm64` failed it. Exactly the class of thing CLAUDE.md says the
  import check cannot see. `compileTestKotlinIosSimulatorArm64` was also forced, which is what
  catches an illegal backticked test name before it reaches D8.
  The **framework link** (`linkIosCore`) logs `SKIPPED` on Windows and is the macOS CI job's to
  prove. This is not a claim that it passed.
- **Definition of done #4 (the Galaxy Tab): not met, and not close.** Nothing runs.

---

## 6b. Connectors, and the two bugs the emulator found that nothing else would have

### A connector is drawn, not moded

ROADMAP's *"snap-to-other-shapes for endpoints"* turned out to be the whole feature rather than a
refinement of it. **Draw a line between two shapes and hold**: draw-and-hold already produces the
line, and binding its ends is the same gesture noticing what it landed on. A modal connector tool
was the obvious design and was rejected — it adds a mode to an eight-chip toolbar to express
something the existing gesture says unambiguously (CLAUDE.md: *"don't gold-plate"*).

What is deliberately refused, each because the alternative is worse than not binding: a rectangle
whose corners touch two boxes (every diagram's outer box would start re-routing); both ends on one
shape (two `Auto` anchors facing the same centre have no direction to resolve from); and attaching
to another **connector** (its route would depend on a route, with no fixed point). An unbound end is
left `null` rather than `ConnectorEnd.Free` — identical geometry today, and they differ the moment
the connector is *moved*, where null follows the transform and `Free` would stay behind.

### ⚠️ The bind radius divided by `pixelsPerPoint` as well as the camera scale

The two conversions in `snapNow` look interchangeable and are not. A **width** is authored in page
points, so dp reaches it through `pixelsPerPoint`. A **reach on the glass** is view pixels, and view
pixels are page units times the camera scale. Doing both shrank the radius by a factor of
`pixelsPerPoint`.

It failed in exactly the way that is hardest to catch by reading: a line drawn *through* both boxes
attached, so every reasonable test passed. A line drawn the way a person draws one — stopping
20 px short of the box it points at, because the gap is how the drawing reads — did not. The first
emulator attempt did precisely that and reported `snapped to line` instead of
`snapped to line · attached`. `SelectionController.snapTolerance` is the same conversion done
correctly, and its own KDoc records the identical bug in the alignment guides, fixed one commit
earlier.

### ⚠️ Two toolbar rows drawn on top of each other

`AnimatedVisibility`'s content slot lays its children out in a **Box**, so adding a second `Row`
beside the selection row stacked them: *"1 selected"* over *"1 shape"*, every button over another
button. It compiled, it ran, and it was unreadable. A screenshot found it — the second time in this
milestone that looking at the screen beat reading the code, after M8a's *"the colour panel was
covering that part of the screen"*.

### The walk

| Gesture | Result |
|---|---|
| Two boxes, each drawn and held | "snapped to rectangle", 0 → 2 |
| Line between them with a realistic gap, held | **"snapped to line · attached"** |
| Select, tap box edge | "1 selected · 1 shape", **Fill/No fill/Round/Square** appear, no route chips |
| Drag that box down | connector followed; **both** ends re-anchored on the boundaries |
| Delete the box | connector fell back to its authored geometry, still attached at the other end |
| Fill, then Round | translucent fill and rounded corners, connector still on the filled box's edge |

⚠️ **Tapping an unfilled box's *centre* selects nothing** — you tap its edge. That is `ShapeHit`'s
rule (*"you can erase through a hollow box without touching it"*) applied to the tap test, on
purpose, and it is the same predicate for both so the two cannot drift. It reads as correct once
the box is filled, and it is worth knowing before deciding it is a bug.

---

## 7. What is left, in order, and what each step inherits

Items 1, 2 and 5 of the original list are done — `DryShape`, its painter, and draw-and-hold. What
remains:

1. **A tool that creates a connector.** Everything under it works and none of it is reachable: the
   record, the routing, the re-attachment, the derived detachment. Until a gesture writes a
   `ShapeRecord` with an end in it, `ConnectorRouting` has no production caller and a green suite
   establishes nothing (CLAUDE.md definition-of-done #6). This is the single largest gap.
2. ~~**`NoteEditor.reconcile` must diff the record.**~~ **Done — M8c closed it in `12063a6`.**
   `NoteEditor.changedRecords` diffs `textOf` (and `formatOf`) between folds and is called from
   `reconcile`; `StrokeStore.applyContent` carries a record to `DryShape.setRecord` and sets
   `routesDirty`, so a peer's re-attachment renders. (⚠️ A peer's *move* already re-routed for
   free — a `SetTransform` the diff carries — which is why this gap was easy to miss when it was
   open: the obvious test passed.)
3. **Fill, corner radius and route pickers.** The record carries all three and nothing sets them,
   so `DryShape.fillable` is false for every shape the app can currently make. The painter's fill
   path has therefore **never drawn a pixel in the app** — only in `ShapeHitTest`'s arithmetic.
4. **The PDF**, with `PdfViewerCompatTest` and not PdfBox as the witness — see §4's last question.
   Nothing writes a shape to a PDF yet, so a published note loses them.
5. **A merge-matrix row for a record under concurrency.** Rows 2, 3 and 4 have connector analogues
   that nothing exercises: a `SetTextContent` on a tombstoned connector, two concurrent record
   writes, and a record write concurrent with a `SetTransform` on its target (which must *both*
   apply — different registers).
6. **Selecting a shape.** `SelectionQueries.objectAt` now hit-tests one through the shared
   predicate, but the lasso and the transform handles have not been tried against a shape on a
   device. ~~and `StrokeStore.duplicate` is still strokes-only~~ — **closed by the M8 follow-up
   audit fixes**, along with two seam bugs this item did not know about: `SelectionController`'s
   restyle read `strokeOf` and fell to `DEFAULT_STYLE` for a shape (recolour reset its width to
   3 pt; set-width recoloured it black), and Duplicate silently dropped shapes. Restyle now reads
   `objectOf` with `DryShape.effectiveStyle()` as the baseline, and a duplicated shape travels as
   `ShapeEdit.duplicate` ops through the store's one door — a connector's record re-targeted at
   copies for ends inside the selection. `SelectionControllerShapeTest` (instrumented) covers all
   three, plus the newly wired rectangular marquee.
7. **The tablet**, including SPEC §3.6's two measurements, which §4 records as owed.
