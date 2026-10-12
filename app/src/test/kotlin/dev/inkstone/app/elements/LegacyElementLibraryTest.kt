package dev.inkstone.app.elements

import dev.inkstone.app.note.InMemoryFileSystem
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.ConnectorEnd
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.ShapeRecord
import dev.inkstone.core.model.TextBoxObject
import dev.inkstone.core.storage.Bundle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⭐ ROADMAP M8c — *"save a selection as a reusable Element … tap or drag to stamp"*.
 *
 * The property that matters most is SPEC §4.9.4's: **an Element is a template, never a recording of
 * ops.** Storing the `AddObject`s and replaying them would stamp the same `ObjectId` twice, and
 * `PageFolder.absorb` is first-write-wins on the payload — so the second insertion would produce
 * *one* object carrying the first stamp's payload at the second stamp's transform, and the first
 * copy would visibly vanish. That failure is defined, non-crashing and silent, which is exactly the
 * kind this suite exists to make impossible.
 */
class LegacyElementLibraryTest {

    @Test
    fun `stamping the same element twice gives two independent copies`() = runTest {
        val library = library()
        val saved = library.save("Box and label", "Diagrams", listOf(shape(), textBox()))
        assertTrue(saved != null)

        val first = library.stamp(saved, PagePoint(0f, 0f), null, ids(), opIds(), clocks())
        val second = library.stamp(saved, PagePoint(300f, 0f), null, ids(), opIds(), clocks())

        val firstIds = first.ops.filterIsInstance<PageOp.AddObject>().map { it.obj.id }
        val secondIds = second.ops.filterIsInstance<PageOp.AddObject>().map { it.obj.id }
        assertEquals(2, firstIds.size)
        assertEquals(2, secondIds.size)
        assertTrue(firstIds.intersect(secondIds.toSet()).isEmpty(), "two stamps shared an ObjectId")
    }

    /**
     * ⚠️ A fresh asset **name** per insertion, not just a fresh id.
     *
     * SPEC §4.9.4: an `ImageObject`'s asset string survives re-identification, so two stamps that
     * kept one name would be two objects sharing one entry — which is legal, and then one of them
     * being deleted must not delete the bytes. Minting a name per copy keeps the refcount-free rule
     * trivially true rather than delicately true.
     */
    @Test
    fun `each stamp mints its own asset name and carries its own bytes`() = runTest {
        val library = library()
        val saved = library.save("Photo", "Scraps", listOf(image()))
        assertTrue(saved != null)

        val first = library.stamp(saved, PagePoint(0f, 0f), null, ids(), opIds(), clocks())
        val second = library.stamp(saved, PagePoint(0f, 0f), null, ids(), opIds(), clocks())

        assertEquals(1, first.assets.size)
        assertEquals(1, second.assets.size)
        assertNotEquals(first.assets.keys.single(), second.assets.keys.single())
        assertContentEquals(PIXELS, first.assets.values.single())
        assertContentEquals(PIXELS, second.assets.values.single())
        // The op has to name the entry the stamp carries, or the picture is a blank rectangle that
        // nothing reports (SPEC §4.9.4).
        val named = first.ops.filterIsInstance<PageOp.AddObject>().map { it.obj }
            .filterIsInstance<ImageObject>().single().asset
        assertEquals(first.assets.keys.single(), named)
        assertTrue(named.startsWith(Bundle.ASSETS_DIR))
    }

    /**
     * ⭐ A connector inside an element must name **this copy's** boxes.
     *
     * M8b's recorded regression, arriving from the other direction: a duplicated diagram whose
     * arrows still named the original's shapes resolves as detached — defined, non-crashing and
     * visibly wrong.
     */
    @Test
    fun `a connector inside an element is re-targeted onto the copy`() = runTest {
        val library = library()
        val box = shape()
        val connector = connectorTo(box.obj.id)
        val saved = library.save("Wired", "Diagrams", listOf(box, connector))
        assertTrue(saved != null)

        val stamped = library.stamp(saved, PagePoint(0f, 0f), null, ids(), opIds(), clocks())
        val copiedBoxId = stamped.ops.filterIsInstance<PageOp.AddObject>()
            .map { it.obj }.filterIsInstance<ShapeObject>()
            .first { it.shape == ShapeKind.RECTANGLE }.id
        val record = stamped.ops.filterIsInstance<PageOp.SetTextContent>()
            .firstNotNullOf { ShapeRecord.decode(it.text) }
        assertEquals(listOf(copiedBoxId), record.targets)
        assertNotEquals(box.obj.id, copiedBoxId)
    }

    @Test
    fun `geometry is stored relative to the capture so a stamp lands where it is dropped`() = runTest {
        val library = library()
        // Cut from the bottom of a page: the shape sits at (400, 700).
        val saved = library.save("Far away", "Diagrams", listOf(shape(Matrix3.translate(400f, 700f))))
        assertTrue(saved != null)

        val stamped = library.stamp(saved, PagePoint(20f, 30f), null, ids(), opIds(), clocks())
        val placed = stamped.ops.filterIsInstance<PageOp.AddObject>().single().obj
        assertEquals(20f, placed.transform.m02, TOLERANCE)
        assertEquals(30f, placed.transform.m12, TOLERANCE)
    }

    @Test
    fun `an element survives a round trip through the index`() = runTest {
        val fs = InMemoryFileSystem()
        val saved = LegacyElementLibrary(fs, FixedClock()).save("Kept", "Shelf", listOf(textBox()))
        assertTrue(saved != null)

        val reopened = LegacyElementLibrary(fs, FixedClock()).list()
        assertEquals(1, reopened.size)
        assertEquals("Kept", reopened.single().name)
        assertEquals("Shelf", reopened.single().collection)
        assertEquals(listOf("Shelf"), LegacyElementLibrary(fs, FixedClock()).collections())
    }

    @Test
    fun `an unreadable index answers empty rather than throwing`() = runTest {
        val fs = InMemoryFileSystem()
        fs.writeBytesAtomically("elements/index.json", "not json".encodeToByteArray())
        assertEquals(emptyList(), LegacyElementLibrary(fs, FixedClock()).list())
    }

    @Test
    fun `saving nothing capturable answers null rather than an empty element`() = runTest {
        assertNull(library().save("Empty", "Shelf", emptyList()))
    }

    // --- fixtures -----------------------------------------------------------

    private fun library() = LegacyElementLibrary(InMemoryFileSystem(), FixedClock())

    private fun shape(transform: Matrix3 = Matrix3.translate(10f, 10f)) = CapturedObject(
        obj = ShapeObject(
            id = ObjectId("018f0000-0000-7000-8000-000000000001"),
            shape = ShapeKind.RECTANGLE,
            widthPt = 100f,
            heightPt = 60f,
            transform = transform,
            style = STYLE,
            z = FractionalKey.Initial,
        ),
        text = null,
        format = null,
        assetBytes = null,
    )

    private fun connectorTo(target: ObjectId) = CapturedObject(
        obj = ShapeObject(
            id = ObjectId("018f0000-0000-7000-8000-000000000002"),
            shape = ShapeKind.ARROW,
            widthPt = 80f,
            heightPt = 1f,
            transform = Matrix3.translate(120f, 40f),
            style = STYLE,
            z = FractionalKey.Initial,
        ),
        text = ShapeRecord.None.copy(endA = ConnectorEnd.Attached(target)).encode(),
        format = null,
        assetBytes = null,
    )

    private fun textBox() = CapturedObject(
        obj = TextBoxObject(
            id = ObjectId("018f0000-0000-7000-8000-000000000003"),
            text = "Label",
            widthPt = 120f,
            heightPt = 30f,
            transform = Matrix3.translate(10f, 90f),
            style = STYLE,
            z = FractionalKey.Initial,
        ),
        text = "Label",
        format = ObjectFormat.Default.copy(sizePt = 18f).encode(),
        assetBytes = null,
    )

    private fun image() = CapturedObject(
        obj = ImageObject(
            id = ObjectId("018f0000-0000-7000-8000-000000000004"),
            asset = "assets/img-018f0000-0000-7000-8000-000000000004.webp",
            widthPt = 100f,
            heightPt = 100f,
            transform = Matrix3.translate(0f, 0f),
            style = STYLE,
            z = FractionalKey.Initial,
        ),
        text = null,
        format = null,
        assetBytes = PIXELS,
    )

    /**
     * Ids that are unique across the whole test class, not merely within one stamp.
     *
     * ⚠️ A per-stamp counter would hand two stamps the same ids and make
     * `stamping the same element twice gives two independent copies` pass by construction — the
     * fixture agreeing with the bug it is meant to catch, which is CLAUDE.md's *"distrust a fixture
     * that is more generous than production"* in the one place it would be invisible.
     */
    private fun ids(): () -> ObjectId = { ObjectId("018f0000-0000-7000-8000-${minted++.toString().padStart(12, '0')}") }

    private fun opIds(): () -> String = { "op-${minted++}" }

    private fun clocks(): () -> LamportClock {
        var counter = 0L
        return { LamportClock(++counter, "test-device") }
    }

    private var minted = 100

    private class FixedClock : Clock {
        private var now = 1_700_000_000_000L

        override fun nowEpochMillis(): Long = now++
    }

    private companion object {
        val STYLE = ObjectStyle(colorArgb = 0xFF112233.toInt(), widthPt = 2f)
        val PIXELS = byteArrayOf(1, 2, 3, 4, 5)
        const val TOLERANCE = 0.001f
    }
}
