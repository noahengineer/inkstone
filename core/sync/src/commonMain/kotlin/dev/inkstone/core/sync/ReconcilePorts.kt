package dev.inkstone.core.sync

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.storage.MergeReport

/*
 * SPEC §7.6's pull side, as the ports :core:sync needs from everything else.
 *
 * Six of them are persistence and are implemented in :core:database; one — [RemoteMergeGate] —
 * is implemented in :app, because merging into a note that may be open in the editor needs the
 * single-writer exclusion and the library index, and neither of those belongs here.
 *
 * The split is M5's, for M5's reason. The reconcile *semantics* are what can be got wrong
 * quietly — what a pruned subtree means, when an absent `oc:id` is a deletion, which of SPEC
 * §7.7's three layers a conflict lands in — so they live where they can be tested against a
 * fake, with no driver, no server and no device. The SQL is one statement each.
 *
 * ⚠️ The row types below are declared **here** and not in `:core:database`, even though that is
 * where the tables are. A port that spoke in `:core:database`'s types would invert the module
 * edge (`:core:database → :core:sync`), and the whole point of the edge running this way is that
 * the module owning the rules does not depend on the module owning SQLDelight.
 *
 * Block comment, not KDoc — a file-level KDoc attached to no declaration is a "dangling toplevel
 * KDoc" and ktlint cannot auto-correct it (the same note is on SyncPorts.kt).
 */

// ---------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------

/**
 * One cached remote collection, and its ETag (SPEC §7.4 rule 1).
 *
 * @property etag null when the server sent none. ⚠️ A **weak** one must never prune — see
 *   [RemoteWalk.unchanged]. [ETag.weak] carries that, which is why this is an [ETag] and not a
 *   `String`.
 * @property ocId the collection's `oc:id`, so a folder renamed in the web UI can move its cache
 *   entry rather than dropping it and re-walking the subtree.
 * @property seenAtEpochMillis ⚠️ **stamped only on a pass that actually WALKED this
 *   directory**, never on one that pruned it. That distinction is the whole reason the column
 *   exists: a pruned subtree's bindings go unstamped too, and reading those as "absent
 *   remotely" is the hundred-notes-in-the-trash bug.
 */
public data class RemoteDirectory(
    val accountId: String,
    val path: String,
    val etag: ETag? = null,
    val ocId: String? = null,
    val seenAtEpochMillis: Long = 0L,
)

/**
 * Where one account's last reconcile pass got to (SPEC §7.6 steps 1 and 4).
 *
 * @property dataFingerprint `nc:data-fingerprint`. A change means an admin restored a backup and
 *   every cached ETag now describes a file that moved *backwards* — invalidate and
 *   full-reconcile, never mass-re-download.
 * @property rootEtag what step 1's `PROPFIND Depth:0` is compared against. The weak flag travels
 *   with it, because a weak match here would end the pass before it started and lose a pull with
 *   no error anywhere.
 * @property lastReconcileAtEpochMillis when the last pass started.
 * @property lastFullAtEpochMillis when the last *unpruned* walk happened — the backstop for
 *   every way ETag pruning can lie.
 * @property refusedUntilEpochMillis ⚠️ **CLAUDE.md rule 5.** When a 401 that SPEC §7.2's
 *   confirmation call could not *confirm* may be met again. `NEEDS_REAUTH` already means zero
 *   requests, so this column is for the two answers that leave the account ACTIVE: the probe said
 *   the credential is alive (so one path is refused, not the account) and the probe could not be
 *   reached (so nothing may be concluded at all). Without it those two walk the whole tree again
 *   at the sixty-second poll floor, forever, each pass re-sending a credential the server has just
 *   refused — which is the pattern rule 5 exists to prevent, arrived at without any code ever
 *   "retrying a 401". Persisted rather than held in memory because a process restart would
 *   otherwise clear it, and WorkManager restarts the process for a living.
 */
public data class SyncCursor(
    val accountId: String,
    val dataFingerprint: String? = null,
    val rootEtag: ETag? = null,
    val lastReconcileAtEpochMillis: Long = 0L,
    val lastFullAtEpochMillis: Long = 0L,
    val refusedUntilEpochMillis: Long = 0L,
)

/**
 * A remote version fetched and not yet merged (SPEC §7.6 step 3).
 *
 * A marker, never a payload (CLAUDE.md rule 4): the bytes are at [tempPath].
 *
 * The row exists so a process kill between the download and the merge is resumable, and — the
 * part that matters more — so that the binding never claims a version the local note does not
 * have. `lastSyncedEtag` advances *after* a successful merge and never before.
 *
 * @property tempPath where the download landed. This row is the only record of that file, so
 *   deleting the row before the file leaks a lecture PDF nothing can name.
 */
public data class PendingDownload(
    val noteId: DocumentId,
    val accountId: String,
    val remotePath: String,
    val remoteOcId: String?,
    val remoteEtag: ETag?,
    val tempPath: String,
    val fetchedAtEpochMillis: Long,
)

/**
 * ⭐ A remote `.pdf` this device has looked inside and found is **not one of ours** (SPEC §5.6).
 *
 * ## Why this has to be remembered
 *
 * Whether a PDF is an Inkstone note is decided by the XMP marker, and the marker is in the PDF
 * catalog — so answering it costs the **whole file** (see `Reconciler.adopt`). Nothing recorded the
 * answer, and a directory's ETag moves every time any note in it uploads, so a 40 MB handout
 * sitting beside the notes was re-downloaded on every note save. Worse: each unknown file spends
 * one unit of the pass budget, so `limit` of them ahead of the notes in listing order consumed
 * every pass forever — the notes behind them never reconciled while the poll reported success.
 *
 * @property fileKey the same stable identity `Reconciler.stagingKey` names the staged file with:
 *   `oc:id` when the server published one (`<padded-fileid><instanceid>`, globally unique — SPEC
 *   §7.3), else the flattened path for an instance behind a proxy that strips `OC-FileId`. One
 *   function for both so a staged download and its "not ours" row cannot disagree about which file
 *   they are about.
 * @property etag what the file was when we looked. A row only answers for **these bytes**: a
 *   changed ETag means look again, because a path that held a stranger's scan yesterday can hold a
 *   peer's note today.
 * @property checksum `oc:checksums`' MD5, so SPEC §7.3's fallback works here too. Without it a
 *   weak-ETag server would re-download every foreign file on every pass, which is the livelock
 *   above with one extra step.
 * @property seenAtEpochMillis when this answer was last confirmed. Nothing sweeps by it yet — the
 *   row count is bounded by the number of non-Inkstone PDFs in the user's notes folders — but a
 *   sweep needs a timestamp and adding one later would need a migration.
 */
public data class ForeignRemote(
    val accountId: String,
    val fileKey: String,
    val remotePath: String,
    val etag: ETag? = null,
    val checksum: String? = null,
    val seenAtEpochMillis: Long = 0L,
)

/**
 * Which remote directory a library folder mirrors.
 *
 * @property remoteOcId ⚠️ SPEC §7.4 rule 2 — a folder renamed in the web UI keeps its `oc:id`,
 *   and this is what makes that a rename instead of a trashed folder plus a duplicate library.
 */
public data class FolderBinding(
    val folderId: FolderId,
    val accountId: String,
    val remotePath: String,
    val remoteOcId: String? = null,
    val remoteEtag: ETag? = null,
)

/**
 * How far one peer device has been seen on one note (SPEC §4.7).
 *
 * @property highestCounter the peer's `highestClockCounter`, from its bundle. Monotonic in the
 *   table — the store takes the `max`, because downloads arrive out of order and a lowered
 *   high-water mark is a false statement about the peer.
 */
public data class PeerClock(
    val noteId: DocumentId,
    val peerDeviceId: String,
    val highestCounter: Long,
    val seenAtEpochMillis: Long = 0L,
)

// ---------------------------------------------------------------------------
// Persistence — implemented in :core:database
// ---------------------------------------------------------------------------

/** The prune-by-directory-ETag cache (SPEC §7.4 rule 1). */
public interface DirectoryCache {

    /**
     * The whole cache for one account.
     *
     * Not one lookup per path: the walk needs the cache in order to decide what *not* to
     * request, so it needs it before it knows which paths it cares about.
     */
    public suspend fun forAccount(accountId: String): List<RemoteDirectory>

    public suspend fun upsert(directory: RemoteDirectory)

    /**
     * A whole walk's worth, in one transaction.
     *
     * Not a convenience. A kill partway through leaves some directories carrying this pass's
     * ETag and some the last one's, and a directory whose cached ETag is newer than its contents
     * were observed to be gets **pruned** on the next pass — so a subtree stops being walked,
     * silently, for as long as nothing else in it changes.
     */
    public suspend fun upsertAll(directories: Collection<RemoteDirectory>)

    /**
     * Drop every cached ETag for an account.
     *
     * Two callers, both correctness: `nc:data-fingerprint` changed (SPEC §7.6 step 4) and
     * sign-out. Cheap to lose — the next pass rebuilds it with one extra walk.
     */
    public suspend fun clearFor(accountId: String)
}

/** One row per account: where the last reconcile pass got to (SPEC §7.6 steps 1 and 4). */
public interface SyncCursorStore {

    /** Null before the first pass — which is **not** the same as "nothing has changed". */
    public suspend fun find(accountId: String): SyncCursor?

    public suspend fun upsert(cursor: SyncCursor)
}

/** Downloads fetched and not yet merged (SPEC §7.6 step 3, CLAUDE.md rule 10). */
public interface PendingDownloadStore {

    public suspend fun find(noteId: DocumentId): PendingDownload?

    public suspend fun upsert(download: PendingDownload)

    /**
     * Forget a download.
     *
     * **Delete the temp file first.** The row is the only thing that knows its name, so the
     * other order leaks it — and unlike a note, nothing else will ever notice.
     */
    public suspend fun remove(noteId: DocumentId)
}

/**
 * The negative half of the identity cache: remote `.pdf`s that are not ours (see [ForeignRemote]).
 *
 * Two methods, because two is all the walk needs — and an unused third would be the *sweep*, which
 * has no caller yet and would be dead code pretending to be a policy.
 */
public interface ForeignRemoteCache {

    /**
     * Every "not ours" answer on one account.
     *
     * The whole set rather than a lookup per file, for [DirectoryCache.forAccount]'s reason: the
     * walk consults it to decide what *not* to download, and a query per unknown file would be one
     * round trip per stranger's PDF on a path that is already the slow one.
     */
    public suspend fun forAccount(accountId: String): List<ForeignRemote>

    /** Remember that this version of this file has no Inkstone marker in it. */
    public suspend fun upsert(file: ForeignRemote)
}

/**
 * SPEC §7.7's "Needs attention" list.
 *
 * Deliberately write-mostly from here. `:core:sync` adds entries and reads the open set to avoid
 * duplicating one; the *screen* watches a Flow, and that method stays on the SQL class rather
 * than on this port — a reconciler with a stream of UI rows would be a reconciler that could
 * start making decisions from them.
 */
public interface AttentionLog {

    /**
     * The open entries, as a snapshot.
     *
     * ⚠️ **Not the dedup read**, however much it looks like one — and this is how it shipped. An
     * acknowledged row is by definition *absent* from this set, so a guard written against it lets
     * the same problem straight through, and [add]'s UPSERT then rewrites the row with
     * `acknowledgedAt = 0`. For an outcome the reconciler deliberately re-reaches every pass — the
     * peer's file lost its bundle, and the ETag is not advanced precisely so the next pass looks
     * again — the user's dismissal lasted one poll interval, forever. Use [find].
     */
    public suspend fun open(): List<AttentionEntry>

    /**
     * ⭐ One entry by id, **acknowledged or not.**
     *
     * The distinction from [open] is the whole point of the method. Two callers, both of them about
     * not repeating oneself: the dedup above, and layer 3 asking whether this conflict already has a
     * copy ([Attention.conflictId]). Both must be able to see a tombstone, because a dismissed row
     * still describes a live problem — that is what dismissing it *means*.
     */
    public suspend fun find(id: String): AttentionEntry?

    /**
     * Record something the user should look at.
     *
     * An UPSERT on [AttentionEntry.id], which is why [Attention.idFor] derives the id from the
     * *problem*: rediscovering the same conflict on the next pass updates one row rather than
     * growing the list by one every sixty seconds.
     *
     * ⚠️ An UPSERT also **clears an acknowledgement**, so a caller must ask [find] first and write
     * only when the row would say something new ([AttentionEntry.saysTheSameAs]).
     */
    public suspend fun add(entry: AttentionEntry)

    /**
     * The user has seen it.
     *
     * The only mutation, and it is a tombstone: the row stays (CLAUDE.md rule 1), because what
     * sync had to do to someone's notes is worth being able to look up afterwards.
     */
    public suspend fun acknowledge(id: String, acknowledgedAtEpochMillis: Long)
}

/** Which remote directory a library folder mirrors (ROADMAP M6 — folder mirroring both ways). */
public interface FolderBindingStore {

    public suspend fun find(folderId: FolderId): FolderBinding?

    /**
     * The folder a walked remote directory belongs to, by `oc:id`.
     *
     * A list rather than one row: SPEC §7.4's caveats include one `oc:id` presented at two paths
     * (a folder shared in twice), and throwing inside a reconcile pass is the opposite of the
     * "degrade gracefully" the same paragraph asks for.
     */
    public suspend fun findByOcId(accountId: String, remoteOcId: String): List<FolderBinding>

    /**
     * Every pin on one account, as `folderId → remotePath`.
     *
     * The whole set rather than one lookup, because the walk's roots are derived from it and it
     * does not know which folders it cares about until it has them.
     */
    public suspend fun pathsForAccount(accountId: String): Map<FolderId, String>

    public suspend fun upsert(binding: FolderBinding)

    /** Unbind, not delete. The folder and every note in it are untouched. */
    public suspend fun remove(folderId: FolderId)
}

/**
 * SPEC §4.7's causal-stability horizon: what each peer has seen, per note.
 *
 * This is what `Compaction.isAllowed`'s `oldestAckedCounter` has been missing since M2 — the
 * parameter has always been passed null, so thirty days of wall clock was the only guard. M6 is
 * the first milestone that knows what a peer has seen, because a downloaded bundle says so.
 */
public interface PeerClockStore {

    public suspend fun forNote(noteId: DocumentId): List<PeerClock>

    /** Monotonic: the stored counter never goes down. Downloads arrive out of order. */
    public suspend fun record(noteId: DocumentId, peerDeviceId: String, highestCounter: Long, seenAtEpochMillis: Long)

    /**
     * `Compaction.isAllowed(oldestAckedCounter = …)` for this note: the `min` across peers, or
     * **null when no peer is known** — which is the value that parameter documents as "nothing
     * is known", and the correct conservative answer for a one-device setup.
     */
    public suspend fun oldestAckedCounter(noteId: DocumentId): Long?
}

/**
 * The seven pull-side stores, as one parameter.
 *
 * Same argument [SyncStores] makes and for the same reason: [Reconciler] needs all of them, they
 * always come from one database, and a caller holding six of the seven could not do anything
 * correct with them. Passing them individually also put the constructor past detekt's parameter
 * limit, which is the linter noticing something true.
 */
public class ReconcileStores(
    public val directories: DirectoryCache,
    public val cursors: SyncCursorStore,
    public val downloads: PendingDownloadStore,
    public val attention: AttentionLog,
    public val folders: FolderBindingStore,
    public val peers: PeerClockStore,
    public val foreign: ForeignRemoteCache,
)

// ---------------------------------------------------------------------------
// The merge gate — implemented in :app
// ---------------------------------------------------------------------------

/**
 * ⭐ Everything the reconciler cannot do itself: touch a note.
 *
 * ## Why this is a port and not code in this module
 *
 * Three reasons, and none of them is tidiness.
 *
 * 1. **Single-writer exclusion.** The note may be open in the editor while a pull lands (SPEC
 *    §13.2 row 10), and two `NoteSession`s over one note each hold their own `pending` list and
 *    their own folded pages — so whichever materialized second would write a bundle missing the
 *    other's work. `NoteSession` is single-threaded by contract and deliberately has no lock;
 *    the exclusion belongs in the thing that knows whether an editor already holds the note,
 *    and that is `:app`.
 * 2. **The library index.** A merged note needs its `localRev` recorded, its thumbnail
 *    invalidated in both tiers, and — for an adopted one — a library row. All of that is
 *    `:app`'s `LibraryRepository`.
 * 3. **`:core:sync` must not be able to open a note.** If it could, the temptation to merge on
 *    the walk's own thread would exist, and CLAUDE.md rule 7's ban on heavy work is easier to
 *    keep when the heavy work is not reachable from here.
 *
 * ## What every implementation must guarantee
 *
 * - **`materialize` runs before returning**, with `MergeReport.Merged.carried` and
 *   `carriedManifest` passed through. Otherwise the first rewrite after a merge deletes the
 *   peer's recognised text and anything a newer build added — invisibly, because by definition
 *   they are the entries this build cannot render.
 * - **Nothing is trashed, replaced or recovered.** A pulled file is never auto-recovered (see
 *   [dev.inkstone.core.model.RemoteBundleVerdict.RECOVERED]).
 * - **The remote ops never reach the undo stack.** `NoteSession.merge` is the only door, and it
 *   is the only one that cannot touch `undoStack`.
 */
public interface RemoteMergeGate {

    /**
     * SPEC §7.7 layers 1 and 2 — fold the peer's op log into the local note.
     *
     * @param bundlePath the peer's `note.inkstone`, already extracted from their published PDF
     *   and staged outside `notes/<id>/`.
     * @return the report and the revision the local note reached. The revision is returned
     *   rather than re-read afterwards because the reconciler writes it into
     *   `binding.lastSyncedRev`, and a note edited between the merge and the re-read would make
     *   that a claim about bytes the server has never seen.
     */
    public suspend fun merge(noteId: DocumentId, bundlePath: String): MergeCommit

    /**
     * SPEC §7.6 step 3, third branch — a note from a peer this device has never held.
     *
     * @return the note's id once it is installed and in the library, or null when it could not
     *   be (a full disk, an unreadable bundle). Null is not an error the pass should stop on:
     *   the binding's ETag has not advanced, so the next pass tries again.
     */
    public suspend fun install(install: RemoteInstall): DocumentId?

    /**
     * SPEC §7.7 layer 3 — keep both, and never a modal.
     *
     * A **new `DocumentId` and new `ObjectId`s are mandatory** (SPEC §5.6 inverted): `note.id` is
     * the index's primary key and `notes/<id>/` is one directory, so a copy sharing the id would
     * not be a copy but the original overwritten; the XMP marker carries `documentId`, so a copy
     * keeping it would be merged straight back into the note the copy exists because it could
     * not merge with; and shared `ObjectId`s in a grow-only union make one copy's geometry
     * silently win on both notes.
     *
     * @return the copy's id, or **null when this build cannot make one** — a `TOO_NEW` or
     *   bundle-less remote file has nothing openable in it. Null is a complete answer, not a
     *   failure: the peer's bytes are still on the server, untouched, and the attention entry
     *   carries the path.
     */
    public suspend fun conflictedCopy(copy: ConflictedCopy): DocumentId?

    /**
     * SPEC §7.6 step 3, fourth branch — the remote file is gone.
     *
     * **Local trash, never a delete** (CLAUDE.md rule 1): `NoteTrash.trash` plus the index's
     * trash row plus thumbnail invalidation. Thirty days of retention and one tap to restore.
     *
     * ⚠️ **An implementation must refuse while an editor holds the note**, and this return value is
     * how it says so. `NoteTrash.trash` *moves* `notes/<id>/` away, and a live `NoteSession` knows
     * nothing about that: its next two-second autosave appends to `journal.ops` at the old path,
     * every `FileSystem` write does `mkdirs` first, and the directory comes back holding a journal
     * and no bundle. From there the note is **unrestorable** — `NoteTrash.restore` used to refuse
     * outright when `notes/<id>/` existed — and thirty days later the trash sweeper deletes the only
     * copy of every page. That is the permanent data loss CLAUDE.md rule 1 is written against, and
     * the trash is where it hid.
     *
     * @return true when the note is now in the trash. **False means "not now"**: nothing was moved,
     *   nothing was unbound, and the caller must leave the binding unstamped so a later pass — after
     *   the editor has let go — concludes the same thing and acts on it.
     */
    public suspend fun trashRemotelyDeleted(noteId: DocumentId, reason: String): Boolean

    /**
     * The note's remote file moved into a directory that mirrors a different library folder.
     *
     * ROADMAP M6's "folder mirroring both directions", pull half. Called only when the walk
     * found a [FolderBinding] for the file's new parent — a move into an unmirrored directory
     * changes the binding's path and leaves the library alone, because there is no folder to
     * move it to and inventing one would be the reconciler reorganising someone's library.
     */
    public suspend fun noteFolderChanged(noteId: DocumentId, folderId: FolderId)
}

/**
 * What [RemoteMergeGate.merge] did.
 *
 * @property localRev the note's revision **after** the merge was flushed and materialized. Not
 *   the peer's: `localRev` is never adopted from a remote document, because a peer's higher
 *   revision would jump this device's counter and break the outbox's `lastSyncedRev`
 *   comparison for good.
 */
public data class MergeCommit(val report: MergeReport, val localRev: Long)

/**
 * A peer's note this device does not have (SPEC §7.6 step 3, third branch).
 *
 * @property noteId ⚠️ the `documentId` **from the XMP marker inside the file**, never derived
 *   from the filename or the remote path (SPEC §5.6, CLAUDE.md's "identity is never a path").
 *   A note renamed on the server is the same note, and two notes can share a title.
 * @property pdfPath the downloaded published PDF, kept so the install can preserve the peer's
 *   original base bytes rather than re-deriving them.
 */
public data class RemoteInstall(
    val noteId: DocumentId,
    val accountId: String,
    val remotePath: String,
    val pdfPath: String,
    val bundlePath: String,
    val folderId: FolderId?,
)

/**
 * A conflict that could not be merged (SPEC §7.7 layer 3).
 *
 * @property localNoteId the note that stays exactly as it is. Null when the remote file is not
 *   one this device holds at all.
 * @property bundlePath null when nothing openable came out of the remote file — a `TOO_NEW`
 *   schema, or a bundle that is missing or does not hash to what the marker claims. The
 *   implementation then has no note to make, which is why [RemoteMergeGate.conflictedCopy] may
 *   answer null.
 * @property title already stamped by `RemoteName.conflictedCopy`, so both devices in a two-way
 *   conflict produce the same name for the same instant and the copy is legal on the server
 *   without further sanitising.
 */
public data class ConflictedCopy(
    val accountId: String,
    val localNoteId: DocumentId?,
    val remotePath: String,
    val pdfPath: String,
    val bundlePath: String?,
    val title: String,
    val folderId: FolderId?,
)
