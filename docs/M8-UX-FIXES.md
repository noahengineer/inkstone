# M8 follow-up — shape snapping, image manipulation, and a real crop tool

Four complaints from using M8b/M8c on the tablet, verbatim:

> the hold to snap is so bad i can like only do ovels/circles and it does not let me adjust it
> after. images are also very bad, you cant move/translate without using the select tool which does
> not even work on images half the time and the cropping is horrible just have a normal crop tool
> where you drag from the corners not some weird draw over the parts you cut

All four were real, all four had a mechanism, and **three of them were exactly as described** — the
user's diagnosis was better than the code's KDoc in every case. This records what each turned out to
be, because in three of the four the obvious reading (*"the thresholds need tuning"*) was wrong.

---

## 1. ⭐ "I can only do ovals/circles" — the classifier measured the wrong thing

Not tuning. The feature and the property it was supposed to measure were **independent**.

`ShapeSnap` classified a closed gesture by `enclosed area ÷ bounding box area`, matched against
three targets (rectangle 0.95, ellipse 0.785, triangle 0.5). The ratios are facts about the shapes —
but only against a box the shape is *square to*. The box was **axis-aligned**, and for a `w × h`
rectangle at angle θ the axis-aligned box is `wh + (w²+h²)·sin2θ/2`, so

```
fill = 1 / (1 + k·sin 2θ)        k = (a + 1/a)/2,  a = w/h
```

which sweeps the entire feature range as the user tilts their hand. Measured against the shipped
constants (accepted half-width is `FILL_WINDOW × (1 − DEFAULT_CONFIDENCE)` = 0.063, **not**
`FILL_WINDOW`):

| tilt of a hand-drawn square | fill | classified as |
|---|---|---|
| 0–3° | 1.00 → 0.91 | rectangle |
| 4–5° | 0.88 → 0.85 | *nothing* |
| **6–8°** | 0.83 → 0.78 | **ellipse** |
| 15–20° | 0.67 → 0.61 | *nothing* |
| **30–45°** | 0.54 → 0.50 | **triangle** — a 45° diamond scored **1.000** |

And a **circle's axis-aligned box does not change when it rotates**, so its fill is `π/4` at every
angle. The ellipse was the one kind the feature could actually measure; every tilted rectangle fell
through the ellipse window on its way down. *"i can like only do ovels/circles"* is the precise,
correct description of that table.

**Fix:** measure against the **minimum-area** enclosing rectangle (`OrientedBounds` — Andrew's
monotone-chain hull, then rotating calipers in its exact `O(h²)` form). All three ratios become
rotation-invariant by construction: a rectangle is flush with its own minimal box at any angle, a
triangle's minimal box is exactly twice its area (a theorem, not a measurement), an ellipse's is
`4ab` against `πab`. The rectangle target becomes the honest **1.0** — it had been nudged to 0.95 to
meet real rectangles that could never reach it, which handicapped the one kind it was meant to help:
a *geometrically perfect* rectangle scored 0.643 where a perfect ellipse and triangle both scored
1.000, and every degradation moved it toward the ellipse target with 0.039 of no-man's-land between.

⚠️ **This allocates**, and `ShapeSnap.recognise`'s *"no allocation at all"* was narrowed honestly
rather than quietly: a hull needs an index array. It runs **once**, when the hold fires and the user
is already waiting for a shape; `sample` on the detector is still two float writes and a compare, so
rule 7's half that matters is untouched.

The shape is also now **placed at the angle it was drawn** (`ShapeEdit.placeOriented`) instead of
flattened onto the axes. That needed no model change at all — `ShapeGeometry` has always been
local-space with an arbitrary `Matrix3`.

### 1a. Two more mechanisms behind the same complaint

- **The closure test was far too loose, not too tight.** `CLOSED_MAX_GAP = 0.22` let an open **300°
  arc** through (gap/len 0.191) at ellipse-confidence 0.84, so a `C`, a bracket or any large open
  curve held still was eaten and turned into an oval. Now 0.12 — an arc must reach ~322° to count as
  closed, while a 200 × 120 rectangle may still leave a 77 pt gap at its corner.
- ⚠️ **The dwell's own samples were being appended to the gesture.** Half a second of tremor at
  240 Hz piles ~130 points onto one spot, which inflates `pathLength` without moving the endpoints —
  and `ShapeSnap` divides by that length twice. A line's straightness sank below its bar, *and* an
  open gesture's end-gap ratio sank **under** `CLOSED_MAX_GAP`, so holding still made an open arc
  look closed. Both got worse the longer the user held, which is the opposite of what holding longer
  should mean. Samples inside the dwell radius are no longer recorded, which also makes sampling
  uniform in arc length rather than in time.
- **A line's bar was 0.9685, not the 0.93 its constant names.** The confidence ramp ran to a
  perfectly straight line, so clearing `LINE_MIN_STRAIGHTNESS` was not enough to clear the
  confidence gate. The ramp now reaches 1.0 at `LINE_CERTAIN = 0.96`.

---

## 2. "It does not let me adjust it after" — two causes, neither of them the handles

The 8 resize grips and the rotate grip existed and worked. Getting *hold* of the shape was the problem.

- **The snapped shape was not selected, and could not have been.** `ShapeSnapController.snap` minted
  the `ObjectId` **inline inside the `ShapeEdit.create(...)` argument list**, so it was unreachable
  the instant it was used — nothing could select, report or address the shape the user had just
  drawn. It is hoisted and the surface now selects it, so the handles are there without a hunt.
  The **tool is deliberately not changed**: a flowchart is five boxes in a row and stealing the pen
  after each would cost five taps to give back.
- ⚠️ **Tapping the inside of a snapped rectangle selected nothing.** The tap shared the *eraser's*
  predicate, whose rule is that an unfilled outline is empty in the middle — correct for an eraser
  (you can drag one through a hollow box), wrong for a tap. A snapped shape carries no fill
  (`ShapeRecord.None`), so `ShapeHit` skipped the interior test entirely and the user had to land
  within 8 dp of a hairline. `DryObject.tappedAt` splits the two predicates; only `DryShape`
  overrides. `docs/M8-CONNECTORS.md` §9 had listed this as *"a product question nobody has asked
  yet"* — it has now been asked, twice, by the person using it.

**Not fixed, and worth stating:** undoing a snap does **not** bring the freehand ink back. The
stroke is cancelled rather than finished, so no `AddObject` for its geometry is ever built and the
undo entry's inverse is one `DeleteObject`. That is a deliberate M8b design (CLAUDE.md rule 1 — a
created-then-deleted stroke keeps its geometry in the log forever and syncs), and changing it is a
bigger decision than this pass.

---

## 3. "You can't move an image without the select tool, which doesn't work half the time"

Both halves were true, and the first was **structural**, not missing.

- ⚠️ **The Image tool put a full-screen Compose overlay in front of the ink surface.**
  `EditorTool.IMAGE.ownsTouch` was true, so `PageOverlay` covered the `AndroidView` and offered a
  tap and nothing else — `InkSurfaceView.onTouchEvent` received **nothing at all** while the one
  tool that knew about images was active. Dragging an image was not unimplemented, it was
  unreachable. `IMAGE` no longer owns touch and falls through to `InkTool.SELECT`, so move, resize
  and rotate are the code that already worked for every other object.

  That needed a channel: `onSelectionChanged` carried an **`Int`** and nothing else, so *"an image
  is selected"* was unrepresentable in `:app`. `InkSelection.soleImage` answers it at the one moment
  it is known to have changed — the argument `shapeCounts` already made.
- ⚠️ **A tap became a lasso after 3.0 page-points of *path*.** `isTap` required
  `points.size < TAP_MAX_POINTS` with `TAP_MAX_POINTS = 3` and a point appended every
  `LASSO_STEP_PT = 1.5`, so two hops of hand tremor while pressing reclassified the tap. The
  resulting three-point squiggle contained nothing, so the tap selected nothing **and cleared the
  existing selection** — intermittently, with no feedback. It bound long before the 8 dp *net* slop
  beside it (~6 pt at fit-width), which was therefore decorative. Now measured on **path length in
  dp** (`TAP_PATH_SLOP_DP = 28`); a guard is still needed, because a deliberate tiny loop returns to
  where it started and net displacement alone would call it a tap.
- ⚠️ **An image offered a lasso only its four corners.** `Lasso.selects` takes a **majority** of
  the points offered, so a lasso drawn *inside* a photo enclosed none of them and a band across its
  middle enclosed none either — while the identical gesture over handwriting worked, because a
  stroke offers every outline vertex. `DryBox.forEachPagePoint` walks its edges now (8 per side).
- **Two tools disagreed about where a rotated image was.** The surface maps the tap through the
  object's inverse and tests the true quad; `:app`'s `tapImage` used `pageBounds()`, the
  **axis-aligned hull**. The tool that owned images was the one that was wrong. `tapImage` is gone —
  selection is the single answer.

---

## 4. "The cropping is horrible" — and the model had been right all along

`ImageCrop.Rect`'s own KDoc reads *"the common case, and **the one with drag handles**"*. There were
no handles anywhere: `CropMath.handlesOf` had exactly one caller in the repo and it was a unit test.

The crop was *arm a chip, then draw the region* — and being one-shot made **adjusting a crop
unrepresentable**, not merely awkward. The gesture ended, the mode cleared, and changing your mind
meant drawing a whole new region over a picture that was by then showing you the *previous* crop's
pixels — which is why the second crop selected the wrong ones.

`CropOverlay` replaces it: the **whole uncropped picture**, what is being cut shaded, eight grips on
the kept region, corners moving x and y independently (⚠️ deliberately **not** `SelectionHandle.resize`,
whose corners preserve aspect by projecting onto the box diagonal — *"take the top off"* is the
commonest crop there is and an aspect-locked corner cannot say it). Nothing is written until Done.

Two real bugs fell out of building it, both invisible from inside:

- ⚠️ **Every crop stretched the picture.** `SetFormat` says which pixels; the renderer then scales
  them to fill the object's *unchanged* box, so a crop written alone **zooms** rather than crops.
  `ImageEdit.crop` emits `SetFormat` **and** an absolute `SetTransform` as one gesture. The frame is
  always recomputed from the whole-source frame (`uncroppedFrame`), never composed onto the previous
  crop's — which is what keeps it widen-able all the way back to no crop, the property that
  distinguishes a crop from an erase (rule 1).
- ⚠️ **The `pointerInput` was keyed on the value it was editing.** `.pointerInput(id, camera,
  session.region)` reads naturally — the gesture wants the current region — but the region changes
  on every delta the gesture reports, so Compose tore the handler down and restarted it **mid-drag**.
  The corner moved one frame's worth and stopped. The symptom reads exactly like a hit-test bug and
  is not one. `rememberUpdatedState` + a `() -> CropSession`. **Found on the emulator; no test could
  have caught it**, because the whole `dev.inkstone.app.ink` package has no JVM tests at all.
- **White chrome on white paper.** Every photo editor's crop frame is white, because it sits on a
  photograph. This one sits on a *note*. It is the selection blue now.

---

## 5. What was checked, and how

⚠️ **My first three device measurements measured nothing.** `uiautomator dump` piped through
`adb shell cat` produced a **0-byte file**, and every "nothing is selected" it returned was vacuous —
including one I briefly believed was a failure of the interior-tap fix. Screenshots were the reliable
instrument. Same shape as M4's PDFs: *the tool that says nothing is wrong may not be looking*.

| Claim | Evidence |
|---|---|
| Rectangle at 20° → `RECTANGLE` | journal: `"sh":"RECTANGLE"`, run on emulator-5554 |
| …at the angle drawn, not axis-aligned | `m=[0.9396,-0.3422,…,0.3422,0.9396,…]` → `atan2` = **20.02°** |
| …with the drawn extents, not the hull's | `w=119.69 h=76.61` → ratio 1.5625, exactly the 500×320 drawn (the axis-aligned hull would be 1.35) |
| Snapped shape is selected immediately | "1 selected" + grips, in the frame it appeared |
| Hollow interior tap selects | selected from a clean nothing-selected baseline; `rev` unchanged, so selection wrote nothing |
| Image drags under the **Image** tool | moved on screen, `rev 8` — a `SetTransform` was written |
| Crop handles drag, cut region shades | corner followed the pen; `rev` **unchanged** during the drag |
| Crop shrinks the frame, does not zoom | `fmt c=0:0:0.641:0.843` + `xf m=[0.641,0,95.35,0,0.843,103.21]`; on screen the right edge moved 1227→899 px, and 915 × 0.641 = 587 ✅ |
| JVM suite | **877** tests, 0 failures, `testDebugUnitTest` across all modules, forced with `--no-build-cache` and timestamps checked |

**Not checked:** the physical Galaxy Tab (`adb devices` reports it `unauthorized`), so
definition-of-done #4 is unmet and every feel judgement here — the dwell time, the confidence bar,
whether 28 dp of tap-path slop is right for a real hand — is still owed a hand. SPEC §4.8.7 is
explicit that a fixture cannot settle any of them, and an emulator is a fixture with a screen.

**Also not checked:** the PDF. A snapped shape now carries a rotation and a cropped image now
carries a scaled frame; `PdfPlacement` has always taken an arbitrary matrix, and `ShapeGeometry` is
the single source of the outline (M4's lesson), so both *should* publish — but "should" is what M4
said too, and nobody has opened the output in a viewer that is not PdfBox.
