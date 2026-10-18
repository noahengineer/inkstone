package dev.inkstone.android.ink

import android.graphics.Bitmap
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize

/**
 * What the strip needs to know about a picture of a page without caring what the picture is made of —
 * which page, and how big its paper is. A bitmap cannot be built on the JVM, and everything the strip
 * *decides* depends on these two facts and on nothing else, so the decisions are tested against a
 * stand-in that has only them ([PageStripController]).
 */
public interface PagePicture {
    /**
     * The page this is a picture of, so a result that arrives after the user has moved on is
     * recognisable as belonging to somebody else and is dropped.
     */
    public val pageId: PageId

    /**
     * The page's extent in points — the paper it was drawn from, which is what lays it out beside the
     * page being edited.
     */
    public val size: PageSize
}

/**
 * ⭐ A **picture** of a page — what the surface draws for the page above and the page below the one
 * being edited, while a note scrolls like a PDF (`InkSurfaceView.continuousScroll`).
 *
 * A picture and not a second live page, on purpose: the editor holds one page at a time (one fold,
 * one stroke store, one op recorder), and everything that writes — the pen, the eraser, the
 * selection, undo — is bound to it. Making two pages live would make "which page does this stroke
 * belong to" a question every input path had to answer. See `PageStrip` in `:core:model` for the
 * rest of that argument.
 *
 * ⚠️ **It is a stand-in, and it may be stale.** It was rendered from the fold at some moment, off
 * the main thread, and nothing keeps it current — the editor re-renders the page being edited a
 * couple of seconds after the pen stops, and replaces a neighbour's picture whenever its revision
 * moves. The real page replaces the picture the instant the camera crosses into it, so what is
 * drawn here is never what is written to.
 *
 * @property bitmap held, not owned: whoever rendered it recycles it, and only after it has been
 *   replaced here. A recycled one is skipped rather than drawn, because the alternative is a crash
 *   in `onDraw`.
 */
public class PageNeighbour(override val pageId: PageId, public val bitmap: Bitmap, override val size: PageSize) :
    PagePicture

/**
 * A picture with the rectangle it occupies in the **active** page's space.
 *
 * Internal: the placement is the surface's business (`PageStrip` decides it), and `:app` should not
 * be able to hand over a rectangle that disagrees with it.
 */
internal class Placed<T : PagePicture>(val picture: T, val rect: PageRect)

internal typealias PlacedPicture = Placed<PageNeighbour>

/** A crossing the surface has noticed: turn to [target], whose rectangle (in the old page's space) is [rect]. */
internal class StripTurn<T : PagePicture>(val target: T, val rect: PageRect)
