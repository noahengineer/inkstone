package dev.inkstone.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ⭐ **Pinning a library folder to any path on the server, with nesting that does not break.**
 *
 * SPEC §9's *"folders mirror Nextcloud both directions"* and ROADMAP M6's folder mirroring.
 * The nesting cases are the whole risk here: a resolver that is merely *plausible* puts notes
 * in the wrong folder on the server, and the user finds out by looking at their Nextcloud
 * rather than by anything the app says.
 */
class RemoteFolderTest {

    private var nextKey = 0

    private fun folder(id: String, name: String, parent: String? = null) = Folder(
        id = FolderId(id),
        parentId = parent?.let(::FolderId),
        name = name,
        sortKey = FractionalKey.sequence(++nextKey).last(),
    )

    private fun tree(vararg folders: Folder): Map<FolderId, Folder> = folders.associateBy { it.id }

    private fun resolve(
        folderId: String?,
        folders: Map<FolderId, Folder>,
        pinned: Map<String, String> = emptyMap(),
        root: String = "Inkstone",
    ) = FolderTree.remoteFolderFor(
        folderId = folderId?.let(::FolderId),
        folders = folders,
        boundFolders = pinned.mapKeys { FolderId(it.key) },
        accountRoot = root,
    )

    // -----------------------------------------------------------------------

    @Test
    fun `with nothing pinned a note lands under the account root`() = run {
        // The generalisation must not change unpinned behaviour: an existing library keeps
        // syncing exactly where it did before.
        val folders = tree(
            folder("school", "School"),
            folder("physics", "Physics", parent = "school"),
        )

        assertEquals("Inkstone/School/Physics", resolve("physics", folders))
        assertEquals("Inkstone/School", resolve("school", folders))
        assertEquals("Inkstone", resolve(null, folders))
    }

    @Test
    fun `a pinned folder puts its children under the pinned path`() = run {
        // The headline case: pin one folder to Documents/Uni and everything beneath follows,
        // with no further configuration.
        val folders = tree(
            folder("school", "School"),
            folder("physics", "Physics", parent = "school"),
            folder("term", "Term 1", parent = "physics"),
        )
        val pinned = mapOf("school" to "Documents/Uni")

        assertEquals("Documents/Uni", resolve("school", folders, pinned))
        assertEquals("Documents/Uni/Physics", resolve("physics", folders, pinned))
        assertEquals("Documents/Uni/Physics/Term 1", resolve("term", folders, pinned))
    }

    @Test
    fun `the pinned folder contributes its path and not its own name`() = run {
        // Appending the library name on top would give Documents/Uni/School, which is not what
        // was asked for and cannot be corrected from the UI.
        val folders = tree(folder("school", "School"))

        assertEquals("Documents/Uni", resolve("school", folders, mapOf("school" to "Documents/Uni")))
    }

    @Test
    fun `the deepest pin wins`() = run {
        // Most-specific-wins is the only rule under which pinning a subfolder means anything.
        val folders = tree(
            folder("school", "School"),
            folder("physics", "Physics", parent = "school"),
            folder("term", "Term 1", parent = "physics"),
        )
        val pinned = mapOf("school" to "Documents/Uni", "physics" to "Archive/Physics")

        assertEquals("Documents/Uni", resolve("school", folders, pinned))
        assertEquals("Archive/Physics", resolve("physics", folders, pinned))
        assertEquals("Archive/Physics/Term 1", resolve("term", folders, pinned))
    }

    @Test
    fun `a pin deeper in the tree does not affect its siblings`() = run {
        val folders = tree(
            folder("school", "School"),
            folder("physics", "Physics", parent = "school"),
            folder("maths", "Maths", parent = "school"),
        )
        val pinned = mapOf("school" to "Documents/Uni", "physics" to "Archive/Physics")

        assertEquals("Archive/Physics", resolve("physics", folders, pinned))
        assertEquals("Documents/Uni/Maths", resolve("maths", folders, pinned), "a sibling followed the wrong pin")
    }

    @Test
    fun `a folder pinned to the server root gets no leading slash`() = run {
        // A leading slash resolves against the DAV endpoint rather than the account's files
        // root — a different place, and its 404 reads as "the folder is missing".
        val folders = tree(
            folder("school", "School"),
            folder("physics", "Physics", parent = "school"),
        )
        val pinned = mapOf("school" to "")

        assertEquals("", resolve("school", folders, pinned))
        val nested = resolve("physics", folders, pinned)
        assertEquals("Physics", nested)
        assertFalse(nested.startsWith("/"), "an absolute path would address the wrong endpoint")
    }

    @Test
    fun `a trailing slash on a pinned path does not double up`() = run {
        val folders = tree(
            folder("school", "School"),
            folder("physics", "Physics", parent = "school"),
        )

        assertEquals("Documents/Uni/Physics", resolve("physics", folders, mapOf("school" to "Documents/Uni/")))
        assertEquals("Documents/Uni/Physics", resolve("physics", folders, mapOf("school" to "/Documents/Uni")))
    }

    @Test
    fun `a folder name containing a slash becomes one segment and not two levels`() = run {
        // SPEC §7.8. A folder called "Physics / Term 2" must not silently become two levels of
        // remote hierarchy — the user made one folder and expects one folder.
        val folders = tree(
            folder("school", "School"),
            folder("split", "Physics / Term 2", parent = "school"),
        )

        val path = resolve("split", folders, mapOf("school" to "Documents/Uni"))
        assertEquals("Documents/Uni/Physics _ Term 2", path)
        // The part derived from folder names must be exactly one segment. Counting slashes in
        // the whole path would also count the pinned base's, which are meant.
        val derived = path.removePrefix("Documents/Uni/")
        assertFalse('/' in derived, "the folder name became $derived — two levels instead of one")
    }

    @Test
    fun `a pinned path keeps its own slashes`() = run {
        // The mirror of the previous test, and the reason the two are treated differently: a
        // pinned path is a *path*, chosen against a real folder listing, so its slashes mean
        // what they say.
        val folders = tree(folder("school", "School"))

        assertEquals("a/b/c", resolve("school", folders, mapOf("school" to "a/b/c")))
    }

    @Test
    fun `an unreachable pin degrades to the account root`() = run {
        // A folder whose parent row is missing cannot see a pin above the break. Falling back
        // puts the note somewhere real rather than at a path assembled from half an ancestry.
        val orphan = folder("physics", "Physics", parent = "missing")
        val folders = tree(orphan)

        assertEquals("Inkstone/Physics", resolve("physics", folders, mapOf("school" to "Documents/Uni")))
    }

    @Test
    fun `a parent cycle terminates and does not hang`() = run {
        // SPEC §9 has folders arriving from a server this app does not control, where a cycle
        // is a reachable state. A library that hangs is worse than a folder at the root.
        val a = Folder(FolderId("a"), FolderId("b"), "A", FractionalKey.Initial)
        val b = Folder(FolderId("b"), FolderId("a"), "B", FractionalKey.Initial)
        val folders = tree(a, b)

        val path = resolve("a", folders, mapOf("a" to "Documents/Uni"))

        assertTrue(path.isNotEmpty())
        assertTrue(path.startsWith("Documents/Uni"), "the deepest pin should still apply: $path")
    }

    @Test
    fun `a deeply nested library produces one segment per level`() = run {
        var folders = emptyMap<FolderId, Folder>()
        var parent: String? = null
        val depth = 12
        val built = ArrayList<Folder>()
        for (level in 1..depth) {
            val f = folder("f$level", "Level $level", parent = parent)
            built += f
            parent = "f$level"
        }
        folders = tree(*built.toTypedArray())

        val path = resolve("f$depth", folders, mapOf("f1" to "Documents/Uni"))

        // The pin replaces level 1, so 11 derived segments hang off it.
        assertEquals("Documents/Uni/" + (2..depth).joinToString("/") { "Level $it" }, path)
    }

    @Test
    fun `a note at the library root ignores every pin`() = run {
        // Nothing above it is pinned because nothing is above it. The account default is the
        // only correct answer.
        val folders = tree(folder("school", "School"))

        assertEquals("Inkstone", resolve(null, folders, mapOf("school" to "Documents/Uni")))
    }

    @Test
    fun `every derived segment is legal on a default Nextcloud`() = run {
        // The paths this produces go straight into a PUT. A segment that is not legal is a 400
        // per upload, forever, for every note in that folder.
        val folders = tree(
            folder("school", "School: 2026"),
            folder("awkward", "Notes<>?*|\"  ", parent = "school"),
            folder("dots", "trailing...", parent = "awkward"),
        )

        val path = resolve("dots", folders, mapOf("school" to "Documents/Uni"))
        val derived = path.removePrefix("Documents/Uni/").split("/")

        for (segment in derived) {
            assertTrue(RemoteName.isLegal(segment), "illegal remote segment: '$segment' in $path")
        }
    }
}

/**
 * ⭐ **The bug: pinning a folder did nothing.**
 *
 * `remoteFolderFor` was correct and every test above passed, and the feature was still inert —
 * pinning recorded the mapping and nothing bound a single note, so no remote folder appeared and
 * nothing uploaded. These are the assertions that would have caught it: not "what path would a
 * note get", but "**is anything going to happen at all**".
 */
class FolderSyncPlanTest {

    private var nextKey = 0

    private fun folder(id: String, name: String, parent: String? = null) = Folder(
        id = FolderId(id),
        parentId = parent?.let(::FolderId),
        name = name,
        sortKey = FractionalKey.sequence(++nextKey).last(),
    )

    private fun note(id: String, title: String, folder: String?, trashed: Boolean = false) = NoteSummary(
        id = DocumentId(id),
        title = title,
        folderId = folder?.let(::FolderId),
        pageCount = 1,
        deletedEpochMillis = if (trashed) 1L else null,
    )

    private fun plan(
        root: String,
        folders: List<Folder>,
        notes: List<NoteSummary>,
        bound: Set<String> = emptySet(),
        pins: Map<String, String> = emptyMap(),
        accountRoot: String = "Inkstone",
    ) = FolderTree.planFolderSync(
        root = FolderId(root),
        folders = folders,
        notes = notes,
        boundNotes = bound.mapTo(HashSet()) { DocumentId(it) },
        pins = pins.mapKeys { FolderId(it.key) },
        accountRoot = accountRoot,
    )

    @Test
    fun `pinning a folder with a note in it produces a bind`() {
        // THE regression test. The first implementation produced nothing here, which is exactly
        // what "no new folder, no note, nothing" looked like on the device.
        val folders = listOf(folder("school", "School"))
        val notes = listOf(note("n1", "Waves", "school"))

        val steps = plan("school", folders, notes, pins = mapOf("school" to "Documents/Uni"))

        assertEquals(1, steps.size, "pinning a folder holding a note planned no work at all")
        assertEquals(DocumentId("n1"), steps.single().noteId)
        assertEquals("Documents/Uni", steps.single().remoteFolder)
        assertFalse(steps.single().alreadyBound, "an unbound note must be bound, which is what creates the folder")
    }

    @Test
    fun `pinning covers notes in nested subfolders too`() {
        val folders = listOf(
            folder("school", "School"),
            folder("physics", "Physics", parent = "school"),
            folder("term", "Term 1", parent = "physics"),
        )
        val notes = listOf(
            note("n1", "Top", "school"),
            note("n2", "Mid", "physics"),
            note("n3", "Deep", "term"),
        )

        val steps = plan("school", folders, notes, pins = mapOf("school" to "Documents/Uni"))

        assertEquals(3, steps.size, "the subtree was not covered")
        assertEquals("Documents/Uni", steps.first { it.noteId.value == "n1" }.remoteFolder)
        assertEquals("Documents/Uni/Physics", steps.first { it.noteId.value == "n2" }.remoteFolder)
        assertEquals("Documents/Uni/Physics/Term 1", steps.first { it.noteId.value == "n3" }.remoteFolder)
    }

    @Test
    fun `an already-bound note is moved and not bound again`() {
        // Binding it again would try to create a file that exists; leaving it alone would leave it
        // at the old path while its siblings moved. Both are wrong in different directions.
        val folders = listOf(folder("school", "School"))
        val notes = listOf(note("n1", "Waves", "school"))

        val steps = plan("school", folders, notes, bound = setOf("n1"), pins = mapOf("school" to "Documents/Uni"))

        assertTrue(steps.single().alreadyBound)
    }

    @Test
    fun `notes outside the pinned folder are untouched`() {
        val folders = listOf(folder("school", "School"), folder("other", "Other"))
        val notes = listOf(note("n1", "In", "school"), note("n2", "Out", "other"), note("n3", "Root", null))

        val steps = plan("school", folders, notes, pins = mapOf("school" to "Documents/Uni"))

        assertEquals(listOf(DocumentId("n1")), steps.map { it.noteId })
    }

    @Test
    fun `a trashed note is not synced back to the server`() {
        // It is in the trash pending 30-day retention. Uploading it would resurrect it remotely.
        val folders = listOf(folder("school", "School"))
        val notes = listOf(note("n1", "Live", "school"), note("n2", "Binned", "school", trashed = true))

        val steps = plan("school", folders, notes, pins = mapOf("school" to "Documents/Uni"))

        assertEquals(listOf(DocumentId("n1")), steps.map { it.noteId })
    }

    @Test
    fun `a deeper pin still wins inside a plan`() {
        val folders = listOf(
            folder("school", "School"),
            folder("physics", "Physics", parent = "school"),
        )
        val notes = listOf(note("n1", "Top", "school"), note("n2", "Phys", "physics"))

        val steps = plan(
            "school",
            folders,
            notes,
            pins = mapOf("school" to "Documents/Uni", "physics" to "Archive/Physics"),
        )

        assertEquals("Documents/Uni", steps.first { it.noteId.value == "n1" }.remoteFolder)
        assertEquals("Archive/Physics", steps.first { it.noteId.value == "n2" }.remoteFolder)
    }

    @Test
    fun `an empty folder plans nothing and that is not a failure`() {
        val folders = listOf(folder("school", "School"))

        val steps = plan("school", folders, notes = emptyList(), pins = mapOf("school" to "Documents/Uni"))

        assertTrue(steps.isEmpty())
    }
}
