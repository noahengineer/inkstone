package dev.inkstone.core.model

/*
 * SPEC §2.2 — the capabilities :core:* cannot implement itself.
 *
 * Declared here, implemented in :app. This is the escape hatch that lets core
 * stay platform-free without becoming useless: when core needs something only
 * the platform can do, it gets an interface, not an androidx import.
 *
 * Every member below is a stub in M0. The owning milestone is named on each.
 *
 * Block comment, not KDoc — a file-level KDoc attached to no declaration is a
 * "dangling toplevel KDoc" and ktlint cannot auto-correct it.
 */

/**
 * SPEC §5.2 — `:core:sync` needs a PDF to upload, and only `:android:pdf` can
 * produce one. Implemented in M4.
 *
 * ⭐ **This is the *gated* materialize, and it has exactly one implementation:
 * `:app`'s `NoteSessionHost`.** That is the whole point of the type. A note the
 * user has open is being written by its editor, and publishing it from anywhere
 * else means two writers over one bundle — so the host holds the note's mutex and
 * hands the work to the live editor when there is one.
 *
 * It used to be [PdfPublisher]'s job as well, and `OutboxDrainer` was wired
 * straight to the raw publisher. That is the bug M6's gate-5 run found: for an
 * **open** note the upload path published from outside the gate, then recorded
 * `lastSyncedRev` for bytes that did not contain what the editor held — after
 * which `SyncEngine.noteChanged` reads `localRev <= lastSyncedRev` and enqueues
 * nothing, ever. The device goes silent for that work while every indicator reads
 * healthy. `MaterializeWorker` already knew to route through the host; the drainer
 * did not, and a KDoc saying so was not enough.
 *
 * Splitting the two apart is what makes the wrong wiring unrepresentable rather
 * than remembered: there is nothing else a `DocumentMaterializer` parameter can be
 * given. Same move as deleting `NoteSession.apply`'s `undoable` default.
 */
public fun interface DocumentMaterializer {
    public suspend fun materialize(noteId: DocumentId): MaterializedFile
}

/**
 * Writes `document.pdf` from the bundle on disk (SPEC §5.2), with **no idea whether
 * anyone else is writing that note**.
 *
 * ⚠️ **Two callers, and no more:** `NoteSessionHost`, which holds the note's mutex
 * and has already established that no editor holds it, and `NoteEditor`, which *is*
 * that editor and is publishing its own session's bundle. Anything else wanting a
 * note published wants [DocumentMaterializer].
 */
public fun interface PdfPublisher {
    public suspend fun publish(noteId: DocumentId): MaterializedFile
}

/**
 * @property localRev the note's `local_rev` at the moment these bytes were
 *   produced. SPEC §7.6: the upload is marked SYNCED only if `local_rev` still
 *   matches afterwards — otherwise the note changed mid-flight and that work
 *   becomes invisible to sync forever.
 */
public data class MaterializedFile(val path: String, val sizeBytes: Long, val sha256: String, val localRev: Long)

/**
 * SPEC §5.6 + §7.6 ⭐ — **[DocumentMaterializer] read backwards.** Implemented in M6 by
 * `:android:pdf`.
 *
 * The reconciler has downloaded a peer's published PDF and needs two things out of it: the
 * embedded `note.inkstone`, and SPEC §5.6's open decision about whether that file is ours,
 * ours-but-mangled, a stranger's, or from a build newer than this one. Both require parsing
 * a PDF, and PdfBox stays inside `:android:pdf` (CLAUDE.md — *"third-party types don't leak
 * across module boundaries"*). So the same shape as materialization: an interface here, the
 * one implementation over there, nothing of the library's vocabulary in between.
 *
 * ## Paths, not bytes
 *
 * Both parameters are paths on the [FileSystem] the app already uses, and the result carries
 * no bytes either. A 40 MB lecture must not cross this boundary in memory: the caller runs
 * inside a WorkManager job on the 4 GB device SPEC §10 names and is already holding the
 * downloaded file, so handing the bundle back as a `ByteArray` would put a second copy of
 * the largest object in the app beside the first.
 *
 * ## Why the verdict is a copy of SPEC §5.6's and not the type itself
 *
 * [RemoteBundleVerdict] mirrors `:core:storage`'s `PdfOpenMode` case for case because
 * `:core:model` cannot depend on `:core:storage` — the dependency runs the other way, and
 * always will. The mapping between the two lives in the `:android:pdf` implementation, in
 * one place, so this is a second *spelling* of the decision rather than a second decision.
 */
public fun interface RemoteBundleExtractor {

    /**
     * @param pdfPath the downloaded published PDF, already on disk. **Never
     *   `notes/<id>/document.pdf`** — the download lands in its own staging path so that a
     *   materialization running concurrently on the same note cannot tear it (CLAUDE.md
     *   rule 10; `AndroidFileSystem`'s temp name is derived from the destination, so two
     *   writers to one path share one temp file).
     * @param bundlePath where to write the extracted `note.inkstone`. Written atomically or
     *   not at all — ask [ExtractedRemote.bundleWritten], never assume.
     * @return what was found. Does **not** throw for a file it cannot make sense of: a
     *   corrupt or truncated download is an ordinary outcome of a network pull, and this
     *   codebase uses result types across module boundaries and exceptions only for genuine
     *   programming errors. Anything unrecognisable is [RemoteBundleVerdict.FOREIGN], which
     *   the reconciler already ignores — and because the binding's ETag is only advanced
     *   after a successful merge, the next pass fetches the file again.
     */
    public suspend fun extract(pdfPath: String, bundlePath: String): ExtractedRemote
}

/**
 * What [RemoteBundleExtractor] found in a downloaded file.
 *
 * @property verdict SPEC §5.6's open decision, and the only field the reconciler branches
 *   on. All four cases are reachable from a perfectly healthy server, which is why none of
 *   them may be treated as an error.
 * @property documentId the `documentId` from the XMP marker, or null when the file carries
 *   no marker. **Never derived from the filename or the remote path** — SPEC §5.6's whole
 *   argument is that identity lives inside the file, and CLAUDE.md forbids deriving identity
 *   from a path. A note renamed on the server is the same note; two notes can share a title.
 * @property schemaVersion the marker's `schemaVersion`, or 0 when there is no marker. Kept
 *   even for a file this build must refuse, because it is the difference between an
 *   unactionable *"written by a newer build"* and *"written against bundle schema 3; this
 *   build reads 1"* on the attention row (SPEC §7.7).
 * @property bundleWritten true when [RemoteBundleExtractor.extract]'s `bundlePath` now holds
 *   the embedded bundle. A flag rather than something inferred from [verdict], because the
 *   two are different questions: a [RemoteBundleVerdict.TOO_NEW] file has a perfectly intact
 *   bundle this build must not open, and a [RemoteBundleVerdict.NORMAL] extraction can still
 *   fail on a full disk. **Never merge from a path this did not write.**
 */
public data class ExtractedRemote(
    val verdict: RemoteBundleVerdict,
    val documentId: DocumentId?,
    val schemaVersion: Int,
    val bundleWritten: Boolean,
)

/**
 * SPEC §5.6's four-way open decision, spelled for `:core:model`.
 *
 * Case for case the same set as `:core:storage`'s `PdfOpenMode`; see [RemoteBundleExtractor]
 * for why it is restated rather than imported. What is deliberately *not* carried across is
 * `PdfOpenMode.Recovered`'s `RecoveryReason` and `RecoveryLoss` set: the reconciler's answer
 * to [RECOVERED] is the same whatever the reason, and a pulled file is never recovered
 * automatically (see below).
 */
public enum class RemoteBundleVerdict {

    /** Marker and bundle both present and consistent. The only case that merges. */
    NORMAL,

    /**
     * Ours, but the bundle is missing or does not hash to what the marker claims.
     *
     * SPEC §7.7 layer 3, always — **a pulled file is never recovered automatically.**
     * Recovery replaces the op log rather than unioning with it and deliberately stamps
     * everything past every clock it has seen, so a peer's lossy recovery folded into this
     * device's note would win every LWW register against full-fidelity local data, and look
     * like a successful sync while doing it. Keep both copies instead.
     */
    RECOVERED,

    /**
     * No marker. Not ours, and not adopted.
     *
     * A PDF with ink annotations and no marker is just as likely to be somebody's Acrobat
     * markup that happens to live in the notes folder (SPEC §5.6). Adopting it would file a
     * stranger's document under one of our document ids and then start merging into it.
     */
    FOREIGN,

    /**
     * Ours, written against a bundle schema this build does not understand.
     *
     * SPEC §5.8 — do not open it and above all do not re-save it. Layer 3, with
     * [ExtractedRemote.schemaVersion] as the detail.
     */
    TOO_NEW,
}

/**
 * SPEC §5.7 — renders one folded page to a thumbnail image. Implemented in M3 by
 * `:android:ink`, because drawing a stroke needs `androidx.ink` and a `Canvas`.
 *
 * Returns encoded image bytes (WebP) rather than a bitmap: a bitmap is a platform type,
 * and the only thing the caller does with the result is write it to the cache directory.
 *
 * **Called off the main thread, always** (CLAUDE.md rule 7). A 500-note library is 500 of
 * these, and the frame budget has no room for one.
 */
public fun interface PageThumbnailRenderer {
    /**
     * @param noteId which note [page] belongs to. Needed since M4: a page whose background
     *   is a [Background.SourcePdfPage] has its paper inside that note's `document.pdf`,
     *   and a [FoldedPage] carries only the page *index*, not the file it indexes into.
     *   Without this the library drew imported notes as blank paper with ink floating on it.
     * @param widthPx the thumbnail's width. Height follows from the page's aspect ratio,
     *   so a thumbnail is never letterboxed and never distorted.
     * @return the encoded image, or null when the page could not be rendered — a decode
     *   failure is a missing thumbnail, never a crash in the library.
     */
    public suspend fun render(noteId: DocumentId, page: FoldedPage, widthPx: Int): ByteArray?
}

/**
 * SPEC §7.1 — [loginName] and [uid] are not interchangeable.
 *
 * [loginName] authenticates (Basic auth); [uid] is the WebDAV path segment,
 * fetched from `/ocs/v2.php/cloud/user` → `ocs.data.id`. Using `loginName` as
 * the path works on some instances and 404s on others, and SPEC §0.1 #3 calls
 * it the single most likely bug in the project — so both are stored, separately,
 * and the type makes you choose.
 */
public data class NextcloudCredential(
    val serverUrl: String,
    val loginName: String,
    val uid: String,
    val appPassword: String,
)

/**
 * SPEC §14 — AES-GCM with the key in the Android Keystore, written to a private
 * file. **Not** `EncryptedSharedPreferences`, which Google deprecated in 2025.
 * Implemented in M5.
 */
public interface CredentialStore {
    public suspend fun put(accountId: String, credential: NextcloudCredential)

    public suspend fun get(accountId: String): NextcloudCredential?

    public suspend fun remove(accountId: String)
}

/**
 * SPEC §2.4 — sync runs under WorkManager only, because it must survive process
 * death. Implemented in M5.
 */
public interface WorkScheduler {
    /** Ask the outbox drainer to run when constraints allow (SPEC §7.6). */
    public fun enqueueOutboxDrain()

    /** Rewrite `document.pdf` off the ink path (SPEC §5.1). */
    public fun enqueueMaterialization(noteId: DocumentId)

    /**
     * SPEC §7.6 ⭐ — **the pull side.** Look for remote changes on every usable account.
     * Added in M6.
     *
     * The cheap case is deliberately the common one: one `PROPFIND Depth:0` against the
     * account root, and if its ETag matches the stored cursor, nothing else at all (SPEC
     * §7.4 rule 1). So calling this often is not expensive, and there is no "am I online"
     * question to ask first — the platform's own network constraint answers it, which is what
     * keeps airplane-mode editing free of requests.
     *
     * ## Why the *periodic* registration is not on this interface
     *
     * The reconcile equivalent of `ensurePeriodicDrain` stays a concrete method on
     * `WorkManagerScheduler`, following that precedent exactly. Its period is fifteen
     * minutes, and that number is WorkManager's floor rather than a decision — a shorter one
     * is silently clamped. Declaring it here would move an Android scheduling policy into
     * `commonMain`, where the constant is unexplainable and an iOS implementation would be
     * asked to honour a constraint its platform does not have. `:core:*` says *what* work is
     * owed; *when* is the platform's business (SPEC §2.2).
     *
     * ## Why the default body is empty, and what it costs if it is left in place
     *
     * So the existing `WorkScheduler` test doubles keep compiling. An interface that grows a
     * method breaks every fake at once, and a fake edited only to gain an empty body is a
     * fake with nothing to say about the change.
     *
     * ⚠️ **The production scheduler must override both overloads.** One that inherits these
     * turns every pull trigger into silence: the app looks healthy, the outbox keeps
     * uploading, badges stay green — and nothing a peer writes ever arrives, with no error
     * anywhere to notice it by.
     */
    public fun enqueueReconcile() {
        // Empty on purpose; see the KDoc. Overridden by the real scheduler.
    }

    /**
     * Reconcile **one** note as soon as constraints allow (SPEC §7.7).
     *
     * This is the 412 path. `If-Match` failed, which means somebody else changed the file,
     * and SPEC §7.4 is explicit that a 412 is a conflict and not a retry (CLAUDE.md rule 1's
     * *"never overwrite a remote file without `If-Match`"* has this as its other half). This
     * is what turns the refusal back into a merge: pull that one file, union the op logs, and
     * let the outbox re-upload against the ETag it just learned.
     *
     * Scoped to one note rather than reusing [enqueueReconcile] because a 412 on one file
     * says nothing about the rest of the account, and a whole pass costs a `PROPFIND` per
     * unpruned directory. It is also what bounds the exchange at two round trips per real
     * conflict rather than leaving it to the next periodic wake-up.
     */
    public fun enqueueReconcile(noteId: DocumentId) {
        // Empty on purpose; see [enqueueReconcile]. Overridden by the real scheduler.
    }
}

/**
 * CLAUDE.md rule 10 — every write is write-temp-then-atomic-rename.
 *
 * There is deliberately no plain `write`: the upload worker must never be able
 * to observe a half-written file, because a torn ZIP inside a PDF is the exact
 * corruption class this design exists to prevent. [appendBytes] is the journal
 * path (SPEC §5.1) and is the only non-atomic operation, by design — an
 * append that is cut short loses at most the in-progress stroke.
 */
public interface FileSystem {
    public suspend fun exists(path: String): Boolean

    /** The file's length in bytes, or -1 if it does not exist. */
    public suspend fun size(path: String): Long

    public suspend fun readBytes(path: String): ByteArray

    /**
     * [length] bytes from [offset], or fewer at end of file.
     *
     * This is what makes SPEC §5.3's *"one file per page gives lazy loading"* real: a
     * 200-page notebook is opened by reading the bundle's central directory and then
     * exactly the pages being drawn, not by inflating 200 pages to reach the first.
     */
    public suspend fun readRange(path: String, offset: Long, length: Int): ByteArray

    public suspend fun writeBytesAtomically(path: String, bytes: ByteArray)

    public suspend fun appendBytes(path: String, bytes: ByteArray)

    public suspend fun createDirectories(path: String)

    /**
     * Rename [from] to [to], atomically when they share a filesystem.
     *
     * Used to move a note into the trash. Not a copy-then-delete: a crash between the
     * two leaves the note in both places or neither.
     */
    public suspend fun move(from: String, to: String)

    /**
     * Delete a file or an empty-able directory tree.
     *
     * The only caller is trash expiry (30-day retention) and the removal of a
     * superseded temp file. **Nothing in this app deletes user data through this
     * without it having been in the trash first** (CLAUDE.md rule 1).
     */
    public suspend fun delete(path: String)

    /** Immediate children of [path], names only, empty when [path] is not a directory. */
    public suspend fun list(path: String): List<String>
}

/**
 * Wall-clock time, for display only.
 *
 * Sync ordering uses Lamport clocks (SPEC §4.1). Never order sync operations by
 * wall clock — device clocks disagree, and the resulting reordering is silent.
 */
public fun interface Clock {
    public fun nowEpochMillis(): Long
}
