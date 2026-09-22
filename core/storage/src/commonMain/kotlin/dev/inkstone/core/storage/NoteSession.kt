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
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageLog
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageOrder
import dev.inkstone.core.model.PageRef
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PageText
import dev.inkstone.core.model.RecognisedLine
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.TextBoxObject
import dev.inkstone.core.model.Undo
import dev.inkstone.core.model.UndoEntry
import dev.inkstone.core.model.UndoStack
import kotlinx.serialization.json.JsonElement

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

    /**
     * SPEC §5.5 text produced since the last materialize, per page — see [recognised].
     *
     * ⚠️ Deliberately **not** in [cache]. That is an LRU over folded page logs and it is
     * evicted at [maxCachedPages]; recognised text that fell out of it would be silently
     * dropped by the next materialize, which is the same shape of bug as the one
     * `reloadJournal` exists to prevent one level down. A page's text is a few kilobytes and
     * a recognition pass covers the pages that changed, so this map is small and short-lived
     * — [materialize] clears it.
     */
    private val pendingText = LinkedHashMap<PageId, ByteArray>()

    /**
     * Pages whose stored text a merge invalidated — see [reconcileText].
     *
     * ⚠️ **A separate set, because "no pending text" and "delete the stored text" are different
     * instructions to [materialize] and it only sees one map.** Without this, dropping a page's
     * stale text does nothing at all: `pendingText` has no entry, so materialize falls through
     * to the reader's copy and writes the very bytes the merge just decided were wrong. The
     * page would then be published, indexed and searched against ink it does not describe, and
     * the only symptom is that search says a page contains a word it no longer contains.
     */
    private val clearedText = LinkedHashSet<PageId>()

    private var documentDirty = false

    /** True between a [flush] and the next re-read — see [pageLog]. */
    private var journalHasUnreadFlushes = false

    /** The journal's size, as far as this session knows it. Half of [foldableBytes]. */
    private var journalBytes: Long = note.journalBytes

    /**
     * The **bundle's** op count — the other half, and the half that was missing.
     *
     * ⚠️ SPEC §4.7's threshold is *"≈2 MB of journal"*, and since M2 the journal has not been
     * the material that gets folded. It is only the delta since the last materialization; the
     * log a page re-folds at every open lives in the bundle, and [NoteStore.materialize]
     * resets the journal to its 8-byte header. Every production caller of [compactIfDue]
     * materializes on the line before it, so the gate was asked whether 8 bytes exceeded 2 MB
     * — every time, on every note, forever. See [compactIfDue].
     *
     * Set by [materialize], which already holds every page's ops in order to write them, so
     * the measurement is free. Zero until then, which is the conservative direction: a session
     * that has not materialized has not read the bundle's log, and under-measuring refuses a
     * compaction rather than allowing one.
     *
     * A **count** rather than the bundle's size on disk, converted by [ESTIMATED_OP_BYTES].
     * That is not laziness about reading a file size: the cost a snapshot removes is one fold
     * step per op, and a stroke's geometry is opaque bytes the fold never looks at (SPEC §5.4)
     * — so a note of few enormous strokes is cheap to open and a note of many small ones is
     * not, which is the opposite of what its byte size says. SPEC §4.7 states the threshold in
     * bytes because in M2's design the journal *was* the whole log; the quantity it is reaching
     * for is the work, and the work is per op.
     */
    private var bundleOpCount: Int = 0

    /**
     * SPEC §4.7's size gate: everything a snapshot would save re-folding.
     *
     * The two terms never overlap — [materialize] moves the journal's ops into the bundle and
     * resets the journal in the same step — so this is a sum and not a maximum.
     */
    private val foldableBytes: Long get() = journalBytes + bundleOpCount * ESTIMATED_OP_BYTES

    /**
     * ⭐ **When this note's ops last changed, as far as this session can honestly tell —
     * SPEC §4.7's causal-stability horizon, read from something [materialize] does not reset.**
     *
     * The horizon keeps an op out of a snapshot until every peer has plausibly seen it. SPEC
     * §4.7 asks that *"all ops being folded are older than the causal-stability horizon"* and
     * [Compaction.isAllowed] tests one number for it, so the number has to bound **the newest
     * op a snapshot would contain** — and it has to survive the materialize that runs
     * immediately before the gate.
     *
     * ⚠️ `document.modifiedEpochMillis` is neither of those. [NoteStore.materialize] stamps it
     * with `now`, so the gate was being asked *"is a note that was written to this instant more
     * than thirty days old?"*. It answered no every time. This is the same shape as the two
     * corrections SPEC §4.7 already records from M6 — a gate tested against a value the step
     * before it had just overwritten — and it is why nothing in this app has ever compacted.
     *
     * Two sources:
     *
     * - **At open**, the `modifiedEpochMillis` the note carried *in* — the instant the bundle
     *   holding its log was written, so every op inside it was created no later than that.
     * - **After that**, [flush], which is the only path an op takes to disk. A flush carrying
     *   only document metadata — a rename, a page move — moves this too, although nothing it
     *   wrote can end up in a snapshot. That is the conservative direction and it is not worth
     *   a second field to avoid.
     *
     * ⚠️ And `modifiedEpochMillis` **0 means unknown, not 1970** — it is the field's default, so
     * it is what a manifest written before the field existed reads back as (`LibraryRepository`
     * spells the same sentinel out with `takeIf { it > 0 }`). Taken literally it is an age of
     * fifty-six years, which clears every horizon there could ever be: the one input the gate
     * cannot verify would become the one input that always says yes.
     *
     * ## The one gap left, stated rather than hidden
     *
     * A **journal left behind by a crashed session** holds ops written *after* the materialize
     * that stamped `modifiedEpochMillis`, so for those ops this is not a bound at all. The next
     * session inherits the old stamp and can compact ops that are minutes old.
     *
     * That is a known imprecision and not a data risk, because of what a snapshot is: the raw
     * log is kept (SPEC §4.7), a peer op that sorts before the snapshot sets `snapshotStale`
     * and re-folds from the log (CLAUDE.md rule 9), and the whole cost is a cache thrown away.
     * Closing it would need a wall clock per journal record, which is a format change — and
     * `MergeFoundationTest` and SPEC §13.2 row 8 both compact from exactly this shape of
     * session, deliberately, because a materialize is what empties a journal. Do not "fix" it
     * by treating an open journal as recent without changing those two first.
     *
     * Erring recent refuses a compaction, which costs disk and never data.
     */
    private var contentChangedEpochMillis: Long =
        note.document.modifiedEpochMillis.takeIf { it > 0L } ?: clock.nowEpochMillis()

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

    /**
     * Whether [pageId] has a gesture to undo.
     *
     * ⚠️ Per page, and it takes the page as an argument for the same reason [UndoEntry] carries
     * one: the histories are per page, so a document-wide answer would enable the button on a page
     * whose [undo] can only be a no-op — or, before the page moved onto the entry, could only be a
     * write to the wrong page.
     */
    public fun canUndo(pageId: PageId): Boolean = undoStack.canUndo(pageId)

    public fun canRedo(pageId: PageId): Boolean = undoStack.canRedo(pageId)

    public val pageCount: Int get() = document.livePages.size

    // -----------------------------------------------------------------------
    // Editing
    // -----------------------------------------------------------------------

    /** A fresh clock for a locally created op. Every op the caller builds must use one. */
    public fun nextClock(): LamportClock = clocks.next()

    public fun nextOpId(): String = ids.newOpId()

    /**
     * Apply a **local gesture's** ops to [pageId] and record it as one undo entry.
     *
     * The inverse is computed here, before the ops apply, because that is the only
     * moment the previous absolute values are in hand (SPEC §4.6). Doing it later would
     * mean re-folding the page.
     *
     * ⭐ **There is deliberately no `undoable` flag** — see [merge]. This used to be
     * `apply(pageId, ops, undoable: Boolean = true)`, and the default was the dangerous
     * one: a caller that meant to ingest a peer's ops and forgot the third argument put
     * a collaborator's strokes on the *local* undo stack, [flush] persisted that to
     * `undo.json`, and the user's next two-finger tap deleted work they had never made —
     * durably, surviving a restart. SPEC §7.7 and §4.6 both forbid it, and nothing in
     * `UndoStack` could enforce it because it was a property of the call site alone.
     *
     * Three named methods replace the flag, so "forgot an argument" is no longer a
     * reachable state: [apply] for a local gesture, [replay] for an op that must not be
     * recorded again, [merge] for anything from a peer.
     */
    public suspend fun apply(pageId: PageId, ops: List<PageOp>): FoldOutcome {
        if (ops.isEmpty()) return FoldOutcome.APPENDED
        val log = pageLog(pageId)
        val inverse = Undo.inverseOf(ops, log.state, ::nextOpId, ::nextClock)
        val outcome = absorb(log, pageId, ops)
        undoStack.commit(UndoEntry(pageId, ops, inverse))
        return outcome
    }

    /**
     * Apply ops that must **not** produce a new undo entry, but are this device's own.
     *
     * Undo and redo (their inverses are already on the stack — SPEC §4.6: undo is *"a new
     * op appended to the log, never a pop"*), a page duplication, and fixture seeding.
     *
     * **Not for remote ops.** It does not subtract by `opId`, so re-applying a peer's log
     * would grow this note's journal by the whole of it every pull. Use [merge].
     */
    public suspend fun replay(pageId: PageId, ops: List<PageOp>): FoldOutcome {
        if (ops.isEmpty()) return FoldOutcome.APPENDED
        return absorb(pageLog(pageId), pageId, ops)
    }

    /**
     * ⭐ **Ingest a peer's ops (SPEC §7.7 layer 1). The only door remote work comes through.**
     *
     * Three things distinguish this from [apply], and each is a rule from a different
     * section of SPEC:
     *
     * 1. **Nothing reaches the undo stack.** SPEC §4.6: *"an incoming remote op does not
     *    enter your undo stack, and undoing does not undo someone else's work."*
     * 2. **Ops already in this page's log are subtracted by `opId`.** A pull delivers the
     *    peer's *whole* page log, most of which is this device's own ops coming back. The
     *    fold would survive the duplicates — every op is an absolute register and
     *    `AddObject` is first-write-wins per `ObjectId`, so state is unaffected — but the
     *    journal would grow by the size of the note on every poll, forever.
     * 3. **The returned [FoldOutcome] is the caller's business.** `REFOLDED` means an op
     *    sorted before state already applied and the page was re-derived from scratch
     *    (CLAUDE.md rule 9 — never patch in place); `SNAPSHOT_STALE` additionally means the
     *    compaction snapshot was not a valid base and has been discarded (SPEC §4.7). A
     *    caller that folded a union itself and assigned the result would bypass both
     *    signals, and SPEC §13.2 row 8 would fail in a way no local test reproduces.
     *
     * @return the worst outcome across [remoteOps], or [FoldOutcome.APPENDED] when every op
     *   was already known.
     */
    public suspend fun merge(pageId: PageId, remoteOps: List<PageOp>): FoldOutcome {
        if (remoteOps.isEmpty()) return FoldOutcome.APPENDED
        val log = pageLog(pageId)
        val known = log.ops.mapTo(HashSet()) { it.opId }
        val novel = remoteOps.filterNot { it.opId in known }
        if (novel.isEmpty()) return FoldOutcome.APPENDED
        return absorb(log, pageId, novel)
    }

    /**
     * The half [apply], [replay] and [merge] share: fold, journal, observe, and honour
     * CLAUDE.md rule 9's snapshot signal.
     *
     * `clocks.observe` per op is not optional. A path that wrote ops straight to the
     * journal without it would leave this device's counter behind the peer's, and then
     * *every subsequent local op* would sort behind the peer's history and lose every
     * conflict it took part in — silently, with all ops present (SPEC §4.1's `max + 1`).
     */
    private suspend fun absorb(log: PageLog, pageId: PageId, ops: List<PageOp>): FoldOutcome {
        val outcome = log.applyAll(ops)
        for (op in ops) {
            clocks.observe(op.clock)
            pending += PendingOp(op, pageId)
        }
        if (log.snapshotStale) {
            // SPEC §4.7 — the snapshot is a cache, and an op older than it has arrived.
            store.discardSnapshot(document.id)
        }
        return outcome
    }

    /** Every `opId` already in [pageId]'s log — what [merge] subtracts against. */
    public suspend fun opIdsOn(pageId: PageId): Set<String> = pageLog(pageId).ops.mapTo(HashSet()) { it.opId }

    /** SPEC §4.6 — appends the inverse ops. Never pops, and never enters the stack again. */
    public suspend fun undo(pageId: PageId): Boolean {
        val ops = undoStack.undo(pageId, ::nextOpId, ::nextClock)
        if (ops.isEmpty()) return false
        replay(pageId, ops)
        return true
    }

    public suspend fun redo(pageId: PageId): Boolean {
        val ops = undoStack.redo(pageId, ::nextOpId, ::nextClock)
        if (ops.isEmpty()) return false
        replay(pageId, ops)
        return true
    }

    // -----------------------------------------------------------------------
    // Pages
    // -----------------------------------------------------------------------

    /** The folded page, loading and folding it if it is not cached. */
    public suspend fun page(pageId: PageId): FoldedPage = pageLog(pageId).state

    // -----------------------------------------------------------------------
    // SPEC §5.5 / §8 — recognised text
    // -----------------------------------------------------------------------

    /**
     * This page's recognised text, **or null if it is stale** (SPEC §5.5).
     *
     * Staleness is not a background job's problem to notice: the stored text carries the
     * digest of the ink it was read from, so a word erased two seconds ago makes this return
     * null immediately, everywhere, without anything having to run. The search index and the
     * PDF text layer both go through here for exactly that reason — a page whose ink has
     * moved on must not be published with text describing what used to be on it.
     */
    public suspend fun recognisedText(pageId: PageId): PageText? {
        val bytes = pendingText[pageId]
            ?: note.reader?.pageText(pageId).takeIf { pageId !in clearedText }
            ?: return null
        val text = PageTextCodec.decode(bytes) ?: return null
        return text.takeIf { it.describes(PageTextCodec.inkDigestOf(page(pageId))) }
    }

    /**
     * Record what the recogniser made of [pageId]. Written to the bundle by the next
     * [materialize] — recognition is advisory (SPEC §8.3) and never worth a bundle rewrite
     * of its own.
     *
     * ⚠️ **The digest is computed here, from the page as it is right now**, rather than being
     * passed in by the caller. Recognition is asynchronous by construction — SPEC §8.3 debounces
     * it three seconds behind the pen — so the ink can and does change while it runs, and a
     * caller stamping the digest it saw when it *started* would certify text against ink that
     * no longer exists. Computing it at the moment of recording makes that particular lie
     * impossible; the text is then either correct or detectably stale, never confidently wrong.
     *
     * @return false when the ink moved under the recogniser, in which case nothing is stored.
     */
    public suspend fun recognised(pageId: PageId, lines: List<RecognisedLine>, recogniser: String): Boolean {
        if (document.pages.none { it.id == pageId && !it.deleted }) return false
        val text = PageText(
            pageId = pageId,
            inkDigest = PageTextCodec.inkDigestOf(page(pageId)),
            recogniser = recogniser,
            lines = lines,
        )
        pendingText[pageId] = PageTextCodec.encode(text)
        return true
    }

    /**
     * ⭐ Settle one page's recognised text against a peer's — SPEC §5.5, the rule in [PageTextMerge].
     *
     * Called by [NoteMerge] for every live page where *either* side has text. Both devices run
     * the identical function over the identical candidate set and the identical merged page, so
     * they reach the identical answer without exchanging anything further.
     *
     * ⚠️ **A page neither side has text for is not passed here at all**, and that is the reason
     * this is cheap. Deciding requires the page's digest, which requires folding it; a note whose
     * pages have never been recognised therefore costs one `pageText` lookup per page — a small
     * read from an already-open ZIP directory — and folds nothing.
     *
     * @param remote the peer's `text/<pageId>.json` bytes, or null when it has none.
     * @return what happened, for the merge report. A [TextOutcome.DROPPED] is normal and expected
     *   after a real two-device merge: the union of two devices' strokes is ink neither of them
     *   recognised, so neither one's text describes it.
     */
    public suspend fun reconcileText(pageId: PageId, remote: ByteArray?): TextOutcome {
        val localBytes = pendingText[pageId] ?: note.reader?.pageText(pageId).takeIf { pageId !in clearedText }
        if (localBytes == null && remote == null) return TextOutcome.NEITHER

        val local = localBytes?.let(PageTextCodec::decode)
        val chosen = PageTextMerge.choose(local, remote?.let(PageTextCodec::decode), inkDigestOf(pageId))
        return when {
            chosen == null -> {
                // Explicit tombstone rather than "leave it alone": the bundle's own copy is
                // what materialize would otherwise carry forward, so not writing anything
                // here is how stale text survives a merge that invalidated it.
                pendingText.remove(pageId)
                clearedText += pageId
                if (local == null) TextOutcome.NEITHER else TextOutcome.DROPPED
            }

            chosen === local -> TextOutcome.KEPT_LOCAL

            else -> {
                pendingText[pageId] = PageTextCodec.encode(chosen)
                clearedText -= pageId
                TextOutcome.ADOPTED_REMOTE
            }
        }
    }

    /** The digest recognition would be certified against right now. For the merge rule. */
    public suspend fun inkDigestOf(pageId: PageId): String = PageTextCodec.inkDigestOf(page(pageId))

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
            defaultCanvas = loaded.canvas,
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
        cache[id] = PageLog(id, defaultCanvas = PageCanvas.fixed(size), defaultBackground = background)
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

    /**
     * Un-delete a page. **Local only — a restore does not reach the other device.**
     *
     * Clearing the flag in place is not the reason, and neither would a fresh [PageRef] be a fix:
     * `PageOrder.reconcile` ORs the two `deleted` flags unconditionally, so once a peer holds the
     * tombstone the peer keeps it and the next merge in the other direction re-deletes the page
     * here. Ordering a delete against a restore needs a Lamport register on `deleted` (CLAUDE.md
     * rule 8), which is a manifest change inside `pages[]` — see `PageOrder.mergePageOrder` and
     * SPEC §4.3. Nothing is at risk in the meantime: the page's whole op log survives under the
     * tombstone (CLAUDE.md rule 1), so restoring again works, and works until the next sync.
     */
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
     *
     * ⚠️ And new ids mean anything that *names* an id has to be re-targeted, which since M8b is a
     * real case: a connector's binding lives in the `SetTextContent` register (`ShapeRecord`).
     * [copiedObjectOps] is the one implementation of both halves — see its KDoc for what a bare
     * `AddObject` loop lost.
     */
    public suspend fun duplicatePage(pageId: PageId, index: Int = pageCount): PageId? {
        val source = page(pageId)
        val copyId = addPage(index, source.size, source.background)
        replay(copyId, copiedObjectOps(source, ids::newObjectId, ::nextOpId, ::nextClock))
        return copyId
    }

    /**
     * Rename the note, stamping the LWW clock SPEC §7.7 layer 2 resolves with.
     *
     * The clock is what makes "the title is LWW" mean anything across two devices — see
     * [dev.inkstone.core.model.Document.titleClock]. Taking one from [nextClock] costs a
     * counter increment and puts the rename in the same total order as every other edit,
     * which is the only ordering this project trusts (SPEC §4.1).
     */
    public fun rename(title: String) {
        if (title == document.title) return
        document = document.copy(title = title, titleClock = nextClock())
        documentDirty = true
    }

    /**
     * SPEC §7.7 layer 2 — merge a peer's document metadata into this one.
     *
     * ⚠️ **Call this BEFORE merging any page's ops, and this is the single worst failure
     * mode available in this class.** [merge] will happily accept ops for a page the
     * document has never heard of: [pageLog] falls through to `store.loadPage`, which
     * answers with defaults for an unknown page, and the ops are journaled. Then
     * `BundleWriter.write` iterates `document.pages` and skips any page not in it, and
     * `NoteStore.materialize` resets the journal immediately afterwards. A remote page's
     * ops journaled without its `PageRef` in [document] are therefore **destroyed,
     * permanently, with no error** — and the result is a valid PDF with the right page
     * count and a missing page.
     *
     * What is taken from [remote] is deliberately only two things:
     *
     * - **Page order**, through [PageOrder.mergePageOrder] then [PageOrder.repair]. Union,
     *   never replacement (SPEC §4.3: *"any page not present in a merged order is appended
     *   at the end, never dropped"*). `repair` is not optional: `mergePageOrder` does not
     *   validate a key's well-formedness, so a malformed `sortKey` from a peer's file
     *   survives the merge and only surfaces later at `keyForInsert`, which silently appends
     *   at the end instead of inserting where the user asked.
     * - **Title and its clock**, resolved by [Document.resolveTitle] — and the peer's clock is
     *   *observed* whether or not it won, exactly as an absorbed op's is.
     *
     * Everything else stays local. In particular **`localRev` is never adopted** — see its
     * KDoc; a peer's higher revision jumps this device's counter past what the outbox
     * recorded and makes the merged work invisible to sync forever.
     *
     * @return true when the local title lost to the remote's, so the caller can record the
     *   override for SPEC §7.7's *"Needs attention"* list rather than discarding it silently.
     */
    public fun mergeDocument(remote: Document): Boolean {
        // ⭐ A peer's rename is a clock this device has now seen, so it is folded into the generator
        // like any absorbed op's (see [absorb]) — **whether or not it won**. Without this the next
        // rename made here is issued *behind* the peer's, the two clocks come out with equal
        // counters, and which title survives is then settled by a lexicographic comparison of two
        // UUIDv7 device ids: on the losing half of that coin flip the user's rename shows locally,
        // is refused by the peer, and is reverted on the next pull. No error, every op present.
        remote.titleClock?.let(clocks::observe)
        val (title, titleClock) = document.resolveTitle(remote.title, remote.titleClock)
        val titleOverridden = title != document.title
        document = document.copy(
            title = title,
            titleClock = titleClock,
            pages = PageOrder.repair(PageOrder.mergePageOrder(document.pages, remote.pages)),
        )
        documentDirty = true
        return titleOverridden
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
        // The one place an op becomes durable, and therefore the one place that can say the
        // note's content has moved. SPEC §4.7's horizon is read off this — see the field.
        contentChangedEpochMillis = clock.nowEpochMillis()
        pending.clear()
        documentDirty = false
        journalHasUnreadFlushes = true
        // Undo state is local and follows the same rhythm — it must survive the same
        // crash the journal survives (ROADMAP M2: undo across an app restart).
        //
        // ⚠️ **Snapshotted, because the encode may not run on this thread.** `undoEntries` and
        // `redoEntries` are `get()`s onto `UndoStack`'s own mutable lists, and `NoteStore` is
        // given a `saveContext` by the editor so this write does not block the UI thread. Handing
        // the live lists across that boundary would let a stack the user is still pressing undo on
        // be read while it is being serialised. Copying 200 references is not a cost worth
        // reasoning about; a torn undo file is.
        store.saveUndo(document.id, undoStack.undoEntries.toList(), undoStack.redoEntries.toList())
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
    public suspend fun materialize(
        extraCarried: Map<String, ByteArray> = emptyMap(),
        extraCarriedManifest: Map<String, JsonElement> = emptyMap(),
    ) {
        flush()
        // Everything just flushed is on disk and *not* in the in-memory view of the journal,
        // and most pages of a large note are not in the cache either. Without this re-read,
        // materializing writes an empty page for every page that was evicted — which is to
        // say it destroys the session's work on all but the last few pages touched. Found by
        // `the cost of opening a note does not grow with what is drawn on its pages`.
        note = store.reloadJournal(note.copy(document = document))
        journalHasUnreadFlushes = false

        val pages = LinkedHashMap<PageId, PageContents>()
        var foldable = 0
        for (ref in document.pages) {
            val cached = cache[ref.id]
            // SPEC §5.5 — text this session recognised wins over what is in the bundle;
            // otherwise the bundle's own copy is carried forward. Read straight from the
            // reader rather than through the fold, so a page nobody looked at keeps its
            // text without being folded to find that out.
            val text = pendingText[ref.id] ?: note.reader?.pageText(ref.id).takeIf { ref.id !in clearedText }
            val contents = if (cached != null) {
                PageContents(cached.state.canvas, cached.state.background, cached.ops, text)
            } else {
                val loaded = store.loadPage(note, ref.id)
                PageContents(loaded.canvas, loaded.background, loaded.ops, text)
            }
            pages[ref.id] = contents
            // SPEC §4.7's size gate, measured here because this is the only moment the whole
            // log is in hand anyway. Live pages only: a tombstoned page's ops stay in the
            // bundle (CLAUDE.md rule 1) but nothing folds them, so they are not a cost a
            // snapshot could remove. See [bundleOpCount].
            if (!ref.deleted) foldable += contents.ops.size
        }
        note = store.materialize(note.copy(document = document), pages, extraCarried, extraCarriedManifest)
        bundleOpCount = foldable
        pendingText.clear()
        clearedText.clear()
        document = note.document
        journalBytes = note.journalBytes
        // The cached logs' ops are now in the bundle; keeping them would double-count
        // them on the next materialize.
        cache.clear()
    }

    /**
     * Materialize, assert SPEC §5.1's staleness invariant, and compact if it is due.
     *
     * ⚠️ **[syncState] has no default, deliberately.** It used to default to `LOCAL_ONLY`,
     * and every caller took the default — so `Compaction.isAllowed`'s first and most
     * important refusal was dead code in production: a `CONFLICTED` or `PENDING_UPLOAD`
     * note was compacted as though it were a note the server had never heard of, which is
     * exactly the case SPEC §4.7 wrote that check to prevent. Passing the note's real state
     * is not something a call site can now forget to do.
     */
    public suspend fun close(syncState: NoteSyncState) {
        materialize()
        store.assertNotStale(document.id)
        // Null, said out loud. A session cannot know what a peer has acked — that lives in
        // the `peerClock` table behind `:app`'s `LibraryUpdates` — so this path is SPEC
        // §4.7's 30-day horizon and nothing else. Which is the correct conservative answer
        // for a single-device install, and *not* the correct one for the two-device case the
        // horizon exists for: `NoteEditor.materializeNow` spells these three steps out
        // instead of calling `close` precisely so it can supply the counter.
        compactIfDue(syncState, oldestAckedCounter = null)
    }

    /**
     * SPEC §4.7 — write a snapshot when the log is big enough and old enough.
     *
     * Deliberately not called from [flush]. Compaction reads and folds a page's whole
     * log, which is not work to do on a two-second timer while someone is writing.
     *
     * ⚠️ **[oldestAckedCounter] has no default either, for the reason [close]'s
     * [syncState] has none.** It defaulted to null until M6's review, which is the same
     * trap one parameter to the right: every caller took the default, so the
     * causal-stability half of SPEC §4.7's gate was thirty days of wall clock and nothing
     * else. Null remains the right *value* wherever no peer clock is known — it is only
     * the silence about it that was wrong.
     *
     * ## ⚠️ A fifth correction to SPEC §4.7: both halves of the gate were measured off values
     * the line above had just reset, so **compaction had never fired in this app at all**
     *
     * `close()` and `NoteEditor.materializeLocked` both materialize and then test the gate.
     * [NoteStore.materialize] writes an empty journal (`journalBytes` → 8) and stamps
     * `document.modifiedEpochMillis` with `now`; this method read the gate's size from the
     * first and its age from the second. So the question actually asked was *"is this
     * 8-byte journal bigger than 2 MB, and is a note written to this instant older than
     * thirty days?"* — no and no, on every note, at every close, since M2. Every page has
     * re-folded its **entire lifetime log** at every open ever since, and the cost grows
     * monotonically with everything the user writes. It is the same failure the section's
     * existing three corrections describe, which is worth saying out loud: a gate whose
     * inputs are produced by the step immediately before it is a gate that tests the step,
     * not the note.
     *
     * ⚠️ It survived four milestones of tests because **every test that made it fire reached it
     * from a session that had never materialized** — `MergeFoundationTest`'s peer-ack pair and
     * SPEC §13.2 row 8 both flush a large journal, reopen, and call this directly, and both say
     * so in a comment. That is a legitimate path and it still works. It is also the one path on
     * which the two resets do not happen, so the suite could show compaction working while no
     * user had ever had a note compacted. CLAUDE.md's *"a feature can be correct in every test
     * and inert in the app"*, in the shape where the fixture is not more generous than
     * production but merely differently ordered.
     *
     * The inputs now come from state materialize does not erase — [foldableBytes], which counts
     * the bundle's log as well as the journal's, and [contentChangedEpochMillis], which is the
     * stamp the note was *opened* with rather than the one it was just given. Each field's KDoc
     * carries its own argument.
     *
     * ## The invariant this preserves
     *
     * > **No op younger than the causal-stability horizon may enter a snapshot.**
     *
     * That is what the 30-day check is for, and it is *not* an arbitrary age: SPEC §4.7's
     * snapshot is a base a page re-folds from, so an op that a peer is still holding offline
     * has to sort *after* it to be absorbed rather than to invalidate it (CLAUDE.md rule 9 —
     * re-fold, never patch). Nothing is lost when that goes wrong, because the raw log is
     * kept and [PageLog] reports `snapshotStale`; what is lost is the snapshot, and with it
     * the whole point of having written one.
     *
     * [contentChangedEpochMillis] preserves the invariant because it is an **upper bound on
     * the creation time of the newest op the snapshot would contain**, never an estimate of
     * it: it is the time of this session's last [flush] if there was one, and otherwise the
     * instant at which the bundle holding every one of those ops was written. `now - bound ≥
     * 30 days` therefore implies *every* op being folded is at least 30 days old, which is
     * SPEC §4.7's *"all ops being folded are older than the causal-stability horizon"* read
     * literally. The horizon is only ever crossed later than the truth, and crossing it later
     * refuses a compaction — disk, never data.
     */
    public suspend fun compactIfDue(syncState: NoteSyncState, oldestAckedCounter: Long?) {
        // ⚠️ A snapshot is a cache **of the log** (SPEC §4.7: *"keep the raw log"*). Folding
        // one over work that is still only in `pending` would produce a cache containing
        // state the log does not — and then discarding it, which CLAUDE.md rule 9 requires
        // the moment an op older than it arrives, would lose that work outright.
        //
        // `close()` materializes first so this never fires in production. It is here because
        // a caller reaching `compactIfDue` directly is one refactor away, the cost of being
        // wrong is silent data loss, and the check is one field read. Found by an assertion
        // added to `a snapshot is discarded when an op older than it arrives` in M6.
        if (hasUnflushedWork) return
        val allowed = Compaction.isAllowed(
            syncState = syncState,
            // `Compaction.isAllowed` names this `journalBytes` and documents it as *"the size
            // of the material that would be folded"*. Since M2 those two have been different
            // things, and only the name was being honoured: after a materialize the journal is
            // 8 bytes and the material is the whole bundle. [foldableBytes] is both terms.
            journalBytes = foldableBytes,
            nowEpochMillis = clock.nowEpochMillis(),
            // ⚠️ The oldest op *being folded*, not the document's creation date. Passing
            // `createdEpochMillis` meant that on any note older than 30 days the
            // causal-stability horizon passed for ops written seconds ago — which is the
            // whole guard SPEC §4.7 describes, inverted, on precisely the long-lived
            // notebooks it exists to protect.
            //
            // ⚠️ And *not* `document.modifiedEpochMillis`, which was M6's fix for that and
            // is reset to `now` by the materialize on the line above every caller. See the
            // KDoc: this is a bound the newest op cannot be younger than, which is the
            // reading of SPEC §4.7 that makes the check mean anything.
            oldestOpEpochMillis = contentChangedEpochMillis,
            oldestAckedCounter = oldestAckedCounter,
            highestFoldedCounter = clocks.highestSeen,
        )
        if (!allowed) return
        for (ref in document.livePages) {
            val folded = page(ref.id)
            // Nothing has been added to this page since its snapshot, so rewriting it would
            // re-serialise every object on it to produce a byte-identical file. Worth a read
            // to avoid, now that the gate can pass: it passes for a note *nothing is changing*,
            // which is precisely the note that would otherwise be re-snapshotted in full on
            // every close and every idle-while-charging pass for the rest of its life. A
            // snapshot's own log is one op per live object, so reading one back is a fraction
            // of the fold that produced it.
            if (store.readSnapshot(document.id, ref.id)?.throughClock == folded.throughClock) continue
            store.writeSnapshot(document.id, folded, document.deviceId)
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
         * A rough per-op cost, for the compaction threshold only.
         *
         * The real size is known after the append, but reading the file back to find it
         * would defeat the point of an append-only journal. Compaction's trigger is a
         * heuristic about when work is worth doing, so an estimate is the right precision
         * — and it errs high, which delays compaction rather than triggering it early.
         *
         * It converts [bundleOpCount] as well, so SPEC §4.7's 2 MB threshold reads as ~4,000
         * ops of accumulated log — deliberately more than the *"2,000 ops is about one page of
         * dense handwriting"* the section rejects as too small a trigger.
         */
        private const val ESTIMATED_OP_BYTES = 512L
    }
}

// The private `withNewId` that used to live here was a third hand-written `when` over the four
// object kinds, beside `NoteDuplicate.withCopiedId`'s two. M8b folded all three copiers into
// `copiedObjectOps`, which is the only caller now — and one implementation is what stops the
// *next* register being carried by two of them and forgotten by the third.
