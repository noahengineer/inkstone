package dev.inkstone.app.sync.matrix

import dev.inkstone.core.database.library.LibraryIndex
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.CredentialStore
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.DocumentMaterializer
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.MaterializedFile
import dev.inkstone.core.model.NextcloudCredential
import dev.inkstone.core.model.NoteSummary
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.StrokeOutline
import dev.inkstone.core.model.StrokeOutliner
import dev.inkstone.core.model.WorkScheduler
import dev.inkstone.core.storage.FileByteSource
import dev.inkstone.core.storage.MergeReport
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.storage.NoteMerge
import dev.inkstone.core.storage.NoteSession
import dev.inkstone.core.storage.NoteStore
import dev.inkstone.core.storage.NoteTrash
import dev.inkstone.core.storage.conflictedCopyOf
import dev.inkstone.core.sync.ConflictedCopy
import dev.inkstone.core.sync.MergeCommit
import dev.inkstone.core.sync.RemoteInstall
import dev.inkstone.core.sync.RemoteMergeGate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes

/*
 * One device's worth of platform, for SPEC §13.2's matrix.
 *
 * Everything here is either the real production class or a stand-in whose *shape* is dictated
 * by a rule the matrix is about. The two stand-ins worth arguing over are both in this file:
 *
 *  - [TempFileSystem] is a real filesystem on a real temp directory, not an in-memory map,
 *    because the pipe under test writes a PDF, reads it back with PdfBox, and reads *ranges*
 *    out of a zip's central directory. An in-memory map would pass a torn write that a real
 *    `move` would not.
 *  - [MatrixGate] does what `NoteSessionHost` does, in the same order, and is not
 *    `NoteSessionHost` — see its KDoc for exactly which claim that costs.
 *
 * Block comment, not KDoc: a file-level KDoc attaches to no declaration.
 */

/** A settable clock. `Instant` is display-only here; ordering is Lamport (CLAUDE.md). */
internal class MatrixClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowEpochMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}

/**
 * A real filesystem rooted at a temp directory.
 *
 * `writeBytesAtomically` really writes a temp file and really renames it, because CLAUDE.md
 * rule 10 is a claim about `move` and an in-memory fake cannot make it. The **fixed** temp name
 * is deliberate: `AndroidFileSystem` uses one too (`"${name}.tmp"`), and the risk that creates —
 * two writers to one path interleaving into one temp file and then renaming a torn PDF into
 * place — is a risk the matrix is supposed to be able to reproduce, not one it should paper over.
 */
internal class TempFileSystem(private val root: Path) : FileSystem {

    private fun resolve(path: String): Path = root.resolve(path)

    override suspend fun exists(path: String): Boolean = resolve(path).exists()

    override suspend fun size(path: String): Long = resolve(path).let { if (it.exists()) it.fileSize() else -1L }

    override suspend fun readBytes(path: String): ByteArray = resolve(path).readBytes()

    override suspend fun readRange(path: String, offset: Long, length: Int): ByteArray {
        val file = resolve(path)
        if (!file.exists()) return ByteArray(0)
        Files.newByteChannel(file, StandardOpenOption.READ).use { channel ->
            if (offset >= channel.size()) return ByteArray(0)
            channel.position(offset)
            val available = (channel.size() - offset).coerceAtMost(length.toLong()).toInt()
            val buffer = java.nio.ByteBuffer.allocate(available)
            while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
            return buffer.array().copyOf(buffer.position())
        }
    }

    override suspend fun writeBytesAtomically(path: String, bytes: ByteArray) {
        val destination = resolve(path)
        destination.parent?.createDirectories()
        val temp = destination.resolveSibling("${destination.fileName}.tmp")
        Files.write(temp, bytes)
        Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING)
    }

    override suspend fun appendBytes(path: String, bytes: ByteArray) {
        val file = resolve(path)
        file.parent?.createDirectories()
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    override suspend fun createDirectories(path: String) {
        resolve(path).createDirectories()
    }

    override suspend fun move(from: String, to: String) {
        val source = resolve(from)
        if (!source.exists()) return
        val destination = resolve(to)
        destination.parent?.createDirectories()
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    override suspend fun delete(path: String) {
        val target = resolve(path)
        if (target.exists()) target.deleteRecursively()
    }

    override suspend fun list(path: String): List<String> {
        val directory = resolve(path)
        if (!directory.exists() || !directory.isDirectory()) return emptyList()
        return directory.listDirectoryEntries().map { it.fileName.toString() }
    }
}

/** One credential, in memory. Keystore AES-GCM is `:app`'s and is not what the matrix tests. */
internal class MatrixCredentials(private val credential: NextcloudCredential) : CredentialStore {

    private val stored = LinkedHashMap<String, NextcloudCredential>()

    init {
        stored[MATRIX_ACCOUNT_ID] = credential
    }

    override suspend fun put(accountId: String, credential: NextcloudCredential) {
        stored[accountId] = credential
    }

    override suspend fun get(accountId: String): NextcloudCredential? = stored[accountId]

    override suspend fun remove(accountId: String) {
        stored.remove(accountId)
    }
}

/** Counts what would have been scheduled. Row 13 asserts a 412 asks for a targeted reconcile. */
internal class CountingScheduler : WorkScheduler {

    var drains: Int = 0
        private set

    var reconciles: Int = 0
        private set

    val reconciledNotes: MutableList<DocumentId> = ArrayList()

    override fun enqueueOutboxDrain() {
        drains++
    }

    override fun enqueueMaterialization(noteId: DocumentId) = Unit

    override fun enqueueReconcile() {
        reconciles++
    }

    override fun enqueueReconcile(noteId: DocumentId) {
        reconciles++
        reconciledNotes += noteId
    }
}

/**
 * A [StrokeOutliner] with no Skia in it.
 *
 * Tessellation and `Path.op(DIFFERENCE)` are `:android:ink`'s and need a device; what the matrix
 * needs is that the published PDF is a real PDF with real `/Ink` annotations carrying real
 * `/NM` names, because that is what makes the round trip representative. A two-point centreline
 * inside a four-point quad is the smallest thing that is still an annotation.
 */
internal object MatrixOutliner : StrokeOutliner {
    override suspend fun outline(page: FoldedPage): List<StrokeOutline> = page.objects.mapIndexed { index, obj ->
        val y = MARGIN_PT + index * ROW_PT
        StrokeOutline(
            objectId = obj.id,
            contours = listOf(
                listOf(
                    PagePoint(MARGIN_PT, y),
                    PagePoint(MARGIN_PT + LENGTH_PT, y),
                    PagePoint(MARGIN_PT + LENGTH_PT, y + WIDTH_PT),
                    PagePoint(MARGIN_PT, y + WIDTH_PT),
                ),
            ),
            centreline = listOf(PagePoint(MARGIN_PT, y), PagePoint(MARGIN_PT + LENGTH_PT, y)),
            colorArgb = INK_BLACK,
            widthPt = WIDTH_PT,
        )
    }

    private const val MARGIN_PT = 72f
    private const val ROW_PT = 12f
    private const val LENGTH_PT = 200f
    private const val WIDTH_PT = 2f
    private const val INK_BLACK = 0xFF000000.toInt()
}

/**
 * ⭐ [RemoteMergeGate], performing exactly `NoteSessionHost`'s steps in exactly its order.
 *
 * ## ⚠️ Why this is not `NoteSessionHost` itself, and what that costs
 *
 * `NoteSessionHost` takes a `LibraryRepository`, which takes a `PdfGateway`, a `ThumbnailCache`
 * and `androidx.compose.ui.graphics.ImageBitmap`. None of those can be built in a JVM unit test
 * without Robolectric, and adding Robolectric to run a merge matrix would mean the matrix's
 * failures could be Robolectric's.
 *
 * So this gate makes the same three calls in the same order — `store.open`,
 * `NoteMerge.mergeBundle`, `session.materialize(carried, carriedManifest)` — against the real
 * `NoteStore` and the real `LibraryIndex`, and every SPEC §7.7 claim the matrix makes is
 * therefore about the real merge.
 *
 * **What it does not cover**, stated plainly rather than implied: `NoteSessionHost`'s own
 * additions — the per-note `Mutex`, the live-editor hand-off for merges and for `MaterializeWorker`,
 * and thumbnail invalidation in both tiers. The first two are structural (a `ConcurrentHashMap` of
 * mutexes and a registry lookup) and the third is `LibraryRepository.notePulled`. The *editor* half
 * of the hand-off is covered — `NoteEditorRemoteOpsTest` and `NoteEditorConcurrencyTest` drive
 * `NoteEditor.mergeRemoteBundle`, which is the method the registry hands the merge to — but the
 * registry lookup itself is exercised nowhere; that is the honest gap in M6's automated coverage
 * and it is in the
 * report.
 */
internal class MatrixGate(
    private val fs: FileSystem,
    private val clock: Clock,
    private val store: NoteStore,
    private val ids: IdFactory,
    private val index: LibraryIndex,
    private val deviceId: suspend () -> String,
) : RemoteMergeGate {

    private val trash = NoteTrash(fs, clock)

    /** Every merge this gate performed, for the rows that count them. */
    val merges: MutableList<MergeReport> = ArrayList()

    /** Notes trashed because their remote file was gone. Row 14 asserts on the trash, not this. */
    val trashedRemotely: MutableList<DocumentId> = ArrayList()

    override suspend fun merge(noteId: DocumentId, bundlePath: String): MergeCommit {
        val note = store.open(noteId) ?: return unreadable(noteId)
        val session = NoteSession.open(store, ids, clock, note, deviceId())
        // `mergeBundle`, exactly as the host does: it is what tells a schema from the future
        // (`MergeReport.TooNew`) from a file that is not a bundle at all (`DifferentDocument`).
        // A `runCatching { BundleReader.open(…) }` here would answer the second for both.
        val report = NoteMerge.mergeBundle(session, FileByteSource(fs, bundlePath))
        merges += report
        if (report is MergeReport.Merged) {
            // ⚠️ Both arguments, always — dropping them is the invisible deletion of a peer's
            // recognised text and of everything a newer build added (SPEC §5.8).
            session.materialize(report.carried, report.carriedManifest)
            index.recordSave(noteId, session.document.localRev, session.pageCount, clock.nowEpochMillis())
            // `LibraryRepository.notePulled`'s title half — SPEC §7.7 layer 2's *reader*. `recordSave`
            // has no title column, so without this a peer's rename lands in the bundle and the library
            // tile keeps the old string for good. The **resolved** title, so the local one winning
            // writes the local one back.
            val resolved = session.document.title
            if (resolved.isNotEmpty() && index.note(noteId)?.title != resolved) {
                index.renameNote(noteId, resolved, clock.nowEpochMillis())
            }
        }
        return MergeCommit(report, session.document.localRev)
    }

    override suspend fun install(install: RemoteInstall): DocumentId? {
        val noteId = install.noteId
        val title = titleOf(install.remotePath)
        if (fs.exists(NoteLayout.noteDir(noteId))) {
            val existing = store.open(noteId) ?: return null
            record(noteId, existing.document.title.ifEmpty { title }, install.folderId, existing.document.localRev)
            return noteId
        }
        val bytes = runCatching { fs.readBytes(install.bundlePath) }.getOrNull() ?: return null
        val installed = store.installBundle(noteId, bytes) ?: return null
        record(noteId, installed.document.title.ifEmpty { title }, install.folderId, installed.document.localRev)
        return noteId
    }

    override suspend fun conflictedCopy(copy: ConflictedCopy): DocumentId? {
        val bundlePath = copy.bundlePath ?: return null
        val document = store.conflictedCopyOf(
            remoteBundle = FileByteSource(fs, bundlePath),
            // `copy.title`, as the host does: the reconciler stamped it once, from one reading of
            // the clock, and is using the same string for the remote path.
            title = copy.title,
            deviceId = deviceId(),
            ids = ids,
            clock = clock,
        ) ?: return null
        record(document.id, document.title, copy.folderId, document.localRev)
        return document.id
    }

    /**
     * @return always true. `NoteSessionHost` answers false while a live editor holds the note (it
     *   would otherwise be unrestorable — see that class), and there are no editors in a matrix run.
     */
    override suspend fun trashRemotelyDeleted(noteId: DocumentId, reason: String): Boolean {
        val path = trash.trash(noteId)
        index.trashNote(noteId, path, clock.nowEpochMillis())
        trashedRemotely += noteId
        return true
    }

    override suspend fun noteFolderChanged(noteId: DocumentId, folderId: FolderId) {
        index.moveNote(noteId, folderId, clock.nowEpochMillis())
    }

    private suspend fun record(id: DocumentId, title: String, folderId: FolderId?, localRev: Long) {
        val now = clock.nowEpochMillis()
        if (index.note(id) == null) {
            index.insertNote(
                NoteSummary(
                    id = id,
                    title = title,
                    folderId = folderId,
                    localRev = localRev,
                    createdEpochMillis = now,
                    modifiedEpochMillis = now,
                ),
            )
        } else {
            index.recordSave(id, localRev, 1, now)
        }
    }

    /** `NoteSessionHost`'s answer for a bundle that will not open: layer 3, ETag left alone. */
    private fun unreadable(noteId: DocumentId) = MergeCommit(MergeReport.DifferentDocument(noteId), 0L)

    private fun titleOf(remotePath: String) = remotePath.substringAfterLast('/').removeSuffix(".pdf")
}

/**
 * A materializer that performs a real local edit **after** producing the bytes.
 *
 * SPEC §7.6's mid-flight guard (row 18): the 2xx must not set `SYNCED`, because `local_rev` moved
 * between the read and the response. A fake server cannot produce this — the race is entirely on
 * this side of the socket — so the seam is here, where the bytes are made.
 */
internal class EditDuringUploadMaterializer(
    private val delegate: DocumentMaterializer,
    private val onBytesProduced: suspend (DocumentId) -> Unit,
) : DocumentMaterializer {

    var fired: Boolean = false
        private set

    override suspend fun materialize(noteId: DocumentId): MaterializedFile {
        val file = delegate.materialize(noteId)
        if (!fired) {
            fired = true
            onBytesProduced(noteId)
        }
        return file
    }
}

/** The one account every device in the matrix binds to. */
internal const val MATRIX_ACCOUNT_ID: String = "matrix-account"
