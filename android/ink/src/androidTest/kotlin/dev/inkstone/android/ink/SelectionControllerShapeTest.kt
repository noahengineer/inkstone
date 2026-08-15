package dev.inkstone.android.ink

import android.os.SystemClock
import android.view.MotionEvent
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.ConnectorEnd
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.Offset
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.ShapeEdit
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.ShapePointRole
import dev.inkstone.core.model.ShapePoints
import dev.inkstone.core.model.ShapeRecord
import dev.inkstone.core.model.ShapeSegment
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
        controller = SelectionController(store, factory, density, pixelsPerPoint, onPreview = {}, onChanged = {})
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

    /**
     * A line **shape**, placed by its endpoints exactly as draw-and-hold places one.
     *
     * Not [addLine], which is a pen *stroke*: a stroke has no points to edit, and building the
     * fixture through `ShapeEdit.placeSegment` is what keeps this test measuring the same geometry
     * `ShapeSnapController` creates rather than a convenient one of its own (CLAUDE.md: *"distrust
     * a fixture that is more generous than production"*).
     */
    private fun addLineShape(record: ShapeRecord = ShapeRecord.None): ObjectId {
        val recorder = required(store.recorder, "the recorder")
        val id = recorder.newObjectId()
        store.beginEdit()
        store.applyOps(
            ShapeEdit.create(
                id = id,
                placement = ShapeEdit.placeSegment(
                    LINE_START_X,
                    LINE_SHAPE_Y,
                    LINE_END_X,
                    LINE_SHAPE_Y,
                    bandPt = LINE_BAND_PT,
                ),
                z = store.allocateTopZ(1).single(),
                style = ObjectStyle(SHAPE_COLOR, SHAPE_WIDTH_PT),
                record = record,
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
    // Point handles — the gesture, end to end, through the real controller
    // -----------------------------------------------------------------------

    /**
     * ⭐ The check `ShapePointsTest` cannot make: that a press lands on a handle at all.
     *
     * The arithmetic is settled in `:core:model`. What is only true here is that the handle is
     * where the controller says it is, that a pen-down within reach of it starts a point drag
     * rather than a lasso or a move, and that the drag writes **one** absolute `SetTransform` — the
     * three joints where a correct solve still reaches nobody.
     */
    @Test
    fun draggingALineEndpointMovesThatEndAndWritesOneTransform() {
        val id = addLineShape()
        controller.select(listOf(id))
        val before = required(store.objectOf(id), "the line").transform
        val end = required(controller.points.lastOrNull(), "the end handle")

        drag(end.page.x, end.page.y, LINE_END_X, LINE_DRAGGED_Y)

        val after = required(store.objectOf(id), "the line").transform
        val ends = ShapePoints.of(ShapeKind.LINE, LINE_SPAN_PT, LINE_BAND_PT)
        // The dragged end is under the finger and the other one did not move.
        assertPointEquals(PagePoint(LINE_END_X, LINE_DRAGGED_Y), after.map(ends[2].local))
        assertPointEquals(before.map(ends[0].local), after.map(ends[0].local))
        // One op, not one per frame — and absolute, which is what makes it merge (CLAUDE.md rule 8).
        val transforms = required(store.recorder, "the recorder").drain()
            .flatten().filterIsInstance<PageOp.SetTransform>().filter { it.target == id }
        assertEquals("a point drag must write exactly one op", 1, transforms.size)
        assertEquals(after, transforms.single().absolute)
    }

    /** The middle handle is the curve, and it writes the *other* register. */
    @Test
    fun draggingALineMiddleWritesABendRecordAndCurvesTheOutline() {
        val id = addLineShape()
        controller.select(listOf(id))
        val middle = required(controller.points.getOrNull(1), "the bend handle")
        assertEquals(ShapePointRole.BEND, middle.point.role)

        drag(middle.page.x, middle.page.y, middle.page.x, middle.page.y - BEND_PULL_PT)

        val shape = required(store.objectOf(id) as? DryShape, "the line")
        val bend = required(shape.record.bend, "the bend the drag should have written")
        // The curve is real geometry, not a flag: the outline is a cubic and it passes through the
        // handle. A straight line's outline has no cubic at all.
        assertTrue("the outline stayed straight", shape.outline.segments.any { it is ShapeSegment.CubicTo })
        assertPointEquals(bend, shape.outline.flattened(samplesPerCurve = 2)[1])
        // ⚠️ Two gestures: the default-record baseline, then the bend. Without the baseline the
        // bend has no inverse — SetTextContent cannot say "unset" — and UndoStack.commit drops the
        // whole gesture, so "bend a line, press undo" would eat the press and keep the curve.
        val gestures = required(store.recorder, "the recorder").drain()
            .map { gesture -> gesture.filterIsInstance<PageOp.SetTextContent>().filter { it.target == id } }
            .filter { it.isNotEmpty() }
        assertEquals("expected a baseline gesture then the bend gesture", 2, gestures.size)
        assertEquals(ShapeRecord.None, ShapeRecord.decode(gestures[0].single().text))
        assertEquals(bend, required(ShapeRecord.decode(gestures[1].single().text), "the bend record").bend)
    }

    /** A press on a handle that goes nowhere must not put a record in the file. */
    @Test
    fun tappingTheMiddleHandleWithoutMovingWritesNothing() {
        val id = addLineShape()
        controller.select(listOf(id))
        val middle = required(controller.points.getOrNull(1), "the bend handle")
        required(store.recorder, "the recorder").drain()

        drag(middle.page.x, middle.page.y, middle.page.x, middle.page.y)

        assertEquals(null, required(store.objectOf(id) as? DryShape, "the line").record.bend)
        val written = required(store.recorder, "the recorder").drain().flatten()
        assertTrue("a press that moved nothing wrote $written", written.isEmpty())
    }

    /**
     * ⚠️ The handles replace the frame's eight grips, so the press has to reach the shape's corner
     * and not the box's — they are the same pixels for an axis-aligned rectangle, and the two
     * gestures do different things.
     */
    @Test
    fun aRectangleCornerDragFollowsTheFingerRatherThanScalingAboutTheDiagonal() {
        val id = addRectangle(SHAPE_LEFT, SHAPE_TOP, SHAPE_RIGHT, SHAPE_BOTTOM)
        controller.select(listOf(id))
        assertEquals("a rectangle should offer four corners", 4, controller.points.size)
        val corner = controller.points[2]

        drag(corner.page.x, corner.page.y, CORNER_TARGET_X, CORNER_TARGET_Y)

        val after = required(store.objectOf(id), "the rectangle").transform
        val corners = ShapePoints.of(ShapeKind.RECTANGLE, SHAPE_RIGHT - SHAPE_LEFT, SHAPE_BOTTOM - SHAPE_TOP)
        assertPointEquals(PagePoint(CORNER_TARGET_X, CORNER_TARGET_Y), after.map(corners[2].local))
        assertPointEquals(PagePoint(SHAPE_LEFT, SHAPE_TOP), after.map(corners[0].local))
    }

    /** A connector's handles would be dead: its geometry is re-derived from what it is attached to. */
    @Test
    fun aConnectorOffersNoPointHandles() {
        val boxId = addRectangle(SHAPE_LEFT, SHAPE_TOP, SHAPE_RIGHT, SHAPE_BOTTOM)
        val id = addLineShape(ShapeRecord(endA = ConnectorEnd.Attached(boxId)))

        controller.select(listOf(id))

        assertTrue("a connector offered handles that cannot move it", controller.points.isEmpty())
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

    // -----------------------------------------------------------------------
    // The store changing under the selection — undo, redo, a peer, an erase
    // -----------------------------------------------------------------------

    /** "Undo something selected": the outline must not stay standing round a deleted object. */
    @Test
    fun deletingASelectedObjectThroughApplyOpsDropsItFromTheSelection() {
        val id = addRectangle(SHAPE_LEFT, SHAPE_TOP, SHAPE_RIGHT, SHAPE_BOTTOM)
        controller.select(listOf(id))
        assertEquals(1, controller.size)

        val recorder = required(store.recorder, "the recorder")
        store.applyOps(
            listOf(PageOp.DeleteObject(recorder.newOpId(), recorder.newClock(), id)),
            factory,
            record = false,
        )
        controller.onStoreChanged()

        assertEquals("the selection kept a dead id", 0, controller.size)
        assertTrue("the outline box outlived the object", controller.bounds == null)
    }

    /** Undo of a move: the ink jumped back, and the box has to jump with it. */
    @Test
    fun aTransformThroughApplyOpsMovesTheSelectionBounds() {
        val id = addRectangle(SHAPE_LEFT, SHAPE_TOP, SHAPE_RIGHT, SHAPE_BOTTOM)
        controller.select(listOf(id))
        val before = required(controller.bounds, "the selection box")

        val recorder = required(store.recorder, "the recorder")
        store.applyOps(
            listOf(
                PageOp.SetTransform(
                    recorder.newOpId(),
                    recorder.newClock(),
                    id,
                    dev.inkstone.core.model.Matrix3.translate(NUDGE_PT, 0f),
                ),
            ),
            factory,
            record = false,
        )
        controller.onStoreChanged()

        val after = required(controller.bounds, "the selection box after the move")
        // SetTransform is an ABSOLUTE register (CLAUDE.md rule 8): the write *replaces* the
        // shape's placement, so the box lands where the new matrix says — at the origin plus
        // the nudge — not at old-box-plus-delta. (The first version of this test expected the
        // delta reading and the store correctly failed it.)
        assertEquals("the box did not follow the object", NUDGE_PT, after.left, TOLERANCE)
        assertTrue("the box did not move at all", after.left != before.left)
    }

    private fun assertPointEquals(expected: PagePoint, actual: PagePoint) {
        assertEquals("x", expected.x, actual.x, TOLERANCE)
        assertEquals("y", expected.y, actual.y, TOLERANCE)
    }

    private companion object {
        const val LINE_START_X = 120f
        const val LINE_END_X = 320f
        const val LINE_SHAPE_Y = 480f
        const val LINE_SPAN_PT = LINE_END_X - LINE_START_X
        const val LINE_BAND_PT = 4f

        /** Straight down from the end handle: a rotation the frame's grips could not express. */
        const val LINE_DRAGGED_Y = 600f
        const val BEND_PULL_PT = 60f
        const val CORNER_TARGET_X = 300f
        const val CORNER_TARGET_Y = 260f

        const val NUDGE_PT = 25f
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
