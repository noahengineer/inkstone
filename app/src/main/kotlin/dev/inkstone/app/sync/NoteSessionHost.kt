package dev.inkstone.app.sync

import android.util.Log
import dev.inkstone.app.library.LibraryRepository
import dev.inkstone.app.search.NoteRecogniser
import dev.inkstone.app.search.RecognitionReport
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.DocumentMaterializer
import dev.inkstone.core.model.ElementId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.MaterializedFile
import dev.inkstone.core.model.PdfPublisher
import dev.inkstone.core.storage.DeviceIdentity
import dev.inkstone.core.storage.Element
import dev.inkstone.core.storage.ElementCopies
import dev.inkstone.core.storage.FileByteSource
import dev.inkstone.core.storage.MergeReport
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.storage.NoteMerge
import dev.inkstone.core.storage.NoteSession
import dev.inkstone.core.storage.NoteStore
import dev.inkstone.core.storage.conflictedCopyOf
import dev.inkstone.core.storage.renameNote
import dev.inkstone.core.sync.ConflictedCopy
import dev.inkstone.core.sync.MergeCommit
import dev.inkstone.core.sync.RemoteInstall
import dev.inkstone.core.sync.RemoteMergeGate
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ⭐ **The single-writer gate over a note's one `NoteSession`.**
 *
 * Two things go through it, and they are here together because they need the same guarantee:
 * [RemoteMergeGate] — everything a reconcile pass is allowed to do to a note — and [renameNote],
 * because SPEC §7.7 layer 2 resolves titles by a Lamport clock and stamping one means writing the
 * note's own document.
 *
 * ## Why the reconciler cannot do this itself
 *
 * `:core:sync` deliberately cannot open a note (see [RemoteMergeGate]'s KDoc). What it needs is
 * three things this module owns: the exclusion below, the library index, and — for a conflicted
 * copy — a `NoteStore` that can mint a new identity.
 *
 * ## ⚠️ Exactly one `NoteSession` per note, and this is what enforces it
 *
 * Two sessions over one note each hold their own `pending` list and their own folded pages, so
 * whichever materialized **second** would write a bundle missing the other's work — silently, with
 * a valid PDF and the right page count. `NoteSession` is single-threaded by contract and
 * deliberately has no lock of its own, so the exclusion belongs here:
 *
 * - a per-note [Mutex], so two callers of this gate cannot overlap on one note. It is no longer what
 *   keeps two reconcile *passes* apart — `Reconciler.reconcile` is single-flighted now, and had to
 *   be, because two passes also share the staging directory and the ETag cache, which this mutex
 *   never covered. It stays because [RemoteMergeGate] is public to `:core:sync` and the invariant is
 *   this class's to keep, not the caller's to remember;
 * - a registry of **live editors**. If a [NoteEditorHandle] holds the note, the merge is handed to
 *   *it* and runs inside the editor's own `work` scope, serialized against everything else the
 *   editor is doing, against the session it already has. This host never opens a second one — and it
 *   does not need the editor to have finished opening the note either: [NoteEditorHandle.mergeRemoteBundle]
 *   waits for the open. Before M6 shipped it answered null in that window, and null here means *"do
 *   it yourself"*.
 *
 * One residual is left, and it is narrower than the KDoc here used to claim: an editor that
 * registers *after* the registry read on line one of [merge] still gets a session built from the
 * pre-merge bundle. What happens then is **not** *"nothing is destroyed and its screen is a merge
 * behind"*. Its `OpenNote.reader` is a `Zip.Directory` of byte offsets over the file this host is
 * about to rewrite, so the editor's next read through it fails a local-header or CRC check and
 * `materialize` **throws** — out of `materializeNow`, out of `viewModelScope.launch`. No bytes are
 * lost (the throw happens while building `pages`, before anything is written, and the journal is
 * untouched), but the note does not materialize and the exception is uncaught.
 *
 * Closing it needs the editor to hold *this* mutex across its own open, and the editor cannot: with
 * no `documentId` — M2's fallback, still the path a launch with an unreadable index takes — it does
 * not know which note it is opening until it has read the notes directory. That is the change to
 * make when that fallback goes.
 *
 * ## What every path here guarantees
 *
 * - **`materialize` runs before returning, with `carried` and `carriedManifest` passed through.**
 *   Not optional: the first bundle rewrite after a merge would otherwise delete a peer's
 *   recognised text and anything a newer build added — invisibly, because by definition they are
 *   the entries this build cannot render.
 * - **Nothing is trashed, replaced or recovered.** A pulled file is never auto-recovered; that is
 *   layer 3's job and layer 3 keeps both copies.
 * - **Remote ops never reach the undo stack.** `NoteSession.merge` is the only door they come
 *   through, and it is the only one that cannot touch `undoStack`.
 */
@Singleton
class NoteSessionHost @Inject constructor(
    private val fs: FileSystem,
    private val clock: Clock,
    private val library: LibraryRepository,
    /**
     * SPEC §5.2's publisher, for [materialize] — the third writer this class had to take
     * responsibility for. See that method for what publishing a note twice costs.
     */
    private val publisher: PdfPublisher,
    /** SPEC §8.3. Injected rather than built here so `:app` owns the ML Kit wiring. */
    private val recogniser: NoteRecogniser,
    /** ⭐ SPEC §4.9.7 — the Element library, for replacing a revised Element's copies. */
    private val elements: dev.inkstone.app.elements.ElementShelf,
) : DocumentMaterializer {

    private val ids = IdFactory(clock)
    private val store = NoteStore(fs, clock, ids)

    /** SPEC §4.1's Lamport tiebreak — one id per install, persisted. See [DeviceIdentity]. */
    private val deviceIdentity = DeviceIdentity(fs, ids)

    /**
     * One mutex per note, created on first use and **never removed**.
     *
     * Never removed on purpose: a map that evicted an unlocked entry would have to check-then-act,
     * and losing that race hands two callers two different mutexes for one note — which is the
     * absence of the exclusion, arrived at down a path that looks like tidying up. The cost is one
     * `Mutex` per note ever pulled, which against SPEC §9's 500-note library is nothing.
     *
     * `computeIfAbsent` rather than `getOrPut`: two reconcile passes racing on a note the app has
     * never pulled before is exactly the case this map exists for, and `getOrPut` on a plain map is
     * a read followed by a write.
     */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * Editors currently holding a note. Keyed by id value; at most one per note in practice.
     *
     * Concurrent rather than mutex-guarded because [unregister] is called from `ViewModel.onCleared`,
     * which is not a coroutine and runs *after* `viewModelScope` has been cancelled — so there is no
     * scope left to suspend in. A registration that could only be released from a coroutine would
     * leak the handle of every editor the user closes, and each leaked handle would keep routing
     * merges into a session that has been shut down.
     */
    private val editors = ConcurrentHashMap<String, NoteEditorHandle>()

    /**
     * What a live editor offers this host.
     *
     * An interface rather than a reference to `NoteEditor` so that the editor stays a plain class
     * with no dependency on the sync package, and so the merge can be driven from a unit test.
     */
    interface NoteEditorHandle {
        /**
         * Merge a peer's bundle into the session **this editor already holds**, inside its own
         * `work` scope and serialized against everything else it is doing, and bring its screen in
         * line without refitting the camera.
         *
         * ⚠️ **Suspends until the editor has a session** rather than answering null while it is
         * still opening the note. Null there was M6's note-open bug: the host would open a second
         * session and rewrite the bundle under the byte offsets the editor had already read.
         *
         * @return null only when the editor can no longer run anything — its scope is cancelled, or
         *   its open failed and left no session. Both mean there is no second writer, so the host
         *   doing the merge itself is correct.
         */
        suspend fun mergeRemoteBundle(bundlePath: String): MergeCommit?

        /**
         * Rename the note through the session **this editor already holds** (SPEC §7.7 layer 2), and
         * tell the library itself — it is the side with the revision and the page count in hand.
         *
         * Suspends until the editor has a session, like [mergeRemoteBundle].
         *
         * @return false only when the editor can no longer run anything, which is the one case where
         *   the host stamping the title through its own session is correct.
         */
        suspend fun rename(title: String): Boolean

        /**
         * Fold and republish through the session **this editor already holds** — `MaterializeWorker`
         * and `OutboxDrainer` reaching in rather than writing `document.pdf` from underneath it.
         *
         * Suspends until the editor has a session, like [mergeRemoteBundle].
         *
         * ⚠️ **Answers the file, not a `Boolean`.** The drainer records `lastSyncedRev` from the
         * revision the uploaded bytes were built from, and only the editor knows what that is once
         * it has folded its own journal. A `Boolean` here is how the upload path came to record a
         * revision the server did not have — see [DocumentMaterializer].
         *
         * @return null only when the editor can no longer run anything, or the PDF could not be
         *   written. Both mean the host publishing it itself is correct.
         */
        suspend fun materialize(): MaterializedFile?

        /**
         * Read this note's handwriting through the session **this editor already holds**
         * (SPEC §8.3's background pass, arriving at a note that happens to be open).
         *
         * Suspends until the editor has a session, like [mergeRemoteBundle].
         *
         * @return null only when the editor can no longer run anything, which is the one case
         *   where the host recognising through its own session is correct.
         */
        suspend fun recognise(): RecognitionReport?

        /**
         * ⭐ How many copies of an Element this editor's note holds (SPEC §4.9.7), counted through
         * the session it already has. Suspends until there is one, like [mergeRemoteBundle].
         */
        suspend fun countElementCopies(elementId: ElementId): Int?

        /**
         * ⭐ Replace those copies with the revised Element, through this editor's session — the same
         * reason a merge comes here: two sessions over one note each hold their own pending ops, and
         * whichever materializes second drops the other's work.
         */
        suspend fun updateElementCopies(revision: dev.inkstone.app.elements.ElementRevision): Int?
    }

    /** Called from the editor's `ViewModel` as it is created; must be paired with [unregister] or [retire]. */
    fun register(noteId: DocumentId, handle: NoteEditorHandle) {
        editors[noteId.value] = handle
    }

    /**
     * ⛔ **Where a closing editor's last save runs — a scope no screen owns.**
     *
     * `NoteActivity.onStop` used to launch the save in `viewModelScope`, and pressing Back runs
     * `onStop` and then clears the view model, which cancels that scope. The save's first
     * suspension is the drain of the surface's recorder, and the drain is destructive: its result
     * came back to a cancelled coroutine and was thrown away. Every stroke drawn in the two seconds
     * before Back was lost — reproduced twice on the emulator on 2026-09-24, journal untouched.
     *
     * `Dispatchers.Main.immediate` because the editor's session is confined to the main thread by
     * contract (`NoteEditor.work`), and immediate so the save starts inside `onStop` itself, while
     * the surface is still attached. A failure is logged rather than thrown: an exception escaping a
     * scope with no handler kills the process, which is not a better outcome for a note whose ink
     * the journal already holds.
     *
     * Lazy because nothing but an editor closing needs it, and the JVM tests that build this class
     * have no main dispatcher to create it with.
     */
    private val leaving by lazy {
        CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate +
                CoroutineExceptionHandler { _, failure -> Log.w(TAG, "a closing note's last save failed", failure) },
        )
    }

    /**
     * Where a session of this host's own does its work when the caller is a screen: the Element
     * editor's Done and its *"Update them all"*. Opening and folding a note is the most expensive
     * read the app does, and none of it needs the main thread. The session is local to the call and
     * the note's mutex is held throughout, so only one coroutine ever touches it.
     */
    private val ownSessionContext = Dispatchers.Default

    /**
     * Run a stopping editor's [save] under this note's mutex, in [leaving] — so a finishing screen
     * cannot cancel it, and a merge or a publish cannot interleave with it.
     *
     * [save] is a plain journal save for most stops, and the close-time materialize only when the
     * screen is finishing. See `NoteEditorViewModel.onStopped` for why the two differ.
     */
    fun saveOnLeaving(noteId: DocumentId, save: suspend () -> Unit) {
        leaving.launch { withLock(noteId) { save() } }
    }

    /**
     * The editor has gone: unregister it and [close] it — **after** anything [saveOnLeaving] queued
     * for this note has finished.
     *
     * [unregister] ran synchronously from `onCleared`, and that was right while the close-time save
     * died with the view model. Now the save outlives it, and an editor that unregistered first
     * would leave a gap in which the drainer finds no editor and publishes the note itself — reading
     * `note.inkstone` while the editor rewrites it, which is the stale-marker overlap
     * [materialize]'s KDoc describes. Doing both under the note's mutex, after the save, closes it:
     * a merge that arrives meanwhile queues on the same mutex and still finds the editor, which
     * still runs it.
     */
    fun retire(noteId: DocumentId, handle: NoteEditorHandle, close: () -> Unit) {
        leaving.launch {
            withLock(noteId) {
                editors.remove(noteId.value, handle)
                close()
            }
        }
    }

    /**
     * Called when the editor goes away.
     *
     * Removes only if [handle] is still the registered one, so an editor recreated across a
     * rotation cannot have its replacement's registration torn down by the outgoing one.
     */
    fun unregister(noteId: DocumentId, handle: NoteEditorHandle) {
        editors.remove(noteId.value, handle)
    }

    // -----------------------------------------------------------------------
    // The library's rename (SPEC §7.7 layer 2)
    // -----------------------------------------------------------------------

    /**
     * ⭐ **Rename a note — the whole rename, both halves.**
     *
     * It goes through this class rather than through `LibraryRepository` because a rename is a write
     * to the note's own document: SPEC §7.7 layer 2 resolves titles by `Document.titleClock`, so the
     * title has to be stamped in the note's one [NoteSession], with a clock from that note's
     * generator. Before M6's review the library renamed only its index row, which is why the LWW
     * title had no writer at all and two devices could hold two titles for good.
     *
     * The two halves, in order:
     *
     * 1. **The document.** A live editor's session if there is one — the same hand-off a merge takes,
     *    for the same reason: two sessions over one note and whichever materializes second writes a
     *    bundle missing the other's work. Otherwise `NoteStore.renameNote` under this note's mutex.
     * 2. **The index and the remote path**, through `LibraryRepository.noteRenamed` — from whichever
     *    side did step 1, because that is the side holding the new `localRev` and page count.
     *
     * The title is made legal *first* and once. The document's string and the index's must be
     * identical or the peer learns a name the library never shows.
     *
     * @return false when there is no such note. The library row is left alone in that case, which is
     *   the honest answer: a note the store cannot open is not one this can rename.
     */
    suspend fun renameNote(noteId: DocumentId, title: String): Boolean {
        val legal = library.legalTitle(title)
        return withLock(noteId) {
            if (editorFor(noteId)?.rename(legal) == true) return@withLock true
            val document = store.renameNote(noteId, legal, deviceIdentity.get(), ids, clock) ?: return@withLock false
            library.noteRenamed(noteId, document.title, document.localRev, document.livePages.size)
            true
        }
    }

    // -----------------------------------------------------------------------
    // ⭐ Element copies (SPEC §4.9.7)
    // -----------------------------------------------------------------------

    /**
     * How many copies of [elementId] [noteId] holds — through its live editor if it has one, else
     * through a session of this host's own, under the note's mutex either way.
     *
     * ⛔ **The own-session branch runs on [ownSessionContext], never on the caller's thread.** The
     * caller is the Element editor's Done, from a Compose scope, so it used to open and fold the
     * whole note on the UI thread. That cost the physics note several seconds of a frozen screen
     * and, on 2026-10-10, an `OutOfMemoryError` in `BundleReader.page` on `AndroidUiDispatcher`,
     * because the note's own editor was reloading the same note beside it
     * (`docs/NOTE-STAYS-LOADED.md`). The merge and recognition branches below already run where
     * their WorkManager callers do, which is never the UI thread.
     *
     * @return 0 for a note that no longer opens: it was trashed, or the list that named it is stale,
     *   and in both cases there is nothing to update.
     */
    suspend fun countElementCopies(noteId: DocumentId, elementId: ElementId): Int = withLock(noteId) {
        editorFor(noteId)?.countElementCopies(elementId)?.let { return@withLock it }
        withContext(ownSessionContext) {
            val note = store.open(noteId) ?: return@withContext 0
            val session = NoteSession.open(store, ids, clock, note, deviceIdentity.get())
            session.document.livePages.sumOf { ElementCopies.count(session.page(it.id), elementId) }
        }
    }

    /**
     * ⭐ Replace every copy of [element] in [noteId] with its current version.
     *
     * The rename's shape exactly: the live editor first, because it holds the session; otherwise a
     * session of this host's own, then **materialize** (the new pieces' picture bytes travel as
     * `extraCarried`, so they reach `assets/` in the same write as the ops that name them — SPEC
     * §4.9.4's order) and **tell the library**, which is the only route to a fresh thumbnail and to
     * an upload (CLAUDE.md: *"`session.flush()` is half of saving"*).
     *
     * One undo entry per page, recorded on the note's own stack — so a person who opens the note
     * afterwards and regrets the update can press undo.
     *
     * The own-session branch runs on [ownSessionContext], for [countElementCopies]'s reason. This one
     * also writes a whole bundle, so it costs even more on the UI thread.
     *
     * @return how many copies were replaced.
     */
    suspend fun updateElementCopies(noteId: DocumentId, revision: dev.inkstone.app.elements.ElementRevision): Int =
        withLock(noteId) {
            editorFor(noteId)?.updateElementCopies(revision)?.let { return@withLock it }
            withContext(ownSessionContext) { updateCopiesInOwnSession(noteId, revision) }
        }

    private suspend fun updateCopiesInOwnSession(
        noteId: DocumentId,
        revision: dev.inkstone.app.elements.ElementRevision,
    ): Int {
        val element = revision.element
        val note = store.open(noteId) ?: return 0
        val session = NoteSession.open(store, ids, clock, note, deviceIdentity.get())
        var replaced = 0
        val assets = LinkedHashMap<String, ByteArray>()
        for (ref in session.document.livePages) {
            val folded = session.page(ref.id)
            val update = elements.replaceCopies(
                folded,
                element,
                ids::newObjectId,
                session::nextOpId,
                session::nextClock,
                revision.originMoved,
            ) ?: continue
            replaced += ElementCopies.count(folded, element.id)
            assets += update.assets
            session.apply(ref.id, update.ops)
        }
        if (replaced > 0) {
            session.materialize(extraCarried = assets)
            library.noteSaved(noteId, session.document.localRev, session.pageCount)
        }
        return replaced
    }

    // -----------------------------------------------------------------------
    // Publishing (SPEC §5.1/§5.2)
    // -----------------------------------------------------------------------

    /**
     * ⭐ **Republish `document.pdf` — `MaterializeWorker`'s whole job, routed through the gate.**
     *
     * The worker exists for the notes the editor's `onStop` never covers: one bound to Nextcloud
     * from the library without ever being opened, and "idle while charging". It used to call
     * the raw [PdfPublisher] directly, which for an *open* note is a second writer with no ordering
     * against the first — the last half of M6's overlap finding, and the half the unique temp name
     * did not close.
     *
     * The damage is not a torn file. Both writes are atomic and, since M6, uniquely named. It is a
     * **stale marker**: `InkstonePdfMaterializer` reads `note.inkstone`, embeds it, and records its
     * SHA-256 in the XMP marker (SPEC §5.6). If the editor rewrites the bundle between that read and
     * the worker's write, the worker's `document.pdf` lands carrying the hash of a bundle that no
     * longer exists — and the next library rebuild reads that as `Recovered(BUNDLE_HASH_MISMATCH)`.
     * Recovery is REPLACE-not-union, stamped at `highestKnownCounter + 1_000_000`, so it then wins
     * every register on the next sync. A healthy note rewritten lossily because it was published
     * twice, with a valid PDF at every step and nothing reported.
     *
     * So: the note's mutex, and the live editor first. [NoteEditorHandle.materialize] does strictly
     * more than the worker asked for — it flushes the journal before publishing — which is the right
     * kind of more.
     *
     * ⚠️ **Throws what the materializer throws.** Deliberately not wrapped: publishing can fail for
     * a missing note or a full disk, `MaterializeWorker` retries all of them, and swallowing the
     * exception here would turn a retryable failure into a silent success — the worker's own
     * `catch` is the policy and this must not pre-empt it. The mutex is released either way.
     */
    override suspend fun materialize(noteId: DocumentId): MaterializedFile = withLock(noteId) {
        // The editor reports the note to the library itself (`onMaterialized`, which redraws the
        // thumbnail from the fold it has just written), so `noteRepublished` is only for the other
        // branch. Calling both would record one revision twice.
        editorFor(noteId)?.materialize()?.let { return@withLock it }
        val materialized = publisher.publish(noteId)
        library.noteRepublished(noteId, materialized.localRev)
        materialized
    }

    // -----------------------------------------------------------------------
    // RemoteMergeGate
    // -----------------------------------------------------------------------

    /** The gate, as the reconciler sees it. Separate object so the host's own API stays small. */
    val gate: RemoteMergeGate = object : RemoteMergeGate {

        override suspend fun merge(noteId: DocumentId, bundlePath: String): MergeCommit =
            this@NoteSessionHost.merge(noteId, bundlePath)

        override suspend fun install(install: RemoteInstall): DocumentId? = this@NoteSessionHost.install(install)

        override suspend fun conflictedCopy(copy: ConflictedCopy): DocumentId? =
            this@NoteSessionHost.conflictedCopy(copy)

        override suspend fun trashRemotelyDeleted(noteId: DocumentId, reason: String): Boolean = withLock(noteId) {
            // ⭐ **Never while an editor holds the note.** `NoteTrash.trash` *moves*
            // `notes/<id>/` away, and the live `NoteSession` is not told: its next two-second
            // autosave appends to `journal.ops` at the old path, `AndroidFileSystem` creates the
            // parent directory first, and the note directory comes back holding a journal and no
            // bundle. The note is then **unrestorable** — `NoteTrash.restore` refused outright while
            // `notes/<id>/` existed — cannot be opened either, and the trash sweeper deletes the
            // only copy of every page at day 30. Permanent loss, out of the one operation rule 1
            // exists to make reversible.
            //
            // Refusing costs one poll interval: the binding stays unstamped, so the pass after the
            // user closes the note reaches the same conclusion and acts on it. `NoteTrash.restore`
            // was taught to tell a stub from a live note in the same change, because the registry
            // check is a lock this host holds and the editor does not — it closes the window the
            // user actually meets, not the race.
            if (editorFor(noteId) != null) return@withLock false

            // The local half only: trash the directory, mark the row, sweep both thumbnail tiers.
            // `SyncEngine.noteDeletedRemotely` and the attention row that carries [reason] are the
            // reconciler's, and it makes both calls itself — see `Reconciler.trashAbsent`. Which is
            // why [reason] is not passed on: it would be written to `note.lastError` and cleared by
            // `noteDeletedRemotely` a moment later.
            library.trashRemotelyDeleted(noteId)
            true
        }

        override suspend fun noteFolderChanged(noteId: DocumentId, folderId: FolderId) {
            library.noteFolderChanged(noteId, folderId)
        }
    }

    /**
     * SPEC §7.7 layers 1 and 2 — fold the peer's op log in, then rewrite the bundle.
     *
     * The order inside is [NoteMerge]'s and is not negotiable; what this adds is who holds the
     * session and that `materialize` cannot be skipped.
     *
     * @return a report the reconciler can act on. A bundle that will not open comes back as
     *   [MergeReport.DifferentDocument] with the local id — *"this file is not this note"* — which
     *   routes to layer 3 and leaves the binding's ETag where it was, so the next pass fetches
     *   again. Never an exception: a conflict raised out of a WorkManager job is read as a
     *   transport failure and retried forever.
     */
    private suspend fun merge(noteId: DocumentId, bundlePath: String): MergeCommit = withLock(noteId) {
        editorFor(noteId)?.mergeRemoteBundle(bundlePath)?.let { return@withLock it }

        val note = store.open(noteId) ?: return@withLock unreadable(noteId)
        val session = NoteSession.open(store, ids, clock, note, deviceIdentity.get())
        // ⭐ [NoteMerge.mergeBundle], not `BundleReader.open` behind a `runCatching`. That is what
        // this line used to be, and it turned *every* refusal into `DifferentDocument` — including
        // the one case SPEC §7.7 layer 3 has a separate message for, a bundle whose manifest is a
        // schema this build does not understand. `MergeReport.TooNew` was therefore a branch nothing
        // could reach. `mergeBundle` decides before the open can throw.
        val report = NoteMerge.mergeBundle(session, FileByteSource(fs, bundlePath))
        if (report is MergeReport.Merged) {
            // ⚠️ Both arguments, always. Dropping them is the invisible deletion of a peer's
            // recognised text and of every entry a newer build added — see the class KDoc.
            session.materialize(report.carried, report.carriedManifest)
            // The **resolved** title (SPEC §7.7 layer 2's reader half): the local one when the local
            // one won. Without it a peer's rename lands in the bundle and the library tile keeps the
            // old string forever, which is how the register stayed invisible through all of M6.
            library.notePulled(noteId, session.document.localRev, session.pageCount, session.document.title)
        }
        MergeCommit(report, session.document.localRev)
    }

    /**
     * ⭐ SPEC §8.3's recognition pass, **through the gate**.
     *
     * Recognition writes `text/<pageId>.json` into the bundle, so it is a bundle writer, so it
     * is exactly the second writer this class exists to exclude. M6's gate-5 bug was a caller
     * given the raw publisher and trusted to behave; the lesson taken from it was that a
     * parameter which can only be given the safe thing needs no warning — hence this method,
     * and hence `RecognitionWorker` holding a `NoteSessionHost` and no `NoteStore`.
     *
     * ⚠️ **`materialize` runs only when something was actually read.** A pass over an unchanged
     * note must not rewrite the bundle: the rewrite bumps `localRev`, `noteChanged` enqueues an
     * upload, and a nightly batch over a 500-note library would re-upload the library every
     * night for ever. `NoteRecogniser` skips pages whose text is already current, so an
     * unchanged note reports zero and this does nothing.
     */
    suspend fun recognise(noteId: DocumentId): RecognitionReport = withLock(noteId) {
        editorFor(noteId)?.recognise()?.let { return@withLock it }

        val note = store.open(noteId) ?: return@withLock RecognitionReport.NOTHING
        val session = NoteSession.open(store, ids, clock, note, deviceIdentity.get())
        val report = recogniser.recognise(session)
        if (report.changedAnything) {
            session.materialize()
            // Not `noteMaterialized`: recognition changes no pixels, so invalidating the
            // thumbnail would redraw every page of every note the batch touched. It does
            // change the published bytes, so the note does owe an upload.
            library.noteRecognised(noteId, session.document.localRev, session.pageCount)
        }
        report
    }

    /**
     * ⭐ Handwriting synthesis's read of one note (docs/HANDWRITING-SYNTHESIS.md §5.6) — **read-only**,
     * through the gate, and never a note an editor holds.
     *
     * Learning writes nothing into a note, so it is not a second *writer*. It would be a second
     * *reader* of a session, though, and a session opened over a note an editor holds reads the bundle
     * without the editor's pending work — so an open note is skipped, which costs one night's delay on
     * the page in front of the user. The [LearningNote] handed to [block] has reads only: a caller
     * cannot write through it however it is used, which is the gate-5 lesson applied before the bug.
     *
     * Off the caller's thread, like every own-session branch here should be: the fold is the cost.
     *
     * @return null when the note is open in an editor or will not open.
     */
    suspend fun <T> readForLearning(noteId: DocumentId, block: suspend (LearningNote) -> T): T? = withLock(noteId) {
        if (editorFor(noteId) != null) return@withLock null
        withContext(Dispatchers.Default) {
            val note = store.open(noteId) ?: return@withContext null
            block(LearningNote(NoteSession.open(store, ids, clock, note, deviceIdentity.get())))
        }
    }

    /**
     * SPEC §7.6 step 3, third branch — a note from a peer this device has never held.
     *
     * ⚠️ **A directory that already exists is never overwritten.** `NoteStore.installBundle`
     * writes the bundle and resets the journal, which for a note the user has been editing
     * *unbound* — bound once, unbound, and the remote file still there — would truncate a journal
     * holding real work. So an existing directory is adopted into the library instead and the id
     * is handed back: the reconciler binds it, and the *next* pass reaches [merge], which unions
     * the two logs the way SPEC §7.7 layer 1 requires. One extra pass, no bytes lost.
     *
     * @return the note's id, or null when the bundle would not install (a full disk, an
     *   unreadable file). Null is not an error the pass stops on — nothing was bound, so nothing
     *   claims a version it does not have.
     */
    private suspend fun install(install: RemoteInstall): DocumentId? = withLock(install.noteId) {
        val noteId = install.noteId
        val title = titleOf(install.remotePath)

        if (fs.exists(NoteLayout.noteDir(noteId))) {
            val existing = store.open(noteId) ?: return@withLock null
            library.noteInstalled(
                id = noteId,
                title = existing.document.title.ifEmpty { title },
                folderId = install.folderId,
                localRev = existing.document.localRev,
                pageCount = existing.document.livePages.size,
            )
            return@withLock noteId
        }

        // Bytes rather than a path because `installBundle` verifies the bundle before it writes
        // anything, and verifying means reading it. A bundle is the small half of the pair — the
        // 40 MB is the PDF, which stays on disk and never crosses this boundary.
        val bytes = runCatching { fs.readBytes(install.bundlePath) }.getOrNull() ?: return@withLock null
        val installed = store.installBundle(noteId, bytes) ?: return@withLock null
        library.noteInstalled(
            id = noteId,
            title = installed.document.title.ifEmpty { title },
            folderId = install.folderId,
            localRev = installed.document.localRev,
            pageCount = installed.document.livePages.size,
        )
        noteId
    }

    /**
     * SPEC §7.7 layer 3 — a copy the user can actually open, beside the local note that is left
     * completely alone.
     *
     * The verbatim remote bytes on the server are the reconciler's half (it binds the copy, and
     * the outbox PUTs it with `If-None-Match: *`). This half is the local note, with a **new
     * `DocumentId` and new `ObjectId`s** — `conflictedCopyOf` argues why all three reasons are
     * independently sufficient.
     *
     * ⭐ **[ConflictedCopy.title] verbatim — one clock reading for the whole conflict.** This used
     * to re-derive the base title from [ConflictedCopy.remotePath] and let `conflictedCopyOf` stamp
     * it, because stamping an already-stamped string gives
     * `Lecture (conflicted copy …) (conflicted copy …)`. The cost was that the stamp came from a
     * *second* reading of the clock, separated from `Reconciler.layerThree`'s by the length of a
     * download — so the local note's title and the remote file holding the same bytes disagreed by
     * however long that took. `conflictedCopyOf` now takes the finished name and stamps nothing, so
     * `RemoteName.conflictedCopy` has exactly one call site and the two names are equal by
     * construction rather than by coincidence.
     *
     * @return the copy's id, or null when there was nothing openable in the remote file. Null is
     *   a complete answer: a `TOO_NEW` or bundle-less file has no note in it this build can make,
     *   the peer's bytes are still on the server untouched, and the attention row carries the path.
     */
    private suspend fun conflictedCopy(copy: ConflictedCopy): DocumentId? {
        val bundlePath = copy.bundlePath ?: return null
        val document = store.conflictedCopyOf(
            remoteBundle = FileByteSource(fs, bundlePath),
            title = copy.title,
            deviceId = deviceIdentity.get(),
            ids = ids,
            clock = clock,
        ) ?: return null

        library.noteInstalled(
            id = document.id,
            title = document.title,
            folderId = copy.folderId,
            localRev = document.localRev,
            pageCount = document.livePages.size,
        )
        // No sync state is set on the copy. The reconciler binds it a moment later, which makes it
        // `PENDING_UPLOAD`, and marking it `CONFLICTED` first would be a flag overwritten before
        // anyone saw it. The *original* is the note flagged (the reconciler does that), and the
        // attention row carries this id — which is what lets the screen offer to open the copy.
        return document.id
    }

    // -----------------------------------------------------------------------
    // Plumbing
    // -----------------------------------------------------------------------

    private suspend fun <T> withLock(noteId: DocumentId, block: suspend () -> T): T =
        locks.computeIfAbsent(noteId.value) { Mutex() }.withLock { block() }

    private fun editorFor(noteId: DocumentId): NoteEditorHandle? = editors[noteId.value]

    /**
     * The answer for a note or a bundle that would not open.
     *
     * `DifferentDocument` rather than a new report variant: the reconciler's response to it is
     * exactly the one wanted here — layer 3, the ETag left alone, the next pass fetching again —
     * and inventing a fifth variant that routes to the same place would be a second name for one
     * decision.
     */
    private fun unreadable(noteId: DocumentId) = MergeCommit(MergeReport.DifferentDocument(noteId), 0L)

    private fun titleOf(remotePath: String): String = remotePath.substringAfterLast('/').removeSuffix(PDF_EXTENSION)

    private companion object {
        const val TAG = "NoteSessionHost"
        const val PDF_EXTENSION = ".pdf"
    }
}
