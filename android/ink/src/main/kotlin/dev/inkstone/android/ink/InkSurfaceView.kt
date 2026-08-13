package dev.inkstone.android.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.FitMode
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.Offset
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.Viewport
import dev.inkstone.core.model.ZoomLimits
import dev.inkstone.core.model.cameraFor
import dev.inkstone.core.model.centredOn
import dev.inkstone.core.model.leashedTo
import dev.inkstone.core.model.zoomedAround

/**
 * The ink surface. SPEC §3, and the reason M1 exists.
 *
 * ## Structure
 *
 * ```
 * InkSurfaceView (this)
 *   ├── DryStrokesView          finished strokes, page space, camera as a canvas transform
 *   └── InProgressStrokesView   wet ink, front-buffered, drawn above
 * ```
 *
 * View-based and wrapped in `AndroidView` by `:app` (SPEC §3.2). The
 * `ink-*-compose` artifacts are deliberately not used: the set is incomplete on
 * the 1.0.0 line, and moving the ink stack to 1.1.0-alpha to obtain them would
 * put an alpha in the single most important code path in the app.
 *
 * ## Coordinate spaces (SPEC §4.4, §4.5)
 *
 * `androidx.ink`'s "world space" **is this app's page space**. A stroke is
 * started with `motionEventToWorldTransform` = the view→page matrix of the
 * camera at that instant, and `strokeToWorldTransform` = identity, so
 * `Stroke.shape` comes out already in page space and nothing has to convert it
 * afterwards.
 *
 * That also satisfies SPEC §4.5's *"a camera change mid-stroke does not
 * retroactively alter the stroke"* by construction rather than by discipline:
 * `startStroke` captures the matrix once, and `addToStroke` takes no matrix, so
 * later inputs of the same stroke cannot be interpreted through a newer camera.
 *
 * ## Prediction (SPEC Appendix B #2 — spike resolved in M1)
 *
 * `androidx.ink` 1.0.0 does **not** predict internally. Verified against the
 * shipped 1.0.0 artifacts three ways: `ink-authoring-android`'s POM declares no
 * dependency on `androidx.input:input-motionprediction`; there is no Kalman or
 * predictor class anywhere in the ink stack; and both
 * `InProgressStrokesView.addToStroke` and `InProgressStroke.enqueueInputs` take
 * predicted input as an explicit caller-supplied parameter alongside the real
 * input, tracked separately (`getRealInputCount`/`getPredictedInputCount`) so
 * that ink can discard the predicted tail when the next real event arrives.
 *
 * So `input-motionprediction` is **kept**, and it is the only predictor in the
 * pipeline. There is no double prediction, and ink's own bookkeeping is what
 * guarantees no predicted geometry survives into a finished stroke.
 * [predictionEnabled] exists so the difference can still be felt on the device.
 * See `docs/M1-INK.md`.
 */
public class InkSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val displayMetrics = resources.displayMetrics
    private val pixelsPerPoint = displayMetrics.densityDpi / POINTS_PER_INCH
    private val zoomLimits = ZoomLimits.forDisplay(pixelsPerPoint)

    private val textureStore = NoTextures

    /**
     * Public so a caller can pre-build strokes with [SyntheticInk] on a
     * background thread using exactly the brushes this surface would use.
     */
    public val brushFactory: InkBrushFactory = InkBrushFactory(displayMetrics.density, pixelsPerPoint)

    private val strokes = StrokeStore()

    private val dryView = DryStrokesView(context, strokes, textureStore)

    /** M8a's chrome, between the ink and the wet layer. See [SelectionOverlayView]. */
    private val overlay = SelectionOverlayView(context)

    private val wetView = InProgressStrokesView(context)

    /** How many finished strokes are on the page. */
    public val strokeCount: Int get() = strokes.size

    private var predictor: MotionEventPredictor? = null

    // --- Input state --------------------------------------------------------
    private var strokePointerId = MotionEvent.INVALID_POINTER_ID
    private var activeStrokeId: InProgressStrokeId? = null
    private var activeStrokeKind = InkBrushKind.FOUNTAIN

    private val eraser = EraserController(strokes, displayMetrics.density) {
        dryView.onStoreChanged()
        onStrokeCountChanged?.invoke(strokes.size)
    }

    /**
     * ⭐ M8a — the selection tool. Built to [EraserController]'s shape; see its own KDoc for
     * why the ops it writes are stamped here rather than in `NoteEditor`.
     */
    // The explicit type is required: the callback reads `selection` back, and without it the
    // compiler cannot infer the property's type from an initialiser that mentions the property.
    private val selectionController: SelectionController =
        SelectionController(strokes, brushFactory, displayMetrics.density, pixelsPerPoint) {
            dryView.onStoreChanged()
            overlay.onSelectionChanged()
            onStrokeCountChanged?.invoke(strokes.size)
            onSelectionChanged?.invoke(selectionController.size)
            onHistoryChanged?.invoke()
        }

    private val gestures = CameraGestureTracker(zoomLimits)
    private val palmGuard = PalmGuard()
    private val taps = MultiFingerTapDetector(context)

    /**
     * How much of the page must stay on screen, whatever the user does.
     *
     * SPEC §4.5's camera used to pin the page to the viewport; that was rejected
     * on the tablet for fighting the user. Panning is free now, and this is the
     * only bound: enough page left to grab, so no gesture can strand you on a
     * blank screen (see `Camera.leashedTo`).
     */
    private val minVisiblePx = MIN_PAGE_VISIBLE_DP * displayMetrics.density

    // Reused — the ink path must not allocate (CLAUDE.md rule 7).
    private val cameraMatrix = CameraMatrix()

    /** SPEC §3.6 — a measurable end-to-end latency number, not vibes. */
    public val latency: LatencyStats = LatencyStats()

    /**
     * What the digitiser reports for pressure and tilt (SPEC §3.3).
     *
     * Measurement scaffolding, like [latency]. It exists because "the pen doesn't react to
     * how hard you press" is a report the app could not previously answer: a stylus that
     * reports no pressure and a brush whose response is too weak look identical from the
     * outside and need opposite fixes.
     */
    public val stylus: StylusStats = StylusStats()

    // --- Configuration ------------------------------------------------------

    public var pen: PenConfig = PenConfig.Default

    /**
     * Which tool the toolbar has selected. The flipped pen tip and barrel button are per-event
     * and override this (SPEC §3.3).
     *
     * ⭐ M8a replaced a `eraserSelected: Boolean` with this. Adding selection as a second
     * boolean would have made "the eraser and the lasso are both active" a representable state
     * that nothing could resolve, which is the same shape as the `undoable` flag M6 deleted —
     * a wrong call that is expressible is eventually made.
     */
    public var tool: InkTool = InkTool.PEN

    /**
     * SPEC §3.4 — "steal this from Goodnotes: after erasing, auto-return to the
     * previously selected pen. Two lines, and their founder cited it as
     * exemplifying the whole product strategy."
     */
    public var autoReturnToPenAfterErase: Boolean = true

    /**
     * Off by default: fingers pan and zoom, they do not draw. This is half of
     * palm rejection — a palm is delivered as a finger, and a surface where
     * fingers cannot mark cannot be marked by a palm. On for emulator work,
     * where there is no stylus.
     */
    public var fingerDrawingEnabled: Boolean = false

    /** The A/B switch for the SPEC Appendix B #2 spike. */
    public var predictionEnabled: Boolean = true

    /**
     * The input smoothing model — the "does it track the nib or chase a
     * spline" knob. See [InkInputModel]. Affects strokes drawn from now on;
     * strokes already on the page keep the geometry they were captured with.
     */
    public var inputModel: InkInputModel
        get() = brushFactory.inputModel
        set(value) {
            brushFactory.inputModel = value
            // The blob records which model a stroke was captured with, so replay matches
            // capture even after the setting changes (SPEC §5.4).
            strokes.inputModel = value
        }

    /**
     * SPEC §3.5, revised in M1: the eraser removes what it passes over, not
     * the whole line. See [EraseMode].
     */
    public var eraseMode: EraseMode
        get() = eraser.mode
        set(value) {
            eraser.mode = value
        }

    /**
     * The page's extent in points — the CropBox with `/Rotate` applied (SPEC §4.4), never the
     * MediaBox.
     *
     * ⚠️ **Assigning this no longer refits the camera, and that is the point.** It used to, which
     * made *every* write to it a camera move — `loadPage` wanted one and nothing else did, and
     * "nothing else" turned out to include a peer's paper change arriving mid-stroke. A setter two
     * indirections away from anything camera-shaped is not somewhere the SPEC §13.2 row 10 rule
     * ("the view must not jump") can be enforced or even noticed, so the two callers now say which
     * they want: [loadPage] refits explicitly, [setPageMeta] re-leashes.
     */
    public var pageSize: PageSize = PageSize.Letter
        set(value) {
            field = value
            dryView.pageSize = value
            overlay.pageSize = value
        }

    /** The paper under the ink. Changed through [loadPage] or [setPageMeta], never on its own. */
    public val pageBackground: Background get() = dryView.pageBackground

    public var camera: Camera = Camera.Identity
        private set(value) {
            field = value
            dryView.camera = value
            overlay.camera = value
            onCameraChanged?.invoke(value)
        }

    public var onCameraChanged: ((Camera) -> Unit)? = null
    public var onStrokeCountChanged: ((Int) -> Unit)? = null

    /**
     * ⭐ M8a — how many objects are selected, so the toolbar can offer the actions that need
     * one and hide them when there is nothing to act on.
     *
     * A nullable callback rather than a method on `NoteEditor.NoteSurface`, for the reason
     * `onStrokeCaptured` is: a method there acquires a `= Unit` default and a recording fake in
     * front of it, which is M6's `setPageMeta` — every assertion true of the editor and false
     * of the app. This has one implementation, it is this class, and there is nothing to stub.
     */
    public var onSelectionChanged: ((Int) -> Unit)? = null

    /**
     * ⭐ M12 — a stroke the user just drew, and the monotonic time its **down event** arrived
     * (docs/M12-AUDIO.md §3).
     *
     * ## Why it is a nullable callback here and not a method on `NoteEditor.NoteSurface`
     *
     * `NoteSurface` is the seam `:app` writes tests against, and a method added there acquires a
     * `= Unit` default and a recording fake in front of it. That is M6's `setPageMeta` exactly —
     * *"the milestone's most convincing invisible bug"*: every assertion true of the editor and
     * false of the app, because the only production implementation did nothing. This callback has
     * one implementation, it is this class, and there is nothing to stub.
     *
     * ## The down event, not the landing
     *
     * The alignment unit is a line of writing and §7's window is asymmetric — **writing lags
     * speech** — so the anchor has to be when the mark *began*. A slow underline is a second long,
     * which is a tenth of the asymmetry the window is built out of.
     *
     * ⚠️ Fired from [FinishedStrokes], **not** from `StrokeStore.add`. `addAll` loops `add` and is
     * what the toolbar's "load 5,000 strokes" button drives, so firing from there would put five
     * thousand entries in a lecture's timeline in one frame. Page loads, undo-of-delete and a
     * peer's merged strokes all arrive through `applyOps` and never touch this path at all.
     */
    public var onStrokeCaptured: ((ObjectId, Long) -> Unit)? = null

    /**
     * When the live stroke's `ACTION_DOWN` arrived, on `SystemClock.elapsedRealtime`.
     *
     * One field is enough because [beginStroke] returns early while a stroke is already live —
     * this surface never has two at once.
     */
    private var strokeStartedAtElapsed: Long = 0L

    /** Fired after any edit, so a toolbar can refresh its state and autosave can run. */
    public var onHistoryChanged: (() -> Unit)? = null

    /**
     * SPEC §4.6 — **undo is a new op appended to the log, never a pop.**
     *
     * M1 kept its own stack here because there was no log to append to. M2 has one, so this
     * view no longer decides what an undo means: it asks, and the ops that come back arrive
     * through [applyOps]. That is what keeps the screen and the folded log in agreement —
     * there is one mechanism, so there is nothing to diverge.
     *
     * Bound to a two-finger tap as well as the toolbar; three fingers redo.
     */
    public var onUndoRequested: (() -> Unit)? = null

    public var onRedoRequested: (() -> Unit)? = null

    /** Whether the toolbar should offer undo. Owned by the session, pushed in by `:app`. */
    public var canUndo: Boolean = false

    public var canRedo: Boolean = false

    public fun undo() {
        onUndoRequested?.invoke()
    }

    public fun redo() {
        onRedoRequested?.invoke()
    }

    // -----------------------------------------------------------------------
    // The op log (SPEC §4.2, §5.1)
    // -----------------------------------------------------------------------

    /**
     * Bind this surface to a page's log.
     *
     * Ids and clocks are supplied by the caller because they belong to the note, not to a
     * view: the Lamport counter has to continue the note's own history (SPEC §4.1's
     * `max + 1`), and a view that minted its own would restart it on every rotation.
     */
    public fun bindPage(nextObjectId: () -> ObjectId, nextOpId: () -> String, nextClock: () -> LamportClock) {
        strokes.recorder = PageOpRecorder(nextObjectId, nextOpId, nextClock)
    }

    /**
     * Show a folded page.
     *
     * The objects arrive already folded, so their absolute registers — including the erased
     * region — are the winning values and nothing here replays a log. Decoding the geometry
     * blobs is the cost, and it is a page-switch cost, not an ink-path one.
     */
    public fun loadPage(page: FoldedPage) {
        wetView.cancelUnfinishedStrokes()
        // Before `reset`, so the overlay is not briefly drawing a box round ids that no longer
        // exist. A selection belongs to a page: carrying it across would leave the toolbar
        // offering "delete" for objects on a page the user has left.
        selectionController.clear()
        strokes.reset()
        pageSize = page.size
        // Explicit, because the setter above no longer does it. A page *switch* should fit the new
        // page — it is a different piece of paper and the old camera means nothing on it.
        resetCamera(FitMode.FIT_WIDTH)
        dryView.pageBackground = page.background
        // Cleared, not kept: the next page's paper is not this one's, and leaving the
        // old bitmap up means an imported page briefly shows the previous page's scan.
        dryView.sourcePage = null
        strokes.applyOps(
            ops = page.objects.filterIsInstance<StrokeObject>().map { obj ->
                // A synthetic AddObject: `applyOps` is the one path into the render state, so
                // loading goes through it rather than around it.
                PageOp.AddObject(opId = "", clock = LOAD_CLOCK, obj = obj)
            },
            brushes = brushFactory,
            record = false,
        )
        dryView.onStoreChanged()
        onStrokeCountChanged?.invoke(strokes.size)
    }

    /**
     * Change the paper and the page's extent **in place** — no camera move, no lost stroke.
     *
     * This is [loadPage] minus everything [loadPage] does that a *live* meta change must not do,
     * and the list is the whole reason the method exists (SPEC §13.2 row 10):
     *
     * - **No `cancelUnfinishedStrokes`.** The stroke under the pen survives. Its geometry is safe
     *   by construction — `startStroke` captures the view→page transform once and page space has
     *   its origin at the page's top-left, so a [PageSize] is an *extent* and changing it moves no
     *   point of any stroke, wet or dry.
     * - **No `strokes.reset()`.** Nothing on screen is rebuilt, so nothing flickers and the open
     *   gesture bracket is not dropped mid-stroke.
     * - **No refit.** [Camera.leashedTo] keeps the current pan and zoom and only clamps the pan
     *   back inside SPEC §4.5's leash, which matters when the page *shrinks* and is an identity
     *   when the camera is already in bounds. A refit would be unrecoverable: the camera is
     *   persisted nowhere, so once refit the user's place in the page is gone.
     *
     * The repaint comes from `DryStrokesView`'s own setters, each of which invalidates —
     * `onStoreChanged()` would be a no-op here because a metadata change bumps no store revision.
     *
     * ⚠️ **The rasterised source page is cleared.** Whatever is in it was rendered for the paper
     * and the size this call is replacing, and leaving it up puts the old scan under the new
     * template. Re-rendering belongs to `:app` (`InkScreen`), which owns `:android:pdf` — same
     * split as [loadPage] and [sourcePage], for the same reason: folding is immediate and
     * rasterising a page of a 200-page scan is not.
     *
     * ⚠️ **The leash is guarded on a non-zero viewport.** `leashedTo` builds a `coerceIn` range
     * from the viewport extent, and with a zero one that range inverts and `Float.coerceIn`
     * throws. [resetCamera] and `onSizeChanged` both guard the same way; the old `pageSize` setter
     * was safe only because it went through [resetCamera].
     */
    public fun setPageMeta(size: PageSize, background: Background) {
        if (size == pageSize && background == dryView.pageBackground) return
        pageSize = size
        dryView.pageBackground = background
        dryView.sourcePage = null
        if (width > 0 && height > 0) camera = camera.leashed()
    }

    /**
     * SPEC §8.4 — mark where a search hit landed, and bring it into view.
     *
     * ⚠️ **The camera move goes through the existing leash, not through a fresh `resetCamera`.**
     * `Camera.leashedTo` builds a `coerceIn` range from the viewport extent and a zero viewport
     * inverts that range and throws, which is why every camera move in this class is guarded on
     * having been laid out. A search hit can perfectly well arrive before the surface has a
     * size — the note opens *because* of the hit — so it is the one caller most likely to be
     * early.
     *
     * @param box null clears the highlight.
     */
    public fun highlight(box: dev.inkstone.core.model.PageRect?) {
        dryView.highlight = box
        if (box == null || width <= 0 || height <= 0) return
        camera = camera.centredOn(box.centreX, box.centreY, width.toFloat(), height.toFloat())
    }

    /**
     * SPEC §6.2 — the imported PDF page this note page was made from, rasterised.
     *
     * Set after [loadPage], by whatever rendered it (`:android:pdf`). Separate from
     * [loadPage] on purpose: folding a page is immediate and rasterising a page of a
     * 200-page scan is not, and a note that waited for its paper before showing its
     * ink would feel slower than one that draws the ink and fills the paper in behind.
     */
    public var sourcePage: Bitmap?
        get() = dryView.sourcePage
        set(value) {
            dryView.sourcePage = value
        }

    /**
     * Take everything recorded since the last call, one list per gesture.
     *
     * **Call this off the UI thread** — it encodes stroke geometry, which CLAUDE.md rule 7
     * forbids on the ink path. The queue is concurrent and finished strokes are immutable, so
     * the user can keep writing while this runs.
     */
    public fun drainOps(): List<List<PageOp>> = strokes.recorder?.drain() ?: emptyList()

    /**
     * Apply ops to the screen: undo, redo, or — from M6 — a peer's edits.
     *
     * @param record true when the ops are new work that still has to be journaled, which is
     *   the case for undo and redo (they are new ops, SPEC §4.6) and not for a page load.
     */
    public fun applyOps(ops: List<PageOp>, record: Boolean = true) {
        if (ops.isEmpty()) return
        strokes.applyOps(ops, brushFactory, record)
        dryView.onStoreChanged()
        onStrokeCountChanged?.invoke(strokes.size)
        onHistoryChanged?.invoke()
    }

    /**
     * Fired when [autoReturnToPenAfterErase] has just put [tool] back to [InkTool.PEN],
     * so the toolbar's own copy of the tool state can follow. Nothing else in
     * this view changes the tool behind the caller's back.
     */
    public var onEraserAutoReleased: (() -> Unit)? = null

    // -----------------------------------------------------------------------
    // Selection (ROADMAP M8a)
    // -----------------------------------------------------------------------

    /**
     * ⭐ What the toolbar does to a selection — see [InkSelection].
     *
     * One handle rather than seven methods on this class. `InkSurfaceView` already owns ink
     * capture, a camera, a page, an undo bridge and a recording hook; the commands read better as
     * `surface.selection.bringToFront()` than as a seventh group of top-level members, and the
     * interface is what `:app` holds so the controller itself stays internal to this module.
     */
    public val selection: InkSelection get() = selectionController

    /** Applies SPEC §4.5's leash: pan freely, but never lose the page. */
    private fun Camera.leashed(): Camera =
        leashedTo(pageSize, Viewport(width.toFloat(), height.toFloat()), minVisiblePx)

    /** Zoom as a percentage, for the toolbar readout. */
    public fun zoomPercent(): Float = camera.zoomPercent(pixelsPerPoint)

    init {
        val fill = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        addView(dryView, fill)
        addView(overlay, fill)
        addView(wetView, fill)
        overlay.selection = selectionController

        // Warm the native ink stack and the texture atlas now, so the first
        // ACTION_DOWN of the session is not the one that pays for them.
        wetView.textureBitmapStore = textureStore
        wetView.eagerInit()
        wetView.addFinishedStrokesListener(FinishedStrokes())
        wetView.reportLatencyTo(latency)

        isFocusable = true
        setWillNotDraw(true)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        predictor = MotionEventPredictor.newInstance(this)
    }

    override fun onDetachedFromWindow() {
        wetView.cancelUnfinishedStrokes()
        predictor = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 && oldh == 0) {
            resetCamera(FitMode.FIT_WIDTH)
        } else if (width > 0 && height > 0) {
            camera = camera.leashed()
        }
    }

    // -----------------------------------------------------------------------
    // Camera (SPEC §4.5)
    // -----------------------------------------------------------------------

    public fun resetCamera(mode: FitMode) {
        if (width == 0 || height == 0) return
        camera = cameraFor(mode, pageSize, Viewport(width.toFloat(), height.toFloat()), pixelsPerPoint, zoomLimits)
    }

    /** Double-tap zoom: toggle between fit-width and 200% of it, around [focus]. */
    public fun toggleZoom(focus: Offset) {
        val fitWidth = width / pageSize.widthPt
        val target = if (camera.scale > fitWidth * ZOOM_TOGGLE_THRESHOLD) fitWidth else fitWidth * DOUBLE_TAP_ZOOM
        camera = camera.zoomedAround(focus, target / camera.scale, zoomLimits).leashed()
    }

    // -----------------------------------------------------------------------
    // Strokes
    // -----------------------------------------------------------------------

    /**
     * A private adapter rather than `InkSurfaceView : InProgressStrokesFinishedListener`.
     * Making this view implement an `androidx.ink` interface would put that type
     * in its public supertypes, which is exactly the third-party-type leak
     * across a module boundary that CLAUDE.md forbids — and `:app` would then
     * fail to compile without an `api` dependency on the whole ink stack.
     */
    private inner class FinishedStrokes : InProgressStrokesFinishedListener {
        override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
            val startedAt = strokeStartedAtElapsed
            for (stroke in strokes.values) {
                val objectId = this@InkSurfaceView.strokes.add(stroke, activeStrokeKind)
                // M12 — null only for the unbound fallback id, which never reaches disk and must
                // never reach a timeline either.
                if (objectId != null && startedAt != 0L) onStrokeCaptured?.invoke(objectId, startedAt)
            }
            dryView.onStoreChanged()
            // Hand off only after the dry layer has been told to redraw, so the
            // stroke is never absent from both layers for a frame.
            wetView.removeFinishedStrokes(strokes.keys)
            this@InkSurfaceView.strokes.endEdit()
            onStrokeCountChanged?.invoke(this@InkSurfaceView.strokes.size)
            onHistoryChanged?.invoke()
        }
    }

    /**
     * Clear the page.
     *
     * Undoable since M2: it emits a `DeleteObject` per stroke, and a delete is a tombstone
     * with the geometry still in the log (CLAUDE.md rule 1).
     */
    public fun clearPage() {
        wetView.cancelUnfinishedStrokes()
        strokes.clear()
        dryView.onStoreChanged()
        onStrokeCountChanged?.invoke(strokes.size)
        onHistoryChanged?.invoke()
    }

    /**
     * Bulk-load finished strokes — the synthetic page the SPEC §3.6 frame-rate
     * gate needs. Build them with [SyntheticInk] off the main thread first.
     */
    public fun addPregeneratedStrokes(generated: SyntheticInk.Batch) {
        strokes.addAll(generated.items)
        dryView.onStoreChanged()
        onStrokeCountChanged?.invoke(strokes.size)
    }

    // -----------------------------------------------------------------------
    // Input (SPEC §3.3 — every row)
    // -----------------------------------------------------------------------

    /** Hover. Feeds the palm guard, and is where M10's hover preview will land. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isStylusPointer(0)) palmGuard.noteStylusEvent(event)
        return super.onGenericMotionEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility") // A drawing surface has no click semantics.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        predictor?.record(event)

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(event)

            MotionEvent.ACTION_MOVE -> onMove(event)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> onPointerUp(event)

            MotionEvent.ACTION_CANCEL -> {
                cancelEverything(event)
                true
            }

            else -> super.onTouchEvent(event)
        }
    }

    private fun onPointerDown(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val pointerId = event.getPointerId(index)

        if (event.isStylusPointer(index)) {
            palmGuard.noteStylusEvent(event)
            // A palm that landed a moment before the pen has already started a
            // gesture; drop it rather than letting it pan the page mid-sentence.
            gestures.abandon()
            taps.cancel()
            // SPEC §3.3, first row. Without this, input is batched to one sample
            // per frame and latency roughly doubles. It must be requested on the
            // DOWN of each gesture — it is not sticky.
            requestUnbufferedDispatch(event)
            beginDrawOrErase(event, index, pointerId, hardwareEraserAllowed = true)
            return true
        }

        val gestureInFlight = activeStrokeId != null || eraser.isActive || selectionController.isActive
        if (gestureInFlight || !palmGuard.allowsFinger(event)) return true

        taps.onPointerDown(event)

        if (fingerDrawingEnabled) {
            requestUnbufferedDispatch(event)
            beginDrawOrErase(event, index, pointerId, hardwareEraserAllowed = false)
            return true
        }

        gestures.addPointer(event, pointerId)
        return true
    }

    /**
     * SPEC §3.3 — flipping the S Pen reports `TOOL_TYPE_ERASER`, and holding the
     * barrel button shows up in the button state.
     *
     * Both `BUTTON_STYLUS_PRIMARY` and `BUTTON_SECONDARY` are checked because
     * OEMs disagree about which one an S Pen's single button reports, and a
     * quick-erase modifier that works on one tablet and not the next is worse
     * than none.
     */
    private fun beginDrawOrErase(event: MotionEvent, index: Int, pointerId: Int, hardwareEraserAllowed: Boolean) {
        val barrel = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY
        val hardwareEraser = hardwareEraserAllowed &&
            (event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER || (event.buttonState and barrel) != 0)

        if (hardwareEraser || tool == InkTool.ERASER) {
            // Only a hardware eraser is "temporary": it restores itself when the
            // pen is flipped back, so it must not trigger SPEC §3.4's auto-return.
            // A flipped tip beats the selection tool too — the eraser end of the pen
            // erases, whatever the toolbar says, which is the whole point of SPEC §3.3.
            eraser.begin(event, index, pointerId, temporary = hardwareEraser, camera = camera)
        } else if (tool == InkTool.SELECT) {
            selectionController.begin(event, index, pointerId, camera, pageSize)
        } else {
            beginStroke(event, pointerId)
        }
        // Reject anything else while the gesture is live: no second stroke, no
        // camera gesture, no palm.
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun beginStroke(event: MotionEvent, pointerId: Int) {
        if (activeStrokeId != null) return
        // M12 — latched here, used when the stroke finishes. `elapsedRealtime` rather than
        // `event.eventTime`, which is `uptimeMillis` and stops during deep sleep: a recording holds
        // the CPU awake, so the two agree in practice, but only one of them is the clock the
        // timeline's offsets are defined against.
        strokeStartedAtElapsed = SystemClock.elapsedRealtime()
        // One undo entry per stroke. Opened here and committed when the stroke
        // finishes, so the entry exists by the time onStrokesFinished inserts.
        strokes.beginEdit()
        activeStrokeKind = pen.kind
        strokePointerId = pointerId
        activeStrokeId = wetView.startStroke(
            event = event,
            pointerId = pointerId,
            brush = brushFactory.brushFor(pen),
            // The camera in effect at stroke start, and only that camera.
            motionEventToWorldTransform = cameraMatrix.viewToPage(camera),
            // Stroke space *is* page space, so nothing needs converting later.
            strokeToWorldTransform = IDENTITY,
        )
    }

    private fun onMove(event: MotionEvent): Boolean {
        val strokeId = activeStrokeId
        if (strokeId != null) {
            palmGuard.noteStylusEvent(event)
            // Four float compares. See StylusStats on why this is allowed here.
            event.findPointerIndex(strokePointerId).takeIf { it >= 0 }?.let { stylus.record(event, it) }
            // The whole MotionEvent goes in, historical samples and all —
            // androidx.ink walks getHistorySize()/getHistoricalAxisValue()
            // itself, and also reads pressure, tilt, orientation and tool type
            // straight off the event. Synthesising StrokeInputs by hand here
            // would silently drop every one of those (SPEC §3.3).
            val prediction = if (predictionEnabled) predictor?.predict() else null
            wetView.addToStroke(event, strokePointerId, strokeId, prediction)
            return true
        }
        if (eraser.isActive) {
            palmGuard.noteStylusEvent(event)
            eraser.extend(event, camera)
            return true
        }
        if (selectionController.isActive) {
            palmGuard.noteStylusEvent(event)
            selectionController.extend(event, camera)
            return true
        }
        if (!gestures.isActive || !palmGuard.allowsFinger(event)) return false
        taps.onMove(event)
        camera = gestures.onMove(event, camera).leashed()
        return true
    }

    private fun onPointerUp(event: MotionEvent): Boolean {
        val pointerId = event.getPointerId(event.actionIndex)
        val cancelled = event.isCancelledPointer()

        when (pointerId) {
            strokePointerId -> {
                val strokeId = activeStrokeId
                if (strokeId != null) {
                    if (cancelled) {
                        wetView.cancelStroke(strokeId, event)
                    } else {
                        wetView.finishStroke(event, pointerId, strokeId)
                    }
                }
                if (cancelled) strokes.endEdit()
                activeStrokeId = null
                strokePointerId = MotionEvent.INVALID_POINTER_ID
            }

            eraser.pointerId -> {
                if (!cancelled) eraser.extend(event, camera)
                releaseEraser()
            }

            selectionController.pointerId -> {
                if (cancelled) {
                    selectionController.cancel()
                } else {
                    selectionController.extend(event, camera)
                    selectionController.end(camera)
                }
            }

            else -> {
                gestures.removePointer(event, pointerId)
                onFingersLifted(event)
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) gestures.abandon()
        return true
    }

    /**
     * Two fingers tapped undoes, three redoes (SPEC §4.6).
     *
     * Checked only on the finger branch, so a stylus stroke or an erase can
     * never be mistaken for one, and [MultiFingerTapDetector] has already
     * disqualified anything that moved or lingered.
     */
    private fun onFingersLifted(event: MotionEvent) {
        when (taps.onPointerUp(event)) {
            UNDO_FINGERS -> undo()
            REDO_FINGERS -> redo()
            else -> Unit
        }
    }

    private fun cancelEverything(event: MotionEvent) {
        activeStrokeId?.let {
            wetView.cancelStroke(it, event)
            strokes.endEdit()
        }
        activeStrokeId = null
        strokePointerId = MotionEvent.INVALID_POINTER_ID
        releaseEraser()
        // Puts the previewed transforms back and writes nothing — a cancelled drag must not
        // leave the objects where the finger last was, because no op describes that position.
        selectionController.cancel()
        gestures.abandon()
        taps.cancel()
    }

    /**
     * SPEC §3.4's Goodnotes borrow: "after erasing, auto-return to the
     * previously selected pen."
     *
     * A flipped tip or a held barrel button needs no restoring — the tool is
     * decided per event — so this only has to undo a *toolbar* eraser selection.
     */
    private fun releaseEraser() {
        val wasToolbarEraser = eraser.end()
        if (wasToolbarEraser && autoReturnToPenAfterErase && tool == InkTool.ERASER) {
            tool = InkTool.PEN
            onEraserAutoReleased?.invoke()
        }
    }

    private companion object {
        const val POINTS_PER_INCH = 72f
        const val MIN_PAGE_VISIBLE_DP = 64f
        const val UNDO_FINGERS = 2
        const val REDO_FINGERS = 3
        const val DOUBLE_TAP_ZOOM = 2f

        /**
         * The clock on the synthetic `AddObject`s a page load builds. Never journaled —
         * `record` is false on that path — so its value is arbitrary, and it is a
         * recognisable sentinel rather than a plausible counter in case one ever escapes.
         */
        val LOAD_CLOCK = LamportClock(0, "load")
        const val ZOOM_TOGGLE_THRESHOLD = 1.2f

        val IDENTITY = Matrix()
    }
}
