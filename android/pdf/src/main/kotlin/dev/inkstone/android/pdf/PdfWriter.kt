package dev.inkstone.android.pdf

import dev.inkstone.core.model.AnnotationDensity
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.ImageCrop
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageAnnotationMode
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PdfPageGeometry
import dev.inkstone.core.model.StrokeOutline
import dev.inkstone.core.storage.InkstoneMarker
import kotlin.math.roundToInt

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
internal data class BasePage(val canvas: PageCanvas, val background: Background)

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
    /**
     * ⭐ The paper this page is published on — grown, if the page is infinite, to hold everything
     * written on it (SPEC §4.5).
     *
     * The publisher derives this the same way the editor does, from the same objects, which is
     * what makes the box in the file and the paper on screen the same function rather than two
     * values that have to be kept in step.
     */
    val canvas: PageCanvas,
    val mode: PageAnnotationMode,
    val outlines: List<StrokeOutline>,
    val words: List<InvisibleWord> = emptyList(),
    val objects: List<PdfPageObject> = emptyList(),
    /**
     * ⭐ Strokes handwriting synthesis wrote (`hw`, docs/HANDWRITING-SYNTHESIS.md D11): each of their
     * annotations carries [PdfPageKeys.GENERATED], so a generated mark can always be told from a
     * written one in the file. A flattened page has no annotations to carry it; see its KDoc.
     */
    val generated: Set<ObjectId> = emptySet(),
) {

    /**
     * ⭐ How many `/Annots` entries this page will produce — **and therefore the number SPEC
     * §6.4's threshold is about**.
     *
     * ⚠️ [objects] contribute **zero**, because M8c writes them into the page's own
     * content stream and never as annotations (see [PdfTextBox]). That is what keeps this count
     * honest, and it is a property of the writer rather than of this expression: the day a text
     * box becomes a `/FreeText`, a page could carry six hundred annotations while this reported
     * two hundred, and SPEC §6.4's *"5,000 annotations will hang Preview and PDFium"* would be
     * enforced against the wrong number. `PdfViewerCompatTest` asserts the identity — a published
     * page's `/Annots` length **is** this — so the two cannot drift in silence.
     */
    val annotatedObjects: Int get() = annotationCount(outlines)

    companion object {

        /** SPEC §6.4's density decision, over the one count [annotatedObjects] reports. */
        fun modeFor(outlines: List<StrokeOutline>): PageAnnotationMode =
            AnnotationDensity.modeFor(annotationCount(outlines))

        /** A stroke the eraser took entirely is not written and must not be counted. */
        private fun annotationCount(outlines: List<StrokeOutline>): Int = outlines.count { !it.isEmpty }
    }
}

/**
 * A page object that is not a stroke — SPEC §4.9's text boxes and images.
 *
 * ⚠️ **One list in z order, not one list per kind.** Two lists would make the writer choose an
 * order between them, and a caption under a photograph would then depend on which loop ran first
 * rather than on the `SetZOrder` register the user set. `FoldedPage.objects` already arrives
 * ordered by `(z, id)` (SPEC §4.3), and the whole job here is to not lose that.
 */
internal sealed interface PdfPageObject {
    val objectId: ObjectId
}

/**
 * ⭐ One text box, ready to become **real PDF text** — ROADMAP M8c acceptance 1: *"selectable,
 * searchable PDF text — not as an image"*.
 *
 * ## Page content, never an annotation, and the decision matters three ways
 *
 * A `/FreeText` annotation would also carry text and would also render. It is not used, for
 * reasons that compound:
 *
 * 1. **Extraction.** Poppler pulls text out of an annotation's appearance stream; PyMuPDF's
 *    `page.get_text()` does not. *"Searchable in one extractor and not the other"* is the shape of
 *    every bug docs/M4-PDF.md records, and the acceptance criterion is not qualified by which tool
 *    the reader happens to own.
 * 2. **Identity buys nothing.** `/NM` exists on our `/Ink` annotations because Recovered mode
 *    rebuilds strokes out of `/InkList` (SPEC §5.6). There is no recovery path from a PDF back to
 *    a text box, so an annotation would carry an id nothing reads.
 * 3. **The density rule.** SPEC §6.4 caps `/Annots` length. Text in the content stream costs a
 *    viewer page-content time, which is the budget the cap exists to protect — see
 *    [PdfPagePlan.annotatedObjects].
 *
 * ⚠️ **The consequence, stated rather than discovered:** a viewer paints annotations *above* page
 * content, so on a `PER_STROKE` page ink is drawn over a text box whatever the two objects' z
 * keys say. That is right for the common case — writing on top of an inserted picture — and wrong
 * for the rarer one. Fixing it properly means interleaving strokes and objects in one stream by z,
 * which needs a z key on `StrokeOutline` that `:core:model` does not carry. Recorded here so the
 * next milestone changes it deliberately.
 *
 * @property style non-null by construction. SPEC §4.9.2: `SetStyle` cannot express *"unset"*, so a
 *   text box created without one has no inverse for its first recolour and *"change the colour,
 *   press undo"* eats the press. Making the field non-null here means a plan carrying an unstyled
 *   text box cannot be built.
 * @property format the `SetFormat` register (SPEC §4.9.2) — font, size, alignment, list. Separate
 *   from [text] because they are separate registers, so that restyling on one device cannot
 *   discard a paragraph typed on the other.
 */
internal data class PdfTextBox(
    override val objectId: ObjectId,
    val text: String,
    val widthPt: Float,
    val heightPt: Float,
    val transform: Matrix3,
    val format: ObjectFormat,
    val style: ObjectStyle,
) : PdfPageObject {
    /** What this box composites at, 0..255 — the colour's own alpha times [ObjectStyle.opacity]. */
    val alpha: Int get() = compositeAlpha(style.colorArgb, style.opacity)
}

/**
 * One inserted image, with the bytes the bundle's `assets/` entry held (SPEC §4.9.4).
 *
 * Page content for the same reasons as [PdfTextBox], plus one of its own: an image annotation
 * would have to be a `/Stamp` with an appearance stream, which is the representation M4.0's spike
 * rejected `androidx.pdf` for producing.
 *
 * ⚠️ **A plain class, not a `data class`.** [bytes] is an array, so generated `equals` would
 * compare it by identity and a `PdfImage` would silently never equal an equal one — detekt's
 * `ArrayInDataClass` is right about this, and a plan is compared in tests.
 *
 * @property crop normalised to the **source image**, never to page points (SPEC §4.9.2). Null is
 *   the whole picture.
 * @property opacity `ObjectStyle.opacity` folded with the style colour's alpha, 0..1.
 */
internal class PdfImage(
    override val objectId: ObjectId,
    val bytes: ByteArray,
    val widthPt: Float,
    val heightPt: Float,
    val transform: Matrix3,
    val crop: ImageCrop?,
    val opacity: Float,
) : PdfPageObject {
    /**
     * What this picture composites at, 0..255.
     *
     * From [opacity] alone: a style's colour is a *tint*, and an image has none — folding the
     * style colour's alpha in here as well would fade every picture on a page whose text boxes
     * happened to be highlighted.
     */
    val alpha: Int get() = (OPAQUE * opacity.coerceIn(0f, 1f)).roundToInt().coerceIn(0, OPAQUE)
}

/**
 * An object's effective alpha as a byte, from a style's colour alpha **and** its opacity.
 *
 * Both exist and both mean transparency: a stroke's translucency is captured in its colour (SPEC
 * §3.4's highlighter) while `ObjectStyle.opacity` is what a slider writes. Taking one and ignoring
 * the other publishes a fully opaque highlighter or a fully opaque faded image, which is M4's
 * missing-`/ca` failure arriving from the register side instead of the resource side.
 */
internal fun compositeAlpha(colorArgb: Int, opacity: Float): Int {
    val fromColour = (colorArgb ushr ALPHA_SHIFT) and OPAQUE
    return (fromColour * opacity.coerceIn(0f, 1f)).roundToInt().coerceIn(0, OPAQUE)
}

private const val ALPHA_SHIFT = 24
private const val OPAQUE = 0xFF

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
    data class Generated(val canvas: PageCanvas, val background: Background) : PageSource
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

    /**
     * Set on an `/Ink` annotation whose stroke handwriting synthesis generated (D11). Ignored by every
     * viewer, which still draws the ink; present so that *"it looks just like you did it"* never
     * becomes *"no file can tell"*. ⚠️ A page dense enough to be flattened (SPEC §6.4) has no
     * annotations, so there the mark is only in the note's own `hw` keys.
     */
    const val GENERATED = "INKS_Generated"
}
