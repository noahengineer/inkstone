package dev.inkstone.android.pdf

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.MaterializedFile
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageRef
import dev.inkstone.core.model.PdfPublisher
import dev.inkstone.core.model.StrokeOutliner
import dev.inkstone.core.model.TextBoxObject
import dev.inkstone.core.model.foldPage
import dev.inkstone.core.storage.InkstoneMarker
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.storage.NoteStore
import dev.inkstone.core.storage.OpenNote
import dev.inkstone.core.storage.PageTextCodec
import dev.inkstone.core.storage.sha256Hex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SPEC §5.1/§5.2 — **materialization**: rewrite `document.pdf` from the bundle.
 *
 * This is `:core:model`'s [PdfPublisher] — the *ungated* one, so only `NoteSessionHost` and
 * `NoteEditor` may hold it. It is the only thing about
 * PDFs that leaves this module.
 *
 * ## ⭐ Rebase, then append exactly one update
 *
 * ```
 * document.pdf = original bytes (verbatim, forever)  +  one incremental update
 * ```
 *
 * Every materialization reads the previous published file, **truncates it back to
 * the original** using [InkstoneMarker.originalLength], and appends a freshly
 * built update. The obvious alternative — append onto the file you already have —
 * produces a valid PDF too, and grows it by a whole annotation set every single
 * time the note is closed. Fifty sessions later a 20 MB lecture is 40 MB of
 * superseded ink and nobody can say when it happened.
 *
 * ## When it runs
 *
 * Document close, before upload, idle-while-charging (SPEC §5.1). **Never on the
 * ink path** (CLAUDE.md rule 7): outlining a page tessellates every stroke and
 * publishing re-serialises the update, so this hops to [dispatcher] itself rather
 * than trusting each caller to remember.
 *
 * ## Order of writes
 *
 * The bundle is written by `NoteStore.materialize` *before* this runs, and this
 * embeds those exact bytes and records their hash. That ordering is what makes the
 * marker's `bundleSha256` meaningful: it always describes the bundle that is in the
 * file, so a mismatch means something outside this app touched it (SPEC §5.6).
 */
public class InkstonePdfMaterializer internal constructor(
    private val fs: FileSystem,
    private val store: NoteStore,
    private val outliner: StrokeOutliner,
    private val dispatcher: CoroutineDispatcher,
    private val writer: PdfWriter,
) : PdfPublisher {

    /**
     * The only constructor callers outside this module can reach.
     *
     * [PdfWriter] stays internal deliberately: SPEC §6.3 puts the interface here so
     * PdfBox can be replaced, not so `:app` can choose a PDF backend.
     *
     * ⚠️ **[fonts] defaults to [PdfFontFaces.None], and that default is a *degrade*, not a
     * disable** (M8c). Without it a text box publishes in a standard-14 base font carrying
     * printable ASCII; with it, in a subset of the face pdfbox-android already ships, carrying
     * everything SPEC §4.9.5 measured. `:app` should pass `AssetPdfFontFaces(context.assets)`. The
     * default is not "no text", because a forgotten injection that produced a valid, parseable
     * PDF with the user's typing missing is precisely the failure docs/M4-PDF.md keeps recording.
     *
     * [images] needs no `Context` at all, so its default is the real one.
     */
    public constructor(
        fs: FileSystem,
        store: NoteStore,
        outliner: StrokeOutliner,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        fonts: PdfFontFaces = PdfFontFaces.None,
        images: PdfImageDecoder = AndroidImageDecoder(),
    ) : this(fs, store, outliner, dispatcher, PdfBoxWriter(fonts, images))

    override suspend fun publish(noteId: DocumentId): MaterializedFile = withContext(dispatcher) {
        val note = checkNotNull(store.open(noteId)) { "no note ${noteId.value} to materialize" }
        val bundle = fs.readBytes(NoteLayout.bundle(noteId))
        val path = NoteLayout.documentPdf(noteId)

        val original = originalFor(note, path)
        val plans = plansFor(note, original)

        val published = writer.publish(
            original = original,
            plan = PublishPlan(
                marker = InkstoneMarker(
                    documentId = noteId,
                    schemaVersion = note.document.schemaVersion,
                    bundleSha256 = bundle.sha256Hex(),
                    originalLength = original.size.toLong(),
                    originalSha256 = original.sha256Hex(),
                ),
                pages = plans,
                bundle = bundle,
            ),
        )

        // CLAUDE.md rule 10. The upload worker may be reading this file right now,
        // and a torn ZIP inside a PDF is the corruption class the whole design
        // exists to prevent.
        fs.writeBytesAtomically(path, published)

        MaterializedFile(
            path = path,
            sizeBytes = published.size.toLong(),
            sha256 = published.sha256Hex(),
            localRev = note.document.localRev,
        )
    }

    /**
     * The bytes every publish appends to: the imported PDF, or the generated base
     * pages of a from-scratch note (SPEC §5.2).
     */
    private suspend fun originalFor(note: OpenNote, path: String): ByteArray {
        if (!fs.exists(path)) return writer.createBase(generatedBase(note))
        val existing = fs.readBytes(path)
        val marker = PdfReader.open(existing).use { it.marker() }

        return when {
            // The normal path: chop the previous update off and start again.
            marker != null && marker.hasOriginal && marker.originalLength <= existing.size ->
                existing.copyOfRange(0, marker.originalLength.toInt())

            // A published file with no marker is one this build did not write —
            // either a pre-M4 note, or an import that has not been published yet.
            // Treating the whole file as the base is the conservative reading: it
            // never truncates into the middle of an object, and costs one update
            // section, once.
            else -> existing
        }
    }

    /** Pages a from-scratch note needs generated. Sizes and templates come from the fold. */
    private suspend fun generatedBase(note: OpenNote): List<BasePage> = note.document.livePages.map { ref ->
        val page = fold(note, ref)
        BasePage(page.size, page.background)
    }

    private suspend fun plansFor(note: OpenNote, original: ByteArray): List<PdfPagePlan> {
        val sourcePages = writer.readGeometry(original).size
        val folds = note.document.livePages.map { ref -> ref to fold(note, ref) }
        val assets = assetsFor(note, folds.map { it.second })
        return folds.map { (ref, folded) ->
            val outlines = outliner.outline(folded)
            PdfPagePlan(
                source = sourceFor(folded, sourcePages),
                pageId = ref.id,
                sortKey = ref.sortKey,
                // SPEC §6.4's density decision, made from the count that will actually be
                // written — a page whose strokes were all erased away is not dense, and M8c's
                // text boxes and images are page content rather than annotations, so they do not
                // enter into it. `PdfPagePlan.modeFor` is the single place that says so.
                mode = PdfPagePlan.modeFor(outlines),
                outlines = outlines,
                words = wordsFor(note, folded),
                objects = objectsOf(folded, assets),
            )
        }
    }

    /**
     * ⭐ SPEC §4.9.4 — an image's **bytes**, which are in the bundle's `assets/` family and never
     * in the op log.
     *
     * Read once for the whole note, and only when a live page actually holds an [ImageObject]: the
     * bundle reader has no per-name accessor, so this is `assets()` or nothing, and paying it for
     * a notebook with no pictures would be a read of every asset on every save.
     *
     * ⚠️ **A missing entry is a blank rectangle, by design and by definition** (SPEC §5.3). It is
     * what a peer's picture looks like before its bundle has arrived, and the honest response is
     * to publish the rest of the page rather than to fail the note's materialization — which would
     * stop its upload, which would stop the missing asset ever arriving.
     */
    private suspend fun assetsFor(note: OpenNote, pages: List<FoldedPage>): Map<String, ByteArray> {
        val needed = pages.any { page -> page.objects.any { it is ImageObject } }
        return if (needed) note.reader?.assets().orEmpty() else emptyMap()
    }

    /**
     * A page's text boxes and images, **in the fold's own order** — which is `(z, id)` (SPEC
     * §4.3), and is the order they have to be drawn in.
     */
    private fun objectsOf(page: FoldedPage, assets: Map<String, ByteArray>): List<PdfPageObject> =
        page.objects.mapNotNull { obj ->
            when (obj) {
                is TextBoxObject -> textBoxFor(page, obj)
                is ImageObject -> imageFor(page, obj, assets)
                else -> null
            }
        }

    /**
     * ⚠️ **The words come from the register, not from the payload.**
     *
     * `PageFolder.materialise` copies the winning `SetTextContent` onto a [TextBoxObject]'s
     * payload, so the two agree today — but the payload is first-write-wins (SPEC §4.9.2) and the
     * register is the thing the user edits. Reading the register is reading the source; reading
     * the payload is reading a copy that is only correct while one line of `materialise` says so.
     */
    private fun textBoxFor(page: FoldedPage, obj: TextBoxObject): PdfTextBox? {
        val text = page.textOf(obj.id) ?: obj.text
        if (text.isEmpty()) return null
        return PdfTextBox(
            objectId = obj.id,
            text = text,
            widthPt = obj.widthPt,
            heightPt = obj.heightPt,
            transform = obj.transform,
            format = ObjectFormat.decode(page.formatOf(obj.id)),
            style = obj.style ?: UNSTYLED,
        )
    }

    private fun imageFor(page: FoldedPage, obj: ImageObject, assets: Map<String, ByteArray>): PdfImage? {
        val bytes = assets[obj.asset]?.takeIf { it.isNotEmpty() } ?: return null
        return PdfImage(
            objectId = obj.id,
            bytes = bytes,
            widthPt = obj.widthPt,
            heightPt = obj.heightPt,
            transform = obj.transform,
            // Crop is `SetFormat`, absolute, normalised to the source image — never `SetTransform`
            // and never `SetErasedRegion` (SPEC §4.9.2).
            crop = ObjectFormat.decode(page.formatOf(obj.id)).crop,
            opacity = obj.style?.opacity ?: 1f,
        )
    }

    /**
     * ⭐ SPEC §5.5 — the invisible text layer's words, from the bundle's recognised text.
     *
     * **M4 built the machinery and wrote an empty word list; this is where M7 fills it in.**
     * The boxes are not estimated from a rasterised glyph the way an OCR sandwich must
     * estimate them — they came from the strokes that made the word, which is the thing
     * Goodnotes structurally cannot do.
     *
     * ⚠️ **The digest is checked here, at publish time, and not taken on trust.** Recognition
     * is debounced three seconds behind the pen and batched overnight (SPEC §8.3), so a page
     * can perfectly well be published between a stroke landing and the recogniser catching up.
     * Publishing the previous text then puts words into the PDF that are not on the page — and
     * because the layer is invisible, *nothing would ever look wrong*: `pdfgrep` would find a
     * word the user had rubbed out, in a file that renders correctly. An absent text layer is a
     * feature that has not run yet; a stale one is a lie with a search index built on it.
     */
    private suspend fun wordsFor(note: OpenNote, folded: FoldedPage): List<InvisibleWord> {
        val stored = note.reader?.pageText(folded.pageId) ?: return emptyList()
        val text = PageTextCodec.decode(stored) ?: return emptyList()
        if (!text.describes(PageTextCodec.inkDigestOf(folded))) return emptyList()
        return text.words.map { word ->
            InvisibleWord(
                text = word.text,
                leftPt = word.bounds.left,
                topPt = word.bounds.top,
                rightPt = word.bounds.right,
                bottomPt = word.bounds.bottom,
            )
        }
    }

    /**
     * Whether this page reuses a page of the original or needs one generating.
     *
     * A [Background.SourcePdfPage] index past the end of the base is not a crash:
     * it is what a note looks like when its source PDF was replaced by something
     * shorter, and drawing the ink on fresh paper keeps the ink.
     */
    private fun sourceFor(page: FoldedPage, sourcePages: Int): PageSource {
        val background = page.background
        return if (background is Background.SourcePdfPage && background.index < sourcePages) {
            PageSource.Existing(background.index)
        } else {
            PageSource.Generated(page.size, background)
        }
    }

    private suspend fun fold(note: OpenNote, ref: PageRef): FoldedPage {
        val loaded = store.loadPage(note, ref.id)
        return foldPage(ref.id, loaded.ops, defaultSize = loaded.size, defaultBackground = loaded.background)
    }

    private companion object {

        /**
         * What a text box with a null style publishes as.
         *
         * ⚠️ SPEC §4.9.2 requires a text box to be **created** with a non-null style, because
         * `SetStyle` cannot express *"unset"* and the first recolour would otherwise have no
         * inverse. That is the editor's invariant to keep, and this is not a second place to keep
         * it: it is what a note written by a build that got it wrong looks like when it is
         * published. Opaque black, because invisible would be the one answer that loses the text
         * without saying so.
         */
        val UNSTYLED = ObjectStyle(colorArgb = 0xFF000000.toInt(), widthPt = 0f)
    }
}
