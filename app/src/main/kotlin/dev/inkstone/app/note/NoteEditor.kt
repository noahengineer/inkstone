package dev.inkstone.app.note

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageTemplate
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.storage.NoteSession
import dev.inkstone.core.storage.NoteStore
import dev.inkstone.core.storage.NoteTrash
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the editing session for the note on screen: autosave, page navigation, undo.
 *
 * ## The two rhythms (SPEC §5.1)
 *
 * - **Every ~2 s**, [autosaveLoop] drains the ink surface and appends to `journal.ops`. One
 *   append. The document is not rewritten.
 * - **On close**, [materialize] folds the journal into the bundle. That is where the rewrite
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
class NoteEditor(
    private val fs: FileSystem,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val drainDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val autosaveIntervalMs: Long = AUTOSAVE_INTERVAL_MS,
) {

    /**
     * A child of [scope], so the editor can be stopped without stopping its owner.
     *
     * The autosave loop runs forever by design, and something has to be able to end it: the
     * `ViewModel` does so via [close] from `onCleared`, and a test does so at the end of the test.
     * Without this, the loop is an uncancellable child of whatever scope it was handed.
     */
    private val work = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val ids = IdFactory(clock)
    private val store = NoteStore(fs, clock, ids)
    private val trash = NoteTrash(fs, clock)

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
     * Persisted with the note rather than generated per launch: a device whose id changes every
     * start would break every tie differently, and two devices could then disagree about the
     * order of the same two ops. M5 moves this to a proper per-install value; for now it comes
     * from the note that created it.
     */
    private var deviceId: String = ""

    /** What the editor needs from the ink surface. Keeps `androidx.ink` out of this class. */
    interface NoteSurface {
        fun bindPage(nextObjectId: () -> ObjectId, nextOpId: () -> String, nextClock: () -> LamportClock)

        fun loadPage(page: FoldedPage)

        fun drainOps(): List<List<PageOp>>

        fun applyOps(ops: List<PageOp>, record: Boolean)
    }

    init {
        work.launch {
            openOrCreate()
            autosaveLoop()
        }
    }

    // -----------------------------------------------------------------------
    // Opening
    // -----------------------------------------------------------------------

    /**
     * Open the note on disk, or create one.
     *
     * M3 owns the library, so "which note" is not a question this milestone answers: it takes
     * the first note in `notes/` and makes one if there is none. Reading the directory rather
     * than storing a "last opened" pointer means there is no second source of truth to get out
     * of step with the files.
     */
    private suspend fun openOrCreate() {
        trash.purgeExpired()
        val existing = fs.list(NoteLayout.NOTES_DIR).firstOrNull()
        val note = if (existing != null) {
            store.open(DocumentId(existing))
        } else {
            null
        } ?: store.create(
            title = DEFAULT_TITLE,
            deviceId = ids.newDeviceId(),
            background = Background.Template(PageTemplate.RULED),
        )

        deviceId = note.document.deviceId.ifEmpty { ids.newDeviceId() }
        val opened = NoteSession.open(store, ids, clock, note, deviceId)
        session = opened
        // Directly, not through `showPage`: that launches a coroutine, and publishing before it
        // ran would show a note with no page.
        display(opened, opened.document.livePages.first().id)
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
        currentPage?.let { page -> work.launch { surface.loadPage(session.page(page)) } }
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
     * One `session.apply` per **gesture**, not per drain, so a drain that catches three strokes
     * produces three undo entries rather than one (SPEC §4.6). They still reach disk in a
     * single journal record, because the append happens once at the end.
     */
    suspend fun save() {
        val session = this.session ?: return
        val page = currentPage ?: return
        val gestures = surface?.let { withContext(drainDispatcher) { it.drainOps() } }.orEmpty()
        for (gesture in gestures) {
            session.apply(page, gesture)
        }
        if (gestures.isNotEmpty() || session.hasUnflushedWork) {
            session.flush()
            publish()
        }
    }

    /**
     * SPEC §5.1 — fold the journal into the bundle. Called when the note goes away.
     *
     * `Activity.onStop` is the last point the process is reliably alive, so this is what keeps
     * the invariant that the published file is never more than one editing session stale.
     */
    fun materialize() {
        val session = this.session ?: return
        work.launch {
            save()
            session.close()
            publish()
        }
    }

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
            save()
            val before = session.page(page)
            if (!session.undo(page)) return@withPage
            reconcile(before, session.page(page))
            publish()
        }
    }

    fun redo() {
        withPage { session, page ->
            save()
            val before = session.page(page)
            if (!session.redo(page)) return@withPage
            reconcile(before, session.page(page))
            publish()
        }
    }

    /**
     * Bring the screen in line with a freshly folded page.
     *
     * The ops the session appended are already folded into `after`, so rather than replaying
     * them through the surface — which would journal them a second time — this diffs the two
     * folds and tells the surface what changed. For an undo that is one or two objects.
     */
    private fun reconcile(before: FoldedPage, after: FoldedPage) {
        val surface = this.surface ?: return
        val beforeIds = before.objects.map { it.id }.toSet()
        val afterIds = after.objects.map { it.id }.toSet()

        val removed = beforeIds - afterIds
        val added = after.objects.filter { it.id !in beforeIds }
        // An object present in both may still have changed — an undone erase is the same
        // object with a different absolute region.
        val reErased = after.objects.filter { obj ->
            obj.id in beforeIds && before.find(obj.id)?.erased != obj.erased
        }

        val ops = ArrayList<PageOp>(removed.size + added.size + reErased.size)
        removed.forEach { ops += PageOp.DeleteObject("", REPLAY_CLOCK, it) }
        added.forEach { ops += PageOp.AddObject("", REPLAY_CLOCK, it) }
        reErased.forEach { ops += PageOp.SetErasedRegion("", REPLAY_CLOCK, it.id, it.erased) }
        // record = false: these ops are already in the log. Journaling them again would append
        // a duplicate of every undo.
        surface.applyOps(ops, record = false)
    }

    // -----------------------------------------------------------------------
    // Pages
    // -----------------------------------------------------------------------

    fun showPage(pageId: PageId) {
        withPage(pageId) { session, page ->
            save()
            display(session, page)
            publish()
        }
    }

    private suspend fun display(session: NoteSession, page: PageId) {
        currentPage = page
        val folded = session.page(page)
        currentTemplate = (folded.background as? Background.Template)?.template ?: PageTemplate.BLANK
        surface?.loadPage(folded)
    }

    fun goToPage(index: Int) {
        val session = this.session ?: return
        session.document.livePages.getOrNull(index)?.let { showPage(it.id) }
    }

    fun addPage(template: PageTemplate) {
        withPage { session, _ ->
            save()
            val index = currentPageIndex() + 1
            val added = session.addPage(index, background = Background.Template(template))
            session.flush()
            showPage(added)
        }
    }

    fun duplicateCurrentPage() {
        withPage { session, page ->
            save()
            val copy = session.duplicatePage(page, currentPageIndex() + 1)
            session.flush()
            copy?.let { showPage(it) }
        }
    }

    /** Delete the current page — a tombstone, and it goes nowhere near the file (rule 1). */
    fun deleteCurrentPage() {
        withPage { session, page ->
            save()
            val index = currentPageIndex()
            if (!session.deletePage(page)) {
                publish(message = CANNOT_DELETE_LAST_PAGE)
                return@withPage
            }
            session.flush()
            val next = session.document.livePages.getOrNull(index) ?: session.document.livePages.last()
            showPage(next.id)
        }
    }

    fun movePage(toIndex: Int) {
        withPage { session, page ->
            save()
            session.movePage(page, toIndex)
            session.flush()
            publish()
        }
    }

    fun setTemplate(template: PageTemplate) {
        withPage { session, page ->
            save()
            val background = Background.Template(template)
            session.apply(
                page,
                listOf(PageOp.SetPageMeta(session.nextOpId(), session.nextClock(), background = background)),
            )
            session.flush()
            display(session, page)
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

    private fun withPage(pageId: PageId? = null, block: suspend (NoteSession, PageId) -> Unit) {
        val session = this.session ?: return
        val page = pageId ?: currentPage ?: return
        work.launch { block(session, page) }
    }

    private fun publish(recovered: Boolean = _state.value.recoveredFromCrash, message: String? = null) {
        val session = this.session ?: return
        _state.value = NoteEditorState(
            title = session.document.title,
            pageIndex = currentPageIndex(),
            pageCount = session.pageCount,
            canUndo = session.canUndo,
            canRedo = session.canRedo,
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

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private companion object {
        /** SPEC §5.1 — "flushed ~2 s". */
        const val AUTOSAVE_INTERVAL_MS = 2_000L
        const val DEFAULT_TITLE = "Notebook"
        const val CANNOT_DELETE_LAST_PAGE = "A note needs at least one page."

        /** Ops built only to update the screen; never journaled. See `reconcile`. */
        val REPLAY_CLOCK = LamportClock(0, "replay")
    }
}

/** Everything the note UI shows. */
data class NoteEditorState(
    val title: String = "",
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
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
