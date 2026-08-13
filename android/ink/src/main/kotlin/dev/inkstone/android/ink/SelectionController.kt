package dev.inkstone.android.ink

import android.view.MotionEvent
import androidx.annotation.ColorInt
import dev.inkstone.core.model.Alignment
import dev.inkstone.core.model.AlignmentGuides
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.Lasso
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.SelectedObject
import dev.inkstone.core.model.SelectionEdit
import dev.inkstone.core.model.SelectionGesture
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * ⭐ What a toolbar may do to a selection — the whole of M8a's public command surface.
 *
 * An interface rather than seven more methods on [InkSurfaceView], for two reasons. It reads as
 * what it is at the call site (`surface.selection.bringToFront()`), and it keeps the surface's own
 * API about *the surface*: a class that already owns ink capture, a camera, a page and a recording
 * hook does not also want to be the place selection commands are looked up.
 *
 * Every method here is **one gesture**, and therefore one op batch and one undo entry (SPEC §4.6).
 * None of them writes a file: the ops reach disk on the next autosave drain, exactly as a stroke's
 * do.
 */
public interface InkSelection {

    /** How many objects are selected. Zero when nothing is. */
    public val size: Int

    /** A tombstone each — the geometry stays in the log (CLAUDE.md rule 1). */
    public fun delete()

    /** Copy, offset, and select the copies. */
    public fun duplicate()

    /** Recolour, keeping each object's own width. */
    public fun recolour(@ColorInt colorArgb: Int)

    /** [widthDp] in dp, converted to page points the way a pen's width is ([InkBrushFactory]). */
    public fun setWidth(widthDp: Float)

    public fun bringToFront()

    public fun sendToBack()

    /** Drop the selection without changing anything on the page. */
    public fun clear()
}

/**
 * ⭐ ROADMAP M8a — the selection gesture: lasso, move, resize, rotate.
 *
 * Built to [EraserController]'s shape deliberately, because it is the same kind of thing and
 * the shape is load-bearing: one gesture is one [StrokeStore.beginEdit]/[StrokeStore.endEdit]
 * bracket, which is one op batch, which is one undo entry (SPEC §4.6).
 *
 * ## Where the ops come from, and why not from the editor
 *
 * Every op this class writes is built by `SelectionEdit` in `:core:model` and stamped with the
 * note's own generators, on the UI thread, at the moment of the edit. The stamping is the part
 * worth defending: `PageOpRecorder`'s KDoc says why ids and clocks cannot be assigned on a
 * worker — *"the Lamport order is the order the user did things in"* — and routing a selection
 * edit up to `NoteEditor` and back would assign its clock inside a coroutine, letting a lasso
 * move and a stroke drawn immediately afterwards swap places in the total order. Silently, and
 * only sometimes.
 *
 * The composition `gesture × current` therefore reads the current absolute matrix out of
 * [StrokeStore] rather than out of the fold. That is sound for exactly the reason the eraser is
 * — the store is not a guess, it is the fold's own state, written only by
 * [StrokeStore.applyOps], which is the single door for a load, an undo, and a peer's ops alike.
 * Erasing has relied on that since M1: `SetErasedRegion` carries the store's whole accumulated
 * region as an absolute value. If anything ever changes the log without telling the store,
 * both features break together, which is the right coupling — one of them is visible.
 *
 * ## A drag previews; only the release writes
 *
 * A move at 240 Hz must not be 240 `SetTransform` ops. So the drag calls
 * [StrokeStore.previewTransform] per frame and [commit] runs once, composing the finished
 * gesture against the transforms captured when the finger went **down**. "A resize writes the
 * resulting matrix, not a delta" (ROADMAP M8a) is that composition, and the reason it composes
 * against the start rather than against the last frame.
 */
internal class SelectionController(
    private val store: StrokeStore,
    private val brushes: InkBrushFactory,
    private val displayDensity: Float,
    private val pixelsPerPoint: Float,
    private val onChanged: () -> Unit,
) : InkSelection {

    /** What the gesture in flight is doing. */
    private enum class Mode { LASSO, MOVE, RESIZE, ROTATE }

    /** The current selection, in render order. Empty when nothing is selected. */
    var selection: List<ObjectId> = emptyList()
        private set

    /** The page-space box round [selection], or null when nothing is selected. */
    var bounds: PageRect? = null
        private set

    /** The lasso being drawn, in page space, or null. Read by the overlay. */
    var lasso: List<PagePoint>? = null
        private set

    /** ROADMAP M8a — *"dashed = centre, solid = edge"*. Non-empty only during a move. */
    var alignment: Alignment = Alignment.None
        private set

    var pointerId: Int = MotionEvent.INVALID_POINTER_ID
        private set

    val isActive: Boolean get() = pointerId != MotionEvent.INVALID_POINTER_ID

    val isEmpty: Boolean get() = selection.isEmpty()

    private var mode = Mode.LASSO
    private var pageSize: PageSize = PageSize.Letter

    /** Where the finger went down, in page space, and where it is now. */
    private val start = PagePointF()
    private val current = PagePointF()

    /**
     * The selection as it was when the finger went **down** — what [commit] composes against.
     *
     * Not re-read per frame, and that is the whole of "a resize writes the resulting matrix,
     * not a delta": the drag previews `gesture × start`, and the op written on release is
     * `gesture × start` for the same start. Composing against what is currently on screen would
     * apply the drag twice, and composing against the fold would need a round trip through the
     * editor that costs the gesture its clock ordering.
     */
    private var startSelected: List<SelectedObject> = emptyList()
    private var startBounds: PageRect? = null
    private var startHandle: SelectionHandle? = null

    /** Boxes of everything *not* selected, sampled once per gesture — the guides' targets. */
    private var guideTargets: List<PageRect> = emptyList()

    // -----------------------------------------------------------------------
    // The gesture
    // -----------------------------------------------------------------------

    fun begin(event: MotionEvent, pointerIndex: Int, pointerId: Int, camera: Camera, pageSize: PageSize) {
        if (isActive) return
        this.pointerId = pointerId
        this.pageSize = pageSize
        toPageSpace(event.getX(pointerIndex), event.getY(pointerIndex), camera, start)
        current.set(start)
        alignment = Alignment.None

        val box = bounds
        val handle = box?.let { SelectionHandle.at(start.x, start.y, it, handleReach(camera)) }
        mode = when {
            handle == SelectionHandle.ROTATE -> Mode.ROTATE
            handle != null -> Mode.RESIZE
            box != null && box.containsWithin(start.x, start.y, 0f) -> Mode.MOVE
            else -> Mode.LASSO
        }

        when (mode) {
            Mode.LASSO -> lasso = mutableListOf(PagePoint(start.x, start.y))

            else -> {
                startHandle = handle
                startBounds = box
                startSelected = store.selected(selection)
                guideTargets = if (mode == Mode.MOVE) store.pageBoundsExcluding(selection) else emptyList()
            }
        }
        onChanged()
    }

    /**
     * Extend the gesture.
     *
     * Historical samples are walked for the lasso only. A move, resize or rotate depends on
     * where the finger *is*, not on the path it took, so replaying intermediate points would be
     * work with no effect; a lasso is precisely its path, and dropping the samples between two
     * frames leaves it with corners a slow hand did not draw.
     */
    fun extend(event: MotionEvent, camera: Camera) {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return
        if (mode == Mode.LASSO) {
            for (h in 0 until event.historySize) {
                toPageSpace(event.getHistoricalX(index, h), event.getHistoricalY(index, h), camera, current)
                appendToLasso()
            }
        }
        toPageSpace(event.getX(index), event.getY(index), camera, current)
        if (mode == Mode.LASSO) {
            appendToLasso()
        } else {
            // The guides are recomputed here rather than inside `moveGesture`, because `commit`
            // calls that too and a snap must be decided once, while the finger is still down.
            if (mode == Mode.MOVE) {
                val box = startBounds
                if (box != null) {
                    val moved = box.translated(current.x - start.x, current.y - start.y)
                    alignment = AlignmentGuides.forMove(moved, guideTargets, pageSize)
                }
            }
            preview(currentGesture())
        }
        onChanged()
    }

    /** Finish the gesture: settle the selection, or write the transform it produced. */
    fun end(camera: Camera) {
        if (mode == Mode.LASSO) settleLasso(camera) else commit(currentGesture())
        finishGesture()
    }

    /** A cancelled pointer: put everything back, write nothing. */
    fun cancel() {
        if (mode != Mode.LASSO) {
            for (obj in startSelected) store.previewTransform(obj.id, obj.transform)
            refreshBounds()
        }
        finishGesture()
    }

    private fun finishGesture() {
        pointerId = MotionEvent.INVALID_POINTER_ID
        lasso = null
        alignment = Alignment.None
        startSelected = emptyList()
        startBounds = null
        startHandle = null
        guideTargets = emptyList()
        onChanged()
    }

    // -----------------------------------------------------------------------
    // Selecting
    // -----------------------------------------------------------------------

    private fun appendToLasso() {
        val points = lasso as? MutableList<PagePoint> ?: return
        val last = points.last()
        // A point per pixel of travel is enough for a containment test and keeps the polygon
        // short enough that `Lasso.contains` — which is O(edges) per sampled point per object —
        // stays cheap on a page of 5,000 strokes.
        if (hypot(current.x - last.x, current.y - last.y) < LASSO_STEP_PT) return
        points += PagePoint(current.x, current.y)
    }

    /**
     * A lasso that barely moved is a **tap**, and a tap selects the topmost object under it —
     * or clears the selection when there is nothing there.
     *
     * Without this, tapping to deselect would instead run a three-point lasso that selects
     * nothing, which happens to look the same; and tapping *on* a stroke would also select
     * nothing, which does not.
     */
    private fun settleLasso(camera: Camera) {
        val points = lasso.orEmpty()
        val travelled = hypot(current.x - start.x, current.y - start.y)
        val hits = if (points.size < TAP_MAX_POINTS && travelled < tapSlop(camera)) {
            listOfNotNull(store.objectAt(start.x, start.y, tapSlop(camera)))
        } else {
            store.objectsIn(Lasso.freehand(points))
        }
        select(hits)
    }

    fun select(ids: List<ObjectId>) {
        selection = ids
        refreshBounds()
        onChanged()
    }

    override fun clear() {
        if (selection.isEmpty()) return
        select(emptyList())
    }

    private fun refreshBounds() {
        bounds = if (selection.isEmpty()) null else store.pageBoundsOf(selection)
    }

    // -----------------------------------------------------------------------
    // Previewing
    // -----------------------------------------------------------------------

    /**
     * The page-space transform the gesture currently describes, or null for a lasso.
     *
     * One function for the drag and the commit, so the matrix previewed and the matrix written
     * cannot come from two pieces of arithmetic that drift.
     */
    private fun currentGesture(): Matrix3? = when (mode) {
        Mode.MOVE -> moveGesture()
        Mode.RESIZE -> resizeGesture()
        Mode.ROTATE -> rotateGesture()
        Mode.LASSO -> null
    }

    private fun preview(gesture: Matrix3?) {
        if (gesture == null || gesture.isDegenerate()) return
        for (obj in startSelected) store.previewTransform(obj.id, gesture * obj.transform)
        refreshBounds()
    }

    // -----------------------------------------------------------------------
    // The gestures themselves — all page-space, all pivot-explicit (SelectionGesture)
    // -----------------------------------------------------------------------

    private fun moveGesture(): Matrix3 =
        SelectionGesture.move(current.x - start.x + alignment.dx, current.y - start.y + alignment.dy)

    /**
     * The resize a handle drag describes.
     *
     * Corners keep the aspect ratio and edges do not, which is a deliberate asymmetry rather
     * than a missing modifier key: squashing handwriting on one axis looks wrong immediately
     * and is almost never what a corner drag meant, while an edge handle is unambiguous about
     * which axis it is for. There is no keyboard on this device to hold anything down with.
     */
    private fun resizeGesture(): Matrix3? {
        val from = startBounds ?: return null
        val handle = startHandle ?: return null
        val to = handle.resize(from, current.x - start.x, current.y - start.y, minimumSize(from))
        return SelectionGesture.resize(from, to)
    }

    private fun rotateGesture(): Matrix3 {
        val box = startBounds ?: return Matrix3.Identity
        val from = atan2(start.y - box.centreY, start.x - box.centreX)
        val to = atan2(current.y - box.centreY, current.x - box.centreX)
        return SelectionGesture.rotate(box.centreX, box.centreY, to - from)
    }

    // -----------------------------------------------------------------------
    // Committing — the only place this class writes ops
    // -----------------------------------------------------------------------

    /**
     * One gesture, one batch, one undo entry.
     *
     * ⚠️ The ops go through [StrokeStore.applyOps] with `record = true` rather than being
     * recorded and applied separately. That is the same door undo, redo and a peer's ops come
     * through, so there is exactly one implementation of "an op changed the page" and the
     * screen cannot end up describing something the log does not say. It also makes the commit
     * idempotent against the preview: every op here is absolute, so re-applying the value the
     * drag already previewed is a no-op by construction.
     */
    private fun commit(gesture: Matrix3?) {
        if (gesture == null) return
        val recorder = store.recorder ?: return
        if (startSelected.isEmpty()) return
        applyAsGesture(
            SelectionEdit.transform(startSelected, gesture, recorder::newOpId, recorder::newClock),
        )
        refreshBounds()
    }

    private fun applyAsGesture(ops: List<PageOp>) {
        if (ops.isEmpty()) return
        store.beginEdit()
        store.applyOps(ops, brushes, record = true)
        store.endEdit()
        onChanged()
    }

    // -----------------------------------------------------------------------
    // The toolbar's actions on a selection
    // -----------------------------------------------------------------------

    override val size: Int get() = selection.size

    /** A tombstone per object — the geometry stays in the log (CLAUDE.md rule 1). */
    override fun delete() {
        val recorder = store.recorder ?: return
        applyAsGesture(SelectionEdit.delete(store.selected(selection), recorder::newOpId, recorder::newClock))
        select(emptyList())
    }

    override fun recolour(colorArgb: Int) {
        restyle { style -> style.copy(colorArgb = colorArgb) }
    }

    override fun setWidth(widthDp: Float) {
        val widthPt = widthDp * displayDensity / pixelsPerPoint
        restyle { style -> style.copy(widthPt = widthPt) }
    }

    override fun bringToFront() {
        val recorder = store.recorder ?: return
        applyAsGesture(
            SelectionEdit.bringToFront(
                store.selected(selection),
                store.allSelected(),
                recorder::newOpId,
                recorder::newClock,
            ),
        )
    }

    override fun sendToBack() {
        val recorder = store.recorder ?: return
        applyAsGesture(
            SelectionEdit.sendToBack(
                store.selected(selection),
                store.allSelected(),
                recorder::newOpId,
                recorder::newClock,
            ),
        )
    }

    /**
     * ⭐ Recolour, and the one action whose **undo** needed work.
     *
     * SPEC §4.2's `SetStyle` carries a non-null [ObjectStyle], and a stroke's style register
     * starts *unset* — null meaning "as captured", because the colour it was drawn with is
     * already in the geometry blob and duplicating it would be two sources of truth. So the
     * inverse of the **first** recolour of any stroke is "unset", which the vocabulary cannot
     * say: `Undo.inverseOf` returns null and the gesture is not undoable.
     *
     * Rather than extend the op format after sync is live — the thing SPEC §4.2 exists to
     * prevent — the register is given a value first. This module is the only one that *can*:
     * the capture-time colour and width are on the decoded brush, right here. So a first
     * recolour is two batches: a baseline that writes what the stroke already looks like, and
     * then the change.
     *
     * The baseline is invisible in every sense that matters. It renders identically (it is the
     * same numbers), and it produces no undo entry of its own because its own inverse is empty
     * and `UndoStack.commit` drops entries that cannot be undone — which is a fix in its own
     * right, made in the same milestone and for this reason.
     */
    private fun restyle(change: (ObjectStyle) -> ObjectStyle) {
        val recorder = store.recorder ?: return
        if (selection.isEmpty()) return
        val baseline = selection
            .mapNotNull { store.strokeOf(it) }
            .filter { it.style == null }
            .map { dry ->
                PageOp.SetStyle(recorder.newOpId(), recorder.newClock(), dry.id, dry.capturedStyle())
            }
        applyAsGesture(baseline)
        applyAsGesture(
            SelectionEdit.restyle(
                objects = store.selected(selection),
                // Each object's own style, with the one field the user changed. After the
                // baseline above, `style` is non-null for every stroke in the selection, so the
                // fallback is unreachable in practice — and it is `capturedStyle` rather than
                // an invented default so that if it ever *is* reached, the stroke keeps the
                // colour it was drawn with instead of turning black.
                styleOf = { obj ->
                    val dry = store.strokeOf(obj.id)
                    change(dry?.style ?: dry?.capturedStyle() ?: DEFAULT_STYLE)
                },
                nextOpId = recorder::newOpId,
                nextClock = recorder::newClock,
            ),
        )
    }

    /**
     * Copy the selection, offset so the copy is visibly a copy, and select it.
     *
     * The `AddObject` is built by [PageOpRecorder.strokeDuplicated] rather than by
     * `SelectionEdit`, because it carries a geometry blob and encoding one must not happen on
     * this thread (CLAUDE.md rule 7). The *decisions* are still core's:
     * [SelectionEdit.keysAbove] for where the copy sits in the stack and
     * [SelectionGesture.move] for the offset.
     */
    override fun duplicate() {
        val recorder = store.recorder ?: return
        if (selection.isEmpty()) return
        val sources = selection.mapNotNull { store.strokeOf(it) }
        val keys = store.allocateTopZ(sources.size)
        val offset = SelectionGesture.move(DUPLICATE_OFFSET_PT, DUPLICATE_OFFSET_PT)
        val copies = ArrayList<ObjectId>(sources.size)

        store.beginEdit()
        for ((index, source) in sources.withIndex()) {
            val id = recorder.newObjectId()
            val transform = offset * source.transform
            recorder.strokeDuplicated(source.stroke, id, source.kind, source.inputModel, transform, keys[index])
            store.duplicate(source.id, id, transform, keys[index])
            copies += id
        }
        store.endEdit()
        select(copies)
    }

    // -----------------------------------------------------------------------
    // Geometry helpers
    // -----------------------------------------------------------------------

    /** A constant size on the glass, so a handle is grabbable at every zoom. */
    private fun handleReach(camera: Camera): Float = HANDLE_TOUCH_DP * displayDensity / camera.scale

    private fun tapSlop(camera: Camera): Float = TAP_SLOP_DP * displayDensity / camera.scale

    /** Never resize a selection to nothing: a box with no extent cannot be grabbed again. */
    private fun minimumSize(from: PageRect): Float = max(MIN_SIZE_PT, min(from.width, from.height) * MIN_SIZE_FRACTION)

    private fun toPageSpace(viewX: Float, viewY: Float, camera: Camera, into: PagePointF) {
        into.set(
            (viewX - camera.translate.x) / camera.scale,
            (viewY - camera.translate.y) / camera.scale,
        )
    }

    private companion object {
        const val LASSO_STEP_PT = 1.5f
        const val HANDLE_TOUCH_DP = 22f
        const val TAP_SLOP_DP = 8f
        const val TAP_MAX_POINTS = 3

        /** Enough that the copy is obviously not the original, small enough to stay on screen. */
        const val DUPLICATE_OFFSET_PT = 18f

        const val MIN_SIZE_PT = 8f
        const val MIN_SIZE_FRACTION = 0.05f

        /** Only reachable for an object with no decoded stroke, which is not a stroke. */
        val DEFAULT_STYLE = ObjectStyle(colorArgb = 0xFF1A1A1A.toInt(), widthPt = 3f)
    }
}

/** The style a stroke was drawn with, read off its brush — see [SelectionController.restyle]. */
internal fun DryStroke.capturedStyle(): ObjectStyle =
    ObjectStyle(colorArgb = stroke.brush.colorIntArgb, widthPt = stroke.brush.size)

/** Move a box without changing its size. */
internal fun PageRect.translated(dx: Float, dy: Float): PageRect =
    PageRect(left + dx, top + dy, right + dx, bottom + dy)

internal fun PageRect.containsWithin(x: Float, y: Float, slop: Float): Boolean =
    x >= left - slop && x <= right + slop && y >= top - slop && y <= bottom + slop

/**
 * The eight resize grips and the rotate grip, and where they are.
 *
 * The box is **axis-aligned even after a rotation** — it is the bounding box of the rotated
 * content, not a rotated box. That is the simpler thing and it is also the more predictable
 * one: the grips are always where the eye expects them, and a second rotation works off the new
 * box rather than accumulating a frame the user cannot see. What it costs is that rotating by
 * 45° and back does not return the box to its original size; the *ink* does return exactly,
 * because the transform is absolute and composed, and the ink is what matters.
 */
internal enum class SelectionHandle {
    TOP_LEFT,
    TOP,
    TOP_RIGHT,
    RIGHT,
    BOTTOM_RIGHT,
    BOTTOM,
    BOTTOM_LEFT,
    LEFT,
    ROTATE,
    ;

    /** Where this grip sits on [box], in page space. */
    fun positionOn(box: PageRect, rotateOffsetPt: Float): PagePoint = when (this) {
        TOP_LEFT -> PagePoint(box.left, box.top)
        TOP -> PagePoint(box.centreX, box.top)
        TOP_RIGHT -> PagePoint(box.right, box.top)
        RIGHT -> PagePoint(box.right, box.centreY)
        BOTTOM_RIGHT -> PagePoint(box.right, box.bottom)
        BOTTOM -> PagePoint(box.centreX, box.bottom)
        BOTTOM_LEFT -> PagePoint(box.left, box.bottom)
        LEFT -> PagePoint(box.left, box.centreY)
        ROTATE -> PagePoint(box.centreX, box.top - rotateOffsetPt)
    }

    /** True for the four grips that keep the aspect ratio. */
    val isCorner: Boolean get() = this == TOP_LEFT || this == TOP_RIGHT || this == BOTTOM_RIGHT || this == BOTTOM_LEFT

    /**
     * The box this grip produces when dragged by ([dx], [dy]).
     *
     * The opposite edge or corner is the fixed point, which is what makes a resize feel like
     * dragging the shape rather than moving it. [minimum] stops the box collapsing — a
     * degenerate one would be refused by `SelectionEdit.transform` anyway, but refusing to draw
     * is worse feedback than simply not shrinking further.
     */
    fun resize(box: PageRect, dx: Float, dy: Float, minimum: Float): PageRect {
        if (this == ROTATE) return box
        val scale = if (isCorner) cornerScale(box, dx, dy) else 0f
        var left = box.left
        var top = box.top
        var right = box.right
        var bottom = box.bottom
        if (isCorner) {
            val width = (box.width * scale).coerceAtLeast(minimum)
            val height = (box.height * scale).coerceAtLeast(minimum)
            when (this) {
                TOP_LEFT -> {
                    left = right - width
                    top = bottom - height
                }

                TOP_RIGHT -> {
                    right = left + width
                    top = bottom - height
                }

                BOTTOM_RIGHT -> {
                    right = left + width
                    bottom = top + height
                }

                else -> {
                    left = right - width
                    bottom = top + height
                }
            }
        } else {
            when (this) {
                LEFT -> left = (box.left + dx).coerceAtMost(right - minimum)
                RIGHT -> right = (box.right + dx).coerceAtLeast(left + minimum)
                TOP -> top = (box.top + dy).coerceAtMost(bottom - minimum)
                else -> bottom = (box.bottom + dy).coerceAtLeast(top + minimum)
            }
        }
        return PageRect(left, top, right, bottom)
    }

    /**
     * How much a corner drag scales the box, from the drag's projection onto the box diagonal.
     *
     * Projecting rather than taking whichever axis moved more: a drag along the diagonal should
     * scale by its whole length, and one perpendicular to it should scale by nothing, which is
     * what the eye expects of a corner grip and what `max(|dx|, |dy|)` gets wrong in both
     * directions.
     */
    private fun cornerScale(box: PageRect, dx: Float, dy: Float): Float {
        val diagonal = hypot(box.width, box.height)
        if (diagonal <= 0f) return 1f
        // Outward is +x for the right-hand grips and +y for the bottom ones.
        val outX = if (this == TOP_RIGHT || this == BOTTOM_RIGHT) 1f else -1f
        val outY = if (this == BOTTOM_LEFT || this == BOTTOM_RIGHT) 1f else -1f
        val along = (dx * outX * box.width + dy * outY * box.height) / diagonal
        return (1f + along / diagonal).coerceAtLeast(0f)
    }

    companion object {
        /** How far above the box the rotate grip floats, in page points at 100%. */
        const val ROTATE_OFFSET_PT = 28f

        /**
         * The grip within [reach] of ([x], [y]), nearest first, or null.
         *
         * The rotate grip is tested first: it is outside the box, so nothing else can claim it,
         * and testing it last would let the TOP grip win whenever the two overlap on a short
         * selection.
         */
        fun at(x: Float, y: Float, box: PageRect, reach: Float): SelectionHandle? {
            var best: SelectionHandle? = null
            var bestDistance = reach
            for (handle in entries) {
                val position = handle.positionOn(box, ROTATE_OFFSET_PT)
                val distance = hypot(x - position.x, y - position.y)
                if (distance <= bestDistance) {
                    bestDistance = distance
                    best = handle
                }
            }
            return best
        }
    }
}
