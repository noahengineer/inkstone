package dev.inkstone.core.database.library

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.Folder
import dev.inkstone.core.model.FolderCover
import dev.inkstone.core.model.FolderIcon
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.NoteSummary
import dev.inkstone.core.model.NoteSyncState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * SPEC §9 — the library index: folders, favourites, trash, and what the grid draws.
 *
 * Everything here is *derived or organisational*. A note's content is its bundle and
 * journal (SPEC §5.1); these rows exist so the library can be drawn without opening a
 * single one of them, which is the same argument SPEC §5.7 makes for thumbnails and for
 * the same reason — 500 notes.
 *
 * ## Why this wrapper exists rather than passing the generated queries around
 *
 * Two reasons, and neither is tidiness. The generated API is blocking and speaks in row
 * types; this one is `suspend`/[Flow] and speaks in `:core:model` types, so a caller
 * cannot accidentally run a query on the UI thread and cannot see a `String` where a
 * [FolderId] belongs. And the multi-statement operations — trashing a folder is up to
 * three writes across two tables — are transactions here, so a process kill between them
 * cannot leave a folder trashed with its notes still showing in the library.
 *
 * @param dispatcher where SQL runs. A parameter because `Dispatchers.IO` does not exist in
 *   common code; `:app` passes it, a test passes its own.
 */
@Suppress("TooManyFunctions")
// One function per query, each a single statement. Splitting it — folders here, notes there,
// retention somewhere else — would put the trash cascade's transaction across two classes,
// which is the one thing in this file that must stay indivisible.
public class LibraryIndex(private val database: InkstoneDatabase, private val dispatcher: CoroutineDispatcher) {

    private val queries = database.libraryQueries

    // -----------------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------------

    /**
     * Every folder, trashed ones included.
     *
     * The whole table, deliberately: [dev.inkstone.core.model.FolderTree] needs the full
     * set to resolve a path or a subtree, and a library with fifty folders is a few
     * kilobytes. Filtering in SQL would mean a query per navigation step and a tree that
     * could not answer "where does this trashed folder restore to".
     */
    public fun folders(): Flow<List<Folder>> =
        queries.selectAllFolders().asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toFolder() } }

    /** Every live note. Trashed notes are [trashedNotes]. */
    public fun notes(): Flow<List<NoteSummary>> =
        queries.selectAllNotes().asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toSummary() } }

    /** Top-level trash entries only — a note trashed *with* its folder appears under the folder. */
    public fun trashedNotes(): Flow<List<NoteSummary>> =
        queries.selectTrashedNotes().asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toSummary() } }

    public fun trashedFolders(): Flow<List<Folder>> =
        queries.selectTrashedFolders().asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toFolder() } }

    /** A snapshot rather than a stream, for the operations that need the whole tree at once. */
    public suspend fun allFolders(): List<Folder> = withContext(dispatcher) {
        queries.selectAllFolders().executeAsList().map { it.toFolder() }
    }

    /**
     * Every note row, trashed ones included.
     *
     * The `includeTrashed` case is not a convenience: `reconcile` compares the index
     * against `notes/`, and a trashed note — whose directory lives under `trash/` — would
     * look like a row pointing at nothing and be dropped.
     */
    public suspend fun allNotes(includeTrashed: Boolean = false): List<NoteSummary> = withContext(dispatcher) {
        if (includeTrashed) {
            queries.selectEveryNote().executeAsList().map { it.toSummary() }
        } else {
            queries.selectAllNotes().executeAsList().map { it.toSummary() }
        }
    }

    public suspend fun folder(id: FolderId): Folder? = withContext(dispatcher) {
        queries.selectFolder(id.value).executeAsOneOrNull()?.toFolder()
    }

    public suspend fun note(id: DocumentId): NoteSummary? = withContext(dispatcher) {
        queries.selectNote(id.value).executeAsOneOrNull()?.toSummary()
    }

    /** Notes carried into the trash by [trashFolder], for showing "12 notes" beside the entry. */
    public suspend fun notesTrashedWith(id: FolderId): List<NoteSummary> = withContext(dispatcher) {
        queries.selectNotesTrashedWithFolder(id.value).executeAsList().map { it.toSummary() }
    }

    // -----------------------------------------------------------------------
    // Folders
    // -----------------------------------------------------------------------

    public suspend fun insertFolder(folder: Folder): Unit = withContext(dispatcher) {
        queries.insertFolder(
            id = folder.id.value,
            parentId = folder.parentId?.value,
            name = folder.name,
            nameFold = fold(folder.name),
            sortKey = folder.sortKey.value,
            colorArgb = folder.colorArgb?.toLong(),
            icon = folder.icon.name,
            coverKind = folder.cover.kind,
            coverRef = folder.cover.ref,
            createdAt = folder.createdEpochMillis,
            modifiedAt = folder.modifiedEpochMillis,
        )
    }

    public suspend fun renameFolder(id: FolderId, name: String, nowEpochMillis: Long): Unit =
        withContext(dispatcher) { queries.updateFolderName(name, fold(name), nowEpochMillis, id.value) }

    public suspend fun setFolderAppearance(
        id: FolderId,
        colorArgb: Int?,
        icon: FolderIcon,
        cover: FolderCover,
        nowEpochMillis: Long,
    ): Unit = withContext(dispatcher) {
        queries.updateFolderAppearance(
            colorArgb = colorArgb?.toLong(),
            icon = icon.name,
            coverKind = cover.kind,
            coverRef = cover.ref,
            modifiedAt = nowEpochMillis,
            id = id.value,
        )
    }

    /**
     * The most recently edited note in any of [folderIds] — `FolderCover.LatestNote`.
     *
     * Takes the whole subtree rather than one folder, so a folder of folders still shows
     * something rather than looking empty.
     */
    public suspend fun latestNoteIn(folderIds: Collection<FolderId>): NoteSummary? = withContext(dispatcher) {
        if (folderIds.isEmpty()) {
            null
        } else {
            queries.selectLatestNoteIn(folderIds.map { it.value }).executeAsOneOrNull()?.toSummary()
        }
    }

    /**
     * Re-parent a folder.
     *
     * The caller checks [dev.inkstone.core.model.FolderTree.isDescendantOf] first — this
     * layer would happily write a cycle, and a cycle detaches a subtree from the root with
     * no way back through the UI.
     */
    public suspend fun moveFolder(
        id: FolderId,
        parentId: FolderId?,
        sortKey: FractionalKey,
        nowEpochMillis: Long,
    ): Unit = withContext(dispatcher) {
        queries.updateFolderParent(parentId?.value, sortKey.value, nowEpochMillis, id.value)
    }

    /**
     * Trash a folder and everything beneath it, in one transaction.
     *
     * CLAUDE.md rule 1 — a tombstone, never a removal. The folder rows stay, the note rows
     * stay, and each note's *directory* has already been moved into the trash by the
     * caller (which is why [noteTrashPaths] is passed in: this layer does no file I/O).
     *
     * One transaction because the alternative is a kill between the folder write and the
     * note writes, which leaves a folder in the trash whose notes are still in the library
     * — pointing at directories that have moved.
     *
     * @param descendantFolderIds every folder beneath [id]. They get `deletedWithFolderId`
     *   so restore is one action and the trash list shows one entry.
     * @param noteTrashPaths every note in the subtree, mapped to where its directory now is.
     */
    public suspend fun trashFolder(
        id: FolderId,
        descendantFolderIds: List<FolderId>,
        noteTrashPaths: Map<DocumentId, String>,
        deletedEpochMillis: Long,
    ): Unit = withContext(dispatcher) {
        database.transaction {
            queries.trashFolder(deletedEpochMillis, id.value)
            if (descendantFolderIds.isNotEmpty()) {
                queries.trashFoldersWithAncestor(deletedEpochMillis, id.value, descendantFolderIds.map { it.value })
            }
            for ((noteId, path) in noteTrashPaths) {
                queries.trashNote(deletedEpochMillis, id.value, path, noteId.value)
            }
        }
    }

    /**
     * Undo [trashFolder] for the **folder rows only** — the folder and its descendants.
     *
     * Its notes are restored one at a time through [restoreNote], because each one's
     * directory has to move back first and that can fail. Clearing every carried note's
     * flags here would restore rows pointing at directories still in the trash.
     */
    public suspend fun restoreFolderSubtree(id: FolderId): Unit =
        withContext(dispatcher) { queries.restoreFolderSubtree(id.value) }

    // -----------------------------------------------------------------------
    // Notes
    // -----------------------------------------------------------------------

    public suspend fun insertNote(note: NoteSummary): Unit = withContext(dispatcher) {
        queries.insertNote(
            id = note.id.value,
            title = note.title,
            titleFold = fold(note.title),
            folderId = note.folderId?.value,
            pageCount = note.pageCount.toLong(),
            localRev = note.localRev,
            isCover = if (note.isCover) 1L else 0L,
            createdAt = note.createdEpochMillis,
            modifiedAt = note.modifiedEpochMillis,
        )
    }

    public suspend fun renameNote(id: DocumentId, title: String, nowEpochMillis: Long): Unit =
        withContext(dispatcher) { queries.updateNoteTitle(title, fold(title), nowEpochMillis, id.value) }

    public suspend fun moveNote(id: DocumentId, folderId: FolderId?, nowEpochMillis: Long): Unit =
        withContext(dispatcher) { queries.updateNoteFolder(folderId?.value, nowEpochMillis, id.value) }

    public suspend fun setFavourite(id: DocumentId, favourite: Boolean): Unit =
        withContext(dispatcher) { queries.updateNoteFavourite(if (favourite) 1L else 0L, id.value) }

    /**
     * Record a save — and, in the same statement, invalidate the thumbnail (SPEC §5.7).
     *
     * The two are one write on purpose. A revision bump *is* the invalidation, so there is
     * no window in which the index reports a fresh thumbnail for content that has changed.
     */
    public suspend fun recordSave(id: DocumentId, localRev: Long, pageCount: Int, nowEpochMillis: Long): Unit =
        withContext(dispatcher) {
            queries.updateNoteRevision(localRev, pageCount.toLong(), nowEpochMillis, id.value)
        }

    /** A thumbnail was rendered from [localRev] and written to the cache. */
    public suspend fun recordThumbnail(id: DocumentId, localRev: Long): Unit =
        withContext(dispatcher) { queries.updateNoteThumbnailRev(localRev, id.value) }

    public suspend fun setSyncState(id: DocumentId, state: NoteSyncState, lastError: String? = null): Unit =
        withContext(dispatcher) { queries.updateNoteSyncState(state.name, lastError, id.value) }

    /** @param trashPath where [dev.inkstone.core.model.FileSystem] moved the note's directory. */
    public suspend fun trashNote(id: DocumentId, trashPath: String, deletedEpochMillis: Long): Unit =
        withContext(dispatcher) { queries.trashNote(deletedEpochMillis, null, trashPath, id.value) }

    public suspend fun restoreNote(id: DocumentId): Unit = withContext(dispatcher) { queries.restoreNote(id.value) }

    // -----------------------------------------------------------------------
    // Retention and eviction
    // -----------------------------------------------------------------------

    /**
     * Rows whose 30 days are up (CLAUDE.md rule 1).
     *
     * **Returns them; does not delete them.** The files have to go first — a row deleted
     * before its directory would leave a note on disk that nothing knows about, which is
     * both a leak and a note that would reappear as "found on disk" the next time the
     * library reconciled. Call [forget] once the files are gone.
     */
    public suspend fun expired(cutoffEpochMillis: Long): ExpiredEntries = withContext(dispatcher) {
        ExpiredEntries(
            notes = queries.selectExpiredNotes(cutoffEpochMillis).executeAsList().map { it.toSummary() },
            folders = queries.selectExpiredFolders(cutoffEpochMillis).executeAsList().map { it.toFolder() },
        )
    }

    /** Drop index rows whose files have already been purged. */
    public suspend fun forget(notes: List<DocumentId>, folders: List<FolderId>): Unit = withContext(dispatcher) {
        database.transaction {
            for (id in notes) queries.deleteNoteRow(id.value)
            for (id in folders) queries.deleteFolderRow(id.value)
        }
    }

    /**
     * Notes whose thumbnails may be evicted — everything outside the [keep] most recently
     * modified.
     *
     * SPEC §5.7 asks for LRU eviction. "Least recently modified" is that ordering without a
     * second table to keep in step with the files it describes: a thumbnail is regenerated
     * on demand, so evicting one the user is about to look at costs a render, not data.
     */
    public suspend fun thumbnailEvictionCandidates(keep: Int): List<DocumentId> = withContext(dispatcher) {
        queries.selectThumbnailEvictionCandidates(keep.toLong()).executeAsList().map { DocumentId(it) }
    }

    private companion object {
        /**
         * Unicode-aware case folding, done here rather than in SQL.
         *
         * SQLite's `lower()` and `COLLATE NOCASE` fold ASCII only, so a search for "ÜBUNG"
         * would not find "Übung" — which is not an edge case in a note app used in any
         * language with accents.
         */
        fun fold(value: String): String = value.lowercase()
    }
}

/** What [LibraryIndex.expired] found. Files first, then [LibraryIndex.forget]. */
public data class ExpiredEntries(val notes: List<NoteSummary>, val folders: List<Folder>)

// Fully qualified receivers: SQLDelight names a table's row type after the table, so the
// generated `dev.inkstone.core.database.Folder` and the model's `Folder` collide. This file
// lives in a sub-package so the *model* type is the unqualified one — it is the type the
// rest of the app speaks — and the row types are named only here, once each.

/**
 * The stored `(kind, ref)` pair as a [FolderCover].
 *
 * An unrecognised kind — or a `DRAWN`/`IMAGE` row whose reference went missing — falls back
 * to the icon rather than failing. A folder that will not render is worse than a folder that
 * renders plainly, and the icon and colour are always set.
 */
private fun folderCoverOf(kind: String, ref: String?): FolderCover = when (kind) {
    FolderCover.LatestNote.kind -> FolderCover.LatestNote
    "DRAWN" -> ref?.let { FolderCover.Drawn(DocumentId(it)) } ?: FolderCover.Icon
    "IMAGE" -> ref?.let { FolderCover.Image(it) } ?: FolderCover.Icon
    else -> FolderCover.Icon
}

/** How a cover is stored. Kept beside [folderCoverOf] so the two cannot drift apart. */
private val FolderCover.kind: String
    get() = when (this) {
        FolderCover.Icon -> "ICON"
        FolderCover.LatestNote -> "LATEST_NOTE"
        is FolderCover.Drawn -> "DRAWN"
        is FolderCover.Image -> "IMAGE"
    }

private val FolderCover.ref: String?
    get() = when (this) {
        FolderCover.Icon, FolderCover.LatestNote -> null
        is FolderCover.Drawn -> noteId.value
        is FolderCover.Image -> asset
    }

private fun dev.inkstone.core.database.Folder.toFolder() = Folder(
    id = FolderId(id),
    parentId = parentId?.let(::FolderId),
    name = name,
    sortKey = FractionalKey(sortKey),
    colorArgb = colorArgb?.toInt(),
    icon = FolderIcon.entries.firstOrNull { it.name == icon } ?: FolderIcon.FOLDER,
    cover = folderCoverOf(coverKind, coverRef),
    createdEpochMillis = createdAt,
    modifiedEpochMillis = modifiedAt,
    deletedEpochMillis = deletedAt,
    deletedWithFolderId = deletedWithFolderId?.let(::FolderId),
)

private fun dev.inkstone.core.database.Note.toSummary() = NoteSummary(
    id = DocumentId(id),
    title = title,
    folderId = folderId?.let(::FolderId),
    favourite = favourite != 0L,
    isCover = isCover != 0L,
    pageCount = pageCount.toInt(),
    // An unrecognised state is treated as local-only rather than rejected: a database
    // written by a newer build must not stop an older one from opening the library.
    syncState = NoteSyncState.entries.firstOrNull { it.name == syncState } ?: NoteSyncState.LOCAL_ONLY,
    localRev = localRev,
    thumbnailRev = thumbnailRev,
    createdEpochMillis = createdAt,
    modifiedEpochMillis = modifiedAt,
    deletedEpochMillis = deletedAt,
    deletedWithFolderId = deletedWithFolderId?.let(::FolderId),
)
