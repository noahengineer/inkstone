package dev.inkstone.android.ink

import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.ShapeEdit
import dev.inkstone.core.model.ShapeKind

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
 * @param onSnapped fired after the shape is on the page, so the toolbar can say what it became.
 *   Without it, the first snap is indistinguishable from the app having eaten the stroke.
 */
internal class ShapeSnapController(
    private val store: StrokeStore,
    private val brushes: InkBrushFactory,
    private val cancelWetStroke: () -> Unit,
    private val onSnapped: (ShapeKind) -> Unit,
) {

    private val detector = ShapeSnapDetector()

    /** See [InkSurfaceView.shapeSnapEnabled]. */
    var enabled: Boolean = true

    /** See [InkSurfaceView.arrowHeads]. */
    var arrowHeads: Boolean = false

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
    fun snap(colorArgb: Int, widthPt: Float): Boolean {
        val candidate = detector.classify() ?: return false
        val recorder = store.recorder ?: return false
        cancelWetStroke()

        val ops = ShapeEdit.create(
            id = recorder.newObjectId(),
            placement = candidate.placement(bandPt = widthPt, asKind = kindFor(candidate.kind)),
            z = store.allocateTopZ(1).single(),
            // The pen's own colour and width, so a snapped shape looks like the ink it replaced.
            style = ObjectStyle(colorArgb, widthPt),
            nextOpId = recorder::newOpId,
            nextClock = recorder::newClock,
        )
        // Through the store's one door, with `record = true` — the same one a peer's ops and an
        // undo come through. One construction path and one insertion path, so the screen cannot
        // disagree with the log about what was made.
        store.applyOps(ops, brushes, record = true)
        onSnapped(kindFor(candidate.kind))
        return true
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
