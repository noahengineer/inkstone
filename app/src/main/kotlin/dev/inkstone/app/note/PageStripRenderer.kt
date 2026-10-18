package dev.inkstone.app.note

import android.graphics.Bitmap
import dev.inkstone.android.ink.InkBrushFactory
import dev.inkstone.android.ink.InkPageThumbnailRenderer
import dev.inkstone.android.ink.PageNeighbour
import dev.inkstone.app.library.ThumbnailSourcePagesImpl
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.PageId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ⭐ Draws the page above and the page below the one being edited, for a note that scrolls like a PDF
 * (`InkSurfaceView.continuousScroll`).
 *
 * The same renderer as the library's thumbnails and the page overview — [InkPageThumbnailRenderer] —
 * so a neighbour can never disagree with the page it becomes about line spacing, erasures or fills.
 * What differs is the output: a bitmap at close to the screen's width rather than a 480 px WebP, in
 * `RGB_565` because the paper is opaque and the strip holds three at once.
 *
 * ⚠️ **Stateless and shared.** The cache of what has been drawn lives with the editor
 * ([PageStripCache]) because whether a picture is still good depends on the note it came from.
 *
 * ⚠️ **Pictures inside a neighbour draw as placeholders.** The editor's own decoded-picture cache is
 * deliberately not lent here: it keeps only the pictures of the page on screen and evicts the rest,
 * so asking it for a neighbour's photographs would push out the page being written on. A neighbour
 * is a stand-in that is replaced by the real page before it is written to, and a grey box where a
 * photograph will be is an honest stand-in for it. (`docs/PAGE-SCROLL.md` §5.)
 */
@Singleton
class PageStripRenderer @Inject constructor(brushes: InkBrushFactory, sourcePages: ThumbnailSourcePagesImpl) {
    private val renderer = InkPageThumbnailRenderer(brushes, sourcePages)

    /** [page] as a picture [widthPx] wide, or null when it could not be drawn. */
    suspend fun render(noteId: DocumentId, page: FoldedPage, widthPx: Int): Bitmap? =
        renderer.renderBitmap(noteId, page, widthPx)
}

/**
 * What the pictures of a note's pages were drawn from, so one is reused exactly as long as it is
 * still a picture of the page.
 *
 * A fold's [FoldedPage.opCount] and [FoldedPage.throughClock] move on every op that touches the
 * page — a stroke, an erase, an undo, a paper change, a peer's merge — and nothing else changes what
 * the page looks like, so together they are the page's revision. The width is part of it because the
 * same page drawn narrower is not the picture a wider view wants.
 */
internal data class PageRevision(val opCount: Int, val through: LamportClock?, val widthPx: Int) {
    companion object {
        fun of(page: FoldedPage, widthPx: Int) = PageRevision(page.opCount, page.throughClock, widthPx)
    }
}

/**
 * A small least-recently-used set of page pictures, keyed by page and valid for one revision.
 *
 * Generic over what a picture is so that it can be tested without a bitmap, which cannot be built on
 * the JVM; production holds [PageNeighbour]s.
 *
 * Bounded at [capacity] pictures because each is several megabytes and this app has already had its
 * own screen destroyed by its own heap (`docs/NOTE-STAYS-LOADED.md`). The three the surface is
 * showing are always the most recently used, so the bound never evicts one that is on screen.
 *
 * ⚠️ **Nothing here recycles a bitmap.** The dry layer may still be drawing one that has just been
 * replaced, and a recycled bitmap in `onDraw` is a crash. Dropping the reference and letting the
 * collector have it is the safe direction.
 *
 * Not thread-safe by design: it is read and written from the editor screen's one coroutine scope.
 */
internal class PageStripCache<T : Any>(private val capacity: Int = DEFAULT_CAPACITY) {

    private class Entry<T>(val revision: PageRevision, val picture: T)

    private val entries = object : LinkedHashMap<PageId, Entry<T>>(capacity, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PageId, Entry<T>>?): Boolean = size > capacity
    }

    /** The picture of [page] at [revision], or null if there is none or it is out of date. */
    fun get(page: PageId, revision: PageRevision): T? = entries[page]?.takeIf { it.revision == revision }?.picture

    fun put(page: PageId, revision: PageRevision, picture: T) {
        entries[page] = Entry(revision, picture)
    }

    fun clear() = entries.clear()

    private companion object {
        /** The page on screen and the two beside it, plus the one the next scroll step will need. */
        const val DEFAULT_CAPACITY = 5
        const val LOAD_FACTOR = 0.75f
    }
}
