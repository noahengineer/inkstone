# M8 follow-up 9 — snap to grid, and the selection bar's shape buttons

*"ok can you make a snap to grid for the shapes"*

A shape lands on a lattice: when draw-and-hold turns ink into a shape, and when a selection made
entirely of shapes is moved, resized or edited by its points. Off by default, switched on by the
**GRID** chip beside SNAP and ARROW.

---

## 1. The lattice is the paper's own

`AlignmentGuides` already states the rule this feature inherits:

> a guide the drag does not snap to is a line that lies, and a snap with no line is the object
> refusing to go where it was put

So `SnapGrid.forBackground` derives the lattice from the **template being drawn**, never from a
constant of its own:

| paper | x | y | origin | drawn by the paper |
|---|---|---|---|---|
| `GRID`, `DOT_GRID` | 18 pt | 18 pt | 0, 0 | yes |
| `RULED`, `CORNELL` | **free** | 24 pt | y = `RULED_MARGIN_PT` (36) | yes |
| `BLANK`, `SourcePdfPage` | 18 pt | 18 pt | 0, 0 | **no** |

The spacings and origins are read off `PageTemplate` and match `TemplateBackground`'s own drawing
loops. A lattice half a cell out from the ink on the paper would be worse than no lattice, because
it would look like the shape had missed.

**A ruled page's x axis is deliberately free.** There is no vertical line on ruled paper, so
snapping x would be exactly the second half of the sentence above — the shape jumps sideways to a
column the user cannot see and has no way to aim at. A spacing under `MIN_SPACING_PT` means *"this
axis does not snap"*, so that costs no special case anywhere downstream.

### Blank paper and PDF pages get a lattice **and** the chrome to go with it

The alternative considered — and the one a stub in `InkUiState` had already been written for (§5) —
was to disable the chip on paper with no ruling. That withdraws the feature from the place diagrams
are most often drawn: over a lecture slide. Instead those pages get the default pitch and
`ToolOverlayView` draws it, faintly, while the setting is on.

⚠️ **Chrome, and it must stay chrome.** The obvious home is `TemplateBackground`, which is where
every other lattice in this app is drawn — and that class states in its own KDoc that it rules a
page *"identically on screen, in a thumbnail and in the published PDF"*. A grid that exists because
a **setting** is on belongs in none of those: it would print, it would sync, and it would be in a
PDF opened on a device where the setting is off.

The draw is bounded twice: the loop runs over the intersection of the paper and the viewport (so an
infinite canvas cannot make it unbounded), and a lattice whose cells are under `MIN_CELL_DP` on the
glass is not drawn at all. M10's dot-grid note is the precedent — a per-cell draw call behind the
ink is where rule 7 says nothing heavy may go, and the fix is to stop before the count is large
rather than to draw it faster.

---

## 2. What snapping means, per kind

`SnapGrid.snapped(placement)` is the one entry point, and it splits because the two kinds are
genuinely different edits:

- **A segment is its two endpoints.** They are recovered from the placement, snapped independently
  and re-placed. Its angle changes, and that *is* the feature — two intersections at the same y are
  a horizontal line, which is what makes a hand-drawn line come out straight. A snap that would
  collapse both ends onto one intersection is refused: a zero-length line is not a tidier version of
  what was drawn.
- **A closed shape keeps its angle.** Its origin corner goes on an intersection and its extents
  round to whole cells. Straightening a shape somebody deliberately drew at 20° would be a rotation
  nobody asked for; rounding the extents is what makes two boxes drawn roughly the same size come
  out exactly the same size, which is most of what a person turning this on wants.

For a **move**, `SnapGrid.moveBy` composes the finger, the alignment correction and the lattice —
and ⚠️ **alignment wins, per axis**. Both are corrections to the same delta, and applying both
satisfies neither: the selection would land beside the guide line the user can see, which is the one
thing `AlignmentGuides` exists to prevent. Lining up with another object is also the more specific
statement — the lattice is always there, another object's edge is there because somebody put it
there. This is visible in the device walk below (§4, move #1): x took the lattice, y kept the guide.

For a **resize**, `SnapGrid.snappedResize` rounds only the edges the handle actually moved — a LEFT
grip must not quietly move the right edge — and re-applies the minimum, because rounding can cross
it. ⚠️ A corner drag stops preserving the aspect ratio here, deliberately: a grid says edges land on
lines, and one rectangle cannot honour both.

A **point drag** snaps in page space, before the shape's own transform is undone. Rounding in the
shape's space would round to a rotated, scaled grid that nothing draws.

---

## 3. Shapes only

`StrokeStore.gridFor` returns the lattice only when **every** selected object is a shape, and the
gesture reads it once at pen-down (a chip flipped mid-drag must not change what the finger is
already doing, for the same reason `lassoIsRectangle` is latched).

A lattice is a diagram tool. Quantising a drag of somebody's handwriting to 18 pt would move their
writing away from the word it belongs beside, in steps they can see, for a setting they turned on to
tidy boxes. A mixed selection keeps the alignment guides it has always had.

A connector counts as a shape: its transform is derived and re-derived every fold (SPEC §4.8.3), so
a drag of one writes nothing the router honours, and excluding it would be a condition with no
observable effect.

⚠️ The **drawn** endpoints, not the snapped ones, are what `connectorRecordFor` tests against the
shapes under them. A snap can move an end half a cell, and half a cell in the wrong direction is a
connector bound to the box next door.

---

## 4. The device walk

Emulator, `dev.inkstone` debug build, driven over `adb`. Ops read back out of `journal.ops` with
`run-as`; the preference read out of `databases/inkstone.db`. Every draw uses
`input stylus motionevent` — `PalmGuard` accepts only `TOOL_TYPE_STYLUS`.

Each shape row is a **pair**: the same gesture with the chip on and off.

| what | grid off | grid on |
|---|---|---|
| draw-and-hold a slanted line, **ruled** paper | `m = [0.9927, −0.1209, 215.24; 0.1209, 0.9927, 221.18]` — a 6.9° slant, ends off every rule | `m = [1, 0, 215.16; 0, 1, 179.33]` — **no rotation**, both ends at y = **180.0** = 36 + 6×24, a printed rule. x untouched, as ruled paper should leave it |
| draw-and-hold a rectangle, **grid** paper | `w = 96.10, h = 65.02` at `(167.34, 102.32)` | `w = 90.0, h = 72.0` at `(360.0, 108.0)` — 5 × 4 cells, corner on an intersection |
| drag the rectangle (guide in range) | — | `xf → (396.0, 131.34)`: x on the lattice (22 × 18), y held by an alignment guide — the moved box's centre is exactly another rectangle's bottom edge |
| drag it into empty space | — | `xf → (450.0, 252.0)` — 25 × 18 and 14 × 18, both axes |
| drag a corner **point** of the rectangle | — | `pts = 0:0, 108:0, 90:72, 0:72` — the dragged vertex at page `(558, 252)` = 31 × 18, 14 × 18 |
| the chrome, **blank** paper | page is plain white | vertical and horizontal lines every **75 px** at that camera = 17.9 pt ≈ the 18 pt default, measured off a screencap pair |

The GRID chip writes `ink.gridSnap` to the settings table on the tap, so the chip → state → surface
path is checked by reading the row back rather than by looking at a highlight.

⚠️ **A single shape has no frame grips**, so `snappedResize` was not reached by the walk: `gripAt`
hides the eight frame handles whenever a shape offers point handles, and the corner the walk grabbed
was a *vertex*, which writes the record instead. That is the correct behaviour and it means a
single-shape "resize" is the point drag above. `snappedResize` is unit-tested only — see §6.

---

## 5. A stub that had been inert for a milestone

`EditorPrefs.gridSnapEnabled`, its codec entry (`ink.gridSnap`) and an `InkUiState.pageHasGrid`
beside it already existed, with a KDoc referring to a `PageGrid` type that does not exist in the
tree. Nothing read any of it: no chip, no assignment in `InkScreen`, and nothing in `:android:ink`.
The preference round-tripped through the settings table perfectly and did nothing to a shape —
definition-of-done #6's shape exactly.

`pageHasGrid` is deleted rather than left: the chip is offered on every page now (§1), so nothing
needs the answer. Its KDoc's plan — disable the chip where the paper has no ruling — is recorded
above as the alternative that was rejected, not lost.

---

## 6. Per-claim status

CLAUDE.md: *"a checklist tick belongs to a claim, not to a file existing."*

| claim | status |
|---|---|
| the lattice matches the drawn template, per axis and origin | **automated** — `SnapGridTest`, 23 tests |
| a segment snaps by its endpoints; a collapse is refused | **automated** + **device** (line pair, §4) |
| a closed shape keeps its angle, corner on an intersection, extents in whole cells | **automated** + **device** (rectangle pair, §4) |
| a move snaps the box's top-left, and an alignment guide wins its own axis | **automated** + **device** (both move rows, §4) |
| a point drag snaps in page space | **automated** (via `SnapGrid.snap`) + **device** (vertex row, §4) |
| a resize rounds only the edges its handle moved, and keeps the minimum | **automated only** — the frame grips are unreachable for a single shape (§4) |
| shapes only; a mixed selection is untouched | **written**, read by inspection — `StrokeStore.gridFor` cannot be built in a JVM test (`StrokeStore` needs `androidx.ink`'s native code) |
| the chrome draws on paper with no lattice of its own, and not on paper that has one | **device** for the first half (screencap pair, §4); the second half is `drawnByThePaper`, unit-tested, not re-checked on screen |
| the chrome never reaches a thumbnail or a published PDF | **by construction** — it is a `View`, and `TemplateBackground` is untouched. Not asserted anywhere |
| it feels right under a hand at these pitches | **unproven** — the tablet, not the emulator |

---

## 7. detekt

`LargeClass` now fires on `InkSurfaceView` and `SelectionController`. Both were a handful of
statements under detekt's default 600 before this change and both are over now — confirmed by
stripping exactly these additions and watching the rule go quiet, then putting them back.

Two of the additions were moved out to where they belonged anyway (`SnapGrid.moveBy` in
`:core:model`, `StrokeStore.gridFor` in `SelectionQueries`), which was not enough. The threshold is
configured at **700** in `config/detekt/detekt.yml` with the same argument `TooManyFunctions`
already makes about these two classes: everything with state of its own has been extracted, what is
left is `MotionEvent` dispatch and one gesture's state, and splitting either by statement count
would put half of an input transition in another file. 700 rather than "off" — about one more
feature's worth of headroom, so the next thing to cross it has to make the argument again.

---

## 8. The buttons on the bar that appears when a shape is selected

> *"you should be able to change things in that UI that shows up when you select an object so like
> it should have buttons for makeing it a perfect circle for example or perfect triangle should be
> able to say if its an arrow or not if its a line and of course the gid snaping"*

Three chips, beside the existing `Fill` / `No fill` / `Rounded` ones and gated the same way — on
what the selection can actually **do**, never on it merely containing shapes.

| chip | shown when | writes |
|---|---|---|
| `Circle` / `Square` / `Equal` / `45°` / `Regular` | a selected shape is not already regular | one absolute `SetTransform` per shape, plus a cleared vertex list where there is one |
| `Arrow` (a toggle) | a selected **segment** that is not a connector | one `SetTextContent` carrying `ShapeRecord.arrow` |
| `Grid` | any shape is selected | nothing — **it toggles the grid mode**; see §9 for what it was and why it changed |

### The regularise chip is named after what it produces

"Regular" is the concept and nobody thinks in it. `ShapeSelectionCounts.soleKind` publishes the one
kind in the selection so `:app` can say **Circle** over an ellipse and **Equal** over a triangle;
`Regular` is only what a mixed selection gets. The label is `:app`'s to choose — a word is not a
fact about the selection.

⚠️ **It disappears the moment it would do nothing.** `ShapeRegular.regularised` answers null for an
already-regular shape and `isRegular` is *that same call*, so the chip cannot be offered for a shape
it would not change and cannot write an op that changes nothing. Both matter: `Undo.inverseOf` gives
a `SetTransform` an inverse whatever it carries, so a no-op write is a perfectly undoable entry that
does nothing, and the next undo press would be eaten by it.

### What "regular" means, and why it is a transform

A shape's `widthPt`/`heightPt` are **payload** — first-write-wins — and its live size is those
extents through its absolute transform. So making a circle is a *scale about the shape's own centre*
(`ShapeRegular`), the same op a resize writes, merging the same way. Re-creating the object at square
extents would mint a fresh `ObjectId` and take its erased bites, its fill and every connector
attached to it along with it.

- **Rectangle → square, ellipse → circle**, at the **mean** of the two sides. The larger extent would
  grow every shape somebody regularises, which reads as the button moving things.
- **Triangle → equilateral**, which is `height = base × √3/2` and *not* a square box: `ShapeGeometry`
  draws an apex-at-top-centre triangle, so a square box gives one visibly too tall.
- **Line/arrow → the nearest 45°**, about its own midpoint, at the length it already has. A segment's
  box is a rotated band whose height is the pen's width, so squaring it would give a line as thick as
  it is long.

⚠️ The vertex list goes with it. A shape whose corners have been dragged draws from
`ShapeRecord.points` and not from its box, so squaring the box alone would be a chip that visibly
does nothing on exactly the shapes somebody has been fiddling with. "Make it a perfect circle" *is*
the request to go back to the kind's own outline — and it is one undo press away.

### The arrowhead is an override register, not a change of kind

`ShapeKind` is payload, so *"make this line an arrow"* cannot rewrite it without destroying the
object. `ShapeRecord.arrow` is a nullable override — null means *"whatever the kind says"* — which is
`SetStyle`'s pattern exactly, and starts null for `SetStyle`'s reason: duplicating the created-as
value into a register would give two sources that drift. `DryShape.hasArrowHead` is the single place
the question is answered, and it already has two readers (the screen and the published PDF), both
through `arrowHead`.

A build that predates the `ah` key keeps the bytes, re-encodes them verbatim and draws the head the
*kind* implies — `bend`'s and `points`' degrade, for the reason a sixth `ShapeKind` was refused in
docs/M8-CONNECTORS.md §1.1.

⚠️ **Connectors are left out**, and not because a head would not draw on one — `ConnectorRouting`
re-derives the geometry every fold and a head follows the route perfectly well. They are left out
because the route chips already own a connector's ends, and two chips disagreeing about one object is
worse than a missing one.

### The `Grid` chip is the action, not the mode

`SelectionController` now carries `paperGrid` (the paper's own lattice, always) and `gridSnapping`
(whether live gestures use it) rather than one field. Folding them together meant the chip did
nothing until the GRID mode was switched on — a button that looks broken at the moment it is most
wanted, which is tidying a diagram that was already drawn freehand.

For a shape with dragged vertices it snaps **the vertices** rather than the box
(`SnapGrid.snappedPoints`), for the reason regularise clears them: the vertices are what is on the
page. Unlike regularise it does not discard them — the request there is to move what exists onto the
lattice, not to start again.

### The device walk

Same instruments as §4: chips found with `uiautomator dump`, ops read out of `journal.ops`.

| press | before | after |
|---|---|---|
| `CIRCLE` on a drawn oval | 143.14 × 71.57, rotated 3.72° | **107.352 × 107.352**, rotation 3.715° and centre `(286.875, 174.037)` both unchanged |
| `EQUAL` on a drawn triangle | base 143.44, height 95.63 | sides **126.928 / 126.928 / 126.928**, ratio 0.86603 = √3/2 |
| `ARROW` on a line, twice | no record | `is1;ah=1`, then `is1;ah=0` — with the default-record baseline written first, in the same gesture |
| `45°` on a line at 8.61° | — | angle **0.0000°**, length 145.314 unchanged, midpoint `(239.18, 268.59)` unchanged |
| `GRID` on that line, **live snapping off** | ends at `(166.52, 270)`–`(311.84, 270)` | `(162, 270)`–`(306, 270)` = 9×18, 15×18, 17×18, 15×18 |

Two things the dump showed that a screenshot would not: for a selected **line** the bar offers
`45°`, `ARROW`, `GRID` and no `Fill` (a line has no interior), and the `45°` chip **disappears** once
the line is straight — which is also how you can tell the press landed.

⚠️ **Not verified: that the arrowhead is drawn.** The record is written and `DryShape.hasArrowHead`
is what `PagePainter` and `InkStrokeOutliner` both read, but the ink surface renders garbage on an
x86_64 emulator (CLAUDE.md), so whether the barbs appear belongs to the tablet.

### detekt, again

`InkSelection` reached 13 functions against a default `thresholdInInterfaces` of 11. It is a facade
with one method per button on the selection bar, and collapsing them into fewer, wider methods
(`apply(action, argument)`) would make the wrong call representable — `NoteSession.apply`'s deleted
`undoable` flag is this repo's standing lesson about that. Configured to 15 in
`config/detekt/detekt.yml` with that argument; another three and the bar itself needs the review.

---

## 9. Two reports, and what they changed

> *"a few problems if you make it a perfect square for example the line sizes get messed up and for
> the grid it just snaps the points to an invisible grid instead when you click that it should make
> it so that you now have a grid that your things snap to like a toggle for that"*

### ⛔ Regularising stretched the pen, because it was a scale

The first version of §8 made a 2:1 oval into a circle with one absolute `SetTransform` carrying
`scale(0.75, 1.5)`. The geometry was right and the ink was wrong: `PagePainter.paintObject`
**concatenates an object's matrix onto the canvas** and then strokes with a paint of one width —
deliberately, so that a move is a matrix and never a rewrite of the geometry (SPEC §3.5, §5.4) — so
a non-uniform matrix scales the *pen* along with the shape. The circle came out twice as thick at
the top as at the sides.

It is now the **outline** that changes: `ShapeRegular.regularise` returns
`ShapeRecord.points` for a closed shape and leaves the transform completely alone. The vertex list
is already the outline whenever it is present (`ShapeGeometry.outline` → `polygon`), the payload
extents are untouched, and a matrix nobody wrote cannot distort a stroke it was not distorting a
moment ago. A **segment** still moves its box, because its regularisation is a *rotation* — rigid,
so it cannot stretch anything, and writing its ends as vertices instead would leave the band that a
tap and a lasso measure against pointing the old way.

Three details fell out of the change:

- The target is built in **page** space and mapped back through the transform's inverse, so a shape
  carrying a rotation gets its square turned to match and one carrying an anisotropic matrix gets
  the oval vertex list that draws a circle.
- The box's two axes are **orthonormalised** first. A shape resized by an edge handle and then
  rotated has a skewed matrix, and a "square" laid out along two axes that are not at right angles
  is a rhombus — regular by the arithmetic, visibly not by eye.
- `isRegular` now reads the **live** vertex list, which is what makes a second press a no-op and the
  chip disappear after the first.

⚠️ **What this still cannot do:** make an already-squashed shape's ink even again. A shape resized
by an edge handle carries an anisotropic matrix from *that* resize, and its stroke is uneven before
this chip is pressed and equally uneven after. That is a property of resizing, and changing it means
changing how every shape in every note is painted.

### The `Grid` chip is a toggle for the mode, not a one-shot

> *"it just snaps the points to an invisible grid"*

Exactly what it did, and the word that matters is **invisible**: the chrome that draws the lattice
on paper with none of its own (§1) is gated on the mode, so a chip that snapped once and left the
mode off moved the shape onto lines nobody could see.

It is now the same switch as the dock's `GRID`, put where a selected shape is: press it and the
lattice appears **and** what you drag lands on it. The one-shot action is gone —
`InkSelection.snapToGrid`, `SnapGrid.snappedTransform` and `SnapGrid.snappedPoints` are deleted
rather than left unreachable, and `SelectionController`'s `paperGrid`/`gridSnapping` split (which
existed only so the one-shot could work with the mode off) is back to a single `grid` field.

### The device walk

| press | wrote |
|---|---|
| `CIRCLE` on a 143.14 × 71.57 oval | **one `txt` op**, `pts=71.568:-17.892, 125.244:35.784, 71.568:89.46, 17.892:35.784` — the four quadrant points of a circle of radius 53.676 (the mean of the two semi-axes) about the box's centre. **No `xf` op at all**, which is the fix |
| the same chip, afterwards | it is **not on the bar** — the shape is regular, so nothing offers to change it |
| `GRID` in the selection bar | `ink.gridSnap` false → **true**, and **zero ops** — it is a mode, not an edit |
