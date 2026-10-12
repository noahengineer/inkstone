package dev.inkstone.android.ink

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkstone.core.model.ErasedArea
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC §3.5 — erasing, in both modes.
 *
 * Instrumented rather than a JVM unit test because `androidx.ink`'s geometry is
 * native: a `Stroke` cannot be tessellated, and therefore cannot be hit-tested,
 * without the `.so` that only ships to a device.
 *
 * The invariant worth the most here is
 * [partialEraseRecordsAnAreaAndLeavesTheStrokeIntact]. Partial erase records the
 * area the eraser swept and never edits the stroke, so the original ink survives
 * every erase (CLAUDE.md rule 1) and the op-log M2 builds can express an erase
 * as one absolute LWW register rather than a delete and a pile of adds.
 */
@RunWith(AndroidJUnit4::class)
class StrokeStoreEraseTest {

    private lateinit var factory: InkBrushFactory
    private lateinit var store: StrokeStore

    private val page = PageSize.Letter

    @Before
    fun setUp() {
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        factory = InkBrushFactory(metrics.density, metrics.densityDpi / 72f)
        store = StrokeStore()
    }

    private fun loadStrokes(count: Int) {
        store.addAll(SyntheticInk.generate(count, page, factory, seed = 7).items)
    }

    @Test
    fun partialEraseRecordsAnAreaAndLeavesTheStrokeIntact() {
        // The invariant the whole design turns on: erasing part of a stroke
        // must not touch the stroke. If this fails, the original ink has been
        // destroyed and CLAUDE.md rule 1 with it.
        store.addAll(SyntheticInk.generate(1, page, factory, seed = 3, pointsPerStroke = 60).items)
        val subject = onlyStroke()
        val inputsBefore = subject.stroke.inputs.size
        val shapeBefore = boundsSignature(subject)

        val midpoint = midpointOfOnlyStroke()
        val at = PagePointF(midpoint.first, midpoint.second)
        val touched = store.eraseAlong(at, at, DAB_RADIUS_PT, EraseMode.PARTIAL)

        assertEquals("the dab should have hit exactly one stroke", 1, touched)
        assertEquals("partial erase must not split or delete", 1, store.size)
        assertEquals("the input list changed", inputsBefore, onlyStroke().stroke.inputs.size)
        assertEquals("the geometry changed", shapeBefore, boundsSignature(onlyStroke()))
        assertTrue("no erased area was recorded", onlyStroke().erased?.capsules?.isNotEmpty() == true)
    }

    @Test
    fun erasingAcrossAStrokeAccumulatesRatherThanReplacing() {
        store.addAll(SyntheticInk.generate(1, page, factory, seed = 3, pointsPerStroke = 60).items)
        val midpoint = midpointOfOnlyStroke()
        val at = PagePointF(midpoint.first, midpoint.second)

        store.eraseAlong(at, at, DAB_RADIUS_PT, EraseMode.PARTIAL)
        store.eraseAlong(at, at, DAB_RADIUS_PT, EraseMode.PARTIAL)

        assertEquals("each sample should add to the region", 2, onlyStroke().erased?.capsules?.size)
    }

    @Test
    fun aLongEraseGestureDoesNotHangOnPrune() {
        // Regression: the first area-based implementation flattened the region
        // into one filled Path and ran Path.op against the stroke silhouette
        // when the gesture ended. A few seconds of scribbling froze and killed
        // the app. This drives far more samples than a real gesture and asserts
        // only that it finishes.
        store.addAll(SyntheticInk.generate(40, page, factory, seed = 21).items)
        val from = PagePointF()
        val to = PagePointF()
        var previousX = 0f
        var previousY = 0f
        for (step in 0 until SCRIBBLE_SAMPLES) {
            val t = step.toFloat() / SCRIBBLE_SAMPLES
            from.set(previousX, previousY)
            to.set(page.widthPt * t, page.heightPt * ((step % 20) / 20f))
            store.eraseAlong(from, to, DAB_RADIUS_PT, EraseMode.PARTIAL)
            previousX = to.x
            previousY = to.y
        }

        val start = System.nanoTime()
        store.pruneFullyErased()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue("prune took ${elapsedMs}ms for $SCRIBBLE_SAMPLES samples", elapsedMs < PRUNE_BUDGET_MS)
    }

    /**
     * ⛔ **A stroke scribbled over hard must be pruned in one ordinary pass.**
     *
     * Twice wrong before this. First `pruneIfAffordable` skipped any candidate whose *estimate* —
     * `inputs × 3 × capsules` — exceeded the budget left, and since the region only grows that
     * meant **never**. Then a forced candidate per pass made it *eventually*, still charging the
     * estimate: on a real note (B) that was 56–101× the work the test really does, so a pass pruned
     * two to six strokes and ~555 invisible strokes kept a `saveLayer` + `DST_OUT` each on every
     * re-record — *"when there are a lot of erases when you zoom in and out it seem to lag"*.
     *
     * The budget is now charged in work actually done (`CoverageMeter`), so a stroke whose estimate
     * is several budgets over is pruned in one pass that spends a small fraction of one. The fixture
     * erases in **many small dabs** — what a person scribbling produces, and the only way to get the
     * capsule count up — and asserts the old estimate really was over the whole budget, so the test
     * cannot quietly stop being about the bug if the constants move.
     */
    @Test
    fun aStrokeWithMoreCapsulesThanTheBudgetIsStillPruned() {
        store.addAll(SyntheticInk.generate(1, page, factory, seed = 3, pointsPerStroke = 60).items)
        val stroke = onlyStroke()
        val bounds = checkNotNull(stroke.bounds)

        // Cover the stroke completely, in dabs: each sample is one capsule, and it is the count
        // that puts the estimate over the budget.
        val from = PagePointF()
        val to = PagePointF()
        for (step in 0..DAB_STEPS) {
            val t = step.toFloat() / DAB_STEPS
            val x = bounds.xMin + (bounds.xMax - bounds.xMin) * t
            val y = bounds.yMin + (bounds.yMax - bounds.yMin) * t
            from.set(x, y)
            to.set(x, y)
            store.eraseAlong(from, to, WIDE_PT, EraseMode.PARTIAL)
        }

        val region = checkNotNull(stroke.erased)
        val oldEstimate = stroke.stroke.inputs.size.toLong() * OLD_POINTS_PER_INPUT * region.capsules.size
        assertTrue(
            "the fixture is too cheap to cover the bug: ${region.capsules.size} capsules",
            oldEstimate > PruneBudgets.PEN_UP,
        )
        assertEquals("nothing should vanish before the gesture ends", 1, store.size)

        store.pruneFullyErased()

        assertEquals("a heavily erased stroke was not pruned in one pass", 0, store.size)
        assertTrue(
            "the pass spent ${store.lastPruneWork} units against an estimate of $oldEstimate",
            store.lastPruneWork <= PruneBudgets.PEN_UP,
        )
    }

    /**
     * ⭐ **The pen-up pass looks at what this gesture erased, and nothing else on the page.**
     *
     * It walked the whole page from the front on every pen-up, re-examining objects no gesture had
     * touched since they were last decided — and paying for them out of the budget the new erasure
     * needed. Here the first gesture leaves many strokes partially erased (decided visible), and the
     * second touches one; the second pass must probe what the second gesture bit and no more.
     */
    @Test
    fun thePenUpPassLooksOnlyAtWhatTheGestureErased() {
        loadStrokes(SMALL_COUNT)
        val midpoints = ArrayList<PagePointF>()
        store.forEachStrokeInRenderOrder(null) { dry ->
            val inputs = dry.stroke.inputs
            val middle = inputs.get(inputs.size / 2)
            midpoints += PagePointF(middle.x, middle.y)
        }

        store.beginEdit()
        for (at in midpoints) store.eraseAlong(at, at, DAB_RADIUS_PT, EraseMode.PARTIAL)
        store.pruneFullyErased()
        store.endEdit()
        val erasedObjects = store.erasedCount
        assertTrue("the first gesture erased too little to prove anything: $erasedObjects", erasedObjects > 10)
        assertEquals("a decided candidate stayed queued", 0, store.pendingPruneCount)

        store.beginEdit()
        val touched = store.eraseAlong(midpoints[0], midpoints[0], DAB_RADIUS_PT, EraseMode.PARTIAL)
        assertEquals("the queue should hold exactly what the dab bit", touched, store.pendingPruneCount)
        store.pruneFullyErased()
        store.endEdit()

        assertEquals("the pass probed objects this gesture never touched", touched, store.lastPruneProbes)
        assertTrue("the fixture's second dab touched everything", touched < erasedObjects)
    }

    /**
     * Every register reaches a pruned stroke — not only the erased region.
     *
     * Pruning takes a fully erased stroke off the page and emits nothing, so it is still in the fold
     * and a peer can move or recolour it. That op used to be dropped here, and an undo of the erase
     * then brought the stroke back **where it was before the move**. It mattered little while a pass
     * pruned a handful; a page load now parks every fully erased stroke.
     */
    @Test
    fun aMoveOfAPrunedStrokeIsKeptForWhenItComesBack() {
        store.addAll(SyntheticInk.generate(1, page, factory, seed = 3, pointsPerStroke = 60).items)
        val id = onlyStroke().id
        eraseAll()
        store.pruneFullyErased()
        assertEquals("the fixture never pruned, so this test proves nothing", 0, store.size)

        val moved = Matrix3.translate(MOVE_X, MOVE_Y)
        store.applyOps(listOf(PageOp.SetTransform("move-1", LamportClock(1L, DEVICE), id, moved)), factory)
        assertEquals("a move must not bring an erased stroke back", 0, store.size)

        store.applyOps(
            listOf(PageOp.SetErasedRegion(UNDO_OP_ID, LamportClock(2L, DEVICE), id, ErasedArea.None)),
            factory,
        )

        assertEquals(1, store.size)
        assertEquals("the move made while it was pruned was lost", moved, onlyStroke().transform)
    }

    /**
     * A clear deletes what is on the page; a stroke erased away is not on it, is not deleted, and
     * must still be reachable by the un-erase an undo will eventually write.
     */
    @Test
    fun aClearLeavesAPrunedStrokeReachableForAnUnErase() {
        store.addAll(SyntheticInk.generate(2, page, factory, seed = 3, pointsPerStroke = 60).items)
        var erasedId: dev.inkstone.core.model.ObjectId? = null
        store.forEachStrokeInRenderOrder(null) { dry -> if (erasedId == null) erasedId = dry.id }
        val id = checkNotNull(erasedId)
        val bounds = checkNotNull(store.objectOf(id)?.bounds)
        val from = PagePointF(bounds.xMin - WIDE_PT, bounds.yMin - WIDE_PT)
        val to = PagePointF(bounds.xMax + WIDE_PT, bounds.yMax + WIDE_PT)
        store.eraseAlong(from, to, WIDE_PT, EraseMode.PARTIAL)
        store.pruneFullyErased()
        assertTrue("the fixture never pruned the stroke", store.objectOf(id) == null)

        store.clear()
        assertEquals(0, store.size)
        store.applyOps(
            listOf(PageOp.SetErasedRegion(UNDO_OP_ID, LamportClock(1L, DEVICE), id, ErasedArea.None)),
            factory,
        )

        assertEquals("the un-erase after a clear landed on nothing", 1, store.size)
        assertEquals(id, onlyStroke().id)
    }

    /** Sweep an eraser far wider than the only stroke along its whole extent. */
    private fun eraseAll() {
        val bounds = checkNotNull(onlyStroke().bounds)
        val from = PagePointF(bounds.xMin - WIDE_PT, bounds.yMin - WIDE_PT)
        val to = PagePointF(bounds.xMax + WIDE_PT, bounds.yMax + WIDE_PT)
        store.eraseAlong(from, to, WIDE_PT, EraseMode.PARTIAL)
    }

    @Test
    fun aStrokeErasedEndToEndIsDroppedOnPrune() {
        store.addAll(SyntheticInk.generate(1, page, factory, seed = 3, pointsPerStroke = 60).items)
        val bounds = checkNotNull(onlyStroke().bounds)

        // Sweep an eraser far wider than the stroke along its whole extent.
        val from = PagePointF(bounds.xMin - WIDE_PT, bounds.yMin - WIDE_PT)
        val to = PagePointF(bounds.xMax + WIDE_PT, bounds.yMax + WIDE_PT)
        store.eraseAlong(from, to, WIDE_PT, EraseMode.PARTIAL)

        assertEquals("nothing should vanish before the gesture ends", 1, store.size)
        store.pruneFullyErased()
        assertEquals("a fully erased stroke should be dropped", 0, store.size)
    }

    /**
     * ⛔ **Erase a stroke away, undo, and it has to come back.**
     *
     * It did not, for as long as pruning has existed, and every layer was individually right.
     * [pruneFullyErased] emits **no op** — a fully erased stroke is not deleted, its
     * `SetErasedRegion` already describes it — so the object stays in the fold; `NoteEditor`'s
     * undo therefore diffs two folds that both contain it and pushes a bare `SetErasedRegion`
     * rather than an `AddObject`; and `StrokeStore.applyRegister` opened with
     * `byId[target] ?: return false`, having just removed that very id. Correct op, correct
     * journal, correct fold, one consumer that could not act on it — and the stroke stayed gone
     * until the page was reloaded. The user's report was *"erasing seems to mess it up as well…
     * you cant go back to how it was"*.
     *
     * It was intermittent because pruning is rationed by a budget: whether a given stroke had
     * actually left the page depended on how much else had been erased first.
     *
     * ⚠️ Asserting on `store.size` and on the **render** walk, not on `objectOf`: the failure is
     * precisely that nothing is drawn, and an id lookup that answered would not have caught it.
     *
     * ⚠️ Deliberately `SetErasedRegion` and nothing else, because that is what `reconcile` emits.
     * A test that helped the store along with an `AddObject` would pass against the broken code.
     */
    @Test
    fun undoingAnEraseBringsBackAStrokeThePruneDropped() {
        store.addAll(SyntheticInk.generate(1, page, factory, seed = 3, pointsPerStroke = 60).items)
        val id = onlyStroke().id
        val bounds = checkNotNull(onlyStroke().bounds)
        val from = PagePointF(bounds.xMin - WIDE_PT, bounds.yMin - WIDE_PT)
        val to = PagePointF(bounds.xMax + WIDE_PT, bounds.yMax + WIDE_PT)
        store.eraseAlong(from, to, WIDE_PT, EraseMode.PARTIAL)
        store.pruneFullyErased()
        assertEquals("the fixture never pruned, so this test proves nothing", 0, store.size)

        store.applyOps(
            listOf(PageOp.SetErasedRegion(UNDO_OP_ID, LamportClock(1L, DEVICE), id, ErasedArea.None)),
            factory,
            record = false,
        )

        assertEquals("the un-erase did not put the stroke back on the page", 1, store.size)
        assertEquals("a different stroke came back", id, onlyStroke().id)
        assertTrue(
            "the erased region survived an absolute write of None",
            onlyStroke().erased?.capsules.isNullOrEmpty(),
        )
    }

    /**
     * The other direction: an un-erase that still covers the stroke leaves it off the page.
     *
     * Undo is per gesture, so shrinking a region back by one erase drag can easily still cover
     * the stroke. Putting it back then would draw nothing and cost a render slot for every stroke
     * the user had ever rubbed out.
     */
    @Test
    fun anUnEraseThatStillCoversTheStrokeLeavesItPruned() {
        store.addAll(SyntheticInk.generate(1, page, factory, seed = 3, pointsPerStroke = 60).items)
        val id = onlyStroke().id
        val bounds = checkNotNull(onlyStroke().bounds)
        val from = PagePointF(bounds.xMin - WIDE_PT, bounds.yMin - WIDE_PT)
        val to = PagePointF(bounds.xMax + WIDE_PT, bounds.yMax + WIDE_PT)
        store.eraseAlong(from, to, WIDE_PT, EraseMode.PARTIAL)
        val covering = onlyStroke().erased.toAbsolute()
        store.pruneFullyErased()
        assertEquals("the fixture never pruned, so this test proves nothing", 0, store.size)

        store.applyOps(
            listOf(PageOp.SetErasedRegion(UNDO_OP_ID, LamportClock(1L, DEVICE), id, covering)),
            factory,
            record = false,
        )

        assertEquals("a stroke that is still fully erased was put back on the page", 0, store.size)
    }

    @Test
    fun wholeStrokeModeStillDeletesTheWholeStroke() {
        store.addAll(SyntheticInk.generate(1, page, factory, seed = 3, pointsPerStroke = 60).items)
        val midpoint = midpointOfOnlyStroke()
        val at = PagePointF(midpoint.first, midpoint.second)

        store.eraseAlong(at, at, DAB_RADIUS_PT, EraseMode.WHOLE_STROKE)

        assertEquals(0, store.size)
    }

    /**
     * ⭐ M8c — `forEachStrokeInRenderOrder`, because this file's subject is ink.
     *
     * The store holds [DryObject]s now, so the general walk hands back something that may not have
     * a mesh. The narrowed walk is not a convenience here: every assertion below reads
     * `dry.stroke`, and a page with an image on it would otherwise fail on a cast rather than on
     * the erase behaviour being tested.
     */
    private fun onlyStroke(): DryStroke {
        var found: DryStroke? = null
        store.forEachStrokeInRenderOrder(null) { found = it }
        return checkNotNull(found)
    }

    private fun boundsSignature(dry: DryStroke): String {
        val box = checkNotNull(dry.stroke.shape.computeBoundingBox())
        return "${box.xMin},${box.yMin},${box.xMax},${box.yMax}"
    }

    /** The page-space midpoint of the single stroke in the store. */
    private fun midpointOfOnlyStroke(): Pair<Float, Float> {
        val inputs = onlyStroke().stroke.inputs
        val middle = inputs.get(inputs.size / 2)
        return middle.x to middle.y
    }

    @Test
    fun eraserRemovesWholeStrokesAndNothingElse() {
        loadStrokes(STROKE_COUNT)
        val before = store.size
        assertEquals(STROKE_COUNT, before)

        // Sweep the eraser diagonally across the whole page. With strokes spread
        // over the page this must hit some, and must not hit all.
        var removed = 0
        val from = PagePointF()
        val to = PagePointF()
        var previousX = 0f
        var previousY = 0f
        for (step in 1..SWEEP_STEPS) {
            val t = step.toFloat() / SWEEP_STEPS
            from.set(previousX, previousY)
            to.set(page.widthPt * t, page.heightPt * t)
            removed += store.eraseAlong(from, to, ERASER_RADIUS_PT, EraseMode.WHOLE_STROKE)
            previousX = to.x
            previousY = to.y
        }

        assertTrue("the eraser hit nothing at all", removed > 0)
        assertTrue("the eraser removed the entire page", removed < before)
        assertEquals("size must account for exactly the strokes removed", before - removed, store.size)
    }

    @Test
    fun survivingStrokesKeepTheirGeometry() {
        loadStrokes(SMALL_COUNT)
        val boundsBefore = collectBounds()

        val from = PagePointF(0f, 0f)
        val to = PagePointF(page.widthPt, page.heightPt)
        store.eraseAlong(from, to, ERASER_RADIUS_PT, EraseMode.WHOLE_STROKE)

        // Every stroke that survived must be byte-for-byte the same shape it was.
        // Whole-stroke erase never touches a point list (SPEC §3.5); the moment
        // it does, a surviving stroke's bounds move.
        val boundsAfter = collectBounds()
        for ((id, bounds) in boundsAfter) {
            assertEquals("stroke $id changed shape during an erase", boundsBefore.getValue(id), bounds)
        }
        assertTrue("nothing survived, so the assertion proved nothing", boundsAfter.isNotEmpty())
        assertTrue("nothing was erased, so the assertion proved nothing", boundsAfter.size < boundsBefore.size)
    }

    @Test
    fun aTinyEraserFarFromEverythingRemovesNothing() {
        loadStrokes(SMALL_COUNT)
        val before = store.size

        // Well outside the page, where SyntheticInk never puts a stroke.
        val from = PagePointF(-500f, -500f)
        val to = PagePointF(-490f, -490f)

        assertEquals(0, store.eraseAlong(from, to, 1f, EraseMode.WHOLE_STROKE))
        assertEquals(before, store.size)
    }

    /**
     * ⛔ **This test asserted the opposite, and could not fail.**
     *
     * It was written as `highlightersRenderBeforePens` against *"SPEC §3.4: the highlighter
     * draws behind ink, so it must be drawn first"* — which is the **pre-M4** behaviour. SPEC
     * §3.4 was corrected from the tablet, in three attempts, after the report *"you can't
     * highlight your own writing, it gets put under it"*: the shipped brush is plain alpha
     * drawn **over** ink, and `StrokeStore.forEachInRenderOrder` emits `ink` then `highlight`
     * deliberately. The stale assertion survived because every test in this class died in
     * `SyntheticInk.generate` before reaching it (see [SyntheticInk.turn]) — so a test that
     * would have argued for reverting a thrice-reported fix sat here looking like coverage.
     *
     * Kept, inverted, because the invariant is worth guarding in the direction it actually
     * holds: `drawsOverInk` decides the band, and a future kind that answers true must land
     * in it rather than be interleaved by z.
     */
    @Test
    fun highlightersRenderAfterPens() {
        loadStrokes(SMALL_COUNT)

        // Strokes only: `InkBrushKind` is a property of ink, and the band this asserts on is
        // decided by `DryObject.drawsOverInk`, which every non-stroke kind answers false to.
        val order = ArrayList<InkBrushKind>()
        store.forEachStrokeInRenderOrder(null) { order += it.kind }

        val firstHighlighter = order.indexOfFirst { it == InkBrushKind.HIGHLIGHTER }
        val lastPen = order.indexOfLast { it != InkBrushKind.HIGHLIGHTER }
        assertTrue("the fixture produced no highlighters", firstHighlighter >= 0)
        assertTrue("the fixture produced no pens", lastPen >= 0)
        assertTrue(
            "SPEC §3.4: the highlighter tints what it covers, so it must be drawn last",
            firstHighlighter > lastPen,
        )
    }

    private fun collectBounds(): Map<dev.inkstone.core.model.ObjectId, String> {
        val result = HashMap<dev.inkstone.core.model.ObjectId, String>()
        store.forEachInRenderOrder(null) { dry ->
            val box = dry.bounds
            result[dry.id] = if (box == null) "empty" else "${box.xMin},${box.yMin},${box.xMax},${box.yMax}"
        }
        return result
    }

    private companion object {
        const val STROKE_COUNT = 400
        const val SMALL_COUNT = 120
        const val SWEEP_STEPS = 40
        const val ERASER_RADIUS_PT = 8f
        const val DAB_RADIUS_PT = 4f
        const val WIDE_PT = 60f
        const val SCRIBBLE_SAMPLES = 3000
        const val PRUNE_BUDGET_MS = 500

        /**
         * Enough dabs that the **old** estimate clears the pen-up budget several times over.
         *
         * Derived, because the first value did not: the estimate was
         * `inputs × COVERAGE_POINTS_PER_INPUT × capsules` = `60 × 3 × capsules`, and one dab
         * records one capsule (measured: 900 steps ⇒ 901 capsules), so clearing 200,000 needs
         * **1,112** and 900 gave 162,180. At 3,600 the estimate is 648,180 — 3.2× the budget, which
         * is what "several times over" has to mean for a one-pass prune to be evidence that the
         * budget is no longer charged in estimates.
         */
        const val DAB_STEPS = 3600

        /** The retired estimate's per-input factor, `DryStroke.COVERAGE_POINTS_PER_INPUT`. */
        const val OLD_POINTS_PER_INPUT = 3L
        const val UNDO_OP_ID = "undo-1"
        const val DEVICE = "device-a"
        const val MOVE_X = 120f
        const val MOVE_Y = 40f
    }
}
