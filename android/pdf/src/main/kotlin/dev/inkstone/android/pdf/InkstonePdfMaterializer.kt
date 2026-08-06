package dev.inkstone.android.pdf

import dev.inkstone.core.model.AnnotationDensity
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.DocumentMaterializer
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.MaterializedFile
import dev.inkstone.core.model.PageRef
import dev.inkstone.core.model.StrokeOutliner
import dev.inkstone.core.model.foldPage
import dev.inkstone.core.storage.InkstoneMarker
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.storage.NoteStore
import dev.inkstone.core.storage.OpenNote
import dev.inkstone.core.storage.sha256Hex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SPEC §5.1/§5.2 — **materialization**: rewrite `document.pdf` from the bundle.
 *
 * This is `:core:model`'s [DocumentMaterializer], and it is the only thing about
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
) : DocumentMaterializer {

    /**
     * The only constructor callers outside this module can reach.
     *
     * [PdfWriter] stays internal deliberately: SPEC §6.3 puts the interface here so
     * PdfBox can be replaced, not so `:app` can choose a PDF backend.
     */
    public constructor(
        fs: FileSystem,
        store: NoteStore,
        outliner: StrokeOutliner,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(fs, store, outliner, dispatcher, PdfBoxWriter())

    override suspend fun materialize(noteId: DocumentId): MaterializedFile = withContext(dispatcher) {
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
        return note.document.livePages.map { ref ->
            val folded = fold(note, ref)
            val outlines = outliner.outline(folded)
            PdfPagePlan(
                source = sourceFor(folded, sourcePages),
                pageId = ref.id,
                sortKey = ref.sortKey,
                // SPEC §6.4's density decision, made from the count that will actually
                // be written — a page whose strokes were all erased away is not dense.
                mode = AnnotationDensity.modeFor(outlines.count { !it.isEmpty }),
                outlines = outlines,
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
}
