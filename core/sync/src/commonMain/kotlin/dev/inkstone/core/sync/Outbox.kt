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

    /** Remote delete — soft on Nextcloud, so it lands in the server's trash (SPEC §7.7). */
    DELETE,

    ;

    /**
     * Drain order within one note, low first.
     *
     * MKCOL before everything (there is nowhere to PUT to otherwise), MOVE before UPLOAD
     * (SPEC §7.6 says so explicitly — uploading first would write to the old path and
     * leave the move to shuffle stale bytes), DELETE last so a MOVE-then-DELETE deletes
     * what is at the *new* path rather than 404ing on the old one.
     */
    public val drainPriority: Int get() = ordinal
}

/**
 * One row. Every field is either the coalescing key or scheduling state — there is
 * deliberately nowhere to put bytes.
 *
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
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0L,
    val createdAt: Long = 0L,
)

/**
 * The change [OutboxCoalescing] decided on, ready to apply in one transaction.
 *
 * A plan rather than a sequence of calls so the decision is a pure function of the
 * pending rows — testable without a database, and impossible to apply half of.
 */
public data class OutboxPlan(
    val upserts: List<OutboxEntry> = emptyList(),
    val removals: List<OutboxOpType> = emptyList(),
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
    public fun plan(
        pending: List<OutboxEntry>,
        request: OutboxOpType,
        noteId: DocumentId,
        localRev: Long,
        everUploaded: Boolean,
        now: Long,
    ): OutboxPlan {
        val existing = pending.associateBy { it.opType }
        return when (request) {
            OutboxOpType.DELETE -> planDelete(existing, noteId, localRev, everUploaded, now)
            else -> OutboxPlan(upserts = listOf(coalesce(existing[request], noteId, request, localRev, now)))
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
        opType: OutboxOpType,
        localRev: Long,
        now: Long,
    ): OutboxEntry = OutboxEntry(
        noteId = noteId,
        opType = opType,
        // The only field that always takes the new value: the drainer must upload the
        // newest revision, and the mid-flight guard has to compare against it.
        localRevAtEnqueue = localRev,
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
     */
    private fun planDelete(
        existing: Map<OutboxOpType, OutboxEntry>,
        noteId: DocumentId,
        localRev: Long,
        everUploaded: Boolean,
        now: Long,
    ): OutboxPlan {
        val removals = listOfNotNull(
            OutboxOpType.UPLOAD.takeIf { it in existing },
            OutboxOpType.MOVE.takeIf { it in existing },
            // A folder that was only ever created for a note now being deleted.
            OutboxOpType.MKCOL.takeIf { it in existing },
        )
        val upserts = if (everUploaded) {
            listOf(coalesce(existing[OutboxOpType.DELETE], noteId, OutboxOpType.DELETE, localRev, now))
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
    public suspend fun reschedule(noteId: DocumentId, opType: OutboxOpType, attempts: Int, nextAttemptAt: Long)

    /** The op completed and is no longer owed. */
    public suspend fun remove(noteId: DocumentId, opType: OutboxOpType)

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
