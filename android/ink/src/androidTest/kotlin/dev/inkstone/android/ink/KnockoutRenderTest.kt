package dev.inkstone.android.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Region
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapeObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ⭐ SPEC §4.9.6 — knockout, read back off the pixels and out of the published geometry.
 *
 * `SilhouetteMaskTest` proves the rule on grids. This proves the two things it cannot: that
 * [PagePainter] draws the object into the picture it measures and cuts in the object's own space,
 * and that [InkStrokeOutliner] subtracts the same silhouette from what is published — a reader that
 * is not the writer, in the second case, since the outline is tested by a `Region` rather than by
 * re-running the painter.
 *
 * The page: two horizontal rules (a hollow 180 × 20 rectangle, its edges at y = 90 and y = 110) and
 * a hollow 80 pt ring centred on (100, 100) above them. With knockout on, the rules vanish inside the
 * ring and nowhere else.
 */
@RunWith(AndroidJUnit4::class)
class KnockoutRenderTest {

    private lateinit var brushes: InkBrushFactory

    @Before
    fun setUp() {
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        brushes = InkBrushFactory(metrics.density, metrics.densityDpi / POINTS_PER_INCH)
    }

    @Test
    fun aKnockoutRingHidesTheRuleInsideItAndNowhereElse() {
        val pixels = render(rules(z = "a"), ring(z = "b", knockout = true))

        assertTrue("the rule inside the ring is hidden", pixels.isPaper(100, 90))
        assertTrue("the rule outside the ring is untouched", pixels.isInk(20, 90))
        assertTrue("the ring itself is still drawn", pixels.isInk(100, 60))
    }

    @Test
    fun withoutTheFlagTheRuleShowsThroughTheRing() {
        // The control: the same page with knockout off must show the rule, or the test above could
        // pass because the ring was simply drawn filled.
        val pixels = render(rules(z = "a"), ring(z = "b", knockout = false))
        assertTrue(pixels.isInk(100, 90))
    }

    @Test
    fun aKnockoutHidesOnlyWhatIsBeneathIt() {
        // The rules drawn *after* the ring sit above it and must not be hidden by it.
        val pixels = render(ring(z = "a", knockout = true), rules(z = "b"))
        assertTrue(pixels.isInk(100, 90))
    }

    @Test
    fun oneKnockoutNeverHidesAnother() {
        // ⭐ The Element case: a hand-drawn icon is several pieces, each flagged. A smaller ring under
        // the big one is part of the same icon and must stay visible inside it.
        val inner = ring(id = INNER, z = "a", knockout = true, size = 30f)
        val pixels = render(rules(z = "0"), inner, ring(z = "b", knockout = true))

        assertTrue("the inner ring survives inside the outer one", pixels.isInk(100, 85))
        assertTrue("the rule is still hidden", pixels.isPaper(80, 90))
    }

    @Test
    fun theBackgroundIsNeverCut() {
        // The cut is made in a layer above the paper, so what shows through is paper and never the
        // transparent window behind the page — the failure a `DST_OUT` on the plain canvas produces.
        val pixels = render(rules(z = "a"), ring(z = "b", knockout = true))
        assertTrue(Color.alpha(pixels.at(100, 90)) == OPAQUE)
    }

    @Test
    fun thePublishedRuleHasTheSameHoleTheScreenHas() = runBlocking {
        val page = folded(rules(z = "a"), ring(z = "b", knockout = true))
        val outlines = InkStrokeOutliner(brushes).outline(page)

        val rule = outlines.filter { it.objectId == ObjectId(RULES) }
        assertTrue("the rule was published", rule.isNotEmpty())
        val drawn = regionOf(rule.flatMap { it.contours })
        assertFalse("the published rule crosses the ring", drawn.contains(100, 90))
        assertTrue("the published rule still runs outside it", drawn.contains(20, 90))
    }

    // --- fixtures -----------------------------------------------------------

    private fun rules(z: String) = listOf<PageOp>(
        PageOp.AddObject(
            "",
            CLOCK,
            ShapeObject(
                id = ObjectId(RULES),
                shape = ShapeKind.RECTANGLE,
                widthPt = 180f,
                heightPt = 20f,
                transform = Matrix3.translate(10f, 90f),
                style = ObjectStyle(Color.BLACK, widthPt = 4f),
                z = FractionalKey(z),
            ),
        ),
    )

    @Test
    fun aCutterHidesTheRuleAndIsNotItselfDrawnInANote() {
        // SPEC §4.9.6 — an invisible knockout area: the hole without the ring.
        val pixels = render(rules(z = "a"), ring(z = "b", knockout = false, cutter = true))
        assertTrue("the rule inside the area is hidden", pixels.isPaper(100, 90))
        assertTrue("the area's own outline is not drawn", pixels.isPaper(100, 60))
        assertTrue("the rule outside the area is untouched", pixels.isInk(20, 90))
    }

    @Test
    fun theElementEditorDrawsACutterSoItCanBeSeen() {
        val pixels =
            render(rules(z = "a"), ring(z = "b", knockout = false, cutter = true), backdrop = ElementBackdrop.LIGHT)
        // Sky blue, not just "not paper" — the light backdrop's own grid lines are not paper either,
        // and a test that counted them would pass with no outline drawn at all.
        val outline = (0 until SIZE).flatMap { x -> (0 until SIZE).map { y -> pixels.at(x, y) } }.count { isSky(it) }
        assertTrue("the dashed outline is on the editor's canvas ($outline sky pixels)", outline > MIN_OUTLINE_PIXELS)
        val note = render(rules(z = "a"), ring(z = "b", knockout = false, cutter = true))
        assertEquals("and nowhere in a note", 0, note.count { isSky(it) })
    }

    private fun isSky(argb: Int) = Color.blue(argb) > SKY_BLUE && Color.red(argb) < SKY_RED

    @Test
    fun aCutterPublishesNoInkAndStillCutsTheRule() = runBlocking {
        val outlines = InkStrokeOutliner(
            brushes,
        ).outline(folded(rules(z = "a"), ring(z = "b", knockout = false, cutter = true)))
        assertTrue("the area itself publishes nothing", outlines.none { it.objectId == ObjectId(RING) })
        val drawn = regionOf(outlines.filter { it.objectId == ObjectId(RULES) }.flatMap { it.contours })
        assertFalse(drawn.contains(100, 90))
        assertTrue(drawn.contains(20, 90))
    }

    private fun ring(
        id: String = RING,
        z: String,
        knockout: Boolean,
        size: Float = 80f,
        cutter: Boolean = false,
    ): List<PageOp> {
        val ops = mutableListOf<PageOp>(
            PageOp.AddObject(
                "",
                CLOCK,
                ShapeObject(
                    id = ObjectId(id),
                    shape = ShapeKind.ELLIPSE,
                    widthPt = size,
                    heightPt = size,
                    transform = Matrix3.translate(100f - size / 2f, 100f - size / 2f),
                    style = ObjectStyle(Color.BLACK, widthPt = 3f),
                    z = FractionalKey(z),
                ),
            ),
        )
        if (knockout || cutter) {
            ops +=
                PageOp.SetFormat("", CLOCK, ObjectId(id), ObjectFormat(knockout = knockout, cutter = cutter).encode())
        }
        return ops
    }

    private fun render(vararg parts: List<PageOp>, backdrop: ElementBackdrop? = null): IntArray {
        val store = StrokeStore()
        store.applyOps(parts.flatMap { it }, brushes, record = false)
        val paper = PageCanvas.fixed(PageSize(SIZE.toFloat(), SIZE.toFloat()))
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val painter = PagePainter().apply { this.backdrop = backdrop }
        val canvas = Canvas(bitmap)
        painter.paintBackground(canvas, paper, Background.Blank)
        painter.paintObjects(canvas, store, Matrix(), visibleBounds = null, paper = paper)
        // The guides pass, exactly as `DryStrokesView.paintPage` runs it — that is where a cutter's
        // outline is drawn now, on top of everything (SPEC §4.9.7).
        painter.paintGuides(canvas, store)
        val pixels = IntArray(SIZE * SIZE)
        bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        bitmap.recycle()
        return pixels
    }

    private fun folded(vararg parts: List<PageOp>): FoldedPage = dev.inkstone.core.model.foldPage(
        PageId("knockout"),
        parts.flatMap { it },
        defaultCanvas = PageCanvas.fixed(PageSize(SIZE.toFloat(), SIZE.toFloat())),
    )

    private fun regionOf(contours: List<List<dev.inkstone.core.model.PagePoint>>): Region {
        val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
        for (contour in contours) {
            contour.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            path.close()
        }
        return Region().apply { setPath(path, Region(0, 0, SIZE, SIZE)) }
    }

    private fun IntArray.at(x: Int, y: Int) = this[y * SIZE + x]

    private fun IntArray.isPaper(x: Int, y: Int): Boolean = brightness(at(x, y)) > PAPER_LEVEL

    private fun IntArray.isInk(x: Int, y: Int): Boolean = brightness(at(x, y)) < INK_LEVEL

    private fun brightness(argb: Int) = (Color.red(argb) + Color.green(argb) + Color.blue(argb)) / CHANNELS

    private companion object {
        const val SIZE = 200
        const val RULES = "rules"
        const val RING = "ring"
        const val INNER = "inner"
        const val POINTS_PER_INCH = 72f
        const val OPAQUE = 255
        const val CHANNELS = 3
        const val PAPER_LEVEL = 230
        const val INK_LEVEL = 100
        const val SKY_BLUE = 200
        const val SKY_RED = 120
        const val MIN_OUTLINE_PIXELS = 50
        val CLOCK = LamportClock(1, "test")
    }
}
