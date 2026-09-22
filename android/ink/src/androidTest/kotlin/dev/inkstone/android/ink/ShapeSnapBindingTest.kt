package dev.inkstone.android.ink

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.ShapeEdit
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapePlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ⭐ A snapped line binds to a **box**, never to another line.
 *
 * ## The report
 *
 * *"You create a line but start drawing your line on a stroke on the page, it does not form
 * correctly and goes into an image like selection, not the proper point based editing."* The
 * "stroke" was a snapped line, and `StrokeStore.shapeAt` accepted it as an anchor: the new line
 * became a connector bound to it, routed from the boundary of a box one pen-width tall, collapsed
 * to a stub on the line it touched, and — being a connector — offered the frame's grips and not its
 * two points. Reproduced on an emulator before the fix: the journal held
 * `"x":"is1;a=o:<the line underneath>"`.
 *
 * ## Through the production controller
 *
 * [ShapeSnapController.snap] is the only caller of `shapeAt`, so the gesture goes through it rather
 * than asking `shapeAt` directly — a test of the lookup alone would still pass if the controller
 * bound through some other path. [aLineDrawnFromInsideABoxStillAttachesToIt] is the control: without
 * it, a harness that could never bind anything would make the refusal pass vacuously.
 *
 * Instrumented because `DryShape` builds an `android.graphics.Path` in its constructor. No `SyntheticInk`, so the
 * geometry here is arithmetic a reader can check.
 */
@RunWith(AndroidJUnit4::class)
class ShapeSnapBindingTest {

    private lateinit var factory: InkBrushFactory
    private lateinit var store: StrokeStore
    private lateinit var snap: ShapeSnapController

    private var ids = 0
    private var clocks = 0L
    private var snapped: Pair<ObjectId, ShapeSnapResult>? = null

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
        snap = ShapeSnapController(
            store = store,
            brushes = factory,
            cancelWetStroke = {},
            onSnapped = { id, result -> snapped = id to result },
        )
    }

    private fun <T : Any> required(value: T?, what: String): T = requireNotNull(value) { "$what was null" }

    /** Through `ShapeEdit.create` and `applyOps`, the door a loaded note's shapes arrive by. */
    private fun createShape(placement: ShapePlacement): ObjectId {
        val recorder = required(store.recorder, "the recorder")
        val id = recorder.newObjectId()
        val ops = ShapeEdit.create(
            id = id,
            placement = placement,
            z = store.allocateTopZ(1).single(),
            style = ObjectStyle(BLACK, PEN_WIDTH_PT),
            nextOpId = recorder::newOpId,
            nextClock = recorder::newClock,
        )
        store.beginEdit()
        store.applyOps(ops, factory, record = true)
        store.endEdit()
        return id
    }

    /**
     * A straight gesture from one point to another, classified and snapped.
     *
     * Samples 2 pt apart — closer than the detector's 3 pt spacing, as a 240 Hz pen is — and never
     * inside the dwell window long enough to count as a hold; [ShapeSnapController.snap] classifies
     * whatever was drawn, which is what the surface's timer calls.
     */
    private fun drawAndSnap(fromX: Float, fromY: Float, toX: Float, toY: Float): Pair<ObjectId, ShapeSnapResult> {
        snapped = null
        store.beginEdit()
        snap.begin(fromX, fromY, 0L)
        for (step in 1..GESTURE_SAMPLES) {
            val t = step.toFloat() / GESTURE_SAMPLES
            snap.sample(fromX + (toX - fromX) * t, fromY + (toY - fromY) * t, step * SAMPLE_MILLIS)
        }
        val made = snap.snap(BLACK, PEN_WIDTH_PT, BIND_SLOP_PT)
        store.endEdit()
        assertTrue("the gesture did not snap at all, so nothing here measures binding", made)
        return required(snapped, "the snapped shape")
    }

    @Test
    fun aLineDrawnFromOneLineToAnotherStaysAPlainLine() {
        createShape(ShapeEdit.placeSegment(LINE_LEFT_X, UPPER_Y, LINE_RIGHT_X, UPPER_Y, bandPt = PEN_WIDTH_PT))
        createShape(ShapeEdit.placeSegment(LINE_LEFT_X, LOWER_Y, LINE_RIGHT_X, LOWER_Y, bandPt = PEN_WIDTH_PT))

        // Starts exactly on the upper line and ends exactly on the lower one: both ends are the
        // case, and the two lines are different objects so the same-shape refusal cannot mask it.
        val (id, result) = drawAndSnap(GESTURE_X, UPPER_Y, GESTURE_X + GESTURE_LEAN, LOWER_Y)

        assertEquals("the gesture is not a line, so the question does not arise", ShapeKind.LINE, result.kind)
        assertFalse("a line was attached to another line", result.attached)
        // What decides point handles: `SelectionController.soleShape` refuses a connector.
        val line = required(store.shapeOf(id), "the line")
        assertFalse("the new line is a connector, so it has no point handles", line.isConnector)
    }

    @Test
    fun aLineDrawnFromInsideABoxStillAttachesToIt() {
        createShape(ShapeEdit.place(ShapeKind.RECTANGLE, PageRect(BOX_LEFT, BOX_TOP, BOX_RIGHT, BOX_BOTTOM)))

        val (id, result) = drawAndSnap((BOX_LEFT + BOX_RIGHT) / 2f, (BOX_TOP + BOX_BOTTOM) / 2f, FAR_X, FAR_Y)

        assertTrue("a line drawn out of a box did not attach — the refusal above would be vacuous", result.attached)
        assertTrue("the attached line is not a connector", required(store.shapeOf(id), "the connector").isConnector)
    }

    private companion object {
        const val POINTS_PER_INCH = 72f
        const val BLACK = 0xFF1A1A1A.toInt()
        const val PEN_WIDTH_PT = 3f

        /** `InkSurfaceView.ENDPOINT_BIND_SLOP_DP` at a scale of one. */
        const val BIND_SLOP_PT = 20f

        const val GESTURE_SAMPLES = 100
        const val SAMPLE_MILLIS = 4L

        const val LINE_LEFT_X = 100f
        const val LINE_RIGHT_X = 400f
        const val UPPER_Y = 300f
        const val LOWER_Y = 500f
        const val GESTURE_X = 250f
        const val GESTURE_LEAN = 40f

        const val BOX_LEFT = 600f
        const val BOX_TOP = 100f
        const val BOX_RIGHT = 800f
        const val BOX_BOTTOM = 300f
        const val FAR_X = 950f
        const val FAR_Y = 500f
    }
}
