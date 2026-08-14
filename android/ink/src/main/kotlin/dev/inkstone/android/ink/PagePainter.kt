package dev.inkstone.android.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.ink.brush.TextureBitmapStore
import androidx.ink.geometry.Box
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import dev.inkstone.android.ink.InkColor.withOpacity
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.PageSize

/**
 * Draws a page — paper, template, ink, erasures — onto a [Canvas] that already carries the
 * page→canvas transform.
 *
 * ## Why this is its own class
 *
 * It is used by two things that must not disagree: [DryStrokesView], which draws what the
 * user is looking at, and [InkPageThumbnailRenderer], which draws the picture the library
 * shows of the same page. A thumbnail whose rules are spaced differently, or whose
 * highlighter sits behind the ink, is a bug the user sees as "the preview is wrong"
 * — and it is exactly what happens when two renderers are maintained side by side.
 *
 * The three things that have to be shared, and were duplicated before this existed:
 *
 * 1. **Render order.** [StrokeStore.forEachInRenderOrder] emits highlighters *last*, with
 *    plain alpha, so a highlight tints the writing it covers (SPEC §3.4 — reversed after
 *    M4; [InkBrushKind.drawsOverInk] records why, and why a multiply blend was rejected).
 * 2. **Erasure compositing.** An erased stroke is drawn into an offscreen layer and the
 *    swept region punched out with `DST_OUT` (SPEC §3.5). Clipping would be cheaper and
 *    its edge is only as good as the hardware renderer's clip antialiasing.
 * 3. **The `strokeToScreenTransform` argument.** It is not a drawing transform — the
 *    canvas carries that. Its linear component sizes the antialiasing feather, in stroke
 *    units, so passing the wrong one makes every edge soft. [DryStrokesView] documents the
 *    device bug that taught us this.
 *
 * Not thread-safe: an instance owns reusable paints and a scratch rect, and `onDraw` must
 * not allocate (CLAUDE.md rule 7). The thumbnail renderer creates its own.
 *
 * ## Software canvases
 *
 * A thumbnail draws onto a `Bitmap`-backed [Canvas], which is not hardware accelerated, so
 * ink's `drawMesh` fast path is unavailable there. **The renderer decides that per draw
 * call**, from the canvas it is handed — nothing here has to ask for it.
 *
 * It would be marginally cheaper to say so up front, and `CanvasStrokeRenderer` has a
 * `create(forcePathRendering, textureStore)` overload that does exactly that. It is
 * `@RestrictTo(LIBRARY_GROUP)`, and lint fails the build on it — one more entry in the list
 * CLAUDE.md keeps of `androidx.ink` 1.0.0's stable-but-restricted surface. The single-argument
 * `create` is public and does the right thing anyway.
 */
internal class PagePainter(textureStore: TextureBitmapStore = NoTextures) {

    private val renderer = CanvasStrokeRenderer.create(textureStore)

    /** ROADMAP M2's templates, ruled in page space so zoom cannot change the spacing. */
    private val template = TemplateBackground()

    private val paperPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = false
    }

    private val borderPaint = Paint().apply {
        color = PAGE_BORDER_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }

    /** Punches the erased region out of the layer a stroke was drawn into. */
    private val eraserPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    private val layerBounds = RectF()

    /** Reused every frame — `onDraw` must not allocate (CLAUDE.md rule 7). */
    private val pageBounds = RectF()

    /**
     * Filtered, because a source page is rasterised once at a fixed width and then
     * scaled by the camera; unfiltered it goes to visible pixel blocks the moment
     * anyone zooms in, which is most of the time on a tablet.
     */
    private val imagePaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }

    /**
     * White paper, the imported page if there is one, and the template rules.
     * Everything under the ink.
     *
     * @param sourcePage a rasterised [Background.SourcePdfPage], or null. Passed in
     *   rather than fetched: rendering a PDF page is `:android:pdf`'s job (SPEC §6.1)
     *   and it is not work to do inside `onDraw`. A null is a page whose source could
     *   not be rendered — the ink still draws, on white paper, which is the honest
     *   failure. It is never a reason not to show a note.
     *
     *   Drawn into the page's own rectangle in **page space**, so it lands under the
     *   strokes at every zoom by construction: the canvas already carries the camera,
     *   and page space is defined as the CropBox with `/Rotate` applied (SPEC §4.4) —
     *   which is exactly the rectangle a rendered page fills.
     */
    fun paintBackground(canvas: Canvas, pageSize: PageSize, background: Background, sourcePage: Bitmap? = null) {
        canvas.drawRect(0f, 0f, pageSize.widthPt, pageSize.heightPt, paperPaint)
        if (sourcePage != null && !sourcePage.isRecycled) {
            pageBounds.set(0f, 0f, pageSize.widthPt, pageSize.heightPt)
            canvas.drawBitmap(sourcePage, null, pageBounds, imagePaint)
        }
        // Under the ink, over the paper. Culling it would mean clipping every rule to the
        // viewport, and a page's worth of lines is a rounding error next to 5,000 strokes.
        template.draw(canvas, background, pageSize)
    }

    /** The page's edge. Drawn last so ink cannot sit on top of it. */
    fun paintBorder(canvas: Canvas, pageSize: PageSize) {
        canvas.drawRect(0f, 0f, pageSize.widthPt, pageSize.heightPt, borderPaint)
    }

    /**
     * Every object in [store], in render order.
     *
     * @param pageToCanvas the transform the canvas already carries. Passed on to the ink
     *   renderer as its antialiasing scale — see the class KDoc, point 3.
     * @param visibleBounds page-space culling rect, or null to draw everything. A thumbnail
     *   passes null: the whole page is "on screen" by definition.
     */
    fun paintObjects(canvas: Canvas, store: StrokeStore, pageToCanvas: Matrix, visibleBounds: Box?) {
        store.forEachInRenderOrder(visibleBounds) { dry -> paintObject(canvas, dry, pageToCanvas) }
    }

    /**
     * SPEC §8.4's search highlight: a translucent wash over the word that was found.
     *
     * Painted *over* the ink rather than behind it, and with alpha rather than a blend mode —
     * this is an ordinary dry-layer draw, but the rule from SPEC §3.4 still applies in spirit:
     * a blend mode here would look different the moment the wet layer was involved, and the
     * highlight has no reason to want one.
     */
    fun paintHighlight(canvas: Canvas, box: dev.inkstone.core.model.PageRect) {
        canvas.drawRect(box.left, box.top, box.right, box.bottom, highlightPaint)
    }

    /** Amber at ~40% — visible over black ink, and not mistakable for a highlighter stroke. */
    private val highlightPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x66FFC107
        isAntiAlias = true
    }

    /**
     * ⭐ M8a — draws one object **through its own transform**.
     *
     * The object's matrix is concatenated onto the canvas rather than applied to the geometry,
     * for the reason SPEC §3.5 gives about erasing and SPEC §5.4 about the blob: the stroke is
     * never modified, so a move is a matrix and not a rewrite, and undo is writing back the
     * previous matrix rather than reconstructing points.
     *
     * ⚠️ **`strokeToScreenTransform` must include the object matrix too.** It is not a drawing
     * transform — the canvas carries that — but its linear component sizes the antialiasing
     * feather in stroke units (see [DryStrokesView] for the device bug that taught us this).
     * Pass the page→canvas matrix alone and a stroke scaled to 300% is feathered as though it
     * were still at 100%: crisp at the wrong width, which reads as "the ink went blurry when I
     * resized it" and is not obviously a transform problem at all.
     *
     * Once concatenated, **the canvas is in the object's own space** — which is the space the
     * erased region and the object's [DryObject.bounds] are already in, so everything below this
     * line is unchanged from before M8a and needs no mapping.
     */
    private fun paintObject(canvas: Canvas, dry: DryObject, pageToCanvas: Matrix) {
        val transform = dry.transform
        if (transform == Matrix3.Identity) {
            paintInObjectSpace(canvas, dry, pageToCanvas)
            return
        }
        transform.into(objectMatrix)
        objectToCanvas.set(pageToCanvas)
        objectToCanvas.preConcat(objectMatrix)
        val saved = canvas.save()
        canvas.concat(objectMatrix)
        paintInObjectSpace(canvas, dry, objectToCanvas)
        canvas.restoreToCount(saved)
    }

    /**
     * Draws one object, minus whatever an eraser has taken out of it, onto a canvas that is
     * already in the object's own space.
     *
     * The layer is bounded to the object, and only objects that have actually been erased
     * pay for one at all, so the common case is untouched.
     *
     * ⭐ **M8c — the compositing is the shared half and the content is not.** This
     * `saveLayer`/`DST_OUT` sandwich is kind-agnostic by construction: it needs the object's own
     * box and its swept region, both of which every [DryObject] has, and it punches the region out
     * of whatever was drawn into the layer. Anything a later kind draws inherits erasing for free,
     * which is `docs/M8-CONNECTORS.md` §6 item 2's stated payoff.
     */
    private fun paintInObjectSpace(canvas: Canvas, dry: DryObject, strokeToCanvas: Matrix) {
        val erased = dry.erased
        val bounds = dry.bounds
        if (erased == null || bounds == null) {
            paintContent(canvas, dry, strokeToCanvas)
            return
        }

        // The layer must cover the whole object, because the whole object is drawn into it.
        // Padding keeps the brush's own antialiased edge from being cut off by the layer
        // boundary.
        layerBounds.set(
            bounds.xMin - LAYER_PADDING_PT,
            bounds.yMin - LAYER_PADDING_PT,
            bounds.xMax + LAYER_PADDING_PT,
            bounds.yMax + LAYER_PADDING_PT,
        )
        val layer = canvas.saveLayer(layerBounds, null)
        paintContent(canvas, dry, strokeToCanvas)
        // One stroked draw per constant-radius run. Skia expands the polyline into the swept
        // capsule, caches the tessellation, and resolves the path's self-overlap in a single
        // coverage pass — so a scribbled erase does not double-darken its own crossings.
        val runs = erased.runs
        for (index in runs.indices) {
            val run = runs[index]
            eraserPaint.strokeWidth = run.radius * 2f
            canvas.drawPath(run.path, eraserPaint)
        }
        canvas.restoreToCount(layer)
    }

    /**
     * ⭐ M8c — what the object actually *is*, drawn in its own space.
     *
     * Exhaustive over the sealed [DryObject], and that is the point of it being sealed: a kind
     * added without a painter is a compile error here rather than an object that is in the log, in
     * the z-order and invisible on the glass. SPEC §4.9.3 describes exactly that state, and it is
     * survivable only for as long as it is *deliberate*.
     */
    private fun paintContent(canvas: Canvas, dry: DryObject, strokeToCanvas: Matrix) {
        when (dry) {
            is DryStroke -> renderer.draw(canvas, dry.stroke, strokeToCanvas)

            is DryShape -> paintShape(canvas, dry)

            // Nothing, on purpose — see [DryPlaceholder]. It holds its place in the z-order and
            // carries its registers; a painter for images and text boxes is the next step owed,
            // and it arrives as a new [DryObject] kind with a branch here, not as a flag on this
            // one.
            is DryPlaceholder -> Unit
        }
    }

    /**
     * ⭐ M8b — a shape or a connector, in its own space.
     *
     * **Fill first, then the outline**, so a stroke of the same colour reads as an edge rather than
     * thickening the fill inwards and outwards unevenly. The fill is skipped for a kind with no
     * interior (`ShapeGeometry.isFillable`) whatever colour the record asks for — a line has no
     * inside, and filling its degenerate path draws a hairline artefact along it.
     *
     * ⚠️ **The opacity is multiplied into the colour's own alpha**, exactly as `DryStroke` does for
     * a restyled highlighter, rather than set on the paint: `ObjectStyle.opacity` and
     * `ShapeRecord.fillOpacity` are both *"this fraction of what it already was"*, so replacing the
     * alpha would make a 50% wash of an already-translucent colour come out more opaque than the
     * colour it started from.
     *
     * ⚠️ **No blend mode, deliberately.** SPEC §3.4 and CLAUDE.md: `InProgressStrokesView`
     * front-buffers through a `SurfaceView` that only alpha-blends, so anything whose dry rendering
     * used a blend mode would visibly change the instant the gesture landed. A translucent fill is
     * plain alpha for the same reason the highlighter is.
     */
    private fun paintShape(canvas: Canvas, dry: DryShape) {
        val record = dry.record
        if (dry.fillable) {
            val fill = record.fillArgb ?: 0
            fillPaint.color = fill.withOpacity(record.fillOpacity)
            canvas.drawPath(dry.path, fillPaint)
        }
        // The one fallback chain — live register, payload, defaults — lives on [DryShape], so
        // what a first restyle's baseline writes down is exactly what was painted.
        val style = dry.effectiveStyle()
        if (style.widthPt <= 0f) return
        shapePaint.color = style.colorArgb.withOpacity(style.opacity)
        shapePaint.strokeWidth = style.widthPt
        canvas.drawPath(dry.path, shapePaint)
        // The head is rebuilt rather than cached because it is two lines and it depends on the
        // stroke width the *style register* currently says — which a peer can change without the
        // outline changing at all.
        dry.arrowHead(style.widthPt * ARROW_HEAD_WIDTHS)?.let { canvas.drawPath(it, shapePaint) }
    }

    /** Filled interior. Antialiased, no blend mode — see [paintShape]. */
    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /** The outline and the arrowhead. Round joins, because a mitre on a tight corner spikes. */
    private val shapePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    /** Reused every frame — `onDraw` must not allocate (CLAUDE.md rule 7). */
    private val objectMatrix = Matrix()
    private val objectToCanvas = Matrix()
    private val matrixValues = FloatArray(MATRIX_VALUES)

    /**
     * [Matrix3] (two rows, the third implied) into an `android.graphics.Matrix` (all nine).
     *
     * Row-major in both, in the same order, so this is a copy and not a transpose — which is
     * worth stating because getting it wrong produces a transform that is *plausible*: a pure
     * scale survives a transpose unchanged, so the mistake only shows up once something is
     * rotated, by which point the change that caused it is long merged.
     */
    // The nine indices ARE the matrix layout, exactly as the byte offsets are the format in
    // `StrokeGeometryCodec` (which suppresses this for the same reason). A constant per slot would
    // say "row one, column two" less clearly than `[5]` does, and would make the block harder to
    // check against the six fields above it.
    @Suppress("MagicNumber")
    private fun Matrix3.into(out: Matrix) {
        matrixValues[0] = m00
        matrixValues[1] = m01
        matrixValues[2] = m02
        matrixValues[3] = m10
        matrixValues[4] = m11
        matrixValues[5] = m12
        matrixValues[6] = 0f
        matrixValues[7] = 0f
        matrixValues[8] = 1f
        out.setValues(matrixValues)
    }

    // `withOpacity` was here, duplicated from `StrokeStore.kt` with a note saying *"if a third
    // appears, hoist all three"*. M8b's shape fill in `InkStrokeOutliner` is the third; it lives in
    // [InkColor] now, shared by the screen, the brush and the published PDF.

    internal companion object {
        const val PAGE_BORDER_COLOR = 0x22000000

        /**
         * Barb length as a multiple of the line width — proportional, so it survives a resize.
         *
         * ⭐ Read by [InkStrokeOutliner] as well, so a published arrow's head is the size of the
         * drawn one. Two constants would be two arrowheads that agree until somebody tunes one.
         */
        const val ARROW_HEAD_WIDTHS = 5f

        const val LAYER_PADDING_PT = 2f
        const val MATRIX_VALUES = 9
    }
}
