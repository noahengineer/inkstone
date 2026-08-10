package dev.inkstone.core.storage

import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FileSystem

/**
 * CLAUDE.md rule 1 — **no hard deletes.** Local trash, 30-day retention.
 *
 * > Everything goes to a trash with 30-day retention — local trash for local deletes,
 * > Nextcloud trash for remote. Before any destructive local operation, the previous
 * > version goes to trash first.
 *
 * Deleting a note **moves** its directory (`notes/<id>` → `trash/<millis>-<id>`) rather
 * than copying and unlinking: a crash between a copy and an unlink leaves the note in
 * both places or in neither, and neither of those is a state worth designing recovery
 * for. A rename is one operation.
 *
 * The timestamp is in the directory name rather than in a sidecar, so expiry needs a
 * directory listing and nothing else — no index to fall out of sync with the files it
 * describes, and no way for a note to become undeletable because its metadata is
 * unreadable.
 *
 * A *page* delete is not this. That is a tombstone in the manifest (see
 * [dev.inkstone.core.model.PageRef]), because the page's ops have to stay in the log or
 * a peer would re-add the page on the next merge.
 */
public class NoteTrash(private val fs: FileSystem, private val clock: Clock) {

    /**
     * Move a note to the trash.
     *
     * @return the trash path, so a caller can offer an immediate undo.
     */
    public suspend fun trash(id: DocumentId): String {
        val destination = NoteLayout.trashEntry(id, clock.nowEpochMillis())
        fs.createDirectories(NoteLayout.TRASH_DIR)
        fs.move(NoteLayout.noteDir(id), destination)
        return destination
    }

    /**
     * Move a note back out of the trash.
     *
     * @return false when the note is not in the trash, or when a live note of the same
     *   id already exists — restoring over a live note would be the destructive
     *   operation this class exists to prevent.
     */
    public suspend fun restore(id: DocumentId): Boolean {
        val entry = entries().firstOrNull { it.id == id } ?: return false
        if (fs.exists(NoteLayout.noteDir(id)) && !setAsideStub(id)) return false
        fs.createDirectories(NoteLayout.NOTES_DIR)
        fs.move(entry.path, NoteLayout.noteDir(id))
        return true
    }

    /**
     * ⚠️ **Tell a live note apart from a stub, and move the stub aside so a restore can proceed.**
     *
     * Added in M6, for a permanent-data-loss path found in review. `notes/<id>/` existing was read
     * as *"a live note is already there"*, and there is a second way for it to exist: a note trashed
     * while a [NoteSession] held it. The session knows nothing about the move, its next two-second
     * autosave appends to `journal.ops` at the old path, and every [dev.inkstone.core.model.FileSystem]
     * write creates the parent directory first — so the directory comes back holding a journal, an
     * `undo.json` and **no bundle**. From there the restore refused forever (this function's caller
     * returned false, silently, on every attempt), the note could not be opened either
     * (`NoteStore.open` needs the bundle), and thirty days later [purgeExpired] deleted the only copy
     * of every page.
     *
     * The bundle is what decides, because the bundle is what makes a directory a note: `NoteStore.open`
     * reads it, and a journal without one folds onto nothing.
     *
     * The stub is **moved, never deleted** (CLAUDE.md rule 1). What is in it is a handful of ops the
     * user drew after the delete landed — small, unopenable on its own, and not ours to throw away.
     * See [NoteLayout.orphanedStub] for why it goes where it goes.
     *
     * @return false when `notes/<id>/` holds a real note, which is still refused rather than
     *   overwritten.
     */
    private suspend fun setAsideStub(id: DocumentId): Boolean {
        if (fs.exists(NoteLayout.bundle(id))) return false
        fs.move(NoteLayout.noteDir(id), NoteLayout.orphanedStub(id, clock.nowEpochMillis()))
        return true
    }

    /** Everything in the trash, newest first. */
    public suspend fun entries(): List<TrashEntry> = fs.list(NoteLayout.TRASH_DIR)
        .mapNotNull { name ->
            val separator = name.indexOf('-')
            if (separator <= 0) return@mapNotNull null
            val millis = name.substring(0, separator).toLongOrNull() ?: return@mapNotNull null
            TrashEntry(
                id = DocumentId(name.substring(separator + 1)),
                deletedEpochMillis = millis,
                path = "${NoteLayout.TRASH_DIR}/$name",
            )
        }
        .sortedByDescending { it.deletedEpochMillis }

    /**
     * Delete trash older than [RETENTION_MILLIS].
     *
     * The only place in this app that destroys user data, and it does so 30 days after
     * the user asked for it and after having kept it recoverable the whole time. It
     * takes a clock and no arguments so there is no way to call it with a shorter
     * retention by mistake.
     *
     * @return how many notes were purged.
     */
    public suspend fun purgeExpired(): Int {
        val cutoff = clock.nowEpochMillis() - RETENTION_MILLIS
        var purged = 0
        for (entry in entries()) {
            if (entry.deletedEpochMillis > cutoff) continue
            fs.delete(entry.path)
            purged++
        }
        return purged
    }

    public companion object {
        /** 30 days (CLAUDE.md rule 1). */
        public const val RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
    }
}

public data class TrashEntry(val id: DocumentId, val deletedEpochMillis: Long, val path: String)
