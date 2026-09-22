package dev.inkstone.android.ink

import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import androidx.ink.geometry.MutableVec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.TextBoxObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ⭐ ROADMAP M8c — what a text box and an image actually **decide** before anything is drawn.
 *
 * ## Why this is here rather than in a screenshot test
 *
 * Everything asserted below is a decision the painter then executes in three or four lines: which
 * typeface, how wide the wrap, which pixels of the source, which outline to clip to. CLAUDE.md's
 * *"hoist the decision into a pure function over plain data so a test can at least assert the
 * decision was made"* is exactly this shape — the alternative is comparing framebuffers, which
 * would catch the same bugs a day later and could not say which of them it had caught.
 *
 * ⚠️ **It is instrumented and not a JVM test** for the reason the rest of this suite is: `Typeface`,
 * `StaticLayout` and `Path` are native, and a `Robolectric`-free JVM test cannot build one. It uses
 * no `androidx.ink` geometry at all, so no fixture of ink's can affect it.
 *
 * ⚠️ **What it does not establish**: that anything reaches the glass. `PagePainter` calls
 * `layout.draw` and `drawBitmap`, and no test in this module drives a `Canvas`. The check that
 * closes that is the emulator walk (CLAUDE.md definition of done #6), and it is owed.
 */
@RunWith(AndroidJUnit4::class)
class DryBoxRenderTest {

    private lateinit var factory: InkBrushFactory

    @Before
    fun setUp() {
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        factory = InkBrushFactory(metrics.density, metrics.densityDpi / POINTS_PER_INCH)
    }

    private fun textBox(text: String, widthPt: Float = BOX_WIDTH_PT, heightPt: Float = BOX_HEIGHT_PT) = DryTextBox(
        TextBoxObject(
            id = ObjectId("caption"),
            text = text,
            widthPt = widthPt,
            heightPt = heightPt,
            style = ObjectStyle(BLACK, widthPt = 0f),
        ),
    )

    private fun imageObject() = DryImage(
        ImageObject(
            id = ObjectId("photo"),
            asset = "assets/img-photo.webp",
            widthPt = BOX_WIDTH_PT,
            heightPt = BOX_HEIGHT_PT,
            style = ObjectStyle(BLACK, widthPt = 0f),
        ),
    )

    // -----------------------------------------------------------------------
    // The layout cache — CLAUDE.md rule 7
    // -----------------------------------------------------------------------

    /**
     * ⚠️ **The layout is not rebuilt by anything that does not change how the text reads.**
     *
     * A `StaticLayout` measures every glyph and breaks every line. A text box on a page being
     * written on is redrawn on every dry-layer invalidation, so rebuilding one per draw — or per
     * `SetTransform` arriving from a peer, which is what a drag produces — would put line breaking
     * on the pen-to-pixel path.
     *
     * The move is the interesting half: a text box is scaled by its matrix, so a resize genuinely
     * must **not** re-wrap. `assertSame` rather than an equality check, because two layouts of the
     * same string are equal in every way except the one this is about.
     */
    @Test
    fun movingATextBoxDoesNotRebuildItsLayout() {
        val box = textBox(PARAGRAPH)
        val before = box.layout

        box.setTransform(Matrix3.translate(MOVE_PT, MOVE_PT))
        box.setContent(PARAGRAPH)
        box.setFormat(null)

        assertSame("the layout was rebuilt by something that changed nothing", before, box.layout)
    }

    /** Each of the three registers that *does* change how it reads rebuilds it. */
    @Test
    fun retypingAndReformattingAndRestylingEachRebuildTheLayout() {
        val box = textBox(PARAGRAPH)

        val afterCreate = box.layout
        box.setContent("something else entirely")
        val afterRetype = box.layout
        box.setFormat(SERIF_RECORD)
        val afterFormat = box.layout
        box.restyle(ObjectStyle(RED, widthPt = 0f), factory)
        val afterRestyle = box.layout

        assertTrue("SetTextContent did not rebuild the layout", afterCreate !== afterRetype)
        assertTrue("SetFormat did not rebuild the layout", afterRetype !== afterFormat)
        assertTrue("SetStyle did not rebuild the layout", afterFormat !== afterRestyle)
    }

    // -----------------------------------------------------------------------
    // The format record actually reaches the type
    // -----------------------------------------------------------------------

    /**
     * Font, size and alignment come off the record and land on the paint and the layout.
     *
     * The size is asserted **through [DryTextBox.LAYOUT_SCALE]**, which is the point of checking it
     * at all: the layout is deliberately oversized and scaled down at draw time, so a reader who
     * removed the scale here and not in the painter would get text eight times too small with every
     * other assertion in this file still green.
     */
    @Test
    fun theFormatRecordDecidesTheTypefaceTheSizeAndTheAlignment() {
        val box = textBox(PARAGRAPH)

        box.setFormat(SERIF_RECORD)

        assertEquals(Typeface.SERIF, box.layout.paint.typeface)
        assertEquals(SERIF_SIZE_PT * DryTextBox.LAYOUT_SCALE, box.layout.paint.textSize, TOLERANCE)
        assertEquals(Layout.Alignment.ALIGN_CENTER, box.layout.alignment)
    }

    /** A monospaced record picks the monospaced face and nothing else changes. */
    @Test
    fun aMonoRecordPicksTheMonospacedFace() {
        val box = textBox(PARAGRAPH)

        box.setFormat(MONO_RECORD)

        assertEquals(Typeface.MONOSPACE, box.layout.paint.typeface)
    }

    /**
     * A numbered list numbers the paragraphs, and a bulleted one marks them.
     *
     * The markers are part of the laid-out text rather than drawn beside it, because a marker has
     * to take part in line breaking and in alignment — a bullet drawn in the margin of a centred
     * list sits in the wrong place, and one that does not consume width lets the first line run a
     * marker's width too far.
     */
    @Test
    fun aNumberedListNumbersEachParagraphAndABulletedOneMarksThem() {
        val box = textBox(TWO_PARAGRAPHS)

        box.setFormat(NUMBERED_RECORD)
        val numbered = box.layout.text.toString()
        box.setFormat(BULLET_RECORD)
        val bulleted = box.layout.text.toString()

        assertTrue("the first item was not numbered: $numbered", numbered.startsWith("1. "))
        assertTrue("the second item was not numbered: $numbered", numbered.contains("\n2. "))
        assertTrue("the items were not bulleted: $bulleted", bulleted.startsWith(DryTextBox.BULLET))
    }

    /**
     * A blank first line survives the list decoration.
     *
     * The separator between items has to be decided by the paragraph's **index**, not by whether
     * anything has been appended yet: a text box that begins with an empty line — which is how
     * somebody makes room above a heading — leaves the builder empty after the first pass, and a
     * "have I written anything" test then swallows that newline and shifts the whole box up a line.
     */
    @Test
    fun aBlankFirstLineIsKeptWhenAListIsApplied() {
        val box = textBox("\nsecond")

        box.setFormat(BULLET_RECORD)

        assertEquals(
            "${DryTextBox.BULLET}\n${DryTextBox.BULLET}second",
            box.layout.text.toString(),
        )
    }

    /** Plain text is handed to the layout unchanged — no marker, no span, nothing to strip later. */
    @Test
    fun textWithNoListStyleIsLaidOutVerbatim() {
        val box = textBox(TWO_PARAGRAPHS)

        assertEquals(TWO_PARAGRAPHS, box.layout.text.toString())
    }

    // -----------------------------------------------------------------------
    // Overflow
    // -----------------------------------------------------------------------

    /**
     * ⚠️ Words that no longer fit the declared height widen the object's **painted** box.
     *
     * Overflow is drawn rather than clipped — hiding what somebody typed because the box was
     * created a line too short is data the user can no longer see, and `heightPt` is on the
     * first-write-wins payload so nothing can grow it. The consequence that has to follow is this
     * one: culling, the erase layer's `saveLayer` and the lasso all read `bounds`, so a box that
     * paints past its own extent must say so or the overflowing lines are clipped by the erase
     * layer and culled a scroll early.
     */
    @Test
    fun aTextBoxThatOverflowsItsHeightWidensItsPaintedBounds() {
        val box = textBox(PARAGRAPH, heightPt = TINY_HEIGHT_PT)

        val bounds = requireNotNull(box.bounds) { "a text box has no bounds" }
        assertTrue(
            "the painted box (${bounds.yMax}) did not grow past the declared height",
            bounds.yMax > TINY_HEIGHT_PT,
        )
        assertEquals("the painted box got wider as well", BOX_WIDTH_PT, bounds.xMax, TOLERANCE)
    }

    /** The silhouette stays the declared rectangle even when the text overflows it. */
    @Test
    fun theSilhouetteOfAnOverflowingTextBoxIsStillItsDeclaredRectangle() {
        val box = textBox(PARAGRAPH, heightPt = TINY_HEIGHT_PT)

        var lowest = 0f
        box.forEachPagePoint(MutableVec()) { _, y -> lowest = maxOf(lowest, y) }

        assertEquals("the selection frame followed the overflow", TINY_HEIGHT_PT, lowest, TOLERANCE)
    }

    // -----------------------------------------------------------------------
    // The crop
    // -----------------------------------------------------------------------

    /** No crop is the whole picture, whatever size the bitmap turns out to be. */
    @Test
    fun anUncroppedImageDrawsTheWholeSource() {
        val image = imageObject()
        val rect = Rect()

        image.sourceRect(SOURCE_W, SOURCE_H, rect)

        assertEquals(Rect(0, 0, SOURCE_W, SOURCE_H), rect)
        assertNull("an uncropped image has a clip path", image.clipPath)
    }

    /**
     * ⭐ **A rectangular crop and a polygon tracing the same rectangle reframe identically.**
     *
     * This is the property that makes the two crop kinds one feature rather than two. Both
     * *reframe* — the selected region fills the object's rectangle — and the polygon additionally
     * clips. If they ever disagree, cropping a photo with the freehand tool and then switching to
     * the rectangular one moves the picture inside its own frame for no reason a user could see.
     */
    @Test
    fun aRectangularCropAndAPolygonTracingItSelectTheSamePixels() {
        val viaRect = imageObject().also { it.setFormat(RECT_CROP) }
        val viaPolygon = imageObject().also { it.setFormat(POLYGON_CROP) }
        val fromRect = Rect()
        val fromPolygon = Rect()

        viaRect.sourceRect(SOURCE_W, SOURCE_H, fromRect)
        viaPolygon.sourceRect(SOURCE_W, SOURCE_H, fromPolygon)

        assertEquals(Rect(CROP_LEFT_PX, 0, CROP_RIGHT_PX, SOURCE_H), fromRect)
        assertEquals("the two crop kinds framed different pixels", fromRect, fromPolygon)
        assertNull("a rectangular crop produced a clip path", viaRect.clipPath)
        assertNotNull("a freehand crop produced no clip path", viaPolygon.clipPath)
    }

    /**
     * A crop with no extent shows the **whole** picture rather than none of it.
     *
     * A degenerate crop is expressible from any build — `ObjectFormatCodec` only refuses an
     * inverted rectangle, and a polygon of three collinear points parses perfectly. Drawing nothing
     * would be a picture that vanished with no way back through the UI; drawing all of it is the
     * failure somebody can undo.
     */
    @Test
    fun aCropWithNoExtentFallsBackToTheWholePicture() {
        val image = imageObject()
        val rect = Rect()

        image.setFormat(COLLINEAR_CROP)
        image.sourceRect(SOURCE_W, SOURCE_H, rect)

        assertEquals(Rect(0, 0, SOURCE_W, SOURCE_H), rect)
        assertNull("a degenerate polygon produced a clip path", image.clipPath)
    }

    /**
     * Fractions outside `[0, 1]` are clamped and the result still has pixels in it.
     *
     * `drawBitmap` with an empty source rectangle draws **nothing at all** — a picture that
     * disappeared because a slider went to 1.0001, with no error anywhere.
     */
    @Test
    fun anOutOfRangeCropIsClampedToSomethingWithPixelsInIt() {
        val image = imageObject()
        val rect = Rect()

        image.setFormat(OUT_OF_RANGE_CROP)
        image.sourceRect(SOURCE_W, SOURCE_H, rect)

        assertTrue("the source rectangle is empty: $rect", rect.width() > 0 && rect.height() > 0)
        assertTrue("the source rectangle left the bitmap: $rect", rect.left >= 0 && rect.right <= SOURCE_W)
    }

    private companion object {
        const val POINTS_PER_INCH = 72f
        const val BOX_WIDTH_PT = 160f
        const val BOX_HEIGHT_PT = 90f
        const val TINY_HEIGHT_PT = 6f
        const val MOVE_PT = 40f
        const val TOLERANCE = 0.01f
        const val BLACK = 0xFF1A1A1A.toInt()
        const val RED = 0xFFEF4444.toInt()

        const val PARAGRAPH =
            "The quick brown fox jumps over the lazy dog and keeps going for several more words " +
                "so that this wraps onto more than one line at any reasonable size."
        const val TWO_PARAGRAPHS = "first item\nsecond item"

        const val SERIF_RECORD = "if1;f=se;sz=18;a=c"
        const val SERIF_SIZE_PT = 18f
        const val MONO_RECORD = "if1;f=mo"
        const val NUMBERED_RECORD = "if1;ls=n;l=1"
        const val BULLET_RECORD = "if1;ls=b;l=1"

        const val SOURCE_W = 400
        const val SOURCE_H = 200
        const val RECT_CROP = "if1;c=0.25:0:0.75:1"
        const val POLYGON_CROP = "if1;cp=0.25,0:0.75,0:0.75,1:0.25,1"
        const val COLLINEAR_CROP = "if1;cp=0.5,0:0.5,0.5:0.5,1"
        const val OUT_OF_RANGE_CROP = "if1;c=-0.5:0.2:0.75:1.5"
        const val CROP_LEFT_PX = 100
        const val CROP_RIGHT_PX = 300
    }
}
