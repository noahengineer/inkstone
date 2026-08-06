package dev.inkstone.android.ink

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    @Test
    fun wholeStrokeModeStillDeletesTheWholeStroke() {
        store.addAll(SyntheticInk.generate(1, page, factory, seed = 3, pointsPerStroke = 60).items)
        val midpoint = midpointOfOnlyStroke()
        val at = PagePointF(midpoint.first, midpoint.second)

        store.eraseAlong(at, at, DAB_RADIUS_PT, EraseMode.WHOLE_STROKE)

        assertEquals(0, store.size)
    }

    private fun onlyStroke(): DryStroke {
        var found: DryStroke? = null
        store.forEachInRenderOrder(null) { found = it }
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

    @Test
    fun highlightersRenderBeforePens() {
        loadStrokes(SMALL_COUNT)

        val order = ArrayList<InkBrushKind>()
        store.forEachInRenderOrder(null) { order += it.kind }

        val lastHighlighter = order.indexOfLast { it == InkBrushKind.HIGHLIGHTER }
        val firstPen = order.indexOfFirst { it != InkBrushKind.HIGHLIGHTER }
        assertTrue("the fixture produced no highlighters", lastHighlighter >= 0)
        assertTrue("the fixture produced no pens", firstPen >= 0)
        assertTrue(
            "SPEC §3.4: the highlighter draws behind ink, so it must be drawn first",
            lastHighlighter < firstPen,
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
    }
}
