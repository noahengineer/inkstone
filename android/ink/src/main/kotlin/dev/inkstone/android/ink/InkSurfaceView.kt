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
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.Offset
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PageStrip
import dev.inkstone.core.model.ShapeEdit
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.SnapGrid
import dev.inkstone.core.model.Viewport
import dev.inkstone.core.model.ZoomLimits
import dev.inkstone.core.model.cameraFor
import dev.inkstone.core.model.centredOn
import dev.inkstone.core.model.leashedTo
import dev.inkstone.core.model.zoomedAround
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot

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
// The project's detekt config already records why this rule judges this class worst: its members
// are lifecycle, camera, strokes, history, and one small named handler per input transition, and
// collapsing them into fewer larger methods to satisfy a count would make the ink path harder to
// read — which is the one place in this app where reading it easily matters most. M8b added two
// (`toPageSpace`, `trySnap`) and put the rest of draw-and-hold in `ShapeSnapController`, which is
// the extraction the rule is actually asking for. Suppressed here rather than by raising the
// shared threshold, so no other class inherits the exemption.
@Suppress("TooManyFunctions")
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

    /** ⭐ M8c's chrome — the straight edge and the laser. Its own view; see [ToolOverlayView]. */
    private val toolOverlay = ToolOverlayView(context)

    private val wetView = InProgressStrokesView(context)

    /** How many finished strokes are on the page. */
    public val strokeCount: Int get() = strokes.size

    /**
     * SPEC §3.6 diagnostics, for the Measure panel — the two facts about a page that change which
     * rendering cost dominates, and neither of which is visible from outside the process.
     *
     * See [StrokeStore.erasedCount] (a per-frame `saveLayer` each) and [StrokeStore.hasHighlighter]
     * (one of them disables the append fast path for every pen stroke on the page).
     */
    public val erasedCount: Int get() = strokes.erasedCount

    public val hasHighlighter: Boolean get() = strokes.hasHighlighter

    /**
     * Whether the dry layer is currently a rasterised texture rather than a replayed display list,
     * and the GPU's texture ceiling it was judged against.
     *
     * ⚠️ Surfaced because **an oversized layer is dropped silently** — no crash, no log, just the
     * slow path again. Without this the failure presents as "it is fast on my tablet and slow on
     * yours" with nothing to look at, and the ceiling itself must be read off the device rather
     * than assumed (the emulator reports the host GPU's).
     */
    public val dryLayerActive: Boolean get() = dryView.layerEnabled

    public val dryLayerRefused: Boolean get() = dryView.layerRefused

    public val maxTextureSize: String get() = "${dryView.maxTextureWidth}×${dryView.maxTextureHeight}"

    /**
     * The cached page's size **in device pixels** — i.e. the size of the texture a compositing
     * layer rasterises it into.
     *
     * ⚠️ Reported because getting this wrong is invisible in every test and obvious on the glass.
     * The first compositing-layer build positioned the node in *page* coordinates, so this read
     * roughly 918×574 while the viewport was 2560×1600 — a texture magnified ~2.8× — and the
     * symptom was "when you dont move around everything is very pixelated". A number that should
     * be at least the viewport's size, printed next to the viewport, makes that a glance instead
     * of an investigation.
     */
    public val dryLayerSize: String get() = "${dryView.nodeWidth}×${dryView.nodeHeight}"

    private var predictor: MotionEventPredictor? = null

    // --- Input state --------------------------------------------------------
    private var strokePointerId = MotionEvent.INVALID_POINTER_ID
    private var activeStrokeId: InProgressStrokeId? = null

    /**
     * Each stroke's pen kind, start time and ruler flag, from its down event until ink reports it
     * finished. Per stroke, because a stroke is reported after the next one may have begun. See
     * [StrokeStarts].
     */
    private val strokeStarts = StrokeStarts<InkBrushKind>()

    /**
     * Whether the live stroke is being drawn by a stylus. With finger drawing on, a *finger*
     * reaches [beginStroke], and its moves must not stamp [PalmGuard] as stylus activity —
     * that blacked out every finger gesture (undo tap, pan) for the guard's whole grace
     * window after each finger-drawn stroke.
     */
    private var activeStrokeIsStylus = true

    private val eraser = EraserController(strokes, displayMetrics.density) { gestureEnded ->
        // An erase can take the outermost ink off an infinite page, and the paper follows it back
        // down — the derivation is honest in both directions (SPEC §4.5).
        //
        // ⚠️ **At the end of the gesture only**, exactly as the selection's `onChanged` does it
        // below. This ran on every eraser sample that touched anything, and `contentBounds` walks
        // the whole page allocating two `PageRect`s per object — ~2,286 allocations per sample on
        // a 1,143-object note, ~18 MB/s at 240 Hz on the UI thread — to recompute a value an
        // area erase **cannot change**: a partial erase grows a region beside an object and never
        // moves its `pageBounds`. The two things that can shrink the paper are a whole-stroke
        // erase and the pen-up prune, and both are settled by the time `gestureEnded` is true.
        // Deferring it is also the better behaviour for the whole-stroke mode: `recomputeCanvas`'s
        // leash would otherwise move the camera — and with it the eraser's own page mapping —
        // while the pen is still down.
        if (gestureEnded) recomputeCanvas()
        dryView.onStoreChanged()
        // A flipped pen tip erases whatever the toolbar says, so it can delete a *selected*
        // stroke (WHOLE_STROKE, or the end-of-gesture prune) with the selection standing.
        // `onStoreChanged` prunes the dead id and fires onChanged only when the selection
        // actually changed, so the per-sample cost with nothing selected is one empty check.
        selectionController.onStoreChanged()
        onStrokeCountChanged?.invoke(strokes.size)
    }

    /**
     * ⭐ M8a — the selection tool. Built to [EraserController]'s shape; see its own KDoc for
     * why the ops it writes are stamped here rather than in `NoteEditor`.
     */
    // The explicit type is required: the callback reads `selection` back, and without it the
    // compiler cannot infer the property's type from an initialiser that mentions the property.
    private val selectionController: SelectionController =
        SelectionController(
            store = strokes,
            brushes = brushFactory,
            displayDensity = displayMetrics.density,
            pixelsPerPoint = pixelsPerPoint,
            // Per preview frame during a drag: repaint the ink and the chrome, and nothing else.
            // The membership-shaped callbacks below used to fire here too, per input event —
            // shapeCounts is O(selection) with a list allocation and soleImage builds a fresh
            // object, so a drag fanned hundreds of recomputations per second into Compose for
            // values that cannot change while the finger is down.
            onPreview = {
                dryView.onStoreChanged()
                overlay.onSelectionChanged()
            },
            onChanged = {
                // ⭐ At the *end* of the gesture, which is where this callback fires and
                // `onPreview` deliberately does not: dragging a stroke past the paper's edge grows
                // an infinite page, and doing it per input event would re-measure every object on
                // the page at 240 Hz for a value that cannot settle until the finger is up.
                recomputeCanvas()
                dryView.onStoreChanged()
                overlay.onSelectionChanged()
                onStrokeCountChanged?.invoke(strokes.size)
                onSelectionChanged?.invoke(selectionController.size)
                onHistoryChanged?.invoke()
            },
            onTappedAway = { onSelectTappedAway?.invoke() },
        )

    /**
     * ⭐ Fired when a tap in Select landed on bare paper — the toolbar's cue to put the pen back.
     *
     * The surface does not change [tool] itself, because the tool is `:app`'s state and a view that
     * wrote it behind the toolbar's back would give the two different opinions — the bug the Image
     * tool had when it owned touch. `:app` sets its own tool and the value flows down here as usual.
     */
    public var onSelectTappedAway: (() -> Unit)? = null

    /**
     * ⭐ ROADMAP M8c — the straight edge. See [RulerController] for how the snap is applied.
     *
     * Placed at the middle of what is on screen the first time it is switched on; a null viewport
     * centre means the surface has not been laid out yet, and the next enable places it.
     */
    private val rulerController = RulerController(
        displayDensity = displayMetrics.density,
        viewportCentre = {
            if (width <= 0 || height <= 0) {
                null
            } else {
                PagePointF().also { toPageSpace(width / 2f, height / 2f, it) }
            }
        },
        onChanged = {
            toolOverlay.onToolsChanged()
            onRulerChanged?.invoke()
        },
    )

    /** ⭐ ROADMAP M8c — the laser pointer. It reaches no store and writes no op; see [LaserController]. */
    private val laser = LaserController { toolOverlay.onToolsChanged() }

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
        set(value) {
            if (field == value) return
            val leavingSelect = field == InkTool.SELECT
            field = value
            // ⭐ **Leaving Select drops the selection**, in the user's words: *"when you move off the
            // tool it unselects anything that is selected"*. The toolbar's own Select chip already
            // did this; every other route out — a brush chip, the eraser, Text, "Draw again" — did
            // not, so handles and a frame stayed on screen under a tool with no use for them. It
            // belongs here rather than in each caller for the reason the chip's KDoc gives about
            // itself: *"a box round objects the toolbar no longer offers actions for is chrome that
            // lies"*, and one setter is the only place that sees every way out.
            //
            // Guarded on an actual change because `InkScreen` pushes this on every recomposition.
            if (leavingSelect) selectionController.clear()
        }

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
     * ⭐ ROADMAP M8b — draw a shape, hold still, and the ink becomes the shape.
     *
     * A **setting**, and on by default, because the acceptance criterion is *"no fighting it to
     * draw a freehand curve"* and the honest answer to somebody it still fights is a switch rather
     * than a promise. It costs nothing when off: [ShapeSnapDetector.begin] is simply not called,
     * so the per-sample path is byte-for-byte what it was.
     */
    public var shapeSnapEnabled: Boolean
        get() = snap.enabled
        set(value) {
            snap.enabled = value
        }

    /** When on, a snapped straight line gets an arrowhead. See `ShapeSnapController`. */
    public var arrowHeads: Boolean
        get() = snap.arrowHeads
        set(value) {
            snap.arrowHeads = value
        }

    /**
     * ⭐ **Snap shapes to the paper's grid** — on draw-and-hold, and on a move, resize or point
     * drag of a selection that is all shapes.
     *
     * Off by default, because a lattice is a diagram tool and this is a handwriting app: somebody
     * ruling a margin does not want their line jumping 18 pt sideways because they had the chip on
     * from yesterday.
     *
     * ⚠️ **The lattice comes from the paper, never from a constant here.** A grid page snaps to
     * the squares it draws, a ruled page snaps y to its rules and leaves x free, and blank paper
     * and an imported PDF page get [SnapGrid.DEFAULT_SPACING_PT] — which nothing draws, so
     * [ToolOverlayView] draws it as chrome while this is on. See [SnapGrid.forBackground]; the rule
     * it serves is `AlignmentGuides`': *a snap with no line is the object refusing to go where it
     * was put.*
     */
    public var gridSnapEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            refreshSnapGrid()
        }

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
    public var pageSize: PageSize
        get() = pageCanvas.size
        set(value) {
            adoptCanvas(storedCanvas.copy(size = value))
        }

    /**
     * ⭐ **The paper** — its extent *and* where it sits in page space (SPEC §4.5, [PageCanvas]).
     *
     * This used to be a [PageSize] alone, on the assumption that the paper is always the
     * rectangle `[0, 0, width, height]`. Two things came of that. Nothing checked it, so ink
     * written beside the page — which free panning makes easy — was captured at a negative
     * page-space x and then dropped by every PDF reader, because an annotation outside the
     * CropBox is not drawn. And an infinite canvas cannot be expressed by an extent at all: it
     * grows in whichever direction the user writes.
     *
     * ⚠️ **Assigning this does not refit the camera**, exactly as the [pageSize] setter it
     * replaced did not. Every write to it used to be a camera move, which `loadPage` wanted and
     * nothing else did — and "nothing else" turned out to include a peer's paper change arriving
     * mid-stroke. [loadPage] refits explicitly; [setPageMeta] re-leashes.
     */
    public var pageCanvas: PageCanvas = PageCanvas.fixed(PageSize.Letter)
        private set(value) {
            val moved = field.rect.left != value.rect.left || field.rect.top != value.rect.top
            field = value
            dryView.pageCanvas = value
            overlay.pageSize = value.size
            toolOverlay.pageCanvas = value
            // The paper's lines start from its corner, so the lattice moves when the corner does.
            if (moved) refreshSnapGrid()
            // The pages beside it hang from its edges, so they move when an edge does.
            strip.layout(value.rect)
        }

    /**
     * The paper **as the page's own registers describe it**, before it is grown to hold the ink.
     *
     * ⭐ The two are different values on an infinite page and the same one everywhere else, and
     * keeping them apart is what lets the canvas come back *down* — erase the one stroke in the
     * far corner, and [recomputeCanvas] re-derives from this rather than from the acre the page
     * had grown to. Deriving from the effective canvas instead would make every growth permanent
     * for the life of the note, which is a page that only ever gets bigger and a thumbnail that
     * shrinks the writing into a corner for good.
     */
    private var storedCanvas: PageCanvas = PageCanvas.fixed(PageSize.Letter)

    /** The paper under the ink. Changed through [loadPage] or [setPageMeta], never on its own. */
    public val pageBackground: Background get() = dryView.pageBackground

    public var camera: Camera = Camera.Identity
        private set(value) {
            field = value
            dryView.camera = value
            overlay.camera = value
            toolOverlay.camera = value
            onCameraChanged?.invoke(value)
        }

    public var onCameraChanged: ((Camera) -> Unit)? = null
    public var onStrokeCountChanged: ((Int) -> Unit)? = null

    /**
     * ⭐ ROADMAP M8c — the straight edge, for a toolbar that wants to switch it on and type an
     * angle at it. See [InkRuler].
     */
    public val ruler: InkRuler get() = rulerController

    /**
     * Fired whenever the edge moved, turned or was switched on or off — so a numeric angle field
     * follows a finger drag instead of going stale the moment anyone touches the ruler itself.
     */
    public var onRulerChanged: (() -> Unit)? = null

    /**
     * ⭐ M8c — where an inserted image's pixels come from (SPEC §4.9.4). Null until `:app` supplies
     * a cache, and a picture whose asset is not there draws a visible placeholder rather than
     * nothing. See [PageAssets].
     */
    public fun setPageAssets(assets: PageAssets?) {
        dryView.pageAssets = assets
    }

    /**
     * ⭐ The Element editor's canvas (SPEC §4.9.7): a grid instead of paper, and every invisible
     * knockout area drawn dashed. Null — the default — is an ordinary note.
     */
    public var elementBackdrop: ElementBackdrop?
        get() = dryView.backdrop
        set(value) {
            dryView.backdrop = value
            refreshSnapGrid()
        }

    /**
     * The lattice a drag lands on right now, or [SnapGrid.None] with the setting off — for `:app`'s
     * Element-editor tools, which place areas and the pivot outside any selection gesture and must
     * land them on the same points a drag would.
     */
    public val snapGrid: SnapGrid
        get() = selectionController.grid

    /**
     * The lattice the paper on screen draws, whether or not snapping is switched on — what the
     * Element editor is opened at, so an Element is made on the grid it will be placed on.
     */
    public val paperGrid: SnapGrid
        get() = SnapGrid.forBackground(pageBackground, pageCanvas.rect.left, pageCanvas.rect.top)

    /**
     * ⭐ The Element editor's lattice spacing (SPEC §4.9.7) — the note's it was opened from, so the
     * grid an Element is drawn on is the grid its copies snap to. Drawn by the backdrop and snapped
     * to by every tool, from zero on both axes.
     */
    public var elementGridPt: Float = SnapGrid.DEFAULT_SPACING_PT
        set(value) {
            if (field == value || value < SnapGrid.MIN_SPACING_PT) return
            field = value
            dryView.backdropCellPt = value
            refreshSnapGrid()
        }

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
    public var onStrokeCaptured: ((objectId: ObjectId, atElapsedRealtime: Long, ruled: Boolean) -> Unit)? = null

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
     * ⭐ True between [beginPageLoad] and [finishPageLoad] — the page's paper is up and its ink is
     * still being built.
     *
     * ⚠️ **The pen is refused for this window, and that is not a nicety.** Until `install` runs the
     * store is empty, so `StrokeStore.topZ` is null: a stroke drawn against it takes a base z key
     * and the page's whole contents then install *above* it. That is M8a's `lastZ` bug — "the
     * stroke I just drew went behind the picture" — recreated by a loading page, and
     * docs/M8-PERF-RENDER-CACHE.md §5 names it as the trap that sank the earlier attempt at this.
     *
     * It costs the user nothing they had: the load used to block the UI thread outright, so input
     * in this window was already going nowhere. What changes is that it is now a page visibly
     * filling in rather than a frozen app.
     */
    public var pageLoading: Boolean = false
        private set(value) {
            if (field == value) return
            field = value
            onPageLoadingChanged?.invoke(value)
        }

    /** Told when [pageLoading] flips, so `:app` can say so rather than showing bare paper. */
    public var onPageLoadingChanged: ((Boolean) -> Unit)? = null

    /**
     * ⭐ Told, as a page load **begins**, which pictures (asset names) the page draws — so `:app` can
     * decode them while the strokes are still being built, instead of after the first frame asks.
     *
     * An optimisation only: the asset cache also decodes whatever the painter asks it for, which is
     * what makes a picture inserted, undone or merged in later appear without this. What this buys
     * is the open — on the physics note the eleven photographs' decode overlaps a multi-second
     * stroke build rather than starting when it ends.
     */
    public var onPagePictures: ((Set<String>) -> Unit)? = null

    /** Bumped per load, so a build that finishes after a newer one started is discarded. */
    private var pageLoadGeneration: Int = 0

    /**
     * Show a folded page — **paper first, ink as soon as it is built.**
     *
     * The objects arrive already folded, so their absolute registers — including the erased
     * region — are the winning values and nothing here replays a log. Decoding and tessellating
     * the geometry blobs is the cost, and it is the largest single cost in the app: see
     * [PreparedPage], which is where it now happens and why it is safe to happen there.
     *
     * ⭐ **M8c: every object, not `filterIsInstance<StrokeObject>()`.** That filter was the last
     * place a text box or an image was dropped between the log and the screen — and dropped from
     * the store's top z key with it, so the next stroke the user drew landed underneath one. See
     * [DryObject].
     *
     * @param dispatcher where the page is built. A parameter so a test can make it deterministic;
     *   never the caller's own dispatcher, which is the whole point.
     */
    public suspend fun loadPage(page: FoldedPage, dispatcher: CoroutineDispatcher = Dispatchers.Default) {
        val generation = beginPageLoad(page)
        // ⭐ **The whole cost of opening a note, off the UI thread.** See [PreparedPage] — both for
        // the measurement and for why building a `Stroke` here is safe rather than hopeful.
        // `build`, not `of`: the objects are built across the dispatcher's threads (see [PreparedPage.build]).
        val prepared = withContext(dispatcher) { PreparedPage.build(page, brushFactory) }
        // ⚠️ A newer load has started while this one was building, so this one's objects belong to
        // a page that is no longer on screen. Installing them would put the previous page's ink on
        // the current page's paper — and `NoteEditor.serial` does *not* rule it out: the editor
        // holds that lock across this suspension, but `bindSurface` and `display` are separate
        // coroutines and a fast page turn is two loads. A counter makes the stale install
        // unrepresentable rather than argued about.
        if (generation != pageLoadGeneration) return
        finishPageLoad(prepared)
    }

    /**
     * The main-thread frame of a load: everything about the *page* that is not its objects.
     *
     * Done first and on its own so the paper, the camera and the background are up immediately.
     * The alternative — hold the old page on screen until the new one is built — shows the user
     * the page they just left for as long as the build takes, which reads as the turn not having
     * happened.
     *
     * @return the generation this load owns, for the staleness check in [loadPage].
     */
    private fun beginPageLoad(page: FoldedPage): Int {
        wetView.cancelUnfinishedStrokes()
        // Immediately, not left to fade: a mark made on the previous page would spend the next
        // second pointing at whatever now happens to be underneath it.
        laser.clear()
        // Before `reset`, so the overlay is not briefly drawing a box round ids that no longer
        // exist. A selection belongs to a page: carrying it across would leave the toolbar
        // offering "delete" for objects on a page the user has left.
        selectionController.clear()
        // ⭐ Scrolling into the next page: remembered before anything is reset, because the strip,
        // the camera and the pending turn all belong to the page being left.
        val turn = strip.take(page.pageId)
        val cameraBefore = camera
        strip.rotate(turn?.target)
        strokes.reset()
        strip.activePageId = page.pageId
        adoptCanvas(page.canvas)
        strip.layout(pageCanvas.rect)
        // Before the refit below, which is what draws and centres a search hit on this page.
        searchHit.pageLoading(page.pageId)
        if (turn != null && width > 0 && height > 0) {
            // ⭐ A page *scrolled into* is not a different piece of paper, it is the one already on
            // the glass: carry the camera so every point stays where it was, and cover the paper
            // with the picture that was there until the ink is built.
            camera = PageStrip.carried(cameraBefore, turn.rect, pageCanvas.rect).leashed()
            dryView.interim = PlacedPicture(turn.target, pageCanvas.rect)
            showHit()
        } else {
            dryView.interim = null
            // Explicit, because the setter above no longer does it. A page *switch* should fit the
            // new page — it is a different piece of paper and the old camera means nothing on it.
            fitOrFrame()
        }
        dryView.pageBackground = page.background
        refreshSnapGrid()
        // Cleared, not kept: the next page's paper is not this one's, and leaving the
        // old bitmap up means an imported page briefly shows the previous page's scan.
        dryView.sourcePage = null
        onPagePictures?.let { tell -> tell(page.objects.mapNotNullTo(HashSet()) { (it as? ImageObject)?.asset }) }
        pageLoading = true
        dryView.onStoreChanged()
        return ++pageLoadGeneration
    }

    private fun finishPageLoad(prepared: PreparedPage) {
        strokes.install(prepared, brushFactory)
        // The real ink is in, in the same frame the stand-in goes out.
        dryView.interim = null
        // ⚠️ **A stroke the user erased away comes back from disk, invisible and expensive.**
        // `SetErasedRegion` is an absolute register (SPEC §3.5) and a fully erased stroke is not
        // deleted — the op says its whole area is gone, which is the correct and lossless record.
        // So the fold re-inserts it, `buildDryStroke` restores its region, and every frame after
        // that it would pay a `saveLayer`, a mesh draw and a `DST_OUT` `drawPath` to render
        // *nothing*.
        //
        // ⭐ `PreparedPage.of` now decides that **off this thread** and `install` parks those
        // strokes without drawing them, so this line normally finds an empty queue. It stays for
        // the page that exhausted the load budget: whatever that left undecided is queued with its
        // progress, and this is the first bounded pass over it. (This line used to *be* the load
        // prune, and on a real note it reached two to six of ~555 — see `PruneBudgets`.)
        strokes.pruneFullyErased()
        // ⚠️ After the objects, not before: the canvas is derived from the ink, and until
        // `install` runs the store is empty — so a page opened without this line would show an
        // infinite note as a sheet of Letter with everything past the first page clipped away,
        // until the user happened to draw. Same class as the fixture-more-generous-than-production
        // note in CLAUDE.md: the load path is the one that has to re-derive what nobody watched
        // happen.
        recomputeCanvas()
        pageLoading = false
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
    public fun setPageMeta(canvas: PageCanvas, background: Background) {
        if (canvas == storedCanvas && background == dryView.pageBackground) return
        adoptCanvas(canvas)
        dryView.pageBackground = background
        refreshSnapGrid()
        dryView.sourcePage = null
        if (width > 0 && height > 0) camera = camera.leashed()
    }

    /**
     * Put the lattice, the two controllers that snap to it and the chrome that draws it back in
     * step — after the setting changed, or after the paper did.
     *
     * ⚠️ **Both, and that is the point of it being one function.** The grid is derived from the
     * background, so a paper change is a grid change: leaving it to the setter alone would mean
     * switching a page from grid to ruled kept snapping shapes to columns that were no longer
     * drawn — a snap with no line, arrived at by a route nobody would look down.
     */
    private fun refreshSnapGrid() {
        // The Element editor draws its own grid (`PagePainter.BACKDROP_CELL_PT`, the same lattice), so
        // the chrome grid would be a second copy of the same lines on top of it.
        val grid = when {
            !gridSnapEnabled -> SnapGrid.None
            elementBackdrop != null -> SnapGrid(elementGridPt, elementGridPt, drawnByThePaper = true)
            else -> paperGrid
        }
        snap.grid = grid
        selectionController.grid = grid
        toolOverlay.snapGrid = grid
    }

    /**
     * SPEC §8.4 — mark where a search hit landed on the page being shown, and bring it into view.
     *
     * ⚠️ **Held, and centred after the page's own fit.** A hit arrives while its note is still
     * opening — the note opens *because* of the hit — and both things that happen next refit the
     * camera: the first layout and the page load ([fitOrFrame]). This used to move the camera at
     * once and return, so the refit always came second: tapping a search hit opened the right note,
     * fitted to its width at the top, with the word off screen and its amber box drawn where nobody
     * was looking. Found on the emulator, on the chemistry and physics notes alike. Now [fitOrFrame]
     * ends by centring the hit, so whichever refit is last puts it in view.
     *
     * Which page the hit belongs to, and so when its box goes, is [SearchHitTracker]'s to decide.
     *
     * The camera move is guarded on having been laid out, like every other here: `Camera.leashedTo`
     * builds a `coerceIn` range from the viewport and a zero viewport inverts it and throws.
     *
     * @param box null clears the highlight.
     */
    public fun highlight(box: dev.inkstone.core.model.PageRect?) {
        searchHit.hit(box)
        showHit()
    }

    private val searchHit = SearchHitTracker()

    /** Draw the search hit if it belongs on this page, and centre it once there is a view to centre it in. */
    private fun showHit() {
        val box = searchHit.drawn
        dryView.highlight = box
        if (box == null || width <= 0 || height <= 0) return
        camera = camera.centredOn(box.centreX, box.centreY, width.toFloat(), height.toFloat())
        searchHit.centred()
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
        // ⭐ A redo of a full erase, a peer's erase, or an undo's re-add of an erased stroke can
        // leave a stroke on the page that is covered completely — invisible, and paying a layer on
        // every re-record. The store queued each one; this is the bounded pass over them, and with
        // nothing queued — the usual case — it is a size check. Before `recomputeCanvas`, because
        // a stroke leaving the page can shrink an infinite one.
        strokes.pruneFullyErased()
        // ⭐ The door a peer's merged strokes, an undo and every object insert come through — so
        // it is the one place an infinite page has to re-derive its paper for work it did not
        // watch being made.
        recomputeCanvas()
        // ⭐ Before the repaint: the selection caches ids and a box, and this is the door an
        // undo's DeleteObject or a peer's move comes through. Without it, "undo something
        // selected" left the outline and grips round objects that had just been deleted or
        // moved back — drawn stale, hit-tested stale, and reported stale to the toolbar.
        selectionController.onStoreChanged()
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

    /**
     * ⭐ The objects a dragged rectangle encloses, strokes included — what an Element capture saves.
     *
     * The marquee's own rule (`Lasso.rectangle`), so a capture box and a selection box drawn round
     * the same diagram take the same objects. `:app` used to answer this itself from the fold and
     * could only see objects with a payload extent, which is why a hand-drawn icon saved as nothing.
     */
    public fun objectsWithin(area: dev.inkstone.core.model.PageRect): List<ObjectId> = strokes.objectsIn(
        dev.inkstone.core.model.Lasso.rectangle(
            dev.inkstone.core.model.PagePoint(area.left, area.top),
            dev.inkstone.core.model.PagePoint(area.right, area.bottom),
        ),
    )

    /**
     * The page-space box round [ids] as drawn — a stroke's tessellated extent included, which only
     * this module can measure (SPEC §5.4). Null when none of them is on the page.
     */
    public fun boundsOf(ids: Collection<ObjectId>): dev.inkstone.core.model.PageRect? = strokes.pageBoundsOf(ids)

    /**
     * Select exactly [ids] — what a freshly stamped Element does, so it can be dragged at once.
     *
     * ⚠️ The tool is not changed here; the caller moves to Select **after** this, exactly as a
     * snapped shape does, because [tool]'s setter drops the selection when it leaves Select and an
     * order the other way round is a no-op on a tool that was already there.
     */
    public fun select(ids: List<ObjectId>) {
        selectionController.select(ids.filter { strokes.objectOf(it) != null })
        overlay.invalidate()
    }

    /**
     * ⭐ ROADMAP M8a — the rectangular half of *"freehand AND rectangular"*. When on, the select
     * tool drags a box instead of drawing a lasso; the containment rule is unchanged
     * (`Lasso.rectangle` builds the same polygon), and the toggle is read at gesture start so
     * flipping it mid-drag changes nothing.
     */
    public var marqueeSelection: Boolean
        get() = selectionController.marquee
        set(value) {
            selectionController.marquee = value
        }

    /** ⭐ The step a selection's rotate grip snaps to, in degrees; 0 rotates freely. The pen bar's `Rotate` chip. */
    public var rotationSnapDegrees: Int
        get() = Math.round(Math.toDegrees(selectionController.rotationSnapRadians.toDouble())).toInt()
        set(value) {
            selectionController.rotationSnapRadians = Math.toRadians(value.toDouble()).toFloat()
        }

    // -----------------------------------------------------------------------
    // ⭐ The infinite canvas (SPEC §4.5)
    // -----------------------------------------------------------------------

    /**
     * Adopt a page's own canvas registers, then grow the paper to whatever is written on it.
     *
     * The single writer of [storedCanvas]; [pageCanvas] is derived from it and never set from
     * outside. One door rather than two, because *"the paper the file says"* and *"the paper the
     * ink needs"* being separately settable is exactly how they would come to disagree.
     */
    private fun adoptCanvas(canvas: PageCanvas) {
        storedCanvas = canvas
        recomputeCanvas()
    }

    /**
     * ⭐ **The paper is derived from the ink, and nothing records it.**
     *
     * A fixed page is its registers and nothing else. An infinite one is those registers grown
     * until everything on the page fits with a margin ([PageCanvas.grownToInclude]) — computed
     * here, computed the same way by the thumbnail renderer, and computed the same way by the
     * publisher before it writes the PDF's box.
     *
     * Deriving rather than journaling is the decision this feature turns on, and it buys three
     * things at once. There is **no growth op**, so writing down a long page is not a
     * `SetPageMeta` per stroke. There is **nothing to converge**, so SPEC §13.2's merges need no
     * rule for it: a peer's strokes are simply in the store, so the page grows to hold them the
     * moment they are folded — where a last-writer-wins size register could resolve the other way
     * and leave their writing outside the box. And there is **nothing that can disagree with the
     * ink**, which is the property that made this worth doing: the paper on screen and the box in
     * the PDF are the same function of the same objects.
     *
     * It is not free of a trade. Erasing or undoing the outermost stroke takes the paper back
     * down, because the derivation is honest in both directions. The slab quantisation is what
     * keeps that from being twitchy — the edge moves half a page at a time or not at all.
     */
    private fun recomputeCanvas() {
        val canvas = if (storedCanvas.infinite) storedCanvas.grownToInclude(strokes.contentBounds()) else storedCanvas
        if (canvas == pageCanvas) return
        pageCanvas = canvas
        // The paper may have shrunk under a camera that was looking at the part that went away.
        if (width > 0 && height > 0) camera = camera.leashed()
    }

    /**
     * Make room for a point the pen is about to land on — the other half of *"write anywhere"*.
     *
     * [recomputeCanvas] derives from ink that exists, and at the ACTION_DOWN this stroke's does
     * not yet. Without this the very first stroke past the edge would be refused by the same gate
     * that protects a fixed page, on a page whose whole promise is that there is no edge.
     *
     * Nothing is lost by growing early: the stroke about to be drawn contains this point, so the
     * derivation at the end of the gesture arrives at the same paper or a larger one.
     */
    private fun growCanvasForPen(x: Float, y: Float) {
        if (!storedCanvas.infinite) return
        val grown = pageCanvas.grownToInclude(x, y)
        if (grown != pageCanvas) pageCanvas = grown
    }

    /**
     * Applies SPEC §4.5's leash: pan freely, but never lose the page.
     *
     * Scrolling like a PDF widens *what* is leashed — the paper and the pages beside it — and
     * nothing else: the rule is unchanged, so the camera still cannot be thrown away from every
     * page there is.
     */
    private fun Camera.leashed(): Camera =
        leashedTo(leashArea(), Viewport(width.toFloat(), height.toFloat()), minVisiblePx)

    private fun leashArea(): PageCanvas = strip.reach(pageCanvas)

    // -----------------------------------------------------------------------
    // Scrolling through the pages like a PDF
    // -----------------------------------------------------------------------

    /** The pictures beside the page, and the decisions about them. See [PageStripController]. */
    private val strip = PageStripController<PageNeighbour> { above, below ->
        dryView.picturesAbove = above
        dryView.picturesBelow = below
    }

    /**
     * ⭐ **Scroll from one page into the next.** Off by default; a setting.
     *
     * The page above and the page below are drawn beside this one as pictures ([setStrip]), the
     * camera may roam over all three, and when the middle of the view enters a neighbour that page
     * becomes the one being edited ([onScrollTurn]) with the camera carried so that nothing on the
     * glass moves ([PageStrip.carried]).
     *
     * Everything about editing is unchanged: there is still exactly one live page, and a stroke
     * still belongs to it. What changes is that reaching the end of a page no longer stops the
     * scroll, and turning the page no longer snaps back to the top of the next.
     */
    public var continuousScroll: Boolean
        get() = strip.enabled
        set(value) {
            if (strip.enabled == value) return
            strip.enabled = value
            if (width > 0 && height > 0) camera = camera.leashed()
        }

    /**
     * Told that the view has crossed into a neighbour (or a pen has landed on one) and its page
     * should become the one being edited. The owner turns to it and then calls [endScrollTurn]
     * whether or not it did, so a refused turn does not wedge the next one.
     */
    public var onScrollTurn: ((PageId) -> Unit)? = null

    /**
     * Hand over the pictures of the pages around [forPage]. Ignored unless it is the page on screen
     * and scrolling is on: a render finishes some time after it was asked for, and the user may have
     * turned the page twice since.
     *
     * [self] is a picture of [forPage] itself. It is not drawn; it is kept so that when the camera
     * leaves this page, the page just left can be put straight into its place as a neighbour instead
     * of vanishing for as long as the editor takes to render it again.
     */
    public fun setStrip(forPage: PageId, above: PageNeighbour?, self: PageNeighbour?, below: PageNeighbour?) {
        if (strip.set(forPage, above, self, below, pageCanvas.rect) && width > 0 && height > 0) {
            camera = camera.leashed()
        }
    }

    /** The owner has finished (or refused) the turn it was asked for. */
    public fun endScrollTurn() = strip.end()

    /** After a pan: has the middle of the view entered a neighbour? */
    private fun checkScrollTurn() {
        if (pageLoading || width <= 0 || height <= 0) return
        requestTurn(strip.crossing((height / 2f - camera.translate.y) / camera.scale))
    }

    private fun requestTurn(turn: StripTurn<PageNeighbour>?) {
        val callback = onScrollTurn
        if (turn == null || callback == null) return
        strip.begin(turn)
        // Posted rather than called: this runs inside touch dispatch, and the owner's turn starts
        // saving the page the moment it is asked.
        post { callback(turn.target.pageId) }
    }

    /** Zoom as a percentage, for the toolbar readout. */
    public fun zoomPercent(): Float = camera.zoomPercent(pixelsPerPoint)

    init {
        val fill = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        addView(dryView, fill)
        addView(overlay, fill)
        addView(toolOverlay, fill)
        addView(wetView, fill)
        overlay.selection = selectionController
        toolOverlay.ruler = rulerController
        toolOverlay.laser = laser

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
        // ⭐ M8c — the fade schedules its own next frame from `onDraw` (see [ToolOverlayView]), so a
        // live trail is the one thing here that would keep asking a detached view to draw.
        laser.clear()
        predictor = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 && oldh == 0) {
            fitOrFrame()
        } else if (width > 0 && height > 0) {
            camera = camera.leashed()
        }
    }

    // -----------------------------------------------------------------------
    // Camera (SPEC §4.5)
    // -----------------------------------------------------------------------

    public fun resetCamera(mode: FitMode) {
        if (width == 0 || height == 0) return
        camera = cameraFor(mode, pageCanvas, Viewport(width.toFloat(), height.toFloat()), pixelsPerPoint, zoomLimits)
    }

    /**
     * ⭐ Centre [box] in the view with room round it — how the Element editor opens on an Element in
     * the middle of a page far larger than the screen (SPEC §4.9.7). Fit-width there would show the
     * Element as a speck.
     *
     * @return false before layout, when there is no view to fit into; the caller tries again.
     */
    public fun frame(box: dev.inkstone.core.model.PageRect): Boolean {
        val laidOut = width > 0 && height > 0
        if (!laidOut || box.width <= 0f || box.height <= 0f) return false
        val scale = minOf(width / (box.width * FRAME_ROOM), height / (box.height * FRAME_ROOM))
            .let(zoomLimits::clamp)
        val centreX = (box.left + box.right) / 2f
        val centreY = (box.top + box.bottom) / 2f
        camera = Camera(Offset(width / 2f - centreX * scale, height / 2f - centreY * scale), scale)
        return true
    }

    /**
     * ⭐ What a page load and the first layout frame the camera on instead of fitting the page's width,
     * or null for every note. The Element editor sets it to the Element's box (SPEC §4.9.7): its page
     * is far larger than the screen, and the load's own refit — which runs whenever the load finishes,
     * after anything a caller could do first — would otherwise leave the Element a speck at 19 %.
     */
    public var preferredFrame: dev.inkstone.core.model.PageRect? = null
        set(value) {
            field = value
            value?.let(::frame)
        }

    /** Every refit ends here, so a search hit is centred after it rather than undone by it. */
    private fun fitOrFrame() {
        val box = preferredFrame
        if (box == null || !frame(box)) resetCamera(FitMode.FIT_WIDTH)
        showHit()
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
            for ((id, stroke) in strokes) {
                // Each stroke's own start, never "the latest one's": this call can come after the
                // next stroke has begun, and can report more than one stroke (StrokeStarts).
                val slot = strokeStarts.find(id)
                val kind = if (slot >= 0) strokeStarts.kind(slot) else pen.kind
                val objectId = this@InkSurfaceView.strokes.add(stroke, kind)
                // M12 — null only for the unbound fallback id, which never reaches disk and must
                // never reach a timeline either.
                if (objectId != null && slot >= 0) {
                    onStrokeCaptured?.invoke(objectId, strokeStarts.startedAt(slot), strokeStarts.ruled(slot))
                }
                if (slot >= 0) strokeStarts.clear(slot)
            }
            // Before the repaint: the stroke may have gone past the paper's edge, and on an
            // infinite page the paper follows it rather than cutting it off.
            recomputeCanvas()
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
        // Same line, same reason as [loadPage]: everything is about to go, and a selection of
        // deleted objects would keep its box, its grips and its toolbar row standing over an
        // empty page.
        selectionController.clear()
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
        // ⚠️ Nothing is drawable, selectable or erasable on a page whose objects are still being
        // built, and a stroke started here would land under all of them — see [pageLoading].
        // Consumed rather than passed up: the surface fills the editor, so declining would hand
        // the gesture to whatever is behind it.
        if (pageLoading) return true
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

        fingerPointerDown(event, index, pointerId)
        return true
    }

    /**
     * A finger landed. The order of the gates is load-bearing:
     *
     * ⭐ **The tap detector is fed BEFORE the palm guard**, and that placement is the fix for
     * "two-finger undo feels very inconsistent". The S Pen reports hover from ~1 cm out and
     * every hover event refreshes the guard, so with the pen held anywhere near the page —
     * the normal posture when tapping with the other hand, and inevitable right after
     * writing — the guard swallowed the fingers before the detector ever saw them. The guard
     * exists to stop a palm *panning the page* or *drawing*; a tap moves nothing and writes
     * nothing, and the detector's own slop and timeout are what reject a palm (a palm slides
     * or lingers; two clean sub-slop sub-400 ms contacts are a person asking for undo).
     *
     * ⭐ **The ruler grab is now in front of it too**, for the same reason and at the same cost —
     * see the comment on the call. The camera and finger drawing stay behind the guard.
     */
    private fun fingerPointerDown(event: MotionEvent, index: Int, pointerId: Int) {
        val gestureInFlight = activeStrokeId != null || eraser.isActive ||
            selectionController.isActive || rulerController.isActive || laser.isActive
        if (gestureInFlight) return

        taps.onPointerDown(event)

        // ⭐ **The ruler is grabbed BEFORE the palm guard**, and this placement is the fix for
        // *"the ruler does not work at all"*. It is the two-finger-undo bug one tool along, with
        // the same cause: the S Pen reports hover from about a centimetre out and every hover event
        // refreshes the guard, so with the pen anywhere near the page the guard swallowed the
        // finger before [RulerController.begin] ever saw it — and *"pen in the other hand"* is the
        // **only** posture a straight edge is ever used in. Behind the guard, the ruler could be
        // moved exactly when nobody wanted to move it.
        //
        // The trade is deliberate and narrow. The guard exists to stop a palm *drawing* or
        // *panning the page*; grabbing the ruler is neither. It writes no op, it requires landing
        // within [RulerController]'s own reach of an edge the user placed themselves, it is visible
        // the instant it happens, and it is undone by dragging the edge back. `gestureInFlight`
        // above still refuses it outright while a stroke is live, so a resting palm cannot steal
        // the edge out from under a line being ruled.
        //
        // Checked here and not in `beginDrawOrErase` because that is the *stylus* path: the pen
        // draws along the ruler, so a pen that also moved it would make drawing and adjusting one
        // gesture.
        if (rulerController.begin(event, index, pointerId, camera)) {
            taps.cancel()
            parent?.requestDisallowInterceptTouchEvent(true)
            return
        }

        if (!palmGuard.allowsFinger(event)) return

        if (fingerDrawingEnabled) {
            requestUnbufferedDispatch(event)
            beginDrawOrErase(event, index, pointerId, hardwareEraserAllowed = false)
            return
        }

        gestures.addPointer(event, pointerId)
    }

    /** SPEC §3.3 — a flipped S Pen or a held barrel button erases ([isHardwareEraser]). */
    private fun beginDrawOrErase(event: MotionEvent, index: Int, pointerId: Int, hardwareEraserAllowed: Boolean) {
        val hardwareEraser = hardwareEraserAllowed && event.isHardwareEraser(index)

        if (hardwareEraser || tool == InkTool.ERASER) {
            // Only a hardware eraser is "temporary": it restores itself when the
            // pen is flipped back, so it must not trigger SPEC §3.4's auto-return.
            // A flipped tip beats the selection tool too — the eraser end of the pen
            // erases, whatever the toolbar says, which is the whole point of SPEC §3.3.
            eraser.begin(event, index, pointerId, temporary = hardwareEraser, camera = camera)
        } else if (tool == InkTool.SELECT) {
            selectionController.begin(event, index, pointerId, camera, pageSize)
        } else if (tool == InkTool.LASER) {
            // ⭐ M8c — stylus only, which is the PalmGuard rule the rest of this class follows.
            // ⚠️ A finger does **nothing** rather than falling through to `beginStroke`: on a
            // surface with `fingerDrawingEnabled` on — which is how this gets driven over adb —
            // falling through would draw permanent ink from a tool whose whole promise is that it
            // writes nothing.
            if (event.isStylusPointer(index)) laser.begin(event, index, pointerId, camera)
        } else {
            beginStroke(event, index, pointerId)
        }
        // Reject anything else while the gesture is live: no second stroke, no
        // camera gesture, no palm.
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    /**
     * ⚠️ **The ruler is applied to the EVENT, not to ink's transform**, and the difference is
     * the whole of *"the ruler does not work at all"*.
     *
     * M8c composed the straight edge's orthogonal projection onto `motionEventToWorldTransform`,
     * on the argument that ink maps every sample through it anyway. A projection is singular, and
     * `InProgressShapesView` **inverts** that matrix to compute `strokeUnitLengthCm` — so
     * `startStroke` threw `IllegalArgumentException: motionEventToWorldTransform must be
     * invertible` on the ACTION_DOWN of every stroke drawn while the edge was out. Not a stroke
     * that failed to snap: a stroke that never began. [RulerController]'s KDoc has the full
     * account and why a near-singular matrix is not the answer either.
     *
     * So ink gets the camera's own `viewToPage`, and [ruled] hands it a copy of the event whose
     * samples — including every historical one — have already been flattened onto the edge.
     */
    private fun beginStroke(event: MotionEvent, pointerIndex: Int, pointerId: Int) {
        if (activeStrokeId != null) return
        // ⭐ **The paper is where the pen works.**
        //
        // Panning is free (SPEC §4.5), so the empty space beside the page is as easy to write in
        // as the page — and a stroke there is captured at a page-space coordinate outside the
        // CropBox, which **no PDF reader draws**. The app showed it; every other program dropped
        // it; nothing said so. That is the silent-loss class CLAUDE.md rule 1 exists for, reached
        // by drawing rather than by syncing.
        //
        // Refused at the ACTION_DOWN, before `strokes.beginEdit()`, so nothing is opened that
        // would have to be unwound: no undo entry, no op, no half-stroke on the wet layer. A
        // stroke that *starts* on the paper and runs off the edge is allowed and clipped where the
        // paper ends ([PagePainter.clipToPaper]), which is what the PDF will do to it anyway.
        //
        // The slack is a stroke width or so: a hand aiming at the very edge of the page and
        // landing a hair outside it meant the page, and the clip renders the overshoot away.
        // On an infinite canvas this refuses nothing: the paper has already grown to hold the
        // point by the time this runs.
        toPageSpace(event.getX(pointerIndex), event.getY(pointerIndex), downPoint)
        growCanvasForPen(downPoint.x, downPoint.y)
        if (!pageCanvas.contains(downPoint.x, downPoint.y, slackPt = EDGE_SLACK_PT)) {
            // ⭐ A pen that lands on the page beside this one means *that page*. Refused for good it
            // would be a page you can see and cannot write on until you scroll a little further; the
            // stroke itself is dropped (there is nothing yet to put it on) and the page turns, so the
            // next one lands.
            requestTurn(strip.landing(downPoint.x, downPoint.y))
            return
        }
        // M12 — latched here, used when the stroke finishes. `elapsedRealtime` rather than
        // `event.eventTime`, which is `uptimeMillis` and stops during deep sleep: a recording holds
        // the CPU awake, so the two agree in practice, but only one of them is the clock the
        // timeline's offsets are defined against.
        val startedAt = SystemClock.elapsedRealtime()
        // One undo entry per stroke. Opened here and committed when the stroke
        // finishes, so the entry exists by the time onStrokesFinished inserts.
        strokes.beginEdit()
        strokePointerId = pointerId
        activeStrokeIsStylus = event.isStylusPointer(pointerIndex)
        val ruled = rulerController.appliesTo(event, pointerIndex)
        // ⭐ M8b — armed here, fed in `onMove`, and it allocates nothing on either path.
        // ⚠️ Not armed while the ruler is out. Draw-and-hold measures dwell against the *raw*
        // samples while the ink lands on the projected ones, so a hold that fired would replace a
        // ruled line with a shape fitted to a hand's wobble — and a straight edge is already the
        // deliberate way to draw a straight line.
        if (!ruled) {
            toPageSpace(event.getX(pointerIndex), event.getY(pointerIndex), snapPoint)
            snap.begin(snapPoint.x, snapPoint.y, event.eventTime)
        }
        val viewToPage = cameraMatrix.viewToPage(camera)
        // ⚠️ A copy, because `CameraMatrix` hands out a reused instance and every later sample of
        // this stroke has to be projected through the camera the stroke *started* in — see
        // `RulerController.eventProjection`.
        activeStrokeIsRuled = ruled
        if (ruled) strokeViewToPage.set(viewToPage)
        val projected = ruled(event)
        val id = wetView.startStroke(
            event = projected,
            pointerId = pointerId,
            brush = brushFactory.brushFor(pen),
            // The camera in effect at stroke start, and only that camera. Never the straight edge:
            // ink inverts this matrix, and a projection has no inverse.
            motionEventToWorldTransform = viewToPage,
            // Stroke space *is* page space, so nothing needs converting later (SPEC §4.4 rule 1).
            strokeToWorldTransform = IDENTITY,
        )
        activeStrokeId = id
        // The ruler flag is kept for handwriting synthesis (docs/HANDWRITING-SYNTHESIS.md §5.1): a ruled
        // stroke is the ruler's line, not the user's hand, and must never be learned as handwriting.
        strokeStarts.put(id, startedAt, ruled, pen.kind)
        release(event, projected)
    }

    /**
     * [event], with every sample flattened onto the straight edge — or [event] itself when no edge
     * is out. **The caller must pass the result to [release].**
     *
     * `MotionEvent.transform` moves the coordinates of every pointer and every *historical* sample
     * and leaves pressure, tilt, orientation and tool type alone, which is exactly the set
     * CLAUDE.md says a hand-built `StrokeInput` silently drops. The copy is pooled and is recycled
     * the moment ink has read it: `StrokeInputPool.obtainAllHistoryForMotionEvent` converts an
     * event into a `MutableStrokeInputBatch` synchronously on the calling thread, so nothing
     * downstream retains it (read off the 1.0.0 bytecode rather than assumed).
     */
    private fun ruled(event: MotionEvent): MotionEvent {
        if (!activeStrokeIsRuled) return event
        val projected = MotionEvent.obtain(event)
        projected.transform(rulerController.eventProjection(strokeViewToPage))
        return projected
    }

    /** [ruled], for the predicted tail — which is a whole event or nothing at all. */
    private fun ruledOrNull(event: MotionEvent?): MotionEvent? = event?.let { ruled(it) }

    /** Give back what [ruled] borrowed, and nothing else. */
    private fun release(original: MotionEvent?, projected: MotionEvent?) {
        if (projected !== original) projected?.recycle()
    }

    /** True while the live stroke is being ruled — see [ruled]. */
    private var activeStrokeIsRuled = false

    /** The camera this stroke started in, for [ruled]. A copy; `CameraMatrix` reuses its own. */
    private val strokeViewToPage = Matrix()

    /**
     * ⭐ ROADMAP M8b — draw-and-hold. Two call sites: armed in [beginStroke], fed in [onMove].
     *
     * A controller rather than four more methods here, for the reason [eraser] and
     * [selectionController] are: this class owns `MotionEvent` dispatch, and dispatch is the one
     * thing in the app that has to stay readable.
     */
    private val snap = ShapeSnapController(
        store = strokes,
        brushes = brushFactory,
        cancelWetStroke = {
            // Null event: on the timer path there is no event, because the whole reason the timer
            // exists is that the digitiser stopped sending them. `cancelStroke` takes one only to
            // release its own pointer bookkeeping and tolerates its absence.
            activeStrokeId?.let { wetView.cancelStroke(it, null) }
            activeStrokeId = null
            // ⚠️ Remembered *before* it is cleared. The stroke's pointer is still on the glass — a
            // snap fires mid-gesture, not on the lift — and `onSnapped` hands it to the selection so
            // the same movement can go on adjusting the shape. Reading it after this line answers
            // INVALID, which is how the hand-over silently did nothing.
            handOverPointerId = strokePointerId
            strokePointerId = MotionEvent.INVALID_POINTER_ID
        },
        onSnapped = { id, result ->
            dryView.onStoreChanged()
            onStrokeCountChanged?.invoke(strokes.size)
            onHistoryChanged?.invoke()
            // ⭐ Select what was just made. The shape the user drew a second ago is the one they are
            // about to want to resize, and leaving the selection empty meant the only way back to it
            // was to switch tools and land a tap on it — on an *unfilled* outline, which until
            // [DryObject.tappedAt] meant hitting a hairline. Selecting here is also what puts the
            // shape row (fill, corner radius) on screen without a hunt.
            //
            // The tool is deliberately **not** changed: someone drawing a flowchart snaps five boxes
            // in a row, and stealing the pen after each one would cost five taps to give back.
            // `InkToolbar` surfaces an "Adjust" action instead, which is one tap and is not a mode
            // change nobody asked for. ⚠️ It did not until this milestone — the sentence was here,
            // the button was not, and the selection made above therefore had no gesture behind it
            // for anyone still holding the pen.
            selectionController.select(listOf(id))
            // ⭐ **Keep the gesture going.** The pen is still down, resting on the end of the line it
            // just drew — which is where that line's endpoint handle now is — so the same unbroken
            // movement can carry straight on into dragging that end. Without this the snap ended the
            // gesture: to nudge the end you lifted, aimed at a 6 dp circle and pressed again, and
            // the *reason* to nudge it is usually that the snap landed a few points off where you
            // wanted.
            //
            // ⚠️ **Armed here, begun on the first real movement** — see [handOverFrom]. Starting the
            // drag now would commit one on the lift even when the pen never moved, and a point drag
            // commits an explicit vertex list: every snapped line would gain a `SetTextContent` it
            // did not need, on the journal and on the wire, for a gesture the user had finished.
            handOverFrom.set(snapPoint.x, snapPoint.y)
            onShapeSnapped?.invoke(result)
        },
    )

    private val snapPoint = PagePointF()

    /** Where the pen went down, in page space. Reused — the ink path must not allocate. */
    private val downPoint = PagePointF()

    /**
     * The pointer that was drawing when a snap fired, so a later move can hand it to the selection.
     *
     * A field rather than an argument because the two halves are a callback apart: `cancelWetStroke`
     * is where the drawing pointer is still known, and `onSnapped` is where the shape exists to be
     * dragged. Cleared on the lift, and on any move that takes the hand-over.
     */
    private var handOverPointerId: Int = MotionEvent.INVALID_POINTER_ID

    /** Where the pen was when the snap fired, so [handOverMove] can measure how far it has come. */
    private val handOverFrom = PagePointF()

    /**
     * View coordinates -> page space.
     *
     * The same two divisions `SelectionController` uses, and deliberately not a `Matrix.mapPoints`:
     * this runs per input sample and a `Matrix` round trip allocates a `FloatArray` unless the
     * caller keeps one, which is a second thing to get wrong for arithmetic this short.
     */
    private fun toPageSpace(viewX: Float, viewY: Float, into: PagePointF) {
        into.set((viewX - camera.translate.x) / camera.scale, (viewY - camera.translate.y) / camera.scale)
    }

    /**
     * One sample into the dwell detector, and the timer that covers a pen too still to report.
     *
     * ⚠️ The timer is not belt and braces. Android delivers `ACTION_MOVE` when the digitiser
     * reports a change, so a pen held genuinely still can stop producing events — and holding
     * perfectly still is exactly what the user was asked to do. See [ShapeSnapDetector.sample].
     */
    private fun trySnap(event: MotionEvent) {
        toPageSpace(event.getX(event.actionIndex), event.getY(event.actionIndex), snapPoint)
        when (val remaining = snap.sample(snapPoint.x, snapPoint.y, event.eventTime)) {
            ShapeSnapDetector.NOT_HOLDING -> removeCallbacks(snapTimer)

            ShapeSnapDetector.FIRE_NOW -> {
                removeCallbacks(snapTimer)
                snapNow()
            }

            // Settled but not for long enough. Re-posted per sample while still, which is a
            // message-queue swap and not work on the pen-to-pixel path.
            else -> {
                removeCallbacks(snapTimer)
                postDelayed(snapTimer, remaining)
            }
        }
    }

    /** One instance, reused — a fresh lambda per sample would allocate on the ink path (rule 7). */
    private val snapTimer = Runnable {
        if (snap.holdElapsed(SystemClock.uptimeMillis())) snapNow()
    }

    /** Forget the pointer a snap was handed. See [handOverPointerId]. */
    private fun clearHandOver() {
        handOverPointerId = MotionEvent.INVALID_POINTER_ID
    }

    /**
     * ⭐ The pen kept moving after a snap — give it the shape's endpoint.
     *
     * Draw a line, hold until it snaps, and *without lifting* carry on: the far end follows the pen.
     * The handle is already under the tip, because a snapped line ends where the gesture did.
     *
     * ⚠️ **[HAND_OVER_SLOP_DP] of movement first.** Arming on the snap and dragging on the very next
     * sample would take a hand's tremor as an adjustment — and a point drag commits an explicit
     * vertex list, so every snapped line would gain a `SetTextContent` it did not need. Below the
     * slop this returns false and the event falls through to the gestures that were live before.
     *
     * ⚠️ A **connector** has no point handles — its geometry is derived from what it is attached to
     * (`SelectionController.soleShape`) — so `beginPointDrag` answers false for one and the pointer
     * is released rather than half-claimed. A line drawn between two boxes *becomes* a connector, so
     * this is the common case and not a corner.
     */
    private fun handOverMove(event: MotionEvent): Boolean {
        if (handOverPointerId == MotionEvent.INVALID_POINTER_ID) return false
        val index = event.findPointerIndex(handOverPointerId)
        if (index < 0) {
            clearHandOver()
            return false
        }
        toPageSpace(event.getX(index), event.getY(index), snapPoint)
        val slopPt = HAND_OVER_SLOP_DP * displayMetrics.density / camera.scale
        if (hypot(snapPoint.x - handOverFrom.x, snapPoint.y - handOverFrom.y) < slopPt) return false

        val pointerId = handOverPointerId
        clearHandOver()
        if (!selectionController.beginPointDrag(handOverFrom.x, handOverFrom.y, pointerId, camera, pageSize)) {
            return false
        }
        selectionController.extend(event, camera)
        return true
    }

    /**
     * ⚠️ **Closes the gesture bracket [beginStroke] opened**, which `onStrokesFinished` normally
     * would — and it will never fire, because the stroke is cancelled rather than finished.
     * Leaving it open folds the *next* stroke into the same undo entry, so "draw a box, hold,
     * draw a line, undo" would remove both.
     */
    private fun snapNow() {
        val widthPt = pen.widthDp * displayMetrics.density / pixelsPerPoint
        // ⭐ In **page** points, through the camera — ROADMAP M8b's "snap-to-other-shapes for
        // endpoints" is a reach on the glass, not on the page. A constant in page units is a
        // different physical distance at every zoom, so a line would bind to a box it is nowhere
        // near when zoomed out and refuse one it is touching when zoomed in.
        //
        // ⚠️ **`dp × density ÷ scale`, and NOT the `÷ pixelsPerPoint` that `widthPt` above needs.**
        // The two conversions look interchangeable and are not: a *width* is authored in page
        // points, so dp reaches it through `pixelsPerPoint`; a *reach on the glass* is view pixels,
        // and view pixels are page units times the camera scale. Dividing by both shrank the radius
        // by a factor of `pixelsPerPoint`, and the emulator found it immediately — a line drawn
        // 20 px short of a box, which is how a person draws one, did not attach, while a line drawn
        // *through* both boxes did. `SelectionController.snapTolerance` is the same conversion and
        // is the one to copy; its own KDoc records the identical bug in the alignment guides.
        val bindSlopPt = ENDPOINT_BIND_SLOP_DP * displayMetrics.density / camera.scale
        if (!snap.snap(pen.colorArgb, widthPt, bindSlopPt)) {
            // Nothing was made, so there is nothing to hand the pointer to. Left set, the *next*
            // snap would inherit a pointer that lifted long ago.
            clearHandOver()
            return
        }
        strokes.endEdit()
    }

    /**
     * Fired after a gesture snapped, so the toolbar can say what it became.
     *
     * ⚠️ **A type rather than the two booleans it replaced.** It carried `(kind, attached)` and was
     * about to carry `(kind, attached, curved)` — three positional arguments, two of them booleans
     * of the same type, which is the shape of call site that eventually gets them the wrong way
     * round. `NoteSession.apply`'s deleted `undoable` flag is the same lesson: a wrong call that is
     * representable is eventually made.
     */
    public var onShapeSnapped: ((ShapeSnapResult) -> Unit)? = null

    private fun onMove(event: MotionEvent): Boolean {
        // ⭐ Disqualification bookkeeping only — two subtractions per tracked pointer, no camera
        // effect — and it runs before every gate on purpose. It used to sit behind the
        // palm-guard/camera gate below, so a two-finger PAN whose moves the guard was rejecting
        // was never disqualified as a tap, and a quick flick could lift into a spurious undo:
        // the page did not move and the last stroke vanished. Movement must always disqualify,
        // whatever the guard thinks of the individual events. (During a stylus gesture the
        // detector is already cancelled, so this is two compares and a return.)
        taps.onMove(event)
        if (stylusGestureMove(event)) return true
        // ⭐ M8c — no `noteStylusEvent`: the ruler is dragged by a **finger**, and telling the palm
        // guard a stylus had been near would then block the very pointer that is doing the moving.
        if (rulerController.isActive) {
            rulerController.extend(event, camera)
            return true
        }
        if (!gestures.isActive || !palmGuard.allowsFinger(event)) return false
        camera = gestures.onMove(event, camera).leashed()
        checkScrollTurn()
        return true
    }

    /**
     * The four gestures a pen can be in the middle of, in the order they can be live.
     *
     * Split out of [onMove] when M8c added the laser: they share a property the ruler does not,
     * which is that every one of them feeds [PalmGuard] — a palm that lands while any of these is
     * running must not start panning the page. Keeping the ruler on the other side of that line is
     * the point of the split, not the line count.
     *
     * @return true when one of them consumed the event.
     */
    private fun stylusGestureMove(event: MotionEvent): Boolean = handOverMove(event) || liveGestureMove(event)

    /** The four that were already running. Split from [stylusGestureMove] only to keep each honest
     *  about its own guard clauses — `ReturnCount` counts them, and the hand-over adds a fifth. */
    private fun liveGestureMove(event: MotionEvent): Boolean {
        val strokeId = activeStrokeId
        if (strokeId != null) {
            // ⭐ Only when the stroke is really a stylus's. With finger drawing on, this branch is
            // reached by a *finger*, and stamping the palm guard from it blacked out every finger
            // gesture — the two-finger undo of the stroke just drawn, first among them — for
            // 300 ms after each stroke.
            if (activeStrokeIsStylus) palmGuard.noteStylusEvent(event)
            // Four float compares. See StylusStats on why this is allowed here.
            event.findPointerIndex(strokePointerId).takeIf { it >= 0 }?.let { stylus.record(event, it) }
            // The whole MotionEvent goes in, historical samples and all —
            // androidx.ink walks getHistorySize()/getHistoricalAxisValue()
            // itself, and also reads pressure, tilt, orientation and tool type
            // straight off the event. Synthesising StrokeInputs by hand here
            // would silently drop every one of those (SPEC §3.3).
            val prediction = if (predictionEnabled) predictor?.predict() else null
            // ⚠️ **The prediction is projected too.** It is a separate `MotionEvent` and travels
            // through none of the event's own transforms, so a ruler that straightened the real
            // samples and left the predicted tail alone would draw a hook at the nib that
            // disappeared a frame later.
            val projected = ruled(event)
            val projectedPrediction = ruledOrNull(prediction)
            wetView.addToStroke(projected, strokePointerId, strokeId, projectedPrediction)
            release(event, projected)
            release(prediction, projectedPrediction)
            // ⭐ M8b — after the ink, never before it. Two float writes and a distance compare
            // (see [ShapeSnapDetector]); putting it ahead of `addToStroke` would add its cost to
            // the pen-to-pixel path that SPEC §3.6 measures, for no benefit at all.
            if (snap.isArmed) trySnap(event)
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
        if (laser.isActive) {
            palmGuard.noteStylusEvent(event)
            laser.extend(event, camera)
            return true
        }
        return false
    }

    private fun onPointerUp(event: MotionEvent): Boolean {
        val pointerId = event.getPointerId(event.actionIndex)
        val cancelled = event.isCancelledPointer()

        when (pointerId) {
            strokePointerId -> endStroke(event, pointerId, cancelled)

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

            // ⭐ M8c — the trail is **not** cleared on the lift. A laser mark fading out after the
            // pen leaves the glass is the feature; clearing it here would make pointing at
            // something require holding the pen there.
            laser.pointerId -> {
                if (!cancelled) laser.extend(event, camera)
                laser.end()
            }

            rulerController.pointerId -> if (cancelled) rulerController.cancel() else rulerController.end()

            else -> {
                gestures.removePointer(event, pointerId)
                onFingersLifted(event)
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) gestures.abandon()
        return true
    }

    /**
     * The pen left the glass — finish the stroke, or throw it away if the pointer was cancelled.
     *
     * ⭐ M8b — the detector is disarmed whichever way the gesture ended. One left armed would
     * measure the *next* stroke's dwell against this one's anchor and travel, and a timer left
     * posted would snap a gesture the user had already finished.
     */
    private fun endStroke(event: MotionEvent, pointerId: Int, cancelled: Boolean) {
        val strokeId = activeStrokeId
        if (strokeId != null) {
            if (cancelled) {
                // A cancel throws the stroke away; the event is only read for latency accounting,
                // so there is nothing here for the edge to straighten.
                wetView.cancelStroke(strokeId, event)
            } else {
                val projected = ruled(event)
                wetView.finishStroke(projected, pointerId, strokeId)
                release(event, projected)
            }
        }
        activeStrokeIsRuled = false
        // A finished stroke closes its own bracket in `onStrokesFinished`; a cancelled one never
        // reaches that listener, so it has to be closed here or the next stroke joins this one's
        // undo entry.
        if (cancelled) strokes.endEdit()
        snap.cancel()
        removeCallbacks(snapTimer)
        activeStrokeId = null
        strokePointerId = MotionEvent.INVALID_POINTER_ID
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
        // ⭐ M8c. The ruler keeps where it has been dragged to — there is nothing to put back,
        // because nothing was written and a straight edge left where the user's hand last had it is
        // not a lost edit. The laser's trail is left to fade for the same reason a lift leaves it.
        rulerController.cancel()
        laser.end()
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

        /**
         * How far outside the paper a stroke may still begin, in page points.
         *
         * A person writing to the very edge of the page lands a little past it, and refusing that
         * stroke would read as the last centimetre of the page not working. Half the widest brush
         * is about right: what the slack lets in is ink whose visible body is on the paper anyway,
         * and the clip takes off what is not.
         */
        const val EDGE_SLACK_PT = 6f
        const val UNDO_FINGERS = 2
        const val REDO_FINGERS = 3
        const val DOUBLE_TAP_ZOOM = 2f

        const val ZOOM_TOGGLE_THRESHOLD = 1.2f

        /** How much bigger than the framed box the view is, so the Element has room round it. */
        const val FRAME_ROOM = 1.8f

        /**
         * How close a snapped line's end must come to a shape to attach to it (ROADMAP M8b).
         *
         * A little wider than the selection tool's 12 dp snap, because a person drawing a line
         * *to* a box habitually stops a few millimetres short of it — the gap is how the drawing
         * reads. Too small and almost nothing binds; too large and a line passing near a box
         * attaches to it. ⚠️ Not measured on a device, like the dwell constants (SPEC §4.8.7).
         */
        const val ENDPOINT_BIND_SLOP_DP = 20f

        /**
         * How far the pen must travel after a snap before it is adjusting rather than resting.
         *
         * A hand holding still to trigger the snap is not holding *perfectly* still, and the
         * hand-over commits an explicit vertex list — so without a slop every snapped line would
         * gain a `SetTextContent` from a tremor. See [handOverMove].
         */
        const val HAND_OVER_SLOP_DP = 6f

        val IDENTITY = Matrix()
    }
}
