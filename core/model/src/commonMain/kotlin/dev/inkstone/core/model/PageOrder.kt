package dev.inkstone.core.model

/**
 * SPEC §4.3 — page order, and the rule that keeps a page from disappearing.
 *
 * > Page ordering uses fractional keys (`sortKey`), resolved by `(sortKey, pageId)`.
 * > This means two devices adding a page offline both keep their page — a plain LWW
 * > list would silently drop one device's page while orphaning its file. **Any page
 * > not present in a merged order is appended at the end, never dropped.**
 *
 * These functions are the whole of that rule. M6 uses [mergePageOrder] on a pull;
 * M2 uses the rest for add/duplicate/reorder.
 */
public object PageOrder {

    /** Sorted by `(sortKey, pageId)` — the canonical order of a page list. */
    public fun sorted(pages: List<PageRef>): List<PageRef> =
        pages.sortedWith(compareBy({ it.sortKey.value }, { it.id.value }))

    /**
     * Merge two page orders without losing a page from either side.
     *
     * Union by [PageId]: a page in both keeps the entry whose [PageRef.sortKey] the
     * two agree on, or — if they disagree — the lexicographically smaller key, so
     * both devices reach the same answer without exchanging anything else. A page in
     * only one order is kept, never dropped.
     *
     * Deletion is a tombstone (see [PageRef.deleted]) and a tombstone wins over a
     * live entry, because the alternative is a page the user deleted on one device
     * reappearing on the other every time it syncs.
     *
     * ⚠️ **A restore therefore does not propagate, and this KDoc used to claim it did**
     * (*"restoring from trash clears the flag with a fresh entry"*). [reconcile]'s
     * `a.deleted || b.deleted` is unconditional, so freshness is not a thing it can
     * see: once a peer has the tombstone, the peer keeps it, and the *next* merge in
     * the other direction re-deletes the page here. `NoteSession.restorePage` clears
     * the flag in place and nothing it could do instead would help — the missing piece
     * is an ordering between the delete and the restore, i.e. `deleted` as a Lamport
     * register per page (CLAUDE.md rule 8) rather than a boolean OR. That is a manifest
     * change inside `pages[]`, where SPEC §5.8's carry-unknown-fields rule does not
     * reach, so it is not a fix that can be smuggled in; it is recorded in SPEC §4.3
     * and pinned by `PageOrderTest`. No bytes are at risk either way: the page's whole
     * op log survives under the tombstone (CLAUDE.md rule 1) and a re-restore works
     * until the next sync.
     */
    public fun mergePageOrder(local: List<PageRef>, remote: List<PageRef>): List<PageRef> {
        val merged = LinkedHashMap<PageId, PageRef>()
        for (page in local) merged[page.id] = page
        for (page in remote) {
            val existing = merged[page.id]
            merged[page.id] = if (existing == null) page else reconcile(existing, page)
        }
        return sorted(merged.values.toList())
    }

    private fun reconcile(a: PageRef, b: PageRef): PageRef {
        val deleted = a.deleted || b.deleted
        // A deterministic tiebreak both devices compute identically. Not "newest",
        // because newest needs a wall clock and device clocks disagree (SPEC §4.1).
        val key = if (a.sortKey <= b.sortKey) a.sortKey else b.sortKey
        val deletedAt = listOfNotNull(a.deletedEpochMillis, b.deletedEpochMillis).minOrNull()
        return PageRef(
            id = a.id,
            sortKey = key,
            deleted = deleted,
            deletedEpochMillis = deletedAt.takeIf { deleted },
        )
    }

    /**
     * A sort key that places a page at [index] in [pages] — where index 0 is before
     * the first page and `pages.size` is after the last.
     *
     * Only live pages bound the insertion: inserting "after page 3" should not
     * depend on whether a deleted page happens to sit between 3 and 4.
     */
    public fun keyForInsert(pages: List<PageRef>, index: Int): FractionalKey {
        val live = sorted(pages).filterNot { it.deleted }
        val before = live.getOrNull(index - 1)?.sortKey
        val after = live.getOrNull(index)?.sortKey
        return FractionalKey.betweenOrNull(before, after)
            // Keys that came from a foreign or damaged file can be unusable as
            // bounds. Re-keying the whole order is the repair — see `repair`.
            ?: FractionalKey.between(live.lastOrNull()?.sortKey?.takeIf { it.isWellFormed }, null)
    }

    /**
     * Re-key an order whose keys are not usable, preserving the order it currently
     * reads in.
     *
     * A sort key is derived data — page identity and page content are untouched — so
     * rewriting one loses nothing (CLAUDE.md rule 1). This exists because a key can
     * arrive malformed from a file another tool touched, and the alternative to
     * repairing is a document that cannot have a page inserted into it.
     *
     * @return the repaired list, or the input unchanged when every key is fine.
     */
    public fun repair(pages: List<PageRef>): List<PageRef> {
        if (pages.all { it.sortKey.isWellFormed }) return sorted(pages)
        val inOrder = sorted(pages)
        val keys = FractionalKey.sequence(inOrder.size)
        return inOrder.mapIndexed { index, page -> page.copy(sortKey = keys[index]) }
    }
}
