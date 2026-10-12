package dev.inkstone.android.ink

import android.view.MotionEvent
import dev.inkstone.core.model.Camera

/**
 * The eraser gesture (SPEC §3.5).
 *
 * Nothing here mutates a stroke's point list. In [EraseMode.PARTIAL] the stroke
 * is left exactly as drawn and the swept area is recorded beside it — see
 * [ErasedRegion], which also explains what M2's op vocabulary owes this.
 *
 * @param onErased called when a sample changed at least one stroke (`gestureEnded` false), and
 *   once more when the gesture ends (`gestureEnded` true), so the dry layer can be invalidated
 *   and the caller's stroke count updated. ⚠️ The flag exists so that work which only the *end*
 *   of a gesture can change — re-deriving an infinite page's paper — is not done per sample; see
 *   the callback in `InkSurfaceView`, which did exactly that at 240 Hz.
 */
internal class EraserController(
    private val store: StrokeStore,
    private val displayDensity: Float,
    private val onErased: (gestureEnded: Boolean) -> Unit,
) {

    /** SPEC §3.5, revised in M1 — see [EraseMode]. */
    var mode: EraseMode = EraseMode.PARTIAL

    private val from = PagePointF()
    private val to = PagePointF()

    var pointerId: Int = MotionEvent.INVALID_POINTER_ID
        private set

    /**
     * True when this erase came from a flipped pen tip or a held barrel button
     * rather than from the toolbar. A temporary eraser needs no restoring — the
     * tool is decided per event — so only a toolbar selection can trigger SPEC
     * §3.4's auto-return.
     */
    var isTemporary: Boolean = false
        private set

    val isActive: Boolean get() = pointerId != MotionEvent.INVALID_POINTER_ID

    fun begin(event: MotionEvent, pointerIndex: Int, pointerId: Int, temporary: Boolean, camera: Camera) {
        if (isActive) return
        this.pointerId = pointerId
        isTemporary = temporary
        // One undo entry per drag, however many samples it turns into.
        store.beginEdit()
        toPageSpace(event.getX(pointerIndex), event.getY(pointerIndex), camera, from)
        to.set(from)
        eraseSegment(camera)
    }

    /**
     * Erase along everything this event carries, historical samples included.
     *
     * Historical points matter here for the same reason they matter when
     * drawing: with a hand moving 30 cm/s, per-frame samples alone leave
     * un-erased gaps between them.
     */
    fun extend(event: MotionEvent, camera: Camera) {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return
        for (h in 0 until event.historySize) {
            toPageSpace(event.getHistoricalX(index, h), event.getHistoricalY(index, h), camera, to)
            eraseSegment(camera)
            from.set(to)
        }
        toPageSpace(event.getX(index), event.getY(index), camera, to)
        eraseSegment(camera)
        from.set(to)
    }

    /** @return true if this gesture was a toolbar eraser, i.e. auto-return applies. */
    fun end(): Boolean {
        val wasToolbarEraser = isActive && !isTemporary
        if (isActive) {
            // Only now, off the input path: coverage-testing every candidate is
            // far too expensive per sample (CLAUDE.md rule 7). The store has queued exactly the
            // objects this gesture bit, so this looks at those and nothing else.
            store.pruneFullyErased()
            store.endEdit()
            onErased(true)
        }
        pointerId = MotionEvent.INVALID_POINTER_ID
        isTemporary = false
        return wasToolbarEraser
    }

    private fun eraseSegment(camera: Camera) {
        // A constant size on the glass, so the eraser feels like a physical tip
        // rather than growing as the page is zoomed out.
        val radiusPageUnits = RADIUS_DP * displayDensity / camera.scale
        if (store.eraseAlong(from, to, radiusPageUnits, mode) > 0) onErased(false)
    }

    private fun toPageSpace(viewX: Float, viewY: Float, camera: Camera, into: PagePointF) {
        into.set(
            (viewX - camera.translate.x) / camera.scale,
            (viewY - camera.translate.y) / camera.scale,
        )
    }

    private companion object {
        const val RADIUS_DP = 6f
    }
}
