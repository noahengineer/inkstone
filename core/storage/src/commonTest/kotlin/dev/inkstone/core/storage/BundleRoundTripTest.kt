package dev.inkstone.core.storage

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Document
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.EraseCapsule
import dev.inkstone.core.model.ErasedArea
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.OpExtras
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageRef
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PageTemplate
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.TextBoxObject
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SPEC §5.3, §5.4 and §5.8 — the format tests ROADMAP M2 asks for by name:
 *
 * - round-trip: save → load → save → **byte-identical**
 * - unknown op types and unknown JSON fields survive a round-trip **untouched**
 * - stroke geometry is copied as **bytes**, never interpreted
 */
class BundleRoundTripTest {

    private val documentId = DocumentId("doc-1")
    private val pageA = PageId("page-a")
    private val pageB = PageId("page-b")

    private var counter = 0L

    private fun clock() = LamportClock(++counter, "device-a")

    private var opIds = 0

    private fun opId() = "op-${opIds++}"

    /**
     * A page holding one of every op and one of every object kind.
     *
     * Every variant, deliberately: the whole point of defining the vocabulary in M2 is
     * that it is complete, and a format test that only covers strokes would let an
     * unencodable `ShapeObject` reach M8 undiscovered.
     */
    private fun everythingPage(): PageContents {
        val strokeId = ObjectId("obj-stroke")
        val textId = ObjectId("obj-text")
        return PageContents(
            size = PageSize.A4,
            background = Background.Template(PageTemplate.CORNELL),
            ops = listOf(
                PageOp.AddObject(
                    opId(),
                    clock(),
                    StrokeObject(
                        id = strokeId,
                        geometry = StrokeGeometry(ByteArray(GEOMETRY_BYTES) { (it * 7 % 256 - 128).toByte() }),
                        z = FractionalKey("V"),
                    ),
                ),
                PageOp.AddObject(
                    opId(),
                    clock(),
                    ImageObject(ObjectId("obj-image"), "assets/img-1.webp", 100f, 80f, z = FractionalKey("W")),
                ),
                PageOp.AddObject(
                    opId(),
                    clock(),
                    TextBoxObject(textId, "hello", 200f, 40f, z = FractionalKey("X")),
                ),
                PageOp.AddObject(
                    opId(),
                    clock(),
                    ShapeObject(ObjectId("obj-shape"), ShapeKind.ARROW, 50f, 50f, z = FractionalKey("Y")),
                ),
                PageOp.SetTransform(opId(), clock(), strokeId, Matrix3(1.5f, 0.25f, -3f, 0f, 2f, 7.5f)),
                PageOp.SetStyle(opId(), clock(), strokeId, ObjectStyle(0xFF1A1A1A.toInt(), 3.5f, 0.75f)),
                PageOp.SetZOrder(opId(), clock(), strokeId, FractionalKey("Zz")),
                PageOp.SetTextContent(opId(), clock(), textId, "hello \"world\"\n— with escapes"),
                PageOp.SetErasedRegion(
                    opId(),
                    clock(),
                    strokeId,
                    ErasedArea(List(CAPSULES) { EraseCapsule(it.toFloat(), 1.5f, it + 1f, 2.25f, 4f) }),
                ),
                PageOp.SetPageMeta(opId(), clock(), background = Background.SourcePdfPage(3), size = PageSize.Letter),
                PageOp.DeleteObject(opId(), clock(), ObjectId("obj-shape")),
            ),
        )
    }

    private fun contents(pages: Map<PageId, PageContents>) = BundleContents(
        document = Document(
            id = documentId,
            title = "Lecture 4 — \"Fractional\" keys",
            pages = pages.keys.mapIndexed { index, id ->
                PageRef(id, FractionalKey.sequence(pages.size)[index])
            },
            localRev = 17,
            deviceId = "device-a",
            createdEpochMillis = 1_700_000_000_000,
            modifiedEpochMillis = 1_800_000_000_000,
        ),
        pages = pages,
        highestClockCounter = counter,
    )

    @Test
    fun `save load save is byte-identical`() = runTest {
        val original = contents(mapOf(pageA to everythingPage(), pageB to everythingPage()))
        val first = BundleWriter.write(original)

        val reader = assertNotNull(BundleReader.open(MemoryByteSource(first)))
        val reloaded = BundleContents(
            document = reader.document,
            pages = reader.document.pages.associate { it.id to assertNotNull(reader.page(it.id)) },
            highestClockCounter = reader.highestClockCounter,
            carried = reader.carried(),
        )
        val second = BundleWriter.write(reloaded)

        assertContentEquals(first, second, "a bundle did not survive a round-trip byte-identically")
    }

    @Test
    fun `every op and object kind survives a round-trip`() = runTest {
        val page = everythingPage()
        val bytes = BundleWriter.write(contents(mapOf(pageA to page)))

        val reader = assertNotNull(BundleReader.open(MemoryByteSource(bytes)))
        val reloaded = assertNotNull(reader.page(pageA))

        assertEquals(page.ops, reloaded.ops)
        assertEquals(page.size, reloaded.size)
        assertEquals(page.background, reloaded.background)
    }

    @Test
    fun `stroke geometry comes back byte-for-byte`() = runTest {
        // SPEC §5.4 — core stores and syncs geometry as opaque bytes and never interprets
        // it. The test for "never interprets" is that arbitrary bytes survive.
        val geometry = StrokeGeometry(ByteArray(GEOMETRY_BYTES) { (it % 251).toByte() })
        val page = PageContents(
            PageSize.Letter,
            Background.Blank,
            listOf(PageOp.AddObject(opId(), clock(), StrokeObject(ObjectId("obj-1"), geometry))),
        )
        val bytes = BundleWriter.write(contents(mapOf(pageA to page)))

        val reader = assertNotNull(BundleReader.open(MemoryByteSource(bytes)))
        val restored = assertNotNull(reader.page(pageA)).ops.single() as PageOp.AddObject

        assertContentEquals(geometry.bytes, (restored.obj as StrokeObject).geometry.bytes)
    }

    @Test
    fun `an unknown op type survives untouched`() = runTest {
        // SPEC §5.8: a newer build writes an op this one has never heard of. Opening and
        // re-saving must not destroy it.
        val fromTheFuture = """{"i":"op-future","c":99,"d":"device-z","t":"SetHologram","depth":3.5}"""
        val page = PageContents(
            PageSize.Letter,
            Background.Blank,
            listOf(
                PageOp.AddObject(opId(), clock(), StrokeObject(ObjectId("obj-1"), StrokeGeometry(byteArrayOf(1)))),
                PageOp.Unknown(
                    opId = "op-future",
                    clock = LamportClock(99, "device-z"),
                    type = "SetHologram",
                    rawJson = fromTheFuture.encodeToByteArray(),
                ),
            ),
        )
        val first = BundleWriter.write(contents(mapOf(pageA to page)))

        val reader = assertNotNull(BundleReader.open(MemoryByteSource(first)))
        val reloaded = assertNotNull(reader.page(pageA))
        val unknown = reloaded.ops.filterIsInstance<PageOp.Unknown>().single()

        assertEquals("SetHologram", unknown.type)
        assertEquals(LamportClock(99, "device-z"), unknown.clock, "an unknown op must keep its place in the order")
        assertTrue(unknown.rawJson.decodeToString().contains("\"depth\":3.5"), "the payload was not preserved")

        // And writing it back produces the same bytes as the first save.
        val second = BundleWriter.write(
            BundleContents(reader.document, mapOf(pageA to reloaded), reader.highestClockCounter, reader.carried()),
        )
        assertContentEquals(first, second)
    }

    @Test
    fun `an unknown field on a known op survives untouched`() = runTest {
        // The subtler half of SPEC §5.8: a newer build adds a field to an op type that
        // already exists. Dropping it degrades the newer device's data on every save.
        val extras = OpExtras("""{"pressureCurve":"v2","tiltCompensated":true}""")
        val page = PageContents(
            PageSize.Letter,
            Background.Blank,
            listOf(
                PageOp.SetTransform(
                    opId(),
                    clock(),
                    ObjectId("obj-1"),
                    Matrix3.scale(2f),
                    extras = extras,
                ),
            ),
        )
        val first = BundleWriter.write(contents(mapOf(pageA to page)))

        val reader = assertNotNull(BundleReader.open(MemoryByteSource(first)))
        val op = assertNotNull(reader.page(pageA)).ops.single()

        assertEquals(extras, op.extras)
        assertEquals(Matrix3.scale(2f), (op as PageOp.SetTransform).absolute, "the known fields still decoded")
        assertContentEquals(
            first,
            BundleWriter.write(
                BundleContents(
                    reader.document,
                    mapOf(pageA to assertNotNull(reader.page(pageA))),
                    reader.highestClockCounter,
                ),
            ),
        )
    }

    @Test
    fun `entries this build does not produce are carried through`() = runTest {
        // `assets/` is M8's, `future/` stands in for whatever comes after. A note that gains
        // them must not lose them the next time an older build saves.
        //
        // ⚠️ This used to use `text/<pageId>.json`. M7 made that a KNOWN family (SPEC §5.5) —
        // it is written from `PageContents` and excluded from `carriedNames` — so as a fixture
        // for *unknown* data it now asserts the opposite of what it says.
        val recognised = """{"anything":"a newer build understands this"}""".encodeToByteArray()
        val image = ByteArray(64) { it.toByte() }
        val first = BundleWriter.write(
            contents(mapOf(pageA to everythingPage())).let { base ->
                BundleContents(
                    base.document,
                    base.pages,
                    base.highestClockCounter,
                    carried = mapOf("future/${pageA.value}.bin" to recognised, "assets/img-1.webp" to image),
                )
            },
        )

        val reader = assertNotNull(BundleReader.open(MemoryByteSource(first)))
        val carried = reader.carried()

        assertContentEquals(recognised, carried.getValue("future/${pageA.value}.bin"))
        assertContentEquals(image, carried.getValue("assets/img-1.webp"))
        assertContentEquals(
            first,
            BundleWriter.write(
                BundleContents(
                    reader.document,
                    reader.document.pages.associate { it.id to assertNotNull(reader.page(it.id)) },
                    reader.highestClockCounter,
                    carried,
                ),
            ),
        )
    }

    @Test
    fun `opening a page reads only that page`() = runTest {
        // SPEC §5.3 — "one file per page gives lazy loading". The claim is only true if
        // reading page A does not touch page B, so this counts the bytes.
        val pages = (0 until MANY_PAGES).associate { PageId("page-$it") to everythingPage() }
        val bytes = BundleWriter.write(contents(pages))
        val counting = CountingByteSource(MemoryByteSource(bytes))

        val reader = assertNotNull(BundleReader.open(counting))
        val afterOpen = counting.bytesRead
        assertNotNull(reader.page(PageId("page-0")))
        val afterOnePage = counting.bytesRead - afterOpen

        // Opening reads the tail, the central directory and the manifest — all of which
        // grow with page *count*, not page content. One page then costs about one page.
        assertTrue(
            afterOnePage < bytes.size / (MANY_PAGES / 2),
            "reading one of $MANY_PAGES pages cost $afterOnePage of ${bytes.size} bytes",
        )
    }

    private class CountingByteSource(private val delegate: ByteSource) : ByteSource {
        var bytesRead: Int = 0
            private set

        override suspend fun size(): Long = delegate.size()

        override suspend fun read(offset: Long, length: Int): ByteArray =
            delegate.read(offset, length).also { bytesRead += it.size }
    }

    private companion object {
        const val GEOMETRY_BYTES = 1024
        const val CAPSULES = 40
        const val MANY_PAGES = 50
    }
}
