package dev.inkstone.core.model

/*
 * SPEC §6.4 — ink → PDF, and the annotation-count decision.
 *
 * Everything here is platform-free on purpose. The *geometry* of a stroke can
 * only be produced by `:android:ink` (androidx.ink tessellates natively), and the
 * *PDF* can only be produced by `:android:pdf`. What sits between them — the
 * shape of the handoff, and the rule for how many annotations a page may carry —
 * is a decision, not a platform capability, so it lives here where both can see
 * it and where it can be unit-tested without a device.
 *
 * Block comment, not KDoc — a file-level KDoc attached to no declaration is a
 * "dangling toplevel KDoc" that ktlint cannot auto-correct.
 */

/**
 * One stroke, ready to become a PDF annotation: its visible area as filled
 * outlines, plus a centreline for `/InkList`.
 *
 * ## Why both
 *
 * SPEC §6.4: PDF `/Ink` **cannot express variable stroke width**. `/InkList` is
 * polylines and `/BS` gives one border width for the whole annotation; there is
 * no per-point pressure anywhere in the format. So each stroke is written twice:
 *
 * 1. **[contours] → `/AP /N`** — the stroke's outline as a filled path. `/AP`
 *    takes rendering precedence over `/InkList`, so this is what every viewer
 *    actually draws, and it carries the full pressure variation.
 * 2. **[centreline] → `/InkList`** — a uniform-width approximation, so other
 *    editors can select, move and delete the annotation rather than seeing an
 *    opaque appearance stream.
 *
 * ## Why erasure is already applied
 *
 * SPEC §3.5 erases by *area*: the stroke is untouched and the swept region is
 * subtracted at draw time. A PDF has no equivalent of the screen renderer's
 * `DST_OUT` layer that is cheap and correct — even-odd and reversed-winding fills
 * both re-fill where two eraser capsules overlap, which is exactly what a
 * scribbled erase produces. So the subtraction is done where there is already an
 * exact boolean path engine (Skia, in `:android:ink`), and [contours] is what is
 * *left*. Nothing downstream has to know an eraser was involved.
 *
 * @property contours closed polygons in **page space** (SPEC §4.4), filled with
 *   the nonzero winding rule. Already flattened to line segments — the outline of
 *   a variable-width stroke has no meaningful control points to preserve.
 * @property centreline the stroke's input path in page space, thinned.
 * @property colorArgb capture-time colour, alpha included. The highlighter's
 *   alpha becomes a `/ca` in the appearance stream's graphics state.
 * @property widthPt the brush size, for `/BS /W` on the `/InkList` half only.
 *   The `/AP` is a filled path and has no width of its own.
 */
public data class StrokeOutline(
    val objectId: ObjectId,
    val contours: List<List<PagePoint>>,
    val centreline: List<PagePoint>,
    val colorArgb: Int,
    val widthPt: Float,
) {
    /** True when the eraser took the whole stroke, or the blob would not decode. */
    public val isEmpty: Boolean get() = contours.all { it.size < MIN_POLYGON_POINTS }

    private companion object {
        /** A polygon needs three points to enclose any area. */
        const val MIN_POLYGON_POINTS = 3
    }
}

/**
 * SPEC §2.2 — the capability `:core:*` cannot implement itself: turning a folded
 * page's strokes into fillable outlines.
 *
 * Implemented in M4 by `:android:ink`, because tessellating a stroke and
 * subtracting an erased area both need `androidx.ink` and Skia. `:android:pdf`
 * consumes the result, which is how the two platform modules cooperate without
 * either depending on the other (SPEC §2.1 keeps the graph one-way).
 *
 * **Called off the main thread, always** (CLAUDE.md rule 7). Materialization is
 * on close, before upload, or on idle-while-charging — never on the ink path.
 */
public fun interface StrokeOutliner {
    /** Outlines in render order: highlighters first, then ink (SPEC §3.4). */
    public suspend fun outline(page: FoldedPage): List<StrokeOutline>

    /**
     * ⭐ SPEC §4.9.6 — the same, with the note's image bytes by bundle entry name, so a knockout
     * **picture** can cut by its own pixels. The default ignores them, which is the right answer for
     * every implementation that has no knockout to draw.
     */
    public suspend fun outline(page: FoldedPage, assets: Map<String, ByteArray>): List<StrokeOutline> = outline(page)
}

/**
 * The other direction, for SPEC §5.6's Recovered mode: a polyline read back out of
 * an `/Ink` annotation, turned into a stroke this app can draw, erase and sync.
 *
 * ## Why this reads `/InkList` and not `/AP`
 *
 * The appearance stream is a *filled outline* — it has an edge but no centre, no
 * inputs and no brush, so re-importing it would give a polygon shaped like ink
 * rather than ink. `/InkList` is the path the stroke was written along, which is
 * exactly what a `StrokeInputBatch` wants. SPEC §6.4 justified writing both
 * representations by interop — *"so other editors can select/move/delete it"* —
 * and it turns out the second one is also the only thing that makes recovery
 * produce editable ink. The cost is uniform width, which is
 * [dev.inkstone.core.storage.RecoveryLoss.PRESSURE_AND_WIDTH] and is declared.
 *
 * Implemented by `:android:ink`, because only it can produce a geometry blob.
 */
public fun interface StrokeRebuilder {
    /**
     * @param points the centreline in **page space**.
     * @return the geometry blob, or null when the polyline is too short to be a
     *   stroke — a degenerate annotation is one stroke lost, never a failed open.
     */
    public fun rebuild(points: List<PagePoint>, colorArgb: Int, widthPt: Float): StrokeGeometry?
}

/**
 * SPEC §6.4 ⭐ — **the density decision. Decide, don't hedge.**
 *
 * > 5,000 annotations on a page will hang Preview and Chrome's PDFium.
 *
 * A dense page of handwriting is thousands of strokes, and a page whose
 * `/Annots` array is thousands of entries long is not slow — it is a viewer that
 * stops responding. Both of the app's stated interop targets fail on it, so the
 * rule is a hard fork rather than a tuning knob.
 *
 * The embedded bundle is the source of truth either way (SPEC §5.3), so nothing
 * is lost by flattening — except the ability to recover per-stroke identity from
 * the PDF alone, which is why [PageAnnotationMode.FLATTENED] is a state Recovered
 * mode has to be able to report honestly (SPEC §5.6 point 4).
 */
public object AnnotationDensity {

    /**
     * The threshold from SPEC §6.4. At or below this, one annotation per stroke;
     * above it, a single flattened page overlay.
     */
    public const val MAX_PER_STROKE_ANNOTATIONS: Int = 500

    public fun modeFor(strokeCount: Int): PageAnnotationMode =
        if (strokeCount <= MAX_PER_STROKE_ANNOTATIONS) PageAnnotationMode.PER_STROKE else PageAnnotationMode.FLATTENED
}

/** How one page's ink is represented in the published PDF (SPEC §6.4). */
public enum class PageAnnotationMode {

    /** One `/Ink` annotation per stroke, each with `/NM` = [ObjectId]. Editable and identifiable elsewhere. */
    PER_STROKE,

    /**
     * The whole page's ink written as a page **content** overlay — no annotation at all,
     * which is what SPEC §6.4 asks for and what `PdfContent.flattenedOverlay` and
     * `PdfBoxWriter`'s FLATTENED branch implement. (This KDoc said "a single annotation"
     * for four milestones; the audit that caught it is why it now matches the code.)
     *
     * Recovered mode can only get a flat image back from this, and **must say so**
     * (SPEC §6.4, SPEC §5.6 point 4).
     */
    FLATTENED,
}

/**
 * SPEC §5.6 — `/NM` on every ink annotation, carrying its [ObjectId].
 *
 * `/NM` is the PDF's own "annotation name": a text string that a conforming
 * writer keeps unique within a page. It is the only per-annotation identity slot
 * the format offers, and without it a recovered import has to invent new ids —
 * which is the failure SPEC §5.6 exists to prevent, because the other device's
 * grow-only union then produces every stroke twice.
 *
 * The value is the ObjectId verbatim, with a short prefix so that an annotation
 * some other editor named cannot be mistaken for one of ours.
 */
public object AnnotationName {

    private const val PREFIX = "inkstone:"

    public fun of(id: ObjectId): String = PREFIX + id.value

    /** @return the id this `/NM` carries, or null if the name is not one of ours. */
    public fun parse(nm: String?): ObjectId? =
        nm?.takeIf { it.startsWith(PREFIX) && it.length > PREFIX.length }?.let { ObjectId(it.removePrefix(PREFIX)) }
}
