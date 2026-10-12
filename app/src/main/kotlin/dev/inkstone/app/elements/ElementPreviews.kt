package dev.inkstone.app.elements

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.inkstone.android.ink.InkBrushFactory
import dev.inkstone.android.ink.InkPageThumbnailRenderer
import dev.inkstone.android.ink.PageAssets
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.storage.Element
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * ⭐ A picture of an Element, for the panel and for the drag ghost.
 *
 * M8c's panel was a list of names on the argument that drawing an Element *"means rendering its
 * members … the same machinery `Thumbnails` already owns for whole pages"*. That is exactly why it
 * is cheap: an Element **is** a page's worth of objects, so it goes through
 * [InkPageThumbnailRenderer] as a small page, and is drawn by the same painter as the editor —
 * erasures, fills, knockouts and all. A second renderer for the panel would be a second place for
 * a picture to disagree with what it stamps.
 *
 * Without one, *"drag this onto the page"* means dragging a row of text, which says nothing about
 * what is about to land or how big it is.
 */
@Singleton
class ElementPreviews @Inject constructor(private val brushes: InkBrushFactory) {

    /**
     * WebP bytes of [element] drawn [widthPx] wide on white paper with a small margin, or null when
     * it could not be drawn.
     */
    suspend fun render(element: Element, widthPx: Int): ByteArray? {
        val width = widthPt(element)
        val height = heightPt(element)
        val margin = marginPt(element)
        val page = FoldedPage(
            pageId = PageId(PREVIEW_PAGE),
            // The objects are stored relative to the Element's own origin, so the paper starts a
            // margin above and to the left of it rather than every object being moved.
            canvas = PageCanvas(PageSize(width + 2 * margin, height + 2 * margin), PagePoint(-margin, -margin)),
            background = Background.Blank,
            objects = element.objects,
            deleted = emptySet(),
            throughClock = null,
            opCount = 0,
            texts = element.texts,
            formats = element.formats,
        )
        val pictures = ElementAssets(element.assets)
        return try {
            InkPageThumbnailRenderer(brushes, assets = pictures).render(PREVIEW_NOTE, page, widthPx)
        } finally {
            pictures.recycle()
        }
    }

    /** The Element's own picture bytes, decoded on demand and released after one render. */
    private class ElementAssets(private val bytes: Map<String, ByteArray>) : PageAssets {
        private val decoded = HashMap<String, Bitmap?>()

        @Synchronized
        override fun bitmap(asset: String): Bitmap? = decoded.getOrPut(asset) {
            bytes[asset]?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }

        @Synchronized
        fun recycle() {
            decoded.values.forEach { it?.recycle() }
            decoded.clear()
        }
    }

    companion object {
        /**
         * The extent a preview — and the drag ghost, which draws the preview — treats [element] as
         * having. One function for both, so the ghost the finger carries is the size of the paper
         * the picture was drawn on.
         */
        fun widthPt(element: Element): Float = max(element.widthPt, MIN_EXTENT_PT)

        fun heightPt(element: Element): Float = max(element.heightPt, MIN_EXTENT_PT)

        /** The blank paper round the Element in its preview, on every side. */
        fun marginPt(element: Element): Float = max(widthPt(element), heightPt(element)) * MARGIN_FRACTION

        /** An Element saved before ink could be measured has a zero extent; draw it on something. */
        private const val MIN_EXTENT_PT = 24f
        private const val MARGIN_FRACTION = 0.08f
        private const val PREVIEW_PAGE = "element-preview"

        /** The renderer asks which note a page belongs to only to fetch a PDF background; there is none. */
        private val PREVIEW_NOTE = DocumentId("element-preview")
    }
}
