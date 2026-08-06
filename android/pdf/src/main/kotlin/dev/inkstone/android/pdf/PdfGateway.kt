package dev.inkstone.android.pdf

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.StrokeRebuilder
import dev.inkstone.core.storage.BundleReader
import dev.inkstone.core.storage.InkstoneMarker
import dev.inkstone.core.storage.MemoryByteSource
import dev.inkstone.core.storage.NewPage
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.storage.NoteStore
import dev.inkstone.core.storage.NoteTrash
import dev.inkstone.core.storage.OpenNote
import dev.inkstone.core.storage.PdfOpenMode
import dev.inkstone.core.storage.RecoveredImport
import dev.inkstone.core.storage.RecoveredNote
import dev.inkstone.core.storage.RecoveryIds
import dev.inkstone.core.storage.RecoveryLoss
import dev.inkstone.core.storage.detectOpenMode
import dev.inkstone.core.storage.sha256Hex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SPEC §5.6 ⭐ — **the three-way open**, and the only door a PDF gets in through.
 *
 * | Marker | Bundle | What happens |
 * |---|---|---|
 * | ✅ | ✅, sha matches | **Normal** — install the bundle, keep the `documentId`. |
 * | ✅ | ❌ or sha mismatch | **Recovered** — rebuild from `/Ink`, **replace**, warn. |
 * | ❌ | — | **Foreign** — a new note with a new `documentId`. |
 * | ✅ (newer schema) | — | **TooNew** — refuse, and above all do not re-save. |
 *
 * The decision itself is `:core:storage`'s [detectOpenMode] and is unit-tested
 * without a PDF in sight; this class supplies it with the three observations and
 * carries out whatever it says.
 *
 * ## Why the Foreign branch matters as much as the Recovered one
 *
 * *"A PDF with ink annotations and no embedded bundle is not necessarily ours — it
 * might be a stranger's Acrobat markup"* (CLAUDE.md). Getting that branch wrong in
 * the generous direction is worse than refusing to open the file: a stranger's
 * document adopted under one of our document ids would sync, and would then start
 * merging with whatever else has that id.
 */
public class PdfGateway(
    private val fs: FileSystem,
    private val store: NoteStore,
    private val trash: NoteTrash,
    private val ids: IdFactory,
    private val clock: Clock,
    private val rebuilder: StrokeRebuilder,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Open a PDF the user picked, whatever it turns out to be.
     *
     * @param deviceId this device, for the ops a recovery writes.
     * @param highestKnownCounter the highest Lamport counter this device can still
     *   account for, for the recovery bump (SPEC §5.6 point 5, [RecoveredImport]).
     *   Zero is a legitimate answer.
     */
    public suspend fun open(
        bytes: ByteArray,
        title: String,
        deviceId: String,
        highestKnownCounter: Long = 0L,
    ): PdfOpenResult = withContext(dispatcher) {
        PdfReader.open(bytes).use { reader ->
            val marker = reader.marker()
            val bundle = reader.bundle()
            val readable = bundle != null && BundleReader.open(MemoryByteSource(bundle)) != null

            when (
                val mode = detectOpenMode(
                    marker = marker,
                    bundle = bundle,
                    bundleIsReadable = readable,
                    originalBytesIntact = originalIsIntact(bytes, marker),
                    hadFlattenedPage = reader.hasFlattenedPage(),
                )
            ) {
                is PdfOpenMode.Normal -> PdfOpenResult.Opened(
                    note = installNormal(bytes, mode.marker, requireNotNull(bundle)),
                    mode = mode,
                )

                is PdfOpenMode.Recovered -> recover(bytes, reader, mode, title, deviceId, highestKnownCounter)

                PdfOpenMode.Foreign -> PdfOpenResult.Opened(
                    note = importForeign(bytes, reader, title, deviceId),
                    mode = PdfOpenMode.Foreign,
                )

                is PdfOpenMode.TooNew -> PdfOpenResult.Refused(mode)
            }
        }
    }

    // -----------------------------------------------------------------------

    /**
     * SPEC §6.2 — *"original bytes become the base of `document.pdf`. Never rewritten."*
     *
     * The imported file is written **as it arrived**, before a single stroke exists.
     * Materialization then appends to it and never touches those bytes again, which
     * is what makes "give me my original PDF back" a `copyOfRange` rather than a
     * reconstruction (SPEC §5.2).
     */
    private suspend fun importForeign(bytes: ByteArray, reader: PdfReader, title: String, deviceId: String): OpenNote {
        val geometries = reader.geometries()
        require(geometries.isNotEmpty()) { "a PDF with no pages is not a document to import" }
        val note = store.create(
            title = title,
            deviceId = deviceId,
            // SPEC §4.4 rule 3 — CropBox with /Rotate applied. The one place in the
            // app that conversion happens; everything after this reads PageSize.
            pages = geometries.mapIndexed { index, geometry ->
                NewPage(geometry.pageSize, Background.SourcePdfPage(index))
            },
        )
        fs.writeBytesAtomically(NoteLayout.documentPdf(note.document.id), bytes)
        return note
    }

    /** Ours, intact: unpack the bundle it carries and keep the identity it claims. */
    private suspend fun installNormal(bytes: ByteArray, marker: InkstoneMarker, bundle: ByteArray): OpenNote {
        val id = marker.documentId
        // Already here: this is the same note arriving twice, not a second one. The
        // local copy may hold an unmaterialized journal, and overwriting it with the
        // published file's older state would throw that work away.
        store.open(id)?.let { return it }

        val note = checkNotNull(store.installBundle(id, bundle)) {
            "bundle in ${id.value} verified its hash and then did not open"
        }
        fs.writeBytesAtomically(NoteLayout.documentPdf(id), bytes)
        return note
    }

    /**
     * SPEC §5.6 — **replace, never union.**
     *
     * The pre-recovery note goes to trash first (point 3), the reconstruction
     * supersedes the previous log rather than merging with it (point 2), stroke and
     * page identity are reused wherever the file still carries them (point 1, and
     * M4's page-identity keys), and the Lamport counter is bumped past everything
     * this device knows about (point 5).
     *
     * What it cannot do is claim to be lossless, so the result carries the list of
     * what went (point 4) — including the one loss the user may have to act on, an
     * object with no recoverable identity.
     */
    private suspend fun recover(
        bytes: ByteArray,
        reader: PdfReader,
        mode: PdfOpenMode.Recovered,
        title: String,
        deviceId: String,
        highestKnownCounter: Long,
    ): PdfOpenResult {
        val pages = reader.recoveredPages(rebuilder)
        if (pages.isEmpty()) return PdfOpenResult.Refused(PdfOpenMode.Foreign)

        val recovered: RecoveredNote = RecoveredImport.rebuild(
            marker = mode.marker,
            pages = pages,
            title = title,
            deviceId = deviceId,
            nowEpochMillis = clock.nowEpochMillis(),
            highestKnownCounter = highestKnownCounter,
            ids = RecoveryIds(ids::newOpId, ids::newPageId, ids::newObjectId),
        )

        val note = store.replaceWithRecovered(trash, recovered)
        // The PDF is written back as it arrived, not re-published. A recovery has
        // already lost the op history; rewriting the file here would be the moment
        // the *original* went too, and the loss report would be describing a state
        // this method had just caused.
        fs.writeBytesAtomically(NoteLayout.documentPdf(note.document.id), bytes)

        val unidentifiedPages = pages.count { it.pageId == null }
        val losses = buildSet {
            addAll(mode.losses)
            if (recovered.unidentifiedStrokes > 0 || unidentifiedPages > 0) add(RecoveryLoss.UNIDENTIFIED_OBJECTS)
        }
        return PdfOpenResult.Opened(
            note = note,
            mode = mode.copy(losses = losses),
            unidentifiedStrokes = recovered.unidentifiedStrokes,
            unidentifiedPages = unidentifiedPages,
        )
    }

    /**
     * Does this file still begin with the base it claims to?
     *
     * Verified against [InkstoneMarker.originalSha256] rather than assumed, because
     * *"the original PDF bytes are recoverable byte-identically"* is an acceptance
     * criterion and an unchecked invariant is a comment. A file some other editor
     * rewrote fails here, and [RecoveryLoss.PRISTINE_ORIGINAL] is then a fact.
     */
    private fun originalIsIntact(bytes: ByteArray, marker: InkstoneMarker?): Boolean {
        val original = PdfReader.originalBytes(bytes, marker) ?: return marker?.hasOriginal != true
        return original.sha256Hex() == marker?.originalSha256
    }
}

/** What [PdfGateway.open] found, and what it did about it. */
public sealed interface PdfOpenResult {

    /**
     * @property unidentifiedStrokes non-zero means those strokes will appear twice
     *   if a peer still holds the originals. The UI must say so — this is the one
     *   entry in a recovery report that is a decision and not a condolence.
     */
    public data class Opened(
        val note: OpenNote,
        val mode: PdfOpenMode,
        val unidentifiedStrokes: Int = 0,
        val unidentifiedPages: Int = 0,
    ) : PdfOpenResult {
        public val documentId: DocumentId get() = note.document.id
    }

    /** A file from a newer schema, or one with nothing recoverable in it. Not opened, not re-saved. */
    public data class Refused(val mode: PdfOpenMode) : PdfOpenResult
}
