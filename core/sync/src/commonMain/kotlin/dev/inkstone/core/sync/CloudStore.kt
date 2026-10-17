package dev.inkstone.core.sync

import dev.inkstone.core.model.CredentialStore

/*
 * docs/CLOUD-PROVIDERS.md §2 — the provider seam, as built in P1.
 *
 * P1 is deliberately a *rename of the dependency*, not a redesign: [OutboxDrainer] and [Reconciler]
 * used to hold a `NextcloudSession` and call `session.dav.put(...)`; they now hold a [CloudStore]
 * and call the same operations through it. [NextcloudStore] forwards each one to the unchanged
 * `WebDavClient` with the same arguments, so Nextcloud's behaviour is byte-for-byte what it was and
 * the 21-row merge matrix (SPEC §13.2) is the proof.
 *
 * What is NOT here yet, and why: the change-feed shape (`changes(cursor)`, §2.1) arrives with the
 * first provider that has one (OneDrive's `delta`). Adding it now would be an interface with one
 * implementation that cannot return it — an untested seam, which is how CLAUDE.md's "inert in the
 * app, green in every test" starts. The read side below ([listing], [download], [locate]) is the
 * Listing shape the reconciler already walks.
 *
 * Block comment, not KDoc, for the usual dangling-toplevel-KDoc reason (see SyncPorts.kt).
 */

/** Which service an account talks to. Persisted by name in P2 (`account.provider`). */
public enum class ProviderKind {
    NEXTCLOUD,
    ONEDRIVE,
}

/**
 * Where a file is, in the two ways a provider may need to address it.
 *
 * Nextcloud addresses by [path] and ignores [id]. OneDrive must update and delete **by id** — its
 * `If-Match` on a path-addressed update answers 412 for a file that is merely missing, which reads
 * as a conflict (docs/CLOUD-PROVIDERS.md §3.1) — and uses [path] only to create. Carrying both
 * everywhere now means P3 does not have to reshape every call site.
 *
 * @property id the binding's `remoteOcId`, opaque to everything but the store. Null before the first
 *   upload has returned one.
 */
public data class RemoteTarget(val path: String, val id: String? = null)

/**
 * What a write is allowed to overwrite — CLAUDE.md rule 1 as a type rather than an optional argument.
 *
 * The drainer used to pass `ifMatch: ETag?` and `ifNoneMatchStar: Boolean`, which can express four
 * combinations of which only three mean anything. This has exactly the meaningful ones.
 */
public sealed interface WritePrecondition {

    /** The file must not exist (`If-None-Match: *`; Graph `conflictBehavior: fail`). */
    public data object CreateOnly : WritePrecondition

    /** The file must still be at [etag]. A mismatch is a **conflict** (412), never a retry. */
    public data class IfMatch(val etag: ETag) : WritePrecondition

    /**
     * No validator is available to send — a stripped or **weak** ETag (SPEC §7.3), where returning
     * `W/"…"` in `If-Match` would be a permanent 412 loop.
     *
     * This exists because Nextcloud has always behaved this way and P1 changes no behaviour. A
     * store whose [CloudStore.supportsUncheckedReplace] is false must refuse it: on OneDrive an
     * unconditional replace is exactly what rule 1 forbids.
     */
    public data object Unchecked : WritePrecondition
}

/** What is being uploaded, for stores that label the body. Audio and timelines are M12's. */
public enum class UploadKind {
    PDF,
    AUDIO_OGG,
    TIMELINE,
}

/**
 * One authenticated connection to one account, as the drainer and the reconciler see it.
 *
 * Every method returns a [DavOutcome] and never throws for a thing a healthy server says on a bad
 * day (CLAUDE.md: sealed results across module boundaries). **There is no hard delete**: [trash] is
 * the only removal, so rule 1 ("no hard deletes") is a property of the type.
 */
public interface CloudStore {

    public val account: SyncAccount

    public val kind: ProviderKind

    /** False when an [WritePrecondition.Unchecked] replace must be refused. See that type. */
    public val supportsUncheckedReplace: Boolean

    // -- writes (OutboxDrainer) ------------------------------------------------------------------

    /** Create [path] and every missing parent. Idempotent: an existing folder is success. */
    public suspend fun ensureFolder(path: String): DavOutcome<Unit>

    @Suppress("LongParameterList") // The target, the bytes, and the three things a PUT may carry.
    public suspend fun put(
        target: RemoteTarget,
        bytes: ByteArray,
        precondition: WritePrecondition,
        mtimeEpochSeconds: Long?,
        md5Hex: String?,
        kind: UploadKind = UploadKind.PDF,
    ): DavOutcome<UploadReceipt>

    /** Move without overwriting whatever is at [toPath] (rule 1). */
    public suspend fun move(from: RemoteTarget, toPath: String): DavOutcome<UploadReceipt>

    /** Soft delete: the server's own trash, with its own retention. Never permanent. */
    public suspend fun trash(target: RemoteTarget): DavOutcome<Unit>

    /**
     * SPEC §7.2 — the one request permitted after a 401: true when the credential still works,
     * false when it is dead, null when the server could not be reached (conclude **nothing**).
     */
    public suspend fun confirmStillAuthorised(): Boolean?

    // -- reads (Reconciler) ----------------------------------------------------------------------

    /** One level of a folder (or just the folder itself), with the validators the pass compares. */
    public suspend fun listing(path: String, depth: ListingDepth): DavOutcome<List<DavResource>>

    public suspend fun download(path: String): DavOutcome<DownloadedFile>

    /**
     * Where the file with this numeric id is **now** (SPEC §7.4 rule 2's M7 repair). An empty list
     * is a real answer; any failure means *"cannot tell"* and must not be read as deletion.
     */
    public suspend fun locate(fileId: Long): DavOutcome<List<DavResource>>
}

/** How much of a folder a [CloudStore.listing] returns. Always explicit — SPEC §7.3. */
public enum class ListingDepth {
    SELF,
    CHILDREN,
}

/**
 * Turns an account row into a store, or null when the account cannot be used right now.
 *
 * Null covers every "stop touching this account" case a provider has (no credential, no uid yet).
 * Callers have already consulted [SyncAccount.isUsable] where they distinguish *unusable* from
 * *skipped*, so this does not re-derive that.
 */
public fun interface CloudStoreFactory {
    public suspend fun open(account: SyncAccount): CloudStore?
}

/** Nextcloud's factory: reads the app password from the Keystore-backed store. */
public class NextcloudStoreFactory(
    private val credentials: CredentialStore,
    private val transport: NextcloudTransport,
) : CloudStoreFactory {

    override suspend fun open(account: SyncAccount): CloudStore? {
        val credential = credentials.get(account.id) ?: return null
        // NextcloudSession's constructor throws on an empty uid; a stored credential can disagree
        // with the account row, so this is checked here and not only through `isUsable`.
        if (credential.uid.isEmpty()) return null
        return NextcloudStore(NextcloudSession(account, credential, transport))
    }
}

/** [CloudStore] over the existing, unchanged WebDAV client. Every method is a one-line forward. */
public class NextcloudStore(private val session: NextcloudSession) : CloudStore {

    override val account: SyncAccount get() = session.account

    override val kind: ProviderKind get() = ProviderKind.NEXTCLOUD

    override val supportsUncheckedReplace: Boolean get() = true

    override suspend fun ensureFolder(path: String): DavOutcome<Unit> = session.dav.mkcolRecursive(path)

    override suspend fun put(
        target: RemoteTarget,
        bytes: ByteArray,
        precondition: WritePrecondition,
        mtimeEpochSeconds: Long?,
        md5Hex: String?,
        kind: UploadKind,
    ): DavOutcome<UploadReceipt> = session.dav.put(
        remotePath = target.path,
        bytes = bytes,
        ifMatch = (precondition as? WritePrecondition.IfMatch)?.etag,
        ifNoneMatchStar = precondition is WritePrecondition.CreateOnly,
        mtimeEpochSeconds = mtimeEpochSeconds,
        md5Hex = md5Hex,
        contentType = when (kind) {
            UploadKind.PDF -> io.ktor.http.ContentType.Application.Pdf
            UploadKind.AUDIO_OGG -> WebDavClient.AUDIO_OGG
            UploadKind.TIMELINE -> WebDavClient.TIMELINE
        },
    )

    override suspend fun move(from: RemoteTarget, toPath: String): DavOutcome<UploadReceipt> =
        session.dav.move(from.path, toPath)

    override suspend fun trash(target: RemoteTarget): DavOutcome<Unit> = session.dav.delete(target.path)

    override suspend fun confirmStillAuthorised(): Boolean? = session.confirmStillAuthorised()

    override suspend fun listing(path: String, depth: ListingDepth): DavOutcome<List<DavResource>> =
        session.dav.propfind(
            path,
            when (depth) {
                ListingDepth.SELF -> WebDavClient.Depth.Zero
                ListingDepth.CHILDREN -> WebDavClient.Depth.One
            },
        )

    override suspend fun download(path: String): DavOutcome<DownloadedFile> = session.dav.download(path)

    override suspend fun locate(fileId: Long): DavOutcome<List<DavResource>> = session.dav.searchByFileId(fileId)
}
