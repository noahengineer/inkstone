package dev.inkstone.android.ink

import dev.inkstone.core.model.ConnectorEnd
import dev.inkstone.core.model.ConnectorRoute
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.ShapeEdit
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapeRecord
import dev.inkstone.core.model.SnapCandidate
import dev.inkstone.core.model.SnapGrid

/**
 * ⭐ ROADMAP M8b — draw a shape, hold still, and the ink becomes the shape.
 *
 * Built to [EraserController]'s and [SelectionController]'s shape, and here for the reason they
 * are: [InkSurfaceView] owns `MotionEvent` dispatch and the camera, and every feature that adds
 * four more methods to it makes the dispatch harder to read. The surface keeps two call sites —
 * arm on `ACTION_DOWN`, feed on `ACTION_MOVE` — and this owns the rest.
 *
 * ## The ink path
 *
 * [sample] is [ShapeSnapDetector.sample] plus a scheduling decision, and allocates nothing. The
 * classification and the op-building in [snap] run **once**, when the hold completes. See
 * [ShapeSnapDetector] for why the split is the whole design, and SPEC §4.8.7.
 *
 * ## Why the ops are built here and not in `NoteEditor`
 *
 * The same reason [SelectionController]'s are: ids and Lamport clocks are assigned on the UI
 * thread at the moment of the edit, because the Lamport order is the order the user did things in.
 * Routing a finished gesture up to the view model would take its clock inside a coroutine, so a
 * stroke drawn immediately afterwards could sort *before* the shape — silently, sometimes, and
 * only under load.
 *
 * @param cancelWetStroke drops the in-progress stroke. **Cancelled, never finished**: a cancelled
 *   stroke never reaches `onStrokesFinished`, so no `AddObject` for it is ever built and nothing
 *   has to be tombstoned afterwards. A stroke created and then deleted would keep its geometry in
 *   the log forever (CLAUDE.md rule 1) and would sync.
 * @param onSnapped fired after the shape is on the page, so the toolbar can say what it became and
 *   the surface can select it. Without it, the first snap is indistinguishable from the app having
 *   eaten the stroke. The boolean is whether it also **attached** — a line and a connector look
 *   identical the instant they are drawn, and they behave completely differently the moment
 *   anything moves. The [ObjectId] is what makes *"adjust it after"* reachable at all: the shape
 *   the user just drew is the one they are about to want handles on, and without its id the only
 *   route to it is a tap that has to find it again.
 */
internal class ShapeSnapController(
    private val store: StrokeStore,
    private val brushes: InkBrushFactory,
    private val cancelWetStroke: () -> Unit,
    private val onSnapped: (ObjectId, ShapeSnapResult) -> Unit,
) {

    private val detector = ShapeSnapDetector()

    /** See [InkSurfaceView.shapeSnapEnabled]. */
    var enabled: Boolean = true

    /** See [InkSurfaceView.arrowHeads]. */
    var arrowHeads: Boolean = false

    /**
     * ⭐ The lattice a snapped shape lands on — see [InkSurfaceView.gridSnapEnabled].
     *
     * [SnapGrid.None] when the setting is off, which makes every `snap*` the identity, so the
     * feature genuinely costs nothing rather than costing a branch per shape. The surface keeps it
     * in step with the paper: the grid a page snaps to is the one that page is drawing.
     */
    var grid: SnapGrid = SnapGrid.None

    val isArmed: Boolean get() = detector.isArmed

    fun begin(pageX: Float, pageY: Float, eventTimeMillis: Long) {
        if (enabled) detector.begin(pageX, pageY, eventTimeMillis) else detector.cancel()
    }

    fun cancel() {
        detector.cancel()
    }

    /** @return [ShapeSnapDetector.FIRE_NOW], [ShapeSnapDetector.NOT_HOLDING], or millis to wait. */
    fun sample(pageX: Float, pageY: Float, eventTimeMillis: Long): Long = detector.sample(pageX, pageY, eventTimeMillis)

    /** The timer's check, for a pen so still the digitiser stopped reporting. */
    fun holdElapsed(nowMillis: Long): Boolean = detector.holdElapsed(nowMillis)

    /**
     * Turn the held gesture into a shape.
     *
     * @param widthPt the pen's width **in page points**. The toolbar's is in dp; converting it is
     *   the caller's job because only the surface knows the display metrics, and getting it wrong
     *   makes a snapped line a different weight from the stroke it replaced.
     * @return true when a shape was made. False leaves the ink exactly as it was, which is what a
     *   gesture that matched nothing well must do (SPEC §4.8.7).
     */
    fun snap(colorArgb: Int, widthPt: Float, bindSlopPt: Float = 0f): Boolean {
        val candidate = detector.classify() ?: return false
        val recorder = store.recorder ?: return false
        val kind = kindFor(candidate.kind)
        val placement = candidate.placement(bandPt = widthPt, asKind = kind, grid = grid)
        // ⭐ A recognised **curve** arrives as a line plus an apex, and the apex has to be converted
        // into the shape's own space before it can be stored — the recogniser only ever saw page
        // coordinates, because the shape it was proposing did not exist yet.
        val record = curved(connectorRecordFor(candidate, kind, bindSlopPt), candidate.bendLocal(placement))
        cancelWetStroke()

        // ⚠️ Hoisted out of the `create(...)` argument list, where it used to be minted inline. The
        // id was therefore unreachable the instant it was used, so nothing could select, report or
        // undo *this particular* shape — which is why the shape a user had just drawn was the one
        // object on the page they had to go hunting for.
        val id = recorder.newObjectId()
        val ops = ShapeEdit.create(
            id = id,
            placement = placement,
            z = store.allocateTopZ(1).single(),
            // The pen's own colour and width, so a snapped shape looks like the ink it replaced.
            style = ObjectStyle(colorArgb, widthPt),
            record = record,
            nextOpId = recorder::newOpId,
            nextClock = recorder::newClock,
        )
        // Through the store's one door, with `record = true` — the same one a peer's ops and an
        // undo come through. One construction path and one insertion path, so the screen cannot
        // disagree with the log about what was made.
        store.applyOps(ops, brushes, record = true)
        // Curved either way it was stored — as an apex in open space, as a route between two shapes.
        onSnapped(
            id,
            ShapeSnapResult(
                kind = kind,
                attached = record.isConnector,
                curved = record.bend != null || record.route == ConnectorRoute.CURVED,
            ),
        )
        return true
    }

    /**
     * ⭐ ROADMAP M8b's *"snap-to-other-shapes for endpoints"* — a drawn line between two boxes
     * **becomes** an attached connector.
     *
     * ## Why this is not a tool
     *
     * A modal connector tool was the obvious design and is the wrong one for this app. Drawing a
     * line from one shape to another is already the gesture people use on paper, and draw-and-hold
     * already turns it into a line; binding its ends is the same gesture noticing what it landed
     * on. A tool would add a mode to a toolbar that is already eight chips wide, to express
     * something the existing gesture says unambiguously (CLAUDE.md: *"don't gold-plate"*).
     *
     * ## What is deliberately refused
     *
     * - **Anything but a line or an arrow.** A rectangle whose corners happen to touch two boxes is
     *   not a connector, and treating it as one would make every diagram's outermost box start
     *   re-routing.
     * - **Both ends on the same shape.** Two `Auto` anchors on one box both face its own centre,
     *   so the route has no direction to resolve from. It stays a plain line, which is what was
     *   drawn.
     * - ⛔ **Only one end on a shape.** This used to bind that end, and it was the same report
     *   twice: *"if you start drawing your line on top of a different stroke it does not form a
     *   proper line and instead forms some weird image thing."* A line started inside a box — or on
     *   handwriting that happens to sit inside one — became a connector: its start was re-routed
     *   out to the box's edge, so the line no longer began where the pen did, and being a connector
     *   it offered the frame's grips (a zero-height frame, eight handles stacked into three piles)
     *   instead of its two points. A radius drawn from a circle's centre, a label arrow pointing
     *   into a diagram, a divider across a box: every one of them is a line that *starts on* a
     *   shape and is not a request to be attached to it. Joining two shapes is the one gesture that
     *   says so unambiguously.
     * - **An end inside the other end's shape.** A line from inside a frame to a box drawn within
     *   that frame lands on two different shapes, and an `Auto` anchor on the frame is the boundary
     *   point *facing the box* — out past the box on the frame's far edge, so the route would run
     *   back across the box it was meant to reach. Nested shapes are not two shapes being joined.
     * - **Neither end on a shape.** Returns [ShapeRecord.None], and `ShapeEdit.create` then emits
     *   the `AddObject` alone — so an ordinary snapped line still writes **no record at all** and
     *   costs no string in the file.
     *
     * An end can still come *unbound* later — its shape deleted, or not yet arrived from a peer —
     * and resolves to the connector's own payload endpoint (SPEC §4.8.4). Drawing never makes one.
     */
    private fun connectorRecordFor(candidate: SnapCandidate, kind: ShapeKind, slopPt: Float): ShapeRecord {
        if (kind != ShapeKind.LINE && kind != ShapeKind.ARROW) return ShapeRecord.None
        if (slopPt <= 0f) return ShapeRecord.None
        // ⚠️ The **drawn** ends, not the grid-snapped ones. Where the pen went is what says
        // which boxes the user meant to join; a snap can move an end up to half a cell, and half a
        // cell of the wrong direction is a connector bound to the box next door.
        val from = store.shapeAt(candidate.from.x, candidate.from.y, slopPt) ?: return ShapeRecord.None
        val to = store.shapeAt(candidate.to.x, candidate.to.y, slopPt) ?: return ShapeRecord.None
        val joinsTwoShapes = from != to &&
            !store.shapeEncloses(from, candidate.to.x, candidate.to.y) &&
            !store.shapeEncloses(to, candidate.from.x, candidate.from.y)
        return if (joinsTwoShapes) {
            ShapeRecord(endA = ConnectorEnd.Attached(from), endB = ConnectorEnd.Attached(to))
        } else {
            ShapeRecord.None
        }
    }

    /**
     * ⭐ The drawn arc's apex, put wherever this shape can actually use it.
     *
     * ⚠️ **A connector cannot use a bend, and storing one anyway would be dead data on disk.** Its
     * geometry is derived from what its ends are attached to and re-derived every fold (SPEC
     * §4.8.3), so `DryShape.reroute` paints straight over the payload outline — bend included. The
     * vocabulary already has the right word for *"this connector is curved"*, and it is
     * [ConnectorRoute.CURVED], which the router honours and which survives both ends moving.
     *
     * So: a curve drawn in open space keeps its exact apex, and a curve drawn **between two shapes**
     * becomes a curved connector. Neither writes a value the renderer will ignore.
     */
    private fun curved(record: ShapeRecord, bendLocal: PagePoint?): ShapeRecord = when {
        bendLocal == null -> record
        record.isConnector -> record.copy(route = ConnectorRoute.CURVED)
        else -> record.copy(bend = bendLocal)
    }

    /**
     * A recognised [ShapeKind.LINE] becomes an arrow when the arrow tool is on.
     *
     * SPEC §4.8.7: an arrowhead is a few samples at the end of a gesture and is indistinguishable
     * from a hooked pen-lift, so it is never *recognised* — it is chosen.
     */
    private fun kindFor(recognised: ShapeKind): ShapeKind =
        if (recognised == ShapeKind.LINE && arrowHeads) ShapeKind.ARROW else recognised
}

/**
 * What a held gesture turned into — see [InkSurfaceView.onShapeSnapped].
 *
 * [curved] is not cosmetic. Draw-and-hold refused every arc until this milestone, so *"it should
 * create a curve out of that"* was the report that changed it; a user who has just been told the
 * feature does not eat their curves needs to see which of the two they got, and a line and a curve
 * differ only by a register.
 */
public data class ShapeSnapResult(val kind: ShapeKind, val attached: Boolean, val curved: Boolean)
