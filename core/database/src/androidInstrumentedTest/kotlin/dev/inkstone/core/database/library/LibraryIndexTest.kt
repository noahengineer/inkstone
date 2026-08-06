package dev.inkstone.core.database.library

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.Folder
import dev.inkstone.core.model.FolderIcon
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.model.NoteSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC §9 and §5.7, against **the device's own SQLite**.
 *
 * Instrumented rather than a JVM unit test, for the reason `:android:ink`'s erase tests are:
 * the thing under test is the engine. The trash cascade is a transaction across two tables
 * and thumbnail invalidation is a single `UPDATE` that must not be splittable — a fake index
 * would pass whatever the fake was written to do, and a desktop SQLite is a different build
 * from the tablet's.
 *
 * ```
 * ./gradlew :core:database:connectedDebugAndroidTest
 * ```
 *
 * In-memory (`name = null`), so each test starts empty and nothing is left on the device.
 */
@RunWith(AndroidJUnit4::class)
class LibraryIndexTest {

    private val driver = AndroidSqliteDriver(
        schema = InkstoneDatabase.Schema,
        context = ApplicationProvider.getApplicationContext(),
        name = null,
    )
    private val database = InkstoneDatabase(driver)
    private val index = LibraryIndex(database, UnconfinedTestDispatcher())

    @AfterTest
    fun close() = driver.close()

    private fun folder(id: String, parent: String? = null, name: String = id) = Folder(
        id = FolderId(id),
        parentId = parent?.let(::FolderId),
        name = name,
        createdEpochMillis = 1_000,
        modifiedEpochMillis = 1_000,
    )

    private fun note(id: String, folder: String? = null, title: String = id, rev: Long = 1) = NoteSummary(
        id = DocumentId(id),
        title = title,
        folderId = folder?.let(::FolderId),
        localRev = rev,
        createdEpochMillis = 1_000,
        modifiedEpochMillis = 1_000,
    )

    // -----------------------------------------------------------------------
    // Thumbnail freshness (SPEC §5.7)
    // -----------------------------------------------------------------------

    @Test
    fun `a save invalidates the thumbnail in the same statement that bumps the revision`() = runTest {
        index.insertNote(note("n1"))
        index.recordThumbnail(DocumentId("n1"), localRev = 1)
        assertTrue(assertNotNull(index.note(DocumentId("n1"))).hasFreshThumbnail)

        index.recordSave(DocumentId("n1"), localRev = 2, pageCount = 3, nowEpochMillis = 2_000)

        val after = assertNotNull(index.note(DocumentId("n1")))
        assertEquals(2, after.localRev)
        assertEquals(3, after.pageCount)
        assertNull(after.thumbnailRev, "the thumbnail survived a save — the library would show the old picture")
        assertFalse(after.hasFreshThumbnail)
    }

    @Test
    fun `a thumbnail recorded for an older revision is not fresh`() = runTest {
        // The race the revision key exists to lose safely: a render that started before an
        // edit and finished after it must not be trusted.
        index.insertNote(note("n1", rev = 5))
        index.recordThumbnail(DocumentId("n1"), localRev = 4)

        assertFalse(assertNotNull(index.note(DocumentId("n1"))).hasFreshThumbnail)
    }

    // -----------------------------------------------------------------------
    // Trash (CLAUDE.md rule 1)
    // -----------------------------------------------------------------------

    @Test
    fun `trashing a folder carries its subtree and restore brings all of it back`() = runTest {
        index.insertFolder(folder("root"))
        index.insertFolder(folder("child", parent = "root"))
        index.insertNote(note("n1", folder = "root"))
        index.insertNote(note("n2", folder = "child"))
        index.insertNote(note("outside"))

        index.trashFolder(
            id = FolderId("root"),
            descendantFolderIds = listOf(FolderId("child")),
            noteTrashPaths = mapOf(DocumentId("n1") to "trash/1-n1", DocumentId("n2") to "trash/1-n2"),
            deletedEpochMillis = 5_000,
        )

        // One entry in the trash list, not three. That is what `deletedWithFolderId` buys.
        assertEquals(listOf("root"), index.trashedFolders().first().map { it.id.value })
        assertTrue(index.trashedNotes().first().isEmpty())
        assertEquals(setOf("n1", "n2"), index.notesTrashedWith(FolderId("root")).mapTo(HashSet()) { it.id.value })
        assertEquals(listOf("outside"), index.allNotes().map { it.id.value })

        for (id in listOf("n1", "n2")) index.restoreNote(DocumentId(id))
        index.restoreFolderSubtree(FolderId("root"))

        assertTrue(index.trashedFolders().first().isEmpty())
        assertEquals(setOf("n1", "n2", "outside"), index.allNotes().mapTo(HashSet()) { it.id.value })
        assertTrue(index.allFolders().none { it.isTrashed })
    }

    @Test
    fun `restoring a folder does not restore a note whose files could not move back`() = runTest {
        // The repository restores notes one at a time because a directory move can fail.
        // `restoreFolderSubtree` must therefore leave note rows alone — if it cleared them,
        // a note whose files are still in the trash would show in the library and fail to
        // open.
        index.insertFolder(folder("root"))
        index.insertNote(note("kept", folder = "root"))
        index.insertNote(note("stuck", folder = "root"))
        index.trashFolder(
            id = FolderId("root"),
            descendantFolderIds = emptyList(),
            noteTrashPaths = mapOf(DocumentId("kept") to "t/kept", DocumentId("stuck") to "t/stuck"),
            deletedEpochMillis = 5_000,
        )

        index.restoreNote(DocumentId("kept"))
        index.restoreFolderSubtree(FolderId("root"))

        assertEquals(listOf("kept"), index.allNotes().map { it.id.value })
        assertEquals(listOf("stuck"), index.notesTrashedWith(FolderId("root")).map { it.id.value })
    }

    @Test
    fun `a trashed note keeps everything needed to restore it`() = runTest {
        index.insertNote(note("n1", title = "Lecture"))

        index.trashNote(DocumentId("n1"), trashPath = "trash/9-n1", deletedEpochMillis = 9_000)

        val trashed = index.trashedNotes().first().single()
        assertEquals("Lecture", trashed.title)
        assertEquals(9_000, trashed.deletedEpochMillis)
        assertNull(trashed.deletedWithFolderId, "a directly trashed note must be a top-level trash entry")
    }

    @Test
    fun `expiry finds only rows past the cutoff and forgetting is what removes them`() = runTest {
        index.insertNote(note("old"))
        index.insertNote(note("recent"))
        index.trashNote(DocumentId("old"), "t/old", deletedEpochMillis = 1_000)
        index.trashNote(DocumentId("recent"), "t/recent", deletedEpochMillis = 9_000)

        val expired = index.expired(cutoffEpochMillis = 5_000)

        assertEquals(listOf("old"), expired.notes.map { it.id.value })
        // Still there: `expired` reports, it does not delete. Files go first, or a purge
        // leaves a note on disk that nothing knows about (and `reconcile` would adopt back).
        assertNotNull(index.note(DocumentId("old")))

        index.forget(expired.notes.map { it.id }, emptyList())
        assertNull(index.note(DocumentId("old")))
        assertNotNull(index.note(DocumentId("recent")))
    }

    // -----------------------------------------------------------------------
    // Everything else
    // -----------------------------------------------------------------------

    @Test
    fun `folders and notes round-trip through their rows`() = runTest {
        index.insertFolder(folder("f1", name = "Physics"))
        index.setFolderAppearance(FolderId("f1"), colorArgb = 0xFFAABBCC.toInt(), FolderIcon.FLASK, 2_000)
        index.insertNote(note("n1", folder = "f1", title = "Waves"))
        index.setFavourite(DocumentId("n1"), true)

        val folder = assertNotNull(index.folder(FolderId("f1")))
        assertEquals("Physics", folder.name)
        assertEquals(0xFFAABBCC.toInt(), folder.colorArgb)
        assertEquals(FolderIcon.FLASK, folder.icon)

        val note = assertNotNull(index.note(DocumentId("n1")))
        assertEquals(FolderId("f1"), note.folderId)
        assertTrue(note.favourite)
    }

    @Test
    fun `moving a note between folders keeps everything else`() = runTest {
        index.insertFolder(folder("f1"))
        index.insertNote(note("n1", folder = "f1", rev = 4))
        index.recordThumbnail(DocumentId("n1"), 4)

        index.moveNote(DocumentId("n1"), folderId = null, nowEpochMillis = 3_000)

        val note = assertNotNull(index.note(DocumentId("n1")))
        assertNull(note.folderId)
        // A move is not an edit: the note's bytes did not change, so its picture is still
        // right and re-rendering 50 thumbnails for a drag would be pure waste.
        assertTrue(note.hasFreshThumbnail)
    }

    @Test
    fun `eviction candidates are everything outside the most recently modified`() = runTest {
        index.insertNote(note("a").copy(modifiedEpochMillis = 1))
        index.insertNote(note("b").copy(modifiedEpochMillis = 2))
        index.insertNote(note("c").copy(modifiedEpochMillis = 3))

        assertEquals(listOf("b", "a"), index.thumbnailEvictionCandidates(keep = 1).map { it.value })
        assertTrue(index.thumbnailEvictionCandidates(keep = 10).isEmpty())
    }

    @Test
    fun `allNotes excludes the trash unless asked`() = runTest {
        index.insertNote(note("live"))
        index.insertNote(note("gone"))
        index.trashNote(DocumentId("gone"), "t/gone", 1_000)

        assertEquals(listOf("live"), index.allNotes().map { it.id.value })
        assertEquals(setOf("live", "gone"), index.allNotes(includeTrashed = true).mapTo(HashSet()) { it.id.value })
    }
}
