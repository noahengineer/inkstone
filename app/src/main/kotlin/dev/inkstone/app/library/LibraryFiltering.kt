package dev.inkstone.app.library

import dev.inkstone.core.model.Folder
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.model.FolderTree
import dev.inkstone.core.model.LibrarySort
import dev.inkstone.core.model.NoteSummary

/**
 * What the library shows, as pure functions.
 *
 * Split out of [LibraryViewModel] so it can be tested without a database or a
 * `viewModelScope`. Search and sort are the two things a user notices immediately when they
 * are wrong, and both are entirely decidable from a list — so they belong somewhere a JVM
 * test can reach them.
 */
internal object LibraryFiltering {

    /**
     * The notes to show.
     *
     * **Search ignores the current folder.** That is the point of having search in an app
     * that also has folders: typing a title should find the note wherever it is, and a
     * search that only looked in the folder you happen to be standing in would be a filter,
     * not a search.
     */
    fun visibleNotes(
        notes: List<NoteSummary>,
        folderId: FolderId?,
        query: String,
        favouritesOnly: Boolean,
        sort: LibrarySort,
        descending: Boolean,
    ): List<NoteSummary> {
        val folded = query.trim().lowercase()
        return notes
            .filter { if (folded.isEmpty()) it.folderId == folderId else it.title.lowercase().contains(folded) }
            .filter { !favouritesOnly || it.favourite }
            .sortedWith(noteOrder(sort, descending))
    }

    /** Folders to show: matches while searching, children otherwise. Never trashed ones. */
    fun visibleFolders(folders: List<Folder>, folderId: FolderId?, query: String): List<Folder> {
        val folded = query.trim().lowercase()
        return if (folded.isEmpty()) {
            FolderTree.childrenOf(folderId, folders)
        } else {
            folders.filter { !it.isTrashed && it.name.lowercase().contains(folded) }
                .sortedBy { it.name.lowercase() }
        }
    }

    /**
     * ROADMAP M3 — *"sort by name/modified/created"*, with two rules on top.
     *
     * **Favourites first, always.** A favourite is a pin, and a pin that moves when you
     * change the sort order is not a pin.
     *
     * **The order is total.** Ties break on id, so a redraw cannot reshuffle equal rows —
     * which the user sees as cells swapping places mid-scroll, and which is exactly what
     * `LazyVerticalGrid`'s item keys are supposed to prevent.
     */
    fun noteOrder(sort: LibrarySort, descending: Boolean): Comparator<NoteSummary> {
        val within: Comparator<NoteSummary> = when (sort) {
            // `lowercase()` and not `String.CASE_INSENSITIVE_ORDER`, which folds ASCII only.
            // The index folds the same way, for the same reason (see Library.sq).
            LibrarySort.NAME -> compareBy { it.title.lowercase() }

            LibrarySort.MODIFIED -> compareBy { it.modifiedEpochMillis }

            LibrarySort.CREATED -> compareBy { it.createdEpochMillis }
        }
        return compareByDescending<NoteSummary> { it.favourite }
            .then(if (descending) within.reversed() else within)
            .thenBy { it.id.value }
    }
}
