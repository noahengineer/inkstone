package dev.inkstone.core.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ROADMAP M8b — the connector record and the routing derived from it.
 *
 * ⚠️ **Test names here may contain no commas and no apostrophes.** These compile for
 * `iosSimulatorArm64`, where Kotlin/Native rejects a comma in a backticked name, and the JVM
 * compiler accepts both — so neither shows up until the klib compile runs (CLAUDE.md).
 */
class ConnectorTest {

    // -----------------------------------------------------------------------
    // The record's wire form
    // -----------------------------------------------------------------------

    @Test
    fun `a record round-trips through its wire form`() {
        val record = ShapeRecord(
            fillArgb = -0x10000,
            fillOpacity = 0.5f,
            cornerRadiusPt = 8f,
            endA = ConnectorEnd.Attached(ObjectId("obj-a"), ConnectorAnchor.Pinned(0.25f, 1f)),
            endB = ConnectorEnd.Attached(ObjectId("obj-b")),
            route = ConnectorRoute.ELBOW,
        )
        assertEquals(record, ShapeRecord.decode(record.encode()))
    }

    @Test
    fun `re-encoding a decoded record produces the same bytes`() {
        // The property an autosave depends on: decoding and re-encoding a register nobody touched
        // must not rewrite it, or every open would enqueue an upload.
        val encoded = ShapeRecord(
            fillArgb = 255,
            cornerRadiusPt = 2.5f,
            endA = ConnectorEnd.Free(12.125f, -3f),
            route = ConnectorRoute.CURVED,
        ).encode()
        assertEquals(encoded, ShapeRecord.decode(encoded)?.encode())
    }

    @Test
    fun `anything that is not a record decodes to null`() {
        // Fail closed: a register holding a stranger's text must never become geometry.
        assertNull(ShapeRecord.decode(null))
        assertNull(ShapeRecord.decode(""))
        assertNull(ShapeRecord.decode("hello world"))
        assertNull(ShapeRecord.decode("is2;f=1"))
    }

    @Test
    fun `unknown keys survive a round-trip`() {
        // SPEC §5.8's forward-compatibility rule one level below where it is normally checked:
        // an M8c key inside the record must not be destroyed by an M8b device re-saving the note.
        val decoded = assertNotNull(ShapeRecord.decode("is1;f=7;zz=future;rt=e"))
        assertEquals(mapOf("zz" to "future"), decoded.extras)
        assertTrue(decoded.encode().contains("zz=future"))
    }

    @Test
    fun `a malformed end leaves the rest of the record intact`() {
        val decoded = assertNotNull(ShapeRecord.decode("is1;f=42;a=o;b=p:1:2"))
        assertEquals(42, decoded.fillArgb)
        assertNull(decoded.endA)
        assertEquals(ConnectorEnd.Free(1f, 2f), decoded.endB)
    }

    @Test
    fun `a shape is a connector only when it has an end`() {
        assertTrue(!ShapeRecord(fillArgb = 1).isConnector)
        assertTrue(ShapeRecord(endB = ConnectorEnd.Free(0f, 0f)).isConnector)
    }

    // -----------------------------------------------------------------------
    // Routing — the part that is derived and never written
    // -----------------------------------------------------------------------

    @Test
    fun `a connector tracks a translate of the shape it is attached to`() {
        val page = pageWith(boxAt(0f, 0f), boxAt(300f, 0f))
        val before = assertNotNull(routeOf(page))

        val moved = page.after(PageOp.SetTransform(id(), clock(3), BOX_B, Matrix3.translate(300f, 400f)))
        val after = assertNotNull(routeOf(moved))

        assertTrue(after.a.isAttached && after.b.isAttached, "both ends stay attached")
        assertTrue(after.b.point.y > before.b.point.y + 300f, "the end on the shape that moved")
        // ⭐ And the end on the shape that did *not* move has moved too, because an Auto anchor
        // faces its partner: A's endpoint slides from its right edge to its bottom edge. That is
        // the feature, not a leak — the connector still leaves A pointing at where B now is.
        assertTrue(after.a.point != before.a.point, "the auto anchor re-faces")
        assertTrue(after.a.point.x in 0f..BOX_SIZE && after.a.point.y in 0f..BOX_SIZE, "still on A")
    }

    @Test
    fun `a connector tracks a resize`() {
        // ⭐ The question a translate cannot answer: an anchor is a point in the target's own box,
        // so scaling the target moves the anchor without anything being written down.
        val page = pageWith(boxAt(0f, 0f), boxAt(300f, 0f))
        val before = assertNotNull(routeOf(page))

        // Double B's width about its own origin. Its left edge stays put; the anchor A faces
        // does not move, but a pinned anchor on its right edge would.
        val wider = page.after(
            PageOp.SetTransform(id(), clock(3), BOX_B, Matrix3.translate(300f, 0f) * Matrix3.scale(2f, 1f)),
        )
        val after = assertNotNull(routeOf(wider))
        assertEquals(before.b.point, after.b.point, "auto anchor still on the near edge")

        val pinned = wider.after(
            PageOp.SetTextContent(
                id(),
                clock(4),
                CONNECTOR,
                ShapeRecord(
                    endA = ConnectorEnd.Attached(BOX_A),
                    endB = ConnectorEnd.Attached(BOX_B, ConnectorAnchor.Pinned(1f, 0.5f)),
                ).encode(),
            ),
        )
        val pinnedRoute = assertNotNull(routeOf(pinned))
        // B's box is 100 wide at scale 2 → its right edge is at 300 + 200 = 500.
        assertClose(500f, pinnedRoute.b.point.x)
    }

    @Test
    fun `a connector tracks a rotation`() {
        // A quarter-turn about B's own centre. A pinned anchor on the top edge must end up on
        // what is now the right-hand side — which only happens if the anchor is carried by the
        // target's matrix rather than recomputed from an axis-aligned box.
        val page = pageWith(boxAt(0f, 0f), boxAt(300f, 0f)).after(
            PageOp.SetTextContent(
                id(),
                clock(3),
                CONNECTOR,
                ShapeRecord(
                    endA = ConnectorEnd.Attached(BOX_A),
                    endB = ConnectorEnd.Attached(BOX_B, ConnectorAnchor.Pinned(0.5f, 0f)),
                ).encode(),
            ),
        )
        val before = assertNotNull(routeOf(page))
        // Top-centre of a 100×100 box at (300, 0) is (350, 0).
        assertClose(350f, before.b.point.x)
        assertClose(0f, before.b.point.y)

        val quarterTurn = SelectionGesture.rotate(350f, 50f, (kotlin.math.PI / 2).toFloat())
        val rotated = page.after(
            PageOp.SetTransform(id(), clock(4), BOX_B, quarterTurn * Matrix3.translate(300f, 0f)),
        )
        val after = assertNotNull(routeOf(rotated))
        // y-down page space, so a positive quarter-turn takes (350, 0) to (400, 50).
        assertClose(400f, after.b.point.x)
        assertClose(50f, after.b.point.y)
    }

    @Test
    fun `nothing is written when the shape a connector touches moves`() {
        // ROADMAP M8b: "a reroute writes no op". The route above changed; the log did not.
        val page = pageWith(boxAt(0f, 0f), boxAt(300f, 0f))
        val recordBefore = page.fold.recordOf(CONNECTOR)
        val moved = page.after(PageOp.SetTransform(id(), clock(3), BOX_B, Matrix3.translate(9f, 9f)))
        assertEquals(recordBefore, moved.fold.recordOf(CONNECTOR))
        assertEquals(
            page.ops.count { it is PageOp.SetTextContent },
            moved.ops.count { it is PageOp.SetTextContent },
            "moving a shape wrote no record",
        )
    }

    @Test
    fun `an end whose target is tombstoned falls back to the connector own geometry`() {
        val page = pageWith(boxAt(0f, 0f), boxAt(300f, 0f))
        val deleted = page.after(PageOp.DeleteObject(id(), clock(3), BOX_B))
        val route = assertNotNull(routeOf(deleted))

        assertEquals(ConnectorEndState.DETACHED_TOMBSTONED, route.b.state)
        assertTrue(route.a.isAttached, "the other end is untouched")
        // The connector's own payload endpoint — what an M8a-era build draws, always.
        assertClose(CONNECTOR_X1, route.b.point.x)
    }

    @Test
    fun `an end whose target has not arrived behaves exactly like a tombstoned one`() {
        // ⭐ The reason detachment is derived. If it were stored the two would diverge: an object
        // still in flight would be written off permanently by whichever device noticed first.
        val page = pageWith(boxAt(0f, 0f), absent = true)
        val route = assertNotNull(routeOf(page))
        assertEquals(ConnectorEndState.DETACHED_ABSENT, route.b.state)
        assertClose(CONNECTOR_X1, route.b.point.x)
    }

    @Test
    fun `an end re-attaches by itself when its target arrives`() {
        val page = pageWith(boxAt(0f, 0f), absent = true)
        assertEquals(ConnectorEndState.DETACHED_ABSENT, assertNotNull(routeOf(page)).b.state)

        val arrived = page.after(PageOp.AddObject(id(), clock(9), box(BOX_B, 300f, 0f)))
        assertEquals(ConnectorEndState.ATTACHED, assertNotNull(routeOf(arrived)).b.state)
    }

    @Test
    fun `an end re-attaches when a delete is undone`() {
        val page = pageWith(boxAt(0f, 0f), boxAt(300f, 0f))
        val deleted = page.after(PageOp.DeleteObject(id(), clock(3), BOX_B))
        val restored = deleted.after(PageOp.AddObject(id(), clock(4), box(BOX_B, 300f, 0f)))
        assertEquals(ConnectorEndState.ATTACHED, assertNotNull(routeOf(restored)).b.state)
    }

    @Test
    fun `attaching to a stroke is refused rather than guessed at`() {
        // `:core:model` cannot measure an opaque geometry blob (SPEC §5.4), so there is no box to
        // anchor against. Nothing in M8b can create this binding; the branch exists so that a
        // future build sending one degrades instead of inventing a point.
        val strokeId = ObjectId("obj-stroke")
        val page = foldOf(
            PageOp.AddObject(id(), clock(1), StrokeObject(strokeId, StrokeGeometry(ByteArray(4)))),
            connectorOp(),
            recordOp(ShapeRecord(endB = ConnectorEnd.Attached(strokeId))),
        )
        assertEquals(ConnectorEndState.DETACHED_UNANCHORABLE, assertNotNull(routeOf(page)).b.state)
    }

    @Test
    fun `a plain shape routes to null`() {
        val page = foldOf(PageOp.AddObject(id(), clock(1), box(BOX_A, 0f, 0f)))
        val shape = assertNotNull(page.find(BOX_A) as? ShapeObject)
        assertNull(ConnectorRouting.route(shape, page.recordOf(BOX_A), page))
    }

    @Test
    fun `an elbow turns on the axis with the larger separation`() {
        val far = routeWith(ConnectorRoute.ELBOW, boxAt(0f, 0f), boxAt(600f, 40f))
        val horizontalTurns = far.path.segments.filterIsInstance<ShapeSegment.LineTo>()
        assertEquals(3, horizontalTurns.size, "three segments")
        assertClose(far.a.point.y, horizontalTurns[0].y, "leaves horizontally")

        val tall = routeWith(ConnectorRoute.ELBOW, boxAt(0f, 0f), boxAt(40f, 600f))
        val verticalTurns = tall.path.segments.filterIsInstance<ShapeSegment.LineTo>()
        assertClose(tall.a.point.x, verticalTurns[0].x, "leaves vertically")
    }

    @Test
    fun `a curved route is one cubic that starts and ends on its anchors`() {
        val route = routeWith(ConnectorRoute.CURVED, boxAt(0f, 0f), boxAt(400f, 0f))
        val cubic = assertNotNull(route.path.segments.filterIsInstance<ShapeSegment.CubicTo>().singleOrNull())
        assertClose(route.b.point.x, cubic.x)
        assertClose(route.b.point.y, cubic.y)
    }

    @Test
    fun `routing is a pure function of the fold`() {
        // The convergence argument: two devices holding the same ops hold the same route, with
        // nothing about the route having been exchanged.
        val ops = listOf(connectorOp(), recordOp(defaultRecord()), boxAt(0f, 0f), boxAt(300f, 0f))
        val one = routeOf(foldOf(*ops.toTypedArray()))
        val other = routeOf(foldOf(*ops.reversed().toTypedArray()))
        assertEquals(one?.a?.point, other?.a?.point)
        assertEquals(one?.b?.point, other?.b?.point)
    }

    // -----------------------------------------------------------------------
    // Id remapping — what a note or page duplicate needs
    // -----------------------------------------------------------------------

    @Test
    fun `remapping rewrites both attached ends and leaves free ones alone`() {
        val record = ShapeRecord(
            endA = ConnectorEnd.Attached(BOX_A, ConnectorAnchor.Pinned(0f, 1f)),
            endB = ConnectorEnd.Free(5f, 6f),
        )
        assertEquals(listOf(BOX_A), record.targets)
        val remapped = record.remapTargets { if (it == BOX_A) ObjectId("copy-a") else null }
        assertEquals(ConnectorEnd.Attached(ObjectId("copy-a"), ConnectorAnchor.Pinned(0f, 1f)), remapped.endA)
        assertEquals(ConnectorEnd.Free(5f, 6f), remapped.endB)
    }

    @Test
    fun `connectorsTouching finds the connectors a moved shape affects`() {
        val page = pageWith(boxAt(0f, 0f), boxAt(300f, 0f)).fold
        assertEquals(listOf(CONNECTOR), page.connectorsTouching(setOf(BOX_B)).map { it.id })
        assertEquals(emptyList(), page.connectorsTouching(setOf(ObjectId("nobody"))).map { it.id })
    }

    // -----------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------

    private var counter = 0

    private fun id(): String = "op-${counter++}"

    private fun clock(counter: Long) = LamportClock(counter, "device-a")

    private fun box(id: ObjectId, x: Float, y: Float) = ShapeObject(
        id = id,
        shape = ShapeKind.RECTANGLE,
        widthPt = BOX_SIZE,
        heightPt = BOX_SIZE,
        transform = Matrix3.translate(x, y),
        z = FractionalKey("m"),
    )

    private fun boxAt(x: Float, y: Float): PageOp =
        PageOp.AddObject(id(), clock(1), box(if (x == 0f) BOX_A else BOX_B, x, y))

    /** The connector's own payload: a horizontal segment, which is the fallback geometry. */
    private fun connectorOp(): PageOp = PageOp.AddObject(
        id(),
        clock(2),
        ShapeObject(
            id = CONNECTOR,
            shape = ShapeKind.ARROW,
            widthPt = CONNECTOR_X1,
            heightPt = 1f,
            transform = Matrix3.translate(0f, 50f) * Matrix3.translate(0f, -0.5f),
            z = FractionalKey("n"),
        ),
    )

    private fun defaultRecord() = ShapeRecord(
        endA = ConnectorEnd.Attached(BOX_A),
        endB = ConnectorEnd.Attached(BOX_B),
    )

    private fun recordOp(record: ShapeRecord): PageOp =
        PageOp.SetTextContent(id(), clock(2), CONNECTOR, record.encode())

    /**
     * A page as its **log**, not as a fold.
     *
     * [after] re-folds from the ops rather than patching the previous fold — CLAUDE.md rule 9 in
     * a test harness, and the only way an assertion about arrival order means anything.
     */
    private class Page(val ops: List<PageOp>) {
        val fold: FoldedPage get() = foldPage(PageId("page"), ops)

        fun after(op: PageOp): Page = Page(ops + op)
    }

    private fun foldOf(vararg ops: PageOp): FoldedPage = Page(ops.toList()).fold

    private fun pageWith(a: PageOp, b: PageOp? = null, absent: Boolean = false): Page = Page(
        buildList {
            add(a)
            if (!absent && b != null) add(b)
            add(connectorOp())
            add(recordOp(defaultRecord()))
        },
    )

    private fun routeOf(page: Page): ConnectorPath? = routeOf(page.fold)

    private fun routeOf(page: FoldedPage): ConnectorPath? {
        val connector = page.find(CONNECTOR) as? ShapeObject ?: return null
        return ConnectorRouting.route(connector, page.recordOf(CONNECTOR), page)
    }

    private fun routeWith(route: ConnectorRoute, a: PageOp, b: PageOp): ConnectorPath {
        val page = Page(listOf(a, b, connectorOp(), recordOp(defaultRecord().copy(route = route)))).fold
        val connector = assertNotNull(page.find(CONNECTOR) as? ShapeObject)
        return assertNotNull(ConnectorRouting.route(connector, page.recordOf(CONNECTOR), page))
    }

    private fun assertClose(expected: Float, actual: Float, message: String = "") {
        assertTrue(abs(expected - actual) < TOLERANCE, "$message expected $expected but was $actual")
    }

    private companion object {
        val BOX_A = ObjectId("obj-box-a")
        val BOX_B = ObjectId("obj-box-b")
        val CONNECTOR = ObjectId("obj-connector")
        const val BOX_SIZE = 100f
        const val CONNECTOR_X1 = 250f
        const val TOLERANCE = 0.01f
    }
}
