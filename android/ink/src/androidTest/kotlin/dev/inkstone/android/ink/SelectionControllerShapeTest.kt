package dev.inkstone.android.ink

import android.os.SystemClock
import android.view.MotionEvent
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.Offset
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.ShapeEdit
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.ShapeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ⭐ The M8a/M8b seam — what the selection tool does to a **shape**.
 *
 * M8b made shapes selectable (`DryShape` answers the shared hit predicate) without updating the
 * M8a action paths that assumed everything selected was a stroke. Two of them then emitted wrong
 * ops for a shape a user can create today with draw-and-hold: `restyle` read `strokeOf` and fell
 * to `DEFAULT_STYLE`, so recolouring a shape reset its width to 3 pt and set-width recoloured it
 * black; and `duplicate` silently dropped shapes from the copy. These tests fail on that build.
 *
 * Instrumented for the reason [StrokeStoreSelectionTest] is — the stroke half needs
 * `androidx.ink`'s native geometry — and the strokes carry no orientation and no tilt, so the
 * ten M1 x86_64 failures cannot leak in.
 */
@RunWith(AndroidJUnit4::class)
class SelectionControllerShapeTest {

    private lateinit var factory: InkBrushFactory
    private lateinit var store: StrokeStore
    private lateinit var controller: SelectionController

    private var density = 1f
    private var pixelsPerPoint = 1f
    private var ids = 0
    private var clocks = 0L

    @Before
    fun setUp() {
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        density = metrics.density
        pixelsPerPoint = metrics.densityDpi / POINTS_PER_INCH
        factory = InkBrushFactory(density, pixelsPerPoint)
        store = StrokeStore()
        store.recorder = PageOpRecorder(
            nextObjectId = { ObjectId("obj-${ids++}") },
            nextOpId = { "op-${ids++}" },
            nextClock = { LamportClock(clocks++, "test-device") },
        )
        controller = SelectionController(store, factory, density, pixelsPerPoint) {}
    }

    private fun <T : Any> required(value: T?, what: String): T = requireNotNull(value) { "$what was null" }

    /** A rectangle through the store's one door, styled the way draw-and-hold styles one. */
    private fun addRectangle(left: Float, top: Float, right: Float, bottom: Float): ObjectId {
        val recorder = required(store.recorder, "the recorder")
        val id = recorder.newObjectId()
        store.beginEdit()
        store.applyOps(
            ShapeEdit.create(
                id = id,
                placement = ShapeEdit.place(ShapeKind.RECTANGLE, PageRect(left, top, right, bottom)),
                z = store.allocateTopZ(1).single(),
                style = ObjectStyle(SHAPE_COLOR, SHAPE_WIDTH_PT),
                nextOpId = recorder::newOpId,
                nextClock = recorder::newClock,
            ),
            factory,
            record = true,
        )
        store.endEdit()
        return id
    }

    /** A horizontal line — no orientation, no tilt, per the class KDoc. */
    private fun addLine(x: Float, y: Float): ObjectId {
        val batch = MutableStrokeInputBatch()
        for (sample in 0 until SAMPLES) {
            val t = sample.toFloat() / (SAMPLES - 1)
            batch.add(
                type = InputToolType.STYLUS,
                x = x + LINE_LENGTH_PT * t,
                y = y,
                elapsedTimeMillis = (t * STROKE_MILLIS).toLong(),
            )
        }
        val stroke = Stroke(factory.brushFor(PenConfig(InkBrushKind.FOUNTAIN, BLACK, PEN_WIDTH_DP)), batch)
        store.beginEdit()
        val id = required(store.add(stroke, InkBrushKind.FOUNTAIN), "the id the store assigned")
        store.endEdit()
        return id
    }

    /** One selection drag, view space == page space (camera at identity). */
    private fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, fromX, fromY, 0)
        controller.begin(down, 0, down.getPointerId(0), CAMERA, PageSize.Letter)
        down.recycle()
        val move = MotionEvent.obtain(downTime, downTime + MOVE_MILLIS, MotionEvent.ACTION_MOVE, toX, toY, 0)
        controller.extend(move, CAMERA)
        move.recycle()
        controller.end(CAMERA)
    }

    // -----------------------------------------------------------------------
    // Restyle — the register only this seam can corrupt
    // -----------------------------------------------------------------------

    @Test
    fun recolouringASelectedShapeKeepsItsWidth() {
        val id = addRectangle(SHAPE_LEFT, SHAPE_TOP, SHAPE_RIGHT, SHAPE_BOTTOM)
        controller.select(listOf(id))

        controller.recolour(RED)

        val style = required((store.objectOf(id) as? DryShape)?.style, "the shape's live style")
        assertEquals("the colour did not land", RED, style.colorArgb)
        assertEquals("recolour reset the shape's width", SHAPE_WIDTH_PT, style.widthPt, TOLERANCE)
        // The op is what syncs — the peer must see the same style the screen shows.
        val written = required(store.recorder, "the recorder").drain()
            .flatten().filterIsInstance<PageOp.SetStyle>().last()
        assertEquals(RED, written.style.colorArgb)
        assertEquals(SHAPE_WIDTH_PT, written.style.widthPt, TOLERANCE)
    }

    @Test
    fun settingWidthOnASelectedShapeKeepsItsColour() {
        val id = addRectangle(SHAPE_LEFT, SHAPE_TOP, SHAPE_RIGHT, SHAPE_BOTTOM)
        controller.select(listOf(id))

        controller.setWidth(NEW_WIDTH_DP)

        val style = required((store.objectOf(id) as? DryShape)?.style, "the shape's live style")
        assertEquals("set-width recoloured the shape", SHAPE_COLOR, style.colorArgb)
        assertEquals(NEW_WIDTH_DP * density / pixelsPerPoint, style.widthPt, TOLERANCE)
    }

    // -----------------------------------------------------------------------
    // Duplicate — the copy path that dropped shapes
    // -----------------------------------------------------------------------

    @Test
    fun duplicatingAMixedSelectionCopiesTheShapeToo() {
        val strokeId = addLine(LINE_X, LINE_Y)
        val shapeId = addRectangle(SHAPE_LEFT, SHAPE_TOP, SHAPE_RIGHT, SHAPE_BOTTOM)
        controller.select(listOf(strokeId, shapeId))

        controller.duplicate()

        assertEquals("the copy is missing objects", 4, store.size)
        assertEquals("the copies are not what is selected", 2, controller.selection.size)
        val shapeCopy = controller.selection
            .mapNotNull { store.objectOf(it) }
            .filterIsInstance<DryShape>()
            .single()
        assertTrue("the copy reused the original's id", shapeCopy.id != shapeId)
        assertEquals(ShapeKind.RECTANGLE, shapeCopy.obj.shape)
        assertEquals("the copy lost the shape's style", SHAPE_COLOR, required(shapeCopy.style, "style").colorArgb)
        val original = required(store.objectOf(shapeId), "the original")
        assertTrue("the copy sits exactly on the original", shapeCopy.transform != original.transform)
        // The copy must reach the log, or it exists on this screen and no peer's.
        val adds = required(store.recorder, "the recorder").drain().flatten().filterIsInstance<PageOp.AddObject>()
        assertTrue("the shape copy never became an op", adds.any { it.obj is ShapeObject && it.obj.id == shapeCopy.id })
    }

    // -----------------------------------------------------------------------
    // Record edits — the fill's undo baseline (the first-SetStyle trap, record register)
    // -----------------------------------------------------------------------

    @Test
    fun fillingAFreshShapeWritesABaselineSoTheFillCanBeUndone() {
        val id = addRectangle(SHAPE_LEFT, SHAPE_TOP, SHAPE_RIGHT, SHAPE_BOTTOM)
        controller.select(listOf(id))

        controller.setFill(FILL_GREEN, FILL_OPACITY)

        // The live register carries the fill.
        val record = required(store.objectOf(id) as? DryShape, "the shape").record
        assertEquals(FILL_GREEN, record.fillArgb)
        // Without the baseline gesture, the fill gesture has no inverse — SetTextContent cannot
        // say "unset" — so UndoStack.commit drops it and pressing undo pops the gesture BEFORE
        // the fill: the box vanishes, filled. Two gestures must reach the log: the default
        // record first, then the fill.
        val gestures = required(store.recorder, "the recorder").drain()
            .map { gesture -> gesture.filterIsInstance<PageOp.SetTextContent>().filter { it.target == id } }
            .filter { it.isNotEmpty() }
        assertEquals("expected a baseline gesture then the fill gesture", 2, gestures.size)
        assertEquals(
            "the baseline must be the record the shape already had",
            ShapeRecord.None,
            ShapeRecord.decode(gestures[0].single().text),
        )
        assertEquals(FILL_GREEN, required(ShapeRecord.decode(gestures[1].single().text), "the fill record").fillArgb)
    }

    // -----------------------------------------------------------------------
    // Marquee — the rectangular half of "freehand AND rectangular"
    // -----------------------------------------------------------------------

    @Test
    fun aMarqueeDragSelectsWhatItsBoxContains() {
        val id = addRectangle(SHAPE_LEFT, SHAPE_TOP, SHAPE_RIGHT, SHAPE_BOTTOM)
        controller.marquee = true

        drag(MARQUEE_LEFT, MARQUEE_TOP, MARQUEE_RIGHT, MARQUEE_BOTTOM)

        assertEquals("the marquee missed the shape inside its box", listOf(id), controller.selection)
    }

    /** The control: the same two-point drag as a freehand lasso has no area and takes nothing. */
    @Test
    fun theSameDragWithoutTheMarqueeSelectsNothing() {
        addRectangle(SHAPE_LEFT, SHAPE_TOP, SHAPE_RIGHT, SHAPE_BOTTOM)

        drag(MARQUEE_LEFT, MARQUEE_TOP, MARQUEE_RIGHT, MARQUEE_BOTTOM)

        assertTrue("a diagonal freehand lasso selected something", controller.selection.isEmpty())
    }

    private companion object {
        const val POINTS_PER_INCH = 72f
        const val SAMPLES = 24
        const val STROKE_MILLIS = 120f
        const val MOVE_MILLIS = 16L
        const val LINE_LENGTH_PT = 80f
        const val LINE_X = 120f
        const val LINE_Y = 400f
        const val SHAPE_LEFT = 150f
        const val SHAPE_TOP = 150f
        const val SHAPE_RIGHT = 250f
        const val SHAPE_BOTTOM = 210f
        const val MARQUEE_LEFT = 100f
        const val MARQUEE_TOP = 100f
        const val MARQUEE_RIGHT = 300f
        const val MARQUEE_BOTTOM = 260f
        const val SHAPE_COLOR = 0xFF2266AA.toInt()
        const val SHAPE_WIDTH_PT = 6f
        const val FILL_GREEN = 0xFF00AA44.toInt()
        const val FILL_OPACITY = 0.5f
        const val NEW_WIDTH_DP = 8f
        const val RED = 0xFFCC0000.toInt()
        const val BLACK = 0xFF1A1A1A.toInt()
        const val PEN_WIDTH_DP = 3f
        const val TOLERANCE = 0.01f
        val CAMERA = Camera(Offset(0f, 0f), 1f)
    }
}
