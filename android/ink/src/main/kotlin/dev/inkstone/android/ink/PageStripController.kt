package dev.inkstone.android.ink

import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageStrip

/**
 * ⭐ The state behind scrolling through the pages like a PDF: which pictures sit above and below the
 * page being edited, where, and whether a turn to one of them is already on its way.
 *
 * Split out of `InkSurfaceView` for the reason `ShapeSnapController` was — and because everything in
 * here is a *decision* about plain data (page ids, sizes, rectangles), which is exactly what can be
 * tested without a window, a bitmap or a `MotionEvent`. The view keeps the glue: it feeds this the
 * camera, asks it what to do, and does the Android half (posting the callback, moving the camera,
 * handing the dry layer its pictures).
 *
 * Generic over the picture so that the decisions can be exercised against a stand-in that has only
 * a page id and a size; production uses [PageNeighbour].
 *
 * @param show told whenever the placed pictures change, so the layer that draws them stays in step.
 */
internal class PageStripController<T : PagePicture>(private val show: (Placed<T>?, Placed<T>?) -> Unit) {

    /** Whether the note scrolls like a PDF at all. Switching it off forgets everything. */
    var enabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) clear()
        }

    /** The page on screen, so a picture rendered for a page the user has since left is recognisable. */
    var activePageId: PageId? = null

    private var above: T? = null
    private var below: T? = null

    /** A picture of the page being edited, kept for the moment it stops being the page being edited. */
    private var self: T? = null

    private var paper: PageRect? = null

    var aboveRect: PageRect? = null
        private set

    var belowRect: PageRect? = null
        private set

    /** The turn that has been asked for and not yet made, so one crossing asks exactly once. */
    private var pending: StripTurn<T>? = null

    /**
     * Take the pictures of the pages around [forPage]. Ignored unless it is the page on screen and
     * scrolling is on: a render finishes some time after it was asked for, and the user may have
     * turned the page twice since.
     *
     * @return whether it was taken - the view re-leashes the camera when it was, because the
     *   reachable area just changed.
     */
    fun set(forPage: PageId, above: T?, self: T?, below: T?, paper: PageRect): Boolean {
        if (!enabled || forPage != activePageId) return false
        this.above = above
        this.self = self
        this.below = below
        layout(paper)
        return true
    }

    /** Place the pictures beside [paper] as it is now. Called when the pictures change and when the paper does. */
    fun layout(paper: PageRect) {
        this.paper = paper
        val aboveNow = above.takeIf { enabled }
        val belowNow = below.takeIf { enabled }
        aboveRect = aboveNow?.let { PageStrip.above(paper, it.size) }
        belowRect = belowNow?.let { PageStrip.below(paper, it.size) }
        show(placed(aboveNow, aboveRect), placed(belowNow, belowRect))
    }

    private fun placed(picture: T?, rect: PageRect?): Placed<T>? =
        if (picture != null && rect != null) Placed(picture, rect) else null

    /** The area the camera may roam: the paper, plus whichever neighbours there are. */
    fun reach(canvas: PageCanvas): PageCanvas = if (enabled && (aboveRect != null || belowRect != null)) {
        PageStrip.reach(canvas, aboveRect, belowRect)
    } else {
        canvas
    }

    /** The crossing, if the middle of the view (at [centreY], in the active page's space) is inside a neighbour. */
    fun crossing(centreY: Float): StripTurn<T>? = when (PageStrip.turnFor(centreY, aboveRect, belowRect)) {
        PageStrip.NEXT -> turnTo(below, belowRect)
        PageStrip.PREVIOUS -> turnTo(above, aboveRect)
        else -> null
    }

    /** The turn a pen landing at ([x], [y]) means, if it landed on a neighbour. */
    fun landing(x: Float, y: Float): StripTurn<T>? = when (PageStrip.neighbourAt(x, y, aboveRect, belowRect)) {
        PageStrip.NEXT -> turnTo(below, belowRect)
        PageStrip.PREVIOUS -> turnTo(above, aboveRect)
        else -> null
    }

    private fun turnTo(picture: T?, rect: PageRect?): StripTurn<T>? {
        if (!enabled || pending != null) return null
        return if (picture != null && rect != null) StripTurn(picture, rect) else null
    }

    /** The turn is under way: no other may be asked for until it ends. */
    fun begin(turn: StripTurn<T>) {
        pending = turn
    }

    /** The owner has finished - or refused - the turn it was asked for. */
    fun end() {
        pending = null
    }

    /**
     * The turn to [pageId], taken exactly once. A turn that arrives for any other page — the page
     * bar's, or a search hit's — is an ordinary page load and gets the ordinary refit.
     */
    fun take(pageId: PageId): StripTurn<T>? {
        val turn = pending
        end()
        return turn?.takeIf { it.target.pageId == pageId }
    }

    /**
     * The strip as it must be the instant the page changes. After a scrolled turn the page just left
     * is put on the side the camera came from, and the page turned to is kept as the picture of the
     * active one — so nothing on the glass blinks while the editor renders what comes next. After
     * any other load the strip is simply emptied, because it described a different page.
     */
    fun rotate(turnedTo: T?) {
        val left = self
        val forward = turnedTo != null && turnedTo.pageId == below?.pageId
        above = if (forward) left else null
        below = if (turnedTo != null && !forward) left else null
        self = turnedTo
    }

    fun clear() {
        above = null
        below = null
        self = null
        aboveRect = null
        belowRect = null
        end()
        show(null, null)
    }
}
