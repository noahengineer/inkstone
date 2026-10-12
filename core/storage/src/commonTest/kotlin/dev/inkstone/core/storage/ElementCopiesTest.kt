package dev.inkstone.core.storage

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.IdFactory
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
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⭐ SPEC §4.9.7 — tagging a stamped copy, finding it again after it moved, and replacing it with a
 * revised Element in place.
 *
 * ⚠️ Pure storage. That the editor's Done reaches [ElementLibrary.replaceCopies] through the
 * single-writer gate, for an open note and a closed one, is `docs/M8-ELEMENT-EDITOR.md` §6.
 */
class ElementCopiesTest {

    private val fs = FakeFileSystem()
    private val library = ElementLibrary(fs, IdFactory(TestClock(), Random(7)))
    private var counter = 0L

    private fun opId() = "op-${counter++}"

    private fun clock() = LamportClock(++counter, "device")

    private fun newId() = ObjectId("018f0000-0000-7000-8000-${(counter++).toString().padStart(12, '0')}")

    @Test
    fun `a stamp with an instance id tags every piece and is found where it was dropped`() = runTest {
        val element = save(box(A, 0f), box(B, 60f))
        val page = fold(stamp(element, Matrix3.translate(100f, 200f), INSTANCE).ops)

        val copy = ElementCopies.on(page, element.id).single()
        assertEquals(INSTANCE, copy.instanceId)
        assertEquals(2, copy.pieces.size)
        assertEquals(100f, copy.placement.m02)
        assertEquals(200f, copy.placement.m12)
    }

    @Test
    fun `a stamp without an instance id is not a copy`() = runTest {
        val element = save(box(A, 0f))
        val page = fold(stamp(element, Matrix3.translate(10f, 10f), null).ops)
        assertTrue(ElementCopies.on(page, element.id).isEmpty())
    }

    @Test
    fun `a copy dragged across the page is found where it is now`() = runTest {
        val element = save(box(A, 0f), box(B, 60f))
        val placed = stamp(element, Matrix3.translate(100f, 200f), INSTANCE).ops
        val stamped = fold(placed)
        val drag = stamped.objects.map {
            PageOp.SetTransform(opId(), clock(), it.id, Matrix3.translate(40f, -30f) * it.transform)
        }
        val page = fold(placed + drag)

        val copy = ElementCopies.on(page, element.id).single()
        assertEquals(140f, copy.placement.m02)
        assertEquals(170f, copy.placement.m12)
    }

    @Test
    fun `a duplicated copy is a second copy and not the same one`() = runTest {
        // Duplicating copies the format register, tag and all, so two copies share an instance id.
        val element = save(box(A, 0f))
        val first = stamp(element, Matrix3.translate(0f, 0f), INSTANCE)
        val second = stamp(element, Matrix3.translate(300f, 0f), INSTANCE)
        val page = fold(first.ops + second.ops)
        assertEquals(2, ElementCopies.count(page, element.id))
    }

    @Test
    fun `replacing a copy puts the revised Element where the old one is and tombstones the old pieces`() = runTest {
        val original = save(box(A, 0f))
        val placed = stamp(original, Matrix3.translate(100f, 100f), INSTANCE)
        val page = fold(placed.ops)
        val revised = library.save(
            "Icon",
            "Set",
            sourcePage(box(A, 0f), box(B, 60f)),
            setOf(ObjectId(A), ObjectId(B)),
            emptyMap(),
            replacing = original.id,
        )

        val update = library.replaceCopies(page, revised, ::newId, ::opId, ::clock)!!
        val after = fold(placed.ops + update.ops)

        val copy = ElementCopies.on(after, revised.id).single()
        assertEquals(INSTANCE, copy.instanceId, "the copy keeps its identity, so a second update finds it")
        assertEquals(2, copy.pieces.size)
        assertEquals(100f, copy.placement.m02)
        val old = page.objects.single().id
        assertTrue(old in after.deleted, "the old piece is a tombstone, not erased from the log")
    }

    @Test
    fun `a replacement keeps the stacking the old copy had`() = runTest {
        val element = save(box(A, 0f))
        val placed = fold(stamp(element, Matrix3.translate(0f, 0f), INSTANCE).ops)
        val above = ShapeObject(
            id = ObjectId(ABOVE),
            shape = ShapeKind.RECTANGLE,
            widthPt = 10f,
            heightPt = 10f,
            style = STYLE,
            z = FractionalKey.between(placed.objects.single().z, null),
        )
        val page = fold(stampedOps(placed) + PageOp.AddObject(opId(), clock(), above))

        val update = library.replaceCopies(page, element, ::newId, ::opId, ::clock)!!
        val after = fold(stampedOps(placed) + PageOp.AddObject("again", clock(), above) + update.ops)
        assertEquals(ABOVE, after.objects.last().id.value, "the object that was above the copy is still above it")
    }

    @Test
    fun `saving from a placed copy drops the tag and keeps the pivot relative`() = runTest {
        val tagged = ObjectFormat(instance = dev.inkstone.core.model.ElementInstance("x", "y", Matrix3.Identity))
        val page = sourcePage(box(A, 0f)).let { base ->
            FoldedPage(
                pageId = base.pageId,
                canvas = base.canvas,
                background = base.background,
                objects = base.objects,
                deleted = emptySet(),
                throughClock = null,
                opCount = 0,
                formats = mapOf(ObjectId(A) to tagged.copy(knockout = true).encode()),
            )
        }
        val saved = library.save(
            "Icon",
            "Set",
            page,
            setOf(ObjectId(A)),
            emptyMap(),
            measured = PageRect(10f, 20f, 60f, 70f),
            pivot = PagePoint(35f, 45f),
        )
        val format = ObjectFormat.decode(saved.formats.values.single())
        assertNull(format.instance)
        assertTrue(format.knockout, "everything else in the register survives")
        assertEquals(PagePoint(25f, 25f), library.list().single().pivot)
    }

    @Test
    fun `revising an Element keeps its id and moves the previous version to trash`() = runTest {
        val original = save(box(A, 0f))
        library.save("Icon v2", "Set", sourcePage(box(B, 0f)), setOf(ObjectId(B)), emptyMap(), replacing = original.id)

        val listed = library.list().single()
        assertEquals(original.id, listed.id)
        assertEquals("Icon v2", listed.name)
        assertTrue(fs.exists("${NoteLayout.ELEMENTS_TRASH_DIR}/${original.id.value}.v1${NoteLayout.ELEMENT_EXTENSION}"))
    }

    // --- fixtures -----------------------------------------------------------

    private suspend fun save(vararg shapes: ShapeObject) =
        library.save("Icon", "Set", sourcePage(*shapes), shapes.map { it.id }.toSet(), emptyMap())

    private fun stamp(element: Element, at: Matrix3, instance: String?): ElementStamp = library.stamp(
        element,
        at,
        null,
        ::newId,
        ::opId,
        ::clock,
        instanceId = instance,
    )

    /** A folded page's own adds, as ops, so a later fold can be built on top of the same ids. */
    private fun stampedOps(page: FoldedPage): List<PageOp> =
        page.objects.map { PageOp.AddObject(opId(), clock(), it) } +
            page.objects.mapNotNull { obj ->
                page.formatOf(obj.id)?.let { PageOp.SetFormat(opId(), clock(), obj.id, it) }
            }

    private fun fold(ops: List<PageOp>) = foldPage(PageId("p"), ops)

    private fun sourcePage(vararg shapes: ShapeObject) = FoldedPage(
        pageId = PageId("source"),
        canvas = PageCanvas.fixed(PageSize.Letter),
        background = Background.Blank,
        objects = shapes.toList(),
        deleted = emptySet(),
        throughClock = null,
        opCount = 0,
    )

    private fun box(id: String, x: Float) = ShapeObject(
        id = ObjectId(id),
        shape = ShapeKind.RECTANGLE,
        widthPt = 50f,
        heightPt = 50f,
        transform = Matrix3.translate(x, 0f),
        style = STYLE,
        z = FractionalKey.Initial,
    )

    private companion object {
        const val A = "018f0000-0000-7000-8000-0000000000a1"
        const val B = "018f0000-0000-7000-8000-0000000000b1"
        const val ABOVE = "018f0000-0000-7000-8000-0000000000c1"
        const val INSTANCE = "018f0000-0000-7000-8000-0000000000d1"
        val STYLE = ObjectStyle(colorArgb = 0xFF000000.toInt(), widthPt = 2f)
    }
}
