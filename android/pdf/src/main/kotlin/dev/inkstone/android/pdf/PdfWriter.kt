package dev.inkstone.android.pdf

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.PageAnnotationMode
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PdfPageGeometry
import dev.inkstone.core.model.StrokeOutline
import dev.inkstone.core.storage.InkstoneMarker

/**
 * SPEC §6.3 — *"PdfBox-Android behind a `PdfWriter` interface. No PdfBox types
 * outside `:android:pdf`."*
 *
 * The interface exists for the reason the spec gives it: PdfBox-Android has had
 * no release since January 2023 and ports PDFBox 2.0.x while upstream is on 3.0.x.
 * That is a dependency to be able to replace, and an interface with four methods
 * is the cost of being able to.
 *
 * It is `internal` because the *implementation* is not what leaves this module —
 * [PdfDocumentMaterializer] is. Nothing outside `:android:pdf` should have to know
 * a PDF writer exists at all.
 */
internal interface PdfWriter {

    /**
     * SPEC §5.2 — *"for notes created from scratch, generate base pages first,
     * then treat that as the original."*
     *
     * The result is the front of `document.pdf` forever after: it is never
     * rewritten, and every materialization appends to it.
     */
    fun createBase(pages: List<BasePage>): ByteArray

    /**
     * The published file: [original] verbatim, then **exactly one** incremental
     * update section (SPEC §5.2).
     *
     * "Exactly one" is the part that is easy to get wrong and expensive to
     * discover. Appending this session's update onto the *previous* update — which
     * is what happens if you simply take the last published file as the base —
     * makes the file grow by a full annotation set per editing session, forever.
     * The caller rebases on the original first; [InkstoneMarker.ORIGINAL_LENGTH]
     * is what lets it.
     */
    fun publish(original: ByteArray, plan: PublishPlan): ByteArray

    /**
     * SPEC §4.4 rule 2/3 — every page's **CropBox with `/Rotate` applied**.
     *
     * Read from the PDF rather than remembered, because the PDF is the front of
     * `document.pdf` verbatim and therefore cannot disagree with itself. See
     * [PdfPageGeometry].
     */
    fun readGeometry(pdf: ByteArray): List<PdfPageGeometry>
}

/** One generated page for a from-scratch note. Templates are drawn as vectors, not raster. */
internal data class BasePage(val size: PageSize, val background: Background)

/**
 * Everything one materialization writes into the update section.
 *
 * @property bundle the lossless `note.inkstone`, embedded via `/Names /EmbeddedFiles`
 *   (SPEC §5.3). Its SHA-256 goes in the marker, which is what makes a stripped or
 *   rebuilt bundle detectable (SPEC §5.6).
 */
internal data class PublishPlan(val marker: InkstoneMarker, val pages: List<PdfPagePlan>, val bundle: ByteArray)

/**
 * One page's ink, ready to write.
 *
 * @property source where the paper comes from — see [PageSource].
 * @property pageId written into the page dictionary — see [PdfPageKeys].
 * @property mode SPEC §6.4's density decision, already made by
 *   [dev.inkstone.core.model.AnnotationDensity].
 * @property words the invisible text layer (SPEC §5.5). **Scaffolding in M4**: the
 *   code path is built and exercised with an empty list; M7 fills it from ML Kit.
 */
internal data class PdfPagePlan(
    val source: PageSource,
    val pageId: PageId,
    val sortKey: FractionalKey,
    val mode: PageAnnotationMode,
    val outlines: List<StrokeOutline>,
    val words: List<InvisibleWord> = emptyList(),
)

/**
 * Where a published page's paper comes from.
 *
 * ## Why [Generated] exists, and why it does not break SPEC §5.2
 *
 * A note imported from a PDF can still gain a blank page — `NoteSession.addPage`
 * does not ask where the note came from — and it can lose one to a tombstone, or
 * have its pages reordered. None of that may touch the original bytes.
 *
 * So added pages, removals and reordering are all expressed **inside the same
 * single update section**: new page objects are appended there, and the page tree
 * is rewritten there if and only if the order actually changed. The original stays
 * byte-identical at the front, and the published file remains *original + exactly
 * one update* however many pages have come and gone (see [PdfWriter.publish]).
 *
 * The alternative — extending the base and treating the result as the new base —
 * is the version of this that grows the file by an annotation set per session, and
 * it is the one that looks obviously right until a note has been edited fifty times.
 */
internal sealed interface PageSource {

    /** Page [index] of the original, reused in place. */
    data class Existing(val index: Int) : PageSource

    /** A page the note added after import, or the whole of a from-scratch note. */
    data class Generated(val size: PageSize, val background: Background) : PageSource
}

/**
 * SPEC §5.5 — one recognised word, positioned from the strokes that made it.
 *
 * Page-space box, converted at write time like everything else. Rendered with text
 * rendering mode `3 Tr` (neither fill nor stroke), which is the "sandwich" OCRmyPDF
 * uses — except that ours is born-digital, so the box is exact rather than guessed.
 */
internal data class InvisibleWord(
    val text: String,
    val leftPt: Float,
    val topPt: Float,
    val rightPt: Float,
    val bottomPt: Float,
)

/**
 * Private page-dictionary keys carrying Inkstone's **page** identity.
 *
 * **Added in M4. SPEC §5.6 has been amended.** The spec puts `documentId` in the
 * XMP marker and an `ObjectId` in each annotation's `/NM`, and stops there — which
 * covers strokes and misses pages. Recovered mode without page identity has to
 * invent a [PageId] per page, and a peer merging that order keeps both the old page
 * and its replacement, because *"any page not present in a merged order is appended
 * at the end, never dropped"* (SPEC §4.3). The note silently doubles its page count:
 * the same failure as duplicated strokes, one level up.
 *
 * Private keys in a page dictionary are legal, ignored by every viewer, and cost
 * two strings per page. The `INKS_` prefix follows the PDF convention for
 * unregistered private names.
 */
internal object PdfPageKeys {
    const val PAGE_ID = "INKS_PageId"
    const val SORT_KEY = "INKS_SortKey"

    /** Set on a page written with [PageAnnotationMode.FLATTENED], so recovery can say so. */
    const val FLATTENED = "INKS_Flattened"
}
