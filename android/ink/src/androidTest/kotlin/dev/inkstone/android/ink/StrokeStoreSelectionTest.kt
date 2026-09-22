package dev.inkstone.android.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Lasso
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PagePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ⭐ ROADMAP M8a — selecting, moving and erasing **real ink**.
 *
 * Instrumented, and not out of caution: `androidx.ink`'s geometry is native, so nothing here can
 * run off a device. A stroke cannot be tessellated, a lasso cannot be tested against a
 * silhouette, and an eraser cannot decide what it covered. The JVM suite — including SPEC
 * §13.2's rows 3 and 7 — proves that the *ops* converge; this file is the only place that proves
 * a gesture produces the right ones.
 *
 * ## Geometry is hand-built, and deliberately without orientation
 *
 * CLAUDE.md: *"Any new ink test should build its geometry by drawing … rather than through
 * `SyntheticInk`, or it inherits the bug."* Drawing needs a window and a `MotionEvent` pump,
 * which is `:app`'s `InkHostActivity` and not reachable from this module. So these strokes simply carry
 * **no orientation and no tilt**, which keeps every expected coordinate checkable by hand.
 *
 * ⚠️ The quoted advice was written when `SyntheticInk` could emit an orientation outside
 * `[0, 2π)`. That was a seed-dependent fixture bug rather than anything to do with x86_64, and
 * it is fixed (`SyntheticInk.turn`); the preference for hand-built geometry here now rests on
 * legibility alone.
 *
 * The strokes are straight horizontal lines, so every expected coordinate below is arithmetic a
 * reader can check rather than a number recorded from a previous run.
 */
@RunWith(AndroidJUnit4::class)
class StrokeStoreSelectionTest {

    private lateinit var factory: InkBrushFactory
    private lateinit var store: StrokeStore

    private var ids = 0
    private var clocks = 0L

    @Before
    fun setUp() {
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        factory = InkBrushFactory(metrics.density, metrics.densityDpi / POINTS_PER_INCH)
        store = StrokeStore()
        store.recorder = PageOpRecorder(
            nextObjectId = { ObjectId("obj-${ids++}") },
            nextOpId = { "op-${ids++}" },
            nextClock = { LamportClock(clocks++, "test-device") },
        )
    }

    /**
     * JUnit's `assertNotNull` returns `Unit`, so it cannot be used as an expression —
     * `StrokeGeometryCodecTest` carries the same helper for the same reason. A null here is a
     * test failure with a message, not a `NullPointerException` three lines later.
     */
    private fun <T : Any> required(value: T?, what: String): T = requireNotNull(value) { "$what was null" }

    /**
     * A horizontal line from ([x], [y]) of [length] points.
     *
     * No orientation, no tilt — see the class KDoc. Pressure is constant so the fountain brush
     * gives a band of even width, which makes "the eraser hit the ink" a claim about position
     * rather than about how hard the fixture pressed.
     */
    private fun line(x: Float, y: Float, length: Float = LINE_LENGTH_PT): Stroke {
        val batch = MutableStrokeInputBatch()
        for (sample in 0 until SAMPLES) {
            val t = sample.toFloat() / (SAMPLES - 1)
            batch.add(
                type = InputToolType.STYLUS,
                x = x + length * t,
                y = y,
                elapsedTimeMillis = (t * STROKE_MILLIS).toLong(),
            )
        }
        return Stroke(factory.brushFor(PenConfig(InkBrushKind.FOUNTAIN, BLACK, PEN_WIDTH_DP)), batch)
    }

    private fun addLine(x: Float, y: Float): ObjectId {
        store.beginEdit()
        val id = required(store.add(line(x, y), InkBrushKind.FOUNTAIN), "the id the store assigned")
        store.endEdit()
        return id
    }

    private fun move(id: ObjectId, dx: Float, dy: Float) {
        store.applyOps(
            listOf(
                PageOp.SetTransform(
                    "op-move-${ids++}",
                    LamportClock(clocks++, "test-device"),
                    id,
                    Matrix3.translate(dx, dy),
                ),
            ),
            factory,
        )
    }

    private fun boxAround(x: Float, y: Float, halfWidth: Float = BOX_HALF_PT) = Lasso.rectangle(
        PagePoint(x - halfWidth, y - halfWidth),
        PagePoint(x + halfWidth + LINE_LENGTH_PT, y + halfWidth),
    )

    // -----------------------------------------------------------------------
    // Selecting
    // -----------------------------------------------------------------------

    @Test
    fun aLassoTakesTheStrokeInsideItAndLeavesTheOneOutside() {
        val inside = addLine(LINE_X, LINE_Y)
        addLine(LINE_X, LINE_Y + FAR_PT)

        val hits = store.objectsIn(boxAround(LINE_X, LINE_Y))

        assertEquals("the lasso took the wrong number of strokes", 1, hits.size)
        assertEquals(inside, hits.single())
    }

    /**
     * ⚠️ The test that catches a stale bounds cache.
     *
     * `DryStroke.bounds` is computed once, at insertion, in the stroke's own space — which was
     * correct for as long as every transform was the identity. A move that does not recompute
     * the **page-space** box leaves the stroke selectable where it used to be and invisible
     * where it is: the lasso misses it, the viewport culls it while it is on screen, and the
     * eraser passes straight through it. All four symptoms, one uninvalidated field.
     */
    @Test
    fun aMovedStrokeIsSelectedWhereItIsAndNotWhereItWas() {
        val id = addLine(LINE_X, LINE_Y)
        move(id, MOVE_DX, MOVE_DY)

        val atOldPlace = store.objectsIn(boxAround(LINE_X, LINE_Y))
        val atNewPlace = store.objectsIn(boxAround(LINE_X + MOVE_DX, LINE_Y + MOVE_DY))

        assertTrue("the stroke is still selectable where it used to be", atOldPlace.isEmpty())
        assertEquals("the stroke is not selectable where it now is", listOf(id), atNewPlace)
    }

    @Test
    fun aTapSelectsTheStrokeUnderItAndNothingWhereThereIsNoInk() {
        val id = addLine(LINE_X, LINE_Y)

        assertEquals(id, store.objectAt(LINE_X + LINE_LENGTH_PT / 2f, LINE_Y, TAP_SLOP_PT))
        assertNull("a tap on blank paper selected something", store.objectAt(LINE_X, LINE_Y + FAR_PT, TAP_SLOP_PT))
    }

    /** A tap into a hole the eraser made is a tap on blank paper, and must select nothing. */
    @Test
    fun aTapInsideAnErasedHoleSelectsNothing() {
        addLine(LINE_X, LINE_Y)
        val middle = PagePointF(LINE_X + LINE_LENGTH_PT / 2f, LINE_Y)
        store.beginEdit()
        store.eraseAlong(middle, middle, ERASER_RADIUS_PT, EraseMode.PARTIAL)
        store.endEdit()

        assertNull(
            "a tap in the erased gap selected the stroke it is a gap in",
            store.objectAt(middle.x, middle.y, SMALL_SLOP_PT),
        )
    }

    // -----------------------------------------------------------------------
    // Erasing a transformed stroke — the M8a question SPEC §13.2 row 7 cannot ask
    // -----------------------------------------------------------------------

    /**
     * ⭐ **The erased region is in the object's own space, so a moved stroke erases where it
     * looks.**
     *
     * Before M8a every transform was the identity and "page space" and "the object's space" were
     * two names for the same numbers. Once a stroke can move they are not, and getting it wrong
     * fails in one of two ways, both silent: record the sweep in page space and the hole stays
     * behind when the stroke is next moved; test coverage in page space and the eraser passes
     * through the moved stroke without touching it.
     *
     * The assertion is the coordinate itself. The eraser is driven at the stroke's **new** page
     * position, and the capsule that comes back must be at its **old** — that is,
     * `pagePosition − translation`, which is where the ink is in its own space.
     */
    @Test
    fun erasingAMovedStrokeHitsItAndRecordsTheSweepInTheStrokesOwnSpace() {
        val id = addLine(LINE_X, LINE_Y)
        move(id, MOVE_DX, MOVE_DY)

        val middleOnPage = PagePointF(LINE_X + MOVE_DX + LINE_LENGTH_PT / 2f, LINE_Y + MOVE_DY)
        store.beginEdit()
        val touched = store.eraseAlong(middleOnPage, middleOnPage, ERASER_RADIUS_PT, EraseMode.PARTIAL)
        store.endEdit()

        assertEquals("the eraser missed the stroke at its new position", 1, touched)
        val capsule = required(store.strokeOf(id)?.erased?.capsules?.firstOrNull(), "the recorded area")
        assertEquals("the sweep was recorded in page space", middleOnPage.x - MOVE_DX, capsule.fromX, TOLERANCE)
        assertEquals("the sweep was recorded in page space", middleOnPage.y - MOVE_DY, capsule.fromY, TOLERANCE)
    }

    /** The same claim under a rotation, where the inverse is not just a subtraction. */
    @Test
    fun erasingARotatedStrokeHitsIt() {
        val id = addLine(LINE_X, LINE_Y)
        val pivot = PagePoint(LINE_X, LINE_Y)
        val rotation = Matrix3.translate(pivot.x, pivot.y) *
            Matrix3.rotate(QUARTER_TURN) *
            Matrix3.translate(-pivot.x, -pivot.y)
        store.applyOps(
            listOf(PageOp.SetTransform("op-rot", LamportClock(clocks++, "test-device"), id, rotation)),
            factory,
        )

        // A quarter turn about the line's left end takes its midpoint from (x + L/2, y) to
        // (x, y + L/2) — straight down the page, which is where the eraser must now find it.
        val middle = rotation.map(PagePoint(LINE_X + LINE_LENGTH_PT / 2f, LINE_Y))
        val at = PagePointF(middle.x, middle.y)
        store.beginEdit()
        val touched = store.eraseAlong(at, at, ERASER_RADIUS_PT, EraseMode.PARTIAL)
        store.endEdit()

        assertEquals("the eraser missed a rotated stroke", 1, touched)
        assertEquals("erasing must never split or delete", 1, store.size)
    }

    /** Whatever else moves, the ink does not (SPEC §3.5, CLAUDE.md rule 1). */
    @Test
    fun movingAndErasingLeaveTheStrokeItselfUntouched() {
        val id = addLine(LINE_X, LINE_Y)
        val inputsBefore = required(store.strokeOf(id), "the stroke").stroke.inputs.size
        move(id, MOVE_DX, MOVE_DY)
        val at = PagePointF(LINE_X + MOVE_DX + LINE_LENGTH_PT / 2f, LINE_Y + MOVE_DY)
        store.beginEdit()
        store.eraseAlong(at, at, ERASER_RADIUS_PT, EraseMode.PARTIAL)
        store.endEdit()

        val inputsAfter = required(store.strokeOf(id), "the stroke").stroke.inputs.size
        assertEquals("the input list changed", inputsBefore, inputsAfter)
    }

    // -----------------------------------------------------------------------
    // Style and z-order
    // -----------------------------------------------------------------------

    /**
     * A restyle rebuilds the brush — and must not throw the erasure away with it.
     *
     * The region is in the stroke's own space and a restyle does not move the stroke, so the
     * bite stays exactly where the user put it even though the mesh around it has been rebuilt.
     */
    @Test
    fun aRestyleChangesTheBrushAndKeepsTheErasedRegion() {
        val id = addLine(LINE_X, LINE_Y)
        val at = PagePointF(LINE_X + LINE_LENGTH_PT / 2f, LINE_Y)
        store.beginEdit()
        store.eraseAlong(at, at, ERASER_RADIUS_PT, EraseMode.PARTIAL)
        store.endEdit()
        val capsulesBefore = required(store.strokeOf(id), "the stroke").erased?.capsules?.size

        store.applyOps(
            listOf(
                PageOp.SetStyle(
                    "op-style",
                    LamportClock(clocks++, "test-device"),
                    id,
                    ObjectStyle(colorArgb = RED, widthPt = RESTYLED_WIDTH_PT),
                ),
            ),
            factory,
        )

        val after = required(store.strokeOf(id), "the stroke")
        assertEquals("the colour did not reach the brush", RED, after.stroke.brush.colorIntArgb)
        assertEquals("the width did not reach the brush", RESTYLED_WIDTH_PT, after.stroke.brush.size, TOLERANCE)
        assertEquals("the erased region was lost with the old mesh", capsulesBefore, after.erased?.capsules?.size)
    }

    /**
     * ⚠️ **A stroke drawn after a bring-to-front must land on top of it.**
     *
     * `PageOpRecorder` used to hand out z keys from a counter of its own, which `SetZOrder` could
     * silently overtake — so bringing one stroke to the front made the *next* stroke the user
     * drew appear underneath it, on screen and in the published PDF's annotation order, with no
     * error and no reason to connect the two actions. The maximum is maintained by the store now
     * because the store is the only object that sees every writer of a key.
     */
    @Test
    fun aStrokeDrawnAfterABringToFrontLandsOnTopOfIt() {
        val first = addLine(LINE_X, LINE_Y)
        store.applyOps(
            listOf(PageOp.SetZOrder("op-z", LamportClock(clocks++, "test-device"), first, FractionalKey("zz"))),
            factory,
        )

        val next = addLine(LINE_X, LINE_Y + FAR_PT)

        val raised = required(store.strokeOf(first), "the raised stroke").z
        val drawn = required(store.strokeOf(next), "the new stroke").z
        assertTrue("the new stroke ($drawn) landed under the raised one ($raised)", drawn > raised)
    }

    /** Render order follows the keys, so what the log says is on top is what is drawn last. */
    @Test
    fun renderOrderFollowsTheZKeys() {
        val first = addLine(LINE_X, LINE_Y)
        val second = addLine(LINE_X, LINE_Y + FAR_PT)
        store.applyOps(
            listOf(PageOp.SetZOrder("op-z", LamportClock(clocks++, "test-device"), first, FractionalKey("zz"))),
            factory,
        )

        val order = ArrayList<ObjectId>()
        store.forEachInRenderOrder(visibleBounds = null) { order += it.id }

        assertEquals("the raised stroke is not drawn last", listOf(second, first), order)
    }

    /** A duplicate shares the mesh, gets its own identity, and does not disturb the original. */
    @Test
    fun aDuplicateIsANewObjectAtANewPlace() {
        val original = addLine(LINE_X, LINE_Y)
        val copyId = ObjectId("obj-copy")

        val offset = Matrix3.translate(MOVE_DX, 0f)
        val copy = required(store.duplicate(original, copyId, offset, FractionalKey("zz")), "the copy")

        assertEquals(2, store.size)
        assertEquals(copyId, copy.id)
        val originalTransform = required(store.strokeOf(original), "the original").transform
        assertEquals("the original moved", Matrix3.Identity, originalTransform)
        assertFalse("the copy is at the original's position", copy.transform == Matrix3.Identity)
    }

    private companion object {
        const val POINTS_PER_INCH = 72f
        const val SAMPLES = 24
        const val STROKE_MILLIS = 120f
        const val LINE_LENGTH_PT = 80f
        const val LINE_X = 120f
        const val LINE_Y = 200f
        const val MOVE_DX = 220f
        const val MOVE_DY = 90f
        const val FAR_PT = 300f
        const val BOX_HALF_PT = 12f
        const val TAP_SLOP_PT = 6f
        const val SMALL_SLOP_PT = 0.5f
        const val ERASER_RADIUS_PT = 6f
        const val PEN_WIDTH_DP = 3f
        const val RESTYLED_WIDTH_PT = 9f
        const val BLACK = 0xFF1A1A1A.toInt()
        const val RED = 0xFFCC0000.toInt()
        const val TOLERANCE = 0.01f
        const val QUARTER_TURN = 1.5707964f
    }
}
