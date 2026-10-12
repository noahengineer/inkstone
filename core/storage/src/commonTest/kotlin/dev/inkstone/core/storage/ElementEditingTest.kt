package dev.inkstone.core.storage

import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.ShapeEdit
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapePlacement
import dev.inkstone.core.model.foldPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⭐ SPEC §4.9.7 — the Element editor's bounds are an object on its page, and what a save keeps.
 *
 * ⚠️ Pure storage over a fold. That the editor's tools reach these ops, that the bounds are drawn and
 * grabbed as chrome, and that undo takes them back in order is `docs/M8-ELEMENT-EDITOR.md` §9.
 */
class ElementEditingTest {

    private var counter = 0L

    private fun opId() = "op-${counter++}"

    private fun clock() = LamportClock(++counter, "device")

    @Test
    fun `the bounds are found with their box and a pivot at their corner`() {
        val page = foldPage(PageId("p"), frame(PageRect(36f, 54f, 126f, 108f), pivot = null))

        val frame = assertNotNull(ElementEditing.frameOf(page))
        assertEquals(PageRect(36f, 54f, 126f, 108f), frame.bounds)
        assertEquals(PagePoint(36f, 54f), frame.pivot)
        assertFalse(frame.hasPivot)
    }

    @Test
    fun `a save keeps every piece and never the bounds`() {
        val ops = frame(PageRect(0f, 0f, 90f, 90f), pivot = null) + piece(PIECE, 10f)
        val page = foldPage(PageId("p"), ops)

        assertEquals(listOf(ObjectId(PIECE)), ElementEditing.pieces(page))
    }

    @Test
    fun `a canvas whose bounds were deleted has none`() {
        val ops = frame(PageRect(0f, 0f, 90f, 90f), pivot = null)
        val page = foldPage(PageId("p"), ops + PageOp.DeleteObject(opId(), clock(), ObjectId(FRAME)))
        assertNull(ElementEditing.frameOf(page))
    }

    @Test
    fun `the pivot rides along when the bounds are moved`() {
        val ops = frame(PageRect(0f, 0f, 90f, 90f), pivot = PagePoint(18f, 36f))
        val moved = ops + PageOp.SetTransform(opId(), clock(), ObjectId(FRAME), Matrix3.translate(54f, 72f))
        val frame = assertNotNull(ElementEditing.frameOf(foldPage(PageId("p"), moved)))

        assertEquals(PagePoint(72f, 108f), frame.pivot)
        assertTrue(frame.hasPivot)
    }

    @Test
    fun `placing the pivot outside the bounds puts it on their edge`() {
        val ops = frame(PageRect(0f, 0f, 90f, 90f), pivot = null)
        val page = foldPage(PageId("p"), ops)
        val frame = assertNotNull(ElementEditing.frameOf(page))

        val op = ElementEditing.pivotOp(page, frame, PagePoint(200f, 45f), ::opId, ::clock)
        val after = assertNotNull(ElementEditing.frameOf(foldPage(PageId("p"), ops + op)))
        assertEquals(PagePoint(90f, 45f), after.pivot)
    }

    @Test
    fun `resizing the bounds from the far corner leaves the pivot on its grid point`() {
        // The device walk: a pivot on a grid point slid off it the first time the bounds were resized,
        // while it was stored as a fraction of them.
        val ops = frame(PageRect(0f, 0f, 90f, 90f), pivot = PagePoint(18f, 36f))
        val grown = ops + PageOp.SetTransform(opId(), clock(), ObjectId(FRAME), Matrix3.scale(2f, 1.4f))
        val frame = assertNotNull(ElementEditing.frameOf(foldPage(PageId("p"), grown)))

        assertEquals(PageRect(0f, 0f, 180f, 126f), frame.bounds)
        assertEquals(PagePoint(18f, 36f), frame.pivot)
    }

    @Test
    fun `fit makes the bounds exactly the box and keeps a placed pivot where it is`() {
        val ops = frame(PageRect(0f, 0f, 100f, 100f), pivot = PagePoint(54f, 36f))
        val page = foldPage(PageId("p"), ops)
        val frame = assertNotNull(ElementEditing.frameOf(page))

        val fit = ElementEditing.fitOps(page, frame, PageRect(36f, 18f, 236f, 68f), ::opId, ::clock)
        val after = assertNotNull(ElementEditing.frameOf(foldPage(PageId("p"), ops + fit)))
        assertEquals(PageRect(36f, 18f, 236f, 68f), after.bounds)
        assertEquals(PagePoint(54f, 36f), after.pivot)
    }

    @Test
    fun `fit pulls a pivot it would leave outside into the new bounds`() {
        val ops = frame(PageRect(0f, 0f, 100f, 100f), pivot = PagePoint(10f, 90f))
        val page = foldPage(PageId("p"), ops)
        val frame = assertNotNull(ElementEditing.frameOf(page))

        val fit = ElementEditing.fitOps(page, frame, PageRect(36f, 18f, 236f, 68f), ::opId, ::clock)
        val after = assertNotNull(ElementEditing.frameOf(foldPage(PageId("p"), ops + fit)))
        assertEquals(PagePoint(36f, 68f), after.pivot)
    }

    @Test
    fun `fit leaves an unplaced pivot at the corner`() {
        val ops = frame(PageRect(0f, 0f, 100f, 100f), pivot = null)
        val page = foldPage(PageId("p"), ops)
        val frame = assertNotNull(ElementEditing.frameOf(page))

        val fit = ElementEditing.fitOps(page, frame, PageRect(36f, 18f, 236f, 68f), ::opId, ::clock)
        assertEquals(1, fit.size)
        val after = assertNotNull(ElementEditing.frameOf(foldPage(PageId("p"), ops + fit)))
        assertEquals(PagePoint(36f, 18f), after.pivot)
        assertFalse(after.hasPivot)
    }

    @Test
    fun `a duplicated frame resolves to the one on top`() {
        val ops = frame(PageRect(0f, 0f, 90f, 90f), pivot = null) +
            frame(PageRect(180f, 0f, 270f, 90f), pivot = null, id = SECOND, z = FractionalKey("t"))
        val frame = assertNotNull(ElementEditing.frameOf(foldPage(PageId("p"), ops)))
        assertEquals(ObjectId(SECOND), frame.id)
    }

    @Test
    fun `an area goes under everything and is a cutter`() {
        val ops = frame(PageRect(0f, 0f, 90f, 90f), pivot = null) + piece(PIECE, 10f)
        val page = foldPage(PageId("p"), ops)
        val area = ElementEditing.areaOps(
            page,
            ObjectId(AREA),
            ShapeKind.RECTANGLE,
            PageRect(18f, 18f, 54f, 36f),
            ::opId,
            ::clock,
        )
        val after = foldPage(PageId("p"), ops + area)

        val added = after.objects.single { it.id == ObjectId(AREA) }
        assertTrue(after.objects.filter { it.id != ObjectId(AREA) }.all { added.z < it.z })
        assertTrue(ObjectFormat.decode(after.formatOf(ObjectId(AREA))).cutter)
        assertTrue(ObjectId(AREA) in ElementEditing.pieces(after))
    }

    @Test
    fun `the pivot tool brings back deleted bounds round the content`() {
        val ops = frame(PageRect(0f, 0f, 90f, 90f), pivot = null) + piece(PIECE, 10f) +
            PageOp.DeleteObject(opId(), clock(), ObjectId(FRAME))
        val page = foldPage(PageId("p"), ops)
        val content = PageRect(10f, 10f, 30f, 30f)

        val edit = ElementEditing.pivotEditOps(page, PagePoint(20f, 12f), content, ObjectId(SECOND), ::opId, ::clock)
        val after = assertNotNull(ElementEditing.frameOf(foldPage(PageId("p"), ops + edit)))
        assertEquals(content, after.bounds)
        assertEquals(PagePoint(20f, 12f), after.pivot)
    }

    @Test
    fun `with nothing to bound the pivot tool does nothing`() {
        val page = foldPage(PageId("p"), emptyList())
        assertTrue(
            ElementEditing.pivotEditOps(page, PagePoint(1f, 1f), null, ObjectId(SECOND), ::opId, ::clock).isEmpty(),
        )
    }

    @Test
    fun `fit brings back deleted bounds`() {
        val ops = piece(PIECE, 10f)
        val page = foldPage(PageId("p"), ops)
        val edit = ElementEditing.fitEditOps(page, PageRect(0f, 0f, 36f, 36f), ObjectId(SECOND), ::opId, ::clock)
        val after = assertNotNull(ElementEditing.frameOf(foldPage(PageId("p"), ops + edit)))
        assertEquals(PageRect(0f, 0f, 36f, 36f), after.bounds)
    }

    private fun frame(
        box: PageRect,
        pivot: PagePoint?,
        id: String = FRAME,
        z: FractionalKey = FractionalKey.Initial,
    ): List<PageOp> = ElementEditing.frameOps(ObjectId(id), box, pivot, z, ::opId, ::clock)

    private fun piece(id: String, x: Float): List<PageOp> = ShapeEdit.create(
        id = ObjectId(id),
        placement = ShapePlacement(ShapeKind.ELLIPSE, 20f, 20f, Matrix3.translate(x, x)),
        z = FractionalKey("p"),
        nextOpId = ::opId,
        nextClock = ::clock,
    )

    private companion object {
        const val FRAME = "018f0000-0000-7000-8000-0000000000f1"
        const val SECOND = "018f0000-0000-7000-8000-0000000000f2"
        const val PIECE = "018f0000-0000-7000-8000-0000000000a1"
        const val AREA = "018f0000-0000-7000-8000-0000000000c1"
    }
}
