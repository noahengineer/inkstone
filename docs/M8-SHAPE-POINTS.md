# M8 follow-up 3 — editing a shape by its points, and the curve

**What was asked for:** *"when creating shapes by holding i dont get the editing features i want …
i want it where there are points you can move around, so like if you have a curve there would be 3
points — and right now you cant even create a curve. For a line you dont get 2 points, you get the
select stuff, so you can only do some basic rotating and resizing."*

**Status: built, revised after the first session with it, and driven through the app on
`emulator-5556`.** Every shape now carries its own handles, a line has three of them, a drawn arc
snaps to a curve, and dragging a corner moves *that corner*. §5 is what was checked and how; §6 is
what is still owed.

⚠️ **§0 is the revision, and it matters more than the rest of this document**, because three of the
four things it changes were decisions this file had already argued for at length.

---

## 0. What the first session with it found

Four complaints, verbatim:

> ok there is quite a few things that are not so good like when not drawing a straight line it
> should not not work when trying to create a shape it should create a curve out of that and i dont
> like how it gives a box around the shapes it can make it quite confusing also it makes it so that
> you draw immediately after so you end up drawing on the shape rather then editing it. also i
> noticed that editing the points is just resizeing the shape from those points rather then actually
> editing the shape witch is not what i want at all

All four were real. Three of them were **design decisions**, argued for in this document and in
SPEC, and all three were called the wrong way:

| Complaint | What it was | What it is now |
|---|---|---|
| *"editing the points is just resizeing the shape"* | every drag solved as a **placement** (§1) — for a rectangle a matrix can only scale | closed shapes store their **vertices**; one drag moves one point |
| *"it should create a curve out of that"* | draw-and-hold **refused** every arc, citing *"no fighting it to draw a freehand curve"* | an arc that is one clean quadratic snaps to a curve |
| *"you draw immediately after… rather then editing it"* | the tool deliberately stayed on the pen after a snap | the editor switches to **Select**; the toolbar offers **Draw again** |
| *"i dont like how it gives a box around the shapes"* | the dashed frame was drawn under the handles | no frame when a shape has its own points; it is still the (invisible) move target |

The pattern is the one `docs/M8-UX-FIXES.md` opened with and is worth restating: **the user's
diagnosis was better than the code's KDoc in every case**, and in three of the four the code had a
written argument for the behaviour being complained about. An argument is not evidence.

### The second session found two more

> its far to easy to make a circle whenever i try to make a square it turns into a circle […] and
> when editing a curve that you started curved if you move that center point to the middle so it
> would act more like a straight line it snapes to its original curve point which is quite weird

**A square turning into a circle is a *structurally* one-sided window, not a bad constant.** A
gesture's fill ratio cannot exceed 1.0 — the minimum-area box encloses the polygon — so the
rectangle's target sits at the very top of the feature's range while every other kind sits in its
interior. Half the rectangle's window is therefore unreachable, and every imperfection a hand
introduces (a rounded corner, a bowed edge, an overshoot past a corner that widens the hull) moves
it *toward* the ellipse with nothing to move it back. Worse, between fill 0.86 and 0.89 the
rectangle fell **below** `DEFAULT_CONFIDENCE` while the ellipse was comfortably above it — so the
square was not merely losing, it was disqualified. `ShapeSnap.missBy` now gives the rectangle a
one-sided allowance (`RECTANGLE_SLACK = 0.08`), moving the rectangle/ellipse boundary from fill
**0.8925 to 0.8525** and letting a rectangle clear the bar from 0.812. A perfect circle is untouched:
it still scores 1.000 as an ellipse, and the winner is the higher confidence rather than whoever
clears the bar.

⚠️ **This is a constant tuned to a report, which is the weakest kind in that file** — but the
*asymmetry* it corrects is a fact about the feature, not a guess, and that is what makes the
direction safe even though the magnitude is not measured.

### The third session: the last placement edit, and a feature that was the wrong one

> *"straight lines work but they dont use the point system, they have the stretching by size
> changing, i dont want any shape to do that no mater what … also still cant make a square its way
> to biased to a circel"*

**A segment was the last shape still edited as a placement**, and the defence for it — a similarity
maps one end anywhere and pins the other *exactly*, with no residue — was true and beside the point.
A similarity scales the whole local frame, so a curve's bulge grew as its chord was dragged out, and
what was being edited was the placement rather than the points. *Expressible* as a matrix is not
*should be* a matrix. A segment now stores its two ends like every other kind, and the three
transform solves this feature was built on — a similarity, an axis-scale and an exact affine — are
**deleted**, not left unreachable. Nothing a handle does writes a `SetTransform` any more.

Two consequences worth stating. `ShapeRecord`'s vertex list accepts **two** entries now, where it
required three, or the decoder would have refused what the encoder had just written. And a bend's
straightness is measured against the **edited** chord: once an end has moved, the segment is no
longer `(0, h/2) → (w, h/2)`, and measuring against the payload's chord would call a real curve
straight.

**The square-versus-circle bias was the wrong *feature*, not the wrong constant.** Two rounds of
widening the rectangle's window had not fixed it, which was the signal. The fill ratio — area over
the minimum-area box — is a fact about *ideal* shapes whose targets are 0.215 apart, and a hand moves
it further than that: rounding takes area away, and bowing an edge or overshooting a corner
*enlarges the box*. So the family decision moved to a different measurement entirely:

- **Corner concentration.** Resample the outline to 48 points equally spaced **along the arc**, take
  the turn at each over a span of 1/24 of the perimeter, and ask what share of all the turning the
  largest six hold. A square holds nearly all of it; a circle spreads it evenly.
- **…or a very high fill**, because the two fail in *different* circumstances: overshoot craters the
  fill while leaving corners sharp, and rounding does the reverse. Either is evidence of corners.

Measured over 30 noisy trials each: squares rounded 6/12/18% of their side were cornered **30/30**,
and circles, noisy circles, coarse circles and ellipses were cornered **0/30**. The fill then only
has to separate a rectangle from a *triangle* — 1.0 against 0.5, half its range apart.

⚠️ **Two traps inside that work, both found by measuring rather than reading.** The turn was first
measured between *adjacent* samples, which counts every vertex of a coarse polyline as a corner — a
12-point circle hands half its turning to the top six and reads as cornered, and every `adb` gesture
is that coarse. And the guard `if (count < CORNER_SAMPLES) return 0f` read *"too few samples"* as
*"no corners"*, so on the emulator every closed shape was an ellipse; resampling **interpolates**, so
a coarse polyline was never a problem in the first place.

⚠️ **And the fixture was wrong.** `roundedSquare` interpolated its straight edges toward the next
corner's *centre* instead of the next arc's *start*, cutting the corners off: it read a fill of 0.799
at an 18% rounding — an oval's number — and a threshold was very nearly moved to accommodate a shape
the fixture had deformed. The real figure is 0.972. M7's *"a synthetic fixture is the worst possible
witness"* has a corollary: check the witness before believing the testimony.

### …and the arc candidate then broke straight lines

> *"ok but now straight lines dont create properly"*

Adding the arc put **two candidates on the same gesture with confidences that are not on the same
scale.** A line's ramp is 0.03 wide (`[0.93, 0.96]` straightness) so it falls off a cliff — 0.83 at a
60° bow, **0.30** at 70° — while an arc's sits nearly flat around 0.78 across that whole range. At
60° the line was winning by **0.03**, which is noise on a real gesture, and past that the arc simply
took it. A hand's line is never straight enough to stay on the good side of a cliff that steep.

The fix is not a nudge to a threshold, it is to make the two **disjoint by construction**: the arc
only takes what the line has already refused. A gesture at the line's own floor — `sin(x)/x = 0.93`,
`x = θ/2` — is a 74.8° arc whose sagitta is 0.169, so `ARC_MIN_SAGITTA` is **0.17**, derived rather
than chosen. `ShapesTest` asserts the *property* (nothing straight enough to be a line ever comes
back as a curve) against `ShapeSnap.LINE_MIN_STRAIGHTNESS`, so the two constants cannot drift apart
silently — which is why that one is public.

The cost is stated: a shallow bow between roughly 65° and 78° now snaps to nothing and stays ink, as
it did before curves existed. On the emulator, lines bowed by 0%, 4%, 8% and **12%** of their chord
all come back as *"snapped to line"* — the last two are exactly what the old 6% floor was stealing —
and a 120° arc still comes back as *"snapped to curve"*.

**The curve springing back was one nullable carrying two meanings**, and my own KDoc asserted it
could not happen. `ShapePoints.bend` returned `null` for *"the drag straightened the line"*;
`DryShape.previewBend` read `null` as *"stop previewing"* and fell back to `record.bend` — the
original arc — **with the finger still down**. The KDoc claimed *"a drag back onto the chord previews
a bend that ShapeGeometry.outline itself renders straight, so the two never have to be told apart"*,
which was simply false: `bend` never produced such a point, it produced null.

The fix makes the confusion unrepresentable rather than fixing the call site. `bend` returns
[`BendDrag`](../core/model/src/commonMain/kotlin/dev/inkstone/core/model/ShapePoints.kt) — `preview`,
which is **never null** and is the foot of the perpendicular once straightened, and `stored`, which
is null for straight. `previewBend` takes a non-null point and `endShapePreview` ends a drag, so
"straight" and "nothing to say" no longer share a channel. The straightened preview also keeps the
handle under the finger's **x** instead of snapping it to the middle of the chord.

### ⚠️ The one that was a real design error, not a trade

*"Editing the points is just resizing"* is exactly right, and §1 below explains why it happened: a
2-D affine map has **three** degrees of freedom, a rectangle has **four** corners, so *"move this
corner and leave the other three"* has no solution as a matrix. The nearest a matrix can do is scale
about the opposite corner — which is what shipped, and which is precisely what *"resizing from those
points"* describes. §1's reasoning is correct and its conclusion was wrong, which is the most
expensive kind of KDoc: it explains why the wrong thing is right.

Closed shapes now store a vertex list in `ShapeRecord.points`, seeded from the kind's own outline on
the first drag. Three properties make that safe, and one of them had to be *made* true:

- **Seeding is invisible.** `ShapeGeometry.outline` reproduces the parametric shape from its own
  seeds bit for bit. The ellipse only does that because its smoothing constant is `KAPPA / 2`
  rather than Catmull-Rom's ⅙ — with any other constant the first drag would visibly re-draw the
  whole shape, which is the complaint arriving from the other direction. `ShapePointsTest` asserts
  it for all three closed kinds.
- **The kind is unchanged**, so an older build draws the parametric shape and round-trips the bytes
  — the same degrade `bend` gets (§2), and why neither needed a sixth `ShapeKind`.
- **The placement is untouched**, so a move, a resize and a rotation are still one `SetTransform`.

A **segment** kept the placement route at this point, because a placement expresses two endpoints
*exactly* — and that lasted exactly one more session before *"i dont want any shape to do that no
matter what"* took it away too. See §0's third session. The triangle moved to vertices with the
other closed kinds even though the affine solved it correctly: a sheared *space* and a moved *point*
differ once an erase region lives in that space, and one rule beats one saved register.

### The fourth session: a shape could not be erased, and it was one missing line

> *"once you create them you cant really remove them because if you erase or delate a shape then
> leave the note and enter it again the shape is back"*

**`DryShape`'s constructor never applied `obj.erased`.** Every other dry object did — `DryBox`'s
init and `dryStrokeFor` both end in `setErased(obj.erased.toRegion())` — and a shape simply never
had the line. So the eraser worked, the `SetErasedRegion` op was recorded, journaled and folded, the
register held the right capsules, and the shape came back **whole** on the next load, because the
only thing that reads that register into a renderable path runs at construction and a shape skipped
it. The published PDF lost the erasure for the same reason.

Three things about it are worth keeping.

- **The op was never the problem, and that is what made it slow to find.** Everything a test would
  naturally assert — that the eraser records an absolute region, that the fold applies it, that the
  bytes round-trip — was true. `SetErasedRegion` is a register like any other (rule 8), and rule 8's
  own machinery was working perfectly. The gap was one consumer of the register, in the one class
  that had no test loading it *with a region already set*.
- **Delete was the red herring the report came wrapped in.** *"erase or delete"* named two paths and
  only one was broken; checking delete first (journal `add:1, del:1`, reopened at 0 strokes) proved
  it correct and pointed the whole investigation at the eraser, which is the opposite of what the
  wording suggested. Splitting a two-verb report into two measurements before believing either is
  cheap, and it was the step that mattered here.
- **Grep for the *sibling*, not for the symptom.** The line was found by grepping `setErased` and
  reading the two callers that had it beside the one that did not. A new object kind inherits an
  invariant nothing enforces — this is the same shape as SPEC §4.8's `SetTextContent` register being
  dropped by three copy paths (§0's second session), and both times the fix was to look at what the
  *other* kinds already do rather than at what the new one does wrong.

`aShapeLoadedWithAnErasedRegionKeepsIt` covers it, and was run against the build without the fix
first — 1 failure of 14 — so the test is known to be able to fail. On the device: the same note
whose left edge had been erased under the previous build, reopened cold, measures **182 dark pixels
of 220** down that edge, the identical count the erased shape showed live in memory before the note
was left, where the previous build returned all 220.

Say which check each half got, because they are not the same. The **editor** was measured on the
device, twice, with the same instrument. The **published PDF and the thumbnail** were not opened in
a viewer for this fix: what establishes them is that `InkStrokeOutliner.outline` and
`InkPageThumbnailRenderer.toStrokeStore` each build their own `StrokeStore` from the fold — so both
reach the same constructor — and that `outlinesOf(DryShape)` subtracts `dry.erased` and nothing
else. The instrumented test is on that constructor, so it covers all three consumers; no renderer
other than the editor's was measured.

---

## 1. The constraint that decided the original design

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

### ⚠️ …and this is the table that was wrong — see §0

| Kind | Handles | A drag writes **now** |
|---|---|---|
| `RECTANGLE` / `ELLIPSE` / `TRIANGLE` | its vertices | `ShapeRecord.points` — only the dragged one moves |
| `LINE` / `ARROW` | 2 ends + 1 bend | `ShapeRecord.points` for an end; `ShapeRecord.bend` for the middle |

⚠️ That second row said `SetTransform` for one more session — see §0's third session, where the last
placement-based edit went the same way as the first three.

The original table gave a rectangle *"a scale on both local axes about the opposite corner"* and an
ellipse *"a scale on one local axis"*, and called the restriction a feature. It is not: a rectangle
has four corners and an affine has three degrees of freedom, so *"drag one corner, pin the other
three"* has no solution as a matrix, and the scale was the nearest thing available rather than the
right answer. Storing the vertices costs one register and answers the question directly.

What survives from the original argument is the **segment**, which a placement really does express
exactly, and the reason none of this needed a new `ShapeKind`.

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

### A curve is created two ways, and the second one had to be argued back in

**Pull the middle of a line.** A straight line's bend handle sits on the chord, which is the whole
affordance — a handle that only appeared once the line was already curved could never be found.
Pulling it back within **14 dp of the chord snaps to straight**, and straight is stored as *no
bend*, which restores `ShapeRecord.isDefault` and stops the line costing a string in the file.

**Or draw the arc and hold.** ⚠️ This was refused in the first version, on the strength of *"no
fighting it to draw a freehand curve"* (SPEC §4.8.7) — and the reading was too broad. That criterion
protects a curve drawn and **lifted**; draw-and-hold only ever runs when the user has deliberately
held still, and a hold is a request. Refusing it was not protecting anything; it was the feature
declining to work on the one gesture it had been asked to work on.

What is still refused is anything **one quadratic cannot draw**, measured as the distance from the
ink to the curve it would become — not by a corner detector, which needs a smoothing window and
therefore a parameter per stylus rate. An S, a zigzag and a scribble all fail by construction.

⚠️ **The threshold is set by the corner, not by the squiggle.** A scribble scores 0.485 and is
nowhere near; a *rounded corner* scores 0.074 and a 180° arc 0.073 — they overlap, because they are
similar shapes. So `ARC_MAX_RESIDUAL` sits **below** the corner rather than above the deepest arc: a
half-circle held still stays ink, which is a feature that did not fire, while a corner turned into a
smooth curve is a drawing that changed under the user. The measured table is in the constant's own
KDoc, and `ShapesTest` pins both sides of the boundary so a future change has to argue with a test
rather than with a number.

⚠️ **The confidence ramp bit for the third time in this file.** The arc's confidence originally
ramped `1 → 0` across the residual, and `recognise` then applies a shared `DEFAULT_CONFIDENCE` bar
of 0.55 — so the *effective* limit was `ARC_MAX_RESIDUAL × 0.45`, and a perfectly good 140° arc
(residual 0.042 against a stated limit of 0.06) scored 0.30 and was refused. That is exactly what
`FILL_WINDOW` and `LINE_CERTAIN` already document, met a third time by writing a new ramp without
reading either. It now ramps down **to the bar**, so anything accepted here clears it by
construction.

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

### ⚠️ …and the tool switch that "Adjust" was a workaround for

`InkSurfaceView.onSnapped` selects the shape it just made, and the tool used to stay on the pen, so
its handles were on screen under a pen that drew straight through them. The reasoning was that a
flowchart is five boxes in a row and stealing the pen after each costs five taps to give back.

That trade was real and it was called the wrong way: the tap to get the pen back is on a chip the
user is already looking at, while the cost of the old default was **drawing on your own new shape
and undoing it**. The editor switches to Select after a snap now, and the toolbar's button switched
sides with it — *Adjust* became **Draw again**.

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
| **A dragged corner moves and the other three do not** | `ShapePointsTest` ✅ | ✅ | ✅ screenshot |
| …and the shape is drawn as the quadrilateral it now is | `ShapePointsTest` ✅ | — | ✅ screenshot |
| A second drag edits the list rather than re-seeding | `ShapePointsTest` ✅ | — | ❌ |
| A vertex may go anywhere, including onto its neighbours' line | `ShapePointsTest` ✅ | — | ❌ |
| **Seeding is invisible for all three closed kinds** | `ShapePointsTest` ✅ | — | ❌ |
| An edited shape keeps its placement (a move is still one op) | `ShapePointsTest` ✅ | ✅ | ❌ |
| Vertices round-trip the record and re-encode byte-stably | `ShapePointsTest` ✅ | — | ✅ read off disk |
| One unreadable vertex abandons the whole list | `ShapePointsTest` ✅ | — | — |
| Line endpoint lands under the finger; the far end is pinned | `ShapePointsTest` ✅ | ✅ | ✅ screenshot |
| …including on a rotated line | `ShapePointsTest` ✅ | — | ✅ (the drag turned it 40°) |
| **A drawn arc becomes a curve through the ink** | `ShapesTest` ✅ | — | ✅ screenshot |
| …and the drawn points all lie on the curve that replaces them | `ShapesTest` ✅ | — | ❌ |
| An S, a scribble and a corner stay ink | `ShapesTest` ✅ | — | ❌ |
| A straight line does not acquire a bend | `ShapesTest` ✅ | — | ❌ |
| The corner/arc boundary is where the measurements put it | `ShapesTest` ✅ | — | ❌ |
| The curve passes exactly through the handle | `ShapePointsTest` ✅ | ✅ | ✅ screenshot |
| A bend near the chord is *no bend*, measured on the page | `ShapePointsTest` ✅ | — | ❌ |
| A flat bend draws a `LineTo`, never a flat cubic | `ShapePointsTest` ✅ | — | ❌ |
| The bend round-trips the record and re-encodes byte-stably | `ShapePointsTest` ✅ | — | ✅ read off disk |
| A non-finite bend never reaches the geometry | `ShapePointsTest` ✅ | — | — |
| A curved line is not a default record | `ShapePointsTest` ✅ | — | — |
| An unreadable bend fails closed and keeps the fill | `ShapePointsTest` ✅ | — | ❌ |
| One endpoint drag writes exactly **one** `SetTransform` | — | ✅ | ✅ op count on disk |
| A bend writes baseline + record, and **no** transform | — | ✅ | ✅ op count on disk |
| Pressing a handle without moving writes nothing | — | ✅ | ❌ |
| A connector offers no handles | — | ✅ | ❌ |
| **Bend, then undo → straight line, not a vanished one** | — | — | ✅ screenshot |
| Handles are drawn where they are computed and are grabbable | — | — | ✅ screenshot |
| **No frame is drawn round a shape with points** | — | — | ✅ screenshot |
| **The tool switches to Select after a snap** | — | — | ✅ "1 selected" + Draw again |
| **A square with rounded corners is a rectangle, not an ellipse** | `ShapesTest` ✅ | — | ✅ screenshot |
| …and a circle is still a circle, with a wide margin | `ShapesTest` ✅ | — | ❌ |
| **A straightened bend previews straight and stores nothing** | `ShapePointsTest` ✅ | — | ✅ screenshot **mid-drag** |
| …and the handle stays under the finger rather than centring | `ShapePointsTest` ✅ | — | ✅ screenshot |

### The walk, on `emulator-5556`

Drawn with `adb shell input stylus motionevent` — `PalmGuard` accepts `TOOL_TYPE_STYLUS` only.

**First pass, before the revision:**

| Gesture | On screen | In `journal.ops` |
|---|---|---|
| Drag, arm the dwell, hold | "snapped to line · drag its points" | `"k":"p","sh":"LINE"` |
| Adjust | tool → Select, "1 selected · 1 shape" | — |
| — | two squares and a ring on the line; the eight frame grips **gone**, rotate grip kept | — |
| Pull the ring up 280 px | a real arc through the ring; the frame grew to the curve | `"t":"txt","x":"is1"` then `"t":"txt","x":"is1;bd=71.719:-66.262"` |
| **Undo** | the curve straightened; the line stayed | — |
| Drag the right square up and out | the line turned ~40° and lengthened; the left end did not move | one `"t":"tr"` |

**Second pass, after the four complaints:**

| Gesture | On screen | In `journal.ops` |
|---|---|---|
| **Draw a 120° arc, hold** | **"snapped to curve · drag its points"**, and no toolbar tap was needed to edit it | `"t":"txt","x":"is1;bd=73.039:-37.338"` |
| — | **no dashed box** — two squares and a ring, and nothing else | — |
| — | "1 selected", **Draw again** on the toolbar: the tool switched by itself | — |
| **Draw again**, box, hold | "snapped to rectangle · drag its points" | — |
| **Drag the bottom-right corner out** | a **quadrilateral**: that corner followed the finger, the other three did not move | `"t":"txt","x":"is1"` then `"t":"txt","x":"is1;pts=0:0,71.719:0,133.875:105.187,0:47.813"` |

Read the last row off the wire: the seeded corners are `(0,0)`, `(71.7,0)`, `(71.7,47.8)`, `(0,47.8)`
and exactly **one** of them differs. That is the complaint, answered, on disk.

**Third pass, the two reports above:**

| Gesture | On screen |
|---|---|
| Square with corners rounded by **12% of its side**, held | **"snapped to rectangle"** — the gesture that used to become an oval |
| Curve snapped, then its ring dragged onto the chord, **captured with the pointer still down** | dead straight, handle under the finger, **no spring back to the arc** |

⚠️ The second row is the one worth the trouble: the bug only existed *during* the drag, so a
screenshot after the release would have shown a correct straight line and proved nothing.
`adb shell input stylus motionevent` sends discrete events, so DOWN + MOVEs, screenshot, then UP
captures the mid-gesture state — the only way to see it.

⚠️ **A 13-sample arc does not snap and a 25-sample one does**, which cost two attempts. `adb shell
input stylus motionevent` sends one event per invocation, so a coarse gesture is a coarse polyline —
and the residual is measured against the polyline, not against the smooth arc the same hand would
have drawn. It scored 0.0545 against a limit of 0.05. Nothing was wrong with the feature; the
instrument was too blunt, which is this repo's most-repeated device lesson.

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
5. ⚠️ **There is no proportional resize of a shape any more.** The frame's eight grips are hidden
   whenever a shape has points, so scaling one *as a whole* now means dragging its corners one at a
   time — and for an ellipse, whose four points are on its own diameters, that is up to four drags.
   The move target (drag inside) and the rotate grip are unaffected. This is the honest cost of the
   handles being on the shape rather than round it, and if it bites, the fix is a modifier or a
   pinch rather than bringing the frame back.
6. ⚠️ **A rectangle stops being a rectangle the moment you touch a corner**, by design and by
   request. There is no "keep it square" mode, and `Round` (the corner radius) is **ignored** once
   vertices exist — a radius is defined against right angles and an arbitrary quadrilateral has
   none. Nothing tells the user that; the button simply stops doing anything, which is the
   dishonest-button problem this codebase otherwise avoids.
7. **A curve cannot be made from more than one arc.** One quadratic, three points — an S needs two
   and the format has no way to say so, which is why an S stays ink rather than snapping to
   something approximate.
6. **The bend is one register on a line, so two devices bending the same line concurrently lose one
   of the two edits** — SPEC §4.8.2's stated cost, unchanged and not made worse. There is no merge
   row for it. `MergeMatrixRecordTest` row 4d covers the identical shape for fill.
