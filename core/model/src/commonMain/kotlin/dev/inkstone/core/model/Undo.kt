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

        // ⭐ The register's previous value, whatever kind of object holds it (M8b).
        //
        // ⚠️ This used to read `(before.find(target) as? TextBoxObject)?.text`, which was complete
        // for as long as a text box was the only thing that could carry one. A shape's record
        // ([ShapeRecord]) lives in this register too, so the cast made *"change a fill, press
        // undo"* return null — and an entry with no inverse is dropped by `UndoStack.commit`, so
        // the press would have done nothing while looking as though it had worked. That is SPEC
        // §4.2's first-`SetStyle` trap, in the register next door.
        //
        // Null when the register was unset before the op — the same honest answer, and the same
        // reason: `SetTextContent.text` is non-null and cannot say "unset". `commit` drops it, so
        // the button stays honest rather than eating a press.
        is PageOp.SetTextContent -> before.textOf(op.target)?.let {
            PageOp.SetTextContent(opId, clock, op.target, it)
        }

        // ⭐ The format register's previous value (M8c, SPEC §4.9.2).
        //
        // Unlike [PageOp.SetTextContent] above, this one has a usable "unset" — the empty
        // record *is* the default presentation, and `ObjectFormat.Default.encode()` produces
        // it. So the first format write on any object is invertible and the whole
        // first-`SetStyle` trap does not apply here. That is a property worth stating rather
        // than rediscovering: it is true only because the record's every field has a default,
        // which is a constraint on [ObjectFormat], not an accident of this method.
        is PageOp.SetFormat -> PageOp.SetFormat(
            opId = opId,
            clock = clock,
            target = op.target,
            record = before.formatOf(op.target).orEmpty(),
        )

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
        is PageOp.SetFormat -> op.copy(opId = opId, clock = clock)
        is PageOp.SetErasedRegion -> op.copy(opId = opId, clock = clock)
        is PageOp.SetPageMeta -> op.copy(opId = opId, clock = clock)
        is PageOp.Unknown -> op.copy(opId = opId, clock = clock)
    }
}

/**
 * One gesture, in both directions, **on one page**.
 *
 * ⚠️ [pageId] is not bookkeeping. Without it this class was a live data bug: the stack was
 * document-wide and [UndoStack.undo] popped whatever was on top, while `NoteSession.undo(pageId)`
 * replayed the result onto whichever page the *caller* named — which is the page on screen. So
 * changing page 1's paper, turning to page 2 and pressing undo wrote the inverse `SetPageMeta`
 * into **page 2's** log: page 2's paper changed, was journaled, and was uploaded, and the user's
 * obvious recovery (press undo again) popped the next entry instead. A stroke undo mis-fired the
 * same way, more quietly — an inverse `DeleteObject` for a page-1 stroke lands in page 2's log as
 * a tombstone for an object that was never there, which the fold accepts by design (SPEC §4.2),
 * so the symptom was simply that undo did nothing.
 *
 * Carrying the page makes the wrong-page write unrepresentable rather than guarded, which is the
 * same move that replaced `apply(pageId, ops, undoable = true)` with three named methods.
 *
 * @property pageId the page the gesture happened on. Its inverse can only be applied there.
 * @property forward the ops the gesture appended.
 * @property inverse the ops that undo them, computed at commit time while the state
 *   before the gesture is still in hand.
 */
public data class UndoEntry(val pageId: PageId, val forward: List<PageOp>, val inverse: List<PageOp>) {
    public val isEmpty: Boolean get() = forward.isEmpty() && inverse.isEmpty()

    /**
     * True when this gesture cannot be undone — [Undo.inverseOf] found no inverse for any of
     * its ops.
     *
     * Not the same as [isEmpty]: this entry *did* something, and pressing undo would do
     * nothing about it. Reachable three ways, and M8a made the third one routine —
     * a gesture of nothing but [PageOp.Unknown]s (a newer build's ops, replayed); a delete of
     * an object whose `AddObject` has not arrived (legal, SPEC §4.2); and the **first restyle
     * of a stroke**, where the previous value of the style register is *unset* and SPEC §4.2's
     * `SetStyle` cannot carry "unset". See [UndoStack.commit].
     */
    public val isUndoable: Boolean get() = inverse.isNotEmpty()
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

    public fun canUndo(pageId: PageId): Boolean = undoable.any { it.pageId == pageId }

    public fun canRedo(pageId: PageId): Boolean = redoable.any { it.pageId == pageId }

    /** For persistence. Oldest first. */
    public val undoEntries: List<UndoEntry> get() = undoable

    public val redoEntries: List<UndoEntry> get() = redoable

    /**
     * Record a committed gesture.
     *
     * A new gesture invalidates the redo branch **of its own page** — the standard linear history,
     * and the same result §4.6's op-log model gives, since a redo op that was never appended simply
     * does not exist. Per page, because the histories are per page: drawing on page 2 is not a
     * reason the user can no longer redo on page 1.
     */
    public fun commit(entry: UndoEntry) {
        if (entry.isEmpty) return
        // ⚠️ **The redo branch dies whatever happens next**, and that is not the same question as
        // whether this gesture can itself be undone. The page has moved on either way, so a redo
        // stored against the old state would re-apply ops on top of something else. Clearing it
        // before the `isUndoable` guard below is deliberate — putting the guard first would leave a
        // stale redo reachable after an edit that is not undoable, which is worse than either.
        redoable.removeAll { it.pageId == entry.pageId }

        // ⚠️ Not `isEmpty` — **`isUndoable`**, and the difference is a dishonest button (M8a).
        //
        // A gesture with ops but no inverse used to be committed, and pressing undo then popped
        // it, applied nothing, and left the user's actual last edit one press further away. That
        // was rare enough to go unnoticed until restyle: SPEC §4.2's `SetStyle` cannot express
        // "back to as-captured", so the *first* recolour of any stroke has no inverse, and
        // "change the colour, press undo" would have done nothing at all — the exact shape of
        // bug CLAUDE.md's definition-of-done #6 is about, shipped in the headline action of the
        // milestone that added it.
        //
        // `:android:ink` fixes the restyle case at the source by writing the capture-time style
        // into the register first, as a gesture of its own — which lands here, has no inverse of
        // its own, and is dropped by this line rather than becoming a second press the user has
        // to make. The two halves are why both exist.
        if (!entry.isUndoable) return
        undoable += entry
        // The cap is a document-wide budget, so the oldest gesture anywhere is the one that goes.
        // What it bounds is `undo.json` and the cost of re-folding from it — **not** the bundle:
        // `undo.json` sits beside the bundle and is not inside it (SPEC §5.1).
        while (undoable.size > maxDepth) undoable.removeAt(0)
    }

    /**
     * Undo the last gesture **on [pageId]**, skipping entries that belong to other pages.
     *
     * ⚠️ Filtering rather than popping the global top is the whole correctness argument (see
     * [UndoEntry]): an entry's inverse is only meaningful on the page it was computed against, so
     * the alternative to filtering is applying it somewhere it does not belong. It also makes the
     * button honest — [canUndo] asks the same question this answers, so "enabled but nothing
     * happens" is not reachable.
     *
     * @return the ops to apply, already re-stamped, or an empty list if this page has nothing to
     *   undo.
     */
    public fun undo(pageId: PageId, nextOpId: () -> String, nextClock: () -> LamportClock): List<PageOp> {
        val index = undoable.indexOfLast { it.pageId == pageId }
        if (index < 0) return emptyList()
        val entry = undoable.removeAt(index)
        redoable += entry
        return entry.inverse.map { Undo.restamp(it, nextOpId(), nextClock()) }
    }

    /** @return the ops to apply, already re-stamped, or an empty list if [pageId] has nothing to redo. */
    public fun redo(pageId: PageId, nextOpId: () -> String, nextClock: () -> LamportClock): List<PageOp> {
        val index = redoable.indexOfLast { it.pageId == pageId }
        if (index < 0) return emptyList()
        val entry = redoable.removeAt(index)
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
