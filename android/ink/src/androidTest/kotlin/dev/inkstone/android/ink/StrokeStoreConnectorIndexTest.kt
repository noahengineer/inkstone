package dev.inkstone.android.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkstone.core.model.ConnectorEnd
import dev.inkstone.core.model.ConnectorEndState
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.ShapeEdit
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapePlacement
import dev.inkstone.core.model.ShapeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ⭐ **The re-route pass is bounded by the shapes on the page, not by everything on it.**
 *
 * ## What this file is the guard against
 *
 * `StrokeStore.rerouteConnectors` walked the whole ink band looking for connectors, and it is
 * called from `forEachInRenderOrder` whenever `routesDirty` is set — which ordinary object
 * mutation sets, including every frame of a drag's `previewTransform`. So a page with 5,000
 * strokes and **zero connectors** paid a 5,000-element scan per drawn frame to find something it
 * could not contain, while the flag's own KDoc claimed the pass was *"O(connectors), not
 * O(objects)"*. The claim was true of the work and false of the search, which is the expensive
 * kind of comment: nothing about it is visibly wrong and it costs frames.
 *
 * [aPageOfHandwritingHasNothingToReroute] and [theReroutePassCountsOnlyTheShapesOnThePage] assert
 * the **bound** rather than a duration, because a duration measured on an emulator is a number
 * about the emulator (CLAUDE.md, *"assert a bound, never just 'it still opens'"*). The rest of the
 * file is what makes a bound safe to rely on: an index is only worth having if every path that
 * puts an object on the page or takes it off maintains it, and a missed path here is a connector
 * that silently stops following the box it is attached to.
 *
 * ## ⚠️ The one that was a real bug, not merely a slow loop
 *
 * [erasingAConnectorsTargetDetachesItOnTheNextFrame] fails on the build before this change, and
 * for a reason that has nothing to do with speed. The eraser's whole-object branch removed an
 * object with `iterator.remove()` and dropped it straight out of `byId`, so it never set
 * `routesDirty` and never recorded a tombstone — although `endEdit` emits a `DeleteObject` for it,
 * so it is a delete by every other measure. Rub a box out with the eraser and the arrow attached
 * to it kept pointing at where the box used to be, and reported that end as **attached**, until
 * some *other* edit happened to dirty the routes. Deleting the same box through `DeleteObject` was
 * always correct, which is why nothing caught it. Funnelling both through `StrokeStore.tombstone`
 * is what closes it, and is why this file drives deletion through both doors.
 *
 * ## Instrumented, and why there is no JVM half
 *
 * `DryShape` builds an `android.graphics.Path` in its constructor and `:android:ink` does not set
 * `unitTests.isReturnDefaultValues`, so a JVM test cannot construct one at all — it dies on the
 * framework stub before reaching an assertion. The strokes here are hand-built with no orientation
 * and no tilt, which keeps every expected coordinate checkable by hand (see
 * [StrokeStoreObjectsTest]'s KDoc).
 */
@RunWith(AndroidJUnit4::class)
class StrokeStoreConnectorIndexTest {

    private lateinit var factory: InkBrushFactory
    private lateinit var store: StrokeStore

    private var ids = 0
    private var clocks = 0L

    /** The ops each fixture shape arrived on, so a test can re-add it the way an undo does. */
    private val addedBy = HashMap<ObjectId, List<PageOp>>()

    @Before
    fun setUp() {
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        factory = InkBrushFactory(metrics.density, metrics.densityDpi / POINTS_PER_INCH)
        store = StrokeStore()
        store.recorder = PageOpRecorder(
            nextObjectId = { ObjectId("obj-${ids++}") },
            nextOpId = { "op-${ids++}" },
            nextClock = { LamportClock(clocks++, "test-device") },
        )
    }

    /** See [StrokeStoreObjectsTest]'s copy: JUnit's `assertNotNull` cannot be used as an expression. */
    private fun <T : Any> required(value: T?, what: String): T = requireNotNull(value) { "$what was null" }

    /**
     * One drawn frame.
     *
     * ⚠️ **Every routing assertion in this file has to go through here**, because a re-route is
     * deliberately lazy: `routesDirty` is a promise to recompute at the next draw, so reading
     * `DryShape.routing` without drawing measures the previous frame and would pass either way.
     */
    private fun draw() = store.forEachInRenderOrder(null) { }

    private fun connector(id: ObjectId) = required(store.shapeOf(id), "the connector $id")

    // --- Fixtures, through the doors production uses ---------------------------

    /** A horizontal pen stroke from ([x], [y]). No orientation, no tilt — see the class KDoc. */
    private fun line(x: Float, y: Float): Stroke {
        val batch = MutableStrokeInputBatch()
        for (sample in 0 until SAMPLES) {
            val t = sample.toFloat() / (SAMPLES - 1)
            batch.add(
                type = InputToolType.STYLUS,
                x = x + LINE_LENGTH_PT * t,
                y = y,
                elapsedTimeMillis = (t * STROKE_MILLIS).toLong(),
            )
        }
        return Stroke(factory.brushFor(PenConfig(InkBrushKind.FOUNTAIN, BLACK, PEN_WIDTH_DP)), batch)
    }

    private fun addLine(x: Float, y: Float) {
        store.beginEdit()
        store.add(line(x, y), InkBrushKind.FOUNTAIN)
        store.endEdit()
    }

    /**
     * A shape through the door every path but draw-and-hold uses — `ShapeEdit.create` applied as
     * ops, which is how a page load, an undo's re-add and a peer's edit all arrive.
     *
     * Deliberately **not** `StrokeStore.addShape`, which is draw-and-hold's own render half and
     * skips `applyOps` entirely: a fixture that only exercises the one path a gesture takes says
     * nothing about the four that a note's shapes actually arrive by (CLAUDE.md, *"distrust a
     * fixture that is more generous than production"*).
     */
    private fun createShape(placement: ShapePlacement, record: ShapeRecord): ObjectId {
        val recorder = required(store.recorder, "the recorder")
        val id = recorder.newObjectId()
        val ops = ShapeEdit.create(
            id = id,
            placement = placement,
            z = store.allocateTopZ(1).single(),
            style = ObjectStyle(SHAPE_COLOR, SHAPE_WIDTH_PT),
            record = record,
            nextOpId = recorder::newOpId,
            nextClock = recorder::newClock,
        )
        addedBy[id] = ops
        store.beginEdit()
        store.applyOps(ops, factory, record = true)
        store.endEdit()
        return id
    }

    private fun addRectangle(left: Float, top: Float, right: Float, bottom: Float): ObjectId =
        createShape(ShapeEdit.place(ShapeKind.RECTANGLE, PageRect(left, top, right, bottom)), ShapeRecord.None)

    /**
     * A connector authored **well away from the route it will take** — at [CONNECTOR_Y], while two
     * attached ends put it at [BOX_MID_Y].
     *
     * That separation is the whole reason the geometry assertions can say anything: an end that
     * fails to resolve falls back to the connector's own authored endpoint (SPEC §4.8.4), and if
     * the fixture authored it along the line between the two boxes, the attached point and the
     * detached fallback would be *the same coordinates* and a detach would be invisible.
     */
    private fun addConnector(endA: ObjectId?, endB: ObjectId?): ObjectId = createShape(
        ShapeEdit.placeSegment(CONNECTOR_LEFT_X, CONNECTOR_Y, CONNECTOR_RIGHT_X, CONNECTOR_Y, bandPt = BAND_PT),
        ShapeRecord(
            endA = endA?.let { ConnectorEnd.Attached(it) },
            endB = endB?.let { ConnectorEnd.Attached(it) },
        ),
    )

    private fun boxA() = addRectangle(BOX_A_LEFT, BOX_TOP, BOX_A_RIGHT, BOX_BOTTOM)

    private fun boxB() = addRectangle(BOX_B_LEFT, BOX_TOP, BOX_B_RIGHT, BOX_BOTTOM)

    /**
     * One whole-object eraser stroke across box A's **left** edge.
     *
     * Deliberately the far edge: the connector leaves box A on the right, so this sweep cannot
     * reach it and a `touched` count of 1 is what proves the eraser took the box and nothing else.
     */
    private fun eraseBoxALeftEdge(): Int {
        store.beginEdit()
        val touched = store.eraseAlong(
            PagePointF(BOX_A_LEFT - ERASER_REACH_PT, BOX_MID_Y),
            PagePointF(BOX_A_LEFT + ERASER_REACH_PT, BOX_MID_Y),
            ERASER_RADIUS_PT,
            EraseMode.WHOLE_STROKE,
        )
        store.endEdit()
        return touched
    }

    // --- The bound ------------------------------------------------------------

    /**
     * The deliverable, stated as a number: a page of handwriting has **nothing** to re-route, and
     * a drawn frame with the routes dirty visits nothing at all.
     *
     * A finished stroke dirties the routes (an object arriving is a target appearing), so this is
     * the state the old scan was in for every frame of every note in the app that has no diagram
     * in it — which is most of them.
     */
    @Test
    fun aPageOfHandwritingHasNothingToReroute() {
        repeat(STROKES) { addLine(LINE_X, LINE_Y + it * LINE_GAP_PT) }

        assertEquals("a page with no shapes has something to re-route", 0, store.reroutePassSize)
        draw()
        assertEquals("a drawn frame left something to re-route", 0, store.reroutePassSize)
        assertEquals("the strokes did not reach the store", STROKES, store.size)
    }

    /** Strokes never join the pass, however many of them there are; shapes join it exactly once. */
    @Test
    fun theReroutePassCountsOnlyTheShapesOnThePage() {
        val a = boxA()
        repeat(STROKES) { addLine(LINE_X, LINE_Y + it * LINE_GAP_PT) }
        val b = boxB()
        addConnector(a, b)
        draw()

        assertEquals("the pass is not bounded by the shapes", SHAPES_ON_PAGE, store.reroutePassSize)
    }

    // --- Every door an object leaves by ---------------------------------------

    @Test
    fun deletingAShapeDropsItFromTheReroutePass() {
        val a = boxA()
        val b = boxB()
        addConnector(a, b)
        draw()

        store.applyOps(listOf(PageOp.DeleteObject(nextOpId(), clock(), a)), factory)

        assertNull("the deleted box is still in the store", store.objectOf(a))
        assertEquals("the deleted box is still in the re-route pass", SHAPES_ON_PAGE - 1, store.reroutePassSize)
    }

    @Test
    fun erasingAShapeWholeDropsItFromTheReroutePass() {
        val a = boxA()
        val b = boxB()
        addConnector(a, b)
        draw()

        assertEquals("the eraser did not take the box, or took something else too", 1, eraseBoxALeftEdge())

        assertNull("the erased box is still in the store", store.objectOf(a))
        assertEquals("the erased box is still in the re-route pass", SHAPES_ON_PAGE - 1, store.reroutePassSize)
    }

    @Test
    fun clearingThePageEmptiesTheReroutePass() {
        val a = boxA()
        addConnector(a, null)
        draw()

        store.clear()

        assertEquals("clear left shapes in the re-route pass", 0, store.reroutePassSize)
    }

    /**
     * ⚠️ A page's shapes must not outlive the page. `reset` is the page turn, and a shape left
     * behind would keep re-routing against the *next* page's objects — resolving, by id, onto
     * whatever happens to share one.
     */
    @Test
    fun turningThePageEmptiesTheReroutePass() {
        val a = boxA()
        addConnector(a, null)
        draw()

        store.reset()

        assertEquals("reset left shapes in the re-route pass", 0, store.reroutePassSize)
    }

    // --- What the index is for -------------------------------------------------

    /** The baseline the two detach tests below are measured against. */
    @Test
    fun aConnectorBetweenTwoBoxesLandsOnTheirFacingEdges() {
        val a = boxA()
        val b = boxB()
        val id = addConnector(a, b)

        draw()

        val routed = required(connector(id).routing, "the route")
        assertEquals("end A is not attached", ConnectorEndState.ATTACHED, routed.a.state)
        assertEquals("end B is not attached", ConnectorEndState.ATTACHED, routed.b.state)
        assertEquals("end A is not on box A's right edge", BOX_A_RIGHT, routed.a.point.x, TOLERANCE)
        assertEquals("end A is not level with the boxes", BOX_MID_Y, routed.a.point.y, TOLERANCE)
    }

    /**
     * ⭐ **Fails on the build before this change.**
     *
     * The eraser's whole-object branch dirtied no routes and recorded no tombstone, so the next
     * drawn frame re-routed nothing: the connector stayed where it was, still calling its end
     * `ATTACHED` to an object that is no longer on the page and whose `DeleteObject` `endEdit` has
     * already emitted. Both halves are asserted — the geometry, because that is what the user sees,
     * and the state, because `DETACHED_TOMBSTONED` rather than `DETACHED_ABSENT` is what the
     * tombstone buys (SPEC §4.8.4).
     */
    @Test
    fun erasingAConnectorsTargetDetachesItOnTheNextFrame() {
        val a = boxA()
        val b = boxB()
        val id = addConnector(a, b)
        draw()

        assertEquals("the eraser did not take the box, or took something else too", 1, eraseBoxALeftEdge())
        draw()

        val routed = required(connector(id).routing, "the route")
        assertEquals(
            "the end of a connector whose target was erased is still attached",
            ConnectorEndState.DETACHED_TOMBSTONED,
            routed.a.state,
        )
        val fellBack = "the detached end did not fall back to its authored point"
        assertEquals(fellBack, CONNECTOR_LEFT_X, routed.a.point.x, TOLERANCE)
        assertEquals(fellBack, CONNECTOR_Y, routed.a.point.y, TOLERANCE)
        assertEquals("the far end detached too", ConnectorEndState.ATTACHED, routed.b.state)
    }

    /** The same, through `DeleteObject` — the door that was always correct, kept honest. */
    @Test
    fun deletingAConnectorsTargetDetachesItOnTheNextFrame() {
        val a = boxA()
        val b = boxB()
        val id = addConnector(a, b)
        draw()

        store.applyOps(listOf(PageOp.DeleteObject(nextOpId(), clock(), a)), factory)
        draw()

        val routed = required(connector(id).routing, "the route")
        assertEquals(
            "the end of a connector whose target was deleted is still attached",
            ConnectorEndState.DETACHED_TOMBSTONED,
            routed.a.state,
        )
    }

    /**
     * ⭐ Undoing the erase puts the arrow back, **with no op about the connector at all** — the
     * round trip SPEC §4.8.4 exists to make free, and the assertion that an object re-entering the
     * store re-enters the index with it.
     */
    @Test
    fun undoingTheEraseReattachesTheConnector() {
        val a = boxA()
        val b = boxB()
        val id = addConnector(a, b)
        draw()
        eraseBoxALeftEdge()
        draw()

        // An undo of a delete is the object's `AddObject` again (SPEC §4.2), which is what
        // `Undo.inverseOf` produces; the op id differs and this layer does not read one.
        store.applyOps(required(addedBy[a], "box A's ops"), factory)
        draw()

        val routed = required(connector(id).routing, "the route")
        assertEquals("the connector did not re-attach", ConnectorEndState.ATTACHED, routed.a.state)
        assertEquals("the re-attached end is not back on box A's edge", BOX_A_RIGHT, routed.a.point.x, TOLERANCE)
        assertEquals("the restored box did not rejoin the re-route pass", SHAPES_ON_PAGE, store.reroutePassSize)
    }

    /**
     * ⚠️ **A shape becomes a connector by register write, not at birth**, so an index keyed on
     * *"is a connector"* would have to be refreshed by every writer of `SetTextContent` — and the
     * writer that forgets is an arrow that never follows anything, with nothing on screen or in a
     * log to say so. The index is keyed on *"is a shape"*, which cannot change while the object is
     * on the page, and this is the assertion that holds the choice in place.
     *
     * This passes on the build before the change too, and is here for that reason: it is the guard
     * on the *implementation*, not the demonstration of the bug.
     */
    @Test
    fun aPlainShapeThatBecomesAConnectorLaterStillRoutes() {
        val a = boxA()
        val id = addConnector(endA = null, endB = null)
        draw()
        assertNull("a shape with no ends routed anyway", connector(id).routing)

        val recorder = required(store.recorder, "the recorder")
        store.applyOps(
            listOf(
                ShapeEdit.writeRecord(
                    target = id,
                    record = ShapeRecord(endA = ConnectorEnd.Attached(a)),
                    nextOpId = recorder::newOpId,
                    nextClock = recorder::newClock,
                ),
            ),
            factory,
        )
        draw()

        // `required` is the assertion that the shape became a connector at all: a plain shape's
        // route stays null, which is what the check above this one just read.
        val routed = required(connector(id).routing, "the route")
        assertEquals("the new end did not attach", ConnectorEndState.ATTACHED, routed.a.state)
        assertEquals("the new end is not on box A's edge", BOX_A_RIGHT, routed.a.point.x, TOLERANCE)
    }

    private fun nextOpId(): String = "op-${ids++}"

    private fun clock() = LamportClock(clocks++, "test-device")

    private companion object {
        const val POINTS_PER_INCH = 72f
        const val SAMPLES = 24
        const val STROKE_MILLIS = 120f
        const val LINE_LENGTH_PT = 80f
        const val LINE_X = 40f
        const val LINE_Y = 600f
        const val LINE_GAP_PT = 6f
        const val STROKES = 30

        /** Two boxes and one connector — the whole population a re-route pass may visit here. */
        const val SHAPES_ON_PAGE = 3

        const val BOX_TOP = 100f
        const val BOX_BOTTOM = 200f
        const val BOX_MID_Y = (BOX_TOP + BOX_BOTTOM) / 2f
        const val BOX_A_LEFT = 100f
        const val BOX_A_RIGHT = 200f
        const val BOX_B_LEFT = 400f
        const val BOX_B_RIGHT = 500f

        /**
         * The connector's authored segment — below the boxes on purpose. See [addConnector].
         */
        const val CONNECTOR_Y = 300f
        const val CONNECTOR_LEFT_X = 210f
        const val CONNECTOR_RIGHT_X = 390f
        const val BAND_PT = 4f

        /** Enough to cross box A's left edge from outside it, and nowhere near anything else. */
        const val ERASER_REACH_PT = 15f
        const val ERASER_RADIUS_PT = 4f

        const val SHAPE_COLOR = 0xFF2266AA.toInt()
        const val SHAPE_WIDTH_PT = 6f
        const val BLACK = 0xFF1A1A1A.toInt()
        const val PEN_WIDTH_DP = 3f
        const val TOLERANCE = 0.01f
    }
}
