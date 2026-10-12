package dev.inkstone.android.ink

import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.ElementInstance
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.Offset
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.SnapGrid
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ⭐ SPEC §4.9.7 — a placed Element copy is selected, framed and snapped as **one thing**.
 *
 * *"When you change the bounding box of the element it does not update the bounding box of the element
 * dragged in, and the pivot does not work — it just does not snap at all really."* Three causes, one
 * test each: a tap selected one stroke of a copy, the selection frame was its pieces' box and not the
 * Element's bounds, and an alignment guide won the axis whenever any edge on the page was near.
 *
 * The page: one copy of a 360 × 240 pt Element placed at (100, 100), with two 100 pt boxes at
 * (30, 30) and (200, 30) inside it and its pivot at (120, 120) — so on the page the bounds are
 * (100, 100)–(460, 340) and the pivot (220, 220). And a stray box elsewhere whose bottom edge sits
 * within a guide's reach of where the copy's bottom will be dragged. Large, because a grip's reach
 * is 22 dp and at this test's 1:1 camera that is ~44 pt: a press near a small copy's corner is a
 * resize, not a move.
 *
 * ⚠️ The controller, not the screen. That the tablet's drop and drag reach it is
 * `docs/M8-ELEMENT-EDITOR.md` §11.
 */
@RunWith(AndroidJUnit4::class)
class ElementCopySelectionTest {

    private lateinit var factory: InkBrushFactory
    private lateinit var store: StrokeStore
    private lateinit var controller: SelectionController
    private var clocks = 0L

    @Before
    fun setUp() {
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        factory = InkBrushFactory(metrics.density, metrics.densityDpi / POINTS_PER_INCH)
        store = StrokeStore()
        var ids = 0
        store.recorder = PageOpRecorder(
            nextObjectId = { ObjectId("obj-${ids++}") },
            nextOpId = { "op-${ids++}" },
            nextClock = { LamportClock(clocks++, "test-device") },
        )
        controller = SelectionController(
            store,
            factory,
            metrics.density,
            metrics.densityDpi / POINTS_PER_INCH,
            onPreview = {},
            onChanged = {},
        )
        store.applyOps(
            piece(FIRST, member = Matrix3.translate(30f, 30f)) +
                piece(SECOND, member = Matrix3.translate(200f, 30f)) +
                box(STRAY, Matrix3.translate(600f, 252f)),
            factory,
        )
    }

    @Test
    fun aTapOnOnePieceSelectsTheWholeCopy() {
        tap(180f, 180f)
        assertEquals(setOf(ObjectId(FIRST), ObjectId(SECOND)), controller.selection.toSet())
    }

    @Test
    fun aSelectedCopyIsFramedByItsBoundsAndNotOnlyItsPieces() {
        tap(180f, 180f)
        // The pieces cover (130, 130)–(400, 230); the Element's bounds are what the frame shows.
        val frame = requireNotNull(controller.bounds)
        assertEquals(100f, frame.left, TOLERANCE)
        assertEquals(100f, frame.top, TOLERANCE)
        assertEquals(460f, frame.right, TOLERANCE)
        assertEquals(340f, frame.bottom, TOLERANCE)
    }

    @Test
    fun withTheGridOnTheCopysPivotLandsOnTheLatticeEvenBesideAGuide() {
        controller.grid = SnapGrid(GRID, GRID)
        tap(180f, 180f)
        // Raw, the pivot would go to (270, 231) and the bounds' bottom to 351 — two points from the
        // stray box's bottom edge at 353, inside a guide's reach. The pivot is the request: (270, 234).
        drag(180f, 180f, 230f, 191f)

        val first = requireNotNull(store.objectOf(ObjectId(FIRST))).transform
        val pivot = requireNotNull(store.objectOf(ObjectId(FIRST))?.pivotOnPage)
        assertEquals(270f, pivot.x, TOLERANCE)
        assertEquals(234f, pivot.y, TOLERANCE)
        // And the whole copy moved with it, not one stroke of it.
        assertEquals(first.m02 + 170f, requireNotNull(store.objectOf(ObjectId(SECOND))).transform.m02, TOLERANCE)
    }

    private fun piece(id: String, member: Matrix3): List<PageOp> {
        val tag =
            ElementInstance(ELEMENT, INSTANCE, member, pivot = PagePoint(120f, 120f), size = PagePoint(360f, 240f))
        return box(id, Matrix3.translate(100f, 100f) * member) +
            PageOp.SetFormat("", LamportClock(clocks++, "seed"), ObjectId(id), ObjectFormat(instance = tag).encode())
    }

    private fun box(id: String, at: Matrix3): List<PageOp> = listOf(
        PageOp.AddObject(
            "",
            LamportClock(clocks++, "seed"),
            ShapeObject(
                id = ObjectId(id),
                shape = ShapeKind.RECTANGLE,
                widthPt = 100f,
                heightPt = 100f,
                transform = at,
                style = ObjectStyle(Color.BLACK, widthPt = 2f),
                z = FractionalKey("V$id"),
            ),
        ),
    )

    private fun tap(x: Float, y: Float) = drag(x, y, x, y)

    /** One selection gesture, view space == page space. */
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

    private companion object {
        const val ELEMENT = "element"
        const val INSTANCE = "drop-1"
        const val FIRST = "a"
        const val SECOND = "b"
        const val STRAY = "s"
        const val GRID = 18f
        const val POINTS_PER_INCH = 72f
        const val MOVE_MILLIS = 16L
        const val TOLERANCE = 0.01f
        val CAMERA = Camera(Offset(0f, 0f), 1f)
    }
}
