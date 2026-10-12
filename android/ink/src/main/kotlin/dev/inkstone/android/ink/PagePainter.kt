package dev.inkstone.android.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import androidx.ink.brush.TextureBitmapStore
import androidx.ink.geometry.Box
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import dev.inkstone.android.ink.InkColor.withOpacity
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.PageCanvas
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

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
     * @param pageToCanvas the transform the canvas already carries, read for its scale only — the
     *   dot grid bakes its lattice at the pixel density it will be drawn at. Null is 1:1.
     */
    fun paintBackground(
        canvas: Canvas,
        page: PageCanvas,
        background: Background,
        sourcePage: Bitmap? = null,
        pageToCanvas: Matrix? = null,
    ) {
        backdrop?.let {
            paintBackdrop(canvas, page, it)
            return
        }
        val pageSize = page.size
        // ⭐ The paper's own top-left, which is the page-space origin on every fixed page and is
        // negative on a canvas that has grown up or to the left (SPEC §4.5, [PageCanvas]).
        // Translating here rather than offsetting each draw is what keeps everything below this
        // line — the source page's rectangle and every template rule — written against a plain
        // `0..width` extent, exactly as it was when the paper could only be at the origin.
        val saved = canvas.save()
        canvas.translate(page.left, page.top)
        canvas.drawRect(0f, 0f, pageSize.widthPt, pageSize.heightPt, paperPaint)
        if (sourcePage != null && !sourcePage.isRecycled) {
            pageBounds.set(0f, 0f, pageSize.widthPt, pageSize.heightPt)
            canvas.drawBitmap(sourcePage, null, pageBounds, imagePaint)
        }
        // Under the ink, over the paper. Not culled: a page's worth of *lines* is a few hundred draws
        // even on a grown canvas. The dots were the exception — one draw each, 39,000 on the physics
        // note — and are a single shaded rectangle now (`TemplateBackground.drawDots`).
        template.draw(canvas, background, pageSize, pxPerPt = pageToCanvas?.mapRadius(1f) ?: 1f)
        canvas.restoreToCount(saved)
    }

    /** The page's edge. Drawn last so ink cannot sit on top of it. */
    fun paintBorder(canvas: Canvas, page: PageCanvas) {
        // The Element editor has no paper, so it has no paper's edge either.
        if (backdrop != null) return
        canvas.drawRect(page.left, page.top, page.right, page.bottom, borderPaint)
    }

    // -----------------------------------------------------------------------
    // ⭐ The Element editor's canvas (SPEC §4.9.7)
    // -----------------------------------------------------------------------

    /**
     * Not null while an Element is being edited: a grid replaces the paper, and [showCutters] draws
     * every invisible knockout area as a dashed outline so it can be seen, selected and moved.
     *
     * *"I would not want it to have like a paper — a black grid background to show it's just that
     * element."* The grid is drawn across the page rectangle, which the editor makes far larger than
     * the screen, so there is no edge anywhere a person would reach.
     */
    var backdrop: ElementBackdrop? = null

    /** The editor's grid cell, in page points — the lattice it snaps to (`InkSurfaceView.elementGridPt`). */
    var backdropCellPt: Float = BACKDROP_CELL_PT

    /** Whether invisible knockout areas are drawn — only on the Element editor's canvas. */
    val showCutters: Boolean get() = backdrop != null

    private fun paintBackdrop(canvas: Canvas, page: PageCanvas, backdrop: ElementBackdrop) {
        backdropPaint.color = backdrop.groundArgb
        canvas.drawRect(page.left, page.top, page.right, page.bottom, backdropPaint)
        var step = 0
        val cell = backdropCellPt
        var x = page.left - page.left % cell
        while (x <= page.right) {
            gridPaint.color = if (step++ % BACKDROP_MAJOR_EVERY == 0) backdrop.majorArgb else backdrop.minorArgb
            canvas.drawLine(x, page.top, x, page.bottom, gridPaint)
            x += cell
        }
        step = 0
        var y = page.top - page.top % cell
        while (y <= page.bottom) {
            gridPaint.color = if (step++ % BACKDROP_MAJOR_EVERY == 0) backdrop.majorArgb else backdrop.minorArgb
            canvas.drawLine(page.left, y, page.right, y, gridPaint)
            y += cell
        }
    }

    /**
     * ⭐ SPEC §4.9.7 — the Element editor's guides, **over** every object: each knockout area dashed in
     * sky blue, and the Element's bounds dashed in amber with its pivot marked.
     *
     * On top rather than at each object's own place in the stack, because an area sits at the bottom
     * of the Element (it hides the note *behind* a copy, not the Element's own pieces) — drawn in
     * place it was hidden under any filled piece above it, which is half of *"hard to position
     * right"*. In page space, so a dash is the same length however the guide has been stretched.
     */
    fun paintGuides(canvas: Canvas, store: StrokeStore) {
        if (!showCutters) return
        store.forEachInRenderOrder(visibleBounds = null) { dry ->
            if (dry !is DryShape || !dry.isGuide) return@forEachInRenderOrder
            dry.transform.into(objectMatrix)
            guidePath.set(dry.path)
            guidePath.transform(objectMatrix)
            canvas.drawPath(guidePath, if (dry.elementFrame) framePaint else cutterPaint)
            if (dry.elementFrame) paintPivot(canvas, dry)
        }
    }

    /**
     * A ring with a cross through it — the same mark a selected copy's pivot gets on a note. Where
     * `ElementEditing.frameOf` puts it: an offset in points from the bounds' top-left, clamped into
     * them, or their centre. Reads [guidePath], which [paintGuides] has just mapped to page space.
     */
    private fun paintPivot(canvas: Canvas, frame: DryShape) {
        guidePath.computeBounds(guideBounds, true)
        val offset = frame.decodedFormat.framePivot
        val x = offset?.let { (guideBounds.left + it.x).coerceIn(guideBounds.left, guideBounds.right) }
            ?: guideBounds.centerX()
        val y = offset?.let { (guideBounds.top + it.y).coerceIn(guideBounds.top, guideBounds.bottom) }
            ?: guideBounds.centerY()
        canvas.drawCircle(x, y, PIVOT_RADIUS_PT, pivotPaint)
        canvas.drawLine(x - PIVOT_ARM_PT, y, x + PIVOT_ARM_PT, y, pivotPaint)
        canvas.drawLine(x, y - PIVOT_ARM_PT, x, y + PIVOT_ARM_PT, pivotPaint)
    }

    private val guidePath = Path()
    private val guideBounds = RectF()

    /** The Element's bounds, as the editor shows them. Amber, the pivot's colour: both are the Element's own geometry. */
    private val framePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = CUTTER_OUTLINE_PT
        color = FRAME_ARGB
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(FRAME_DASH_PT, CUTTER_DASH_PT), 0f)
    }

    private val pivotPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = CUTTER_OUTLINE_PT
        color = FRAME_ARGB
        isAntiAlias = true
    }

    private val backdropPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = false
    }

    /** A hairline at any zoom: a stroke width of zero is one device pixel. */
    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 0f
        isAntiAlias = false
    }

    /** An invisible knockout area, as the editor shows it. */
    private val cutterPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = CUTTER_OUTLINE_PT
        color = CUTTER_ARGB
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(CUTTER_DASH_PT, CUTTER_DASH_PT), 0f)
    }

    /**
     * Every object in [store], in render order.
     *
     * @param pageToCanvas the transform the canvas already carries. Passed on to the ink
     *   renderer as its antialiasing scale — see the class KDoc, point 3.
     * @param visibleBounds page-space culling rect, or null to draw everything. A thumbnail
     *   passes null: the whole page is "on screen" by definition.
     */
    fun paintObjects(canvas: Canvas, store: StrokeStore, pageToCanvas: Matrix, visibleBounds: Box?, paper: PageCanvas) {
        val saved = clipToPaper(canvas, paper)
        if (store.hasKnockouts) {
            paintWithKnockouts(canvas, store, pageToCanvas, visibleBounds)
        } else {
            store.forEachInRenderOrder(visibleBounds) { dry -> paintObject(canvas, dry, pageToCanvas) }
        }
        canvas.restoreToCount(saved)
    }

    // -----------------------------------------------------------------------
    // ⭐ Knockout — SPEC §4.9.6
    // -----------------------------------------------------------------------

    /**
     * Every object, with each knockout hiding whatever is beneath its filled silhouette.
     *
     * ## Why there is a layer, and only here
     *
     * `DST_OUT` clears whatever the canvas already holds — and on the plain path that includes the
     * **paper**, which [paintBackground] drew onto the same canvas a moment earlier. Cleared paper is
     * a hole through to the window behind the page. So the objects go into a layer of their own,
     * where a cut clears ink and leaves transparency that composites back over the paper (and over
     * an imported PDF page, which is paper too: a knockout hides ink, never the document). A page
     * with no knockout never reaches this function, and pays for no layer.
     *
     * ## ⚠️ One knockout never hides another
     *
     * The case this has to get right is an Element made of several pieces — a hand-drawn icon is a
     * dozen strokes, each flagged. If every piece cut everything beneath it, the face's outline
     * would erase the eyes drawn before it. So a cut is followed by repainting, clipped to the cut,
     * every knockout already drawn: inside the silhouette, everything below that is not a knockout
     * is gone and every knockout comes back in its own order. Two *separate* icons overlapping
     * therefore show through each other, which is the price of not having groups; it is the rarer
     * case of the two by a long way.
     *
     * ## Highlighters
     *
     * The highlight band is drawn after all ink by design (SPEC §3.4), so a highlighter under a
     * knockout would otherwise be painted back over it. It gets a layer of its own, interleaved with
     * the knockouts by `(z, id)` — see [StrokeStore.forEachHighlightAmongKnockouts].
     */
    private fun paintWithKnockouts(canvas: Canvas, store: StrokeStore, pageToCanvas: Matrix, visibleBounds: Box?) {
        val layer = canvas.saveLayer(null, null)
        knockedSoFar.clear()
        store.forEachInInkBand(visibleBounds) { dry ->
            val cut = if (dry.knockout) silhouetteOf(dry) else null
            if (cut == null) {
                paintObject(canvas, dry, pageToCanvas)
            } else {
                cutOut(canvas, dry, cut)
                restoreKnockoutsUnder(canvas, dry, cut, pageToCanvas)
                paintObject(canvas, dry, pageToCanvas)
                knockedSoFar += dry
            }
        }
        if (store.hasHighlighter) {
            val highlights = canvas.saveLayer(null, null)
            store.forEachHighlightAmongKnockouts(
                visibleBounds,
                onHighlighter = { dry -> paintObject(canvas, dry, pageToCanvas) },
                onKnockout = { dry -> silhouetteOf(dry)?.let { cutOut(canvas, dry, it) } },
            )
            canvas.restoreToCount(highlights)
        }
        knockedSoFar.clear()
        canvas.restoreToCount(layer)
    }

    /** Clears [silhouette] — in [dry]'s own space — out of everything painted so far in this layer. */
    private fun cutOut(canvas: Canvas, dry: DryObject, silhouette: Path) {
        val saved = canvas.save()
        if (dry.transform != Matrix3.Identity) {
            dry.transform.into(objectMatrix)
            canvas.concat(objectMatrix)
        }
        canvas.drawPath(silhouette, knockoutPaint)
        canvas.restoreToCount(saved)
    }

    /** See [paintWithKnockouts] — every knockout already drawn, back, but only where [dry] just cut. */
    private fun restoreKnockoutsUnder(canvas: Canvas, dry: DryObject, silhouette: Path, pageToCanvas: Matrix) {
        if (knockedSoFar.isEmpty()) return
        clipPath.set(silhouette)
        if (dry.transform != Matrix3.Identity) {
            dry.transform.into(objectMatrix)
            clipPath.transform(objectMatrix)
        }
        val saved = canvas.save()
        canvas.clipPath(clipPath)
        for (index in knockedSoFar.indices) paintObject(canvas, knockedSoFar[index], pageToCanvas)
        canvas.restoreToCount(saved)
    }

    /**
     * ⭐ [dry]'s filled silhouette in its own space — built once, then cached on the object until
     * something about how it looks changes (`DryObject.invalidateSilhouette`).
     *
     * A text box answers its **frame**, not its letters. The letters' silhouette is the rule applied
     * faithfully and is useless — the gaps between words would show the lines underneath — and a
     * label that clears a box behind it is the thing a person flagging a text box wants.
     *
     * Every other kind is drawn into a picture and handed to [SilhouetteMask], because "the outline
     * with the holes filled in" has no per-kind definition that holds for a hand-drawn loop, a
     * picture with an alpha channel and a rounded rectangle alike.
     *
     * @return null when there is nothing to cut yet — notably an image whose pixels have not loaded,
     *   which is then *not* cached, so the cut appears with the picture rather than never.
     */
    internal fun silhouetteOf(dry: DryObject): Path? {
        val source: Any = when (dry) {
            is DryImage -> assets?.bitmap(dry.asset)?.takeUnless { it.isRecycled } ?: return null
            else -> Unit
        }
        dry.silhouette?.let { cached -> if (dry.silhouetteSource === source) return cached }
        val built = when {
            dry is DryTextBox -> Path().apply { addRect(0f, 0f, dry.widthPt, dry.heightPt, Path.Direction.CW) }

            // A cutter draws nothing, so there is no picture to measure — its silhouette is its
            // outline, filled, which for a closed shape is exactly the area it names.
            dry is DryShape && dry.cutter -> Path(dry.path).apply { fillType = Path.FillType.WINDING }

            else -> rasterSilhouette(dry)
        } ?: return null
        dry.silhouette = built
        dry.silhouetteSource = source
        return built
    }

    /**
     * Draw [dry] into a picture, find its filled silhouette, and bring that back as a [Path] in the
     * object's own space.
     *
     * The picture is sized from the object's own box at up to [SILHOUETTE_PX_PER_PT], capped at
     * [SILHOUETTE_MAX_PX] on the long side — a few milliseconds, once per change, and never on the
     * ink path: it runs while the page is being recorded, which already walks every object.
     */
    private fun rasterSilhouette(dry: DryObject): Path? {
        val box = dry.bounds ?: return null
        val extent = max(box.xMax - box.xMin, box.yMax - box.yMin)
        if (!(extent > 0f)) return null
        val scale = min(SILHOUETTE_PX_PER_PT, SILHOUETTE_MAX_PX / extent)
        val closeRadius = max(1, ceil(SILHOUETTE_GAP_PT * scale).toInt())
        val pad = closeRadius + SILHOUETTE_MARGIN_PX
        val width = ceil((box.xMax - box.xMin) * scale).toInt() + 2 * pad
        val height = ceil((box.yMax - box.yMin) * scale).toInt() + 2 * pad

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        try {
            val picture = Canvas(bitmap)
            picture.translate(pad.toFloat(), pad.toFloat())
            picture.scale(scale, scale)
            picture.translate(-box.xMin, -box.yMin)
            val toPicture = Matrix().apply { setScale(scale, scale) }
            paintInObjectSpace(picture, dry, toPicture)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        } finally {
            bitmap.recycle()
        }
        for (index in pixels.indices) pixels[index] = pixels[index] ushr ALPHA_SHIFT

        val mask = SilhouetteMask.of(pixels, width, height, closeRadius)
        val region = Region()
        SilhouetteMask.forEachRun(mask, width, height) { y, from, to ->
            region.op(from, y, to, y + 1, Region.Op.UNION)
        }
        if (region.isEmpty) return null
        val path = region.boundaryPath
        // Picture pixels back to the object's own space: the inverse of the three steps above.
        path.transform(
            Matrix().apply {
                setTranslate(-pad.toFloat(), -pad.toFloat())
                postScale(1f / scale, 1f / scale)
                postTranslate(box.xMin, box.yMin)
            },
        )
        return path
    }

    /** Clears to transparent where a silhouette is drawn. Filled, antialiased. */
    private val knockoutPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    /** Knockouts drawn so far this pass — see [paintWithKnockouts]. Reused, never reallocated. */
    private val knockedSoFar = ArrayList<DryObject>()

    /** A silhouette moved into page space for a clip. Reused. */
    private val clipPath = Path()

    /**
     * ⭐ **The paper is where the ink stops, on screen as well as in the PDF.**
     *
     * A PDF annotation outside the CropBox is not drawn by any reader, so ink beyond the paper's
     * edge was ink this app displayed and every other program dropped — invisible loss, and the
     * kind that only shows up when the note is opened somewhere else. The pen refuses to *start*
     * outside the paper (`InkSurfaceView.beginStroke`), which handles writing beside the page;
     * this handles the rest of it, the stroke that begins on the page and runs off the edge.
     *
     * Clipped rather than culled: the stroke is untouched in the log and un-erased, exactly like
     * every other rendering decision here (SPEC §3.5) — make the page bigger, or turn the canvas
     * infinite, and all of it is back with nothing to reconstruct.
     *
     * On an infinite page this is a no-op by construction: the canvas has already grown to hold
     * whatever was written on it, so the clip is never reached.
     */
    private fun clipToPaper(canvas: Canvas, paper: PageCanvas): Int {
        val saved = canvas.save()
        canvas.clipRect(paper.left, paper.top, paper.right, paper.bottom)
        return saved
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
     * One object, for the dry layer's append tail — the strokes drawn *after* a cached recording
     * rather than baked into it (see [StrokeStore.appendsSince]).
     *
     * Deliberately the same function [paintObjects] uses per object, not a second painter: the
     * whole reason this class exists is that a page drawn two ways is a page drawn two different
     * ways eventually. A stroke must look identical whether it landed before the recording or
     * after it, because it crosses that line the moment anything re-records.
     */
    fun paintOne(canvas: Canvas, dry: DryObject, pageToCanvas: Matrix, paper: PageCanvas) {
        // ⚠️ The same clip as [paintObjects], for the same reason and not merely for tidiness: the
        // tail is where a stroke lives between landing and the next re-record, so a clip applied
        // only in the recording would let a stroke run past the paper's edge for a second or two
        // and then get cut — which reads as the app deleting the end of a line.
        val saved = clipToPaper(canvas, paper)
        paintObject(canvas, dry, pageToCanvas)
        canvas.restoreToCount(saved)
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
            is DryTextBox -> paintText(canvas, dry)
            is DryImage -> paintImage(canvas, dry)
        }
    }

    /**
     * ⭐ M8c — a text box, in its own space.
     *
     * Everything expensive already happened: [DryTextBox] rebuilds its `StaticLayout` when its
     * words, its format or its style change, and this method scales, draws and restores. That split
     * is CLAUDE.md rule 7 — a text box on a page being written on is redrawn on every dry-layer
     * invalidation, and measuring glyphs there would put line breaking on the pen-to-pixel path.
     *
     * ⚠️ **The layout is [DryTextBox.LAYOUT_SCALE]× oversized and the canvas is scaled down**, for
     * the reason that class records: `StaticLayout`'s wrap width and line height are integers, and
     * at page-point magnitudes a 14 pt line rounds to a 16 pt one — a 4 % error compounded once per
     * line.
     */
    private fun paintText(canvas: Canvas, dry: DryTextBox) {
        val saved = canvas.save()
        canvas.scale(1f / DryTextBox.LAYOUT_SCALE, 1f / DryTextBox.LAYOUT_SCALE)
        dry.layout.draw(canvas)
        canvas.restoreToCount(saved)
    }

    /**
     * ⭐ M8c — an image, cropped, in its own space.
     *
     * The crop is a source-rectangle draw and, for a freehand crop, a clip on top of it — both
     * decided by [DryImage], which holds the reframing rule so that the pixels and the shaped edge
     * cannot disagree. Everything else about placing an image is the object matrix, which
     * [paintObject] has already concatenated: a rotation and a flip are `SetTransform` (SPEC
     * §4.9.2), so this draws into `[0, w] × [0, h]` and never looks at either.
     *
     * ⚠️ **A missing bitmap draws a placeholder rather than nothing.** [PageAssets] returns null for
     * an asset that has not loaded yet and for one that is not there, and both are ordinary — an
     * image merged from a peer arrives one poll before its bytes do. Drawing nothing would make a
     * picture indistinguishable from blank paper, which is the *silent* half of SPEC §4.9.4's
     * "blank rectangle" failure and the half a user cannot report.
     */
    private fun paintImage(canvas: Canvas, dry: DryImage) {
        val bitmap = assets?.bitmap(dry.asset)
        if (bitmap == null || bitmap.isRecycled) {
            // Asked *after* [PageAssets.bitmap], because asking is what starts a decode.
            if (assets?.isPending(dry.asset) == true) paintPendingImage(canvas, dry) else paintMissingImage(canvas, dry)
            return
        }
        val saved = canvas.save()
        dry.clipPath?.let { canvas.clipPath(it) }
        dry.sourceRect(bitmap.width, bitmap.height, sourceRect)
        destRect.set(0f, 0f, dry.widthPt, dry.heightPt)
        // Opacity only. `ObjectStyle.colorArgb` means nothing for a photograph — it has its own
        // colours — so a recolour applied to a mixed selection leaves pictures alone, exactly as
        // `setFill` leaves pen strokes alone (`InkSelection.setFill`).
        objectImagePaint.alpha = (dry.effectiveStyle().opacity.coerceIn(0f, 1f) * OPAQUE).toInt()
        canvas.drawBitmap(bitmap, sourceRect, destRect, objectImagePaint)
        canvas.restoreToCount(saved)
    }

    /**
     * The "this picture is not here" box: a flat wash, a dashed edge and a cross.
     *
     * Deliberately unmistakable rather than tasteful. A neutral grey rectangle alone reads as a
     * design choice; a crossed one reads as a fault, which is what it is, and is the difference
     * between a user reporting a missing image and never noticing one.
     */
    private fun paintMissingImage(canvas: Canvas, dry: DryImage) {
        val width = dry.widthPt
        val height = dry.heightPt
        destRect.set(0f, 0f, width, height)
        canvas.drawRect(destRect, missingFill)
        canvas.drawRect(destRect, missingEdge)
        canvas.drawLine(0f, 0f, width, height, missingEdge)
        canvas.drawLine(width, 0f, 0f, height, missingEdge)
    }

    /**
     * A picture whose pixels are on their way: the same flat wash, **without** the cross or the
     * dashed edge. See [PageAssets.isPending] — a page opening with eleven photographs used to show
     * eleven fault markers for as long as their first decode took.
     */
    private fun paintPendingImage(canvas: Canvas, dry: DryImage) {
        destRect.set(0f, 0f, dry.widthPt, dry.heightPt)
        canvas.drawRect(destRect, missingFill)
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
        // ⭐ An invisible knockout area draws nothing — it only cuts (SPEC §4.9.6) — and the Element
        // editor's bounds are not part of the picture at all. Both are drawn by [paintGuides], on top
        // of everything, while an Element is being edited.
        if (dry.isGuide) return
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

    /**
     * ⭐ M8c — where an [DryImage]'s pixels come from, or null when nobody has supplied a lookup.
     *
     * Null is not a misconfiguration to be defended against: [InkPageThumbnailRenderer] renders on
     * a background dispatcher and a caller that has no asset cache is entitled to say so. It draws
     * the same placeholder a missing asset does, which is the honest picture either way.
     */
    var assets: PageAssets? = null

    /** The image's own pixels. Separate from [imagePaint] so the two alphas cannot interfere. */
    private val objectImagePaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }

    /** A flat wash under a missing picture — light enough to read as paper, dark enough to see. */
    private val missingFill = Paint().apply {
        style = Paint.Style.FILL
        color = MISSING_FILL_COLOR
        isAntiAlias = true
    }

    /** The dashed edge and the cross. See [paintMissingImage] on why it is a cross. */
    private val missingEdge = Paint().apply {
        style = Paint.Style.STROKE
        color = MISSING_EDGE_COLOR
        strokeWidth = 1f
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(MISSING_DASH_PT, MISSING_DASH_PT), 0f)
    }

    /** Reused every frame — `onDraw` must not allocate (CLAUDE.md rule 7). */
    private val objectMatrix = Matrix()
    private val objectToCanvas = Matrix()
    private val matrixValues = FloatArray(MATRIX_VALUES)
    private val sourceRect = Rect()
    private val destRect = RectF()

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

        /** Full alpha on a `Paint`, and the multiplier `ObjectStyle.opacity` scales. */
        const val OPAQUE = 255

        const val MISSING_FILL_COLOR = 0x14000000
        const val MISSING_EDGE_COLOR = 0x59000000
        const val MISSING_DASH_PT = 4f

        /** Picture resolution for a silhouette — finer than any tablet shows a point at fit width. */
        const val SILHOUETTE_PX_PER_PT = 4f

        /** And never more than this on the long side, so a full-page picture costs a bounded mask. */
        const val SILHOUETTE_MAX_PX = 768f

        /**
         * ⭐ The widest gap, in page points, that still closes a shape — a hand-drawn circle whose
         * pen lifted short of where it began. Three points is about a millimetre either side: wide
         * enough for a careless loop, narrow enough that an arc is still an arc.
         */
        const val SILHOUETTE_GAP_PT = 3f

        /** Blank border round the picture, so the outside can be flooded all the way round. */
        const val SILHOUETTE_MARGIN_PX = 2

        /**
         * The Element editor's grid: a cell, and how often a heavier line is drawn.
         *
         * The grid the editor draws **is** the grid it snaps to: blank paper's lattice
         * (`SnapGrid.DEFAULT_SPACING_PT`, the square-grid template's spacing). It was 12 pt against an
         * 18 pt snap, so a snapped area landed between the lines the user was aiming at.
         */
        const val BACKDROP_CELL_PT = dev.inkstone.core.model.SnapGrid.DEFAULT_SPACING_PT
        const val BACKDROP_MAJOR_EVERY = 5

        /** sky-400, the chrome's accent — readable on both backdrops. */
        const val CUTTER_ARGB = 0xFF38BDF8.toInt()
        const val CUTTER_OUTLINE_PT = 1.5f
        const val CUTTER_DASH_PT = 5f
        const val FRAME_ARGB = 0xFFF59E0B.toInt()
        const val FRAME_DASH_PT = 9f
        const val PIVOT_RADIUS_PT = 6f
        const val PIVOT_ARM_PT = 10f

        const val ALPHA_SHIFT = 24
    }
}
