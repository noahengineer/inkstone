package dev.inkstone.core.model

/**
 * SPEC §4.6 — undo is **a new op appended to the log, never a pop**.
 *
 * ```
 * AddObject          ↔ DeleteObject
 * SetTransform       ↔ SetTransform(previous absolute matrix)
 * SetStyle           ↔ SetStyle(previous style)
 * SetZOrder          ↔ SetZOrder(previous key)
 * SetTextContent     ↔ SetTextContent(previous text)
 * SetErasedRegion    ↔ SetErasedRegion(previous absolute region)
 * ```
 *
 * Every one of those is *"write back the value that was there"*, which is only
 * expressible because the registers are absolute (CLAUDE.md rule 8). With deltas the
 * inverse would be another delta, and SPEC §4.2's worked example is what happens
 * next.
 *
 * Undo is **local-only and per-device**: a remote op never enters your undo stack,
 * and undoing never undoes someone else's work. That falls out of the inverse being
 * computed from an op *you* created plus the state before you created it — there is
 * nothing global to consult.
 */
public object Undo {

    /**
     * The op that undoes [op], given the page as it was **before** [op] applied.
     *
     * `before` is what makes this simple. A delete's inverse needs the object's
     * payload, and before the delete the object is still live in the fold — so
     * there is no tombstone side table anywhere in this design.
     *
     * @return null when there is nothing to undo: an op that changed nothing, or one
     *   whose target was not on the page to begin with (a delete whose `AddObject`
     *   has not arrived yet, which is legal — SPEC §4.2).
     */
    // One branch per op type, which is the point: the table above is the specification, and
    // seeing all seven inverses side by side is what makes it checkable. Splitting it up would
    // hide the one thing a reader needs to verify.
    @Suppress("CyclomaticComplexMethod")
    public fun inverseOf(op: PageOp, before: FoldedPage, opId: String, clock: LamportClock): PageOp? = when (op) {
        // It was not there before, so undoing puts it back to not-there. The
        // AddObject stays in the log; only presence moves (see `Presence`).
        is PageOp.AddObject -> PageOp.DeleteObject(opId, clock, op.obj.id)

        // Re-adds the object with the payload it had. Safe because payloads are
        // first-write-wins in the fold, so a re-add can only write presence — it
        // cannot overwrite geometry with a stale copy.
        is PageOp.DeleteObject -> before.find(op.target)?.let { PageOp.AddObject(opId, clock, it) }

        is PageOp.SetTransform -> before.find(op.target)?.let {
            PageOp.SetTransform(opId, clock, op.target, it.transform)
        }

        is PageOp.SetStyle -> before.find(op.target)?.style?.let {
            PageOp.SetStyle(opId, clock, op.target, it)
        }

        is PageOp.SetZOrder -> before.find(op.target)?.let {
            PageOp.SetZOrder(opId, clock, op.target, it.z)
        }

        is PageOp.SetTextContent -> (before.find(op.target) as? TextBoxObject)?.let {
            PageOp.SetTextContent(opId, clock, op.target, it.text)
        }

        // The region as it was — `None` for a first erase, which is why un-erasing is
        // cheap: nothing was destroyed, so nothing is reconstructed (SPEC §3.5).
        is PageOp.SetErasedRegion -> PageOp.SetErasedRegion(
            opId = opId,
            clock = clock,
            target = op.target,
            absolute = before.find(op.target)?.erased ?: ErasedArea.None,
        )

        is PageOp.SetPageMeta -> PageOp.SetPageMeta(
            opId = opId,
            clock = clock,
            background = op.background?.let { before.background },
            size = op.size?.let { before.size },
        )

        // This build does not know what it did, so it cannot know what undoes it.
        is PageOp.Unknown -> null
    }

    /**
     * The inverse of a whole gesture.
     *
     * Reversed, because undoing "delete A then delete B" has to restore B before A for
     * any per-object ordering to come out the same.
     */
    public fun inverseOf(
        ops: List<PageOp>,
        before: FoldedPage,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): List<PageOp> = ops.asReversed().mapNotNull { inverseOf(it, before, nextOpId(), nextClock()) }

    /**
     * Re-stamp a stored op with a fresh id and clock.
     *
     * Applying a stored op verbatim would not work: its clock is older than the state
     * it is being applied to, so the fold would prefer whatever is already there — and
     * [PageLog] would correctly treat it as a late arrival and re-fold. Undo is a new
     * op, so it gets a new clock.
     */
    public fun restamp(op: PageOp, opId: String, clock: LamportClock): PageOp = when (op) {
        is PageOp.AddObject -> op.copy(opId = opId, clock = clock)
        is PageOp.DeleteObject -> op.copy(opId = opId, clock = clock)
        is PageOp.SetTransform -> op.copy(opId = opId, clock = clock)
        is PageOp.SetStyle -> op.copy(opId = opId, clock = clock)
        is PageOp.SetZOrder -> op.copy(opId = opId, clock = clock)
        is PageOp.SetTextContent -> op.copy(opId = opId, clock = clock)
        is PageOp.SetErasedRegion -> op.copy(opId = opId, clock = clock)
        is PageOp.SetPageMeta -> op.copy(opId = opId, clock = clock)
        is PageOp.Unknown -> op.copy(opId = opId, clock = clock)
    }
}

/**
 * One gesture, in both directions.
 *
 * @property forward the ops the gesture appended.
 * @property inverse the ops that undo them, computed at commit time while the state
 *   before the gesture is still in hand.
 */
public data class UndoEntry(val forward: List<PageOp>, val inverse: List<PageOp>) {
    public val isEmpty: Boolean get() = forward.isEmpty() && inverse.isEmpty()
}

/**
 * The undo/redo stacks, at **gesture** grain (SPEC §4.6, as revised in M1: one entry
 * per whole stroke or whole erase drag, not per input sample).
 *
 * ## Why the entries are stored rather than recomputed
 *
 * ROADMAP M2 requires *"undo/redo across 200 ops including an app restart"*. Undoing
 * after a restart means knowing what the page looked like before each gesture, and
 * re-deriving that would mean re-folding the log once per undo step. Instead the
 * inverse is computed when the gesture commits — when `before` is already in hand —
 * and both directions are persisted with the note.
 *
 * Both directions replay by **re-stamping with a fresh clock**, which is only sound
 * because every op is an absolute register: replaying `SetTransform(M)` writes `M`
 * whatever the current value is, and replaying `AddObject` writes presence without
 * touching the payload. A delta-based log could not do this at all.
 *
 * Not thread-safe — it lives beside the edit path, on the UI thread.
 */
public class UndoStack(
    undo: List<UndoEntry> = emptyList(),
    redo: List<UndoEntry> = emptyList(),
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {

    private val undoable = ArrayList(undo)
    private val redoable = ArrayList(redo)

    public val canUndo: Boolean get() = undoable.isNotEmpty()
    public val canRedo: Boolean get() = redoable.isNotEmpty()

    /** For persistence. Oldest first. */
    public val undoEntries: List<UndoEntry> get() = undoable

    public val redoEntries: List<UndoEntry> get() = redoable

    /**
     * Record a committed gesture.
     *
     * A new gesture invalidates the redo branch — the standard linear history, and the
     * same result §4.6's op-log model gives, since a redo op that was never appended
     * simply does not exist.
     */
    public fun commit(entry: UndoEntry) {
        if (entry.isEmpty) return
        undoable += entry
        redoable.clear()
        while (undoable.size > maxDepth) undoable.removeAt(0)
    }

    /** @return the ops to apply, already re-stamped, or an empty list if there is nothing to undo. */
    public fun undo(nextOpId: () -> String, nextClock: () -> LamportClock): List<PageOp> {
        val entry = undoable.removeLastOrNull() ?: return emptyList()
        redoable += entry
        return entry.inverse.map { Undo.restamp(it, nextOpId(), nextClock()) }
    }

    /** @return the ops to apply, already re-stamped, or an empty list if there is nothing to redo. */
    public fun redo(nextOpId: () -> String, nextClock: () -> LamportClock): List<PageOp> {
        val entry = redoable.removeLastOrNull() ?: return emptyList()
        undoable += entry
        return entry.forward.map { Undo.restamp(it, nextOpId(), nextClock()) }
    }

    public fun clear() {
        undoable.clear()
        redoable.clear()
    }

    public companion object {
        /**
         * Deep enough to cover a session's worth of mistakes, bounded so the stacks
         * cannot become the largest thing in the bundle. A stroke's inverse is a
         * `DeleteObject` — a few dozen bytes — but an undone *erase* carries a whole
         * absolute region, and a scribble-erase region is thousands of capsules.
         */
        public const val DEFAULT_MAX_DEPTH: Int = 200
    }
}
