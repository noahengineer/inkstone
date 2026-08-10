package dev.inkstone.android.pdf

import dev.inkstone.core.model.Document
import dev.inkstone.core.model.ExtractedRemote
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.RemoteBundleExtractor
import dev.inkstone.core.model.RemoteBundleVerdict
import dev.inkstone.core.storage.BundleReader
import dev.inkstone.core.storage.InkstoneMarker
import dev.inkstone.core.storage.MemoryByteSource
import dev.inkstone.core.storage.PdfOpenMode
import dev.inkstone.core.storage.detectOpenMode
import dev.inkstone.core.storage.sha256Hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SPEC §5.6 + §7.6 ⭐ — **[PdfGateway]'s observations without its actions.** Added in M6.
 *
 * The reconciler has a peer's published PDF on disk and wants two things out of it: the
 * embedded `note.inkstone`, and SPEC §5.6's verdict on whether the file is ours, ours but
 * mangled, a stranger's, or from a build newer than this one. Both need PdfBox, which stays
 * inside this module (CLAUDE.md), so the answer crosses the boundary as
 * [RemoteBundleExtractor] — the module's third public door, beside [PdfGateway] and
 * [InkstonePdfMaterializer].
 *
 * ## ⚠️ Why this is not [PdfGateway]
 *
 * [PdfGateway] answers the same three questions and then *acts on them as a user's import*,
 * which is wrong twice over for a background pull:
 *
 *  - Its Normal branch does `store.open(id)?.let { return it }` — deliberately, because a
 *    user picking a file the app already holds must not have their unmaterialized journal
 *    overwritten by an older published copy. For a pull that early return is a **silent
 *    no-op**: a note this device already has is exactly the note a peer just edited, so
 *    every remote edit to every known note would be dropped with no error anywhere. SPEC
 *    §13.2 rows 1, 5, 6, 9 and 10 would all pass green while syncing nothing.
 *  - Its Recovered branch calls `NoteStore.replaceWithRecovered`, which trashes the local
 *    note and supersedes its op log. From a WorkManager job, with no user in the loop, on
 *    the strength of a file that arrived over a network.
 *
 * So this class **writes nothing under `notes/<id>/` and never trashes anything.** The only
 * thing it writes is the caller's staging path, and the only thing it decides is which of
 * SPEC §5.6's four verdicts applies. What to do about that is the reconciler's business.
 *
 * ## ⚠️ A pulled file is never recovered
 *
 * [RemoteBundleVerdict.RECOVERED] and [RemoteBundleVerdict.TOO_NEW] are both handed back for
 * the caller to turn into SPEC §7.7 layer 3 — a conflicted copy — and neither is repaired
 * here. Recovery **replaces** an op log rather than unioning with it (SPEC §5.6 point 2) and
 * stamps the result at `highestKnownCounter + 1_000_000` (`RecoveredImport`), so folding a
 * peer's lossy recovery into this device's note would let it win every LWW register against
 * full-fidelity local data — and look like a successful sync while doing it.
 * [RemoteBundleVerdict.FOREIGN] means ignore the file entirely: a PDF with ink and no marker
 * is as likely to be somebody's Acrobat markup as one of ours (CLAUDE.md), and adopting it
 * would file a stranger's document under one of our document ids.
 */
public class PulledPdfExtractor(
    private val fs: FileSystem,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RemoteBundleExtractor {

    /**
     * @param pdfPath the downloaded file. Read, never written; never `notes/<id>/document.pdf`.
     * @param bundlePath where the embedded bundle goes, atomically or not at all.
     */
    override suspend fun extract(pdfPath: String, bundlePath: String): ExtractedRemote = withContext(dispatcher) {
        val observed = observe(pdfPath) ?: return@withContext UNREADABLE
        val verdict = verdictOf(observed.mode)
        ExtractedRemote(
            verdict = verdict,
            documentId = observed.marker?.documentId,
            schemaVersion = observed.marker?.schemaVersion ?: NO_MARKER_VERSION,
            // Written only for the one verdict that merges. A TooNew file has a
            // perfectly intact bundle and staging it would be an invitation to open it
            // (SPEC §5.8); a Recovered or Foreign file has nothing worth staging.
            bundleWritten = verdict == RemoteBundleVerdict.NORMAL &&
                observed.bundle != null &&
                stage(bundlePath, observed.bundle),
        )
    }

    // -----------------------------------------------------------------------

    /**
     * Read the file and ask `:core:storage` what it is.
     *
     * @return null when the bytes are not a PDF this build can parse at all — a truncated
     *   or torn download, which is an ordinary outcome of a network pull rather than an
     *   error. The caller sees [RemoteBundleVerdict.FOREIGN] and ignores the file; because
     *   the binding's ETag only advances after a successful merge, the next pass refetches.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    // PDDocument.load on arbitrary downloaded bytes throws IOException for a truncated file
    // and, on a file whose xref points into nothing, whatever the parser happens to hit
    // first — this codebase has already seen ArrayIndexOutOfBounds out of a mangled one.
    // Narrowing the catch here would compile and then let a corrupt download escape as a
    // crash from inside a WorkManager job. Cancellation is rethrown so a cancelled sync
    // worker is not mistaken for a foreign file.
    private suspend fun observe(pdfPath: String): Observed? = try {
        val bytes = fs.readBytes(pdfPath)
        PdfReader.open(bytes).use { reader ->
            val marker = reader.marker()
            val bundle = reader.bundle()
            Observed(
                marker = marker,
                bundle = bundle,
                mode = detectOpenMode(
                    marker = marker,
                    bundle = bundle,
                    bundleIsReadable = isReadable(marker, bundle),
                    originalBytesIntact = originalIsIntact(bytes, marker),
                    hadFlattenedPage = reader.hasFlattenedPage(),
                ),
            )
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        null
    }

    /**
     * Does the embedded bundle actually open?
     *
     * ⚠️ **The schema check comes first, and the order is the point.** `BundleReader.open`
     * does not return a state for a bundle from the future — it `require`s the schema is one
     * this build understands and **throws** (SPEC §5.8). Asking "is it readable" before
     * asking "is it from the future" would therefore turn SPEC §5.6's TooNew verdict into an
     * exception out of the middle of a WorkManager job, read as a transport failure and
     * retried with backoff forever.
     *
     * A bundle that still refuses to open counts as unreadable, which [detectOpenMode] reads
     * as [PdfOpenMode.Recovered] — layer 3, non-destructive, both versions kept.
     */
    private suspend fun isReadable(marker: InkstoneMarker?, bundle: ByteArray?): Boolean {
        if (bundle == null) return false
        if (marker != null && marker.schemaVersion > Document.SCHEMA_VERSION) return false
        return BundleReader.open(MemoryByteSource(bundle)) != null
    }

    /**
     * Does this file still begin with the base it claims to (SPEC §5.2)?
     *
     * Only ever consulted to describe a recovery's losses, but consulted honestly: the same
     * check [PdfGateway] makes, so a file that a third-party editor rewrote is reported as
     * having lost [dev.inkstone.core.storage.RecoveryLoss.PRISTINE_ORIGINAL] rather than
     * being assumed intact.
     */
    private fun originalIsIntact(bytes: ByteArray, marker: InkstoneMarker?): Boolean {
        val original = PdfReader.originalBytes(bytes, marker) ?: return marker?.hasOriginal != true
        return original.sha256Hex() == marker?.originalSha256
    }

    /**
     * Write the bundle to the caller's staging path.
     *
     * @return false when the write did not happen — a full disk, a read-only volume. The
     *   caller must never merge from a path this did not write, which is why
     *   [ExtractedRemote.bundleWritten] is a flag and not something inferred from the
     *   verdict.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    // Same argument as `observe`: this runs inside a background job, and a failed write is
    // a state the reconciler already handles (it re-downloads next pass) rather than a
    // crash. `FileSystem` does not declare what it throws, and the Android implementation's
    // failures range from IOException to an `error(...)` on a refused rename.
    private suspend fun stage(bundlePath: String, bundle: ByteArray): Boolean = try {
        fs.writeBytesAtomically(bundlePath, bundle)
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        false
    }

    /**
     * SPEC §5.6's four cases, in `:core:model`'s spelling.
     *
     * The `when` is exhaustive on purpose rather than defaulted: a fifth open mode added to
     * `:core:storage` must fail to compile here, because the alternative — falling through to
     * [RemoteBundleVerdict.FOREIGN] — would silently start ignoring a whole class of file.
     */
    private fun verdictOf(mode: PdfOpenMode): RemoteBundleVerdict = when (mode) {
        is PdfOpenMode.Normal -> RemoteBundleVerdict.NORMAL
        is PdfOpenMode.Recovered -> RemoteBundleVerdict.RECOVERED
        PdfOpenMode.Foreign -> RemoteBundleVerdict.FOREIGN
        is PdfOpenMode.TooNew -> RemoteBundleVerdict.TOO_NEW
    }

    private class Observed(val marker: InkstoneMarker?, val bundle: ByteArray?, val mode: PdfOpenMode)

    private companion object {
        /** No marker means no schema to report (SPEC §5.6 — identity lives inside the file). */
        const val NO_MARKER_VERSION = 0

        /** A file that would not parse. Indistinguishable from a stranger's, and treated the same. */
        val UNREADABLE = ExtractedRemote(
            verdict = RemoteBundleVerdict.FOREIGN,
            documentId = null,
            schemaVersion = NO_MARKER_VERSION,
            bundleWritten = false,
        )
    }
}
