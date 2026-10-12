package dev.inkstone.app.elements

import dev.inkstone.app.note.InMemoryFileSystem
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.TextBoxObject
import dev.inkstone.core.model.foldPage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⭐ The Element library the editor uses — `:core:storage`'s, behind [ElementShelf] — and the
 * migration of what the M8c stand-in saved.
 *
 * ⚠️ **What these cannot say.** That the ink surface hands a save the right ids and the right box
 * (`InkSurfaceView.objectsWithin`, `boundsOf`), that the panel shows a picture, and that a drag lands
 * where the finger let go — those are the emulator walk in `docs/M8-ELEMENTS-KNOCKOUT.md` §6.
 */
class ElementShelfTest {

    private val fs = InMemoryFileSystem()
    private val clock = TickingClock()

    @Test
    fun `an Element of handwriting can be saved and stamped`() = runTest {
        // ⭐ The report: a hand-drawn icon saved as "nothing". A stroke is copied as its opaque
        // blob and placed by the box the surface measured, so it lands where it is dropped.
        val shelf = ElementShelf(fs, clock)
        val saved = assertNotNull(
            shelf.save(
                "Squiggle",
                "Icons",
                page(stroke()),
                listOf(ObjectId(STROKE)),
                emptyMap(),
                PageRect(40f, 50f, 100f, 80f),
            ),
        )
        assertEquals(1, saved.objects.size)
        assertEquals(60f, saved.widthPt)

        val stamped = shelf.stamp(saved, PagePoint(500f, 500f), null, freshIds(), opIds(), clocks())
        val landed = foldPage(PageId("p"), stamped.ops).objects.single()
        assertTrue(landed is StrokeObject)
        assertEquals(460f, landed.transform.m02)
        assertEquals(450f, landed.transform.m12)
    }

    @Test
    fun `a save of ids that are not on the page answers null`() = runTest {
        val shelf = ElementShelf(fs, clock)
        assertNull(shelf.save("Ghost", "Icons", page(stroke()), listOf(ObjectId(OTHER)), emptyMap(), null))
        assertTrue(shelf.list().isEmpty())
    }

    @Test
    fun `a legacy Element is migrated with its registers and its picture`() = runTest {
        val legacy = LegacyElementLibrary(fs, clock)
        assertNotNull(legacy.save("Old diagram", "Diagrams", listOf(box(), label(), picture())))

        val listed = ElementShelf(fs, clock).list()

        val migrated = listed.single()
        assertEquals("Old diagram", migrated.name)
        assertEquals("Diagrams", migrated.collection)
        assertEquals(3, migrated.objects.size)
        val text = migrated.objects.filterIsInstance<TextBoxObject>().single()
        assertEquals("Label", migrated.texts[text.id] ?: text.text)
        assertEquals(18f, ObjectFormat.decode(migrated.formats[text.id]).sizePt)
        val image = migrated.objects.filterIsInstance<ImageObject>().single()
        assertContentEquals(PIXELS, migrated.assets[image.asset])
    }

    @Test
    fun `migration removes the index row and leaves the picture file on disk`() = runTest {
        val legacy = LegacyElementLibrary(fs, clock)
        val old = assertNotNull(legacy.save("Photo", "Scraps", listOf(picture())))

        ElementShelf(fs, clock).list()

        assertTrue(legacy.list().isEmpty(), "the row is gone, so a second shelf does not migrate it again")
        // CLAUDE.md rule 1 — the bytes the row named are still where they were.
        assertTrue(fs.exists("elements/${old.id}/0.webp"))
        assertEquals(1, ElementShelf(fs, clock).list().size)
    }

    @Test
    fun `listing twice does not migrate twice`() = runTest {
        LegacyElementLibrary(fs, clock).save("Once", "Shelf", listOf(box()))
        val shelf = ElementShelf(fs, clock)
        shelf.list()
        assertEquals(1, shelf.list().size)
    }

    @Test
    fun `a knockout flag survives a save and a stamp`() = runTest {
        // SPEC §4.9.6 — an icon made to hide what is under it must still do so when stamped.
        val knockout = ObjectFormat(knockout = true).encode()
        val folded = page(stroke(), formats = mapOf(ObjectId(STROKE) to knockout))
        val shelf = ElementShelf(fs, clock)
        val saved = assertNotNull(shelf.save("Hole", "Icons", folded, listOf(ObjectId(STROKE)), emptyMap(), null))

        val stamped = shelf.stamp(saved, PagePoint(0f, 0f), null, freshIds(), opIds(), clocks())
        val landed = foldPage(PageId("p"), stamped.ops)
        assertTrue(ObjectFormat.decode(landed.formatOf(landed.objects.single().id)).knockout)
    }

    @Test
    fun `revising an Element keeps its id and its name`() = runTest {
        val shelf = ElementShelf(fs, clock)
        val saved =
            assertNotNull(shelf.save("Face", "Icons", page(stroke()), listOf(ObjectId(STROKE)), emptyMap(), null))
        val revised = assertNotNull(
            shelf.revise(
                saved,
                page(stroke()),
                listOf(ObjectId(STROKE)),
                emptyMap(),
                PageRect(0f, 0f, 40f, 40f),
                PagePoint(20f, 40f),
            ),
        )
        assertEquals(saved.id, revised.id)
        assertEquals("Face", revised.name)
        assertEquals(PagePoint(20f, 40f), shelf.list().single().pivot)
    }

    @Test
    fun `the notes an Element was placed in are remembered once each`() = runTest {
        val shelf = ElementShelf(fs, clock)
        val saved =
            assertNotNull(shelf.save("Face", "Icons", page(stroke()), listOf(ObjectId(STROKE)), emptyMap(), null))
        val note = dev.inkstone.core.model.DocumentId("018f0000-0000-7000-8000-0000000000c1")
        shelf.recordUse(saved.id, note)
        shelf.recordUse(saved.id, note)
        assertEquals(listOf(note), shelf.uses(saved.id))
        assertEquals(1, shelf.list().size, "the use list is not mistaken for an Element")
    }

    // --- fixtures -----------------------------------------------------------

    private fun page(vararg objects: dev.inkstone.core.model.InkObject, formats: Map<ObjectId, String> = emptyMap()) =
        FoldedPage(
            pageId = PageId("source"),
            canvas = PageCanvas.fixed(PageSize.Letter),
            background = Background.Blank,
            objects = objects.toList(),
            deleted = emptySet(),
            throughClock = null,
            opCount = 0,
            formats = formats,
        )

    private fun stroke() = StrokeObject(id = ObjectId(STROKE), geometry = StrokeGeometry(byteArrayOf(9, 8, 7)))

    private fun box() = CapturedObject(
        obj = ShapeObject(
            id = ObjectId("018f0000-0000-7000-8000-000000000001"),
            shape = ShapeKind.RECTANGLE,
            widthPt = 100f,
            heightPt = 60f,
            transform = Matrix3.translate(10f, 10f),
            style = STYLE,
            z = FractionalKey.Initial,
        ),
        text = null,
        format = null,
        assetBytes = null,
    )

    private fun label() = CapturedObject(
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
        format = ObjectFormat(sizePt = 18f).encode(),
        assetBytes = null,
    )

    private fun picture() = CapturedObject(
        obj = ImageObject(
            id = ObjectId("018f0000-0000-7000-8000-000000000004"),
            asset = "assets/img-018f0000-0000-7000-8000-000000000004.webp",
            widthPt = 100f,
            heightPt = 100f,
            transform = Matrix3.translate(0f, 140f),
            style = STYLE,
            z = FractionalKey.Initial,
        ),
        text = null,
        format = null,
        assetBytes = PIXELS,
    )

    private var minted = 500

    private fun freshIds(): () -> ObjectId = {
        ObjectId("018f0000-0000-7000-8000-${minted++.toString().padStart(12, '0')}")
    }

    private fun opIds(): () -> String = { "op-${minted++}" }

    private fun clocks(): () -> LamportClock {
        var counter = 0L
        return { LamportClock(++counter, "test-device") }
    }

    private class TickingClock : Clock {
        private var now = 1_700_000_000_000L

        override fun nowEpochMillis(): Long = now++
    }

    private companion object {
        const val STROKE = "018f0000-0000-7000-8000-0000000000aa"
        const val OTHER = "018f0000-0000-7000-8000-0000000000bb"
        val STYLE = ObjectStyle(colorArgb = 0xFF112233.toInt(), widthPt = 2f)
        val PIXELS = byteArrayOf(1, 2, 3, 4, 5)
    }
}
