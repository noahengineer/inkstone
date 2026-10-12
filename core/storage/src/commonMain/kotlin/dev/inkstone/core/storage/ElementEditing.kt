package dev.inkstone.core.storage

import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.SelectionEdit
import dev.inkstone.core.model.ShapeEdit
import dev.inkstone.core.model.ShapeGeometry
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.ShapePlacement
import kotlin.math.max
import kotlin.math.min

/**
 * The Element's bounds on the editor's canvas, as the fold has them now.
 *
 * @property id the frame object — what a selection, a move and an undo act on.
 * @property bounds the frame in page space: the axis-aligned box around its four corners, so a frame
 *   somebody rotated still answers with the box the Element will be placed by.
 * @property pivot the pivot in page space — the frame's own, or its **top-left corner** when it has
 *   none, which is what a placed copy without one snaps by (`ElementInstance.pivotOn`). Stored as
 *   an offset in points from the bounds' top-left (`ObjectFormat.framePivot`), **not** as a fraction
 *   of them: a pivot put on a grid point stays on it when the bounds are resized from the far corner,
 *   and moves by whole cells when they are moved — where a proportional one slid off the lattice on
 *   the first resize, which the device walk found.
 * @property hasPivot whether the pivot was placed, rather than defaulting to the centre. What is
 *   saved is null in that case, so an Element whose bounds later change keeps a centred pivot.
 */
public data class ElementFrame(val id: ObjectId, val bounds: PageRect, val pivot: PagePoint, val hasPivot: Boolean)

/**
 * ⭐ SPEC §4.9.7 — **the Element editor's guides, read off and written to its page.**
 *
 * The bounds are an object on the scratch page — a rectangle whose `SetFormat` register carries
 * `ObjectFormat.elementFrame` and the pivot — rather than a value the screen holds. That is the whole
 * design, and it is the answer to *"hard to position right with no undo or edit … no grid snap"*: an
 * object is selected, moved, resized and snapped to the grid by the gestures every other object
 * already has, and every change to it is an op, so it is on **the editor's** undo stack in the order
 * it happened. A value beside the page would need its own undo, its own handles and its own snapping,
 * and would get all three subtly different from everything next to it.
 *
 * The frame never leaves the editor: [pieces] is what a save keeps, and it is everything *but* the
 * frame.
 *
 * Pure over a fold, for CLAUDE.md definition-of-done #6's reason — the decisions "what is saved, at
 * what box, with which pivot" are assertable in a JVM test, and the app only routes them.
 */
public object ElementEditing {

    /**
     * The frame on [page], or null when there is none — deleted, which undo or [fitOps] puts right.
     *
     * Topmost wins when there are two (a duplicated frame), because the one drawn last is the one the
     * user sees on top and has most recently touched.
     */
    public fun frameOf(page: FoldedPage): ElementFrame? {
        val shape = page.objects
            .filterIsInstance<ShapeObject>()
            .filter { ObjectFormat.decode(page.formatOf(it.id)).elementFrame }
            .maxByOrNull { it.z }
            ?: return null
        val format = ObjectFormat.decode(page.formatOf(shape.id))
        val corners = listOf(
            PagePoint(0f, 0f),
            PagePoint(shape.widthPt, 0f),
            PagePoint(shape.widthPt, shape.heightPt),
            PagePoint(0f, shape.heightPt),
        ).map { shape.transform.map(it) }
        val bounds = PageRect(
            corners.minOf { it.x },
            corners.minOf { it.y },
            corners.maxOf { it.x },
            corners.maxOf { it.y },
        )
        val pivot = format.framePivot?.let { clampInto(PagePoint(bounds.left + it.x, bounds.top + it.y), bounds) }
            ?: PagePoint(bounds.left, bounds.top)
        return ElementFrame(shape.id, bounds, pivot, format.framePivot != null)
    }

    /** What a save keeps: every object on [page] except the editor's frame. */
    public fun pieces(page: FoldedPage): List<ObjectId> = page.objects
        .filterNot { ObjectFormat.decode(page.formatOf(it.id)).elementFrame }
        .map { it.id }

    /**
     * A new frame at [box], with [pivot] (page space) or a centred one.
     *
     * At [z] — the caller passes the bottom of the page, so the frame is under every piece and a tap
     * inside it lands on the Element rather than on its bounds.
     */
    public fun frameOps(
        id: ObjectId,
        box: PageRect,
        pivot: PagePoint?,
        z: FractionalKey,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): List<PageOp> {
        val width = max(box.width, ShapeGeometry.MIN_EXTENT_PT)
        val height = max(box.height, ShapeGeometry.MIN_EXTENT_PT)
        val ops = ShapeEdit.create(
            id = id,
            placement = ShapePlacement(ShapeKind.RECTANGLE, width, height, Matrix3.translate(box.left, box.top)),
            z = z,
            style = FRAME_STYLE,
            nextOpId = nextOpId,
            nextClock = nextClock,
        )
        val format = ObjectFormat(elementFrame = true, framePivot = pivot?.let { offsetIn(it, box) }).encode()
        return ops + PageOp.SetFormat(nextOpId(), nextClock(), id, format)
    }

    /**
     * The op that moves the pivot of [frame] to [at] (page space) — one absolute `SetFormat`, so it
     * is undone like any other edit.
     *
     * The point is clamped into the bounds: a pivot is the point a copy *of these bounds* snaps by,
     * and one outside them would snap a copy by a point nothing on the page marks.
     */
    public fun pivotOp(
        page: FoldedPage,
        frame: ElementFrame,
        at: PagePoint,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): PageOp.SetFormat {
        val format = ObjectFormat.decode(page.formatOf(frame.id)).copy(framePivot = offsetIn(at, frame.bounds))
        return PageOp.SetFormat(nextOpId(), nextClock(), frame.id, format.encode())
    }

    /**
     * The ops that make [frame] exactly [box] — the editor's *Fit*. One absolute `SetTransform`
     * (CLAUDE.md rule 8), and — for a pivot somebody placed — one `SetFormat` that keeps it **where it
     * is on the page**, clamped into the new bounds, because a pivot put on a grid point should stay on
     * it when the bounds around it change. Empty when the frame is not a shape any more.
     */
    public fun fitOps(
        page: FoldedPage,
        frame: ElementFrame,
        box: PageRect,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): List<PageOp> {
        val shape = page.objects.firstOrNull { it.id == frame.id } as? ShapeObject ?: return emptyList()
        val width = max(box.width, ShapeGeometry.MIN_EXTENT_PT)
        val height = max(box.height, ShapeGeometry.MIN_EXTENT_PT)
        val scale = Matrix3.scale(width / shape.widthPt, height / shape.heightPt)
        val placed = Matrix3.translate(box.left, box.top) * scale
        val ops = mutableListOf<PageOp>(PageOp.SetTransform(nextOpId(), nextClock(), frame.id, placed))
        if (frame.hasPivot) {
            val fitted = PageRect(box.left, box.top, box.left + width, box.top + height)
            ops += pivotOp(page, frame.copy(bounds = fitted), frame.pivot, nextOpId, nextClock)
        }
        return ops
    }

    /**
     * ⭐ An invisible knockout area (SPEC §4.9.6) at [box] — a closed shape that hides what is
     * beneath its outline and is not itself drawn. The shape and its `SetFormat` together, so one
     * undo removes it.
     *
     * ⭐ **At the back of the page, not the top.** An area hides what is beneath it, and drawn on top
     * it hid the Element's own pieces too — a cut-out round a face took the eyes with it (found on
     * the emulator). What an area is for is the *note* behind a placed copy, and every copy sits
     * above the note, so the back of the Element is where it does that and nothing else. Front and
     * Back still move it, for the rarer area that should hide one of its own pieces.
     */
    public fun areaOps(
        page: FoldedPage,
        id: ObjectId,
        kind: ShapeKind,
        box: PageRect,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): List<PageOp> = ShapeEdit.create(
        id = id,
        placement = ShapePlacement(kind, box.width, box.height, Matrix3.translate(box.left, box.top)),
        z = bottomOf(page),
        style = AREA_STYLE,
        nextOpId = nextOpId,
        nextClock = nextClock,
    ) + PageOp.SetFormat(nextOpId(), nextClock(), id, ObjectFormat(cutter = true).encode())

    /**
     * The editor's Pivot tool: the pivot moved to [at]. On a canvas whose bounds were deleted the
     * bounds come back first, at [content] — the content's own box — because a pivot is a point *of
     * the bounds* and there is nothing else to hang it on. Empty when there is nothing to bound.
     */
    public fun pivotEditOps(
        page: FoldedPage,
        at: PagePoint,
        content: PageRect?,
        newId: ObjectId,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): List<PageOp> {
        val frame = frameOf(page) ?: return content?.let {
            frameOps(newId, it, at, bottomOf(page), nextOpId, nextClock)
        }.orEmpty()
        return listOf(pivotOp(page, frame, at, nextOpId, nextClock))
    }

    /** The editor's *Fit*: the bounds made [box] — or recreated there, if they were deleted. */
    public fun fitEditOps(
        page: FoldedPage,
        box: PageRect,
        newId: ObjectId,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): List<PageOp> {
        val frame = frameOf(page) ?: return frameOps(newId, box, null, bottomOf(page), nextOpId, nextClock)
        return fitOps(page, frame, box, nextOpId, nextClock)
    }

    /** A key under everything on [page] — or above it, for keys too malformed to go under. */
    private fun bottomOf(page: FoldedPage): FractionalKey =
        FractionalKey.betweenOrNull(null, page.objects.minOfOrNull { it.z })
            ?: SelectionEdit.keyAbove(page.objects.maxOfOrNull { it.z })

    /** [point], clamped into [box], as an offset from the box's top-left — what `ObjectFormat.framePivot` holds. */
    private fun offsetIn(point: PagePoint, box: PageRect): PagePoint {
        val clamped = clampInto(point, box)
        return PagePoint(clamped.x - box.left, clamped.y - box.top)
    }

    private fun clampInto(point: PagePoint, box: PageRect) = PagePoint(
        min(max(point.x, box.left), box.right),
        min(max(point.y, box.top), box.bottom),
    )

    /**
     * A knockout area's style. Never drawn in a note, but SPEC §4.9.2 requires a shape to be created
     * with one (`SetStyle` cannot say "unset"), and the editor's dashed outline is its own.
     *
     * ⚠️ **Zero width.** A shape's bounds include half its stroke, and the grid lands a dragged
     * selection by its bounds' corner — so a 1 pt style put every snapped area half a point off the
     * lattice the area tool had just placed it on (found on the emulator). An area cuts by its
     * outline filled and has no stroke to account for.
     */
    private val AREA_STYLE = ObjectStyle(colorArgb = AREA_ARGB, widthPt = 0f)

    /** sky-400, as the editor draws an area — what a later build that does draw the style would show. */
    private const val AREA_ARGB = 0xFF38BDF8.toInt()

    /** A hairline: the frame is drawn as chrome by the editor, never by its style. */
    private val FRAME_STYLE = ObjectStyle(colorArgb = 0, widthPt = 0f, opacity = 0f)
}
