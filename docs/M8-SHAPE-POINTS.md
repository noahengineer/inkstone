# M8 follow-up 3 — editing a shape by its points, and the curve

**What was asked for:** *"when creating shapes by holding i dont get the editing features i want …
i want it where there are points you can move around, so like if you have a curve there would be 3
points — and right now you cant even create a curve. For a line you dont get 2 points, you get the
select stuff, so you can only do some basic rotating and resizing."*

**Status: built, and driven through the app on `emulator-5556`.** Every shape now carries its own
handles, a line has three of them, and pulling the middle one turns it into a curve. §5 is what was
checked and how; §6 is what is still owed.

---

## 1. The constraint that decided the whole design

A curve is new geometry, and the obvious home for it — a sixth `ShapeKind` — was **already ruled
out in writing**. `docs/M8-CONNECTORS.md` §1.1 dropped `POLYGON` for exactly this reason and SPEC
§4.8.8 records it:

> `OpCodec.decodeObject` reads the kind with `ShapeKind.valueOf` and `BundleReader` decodes a page's
> ops unguarded — so a sixth constant is not a degrade, it is **an older build failing to open a
> note it has already synced**.

So the milestone had two questions, not one, and they have different answers:

| | Where it went | Why |
|---|---|---|
| **Moving a point** | nowhere — it is an ordinary absolute `SetTransform` | three points *are* an affine map |
| **Curving a line** | `ShapeRecord.bend`, in the `SetTextContent` register | the one home an older build round-trips losslessly (SPEC §4.8.2) |

### ⭐ Three points are an affine map, so most of this feature stores nothing

A `ShapeObject` is a kind, a local box, and the matrix that places it. A 2-D affine map is
determined by exactly **three** point correspondences — so *"put this vertex there and leave the
other two"* has one answer, and that answer is a matrix. Every vertex drag therefore emits the op
M2 froze into the vocabulary in SPEC §4.2: `SetTransform`, absolute, one per gesture.

That is not a trick to avoid a format change; it is why the feature is safe. Undo already inverts
`SetTransform`. The merge matrix already covers it. The PDF writer, the thumbnail renderer, the
conflicted-copy path and every peer already handle it, **because none of them can tell a point drag
from a drag of the whole shape**. The alternative — storing per-vertex geometry beside the payload
— would have needed a new register, a new merge rule, a new copy path (the trap
`docs/M8-CONNECTORS.md` §3 caught three times) and a new way for two devices to disagree.

### ⚠️ Only the triangle gets the free affine, and the restriction is the feature

| Kind | Handles | A drag solves |
|---|---|---|
| `TRIANGLE` | 3 vertices | the exact affine; the other two are pinned |
| `LINE` / `ARROW` | 2 ends + 1 bend | a **similarity** about the far end; the bend writes the record |
| `RECTANGLE` | 4 corners | a scale on **both** local axes about the opposite corner |
| `ELLIPSE` | 4 quadrant points | a scale on **one** local axis about the opposite point |

A rectangle has four corners and an affine has three degrees of freedom, so *"drag one corner, pin
the other three"* has **no** solution — the fourth corner is `B + D − A` by construction. Solving it
as a free affine anyway is the bug this table exists to prevent: every rectangle would silently
become a parallelogram the moment a corner was touched. Scaling the shape's **own** axes keeps the
right angles, and because the axes are the shape's and not the page's, the dragged corner still
lands exactly under the finger at any rotation.

That last clause is the reason the old frame was not merely coarse here. `SelectionHandle`'s box is
**axis-aligned even after a rotation** — deliberately, and correctly, *for a frame* — so a rotated
rectangle's grips sit off the shape entirely. And an axis-aligned line's frame has a height of
about one point, which is why *"you can only do some basic rotating and resizing"* was, for a line,
generous.

---

## 2. The curve: one quadratic, and the handle is **on** it

`ShapeRecord.bend` is the on-curve **midpoint** of a `LINE` or `ARROW`, in the shape's own space.

- **A quadratic, not a cubic.** Three points is what was asked for and a quadratic through three
  points is unique. Two independent control points would need four handles and would make *"pull the
  middle"* ambiguous. `ShapeSegment` has no quadratic — PDF has no quadratic operator — so the
  degree is raised in `ShapeGeometry`, once, where the screen, the thumbnail and the PDF all read
  the same answer.
- **The handle is the midpoint, not the control point.** A control point sits off the curve, and
  someone dragging one is aiming at a place the ink never goes. The conversion is exact:
  `C = 2M − (A + B)/2`, because a quadratic's midpoint is `(A + 2C + B)/4`.
- **Own space, not page space** — the same reasoning as `ConnectorAnchor.Pinned` (SPEC §4.8.3). A
  point in the object's box is carried by the object's absolute matrix, so moving, resizing or
  rotating a curve writes one `SetTransform` and nothing else.
- **The degrade for an older build is the best one available**: it keeps the bytes, re-encodes them
  verbatim, and draws the line **straight** — which is exactly what the payload has always said, and
  the same fallback a detached connector already uses.

### A curve is created by bending a line, not by recognising one

Draw-and-hold deliberately **refuses** an open arc: `CLOSED_MAX_GAP` was tightened to 0.12 in the
last follow-up precisely so that a `C`, a bracket or a large open curve stays freehand ink, and
*"no fighting it to draw a freehand curve"* is an acceptance criterion (SPEC §4.8.7). Snapping arcs
to curves would break it in the same breath as adding the feature.

So the gesture is: **snap a line, then pull its middle handle.** A straight line's bend handle sits
on the chord, which is the whole affordance — a handle that only appeared once the line was already
curved could never be found. Pulling it back within **14 dp of the chord snaps to straight**, and
straight is stored as *no bend*, which restores `ShapeRecord.isDefault` and stops the line costing a
string in the file.

---

## 3. Three things that were nearly wrong, and one that was

### ⚠️ `isDefault` had to learn about the bend, and forgetting it is silent

`ShapeEdit.create` omits a default record **entirely**, and `SelectionController.editRecords` writes
its undo baseline **only** for a shape whose record is default. A curved line reading as "default"
would therefore have been published with no record at all by one path, and given a baseline that
straightens it by the other. One `&& bend == null`, and a test that says why.

### ⚠️ The bend needed the same undo baseline a fill does

`SetTextContent` cannot say *"unset"*, so the **first** record a plain shape ever gets has no
inverse; `Undo.inverseOf` answers null and `UndoStack.commit` drops the whole gesture. That is the
bug `docs/M8-CONNECTORS.md` §7.3 records for fill — *"the box vanished, filled"* — and *"bend a line,
press undo"* is the same shape. Routing the commit through the existing `editRecords` was not a
convenience: it is what writes the baseline. Checked on the device (§5).

### ⚠️ A preview must not be mistaken for a register write

A curve has to follow the finger at 240 Hz and must produce exactly **one** `SetTextContent` when it
lifts. `DryShape.previewBend` holds the drag's bend *in front of* `record` rather than through
`setRecord`, so `record` stays what the log says. `setRecord` clears the preview — without that,
committing would leave the preview shadowing the value it had just written, and the **next** op to
arrive from an undo or a peer would be drawn with the last drag's curve.

### ⚠️ The one that was actually wrong: "Adjust" did not exist

`InkSurfaceView.onSnapped` deliberately does not switch the tool — a flowchart is five boxes in a
row and stealing the pen after each would cost five taps — and it selects the new shape instead, so
its handles are on screen. Its KDoc has said since M8b that *"`InkScreen` surfaces an 'Adjust' action
instead, which is one tap"*.

**It did not.** Nothing in `:app` had ever contained the word. A comment describing a caller that
does not exist is CLAUDE.md's definition-of-done #6 written down as a promise, and it was load-
bearing for exactly this milestone: without it, a user who has just snapped a shape is holding a pen
that draws straight through every handle. The button is one `TextButton` beside the acknowledgement.

---

## 4. What a connector deliberately does **not** get

No point handles at all. Its geometry is derived from what its ends are attached to and re-derived
on every fold (SPEC §4.8.3), so a vertex drag would write a perfectly correct `SetTransform` that
`DryShape.reroute` then paints over, and a bend would be discarded the same way — an honest-looking
dead grip, which is worse than no grip.

The gesture a connector actually wants is **dragging an endpoint onto a different shape**, which is
`docs/M8-CONNECTORS.md` §7.7 and is still owed. It is now the only kind of shape with no point
editing, which is a better argument for building it than it had before.

---

## 5. What was checked, and how

CLAUDE.md asks which of *written*, *automated*, *automated against the real dependency* and *run on
a device* each claim is.

| Claim | Unit (JVM + iOS) | Instrumented | Driven through the app |
|---|---|---|---|
| Line endpoint lands under the finger; the far end is pinned | `ShapePointsTest` ✅ | ✅ | ✅ screenshot |
| …including on a rotated line | `ShapePointsTest` ✅ | — | ✅ (the drag turned it 40°) |
| Triangle vertex is exact; the other two are pinned | `ShapePointsTest` ✅ | — | ❌ |
| A triangle drag shears, and a flattened one is refused | `ShapePointsTest` ✅ | — | ❌ |
| Rectangle corner is exact; the opposite corner is pinned | `ShapePointsTest` ✅ | ✅ | ✅ screenshot |
| A rotated rectangle stays a rectangle | `ShapePointsTest` ✅ | — | ❌ |
| A corner dragged past its pin does not mirror | `ShapePointsTest` ✅ | — | ❌ |
| Ellipse quadrant travels on its own diameter only | `ShapePointsTest` ✅ | — | ❌ |
| The curve passes exactly through the handle | `ShapePointsTest` ✅ | ✅ | ✅ screenshot |
| A bend near the chord is *no bend*, measured on the page | `ShapePointsTest` ✅ | — | ❌ |
| A flat bend draws a `LineTo`, never a flat cubic | `ShapePointsTest` ✅ | — | ❌ |
| The bend round-trips the record and re-encodes byte-stably | `ShapePointsTest` ✅ | — | ✅ read off disk |
| A non-finite bend never reaches the geometry | `ShapePointsTest` ✅ | — | — |
| A curved line is not a default record | `ShapePointsTest` ✅ | — | — |
| An unreadable bend fails closed and keeps the fill | `ShapePointsTest` ✅ | — | ❌ |
| One drag writes exactly **one** `SetTransform` | — | ✅ | ✅ op count on disk |
| A bend writes baseline + record, and **no** transform | — | ✅ | ✅ op count on disk |
| Pressing a handle without moving writes nothing | — | ✅ | ❌ |
| A connector offers no handles | — | ✅ | ❌ |
| **Bend, then undo → straight line, not a vanished one** | — | — | ✅ screenshot |
| Handles are drawn where they are computed and are grabbable | — | — | ✅ screenshot |

### The walk, on `emulator-5556`

Drawn with `adb shell input stylus motionevent` — `PalmGuard` accepts `TOOL_TYPE_STYLUS` only.

| Gesture | On screen | In `journal.ops` |
|---|---|---|
| Drag, arm the dwell, hold | "snapped to line · drag its points" | `"k":"p","sh":"LINE"` |
| **Adjust** | tool → Select, "1 selected · 1 shape" | — |
| — | two squares and a ring on the line; the eight frame grips **gone**, rotate grip kept | — |
| Pull the ring up 280 px | a real arc through the ring; the frame grew to the curve | `"t":"txt","x":"is1"` then `"t":"txt","x":"is1;bd=71.719:-66.262"` |
| **Undo** | the curve straightened; the line stayed | — |
| Drag the right square up and out | the line turned ~40° and lengthened; the left end did not move | one `"t":"tr"` |
| Box, hold, Adjust, drag the bottom-right corner | the rectangle grew to the finger; the top-left corner did not move | one `"t":"tr"` |

Two things worth reading off that table. The bend wrote **two `txt` ops and zero `tr` ops** — the
baseline that makes undo work, then the curve, and nothing about the placement. And the whole
journal for that note is `add ×3, del ×2, txt ×2`: no per-frame ops from a drag that produced
dozens of previews.

⚠️ **Draw-and-hold needs a *stationary* sample to arm the dwell, and the first two walk attempts
did not snap because of it.** `ShapeSnapDetector.sample` schedules the timer only when a sample
lands *inside* the hold radius; a gesture whose last event is a large `MOVE` answers `NOT_HOLDING`
and schedules nothing, so `sleep 1.5` waits for a timer that was never set. The recipe is
`… MOVE 1300 1100; MOVE 1301 1100; sleep 1.6; UP`. `ShapeSnap.recognise` also wants **six** points,
which a four-`MOVE` gesture does not have. Neither is a bug — both are worth writing down, because
both look exactly like *"the feature is broken"* from the shell.

### Gates

- **`./gradlew build`: SUCCESSFUL**, and the test tasks *ran* — every
  `*/build/test-results/testDebugUnitTest/*.xml` is stamped inside the build's own 14 minutes, not
  restored from the cache. `tests=` summed over them:

  | Module | tests | failures |
  |---|---|---|
  | `:core:model` | 291 | 0 |
  | `:core:storage` | 178 | 0 |
  | `:core:sync` | 190 | 0 |
  | `:core:search` | 22 | 0 |
  | `:app` | 137 | 0 |
  | **Total** | **818** | **0** |

  **29** of `:core:model`'s are new (`ShapePointsTest`), and 5 of the instrumented ones. Two
  warnings survive the build and both predate this work: a redundant `else` in `ElementLibrary` and
  a Hilt `hiltViewModel` deprecation in `InkScreen`.
- **`detekt` and `spotlessCheck`: green, executed.** ⚠️ detekt caught two things this work
  introduced and neither was suppressed: `SelectionController.begin` crossed cyclomatic complexity
  15 (the grip lookup is its own function now, which reads better anyway) and
  `ShapeRecordCodec.applyKnown` crossed it too (the two endpoint branches and the bend became
  extension functions). The one suppression added is `@file:Suppress("MagicNumber")` on
  `ShapePoints.kt`, with the same justification `Shapes.kt` and `ShapeSnap.kt` carry.
- **`:android:ink:connectedDebugAndroidTest`: 85 tests, 10 failures**, and all ten are the two
  documented x86_64 causes — nine `StrokeInput::orientation` from M1's `SyntheticInk` fixture and
  one arm64-tuned float tolerance (`415.70966` vs `415.7085`). **All five new tests passed.** The
  new fixtures build geometry through `ShapeEdit.placeSegment` rather than `SyntheticInk`, which is
  why they cannot inherit the M1 bug.
- **Guardrail 2, klib half:** `compileKotlinIosSimulatorArm64` and
  `compileTestKotlinIosSimulatorArm64` both green — the latter is what proves the new backticked
  test names are Kotlin/Native-legal. The **framework link is not claimed**; it logs `SKIPPED` on
  Windows and belongs to the macOS CI job.
- **Definition of done #4 (the Galaxy Tab): not met.** `adb devices` lists emulators only. Nothing
  here measures ink feel, but nothing here is on the ink path either — a handle drag is a selection
  gesture.

---

## 6. What is left

1. ⚠️ **A connector's endpoints still cannot be re-attached** — `docs/M8-CONNECTORS.md` §7.7,
   unchanged, and now the only shape gesture with no handles behind it.
2. **The triangle and the ellipse have not been driven on a device.** Their solves are the same two
   functions the rectangle and the line use and they are covered by JVM tests, so the risk is low —
   and it is still an untried path, which is the sentence M6's inert features had.
3. **A rotated rectangle's corner drag has not been driven on a device either**, which is a shame,
   because rotation-invariance is the whole argument for solving in the shape's own basis. The
   emulator can express it (rotate the shape with the grip, then drag a corner); it was not done.
4. **Nothing snaps a point to anything.** A vertex does not align to another shape's edge, to the
   page's centre, or to the grid, though `AlignmentGuides` already exists and a move gesture already
   uses it. Deliberately out of scope here; it is the obvious next thing.
5. ⚠️ **An ellipse now costs two drags to resize in both axes**, and that is a real regression
   against the frame's corner grips, which scaled proportionally in one. It is the price of the
   handles being *on the shape*: a quadrant point lives on a diameter, and letting it travel
   sideways would be a point that does not go where the finger does. A rectangle is unaffected — a
   corner drag solves both axes at once, and dragging one horizontally leaves the height exactly
   alone, because the drag is measured from the **pinned corner** and not from the handle. If the
   ellipse case turns out to bite, the fix is to offer its box corners *as well*, not to loosen the
   quadrant rule.
6. **The bend is one register on a line, so two devices bending the same line concurrently lose one
   of the two edits** — SPEC §4.8.2's stated cost, unchanged and not made worse. There is no merge
   row for it. `MergeMatrixRecordTest` row 4d covers the identical shape for fill.
