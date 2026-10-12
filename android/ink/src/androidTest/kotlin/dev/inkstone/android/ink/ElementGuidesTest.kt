package dev.inkstone.android.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.foldPage
import dev.inkstone.core.storage.ElementEditing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ⭐ SPEC §4.9.7 — the Element editor's guides, as the surface treats them.
 *
 * The page: a knockout area (a 70 pt square cutter, at the **bottom**, as the area tool places it)
 * with a hollow ring drawn over it, and the Element's bounds around both. The report these answer is
 * *"hard to position right with no undo or edit"*: an area under a piece could be seen and not
 * selected, because the piece's inside won every tap.
 *
 * ⚠️ The store and the painter, not the gestures. That a tap with the Select tool reaches [objectAt]
 * and that the area tool leaves the new area selected is the emulator walk in
 * `docs/M8-ELEMENT-EDITOR.md` §9.
 */
@RunWith(AndroidJUnit4::class)
class ElementGuidesTest {

    private lateinit var brushes: InkBrushFactory

    @Before
    fun setUp() {
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        brushes = InkBrushFactory(metrics.density, metrics.densityDpi / POINTS_PER_INCH)
    }

    @Test
    fun inTheEditorATapInsideAnAreaUnderAShapeSelectsTheArea() {
        val store = page(editing = true)
        assertEquals(ObjectId(AREA), store.objectAt(100f, 100f, SLOP))
    }

    @Test
    fun inTheEditorTheInkOfAShapeStillBeatsTheAreaUnderIt() {
        val store = page(editing = true)
        // On the ring's own outline where it crosses the area: the ring is drawn there, the area only
        // underneath. 45° round a 40 pt ring centred on (100, 100) — and well clear of the area's edge.
        assertEquals(ObjectId(RING), store.objectAt(128.28f, 128.28f, SLOP))
    }

    @Test
    fun inANoteTheTopmostObjectStillWins() {
        // Unchanged everywhere but the editor — an area on a note is invisible, and the piece over it
        // is what the eye says was tapped.
        val store = page(editing = false)
        assertEquals(ObjectId(RING), store.objectAt(100f, 100f, SLOP))
    }

    @Test
    fun theBoundsAreGrabbedByTheirEdgeAndNotTheirInside() {
        val store = page(editing = true)
        assertEquals("on the edge", ObjectId(FRAME), store.objectAt(20f, 120f, SLOP))
        assertNull("inside, on nothing", store.objectAt(36f, 36f, SLOP))
    }

    @Test
    fun theEraserLeavesTheBoundsAlone() {
        val store = page(editing = true)
        // Straight down the bounds' left edge, clear of the area and the ring.
        val touched = store.eraseAlong(PagePointF(20f, 30f), PagePointF(20f, 170f), ERASER, EraseMode.PARTIAL)
        assertEquals(0, touched)
    }

    @Test
    fun theBoundsAndPivotAreDrawnInTheEditorAndNeverInANote() {
        val editor = render(page(editing = true), ElementBackdrop.LIGHT)
        val note = render(page(editing = false), backdrop = null)
        assertTrue("amber bounds on the editor's canvas", editor.count { isAmber(it) } > MIN_GUIDE_PIXELS)
        assertEquals("and none in a note", 0, note.count { isAmber(it) })
        // The pivot sits at (140, 60): a ring and a cross in the same amber.
        assertTrue("the pivot is marked", isAmber(editor[60 * SIZE + 140]))
    }

    @Test
    fun theBoundsPublishNothing() = runBlocking {
        val outlines = InkStrokeOutliner(brushes).outline(foldPage(PageId("guides"), ops()))
        assertTrue(outlines.none { it.objectId == ObjectId(FRAME) })
    }

    private fun page(editing: Boolean): StrokeStore = StrokeStore().apply {
        applyOps(ops(), brushes, record = false)
        guidesShown = editing
    }

    private fun ops(): List<PageOp> {
        var clock = 0L
        val next = { LamportClock(++clock, "device") }
        val area = ShapeObject(
            id = ObjectId(AREA),
            shape = ShapeKind.RECTANGLE,
            widthPt = 70f,
            heightPt = 70f,
            transform = Matrix3.translate(65f, 65f),
            style = ObjectStyle(Color.BLUE, widthPt = 1f),
            z = FractionalKey("b"),
        )
        val ring = ShapeObject(
            id = ObjectId(RING),
            shape = ShapeKind.ELLIPSE,
            widthPt = 80f,
            heightPt = 80f,
            transform = Matrix3.translate(60f, 60f),
            style = ObjectStyle(Color.BLACK, widthPt = 3f),
            z = FractionalKey("c"),
        )
        return ElementEditing.frameOps(
            ObjectId(FRAME),
            PageRect(20f, 20f, 180f, 180f),
            pivot = PagePoint(140f, 60f),
            z = FractionalKey("a"),
            nextOpId = { "" },
            nextClock = next,
        ) + listOf(
            PageOp.AddObject("", next(), area),
            PageOp.SetFormat("", next(), area.id, ObjectFormat(cutter = true).encode()),
            PageOp.AddObject("", next(), ring),
        )
    }

    private fun render(store: StrokeStore, backdrop: ElementBackdrop?): IntArray {
        val paper = PageCanvas.fixed(PageSize(SIZE.toFloat(), SIZE.toFloat()))
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val painter = PagePainter().apply { this.backdrop = backdrop }
        val canvas = Canvas(bitmap)
        painter.paintBackground(canvas, paper, Background.Blank)
        painter.paintObjects(canvas, store, Matrix(), visibleBounds = null, paper = paper)
        painter.paintGuides(canvas, store)
        val pixels = IntArray(SIZE * SIZE)
        bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        bitmap.recycle()
        return pixels
    }

    /** `PagePainter.FRAME_ARGB` is 0xF59E0B: strong red, middling green, almost no blue. */
    private fun isAmber(argb: Int) =
        Color.red(argb) > AMBER_RED && Color.green(argb) in AMBER_GREEN && Color.blue(argb) < AMBER_BLUE

    private companion object {
        const val SIZE = 200
        const val FRAME = "frame"
        const val AREA = "area"
        const val RING = "ring"
        const val SLOP = 4f
        const val ERASER = 3f
        const val POINTS_PER_INCH = 72f
        const val MIN_GUIDE_PIXELS = 100
        const val AMBER_RED = 200
        val AMBER_GREEN = 110..190
        const val AMBER_BLUE = 80
    }
}
