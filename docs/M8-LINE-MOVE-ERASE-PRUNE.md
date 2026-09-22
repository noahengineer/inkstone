# M8 follow-up 10 — a vertical line could not be moved, and a scribbled-over stroke was never pruned

Two reports, in the user's words:

> *"if i draw a line vertically instead of diagonally or horizontally there seems to be problems,
> also it seem like when there are a lot of erases when you zoom in and out it seem to lag."*

Both are real. They are unrelated, and neither is where the first hour of looking put them.

---

## 1. The vertical line — a shape's box was its centre line, and a segment's centre line has no area

### What the user sees

Draw a line with draw-and-hold, select it, and try to drag it somewhere else.

| the line | grab at its middle | grab anywhere else along it |
|---|---|---|
| diagonal | bends (the bend handle — by design, SPEC §4.8.9) | **moves** ✓ |
| vertical | bends | **nothing happens, and the selection is lost** ✗ |
| horizontal | bends | **nothing happens, and the selection is lost** ✗ |

So the only thing an axis-aligned line will do under a finger is *bend*. It is describable exactly
as it was reported: a vertical line is a problem and a diagonal one is not.

### Why

`DryShape.boundsOf` measured `outline.flattened()` and nothing else. A straight segment's outline
is **two points**, so the box is a zero-thickness rectangle in the object's own space;
`setBounds` maps it to page space, where

- a **diagonal** segment's axis-aligned box has width *and* height — a proper rectangle;
- a **horizontal** one has height exactly `0`;
- a **vertical** one has width exactly `0`.

`SelectionController.begin` then picks its mode:

```kotlin
box != null && box.containsWithin(start.x, start.y, 0f) -> Mode.MOVE
else -> Mode.LASSO
```

with **zero slop**. A zero-width box contains nothing a finger can land on, so the press fell
through to `Mode.LASSO` — which moves nothing, and clears the selection on its way past.

Two things kept this hidden for four milestones:

- **A box that is a hairline too small still culls correctly.** Until M8a nothing but culling read
  `pageBounds`, and a box slightly smaller than the ink never wrongly culls by enough to see. The
  moment a *gesture* was decided by it, an exactly-zero extent stopped being a rounding question.
- **Every line fixture in the repo is horizontal, and none of them tries to move one.**
  `SelectionControllerShapeTest` has three line tests — endpoint drag, bend drag, tap-without-move
  — and all three grab a *handle*. `Mode.MOVE` on a shape had no test at all.

### The fix

Two halves, and both are needed:

1. **`DryShape.boundsOf` inflates by half the pen.** A shape's outline is its centre line and
   `PagePainter` strokes it with a paint of `effectiveStyle().widthPt`, so the ink occupies half
   that either side. `tappedAt` and `hitBy` *already* add `widthPt / 2` — `boundsOf` was the
   sibling that did not, which is the shape of bug this file keeps finding by grepping for the
   other implementations of an invariant rather than for the symptom. It makes the box depend on
   the style, so `DryShape.restyle` now rebuilds it (`DryObject.restyle` only records the
   register; `DryStroke` already overrides for the same reason).
2. **`Mode.MOVE` uses `tapSlop`, not `0f`.** The same 8 dp that decides *"you tapped this object"*
   in `objectAt` is the honest answer to *"you grabbed this selection"*. Two guards on one decision
   were measuring different quantities, which this repo has been bitten by before (the tap that was
   reclassified as a lasso after 3.0 page-points of path).

Half 1 alone leaves a hairline pen nearly ungrabbable; half 2 alone leaves the box lying about
where the ink is, so the lasso and the viewport cull still measure the centre line.

### ⚠️ The test passed against the broken build first

`draggingAnAxisAlignedLineBetweenItsHandlesMovesIt` grabbed the line at its **exact** centre
coordinate — and `containsWithin` on a zero-width box is `xMin <= x && x <= xMax` with
`xMin == xMax`, which a press at exactly that float **satisfies**. The fixture was the one thing in
the system able to hit a hairline; a finger cannot. It now grabs a quarter of a pen off the centre
line — still visibly on the ink, and outside the box the old code computed. Checked in both
directions: red without either half of the fix, green with both.

---

## 2. The erase lag — "not pruning yet" was "never pruning"

### The arithmetic

`pruneIfAffordable` skipped any candidate whose estimated cost exceeded the budget left:

```kotlin
val cost = dry.eraseCoverageCost(region)   // inputs × 3 × capsules
if (cost > budget) return 0L               // PRUNE_BUDGET = 200_000
```

The estimate is `inputs × 3 × capsules`. The region is **append-only** (`SetErasedRegion` is an
absolute register, CLAUDE.md rule 8, and nothing compacts it) and `inputs` is fixed — so a stroke
that once exceeded the budget exceeds it on **every future pass, forever**. The KDoc promised the
degradation was *"not pruning yet"*; it was *"never pruning"*.

The threshold is well inside ordinary use: a 200-sample stroke needs only ~333 capsules, and one
second of scribbled erasing at 240 Hz is roughly 240 of them.

### Why that is a *zoom* bug

The prune runs at pen-up, not on the camera. What its failure leaves behind is what costs:

- a fully erased stroke is **invisible** and still costs `PagePainter` a `saveLayer` + `DST_OUT`
  sandwich in every record of the page (`PagePainter.kt`, the erasure-compositing branch);
- `DryStrokesView` records the whole page into a `RenderNode` and **re-records it after any change
  of scale** — `settlePending = recordedScale != camera.scale`, then `postDelayed(settleRecording)`;
- so the bill is paid again on every pinch, in proportion to how much has been erased, and on a
  page big enough it is the 48–105 ms per re-record that file's own comment measured at 1,121
  objects.

That is the report exactly: *lots of erases*, then *zoom in and out*, then lag.

### The fix

- **One candidate per pass runs whatever it costs.** The budget still bounds the total work, but
  the affordability decision moved to `pruneFullyErased`, which lets the first over-budget stroke
  through and refuses the rest. A prune runs per erase gesture, so a page now cleans itself up over
  the next few gestures instead of never.
- **`ErasedRegion.coveringCapsule` takes a rolling hint.** `isFullyErased` asks once per point of a
  stroke, in order, against a capsule list that is also in gesture order — and consecutive points
  of a stroke are near each other. Feeding the previous answer back turns the common case from a
  scan of the whole region into a hit on the first compare. Same answer, every capsule still
  visited at most once; only the starting point moves.

---

## 3. What was measured, and what was not

Per definition-of-done #6, one line per claim.

| claim | how it was checked |
|---|---|
| a vertical line will not move, a diagonal one will | **driven on the emulator**, controlled comparison, screenshots before/after |
| the fix makes it move | **driven on the emulator** on the rebuilt APK — the line moved the full drag and stayed selected |
| `Mode.MOVE` is chosen by a zero-slop `containsWithin` | read, and confirmed by the test going red when it is put back |
| a shape's box excludes its pen | instrumented test `aStraightLinesBoxIsAsThickAsItsPen`, red without the fix |
| moving an axis-aligned line | instrumented test `draggingAnAxisAlignedLineBetweenItsHandlesMovesIt`, red without the fix |
| a scribbled-over stroke is never pruned | **read only** — the arithmetic is `inputs × 3 × capsules > 200_000`, and it is written as a test |
| the prune fix works | ⚠️ **written, not run.** `aStrokeWithMoreCapsulesThanTheBudgetIsStillPruned` is instrumented and needs the **physical tablet** |
| erasing still works and undoes after the change | driven on the emulator — heavy scribble-erase, zoom, three undos, no crash and no jank |

The JVM side, with both tasks forced to execute rather than answering `UP-TO-DATE` or `FROM-CACHE`
(CLAUDE.md definition-of-done #3): `:core:model:testDebugUnitTest` **366 tests, 0 failures**,
`:android:ink:testDebugUnitTest` **12 tests, 0 failures**. `checkCoreIsPlatformFree` passes
(`UP-TO-DATE`, legitimately — nothing in `:core:*` was touched) and the iOS **klib compile** runs;
guardrail 2's framework link is `SKIPPED` on Windows and remains the macOS job's to prove.
`:android:ink:detekt` needed the prune loop split into `pruneFullyErased` +
`coverageCostToSpend` — `NestedBlockDepth` and `LoopWithTooManyJumpStatements`, both from the
forced-candidate branch — and `spotlessCheck` caught two adjacent KDoc blocks on one function
(ktlint `standard:kdoc`).

### ⚠️ `./gradlew build` is **not** green, and the failures are not this work's

Five of SPEC §13.2's matrix tests fail in `:app`:

    MergeMatrixConvergenceTest  (2)  MergeMatrixConvergenceTest.kt:40, :113
    MergeMatrixFoldTest  rows 8, 9, 10   MergeMatrixFoldTest.kt:42, :103, :193

They were **attributed, not assumed**. Neutralising all three of this work's behavioural hunks —
`boundsOf`'s pen inflation, `Mode.MOVE`'s slop, and the prune's forced candidate — and re-running
gives the *same five failures at the same line numbers*. That is consistent with the structural
argument (`:app`'s JVM matrix cannot reach `:android:ink` at all — `MatrixStack`'s own KDoc says
*"there is no ink in a JVM test"*, and there is no import), and with where the tree's other
uncommitted work sits: `NoteSession.kt` +196, `NoteStore.kt` +78, `OpCodec.kt` +68 and
`SyncEngine.kt` +72 are all on the matrix's path and all predate this session.

**They are somebody's to fix and they are not fixed here.** `:android:ink` itself is green:
`testDebugUnitTest` executed (not `UP-TO-DATE`), `detekt`, `spotlessCheck`, and both new
instrumented tests pass on the emulator and fail without the fix.

### ⚠️ The erase test cannot run on the emulator, and that is M1's bug, not this one

Every test in `StrokeStoreEraseTest` fails on x86_64 with
`StrokeInput::orientation must be -1 or in the range [0, 2 * pi). Got: -0.00147393π` — the exact
value CLAUDE.md already records. `SyntheticInk` computes orientations without clamping to `[0, 2π)`
and x86_64 rounding puts them a thousandth outside. The new prune test uses `SyntheticInk` like its
neighbours and inherits it. **It has never been run.** It needs `tools/send-to-tablet.ps1` and the
Galaxy Tab.

### What was *not* reproduced

The zoom lag itself could not be reproduced on the emulator. A controlled dose-response — the same
36 strokes, erase load raised from 6 to 330 scrubs and then to six 150-sample dense gestures,
journal from 15 KB to 216 KB — produced **zero dropped frames** across every zoom cycle. One
9.3-second freeze was captured earlier in the session and could not be reproduced under control, so
it is not evidence of anything.

Two reasons to expect that, and neither weakens the mechanism:

- the pages that can be driven over `adb` are ~36 objects, and the re-record cost the code's own
  comment measures is at **1,121**;
- `adb shell input` cannot pinch — `/dev/input` is root-only on a Play emulator image — so the zoom
  was driven by the `FIT PAGE` / `100%` chips, which change the scale but deliver none of the
  per-event work a real pinch does.

**The prune bug is arithmetic and is certain; its contribution to the user's lag is inferred.**
The next thing to do is the physical tablet: a real note, a real scribble-erase, and
`dumpsys gfxinfo dev.inkstone framestats` across a real pinch, before and after.
