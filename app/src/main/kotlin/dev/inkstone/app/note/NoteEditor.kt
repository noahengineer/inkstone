package dev.inkstone.app.note

import dev.inkstone.app.search.NoteRecogniser
import dev.inkstone.app.search.RecognitionReport
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.InkObject
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.MaterializedFile
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PageTemplate
import dev.inkstone.core.model.PdfPublisher
import dev.inkstone.core.storage.BundleReader
import dev.inkstone.core.storage.DeviceIdentity
import dev.inkstone.core.storage.FileByteSource
import dev.inkstone.core.storage.MergeReport
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.storage.NoteMerge
import dev.inkstone.core.storage.NoteSession
import dev.inkstone.core.storage.NoteStore
import dev.inkstone.core.sync.MergeCommit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the editing session for the note on screen: autosave, page navigation, undo.
 *
 * ## The two rhythms (SPEC §5.1)
 *
 * - **Every ~2 s**, [autosaveLoop] drains the ink surface and appends to `journal.ops`. One
 *   append. The document is not rewritten.
 * - **On close**, [materializeNow] folds the journal into the bundle. That is where the rewrite
 *   happens, and it happens once.
 *
 * Draining encodes stroke geometry, so it runs on [Dispatchers.Default] — CLAUDE.md rule 7
 * forbids serialization on the ink path, and "the timer happened to fire while you were
 * writing" is exactly the case that rule is about. Finished strokes are immutable and the
 * recorder's queue is concurrent, so the user can keep writing throughout.
 *
 * ## Why the surface is a field
 *
 * This holds a reference to the ink surface, which the layer above — a `ViewModel` — normally
 * should not. The alternative is worse: the recorder lives inside the surface (it has to; it holds
 * `androidx.ink` types that must not cross the module boundary), so something has to drain it on a
 * timer. Keeping the reference here and clearing it in [onSurfaceDetached] is the honest version.
 *
 * ## Why this is a plain class and not the ViewModel itself
 *
 * It was the ViewModel, and the binding bug [bindSurface] documents got through because this code
 * could not be tested without a device: `viewModelScope` is wired to `Dispatchers.Main` and its
 * autosave loop is only cancelled by `onCleared`, neither of which a unit test can drive. Taking
 * the scope, the drain dispatcher and the interval as parameters makes the whole thing verifiable
 * on the JVM, and `NoteEditorViewModel` is now a shell that supplies `viewModelScope`.
 *
 * @param scope the lifetime this editor lives inside. Its work runs in a child of it, so
 *   cancelling either the parent or [close] stops the editor.
 * @param drainDispatcher where stroke geometry is encoded — **never the UI thread** (CLAUDE.md
 *   rule 7). A parameter so a test can make it deterministic.
 */
// One method per thing the note UI can do — open, save, materialize, rename, undo, redo, the six
// page operations — plus the three the sync layer reaches in through, which have to be able to
// *wait* for this editor's session rather than be told there isn't one. The count tracks the size of
// the editor's surface, not the complexity of any part of it: none of these is longer than a dozen
// lines, and everything with state of its own has already been extracted (the session, the store,
// the surface, the device identity, the library reports). Collapsing them to satisfy a count would
// put `save()`'s flush ordering and `absorbRemote`'s "never through display()" rule into the same
// method, which are the two places in this file where being wrong is silent. Same judgement
// `LibraryRepository` gets, for the same reason.
@Suppress("TooManyFunctions")
class NoteEditor(
    private val fs: FileSystem,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val drainDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val autosaveIntervalMs: Long = AUTOSAVE_INTERVAL_MS,
    /**
     * Which note to open. M3 made this a question with an answer — the library picks.
     *
     * Null keeps M2's behaviour (first note on disk, or a new one), which is what the tests
     * use and what a launch with no library state falls back to.
     */
    private val documentId: DocumentId? = null,
    /**
     * Where saves are reported (M3). Null in tests, and in that case the editor simply does
     * not maintain an index — the note itself is unaffected, because the index is a cache
     * of the files and never the truth.
     */
    private val library: LibraryUpdates? = null,
    /**
     * SPEC §5.2 — what turns the bundle into `document.pdf` (M4).
     *
     * Null in tests, and in that case the note is materialized as far as its bundle
     * and no further. That is a real and safe state, not a degraded one: the bundle
     * is the lossless truth (SPEC §5.3) and the PDF is the *published* form, so a
     * missing publisher costs interoperability, never data.
     */
    private val publisher: PdfPublisher? = null,
    /**
     * SPEC §8.3's debounced recognition (M7). Null in tests and on a build with no ML Kit, in
     * which case the note is simply never read — search degrades to titles and nothing else
     * about the editor changes.
     */
    private val recogniser: NoteRecogniser? = null,
) {

    /**
     * A child of [scope], so the editor can be stopped without stopping its owner.
     *
     * The autosave loop runs forever by design, and something has to be able to end it: the
     * `ViewModel` does so via [close] from `onCleared`, and a test does so at the end of the test.
     * Without this, the loop is an uncancellable child of whatever scope it was handed.
     */
    private val work = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    /**
     * ⭐ **The editor's operations run one at a time, and this is the only thing that makes them.**
     *
     * [work] is a scope, not a queue. It runs on `Dispatchers.Main.immediate`, so nothing here races
     * for memory — but every coroutine launched into it interleaves freely at every suspension point,
     * and the operations are full of them: a drain, a journal append, a whole-bundle write. The
     * autosave loop is one long-lived coroutine in that scope, and since M6 a merge is another. That
     * is two writers over one [NoteSession], which is the exact thing `NoteSessionHost`'s gate exists
     * to prevent between two *sessions* and could not see between two *coroutines*:
     *
     * ```
     * merge:     session.materialize()  — snapshots every page, then writes the bundle   ← suspends
     * autosave:  save() → session.apply(gesture) → session.flush() → a journal record on disk
     * merge:     ... and then resets the journal to a bare header
     * ```
     *
     * The stroke the user drew during the merge is then in no journal, no bundle, no `pending` list
     * and no page cache; `localRev` regresses over the flush's; and the output is a valid PDF with the
     * right page count. Nothing reports anything. Downstream, `MergeCommit` carries the regressed
     * revision, so the library index and the binding both agree that nothing is owed.
     *
     * [NoteSession] is single-threaded *by contract* and deliberately has no lock of its own — a lock
     * on the ink path is a cost with no benefit (CLAUDE.md rule 7) — so the serialization belongs to
     * whoever owns the session. That is this class.
     *
     * ⭐ **Created locked, released when the note is open** (see [init]). A merge that arrives while
     * the note is still opening then *waits for the open* instead of being told there is no session
     * here — which is what [mergeRemoteBundle] used to answer, and answering it made the host open a
     * second session and rewrite `note.inkstone` under this editor's byte-offset [BundleReader].
     *
     * ⚠️ **Not re-entrant.** A [Mutex] never is: a locked function that calls another locked function
     * *directly* deadlocks the editor for good. Everything shared between them is spelled `…Locked`
     * and takes no lock of its own. Calling a locked entry point from inside another is safe only
     * because they go through [work]`.launch`, where waiting for the lock suspends a *different*
     * coroutine.
     */
    private val serial = Mutex(locked = true)

    private val ids = IdFactory(clock)
    private val store = NoteStore(fs, clock, ids)

    /** SPEC §4.1's Lamport tiebreak — one id per install, persisted. See [DeviceIdentity]. */
    private val deviceIdentity = DeviceIdentity(fs, ids)

    private val _state = MutableStateFlow(NoteEditorState())
    val state: StateFlow<NoteEditorState> = _state.asStateFlow()

    private var session: NoteSession? = null
    private var surface: NoteSurface? = null

    /** The surface [bindSurface] has already wired to the session, so it is not wired twice. */
    private var boundSurface: NoteSurface? = null
    private var currentPage: PageId? = null

    /** The current page's template, for the picker. Set wherever the page or its background changes. */
    private var currentTemplate: PageTemplate = PageTemplate.BLANK

    /**
     * The device's identity for the Lamport tiebreak (SPEC §4.1).
     *
     * ⭐ **Per install, from [DeviceIdentity] — corrected in M6.** It used to be
     * `note.document.deviceId`, i.e. read out of the manifest, i.e. the id of whichever
     * device *created* the note. Two tablets editing one note therefore stamped their ops
     * with the **same** deviceId, which collapses SPEC §4.1's tiebreak: two concurrent ops
     * at the same counter compare *equal*, `Register.write` keeps the incumbent, and the
     * incumbent is whichever op that device folded first. The two devices then hold
     * different values permanently, with every op present and no error — SPEC §13.2 row 3.
     *
     * M2 wrote this as temporary and named M5 as the fix; M5 did not do it, and M6 is the
     * first milestone where a second device exists to expose it.
     */
    private var deviceId: String = ""

    /**
     * What the editor tells the library (SPEC §5.7, §9).
     *
     * An interface rather than a direct dependency on `LibraryRepository` for the reason the
     * editor is not a `ViewModel` any more: everything here has to be drivable from a unit
     * test, and a repository drags in a database.
     */
    interface LibraryUpdates {
        /**
         * A local change was flushed — an autosave tick, **or any structural edit**: add, duplicate,
         * delete or move a page, or change the paper.
         *
         * ⚠️ It said "autosave committed" for a milestone, and that reading is why every structural
         * edit called a bare `session.flush()` and reported nothing. This ends in
         * `SyncEngine.noteChanged`, so it is also the only route such an edit has to an **upload**.
         */
        suspend fun onSaved(id: DocumentId, localRev: Long, pageCount: Int)

        /**
         * The note closed and its bundle was rewritten (SPEC §5.1) — so the library's
         * picture of it is now wrong, and [cover] is the page to redraw it from.
         */
        suspend fun onMaterialized(id: DocumentId, localRev: Long, pageCount: Int, cover: FoldedPage)

        /**
         * The note's standing with the server, for SPEC §4.7's compaction gate.
         *
         * Compaction may fold a page's log into a snapshot only when the note is
         * `LOCAL_ONLY` or `SYNCED` — a note with pending or failed uploads has ops the
         * server has not seen, and folding those away leaves a peer's older ops nowhere to
         * reintegrate. The editor cannot know this itself; the library index owns
         * `sync_state`.
         *
         * Defaulted to `LOCAL_ONLY` **only** for the no-library case (M2's fallback path and
         * the unit tests), where there is genuinely no binding and no server.
         */
        suspend fun syncStateOf(id: DocumentId): NoteSyncState = NoteSyncState.LOCAL_ONLY

        /**
         * The note was renamed (SPEC §7.7 layer 2), so the library's *title* is stale and the
         * remote file needs to move.
         *
         * Deliberately **not** [onSaved]: `recordSave` has no title column, so a rename reported
         * that way bumps the revision and leaves the tile showing the old string. That is half of
         * why layer 2 spent M6 unreachable — a register with no writer at one end and no reader at
         * the other.
         */
        suspend fun onRenamed(id: DocumentId, title: String, localRev: Long, pageCount: Int) = Unit

        /**
         * A peer's edits were merged into this note while it was open (M6).
         *
         * Deliberately **not** [onSaved]: that path ends in `SyncEngine.noteChanged`, which
         * enqueues an UPLOAD unconditionally and turns every merge into an ETag ping-pong the
         * two devices sustain forever. Whether an upload is owed is the reconciler's decision
         * table, so this only bumps the index row and invalidates the thumbnail.
         *
         * @param title the title the merge **resolved** to (SPEC §7.7 layer 2) — the local one when
         *   the local one won, so the caller can write it back without asking who did. Without it a
         *   peer's rename lands in the bundle and the library tile keeps the old string forever.
         */
        suspend fun onPulled(id: DocumentId, localRev: Long, pageCount: Int, title: String) = Unit

        /**
         * SPEC §4.7's causal-stability horizon: the lowest Lamport counter every known peer has
         * been seen to hold, or null when no peer is known.
         *
         * ⭐ Wired for the first time in M6. `Compaction.isAllowed`'s parameter has existed since
         * M2 and every caller passed null, so thirty days of wall clock was the only guard against
         * folding away ops a peer still needs in order to reintegrate its own.
         *
         * Null in the no-library case *and* for a genuinely single-device note, which is the same
         * conservative answer the parameter documents.
         */
        suspend fun oldestAckedCounter(id: DocumentId): Long? = null
    }

    /** What the editor needs from the ink surface. Keeps `androidx.ink` out of this class. */
    interface NoteSurface {
        fun bindPage(nextObjectId: () -> ObjectId, nextOpId: () -> String, nextClock: () -> LamportClock)

        fun loadPage(page: FoldedPage)

        fun drainOps(): List<List<PageOp>>

        fun applyOps(ops: List<PageOp>, record: Boolean)

        /**
         * SPEC §8.4 — mark where a search hit landed and bring it into view; null clears it.
         *
         * ⚠️ **Not a `= Unit` default.** M6's `setPageMeta` was exactly that, with a KDoc
         * explaining why, and it was the milestone's most convincing invisible bug: a
         * recording fake made every test true of the editor and false of the app. A method
         * with no body here would make "search finds it and nothing happens" the shipped
         * behaviour, and the test suite would agree it worked.
         */
        fun highlight(box: PageRect?)

        /**
         * The current page's size or paper changed **without** the page changing — a peer's
         * `SetPageMeta`, or the PageBar's template picker in **either** direction.
         *
         * Forward and reverse deliberately share this one route. `setTemplate` used to repaint by
         * ending in [display] → [loadPage] while its own undo came through here, so the two could
         * disagree — and did: the forward direction worked and undo did nothing, because the only
         * production implementation of this method was `= Unit`.
         *
         * Separate from [loadPage] because [loadPage] cancels the in-progress stroke and refits the
         * camera, which are both of SPEC §13.2 row 10's forbidden outcomes. A page whose paper just
         * changed under the user is still the page they are writing on.
         */
        fun setPageMeta(size: PageSize, background: Background)
    }

    init {
        work.launch {
            // [serial] is already held — it is constructed locked — so this is the one operation that
            // does not take it, and the one that releases it. Anything that arrives while the note is
            // opening therefore queues behind the open rather than concluding there is no session
            // here.
            //
            // `finally`, because an open that fails must not leave the editor holding its own lock
            // forever. It leaves `session` null instead, which every entry point already treats as
            // "nothing to do" and which `mergeRemoteBundle` answers with null — the honest answer, and
            // the only case where the host opening its own session is correct.
            try {
                openOrCreate()
            } finally {
                serial.unlock()
            }
            // Its own coroutine, not a second statement here: `autosaveLoop` never returns,
            // so anything after it is unreachable — which is how a loop can be written,
            // reviewed, tested through its own function and still never run in the app.
            work.launch { recognitionLoop() }
            autosaveLoop()
        }
    }

    // -----------------------------------------------------------------------
    // Opening
    // -----------------------------------------------------------------------

    /**
     * Open the note the library asked for — or, with no id, M2's fallback.
     *
     * The fallback is not dead code: it is what a launch with an empty or unreadable library
     * index gets, and it is what the unit tests drive. It takes the first note in `notes/`
     * and makes one if there is none, which is the same rule `LibraryRepository.reconcile`
     * uses to adopt an orphaned directory — the files are the truth in both.
     */
    private suspend fun openOrCreate() {
        deviceId = deviceIdentity.get()
        val requested = documentId ?: fs.list(NoteLayout.NOTES_DIR).firstOrNull()?.let(::DocumentId)
        val note = requested?.let { store.open(it) }
            ?: store.create(
                title = DEFAULT_TITLE,
                deviceId = deviceId,
                background = Background.Template(PageTemplate.RULED),
            )

        val opened = NoteSession.open(store, ids, clock, note, deviceId)
        session = opened
        // Where this device left off, falling back to the first page. Checked against the live
        // pages rather than trusted: the remembered page may have been deleted here, or deleted
        // on the other tablet and merged in since, and `view.json` is not part of that merge.
        val live = opened.document.livePages
        val remembered = store.loadViewState(opened.document.id)?.takeIf { saved -> live.any { it.id == saved } }
        // Directly, not through `showPage`: that launches a coroutine, and publishing before it
        // ran would show a note with no page.
        display(opened, remembered ?: live.first().id)
        // The other half of the binding race — see `bindSurface`. On a real device this is the
        // side that runs second, and if it did not bind, nothing the user drew would be recorded.
        bindSurface()
        publish(recovered = opened.recoveredFromTornJournal)
    }

    fun onSurfaceAttached(surface: NoteSurface) {
        this.surface = surface
        bindSurface()
    }

    fun onSurfaceDetached() {
        surface = null
        boundSurface = null
    }

    /**
     * Bind the surface to the note — **whichever of the two arrives second.**
     *
     * The bug this shape exists to prevent, found on the tablet: the view is created during the
     * first layout pass, and opening a note is file I/O, so on a real device the surface attaches
     * *before* the session exists. An `onSurfaceAttached` that gave up when `session` was null
     * therefore never bound anything — and an unbound surface records no ops at all. Nothing was
     * journaled, nothing survived a close, and undo stayed greyed out because the undo stack
     * never received an entry. Every layer below this was tested and correct; the wiring was
     * order-dependent and silent about it.
     *
     * So binding is driven from both sides and is idempotent. The `boundSurface` guard matters
     * for more than tidiness: [NoteSurface.bindPage] installs a fresh recorder, so binding the
     * same surface twice would discard whatever it had queued but not yet flushed.
     */
    private fun bindSurface() {
        val surface = this.surface ?: return
        val session = this.session ?: return
        if (boundSurface === surface) return
        boundSurface = surface
        surface.bindPage(ids::newObjectId, session::nextOpId, session::nextClock)
        // Folding a page reads the journal and mutates the session's cache, so it queues with
        // everything else. Called from inside the open — which holds [serial] — but only ever
        // *launched*, so the wait suspends this new coroutine and not the open.
        currentPage?.let { page -> work.launch { serial.withLock { surface.loadPage(session.page(page)) } } }
    }

    // -----------------------------------------------------------------------
    // Autosave (SPEC §5.1)
    // -----------------------------------------------------------------------

    private suspend fun autosaveLoop() {
        while (work.isActive) {
            delay(autosaveIntervalMs)
            save()
        }
    }

    /**
     * Drain the surface and append. Safe to call at any time; does nothing when idle.
     *
     * Takes [serial], so an autosave tick that lands in the middle of a merge waits for it rather
     * than flushing a journal the merge is about to reset.
     */
    suspend fun save(): Unit = serial.withLock { saveLocked() }

    /**
     * [save]'s body, for the operations that already hold [serial].
     *
     * One `session.apply` per **gesture**, not per drain, so a drain that catches three strokes
     * produces three undo entries rather than one (SPEC §4.6). They still reach disk in a
     * single journal record, because the append happens once at the end.
     */
    private suspend fun saveLocked() {
        val session = this.session ?: return
        val page = currentPage ?: return
        val gestures = surface?.let { withContext(drainDispatcher) { it.drainOps() } }.orEmpty()
        for (gesture in gestures) {
            session.apply(page, gesture)
        }
        if (gestures.isNotEmpty() || session.hasUnflushedWork) {
            flushAndReport(session)
            publish()
        }
        // SPEC §8.3 — "debounced ~3 s after the last stroke on a page". This is the one place
        // that knows a gesture actually landed *and* is off the ink thread; hanging it off the
        // surface's stroke-finished callback instead would put the timer on the UI thread
        // inside `onStrokesFinished` (CLAUDE.md rule 7).
        if (gestures.isNotEmpty()) {
            unreadPages += page
            lastStrokeAtMs = clock.nowEpochMillis()
        }
    }

    // -----------------------------------------------------------------------
    // Recognition (SPEC §8.3)
    // -----------------------------------------------------------------------

    /** Pages the pen has touched since the recogniser last read them. */
    private val unreadPages = LinkedHashSet<PageId>()

    private var lastStrokeAtMs: Long = 0

    /**
     * ⭐ SPEC §8.3 — **never while the user is writing.**
     *
     * The loop wakes on its own timer and does nothing until the pen has been still for
     * [RECOGNITION_DEBOUNCE_MS]. Recognising on every save would put ML Kit on the two-second
     * autosave tick; recognising from the stroke callback would put it on the ink path. Both
     * are CLAUDE.md rule 7, and the second is the one that would actually drop frames.
     */
    private suspend fun recognitionLoop() {
        while (work.isActive) {
            delay(RECOGNITION_POLL_MS)
            val idleFor = clock.nowEpochMillis() - lastStrokeAtMs
            if (unreadPages.isEmpty() || idleFor < RECOGNITION_DEBOUNCE_MS) continue
            recogniseNow()
        }
    }

    /**
     * Read the pages the pen has touched. Takes [serial], so it queues behind a merge or a
     * save rather than folding a page one of them is rewriting.
     */
    suspend fun recogniseNow(): RecognitionReport = serial.withLock { recogniseLocked() }

    /**
     * SPEC §8.3's background pass reaching a note this editor holds — `NoteSessionHost.recognise`
     * handing the job here rather than opening a second session over the same note.
     *
     * @return null only when this editor can no longer run anything, which is the one case where
     *   the host recognising through its own session is correct. Same contract, and the same
     *   `work.async` reason, as [materializeInSession].
     */
    internal suspend fun recogniseInSession(): RecognitionReport? {
        if (!work.isActive) return null
        // Every live page, not just the unread ones: the batch pass exists to catch up a note
        // the debounce never got to — a note opened, written in and closed inside three seconds.
        return work.async { serial.withLock { recogniseAllLocked() } }.await()
    }

    private suspend fun recogniseAllLocked(): RecognitionReport {
        val session = this.session ?: return RecognitionReport.NOTHING
        val recogniser = this.recogniser ?: return RecognitionReport.NOTHING
        unreadPages.clear()
        val report = recogniser.recognise(session)
        if (report.changedAnything) flushAndReport(session)
        return report
    }

    private suspend fun recogniseLocked(): RecognitionReport {
        val session = this.session ?: return RecognitionReport.NOTHING
        val recogniser = this.recogniser ?: return RecognitionReport.NOTHING
        // Taken before the pass, not after: a stroke landing while ML Kit is working must
        // leave that page queued for the next round rather than being marked read. The
        // session's ink digest would catch it anyway (SPEC §5.5), but catching it here saves
        // publishing a page with no text at all until the next stroke.
        val pages = unreadPages.toList()
        unreadPages.clear()
        val report = recogniser.recognise(session, pages)
        if (report.changedAnything) flushAndReport(session)
        return report
    }

    /**
     * Flush, then tell the library — the two halves of *"this note changed"*.
     *
     * ⚠️ **The second half used to happen only on the autosave path**, and every structural edit
     * called `session.flush()` on its own. `library.onSaved` is the only route to
     * `thumbnails.invalidate` **and** `sync.noteChanged`, and `saveLocked`'s report is gated on
     * `hasUnflushedWork`, which a bare `flush()` has just cleared. So adding a page, deleting one,
     * moving one, duplicating one or changing the paper left the library tile's page count stale for
     * the whole session *and enqueued no upload* — the change reached the server only when the user
     * happened to draw a stroke afterwards, or when the note closed and `materialize` fired
     * `onMaterialized`. Structural edits are exactly the ones a user makes and then puts the tablet
     * down.
     *
     * SPEC §5.7 — told, not redrawn: rendering a page here would put a tessellation on the
     * two-second autosave timer, which is the kind of work CLAUDE.md rule 7 keeps off this path.
     */
    private suspend fun flushAndReport(session: NoteSession) {
        session.flush()
        library?.onSaved(session.document.id, session.document.localRev, session.pageCount)
    }

    /**
     * SPEC §5.1 — fold the journal into the bundle, then republish the PDF. Called when the
     * note goes away, and before an export.
     *
     * `Activity.onStop` is the last point the process is reliably alive, so that is what keeps
     * the invariant that the published file is never more than one editing session stale.
     *
     * Suspending rather than fire-and-forget because export has to wait for it: sharing before
     * the rewrite hands out a file SPEC §5.1 explicitly allows to be a session behind, and a
     * note exported without the ink just drawn on it looks exactly like data loss.
     *
     * Takes [serial]: this rewrites the bundle and resets the journal, so a merge or an autosave tick
     * running alongside it is the data loss that class of interleaving produces.
     *
     * @return the published file, or null when the PDF could not be written. The bundle is still
     *   saved — see the comment on the publish step — so null is "not publishable yet", never
     *   "lost". It answers the file rather than a `Boolean` because `OutboxDrainer` records
     *   `lastSyncedRev` from the revision these bytes were built from.
     */
    suspend fun materializeNow(): MaterializedFile? = serial.withLock { materializeLocked() }

    /**
     * ⭐ **The same materialize, for `MaterializeWorker` — which has to be able to *wait* for it.**
     *
     * `MaterializeWorker` republishes `document.pdf` for the notes the editor's `onStop` never
     * covers: one bound from the library without being opened, and "idle while charging". It did so
     * by calling the raw `PdfPublisher` directly, which for an *open* note is a second writer with
     * no ordering against this one — and the failure is not a torn file (writes are atomic and, since
     * M6, uniquely named) but a **stale marker**. `InkstonePdfMaterializer` reads `note.inkstone`,
     * embeds it and records its SHA-256; if this editor rewrites the bundle in between, the worker's
     * `document.pdf` lands last carrying the hash of a bundle that no longer exists. The next library
     * rebuild reads that as `Recovered(BUNDLE_HASH_MISMATCH)` — and recovery is REPLACE-not-union,
     * stamped past every clock the note has seen (SPEC §5.6), so it wins the next sync. A lossy
     * rewrite of a healthy note, arrived at by publishing it twice.
     *
     * So the worker goes through `NoteSessionHost`, which hands the job here when this editor holds
     * the note. Taking [serial] is the whole of the fix; [materializeLocked] does strictly more than
     * the worker asked for (it flushes the journal first), which is the right kind of more.
     *
     * @return false **only** when this editor can no longer run anything, or the PDF could not be
     *   written. Both mean the host publishing through the materializer itself is correct.
     */
    internal suspend fun materializeInSession(): MaterializedFile? {
        // `work.async` on a cancelled scope produces a cancelled `Deferred`, and awaiting one throws
        // into the worker rather than answering it — the same contract [renameInSession] documents.
        if (!work.isActive) return null
        return work.async { serial.withLock { materializeLocked() } }.await()
    }

    /**
     * ⚠️ **Returns the published file so the caller can trust its revision.** It used to answer a
     * `Boolean`, and `NoteSessionHost` passed that straight to `OutboxDrainer`'s
     * `DocumentMaterializer` — which needs the `localRev` the bytes were built from, because
     * recording any other revision as synced silences every future upload of the difference.
     */
    private suspend fun materializeLocked(): MaterializedFile? {
        val session = this.session ?: return null
        saveLocked()
        // ⚠️ Deliberately **not** `session.close(syncState)`, which is `materialize` +
        // `assertNotStale` + `compactIfDue(syncState, oldestAckedCounter = null)` — null because a
        // session has no route to the peer table. Spelling the three steps out is the only way to
        // supply SPEC §4.7's causal-stability horizon, which M6 is the first milestone to know:
        // a downloaded bundle says what a peer holds, and without it thirty days of wall clock was
        // the whole guard against compacting away ops a peer still needs to reintegrate.
        //
        // The note's real sync state, not the old `close()` default either. A CONFLICTED or
        // PENDING_UPLOAD note must not be compacted at all (SPEC §4.7), and every call site used
        // to take that default — so the first and most important refusal never fired.
        val id = session.document.id
        val syncState = library?.syncStateOf(id) ?: NoteSyncState.LOCAL_ONLY
        session.materialize()
        store.assertNotStale(id)
        session.compactIfDue(syncState, library?.oldestAckedCounter(id))
        // The bundle has just been rewritten, so this fold is what the note now *is* —
        // which makes it the only honest moment to redraw the library's thumbnail. The
        // published PDF cannot be the source: it may be one session stale (SPEC §5.1).
        session.document.livePages.firstOrNull()?.let { first ->
            library?.onMaterialized(
                id = session.document.id,
                localRev = session.document.localRev,
                pageCount = session.pageCount,
                cover = session.page(first.id),
            )
        }
        // SPEC §5.1/§5.2 — and strictly after the bundle: the published PDF embeds
        // those exact bytes and records their hash, so publishing first would write
        // a marker describing a bundle that no longer exists (SPEC §5.6).
        //
        // Failure here is reported and does not undo the save. The bundle is already
        // on disk and is the truth; a note whose PDF could not be written is a note
        // that opens perfectly and cannot yet be read elsewhere.
        val published = publisher?.let { runCatching { it.publish(session.document.id) } }
        publish(message = if (published?.isFailure == true) PUBLISH_FAILED else null)
        return published?.getOrNull()
    }

    /**
     * ROADMAP M3 / SPEC §7.7 layer 2 — rename the note. The library sees it through [LibraryUpdates].
     *
     * ⭐ **This is the only thing in the app that writes a title the sync layer can resolve**, and
     * until M6's review nothing reached it. The production rename was the library's, which renamed an
     * index row: `Document.title` and `Document.titleClock` were never touched, so two devices
     * renaming one note kept two titles indefinitely with no clock to order them. Both paths now come
     * through here or through `NoteStore.renameNote` — see `NoteSessionHost.renameNote`.
     */
    fun rename(title: String) {
        work.launch { serial.withLock { renameLocked(title) } }
    }

    /**
     * The rename, for `NoteSessionHost` — which has to be able to *wait* for it.
     *
     * The same contract as [mergeRemoteBundle], for the same reason: this editor holds the note's one
     * [NoteSession], so the host must not open a second one to stamp a title in.
     *
     * @return false **only** when this editor can no longer run anything — its scope is cancelled, or
     *   its open failed and left no session. Both mean the host renaming through its own session is
     *   correct, because there is no second writer to collide with.
     */
    internal suspend fun renameInSession(title: String): Boolean {
        // `work.async` on a cancelled scope produces a cancelled `Deferred`, and awaiting one throws
        // into the caller rather than answering it.
        if (!work.isActive) return false
        return work.async { serial.withLock { renameLocked(title) } }.await()
    }

    private suspend fun renameLocked(title: String): Boolean {
        val session = this.session ?: return false
        if (title != session.document.title) {
            session.rename(title)
            // ⚠️ **Materialized, not merely flushed.** The published PDF embeds `note.inkstone` as it
            // is on disk, so a title that reached only the journal is one no peer can see until the
            // note happens to close — the rename looks done here and is invisible everywhere else.
            // `materializeLocked`, never `materializeNow`: [serial] is not re-entrant.
            materializeLocked()
        }
        // Reported even when the document already said this. The *index* may not — a row whose title
        // drifted from the note is exactly what this repairs — and the report is what moves the
        // remote file, which a second attempt at the same rename may still owe.
        library?.onRenamed(session.document.id, session.document.title, session.document.localRev, session.pageCount)
        return true
    }

    /**
     * One page's fold, for a page thumbnail. Null before the note is open.
     *
     * Under [serial] because folding is not read-only: it re-reads the journal and populates the
     * session's page cache, which a concurrent [NoteSession.materialize] clears.
     */
    suspend fun foldedPage(pageId: PageId): FoldedPage? = serial.withLock { session?.page(pageId) }

    // -----------------------------------------------------------------------
    // Undo / redo (SPEC §4.6)
    // -----------------------------------------------------------------------

    /**
     * Undo appends the inverse ops and then shows the result.
     *
     * Everything queued has to be flushed first: an undo of a stroke that has not reached the
     * session yet would compute its inverse against a page that does not contain it.
     */
    fun undo() {
        withPage { session, page ->
            saveLocked()
            val before = session.page(page)
            if (!session.undo(page)) return@withPage
            reconcile(before, session.page(page))
            publish()
        }
    }

    fun redo() {
        withPage { session, page ->
            saveLocked()
            val before = session.page(page)
            if (!session.redo(page)) return@withPage
            reconcile(before, session.page(page))
            publish()
        }
    }

    // -----------------------------------------------------------------------
    // A peer's edits, while the note is open (SPEC §13.2 row 10)
    // -----------------------------------------------------------------------

    /**
     * ⭐ **The reconciler's one door into an open note, through `NoteSessionHost`.**
     *
     * There must be exactly one [NoteSession] per note, so when this editor holds one the pull
     * cannot open its own — whichever materialized second would write a bundle missing the other's
     * work. The host asks; this runs the merge against the session already in hand, and brings the
     * screen in line through [absorbRemote].
     *
     * ⭐ **One door, deliberately.** There was a second until M6's close-out — an
     * `applyRemoteOps(pageId, ops)` shaped like [undo], written for the design in which the host
     * merged in its own session and then *told* the editor through a bus. That is not how this
     * shipped: the host hands the merge to the editor, so the ops are folded and the screen
     * reconciled inside one critical section here, and the op-level door had no caller for the
     * whole milestone. It could not be given a correct one either — after this method has run, the
     * peer's ops are already in the log (a second delivery would subtract to nothing), and before
     * it has, ops delivered on their own would go in through `NoteSession.merge` with **no
     * `NoteMerge.mergeDocument` in front of them**. That is the trap SPEC §4.3 names: `merge`
     * accepts ops for a page `document.pages` has never heard of and journals them, `BundleWriter`
     * then skips the page it cannot find a `PageRef` for, and `materialize` resets the journal —
     * so a peer's whole page is destroyed, permanently, leaving a valid PDF with the right page
     * count. Deleting the door makes "forgot `mergeDocument`" unrepresentable instead of guarded,
     * which is the same move the `undoable` boolean got.
     *
     * ⚠️ `materialize` is given `carried` and `carriedManifest`. Without them the rewrite deletes
     * the peer's recognised text and every entry a newer build added — invisibly, because those are
     * by definition the entries this build cannot render.
     *
     * ⚠️ **An unreadable bundle answers with a report, not null.** Null means *"no session here, do
     * it yourself"*, and answering it for a truncated download would have the host open a second
     * session on a note this editor holds — the one thing the whole gate exists to prevent.
     * `NoteMerge.mergeBundle` never throws and never answers null: a schema from the future comes
     * back as `TooNew` and anything else unreadable as `DifferentDocument`, both of which route to
     * SPEC §7.7 layer 3, leave the ETag alone, and have the next pass fetch the file again.
     *
     * ⭐ **The session is read inside [serial], not before it** — and that one line of ordering is
     * what closes the note-open window. `NoteEditorViewModel` registers this editor's handle in its
     * `init`, which is *before* the open has read a byte, so a pull landing inside the note-open I/O
     * used to find `session == null`, get null back, and open its own session: two sessions over one
     * note, and the host's `materialize` then rewrote `note.inkstone` under the byte offsets this
     * editor's [BundleReader] had already recorded. The editor's next read of that reader fails its
     * local-header or CRC check and *throws* — out of `materializeNow`, out of
     * `viewModelScope.launch`. Nothing is written and the journal survives, so no bytes are lost, but
     * the claim that it "folds both" was wrong. Because [serial] is held from construction until the
     * open finishes, waiting here is waiting for exactly the session the merge should run against.
     *
     * @return null **only** when this editor can no longer run anything — its scope is cancelled, or
     *   the open failed and left no session. Both mean the host doing the merge itself is correct:
     *   there is no second writer to collide with.
     */
    internal suspend fun mergeRemoteBundle(bundlePath: String): MergeCommit? {
        // Cheap and honest: `work.async` on a cancelled scope produces a cancelled `Deferred`, and
        // awaiting one throws into the reconcile pass rather than answering it.
        if (!work.isActive) return null
        return work.async {
            serial.withLock {
                val session = this@NoteEditor.session ?: return@withLock null
                val report = absorbRemote(session) {
                    NoteMerge.mergeBundle(session, FileByteSource(fs, bundlePath)).also { report ->
                        if (report is MergeReport.Merged) {
                            session.materialize(report.carried, report.carriedManifest)
                            // The resolved title, so a peer's rename reaches the library tile and not
                            // only the bundle (SPEC §7.7 layer 2's reader half).
                            library?.onPulled(
                                id = session.document.id,
                                localRev = session.document.localRev,
                                pageCount = session.pageCount,
                                title = session.document.title,
                            )
                        }
                    }
                }
                MergeCommit(report, session.document.localRev)
            }
        }.await()
    }

    /**
     * [mergeRemoteBundle]'s tail: flush what the user has drawn, take the union, then bring the
     * screen in line from the fold diff.
     *
     * ⚠️ **Never through [display].** `display` → `NoteSurface.loadPage` → `resetCamera(FIT_WIDTH)`
     * plus `cancelUnfinishedStrokes()`. The refit used to arrive through `InkSurfaceView.pageSize`'s
     * setter, so *every* write to the size was a camera move; it is an explicit line in `loadPage`
     * now, which makes this no safer to call — a page **switch** wants a refit, and this is not one. The camera is persisted nowhere (`view.json` holds only the
     * last-open page), so once refit the previous value is gone. That is *both* of SPEC §13.2 row
     * 10's forbidden outcomes — the view jumps and the in-progress stroke is eaten — reached two
     * indirections from anything camera-shaped, with every JVM test still passing. [reconcile] is
     * correct for `APPENDED` and `REFOLDED` alike because it diffs the folds rather than replaying
     * ops, which is why no reload is needed at all.
     *
     * `before` is captured for the **current page only** — the only page on screen. Every other page
     * is folded fresh the next time it is shown.
     */
    private suspend fun <T> absorbRemote(session: NoteSession, ingest: suspend () -> T): T {
        // Queued local ink has to reach the session before the union is taken. Otherwise the diff is
        // computed against a page missing the last two seconds of writing, and `reconcile` would
        // then push a "removed" op for a stroke that is on screen and in the log.
        //
        // `saveLocked`, because both callers hold [serial] — and they must, for the whole merge and
        // not only for this flush.
        saveLocked()
        val page = currentPage
        val before = page?.let { session.page(it) }
        val result = ingest()
        if (page != null && before != null) reconcile(before, session.page(page))
        publish()
        return result
    }

    /**
     * Bring the screen in line with a freshly folded page.
     *
     * The ops the session appended are already folded into `after`, so rather than replaying
     * them through the surface — which would journal them a second time — this diffs the two
     * folds and tells the surface what changed. For an undo that is one or two objects.
     */
    private fun reconcile(before: FoldedPage, after: FoldedPage) {
        // ⚠️ Page metadata is part of the diff since M6. A peer's `SetPageMeta` folds correctly and
        // — before this — rendered not at all, so the screen and the log disagreed about the paper
        // until the next page switch, with no error anywhere. Pushed through `setPageMeta` and not
        // `loadPage`, because `loadPage` refits the camera and cancels the in-progress stroke.
        //
        // The picker's own label is updated *before* the `surface ?: return` below, because the
        // label is state and the surface is a view: a fold that changed the paper has changed what
        // the picker should say whether or not anything is currently on screen to draw it.
        val metaChanged = after.size != before.size || after.background != before.background
        if (metaChanged) {
            currentTemplate = (after.background as? Background.Template)?.template ?: PageTemplate.BLANK
        }
        val surface = this.surface ?: return
        if (metaChanged) surface.setPageMeta(after.size, after.background)
        val beforeIds = before.objects.map { it.id }.toSet()
        val afterIds = after.objects.map { it.id }.toSet()

        val removed = beforeIds - afterIds
        val added = after.objects.filter { it.id !in beforeIds }

        val ops = ArrayList<PageOp>(removed.size + added.size)
        removed.forEach { ops += PageOp.DeleteObject("", REPLAY_CLOCK, it) }
        added.forEach { ops += PageOp.AddObject("", REPLAY_CLOCK, it) }
        // ⭐ An object present in both may still have changed, and every absolute register it
        // carries has to be compared — an undone erase is the same object with a different
        // region, and since **M8a** the same is true of its transform, its style and its z key.
        //
        // ⚠️ The three M8a registers were the whole of this milestone's inert-feature risk, and
        // in the most convincing form: `SetTransform` folds correctly, journals, uploads and
        // merges, and produces an **empty diff** here. So the undo of your own lasso-move would
        // have changed nothing on screen, and a peer's move would have arrived invisibly and
        // stayed invisible until the next page switch — with every test green, because the log
        // is right and only the screen is wrong. That is M6's `setPageMeta` exactly, and this
        // method is where it was fixed then too.
        for (obj in after.objects) {
            before.find(obj.id)?.let { ops += changedRegisters(it, obj) }
        }
        // An empty diff is the *common* case on the pull side, not an edge one: a poll re-delivers a
        // peer's whole page log, `NoteSession.merge` subtracts nearly all of it by opId, and the
        // folds come out identical. Reaching the surface with nothing to say would cost a call per
        // poll on every open note. The deleted `applyRemoteOps` guarded this at its own entry point,
        // which is not where the calls come from.
        if (ops.isEmpty()) return
        // record = false: these ops are already in the log. Journaling them again would append
        // a duplicate of every undo.
        surface.applyOps(ops, record = false)
    }

    /**
     * ⭐ The absolute registers that differ between one object's two folds.
     *
     * ⚠️ **All four, and M8a is why that sentence is here.** This method compared presence and the
     * erased region only, which was complete for as long as nothing else could change — and
     * `SetTransform`, `SetStyle` and `SetZOrder` then produced an **empty diff**. So the undo of
     * your own lasso-move would have changed nothing on screen, and a peer's move would have
     * arrived invisibly and stayed invisible until the next page switch, with every test green
     * because the log was right and only the screen was wrong. That is M6's `setPageMeta` bug, in
     * the method that was extended to fix it then.
     *
     * A null style is skipped rather than pushed: "as captured" is what a register that has never
     * been written means, no op can say it (SPEC §4.2), and it cannot be the *result* of a change
     * either — a register never goes back to unset — so there is nothing here to miss.
     */
    private fun changedRegisters(was: InkObject, now: InkObject): List<PageOp> {
        val ops = ArrayList<PageOp>(REGISTER_COUNT)
        if (was.erased != now.erased) ops += PageOp.SetErasedRegion("", REPLAY_CLOCK, now.id, now.erased)
        if (was.transform != now.transform) ops += PageOp.SetTransform("", REPLAY_CLOCK, now.id, now.transform)
        now.style?.takeIf { it != was.style }?.let { ops += PageOp.SetStyle("", REPLAY_CLOCK, now.id, it) }
        if (was.z != now.z) ops += PageOp.SetZOrder("", REPLAY_CLOCK, now.id, now.z)
        return ops
    }

    // -----------------------------------------------------------------------
    // Pages
    // -----------------------------------------------------------------------

    fun showPage(pageId: PageId) {
        withPage(pageId) { session, page ->
            saveLocked()
            display(session, page)
            publish()
        }
    }

    /**
     * The one place the current page changes — which is why remembering it lives here rather
     * than in each of the six callers that navigate.
     */
    private suspend fun display(session: NoteSession, page: PageId) {
        currentPage = page
        val folded = session.page(page)
        currentTemplate = (folded.background as? Background.Template)?.template ?: PageTemplate.BLANK
        surface?.loadPage(folded)
        // Convenience, not data: a note that cannot record where it was still opens, on page
        // one. So this must never be able to fail an edit that has already happened.
        runCatching { store.saveViewState(session.document.id, page) }
    }

    fun goToPage(index: Int) {
        val session = this.session ?: return
        session.document.livePages.getOrNull(index)?.let { showPage(it.id) }
    }

    // The three that create or remove a page finish by *showing* it with [display] rather than by
    // calling [showPage], which would queue a second operation behind this one and leave the note
    // between two states — the page in `document`, the old one still `currentPage` — for as long as
    // whatever else is waiting on [serial] takes. They have already saved and flushed, which is the
    // only thing [showPage] would add.

    fun addPage(template: PageTemplate) {
        withPage { session, _ ->
            saveLocked()
            val index = currentPageIndex() + 1
            val added = session.addPage(index, background = Background.Template(template))
            flushAndReport(session)
            display(session, added)
            publish()
        }
    }

    fun duplicateCurrentPage() {
        withPage { session, page ->
            saveLocked()
            val copy = session.duplicatePage(page, currentPageIndex() + 1)
            flushAndReport(session)
            copy?.let { display(session, it) }
            publish()
        }
    }

    /** Delete the current page — a tombstone, and it goes nowhere near the file (rule 1). */
    fun deleteCurrentPage() {
        withPage { session, page ->
            saveLocked()
            val index = currentPageIndex()
            if (!session.deletePage(page)) {
                publish(message = CANNOT_DELETE_LAST_PAGE)
                return@withPage
            }
            flushAndReport(session)
            val next = session.document.livePages.getOrNull(index) ?: session.document.livePages.last()
            display(session, next.id)
            publish()
        }
    }

    fun movePage(toIndex: Int) {
        withPage { session, page ->
            saveLocked()
            session.movePage(page, toIndex)
            flushAndReport(session)
            publish()
        }
    }

    /**
     * The PageBar's template picker — the one production writer that changes an *existing* page's
     * paper (every other `SetPageMeta` stamps a page being created).
     *
     * ⭐ **Through [reconcile], not [display], and that is the fix rather than a tidy-up.** It used
     * to end in `display`, which reloads the page through `NoteSurface.loadPage` — so the forward
     * direction repainted by *rebuilding the page*, cancelling the in-progress stroke and refitting
     * the camera on the way, while its own undo went through [reconcile] and reached
     * `setPageMeta`, which was a no-op in the app. Two mechanisms for one change, and only one of
     * them worked: picking a paper redrew, undoing it did not.
     *
     * Now both directions are the same three lines as [undo] — fold, diff, push the diff — so
     * "changing the paper" and "un-changing the paper" cannot disagree, and neither of them touches
     * the camera or the pen.
     */
    fun setTemplate(template: PageTemplate) {
        withPage { session, page ->
            saveLocked()
            val before = session.page(page)
            session.apply(
                page,
                listOf(
                    PageOp.SetPageMeta(
                        session.nextOpId(),
                        session.nextClock(),
                        background = Background.Template(template),
                    ),
                ),
            )
            flushAndReport(session)
            reconcile(before, session.page(page))
            publish()
        }
    }

    private fun currentPageIndex(): Int {
        val session = this.session ?: return 0
        return session.document.livePages.indexOfFirst { it.id == currentPage }.coerceAtLeast(0)
    }

    // -----------------------------------------------------------------------
    // Plumbing
    // -----------------------------------------------------------------------

    /**
     * Every user-driven operation on the open note, queued behind whatever else is running.
     *
     * The blocks handed here call [saveLocked] and [session]`.flush()`, so they must hold [serial]
     * for their whole length rather than around each step: an operation that took the lock twice
     * would let a merge land in the gap between its `save` and its `flush`, which is the interleaving
     * [serial] exists to remove.
     */
    private fun withPage(pageId: PageId? = null, block: suspend (NoteSession, PageId) -> Unit) {
        val session = this.session ?: return
        val page = pageId ?: currentPage ?: return
        work.launch { serial.withLock { block(session, page) } }
    }

    private fun publish(recovered: Boolean = _state.value.recoveredFromCrash, message: String? = null) {
        val session = this.session ?: return
        _state.value = NoteEditorState(
            documentId = session.document.id,
            title = session.document.title,
            pageIndex = currentPageIndex(),
            pageCount = session.pageCount,
            pageIds = session.document.livePages.map { it.id },
            canUndo = currentPage?.let { session.canUndo(it) } ?: false,
            canRedo = currentPage?.let { session.canRedo(it) } ?: false,
            localRev = session.document.localRev,
            template = currentTemplate,
            recoveredFromCrash = recovered,
            message = message,
        )
    }

    /**
     * Stop the editor.
     *
     * Not a save — [materialize] is. Whatever was pending is already in `journal.ops` or is lost
     * with the process, which is the trade SPEC §5.1 makes deliberately.
     */
    fun close() {
        work.cancel()
    }

    /**
     * Put a message in front of the user, or clear it with null.
     *
     * One setter rather than one function per message: the snackbar is the only channel the
     * editor has, and a second way to reach it is a second thing to keep in step.
     */
    fun showMessage(text: String?) {
        _state.value = _state.value.copy(message = text)
    }

    internal companion object {
        /** SPEC §5.1 — "flushed ~2 s". */
        const val AUTOSAVE_INTERVAL_MS = 2_000L

        /** SPEC §8.3 — "debounced ~3 s after the last stroke on a page". */
        const val RECOGNITION_DEBOUNCE_MS = 3_000L

        /**
         * How often the debounce is checked. Deliberately coarser than the debounce itself:
         * the cost of being a second late reading a page nobody is looking at is nothing, and
         * the cost of waking a coroutine every 200 ms for the whole time a note is open is a
         * battery complaint nobody would trace back to here.
         */
        const val RECOGNITION_POLL_MS = 1_000L
        const val DEFAULT_TITLE = "Notebook"
        const val CANNOT_DELETE_LAST_PAGE = "A note needs at least one page."

        /**
         * Deliberately not "save failed" — the save succeeded. Only the *published*
         * form did not, and the distinction is the one the user needs: their writing
         * is safe, and the file other apps read is one session behind.
         */
        const val PUBLISH_FAILED = "Saved. Could not update the shareable PDF this time."

        /**
         * Export found nothing to hand over. Says which half is fine, because "export
         * failed" on a note-taking app reads as "your note is gone" and it is not: the
         * bundle is the truth (SPEC §5.3) and it is already on disk.
         */
        const val EXPORT_FAILED = "Your note is saved, but the PDF could not be prepared to share."

        /** Ops built only to update the screen; never journaled. See `reconcile`. */
        val REPLAY_CLOCK = LamportClock(0, "replay")

        /** Transform, style, z-order, erased region — see [NoteEditor.changedRegisters]. */
        const val REGISTER_COUNT = 4
    }
}

/** Everything the note UI shows. */
data class NoteEditorState(
    val documentId: DocumentId? = null,
    val title: String = "",
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    /** The live pages, in order — what the page overview draws a thumbnail for (M3). */
    val pageIds: List<PageId> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val localRev: Long = 0,
    val template: PageTemplate = PageTemplate.BLANK,
    /**
     * True when the journal ended in a torn record — a crash mid-stroke.
     *
     * Surfaced rather than swallowed. SPEC §5.6 makes the same argument for Recovered mode:
     * the user is entitled to know that something was lost and what.
     */
    val recoveredFromCrash: Boolean = false,
    val message: String? = null,
)
