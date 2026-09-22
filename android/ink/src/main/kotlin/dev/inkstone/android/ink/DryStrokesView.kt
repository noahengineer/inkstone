package dev.inkstone.android.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RecordingCanvas
import android.graphics.RenderNode
import android.view.View
import androidx.ink.brush.TextureBitmapStore
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageSize
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The dry layer: every finished stroke, drawn through the camera.
 *
 * It sits *below* [androidx.ink.authoring.InProgressStrokesView] in the view
 * hierarchy, so wet ink is composited over it by the front buffer without this
 * view redrawing at all — which is the whole reason the two are separate.
 *
 * Strokes are held in page space (SPEC §4.4 rule 1) and the camera is applied
 * as a canvas transform, so panning and zooming never touch stroke geometry.
 *
 * ## Why the page→view matrix is passed to `draw` as well as concatenated
 *
 * `CanvasStrokeRenderer.draw`'s `strokeToScreenTransform` is not a drawing
 * transform — the canvas carries that. Its linear component becomes a **shader
 * uniform that sizes the antialiasing feather**, in stroke units. Get it wrong
 * by a factor of N and every edge is feathered N times too wide.
 *
 * `ViewStrokeRenderer` computes it for you, from `Canvas.getMatrix()`. That is
 * the documented path and it is what this view used first — and on the tablet
 * every stroke went soft the instant it left the wet layer, at the right size
 * and in the right place, which is the signature of a feather scaled by the
 * camera. The wet layer was unaffected because `InProgressStrokesView` knows
 * its own transforms and never asks the canvas.
 *
 * So the matrix is passed explicitly. It is already computed for the `concat`,
 * it is exact, and it removes a dependency on a deprecated method whose
 * behaviour on a hardware-accelerated canvas is not something to bet the most
 * important surface in the app on. Only the linear component is read, so the
 * view's own offset on screen is correctly irrelevant.
 *
 * ## ⭐ The cached page render, and why a pan may reuse it but a zoom may not
 *
 * `docs/M8-PERF-UNDO.md` §3 deferred this and named the risk. Until it landed, **every**
 * pan frame re-recorded the whole visible page — one [androidx.ink.rendering.android.canvas.CanvasStrokeRenderer]
 * draw per visible object, plus a `saveLayer`/`DST_OUT` composite per erased object — even
 * though nothing in page space had changed and only the camera's six floats had moved.
 * Measured on a Pixel Tablet AVD at 1,000 strokes: **101 ms median frame, 93 of 103 frames
 * `Slow UI thread`**, i.e. the cost was display-list *recording* on the CPU, not the GPU.
 *
 * The content is therefore recorded once into a [RenderNode] **in page space** and replayed
 * under the camera transform. What makes that sound is the sentence above: the
 * `strokeToScreenTransform` uniform reads only the matrix's **linear** component. A pan
 * changes only the translation, so the recorded feather is still exactly right and the node
 * can be replayed unchanged.
 *
 * A **zoom** does change it, and the first version of this therefore re-recorded per frame while
 * a pinch was in flight — which was defensible only as "no worse than before", and the user
 * reported it as *"when you zoom out there is some input lag"*. A zoom now replays the recording
 * within [ZOOM_REUSE_TOLERANCE] and [settleRecording] re-records at the exact scale once the
 * fingers stop. The geometry is a display list, so it re-rasterises exactly at whatever scale it
 * is replayed under; the feather is the only thing approximate, by a bounded ratio, for a
 * bounded time.
 *
 * ⚠️ **Writing does not invalidate the recording either — see [StrokeStore.appendsSince].**
 * Keying on `store.revision` alone meant a finished stroke re-recorded the whole page, measured
 * at 48–105 ms per re-record at 1,121 objects. Strokes appended on top are drawn over the
 * recording instead, and the store refuses to call anything else an append.
 *
 * ⚠️ **The node is deliberately not view-sized.** `docs/M8-PERF-UNDO.md` warned that a
 * view-sized `LAYER_TYPE_HARDWARE` clips pans and shows blank margins. This records a
 * page-space *region* — the viewport inflated by [RECORD_MARGIN_FRACTION] — and re-records
 * when the viewport leaves it, so a pan is free until it runs off the recorded edge and the
 * margins are never blank. Recording the whole page instead would make every re-record
 * O(page) even when zoomed into a corner, which is the case where a re-record is most
 * frequent (one per finished stroke).
 *
 * ⚠️ **A `RenderNode` can only be replayed into a hardware-accelerated canvas.** A software
 * canvas — a screenshot, a `View.draw(Canvas)` into a bitmap — gets the original uncached
 * path. That is a fallback and not a second renderer: both call the same [PagePainter], so
 * the two cannot disagree the way M3's duplicated thumbnail renderer did.
 */
internal class DryStrokesView(context: Context, private val store: StrokeStore, textureStore: TextureBitmapStore) :
    View(context) {

    /** Reused every frame — `onDraw` must not allocate. */
    private val cameraMatrix = CameraMatrix()

    /**
     * Paper, template, ink and erasures — shared with the thumbnail renderer.
     *
     * M3 moved this out of here. The library shows a picture of the same page, and two
     * renderers maintained side by side is how a preview ends up with different line
     * spacing or the highlighter behind the ink. See [PagePainter].
     */
    private val painter = PagePainter(textureStore)

    var camera: Camera = Camera.Identity
        set(value) {
            field = value
            invalidate()
        }

    /**
     * The paper: where it is in page space and how big it is (SPEC §4.5, [PageCanvas]).
     *
     * Was a bare [PageSize] until the canvas could grow up and to the left, at which point
     * "the paper is at the page-space origin" stopped being true and had to become a value
     * somebody says out loud.
     */
    var pageCanvas: PageCanvas = PageCanvas.fixed(PageSize.Letter)
        set(value) {
            field = value
            invalidate()
        }

    var pageBackground: Background = Background.Blank
        set(value) {
            field = value
            invalidate()
        }

    /**
     * The rasterised source page, when this page came from an imported PDF
     * (SPEC §6.2). Null until `:android:pdf` has rendered it, and null forever for
     * a page that never had one.
     *
     * Held, not owned: whoever rendered it also recycles it, and does so only after
     * clearing this. A recycled bitmap is checked for rather than trusted, because
     * the alternative is a crash in `onDraw` if a page is scrolled away mid-render.
     */
    var sourcePage: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    /**
     * ⭐ M8c — where an image object's pixels come from. Null draws the missing-asset placeholder.
     *
     * A pass-through to [PagePainter] rather than state of its own, so there is one answer to
     * *"what draws a picture"* and the invalidate that follows a cache being populated is here
     * rather than at whichever call site happened to remember it.
     */
    var pageAssets: PageAssets?
        get() = painter.assets
        set(value) {
            painter.assets = value
            // A [PageAssets] is a mutable cache: the same instance is handed back once a bitmap
            // has loaded, so it cannot be compared by value in [recordingIsUsable]. The epoch is
            // what makes "the pictures changed" representable at all.
            assetsEpoch++
            invalidate()
        }

    private var drawnRevision = -1L

    /** Called by the owner when [store] has changed under it. */
    fun onStoreChanged() {
        if (store.revision != drawnRevision) invalidate()
    }

    // --- The cached page render -------------------------------------------------------------
    //
    // See the class KDoc. Everything the recording depends on is compared in
    // [recordingIsUsable] at draw time rather than discarded from each setter: a setter that
    // forgets to discard is a stale picture on screen, and there is no way to notice one is
    // missing. Comparing here means a new dependency shows up as a field this function does not
    // mention, in the one place anybody looks.

    private val node = RenderNode("dry-page")
    private var assetsEpoch = 0L

    /** Reused every frame — `onDraw` must not allocate. See [StrokeStore.appendsSince]. */
    private val tail = ArrayList<DryObject>(STORE_APPEND_LIMIT)

    private var recordedRevision = -1L
    private var recordedScale = Float.NaN
    private var recordedAssetsEpoch = -1L
    private var recordedPageCanvas: PageCanvas? = null
    private var recordedBackground: Background? = null
    private var recordedSourcePage: Bitmap? = null
    private var recordedLeft = 0f
    private var recordedTop = 0f
    private var recordedRight = 0f
    private var recordedBottom = 0f

    /** The camera translation the node's device pixels were laid out for — see [drawNode]. */
    private var recordedTranslateX = 0f
    private var recordedTranslateY = 0f

    override fun onDraw(canvas: Canvas) {
        drawnRevision = store.revision

        val pageToView = cameraMatrix.pageToView(camera)
        // ⚠️ **The node is NOT drawn under this transform** — it holds device pixels and carries
        // its own mapping (see [drawNode]). Only the page-space content drawn *beside* it — the
        // append tail, the search highlight, and the whole software fallback — is.
        val saved = canvas.save()
        // ⚠️ The size guard is the same one `resetCamera` and `onSizeChanged` carry: a zero
        // viewport makes the page-space rectangle degenerate, and `RenderNode.setPosition`
        // rejects an empty one. Painting directly is correct and costs nothing at zero size.
        if (canvas is RecordingCanvas && width > 0 && height > 0) {
            // ⭐ The append tail. Writing on a full page changes `store.revision` per stroke, so
            // without this every few strokes re-records every object — 48–105 ms at 1,121 of them,
            // measured, and culling cannot help because at fit-width the whole page is on screen.
            // `appendsSince` answers null unless it can *prove* nothing but appending happened, so
            // the fallback is the correct full re-record rather than a stale page.
            val appended = recordingIsUsable() && store.appendsSince(recordedRevision, tail)
            if (!appended) record(pageToView)
            // Before the draw, so a layer decided on this frame takes effect on this frame.
            updateCompositingLayer(canvas)
            drawNode(canvas)
            // The tail is drawn over the layer rather than into it — a handful of draw calls,
            // and the alternative is re-rasterising the whole page once per stroke. In page
            // space, so it needs the camera the node does not.
            if (appended && tail.isNotEmpty()) {
                val tailSave = canvas.save()
                canvas.concat(pageToView)
                for (index in tail.indices) painter.paintOne(canvas, tail[index], pageToView, pageCanvas)
                canvas.restoreToCount(tailSave)
            }
            // Only while the replay is being stretched away from the scale it was recorded at.
            // A pan, or a pinch that has come to rest, cancels it and pays nothing.
            removeCallbacks(settleRecording)
            settlePending = recordedScale != camera.scale
            if (settlePending) postDelayed(settleRecording, SETTLE_DELAY_MS)
        } else {
            // Software canvas: a RenderNode cannot be replayed into one. Same painter, so the
            // picture is identical — see the class KDoc.
            canvas.concat(pageToView)
            paintPage(canvas, pageToView, visibleBoundsInPageSpace())
        }
        canvas.restoreToCount(saved)
        // ⭐ Outside the node, deliberately. SPEC §8.4's search highlight changes when the user
        // steps through hits, and nothing about the ink under it has changed — re-recording the
        // whole page to move a translucent rectangle would make stepping through search results
        // the most expensive thing in the editor. In page space, so it carries the camera itself.
        highlight?.let {
            val highlightSave = canvas.save()
            canvas.concat(pageToView)
            painter.paintHighlight(canvas, it)
            canvas.restoreToCount(highlightSave)
        }
    }

    /**
     * True when [node]'s display list is still a valid picture of **the objects it was recorded
     * from** — deliberately saying nothing about whether objects have since been added.
     *
     * The revision is the caller's business, because the two answers differ: a changed revision
     * may still be serviceable by drawing [StrokeStore.appendsSince]'s tail over the top, while
     * everything compared here invalidates the recording outright.
     *
     * The scale comparison is the load-bearing one and the class KDoc says why: the recorded
     * antialiasing feather is correct only for the scale it was recorded at.
     *
     * ⚠️ **It is a tolerance and not an equality, and [settleRecording] is what makes that
     * honest.** An exact compare re-recorded the whole page on every frame of a pinch — which was
     * defensible while that was merely no worse than before, and stopped being defensible when
     * the user reported *"when you zoom out there is some input lag"*. Within
     * [ZOOM_REUSE_TOLERANCE] the recording is replayed under the new transform: the geometry is a
     * display list and re-rasterises exactly, so the **only** thing wrong is the feather, by at
     * most that ratio, for as long as the fingers are moving. The settle pass then re-records at
     * the exact scale, so what the user is left looking at is never approximate.
     */
    private fun recordingIsUsable(): Boolean = node.hasDisplayList() &&
        scaleIsWithinTolerance() &&
        recordedAssetsEpoch == assetsEpoch &&
        recordedPageCanvas == pageCanvas &&
        recordedBackground == pageBackground &&
        recordedSourcePage === sourcePage &&
        viewportInsideRecordedRegion()

    /**
     * Whether the recording may be replayed at the camera's current scale.
     *
     * ⚠️ **The window is wider while the fingers are moving, and that is the point.** At rest the
     * recording is re-made at the exact scale, so what the user looks at is never approximate. But
     * a pinch produces a scale change every frame, and re-recording per frame is the 48–105 ms
     * cost §2b exists to avoid — while *refusing* to reuse costs the 2,000 draw calls §2d does.
     * The first compositing-layer build did exactly that and the report was
     * *"except when you zoom in or out then its really bad again"*.
     *
     * So during a gesture the recording is stretched over a much wider window
     * ([ZOOM_GESTURE_TOLERANCE]) and [settleRecording] makes it exact ~120 ms after the fingers
     * stop. This is the ordinary "scale the raster, refine at the end" that every map does.
     */
    private fun scaleIsWithinTolerance(): Boolean {
        val recorded = recordedScale
        if (recorded == camera.scale) return true
        if (recorded.isNaN() || recorded <= 0f || camera.scale <= 0f) return false
        val tolerance = if (settlePending) ZOOM_GESTURE_TOLERANCE else ZOOM_REUSE_TOLERANCE
        val ratio = camera.scale / recorded
        return ratio in (1f / tolerance)..tolerance
    }

    /** True while a scale change is still settling — i.e. the user is mid-pinch. */
    private var settlePending = false

    /**
     * ⭐ **Rasterise the recording once and blit it, instead of re-submitting it every frame.**
     *
     * ## The measurement this exists for
     *
     * Caching the *display list* removed the CPU cost of re-recording, and left the GPU cost
     * untouched: replaying a node still submits one `drawMesh` per stroke, every frame, and
     * androidx.ink offers no batched draw (`CanvasStrokeRenderer.draw` takes one `Stroke`; Skia's
     * `MeshOp::onCombineIfPossible` rejects custom meshes outright). On the Galaxy Tab, on a
     * 2,000-stroke note, the Measure panel read:
     *
     * > `Frames: 3 fps · mean 335 ms (draw 7.7 · issue 80 climbing) · worst 2336 ms`
     *
     * `draw` is the UI thread and was already fine. **`issue` is the RenderThread handing those
     * submissions to the GPU**, and the ~247 ms beyond the two is the GPU shading them. A
     * compositing layer collapses both: HWUI renders the node into a texture when its display
     * list changes, and every frame after that is one textured quad.
     *
     * ## ⚠️ Only at exactly 1:1, and that is the whole safety argument
     *
     * A stretched **display list** re-rasterises exactly — which is what makes §2c's zoom
     * tolerance sound. A stretched **texture** is genuinely resampled, i.e. soft. So the layer is
     * enabled only where it is provably a 1:1 blit ([recordedScale] == the live scale), and
     * disabled for every frame of a pinch, which then behaves exactly as it does today. Every way
     * this gate can fail degrades to the previous build rather than to a wrong picture.
     *
     * ⚠️ **A layer bigger than the GPU's maximum texture is dropped silently** — no crash, no log,
     * just the uncached path again, presenting as "slow on that one tablet". The size is checked
     * against the canvas's own reported maximum rather than a hardcoded 4096/8192, and the answer
     * is surfaced in the Measure panel so the device can be asked instead of guessed at.
     */
    private fun updateCompositingLayer(canvas: RecordingCanvas) {
        // ⚠️ **Not "only at exact scale" — that was the bug the zoom complaint named.** Gating the
        // layer on an exact match meant it switched *off* for every frame of a pinch, which is
        // precisely when the recording was being reused rather than re-made: the gesture paid the
        // full 2,000-draw-call submission with none of the cache's benefit. The layer now lives
        // wherever the recording itself is being reused ([scaleIsWithinTolerance]).
        //
        // What is given up is bounded and brief. A texture replayed at ratio `z` carries `1/z` of
        // device resolution, so a zoom-*in* mid-gesture is softer by that much — and a zoom-*out*
        // is not soft at all, being downsampled from a denser texture. [settleRecording] re-makes
        // it exactly ~120 ms after the fingers stop, so nothing the user pauses on is approximate.
        // That is the same trade every map application makes, and it is a far better one than
        // three frames a second.
        val reusable = scaleIsWithinTolerance()
        // The node is already in device pixels, so its own size is the texture size — no
        // conversion to get wrong a second time.
        val fits = node.width <= canvas.maximumBitmapWidth && node.height <= canvas.maximumBitmapHeight
        val wanted = reusable && fits
        if (wanted != layerEnabled) {
            node.setUseCompositingLayer(wanted, null)
            layerEnabled = wanted
        }
        layerRefused = reusable && !fits
        maxTextureWidth = canvas.maximumBitmapWidth
        maxTextureHeight = canvas.maximumBitmapHeight
    }

    /** Whether the cached page is currently a rasterised texture rather than a display list. */
    internal var layerEnabled: Boolean = false
        private set

    /** True when a layer was wanted and the recorded region was too large for one. */
    internal var layerRefused: Boolean = false
        private set

    internal var maxTextureWidth: Int = 0
        private set

    internal var maxTextureHeight: Int = 0
        private set

    /** The recording's size in device pixels — the texture a layer rasterises it into. */
    internal val nodeWidth: Int get() = node.width

    internal val nodeHeight: Int get() = node.height

    /**
     * Re-records at the exact scale once the camera has stopped moving.
     *
     * Posted (and re-posted) from `onDraw` for as long as the replayed recording is being scaled
     * away from the scale it was made at, so a pinch pays **one** re-record when the fingers lift
     * rather than one per frame while they move. Forcing it through `recordedScale = NaN` rather
     * than a second flag keeps [recordingIsUsable] the only thing that decides — NaN compares
     * false against everything, including itself, which is exactly the answer wanted here.
     */
    private val settleRecording = Runnable {
        recordedScale = Float.NaN
        invalidate()
    }

    /**
     * Records the page into [node], over the viewport inflated by [RECORD_MARGIN_FRACTION].
     *
     * @param pageToView passed on to the painter as the antialiasing scale — **not** the
     *   recording canvas's own transform. The recording canvas carries a translation only (the
     *   node's origin), and the feather reads the linear component, so the matrix the content
     *   will finally be drawn through is the right one to hand over. Handing over the recording
     *   canvas's matrix instead would feather every stroke as though the camera were at 100%.
     */
    private fun record(pageToView: android.graphics.Matrix) {
        val viewport = visibleBoundsInPageSpace()
        val marginX = (viewport.xMax - viewport.xMin) * RECORD_MARGIN_FRACTION
        val marginY = (viewport.yMax - viewport.yMin) * RECORD_MARGIN_FRACTION
        recordedLeft = viewport.xMin - marginX
        recordedTop = viewport.yMin - marginY
        recordedRight = viewport.xMax + marginX
        recordedBottom = viewport.yMax + marginY

        // ⚠️ **The node is positioned in DEVICE pixels, and getting this wrong is a visible bug
        // rather than a slow one.** `RenderNode.setPosition` defines the node's size, and a node
        // promoted to a compositing layer is rasterised into a texture *of that size*. The first
        // version of this passed the page-space rectangle — roughly 918×1188 for a Letter page —
        // so the layer was a 918×1188 texture that the camera then magnified to fill a 2560 px
        // viewport. It shipped, and the report was "when you dont move around everything is very
        // pixelated": the layer engages exactly when the camera is at rest, so the artefact
        // appeared precisely when the user stopped to look at the page.
        recordedTranslateX = camera.translate.x
        recordedTranslateY = camera.translate.y
        val left = floor(recordedLeft * camera.scale + recordedTranslateX).toInt()
        val top = floor(recordedTop * camera.scale + recordedTranslateY).toInt()
        val right = ceil(recordedRight * camera.scale + recordedTranslateX).toInt()
        val bottom = ceil(recordedBottom * camera.scale + recordedTranslateY).toInt()
        node.setPosition(left, top, right, bottom)

        val recording = node.beginRecording()
        try {
            // The recording canvas's origin is the node's top-left in device space, and the
            // content is authored in page space — so undo the node's device origin, then apply the
            // camera. `paintPage` below is then drawing in page space exactly as it does on the
            // software path, and the texture is rasterised at true device resolution.
            recording.translate(-left.toFloat(), -top.toFloat())
            recording.concat(pageToView)
            paintPage(
                recording,
                pageToView,
                ImmutableBox.fromTwoPoints(
                    ImmutableVec(recordedLeft, recordedTop),
                    ImmutableVec(recordedRight, recordedBottom),
                ),
            )
        } finally {
            node.endRecording()
        }

        recordedRevision = store.revision
        recordedScale = camera.scale
        recordedAssetsEpoch = assetsEpoch
        recordedPageCanvas = pageCanvas
        recordedBackground = pageBackground
        recordedSourcePage = sourcePage
    }

    /**
     * Draws [node], mapping *device space as it was at record time* onto device space now.
     *
     * The node holds pixels laid out for the camera it was recorded with. For a camera `C` and a
     * recording camera `R`, a page point `p` sits at `d = R.scale·p + R.translate` in the node and
     * must land at `C.scale·p + C.translate` on screen — so the mapping is a scale of
     * `s = C.scale / R.scale` and a translation of `C.translate − s·R.translate`.
     *
     * At the same scale — every pan, and every frame the compositing layer is allowed on — `s` is
     * exactly 1 and this is a pure translation, which is what makes a cached texture legitimate
     * there. During a pinch `s` is the zoom ratio and the layer is off, so what is being stretched
     * is a display list, which re-rasterises exactly.
     */
    private fun drawNode(canvas: RecordingCanvas) {
        val scale = camera.scale / recordedScale
        val saved = canvas.save()
        canvas.translate(
            camera.translate.x - scale * recordedTranslateX,
            camera.translate.y - scale * recordedTranslateY,
        )
        canvas.scale(scale, scale)
        canvas.drawRenderNode(node)
        canvas.restoreToCount(saved)
    }

    /** Paper, template, source page, ink and the page edge — the whole cacheable picture. */
    private fun paintPage(canvas: Canvas, pageToView: android.graphics.Matrix, cull: ImmutableBox) {
        painter.paintBackground(canvas, pageCanvas, pageBackground, sourcePage)
        painter.paintObjects(canvas, store, pageToView, cull, pageCanvas)
        painter.paintBorder(canvas, pageCanvas)
    }

    /**
     * Whether the viewport still lies inside the recorded region — in plain floats.
     *
     * `onDraw` must not allocate (CLAUDE.md rule 7), and [visibleBoundsInPageSpace] builds a box
     * and two vectors. Once per frame for the *cull* rectangle was already the cost; asking the
     * same question three times a frame to decide whether to re-record would have tripled it, on
     * the one path where the whole point of this change is to do less work per frame.
     */
    private fun viewportInsideRecordedRegion(): Boolean {
        val inverseScale = 1f / camera.scale
        val left = (0f - camera.translate.x) * inverseScale - CULL_MARGIN_PT
        val top = (0f - camera.translate.y) * inverseScale - CULL_MARGIN_PT
        val right = (width - camera.translate.x) * inverseScale + CULL_MARGIN_PT
        val bottom = (height - camera.translate.y) * inverseScale + CULL_MARGIN_PT
        return left >= recordedLeft && top >= recordedTop && right <= recordedRight && bottom <= recordedBottom
    }

    /**
     * The display list holds native memory and a reference to every mesh it drew, so it is
     * dropped when the view leaves the hierarchy rather than kept alive by a detached view.
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(settleRecording)
        node.discardDisplayList()
        recordedRevision = -1L
    }

    /**
     * SPEC §8.4 — the box a search hit landed on, or null.
     *
     * Drawn inside the same page-space transform as the ink, so it stays on the word through a
     * pan or a zoom rather than being a rectangle pinned to the screen.
     */
    var highlight: dev.inkstone.core.model.PageRect? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * The viewport in page space, with a margin so a stroke whose *centre line*
     * is just off-screen but whose width still bleeds in is not culled.
     */
    private fun visibleBoundsInPageSpace(): ImmutableBox {
        val inverseScale = 1f / camera.scale
        val left = (0f - camera.translate.x) * inverseScale
        val top = (0f - camera.translate.y) * inverseScale
        val right = (width - camera.translate.x) * inverseScale
        val bottom = (height - camera.translate.y) * inverseScale
        val margin = CULL_MARGIN_PT
        return ImmutableBox.fromTwoPoints(
            ImmutableVec(left - margin, top - margin),
            ImmutableVec(right + margin, bottom + margin),
        )
    }

    private companion object {
        const val CULL_MARGIN_PT = 24f

        /**
         * How far past the viewport the cached render reaches, as a fraction of the viewport.
         *
         * The trade is the ordinary one: a bigger margin means a pan travels further before it
         * runs off the recording, and every re-record costs more.
         *
         * ⚠️ **Lowered from 0.5 when the compositing layer landed, and the reason is memory, not
         * time.** The layer is a texture the size of the recorded region in *device pixels*: at
         * 0.5 that is 2× the viewport in each direction, so on a 2560×1600 tablet 5120×3200 — 65 MB
         * of GPU memory, and close enough to `GL_MAX_TEXTURE_SIZE` on a mid-range Mali to be
         * refused outright on some devices (silently — see [updateCompositingLayer]). 0.25 is
         * 3840×2400, about 37 MB, comfortably inside 8192 while still giving half a screen of pan
         * slack in every direction.
         */
        const val RECORD_MARGIN_FRACTION = 0.25f

        /**
         * How far the camera may zoom away from a recording before it must be re-made.
         *
         * The only thing that goes wrong inside this window is the antialiasing feather, by at
         * most this ratio — the geometry is a display list and re-rasterises exactly at whatever
         * scale it is replayed under — and the compositing layer, which would *not* re-rasterise
         * exactly, is switched off for the whole of this window ([updateCompositingLayer]).
         *
         * 1.3 is chosen to stay inside [RECORD_MARGIN_FRACTION]'s window too: the recorded region
         * is **1.5×** the viewport, so a zoom-*out* within this tolerance cannot reach its edge and
         * show a blank margin, which is the failure `docs/M8-PERF-UNDO.md` warned a cached layer
         * would produce. ⚠️ The two constants are coupled — raising this one past the margin's
         * window reintroduces exactly that bug.
         *
         * ⚠️ It is deliberately not larger. A soft edge is the exact symptom of the tablet bug
         * this file's KDoc opens with, and the point of a *bounded* approximation held for a few
         * hundred milliseconds is that nobody can mistake it for that bug returning.
         */
        const val ZOOM_REUSE_TOLERANCE = 1.3f

        /**
         * How far the camera may zoom from a recording **while the pinch is still in progress**.
         *
         * A gesture spanning 3× against [ZOOM_REUSE_TOLERANCE] would cross the window
         * `log(3)/log(1.3) ≈ 4` times, and each crossing is a full re-record — 48–105 ms at a
         * thousand objects, i.e. four hitches through the pinch. 4.0 covers essentially any single
         * pinch in one recording, so the gesture costs **zero** re-records and one textured quad
         * per frame.
         *
         * The price is resolution while moving, and only when zooming **in**: at ratio `z` the
         * texture carries `1/z` of device resolution, so a 4× zoom-in is briefly soft, bounded,
         * and gone 120 ms after the fingers stop. A zoom-*out* is downsampled from a denser
         * texture and is not soft at all.
         *
         * ⚠️ **This constant does not bound the zoom-out case, and must not be relied on to.**
         * What stops a zoom-out showing a blank margin is `viewportInsideRecordedRegion()` in
         * [recordingIsUsable] — the recorded region is 1.5× the viewport, and leaving it forces a
         * re-record regardless of what this says. The two guards are independent on purpose:
         * this one is about how sharp the cache is, that one about whether it covers the screen.
         */
        const val ZOOM_GESTURE_TOLERANCE = 4.0f

        /**
         * How long after the last camera change the exact re-record happens.
         *
         * Long enough that a pinch's own frames do not each reschedule a wasted record, short
         * enough that the approximate feather is gone before anyone looks at a still page.
         */
        const val SETTLE_DELAY_MS = 120L

        /** Initial capacity for [tail] — the store's own cap, so it never grows. */
        const val STORE_APPEND_LIMIT = 48
    }
}
