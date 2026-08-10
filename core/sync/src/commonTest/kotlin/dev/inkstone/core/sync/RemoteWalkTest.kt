package dev.inkstone.core.sync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SPEC §7.4 rule 1 and §7.6 step 2, tested without a server.
 *
 * [RemoteWalk] is pure for the same reason [OutboxCoalescing] is: the walk's whole value is in what
 * it decides *not* to request, and a decision expressed as a table can be checked against a table.
 * Every case below is one row of the pruning rule, and three of them are silent-data-loss bugs if
 * they come out the other way.
 */
class RemoteWalkTest {

    private fun collection(path: String, etag: String?, ocId: String? = null, weak: Boolean = false) = DavResource(
        path = path, href = "/$path", isCollection = true,
        etag = etag?.let { ETag(it, weak) }, lastModified = null, contentLength = null,
        ocId = ocId, fileId = null, permissions = null, recursiveSize = null,
        checksums = emptyList(), dataFingerprint = null, lockOwner = null, locked = false,
        quotaAvailableBytes = null,
    )

    private fun file(path: String, etag: String = "e") = DavResource(
        path = path, href = "/$path", isCollection = false,
        etag = ETag(etag, weak = false), lastModified = null, contentLength = 1L,
        ocId = "oc-$path", fileId = null, permissions = null, recursiveSize = null,
        checksums = emptyList(), dataFingerprint = null, lockOwner = null, locked = false,
        quotaAvailableBytes = null,
    )

    private fun cache(vararg rows: RemoteDirectory) = rows.associateBy { it.path }

    // -----------------------------------------------------------------------
    // The pruning rule
    // -----------------------------------------------------------------------

    @Test
    fun `a strong matching child ETag prunes the subtree and never descends into it`() {
        // SPEC §7.4 rule 1 — a change anywhere under a folder changes that folder's ETag, so a
        // match proves the whole subtree is unchanged. This is what makes a 500-note library
        // cost one request.
        val step = RemoteWalk.plan(
            accountId = "acct-1",
            path = "Inkstone",
            listing = listOf(collection("Inkstone", "root"), collection("Inkstone/Physics", "same")),
            cached = cache(RemoteDirectory("acct-1", "Inkstone/Physics", ETag("same", weak = false))),
            nowEpochMillis = 100L,
        )

        assertContentEquals(listOf("Inkstone/Physics"), step.pruned)
        assertTrue(step.descend.isEmpty(), "a proven-unchanged subtree must not be listed")
    }

    @Test
    fun `a weak child ETag forces a descend`() {
        // ⚠️ The silent one. A weak validator says "semantically equivalent", not
        // "byte-identical", so treating one as a match means the pull never happens — the note
        // simply stops syncing and nothing anywhere reports an error. A false "changed" costs
        // one request. Take the expensive direction.
        val step = RemoteWalk.plan(
            accountId = "acct-1",
            path = "Inkstone",
            listing = listOf(collection("Inkstone", "root"), collection("Inkstone/Physics", "same", weak = true)),
            cached = cache(RemoteDirectory("acct-1", "Inkstone/Physics", ETag("same", weak = false))),
            nowEpochMillis = 100L,
        )

        assertContentEquals(listOf("Inkstone/Physics"), step.descend)
        assertTrue(step.pruned.isEmpty())
    }

    @Test
    fun `a weak cached ETag forces a descend too`() {
        // The other half. A weak value that was *stored* is just as unusable as one that arrives
        // weak, which is why `ETag` carries the flag instead of the call site recomputing it.
        val step = RemoteWalk.plan(
            accountId = "acct-1",
            path = "Inkstone",
            listing = listOf(collection("Inkstone", "root"), collection("Inkstone/Physics", "same")),
            cached = cache(RemoteDirectory("acct-1", "Inkstone/Physics", ETag("same", weak = true))),
            nowEpochMillis = 100L,
        )

        assertContentEquals(listOf("Inkstone/Physics"), step.descend)
    }

    @Test
    fun `an unchanged predicate refuses every case where either side is weak or absent`() {
        assertTrue(RemoteWalk.unchanged(ETag("a", weak = false), ETag("a", weak = false)))
        assertFalse(RemoteWalk.unchanged(ETag("a", weak = true), ETag("a", weak = false)))
        assertFalse(RemoteWalk.unchanged(ETag("a", weak = false), ETag("a", weak = true)))
        assertFalse(RemoteWalk.unchanged(null, ETag("a", weak = false)))
        assertFalse(RemoteWalk.unchanged(ETag("a", weak = false), null))
        assertFalse(RemoteWalk.unchanged(ETag("a", weak = false), ETag("b", weak = false)))
    }

    @Test
    fun `a never seen child is descended into`() {
        val step = RemoteWalk.plan(
            accountId = "acct-1",
            path = "Inkstone",
            listing = listOf(collection("Inkstone", "root"), collection("Inkstone/New", "e")),
            cached = emptyMap(),
            nowEpochMillis = 100L,
        )

        assertContentEquals(listOf("Inkstone/New"), step.descend)
    }

    @Test
    fun `a forced walk prunes nothing at all`() {
        // SPEC §7.6 step 4. `nc:data-fingerprint` moved, so an admin restored a backup: every
        // cached ETag now describes a file that went *backwards*, and a match there proves the
        // opposite of what a match usually proves.
        val step = RemoteWalk.plan(
            accountId = "acct-1",
            path = "Inkstone",
            listing = listOf(collection("Inkstone", "root"), collection("Inkstone/Physics", "same")),
            cached = cache(RemoteDirectory("acct-1", "Inkstone/Physics", ETag("same", weak = false))),
            nowEpochMillis = 100L,
            force = true,
        )

        assertTrue(step.pruned.isEmpty())
        assertContentEquals(listOf("Inkstone/Physics"), step.descend)
    }

    @Test
    fun `descent is refused for the DAV root and its children are not pruned either`() {
        // Rule 3. The user's Nextcloud holds their photo library, and a Depth-1-per-changed-child
        // walk from `/` is a crawl of it. The root's *files* are still visited, because a note can
        // legitimately be bound at the top level — and the child collections are left in neither
        // bucket, because "not descended because we may not" must not be recorded as "unchanged".
        val step = RemoteWalk.plan(
            accountId = "acct-1",
            path = "",
            listing = listOf(collection("", "root"), collection("Photos", "p"), file("Loose.pdf")),
            cached = emptyMap(),
            nowEpochMillis = 100L,
            allowDescend = false,
        )

        assertTrue(step.descend.isEmpty(), "descending from the DAV root crawls the user's drive")
        assertTrue(step.pruned.isEmpty(), "a directory we refused to read is not a directory we proved unchanged")
        assertContentEquals(listOf("Loose.pdf"), step.files.map { it.path })
    }

    @Test
    fun `only pdf children are visited`() {
        val step = RemoteWalk.plan(
            accountId = "acct-1",
            path = "Inkstone",
            listing = listOf(
                collection("Inkstone", "root"),
                file("Inkstone/Lecture.pdf"),
                file("Inkstone/Lecture.PDF"),
                file("Inkstone/notes.txt"),
                file("Inkstone/scan.jpeg"),
            ),
            cached = emptyMap(),
            nowEpochMillis = 100L,
        )

        assertContentEquals(
            listOf("Inkstone/Lecture.pdf", "Inkstone/Lecture.PDF"),
            step.files.map { it.path },
            "the extension check must be case-insensitive and must not adopt the user's other files",
        )
    }

    @Test
    fun `the directory that was listed is stamped and its children are not`() {
        // ⚠️ `seenAt` means "this pass WALKED here", and nothing else. A pruned subtree's bindings
        // go unstamped too, so if a pruned directory were stamped as walked the deletion branch
        // would read the notes inside it as absent remotely — a hundred notes in the trash on one
        // lucky ETag match.
        val step = RemoteWalk.plan(
            accountId = "acct-1",
            path = "Inkstone",
            listing = listOf(collection("Inkstone", "root", ocId = "oc-root"), collection("Inkstone/P", "same")),
            cached = cache(RemoteDirectory("acct-1", "Inkstone/P", ETag("same", weak = false))),
            nowEpochMillis = 100L,
        )

        assertEquals(RemoteDirectory("acct-1", "Inkstone", ETag("root", weak = false), "oc-root", 100L), step.self)
        assertContentEquals(listOf("Inkstone/P"), step.pruned)
    }

    // -----------------------------------------------------------------------
    // Where a walk starts
    // -----------------------------------------------------------------------

    @Test
    fun `roots are the parents of bound notes plus the pinned folders plus the default folder`() {
        val roots = RemoteWalk.rootsOf(
            defaultFolder = "Inkstone",
            bindingPaths = listOf("Notes/Physics/Term 2/Lecture.pdf", "Notes/Chemistry/Titration.pdf"),
            pinnedPaths = listOf("Shared/Seminar"),
        )

        assertEquals(
            listOf("Inkstone", "Notes/Chemistry", "Notes/Physics/Term 2", "Shared/Seminar"),
            roots,
            "a pinned folder is the only thing that can see a note a PEER made in a folder we have none in",
        )
    }

    @Test
    fun `a root that contains another root absorbs it`() {
        // Walking `Notes` and `Notes/Physics` separately lists the second twice — and worse, lets
        // the second walk stamp it as seen after the first had pruned it.
        val roots = RemoteWalk.rootsOf(
            defaultFolder = "Notes",
            bindingPaths = listOf("Notes/Physics/a.pdf"),
            pinnedPaths = emptyList(),
        )

        assertEquals(listOf("Notes"), roots)
    }

    @Test
    fun `the DAV root is a root only when something is bound in it and it absorbs nothing`() {
        // ⚠️ `""` is an ancestor of everything. Letting it absorb the others would leave one root
        // that may not be descended into (rule 3) and no root that may — a walk that reads nothing
        // and reports success.
        val withTopLevel = RemoteWalk.rootsOf("Inkstone", listOf("Loose.pdf", "Inkstone/a.pdf"), emptyList())
        assertEquals(listOf("", "Inkstone"), withTopLevel)

        val withoutTopLevel = RemoteWalk.rootsOf("Inkstone", listOf("Inkstone/a.pdf"), emptyList())
        assertEquals(listOf("Inkstone"), withoutTopLevel)
    }

    @Test
    fun `the common ancestor is the one directory a Depth zero probe has to ask about`() {
        assertEquals("Notes", RemoteWalk.commonAncestor(listOf("Notes/Physics", "Notes/Chemistry")))
        assertEquals("Notes/Physics", RemoteWalk.commonAncestor(listOf("Notes/Physics")))
        // Nothing shared: the drive root. Depth:0 on a collection returns that collection and no
        // children, so asking about the root does not read the root.
        assertEquals("", RemoteWalk.commonAncestor(listOf("Inkstone", "Shared/Seminar")))
        assertEquals("", RemoteWalk.commonAncestor(emptyList()))
        assertEquals("", RemoteWalk.commonAncestor(listOf("", "Inkstone")))
    }
}
