package dev.inkstone.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ROADMAP M8a — the selection edits, as arithmetic.
 *
 * Everything M8a writes to the op log is decided here, so this file is where the decisions can
 * be checked without a device. What it deliberately does **not** prove is that anything calls
 * them: that is `SelectionController`, `InkSurfaceView` and the emulator walk in
 * `docs/M8-SELECTION.md` §5 — "it has tests" is not a caller.
 */
class SelectionTest {

    private var counter = 0L

    private fun nextOpId(): String = "op-${counter++}"

    private fun nextClock(): LamportClock = LamportClock(counter, "device-a")

    private fun obj(id: String, transform: Matrix3 = Matrix3.Identity, z: String = "V") =
        SelectedObject(ObjectId(id), transform, FractionalKey(z))

    private fun folded(vararg objects: InkObject): FoldedPage = foldPage(
        pageId = PageId("page-1"),
        ops = objects.mapIndexed { index, obj ->
            PageOp.AddObject("add-$index", LamportClock(index.toLong(), "device-a"), obj)
        },
    )

    private fun stroke(id: String, z: String = "V") =
        StrokeObject(ObjectId(id), StrokeGeometry(byteArrayOf(1, 2, 3)), z = FractionalKey(z))

    // -----------------------------------------------------------------------
    // The rule the whole milestone rests on
    // -----------------------------------------------------------------------

    /**
     * ⭐ ROADMAP M8a: *"`SetTransform` carries an ABSOLUTE matrix; a resize writes the resulting
     * matrix, not a delta."*
     *
     * The object is already scaled 2× when the gesture arrives, so a builder that wrote the
     * *gesture* would produce `translate(10, 0)` and a builder that wrote the *result* produces
     * the composition. Those differ, which is the point: with a delta, replaying the log twice
     * moves the object twice.
     */
    @Test
    fun `a transform writes the composed absolute matrix and never the gesture`() {
        val existing = Matrix3.scale(2f, 2f)
        val gesture = SelectionGesture.move(10f, 4f)

        val ops = SelectionEdit.transform(listOf(obj("a", existing)), gesture, ::nextOpId, ::nextClock)

        val written = assertNotNull(ops.single() as? PageOp.SetTransform)
        assertEquals(gesture * existing, written.absolute, "the op did not carry the composed matrix")
        assertEquals(
            PagePoint(2f * 3f + 10f, 2f * 5f + 4f),
            written.absolute.map(PagePoint(3f, 5f)),
            "the composition is in the wrong order — the gesture must apply after the object's own transform",
        )
    }

    /**
     * A resize is [SelectionGesture.resize] composed the same way, and the composition of two
     * scales about different origins is not a scale — which is exactly why writing a delta
     * would be wrong even for the simplest possible gesture.
     */
    @Test
    fun `a resize maps the old box onto the new one`() {
        val from = PageRect(100f, 100f, 200f, 200f)
        val to = PageRect(100f, 100f, 300f, 400f)

        val gesture = assertNotNull(SelectionGesture.resize(from, to))

        assertEquals(PagePoint(100f, 100f), gesture.map(PagePoint(100f, 100f)), "the fixed corner moved")
        assertEquals(PagePoint(300f, 400f), gesture.map(PagePoint(200f, 200f)), "the dragged corner is wrong")
    }

    /** A rotation about an explicit pivot leaves the pivot where it is. */
    @Test
    fun `a rotation turns about its pivot and does not skew`() {
        val gesture = SelectionGesture.rotate(50f, 50f, (PI / 2).toFloat())

        val pivot = gesture.map(PagePoint(50f, 50f))
        assertTrue(abs(pivot.x - 50f) < TOLERANCE && abs(pivot.y - 50f) < TOLERANCE, "the pivot moved to $pivot")
        assertFalse(gesture.isSkewed(), "a rotation must not skew")
        val turned = gesture.map(PagePoint(60f, 50f))
        assertTrue(abs(turned.x - 50f) < TOLERANCE, "expected a quarter turn, got $turned")
        assertTrue(abs(turned.y - 60f) < TOLERANCE, "expected a quarter turn, got $turned")
    }

    /** Near a multiple of 15° the drag is pulled onto it; between them it is left alone. */
    @Test
    fun `a rotation snaps to a round angle only when it is close to one`() {
        val degree = (PI / 180).toFloat()

        assertNear(90f * degree, SelectionGesture.snappedRotation(0f, 87f * degree), "87° should settle on 90°")
        assertNear(-45f * degree, SelectionGesture.snappedRotation(0f, -48f * degree), "-48° should settle on -45°")
        assertNear(8f * degree, SelectionGesture.snappedRotation(0f, 8f * degree), "8° is between steps and must stay")
    }

    /** What snaps is the angle the object ends at, so a crooked picture can be made level. */
    @Test
    fun `a rotation snaps the result and not the drag`() {
        val degree = (PI / 180).toFloat()
        val crooked = 7f * degree

        // Dragging back by 9° would leave it at -2°; it lands level instead, i.e. a turn of -7°.
        assertNear(-7f * degree, SelectionGesture.snappedRotation(crooked, -9f * degree), "should level the picture")
    }

    @Test
    fun `the shared angle is the selection own one or none when the objects disagree`() {
        val degree = (PI / 180).toFloat()
        val tilted = SelectionGesture.rotate(10f, 20f, 30f * degree) * Matrix3.scale(2f)

        assertNear(30f * degree, SelectionGesture.sharedRotation(listOf(tilted, tilted)), "one angle shared")
        assertNear(0f, SelectionGesture.sharedRotation(listOf(tilted, Matrix3.Identity)), "two angles")
        assertNear(0f, SelectionGesture.sharedRotation(emptyList()), "nothing selected")
    }

    private fun assertNear(expected: Float, actual: Float, message: String) =
        assertTrue(abs(expected - actual) < 1e-4f, "$message — expected $expected, got $actual")

    /**
     * ⚠️ A gesture that collapses the selection writes **nothing**.
     *
     * `Matrix3.inverse()` answers null for a singular matrix, and the inverse is what maps an
     * eraser's page-space sweep into a transformed object's own space — so a zero-height resize
     * would leave an object that is invisible *and* cannot be erased. Undo would still recover
     * it, which is why this is a refusal rather than an error.
     */
    @Test
    fun `a degenerate gesture is refused rather than written`() {
        val flat = assertNotNull(
            SelectionGesture.resize(PageRect(0f, 0f, 100f, 100f), PageRect(0f, 0f, 100f, 100f)),
        )
        assertFalse(flat.isDegenerate(), "an identity resize is not degenerate")

        val collapsed = Matrix3.scale(1f, 0f)
        assertTrue(collapsed.isDegenerate(), "a zero-height scale is degenerate")
        assertTrue(
            SelectionEdit.transform(listOf(obj("a")), collapsed, ::nextOpId, ::nextClock).isEmpty(),
            "a degenerate gesture must produce no ops",
        )
    }

    // -----------------------------------------------------------------------
    // The other registers
    // -----------------------------------------------------------------------

    /**
     * ⚠️ Recolouring a mixed selection must not flatten it to one width.
     *
     * `SetStyle` carries a *complete* style, so a builder taking one shared value would make
     * "make these red" also make a 3 pt pen and a 16 pt highlighter the same size — visible,
     * destructive of intent, and not what any user pressing a colour swatch asked for.
     */
    @Test
    fun `a recolour keeps each object's own width`() {
        val widths = mapOf(ObjectId("thin") to 3f, ObjectId("fat") to 16f)

        val ops = SelectionEdit.restyle(
            objects = listOf(obj("thin"), obj("fat")),
            styleOf = { ObjectStyle(colorArgb = RED, widthPt = widths.getValue(it.id)) },
            nextOpId = ::nextOpId,
            nextClock = ::nextClock,
        ).filterIsInstance<PageOp.SetStyle>()

        assertEquals(2, ops.size)
        assertTrue(ops.all { it.style.colorArgb == RED }, "the colour did not reach both")
        assertEquals(3f, ops.first { it.target.value == "thin" }.style.widthPt)
        assertEquals(16f, ops.first { it.target.value == "fat" }.style.widthPt)
    }

    /**
     * Bring-to-front measures against what is **staying**, not against the whole page.
     *
     * Measuring against everything would include the selection itself, so bringing something
     * already at the front to the front would lengthen its key on every press and never settle.
     */
    @Test
    fun `bring to front puts the selection above everything else and keeps its own order`() {
        val page = listOf(obj("low", z = "B"), obj("mid", z = "M"), obj("high", z = "X"))

        val ops = SelectionEdit.bringToFront(
            objects = listOf(page[0], page[1]),
            page = page,
            nextOpId = ::nextOpId,
            nextClock = ::nextClock,
        ).filterIsInstance<PageOp.SetZOrder>()

        val low = ops.first { it.target.value == "low" }.z
        val mid = ops.first { it.target.value == "mid" }.z
        assertTrue(low > FractionalKey("X"), "low did not clear the object that stayed")
        assertTrue(mid > low, "the selection lost its own stacking")
    }

    @Test
    fun `send to back puts the selection below everything else and keeps its own order`() {
        val page = listOf(obj("low", z = "B"), obj("mid", z = "M"), obj("high", z = "X"))

        val ops = SelectionEdit.sendToBack(
            objects = listOf(page[1], page[2]),
            page = page,
            nextOpId = ::nextOpId,
            nextClock = ::nextClock,
        ).filterIsInstance<PageOp.SetZOrder>()

        val mid = ops.first { it.target.value == "mid" }.z
        val high = ops.first { it.target.value == "high" }.z
        assertTrue(high < FractionalKey("B"), "high did not clear the object that stayed")
        assertTrue(mid < high, "the selection lost its own stacking")
    }

    /** A malformed key on the page must misorder, not throw: notes have to keep opening. */
    @Test
    fun `a malformed z key on the page does not break bring to front`() {
        val page = listOf(obj("broken", z = "not a key!"), obj("fine", z = "M"))

        val ops = SelectionEdit.bringToFront(listOf(page[1]), page, ::nextOpId, ::nextClock)

        assertEquals(1, ops.size)
        assertTrue((ops.single() as PageOp.SetZOrder).z > FractionalKey("M"))
    }

    @Test
    fun `keys above are ascending and clear the current top`() {
        val keys = SelectionEdit.keysAbove(FractionalKey("M"), 3)

        assertEquals(3, keys.size)
        assertTrue(keys.first() > FractionalKey("M"), "the first copy did not clear the page")
        assertEquals(keys.sorted(), keys, "the copies are not in ascending order")
    }

    /** A tombstone, never a removal — the payload stays in the log (CLAUDE.md rule 1). */
    @Test
    fun `delete writes a tombstone per object and the fold keeps the geometry`() {
        val page = folded(stroke("a"), stroke("b"))
        val ops = SelectionEdit.delete(page.objects.map { it.selected }, ::nextOpId, ::nextClock)

        assertEquals(2, ops.size)
        assertTrue(ops.all { it is PageOp.DeleteObject })
        val after = foldPage(
            PageId("page-1"),
            page.objects.mapIndexed { index, obj ->
                PageOp.AddObject("add-$index", LamportClock(index.toLong(), "device-a"), obj)
            } + ops,
        )
        assertTrue(after.objects.isEmpty(), "the objects are still visible")
        assertEquals(2, after.deleted.size, "the tombstones are missing")
    }

    // -----------------------------------------------------------------------
    // The lasso
    // -----------------------------------------------------------------------

    @Test
    fun `a rectangular lasso contains what is inside it and not what is outside`() {
        val lasso = Lasso.rectangle(PagePoint(10f, 10f), PagePoint(50f, 50f))

        assertTrue(lasso.contains(30f, 30f))
        assertFalse(lasso.contains(60f, 30f))
        assertFalse(lasso.contains(30f, 5f))
    }

    /**
     * A concave lasso — the shape a real hand draws — must not select what sits in its notch.
     *
     * This is what distinguishes a genuine point-in-polygon test from a bounding-box one, and
     * a bounding box would pass every other test in this file.
     */
    @Test
    fun `a concave lasso excludes the notch`() {
        // A "C" opening to the right: the middle-right region is outside the polygon but well
        // inside its bounding box.
        val lasso = Lasso.freehand(
            listOf(
                PagePoint(0f, 0f),
                PagePoint(100f, 0f),
                PagePoint(100f, 20f),
                PagePoint(30f, 20f),
                PagePoint(30f, 80f),
                PagePoint(100f, 80f),
                PagePoint(100f, 100f),
                PagePoint(0f, 100f),
            ),
        )

        assertTrue(lasso.contains(10f, 50f), "the spine of the C is inside")
        assertFalse(lasso.contains(70f, 50f), "the notch of the C is outside — this is a bounding-box answer")
    }

    /**
     * ⭐ Majority, not "any part".
     *
     * The underline case is the one that matters and the one that decides the rule: a lasso
     * round one word overlaps the rule running under the whole line, so "any part inside" would
     * drag the underline off with the word every time.
     */
    @Test
    fun `a lasso takes a stroke that is mostly inside and leaves one that is mostly outside`() {
        val lasso = Lasso.rectangle(PagePoint(0f, 0f), PagePoint(100f, 100f))

        val word = List(10) { PagePoint(10f + it * 5f, 50f) }
        val underline = List(10) { PagePoint(10f + it * 40f, 90f) }

        assertTrue(lasso.selects(word), "the word the user drew round was not selected")
        assertFalse(lasso.selects(underline), "a line running past the lasso was dragged along with the word")
    }

    @Test
    fun `a lasso of fewer than three points selects nothing`() {
        val tap = Lasso.freehand(listOf(PagePoint(10f, 10f), PagePoint(11f, 11f)))

        assertEquals(null, tap.bounds)
        assertFalse(tap.selects(listOf(PagePoint(10f, 10f))))
    }

    // -----------------------------------------------------------------------
    // Alignment guides
    // -----------------------------------------------------------------------

    /** ROADMAP M8a — *"dashed = centre, solid = edge"*, and the snap that makes them true. */
    @Test
    fun `a move within tolerance snaps and reports the guide it snapped to`() {
        val moving = PageRect(102f, 200f, 152f, 240f)
        val other = PageRect(100f, 300f, 150f, 340f)

        val alignment = AlignmentGuides.forMove(moving, listOf(other), PageSize.Letter)

        assertEquals(-2f, alignment.dx, "the left edges did not snap")
        val edgeGuide = alignment.guides.any {
            it.axis == GuideAxis.VERTICAL && it.kind == GuideKind.EDGE && it.positionPt == OTHER_LEFT
        }
        assertTrue(edgeGuide, "no edge guide was reported for the snap that happened: ${alignment.guides}")
    }

    /** A snap with no line, or a line with no snap, is chrome that lies. Neither is reachable. */
    @Test
    fun `nothing within tolerance snaps nothing and draws nothing`() {
        val moving = PageRect(400f, 200f, 450f, 240f)
        val other = PageRect(100f, 300f, 150f, 340f)

        val alignment = AlignmentGuides.forMove(moving, listOf(other), PageSize.Letter)

        assertEquals(0f, alignment.dx)
        assertEquals(0f, alignment.dy)
        assertTrue(alignment.isEmpty)
    }

    @Test
    fun `the centre of the page is a guide and is reported as a centre`() {
        val centre = PageSize.Letter.widthPt / 2f
        val moving = PageRect(centre - 25f + 3f, 100f, centre + 25f + 3f, 140f)

        val alignment = AlignmentGuides.forMove(moving, emptyList(), PageSize.Letter)

        assertEquals(-3f, alignment.dx, "the selection did not centre on the page")
        assertTrue(
            alignment.guides.any { it.kind == GuideKind.CENTRE && it.positionPt == centre },
            "the page centre was not reported as a centre guide",
        )
    }

    private companion object {
        const val TOLERANCE = 1e-3f
        const val RED = 0xFFCC0000.toInt()
        const val OTHER_LEFT = 100f
    }
}
