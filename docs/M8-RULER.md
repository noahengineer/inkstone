# M8c follow-up — the ruler never worked, and it covered the line it ruled

**Status: fixed and checked on a device.** §5 says which check each claim rests on.

The report, in full:

> *"The ruler does not work at all and it should to use the center because otherwise it covers it"*

Two things, and the first one is much worse than "does not work".

---

## 1. ⛔ Every stroke drawn while the edge was out **threw**

`androidx.ink` refuses a singular `motionEventToWorldTransform`:

```
java.lang.IllegalArgumentException: motionEventToWorldTransform must be invertible,
    but was Matrix{[0.23906249, 0.0, 2.9182433E-5][0.0, 0.0, 191.24998][0.0, 0.0, 1.0]}
  at androidx.ink.authoring.InProgressShapesView.strokeUnitLengthCm(InProgressShapesView.kt:491)
  at androidx.ink.authoring.InProgressShapesView.startShape(InProgressShapesView.kt:480)
  at androidx.ink.authoring.InProgressStrokesView.startStroke(InProgressStrokesView.kt:295)
  at dev.inkstone.android.ink.InkSurfaceView.beginStroke(InkSurfaceView.kt:1030)
```

So it was not that ruled strokes failed to snap. **They failed to exist** — the exception came out
of `ACTION_DOWN`, before any ink was captured at all. Switch the ruler on and the pen stops working;
switch it off and the pen works again. That is the whole of *"does not work at all"*.

### 1.1 The reasoning that shipped it, and the one word that was wrong

M8c applied the snap as an orthogonal projection composed onto the transform ink is given, which is
an elegant design and costs nothing per sample. `RulerController`'s KDoc closed the argument:

> *"The composed matrix is **singular** — that is what a projection is — and that is safe here only
> because `strokeToWorldTransform` is the identity (SPEC §4.4 rule 1: stroke space *is* page space),
> so nothing ever inverts it. If that ever stops being true this stops working, which is why it is
> stated here rather than discovered."*

Every clause is true except the load-bearing one. **It is inverted** — not by us, and not because
`strokeToWorldTransform` stopped being the identity, but by `InProgressShapesView.strokeUnitLengthCm`,
which inverts `motionEventToWorldTransform` to work out how many centimetres one stroke unit is. This
app *needs* that number: `InkstoneBrushFamilies` gives the fountain pen and the pencil an
`INPUT_SPEED_IN_CENTIMETERS_PER_SECOND → SIZE_MULTIPLIER` behaviour.

`RulerAndLaserTest`'s own KDoc had already listed this as the open risk:

> *"**Not asserted here**: that `InProgressStrokesView` accepts a singular
> `motionEventToWorldTransform`. … it is the one thing about the ruler that could still be wrong …
> The emulator walk is what closes it."*

The walk never happened. **A named unverified assumption is not a check** — that is the lesson, and
it is why the replacement is a test (`RulerReachTest`) and not another walk.

### 1.2 What it does now: project the **event**, not ink's matrix

Ink gets the camera's own `viewToPage` — invertible, well-conditioned, and the same matrix an
unruled stroke uses, so `strokeUnitLengthCm` is measured against a camera rather than a projection.
The straight edge is applied to a **copy of the `MotionEvent`** with `MotionEvent.transform`, which
moves every pointer and **every historical sample** and leaves pressure, tilt, orientation and tool
type alone — the exact set CLAUDE.md says a hand-built `StrokeInput` silently drops.

Three details carry the cost:

- **The predicted tail is projected too.** It is a separate `MotionEvent` from the predictor and
  travels through none of the event's own transforms, so straightening the real samples and not the
  predicted ones would draw a hook at the nib that vanished a frame later.
- **The copy is pooled and recycled immediately.** `StrokeInputPool.obtainAllHistoryForMotionEvent`
  converts an event into a `MutableStrokeInputBatch` synchronously on the calling thread, so nothing
  retains it. Read off the 1.0.0 bytecode rather than assumed — the whole defect above is what
  assuming looks like.
- **The camera is the one the stroke started in**, not the live one. Ink captures its transform at
  `startStroke`; projecting later samples through a camera that had since moved would put them on
  the edge as it is now and then map them with the camera as it was.

### 1.3 ⚠️ The near-singular matrix is a trap, and it looks like the obvious fix

`εI + (1 − ε)P` is invertible, has determinant ε, and leaves at most ε of the hand's wobble. It is
still wrong. `strokeUnitLengthCm` averages the **row norms of the inverse**, and the inverse of that
matrix is `1/ε` along the perpendicular — so any ε small enough to straighten a line reports a
stroke unit tens of times too long, and the fountain pen then sizes itself off a speed the pen never
travelled. The condition number *is* the error.

## 2. The body covered the line it had just ruled

The translucent slab was drawn centred on the guide, so ruled ink landed down the middle of a 44 dp
band — over the line and over whatever was being ruled against. `ToolOverlayView` argued the case:

> *"A real ruler's ink lands against its **edge**, so the obvious drawing — a wide bar with the snap
> line at one border — is what a person expects; it is not what is drawn, because the projection is
> onto the centre line and chrome that disagreed with the geometry by half the slab's width would
> make every ruled stroke land visibly beside the guide."*

The argument is sound and it had a third answer nobody took: **move the body, keep the line.** The
guide is exactly where it was — `project` and `eventProjection` are untouched — and the slab now
hangs off one side of it along `InkRuler.bodyNormalX/Y`, which for a horizontal edge is *below*
(page space has y growing downwards, SPEC §4.4). Turning the edge past a half turn carries the body
with it, exactly as flipping a real ruler over does.

**The grab moved with the chrome**, asymmetrically: the full body on one side, 12 dp of grace on the
pen's side. A chrome side and a grab side that disagree would be a ruler you can see and cannot pick
up — at 12 % alpha, nobody would ever diagnose that from the screen. `InkRuler.bodyWidthDp` is one
constant read by both, rather than one each.

## 3. ⚠️ The finger could not grab it while the pen was in your hand

`RulerController.begin` sat **behind** `PalmGuard`. The S Pen reports hover from about a centimetre
out and every hover event refreshes the guard, so with the pen anywhere near the page the finger was
dropped before the ruler ever saw it — and *"pen in the other hand"* is the **only** posture a
straight edge is used in. The edge could be moved exactly when nobody wanted to move it.

This is the two-finger-undo bug one tool along, with the same cause and the same fix — CLAUDE.md
already records it, and `fingerPointerDown`'s own KDoc said *"the camera, the ruler and finger
drawing stay behind the guard, exactly as before"*, which is the sentence that needed re-reading.
`onMove` had already been given the exception (*"no `noteStylusEvent`: the ruler is dragged by a
**finger**"*); only the grab was left behind.

The trade is narrow and deliberate. The guard exists to stop a palm *drawing* or *panning*; grabbing
the ruler is neither — it writes no op, it needs a landing within reach of an edge the user placed
themselves, it is visible instantly, and it is undone by dragging back. `gestureInFlight` still
refuses it outright while a stroke is live, so a resting palm cannot steal the edge out from under a
line being ruled.

## 4. Two smaller things found on the way

- `edgePaint.strokeWidth` was a raw `1.5f` — **pixels**, on a class whose KDoc promises every size
  goes through `density`. On a 320 dpi tablet that is half the hairline it claims to be. It is
  `1.5.dp` now.
- The grab reach was 24 dp on **both** sides of a line the user cannot see the far side of. It is
  the body on one side and 12 dp on the other, which is more grabbable in total and never claims
  the paper the pen is aimed at.

## 5. What was checked, and how

Per CLAUDE.md's definition-of-done #6:

| Claim | Check |
|---|---|
| A stroke drawn while the edge is out finishes | **device** — `RulerReachTest`, and it is the test that caught the defect |
| A finger moves the edge while the pen is hovering | **device** — `RulerReachTest`; verified sensitive by restoring the old ordering, which fails it |
| The body is grabbable and the paper above the edge is not | **device** — `RulerReachTest` and `RulerAndLaserTest` |
| The event projection lands where `project` says, through a real camera | automated — `RulerAndLaserTest`, 2 cases |
| The body follows the edge past a half turn | automated — `RulerAndLaserTest` |
| A ruled stroke lands **on** the guide and straight | **device** — a diagonal `input stylus swipe` came back as a straight line lying on the edge; §5.1 |
| Ruled strokes keep the right brush width | *not measured.* The transform ink is given is now identical to an unruled stroke's, so `strokeUnitLengthCm` is the same number it always was — but no width was compared side by side |

### 5.1 The walk

⚠️ `input stylus swipe` is what draws; a plain `input swipe` is a finger and `PalmGuard` drops it.
And **before the fix the same command drew nothing**, which is worth knowing because it looks
exactly like the instrument being wrong — the fourth time this file's family of notes has been
earned. The distinguishing evidence was the stack trace, not the screen.

- `RULER` → the edge appears with the guide on top and the body hanging below it, and the secondary
  row gains `angle °` / 0° / 45° / 90°.
- A finger dragged on the body moves the edge; the page does not pan.
- `input stylus swipe 700 900 1700 1050` — a diagonal — landed as a straight line **on** the guide,
  fully visible above the body.

### 5.2 The suites

- `:android:ink` `RulerAndLaserTest` — 16/16 on the emulator.
- `:app` `dev.inkstone.app.device` — 14/14, which is `RulerReachTest` plus `WetInkRemoteMergeTest`.
- `:android:ink` full instrumented suite — 101 tests, **10 failures**, all in `StrokeStoreEraseTest`
  and `StrokeGeometryCodecTest`: the documented `SyntheticInk` x86_64 orientation-clamp and
  arm64-tuned-tolerance failures (`y at 1 expected:<415.70966> but was:<415.7085>`). Nothing the
  ruler touches.
- `spotlessCheck`, `detekt`, `checkCoreIsPlatformFree` — green.
