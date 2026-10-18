package dev.inkstone.android.ink

import android.os.Looper
import android.view.Choreographer
import android.view.MotionEvent
import androidx.annotation.ColorInt
import dev.inkstone.core.model.Alignment
import dev.inkstone.core.model.AlignmentGuides
import dev.inkstone.core.model.BendDrag
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.ConnectorRoute
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.Lasso
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.SelectedObject
import dev.inkstone.core.model.SelectionEdit
import dev.inkstone.core.model.SelectionGesture
import dev.inkstone.core.model.ShapeEdit
import dev.inkstone.core.model.ShapeGeometry
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapePoint
import dev.inkstone.core.model.ShapePointRole
import dev.inkstone.core.model.ShapePoints
import dev.inkstone.core.model.ShapeRecord
import dev.inkstone.core.model.ShapeRegular
import dev.inkstone.core.model.SnapGrid
import dev.inkstone.core.model.TextBoxObject
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

    /**
     * ⭐ ROADMAP M8b — *"fill colour with opacity"*. Null clears the fill.
     *
     * Ignored for anything that is not a shape, and for a shape with no interior
     * ([ShapeGeometry.isFillable] — a line has none, whatever colour you ask for). A mixed
     * selection therefore fills the boxes and leaves the ink alone, which is what the user can
     * see happening.
     */
    public fun setFill(@ColorInt colorArgb: Int?, opacity: Float = 1f)

    /** ROADMAP M8b — *"rounded corners"*. Rectangles only ([ShapeGeometry.roundsCorners]). */
    public fun setCornerRadius(radiusPt: Float)

    /** ROADMAP M8b — elbow and curved. Applies to connectors; a plain shape has no route. */
    public fun setRoute(route: ConnectorRoute)

    /**
     * ⭐ *"buttons for makeing it a perfect circle … or perfect triangle"* — one absolute op per
     * shape, in one gesture.
     *
     * The arithmetic and the meaning of "regular" per kind are [ShapeRegular]'s: a closed shape
     * has its **vertices** rewritten and a segment its rotation, and neither touches the scale —
     * because a non-uniform matrix stretches the pen as well as the shape, which is what
     * *"the line sizes get messed up"* was. A shape that is already regular writes nothing, so the
     * chip cannot produce an undo entry that does nothing.
     */
    public fun makeRegular()

    /**
     * ⭐ *"should be able to say if its an arrow or not if its a line"* — one `SetTextContent` per
     * segment, carrying [ShapeRecord.arrow].
     *
     * An override of the kind rather than a change to it; see that property for why the kind itself
     * cannot move. Ignored for anything that is not a segment — a rectangle has no tip to put a
     * head on, and a record saying it had one would be a value two builds could read differently.
     */
    public fun setArrowHead(shown: Boolean)

    /** How many selected objects are shapes, and how many of those are connectors. */
    public val shapeCounts: ShapeSelectionCounts

    /**
     * ⭐ The selected objects' ids, in render order.
     *
     * Exposed for *"save this selection as an Element"*. It used to be a count and nothing else, and
     * `ElementLibrary`'s KDoc named that as the reason handwriting could not be saved: `:app` can
     * read an object out of the fold by id, but only `:android:ink` can measure a stroke, so without
     * the ids the lasso could not say *which* ink it had just enclosed.
     */
    public val ids: List<ObjectId>

    /**
     * ⭐ SPEC §4.9.7 — the pivot of the Element copy in the selection, in page space, or null when
     * nothing selected came from an Element with one. With the grid on, a move lands this point on
     * an intersection; `:app` draws it and offers the grid toggle when it is not null.
     */
    public val pivot: PagePoint?

    /**
     * ⭐ SPEC §4.9.6 — how many selected objects can carry knockout, and how many already do.
     *
     * Two numbers rather than a boolean so the chip can show *on* only when every one is, and so a
     * selection of nothing but highlighters — which never knock out — offers no chip at all.
     */
    public val knockoutCounts: KnockoutCounts

    /**
     * ⭐ SPEC §4.9.6 — hide, or stop hiding, whatever is underneath each selected object's filled
     * silhouette. One `SetFormat` per object whose record actually changes, as one gesture, so one
     * undo puts all of them back. Nothing beneath is written to.
     */
    public fun setKnockout(on: Boolean)

    /**
     * ⭐ The one selected image, or null when the selection is not exactly one image.
     *
     * ## Why the selection had to start answering this
     *
     * The Image tool used to be a *mode*: a full-screen Compose overlay in front of the surface,
     * whose tap wrote which picture the Rotate / Flip / Crop buttons acted on. That overlay is why
     * an image could not be dragged — the surface never received a single event while the tool that
     * owned images was the active one, so *"move this photo"* meant switching to the Select tool
     * first. Two tools disagreed about images, and only one of them could move them.
     *
     * Driving the image bar off the **selection** instead collapses the two: the Image tool now
     * falls through to `InkTool.SELECT` like every other object tool, so move, resize and rotate are
     * the ones that already work, and the buttons follow whatever is selected however it got
     * selected. `onSelectionChanged` is the one moment this is known to have changed, which is why
     * it is asked for here rather than pushed through a second callback (the argument
     * [shapeCounts] already makes).
     */
    public val soleImage: SelectedImage?

    /**
     * ⭐ The one selected text box, or null when the selection is not exactly one.
     *
     * [soleImage]'s argument, for the kind next door: *"Edit text"* acts on a single paragraph, and
     * a bar offering it for a mixed selection would open a field over one of them at random. Asked
     * for inside `onSelectionChanged` for the same reason as the rest — it is a property of the
     * selection, and a second callback is a second thing to forget to fire.
     *
     * It carries the words and the format because the alternative is `:app` reading them back out
     * of the fold, which is a second source for values the surface is already holding — and the
     * mistake `tapImage` made when two layers each had their own idea of where an object was.
     */
    public val soleTextBox: SelectedText?

    /**
     * ⭐ The generated phrase the selection is (docs/HANDWRITING-SYNTHESIS.md §6.2), or null when the
     * selection is anything else — ink the user wrote, two phrases, a phrase and something beside it.
     *
     * What *Write again* and *Edit text* key off. Exactly one phrase, for [soleTextBox]'s reason: both
     * act on one set of words, and a bar offering them for a mixed selection would rewrite one of
     * them at random.
     */
    public val phrase: SelectedPhrase?

    /**
     * ⭐ ROADMAP M10 — the page-space box round the selection, or null when nothing is selected.
     *
     * Exposed because the redesign's contextual toolbars *float beside the object they act on*
     * rather than living in a global bar, so `:app` has to know where the object is. The controller
     * has maintained this for its own handles since M8a; publishing it is what stops `:app`
     * recomputing a second, disagreeing box — which is precisely the mistake `tapImage` made when
     * two tools each had their own idea of where a rotated picture was.
     *
     * ⚠️ Read at the same moment as [shapeCounts] and [soleImage] — inside `onSelectionChanged` —
     * for the reason those two are: it is a *property of the selection*, and a second callback is a
     * second thing to forget to fire.
     */
    public val bounds: PageRect?

    /**
     * ⭐ The page-space box every grip's **centre** sits inside — [bounds], plus the rotate grip that
     * floats above it. Null when nothing is selected.
     *
     * Published so a toolbar can stay off the grips. The bar used to anchor to [bounds] with a 14 dp
     * gap while the rotate grip floated 28 pt above the same edge, so the bar was drawn squarely over
     * it: *"it covers the rotation handle for the object"*. Only the controller knows where its grips
     * are, so it says so rather than `:app` copying the offset and drifting from it.
     *
     * Add [HANDLE_REACH_DP] round it on screen for the area a finger can actually grab.
     */
    public val chromeBounds: PageRect?

    /**
     * ⭐ True while a pen is **moving, resizing, rotating or reshaping** the selection — false for a
     * lasso, and false between gestures.
     *
     * A floating bar is placed from [bounds] as they were when the gesture began, so during a drag
     * it stands still over whatever the object is being dragged across and then jumps when the pen
     * lifts: *"it only updates its position once you let go … which makes it hard to see what you
     * are doing"*. Chasing the finger instead would be per-frame recomposition (CLAUDE.md rule 7),
     * so `:app` hides the bar for the gesture. It changes exactly at pen-down and pen-up, which are
     * both moments `onSelectionChanged` already fires.
     */
    public val isManipulating: Boolean

    public companion object {
        /**
         * How far from a grip's centre a press still takes the grip, in dp on the glass.
         *
         * Public so a toolbar can keep out of it: the drawn grip is 6 dp, and a bar clear of the
         * drawing but inside the touch target is a grip the user can see and cannot press.
         */
        public const val HANDLE_REACH_DP: Float = 22f
    }
}

/**
 * The single selected image — what the image bar and the crop overlay act on.
 *
 * Carries the object's own extents and its live format because both are needed to place crop
 * handles, and reading them back out of the fold from `:app` would be a second source for a number
 * the surface already has.
 */
public data class SelectedImage(
    val id: ObjectId,
    val bounds: PageRect,
    val widthPt: Float,
    val heightPt: Float,
    val transform: Matrix3,
    val format: ObjectFormat,
)

/**
 * The single selected text box — what *"Edit text"* opens a field over.
 *
 * @property frame the box in its **own** space, which is [ObjectFormat.boxWidthPt] when the user has
 *   dragged it and the payload's extents otherwise. The pair with [bounds] is what lets the editor
 *   convert a handle drag in page points back into the frame it writes: a box that has also been
 *   scaled has `bounds.width != frame.first`, and the ratio between them is the scale to divide by.
 */
public data class SelectedText(
    val id: ObjectId,
    val bounds: PageRect,
    val widthPt: Float,
    val heightPt: Float,
    val text: String,
    val format: ObjectFormat,
    val style: ObjectStyle,
)

/**
 * A selected generated phrase: its `hw` group and the strokes selected, all of which carry it.
 *
 * The words it was written from are on one stroke's content register (`PhraseRecord`), which the
 * surface does not hold — strokes have no content register on the render side — so `:app` reads
 * them from the fold by these ids.
 */
public data class SelectedPhrase(val group: String, val ids: List<ObjectId>, val bounds: PageRect)

/**
 * What the selection's shapes can *have done to them*, so the toolbar offers only what applies.
 *
 * ⚠️ Offering *"fill"* for a selection of pen strokes is a button that does nothing, which is the
 * dishonest-button problem `UndoStack.commit` exists to avoid one screen further down. [shapes] and
 * [connectors] were enough for that while every shape could be filled and only a rectangle could be
 * rounded; they are not enough now. **Capabilities, not kinds** — a `ROUNDED` chip over a selected
 * ellipse is the same dishonest button, one kind lower down.
 *
 * `:app` holds ids and counts and never the render state, so *"is there anything here I could
 * round?"* has to be answered on this side.
 *
 * @param fillable how many have an interior ([ShapeGeometry.isFillable]).
 * @param roundable how many have corners ([ShapeGeometry.roundsCorners]).
 * @param rounded how many of those actually are rounded right now, so one toggle can show its state.
 */
public data class ShapeSelectionCounts(
    val shapes: Int,
    val connectors: Int,
    val fillable: Int = 0,
    val roundable: Int = 0,
    val rounded: Int = 0,
    /** ⭐ How many selected shapes [ShapeRegular] would actually change. */
    val irregular: Int = 0,
    /** ⭐ Segments — the only kind an arrowhead means anything for — and how many show one. */
    val arrowable: Int = 0,
    val arrowed: Int = 0,
    /**
     * ⭐ The kind every selected shape is, or null for a mixed or empty selection.
     *
     * Published so the bar can say **Circle** over an ellipse and **Equal** over a triangle rather
     * than one word for all of them: "regular" is the concept and nobody thinks in it. `:app` maps
     * the kind to the word, because a label is not a fact about the selection.
     */
    val soleKind: ShapeKind? = null,
)

/**
 * ⭐ SPEC §4.9.6 — what the knockout chip needs to know about a selection.
 *
 * @property eligible selected objects that can knock out — everything but a highlighter.
 * @property on how many of those already do.
 */
public data class KnockoutCounts(val eligible: Int, val on: Int)

/**
 * [SelectedText] for a lone selected text box, or null for anything else. Top-level for
 * `countShapes`'s reason — it costs [SelectionController] no size.
 */
private fun selectedTextOf(dry: DryObject?): SelectedText? {
    val box = dry as? DryTextBox ?: return null
    val bounds = box.pageBounds ?: return null
    return SelectedText(
        id = box.id,
        bounds = PageRect(bounds.xMin, bounds.yMin, bounds.xMax, bounds.yMax),
        // The **live** frame, not `source.widthPt`: the payload is first-write-wins and says how big
        // the box was placed, which stops being the truth the first time a handle is dragged
        // (`DryBox.widthPt`).
        widthPt = box.widthPt,
        heightPt = box.heightPt,
        // The register, then the payload — `PageFolder.materialise` copies the winning
        // `SetTextContent` onto the payload, so the two agree today and only one of them is the
        // source (`InkstonePdfMaterializer.textBoxFor` makes the same choice).
        text = box.content ?: box.source.text,
        format = box.decodedFormat,
        style = box.effectiveStyle(),
    )
}

/** [ShapeSelectionCounts] over the selection's shapes. Top-level so it costs the class no size. */
private fun countShapes(shapes: List<DryShape>): ShapeSelectionCounts {
    val roundable = shapes.filter { ShapeGeometry.roundsCorners(it.obj.shape) }
    // ⚠️ A **connector** is left out of the arrow count deliberately, and not because a head would
    // not draw: `ConnectorRouting` re-derives its geometry every fold, so a head follows the route
    // perfectly well. It is left out because the two chips would then disagree about the same
    // object — the route chips already own a connector's ends — and because the head a connector
    // carries came from the tool that drew it. Say so here, once, rather than in the bar.
    val segments = shapes.filter { ShapeGeometry.bends(it.obj.shape) && !it.isConnector }
    return ShapeSelectionCounts(
        shapes = shapes.size,
        connectors = shapes.count { it.isConnector },
        fillable = shapes.count { ShapeGeometry.isFillable(it.obj.shape) },
        roundable = roundable.size,
        rounded = roundable.count { it.record.cornerRadiusPt > 0f },
        irregular = shapes.count { !it.isRegular },
        arrowable = segments.size,
        arrowed = segments.count { it.hasArrowHead },
        soleKind = shapes.map { it.obj.shape }.distinct().singleOrNull(),
    )
}

/**
 * Whether [ShapeRegular] would leave this shape alone — asked of the **live** outline and matrix,
 * never of the payload, so the chip disappears the moment the press has done its work.
 */
private val DryShape.isRegular: Boolean
    get() = ShapeRegular.isRegular(obj.shape, obj.widthPt, obj.heightPt, record.points, transform)

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
// ⚠️ Suppressed **on this class**, with a reason, rather than by raising the shared threshold —
// which would hand the same exemption to every class in the module. `SelectionController` is two
// things by necessity: a `MotionEvent` gesture state machine, and the implementation of
// [InkSelection], whose size is set by how many commands a toolbar offers a selection. M8b added
// three (fill, corners, route) and the count crossed 30. Splitting the command half into its own
// type is the real answer and it is a public-API change (`surface.selection.setFill(…)` becomes
// `surface.selection.shapes.setFill(…)`), which is not a thing to do in the same change that adds
// the commands — M8a already reshaped this seam once and had to re-run the whole emulator walk to
// prove nothing had come unhooked.
@Suppress("TooManyFunctions")
internal class SelectionController(
    private val store: StrokeStore,
    private val brushes: InkBrushFactory,
    private val displayDensity: Float,
    private val pixelsPerPoint: Float,
    private val onPreview: () -> Unit,
    private val onChanged: () -> Unit,
    /**
     * ⭐ A tap that landed on nothing — *"if you just tap the screen it unselects **and exits the
     * select tool**"*.
     *
     * Reported here rather than inferred from [onChanged] because the two are different facts: a
     * selection can empty for half a dozen reasons (an undo, a peer's delete, an erase), and only
     * one of them is a person tapping bare paper to say *"I am done"*. Inferring it would put the
     * user back on the pen because a collaborator deleted something.
     */
    private val onTappedAway: () -> Unit = {},
) : InkSelection {

    /** What the gesture in flight is doing. */
    private enum class Mode { LASSO, MOVE, RESIZE, ROTATE, POINT }

    /** The current selection, in render order. Empty when nothing is selected. */
    var selection: List<ObjectId> = emptyList()
        private set

    /** The page-space box round [selection], or null when nothing is selected. */
    override var bounds: PageRect? = null
        private set

    /**
     * [bounds] stretched up to the rotate grip. Every selection has one — the point handles replace
     * the eight frame grips, never the rotate grip (see [gripAt]) — so this is unconditional.
     */
    override val chromeBounds: PageRect?
        get() = bounds?.let { PageRect(it.left, it.top - SelectionHandle.ROTATE_OFFSET_PT, it.right, it.bottom) }

    /** A lasso is excluded: it replaces the selection rather than moving it. */
    override val isManipulating: Boolean get() = isActive && mode != Mode.LASSO

    /** The lasso being drawn, in page space, or null. Read by the overlay. */
    var lasso: List<PagePoint>? = null
        private set

    /**
     * ⭐ The sole selected shape's editable points, in page space — empty for anything else.
     *
     * Maintained by [refreshBounds], which is the one place the selection's derived geometry is
     * recomputed, so the handles cannot describe a shape the frame does not. Read by the overlay to
     * draw them and by [begin] to hit-test them.
     */
    var points: List<PointHandle> = emptyList()
        private set

    /** ROADMAP M8a — *"dashed = centre, solid = edge"*. Non-empty only during a move. */
    var alignment: Alignment = Alignment.None
        private set

    var pointerId: Int = MotionEvent.INVALID_POINTER_ID
        private set

    val isActive: Boolean get() = pointerId != MotionEvent.INVALID_POINTER_ID

    val isEmpty: Boolean get() = selection.isEmpty()

    /**
     * ROADMAP M8a — *"freehand AND rectangular"*: when true, the select tool drags a box.
     * The containment rule is unchanged ([Lasso.rectangle] builds the same polygon), so the
     * marquee cannot select differently from a lasso traced round the same rectangle.
     */
    var marquee: Boolean = false

    /**
     * ⭐ The lattice a shape drag lands on — see [InkSurfaceView.gridSnapEnabled].
     *
     * [SnapGrid.None] when the setting is off. Read **once per gesture** into [gestureGrid]: a
     * toggle flipped mid-drag must not change what the finger is already doing, for the same
     * reason [lassoIsRectangle] is latched at pen-down.
     */
    var grid: SnapGrid = SnapGrid.None

    /** [marquee] as it was when the gesture began — see [begin]. */
    private var lassoIsRectangle = false

    /** Cumulative path of the current lasso, in page points. See [settleLasso]. */
    private var pathTravelled = 0f

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

    /** The selection's own angle at pen-down — what a rotate snaps level from ([SelectionGesture.sharedRotation]). */
    private var startRotation = 0f

    /**
     * ⭐ The selection's Element pivot at pen-down, in page space, or null — what a move lands on
     * the grid instead of the box's top-left (SPEC §4.9.7). Latched with [startBounds] for the same
     * reason: every frame solves the drag against where the gesture began.
     */
    private var startPivot: PagePoint? = null
    private var startHandle: SelectionHandle? = null

    /** The shape handle grabbed at pen-down, and the shape as it was then. Both null off [Mode.POINT]. */
    private var startPoint: ShapePoint? = null
    private var startShape: PointTarget? = null

    /** Snap positions of everything *not* selected, built once per gesture — the guides' targets. */
    private var guideTargets: AlignmentGuides.AlignmentTargets? = null

    /**
     * ⭐ [grid] as it applies to **this** gesture, or [SnapGrid.None].
     *
     * Shapes only — the predicate and the reasoning behind it are [StrokeStore.gridFor]'s.
     */
    private var gestureGrid: SnapGrid = SnapGrid.None

    /** The snap radius in page points, refreshed per event. The camera cannot change mid-gesture. */
    private var snapTolerancePt = 0f

    /**
     * How near the chord a dragged bend counts as straight, in page points.
     *
     * ⚠️ Set in [begin] as well as [extend], and that is not belt and braces: a press on the bend
     * handle that never moves still reaches [flushPreview] from [end], and with a zero tolerance
     * the bend it computes is *"exactly on the chord"* rather than *"none"* — which is a record
     * written, a string added to the file, and a `SetTextContent` synced, for a line the user
     * merely touched.
     */
    private var straightenTolerancePt = 0f

    private var previewScheduled = false

    /**
     * ⭐ One preview per drawn frame, not per input event.
     *
     * A stylus reports at up to 240 Hz and the display draws at most 120, so previewing from
     * [extend] computed two to four frames' worth of guide searches and per-object transforms
     * per frame and threw the extra away — on the UI thread, scaling with page fullness and
     * selection size. The event handler now only records where the finger *is*; this callback
     * does the work once per frame the user can actually see.
     */
    private val previewFrame = Choreographer.FrameCallback {
        previewScheduled = false
        if (isActive && mode != Mode.LASSO) flushPreview()
    }

    private fun schedulePreview() {
        // A thread with no looper cannot frame-throttle — the instrumented tests drive this
        // controller directly from the instrumentation thread — so apply immediately there.
        // Production input dispatch is always the main thread, which has one.
        if (Looper.myLooper() == null) {
            flushPreview()
            return
        }
        if (previewScheduled) return
        previewScheduled = true
        Choreographer.getInstance().postFrameCallback(previewFrame)
    }

    private fun unschedulePreview() {
        if (!previewScheduled) return
        previewScheduled = false
        Choreographer.getInstance().removeFrameCallback(previewFrame)
    }

    /**
     * Apply the gesture as it currently stands: guides, preview transforms, chrome.
     *
     * Runs once per drawn frame while the finger moves, and once more synchronously from [end],
     * because the committed matrix must be the last previewed one — the guides are part of the
     * gesture and a snap must be decided while the finger is still down, not recomputed after.
     */
    private fun flushPreview() {
        if (mode == Mode.MOVE && !gridLeads()) {
            val box = startBounds
            val targets = guideTargets
            if (box != null && targets != null) {
                val moved = box.translated(current.x - start.x, current.y - start.y)
                alignment = AlignmentGuides.forMove(moved, targets, snapTolerancePt)
            }
        }
        when {
            isBendGesture() -> previewBend()
            isVertexGesture() -> previewPoints()
            else -> preview(currentGesture())
        }
        onPreview()
    }

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

        snapTolerancePt = snapTolerance(camera)
        straightenTolerancePt = straightenTolerance(camera)

        val box = bounds
        val reach = handleReach(camera)
        val point = pointAt(start.x, start.y, reach)
        val handle = gripAt(box, reach, claimedByAPoint = point != null)
        mode = when {
            point != null -> Mode.POINT

            handle == SelectionHandle.ROTATE -> Mode.ROTATE

            handle != null -> Mode.RESIZE

            // ⚠️ **[tapSlop], not `0f`.** A selection box can legitimately be a hairline — a
            // straight segment's is (`ShapeEdit.placeSegment` gives it the pen's width as its
            // band, and an axis-aligned one collapses that to nothing in page space) — and with
            // no slop the press then lands *outside* a box drawn right under the user's finger.
            // It fell through to [Mode.LASSO], which moves nothing and clears the selection on
            // the way past. The same 8 dp that decides "you tapped this object" in [objectAt] is
            // the honest answer to "you grabbed this selection"; the two used to measure
            // different quantities for one decision, which is a trap this repo has sprung before.
            box != null && box.containsWithin(start.x, start.y, tapSlop(camera)) -> Mode.MOVE

            else -> Mode.LASSO
        }

        when (mode) {
            Mode.LASSO -> {
                // Read once, at gesture start: toggling the chip mid-drag must not turn a
                // half-drawn lasso into a rectangle it never was.
                lassoIsRectangle = marquee
                lasso = mutableListOf(PagePoint(start.x, start.y))
                pathTravelled = 0f
            }

            else -> {
                startHandle = handle
                startPoint = point
                // The placement as it is *now*, for the same reason [startSelected] is captured
                // here: every frame solves the drag against where the shape started, so composing
                // against what is on screen would apply the gesture once per frame.
                startShape = point?.let { soleShape()?.asPointTarget() }
                startBounds = box
                startPivot = pivot
                startSelected = store.selected(selection)
                startRotation = SelectionGesture.sharedRotation(startSelected.map { it.transform })
                guideTargets = if (mode == Mode.MOVE) {
                    AlignmentGuides.AlignmentTargets(store.pageBoundsExcluding(selection), pageSize)
                } else {
                    null
                }
                // A selection with a pivot snaps by it whatever it is made of — handwriting included,
                // which the lattice otherwise leaves alone (`gridFor`).
                gestureGrid = if (startPivot != null && mode == Mode.MOVE) grid else store.gridFor(selection, grid)
            }
        }
        onChanged()
    }

    /**
     * ⭐ Hand a **live** pointer straight to the point handle under it.
     *
     * Draw a line, hold, and it snaps — and the pen is still on the glass, resting on the end of
     * what was just drawn, which is exactly where that shape's endpoint handle now is. This is what
     * lets the same unbroken gesture carry on into adjusting it: no lift, no re-aim, no hunting for
     * a 6 dp circle. `InkSurfaceView` calls it the instant a snap lands.
     *
     * ⚠️ **[Mode.POINT] or nothing.** It deliberately does not fall through to [Mode.MOVE] or
     * [Mode.LASSO] the way [begin] does. Falling through to MOVE would mean a pen that twitched
     * after a snap dragged the whole shape off its own ink; falling through to LASSO would mean it
     * drew a marquee that, on the lift, *replaced* the selection of the shape just made. Both are
     * silent, and both undo work the user thought was finished. If there is no handle under the
     * pen this answers false and the surface leaves the pointer alone.
     *
     * @return true when a handle was there and the drag is now live on [pointerId].
     */
    fun beginPointDrag(pageX: Float, pageY: Float, pointerId: Int, camera: Camera, pageSize: PageSize): Boolean {
        if (isActive) return false
        val point = pointAt(pageX, pageY, handleReach(camera)) ?: return false
        val shape = soleShape()?.asPointTarget() ?: return false

        this.pointerId = pointerId
        this.pageSize = pageSize
        start.set(pageX, pageY)
        current.set(start)
        alignment = Alignment.None
        snapTolerancePt = snapTolerance(camera)
        straightenTolerancePt = straightenTolerance(camera)

        mode = Mode.POINT
        startHandle = null
        startPoint = point
        // The placement as it is *now* — every frame solves the drag against where the shape
        // started, so composing against what is on screen would apply the gesture once per frame.
        startShape = shape
        startBounds = bounds
        startSelected = store.selected(selection)
        guideTargets = null
        // [soleShape] has already proved this is one shape, so the predicate would only ask again.
        gestureGrid = grid
        onChanged()
        return true
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
        if (mode == Mode.LASSO && !lassoIsRectangle) {
            for (h in 0 until event.historySize) {
                toPageSpace(event.getHistoricalX(index, h), event.getHistoricalY(index, h), camera, current)
                appendToLasso()
            }
        }
        toPageSpace(event.getX(index), event.getY(index), camera, current)
        if (mode == Mode.LASSO) {
            // A marquee is where the finger *is* — its corners are derived from two points, so
            // historical samples add nothing and the outline is rebuilt whole, not appended to.
            // [Lasso.rectangle]'s own corners, so chrome and containment share one rule.
            if (lassoIsRectangle) {
                lasso = Lasso.rectangle(PagePoint(start.x, start.y), PagePoint(current.x, current.y)).points
            } else {
                appendToLasso()
            }
            // The lasso is chrome only; invalidates coalesce, so per-event is already per-frame.
            onPreview()
        } else {
            // Divided by the camera scale so the snap radius is constant on the glass — the
            // conversion [AlignmentGuides.forMove]'s own KDoc asks the caller for, and which was
            // missing here once: 6 pt of page was 2 mm at 100% and 8 mm of reach at 25%,
            // snapping things nowhere near each other.
            snapTolerancePt = snapTolerance(camera)
            straightenTolerancePt = straightenTolerance(camera)
            // The guides and transforms are applied in [flushPreview], once per drawn frame.
            schedulePreview()
        }
    }

    /** Finish the gesture: settle the selection, or write the transform it produced. */
    fun end(camera: Camera) {
        if (mode == Mode.LASSO) {
            settleLasso(camera)
        } else {
            // The previewed matrix and the committed one must be the same arithmetic over the
            // same inputs, so the last preview runs synchronously before the commit reads it.
            unschedulePreview()
            flushPreview()
            when {
                isBendGesture() -> commitBend()
                isVertexGesture() -> commitPoints()
                else -> commit(currentGesture())
            }
        }
        finishGesture()
    }

    /** A cancelled pointer: put everything back, write nothing. */
    fun cancel() {
        if (mode != Mode.LASSO) {
            for (obj in startSelected) store.previewTransform(obj.id, obj.transform)
            // A bend and a vertex list preview through their own register, so restoring the
            // transforms does not undo them. Null is "no preview", which puts the shape back to
            // whatever its record says.
            startShape?.let { store.endShapePreview(it.id) }
            refreshBounds()
        }
        finishGesture()
    }

    private fun finishGesture() {
        unschedulePreview()
        pointerId = MotionEvent.INVALID_POINTER_ID
        lasso = null
        lassoIsRectangle = false
        alignment = Alignment.None
        startSelected = emptyList()
        startBounds = null
        startRotation = 0f
        startPivot = null
        startHandle = null
        startPoint = null
        startShape = null
        guideTargets = null
        gestureGrid = SnapGrid.None
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
        val step = hypot(current.x - last.x, current.y - last.y)
        if (step < LASSO_STEP_PT) return
        points += PagePoint(current.x, current.y)
        pathTravelled += step
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
        // A marquee's outline is always four derived corners, so its tap test is travel alone.
        //
        // ⚠️ The second test is on **path length**, not on the number of points, and that is the
        // difference between the Select tool working on a photo and not. It used to be
        // `points.size < 3`: `appendToLasso` adds a point every 1.5 pt, so **3 pt of cumulative
        // path** — a hand's tremor while pressing — reclassified a tap as a lasso. The resulting
        // three-point squiggle contains nothing, so the tap selected nothing *and* cleared what was
        // already selected, intermittently and with no feedback. It bound long before the 8 dp net
        // slop beside it (~6 pt at fit-width), so that slop was decorative.
        //
        // A guard is still needed — a deliberate tiny loop returns to where it started, so net
        // displacement alone would call it a tap — but the quantity that separates the two is how
        // far the pen *went*, not how many samples it left.
        val isTap =
            travelled < tapSlop(camera) && (lassoIsRectangle || pathTravelled < tapPathSlop(camera))
        val hits = when {
            isTap -> listOfNotNull(store.objectAt(start.x, start.y, tapSlop(camera)))

            // The same two corners the chrome drew, through the same [Lasso.rectangle], so what
            // was on screen and what is selected cannot come from two pieces of arithmetic.
            lassoIsRectangle -> store.objectsIn(
                Lasso.rectangle(PagePoint(start.x, start.y), PagePoint(current.x, current.y)),
            )

            else -> store.objectsIn(Lasso.freehand(points))
        }
        select(store.withWholeCopies(hits))
        // ⭐ A tap on bare paper is the gesture for *"done"*: it clears the selection, and now it
        // also hands the tool back. Only for a **tap that hit nothing** — a tap that selects
        // something is the opposite intention, and a lasso that happens to enclose nothing is a
        // gesture the user is still in the middle of.
        if (isTap && hits.isEmpty()) onTappedAway()
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

    /**
     * ⭐ The store changed under the selection — an undo, a redo, a peer's ops, or an erase.
     *
     * Everything this class caches is a snapshot of the store: [selection] holds ids the store
     * may since have deleted, and [bounds] is where the objects *were*. Every path that changes
     * the store without coming through this class's own gestures must call this, or the overlay
     * keeps drawing a box round objects that moved or no longer exist, [begin] hit-tests the
     * next pen-down against that phantom box, and `:app` keeps a toolbar up for a selection of
     * nothing — with the colour picker captured by it (InkToolbar reroutes swatch taps to the
     * selection while `selectionSize > 0`).
     *
     * The empty early-return keeps the common case free, and [onChanged] fires only when the
     * selection actually changed — membership or box — so an erase drag *near* a selection does
     * not fan out into Compose per sample.
     */
    fun onStoreChanged() {
        if (selection.isEmpty()) return
        val alive = selection.filter { store.objectOf(it) != null }
        val membershipChanged = alive.size != selection.size
        if (membershipChanged) selection = alive
        val oldBounds = bounds
        // ⚠️ The points as well as the box, because a record can change without the box moving —
        // straightening a shallow curve is the case, and the handle would then be drawn on a bend
        // that is no longer there until something else happened to invalidate the overlay. The
        // comparison is at most four data-class points, against a callback that fans out to Compose.
        val oldPoints = points
        refreshBounds()
        if (membershipChanged || bounds != oldBounds || points != oldPoints) onChanged()
    }

    private fun refreshBounds() {
        bounds = if (selection.isEmpty()) null else store.pageBoundsOf(selection)
        points = editablePoints()
    }

    /**
     * ⭐ The handles for the one selected shape, in page space — the whole of what the overlay draws
     * and what [begin] hit-tests.
     *
     * Computed from the shape's **live** transform and its **live** bend, so during a drag the
     * handles are on the ink rather than where it was when the finger went down. That is the same
     * reason this sits in [refreshBounds]: the frame and the handles describe one shape and are
     * recomputed together, at every door the store can change through.
     */
    private fun editablePoints(): List<PointHandle> {
        // The Element editor's bounds stay a rectangle: they resize by the selection frame's grips, which
        // snap to the grid, rather than by corners that would each drag on their own (SPEC §4.9.7).
        val shape = soleShape()?.takeIf { !it.elementFrame } ?: return emptyList()
        return ShapePoints.of(
            kind = shape.obj.shape,
            widthPt = shape.obj.widthPt,
            heightPt = shape.obj.heightPt,
            bendLocal = shape.bendNow(),
            points = shape.pointsNow(),
        ).map { PointHandle(it, shape.transform.map(it.local)) }
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

        // ⭐ Null, and the branch is spelled out rather than folded into `LASSO` because it is the
        // statement of a rule: **a point drag never writes a placement.** Both of its handles write
        // geometry into the record instead, which is what *"i dont want any shape to do that no
        // matter what"* asked for. `flushPreview` and `end` route it before this is reached.
        Mode.POINT -> null

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

    /** The finger, the guides and the lattice — see [SnapGrid.moveBy], which is all three. */
    private fun moveGesture(): Matrix3 {
        val dx = current.x - start.x
        val dy = current.y - start.y
        val box = startBounds ?: return SelectionGesture.move(dx + alignment.dx, dy + alignment.dy)
        val pivot = startPivot
        val delta = if (pivot != null) {
            gestureGrid.moveBy(pivot, dx, dy, alignment)
        } else {
            gestureGrid.moveBy(box, dx, dy, alignment)
        }
        return SelectionGesture.move(delta.x, delta.y)
    }

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
        val minimum = minimumSize(from)
        // ⭐ The grid rounds the edges this handle moved, and only those — see [SnapGrid.snappedResize],
        // which is also where a corner drag gives up its aspect ratio and why.
        val dragged = handle.resize(from, current.x - start.x, current.y - start.y, minimum)
        val to = gestureGrid.snappedResize(from, dragged, minimum)
        return SelectionGesture.resize(from, to)
    }

    /**
     * ⭐ Whether the lattice, rather than the alignment guides, decides this move.
     *
     * `SnapGrid.moveBy` lets a guide win on any axis it speaks for, which is right for a box on a
     * page of boxes. It is wrong for an Element copy with a pivot — *"a pivot point for snap so it
     * snaps to a grid"* is an explicit request, and on a page of handwriting some edge is always
     * within a guide's reach, so the pivot almost never landed on the grid (*"it just does not snap
     * at all really"*). And on the Element editor's canvas the grid is the instrument. In both, the
     * grid leads and no guide is drawn for a snap that is not going to happen.
     */
    private fun gridLeads(): Boolean = !gestureGrid.isEmpty && (startPivot != null || store.guidesShown)

    private fun rotateGesture(): Matrix3 {
        val box = startBounds ?: return Matrix3.Identity
        val from = atan2(start.y - box.centreY, start.x - box.centreX)
        val to = atan2(current.y - box.centreY, current.x - box.centreX)
        // Snapped while the pen is down, so the preview shows the angle the lift will write.
        val turn = SelectionGesture.snappedRotation(startRotation, to - from)
        return SelectionGesture.rotate(box.centreX, box.centreY, turn)
    }

    // -----------------------------------------------------------------------
    // ⭐ Editing a shape by its points
    // -----------------------------------------------------------------------

    /**
     * The shape whose points may be edited: exactly one selected, and not a connector.
     *
     * ⚠️ **A connector is excluded, and the reason is that its handles would be honest-looking and
     * dead.** Its geometry is *derived* from what its ends are attached to and re-derived on every
     * fold (SPEC §4.8.3) — so a vertex drag would write a perfectly correct `SetTransform` that
     * `DryShape.reroute` then paints straight over, and a bend would be discarded the same way.
     * Dragging an endpoint onto a *different* shape is the gesture a connector actually wants
     * (docs/M8-CONNECTORS.md §7.7, still owed); until it exists, a connector keeps the frame it has
     * always had rather than gaining a grip that does nothing.
     */
    private fun soleShape(): DryShape? = selection.singleOrNull()?.let { store.shapeOf(it) }?.takeIf { !it.isConnector }

    private fun DryShape.asPointTarget() =
        PointTarget(id, obj.shape, obj.widthPt, obj.heightPt, transform, record.points)

    /**
     * ⭐ Which **frame** grip the press at [start] claims — and the rule that keeps the two sets of
     * grips from fighting.
     *
     * A point handle wins outright, and when a shape offers any, the eight frame grips are not
     * offered at all — the overlay hides them for the same reason. A rectangle's corners sit
     * exactly on the frame's corner grips, so both gestures would claim the same pixels and the one
     * the user can *see* (the shape's own corner, which follows the finger at any rotation) would
     * lose to the one drawn underneath it.
     *
     * The rotate grip survives, because it floats outside the box and collides with nothing, and
     * because rotating is not something the points can express: three of them pinned is a shear.
     */
    private fun gripAt(box: PageRect?, reach: Float, claimedByAPoint: Boolean): SelectionHandle? = when {
        claimedByAPoint || box == null -> null
        points.isEmpty() -> SelectionHandle.at(start.x, start.y, box, reach)
        else -> SelectionHandle.ROTATE.takeIf { it.isWithin(start.x, start.y, box, reach) }
    }

    /** The handle within [reach] of ([x], [y]), nearest first, or null. */
    private fun pointAt(x: Float, y: Float, reach: Float): ShapePoint? {
        var best: ShapePoint? = null
        var bestDistance = reach
        for (handle in points) {
            val distance = hypot(x - handle.page.x, y - handle.page.y)
            if (distance <= bestDistance) {
                bestDistance = distance
                best = handle.point
            }
        }
        return best
    }

    /**
     * ⭐ Which of the three routes the handle under the finger takes.
     *
     * A segment's middle handle and every vertex of a closed shape write **geometry** into the
     * `SetTextContent` record; only a segment's two ends still write a placement, because a
     * placement expresses those exactly. See `ShapePoints` for why the split is where it is.
     */
    private fun isBendGesture(): Boolean = mode == Mode.POINT && startPoint?.role == ShapePointRole.BEND

    /**
     * ⭐ Every handle that is not the bend, for **every** kind.
     *
     * Every kind edits its points now — a segment's ends stopped being a placement edit when *"i
     * dont want any shape to do that no matter what"* arrived — so the only question left is which
     * of the two registers this drag writes.
     */
    private fun isVertexGesture(): Boolean = mode == Mode.POINT && startPoint?.role == ShapePointRole.VERTEX

    /** What the bend drag currently means: what to draw, and what to store. See [BendDrag]. */
    private fun currentBend(): BendDrag? {
        val target = startShape ?: return null
        return ShapePoints.bend(
            widthPt = target.widthPt,
            heightPt = target.heightPt,
            transform = target.transform,
            // ⚠️ The *edited* endpoints, so "is this straight" is measured against the chord the
            // user can see rather than the payload's original one.
            points = target.points,
            // The apex lands on the lattice too, so a curve drawn on a grid is as repeatable as
            // the box beside it. A snap onto the chord straightens the line, which is what the
            // handle sitting on the chord already means.
            toPage = gestureGrid.snap(PagePoint(current.x, current.y)),
            straightenTolerancePt = straightenTolerancePt,
        )
    }

    private fun previewBend() {
        val target = startShape ?: return
        val drag = currentBend() ?: return
        // ⚠️ [BendDrag.preview], never [BendDrag.stored]. The stored value is null once the drag has
        // straightened the line, and a null preview means *"stop previewing"* — which fell back to
        // the record and sprang the curve back to its original arc with the finger still down.
        store.previewBend(target.id, drag.preview)
        // The curve's own extent moved, and so did the handle sitting on it — both are read out of
        // the store by [refreshBounds], which is why the bend preview goes through it and a
        // transform preview does too.
        refreshBounds()
    }

    /** Where the dragged vertex is now, in the shape's own space, with the others untouched. */
    private fun currentPoints(): List<PagePoint>? {
        val target = startShape ?: return null
        val point = startPoint ?: return null
        // ⭐ Snapped in **page** space, before the shape's own transform is undone: the lattice is a
        // fact about the paper, and rounding in the shape's space would round to a rotated, scaled
        // grid that nothing draws.
        val dragged = gestureGrid.snap(PagePoint(current.x, current.y))
        val local = ShapePoints.toLocal(target.transform, dragged) ?: return null
        return ShapePoints.moved(
            kind = target.kind,
            widthPt = target.widthPt,
            heightPt = target.heightPt,
            current = target.points,
            index = point.index,
            toLocal = local,
        )
    }

    private fun previewPoints() {
        val target = startShape ?: return
        // A solve that failed leaves the last preview standing rather than ending it: dropping the
        // preview mid-drag is the springing-back bug the bend had, one kind over.
        val points = currentPoints() ?: return
        store.previewPoints(target.id, points)
        refreshBounds()
    }

    /**
     * One `SetTextContent` carrying the whole vertex list — absolute, like every register here.
     *
     * ⚠️ **The list is whole and not a diff**, and that is CLAUDE.md rule 8 rather than a
     * convenience: two devices dragging *different* corners concurrently would, with deltas, produce
     * a shape neither of them drew. Whole means one of the two edits wins intact — SPEC §4.8.2's
     * stated cost, and the same one a fill already pays.
     */
    private fun commitPoints() {
        val target = startShape ?: return
        val points = currentPoints()
        editRecords { shape, record ->
            if (points != null) record.copy(points = points) else null
        }
        store.endShapePreview(target.id)
        refreshBounds()
    }

    /**
     * One `SetTextContent`, through the same baseline-then-change path a fill takes.
     *
     * ⚠️ [editRecords] is not a convenience here, it is the fix: `SetTextContent` cannot say
     * "unset", so the **first** record a plain shape ever gets has no inverse and `UndoStack.commit`
     * drops the gesture — *"bend a line, press undo"* would eat the press and leave the curve. The
     * baseline it writes is the default record, which renders identically and gives the bend
     * something to be undone to. See [editRecords]' own KDoc.
     */
    private fun commitBend() {
        val target = startShape ?: return
        val bend = currentBend()?.stored
        editRecords { shape, record ->
            // The kind test is the same one the geometry makes, so a bend cannot be written onto
            // something that would ignore it — a filled record on a line's sibling problem
            // ([setFill] refuses the mirror case for the same reason).
            if (ShapeGeometry.bends(shape.obj.shape) && !record.isConnector) record.copy(bend = bend) else null
        }
        // The register is the truth again. `setRecord` clears the preview when an op actually
        // lands, and this covers the case where none did — a drag that ended where it started
        // writes nothing and must still stop previewing.
        store.endShapePreview(target.id)
        refreshBounds()
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
        // A restyle can genuinely move the box: `SetStyle` re-tessellates and a widened stroke
        // has a wider extent. Without this, thickening a selected stroke left the dashed frame
        // snug round the old width with the ink poking past it. A no-op for the actions whose
        // bounds cannot change.
        refreshBounds()
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

    /**
     * ⭐ The outline **and** the fill, as one gesture.
     *
     * ⚠️ It was `restyle { it.copy(colorArgb = …) }` and nothing else, and for a *filled* shape that
     * changed the outline and left the interior the colour it always was — reported as *"if you
     * change colour of a shape, a filled shape does not work with those colour changes"*. The two
     * live in different registers by design: a stroke's colour is `SetStyle`'s [ObjectStyle], and a
     * shape's fill is `fillArgb` inside the `SetTextContent` record (SPEC §4.8.2). Recolouring wrote
     * one of them.
     *
     * They are recoloured together rather than separately because there is no UI for a fill colour
     * of its own — [setFill] takes the pen's colour — so *"make this shape red"* is the only thing a
     * user can mean. [ShapeRecord.fillOpacity] is untouched, so a translucent fill stays translucent
     * and only its hue moves.
     *
     * ⚠️ **One gesture, which is why this does not simply call [restyle] and then [editRecords].**
     * Each of those applies two batches of its own, so the pair would be four — and an undo would
     * put the outline back while leaving the interior recoloured, which is a worse state than either
     * end. The baselines go in one batch and the changes in the other, exactly as each half does
     * alone; see [restyle] on why a baseline is needed at all and why it costs no undo entry.
     */
    override fun recolour(colorArgb: Int) {
        val recorder = store.recorder ?: return
        if (selection.isEmpty()) return

        // Only shapes that are *actually* filled. A hollow one has no interior to recolour, and
        // writing `fillArgb` into it would fill it as a side effect of changing its colour.
        val filled = selection
            .mapNotNull { store.shapeOf(it) }
            .filter { it.record.fillArgb != null }

        applyAsGesture(
            styleBaseline(recorder) +
                filled.filter { it.record.isDefault }.map { shape ->
                    ShapeEdit.writeRecord(shape.id, shape.record, recorder::newOpId, recorder::newClock)
                },
        )
        applyAsGesture(
            styleChange(recorder) { style -> style.copy(colorArgb = colorArgb) } +
                filled.mapNotNull { shape ->
                    val next = shape.record.copy(fillArgb = colorArgb)
                    if (next == shape.record) {
                        null
                    } else {
                        ShapeEdit.writeRecord(shape.id, next, recorder::newOpId, recorder::newClock)
                    }
                },
        )
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

    // --- The record's own properties (M8b) ----------------------------------

    override val shapeCounts: ShapeSelectionCounts
        get() = countShapes(selection.mapNotNull { store.shapeOf(it) })

    override val ids: List<ObjectId> get() = selection

    override val pivot: PagePoint?
        get() = selection.firstNotNullOfOrNull { store.objectOf(it)?.pivotOnPage }

    override val knockoutCounts: KnockoutCounts
        get() {
            val eligible = selection.mapNotNull { store.objectOf(it) }.filterNot { it.drawsOverInk || it.elementFrame }
            return KnockoutCounts(eligible = eligible.size, on = eligible.count { it.knockout })
        }

    override fun setKnockout(on: Boolean) {
        val recorder = store.recorder ?: return
        val ops = selection.mapNotNull { id ->
            val dry = store.objectOf(id) ?: return@mapNotNull null
            // A highlighter never knocks out (DryObject.knockout), so a flag written on one would be a
            // value in the file that this build ignores and a later one might not.
            if (dry.drawsOverInk || dry.elementFrame) return@mapNotNull null
            // The live record, copied and re-encoded — never rebuilt from the one field changing, or
            // a crop, a font or a later build's key on the same object would be reset by the press.
            val next = dry.decodedFormat.copy(knockout = on).encode()
            if (next == dry.format.orEmpty()) return@mapNotNull null
            PageOp.SetFormat(recorder.newOpId(), recorder.newClock(), id, next)
        }
        applyAsGesture(ops)
    }

    /**
     * Exactly one, deliberately. Rotate, flip and crop each act on a single picture, and a bar that
     * appears for a mixed selection would offer a crop that silently applied to one of them.
     */
    override val soleImage: SelectedImage?
        get() {
            val image = selection.singleOrNull()?.let { store.objectOf(it) } as? DryImage ?: return null
            val bounds = image.pageBounds ?: return null
            return SelectedImage(
                id = image.id,
                bounds = PageRect(bounds.xMin, bounds.yMin, bounds.xMax, bounds.yMax),
                widthPt = image.widthPt,
                heightPt = image.heightPt,
                transform = image.transform,
                format = image.decodedFormat,
            )
        }

    /** Exactly one, for [soleImage]'s reason — a field can only be opened over one paragraph. */
    override val soleTextBox: SelectedText?
        get() = selectedTextOf(selection.singleOrNull()?.let { store.objectOf(it) })

    override val phrase: SelectedPhrase?
        get() {
            if (selection.isEmpty()) return null
            val keys = selection.mapTo(HashSet()) { id ->
                store.objectOf(id)?.takeIf { it.generated != null }?.copyKey
            }
            val group = (keys.singleOrNull() ?: return null).substringBefore('@')
            return SelectedPhrase(group, selection, bounds ?: return null)
        }

    override fun setFill(colorArgb: Int?, opacity: Float) = editRecords { shape, record ->
        // A line has no interior. Refused here rather than written and ignored by the painter,
        // because a record that says "filled" for something unfillable is a value two builds could
        // read differently — and it would survive into a build that *can* fill it.
        if (!ShapeGeometry.isFillable(shape.obj.shape)) {
            null
        } else {
            record.copy(fillArgb = colorArgb, fillOpacity = opacity.coerceIn(0f, 1f))
        }
    }

    override fun setCornerRadius(radiusPt: Float) = editRecords { shape, record ->
        if (!ShapeGeometry.roundsCorners(shape.obj.shape)) null else record.copy(cornerRadiusPt = radiusPt)
    }

    override fun setRoute(route: ConnectorRoute) = editRecords { _, record ->
        // A plain shape has no route, and writing one would put a string in the file for every
        // rectangle in the selection — see `ShapeEdit.create` on why a default record is omitted.
        if (!record.isConnector) null else record.copy(route = route)
    }

    override fun setArrowHead(shown: Boolean) = editRecords { shape, record ->
        // Refused for anything that is not a segment, and for a connector — `countShapes` says why
        // the second one, and the first is `setFill`'s rule: a record that claims a head for a
        // rectangle is a value a later build could act on.
        if (!ShapeGeometry.bends(shape.obj.shape) || record.isConnector) null else record.copy(arrow = shown)
    }

    override fun makeRegular() {
        applyShapeGeometry { shape ->
            // ⚠️ The **live** vertex list, so pressing this twice is a no-op rather than a second
            // identical write — and so the chip disappears once there is nothing left to do.
            val edit = ShapeRegular.regularise(
                shape.obj.shape,
                shape.obj.widthPt,
                shape.obj.heightPt,
                shape.record.points,
                shape.transform,
            )
            ShapeChange(
                transform = edit?.transform,
                record = edit?.points?.let { shape.record.copy(points = it) },
            )
        }
    }

    /**
     * ⭐ The two actions that change a shape's **geometry from a button**, as one gesture each.
     *
     * ⚠️ **One batch**, which is why this exists rather than a call to [editRecords] or to
     * [commit]: the two are different ops for different kinds of the same gesture — a segment moves
     * its box and a closed shape its vertices — and a selection can hold both. Two batches would be
     * two undo entries for one press. `applyAsGesture` is one `beginEdit`/`endEdit` bracket, which
     * is one entry (SPEC §4.6).
     *
     * ⚠️ The record **baseline** comes first and in its own batch, exactly as [editRecords] does and
     * for the identical reason: `SetTextContent` cannot say "unset", so the first record any plain
     * shape gets has no inverse and `UndoStack.commit` would drop the whole gesture — the press
     * eaten, the shape changed. Its own inverse is empty, so it adds no entry of its own.
     */
    private fun applyShapeGeometry(change: (DryShape) -> ShapeChange) {
        val recorder = store.recorder ?: return
        val edits = selection.mapNotNull { id ->
            val shape = store.shapeOf(id) ?: return@mapNotNull null
            val next = change(shape)
            if (next.isEmpty || next.record == shape.record) return@mapNotNull null
            shape to next
        }
        if (edits.isEmpty()) return
        applyAsGesture(
            edits
                .filter { (shape, next) -> next.record != null && shape.record.isDefault }
                .map { (shape, _) ->
                    ShapeEdit.writeRecord(shape.id, shape.record, recorder::newOpId, recorder::newClock)
                },
        )
        val matrices = edits.mapNotNull { (shape, next) -> next.transform?.let { shape.id to it } }.toMap()
        val moves = SelectionEdit.transformEach(
            store.selected(matrices.keys),
            { obj -> matrices[obj.id] },
            recorder::newOpId,
            recorder::newClock,
        )
        val records = edits.mapNotNull { (shape, next) ->
            next.record?.let { ShapeEdit.writeRecord(shape.id, it, recorder::newOpId, recorder::newClock) }
        }
        applyAsGesture(moves + records)
    }

    /** What one shape's geometry button changes: a placement, a record, or both. */
    private class ShapeChange(val transform: Matrix3?, val record: ShapeRecord?) {
        val isEmpty: Boolean get() = transform == null && record == null
    }

    /**
     * One `SetTextContent` per selected shape whose record actually changes — one gesture, so one
     * undo entry (SPEC §4.6).
     *
     * ⚠️ **[change] returns null to mean "not this one"**, and the whole record otherwise. The
     * record is read from the [DryShape] and copied, never rebuilt: it carries a `fillOpacity` this
     * toolbar does not set and an `extras` map a *later build* may have written (SPEC §4.8.2), and
     * reconstructing it from the fields the caller knows about would drop both — the same
     * forward-compatibility loss `OpExtras` exists to prevent, one level down.
     *
     * A record that comes back equal writes nothing, so pressing "elbow" twice costs one op rather
     * than two, and an object the change does not apply to is skipped rather than rewritten.
     */
    private fun editRecords(change: (DryShape, ShapeRecord) -> ShapeRecord?) {
        val recorder = store.recorder ?: return
        val edits = selection.mapNotNull { id ->
            val shape = store.shapeOf(id) ?: return@mapNotNull null
            val next = change(shape, shape.record) ?: return@mapNotNull null
            if (next == shape.record) return@mapNotNull null
            shape to next
        }
        // ⭐ The record register's own first-`SetStyle` trap (SPEC §4.2), and the same cure
        // [restyle] uses. The inverse of a `SetTextContent` is the register's previous value,
        // and for a plain shape the register is *unset* — the op cannot say "unset",
        // `Undo.inverseOf` answers null, and `UndoStack.commit` drops the gesture. So "fill a
        // fresh box, press undo" popped the gesture BEFORE the fill: the box vanished, filled.
        // The baseline writes the record the shape already shows (the default), which renders
        // identically, produces no undo entry of its own (its own inverse is null, so `commit`
        // drops it), and gives the change an inverse to restore. `isDefault` covers "unset"
        // and, harmlessly, "explicitly default" — the baseline is then the value already there.
        applyAsGesture(
            edits
                .filter { (shape, _) -> shape.record.isDefault }
                .map { (shape, _) ->
                    ShapeEdit.writeRecord(shape.id, shape.record, recorder::newOpId, recorder::newClock)
                },
        )
        applyAsGesture(
            edits.map { (shape, next) ->
                ShapeEdit.writeRecord(shape.id, next, recorder::newOpId, recorder::newClock)
            },
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
        applyAsGesture(styleBaseline(recorder))
        applyAsGesture(styleChange(recorder, change))
    }

    /**
     * The "write what it already looks like" half. See [restyle]'s KDoc for why it exists.
     *
     * Split out of [restyle] so [recolour] can put it in the *same* batch as the record baseline it
     * also needs — two halves of one gesture rather than two gestures.
     */
    private fun styleBaseline(recorder: PageOpRecorder): List<PageOp> = selection
        .mapNotNull { store.objectOf(it) }
        .filter { it.style == null }
        .mapNotNull { dry ->
            dry.baselineStyle()?.let { style ->
                PageOp.SetStyle(recorder.newOpId(), recorder.newClock(), dry.id, style)
            }
        }

    /** The change half of [restyle], for the same reason [styleBaseline] is separate. */
    private fun styleChange(recorder: PageOpRecorder, change: (ObjectStyle) -> ObjectStyle): List<PageOp> =
        SelectionEdit.restyle(
            objects = store.selected(selection),
            // Each object's own style, with the one field the user changed. After the baseline,
            // `style` is non-null for every object in the selection, so the fallback is unreachable
            // in practice — and it is [baselineStyle] rather than an invented default so that if it
            // ever *is* reached, the object keeps what it is painted with instead of turning black.
            // ⚠️ `objectOf`, never `strokeOf`: reading through `strokeOf` answered null for a shape,
            // the DEFAULT_STYLE fallback fired, and recolouring a shape reset its width to 3 pt
            // while set-width recoloured it black — the M8a/M8b seam bug.
            styleOf = { obj ->
                val dry = store.objectOf(obj.id)
                change(dry?.style ?: dry?.baselineStyle() ?: DEFAULT_STYLE)
            },
            nextOpId = recorder::newOpId,
            nextClock = recorder::newClock,
        )

    /**
     * Copy the selection, offset so the copy is visibly a copy, and select it.
     *
     * Two construction paths by necessity, one gesture by design. A stroke's `AddObject` is
     * built by [PageOpRecorder.strokeDuplicated] because it carries a geometry blob and
     * encoding one must not happen on this thread (CLAUDE.md rule 7); a shape's whole gesture
     * is [ShapeEdit.duplicate] in `:core:model`, applied through the store's one door with
     * `record = true`. The *decisions* are core's either way: [SelectionEdit.keysAbove] for
     * where the copies sit in the stack and [SelectionGesture.move] for the offset.
     *
     * A connector's record is re-targeted at the copies for ends attached *inside* the
     * selection — a duplicated diagram keeps its own wiring — and left alone for ends attached
     * outside it, so that copy attaches to the same shape the original does. Ids are minted
     * up front for exactly this: a record may name a shape that comes later in the selection.
     */
    override fun duplicate() {
        val recorder = store.recorder ?: return
        if (selection.isEmpty()) return
        val sources = selection.mapNotNull { store.objectOf(it) }
        if (sources.isEmpty()) return
        val keys = store.allocateTopZ(sources.size)
        val offset = SelectionGesture.move(DUPLICATE_OFFSET_PT, DUPLICATE_OFFSET_PT)
        val ids = HashMap<ObjectId, ObjectId>(sources.size)
        for (source in sources) ids[source.id] = recorder.newObjectId()

        store.beginEdit()
        for ((index, source) in sources.withIndex()) {
            val id = ids.getValue(source.id)
            val transform = offset * source.transform
            when (source) {
                is DryStroke -> {
                    recorder.strokeDuplicated(source.stroke, id, source.kind, source.inputModel, transform, keys[index])
                    store.duplicate(source.id, id, transform, keys[index])
                }

                is DryShape -> store.applyOps(
                    ShapeEdit.duplicate(
                        source = source.obj,
                        id = id,
                        transform = transform,
                        style = source.style,
                        erased = source.erased.toAbsolute(),
                        z = keys[index],
                        record = source.record.remapTargets { ids[it] },
                        nextOpId = recorder::newOpId,
                        nextClock = recorder::newClock,
                    ),
                    brushes,
                    record = true,
                )

                is DryBox -> store.applyOps(copyOps(source, id, transform, keys[index]), brushes, record = true)
            }
        }
        store.endEdit()
        select(sources.map { ids.getValue(it.id) })
    }

    /**
     * ⭐ M8c — a text box or an image copied, as ops.
     *
     * Third construction path, and it is the *cheap* one: neither payload needs an encoder (a
     * stroke's does) and neither is a shape (whose whole gesture `ShapeEdit.duplicate` builds in
     * `:core:model`). What has to be right is what travels, and it is three things:
     *
     * - **The payload**, at the copy's id, transform and z key. ⚠️ With a **non-null style** — SPEC
     *   §4.9.2's rule, applied here because this is a place a text box gets *created*: seed it null
     *   and the copy's first recolour has no inverse and *"change the colour, press undo"* silently
     *   eats the press. [DryBox.effectiveStyle] is what is on the screen, so the copy looks like the
     *   original whether or not the original's register was ever written.
     * - **The format register**, which has no payload home at all — it is the one thing that would
     *   be silently lost, and losing it means a duplicated caption comes back in the default font.
     *   Exactly the failure `copiedObjectOps` exists to prevent for a shape's record.
     * - **The erased region**, absolutely, so a half-erased picture copies half-erased. Anything
     *   else would make "duplicate" a way to recover erased content, which is a surprise either way.
     *
     * ⚠️ **An image's copy names the original's asset and copies no bytes.** SPEC §4.9.4: asset
     * names are UUIDs, lifetime is refcount-free, and *"a duplicated note and its original can name
     * one entry"*. Within one note there is nothing to copy and nothing may ever delete an entry on
     * the grounds that no object in this note names it.
     */
    private fun copyOps(source: DryBox, id: ObjectId, transform: Matrix3, z: FractionalKey): List<PageOp> {
        val recorder = store.recorder ?: return emptyList()
        val style = source.effectiveStyle()
        val erased = source.erased.toAbsolute()
        val ops = ArrayList<PageOp>(COPY_OPS)
        when (source) {
            is DryTextBox -> ops += PageOp.AddObject(
                recorder.newOpId(),
                recorder.newClock(),
                TextBoxObject(
                    id = id,
                    text = source.content.orEmpty(),
                    widthPt = source.widthPt,
                    heightPt = source.heightPt,
                    transform = transform,
                    style = style,
                    z = z,
                    erased = erased,
                ),
            )

            is DryImage -> {
                ops += PageOp.AddObject(
                    recorder.newOpId(),
                    recorder.newClock(),
                    ImageObject(
                        id = id,
                        asset = source.asset,
                        widthPt = source.widthPt,
                        heightPt = source.heightPt,
                        transform = transform,
                        style = style,
                        z = z,
                        erased = erased,
                    ),
                )
                // A text box's words ride on its payload; an image's content register has no home
                // there (SPEC §4.9.2's table leaves it free), so a peer's write to it travels as
                // its own op or is dropped.
                source.content?.let { ops += PageOp.SetTextContent(recorder.newOpId(), recorder.newClock(), id, it) }
            }
        }
        source.format?.let { ops += PageOp.SetFormat(recorder.newOpId(), recorder.newClock(), id, it) }
        return ops
    }

    // -----------------------------------------------------------------------
    // Geometry helpers
    // -----------------------------------------------------------------------

    /** A constant size on the glass, so a handle is grabbable at every zoom. */
    private fun handleReach(camera: Camera): Float = HANDLE_TOUCH_DP * displayDensity / camera.scale

    /** A constant snap distance on the glass, like [handleReach] — see the note in [extend]. */
    private fun snapTolerance(camera: Camera): Float = SNAP_TOLERANCE_DP * displayDensity / camera.scale

    /**
     * How near the chord a bend counts as straight, on the glass.
     *
     * ⚠️ Divided by the camera scale and **not** by [pixelsPerPoint], which is the pair of
     * conversions M8b got wrong for the connector bind radius: a *reach on the glass* is view
     * pixels, and view pixels are page units times the camera scale. A width is the other one.
     */
    private fun straightenTolerance(camera: Camera): Float = ShapePoints.STRAIGHTEN_DP * displayDensity / camera.scale

    private fun tapSlop(camera: Camera): Float = TAP_SLOP_DP * displayDensity / camera.scale

    /** How far the pen may wander in total and still have been a tap. See [settleLasso]. */
    private fun tapPathSlop(camera: Camera): Float = TAP_PATH_SLOP_DP * displayDensity / camera.scale

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

        /** One number for the hit test and for the toolbar that keeps out of it. */
        const val HANDLE_TOUCH_DP = InkSelection.HANDLE_REACH_DP
        const val TAP_SLOP_DP = 8f

        /**
         * Total path a tap may wander, in dp. Replaces `TAP_MAX_POINTS = 3`, which was the same
         * test expressed in *samples* and therefore fired after 3 pt of travel — see [settleLasso].
         *
         * Generous on purpose: the cost of calling a tiny lasso a tap is that the user selects the
         * one object under their pen instead of the nothing their loop enclosed, and the cost of
         * the reverse is the bug this replaced.
         */
        const val TAP_PATH_SLOP_DP = 28f

        /** Enough that the copy is obviously not the original, small enough to stay on screen. */
        const val DUPLICATE_OFFSET_PT = 18f

        /** `AddObject`, and at most one op per record register — see [SelectionController.copyOps]. */
        const val COPY_OPS = 3

        const val MIN_SIZE_PT = 8f
        const val MIN_SIZE_FRACTION = 0.05f

        /** About 2 mm on the glass at any zoom — what [AlignmentGuides]' page-space default only was at 100%. */
        const val SNAP_TOLERANCE_DP = 12f

        /**
         * Unreachable in practice: every selectable kind answers [baselineStyle]. Kept so a
         * future kind that slips through keeps *a* style rather than crashing — this was the
         * live fallback for shapes for one milestone, which made recolouring a shape reset its
         * width to 3 pt and set-width recolour it black.
         */
        val DEFAULT_STYLE = ObjectStyle(colorArgb = 0xFF1A1A1A.toInt(), widthPt = 3f)
    }
}

/**
 * One of [SelectionController.points]: a shape handle and where it currently is on the page.
 *
 * The local point and the page point are carried together because the two consumers want different
 * ones — the overlay draws at [page], the solve in `ShapePoints` works from [ShapePoint.local] — and
 * deriving either from the other at the call site would be a second place the shape's transform is
 * applied.
 */
internal data class PointHandle(val point: ShapePoint, val page: PagePoint)

/**
 * The shape a point drag is editing, as it was when the finger went **down**.
 *
 * A snapshot rather than the [DryShape] itself, for the reason [SelectedObject] is one: holding the
 * live object would make it far too easy to read `transform` mid-drag — which is the previewed
 * matrix, not the one the gesture must compose against — and that mistake looks like acceleration
 * rather than like a bug.
 */
internal data class PointTarget(
    val id: ObjectId,
    val kind: ShapeKind,
    val widthPt: Float,
    val heightPt: Float,
    val transform: Matrix3,
    /** The record's vertices at pen-down, or null if this shape has never been point-edited. */
    val points: List<PagePoint>?,
)

/** The style a stroke was drawn with, read off its brush — see [SelectionController.restyle]. */
internal fun DryStroke.capturedStyle(): ObjectStyle =
    ObjectStyle(colorArgb = stroke.brush.colorIntArgb, widthPt = stroke.brush.size)

/**
 * The style an object shows on screen right now — what a first restyle's baseline writes, so
 * that undoing the restyle puts back what was actually painted. A stroke's is its capture-time
 * style, readable only in this module (off the decoded brush); a shape's is the painter's own
 * fallback chain ([DryShape.effectiveStyle]).
 *
 * ⭐ **M8c: never null.** It was null for the one kind that could not be selected at all, and there
 * is no such kind any more — every [DryObject] paints itself and therefore answers the geometric
 * predicates. The nullable return is kept because a *future* kind might arrive before it can be
 * styled, and `restyle`'s `mapNotNull` is what would then skip it rather than write a black
 * baseline over it.
 */
internal fun DryObject.baselineStyle(): ObjectStyle? = when (this) {
    is DryStroke -> capturedStyle()

    is DryShape -> effectiveStyle()

    // ⭐ M8c. SPEC §4.9.2 requires a text box and an image to be *created* with a non-null style
    // precisely so this is never needed — but this module cannot make a peer obey that, and a
    // baseline of "what is on the screen" is the same fallback chain the painter uses.
    is DryBox -> effectiveStyle()
}

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

    /** Whether this grip is within [reach] of ([x], [y]) — [at], asked of one grip. */
    fun isWithin(x: Float, y: Float, box: PageRect, reach: Float): Boolean {
        val position = positionOn(box, ROTATE_OFFSET_PT)
        return hypot(x - position.x, y - position.y) <= reach
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
