package dev.inkstone.core.model

import kotlin.math.ceil

/**
 * ⭐ **Where the paper is, and how big it is** — the rectangle in page space that a user may
 * write on and that a publisher must fit into one PDF page.
 *
 * Until this existed the answer was implicit: a page was a [PageSize] and the paper was
 * `[0, 0, width, height]`, everywhere, by convention. Two things went wrong with that.
 *
 * 1. **Nothing enforced it.** Ink is captured in view space and converted at the camera in
 *    effect (SPEC §4.5), and panning is free — so writing beside the paper produced a stroke at
 *    a negative page-space x, journaled, synced and folded exactly like any other. It rendered
 *    on screen, because the dry layer clips to the viewport rather than to the page, and it was
 *    invisible in **every** PDF viewer, because an annotation outside the CropBox is not drawn.
 *    Ink that the app showed you and no reader could: the silent-loss class CLAUDE.md rule 1
 *    exists for.
 * 2. **An infinite canvas has no such rectangle to assume.** It grows in whichever direction the
 *    user writes, including up and left, and a page whose paper starts at `(0, 0)` cannot say so.
 *
 * So the paper is a value now. [origin] is its top-left **in page space**, which is `(0, 0)` for
 * every page this app has ever written and stays there for every fixed page; [size] is its
 * extent. Page space itself is unchanged and unmoved — that is the whole point. Growing the
 * canvas up and to the left moves [origin] negative and **no object's coordinates change**, which
 * is what makes growth a single absolute op rather than a rewrite of every stroke on the page
 * (SPEC §4.2, CLAUDE.md rule 8) and what keeps a peer's concurrent stroke landing where its
 * author put it.
 *
 * ⚠️ **`origin` is not a second coordinate space.** Nothing is ever expressed *relative to* it
 * inside the app: every stroke, every erase capsule and every transform stays in page space with
 * the origin page space has always had. It is read in exactly two kinds of place — the ones that
 * draw the paper's rectangle, and the one that hands a page to a PDF writer, which is the single
 * boundary where "where the box is" and "where the ink is" have to be reconciled. CLAUDE.md's
 * note about erase capsules — *"when a coordinate space is only ever the identity, two different
 * spaces have the same numbers and the code says the wrong one for free"* — is about exactly this
 * hazard, and the defence is that there is no second space to name.
 *
 * @property origin the paper's top-left corner in page space. `(0, 0)` unless the canvas has been
 *   grown up or to the left.
 * @property size the paper's extent from [origin].
 * @property infinite whether this page grows to hold whatever is written on it. `false` is a
 *   fixed sheet: ink outside it is refused at the pen and clipped in the renderer, so what is on
 *   screen is what a reader will see.
 */
public data class PageCanvas(
    val size: PageSize,
    val origin: PagePoint = PagePoint(0f, 0f),
    val infinite: Boolean = false,
) {
    public val left: Float get() = origin.x

    public val top: Float get() = origin.y

    public val right: Float get() = origin.x + size.widthPt

    public val bottom: Float get() = origin.y + size.heightPt

    /** The paper, as a rectangle in page space. */
    public val rect: PageRect get() = PageRect(left, top, right, bottom)

    /**
     * Whether ([x], [y]) is on the paper.
     *
     * [slackPt] widens the test in every direction, and the pen path passes a little: a stroke
     * begun a hair outside the edge by a hand aiming *at* the edge is one the user meant, and
     * clipping renders the overshoot away in any case.
     */
    public fun contains(x: Float, y: Float, slackPt: Float = 0f): Boolean =
        x >= left - slackPt && x <= right + slackPt && y >= top - slackPt && y <= bottom + slackPt

    /**
     * This canvas, grown until [content] fits with [MARGIN_PT] to spare — or itself, if it
     * already does.
     *
     * **Grow-only, and quantised.** Two properties, both load-bearing:
     *
     * - *Grow-only* means this can be re-derived from a page's own contents at any time and never
     *   takes anything away. That is what makes it safe against SPEC §13.2's merges: two devices
     *   that grew a page differently both re-grow to cover everything the union contains the next
     *   time either of them folds it, so no peer's ink can be left outside the box by a register
     *   that resolved the other way. The op is still absolute and still last-writer-wins; it is
     *   the *derivation* that converges, which is why the publisher runs it too.
     * - *Quantised* to [SLAB_PT] means writing steadily down a page emits an op every slab rather
     *   than one per stroke, and the paper's edge moves in visible steps instead of creeping
     *   after the pen.
     *
     * A null [content] is an empty page — *"nothing to hold"*, which is not the same as an empty
     * rectangle at the origin and would grow the paper to reach `(0, 0)` if it were treated as
     * one.
     *
     * This never shrinks. Coming back down is the caller's business and is done by re-deriving
     * from the page's *stored* canvas rather than from the grown one, which is why
     * `InkSurfaceView` keeps the two apart.
     */
    public fun grownToInclude(content: PageRect?): PageCanvas {
        if (content == null) return this
        val grown = PageRect(
            left = growDown(left, content.left - MARGIN_PT),
            top = growDown(top, content.top - MARGIN_PT),
            right = growUp(right, content.right + MARGIN_PT),
            bottom = growUp(bottom, content.bottom + MARGIN_PT),
        )
        if (grown == rect) return this
        return copy(
            origin = PagePoint(grown.left, grown.top),
            size = PageSize(grown.width, grown.height),
        )
    }

    /** [grownToInclude] for a single point — what the pen asks before a stroke may begin. */
    public fun grownToInclude(x: Float, y: Float): PageCanvas = grownToInclude(PageRect(x, y, x, y))

    public companion object {
        /** Kept clear beyond the furthest ink, so the paper's edge is never against the writing. */
        public const val MARGIN_PT: Float = 72f

        /**
         * The growth quantum — half a Letter page.
         *
         * Small enough that the canvas never grows into acres nobody asked for, large enough that
         * a page of writing is a handful of `SetPageMeta` ops rather than one per stroke.
         */
        public const val SLAB_PT: Float = 396f

        /** A fixed sheet at the page-space origin: what every page was before this type existed. */
        public fun fixed(size: PageSize): PageCanvas = PageCanvas(size)

        /** Extends an edge outwards to at least [wanted], in whole slabs. */
        private fun growUp(edge: Float, wanted: Float): Float =
            if (wanted <= edge) edge else edge + ceil((wanted - edge) / SLAB_PT) * SLAB_PT

        /** [growUp] for an edge that grows by decreasing — the left and top ones. */
        private fun growDown(edge: Float, wanted: Float): Float =
            if (wanted >= edge) edge else edge - ceil((edge - wanted) / SLAB_PT) * SLAB_PT
    }
}

/**
 * [PageRect.union], for accumulating a bounds over a collection that may be empty.
 *
 * A null receiver is "nothing so far" rather than an empty rectangle at the origin — which is a
 * distinction that matters here, since a page whose only ink sits at `(900, 900)` must not have
 * its content bounds dragged back to `(0, 0)` by the accumulator's starting value.
 */
public fun PageRect?.unionOrNull(other: PageRect): PageRect = this?.union(other) ?: other

/**
 * ⭐ Everything on this page, as one page-space box — **what an infinite canvas grows to hold**,
 * asked by the one caller that is not the editor: the publisher.
 *
 * `:core:model` cannot measure a stroke, because stroke geometry is an opaque blob it must never
 * look inside (SPEC §5.4). So the strokes arrive already outlined, from the [StrokeOutliner] the
 * materializer runs anyway, and everything else is measured from its own box under its own
 * absolute transform.
 *
 * ⚠️ **A text box is measured by its frame, not by its payload.** Resizing one writes `SetFormat`
 * (SPEC §4.9.2) and leaves `widthPt` at whatever it was created with, so measuring the payload
 * would put the paper's edge through a paragraph the user had widened — the same read-side
 * mismatch `docs/M8-TEXT-FRAME.md` records, reached through the canvas instead of through the
 * layout.
 *
 * Null for a page with nothing on it.
 */
public fun FoldedPage.contentBounds(outlines: List<StrokeOutline>): PageRect? {
    var bounds: PageRect? = null
    for (outline in outlines) {
        for (contour in outline.contours) {
            for (point in contour) bounds = bounds.unionOrNull(PageRect(point.x, point.y, point.x, point.y))
        }
    }
    for (obj in objects) {
        val extent = extentOf(obj) ?: continue
        bounds = bounds.unionOrNull(boxUnder(extent.first, extent.second, obj.transform))
    }
    return bounds
}

/** An object's own width and height, or null for a kind that is measured from its outline. */
private fun FoldedPage.extentOf(obj: InkObject): Pair<Float, Float>? = when (obj) {
    is TextBoxObject -> ObjectFormat.decode(formatOf(obj.id)).let { format ->
        format.frameWidth(obj.widthPt) to format.frameHeight(obj.heightPt)
    }

    is ImageObject -> obj.widthPt to obj.heightPt

    is ShapeObject -> obj.widthPt to obj.heightPt

    // A stroke has no box of its own — it is measured from the outline the publisher already has,
    // which is also the only measurement that accounts for what an eraser has taken out of it.
    else -> null
}

/** A `0..width` × `0..height` box under [transform], as an axis-aligned page-space rectangle. */
private fun boxUnder(widthPt: Float, heightPt: Float, transform: Matrix3): PageRect {
    val corners = listOf(
        transform.map(PagePoint(0f, 0f)),
        transform.map(PagePoint(widthPt, 0f)),
        transform.map(PagePoint(widthPt, heightPt)),
        transform.map(PagePoint(0f, heightPt)),
    )
    return PageRect(
        left = corners.minOf { it.x },
        top = corners.minOf { it.y },
        right = corners.maxOf { it.x },
        bottom = corners.maxOf { it.y },
    )
}
