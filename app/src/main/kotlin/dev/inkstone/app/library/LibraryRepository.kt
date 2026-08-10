package dev.inkstone.app.library

import androidx.compose.ui.graphics.ImageBitmap
import dev.inkstone.android.pdf.PdfGateway
import dev.inkstone.android.pdf.PdfOpenResult
import dev.inkstone.core.database.library.LibraryIndex
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.Folder
import dev.inkstone.core.model.FolderCover
import dev.inkstone.core.model.FolderIcon
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.model.FolderTree
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.NoteSummary
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageTemplate
import dev.inkstone.core.model.RemoteName
import dev.inkstone.core.model.foldPage
import dev.inkstone.core.storage.DeviceIdentity
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.storage.NoteStore
import dev.inkstone.core.storage.NoteTrash
import dev.inkstone.core.storage.ThumbnailCache
import dev.inkstone.core.storage.duplicateNote
import dev.inkstone.core.sync.FolderBinding
import dev.inkstone.core.sync.RemoteBinding
import dev.inkstone.core.sync.SyncAccount
import dev.inkstone.core.sync.SyncEngine
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SPEC §9 — everything the library does, in one place.
 *
 * The composition point: the index knows *about* notes, the filesystem holds them, and the
 * trash moves directories. Every library action touches at least two of the three, and the
 * order matters in a way that is easy to get wrong once and never notice.
 *
 * ## The ordering rule
 *
 * **Files move before rows change, and rows are dropped only after files are gone.**
 *
 * A crash in the middle then leaves a state the app can repair: a directory in the trash
 * with a row that still says "live" shows a note that fails to open — visible, and fixed by
 * [reconcile]. The other order leaves a row saying "trashed" with the directory still in
 * `notes/`, which looks fine and quietly loses the note on the next purge. One of those is
 * a bug report; the other is CLAUDE.md rule 1.
 *
 * ## No hard delete
 *
 * ROADMAP M3: *"no hard delete anywhere in the UI"*. There is deliberately no method here
 * that destroys a note. [purgeExpired] is the only thing in the app that removes user data,
 * it is driven by a 30-day clock rather than by a user action, and it is not reachable from
 * any button.
 */
// The library's whole surface: notes, folders, covers, trash, retention and thumbnails. Each
// one is a few lines, and the alternative — splitting by noun — would put the trash cascade's
// file-then-row ordering across two classes. That ordering is the thing this file exists to
// get right (see the KDoc), so it stays in one place.
@Singleton
@Suppress("TooManyFunctions")
class LibraryRepository @Inject constructor(
    private val fs: FileSystem,
    private val clock: Clock,
    private val index: LibraryIndex,
    private val thumbnails: Thumbnails,
    private val thumbnailCache: ThumbnailCache,
    /** SPEC §5.6 (M4) — the only way a PDF gets into the library. */
    private val gateway: PdfGateway,
    /**
     * SPEC §7.6 (M5) — where "this note owes the server something" gets recorded.
     *
     * Every call into it is local: an outbox row and a nudge to WorkManager, never a
     * request (CLAUDE.md rule 4). That is what keeps every method here as fast offline as
     * on, and it is why binding a note does not block editing it.
     */
    private val sync: SyncEngine,
    /**
     * The three sync tables the library reads directly (M6). See [LibrarySyncTables] for why they
     * arrive as one parameter and why they are the concrete stores rather than `:core:sync`'s ports.
     */
    private val tables: LibrarySyncTables,
) {

    private val ids = IdFactory(clock)
    private val store = NoteStore(fs, clock, ids)
    private val trash = NoteTrash(fs, clock)

    val folders: Flow<List<Folder>> = index.folders()
    val notes: Flow<List<NoteSummary>> = index.notes()
    val trashedNotes: Flow<List<NoteSummary>> = index.trashedNotes()
    val trashedFolders: Flow<List<Folder>> = index.trashedFolders()

    /** Which folders are pinned to a remote path — the library's cloud badge watches this (M6). */
    val pinnedFolders: Flow<Set<FolderId>> = tables.folders.pinnedFolderIds()

    /** Notes with an open "Needs attention" entry — the library's flag on a tile (SPEC §7.7). */
    val notesNeedingAttention: Flow<Set<DocumentId>> = tables.attention.noteIdsNeedingAttention()

    /** How many entries are open, for the toolbar. Zero hides the button entirely. */
    val openAttentionCount: Flow<Long> = tables.attention.openCount()

    /**
     * A per-install device identity for the Lamport tiebreak (SPEC §4.1).
     *
     * ⭐ **Actually per install since M6.** M2 took it from whichever note happened to
     * create it; M3 replaced that with one id per *process*, documented as temporary and
     * "enough for a device with one library". It was not enough: a device whose id changes
     * on every restart breaks every tie differently across restarts, so two tablets can
     * disagree about the order of the same two ops — and the disagreement is permanent,
     * silent, and invisible to every JVM test.
     *
     * [DeviceIdentity] persists it beside `notes/`, so it survives a restart *and* the
     * database being deleted and rebuilt (SPEC §13.1's disaster-recovery story).
     */
    private val deviceIdentity = DeviceIdentity(fs, ids)

    private suspend fun deviceId(): String = deviceIdentity.get()

    // -----------------------------------------------------------------------
    // Startup
    // -----------------------------------------------------------------------

    /**
     * Expire old trash, repair the index against what is on disk, and bound the thumbnail
     * cache.
     *
     * Runs once per launch, off the main thread. In that order, and each step is cheap
     * enough to be unconditional: a purge is a directory listing, a reconcile is another,
     * and eviction is one query.
     */
    suspend fun startup() {
        purgeExpired()
        reconcile()
        evictThumbnails()
    }

    /**
     * Rebuild index rows from what is actually in `notes/`.
     *
     * The index is a cache of the filesystem (see `Library.sq`), so the filesystem wins
     * every disagreement:
     *
     * - A directory with no row is a note the index lost — an interrupted create, or a
     *   database restored from an older backup. Adopt it, at the library root.
     * - A **live** row with no directory is a row pointing at nothing. Dropped: it would
     *   otherwise show a note that cannot be opened and cannot be deleted.
     *
     * A *trashed* row with no directory is left alone. Its directory is under `trash/`,
     * which this does not walk — and a trashed row is exactly the one where guessing wrong
     * destroys the user's only copy.
     */
    suspend fun reconcile() {
        val onDisk = fs.list(NoteLayout.NOTES_DIR).toSet()
        val rows = index.allNotes(includeTrashed = true)

        val ghosts = rows.filter { !it.isTrashed && it.id.value !in onDisk }.map { it.id }
        if (ghosts.isNotEmpty()) index.forget(ghosts, emptyList())

        val known = rows.mapTo(HashSet()) { it.id.value }
        for (directory in onDisk) {
            if (directory in known) continue
            adopt(DocumentId(directory))
        }
    }

    /**
     * Index a note that [LibraryFixture] wrote directly through `NoteStore`.
     *
     * The fixture bypasses [createNote] because it needs a session to write strokes into,
     * and this is the row that would otherwise be missing. Not a general-purpose hook — the
     * only other thing that indexes an unknown directory is [reconcile], and it deliberately
     * knows nothing about folders.
     */
    suspend fun adoptGenerated(id: DocumentId, title: String, folderId: FolderId?) {
        val now = clock.nowEpochMillis()
        index.insertNote(
            NoteSummary(
                id = id,
                title = title,
                folderId = folderId,
                pageCount = 1,
                localRev = store.open(id)?.document?.localRev ?: 0L,
                createdEpochMillis = now,
                modifiedEpochMillis = now,
            ),
        )
    }

    /** Pull a note the index does not know about into the library, reading its manifest. */
    private suspend fun adopt(id: DocumentId) {
        val note = store.open(id) ?: return
        val now = clock.nowEpochMillis()
        index.insertNote(
            NoteSummary(
                id = id,
                title = note.document.title.ifEmpty { UNTITLED },
                pageCount = note.document.livePages.size,
                localRev = note.document.localRev,
                createdEpochMillis = note.document.createdEpochMillis.takeIf { it > 0 } ?: now,
                modifiedEpochMillis = note.document.modifiedEpochMillis.takeIf { it > 0 } ?: now,
            ),
        )
    }

    /**
     * CLAUDE.md rule 1 — 30-day retention, and the only code in the app that destroys user
     * data.
     *
     * Files first, then rows: a row dropped before its directory would leave a note on disk
     * that nothing knows about, which [reconcile] would then adopt back into the library —
     * a deleted note reappearing a month later.
     */
    suspend fun purgeExpired(): Int {
        val purgedDirectories = trash.purgeExpired()
        val cutoff = clock.nowEpochMillis() - NoteTrash.RETENTION_MILLIS
        val expired = index.expired(cutoff)
        index.forget(expired.notes.map { it.id }, expired.folders.map { it.id })
        for (note in expired.notes) thumbnails.invalidate(note.id)
        thumbnailCache.sweepOrphans(index.allNotes(includeTrashed = true).mapTo(HashSet()) { it.id })
        return purgedDirectories
    }

    private suspend fun evictThumbnails() {
        val stale = index.thumbnailEvictionCandidates(ThumbnailCache.EVICTION_KEEP_NOTES)
        if (stale.isNotEmpty()) thumbnailCache.evict(stale)
    }

    // -----------------------------------------------------------------------
    // Notes
    // -----------------------------------------------------------------------

    suspend fun createNote(
        title: String = UNTITLED,
        folderId: FolderId? = null,
        template: PageTemplate = PageTemplate.RULED,
    ): DocumentId {
        val note = store.create(
            title = title,
            deviceId = deviceId(),
            background = Background.Template(template),
        )
        val now = clock.nowEpochMillis()
        index.insertNote(
            NoteSummary(
                id = note.document.id,
                title = title,
                folderId = folderId,
                pageCount = 1,
                localRev = note.document.localRev,
                createdEpochMillis = now,
                modifiedEpochMillis = now,
            ),
        )
        // A new note in a pinned folder syncs without being asked (SPEC §9's mirror). Without
        // this, a pin would only ever apply to notes that already existed when it was set.
        autoBindIfPinned(note.document.id, folderId)
        return note.document.id
    }

    /**
     * SPEC §6.2 / §5.6 — bring a PDF in, whatever it turns out to be.
     *
     * Every PDF goes through [PdfGateway], which makes SPEC §5.6's three-way
     * decision: a stranger's file becomes a **new** note with a new `documentId`, one
     * of ours arrives with the identity it already had, and one of ours whose bundle
     * did not survive is **recovered** — replaced, and reported.
     *
     * The library index is updated to match whichever happened. A Normal or Recovered
     * open of a note the index already knows about updates the existing row rather
     * than inserting a second one, because the note is the same note; it is the
     * `documentId` that says so, not the file it arrived in.
     *
     * @return what the gateway decided, so the UI can warn if it has to.
     */
    suspend fun importPdf(bytes: ByteArray, title: String, folderId: FolderId? = null): PdfOpenResult {
        val result = gateway.open(bytes = bytes, title = title.trim().ifEmpty { UNTITLED }, deviceId = deviceId())
        if (result !is PdfOpenResult.Opened) return result

        val document = result.note.document
        val now = clock.nowEpochMillis()
        val existing = index.note(document.id)
        if (existing == null) {
            index.insertNote(
                NoteSummary(
                    id = document.id,
                    title = document.title.ifEmpty { title },
                    folderId = folderId,
                    pageCount = document.livePages.size,
                    localRev = document.localRev,
                    createdEpochMillis = now,
                    modifiedEpochMillis = now,
                ),
            )
        } else {
            // Recovery replaced the note's contents, so every cached picture of it is
            // of something that no longer exists. Bumping `local_rev` is what invalidates
            // them — the same SQL statement, so there is no window in between (SPEC §5.7).
            index.recordSave(document.id, document.localRev, document.livePages.size, now)
            thumbnails.invalidate(document.id)
        }
        return result
    }

    /**
     * ⭐ **The index and remote-path halves of a rename (SPEC §7.7 layer 2).**
     *
     * ⚠️ **Not the whole rename, and not an entry point.** The note's own `Document.title` and
     * `Document.titleClock` are stamped first, in the note's single `NoteSession`, by
     * `NoteSessionHost.renameNote` — which is what the library screen calls. This method *was* the
     * whole rename (an `index.renameNote` and a `notePathChanged`), and that made SPEC §7.7 layer 2 a
     * register with no writer: two devices renaming one note kept two titles indefinitely, with no
     * clock to order them, no conflict, and each device MOVEing the server file back to its own name
     * whenever anything made it re-derive the path.
     *
     * [localRev] is not decoration. `SyncEngine.noteChanged` compares the index's revision against
     * `binding.lastSyncedRev` (SPEC §7.6), so a rename recorded without moving it is a change no
     * upload is ever owed for — the title would sit in this device's bundle and reach no peer.
     */
    suspend fun noteRenamed(id: DocumentId, title: String, localRev: Long, pageCount: Int) {
        val legal = legalTitle(title)
        val now = clock.nowEpochMillis()
        index.renameNote(id, legal, now)
        index.recordSave(id, localRev, pageCount, now)
        // The remote file is named after the note, so a rename is a remote MOVE — which
        // SPEC §7.6 drains before that note's UPLOAD, so the bytes never land at the old
        // path and get shuffled afterwards. A no-op for an unbound note, which is most.
        sync.notePathChanged(id, legal)
        // And the bundle itself changed, because the title and its clock are inside it. Unlike a
        // pull, this *is* local novelty, so an upload is genuinely owed (SPEC §7.6's table).
        sync.noteChanged(id)
    }

    /**
     * A note title as the index will store it: trimmed, never empty.
     *
     * Public because the note's own title register is stamped before this class sees the rename
     * (`NoteSessionHost.renameNote`), and the two strings have to be identical — a document holding
     * `"   "` beside a row saying "Untitled" is a note whose peer learns a name the library never
     * shows, and neither side would look wrong on its own.
     */
    fun legalTitle(title: String): String = title.trim().ifEmpty { UNTITLED }

    /**
     * Move notes between library folders — and, since M6, move their remote files with them.
     *
     * `docs/M5-NEXTCLOUD.md` recorded the old behaviour as a deliberate gap: *"Moving a note
     * between library folders does **not** move the remote file."* That was defensible while the
     * remote folder was whatever the user picked once per note, and stops being defensible the
     * moment a library folder can be pinned to a remote path — because then the library
     * hierarchy genuinely does describe where things live on the server, and a note that moved
     * here but not there is the mirror silently out of step.
     *
     * A note whose resolved path does not change (an unpinned library, or a move within one
     * pinned subtree that maps to the same place) enqueues nothing: `notePathChanged` returns
     * early when the path it computes matches the binding.
     */
    suspend fun moveNotes(ids: Collection<DocumentId>, folderId: FolderId?) {
        val now = clock.nowEpochMillis()
        for (id in ids) index.moveNote(id, folderId, now)
        // Resolved after the index write, so the new folder is what the resolver walks.
        for (id in ids) {
            val binding = sync.bindingOf(id)
            val note = index.note(id)
            when {
                // Already bound: follow the note to where the destination folder says it belongs.
                binding != null && note != null ->
                    sync.notePathChanged(id, note.title, resolveRemoteFolder(binding.accountId, folderId))

                // Unbound, dragged into a pinned folder: that folder's rule now covers it.
                else -> autoBindIfPinned(id, folderId)
            }
        }
    }

    suspend fun setFavourite(id: DocumentId, favourite: Boolean) {
        index.setFavourite(id, favourite)
    }

    /** ROADMAP M3 — duplicate. New object ids throughout; see `duplicateNote`. */
    suspend fun duplicate(id: DocumentId): DocumentId? {
        val source = index.note(id) ?: return null
        val copy = store.duplicateNote(
            sourceId = id,
            title = "${source.title} $COPY_SUFFIX",
            deviceId = deviceId(),
            ids = ids,
            clock = clock,
        ) ?: return null
        val now = clock.nowEpochMillis()
        index.insertNote(
            NoteSummary(
                id = copy.id,
                title = copy.title,
                folderId = source.folderId,
                pageCount = copy.livePages.size,
                localRev = copy.localRev,
                createdEpochMillis = now,
                modifiedEpochMillis = now,
            ),
        )
        return copy.id
    }

    /** Move notes to the trash. Files first (see the class KDoc's ordering rule). */
    suspend fun trashNotes(ids: Collection<DocumentId>) {
        val now = clock.nowEpochMillis()
        for (id in ids) {
            val path = trash.trash(id)
            index.trashNote(id, path, now)
            thumbnails.invalidate(id)
            // SPEC §7.6 — local trash immediately, remote DELETE enqueued. The DELETE
            // annihilates any pending UPLOAD, so a note deleted with an upload still
            // queued is not re-created on the server moments later.
            sync.noteTrashed(id)
        }
    }

    /**
     * Restore a note from the trash.
     *
     * @return false when the directory could not be moved back — most often because a live
     *   note of the same id already exists, which `NoteTrash.restore` refuses rather than
     *   overwrite. The row is left trashed in that case, so nothing is lost and the entry
     *   stays visible.
     */
    suspend fun restoreNote(id: DocumentId): Boolean {
        if (!trash.restore(id)) return false
        index.restoreNote(id)
        // SPEC §7.6 — re-enqueue an UPLOAD, and drop the stored oc:id and ETag: a
        // Nextcloud trash-restore changes the fileid, so an If-Match built from the old
        // one would 412 forever.
        sync.noteRestored(id)
        return true
    }

    // -----------------------------------------------------------------------
    // Folders
    // -----------------------------------------------------------------------

    suspend fun createFolder(parentId: FolderId?, name: String): FolderId {
        val now = clock.nowEpochMillis()
        val id = ids.newFolderId()
        index.insertFolder(
            Folder(
                id = id,
                parentId = parentId,
                name = legalName(name),
                sortKey = keyAfter(FolderTree.childrenOf(parentId, index.allFolders())),
                createdEpochMillis = now,
                modifiedEpochMillis = now,
            ),
        )
        return id
    }

    suspend fun renameFolder(id: FolderId, name: String) {
        index.renameFolder(id, legalName(name), clock.nowEpochMillis())
    }

    suspend fun setFolderAppearance(id: FolderId, colorArgb: Int?, icon: FolderIcon, cover: FolderCover) {
        index.setFolderAppearance(id, colorArgb, icon, cover, clock.nowEpochMillis())
    }

    // -----------------------------------------------------------------------
    // Folder covers
    // -----------------------------------------------------------------------

    /**
     * The picture for a folder's tile, or null to fall back to its icon.
     *
     * Every branch reuses the thumbnail pipeline rather than adding a second one — which is
     * why a redrawn cover updates by itself: [FolderCover.Drawn] points at an ordinary note,
     * and an ordinary note's cover thumbnail is regenerated on materialize (SPEC §5.7).
     */
    suspend fun coverFor(folder: Folder): ImageBitmap? = when (val cover = folder.cover) {
        FolderCover.Icon -> null

        FolderCover.LatestNote -> {
            val subtree = FolderTree.subtreeOf(folder.id, index.allFolders()).map { it.id }
            index.latestNoteIn(subtree)?.let { thumbnailOf(it) }
        }

        // A cover note that has been trashed or lost leaves the folder on its icon rather
        // than blank — `index.note` returning null is a normal outcome, not an error.
        is FolderCover.Drawn -> index.note(cover.noteId)?.let { thumbnailOf(it) }

        is FolderCover.Image -> thumbnails.folderImage(cover.asset) {
            if (fs.exists(cover.asset)) fs.readBytes(cover.asset) else null
        }
    }

    /**
     * Create the note that *is* a folder's cover, and point the folder at it.
     *
     * The whole feature, and it is almost no code: a folder cover the user draws is an
     * ordinary single-page note opened in the ordinary editor. It gets autosave, undo, every
     * brush, and — the part that matters — its thumbnail is regenerated when it closes, so
     * the folder's tile updates with nothing else written.
     *
     * `isCover` keeps it out of the library's own lists, so the user is not left wondering
     * why there is a note called "Cover" next to their notebooks.
     *
     * @return the note to open in the editor.
     */
    suspend fun createDrawnCover(folderId: FolderId): DocumentId? {
        val folder = index.folder(folderId) ?: return null
        // Reuse the existing drawing if there is one, so "draw a cover" a second time edits
        // the picture instead of silently abandoning it and starting a blank one.
        (folder.cover as? FolderCover.Drawn)?.let { existing ->
            if (index.note(existing.noteId) != null) return existing.noteId
        }

        val note = store.create(
            title = "$COVER_TITLE_PREFIX${folder.name}",
            deviceId = deviceId(),
            background = Background.Blank,
        )
        val now = clock.nowEpochMillis()
        index.insertNote(
            NoteSummary(
                id = note.document.id,
                title = note.document.title,
                isCover = true,
                localRev = note.document.localRev,
                createdEpochMillis = now,
                modifiedEpochMillis = now,
            ),
        )
        index.setFolderAppearance(folderId, folder.colorArgb, folder.icon, FolderCover.Drawn(note.document.id), now)
        return note.document.id
    }

    /**
     * Store a picked image as a folder's cover.
     *
     * The bytes arrive already re-encoded and downscaled by the caller — `:app` owns the
     * decode because it needs `BitmapFactory`. This writes them atomically and points the
     * folder at the file.
     */
    suspend fun setImageCover(folderId: FolderId, encoded: ByteArray) {
        val folder = index.folder(folderId) ?: return
        val path = NoteLayout.folderCoverImage(folderId)
        fs.createDirectories(NoteLayout.folderDir(folderId))
        fs.writeBytesAtomically(path, encoded)
        // The path does not change between imports, so a previously decoded image would
        // otherwise stay in the memory cache and the folder would keep showing the old one.
        thumbnails.invalidateFolderImage(path)
        index.setFolderAppearance(
            folderId,
            folder.colorArgb,
            folder.icon,
            FolderCover.Image(path),
            clock.nowEpochMillis(),
        )
    }

    /**
     * Re-parent a folder.
     *
     * @return false when [parentId] is [id] or one of its descendants. Allowing it would
     *   detach the whole subtree from the root: the rows would still be there and nothing
     *   in the UI could ever reach them again.
     */
    suspend fun moveFolder(id: FolderId, parentId: FolderId?): Boolean {
        val all = index.allFolders()
        if (parentId != null && FolderTree.isDescendantOf(parentId, id, all.associateBy { it.id })) return false
        index.moveFolder(id, parentId, keyAfter(FolderTree.childrenOf(parentId, all)), clock.nowEpochMillis())
        return true
    }

    /**
     * Trash a folder, its subfolders, and every note inside any of them.
     *
     * The whole subtree in one index transaction, with every note's directory moved first.
     * Restoring is [restoreFolder] and puts all of it back — which is why the descendants
     * are marked with the folder they went down with rather than trashed individually.
     */
    suspend fun trashFolder(id: FolderId) {
        val subtree = FolderTree.subtreeOf(id, index.allFolders())
        val subtreeIds = subtree.mapTo(HashSet()) { it.id }
        val affected = index.allNotes().filter { it.folderId in subtreeIds }

        val paths = LinkedHashMap<DocumentId, String>(affected.size)
        for (note in affected) {
            paths[note.id] = trash.trash(note.id)
            thumbnails.invalidate(note.id)
        }
        index.trashFolder(
            id = id,
            descendantFolderIds = subtree.map { it.id }.filterNot { it == id },
            noteTrashPaths = paths,
            deletedEpochMillis = clock.nowEpochMillis(),
        )
    }

    /**
     * Restore a trashed folder and everything that went into the trash with it.
     *
     * A note whose directory will not move back is left trashed and reported, rather than
     * having its row restored to point at a directory that is not there.
     *
     * @return the notes that could not be restored.
     */
    suspend fun restoreFolder(id: FolderId): List<DocumentId> {
        val failed = ArrayList<DocumentId>()
        for (note in index.notesTrashedWith(id)) {
            // Directory first, row second (the class KDoc's ordering rule). A note whose
            // directory will not move back keeps its trashed row and stays in the trash,
            // where the user can still see it.
            if (trash.restore(note.id)) index.restoreNote(note.id) else failed += note.id
        }
        index.restoreFolderSubtree(id)
        return failed
    }

    suspend fun notesTrashedWith(id: FolderId): List<NoteSummary> = index.notesTrashedWith(id)

    // -----------------------------------------------------------------------
    // What the editor reports back
    // -----------------------------------------------------------------------

    /**
     * Autosave committed (SPEC §5.1). Bumps the row and invalidates the thumbnail.
     *
     * Not a thumbnail *render* — that would put a page tessellation on the two-second
     * autosave timer, which is the kind of thing CLAUDE.md rule 7 is about even when it
     * runs off the ink thread. The picture is regenerated when the note closes, and on
     * demand before that.
     */
    suspend fun noteSaved(id: DocumentId, localRev: Long, pageCount: Int) {
        index.recordSave(id, localRev, pageCount, clock.nowEpochMillis())
        thumbnails.invalidate(id)
        // Safe on every two-second autosave tick: the outbox UPSERTs on
        // `(note_id, UPLOAD)`, so 500 offline edits leave exactly one row, and the
        // drainer's 30-second floor turns "upload immediately" into "at most once per
        // 30 s" (SPEC §7.6).
        sync.noteChanged(id)
    }

    /**
     * The note's standing with the server, for SPEC §4.7's compaction gate.
     *
     * `LOCAL_ONLY` for a note the index has never heard of, which is the conservative
     * answer in the only direction that matters: it is a state compaction *permits*, and a
     * note absent from the index has no binding, so it genuinely has no ops the server is
     * waiting for.
     */
    suspend fun syncStateOf(id: DocumentId): NoteSyncState = index.note(id)?.syncState ?: NoteSyncState.LOCAL_ONLY

    /** The note closed and its bundle was rewritten (SPEC §5.1). Redraw the library's picture. */
    suspend fun noteMaterialized(id: DocumentId, localRev: Long, pageCount: Int, cover: FoldedPage) {
        index.recordSave(id, localRev, pageCount, clock.nowEpochMillis())
        thumbnails.invalidate(id)
        if (thumbnails.refreshCover(id, cover)) index.recordThumbnail(id, localRev)
        sync.noteChanged(id)
    }

    /**
     * `document.pdf` was rewritten by the background materializer (M5's `MaterializeWorker`)
     * rather than by the editor closing.
     *
     * Only the thumbnail is invalidated, not re-rendered: this runs while the user is
     * somewhere else, and the library redraws it on demand.
     */
    suspend fun noteRepublished(id: DocumentId, localRev: Long) {
        val note = index.note(id) ?: return
        index.recordSave(id, localRev, note.pageCount, clock.nowEpochMillis())
        thumbnails.invalidate(id)
    }

    // -----------------------------------------------------------------------
    // What the reconciler reports back (SPEC §7.6's pull side, M6)
    // -----------------------------------------------------------------------

    /**
     * ⭐ A peer's edits were merged into a note this device already had (SPEC §7.6 step 3).
     *
     * ⚠️ **Deliberately not [noteSaved].** The obvious wiring — reporting a merge through the
     * ordinary save path — ends in `sync.noteChanged(id)`, which enqueues an UPLOAD
     * unconditionally. That changes the remote ETag, the peer sees a remote change on its next
     * poll, merges nothing novel, and uploads back. Forever, at the poll interval, looking
     * exactly like healthy sync. Whether an upload is owed is `Reconciler.apply`'s decision
     * table and nothing else's.
     *
     * ⚠️ **[Thumbnails.invalidate], never `ThumbnailCache.invalidate`.** The decoded-bitmap
     * memory tier is swept only by the wrapper (see its KDoc), so calling the `:core` class
     * alone leaves the pre-merge picture on screen for the life of the process — which for a
     * merge is the one moment the library's picture is provably wrong.
     *
     * The invalidation is only honest because `localRev` moved: `recordSave` clears
     * `thumbnailRev` in the same SQL statement, so there is no window in which a stale image
     * looks fresh (SPEC §5.7).
     *
     * @param title ⭐ **SPEC §7.7 layer 2's reader, added after M6's review.** `recordSave` has no
     *   title column, so a peer's rename used to land in the bundle and stop there: the tile kept
     *   the old string for good, and the LWW title was a register with nothing reading it. This is
     *   the **resolved** title — the local one when the local one won — so it can be written back
     *   without asking who won. Empty is left alone; a bundle with no title is not an instruction to
     *   blank the row.
     *
     *   ⚠️ **No `sync.notePathChanged` here.** The remote file is already at the peer's name (that is
     *   how the rename arrived), the reconciler owns the binding's path, and asking the server to
     *   repeat a move it has already made is a pull turning itself into a push.
     */
    suspend fun notePulled(id: DocumentId, localRev: Long, pageCount: Int, title: String) {
        val now = clock.nowEpochMillis()
        if (title.isNotEmpty() && index.note(id)?.title != title) index.renameNote(id, title, now)
        index.recordSave(id, localRev, pageCount, now)
        thumbnails.invalidate(id)
    }

    /**
     * ⭐ A note the reconciler created: a peer's note this device had never held, or SPEC §7.7
     * layer 3's conflicted copy.
     *
     * Inserted if the index has never heard of it and refreshed if it has — an unbound note
     * whose remote file reappeared is the same note, and a second row for it would be a
     * duplicate the user has to reconcile by hand.
     */
    suspend fun noteInstalled(id: DocumentId, title: String, folderId: FolderId?, localRev: Long, pageCount: Int) {
        val now = clock.nowEpochMillis()
        val existing = index.note(id)
        if (existing == null) {
            index.insertNote(
                NoteSummary(
                    id = id,
                    title = title.ifEmpty { UNTITLED },
                    folderId = folderId,
                    pageCount = pageCount,
                    localRev = localRev,
                    createdEpochMillis = now,
                    modifiedEpochMillis = now,
                ),
            )
        } else {
            index.recordSave(id, localRev, pageCount, now)
        }
        thumbnails.invalidate(id)
    }

    /**
     * ⚠️ **SPEC §7.6 step 3, fourth branch — the remote file is gone. Local trash, never a
     * delete** (CLAUDE.md rule 1).
     *
     * Files first, then rows, for the reason the class KDoc gives. Thirty days of retention and
     * one tap to restore, because "somebody deleted it on the server" and "the user wants it
     * gone" are different statements and only one of them was made here.
     *
     * ⚠️ **Not [trashNotes].** That calls `sync.noteTrashed`, which enqueues a remote DELETE and
     * *annihilates the pending UPLOAD* — and row 14's whole premise is a note with local edits
     * still queued, so that would destroy the only route those edits had to the server, through
     * a code path whose own comments read correct. The pull-side equivalent is
     * `SyncEngine.noteDeletedRemotely`, and the **reconciler** calls it, along with writing the
     * attention row: this method is only the local half.
     *
     * ⚠️ The reconciler's `reason` string is deliberately **not** carried into the index's
     * `lastError`. `SyncEngine.noteDeletedRemotely` runs immediately after this and sets
     * `(LOCAL_ONLY, lastError = null)`, so anything stored here would be cleared microseconds later
     * by code that reads as correct at both ends. The durable place for the reason is the
     * `syncAttention` row — which is what SPEC §7.7's table exists for, and which the reconciler
     * writes in the same breath.
     */
    suspend fun trashRemotelyDeleted(id: DocumentId) {
        if (index.note(id) == null) return
        val path = trash.trash(id)
        index.trashNote(id, path, clock.nowEpochMillis())
        thumbnails.invalidate(id)
    }

    /**
     * The note's remote file moved into a directory mirroring a different library folder
     * (ROADMAP M6's folder mirroring, pull half).
     *
     * The index only; no remote work. The file is already where the binding now says it is —
     * that is what made this call happen — so enqueuing a MOVE would be this device asking the
     * server to repeat something it has already done.
     */
    suspend fun noteFolderChanged(id: DocumentId, folderId: FolderId) {
        val note = index.note(id) ?: return
        if (note.folderId == folderId) return
        index.moveNote(id, folderId, clock.nowEpochMillis())
    }

    /**
     * SPEC §4.7's causal-stability horizon for one note: the lowest counter every known peer has
     * acknowledged, or null when no peer is known.
     *
     * ⭐ **Wired for the first time in M6.** `Compaction.isAllowed`'s `oldestAckedCounter`
     * parameter has existed since M2 and has always been passed null, so thirty days of wall
     * clock was the only guard against compacting away ops a peer still needs to reintegrate.
     * A downloaded bundle is the first thing that has ever known what a peer has seen.
     *
     * Null for a one-device setup, which is the value the parameter documents as *"nothing is
     * known"* and the correct conservative answer: it leaves the horizon gate to the clock.
     */
    suspend fun oldestAckedCounter(id: DocumentId): Long? = tables.peers.oldestAckedCounter(id)

    // -----------------------------------------------------------------------
    // Nextcloud (SPEC §7.6)
    // -----------------------------------------------------------------------

    /**
     * SPEC §7.6 — *"Save to Nextcloud…"*.
     *
     * Three local writes and a nudge to WorkManager. **Never blocks, never needs a
     * network**, and the note stays fully editable throughout — which is SPEC §7.6
     * point 4 and the reason binding is not a modal progress dialog.
     */
    suspend fun bindToNextcloud(id: DocumentId, accountId: String, remoteFolder: String?): RemoteBinding? {
        val note = index.note(id) ?: return null
        // A folder the user picked in the browser wins outright. Null means "work it out", which
        // is where a pinned ancestor folder takes over (SPEC §9's mirror).
        val folder = remoteFolder ?: resolveRemoteFolder(accountId, note.folderId)
        return sync.bind(noteId = id, accountId = accountId, title = note.title, remoteFolder = folder)
    }

    /**
     * ⭐ **Pin a library folder to a path on the server (M6).**
     *
     * Everything in it, and everything in every folder beneath it, syncs under that path from
     * then on — one choice instead of one per note. SPEC §9's *"folders mirror Nextcloud both
     * directions"*, ROADMAP M6's folder mirroring.
     *
     * Notes **already** bound keep the path they were bound with. Re-pointing them is a remote
     * MOVE per note, and doing that silently from a settings change is exactly the kind of
     * unrequested bulk operation on someone's files that CLAUDE.md rule 1 exists to rule out —
     * so [repointFolder] is a separate, explicit action.
     */
    suspend fun pinFolderToRemote(folderId: FolderId, accountId: String, remotePath: String) {
        tables.folders.upsert(
            FolderBinding(folderId = folderId, accountId = accountId, remotePath = remotePath.trim('/')),
        )
    }

    suspend fun unpinFolder(folderId: FolderId) {
        tables.folders.remove(folderId)
    }

    suspend fun folderPin(folderId: FolderId): FolderBinding? = tables.folders.find(folderId)

    /**
     * What pinning a folder actually did, so the UI can say so.
     *
     * Reported because the first version of this feature was silent *and* inert, and a silent
     * success is indistinguishable from the bug.
     */
    data class FolderSyncResult(val newlyBound: Int, val movedOnServer: Int, val folders: Int) {
        val total: Int get() = newlyBound + movedOnServer
    }

    /**
     * ⭐ **Pin a folder and make it true: bind and upload everything already inside it.**
     *
     * This is the whole feature, and the first version of it did not work. Pinning wrote one
     * row and stopped — because a pin is only *consulted* when a note is bound, and nothing
     * bound anything. So the user pinned a folder holding a note, watched their Nextcloud, and
     * saw no folder appear and no note upload. Correct by every unit test and inert in the app.
     *
     * The lesson worth keeping: "always sync this folder" is a statement about the notes, not
     * about a mapping. Recording the mapping is the easy half and does nothing on its own.
     *
     * Both halves are what the user asked for, so both happen here rather than behind a second
     * confirmation:
     *
     * - An **unbound** note is bound and uploaded. `sync.bind` enqueues `MKCOL` then `UPLOAD`,
     *   which is what creates the remote folder — nothing else in the app would have.
     * - An **already-bound** note is re-pointed with a `MOVE`, which SPEC §7.6 drains before
     *   that note's `UPLOAD` so the bytes never land at the old path and get shuffled after.
     *
     * Moving the already-bound ones is not an unrequested bulk operation (CLAUDE.md rule 1):
     * the user chose *"always sync this folder to here"*, and a note in that folder sitting
     * somewhere else afterwards is the instruction not being carried out. A `MOVE` also
     * destroys nothing — `Overwrite: F`, and the bytes are never rewritten.
     *
     * Nothing here touches the network (CLAUDE.md rule 4). It is `n` outbox rows and returns
     * immediately, online or off.
     */
    suspend fun syncFolderToRemote(folderId: FolderId, accountId: String, remotePath: String): FolderSyncResult {
        pinFolderToRemote(folderId, accountId, remotePath)

        val folders = index.allFolders()
        val notes = index.allNotes(includeTrashed = false)
        // The decision is `FolderTree.planFolderSync` — pure, and unit-tested, because the
        // omission that made the first version of this inert lives exactly here and cannot be
        // reached from a JVM test through this class.
        val plan = FolderTree.planFolderSync(
            root = folderId,
            folders = folders,
            notes = notes,
            boundNotes = notes.filterTo(HashSet()) { sync.bindingOf(it.id) != null }.mapTo(HashSet()) { it.id },
            pins = tables.folders.pathsForAccount(accountId),
            accountRoot = accountOrDefaultRoot(accountId),
        )

        for (step in plan) {
            if (step.alreadyBound) {
                sync.notePathChanged(step.noteId, step.title, step.remoteFolder)
            } else {
                sync.bind(
                    noteId = step.noteId,
                    accountId = accountId,
                    title = step.title,
                    remoteFolder = step.remoteFolder,
                )
            }
        }
        return FolderSyncResult(
            newlyBound = plan.count { !it.alreadyBound },
            movedOnServer = plan.count { it.alreadyBound },
            folders = FolderTree.subtreeOf(folderId, folders).size,
        )
    }

    private suspend fun accountOrDefaultRoot(accountId: String): String =
        sync.accountOf(accountId)?.defaultFolder ?: SyncAccount.DEFAULT_FOLDER

    /**
     * The pin governing [folderId], from the **deepest** pinned ancestor — or null when nothing
     * above it is pinned.
     *
     * Deepest-first for the reason `FolderTree.remoteFolderFor` resolves that way: pinning a
     * subfolder has to be able to override the pin above it, or the second pin is dead.
     *
     * A folder has at most one pin (it is the primary key), and the pin carries its own account
     * — so this answers "which account, and is there one at all" in at most one lookup per level
     * of depth, with no query that has to know the account first.
     */
    private suspend fun pinFor(folderId: FolderId?): FolderBinding? {
        if (folderId == null) return null
        val folders = index.allFolders().associateBy { it.id }
        return FolderTree.pathOf(folderId, folders)
            .asReversed()
            .firstNotNullOfOrNull { tables.folders.find(it.id) }
    }

    /**
     * Bind a note because the folder it is in (or an ancestor) is pinned.
     *
     * Called wherever a note *arrives* in a folder — created, imported, or moved. Without this
     * a pin only ever applies to notes that existed when it was set, which is the same "nothing
     * happens" bug one step later: pin a folder, make a note in it, and it stays local.
     *
     * A no-op for an unpinned folder and for a note that is already bound, so every caller can
     * call it unconditionally.
     */
    private suspend fun autoBindIfPinned(noteId: DocumentId, folderId: FolderId?) {
        val pin = pinFor(folderId) ?: return
        if (sync.bindingOf(noteId) != null) return
        // Unlike "Save to Nextcloud…", which the UI only offers for a usable account, this fires
        // on its own — so it has to check. Binding against a signed-out or halted account would
        // enqueue a row nothing can drain, which is the stall M5 found on a device.
        if (sync.accountOf(pin.accountId)?.isUsable != true) return
        val note = index.note(noteId) ?: return
        sync.bind(
            noteId = noteId,
            accountId = pin.accountId,
            title = note.title,
            remoteFolder = resolveRemoteFolder(pin.accountId, folderId),
        )
    }

    /**
     * Where a note in [folderId] belongs on [accountId], honouring pinned ancestors.
     *
     * Falls back to the account's default folder when nothing above it is pinned, which is
     * exactly the pre-M6 behaviour — so an unpinned library keeps syncing where it did.
     */
    private suspend fun resolveRemoteFolder(accountId: String, folderId: FolderId?): String {
        val account = sync.accountOf(accountId) ?: return SyncAccount.DEFAULT_FOLDER
        return FolderTree.remoteFolderFor(
            folderId = folderId,
            folders = index.allFolders().associateBy { it.id },
            boundFolders = tables.folders.pathsForAccount(accountId),
            accountRoot = account.defaultFolder,
        )
    }

    /**
     * ⭐ Move a synced note's remote copy to a different folder, **without unbinding it**.
     *
     * The only way to relocate a synced note used to be "Stop syncing" and then "Save to
     * Nextcloud…" somewhere else — which leaves the original file on the server forever, because
     * unbinding deliberately does not delete it (CLAUDE.md rule 1), and binding again creates a
     * second one. Do that a few times and the remote folder fills with copies the app no longer
     * knows about.
     *
     * This is a `MOVE` instead: one request, `Overwrite: F`, and SPEC §7.6 drains it before that
     * note's `UPLOAD` so the bytes never land at the old path and get shuffled afterwards. The
     * binding, the note's identity, its history and its `oc:id` are all untouched — a moved file
     * keeps its ETag (SPEC §7.4 rule 2), so nothing re-downloads either.
     *
     * @return the path the note is moving to, or null when it is not bound.
     */
    suspend fun moveRemoteTo(id: DocumentId, remoteFolder: String): String? {
        val binding = sync.bindingOf(id) ?: return null
        val note = index.note(id) ?: return null
        sync.notePathChanged(id, note.title, remoteFolder)
        return sync.bindingOf(id)?.remotePath
    }

    /** Stop syncing a note. **Not** a remote delete — the copy on the server stays where it is. */
    suspend fun unbindFromNextcloud(id: DocumentId) {
        sync.unbind(id)
    }

    suspend fun bindingOf(id: DocumentId): RemoteBinding? = sync.bindingOf(id)

    // -----------------------------------------------------------------------
    // Thumbnails
    // -----------------------------------------------------------------------

    /**
     * The library's picture of [note] — cached if there is a fresh one, rendered if not.
     *
     * The fold is passed as a lambda so [Thumbnails] can decide whether it is needed at all.
     * Opening a note to render a thumbnail that is already on disk would be the exact cost
     * SPEC §5.7 exists to avoid, 500 times over.
     */
    suspend fun thumbnailOf(note: NoteSummary) = thumbnails.cover(note) { foldCoverPage(it) }

    /**
     * Fold a note's first page, for a thumbnail.
     *
     * Opens the note and reads exactly one page — not the whole notebook — which is what
     * `NoteStore.loadPage` exists for. Called only when there is no fresh cached image.
     */
    suspend fun foldCoverPage(id: DocumentId): FoldedPage? {
        val note = store.open(id) ?: return null
        val first = note.document.livePages.firstOrNull() ?: return null
        val loaded = store.loadPage(note, first.id)
        return foldPage(first.id, loaded.ops, defaultSize = loaded.size, defaultBackground = loaded.background)
    }

    // There is deliberately no `foldPageOf(id, pageId)` here. A *page* thumbnail is only ever
    // wanted for the note that is open, and the open session holds ops the bundle does not —
    // so folding one from disk would show the page as it was at the last materialization.
    // `NoteEditorViewModel.pageThumbnail` folds from the session instead.

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun keyAfter(siblings: List<Folder>): FractionalKey =
        FractionalKey.betweenOrNull(siblings.lastOrNull()?.sortKey, null) ?: FractionalKey.Initial

    /**
     * SPEC §7.8 — a folder name has to be legal on a WebDAV server, and M5 turns these into
     * remote paths. Sanitising at creation rather than at upload means the user sees the
     * name they will actually get, once, instead of finding it changed a milestone later.
     */
    private fun legalName(name: String): String = RemoteName.sanitise(name.trim())

    private companion object {
        const val UNTITLED = "Untitled"

        /** Titles a folder cover note, so it is recognisable if it ever surfaces. */
        const val COVER_TITLE_PREFIX = "Cover — "
        const val COPY_SUFFIX = "copy"
    }
}
