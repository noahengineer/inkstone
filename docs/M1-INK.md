# M1 — Ink

What was built, what the ink library actually turned out to be, and exactly how
to run the acceptance measurements.

Companion to `docs/M0-VERIFICATION.md`. Everything below was measured against
the shipped `androidx.ink:*:1.0.0` artifacts on **2026-08-05**, not recalled.

---

## 1. The spike: does `androidx.ink` 1.0.0 predict internally?

**SPEC Appendix B #2. Answer: no. `input-motionprediction` is KEPT.**

Three independent pieces of evidence from the shipped artifacts:

1. **The POM.** `androidx.ink:ink-authoring-android:1.0.0` declares
   `ink-rendering-android`, `ink-strokes-android`, `ink-brush-android`,
   `ink-geometry-android`, `ink-nativeloader-android`, `androidx.collection`,
   `androidx.core`, `androidx.fragment`, `androidx.graphics:graphics-core` and
   `espresso-idling-resource`. **No `androidx.input:input-motionprediction`.**

2. **No predictor exists in the stack.** `ink-authoring`, `ink-strokes`,
   `ink-brush`, `ink-geometry` and `ink-rendering` contain no Kalman filter, no
   extrapolator, and no class with `predict` in its name. By contrast
   `input-motionprediction` ships `KalmanFilter`, `PointerKalmanFilter`,
   `SinglePointerPredictor`, `MultiPointerPredictor` and a
   `SystemMotionEventPredictor` that delegates to the platform's
   `android.view.MotionPredictor`.

3. **The API takes prediction as an input.** Prediction is a parameter,
   supplied by the caller, at both levels:

   ```
   InProgressStrokesView.addToStroke(event, pointerId, strokeId, prediction: MotionEvent?)
   InProgressStroke.enqueueInputs(realInputs, predictedInputs)
   ```

   and ink tracks the two separately — `getRealInputCount()` /
   `getPredictedInputCount()` — which is the bookkeeping that lets it discard
   the predicted tail when the next real event arrives.

### What this means

There is exactly **one** predictor in the pipeline, and it is
`androidx.input:input-motionprediction` 1.0.0. There is no double prediction to
remove, so the failure mode the spike was guarding against — visible overshoot
on direction changes — does not arise from this combination.

Point 3 also answers a question SPEC §3.3 raises separately: *"predicted
segments are speculative; un-draw them when real events arrive. Verify no
predicted tail survives into the dry layer."* Ink does this itself, because it
knows which inputs were predicted. Our code does not have to un-draw anything,
and must not try.

On Android 14+ (the target), `input-motionprediction` routes to the platform's
own `MotionPredictor` rather than its bundled Kalman filter, so the prediction
is the same one the system compositor was tuned against.

`InkSurfaceView.predictionEnabled` is an A/B switch, exposed in the toolbar's
**Measure** panel, so the difference can still be felt on the device.

---

## 2. Where the SPEC turned out to be wrong

Seven things. **§2.1 is the big one** — it ends with `StockBrushes` removed
entirely — and it is half library finding, half device finding.

`androidx.ink` 1.0.0 restricts far more of its API to `LIBRARY_GROUP` than the
"stable 1.0.0" framing suggests (§2.1, §2.2). `@RestrictTo(LIBRARY_GROUP)` is a
**lint contract, not a runtime one** — the code runs — but Android lint fails the
build on it, and Google reserves the right to change those members without
deprecation.

**Four of the five needed the tablet to find**, and none was reachable from a
desk: two rendering defects that only show up on glass — both reported in the
same words, *"the line goes blurry when I lift the pen"*, and only one of them
was — the brush taper in §2.1, and the erase model in §2.5, which took three
attempts because the first two were wrong in ways that only show up in your
hand. That is what CLAUDE.md's definition of done means by *"used on the
actual Galaxy Tab, not just an emulator"*.

### 2.1 `StockBrushes` is unusable, twice over (SPEC §3.4)

**First finding.** Of the stock families (`marker`, `pressurePen`, `highlighter`,
`dashedLine`, `emojiHighlighter`, `pencilUnstable`), **only `pencilUnstable`
responds to tilt**, and it is `@RestrictTo(LIBRARY_GROUP)`. So is **every
`BrushFamily` constructor and `BrushFamily.builder()`**, on top of
`@ExperimentalInkCustomBrushApi` — a custom family cannot be built either.
Tilt itself is never lost (`StrokeInput` carries `tiltRadians`), but nothing
maps it to anything visible, and ROADMAP M1 makes "tilt visibly affects Pencil"
an acceptance criterion.

**Second finding, from the tablet, and the decisive one.** The stock brushes
wire `DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE` and
`DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE` into `SIZE_MULTIPLIER` and
`OPACITY_MULTIPLIER` — ten behaviour entries across the set. Those are the
start/end tapers, and they are parameterised **along the stroke**.

That makes a stroke un-cuttable. Partial erase rebuilds each surviving piece as
a new `Stroke` (§2.5), and a new stroke re-runs its width and opacity profile
from its own start — so erasing through the middle of a line gave two ends that
faded to a point instead of two blunt cuts. It came back as *"the erasing is not
that good … you seem to be using a spline system"*, which was the right
instinct pointed at slightly the wrong layer: it is not the geometry engine, it
is ten lines of content in Google's sample brushes.

**Inkstone therefore defines its own families** (`InkstoneBrushFamilies.kt`),
around one rule: **no behaviour may depend on distance or time along the
stroke.** Everything is driven by an instantaneous property of the input —
pressure, tilt, speed — so a fragment renders exactly as that part of the
original did. A fountain pen still starts thin, for the physical reason rather
than a scripted one: pressure ramps as the nib lands, and pressure drives width.

This widens the restricted-API suppression from one function to the brush layer.
Taken deliberately, because it is also what `androidx.ink` was chosen for.
`BrushBehavior` in 1.0.0 is a **dataflow graph**:

| | |
|---|---|
| Nodes | `SourceNode` `ConstantNode` `NoiseNode` `BinaryOpNode` `InterpolationNode` `DampingNode` `ResponseNode` `FallbackFilterNode` `ToolTypeFilterNode` `TargetNode` `PolarTargetNode` |
| Curves | `Predefined` `Linear` (piecewise) `CubicBezier` `Steps` |
| Sources | ~38, incl. pressure, tilt X/Y, orientation, speed, velocity, acceleration (forward and lateral separately), and `INPUT_SPEED_IN_CENTIMETERS_PER_SECOND` — device-independent |
| Targets | size/width/height, slant, pinch, rotation, corner rounding, position offset (x/y/forward/lateral), **hue, saturation, luminosity**, opacity |
| Paint | multiple `TextureLayer`s with `BlendMode`, `TextureMapping`, `TextureWrap`, animation, plus `ColorFunction`s |
| Tip | `particleGapDistanceScale` / `particleGapDurationMillis` — stamping and spray brushes |
| Family | a list of coats, so one mark can be several layered strokes |

That is an art-application brush engine. `StockBrushes` is a thin sample on top
of it, and using the real thing is the point of the dependency. **Revisit the
suppression when the `androidxInk` pin moves** — if the API is public by then,
delete it.

**Also added: the input model is a setting.** `BrushFamily` takes
`SPRING_MODEL` (default — spring dynamics, smoother, slightly laggier) or
`EXPERIMENTAL_NAIVE_MODEL` (tracks the samples more literally). This is the
"does it follow the nib or chase a spline" knob. It is pure taste and cannot be
settled off-device, so it is a switch in the Measure panel rather than a
decision recorded here.

Still not delivered: Pencil's **grain texture**. It needs a texture asset of
ours; `BrushPaint.TextureLayer` is where it goes. Not an M1 criterion — M10.

### 2.2 Other restricted members, and what was done instead

| Restricted member | What we do |
|---|---|
| `StockTextureBitmapStore` | `NoTextures`, a three-line `TextureBitmapStore` returning null. No brush we use is textured yet; Pencil's grain will register here. |
| `Box.toImmutable()`, `StrokeInputBatch.toImmutable()`, and every other `toImmutable()` | Copy the four floats into `PageBounds`; build a fresh `MutableStrokeInputBatch` per synthetic stroke and pass it to `Stroke` directly. Cheaper anyway. |
| `AffineTransform.getM00()`…`getM21()` | Not needed — the camera affine comes from `:core:model`, and ink is handed `android.graphics.Matrix`. |
| `CanvasStrokeRenderer.create(boolean)` and `create(boolean, store)` | Use the unrestricted single-argument `create(textureStore)`. |
| `InProgressStrokesView.setLatencyDataCallback`, `LatencyData` | Suppressed, narrowly, in `LatencyStats.kt`. This is measurement scaffolding for the §3.6 gate, not shipping behaviour: if a future ink version removes it, the HUD goes and the ink stays. |

### 2.3 Tessellation tolerance is a display quantity, not a brush quantity

Found on the tablet, not in a test. `Brush.epsilon` is the geometric
simplification tolerance in stroke units, and M1 first shipped it as a fraction
of the brush width — so the *absolute* error grew with the brush, and **a wide
pen drew visibly faceted curves.**

This was also, wrongly, taken to explain the softening on lift: wet ink is
tessellated incrementally while the finished `Stroke`'s mesh is simplified to
`epsilon`, which is a real difference and a plausible story. It was the wrong
story — see §2.4. Worth recording, because the two symptoms arrived in one
sentence and the tempting move was to assume one cause.

Epsilon is now `0.05 px / pixelsPerPoint` — a fraction of a *physical pixel*,
converted into page points, independent of brush width. Google's own ink samples
use `0.1` with stroke space in pixels; this is deliberately half that, because
unlike those samples this app zooms, and a stroke tessellated once at 100% is
still on screen at 1600%.

**This changes vertex counts, so §4.1's frame-rate run needs redoing.** Finer
tessellation is more triangles per stroke; whether 5,000 strokes still hold the
budget is now an open question rather than a settled one.

It fixed the faceting on wide brushes. It did **not** fix the softening on lift,
which had a separate cause — §2.4.

### 2.4 The antialiasing feather is sized by a matrix you pass in

The other half of "the line goes soft when you lift the pen", and the one that
actually caused it — §2.3's tolerance fix did not move this symptom at all.

`CanvasStrokeRenderer.draw(canvas, stroke, strokeToScreenTransform)`'s matrix is
**not a drawing transform** — the canvas carries that. Its linear component is
extracted (`Matrix.getValues`) and handed to the mesh shader as a uniform;
`CanvasMeshRenderer` calls the field `objectToCanvasLinearComponentScratch` and
includes it in the per-mesh cache key. It sizes the **antialiasing feather** in
stroke units. Under-report the scale by N and every edge is feathered N times
too wide — the stroke lands at the right size, in the right place, and looks
out of focus.

`ViewStrokeRenderer` computes that matrix for you from `Canvas.getMatrix()`,
which is the documented path and what the dry layer used first. Our dry layer
renders page-space geometry into a canvas scaled by the camera (~4.4× at
fit-width on the target tablet), so if the concatenated scale does not reach
that uniform, the feather is ~4.4 px instead of ~1 px.

The wet layer was never affected, which is the diagnostic that matters:
`InProgressStrokesView` knows its own transforms and never asks the canvas. Wet
sharp + dry soft = the feather, not the geometry.

`DryStrokesView` now passes the page→view matrix explicitly. It is already
computed for the `canvas.concat`, it is exact, and it removes a dependency on a
deprecated method whose hardware-canvas behaviour is not worth betting the most
important surface in the app on. Only the linear component is read, so the
view's offset on screen is correctly irrelevant.

### 2.5 Erasing an area, after two wrong models

Three attempts, and the first two are worth keeping because both looked correct
until they were held.

**Attempt 1 — whole-stroke.** What SPEC §3.5 originally specified. Rejected on
contact: deleting an entire line because the eraser clipped its tail is
startling, and nothing else on the device behaves that way.

**Attempt 2 — rebuild from surviving input samples.** Delete the stroke, rebuild
each surviving run of inputs as a new `Stroke`. This is the `DeleteObject +
AddObject(fragments)` shape SPEC §3.5 asks for, it passed its tests, and it is
still the wrong model:

> Sample-based erasing **cannot erase part of a line, only cut it.** It removes
> whole input samples, so it always takes the full width of the stroke, and only
> where a sample happens to be. Shaving the side off a thick stroke, or taking a
> small bite out of one, is not expressible as "these samples are gone".

It also inherited §2.1's taper problem, since a rebuilt fragment re-runs the
brush from its own start.

**Attempt 3 — erase an area.** The eraser records the region it swept, per
stroke, and that region is subtracted at draw time. The eraser's precision is
now its own geometry rather than the stylus sampling rate, so partial-width
erasing simply works.

Three details that matter:

- **`DST_OUT` into a bounded layer, not `clipOutPath`.** Clipping is cheaper —
  no offscreen — but its edge is only as good as the hardware renderer's clip
  antialiasing, and this app has already been through one round of *"the ink
  looks soft"* (§2.4). A blend gives the erased edge exactly the antialiasing
  the stroke's own edge gets. Only strokes that have actually been erased pay
  for a layer, and it is bounded to the stroke.
- **The stroke is never modified.** Inputs and mesh stay exactly as drawn.
  That is strictly stronger than the no-mutation rule it replaces: it satisfies
  CLAUDE.md rule 1 at the stroke level, and makes un-erase a matter of dropping
  region entries rather than reconstructing geometry that is gone.
- **Capsules are the durable form**, not the flattened path. Five floats each —
  segment plus tip radius, in page space — resolution-independent and replayable
  anywhere. The `Path` is a render cache.

⚠️ **This adds an op to SPEC §4.2, and M2 owes it:**
`SetErasedRegion(target, absolute: List<EraseCapsule>)` — **absolute**, LWW, like
every other register in that vocabulary (rule 8). §4.2 says to define the
complete vocabulary in M2 *so it is never extended once sync is live*; this is
that instruction paying off, with the op identified before M2 rather than after
M6. M8a's "partial erase splits a stroke into fragments" is superseded — nothing
splits.

**Attempt 3a — and it froze and killed the app.** The first area version
flattened each capsule into a filled outline, unioned them into one `Path`, and
at gesture end ran `Path.op(region, DIFFERENCE)` against the stroke's silhouette
to decide whether the stroke was gone. A few seconds of scribble-erasing at
240 Hz is thousands of capsules, so that path carried tens of thousands of
segments and Skia's PathOps on it is a hang. Erasing itself felt fine; lifting
the pen killed it. Two fixes, both structural:

- **Store the eraser's centre line, not its outline.** Regions now keep a
  polyline grouped into runs of constant tip radius — a new run only when the
  zoom changes, so a normal gesture is one run — and draw it with a round-capped
  stroking paint. Skia expands it to the swept capsule at draw time, caches the
  tessellation, and resolves self-overlap in one coverage pass so overlapping
  passes do not double-erase at the crossings. No boolean ops anywhere, and the
  stored path stays proportional to the sample count.
- **Replace the fully-erased test with bounded coverage checks.** Region bounds
  first (four compares), then every input point, then every outline vertex, each
  with early exit — so a stroke that is obviously still visible is rejected
  almost immediately. Both point sets are required: inputs alone would call a
  thick stroke erased when a thin eraser cleared only its centre line, which is
  exactly the case this design exists to support; outline vertices alone would
  call it erased when a ring-shaped erase left the middle. A false positive here
  silently destroys ink, so the two have to agree.

The prune also carries a **work budget**: past it, strokes simply are not pruned
this pass and get another chance at the next gesture end. Degrading into "an
invisible stroke lingers" is acceptable; degrading into a frozen UI is not.
`aLongEraseGestureDoesNotHangOnPrune` drives 3,000 samples across 40 strokes and
asserts the prune finishes.

### 2.6 The camera pinned the page, and undo did not exist

Two more from the tablet, in the same session.

**Panning was fighting the user.** SPEC §4.5's camera centred any axis that fitted
and hard-stopped at the page edge otherwise, so zoomed out the page could not be
moved at all. Replaced by a leash: pan freely anywhere, with the single rule that
~64 dp of page stays on screen so there is always an edge to grab. Fit modes
still centre — that is what they are for. `Camera.leashedTo` is the rule;
`clampedTo` is gone.

**Undo/redo shipped early**, on a two-finger tap and a three-finger tap. SPEC
§4.6 puts undo in the op-log, which is M2, but M1 accumulated enough destructive
actions to need it. `EditHistory` is a plain stack — the honest form of §4.6's
model when there is no log to append to — with one entry per gesture.

Two things fell out rather than being designed:

- **Undoing an erase is dropping capsules.** Because §2.5 never destroys the
  stroke, there is no geometry to reconstruct. The never-destroy rule has now
  paid for itself twice.
- **It confirms `SetErasedRegion` must be absolute.** Undo writes back the region
  *as it was*; an op carrying only a difference could not express that. That is
  CLAUDE.md rule 8 arriving from a direction the rule did not anticipate.

The tap detector deliberately is not a `GestureDetector` — that has no notion of
how many fingers were involved, which is the entire signal. It watches the same
pointers the camera does and disqualifies itself on touch slop or a 300 ms
timeout, so a pinch or a drag can never be mistaken for a tap, and a real tap
moves the camera by a pixel or two before the fingers lift.

### 2.7 The Compose artifacts (SPEC §3.2 — confirmed, with a correction)

`ink-authoring-android:1.0.0`'s POM lists `ink-authoring-compose`,
`ink-brush-compose` and `ink-geometry-compose` at 1.0.0 in its
`dependencyManagement` block. This corroborates M0's correction: the Compose set
on the 1.0.0 line is **incomplete, not absent** — `ink-rendering-compose:1.0.0`
is the 404. The View-based path is what M1 uses, as SPEC §3.2 requires.

---

## 3. What was built

```
:core:model
  Geometry.kt         Offset / PagePoint / PdfPoint / PageSize / PdfBox — SPEC §4.4's three spaces as types
  Camera.kt           Camera, viewToPage/pageToView, zoom limits, fit modes, clamping — SPEC §4.5
  PdfCoordinates.kt   pageToPdfUser / pdfUserToPage / pageSizeOf, with /Rotate and a non-zero CropBox origin

:android:ink
  InkSurfaceView      the surface: dry layer + InProgressStrokesView, all of SPEC §3.3
  DryStrokesView      finished strokes, page space, camera as a canvas transform
  StrokeStore         the dry layer's contents; both erase modes (SPEC §3.5)
  StrokeSplitter      partial erase, as delete + add(fragments) — never mutation
  EraserController    the erase gesture, historical points included
  CameraGestureTracker pan + pinch, fingers only
  PalmGuard           hover-based palm rejection + FLAG_CANCELED
  InkBrushes          the four brushes of SPEC §3.4
  CameraMatrix        Camera -> android.graphics.Matrix
  LatencyStats        SPEC §3.6's instrument
  SyntheticInk        the 5,000-stroke page the frame-rate gate needs

:app
  InkScreen           AndroidView host
  InkToolbar          minimal floating toolbar + the Measure panel
  ColorPicker         hex + wheel + 12 slots
  InkUiState          in-memory only — M1 has no persistence
```

### SPEC §3.3, row by row

| Requirement | Where |
|---|---|
| `requestUnbufferedDispatch` on `ACTION_DOWN` | `InkSurfaceView.onPointerDown`, on every gesture — it is not sticky |
| **Historical points** | `addToStroke(event, …)` is given the whole `MotionEvent`; ink's `StrokeInputPool.obtainAllHistoryForMotionEvent` walks `getHistorySize()` / `getHistoricalAxisValue()` / `getHistoricalEventTime()` itself. The eraser walks them in `EraserController.extend`. |
| Pressure | read by ink off the event. Synthesising `StrokeInput`s by hand is what loses it. |
| Tilt | ditto — and now *visible*, via the pencil family in §2.1 |
| Orientation | ditto; ink normalises it (`convertOrientationToStrokeInputRadians`) |
| Eraser tip | `TOOL_TYPE_ERASER` in `beginDrawOrErase` |
| Barrel button | `BUTTON_STYLUS_PRIMARY or BUTTON_SECONDARY` — OEMs disagree about which one an S Pen reports |
| Palm rejection | `PalmGuard` (stylus proximity), fingers-never-draw by default, `ACTION_CANCEL`, and `FLAG_CANCELED` on API 33+ |
| Hover preview | deferred to M10 per the SPEC; hover events already feed the palm guard |

### Coordinate spaces (SPEC §4.4, §4.5)

`androidx.ink`'s "world space" **is this app's page space**:

```
startStroke(motionEventToWorldTransform = viewToPage(camera at stroke start),
            strokeToWorldTransform      = identity)
```

so `Stroke.shape` comes out already in page space and nothing converts it after
the fact. This also satisfies SPEC §4.5's *"a camera change mid-stroke does not
retroactively alter the stroke"* **by construction**: `startStroke` captures the
matrix once and `addToStroke` takes no matrix, so later inputs of the same
stroke cannot be reinterpreted through a newer camera. Camera gestures are
additionally ignored while a stroke is live.

### Scope limits held

- **No in-place point-list mutation.** The whole-stroke-only limit was reversed
  during device testing (§2.4), but the rule underneath it was kept: splitting
  is delete + add, so M8a inherits the model rather than replacing it.
- **No persistence.** Close the app, the drawing is gone.

---

## 4. Running the acceptance measurements

Set `JAVA_HOME` to JDK 21 first (see the README — this is the trap that costs an
hour):

```bash
export JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot'
```

Get the build onto the tablet. With USB debugging authorized (`adb devices`
says `device`, not `unauthorized`):

```bash
./gradlew :app:installDebug
```

Otherwise — and this is the usual case, because the "Allow USB debugging?"
dialog is easy to miss and only appears in File transfer mode — copy it over MTP
instead and tap it in **My Files → Download**:

```bash
powershell -ExecutionPolicy Bypass -File tools\send-to-tablet.ps1
```

⚠️ **§4.1 below needs adb regardless.** `dumpsys gfxinfo` has no MTP
equivalent, so the frame-rate gate cannot be measured until USB debugging is
authorized. §4.2's camera comparison and §4.3's manual checks do not need it —
those work on a sideloaded build.

### 4.1 Frame rate with 5,000 dry strokes — `dumpsys gfxinfo`

**Setup.** Open the app, tap **Measure**, tap **Load 5,000 strokes**. Wait for
the "Generating…" line to clear.

Reset the counters, then use the app for 30 seconds — pan, pinch, and write a
few lines over the synthetic ink. Do not idle: `gfxinfo` averages over frames
that were actually produced.

```bash
adb shell dumpsys gfxinfo dev.inkstone reset
```

```bash
adb shell dumpsys gfxinfo dev.inkstone
```

**Read the histogram, not the average.** The output includes:

```
Total frames rendered: N
Janky frames: J (P%)
50th percentile: A ms
90th percentile: B ms
95th percentile: C ms
99th percentile: D ms
Number Missed Vsync: …
```

**Pass / fail.** The Galaxy Tab panel is 120 Hz, so the frame budget is
**8.33 ms** (16.67 ms if you have forced 60 Hz in developer options):

| Metric | Pass | Fail |
|---|---|---|
| **95th percentile** | ≤ 8.33 ms | > 8.33 ms |
| **Janky frames** | ≤ 5% | > 5% |
| **99th percentile** | ≤ 16.67 ms (one dropped frame under load is tolerable) | > 16.67 ms |
| **Number Missed Vsync** | small and not growing | climbing steadily |

The 50th percentile is not the gate — a median of 4 ms with a 95th of 20 ms is a
stutter you can feel, and it is what "sustained 60fps" is actually asking about.

Repeat with **Clear** and no synthetic strokes to get a baseline. If the empty
page is already over budget, the problem is not the dry layer.

### 4.2 Latency versus Samsung Notes — 240 fps capture

The gate SPEC §3.6 actually defines: **≤ 2 frames of additional visual lag
versus Samsung Notes, at 240 fps capture, same device, same stroke.**

1. Put the tablet flat. Use a second phone on a tripod or a stack of books —
   the camera must not move between the two takes.
2. Record at **240 fps** (Slow-mo on most phones; confirm the rate, some default
   to 120).
3. Draw a **fast straight diagonal**, roughly 15 cm, at a steady speed. Speed is
   what makes the gap visible; a slow stroke hides everything.
4. Do the same in Samsung Notes with a comparable pen.
5. Step through both clips frame by frame. Count the frames between **the pen
   tip reaching a fixed reference point** and **ink appearing at that point**.
   At 240 fps, one captured frame is 4.17 ms; a display frame at 120 Hz is two
   captured frames.

**Pass / fail.**

- **≤ 2 display frames behind Samsung Notes → pass.**
- **Within 1 frame after three serious attempts → accept and proceed.** This is
  SPEC §3.6's exit condition, and it is deliberate: Samsung uses vendor-private
  low-latency paths that public APIs may not match. Do not deadlock the project
  at milestone one.

**The in-app number is not this measurement.** The **Measure** panel reports
ink's own `osDetectsEvent → estimatedPixelPresentationTime` in milliseconds.
That is the right instrument for *"did this change help?"* while iterating —
Samsung's number is not measurable from inside our process. Expect roughly one
frame plus panel latency; a mean above ~25 ms with prediction on means something
is wrong before you get the camera out.

Use it to A/B the spike: toggle **Motion prediction** off, draw the same stroke,
compare. With prediction off the perceived lag should get visibly worse and the
mean should rise by roughly one frame.

### 4.3 The manual criteria

| Criterion | How |
|---|---|
| Two-finger tap undoes, three-finger tap redoes | Draw three strokes, two-finger tap twice, three-finger tap once. A pinch or a drag must never trigger either. |
| The page can be moved anywhere | Zoom out until the page is small, then drag it into a corner. It should go, and should stop with an edge still on screen. |
| Erasing removes only what the tip passed over | Draw a long line, dab the eraser in its middle. Two pieces, not zero. The `erase: part` / `erase: whole` chip switches modes. |
| A fat brush draws smooth curves, not facets | Set the width slider near maximum and draw a tight curve. Any straight facets mean the epsilon fix (§2.3) regressed. |
| A stroke does not change appearance when the pen lifts | Watch the line as you lift. Wet and dry should be indistinguishable. |
| Palm produces zero stray marks in 20 attempts | Rest your palm, then write. Twenty times. Leave **Finger drawing** off — that is the shipping configuration. |
| Flip the S Pen → erases; barrel button → erases; release → previous pen | Both are per-event, so "release → previous pen" is automatic. Also check the *toolbar* eraser: select it, erase, lift — it should snap back to the pen (SPEC §3.4's Goodnotes borrow). |
| Pressure visibly varies width on Fountain | One stroke, light to heavy. |
| Tilt visibly affects Pencil | Select Pencil. One stroke upright, one with the pen laid flat. The flat one should be wider and lighter. |
| Fast strokes are smooth curves, not polygons | Scribble as fast as you can. Any visible straight-line segments mean historical points are being dropped. |
| No predicted-stroke artifacts after a stroke completes | Draw a fast stroke ending in a sharp direction change and stop dead. No spur past the end point. |
| **Zoomed to 400%, ink lands exactly under the pen tip** | Pinch to ~400% (the toolbar shows the percentage), then draw. The `viewToPage` half of this is unit-tested in `CameraTest.ink lands under the pen tip at 400 percent`; the tablet is where the *plumbing* gets checked. |

### 4.4 Tests

```bash
./gradlew :core:model:testDebugUnitTest
```

21 tests: the camera, the zoom limits, the fit modes, and the PDF coordinate
conversions including `/Rotate 90` and a non-zero-origin CropBox.

```bash
./gradlew :android:ink:connectedDebugAndroidTest
```

Whole-stroke erase and highlighter render order, on a device. Instrumented
rather than JVM because ink's geometry is native — a `Stroke` cannot be
tessellated, and so cannot be hit-tested, off a device.

---

## 5. Known gaps, carried forward

| Gap | Owner |
|---|---|
| Pencil has no grain texture (§2.1) | M10 |
| Hover preview (`AXIS_DISTANCE`) | M10, per SPEC §3.3 |
| Eyedropper in the colour picker | M10 — SPEC §3.4 lists it, ROADMAP M1's scope line does not |
| `pageToPdfUser` / `pdfUserToPage` are unit-tested but not yet checked against a real PDF producer | M4, per SPEC §4.4 |
| Custom-brush API is restricted; revisit both suppressions when the ink pin moves | whichever milestone bumps `androidxInk` |
