package dev.inkstone.android.ink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.StrokeInput
import dev.inkstone.android.ink.InkColor.withOpacity
import dev.inkstone.android.ink.PagePainter.Companion.ARROW_HEAD_WIDTHS
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.StrokeOutline
import dev.inkstone.core.model.StrokeOutliner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * SPEC §6.4 — the `:android:ink` half of the ink→PDF handoff.
 *
 * Turns a folded page into filled outlines and centrelines in page space
 * ([StrokeOutline]), which `:android:pdf` writes as `/AP` appearance streams and
 * `/InkList` polylines. Neither module depends on the other; the shape of what
 * passes between them is a platform-free type in `:core:model` (SPEC §2.1).
 *
 * ## ⭐ Why the erasure is subtracted here
 *
 * SPEC §3.5 erases by *area*: the stroke is never touched, and the swept region is
 * removed at draw time. On screen [PagePainter] does that with a `DST_OUT` layer.
 * A PDF has no cheap equivalent, and the two constructions that look like one both
 * fail on the input that matters:
 *
 * - **Even-odd fill** of *stroke ∪ capsules* removes a single capsule correctly and
 *   **re-fills** wherever two capsules overlap — which a scribbled erase produces
 *   on nearly every stroke it touches.
 * - **Reversed-winding subpaths** under nonzero fill fail the same way, for the
 *   same reason: two overlapping subtractions wind back to non-zero.
 *
 * A soft mask would be exact and is a lot of machinery. But there is already an
 * exact boolean path engine on this side of the boundary — Skia — so the
 * subtraction is done with [Path.op] and what crosses the boundary is what is
 * *left*. `:android:pdf` fills a polygon and never learns that an eraser existed.
 *
 * ## Threading
 *
 * `Dispatchers.Default`, hopped to here rather than at each call site (CLAUDE.md
 * rule 7). Tessellating a page of strokes and running boolean ops on the erased
 * ones is tens of milliseconds; materialization is never on the ink path anyway,
 * but a renderer that quietly runs on whatever thread it was called from is how
 * that stops being true.
 */
public class InkStrokeOutliner(
    private val brushes: InkBrushFactory,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : StrokeOutliner {

    override suspend fun outline(page: FoldedPage): List<StrokeOutline> = outline(page, emptyMap())

    /**
     * @param assets the note's image bytes by entry name, so a knockout **picture** cuts by its own
     *   pixels (SPEC §4.9.6). Without them it cuts nothing — a picture whose pixels are not here has
     *   no silhouette to cut with, which is the same answer the screen gives before an image loads.
     */
    override suspend fun outline(page: FoldedPage, assets: Map<String, ByteArray>): List<StrokeOutline> =
        withContext(dispatcher) {
            val store = StrokeStore()
            // The same decoder the editor and the thumbnail renderer use. A second one
            // would be a second place for render order and absolute erasure to drift —
            // and the published PDF disagreeing with the screen is the one bug in this
            // milestone the user would notice and could not explain.
            // ⚠️ **The `AddObject`s alone are not the page** (M8b). A shape's fill, corner radius and
            // connector attachment live in the `SetTextContent` register, which is a *side table* on
            // the fold and not on the payload (SPEC §4.8.2) — so a store built from `page.objects`
            // holds every shape as an unfilled, square-cornered, unattached one. The published file
            // would then disagree with the screen about exactly the properties this milestone added,
            // and would do it silently, because every shape would still be there and still be a shape.
            //
            // Same shape of bug as `copiedObjectOps`: if the fold answers for it, the consumer has to
            // ask. Ordered after the adds so each record lands on an object that exists.
            val ops = ArrayList<PageOp>(page.objects.size * 2)
            page.objects.mapTo(ops) { PageOp.AddObject(opId = "", clock = OUTLINE_CLOCK, obj = it) }
            for (obj in page.objects) {
                val text = page.textOf(obj.id) ?: continue
                ops += PageOp.SetTextContent(opId = "", clock = OUTLINE_CLOCK, target = obj.id, text = text)
            }
            // ⭐ SPEC §4.9.6 — and the format register, which is where the knockout flag lives. It is a
            // side table exactly as the record above is, so the same rule applies: ask, or lose it.
            for (obj in page.objects) {
                val format = page.formatOf(obj.id) ?: continue
                ops += PageOp.SetFormat(opId = "", clock = OUTLINE_CLOCK, target = obj.id, record = format)
            }
            store.applyOps(ops = ops, brushes = brushes, record = false)
            val pictures = DecodedAssets(assets)
            val knockouts = try {
                KnockoutCuts.of(store, PagePainter().apply { this.assets = pictures })
            } finally {
                pictures.recycle()
            }

            val result = ArrayList<StrokeOutline>(store.size)
            // Ink first, then highlighters (SPEC §3.4 — reversed after M4). Annotation order is
            // paint order in a PDF too, so this is not merely cosmetic: it is the same z-order
            // the editor draws, and the published file disagreeing with the screen is the one
            // bug in this milestone the user would notice and could not explain. The highlight
            // tints rather than hides what it covers because of its alpha, exactly as on screen.
            // ⭐ **M8b: shapes too, in the one render order.** Not `forEachStrokeInRenderOrder` any
            // more — that walked ink and skipped everything else, which was right while nothing else
            // could be painted and became "the published PDF is missing the diagram" the moment
            // `DryShape` existed. Walking one list is also what keeps a shape's `(z, id)` place in the
            // `/Annots` order, and annotation order is paint order in a PDF exactly as it is on screen.
            //
            //
            // ⚠️ **M8c: a text box and an image contribute nothing here, and that is now a real gap.**
            // It was not one before — nothing painted them on screen either, so a PDF without them
            // agreed with what the user was looking at (SPEC §4.9.3). They are painted now, so a
            // published page is missing something the editor shows. Neither can travel through *this*
            // boundary: a [StrokeOutline] is filled polygons plus a centreline, and a text box is PDF
            // **text** — glyph codes in an embedded CID font with a `/ToUnicode` CMap, which is the
            // whole point of SPEC §4.9.5 and is what makes the words searchable — while an image is an
            // `/XObject`. Flattening either into polygons would publish a picture of words that
            // `pdfgrep` cannot read, which is SPEC §5.5's feature failing while every visual check
            // passes. The boundary they need is `:android:pdf`'s and does not exist yet;
            // `docs/M8-TEXT.md` §6 items 4 and 6 own it.
            //
            // ⭐ SPEC §4.9.6 — and each stroke and shape loses whatever a knockout above it hides, as an
            // area subtracted exactly like an erasure. A text box and an image under a knockout are
            // **not** cut here: they are page content rather than annotations, and that boundary has
            // no subtraction (see `docs/M8-ELEMENTS-KNOCKOUT.md` §5).
            store.forEachInRenderOrder(visibleBounds = null) { dry ->
                when (dry) {
                    is DryStroke -> result += outlineOf(dry, knockouts.cutFor(dry))

                    // An invisible knockout area publishes no ink of its own; it only cuts, above.
                    is DryShape -> if (!dry.isGuide) result += outlinesOf(dry, knockouts.cutFor(dry))

                    is DryTextBox -> Unit

                    is DryImage -> Unit
                }
            }
            result
        }

    /**
     * ⭐ M8a — the brush is read *after* any `SetStyle` has been applied.
     *
     * `StrokeStore.applyOps` rebuilds a restyled stroke's brush, so `brush.colorIntArgb` and
     * `brush.size` here are the object's current colour and width and not its captured ones.
     * That is the whole of "a recoloured stroke publishes recoloured", and it works because
     * this outliner drives the same store the editor does — a second decoder would have had to
     * reimplement the override, which is the drift this class already refuses elsewhere.
     */
    private fun outlineOf(dry: DryStroke, cut: Path?): StrokeOutline {
        val brush = dry.stroke.brush
        return StrokeOutline(
            objectId = dry.id,
            contours = contoursOf(dry, cut),
            centreline = centrelineOf(dry),
            colorArgb = brush.colorIntArgb,
            widthPt = brush.size,
        )
    }

    /**
     * ⭐ M8b — a shape as **filled outlines**, which is the only thing this boundary carries.
     *
     * ## Why a shape becomes a `StrokeOutline` rather than gaining a type of its own
     *
     * Everything downstream already works on filled polygons: `PdfContent.strokeAppearance` fills
     * them into an `/AP`, `flattenedOverlay` fills them into the page's content stream, and both
     * pick an `/ExtGState` from the colour's **alpha** — so a translucent fill needs no new
     * machinery at all, only an ARGB with alpha in it. A `ShapeOutline` type would have meant a
     * second writer, a second `/Resources` decision, and a second chance to make M4's mistake
     * (*"valid, parseable, and blank"*).
     *
     * ## Up to two outlines, in paint order
     *
     * The fill first, then the outline over it — the order [PagePainter] draws them, so the PDF and
     * the screen agree by construction rather than by two people implementing the same rule.
     *
     * ⚠️ **The stroke is converted to a fill with [Paint.getFillPath], not written as a PDF `S`
     * operator.** A stroked path would be thinner machinery, and it would be a *second* way for
     * geometry to reach the file: erasure is subtracted as an area (SPEC §3.5), and subtracting an
     * area from something that has not been expanded to its painted width yet takes a bite out of
     * the centreline instead of out of the ink. Expanding first means a shape erases exactly as a
     * stroke does, through the same [Path.op].
     */
    private fun outlinesOf(dry: DryShape, cut: Path?): List<StrokeOutline> {
        val style = dry.effectiveStyle()
        val outlines = ArrayList<StrokeOutline>(2)
        val record = dry.record

        val fill = record.fillArgb
        if (dry.fillable && fill != null) {
            val interior = pathOf(dry)
            outlines += StrokeOutline(
                objectId = dry.id,
                contours = shapeContours(dry, interior, cut),
                // Alpha carried in the colour, because that is what `stateNameFor` reads to pick
                // the `/ca` graphics state. `fillOpacity` multiplies the colour's own alpha for
                // the reason `Int.withOpacity` gives on screen: 50% of a translucent fill is half
                // of *its* translucency, not half of opaque.
                colorArgb = fill.withOpacity(record.fillOpacity),
                widthPt = 0f,
                centreline = emptyList(),
            )
        }

        if (style.widthPt > 0f) {
            val stroked = Path()
            outlinePaint.strokeWidth = style.widthPt
            outlinePaint.getFillPath(pathOf(dry), stroked)
            // The arrowhead is part of the painted outline, not a third object: it shares the
            // stroke's colour and width and is drawn from the same last-segment direction
            // [PagePainter] uses, so a published arrow points where the drawn one does.
            dry.arrowHead(style.widthPt * ARROW_HEAD_WIDTHS)?.let { head ->
                val expanded = Path()
                outlinePaint.getFillPath(head, expanded)
                stroked.op(expanded, Path.Op.UNION)
            }
            outlines += StrokeOutline(
                objectId = dry.id,
                contours = shapeContours(dry, stroked, cut),
                colorArgb = style.colorArgb.withOpacity(style.opacity),
                widthPt = style.widthPt,
                // ⚠️ Empty, deliberately. `/InkList` is where Recovered mode reads a stroke's path
                // back (SPEC §6.4), and a shape recovered as a *stroke* would come back as ink that
                // can never again be filled, rounded or attached to. A shape's truth is the bundle;
                // it is recovered from there or not at all.
                centreline = emptyList(),
            )
        }
        return outlines
    }

    /** A shape's own-space outline as a [Path] — the same one [PagePainter] draws. */
    private fun pathOf(dry: DryShape) = Path(dry.path)

    /** Erasure subtracted in object space, then the transform applied — exactly as ink does. */
    private fun shapeContours(dry: DryShape, path: Path, cut: Path?): List<List<PagePoint>> {
        val erased = dry.erased
        if (erased != null) path.op(eraserRegion(erased), Path.Op.DIFFERENCE)
        if (cut != null) path.op(cut, Path.Op.DIFFERENCE)
        val local = flatten(path, FLATTEN_STEP_PT / dry.transform.linearScale())
        val transform = dry.transform
        if (transform == Matrix3.Identity) return local
        return local.map { contour -> contour.map { transform.map(it) } }
    }

    /**
     * The visible outline, in page space.
     *
     * The untouched case reads the mesh's own outline vertices — exact, and already
     * polylines, because a tessellated stroke has no curves left. Only an erased
     * stroke pays for the round trip through [Path] and [PathMeasure].
     *
     * ⚠️ **The object's transform is applied last, after the erasure has been subtracted.**
     * Both the mesh and the eraser's capsules are in the object's own space (SPEC §3.5 as
     * revised in M8a), so subtracting there is exact and needs no mapping; mapping first would
     * mean transforming the capsules too, and a rotation turns a round eraser tip into an
     * ellipse that `Path` stroking cannot express. What crosses into `:android:pdf` is page
     * space, because that is what SPEC §4.4 says a persisted coordinate is.
     */
    private fun contoursOf(dry: DryStroke, cut: Path?): List<List<PagePoint>> {
        val erased = dry.erased
        val local = if (erased == null && cut == null) meshContours(dry) else erasedContours(dry, erased, cut)
        val transform = dry.transform
        if (transform == Matrix3.Identity) return local
        return local.map { contour -> contour.map { transform.map(it) } }
    }

    private fun meshContours(dry: DryStroke): List<List<PagePoint>> {
        val mesh = dry.stroke.shape
        val scratch = MutableVec()
        val contours = ArrayList<List<PagePoint>>()
        for (group in 0 until mesh.getRenderGroupCount()) {
            for (outline in 0 until mesh.getOutlineCount(group)) {
                val count = mesh.getOutlineVertexCount(group, outline)
                if (count < MIN_POLYGON_POINTS) continue
                val points = ArrayList<PagePoint>(count)
                for (vertex in 0 until count) {
                    mesh.populateOutlinePosition(group, outline, vertex, scratch)
                    points += PagePoint(scratch.x, scratch.y)
                }
                contours += points
            }
        }
        return contours
    }

    /**
     * Stroke minus eraser, exactly, via Skia.
     *
     * The eraser's swept capsules are the same ones [PagePainter] strokes with
     * `DST_OUT`, so the two agree by construction rather than by two people having
     * implemented the same rule twice.
     */
    private fun erasedContours(dry: DryStroke, erased: ErasedRegion?, cut: Path?): List<List<PagePoint>> {
        // Built from the same exact vertices [meshContours] reads, rather than through
        // ink's `outlinesToPath` extension: one way of getting a stroke's outline, so
        // an erased stroke and an untouched one cannot end up subtly different shapes.
        val stroke = Path()
        for (contour in meshContours(dry)) {
            for ((index, point) in contour.withIndex()) {
                if (index == 0) stroke.moveTo(point.x, point.y) else stroke.lineTo(point.x, point.y)
            }
            stroke.close()
        }

        if (erased != null) stroke.op(eraserRegion(erased), Path.Op.DIFFERENCE)
        // A knockout above it, already in this stroke's own space — see [KnockoutCuts.cutFor].
        if (cut != null) stroke.op(cut, Path.Op.DIFFERENCE)
        // Sampled at a step that will be [FLATTEN_STEP_PT] once the object's transform has
        // scaled it. A stroke enlarged 4× and flattened at the plain step publishes visibly
        // faceted curves — correct geometry, wrong resolution, and only on the objects the user
        // has bothered to make big.
        return flatten(stroke, FLATTEN_STEP_PT / dry.transform.linearScale())
    }

    /**
     * The eraser's swept capsules as **one** region, in the object's own space.
     *
     * ⚠️ `UNION`, not `addPath`: overlapping capsules must become a single region *before* the
     * subtraction. Adding them and subtracting the result under nonzero winding re-fills wherever
     * two overlap, which is what a scribbled erase produces on nearly every stroke it touches —
     * the failure the class KDoc describes, and the reason this is done with Skia at all.
     *
     * Shared by ink and shapes since M8b, so an erased rectangle and an erased stroke cannot end
     * up subtly different subtractions.
     */
    private fun eraserRegion(erased: ErasedRegion): Path {
        val eraser = Path()
        val expanded = Path()
        for (run in erased.runs) {
            eraserPaint.strokeWidth = run.radius * 2f
            expanded.reset()
            eraserPaint.getFillPath(run.path, expanded)
            eraser.op(expanded, Path.Op.UNION)
        }
        return eraser
    }

    /**
     * A [Path]'s contours as polylines.
     *
     * Sampled by arc length rather than read vertex by vertex, because the vertex
     * list of a path is not reachable before API 34 (`PathIterator`) and this app's
     * floor is 30. `Path.approximate` *is* available and is deliberately not used:
     * it returns one flat array with no contour boundaries in it, so a stroke with
     * a hole in it — which is exactly what erasing produces — comes back with the
     * hole's edge joined to the outside by a stray segment.
     */
    private fun flatten(path: Path, stepPt: Float): List<List<PagePoint>> {
        val measure = PathMeasure(path, false)
        val contours = ArrayList<List<PagePoint>>()
        val position = FloatArray(2)
        do {
            val length = measure.length
            if (length <= 0f) continue
            val steps = (length / stepPt).toInt().coerceIn(MIN_POLYGON_POINTS, MAX_CONTOUR_POINTS)
            val points = ArrayList<PagePoint>(steps + 1)
            for (step in 0..steps) {
                if (!measure.getPosTan(length * step / steps, position, null)) continue
                points += PagePoint(position[0], position[1])
            }
            if (points.size >= MIN_POLYGON_POINTS) contours += points
        } while (measure.nextContour())
        return contours
    }

    /**
     * The stroke's input path, thinned, for `/InkList`.
     *
     * Thinned because `/InkList` is the *interop* representation (SPEC §6.4) and
     * nothing renders from it: a 200 Hz capture is hundreds of points per stroke,
     * and keeping every one would put megabytes of numbers into the PDF to describe
     * a line another editor will draw at uniform width anyway.
     *
     * It is also what Recovered mode reads back ([InkStrokeRebuilder]), so the
     * threshold is a fidelity floor for recovery, not just a size knob — a
     * quarter-point is well under the width of any pen.
     */
    private fun centrelineOf(dry: DryStroke): List<PagePoint> {
        val inputs = dry.stroke.inputs
        if (inputs.size == 0) return emptyList()
        val scratch = StrokeInput()
        val transform = dry.transform
        val points = ArrayList<PagePoint>(inputs.size)
        var lastX = Float.NaN
        var lastY = Float.NaN
        for (index in 0 until inputs.size) {
            inputs.populate(index, scratch)
            val isLast = index == inputs.size - 1
            if (!isLast && !lastX.isNaN() && hypot(scratch.x - lastX, scratch.y - lastY) < THIN_THRESHOLD_PT) continue
            // ⚠️ Mapped, like [contoursOf]. Missing it here and not there is worse than missing
            // it in both: the appearance stream would move and the `/InkList` interop polyline
            // would stay behind, so another editor's idea of where the stroke is would differ
            // from the picture in the same file — and Recovered mode reads `/InkList`
            // ([InkStrokeRebuilder]), so a recovered note would put the ink back where it was
            // before the user moved it.
            points += transform.map(PagePoint(scratch.x, scratch.y))
            lastX = scratch.x
            lastY = scratch.y
        }
        return points
    }

    /** Reused across strokes; this class is confined to [dispatcher] for one call. */
    private val eraserPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * ⭐ M8b — expands a shape's outline to its painted width, so the PDF *fills* what the screen
     * strokes.
     *
     * Round cap and join because that is what [PagePainter] draws with. A mitre here would put
     * spikes on a published rectangle's corners that are not on the screen's — the published file
     * disagreeing with the glass, which is the one class of bug in this pipeline the user would
     * notice and could not explain.
     */
    private val outlinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private companion object {
        /** Synthetic, never journaled — the same trick [InkPageThumbnailRenderer] uses. */
        val OUTLINE_CLOCK = LamportClock(0, "outline")

        const val MIN_POLYGON_POINTS = 3
        const val FLATTEN_STEP_PT = 0.75f
        const val MAX_CONTOUR_POINTS = 2048
        const val THIN_THRESHOLD_PT = 0.25f
    }
}

/**
 * ⭐ SPEC §4.9.6 — what each object on a published page loses to the knockouts above it.
 *
 * The screen cuts with `DST_OUT` in a layer ([PagePainter.paintObjects]); a PDF has no cheap
 * equivalent, for exactly the reason erasing has none (see [InkStrokeOutliner]'s KDoc), so the cut
 * is subtracted from the geometry here, through the same `Path.op` an erasure goes through, and
 * `:android:pdf` never learns a knockout existed.
 *
 * The rule is the screen's, stated in geometry: an object loses the union of the silhouettes of
 * every knockout **after it in render order** — `(z, id)`, the comparator both bands sort by — and a
 * knockout loses nothing to another knockout. One silhouette per knockout, built once by the same
 * [PagePainter.silhouetteOf] the screen uses, so the file and the glass cannot disagree about a
 * shape's inside.
 */
private class KnockoutCuts(private val ordered: List<DryObject>, private val suffixes: List<Path>) {

    private val suffixBounds: List<RectF> = suffixes.map { RectF().also { box -> it.computeBounds(box, true) } }

    /**
     * The cut for [dry], in **its own** space so it can be subtracted beside its erasure, or null
     * when nothing above it hides any of it.
     */
    fun cutFor(dry: DryObject): Path? {
        if (dry.knockout) return null
        val first = ordered.indexOfFirst { RENDER_ORDER.compare(it, dry) > 0 }
        val page = dry.pageBounds
        if (first < 0 || page == null || !page.meets(suffixBounds[first])) return null
        val local = Path(suffixes[first])
        if (dry.transform == Matrix3.Identity) return local
        val inverse = dry.transform.inverse() ?: return null
        local.transform(inverse.toAndroid())
        return local
    }

    /** Whether two boxes overlap at all — a stroke nowhere near a knockout pays no `Path.op`. */
    private fun PageBounds.meets(box: RectF): Boolean {
        val acrossX = xMax >= box.left && xMin <= box.right
        val acrossY = yMax >= box.top && yMin <= box.bottom
        return acrossX && acrossY
    }

    companion object {
        fun of(store: StrokeStore, painter: PagePainter): KnockoutCuts {
            if (!store.hasKnockouts) return KnockoutCuts(emptyList(), emptyList())
            val knockouts = ArrayList<DryObject>()
            val silhouettes = ArrayList<Path>()
            store.forEachInInkBand(visibleBounds = null) { dry ->
                if (!dry.knockout) return@forEachInInkBand
                val own = painter.silhouetteOf(dry) ?: return@forEachInInkBand
                knockouts += dry
                silhouettes += Path(own).apply {
                    if (dry.transform != Matrix3.Identity) transform(dry.transform.toAndroid())
                }
            }
            // Suffix unions, built from the top down: `suffixes[i]` is everything knockout `i` and
            // those above it hide, so an object finds its whole cut with one search.
            val suffixes = MutableList(silhouettes.size) { Path() }
            for (index in silhouettes.indices.reversed()) {
                val union = Path(silhouettes[index])
                if (index + 1 < silhouettes.size) union.op(suffixes[index + 1], Path.Op.UNION)
                suffixes[index] = union
            }
            return KnockoutCuts(knockouts, suffixes)
        }

        private fun Matrix3.toAndroid(): Matrix = Matrix().apply {
            setValues(floatArrayOf(m00, m01, m02, m10, m11, m12, 0f, 0f, 1f))
        }
    }
}

/**
 * The note's picture bytes as [PageAssets], decoded only when asked — which here means only for a
 * knockout picture, since nothing else in the outliner draws one.
 */
private class DecodedAssets(private val bytes: Map<String, ByteArray>) : PageAssets {
    private val decoded = HashMap<String, Bitmap?>()

    override fun bitmap(asset: String): Bitmap? = decoded.getOrPut(asset) {
        bytes[asset]?.takeIf { it.isNotEmpty() }?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    /** Explicit, for [InkPageThumbnailRenderer]'s reason: a publish walks every page of a note. */
    fun recycle() {
        decoded.values.forEach { it?.recycle() }
        decoded.clear()
    }
}
