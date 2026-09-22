package dev.inkstone.android.ink

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkstone.core.model.ErasedArea
import dev.inkstone.core.model.LamportClock
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
     * ⛔ **A stroke scribbled over hard must still be pruned, and it never was.**
     *
     * `pruneIfAffordable` skipped any candidate whose estimate exceeded the budget left. That
     * estimate is `inputs x 3 x capsules`; the region is append-only and `inputs` is fixed, so a
     * stroke that once exceeded `PRUNE_BUDGET` exceeded it on **every future pass, forever**. The
     * KDoc called the degradation *"not pruning yet"*; it was *"never pruning"*.
     *
     * The threshold is low enough that ordinary use crosses it: a 200-sample stroke needs about
     * 333 capsules, and one second of scribbled erasing at 240 Hz is roughly 240 of them.
     *
     * What it costs is not the prune. A fully erased stroke is invisible and still costs
     * `PagePainter` a `saveLayer` + `DST_OUT` sandwich in every record of the page, and
     * `DryStrokesView` re-records the whole page after any change of scale — so the bill is paid
     * again on every pinch, in proportion to how much has been erased. That is the report this
     * test exists for: *"when there are a lot of erases when you zoom in and out it seem to lag"*.
     *
     * The fixture erases the stroke away in **many small dabs** rather than one wide sweep, which
     * is what a person scribbling actually produces and is the only way to get the capsule count
     * over the line. It asserts the estimate really does exceed the whole budget, so the test
     * cannot quietly stop covering the bug if the constants move.
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
        assertTrue(
            "the fixture is too cheap to cover the bug: ${region.capsules.size} capsules",
            stroke.eraseCoverageCost(region) > PRUNE_BUDGET_ESTIMATE,
        )
        assertEquals("nothing should vanish before the gesture ends", 1, store.size)

        store.pruneFullyErased()

        assertEquals("a heavily erased stroke was skipped, and would be skipped forever", 0, store.size)
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
     * It was intermittent because pruning is rationed by `PRUNE_BUDGET`: whether a given stroke
     * had actually left the page depended on how much else had been erased first.
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
         * Enough dabs that the estimate clears `StrokeStore.PRUNE_BUDGET` several times over.
         *
         * Derived, because the first value did not: the cost is
         * `inputs × COVERAGE_POINTS_PER_INPUT × capsules` = `60 × 3 × capsules`, and one dab
         * records one capsule (measured: 900 steps ⇒ 901 capsules), so clearing 200,000 needs
         * **1,112** and 900 gave 162,180. At 3,600 the estimate is 648,180 — 3.2× the budget,
         * which is what "several times over" has to mean for the forced-candidate path to be
         * the only thing that can prune this stroke.
         */
        const val DAB_STEPS = 3600

        /** `StrokeStore.PRUNE_BUDGET`, which is private — asserted against rather than reused. */
        const val PRUNE_BUDGET_ESTIMATE = 200_000L
        const val UNDO_OP_ID = "undo-1"
        const val DEVICE = "device-a"
    }
}
