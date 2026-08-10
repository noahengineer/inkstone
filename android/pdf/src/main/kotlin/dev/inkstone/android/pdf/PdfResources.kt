package dev.inkstone.android.pdf

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSUpdateInfo

/**
 * Mark an object to be written into an incremental update section.
 *
 * The one call in this module that must never be forgotten. In incremental mode PDFBox
 * writes only objects whose `COSUpdateInfo.isNeedToBeUpdated()` is true — *including ones
 * this code just created* — and an unmarked object is simply absent from the output with no
 * error anywhere. It is a function rather than an inline `setNeedToBeUpdated(true)` so that
 * every site that creates an object has a single, greppable thing to have done.
 *
 * Top-level rather than a method on [PdfBoxWriter] because [PdfResources] needs it too, and
 * two copies of this rule would be one copy too many.
 */
internal fun markUpdated(value: Any?) {
    (value as? COSUpdateInfo)?.isNeedToBeUpdated = true
}

/**
 * ⭐ Where a content stream's named resources belong — and it is not the same answer twice.
 *
 * A **form XObject** is a stream *with* a `/Resources` entry, so an annotation's `/AP /N`
 * carries its own. A **page content stream** has none: it resolves every name it uses through
 * the page dictionary (PDF 32000-1 §7.8.3, `/Resources`, *"Required; inheritable"*).
 *
 * **M4 put them on the stream in both cases, and that is the defect this object exists to
 * fix.** `/GS102 gs` in a flattened overlay (SPEC §6.4) and `/InkstoneF0 Tf` in the invisible
 * text layer (SPEC §5.5) were undefined names in every file this writer published. Nothing
 * here noticed: PdfBox re-parses it, `PdfReader` finds the ink, `detectOpenMode` says
 * `Normal`, and M4's acceptance tests checked the content stream's *text*. What a third-party
 * viewer does with an undefined `gs` is up to the viewer — PDF.js warns and carries on with
 * the default graphics state, so a translucent highlighter on a dense page came out **fully
 * opaque and covering the writing underneath it**; a stricter reader abandons the rest of the
 * stream, which is the overlay, so the page's ink is simply not drawn. Found in M6 by
 * `PdfViewerCompatTest`, the first test here to ask whether anything but PdfBox could draw
 * the file.
 */
internal object PdfResources {

    /** One category, wrapped as a form XObject's own `/Resources` dictionary. */
    fun form(category: COSName, entries: COSDictionary): COSDictionary = COSDictionary().apply {
        setItem(category, entries)
        markUpdated(this)
    }

    /**
     * Merge [entries] into the **page's own** `/Resources` under [category].
     *
     * ⚠️ **`/Resources` is inheritable, so absent on the page does not mean absent.** A source
     * PDF may keep one dictionary on a `/Pages` node for every page beneath it. Writing a
     * fresh one here would *shadow* it and the page would lose the fonts and images its own
     * content references — trading our one missing name for all of theirs. So the inherited
     * dictionary is copied down first.
     */
    fun addToPage(pageDict: COSDictionary, category: COSName, entries: COSDictionary) {
        if (entries.keySet().isEmpty()) return
        val resources = ownResources(pageDict)
        // A copy rather than the parsed dictionary itself: a source PDF's resource
        // dictionaries are routinely shared between pages, and adding our name to the
        // original would put it on pages that never asked for it.
        val merged = copyOf(resources.getDictionaryObject(category) as? COSDictionary)
        for (name in entries.keySet()) merged.setItem(name, entries.getItem(name))
        markUpdated(merged)
        resources.setItem(category, merged)
    }

    /**
     * Copy onto the page anything it was *inheriting*, before its `/Parent` is rewritten.
     *
     * `/Resources`, `/MediaBox`, `/CropBox` and `/Rotate` are the four inheritable page
     * attributes (PDF 32000-1 §7.7.3.4), and a source PDF is free to keep them on an
     * intermediate `/Pages` node — pdfTeX and Ghostscript both build nested trees for a long
     * document. `PdfBoxWriter.reorderIfNeeded` flattens the tree, which cuts that node out of
     * the chain, so a page that inherited its `/MediaBox` from it ends up with **no page size
     * at all**: a viewer either refuses the page or silently falls back to US Letter, and the
     * ink — written against the geometry read before the reorder — is then on paper of a
     * different size. `PDDocument.load` and `PdfReader` report the same page count they always
     * did.
     */
    fun inheritDown(pageDict: COSDictionary) {
        for (key in INHERITABLE_PAGE_ATTRIBUTES) {
            if (pageDict.getItem(key) == null) {
                // The raw item, not the resolved object: an inherited /MediaBox is normally an
                // indirect reference, and copying the reference keeps the base's own object
                // rather than dragging a duplicate into the update section.
                inheritedFrom(pageDict, key)?.getItem(key)?.let { pageDict.setItem(key, it) }
            }
        }
    }

    // -----------------------------------------------------------------------

    /** The page's own `/Resources`, created — or inherited and copied down — if it has none. */
    private fun ownResources(pageDict: COSDictionary): COSDictionary {
        (pageDict.getDictionaryObject(COSName.RESOURCES) as? COSDictionary)?.let { own ->
            // It may have come from the base, in which case the incremental writer skips it
            // unless it is marked — and the category dictionary hanging off it would then
            // never be reached. Same failure as markParentChain, one level down.
            markUpdated(own)
            return own
        }
        val inherited = inheritedFrom(pageDict, COSName.RESOURCES)
            ?.getDictionaryObject(COSName.RESOURCES) as? COSDictionary
        val resources = copyOf(inherited)
        markUpdated(resources)
        pageDict.setItem(COSName.RESOURCES, resources)
        return resources
    }

    /**
     * The nearest ancestor of [pageDict] that supplies [key] by inheritance, or null.
     *
     * The *node* rather than the value, because the two callers want it differently: one
     * copies the raw item to preserve an indirect reference, the other needs the resolved
     * dictionary. Asking for the value with `getItem` and casting is the trap — after a round
     * trip through the writer every nested dictionary is a `COSObject`, so the cast silently
     * yields null and an inherited dictionary looks absent.
     */
    private fun inheritedFrom(pageDict: COSDictionary, key: COSName): COSDictionary? {
        var node: COSDictionary? = pageDict.getDictionaryObject(COSName.PARENT) as? COSDictionary
        var depth = 0
        // A malformed file could have a /Parent cycle; a page tree is never deep, so a bound
        // is cheaper than a visited set. Same reasoning as PdfBoxWriter.markParentChain.
        while (node != null && depth < MAX_PAGE_TREE_DEPTH) {
            if (node.getItem(key) != null) return node
            node = node.getDictionaryObject(COSName.PARENT) as? COSDictionary
            depth++
        }
        return null
    }

    /** A shallow copy, so a dictionary that came out of the source file is never mutated. */
    private fun copyOf(dictionary: COSDictionary?): COSDictionary = COSDictionary().apply {
        dictionary ?: return@apply
        for (name in dictionary.keySet()) setItem(name, dictionary.getItem(name))
    }

    /** PDF 32000-1 §7.7.3.4 — the only page attributes a `/Pages` node may supply. */
    private val INHERITABLE_PAGE_ATTRIBUTES = listOf(
        COSName.RESOURCES,
        COSName.MEDIA_BOX,
        COSName.CROP_BOX,
        COSName.ROTATE,
    )

    private const val MAX_PAGE_TREE_DEPTH = 64
}
