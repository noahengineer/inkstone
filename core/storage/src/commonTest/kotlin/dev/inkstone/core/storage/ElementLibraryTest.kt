package dev.inkstone.core.storage

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.ConnectorEnd
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.InkObject
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.ShapeRecord
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.TextBoxObject
import dev.inkstone.core.model.TextFont
import dev.inkstone.core.model.foldPage
import dev.inkstone.core.model.formatting
import dev.inkstone.core.model.recordOf
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ROADMAP M8c / SPEC §4.9.4 — the Element library.
 *
 * ⭐ **The assertion that matters is the first one: two stamps of one Element must leave two
 * objects on the page.** An Element built the obvious way — a stored list of `AddObject` ops,
 * replayed — passes every other test in this file and fails that one, because `PageFolder.absorb`
 * is first-write-wins on payload: two stamps of one Element converge to a **single** object
 * carrying the first stamp's payload at the second stamp's transform, so the first stamp visibly
 * vanishes while the log stays well-formed, the fold stays deterministic and both devices agree.
 * Everything else here is scaffolding around that one case.
 *
 * ⚠️ These are pure-storage tests. They establish nothing about whether the user can reach an
 * Element in the app (CLAUDE.md definition-of-done #6); that is the emulator walk's job.
 */
class ElementLibraryTest {

    private val fs = FakeFileSystem()
    private val clock = TestClock()
    private val ids = IdFactory(clock, Random(19))
    private val library = ElementLibrary(fs, ids)

    private var opCounter = 0
    private var clockCounter = 0L

    private fun nextOpId(): String = "op-${opCounter++}"

    private fun nextClock(): LamportClock = LamportClock(++clockCounter, DEVICE)

    // -----------------------------------------------------------------------
    // ⭐ The corruption this design exists to prevent
    // -----------------------------------------------------------------------

    @Test
    fun `stamping one Element twice puts two objects on the page`() = runTest {
        val element = library.save("Beaker", "Chemistry", diagram(), setOf(BOX_A, PICTURE), sourceAssets())

        val first = stamp(element, Matrix3.translate(10f, 10f))
        val second = stamp(element, Matrix3.translate(400f, 300f))

        val page = foldPage(PAGE, first.ops + second.ops)
        assertEquals(4, page.objects.size, "two objects per stamp, and the first stamp did not vanish")

        val stamped = page.objects.map { it.id }
        assertEquals(stamped.size, stamped.toSet().size, "every stamped object has an identity of its own")
        assertTrue(stamped.none { it == BOX_A || it == PICTURE }, "and none of them is the saved object's id")
    }

    @Test
    fun `each stamp of an image gets its own asset name and its own bytes`() = runTest {
        // ⭐ `copiedObjectOps` copies `ImageObject.asset` verbatim, which is right *within* one
        // note — a copy shares the original's bytes and asset lifetime is refcount-free. An Element
        // is not within one note: the bytes are the Element's own and the note it lands in has
        // never seen them, so the name has to be minted fresh from the new object's id or the
        // stamped picture is a blank rectangle (SPEC §4.9.4).
        val element = library.save("Beaker", "Chemistry", diagram(), setOf(PICTURE), sourceAssets())

        val first = stamp(element, Matrix3.translate(10f, 10f))
        val second = stamp(element, Matrix3.translate(400f, 300f))

        val page = foldPage(PAGE, first.ops + second.ops)
        val assets = page.objects.filterIsInstance<ImageObject>().map { it.asset }
        assertEquals(2, assets.size)
        assertEquals(2, assets.toSet().size, "two stamps, two asset names")
        assertEquals(assets.toSet(), first.assets.keys + second.assets.keys)
        assertContentEquals(PIXELS, first.assets.values.single())
        assertContentEquals(PIXELS, second.assets.values.single())
        assertFalse(Bundle.imageAsset(PICTURE) in assets, "and not the source note's name")
    }

    @Test
    fun `the two stamps land where they were put`() = runTest {
        val element = library.save("Box", "Chemistry", diagram(), setOf(BOX_A), emptyMap())

        val first = stamp(element, Matrix3.translate(10f, 10f))
        val second = stamp(element, Matrix3.translate(400f, 300f))

        val page = foldPage(PAGE, first.ops + second.ops)
        val origins = page.objects.map { it.transform.m02 to it.transform.m12 }.toSet()
        // The saved box is at its Element's own origin, so `at` is simply where it goes.
        assertEquals(setOf(10f to 10f, 400f to 300f), origins)
    }

    @Test
    fun `a stamp keeps its internal stacking and lands above the key it was given`() = runTest {
        val element = library.save("Group", "Chemistry", diagram(), setOf(BOX_A, BOX_B), emptyMap())

        val page = foldPage(PAGE, stamp(element, Matrix3.Identity).ops)
        val keys = page.objects.map { it.z }
        assertEquals(2, keys.size)
        assertTrue(keys.all { it > DROP_AT }, "the stamp sits above where it was dropped")
        assertEquals(keys.sorted(), keys, "and keeps its own order")
    }

    // -----------------------------------------------------------------------
    // Save and re-open
    // -----------------------------------------------------------------------

    @Test
    fun `an Element survives being written and read back`() = runTest {
        val saved = library.save("Beaker", "Chemistry", diagram(), setOf(BOX_A, LABEL, INK, PICTURE), sourceAssets())

        val listed = assertNotNull(library.list().singleOrNull())
        assertEquals(saved.id, listed.id)
        assertEquals("Beaker", listed.name)
        assertEquals("Chemistry", listed.collection)
        assertEquals(4, listed.objects.size)

        // Both registers, and the opaque geometry blob — which is why the file is a ZIP with a
        // sibling `.ink` entry rather than one JSON document (SPEC §5.4).
        assertEquals("Concentration", listed.texts[LABEL])
        assertEquals(TextFont.SERIF, ObjectFormat.decode(listed.formats[LABEL]).font)
        val stroke = assertNotNull(listed.objects.filterIsInstance<StrokeObject>().singleOrNull())
        assertContentEquals(GEOMETRY, stroke.geometry.bytes)
        assertContentEquals(PIXELS, assertNotNull(listed.assets.values.singleOrNull()))
    }

    @Test
    fun `a stamped text box carries both registers onto the copy`() = runTest {
        library.save("Label", "Chemistry", diagram(), setOf(LABEL), emptyMap())
        val reopened = assertNotNull(library.list().singleOrNull())

        val page = foldPage(PAGE, stamp(reopened, Matrix3.Identity).ops)
        val copy = page.objects.single()
        assertNotEquals(LABEL, copy.id)
        assertEquals("Concentration", page.textOf(copy.id))
        assertEquals(TextFont.SERIF, page.formatting(copy.id).font)
        assertEquals(STYLE, copy.style, "and the style, so the first recolour has an inverse")
    }

    @Test
    fun `a stamped connector points at the copies and not at the originals`() = runTest {
        // The failure a bare AddObject loop produces: a record carried verbatim names objects the
        // stamp does not contain, so every connector in a stamped diagram resolves as detached.
        val element = library.save("Wired", "Chemistry", diagram(), setOf(BOX_A, BOX_B, CONNECTOR), emptyMap())

        val page = foldPage(PAGE, stamp(element, Matrix3.translate(5f, 5f)).ops)
        val record = page.objects.map { page.recordOf(it.id) }.first { it.isConnector }
        assertTrue(record.targets.isNotEmpty(), "the record was carried at all")
        assertTrue(record.targets.none { it == BOX_A || it == BOX_B }, "and re-targeted")
        assertTrue(record.targets.all { target -> page.objects.any { it.id == target } })
    }

    @Test
    fun `transforms are stored relative to the selection and not to the page`() = runTest {
        // BOX_B sits at (300, 0) on the source page; saved alone it becomes the Element's origin,
        // so a stamp is placed by `at` rather than being pinned where it was drawn.
        val element = library.save("Box", "Chemistry", diagram(), setOf(BOX_B), emptyMap())

        assertEquals(0f, element.objects.single().transform.m02)
        assertEquals(0f, element.objects.single().transform.m12)
        assertEquals(BOX_EXTENT, element.widthPt)
        assertEquals(BOX_EXTENT, element.heightPt)
    }

    @Test
    fun `an Element of nothing but ink reports a zero extent rather than guessing one`() = runTest {
        // ⚠️ SPEC §5.4 — a stroke's geometry is an opaque blob, so `:core:*` cannot measure it and
        // does not pretend to. The zero extent is a signal the caller can check; inventing a box
        // here would be a number that looks right in a picker and is wrong on paper.
        val element = library.save("Squiggle", "Chemistry", diagram(), setOf(INK), emptyMap())

        assertEquals(0f, element.widthPt)
        assertEquals(0f, element.heightPt)
    }

    @Test
    fun `an image whose bytes were not supplied is dropped rather than saved empty`() = runTest {
        val element = library.save("Picture", "Chemistry", diagram(), setOf(PICTURE), emptyMap())

        assertTrue(element.assets.isEmpty())
        assertTrue(stamp(element, Matrix3.Identity).assets.isEmpty(), "and nothing empty reaches the note")
    }

    // -----------------------------------------------------------------------
    // Listing, collections, deletion
    // -----------------------------------------------------------------------

    @Test
    fun `listing filters by collection and is ordered`() = runTest {
        library.save("Beaker", "Chemistry", diagram(), setOf(BOX_A), emptyMap())
        library.save("Axis", "Maths", diagram(), setOf(BOX_B), emptyMap())
        library.save("Atom", "Chemistry", diagram(), setOf(BOX_A), emptyMap())

        assertEquals(listOf("Atom", "Beaker"), library.list("Chemistry").map { it.name })
        assertEquals(listOf("Axis"), library.list("Maths").map { it.name })
        assertEquals(listOf("Atom", "Beaker", "Axis"), library.list().map { it.name })
    }

    @Test
    fun `deleting an Element moves it to a trash rather than destroying it`() = runTest {
        // CLAUDE.md rule 1 has no exception for things that look small.
        val element = library.save("Beaker", "Chemistry", diagram(), setOf(BOX_A), emptyMap())
        library.delete(element.id)

        assertTrue(library.list().isEmpty())
        assertTrue(fs.exists(NoteLayout.trashedElement(element.id)), "the bytes are still there")
        assertFalse(fs.exists(NoteLayout.element(element.id)))
    }

    @Test
    fun `deleting an Element that is not there is not an error`() = runTest {
        library.delete(ids.newElementId())
        assertTrue(library.list().isEmpty())
    }

    @Test
    fun `an unreadable file costs one row and not the picker`() = runTest {
        val element = library.save("Beaker", "Chemistry", diagram(), setOf(BOX_A), emptyMap())
        fs.writeBytesAtomically("${NoteLayout.ELEMENTS_DIR}/not-an-element.element", byteArrayOf(1, 2, 3))

        assertEquals(listOf(element.id), library.list().map { it.id })
    }

    @Test
    fun `saving nothing yields an empty Element rather than an error`() = runTest {
        val element = library.save("Nothing", "Chemistry", diagram(), emptySet(), emptyMap())

        assertTrue(element.objects.isEmpty())
        assertTrue(stamp(element, Matrix3.Identity).ops.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Fixture — a small diagram: two boxes, a connector, a label, a picture, a stroke
    // -----------------------------------------------------------------------

    private fun stamp(element: Element, at: Matrix3): ElementStamp =
        library.stamp(element, at, DROP_AT, ids::newObjectId, ::nextOpId, ::nextClock)

    private fun sourceAssets(): Map<String, ByteArray> = mapOf(Bundle.imageAsset(PICTURE) to PIXELS)

    private fun diagram(): FoldedPage {
        val objects = listOf<InkObject>(
            box(BOX_A, 0f, FractionalKey("a")),
            box(BOX_B, 300f, FractionalKey("b")),
            ShapeObject(
                id = CONNECTOR,
                shape = ShapeKind.ARROW,
                widthPt = 250f,
                heightPt = 1f,
                transform = Matrix3.translate(0f, 49.5f),
                z = FractionalKey("c"),
            ),
            TextBoxObject(
                id = LABEL,
                text = "Concentration",
                widthPt = 120f,
                heightPt = 20f,
                transform = Matrix3.translate(10f, 200f),
                style = STYLE,
                z = FractionalKey("d"),
            ),
            ImageObject(
                id = PICTURE,
                asset = Bundle.imageAsset(PICTURE),
                widthPt = 80f,
                heightPt = 60f,
                transform = Matrix3.translate(150f, 150f),
                style = STYLE,
                z = FractionalKey("e"),
            ),
            StrokeObject(id = INK, geometry = StrokeGeometry(GEOMETRY), z = FractionalKey("f")),
        )
        return FoldedPage(
            pageId = PAGE,
            canvas = PageCanvas.fixed(PageSize.Letter),
            background = Background.Blank,
            objects = objects,
            deleted = emptySet(),
            throughClock = LamportClock(1, DEVICE),
            opCount = objects.size,
            texts = mapOf(
                LABEL to "Concentration",
                CONNECTOR to ShapeRecord(
                    endA = ConnectorEnd.Attached(BOX_A),
                    endB = ConnectorEnd.Attached(BOX_B),
                ).encode(),
            ),
            formats = mapOf(LABEL to ObjectFormat(font = TextFont.SERIF, sizePt = 18f).encode()),
        )
    }

    private fun box(id: ObjectId, x: Float, z: FractionalKey) = ShapeObject(
        id = id,
        shape = ShapeKind.RECTANGLE,
        widthPt = BOX_EXTENT,
        heightPt = BOX_EXTENT,
        transform = Matrix3.translate(x, 0f),
        z = z,
    )

    private companion object {
        const val DEVICE = "device-a"
        const val BOX_EXTENT = 100f
        val PAGE = PageId("page-1")
        val DROP_AT = FractionalKey("m")
        val BOX_A = ObjectId("obj-box-a")
        val BOX_B = ObjectId("obj-box-b")
        val CONNECTOR = ObjectId("obj-connector")
        val LABEL = ObjectId("obj-label")
        val PICTURE = ObjectId("obj-picture")
        val INK = ObjectId("obj-ink")
        val STYLE = ObjectStyle(colorArgb = -0x1000000, widthPt = 1f)
        val GEOMETRY = byteArrayOf(9, 8, 7, 6, 5)
        val PIXELS = byteArrayOf(1, 2, 3, 4)
    }
}
