package dev.inkstone.core.storage

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.Compaction
import dev.inkstone.core.model.Document
import dev.inkstone.core.model.FoldOutcome
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.InkObject
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.LamportClockGenerator
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageLog
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageOrder
import dev.inkstone.core.model.PageRef
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.TextBoxObject
import dev.inkstone.core.model.Undo
import dev.inkstone.core.model.UndoEntry
import dev.inkstone.core.model.UndoStack

/**
 * An editing session over one note: ops in, journal out, pages folded on demand.
 *
 * This is where SPEC §5.1's two rhythms meet. Edits land in memory immediately and in
 * the journal on the next [flush] (~2 s); the bundle is rewritten only by
 * [materialize], on close or before upload. Nothing here rewrites a document to record
 * a stroke.
 *
 * ## Threading
 *
 * Single-threaded by contract, on whatever thread owns the edit — the UI thread in this
 * app (SPEC §2.4). Ops arrive already stamped, geometry arrives already encoded, and
 * the only work done here is bookkeeping and, on [flush], one suspending append. There
 * is no lock, because a lock on the edit path is a cost with no benefit (CLAUDE.md rule
 * 7).
 *
 * ## Page cache
 *
 * Pages are folded when drawn and evicted when they are not. Eviction is unconditionally
 * safe, which is worth stating plainly: every op is already in the journal before it can
 * be evicted, so the log is the truth and a folded page is only ever a cache of it
 * (ROADMAP M2: *"memory doesn't grow linearly with page count while scrolling"*).
 */
public class NoteSession(
    private val store: NoteStore,
    private val ids: IdFactory,
    private val clock: Clock,
    private var note: OpenNote,
    private val clocks: LamportClockGenerator,
    undo: List<UndoEntry> = emptyList(),
    redo: List<UndoEntry> = emptyList(),
    private val maxCachedPages: Int = DEFAULT_MAX_CACHED_PAGES,
) {

    public var document: Document = note.document
        private set

    /** True after a crash whose journal ended mid-record. Surfaced, not swallowed. */
    public val recoveredFromTornJournal: Boolean = note.journalWasTruncated

    private val undoStack = UndoStack(undo, redo)

    /** Folded pages, most-recently-used last. */
    private val cache = LinkedHashMap<PageId, PageLog>()

    /** Ops written since the last flush, with the page each belongs to. */
    private val pending = ArrayList<PendingOp>()

    private var documentDirty = false

    /** True between a [flush] and the next re-read — see [pageLog]. */
    private var journalHasUnreadFlushes = false

    private var journalBytes: Long = note.journalBytes

    init {
        // The `max + 1` rule (SPEC §4.1) is only as good as what the generator was seeded
        // with, and a generator seeded from the wrong note issues clocks that sort *behind*
        // the note's own history — every op it writes then loses every conflict, silently.
        // Cheap to check, so it is checked rather than documented. Use [NoteSession.open].
        check(clocks.highestSeen >= note.highestClockCounter) {
            "clock generator is behind the note (${clocks.highestSeen} < ${note.highestClockCounter}); " +
                "seed it with OpenNote.highestClockCounter (SPEC §4.1)"
        }
    }

    public val hasUnflushedWork: Boolean get() = pending.isNotEmpty() || documentDirty

    public val canUndo: Boolean get() = undoStack.canUndo

    public val canRedo: Boolean get() = undoStack.canRedo

    public val pageCount: Int get() = document.livePages.size

    // -----------------------------------------------------------------------
    // Editing
    // -----------------------------------------------------------------------

    /** A fresh clock for a locally created op. Every op the caller builds must use one. */
    public fun nextClock(): LamportClock = clocks.next()

    public fun nextOpId(): String = ids.newOpId()

    /**
     * Apply a gesture's ops to [pageId] and record it as one undo entry.
     *
     * The inverse is computed here, before the ops apply, because that is the only
     * moment the previous absolute values are in hand (SPEC §4.6). Doing it later would
     * mean re-folding the page.
     *
     * @param undoable false for an edit that must not enter the undo stack — a remote op
     *   (SPEC §4.6: *"an incoming remote op does not enter your undo stack"*), or a
     *   replay of an op already on the stack.
     */
    public suspend fun apply(pageId: PageId, ops: List<PageOp>, undoable: Boolean = true): FoldOutcome {
        if (ops.isEmpty()) return FoldOutcome.APPENDED
        val log = pageLog(pageId)
        val before = log.state
        val inverse = if (undoable) Undo.inverseOf(ops, before, ::nextOpId, ::nextClock) else emptyList()

        val outcome = log.applyAll(ops)
        for (op in ops) {
            clocks.observe(op.clock)
            pending += PendingOp(op, pageId)
        }
        if (undoable) undoStack.commit(UndoEntry(ops, inverse))

        if (log.snapshotStale) {
            // SPEC §4.7 — the snapshot is a cache, and an op older than it has arrived.
            store.discardSnapshot(document.id)
        }
        return outcome
    }

    /** SPEC §4.6 — appends the inverse ops. Never pops, and never enters the stack again. */
    public suspend fun undo(pageId: PageId): Boolean {
        val ops = undoStack.undo(::nextOpId, ::nextClock)
        if (ops.isEmpty()) return false
        apply(pageId, ops, undoable = false)
        return true
    }

    public suspend fun redo(pageId: PageId): Boolean {
        val ops = undoStack.redo(::nextOpId, ::nextClock)
        if (ops.isEmpty()) return false
        apply(pageId, ops, undoable = false)
        return true
    }

    // -----------------------------------------------------------------------
    // Pages
    // -----------------------------------------------------------------------

    /** The folded page, loading and folding it if it is not cached. */
    public suspend fun page(pageId: PageId): FoldedPage = pageLog(pageId).state

    private suspend fun pageLog(pageId: PageId): PageLog {
        cache.remove(pageId)?.let { existing ->
            cache[pageId] = existing // re-inserted so LinkedHashMap order is LRU
            return existing
        }
        // Folding a page that has been evicted has to find *everything* written to it, and
        // that lives in three places: the bundle, the journal, and — for edits since the
        // last flush — this session's own `pending` list. Missing the third silently loses
        // up to two seconds of ink whenever a page is scrolled away and back.
        if (journalHasUnreadFlushes) {
            note = store.reloadJournal(note)
            journalHasUnreadFlushes = false
        }
        val loaded = store.loadPage(note, pageId)
        val unflushed = pending.filter { it.pageId == pageId }.map { it.op }
        val log = PageLog(
            pageId = pageId,
            ops = loaded.ops + unflushed,
            snapshot = store.readSnapshot(document.id, pageId),
            defaultSize = loaded.size,
            defaultBackground = loaded.background,
        )
        clocks.observeAll(log.ops)
        cache[pageId] = log
        evictIfNeeded()
        return log
    }

    private fun evictIfNeeded() {
        while (cache.size > maxCachedPages) {
            // Safe unconditionally: a folded page is only ever a cache of the log, and the
            // log is on disk or in `pending`. Both are consulted on the way back in.
            cache.remove(cache.keys.first())
        }
    }

    /** Insert a blank page at [index] (0 = before the first live page). */
    public fun addPage(
        index: Int = pageCount,
        size: PageSize = PageSize.Letter,
        background: Background = Background.Blank,
    ): PageId {
        val id = ids.newPageId()
        val key = PageOrder.keyForInsert(document.pages, index)
        document = document.copy(pages = PageOrder.sorted(document.pages + PageRef(id, key)))
        documentDirty = true
        // The page's own metadata is a page op, so it lands in the journal like any other
        // edit and survives a crash before the next materialization.
        cache[id] = PageLog(id, defaultSize = size, defaultBackground = background)
        pending += PendingOp(
            PageOp.SetPageMeta(nextOpId(), nextClock(), background = background, size = size),
            id,
        )
        return id
    }

    /**
     * Delete a page — as a **tombstone**, never a removal (CLAUDE.md rule 1).
     *
     * The page's ops stay in the bundle, so the delete is reversible with
     * [restorePage] and a peer cannot re-add the page by merging an order that still
     * lists it (SPEC §4.3).
     */
    public fun deletePage(pageId: PageId): Boolean {
        val ref = document.page(pageId) ?: return false
        if (ref.deleted) return false
        // Refusing to empty a note is not paternalism: a note with no pages has nothing
        // to draw on and no way back to a page, and "delete the note" is a different
        // action with a different undo (the trash).
        if (pageCount <= 1) return false
        document = document.copy(
            pages = document.pages.map {
                if (it.id == pageId) it.copy(deleted = true, deletedEpochMillis = clock.nowEpochMillis()) else it
            },
        )
        documentDirty = true
        return true
    }

    public fun restorePage(pageId: PageId): Boolean {
        val ref = document.page(pageId) ?: return false
        if (!ref.deleted) return false
        document = document.copy(
            pages = document.pages.map {
                if (it.id == pageId) it.copy(deleted = false, deletedEpochMillis = null) else it
            },
        )
        documentDirty = true
        return true
    }

    /** Move a page to [toIndex] among the live pages. */
    public fun movePage(pageId: PageId, toIndex: Int): Boolean {
        val live = document.livePages
        val from = live.indexOfFirst { it.id == pageId }
        if (from < 0 || from == toIndex) return false
        val without = document.pages.filterNot { it.id == pageId }
        // Insert positions are counted after removal, so "move page 1 to index 3" means
        // what it says rather than landing one short.
        val key = PageOrder.keyForInsert(without, toIndex.coerceIn(0, without.count { !it.deleted }))
        document = document.copy(
            pages = PageOrder.sorted(without + PageRef(pageId, key, deleted = false)),
        )
        documentDirty = true
        return true
    }

    /**
     * Duplicate a page, giving every object a **new** [dev.inkstone.core.model.ObjectId].
     *
     * New ids are not a detail. Copying them would mean two objects with one identity in
     * a grow-only union, and the fold's first-write-wins payload rule would then make one
     * copy's geometry silently win on both pages — SPEC §5.6 names the same failure for
     * recovered imports.
     */
    public suspend fun duplicatePage(pageId: PageId, index: Int = pageCount): PageId? {
        val source = page(pageId)
        val copyId = addPage(index, source.size, source.background)
        val ops = source.objects.map { obj ->
            PageOp.AddObject(
                opId = nextOpId(),
                clock = nextClock(),
                obj = obj.withRegisters().withNewId(ids.newObjectId()),
            )
        }
        apply(copyId, ops, undoable = false)
        return copyId
    }

    public fun rename(title: String) {
        if (title == document.title) return
        document = document.copy(title = title)
        documentDirty = true
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    /**
     * SPEC §5.1 — **autosave.** One append, no rewrite.
     *
     * Called on a ~2 s timer and again when the note closes. Idempotent when there is
     * nothing to write, so the timer can be dumb.
     *
     * @return the note's new `local_rev`, or the old one when nothing was pending.
     */
    public suspend fun flush(): Long {
        if (!hasUnflushedWork) return document.localRev
        val ops = pending.map { it.op }
        val pages = pending.associate { it.op to it.pageId }
        val revision = store.appendOps(
            note = note.copy(document = document),
            ops = ops,
            pages = pages,
            document = document.takeIf { documentDirty },
        )
        document = document.copy(localRev = revision)
        note = note.copy(document = document)
        journalBytes += ops.size.toLong() * ESTIMATED_OP_BYTES
        pending.clear()
        documentDirty = false
        journalHasUnreadFlushes = true
        // Undo state is local and follows the same rhythm — it must survive the same
        // crash the journal survives (ROADMAP M2: undo across an app restart).
        store.saveUndo(document.id, undoStack.undoEntries, undoStack.redoEntries)
        return revision
    }

    /**
     * Fold the journal into the bundle. On close, before upload, or on idle-while-charging
     * — **never on the ink path** (SPEC §5.1).
     *
     * Every page the note has must be written, not only the cached ones, or materializing
     * would drop the pages nobody happened to look at. Pages that were never folded are
     * copied straight from the old bundle, which costs one read and no fold.
     */
    public suspend fun materialize() {
        flush()
        // Everything just flushed is on disk and *not* in the in-memory view of the journal,
        // and most pages of a large note are not in the cache either. Without this re-read,
        // materializing writes an empty page for every page that was evicted — which is to
        // say it destroys the session's work on all but the last few pages touched. Found by
        // `the cost of opening a note does not grow with what is drawn on its pages`.
        note = store.reloadJournal(note.copy(document = document))
        journalHasUnreadFlushes = false

        val pages = LinkedHashMap<PageId, PageContents>()
        for (ref in document.pages) {
            val cached = cache[ref.id]
            pages[ref.id] = if (cached != null) {
                PageContents(cached.state.size, cached.state.background, cached.ops)
            } else {
                val loaded = store.loadPage(note, ref.id)
                PageContents(loaded.size, loaded.background, loaded.ops)
            }
        }
        note = store.materialize(note.copy(document = document), pages)
        document = note.document
        journalBytes = note.journalBytes
        // The cached logs' ops are now in the bundle; keeping them would double-count
        // them on the next materialize.
        cache.clear()
    }

    /** Materialize, assert SPEC §5.1's staleness invariant, and compact if it is due. */
    public suspend fun close(syncState: NoteSyncState = NoteSyncState.LOCAL_ONLY) {
        materialize()
        store.assertNotStale(document.id)
        compactIfDue(syncState)
    }

    /**
     * SPEC §4.7 — write a snapshot when the log is big enough and old enough.
     *
     * Deliberately not called from [flush]. Compaction reads and folds a page's whole
     * log, which is not work to do on a two-second timer while someone is writing.
     */
    public suspend fun compactIfDue(syncState: NoteSyncState, oldestAckedCounter: Long? = null) {
        val allowed = Compaction.isAllowed(
            syncState = syncState,
            journalBytes = journalBytes,
            nowEpochMillis = clock.nowEpochMillis(),
            oldestOpEpochMillis = document.createdEpochMillis,
            oldestAckedCounter = oldestAckedCounter,
            highestFoldedCounter = clocks.highestSeen,
        )
        if (!allowed) return
        for (ref in document.livePages) {
            store.writeSnapshot(document.id, page(ref.id), document.deviceId)
        }
    }

    private data class PendingOp(val op: PageOp, val pageId: PageId)

    public companion object {
        /**
         * The way to start a session: seeds the Lamport generator from the note and restores
         * the local undo stacks.
         *
         * The constructor takes both as parameters because a test needs to control them, but
         * getting either wrong is a silent data bug — a stale clock loses every conflict, and
         * a missing undo stack loses the session's history across a restart. So the normal
         * path is one call that cannot get them wrong.
         */
        public suspend fun open(
            store: NoteStore,
            ids: IdFactory,
            clock: Clock,
            note: OpenNote,
            deviceId: String,
        ): NoteSession {
            val (undo, redo) = store.loadUndo(note.document.id)
            return NoteSession(
                store = store,
                ids = ids,
                clock = clock,
                note = note,
                // SPEC §4.1 — "counter = (max counter across all loaded ops) + 1".
                clocks = LamportClockGenerator(deviceId, note.highestClockCounter),
                undo = undo,
                redo = redo,
            )
        }

        /**
         * How many folded pages to keep. Sized for a two-page spread plus what is either
         * side of it, so ordinary paging never re-folds and a 200-page notebook never
         * holds 200 pages of geometry.
         */
        public const val DEFAULT_MAX_CACHED_PAGES: Int = 6

        /**
         * A rough per-op journal cost, for the compaction threshold only.
         *
         * The real size is known after the append, but reading the file back to find it
         * would defeat the point of an append-only journal. Compaction's trigger is a
         * heuristic about when work is worth doing, so an estimate is the right precision
         * — and it errs high, which delays compaction rather than triggering it early.
         */
        private const val ESTIMATED_OP_BYTES = 512L
    }
}

/**
 * A copy of this object under a new identity.
 *
 * Lives here rather than on [dev.inkstone.core.model.InkObject] because re-identifying an
 * object is a storage-layer operation with exactly one legitimate caller — duplicating a
 * page. Offering it on the model type would make it look like something an editor may do,
 * and an object that changes id mid-life is unmergeable.
 */
private fun InkObject.withNewId(id: ObjectId): InkObject = when (this) {
    is StrokeObject -> copy(id = id)
    is ImageObject -> copy(id = id)
    is TextBoxObject -> copy(id = id)
    is ShapeObject -> copy(id = id)
}
