package dev.inkstone.core.sync

import dev.inkstone.core.model.DocumentId

/**
 * SPEC §7.7's *"surface in Needs attention"* — the list, and the rule that fills it.
 *
 * ## Why a table and not a state on the note
 *
 * `note.syncState` is one value and `note.lastError` is one string. A note can need attention
 * for two reasons at once — a conflicted copy *and* a title this device lost — and a list cannot
 * be reconstructed from a per-note enum. It also has to survive the note being closed, the app
 * being killed and the account being signed out, because the whole promise of CLAUDE.md rule 6
 * is that the user gets to review it *later*.
 *
 * ## Never a modal, never blocking
 *
 * Nothing in this file waits for anything or asks anything. [Attention.entryFor] is a pure
 * function from *what happened* to *what to write down*, and the reconciler writes the row and
 * carries on. Nextcloud's own Android client resolves conflicts with a modal raised from a
 * notification and has roughly nine open bugs about it.
 */
public object Attention {

    /**
     * The dedup handle, derived from the **problem** rather than minted fresh.
     *
     * [AttentionLog.add] is an UPSERT on this, so a poll that rediscovers the same conflict every
     * sixty seconds updates one row instead of growing the list by 1 440 entries a day. That is
     * the whole reason this is not a UUIDv7: a reconcile pass is *expected* to reach the same
     * conclusion repeatedly, because the conclusion is usually "leave this alone".
     *
     * [discriminator] is what keeps two genuinely different problems of one kind apart. For layer 3
     * that is the **remote file** ([conflictId]) and never the copy: an id keyed on the copy changes
     * every time a copy is made, which is the exact opposite of a dedup handle — it is what let one
     * unresolvable conflict mint a fresh copy on every poll.
     */
    public fun idFor(
        kind: AttentionKind,
        accountId: String,
        noteId: DocumentId?,
        discriminator: String? = null,
    ): String = listOfNotNull(kind.name, noteId?.value ?: accountId, discriminator).joinToString(":")

    /**
     * ⭐ The id of *this conflict's* row — the pair (local note, remote file).
     *
     * Read **before** layer 3 mints anything, and that is what makes a conflicted copy idempotent.
     * Layer 3 deliberately never advances the ETag, so the same file is fetched and refused again on
     * every poll for as long as the condition lasts, and `conflictedCopyOf` mints a new `DocumentId`
     * every time it is asked. Unkeyed, one unresolvable condition filled the library *and* the
     * user's server at one `… (conflicted copy …).pdf` a minute.
     *
     * The row is what remembers, because the row is **never deleted** — acknowledging is a tombstone
     * (CLAUDE.md rule 1). So a dismissal is also the record of the copy, which is a second and
     * independent reason `syncAttention` has no DELETE.
     *
     * @param fileKey the remote file's identity: its `oc:id`, or its path when a proxy stripped the
     *   header. The id and not the path wherever there is one, because a rename is not a new
     *   conflict (SPEC §7.4 rule 2) and keying on the path would make it a second copy.
     */
    public fun conflictId(accountId: String, noteId: DocumentId?, fileKey: String): String =
        idFor(AttentionKind.CONFLICTED_COPY, accountId, noteId, fileKey)

    /**
     * The one place a [ReconcileOutcome] becomes something the user sees.
     *
     * Pure, and exhaustive over the outcome type, so a new outcome cannot be added without
     * deciding whether it is worth telling anyone about. Most are not: a merge that simply
     * worked, a file that moved, a stranger's PDF ignored — SPEC §7.7 layer 1 is explicit that
     * two people writing in different corners of a page are told nothing at all.
     *
     * @return null when there is nothing to say. **Not** an empty entry: a row the screen has to
     *   filter out is a row that eventually gets shown.
     */
    @Suppress("CyclomaticComplexMethod")
    // One branch per reconcile outcome, which is the only way to check this against SPEC §7.7's
    // three layers. Collapsing the silent ones into an `else` would make adding a fourth silent
    // outcome — or forgetting to surface a loud one — invisible at review.
    public fun entryFor(outcome: ReconcileOutcome, accountId: String, nowEpochMillis: Long): AttentionEntry? =
        when (outcome) {
            // SPEC §7.7 layer 1. The 95 % case, and the user hears nothing.
            is ReconcileOutcome.Unchanged,
            is ReconcileOutcome.Moved,
            is ReconcileOutcome.Adopted,
            is ReconcileOutcome.Ignored,
            is ReconcileOutcome.Deferred,
            -> null

            // Layer 2, and only when it actually discarded a string somebody typed. A page-order
            // merge is silent because there is nothing for a user to decide about a fractional key.
            is ReconcileOutcome.Merged -> outcome.overriddenTitle?.let { lost ->
                entry(AttentionKind.TITLE_OVERRIDDEN, accountId, outcome.noteId, nowEpochMillis, detail = lost)
            }

            is ReconcileOutcome.NotRecoverable -> entry(
                AttentionKind.REMOTE_NOT_RECOVERABLE,
                accountId,
                outcome.noteId,
                nowEpochMillis,
                remotePath = outcome.remotePath,
            )

            is ReconcileOutcome.TooNew -> entry(
                AttentionKind.REMOTE_TOO_NEW,
                accountId,
                outcome.noteId,
                nowEpochMillis,
                // The version, not a bare "written by a newer build": the difference between an
                // unactionable message and "update Inkstone to open this" is this number.
                detail = outcome.remoteSchemaVersion.toString(),
                remotePath = outcome.remotePath,
            )

            is ReconcileOutcome.Unmergeable -> entry(
                AttentionKind.CONFLICTED_COPY,
                accountId,
                outcome.noteId,
                nowEpochMillis,
                detail = outcome.reason,
                remotePath = outcome.remotePath,
                copyNoteId = outcome.copyNoteId,
                // ⚠️ The remote **file**, not the copy — see [conflictId]. Two remote files that
                // cannot merge with one note are two problems and keep two copies; the same file met
                // on the four hundredth poll is one problem, and the row is where the reconciler
                // reads that it already has a copy of it.
                discriminator = outcome.fileKey,
            )

            is ReconcileOutcome.DeletedRemotely -> entry(
                AttentionKind.REMOTE_DELETED,
                accountId,
                outcome.noteId,
                nowEpochMillis,
                remotePath = outcome.remotePath,
            )

            is ReconcileOutcome.Failed -> kindFor(outcome.failure)?.let { kind ->
                entry(
                    kind,
                    accountId,
                    // NEEDS_REAUTH is about the account, not the file that happened to hit it.
                    noteId = outcome.noteId.takeIf { kind != AttentionKind.NEEDS_REAUTH },
                    nowEpochMillis = nowEpochMillis,
                    detail = outcome.failure.describe,
                    remotePath = outcome.remotePath,
                )
            }
        }

    /**
     * Which failures are worth a row.
     *
     * Only the three the user can do something about. A transport failure, a 423 and a 5xx are
     * all "try again in a minute", and putting those in a list the user is meant to come back to
     * would fill it with rows that fix themselves — after which nobody reads it.
     */
    private fun kindFor(failure: DavFailure): AttentionKind? = when (failure) {
        DavFailure.QuotaExceeded -> AttentionKind.QUOTA_EXCEEDED
        is DavFailure.Forbidden -> AttentionKind.FORBIDDEN
        DavFailure.Unauthorized -> AttentionKind.NEEDS_REAUTH
        else -> null
    }

    @Suppress("LongParameterList") // Every column of one row, and the row is nine columns wide.
    private fun entry(
        kind: AttentionKind,
        accountId: String,
        noteId: DocumentId?,
        nowEpochMillis: Long,
        detail: String? = null,
        remotePath: String? = null,
        copyNoteId: DocumentId? = null,
        discriminator: String? = null,
    ) = AttentionEntry(
        id = idFor(kind, accountId, noteId, discriminator),
        accountId = accountId,
        kind = kind.name,
        createdAtEpochMillis = nowEpochMillis,
        noteId = noteId,
        detail = detail,
        conflictedCopyNoteId = copyNoteId,
        remotePath = remotePath,
    )
}

/**
 * One entry in SPEC §7.7's "Needs attention" list.
 *
 * @property id the dedup handle — see [Attention.idFor]. **Not** a UUIDv7 minted per pass.
 * @property noteId null for an account-level entry: a full quota, an unwritable folder, a
 *   credential the server has stopped accepting.
 * @property kind stored **by name**, as a `String` and not an [AttentionKind]. An unrecognised
 *   kind written by a newer build must be kept and shown as itself; dropping it would hide a
 *   conflict, which is the one thing this list exists not to do. [kindOrNull] is the typed read.
 * @property conflictedCopyNoteId SPEC §7.7 layer 3's second copy. Without it the entry cannot
 *   offer to open the copy, and the copy is a mystery file the user deletes.
 * @property acknowledgedAtEpochMillis 0 while open. Acknowledging is a tombstone — there is no
 *   delete (CLAUDE.md rule 1), because what sync had to do to someone's notes is worth being
 *   able to look up afterwards.
 */
public data class AttentionEntry(
    val id: String,
    val accountId: String,
    val kind: String,
    val createdAtEpochMillis: Long,
    val noteId: DocumentId? = null,
    val detail: String? = null,
    val conflictedCopyNoteId: DocumentId? = null,
    val remotePath: String? = null,
    val acknowledgedAtEpochMillis: Long = 0L,
) {
    public val isOpen: Boolean get() = acknowledgedAtEpochMillis == 0L

    /** Null for a kind this build does not know. Show the row anyway — see [kind]. */
    public val kindOrNull: AttentionKind? get() = AttentionKind.entries.firstOrNull { it.name == kind }

    /**
     * ⭐ Whether this row would tell the user exactly what [other] would — **everything except when.**
     *
     * The reconciler's dedup rule, and the reason it is a comparison rather than "a row with this id
     * exists": a dismissal covers the problem the user *read*, not the note. "The peer's bundle
     * schema is now 4" is something new to say and belongs back on the list; "the peer's file still
     * has no bundle" is the row they put away sixty seconds ago.
     *
     * ⚠️ A field added later is compared **by default**, which is the direction that fails safe for a
     * payload column and unsafe for a clock. If M7 adds something that moves on its own, it belongs
     * beside the two below or every pass re-opens every dismissal — which is the notification loop
     * this comparison exists to end.
     */
    internal fun saysTheSameAs(other: AttentionEntry): Boolean = withoutTimestamps() == other.withoutTimestamps()

    /**
     * Everything the screen draws, and neither of the two values that move without anything having
     * happened: `createdAt` is stamped per pass (1 440 of them a day at SPEC §7.6's floor) and
     * `acknowledgedAt` is the dismissal being protected.
     */
    private fun withoutTimestamps() = copy(createdAtEpochMillis = 0L, acknowledgedAtEpochMillis = 0L)
}

/** The nine reasons a note or an account can be in the list (SPEC §7.7). */
public enum class AttentionKind {

    /** Layer 3 fired. Both versions kept; [AttentionEntry.conflictedCopyNoteId] is the second. */
    CONFLICTED_COPY,

    /**
     * Layer 2 resolved a title by LWW and the local one lost.
     *
     * SPEC §7.7 requires the override be *"recorded in history"*, and the string worth recording
     * is the one the user typed and no longer has. It goes in [AttentionEntry.detail].
     */
    TITLE_OVERRIDDEN,

    /** SPEC §7.6 step 3, fourth branch. The local copy is in the trash, restorable for 30 days. */
    REMOTE_DELETED,

    /**
     * The peer's published PDF lost its embedded bundle, or it does not hash to what the marker
     * claims (SPEC §5.6).
     *
     * Layer 3 **always**, never an automatic recovery. Recovery replaces the op log rather than
     * unioning with it and stamps everything past every clock it has seen, so a peer's lossy
     * recovery folded in here would win every LWW register against full-fidelity local data —
     * and look like a successful sync while doing it.
     */
    REMOTE_NOT_RECOVERABLE,

    /**
     * The peer runs a newer bundle schema (SPEC §5.6 row 4 / §5.8).
     *
     * The note is left completely alone and, above all, is **not re-saved**: rewriting it in this
     * build's vocabulary under the original `documentId` would win the next sync and take the
     * newer data with it. [AttentionEntry.detail] carries the schema version.
     */
    REMOTE_TOO_NEW,

    /**
     * A recovery dropped strokes or pages it could not identify (SPEC §5.6).
     *
     * ⚠️ Not raised by the reconciler — a *pulled* file is never recovered. This is the
     * user-driven import path in `:app` reporting what it could not save, and it is in this enum
     * because it belongs in the same list.
     */
    RECOVERY_LOSS,

    /** 507. That account's uploads are paused rather than hammering a full disk. */
    QUOTA_EXCEEDED,

    /** 403 — authenticated and refused. A withdrawn share, or a folder an admin locked. */
    FORBIDDEN,

    /**
     * SPEC §7.2 / CLAUDE.md rule 5 — the account is halted and **nothing** is sent to it.
     *
     * Account-level, so [AttentionEntry.noteId] is null. This is the only row that is not about
     * a note, and it is what makes "your notes stopped syncing an hour ago" visible without the
     * app ever retrying a credential to find out.
     */
    NEEDS_REAUTH,
}

/**
 * What one remote file's pass concluded — the reconciler's per-file verdict.
 *
 * Sealed and pure so that [Attention.entryFor] can be a table rather than a scattering of
 * `attention.add(...)` calls along the walk. That matters more than it looks: SPEC §7.7 is a
 * policy about what the user is told, and a policy expressed as fifteen call sites is a policy
 * nobody can check.
 */
public sealed interface ReconcileOutcome {

    /** The remote file is byte-for-byte what this device last saw. The common case. */
    public data object Unchanged : ReconcileOutcome

    /** SPEC §7.4 rule 2 — same `oc:id`, same ETag, new href. Binding repathed, **no download**. */
    public data class Moved(val noteId: DocumentId, val from: String, val to: String) : ReconcileOutcome

    /**
     * SPEC §7.7 layers 1 and 2 applied.
     *
     * @property remoteNovelOps how many of the peer's ops this device had not seen. **Zero is
     *   normal** — it is what a poll finds when the remote ETag moved because *this* device
     *   uploaded, and it is what stops the two devices uploading each other's merges forever.
     */
    public data class Merged(val noteId: DocumentId, val remoteNovelOps: Int, val overriddenTitle: String?) :
        ReconcileOutcome

    /** A note from a peer this device had never held, now in the library. */
    public data class Adopted(val noteId: DocumentId, val remotePath: String) : ReconcileOutcome

    /**
     * Not ours, and deliberately left alone.
     *
     * A PDF with ink annotations and no Inkstone marker might be a stranger's Acrobat markup
     * (SPEC §5.6), and adopting one would put a foreign document under one of our ids where it
     * would start merging with whatever else has that id.
     */
    public data class Ignored(val remotePath: String, val reason: String) : ReconcileOutcome

    /** The peer's file lost its bundle. Layer 3; see [AttentionKind.REMOTE_NOT_RECOVERABLE]. */
    public data class NotRecoverable(val noteId: DocumentId?, val remotePath: String) : ReconcileOutcome

    /** A newer bundle schema. Layer 3; see [AttentionKind.REMOTE_TOO_NEW]. */
    public data class TooNew(val noteId: DocumentId?, val remotePath: String, val remoteSchemaVersion: Int) :
        ReconcileOutcome

    /**
     * Layer 3 for any other reason the union could not be taken. Both versions kept.
     *
     * [noteId] is null when the remote file is not one this device holds — a peer's note whose
     * bundle came out unwritable, say. The entry is then account-level, which is correct: there is
     * no local note for the user to look at, only a path on the server.
     *
     * @property fileKey the remote file's identity, which is what makes this conflict *the same one*
     *   on the next poll rather than a new one. See [Attention.conflictId]: without it the copy was
     *   the row's discriminator, so every re-encounter was a new row and a new copy.
     */
    public data class Unmergeable(
        val noteId: DocumentId?,
        val remotePath: String,
        val reason: String,
        val copyNoteId: DocumentId?,
        val fileKey: String,
    ) : ReconcileOutcome

    /** SPEC §7.6 step 3, fourth branch. The local copy went to the trash, never a delete. */
    public data class DeletedRemotely(val noteId: DocumentId, val remotePath: String) : ReconcileOutcome

    /**
     * Nothing was concluded and the work is still owed — a fetched-but-unmerged download, a
     * gate that could not take the note.
     *
     * Distinct from [Failed] because it is the *only* other outcome that must stop the file's
     * directory from having its ETag cached: a cached ETag means "everything under here is
     * fully reconciled at this version", and caching one over unfinished work prunes the subtree
     * on every later pass until something else in it happens to change.
     */
    public data class Deferred(val noteId: DocumentId?, val remotePath: String, val reason: String) : ReconcileOutcome

    /** A request failed. Retried by the next pass; [Attention] surfaces only the three that will not fix themselves. */
    public data class Failed(val noteId: DocumentId?, val remotePath: String, val failure: DavFailure) :
        ReconcileOutcome
}
