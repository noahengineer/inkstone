package dev.inkstone.core.storage

import dev.inkstone.core.model.ConnectorAnchor
import dev.inkstone.core.model.ConnectorEnd
import dev.inkstone.core.model.ConnectorEndState
import dev.inkstone.core.model.ConnectorRoute
import dev.inkstone.core.model.ConnectorRouting
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.ShapeRecord
import dev.inkstone.core.model.recordOf
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ROADMAP M8b — a connector's attachment through the **storage** layer.
 *
 * `ConnectorTest` proves the routing arithmetic. These prove the thing that arithmetic cannot: that
 * the register survives every path a note's bytes take — a bundle round trip, a compaction
 * snapshot, a page duplicate and a note duplicate — because the register is the only place the
 * attachment exists, and every one of those paths rebuilds a page from something other than its
 * raw log.
 */
class ShapeRecordStorageTest {

    private val fs = FakeFileSystem()
    private val clock = TestClock()
    private val ids = IdFactory(clock, Random(11))
    private val store = NoteStore(fs, clock, ids)

    private var counter = 0L

    @Test
    fun `a connector record survives a bundle round trip`() = runTest {
        val id = diagramNote()
        val reopened = store.open(id)
        val session = NoteSession.open(store, ids, clock, assertNotNull(reopened), DEVICE)
        val page = session.page(session.document.livePages.single().id)

        val record = page.recordOf(CONNECTOR)
        assertEquals(ConnectorEnd.Attached(BOX_A), record.endA)
        assertEquals(ConnectorEnd.Attached(BOX_B, ConnectorAnchor.Pinned(0f, 0.5f)), record.endB)
        assertEquals(ConnectorRoute.ELBOW, record.route)
        assertEquals(FILL, record.fillArgb)
        assertTrue(routeOf(page).a.isAttached && routeOf(page).b.isAttached)
    }

    @Test
    fun `a compaction snapshot carries the record`() = runTest {
        // ⚠️ The snapshot re-expresses a fold as `AddObject` per object, and a shape's record is
        // the one register `materialise` does not bake onto the payload. The log is kept either
        // way (SPEC §4.7), so this is about the snapshot being a faithful cache rather than one
        // that is quietly repaired from the log on every load.
        val id = diagramNote()
        val reopened = assertNotNull(store.open(id))
        val session = NoteSession.open(store, ids, clock, reopened, DEVICE)
        val pageId = session.document.livePages.single().id
        store.writeSnapshot(id, session.page(pageId), DEVICE)

        val snapshot = assertNotNull(store.readSnapshot(id, pageId))
        assertEquals(ConnectorRoute.ELBOW, snapshot.recordOf(CONNECTOR).route)
        assertEquals(ConnectorEndState.ATTACHED, routeOf(snapshot).b.state)
    }

    @Test
    fun `duplicating a page re-targets the bindings onto the copies`() = runTest {
        // ⭐ The failure a bare `AddObject` loop produced: every id in the copy is fresh, so a
        // record carried verbatim names objects the copy does not contain and every connector in
        // a duplicated diagram resolves as detached.
        val id = diagramNote()
        val reopened = assertNotNull(store.open(id))
        val session = NoteSession.open(store, ids, clock, reopened, DEVICE)
        val sourcePage = session.document.livePages.single().id
        val copyPage = assertNotNull(session.duplicatePage(sourcePage))

        val copy = session.page(copyPage)
        assertEquals(3, copy.objects.size, "two boxes and a connector")

        val connector = assertNotNull(copy.objects.firstOrNull { it.id !in setOf(BOX_A, BOX_B, CONNECTOR) })
        val copiedRecord = copy.objects.map { copy.recordOf(it.id) }.first { it.isConnector }
        assertTrue(copiedRecord.targets.isNotEmpty(), "the record was carried at all")
        assertTrue(copiedRecord.targets.none { it == BOX_A || it == BOX_B }, "and re-targeted")
        assertTrue(copiedRecord.targets.all { target -> copy.objects.any { it.id == target } })

        val routed = assertNotNull(
            ConnectorRouting.route(assertNotNull(copy.find(connector.id) as? ShapeObject), copiedRecord, copy),
        )
        assertEquals(ConnectorEndState.ATTACHED, routed.a.state)
        assertEquals(ConnectorEndState.ATTACHED, routed.b.state)

        // And the original is untouched — the same assertion NoteDuplicateTest makes about ids.
        assertEquals(ConnectorEnd.Attached(BOX_A), session.page(sourcePage).recordOf(CONNECTOR).endA)
    }

    @Test
    fun `duplicating a note re-targets the bindings onto the copies`() = runTest {
        val id = diagramNote()
        val copy = assertNotNull(store.duplicateNote(id, "Diagram copy", DEVICE, ids, clock))

        val reopened = assertNotNull(store.open(copy.id))
        val session = NoteSession.open(store, ids, clock, reopened, DEVICE)
        val page = session.page(session.document.livePages.single().id)

        val record = page.objects.map { page.recordOf(it.id) }.first { it.isConnector }
        assertNotEquals(listOf(BOX_A), record.targets)
        assertTrue(record.targets.all { target -> page.objects.any { it.id == target } })
        assertEquals(FILL, page.objects.map { page.recordOf(it.id) }.first { it.fillArgb != null }.fillArgb)
    }

    @Test
    fun `deleting a shape detaches its connector without writing anything`() = runTest {
        val id = diagramNote()
        val reopened = assertNotNull(store.open(id))
        val session = NoteSession.open(store, ids, clock, reopened, DEVICE)
        val pageId = session.document.livePages.single().id

        val recordBefore = session.page(pageId).recordOf(CONNECTOR)
        session.apply(pageId, listOf(PageOp.DeleteObject(session.nextOpId(), session.nextClock(), BOX_B)))

        val after = session.page(pageId)
        assertEquals(recordBefore, after.recordOf(CONNECTOR), "the record is untouched")
        assertEquals(ConnectorEndState.DETACHED_TOMBSTONED, routeOf(after).b.state)
        assertTrue(routeOf(after).a.isAttached, "the other end is unaffected")
    }

    // -----------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------

    /** Two boxes joined by a filled elbow connector, closed so it is on disk. */
    private suspend fun diagramNote(): DocumentId {
        val note = store.create("Diagram", DEVICE, pageCount = 1)
        val session = NoteSession.open(store, ids, clock, note, DEVICE)
        val pageId = note.document.livePages.single().id
        session.apply(
            pageId,
            listOf(
                add(box(BOX_A, 0f)),
                add(box(BOX_B, 300f)),
                add(
                    ShapeObject(
                        id = CONNECTOR,
                        shape = ShapeKind.ARROW,
                        widthPt = 250f,
                        heightPt = 1f,
                        transform = Matrix3.translate(0f, 49.5f),
                        z = FractionalKey("n"),
                    ),
                ),
                PageOp.SetTextContent(
                    session.nextOpId(),
                    LamportClock(++counter, DEVICE),
                    CONNECTOR,
                    ShapeRecord(
                        fillArgb = FILL,
                        endA = ConnectorEnd.Attached(BOX_A),
                        endB = ConnectorEnd.Attached(BOX_B, ConnectorAnchor.Pinned(0f, 0.5f)),
                        route = ConnectorRoute.ELBOW,
                    ).encode(),
                ),
            ),
        )
        session.close(NoteSyncState.LOCAL_ONLY)
        return note.document.id
    }

    private fun box(id: ObjectId, x: Float) = ShapeObject(
        id = id,
        shape = ShapeKind.RECTANGLE,
        widthPt = 100f,
        heightPt = 100f,
        transform = Matrix3.translate(x, 0f),
        z = FractionalKey("m"),
    )

    private fun add(shape: ShapeObject) = PageOp.AddObject(ids.newOpId(), LamportClock(++counter, DEVICE), shape)

    private fun routeOf(page: FoldedPage) = assertNotNull(
        ConnectorRouting.route(
            assertNotNull(page.find(CONNECTOR) as? ShapeObject),
            page.recordOf(CONNECTOR),
            page,
        ),
    )

    private companion object {
        const val DEVICE = "device-a"
        const val FILL = -0x330000
        val BOX_A = ObjectId("obj-box-a")
        val BOX_B = ObjectId("obj-box-b")
        val CONNECTOR = ObjectId("obj-connector")
    }
}
