package dev.inkstone.core.sync

import dev.inkstone.core.model.DocumentId

/**
 * SPEC §7.6 — the outbox. **CLAUDE.md rule 4: no code path performs a network write
 * directly.** Every remote mutation is a row here, drained by WorkManager.
 *
 * ```sql
 * outbox(note_id, op_type, local_rev_at_enqueue, attempts, next_attempt_at, created_at,
 *        PRIMARY KEY (note_id, op_type))     -- the coalescing key
 * ```
 *
 * ## Rows are markers, never payloads
 *
 * A row says *"this note owes the server an upload"*. It does **not** carry the note's
 * bytes, and adding a `payload BLOB` here would quietly double the storage cost of every
 * note in the app. The drainer reads a snapshot of `document.pdf` at drain time, which is
 * also what makes 500 offline edits cost one upload rather than 500 queued copies.
 *
 * ## The five coalescing rules (SPEC §7.6)
 *
 * | Situation | Result |
 * |---|---|
 * | UPLOAD enqueued while UPLOAD pending | UPSERT on `(note_id, UPLOAD)`. Earliest `created_at` kept, `local_rev_at_enqueue` updated, **existing backoff inherited** |
 * | MOVE then UPLOAD | Both rows kept; MOVE always drains first for that note |
 * | DELETE enqueued with UPLOAD pending | DELETE **annihilates** the pending UPLOAD. The remote DELETE still runs if the note was ever uploaded |
 * | MKCOL | Drains before any op targeting a path inside it |
 * | — | Minimum **30 s** between uploads of the same note while actively editing |
 *
 * The backoff-inheritance rule is the one that looks like a detail and is not. Resetting
 * `next_attempt_at` on every coalesce means a note being actively written to has its
 * retry timer reset every two seconds — so a note that is failing to upload retries
 * *immediately, forever*, which is a hot loop against the user's server.
 */
public enum class OutboxOpType {
    /** Create the remote folder. Drains before anything targeting a path inside it. */
    MKCOL,

    /** The note moved to a different remote path. Drains before its UPLOAD. */
    MOVE,

    /** Publish `document.pdf`. The common case, and the one that coalesces. */
    UPLOAD,

    /**
     * ⭐ M12 — one recording's audio **and its timeline**, uploaded together
     * (docs/M12-AUDIO.md §5).
     *
     * ⚠️ **The one op type that must not coalesce.** Every other row here says *"this note
     * owes the server the current version of one thing"*, and a newer enqueue strictly
     * supersedes an older one — which is what makes 500 offline edits cost one upload. A
     * recording is the opposite: every one is a **distinct, irreplaceable object and none
     * supersedes another**. Two lectures recorded into one notebook while offline would
     * UPSERT onto one row, one would upload, and the other would sit on disk with nothing
     * pointing at it and nothing reporting anything.
     *
     * That is why [OutboxEntry.subject] exists and why this is the only op type that fills
     * it. See `MediaUploadTest`, which is the row that fails without it.
     *
     * ## Why it drains before [DELETE], which is a correctness rule and not a preference
     *
     * `OutboxDrainer.performDelete` ends in `bindings.remove(noteId)`, and `drainOne` drops
     * any row whose note has no binding. So a DELETE draining first would silently discard
     * the queued lecture — while doc §5 requires the opposite: trashing a note whose lecture
     * has not uploaded yet must still deliver the lecture. (The media files themselves
     * survive a DELETE regardless; they are in a sibling directory, not in the note's file.)
     */
    MEDIA_UPLOAD,

    /** Remote delete — soft on Nextcloud, so it lands in the server's trash (SPEC §7.7). */
    DELETE,

    ;

    /**
     * Drain order within one note, low first.
     *
     * MKCOL before everything (there is nowhere to PUT to otherwise), MOVE before UPLOAD
     * (SPEC §7.6 says so explicitly — uploading first would write to the old path and
     * leave the move to shuffle stale bytes), MEDIA_UPLOAD before DELETE (see its KDoc —
     * a drained DELETE removes the binding the media upload needs), DELETE last so a
     * MOVE-then-DELETE deletes what is at the *new* path rather than 404ing on the old one.
     *
     * ⚠️ `Sync.sq`'s `selectDueOutbox` restates this as a `CASE`, because SQL cannot read a
     * Kotlin enum's ordinal. The two must be changed together; the migration comment on
     * `7.sqm` says so as well.
     */
    public val drainPriority: Int get() = ordinal
}

/**
 * One row. Every field is either the coalescing key or scheduling state — there is
 * deliberately nowhere to put bytes.
 *
 * @property subject ⭐ **M12 — the third of the coalescing key**, and empty for every op
 *   type but [OutboxOpType.MEDIA_UPLOAD].
 *
 *   Empty means *"one of these per note"*, which is the correct rule for MKCOL, MOVE,
 *   UPLOAD and DELETE: each says "the note owes the server the current version of one
 *   thing", so a newer enqueue supersedes an older one and 500 offline edits leave one row.
 *
 *   Non-empty means *"one of these per **object**"* — the recording id, for audio. Every
 *   recording is a distinct, irreplaceable file and none supersedes another, so an UPSERT
 *   that collapsed two of them would upload one lecture and silently strand the other
 *   (docs/M12-AUDIO.md §5). This column is what makes the two rules one mechanism instead
 *   of a special case in the drainer.
 * @property localRevAtEnqueue what `note.local_rev` was when this was enqueued. SPEC
 *   §7.6's mid-flight guard compares it against the note's *current* rev on 2xx: if they
 *   differ the note changed while the PUT was in the air, and marking SYNCED would make
 *   that work invisible to sync forever.
 * @property nextAttemptAt epoch millis. Wall clock is correct here — this is a local
 *   scheduling decision, not an ordering between devices (SPEC §4.1).
 */
public data class OutboxEntry(
    val noteId: DocumentId,
    val opType: OutboxOpType,
    val localRevAtEnqueue: Long,
    val subject: String = NO_SUBJECT,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0L,
    val createdAt: Long = 0L,
) {
    /** This row's identity in the table — what a remove or a reschedule has to name. */
    public val key: OutboxKey get() = OutboxKey(opType, subject)
}

/**
 * ⭐ A row's identity within one note: the op type **and** its subject.
 *
 * A type rather than two parameters, because every place that removes or reschedules a row
 * has to carry both and *"forgot the subject"* would otherwise be a legal call that deletes
 * the wrong lecture's queue entry — or, with an empty default, quietly deletes nothing. Same
 * reasoning as `NoteSession.apply`'s deleted `undoable` default: make the wrong call
 * unrepresentable rather than documented.
 */
public data class OutboxKey(val opType: OutboxOpType, val subject: String = NO_SUBJECT)

/** The subject of a row that is one-per-note. See [OutboxEntry.subject]. */
public const val NO_SUBJECT: String = ""

/**
 * The change [OutboxCoalescing] decided on, ready to apply in one transaction.
 *
 * A plan rather than a sequence of calls so the decision is a pure function of the
 * pending rows — testable without a database, and impossible to apply half of.
 */
public data class OutboxPlan(
    val upserts: List<OutboxEntry> = emptyList(),
    val removals: List<OutboxKey> = emptyList(),
) {
    public val isEmpty: Boolean get() = upserts.isEmpty() && removals.isEmpty()

    public companion object {
        public val Nothing: OutboxPlan = OutboxPlan()
    }
}

/**
 * SPEC §7.6's coalescing rules, as a pure function.
 *
 * Pure on purpose: these five rules are the whole of the outbox's cleverness, and the
 * only way to be sure they are right is to be able to state them as a table and test
 * that table. The persistence layer applies the result; it does not reimplement it.
 */
public object OutboxCoalescing {

    /**
     * @param pending every row currently queued for this note.
     * @param request what is being enqueued now.
     * @param localRev the note's current `local_rev`.
     * @param everUploaded whether the note has a remote binding that has been written at
     *   least once. SPEC §7.6: a DELETE annihilates a pending UPLOAD, *"if the note was
     *   ever uploaded, the remote DELETE still runs"* — and if it never was, there is
     *   nothing on the server to delete and the DELETE row would be a guaranteed 404.
     * @param now epoch millis, for a newly created row's `created_at` / `next_attempt_at`.
     */
    @Suppress("LongParameterList") // The request, its subject, and the three facts it is decided against.
    public fun plan(
        pending: List<OutboxEntry>,
        request: OutboxOpType,
        noteId: DocumentId,
        localRev: Long,
        everUploaded: Boolean,
        now: Long,
        /** M12 — the recording id for [OutboxOpType.MEDIA_UPLOAD]; empty for everything else. */
        subject: String = NO_SUBJECT,
    ): OutboxPlan {
        // ⚠️ Keyed by the **whole** key, not by op type. Keying this map by `opType` alone was
        // the bug in miniature: two MEDIA_UPLOADs for one note would find each other here, and
        // the second would be treated as a newer version of the first.
        val existing = pending.associateBy { it.key }
        val key = OutboxKey(request, subject)
        return when (request) {
            OutboxOpType.DELETE -> planDelete(existing, noteId, localRev, everUploaded, now)
            else -> OutboxPlan(upserts = listOf(coalesce(existing[key], noteId, key, localRev, now)))
        }
    }

    /**
     * UPSERT on `(note_id, op_type)`.
     *
     * Three things are preserved from the existing row and each one is a rule:
     * the **earliest `created_at`** (so "how long has this been waiting" stays true),
     * the **attempt count**, and the **`next_attempt_at`** — the backoff is *inherited*,
     * never reset. See the [OutboxOpType] KDoc for what resetting it does.
     */
    private fun coalesce(
        existing: OutboxEntry?,
        noteId: DocumentId,
        key: OutboxKey,
        localRev: Long,
        now: Long,
    ): OutboxEntry = OutboxEntry(
        noteId = noteId,
        opType = key.opType,
        // The only field that always takes the new value: the drainer must upload the
        // newest revision, and the mid-flight guard has to compare against it.
        localRevAtEnqueue = localRev,
        subject = key.subject,
        attempts = existing?.attempts ?: 0,
        nextAttemptAt = existing?.nextAttemptAt ?: now,
        createdAt = existing?.createdAt ?: now,
    )

    /**
     * SPEC §7.6 — *"DELETE **annihilates** the pending UPLOAD. If the note was ever
     * uploaded, the remote DELETE still runs."*
     *
     * Both halves matter. Leaving the UPLOAD queued would re-create on the server a file
     * the user just deleted; dropping the DELETE for a note that *was* uploaded would
     * leave it on the server forever.
     *
     * MOVE is annihilated too, for the same reason and with less ceremony: moving a file
     * you are about to delete is two requests to reach the same place as one.
     *
     * ⚠️ **[OutboxOpType.MEDIA_UPLOAD] is deliberately not in this list** (docs/M12-AUDIO.md
     * §5). Annihilation is right when the queued op would put back something the user has just
     * removed; a lecture is not that. Its files are in a sibling directory that the note's
     * DELETE does not touch, the recording is the one thing in this app that cannot be
     * re-made, and *"trashed the note the lecture was in"* is a thing people do the same
     * evening they recorded it.
     *
     * The list is written as an **opt-in** — three named types, not "everything except
     * DELETE" — so a new op type is excluded until somebody decides otherwise, which is the
     * safe direction for a rule whose failure mode is destroying queued work. M6 found the
     * same shape from the other side: `noteDeletedRemotely` must not be `noteTrashed`,
     * because that annihilated the UPLOAD carrying a note's only unsynced edits.
     */
    private fun planDelete(
        existing: Map<OutboxKey, OutboxEntry>,
        noteId: DocumentId,
        localRev: Long,
        everUploaded: Boolean,
        now: Long,
    ): OutboxPlan {
        val removals = listOf(OutboxOpType.UPLOAD, OutboxOpType.MOVE, OutboxOpType.MKCOL)
            // A folder that was only ever created for a note now being deleted.
            .map { OutboxKey(it) }
            .filter { it in existing }
        val deleteKey = OutboxKey(OutboxOpType.DELETE)
        val upserts = if (everUploaded) {
            listOf(coalesce(existing[deleteKey], noteId, deleteKey, localRev, now))
        } else {
            emptyList()
        }
        return OutboxPlan(upserts = upserts, removals = removals)
    }
}

/**
 * Persistence for [OutboxEntry]. Implemented by `:core:database`.
 *
 * The interface is here rather than there so that `:core:sync` — which owns the
 * semantics — can be tested against a fake, and so the dependency runs
 * `:core:database → :core:sync` and not the other way round.
 */
public interface OutboxStore {

    /**
     * Read this note's pending rows, run [plan] over them, and apply the result —
     * **atomically**.
     *
     * The lambda is not `suspend` because it must be able to run inside a database
     * transaction, and because it is a pure decision ([OutboxCoalescing.plan]): anything
     * that needed to await something would be reading state the transaction cannot see
     * consistently anyway.
     */
    public suspend fun mutate(noteId: DocumentId, plan: (List<OutboxEntry>) -> OutboxPlan)

    /** Rows whose `next_attempt_at` has passed, in drain order. See [OutboxOpType.drainPriority]. */
    public suspend fun due(nowEpochMillis: Long, limit: Int): List<OutboxEntry>

    public suspend fun pendingFor(noteId: DocumentId): List<OutboxEntry>

    public suspend fun all(): List<OutboxEntry>

    /** Reschedule after a failure, keeping the row. */
    public suspend fun reschedule(noteId: DocumentId, key: OutboxKey, attempts: Int, nextAttemptAt: Long)

    /** The op completed and is no longer owed. */
    public suspend fun remove(noteId: DocumentId, key: OutboxKey)

    /**
     * ⭐ Every row this note owes, gone — sign-out, unbind, and *"the remote file was deleted
     * by somebody else"*.
     *
     * A method rather than the `for (opType in OutboxOpType.entries) remove(noteId, opType)`
     * that three call sites used to spell out. That loop was already a hazard — it silently
     * needs editing whenever the vocabulary grows — and M12 broke it outright: a
     * [OutboxOpType.MEDIA_UPLOAD] row is keyed by a recording id the loop cannot know, so the
     * loop would have left it behind, pointing at an account that can no longer send it. One
     * statement over `noteId` cannot have that bug.
     *
     * ⚠️ It **is** destructive, and the recordings it drops are re-derived rather than
     * remembered: `SyncEngine.resumeAccount` reads [RecordingUploads.pendingFor] and enqueues
     * them again. That is why the recording table exists as well as the queue.
     */
    public suspend fun removeAll(noteId: DocumentId)

    public suspend fun count(): Int
}

/**
 * SPEC §7.5 / §7.2 — exponential backoff with jitter, capped.
 *
 * Jitter is not decoration. Every note that failed during one offline period becomes due
 * at the same instant when the network returns, and a fleet of identical retry timers is
 * how a phone turns its own server's brute-force protection on (SPEC §7.2).
 */
public object Backoff {

    public const val BASE_MILLIS: Long = 30_000L
    public const val MAX_MILLIS: Long = 6L * 60 * 60 * 1000

    /** Doubling from [BASE_MILLIS], ±25 %, never past [MAX_MILLIS]. */
    public fun nextAttemptAt(attempts: Int, now: Long, jitter: Double): Long {
        val exponent = attempts.coerceIn(0, MAX_DOUBLINGS)
        val plain = (BASE_MILLIS shl exponent).coerceAtMost(MAX_MILLIS)
        val spread = (plain * JITTER_FRACTION * (jitter.coerceIn(0.0, 1.0) * 2 - 1)).toLong()
        return now + (plain + spread).coerceAtLeast(BASE_MILLIS / 2)
    }

    private const val MAX_DOUBLINGS = 10
    private const val JITTER_FRACTION = 0.25
}
