package dev.inkstone.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [FolderTree.libraryPathFor] — the inverse of [FolderTree.remoteFolderFor].
 *
 * ⛔ **The bug this exists for:** a factory reset returned 25 notes from `Documents/School/…`
 * flat, at the library root, because nothing read the folder structure back out of the remote
 * paths. Folder membership is the one thing `Library.sq` says cannot be recovered if the
 * database is lost — and it was sitting in every binding's `remotePath` the whole time.
 *
 * The property that matters most is the **round trip**: a chain this returns must re-derive,
 * through `remoteFolderFor`, to the directory it came from. Otherwise a later `moveNotes`
 * computes a different remote path and enqueues a MOVE that renames the user's own directory.
 */
class LibraryPathForTest {

    @Test
    fun aDirectoryUnderTheBaseBecomesOneSegmentPerLevel() {
        assertEquals(
            listOf("Physical Sciences", "Chemistry", "Revision"),
            FolderTree.libraryPathFor("Documents/School/Physical Sciences/Chemistry/Revision", "Documents/School"),
        )
    }

    @Test
    fun theBaseItselfIsTheLibraryRootAndNotAFolderNamedAfterIt() {
        // The real case: `Documents/School/document_149.pdf` belongs at the top level, not in a
        // folder called "School". An empty list is a decision; see the KDoc's three answers.
        assertEquals(emptyList(), FolderTree.libraryPathFor("Documents/School", "Documents/School"))
    }

    @Test
    fun aDirectoryOutsideTheBaseDecidesNothing() {
        // `null`, not `emptyList()`. Filing this at the root would be the reconciler moving a
        // note the mirror does not cover.
        assertNull(FolderTree.libraryPathFor("Photos/2026", "Documents/School"))
        assertNull(FolderTree.libraryPathFor("Documents/SchoolWork", "Documents/School"))
    }

    @Test
    fun leadingAndTrailingSlashesDoNotChangeTheAnswer() {
        assertEquals(listOf("Art"), FolderTree.libraryPathFor("/Documents/School/Art/", "/Documents/School/"))
    }

    @Test
    fun anEmptyBaseIsTheDavRootAndContainsEverything() {
        assertEquals(listOf("Documents", "School"), FolderTree.libraryPathFor("Documents/School", ""))
        assertEquals(emptyList(), FolderTree.libraryPathFor("", ""))
    }

    @Test
    fun segmentsAreSanitisedSoTheyAreAFixedPoint() {
        // `RemoteName.sanitise` is applied by `createFolder` anyway. Applying it here too is what
        // makes the chain re-derive to the same remote path rather than to a mangled sibling.
        val segments = FolderTree.libraryPathFor("base/a:b", "base")
        assertEquals(listOf(RemoteName.sanitise("a:b")), segments)
    }

    /**
     * The round trip, against the real forward map — the assertion that would have caught the
     * whole class of mis-filing, and the reason `mirrorRemoteFolders` re-checks before it moves
     * anything: `sanitise` is not injective, so this cannot hold for *every* name.
     */
    @Test
    fun aDerivedChainReDerivesToTheDirectoryItCameFrom() {
        val base = "Documents/School"
        val directory = "Documents/School/Physical Sciences/Chemistry/Revision"
        val segments = checkNotNull(FolderTree.libraryPathFor(directory, base))

        var parent: FolderId? = null
        val folders = mutableMapOf<FolderId, Folder>()
        for ((depth, segment) in segments.withIndex()) {
            val id = FolderId("folder-$depth")
            folders[id] = Folder(
                id = id,
                parentId = parent,
                name = segment,
                sortKey = FractionalKey.Initial,
                createdEpochMillis = 0,
                modifiedEpochMillis = 0,
            )
            parent = id
        }

        assertEquals(
            directory,
            FolderTree.remoteFolderFor(parent, folders, boundFolders = emptyMap(), accountRoot = base),
        )
    }

    /**
     * ⚠️ Rule 2 of [FolderTree.remoteFolderFor] read backwards: a pinned folder contributes its
     * *path*, so the chain starts **after** it. `mirrorRemoteFolders` anchors `parent` at the pin
     * for exactly this reason — deriving from the library root instead would build a duplicate
     * tree beside the pin.
     */
    @Test
    fun underAPinTheChainIsRelativeToThePinnedPath() {
        assertEquals(listOf("Chemistry"), FolderTree.libraryPathFor("shared/sci/Chemistry", "shared/sci"))
    }
}
