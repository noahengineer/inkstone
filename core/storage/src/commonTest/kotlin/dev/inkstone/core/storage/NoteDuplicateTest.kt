package dev.inkstone.core.storage

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageTemplate
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.foldPage
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ROADMAP M3 — duplicate.
 *
 * The two things a file copy would get wrong, as tests: **new object ids** (SPEC §5.6's
 * argument — two objects with one identity converge onto one another's geometry) and **the
 * original untouched**.
 */
class NoteDuplicateTest {

    private val fs = FakeFileSystem()
    private val clock = TestClock()
    private val ids = IdFactory(clock, Random(7))
    private val store = NoteStore(fs, clock, ids)

    private var counter = 0L

    private fun addOp(id: String, bytes: Int = 64) = PageOp.AddObject(
        opId = ids.newOpId(),
        clock = LamportClock(++counter, "device-a"),
        obj = StrokeObject(ObjectId(id), StrokeGeometry(ByteArray(bytes) { (it + id.length).toByte() })),
    )

    private suspend fun sourceNote(): dev.inkstone.core.model.DocumentId {
        val note = store.create("Lecture", "device-a", pageCount = 2)
        val session = NoteSession.open(store, ids, clock, note, "device-a")
        val pages = note.document.livePages
        session.apply(
            pages[0].id,
            listOf(
                PageOp.SetPageMeta(
                    session.nextOpId(),
                    session.nextClock(),
                    background = Background.Template(PageTemplate.CORNELL),
                ),
                addOp("obj-1"),
                addOp("obj-2"),
            ),
        )
        session.apply(pages[1].id, listOf(addOp("obj-3")))
        session.close(NoteSyncState.LOCAL_ONLY)
        return note.document.id
    }

    @Test
    fun `a duplicate has the same picture with different object identities`() = runTest {
        val sourceId = sourceNote()

        val copy = store.duplicateNote(sourceId, "Lecture copy", "device-a", ids, clock)
        assertNotNull(copy)

        assertEquals("Lecture copy", copy.title)
        assertEquals(2, copy.livePages.size)

        val source = assertNotNull(store.open(sourceId))
        val copied = assertNotNull(store.open(copy.id))

        val sourceObjects = fold(source, source.document.livePages[0].id)
        val copiedObjects = fold(copied, copied.document.livePages[0].id)

        // Same geometry, in the same order.
        assertContentEquals(
            sourceObjects.objects.map { (it as StrokeObject).geometry.bytes.toList() },
            copiedObjects.objects.map { (it as StrokeObject).geometry.bytes.toList() },
        )
        // Page metadata is carried too: a duplicate of a Cornell page that comes back blank
        // is the bug the SetPageMeta op in `duplicateNote` exists to prevent.
        assertEquals(Background.Template(PageTemplate.CORNELL), copiedObjects.background)

        // ...and not one shared identity between them.
        val sourceIds = sourceObjects.objects.mapTo(HashSet()) { it.id }
        val copiedIds = copiedObjects.objects.mapTo(HashSet()) { it.id }
        assertEquals(2, copiedIds.size)
        assertTrue(
            (sourceIds intersect copiedIds).isEmpty(),
            "the copy reused an ObjectId — the fold would then apply one geometry to both notes",
        )
    }

    @Test
    fun `the original is not modified`() = runTest {
        val sourceId = sourceNote()
        val before = assertNotNull(store.open(sourceId))
        val revBefore = before.document.localRev
        val objectsBefore = fold(before, before.document.livePages[0].id).objects.map { it.id.value }

        store.duplicateNote(sourceId, "copy", "device-a", ids, clock)

        val after = assertNotNull(store.open(sourceId))
        assertEquals(revBefore, after.document.localRev)
        assertEquals(objectsBefore, fold(after, after.document.livePages[0].id).objects.map { it.id.value })
    }

    @Test
    fun `duplicating a note that does not exist returns null`() = runTest {
        assertEquals(null, store.duplicateNote(dev.inkstone.core.model.DocumentId("nope"), "x", "d", ids, clock))
    }

    @Test
    fun `a duplicate carries the source notes image bytes`() = runTest {
        // ⚠️ SPEC §4.9.4's third consequence. Image bytes are in `assets/` and in **no op**, so
        // folding a page and re-emitting its ops copies the `ImageObject` and not the picture —
        // and `duplicateNote`'s materialize carries forward the *copy's* own bundle, which for a
        // note created a moment earlier is empty. Get this wrong and the duplicate is an
        // ImageObject naming an entry that does not exist: a blank rectangle, defined,
        // non-crashing, and reported by nothing.
        val sourceId = sourceNote()
        val asset = Bundle.imageAsset(ObjectId("obj-picture"))
        val bytes = ByteArray(64) { (it * 3).toByte() }
        withAsset(sourceId, asset, bytes)

        val copy = assertNotNull(store.duplicateNote(sourceId, "Lecture copy", "device-a", ids, clock))

        val reader = assertNotNull(BundleReader.open(FileByteSource(fs, NoteLayout.bundle(copy.id))))
        assertContentEquals(bytes, assertNotNull(reader.assets()[asset], "the copy has no picture bytes"))
        // ⚠️ And the name is the *original's*, deliberately — an asset string survives
        // re-identification (`copiedObjectOps`). Which is what makes asset lifetime refcount-free:
        // two objects in two notes now name one entry, so nothing may ever delete one.
        assertContentEquals(
            bytes,
            assertNotNull(
                BundleReader.open(FileByteSource(fs, NoteLayout.bundle(sourceId)))?.assets()?.get(asset),
                "the original lost its picture",
            ),
        )
    }

    /** Put an asset into [id]'s bundle the way M8c's image insert will — a rewrite that adds bytes. */
    private suspend fun withAsset(id: dev.inkstone.core.model.DocumentId, asset: String, bytes: ByteArray) {
        val note = assertNotNull(store.open(id))
        val pages = note.document.livePages.associate { ref ->
            val loaded = store.loadPage(note, ref.id)
            ref.id to PageContents(loaded.size, loaded.background, loaded.ops)
        }
        store.materialize(note, pages, extraCarried = mapOf(asset to bytes))
    }

    @Test
    fun `a duplicate is not stale — its bundle is written before it is returned`() = runTest {
        // `duplicateNote` closes the session, which materializes. If it did not, the copy
        // would exist only as a journal and SPEC §5.1's staleness assertion would fire the
        // first time it was opened and closed.
        val sourceId = sourceNote()
        val copy = assertNotNull(store.duplicateNote(sourceId, "copy", "device-a", ids, clock))

        store.assertNotStale(copy.id)
    }

    private suspend fun fold(note: OpenNote, pageId: dev.inkstone.core.model.PageId) =
        store.loadPage(note, pageId).let { loaded ->
            foldPage(pageId, loaded.ops, defaultSize = loaded.size, defaultBackground = loaded.background)
        }
}
