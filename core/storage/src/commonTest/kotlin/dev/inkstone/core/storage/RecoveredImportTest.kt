package dev.inkstone.core.storage

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.foldPage
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * SPEC §5.6 ⭐ — **Recovered mode replaces, and does not duplicate.**
 *
 * ROADMAP M4's acceptance criterion is *"a recovered document syncs without
 * duplicating strokes"*, which is SPEC §13.2 row 9. The merge itself is M6's, but
 * the property is decided here, at recovery time, by whether the reconstruction
 * reuses the ids the file carried. So the last test in this file does the merge by
 * hand — union the two logs, fold, count — because by the time M6 exists it is far
 * too late to find out that it does not hold.
 */
class RecoveredImportTest {

    private val clock = TestClock()
    private val ids = IdFactory(clock, Random(7))
    private val documentId = DocumentId("0192f3c4-2222-7abc-8def-0123456789ab")
    private val marker = InkstoneMarker(documentId, 1, "ab".repeat(32))

    private fun geometry(seed: Int) = StrokeGeometry(ByteArray(64) { (it + seed).toByte() })

    private fun page(vararg strokes: RecoveredStroke, id: PageId? = PageId("page-1")) = RecoveredPage(
        pageId = id,
        sortKey = FractionalKey.Initial,
        size = PageSize.Letter,
        background = Background.SourcePdfPage(0),
        strokes = strokes.toList(),
    )

    private fun rebuild(vararg pages: RecoveredPage, highestKnownCounter: Long = 0L) = RecoveredImport.rebuild(
        marker = marker,
        pages = pages.toList(),
        title = "Recovered",
        deviceId = "device-b",
        nowEpochMillis = 1_700_000_000_000L,
        highestKnownCounter = highestKnownCounter,
        ids = RecoveryIds(ids::newOpId, ids::newPageId, ids::newObjectId),
    )

    // -----------------------------------------------------------------------

    @Test
    fun `identity from NM is reused and never reminted`() {
        val kept = ObjectId("stroke-a")
        val recovered = rebuild(page(RecoveredStroke(kept, geometry(1))))

        val ops = recovered.contents.pages.values.single().ops
        val added = ops.filterIsInstance<PageOp.AddObject>()
        assertEquals(listOf(kept), added.map { it.obj.id })
        assertEquals(0, recovered.unidentifiedStrokes)
    }

    @Test
    fun `a stroke with no recoverable name gets a fresh id and is counted`() {
        val recovered = rebuild(
            page(RecoveredStroke(null, geometry(2)), RecoveredStroke(ObjectId("known"), geometry(3))),
        )

        assertEquals(1, recovered.unidentifiedStrokes)
        val added = recovered.contents.pages.values.single().ops.filterIsInstance<PageOp.AddObject>()
        assertEquals(2, added.size)
        assertTrue(added.any { it.obj.id == ObjectId("known") })
        assertTrue(added.none { it.obj.id.value.isEmpty() })
    }

    /**
     * The page-level form of the same failure. Without `/INKS_PageId` a recovered
     * page is a *new* page to a peer, and SPEC §4.3's merge rule — *"any page not
     * present in a merged order is appended at the end, never dropped"* — then
     * doubles the notebook rather than the ink.
     */
    @Test
    fun `page identity is reused when the PDF still carried it`() {
        val recovered = rebuild(page(RecoveredStroke(ObjectId("s"), geometry(4)), id = PageId("page-original")))
        assertEquals(listOf(PageId("page-original")), recovered.contents.document.pages.map { it.id })
    }

    @Test
    fun `a page with no identity still recovers under a new id`() {
        val recovered = rebuild(page(RecoveredStroke(ObjectId("s"), geometry(5)), id = null))
        val id = recovered.contents.document.pages.single().id
        assertNotEquals(PageId(""), id)
        assertTrue(id.value.isNotEmpty())
    }

    /** SPEC §5.6 point 5 — recovered state must win the next sync. */
    @Test
    fun `every recovered op sorts after anything this device knew about`() {
        val known = 4_242L
        val recovered = rebuild(page(RecoveredStroke(ObjectId("s"), geometry(6))), highestKnownCounter = known)

        val clocks = recovered.contents.pages.values.single().ops.map { it.clock.counter }
        assertTrue(clocks.all { it > known + RecoveredImport.RECOVERY_BUMP }, "recovered clocks were $clocks")
        assertEquals(recovered.highestClockCounter, clocks.max())
    }

    /**
     * The page's own size and background are ops, not implicit. A `/Rotate 90` page
     * is 792x612 in page space (SPEC §4.4 rule 3), and a recovery that forgot to
     * write that would put every recovered stroke on a portrait page.
     */
    @Test
    fun `the page's metadata is recovered as an op`() {
        val landscape = PageSize(792f, 612f)
        val recovered = rebuild(
            RecoveredPage(PageId("p"), FractionalKey.Initial, landscape, Background.SourcePdfPage(3), emptyList()),
        )
        val meta = recovered.contents.pages.values.single().ops.filterIsInstance<PageOp.SetPageMeta>().single()
        assertEquals(landscape, meta.size)
        assertEquals(Background.SourcePdfPage(3), meta.background)
    }

    // -----------------------------------------------------------------------
    // The one that matters
    // -----------------------------------------------------------------------

    /**
     * ⭐ ROADMAP M4 / SPEC §13.2 row 9 — **a recovered document syncs without
     * duplicating strokes.**
     *
     * Device A still holds the original log. Device B loses its bundle, recovers
     * from the PDF, and the two are merged the way SPEC §4.3 says: union the ops,
     * sort by clock, fold from empty. If recovery had minted new ids, this fold
     * would hold six objects instead of three and every stroke would be drawn twice
     * — permanently, on both devices, with nothing to notice it by.
     */
    @Test
    fun `a recovered log merges onto the peer's originals instead of doubling them`() {
        val originalIds = listOf(ObjectId("s-1"), ObjectId("s-2"), ObjectId("s-3"))
        val pageId = PageId("page-1")

        // Device A: the note as it always was.
        val peerOps = originalIds.mapIndexed { index, id ->
            PageOp.AddObject(
                opId = "op-$index",
                clock = LamportClock((index + 1).toLong(), "device-a"),
                obj = StrokeObject(id = id, geometry = geometry(100 + index)),
            )
        }

        // Device B: the bundle is gone; rebuild from the PDF's /Ink annotations,
        // reusing each /NM. The geometry differs — recovery is lossy — which is what
        // makes first-write-wins visible in the assertion below.
        val recovered = rebuild(
            page(*originalIds.mapIndexed { index, id -> RecoveredStroke(id, geometry(200 + index)) }.toTypedArray()),
        )
        val recoveredOps = recovered.contents.pages.getValue(pageId).ops

        val merged = foldPage(pageId, peerOps + recoveredOps)

        assertEquals(3, merged.objects.size, "the union produced ${merged.objects.size} strokes, not 3")
        assertEquals(originalIds.toSet(), merged.objects.map { it.id }.toSet())
        // First-write-wins on the payload (SPEC §4.3): the peer's original geometry —
        // the one that still has its pressure — is what survives. Recovery costs the
        // device that recovered nothing, and costs the peer nothing at all.
        for ((index, id) in originalIds.withIndex()) {
            val survivor = merged.objects.first { it.id == id } as StrokeObject
            assertEquals(geometry(100 + index), survivor.geometry)
        }
    }

    /**
     * The counter-example, spelled out. This is what the naive implementation SPEC
     * §5.6 warns about actually produces, and it is here so that a future change
     * which quietly stops reusing `/NM` fails a test that says why.
     */
    @Test
    fun `minting new ids would double every stroke — which is why it is not done`() {
        val pageId = PageId("page-1")
        val peerOps = listOf(
            PageOp.AddObject("op-0", LamportClock(1, "device-a"), StrokeObject(ObjectId("s-1"), geometry(1))),
        )
        val naive = rebuild(page(RecoveredStroke(id = null, geometry = geometry(2))))
        val naiveOps = naive.contents.pages.getValue(pageId).ops

        assertEquals(2, foldPage(pageId, peerOps + naiveOps).objects.size)
        assertEquals(1, naive.unidentifiedStrokes)
    }

    // -----------------------------------------------------------------------
    // Replacement on disk
    // -----------------------------------------------------------------------

    /** SPEC §5.6 point 3 — *"move the pre-recovery version to trash first."* */
    @Test
    fun `installing a recovery trashes what it replaces`() = runTest {
        val fs = FakeFileSystem()
        val store = NoteStore(fs, clock, ids)
        val trash = NoteTrash(fs, clock)

        val before = store.create("Before", "device-a")
        val recovered = RecoveredImport.rebuild(
            marker = InkstoneMarker(before.document.id, 1, ""),
            pages = listOf(page(RecoveredStroke(ObjectId("s"), geometry(9)))),
            title = "Recovered",
            deviceId = "device-b",
            nowEpochMillis = 1L,
            highestKnownCounter = 0L,
            ids = RecoveryIds(ids::newOpId, ids::newPageId, ids::newObjectId),
        )

        val after = store.replaceWithRecovered(trash, recovered)

        assertEquals(before.document.id, after.document.id)
        assertEquals(1, trash.entries().size, "the pre-recovery version was not trashed")
        // The undo stack described ops that no longer exist (SPEC §4.6 — it is local
        // and per device), so it goes with them.
        assertTrue(!fs.exists(NoteLayout.undo(after.document.id)))
    }
}
