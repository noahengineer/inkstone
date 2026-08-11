package dev.inkstone.app.audio

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.database.library.LibraryIndex
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.model.FolderTree
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * ⭐ M12 §10 — *"Auto-record is per folder, not global."*
 *
 * *"Auto-record in Maths"* is what is actually wanted: you record in lectures, not when reviewing
 * at home. A global setting would mean the microphone turns itself on whenever **any** note opens,
 * which is a materially different thing from a button and would make the notification the only
 * signal that it had.
 *
 * ## Deepest ancestor wins, and off is a real answer
 *
 * The resolution is `LibraryRepository.pinFor`'s, deliberately — folder pinning (M6) is the same
 * shape and reusing its rule means there is one mental model for "a folder setting" rather than
 * two. Walk the note's folder ancestry from the leaf up and take the first folder that has a row.
 *
 * ⚠️ **A row saying `false` is not the same as no row.** *"Record in Maths, but not in
 * Maths/Revision"* needs an explicit off that overrides the on above it; without it the only way
 * to stop recording in a subfolder is to move it out of the tree.
 */
class AutoRecordFolders(
    private val database: InkstoneDatabase,
    private val index: LibraryIndex,
    private val dispatcher: CoroutineDispatcher,
) {

    private val queries = database.settingQueries

    /**
     * Should opening [noteId] start recording?
     *
     * False for a note in no folder at all: the root is not a lecture, and a setting that applied
     * to loose notes would be the global switch this is deliberately not.
     */
    suspend fun shouldAutoRecord(noteId: DocumentId): Boolean {
        val folderId = index.note(noteId)?.folderId ?: return false
        return governing(folderId) == true
    }

    /**
     * The setting that governs [folderId]: `true`, `false`, or null for *"inherit"* — which at the
     * top of the tree means off.
     */
    suspend fun governing(folderId: FolderId?): Boolean? {
        if (folderId == null) return null
        val settings = withContext(dispatcher) {
            queries.selectFolderSettings().executeAsList().associate { it.folderId to (it.autoRecord != 0L) }
        }
        if (settings.isEmpty()) return null
        val folders = index.allFolders().associateBy { it.id }
        return FolderTree.pathOf(folderId, folders)
            .asReversed()
            .firstNotNullOfOrNull { folder -> settings[folder.id.value] }
    }

    /** What this folder itself says, ignoring its ancestors — what the toggle in its menu shows. */
    suspend fun explicit(folderId: FolderId): Boolean? = withContext(dispatcher) {
        queries.selectFolderSetting(folderId.value).executeAsOneOrNull()?.let { it.autoRecord != 0L }
    }

    suspend fun set(folderId: FolderId, autoRecord: Boolean): Unit = withContext(dispatcher) {
        queries.upsertFolderSetting(folderId.value, if (autoRecord) 1L else 0L)
    }

    /**
     * Every folder with an **explicit** setting, by folder-id string — what the library's folder
     * menu reads so that opening one costs no query.
     *
     * Absence from this map is *inherit*, which is why it is a map of the rows that exist rather
     * than a set of the folders that record. A folder inside an auto-record folder is not in here
     * and still records; [shouldAutoRecord] is the one that resolves that.
     */
    fun explicitByFolder(): Flow<Map<String, Boolean>> = queries.selectFolderSettings()
        .asFlow()
        .mapToList(dispatcher)
        .map { rows -> rows.associate { it.folderId to (it.autoRecord != 0L) } }

    /** Back to inheriting. Not the same as setting it to false — see the class KDoc. */
    suspend fun clear(folderId: FolderId): Unit = withContext(dispatcher) {
        queries.clearFolderSetting(folderId.value)
    }
}
