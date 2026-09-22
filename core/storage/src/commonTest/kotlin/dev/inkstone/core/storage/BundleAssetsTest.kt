package dev.inkstone.core.storage

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Document
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageRef
import dev.inkstone.core.model.PageSize
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⭐ **SPEC §5.3's M8c subsection — `assets/` as a *known* entry family, with a peer union.**
 *
 * Until M8c nothing wrote an asset. `Bundle.ASSETS_DIR` was a declared constant with no writer,
 * and the directory survived a round-trip only because `carriedNames` is defined by *exclusion*:
 * anything that is not `manifest.json`, `pages/` or `text/`. That made every image a **carried
 * unknown**, and carried unknowns merge local-wins — which is exactly wrong for a family both
 * devices write.
 *
 * Three claims are under test here, and each one fails invisibly if it is wrong. An `ImageObject`
 * whose asset is missing is a **blank rectangle**: defined, non-crashing, and reported by nothing.
 *
 * 1. **The bytes survive a write and a read**, and a note that already has them does not lose
 *    them the first time this build rewrites its bundle. That is the regression the change
 *    itself could have caused: the family left `carriedNames` in the same commit that gave it a
 *    writer, so a materialize that did not read `assets()` back would have deleted every picture
 *    in every existing note.
 * 2. **The merge is a union by name, keeping both sides.** An asset name embeds a UUIDv7
 *    ([Bundle.imageAsset]), so the same name on two devices is the same bytes and there is
 *    nothing to resolve; a name only one device has is a picture only that device can draw.
 * 3. **An entry is written exactly once**, however many routes hand it in. `Zip.write` neither
 *    de-duplicates nor complains, so a family that is both written and carried becomes two ZIP
 *    entries with one name and the winner depends on map iteration order — M7's `text/` trap
 *    verbatim, which is why [entryNamesInCentralDirectory] reads the container by hand rather
 *    than through `Zip.Directory`, whose index is keyed by name and so cannot show a duplicate
 *    at all.
 */
class BundleAssetsTest {

    private val fs = FakeFileSystem()
    private val clock = TestClock()
    private val ids = IdFactory(clock, Random(8))
    private val store = NoteStore(fs, clock, ids)

    private val pageA = PageId("page-a")

    private val ours = Bundle.imageAsset(ObjectId("obj-ours"))
    private val theirs = Bundle.imageAsset(ObjectId("obj-theirs"))

    private fun webp(seed: Int) = ByteArray(ASSET_BYTES) { (it * seed % 256 - 128).toByte() }

    private fun imagePage(asset: String) = PageContents(
        canvas = PageCanvas.fixed(PageSize.Letter),
        background = Background.Blank,
        ops = listOf(
            PageOp.AddObject(
                opId = "op-image",
                clock = LamportClock(1, "device-a"),
                obj = ImageObject(ObjectId("obj-ours"), asset, 100f, 80f, z = FractionalKey.Initial),
            ),
        ),
    )

    private fun contents(assets: Map<String, ByteArray>, carried: Map<String, ByteArray> = emptyMap()) = BundleContents(
        document = Document(
            id = DocumentId("doc-assets"),
            title = "Diagram",
            pages = listOf(PageRef(pageA, FractionalKey.Initial)),
            deviceId = "device-a",
        ),
        pages = mapOf(pageA to imagePage(ours)),
        highestClockCounter = 1L,
        carried = carried,
        assets = assets,
    )

    // -----------------------------------------------------------------------
    // The family itself
    // -----------------------------------------------------------------------

    @Test
    fun `an asset survives a round-trip byte-for-byte`() = runTest {
        val bytes = webp(seed = 7)
        val written = BundleWriter.write(contents(mapOf(ours to bytes)))

        val reader = assertNotNull(BundleReader.open(MemoryByteSource(written)))

        assertContentEquals(bytes, assertNotNull(reader.assets()[ours]), "an asset did not survive the write")
        // The name the object holds is the entry name, with no derivation in between: SPEC §5.3
        // and `ImageObject.asset`. A mismatch here is precisely the blank rectangle.
        val image = assertNotNull(reader.page(pageA)).ops
            .filterIsInstance<PageOp.AddObject>()
            .map { it.obj }
            .filterIsInstance<ImageObject>()
            .single()
        assertEquals(ours, image.asset)
    }

    @Test
    fun `assets are no longer carried unknowns`() = runTest {
        val written = BundleWriter.write(contents(mapOf(ours to webp(seed = 3))))

        val reader = assertNotNull(BundleReader.open(MemoryByteSource(written)))

        // The half that stops the double entry: a family with a writer must not also be in the
        // verbatim pass-through set, or `BundleWriter` emits it twice.
        assertFalse(ours in reader.carriedNames, "assets/ is still in carriedNames — it would be written twice")
        assertTrue(reader.carriedUnknowns().isEmpty(), "an asset came back as an entry this build cannot interpret")
        // ...and the half that stops the silent drop: what a merge or a copy hands to the next
        // materialize still has to contain them, or a peer's pictures vanish on the first
        // bundle rewrite with nothing reported.
        assertEquals(listOf(ours), reader.assetNames)
        assertEquals(setOf(ours), reader.carried().keys)
    }

    @Test
    fun `an asset handed in by both routes is written exactly once`() = runTest {
        // The trap SPEC §5.3 names: `MergeReport.carried` is an uninspected map, and before M8c
        // it was where a peer's assets arrived. Handing the same name in as *both* a carried
        // entry and a written one must not append two entries — the reader's winner would then
        // depend on map iteration order, and the two devices need not pick the same one.
        val written = BundleWriter.write(
            contents(assets = mapOf(ours to webp(seed = 5)), carried = mapOf(ours to webp(seed = 5))),
        )

        val names = entryNamesInCentralDirectory(written)

        assertEquals(names.size, names.toSet().size, "a name appears twice in the ZIP: $names")
        assertEquals(1, names.count { it == ours }, "the asset was written ${names.count { it == ours }} times")
    }

    @Test
    fun `a carried entry that collides with a written family is refused`() = runTest {
        // The general form of the rule, for the *next* family to gain a writer — that is how
        // `text/` arrived here in M7 and `assets/` in M8c. `BundleContents` already makes the
        // assets case unrepresentable; everything else has to be caught at the one point that
        // can see both sets. A caller doing this has made a programming error, so it throws
        // rather than silently dropping one side.
        //
        // The fixture collides with `pages/<id>.ink`, which is written unconditionally — the
        // guard compares against the names actually emitted rather than against a list of
        // family prefixes, so it fires on exactly the inputs that would produce two entries and
        // on no others.
        val clash = Bundle.pageInk(pageA)
        val failure = assertFailsWith<IllegalArgumentException> {
            BundleWriter.write(contents(assets = emptyMap(), carried = mapOf(clash to webp(seed = 1))))
        }

        assertTrue(clash in failure.message.orEmpty(), "the refusal did not name the colliding entry")
    }

    // -----------------------------------------------------------------------
    // The union — SPEC §5.3, and the reason it is not local-wins
    // -----------------------------------------------------------------------

    @Test
    fun `a materialize keeps the notes own assets`() = runTest {
        // ⚠️ The regression the change itself could have caused. `assets/` left `carriedNames`
        // in the same commit that gave it a writer, so a materialize that did not read
        // `reader.assets()` back would delete every picture in every existing note — on the
        // first ordinary autosave-then-close, with nothing reported.
        val note = firstMaterialize(mapOf(ours to webp(seed = 9)))

        val after = store.materialize(note, pagesOf(note))

        val kept = assertNotNull(after.reader).assets()[ours]
        assertContentEquals(webp(seed = 9), assertNotNull(kept, "the note lost its own image"))
    }

    @Test
    fun `a materialize unions the peers assets with the local ones`() = runTest {
        val note = firstMaterialize(mapOf(ours to webp(seed = 9)))

        // What a merge hands over: the peer's whole bundle-entry contribution, which since M8c
        // includes its images. The peer has one we do not, and a copy of one we do.
        val after = store.materialize(
            note = note,
            pages = pagesOf(note),
            extraCarried = mapOf(theirs to webp(seed = 4), ours to webp(seed = 9)),
        )

        val assets = assertNotNull(after.reader).assets()
        // Both sides. Local-wins would keep only `ours`, and the peer's picture would be a blank
        // rectangle on this device forever — with every op present and nothing reported.
        assertEquals(setOf(ours, theirs), assets.keys, "the union dropped a side")
        assertContentEquals(webp(seed = 9), assets.getValue(ours))
        assertContentEquals(webp(seed = 4), assets.getValue(theirs))
    }

    @Test
    fun `a peers unknown entry is still carried while its assets are not`() = runTest {
        // The two families take different routes out of one map, and both have to arrive. The
        // carried half is SPEC §5.8's rule and predates M8c; the assets half is the new one.
        val note = firstMaterialize(emptyMap())

        val after = store.materialize(
            note = note,
            pages = pagesOf(note),
            extraCarried = mapOf(theirs to webp(seed = 4), "future/thing.bin" to "later".encodeToByteArray()),
        )

        val reader = assertNotNull(after.reader)
        assertEquals(setOf(theirs), reader.assets().keys)
        assertEquals(setOf("future/thing.bin"), reader.carriedUnknowns().keys)
        // And exactly one entry per name in the file that was actually written.
        val names = entryNamesInCentralDirectory(fs.readBytes(NoteLayout.bundle(after.document.id)))
        assertEquals(names.size, names.toSet().size, "a name appears twice in the materialized bundle: $names")
    }

    // -----------------------------------------------------------------------

    /** A note on disk whose bundle already holds [assets] — i.e. the state M8c's writer produces. */
    private suspend fun firstMaterialize(assets: Map<String, ByteArray>): OpenNote {
        val note = store.create("Diagram", "device-a")
        return store.materialize(note, pagesOf(note), extraCarried = assets)
    }

    private suspend fun pagesOf(note: OpenNote): Map<PageId, PageContents> = note.document.livePages.associate { ref ->
        val loaded = store.loadPage(note, ref.id)
        ref.id to PageContents(PageCanvas.fixed(loaded.size), loaded.background, loaded.ops)
    }

    /**
     * Every entry name in the ZIP's central directory, **duplicates included**.
     *
     * `Zip.Directory` cannot answer this: its index is a `Map` keyed by name, so a duplicate
     * silently collapses into whichever entry the central directory listed last — which is the
     * exact failure being tested for. Reading the container by hand is the *"verify output with a
     * reader that is not the writer"* rule applied to the one question the writer's own reader
     * is structurally unable to answer.
     */
    private fun entryNamesInCentralDirectory(zip: ByteArray): List<String> {
        val eocd = zip.size - EOCD_SIZE
        val count = zip.readU16(eocd + EOCD_ENTRY_COUNT)
        var cursor = zip.readU32(eocd + EOCD_CENTRAL_OFFSET).toInt()
        return List(count) {
            val nameLength = zip.readU16(cursor + CENTRAL_NAME_LENGTH)
            val extraLength = zip.readU16(cursor + CENTRAL_EXTRA_LENGTH)
            val commentLength = zip.readU16(cursor + CENTRAL_COMMENT_LENGTH)
            val nameStart = cursor + CENTRAL_HEADER_SIZE
            cursor = nameStart + nameLength + extraLength + commentLength
            zip.decodeToString(nameStart, nameStart + nameLength)
        }
    }

    private companion object {
        const val ASSET_BYTES = 64

        // ZIP field offsets, from the same specification `Zip` implements. Named rather than
        // inline so this walker is checkable against it side by side.
        const val EOCD_SIZE = 22
        const val EOCD_ENTRY_COUNT = 10
        const val EOCD_CENTRAL_OFFSET = 16
        const val CENTRAL_HEADER_SIZE = 46
        const val CENTRAL_NAME_LENGTH = 28
        const val CENTRAL_EXTRA_LENGTH = 30
        const val CENTRAL_COMMENT_LENGTH = 32
    }
}
