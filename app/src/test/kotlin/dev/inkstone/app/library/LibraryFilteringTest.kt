package dev.inkstone.app.library

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.Folder
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.model.LibrarySort
import dev.inkstone.core.model.NoteSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ROADMAP M3 — *"title search returns results as you type"*, and *"sort by
 * name/modified/created"*.
 *
 * Both are decidable from a list, which is why they live in [LibraryFiltering] rather than
 * inside the view model — a search that quietly only looked in the current folder would
 * otherwise need a device to notice.
 */
class LibraryFilteringTest {

    private val work = FolderId("work")
    private val personal = FolderId("personal")

    private fun note(
        id: String,
        title: String = id,
        folder: FolderId? = null,
        favourite: Boolean = false,
        modified: Long = 0,
        created: Long = 0,
    ) = NoteSummary(
        id = DocumentId(id),
        title = title,
        folderId = folder,
        favourite = favourite,
        modifiedEpochMillis = modified,
        createdEpochMillis = created,
    )

    private fun visible(
        notes: List<NoteSummary>,
        folderId: FolderId? = null,
        query: String = "",
        favouritesOnly: Boolean = false,
        sort: LibrarySort = LibrarySort.MODIFIED,
        descending: Boolean = true,
    ) = LibraryFiltering.visibleNotes(notes, folderId, query, favouritesOnly, sort, descending).map { it.id.value }

    @Test
    fun `with no query, only the current folder's notes are shown`() {
        val notes = listOf(note("a", folder = work), note("b", folder = personal), note("c"))

        assertEquals(listOf("a"), visible(notes, folderId = work))
        assertEquals(listOf("c"), visible(notes, folderId = null))
    }

    @Test
    fun `search reaches every folder, not just the one you are standing in`() {
        // The whole reason search exists in an app that also has folders. A search scoped to
        // the current folder is a filter, and finding nothing would look like the note was
        // gone.
        val notes = listOf(
            note("a", title = "Waves", folder = work),
            note("b", title = "Wave equation", folder = personal),
            note("c", title = "Groceries"),
        )

        assertEquals(setOf("a", "b"), visible(notes, folderId = work, query = "wave").toSet())
    }

    @Test
    fun `search is case-insensitive beyond ASCII`() {
        // SQLite's own `lower()` folds ASCII only, which is why the fold happens in Kotlin
        // both here and in the index (Library.sq). A note called "Übung" that a search for
        // "übung" cannot find is a bug in any language with accents.
        val notes = listOf(note("a", title = "Übung 3"), note("b", title = "ÜBUNG 4"))

        assertEquals(setOf("a", "b"), visible(notes, query = "übung").toSet())
        assertEquals(setOf("a", "b"), visible(notes, query = "ÜBUNG").toSet())
    }

    @Test
    fun `a blank query is not a search`() {
        val notes = listOf(note("a", folder = work), note("b"))

        assertEquals(listOf("b"), visible(notes, query = "   "))
    }

    @Test
    fun `favourites sort first whatever the order`() {
        val notes = listOf(
            note("plain", title = "AAA", modified = 100),
            note("pinned", title = "ZZZ", favourite = true, modified = 1),
        )

        for (sort in LibrarySort.entries) {
            for (descending in listOf(true, false)) {
                assertEquals(
                    "a favourite moved when the sort changed — a pin that moves is not a pin",
                    "pinned",
                    visible(notes, sort = sort, descending = descending).first(),
                )
            }
        }
    }

    @Test
    fun `sort by name is A to Z and by date is newest first`() {
        val notes = listOf(
            note("a", title = "Beta", modified = 1, created = 3),
            note("b", title = "Alpha", modified = 2, created = 1),
            note("c", title = "Gamma", modified = 3, created = 2),
        )

        assertEquals(listOf("b", "a", "c"), visible(notes, sort = LibrarySort.NAME, descending = false))
        assertEquals(listOf("c", "b", "a"), visible(notes, sort = LibrarySort.MODIFIED))
        assertEquals(listOf("a", "c", "b"), visible(notes, sort = LibrarySort.CREATED))
    }

    @Test
    fun `equal rows have a stable, total order`() {
        // Without the id tiebreak, two notes saved in the same millisecond can swap places
        // between redraws — which reads as cells jumping around mid-scroll.
        val notes = listOf(note("z", modified = 5), note("a", modified = 5), note("m", modified = 5))
        val order = LibraryFiltering.noteOrder(LibrarySort.MODIFIED, descending = true)

        val once = notes.sortedWith(order).map { it.id.value }
        val again = notes.reversed().sortedWith(order).map { it.id.value }

        assertEquals(once, again)
        assertEquals(listOf("a", "m", "z"), once)
    }

    @Test
    fun `the favourites filter applies to search results too`() {
        val notes = listOf(
            note("a", title = "Waves", favourite = true),
            note("b", title = "Waves 2"),
        )

        assertEquals(listOf("a"), visible(notes, query = "waves", favouritesOnly = true))
    }

    @Test
    fun `folders follow the same rule as notes`() {
        val folders = listOf(
            Folder(id = work, parentId = null, name = "Work"),
            Folder(id = personal, parentId = work, name = "Work notes"),
            Folder(id = FolderId("gone"), parentId = null, name = "Work archive", deletedEpochMillis = 1L),
        )

        assertEquals(listOf("Work"), LibraryFiltering.visibleFolders(folders, null, "").map { it.name })
        assertEquals(listOf(personal), LibraryFiltering.visibleFolders(folders, work, "").map { it.id })

        val found = LibraryFiltering.visibleFolders(folders, null, "work").map { it.name }
        assertEquals(listOf("Work", "Work notes"), found)
        assertTrue("a trashed folder came back in a search", "Work archive" !in found)
    }
}
