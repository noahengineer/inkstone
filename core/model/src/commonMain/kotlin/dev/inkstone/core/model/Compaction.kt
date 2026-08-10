package dev.inkstone.core.model

/**
 * SPEC §4.7 — when a page's log may be folded into a snapshot.
 *
 * ## The two things the naive rule gets wrong
 *
 * *"Compact when SYNCED"* fails twice:
 *
 * 1. It never compacts a `LOCAL_ONLY` note — which is most notes, and **every** note
 *    before M5 exists.
 * 2. "SYNCED" means *this device uploaded*, not *every peer has seen these ops*.
 *    Compacting while your other tablet holds older ops offline leaves those ops
 *    nowhere to reintegrate: they arrive, they sort before the snapshot, and the only
 *    correct response is to throw the snapshot away (which is why one exists as a
 *    cache and not as the truth).
 *
 * ## The rule
 *
 * Compact when the note is in a state where compaction is meaningful **and** every op
 * being folded is older than the causal-stability horizon: 30 days, or the oldest
 * clock any known device has acked, whichever is more conservative.
 *
 * The threshold is in **bytes**, not op count. "2,000 ops" is about one page of dense
 * handwriting, whereas 2 MB of journal is a real cost wherever it appears.
 */
public object Compaction {

    /** SPEC §4.7 — "threshold in bytes (≈2 MB of journal), not op count". */
    public const val JOURNAL_THRESHOLD_BYTES: Long = 2L * 1024 * 1024

    /** SPEC §4.7 — "30 days, or the oldest clock any known device has acked". */
    public const val STABILITY_HORIZON_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

    /**
     * Whether [page] may be compacted now.
     *
     * @param syncState where the note stands with the server. Compaction is allowed in
     *   `LOCAL_ONLY` and `SYNCED` and nowhere else: a note with pending or failed
     *   uploads has ops the server has not seen, and folding those away is exactly the
     *   case above.
     * @param journalBytes the size of the material that would be folded.
     * @param oldestOpEpochMillis when the oldest op being folded was created, for
     *   display-clock purposes only — this is the one place a wall clock legitimately
     *   appears, because "30 days" is a human duration and not an ordering.
     * @param oldestAckedCounter the lowest Lamport counter any known peer has
     *   acknowledged, or null when nothing is known — in which case the horizon is the
     *   only guard, which is the correct conservative answer for a single-device setup.
     * @param highestFoldedCounter the highest counter that would end up inside the
     *   snapshot.
     */
    @Suppress("LongParameterList") // Every one of these is a separate reason to refuse.
    public fun isAllowed(
        syncState: NoteSyncState,
        journalBytes: Long,
        nowEpochMillis: Long,
        oldestOpEpochMillis: Long,
        oldestAckedCounter: Long?,
        highestFoldedCounter: Long,
    ): Boolean {
        if (syncState != NoteSyncState.LOCAL_ONLY && syncState != NoteSyncState.SYNCED) return false
        if (journalBytes < JOURNAL_THRESHOLD_BYTES) return false
        if (nowEpochMillis - oldestOpEpochMillis < STABILITY_HORIZON_MILLIS) return false
        // A peer that has only acked up to counter N has not seen anything above it,
        // so nothing above N is causally stable.
        if (oldestAckedCounter != null && highestFoldedCounter > oldestAckedCounter) return false
        return true
    }
}

/**
 * A note's standing with the server (SPEC §7.6).
 *
 * Declared here rather than in `:core:sync` because [Compaction] needs it and
 * `:core:model` may not depend on `:core:sync` — the dependency runs the other way.
 * The states themselves are M5's business; the values are fixed now so the storage
 * layer can persist one without a migration later.
 */
public enum class NoteSyncState {
    /** Never bound to a server. The default, and the state most notes stay in. */
    LOCAL_ONLY,

    /** Uploaded, and unchanged since. */
    SYNCED,

    /** Local changes the outbox has not drained yet. */
    PENDING_UPLOAD,

    /** Remote changes not yet applied locally. */
    PENDING_DOWNLOAD,

    /** A non-destructive conflicted copy exists (SPEC §7.7). Appears in "Needs attention". */
    CONFLICTED,

    /**
     * The server refused for a reason waiting will not fix — a full quota, a folder the
     * user may not write to, a 4xx that is not a conflict.
     *
     * Added in M5, and it is SPEC §7.6's `ERROR`. Distinct from [CONFLICTED] because the
     * two need different things from the user: a conflict is resolved by merging (M6),
     * whereas this is resolved by changing something on the server. Distinct from
     * [NEEDS_REAUTH] because the credential is fine. The note's outbox row is kept and
     * parked, never dropped — SPEC §7.7 rule 3: flagged and skipped, everything else
     * keeps syncing.
     */
    ERROR,

    /** A 401. The worker halted and did **not** retry (CLAUDE.md rule 5). */
    NEEDS_REAUTH,

    /**
     * ⭐ **Bound, but its account is signed out** (M6).
     *
     * The note is still linked to a remote file — the binding survives a sign-out so that
     * signing back in resumes it — but nothing will be sent until the user returns.
     *
     * It exists because the alternative was to call this [LOCAL_ONLY], and that is a lie the
     * user can see: `LOCAL_ONLY` means *"never bound to a server"*, so the library showed no
     * sync badge at all on a note that is sitting on Nextcloud right now. "Is this one
     * actually up there?" is the question the badge exists to answer, and answering it
     * "no" for a note that is up there is worse than not having a badge.
     *
     * **Not compactable**, unlike `LOCAL_ONLY` and `SYNCED`. A paused note may hold edits the
     * server has never seen, and folding those into a snapshot is precisely SPEC §4.7's
     * *"compacting while your other tablet holds older ops offline leaves them nowhere to
     * reintegrate"* — the other tablet here being the server itself.
     */
    PAUSED,
}
