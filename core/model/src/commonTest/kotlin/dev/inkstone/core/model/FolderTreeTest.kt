package dev.inkstone.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SPEC §9 — the folder tree, and the three ways a real one is broken.
 *
 * The happy path is barely worth a test. What is worth testing is that a tree assembled
 * from rows a *server* wrote — M5 pulls folders one PROPFIND level at a time — cannot hang
 * the library or hide a folder from it.
 */
class FolderTreeTest {

    private fun folder(id: String, parent: String? = null, name: String = id, key: String = "M") = Folder(
        id = FolderId(id),
        parentId = parent?.let(::FolderId),
        name = name,
        sortKey = FractionalKey(key),
    )

    @Test
    fun `a path runs from the root down to the folder`() {
        val folders = listOf(folder("a"), folder("b", parent = "a"), folder("c", parent = "b"))
        val path = FolderTree.pathOf(FolderId("c"), folders.associateBy { it.id })

        assertEquals(listOf("a", "b", "c"), path.map { it.id.value })
    }

    @Test
    fun `the library root has an empty path`() {
        assertEquals(emptyList(), FolderTree.pathOf(null, emptyMap()))
    }

    @Test
    fun `a cycle does not hang`() {
        // Reachable through two devices moving the same two folders into each other while
        // offline, which the merge would then apply in an order neither device chose.
        // Whatever the library does about that, it must not be "spin forever".
        val folders = listOf(folder("a", parent = "b"), folder("b", parent = "a"))
        val byId = folders.associateBy { it.id }

        val path = FolderTree.pathOf(FolderId("a"), byId)

        assertTrue(path.size <= folders.size, "a cycle produced a path longer than the tree")
        assertTrue(FolderTree.isDescendantOf(FolderId("a"), FolderId("b"), byId))
    }

    @Test
    fun `a folder whose parent is missing still has a path ending in itself`() {
        // The M5 case: a child arrives before its parent, or the parent is in the trash. A
        // folder that vanished from the UI because its parent row was late would look like
        // data loss.
        val orphan = folder("child", parent = "gone")
        val path = FolderTree.pathOf(orphan.id, mapOf(orphan.id to orphan))

        assertEquals(listOf("child"), path.map { it.id.value })
    }

    @Test
    fun `a subtree reaches every descendant exactly once`() {
        val folders = listOf(
            folder("root"),
            folder("a", parent = "root"),
            folder("b", parent = "root"),
            folder("a1", parent = "a"),
            folder("elsewhere"),
        )

        val subtree = FolderTree.subtreeOf(FolderId("root"), folders).map { it.id.value }

        assertEquals(listOf("root", "a", "b", "a1"), subtree)
        assertEquals(subtree.size, subtree.distinct().size)
    }

    @Test
    fun `a folder is its own descendant — so it cannot be moved into itself`() {
        val folders = listOf(folder("a"), folder("b", parent = "a"))
        val byId = folders.associateBy { it.id }

        assertTrue(FolderTree.isDescendantOf(FolderId("a"), FolderId("a"), byId))
        assertTrue(FolderTree.isDescendantOf(FolderId("b"), FolderId("a"), byId))
        assertFalse(FolderTree.isDescendantOf(FolderId("a"), FolderId("b"), byId))
    }

    @Test
    fun `children exclude trashed folders and sort by key then name`() {
        val folders = listOf(
            folder("z", name = "Zebra", key = "B"),
            folder("a", name = "Apple", key = "C"),
            folder("gone", name = "Gone", key = "A").copy(deletedEpochMillis = 1L),
        )

        val children = FolderTree.childrenOf(null, folders)

        assertEquals(listOf("Zebra", "Apple"), children.map { it.name })
    }

    // -----------------------------------------------------------------------
    // Remote paths — SPEC §9's "folders mirror Nextcloud both directions"
    // -----------------------------------------------------------------------

    @Test
    fun `a remote path is the sanitised chain under the notes root`() {
        val folders = listOf(folder("a", name = "Physics"), folder("b", parent = "a", name = "Term 2"))

        val path = FolderTree.remotePathOf(FolderId("b"), folders.associateBy { it.id }, "/Notes")

        assertEquals("/Notes/Physics/Term 2", path)
    }

    @Test
    fun `a slash in a folder name does not become a second remote level`() {
        // The whole reason the sanitiser runs here rather than at upload time: one folder
        // named "Physics / Term 2" must be one remote folder, not two.
        val folders = listOf(folder("a", name = "Physics / Term 2"))

        val path = FolderTree.remotePathOf(FolderId("a"), folders.associateBy { it.id }, "/Notes")

        assertEquals("/Notes/Physics _ Term 2", path)
        assertEquals(1, path.removePrefix("/Notes/").count { it == '/' } + 1)
    }

    @Test
    fun `the library root maps to the notes root itself`() {
        assertEquals("/Notes", FolderTree.remotePathOf(null, emptyMap(), "/Notes/"))
    }
}
