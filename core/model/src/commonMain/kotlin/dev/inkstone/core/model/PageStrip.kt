package dev.inkstone.core.model

import kotlin.math.max
import kotlin.math.min

/**
 * ⭐ The arithmetic of **scrolling through a note's pages like a PDF** — the page above and the
 * page below laid out beside the one being edited, and the moment the camera passes from one to
 * the next.
 *
 * ## Why this is geometry and not a second editor
 *
 * The editor holds exactly one page at a time: one fold, one stroke store, one op recorder, one
 * undo stack per page. A continuous scroll that kept two of them live would make "which page does
 * this stroke belong to" a question every input path had to answer, and CLAUDE.md rule 7 says what
 * those paths may cost. So the neighbours are **pictures** — a bitmap of each, placed in the active
 * page's own coordinate space — and the page that is *editable* changes when the camera's centre
 * crosses into one. Everything below is the placement and the crossing; the pictures are
 * `:android:ink`'s and the page turn is the editor's.
 *
 * ## Page space is never re-anchored
 *
 * A neighbour is placed in the **active page's** space (its rectangle may be at a negative y), and
 * nothing is written into it. When the active page changes, [carried] moves the *camera*, not any
 * object: page space is per page and has always been, so the only quantity that has to be
 * continuous across a turn is where things are on the glass.
 *
 * ⚠️ **The two thresholds are not the same line, and that is the point of [turnFor].** A turn
 * happens when the centre of the view enters a neighbour's rectangle, which leaves it exactly
 * [GAP_PT] inside its new page. Turning back needs it to cross that whole gap again, so a hand
 * resting at the boundary does not turn the page over and back on every wobble. A threshold at the
 * gap's midpoint has no such margin and ping-pongs.
 */
public object PageStrip {

    /** The blank band between two pages, in page points — enough to read as a page edge, not a hole. */
    public const val GAP_PT: Float = 24f

    /** Where a page of [size] sits **above** [paper]: centred on it, [GAP_PT] clear. */
    public fun above(paper: PageRect, size: PageSize): PageRect {
        val left = paper.centreX - size.widthPt / 2f
        val bottom = paper.top - GAP_PT
        return PageRect(left, bottom - size.heightPt, left + size.widthPt, bottom)
    }

    /** Where a page of [size] sits **below** [paper]: centred on it, [GAP_PT] clear. */
    public fun below(paper: PageRect, size: PageSize): PageRect {
        val left = paper.centreX - size.widthPt / 2f
        val top = paper.bottom + GAP_PT
        return PageRect(left, top, left + size.widthPt, top + size.heightPt)
    }

    /**
     * The area the camera may roam, as a canvas the existing leash ([leashedTo]) understands — the
     * paper and whichever neighbours there are.
     *
     * A leash about the paper alone would stop the camera at the page's edge, which is exactly the
     * wall a continuous scroll exists to remove.
     */
    public fun reach(paper: PageCanvas, above: PageRect?, below: PageRect?): PageCanvas {
        var area = paper.rect
        above?.let { area = area.union(it) }
        below?.let { area = area.union(it) }
        return paper.copy(
            size = PageSize(area.width, area.height),
            origin = PagePoint(area.left, area.top),
        )
    }

    /**
     * Which neighbour the view's centre has entered: [PREVIOUS], [NEXT], or [STAY].
     *
     * @param centreY the y of the middle of the viewport, in the active page's space.
     */
    public fun turnFor(centreY: Float, above: PageRect?, below: PageRect?): Int = when {
        below != null && centreY > below.top -> NEXT
        above != null && centreY < above.bottom -> PREVIOUS
        else -> STAY
    }

    /**
     * Whether ([x], [y]) is on the page above or the page below — what a pen landing off the paper
     * is asked, so a stroke begun on a neighbour turns to it rather than being refused for ever.
     *
     * @return [PREVIOUS], [NEXT] or [STAY].
     */
    public fun neighbourAt(x: Float, y: Float, above: PageRect?, below: PageRect?): Int = when {
        below != null && x >= below.left && x <= below.right && y >= below.top && y <= below.bottom -> NEXT
        above != null && x >= above.left && x <= above.right && y >= above.top && y <= above.bottom -> PREVIOUS
        else -> STAY
    }

    /**
     * The camera that leaves everything on the glass where it was when the active page changes to
     * the neighbour at [neighbourRect] (in the *old* page's space), whose own paper is [newPaper]
     * (in the *new* page's space).
     *
     * The neighbour's top-left corner is the fixed point: it was at `neighbourRect.topLeft` under
     * [camera], and the new page's paper starts at `newPaper.topLeft`, so the translation absorbs
     * the difference. The scale is untouched — a turn must not zoom.
     */
    public fun carried(camera: Camera, neighbourRect: PageRect, newPaper: PageRect): Camera = camera.copy(
        translate = Offset(
            x = camera.translate.x + (neighbourRect.left - newPaper.left) * camera.scale,
            y = camera.translate.y + (neighbourRect.top - newPaper.top) * camera.scale,
        ),
    )

    /** The neighbour above (a turn *back*), or the page before. */
    public const val PREVIOUS: Int = -1

    /** No turn. */
    public const val STAY: Int = 0

    /** The neighbour below (a turn *forward*). */
    public const val NEXT: Int = 1

    /**
     * A strip of bitmaps wants a bounded width: the **smaller** of what the screen could show and a
     * ceiling, because three full-resolution Letter pages at 2560 px are ~100 MB and this app has
     * already been destroyed by its own heap once (`docs/NOTE-STAYS-LOADED.md`).
     */
    public fun pictureWidthPx(viewportWidthPx: Int, ceilingPx: Int): Int = max(1, min(viewportWidthPx, ceilingPx))
}
