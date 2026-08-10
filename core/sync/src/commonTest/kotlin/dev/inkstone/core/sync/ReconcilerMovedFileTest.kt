package dev.inkstone.core.sync

import dev.inkstone.core.model.DocumentId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⭐ SPEC §7.4 rule 2 for the move it cannot see — **the fileid `SEARCH`**.
 *
 * Rule 2 detects a move as *"same `oc:id` + same ETag + a different href"*, and every word of that is
 * about a file the walk **listed**. [RemoteWalk.rootsOf] visits only the account's default folder, the
 * parent of every binding and the pinned folders, because `Depth:1` from `/` is a crawl of the user's
 * photo library — so a note the user drags in the Nextcloud **web UI** into anything outside that set
 * appears in no listing at all, its old directory is walked and *settled* without it, and SPEC §7.6
 * step 3's fourth branch concluded a **remote deletion**. Reported from a real tablet; SPEC §13.2
 * row 11 passed throughout, because its fixture moves the file *within* a walked folder.
 *
 * Its own class rather than a section of [ReconcilerFileTest] because detekt's `LargeClass` said so,
 * and because the subject really is separate: everything here is about what may be concluded from a
 * file's **absence**, which is the one conclusion in the reconciler that destroys something.
 *
 * Four claims, and the last three are the ones that keep the cure from being worse than the disease:
 *
 * 1. a move out of the walked set is followed, with no download;
 * 2. a fileid nothing answers for is still a deletion — SPEC §13.2 row 14 must keep working;
 * 3. a search that **failed** concludes nothing, in either direction;
 * 4. it costs one request per *absent candidate*, and none at all for the two guards that were
 *    already there.
 *
 * ⚠️ The tests in [ReconcilerFileTest] use `oc-42` as an `oc:id`, which has no leading digits and
 * therefore yields no fileid — so they exercise the *fallback* and not this. That is deliberate (they
 * are about other things and their conclusions are unchanged), but it means none of them is evidence
 * about anything below.
 */
class ReconcilerMovedFileTest {

    private val lecture = DocumentId("note-lecture")
    private val seminar = DocumentId("note-seminar")

    /**
     * A tree with one note in it and one folder the walk has no reason to visit.
     *
     * `Outside` is a sibling of the only root, so [RemoteWalk.rootsOf] never lists it — which is the
     * whole premise of this class. Its `oc:id` is a real one (`<padded fileid><instanceid>`, SPEC
     * §7.3) because [RemoteBinding.remoteFileId] reads the fileid out of it.
     */
    private fun outsideTree() = FakeServer()
        .directory("Inkstone", etag = "r2", ocId = "oc-root")
        .directory("Outside", etag = "o1", ocId = "oc-outside")
        .file("Inkstone/Lecture.pdf", etag = "f1", ocId = LECTURE_OC_ID, fileId = LECTURE_FILE_ID)

    @Test
    fun `a note moved outside every walk root follows the file instead of being trashed`() = runTest {
        // ⭐ The bug, reported from a tablet: the note was moved in the Nextcloud web UI into a folder
        // the walk has no reason to visit, so SPEC §7.4 rule 2 — which needs the *new* href in hand —
        // could not fire, the old directory was walked and settled without it, and step 3's fourth
        // branch read that as a remote deletion. The file was untouched on the server one folder away.
        val server = outsideTree()
        val h = ReconcileHarness(server)
        h.bind(lecture, "Inkstone/Lecture.pdf", LECTURE_OC_ID, ETag("f1", weak = false))
        server.moveFile("Inkstone/Lecture.pdf", "Outside/Lecture.pdf")

        val report = h.reconciler.reconcile()

        assertEquals(1, report.moved, "the move was not detected")
        assertEquals(0, report.trashed, "a moved note was read as a deletion")
        assertTrue(h.gate.trashed.isEmpty())
        val binding = assertNotNull(h.bindings.rows[lecture.value])
        assertEquals("Outside/Lecture.pdf", binding.remotePath, "the binding did not follow the file")
        assertEquals(
            ETag("f1", weak = false),
            binding.lastSyncedEtag,
            "a move does not change the ETag, so the stored one is still what an If-Match must send",
        )
        assertEquals(0, h.requests("GET"), "a move is not a download, however it was detected")
        assertEquals(1, h.requests("SEARCH"), "one search per absent candidate — never per file")
        assertTrue(h.attention.rows.isEmpty(), "nothing happened that the user has to decide about")
        // ⭐ The follow-on, and it needs no code: roots are derived from the bindings, so the pass
        // after this one lists `Outside` and reconciles the file there like any other.
        assertTrue(
            "Outside" in RemoteWalk.rootsOf(testAccount.defaultFolder, listOf(binding.remotePath), emptyList()),
            "the new parent is not a walk root, so a move plus an edit would never be read",
        )
    }

    @Test
    fun `a fileid the server cannot find anywhere is still a deletion`() = runTest {
        // The other half: the search must not become a reason never to trash anything. A 207 with an
        // empty multistatus is what a real Nextcloud answers for a fileid with nothing at it —
        // including one whose file is in the server's own trash, which is row 14's premise.
        val server = FakeServer().directory("Inkstone", etag = "r2", ocId = "oc-root")
        val h = ReconcileHarness(server)
        h.bind(lecture, "Inkstone/Lecture.pdf", LECTURE_OC_ID, ETag("f1", weak = false))

        val report = h.reconciler.reconcile()

        assertEquals(1, report.trashed, "a file that is genuinely gone was not trashed")
        assertEquals(listOf(lecture), h.gate.trashed)
        assertEquals(listOf(AttentionKind.REMOTE_DELETED), h.attention.kinds())
    }

    @Test
    fun `a server that cannot search leaves the pass's own conclusion exactly as it was`() = runTest {
        // ⚠️ *"Cannot tell"* must be neither of the two interesting answers. Concluding a deletion
        // from a failed search would let an unsupported method delete notes; keeping the binding
        // anyway would leave the note bound to a path that is not there, syncing nothing, with the
        // poll reporting success. So a non-207 changes nothing at all — the walk had already decided.
        val server = FakeServer().directory("Inkstone", etag = "r2", ocId = "oc-root").refusingSearch()
        val h = ReconcileHarness(server)
        h.bind(lecture, "Inkstone/Lecture.pdf", LECTURE_OC_ID, ETag("f1", weak = false))

        val report = h.reconciler.reconcile()

        assertEquals(1, h.requests("SEARCH"), "the fallback was not reached through a real refusal")
        assertEquals(0, report.failed, "a search this server cannot do is not a failure of the pass")
        assertEquals(1, report.trashed, "the conclusion the walk reached was abandoned over a failed search")
        assertEquals(listOf(lecture), h.gate.trashed)
    }

    @Test
    fun `a hit whose oc id does not match repaths nothing and concludes no deletion`() = runTest {
        // ⚠️ `remoteFileId` reads digits off a string, so a hit has to be *identified* before a
        // binding is pointed at it — otherwise a mis-derived fileid repaths the note onto a
        // stranger's file and the next UPLOAD lands on top of it. An unidentifiable hit is therefore
        // "cannot tell" and **not** "gone": only an empty answer is gone.
        val server = FakeServer()
            .directory("Inkstone", etag = "r2", ocId = "oc-root")
            // Same fileid, a different `oc:id` — which is what a server that will not select `oc:id`,
            // or a fileid we derived wrongly, looks like from here.
            .file("Elsewhere/Stranger.pdf", etag = "x1", ocId = "00000042otherinstance", fileId = LECTURE_FILE_ID)
        val h = ReconcileHarness(server)
        h.bind(lecture, "Inkstone/Lecture.pdf", LECTURE_OC_ID, ETag("f1", weak = false))

        h.reconciler.reconcile()

        assertEquals(
            "Inkstone/Lecture.pdf",
            assertNotNull(h.bindings.rows[lecture.value]).remotePath,
            "the binding was pointed at a file that is not this note's",
        )
    }

    @Test
    fun `a moved note an editor holds follows the file without ever being offered to the trash`() = runTest {
        // ⭐ Why the search runs **before** the gate. `NoteSessionHost` refuses the trash while an
        // editor holds the note, and the refusal defers for ever — which is *"the app still says
        // SYNCED and nothing happens"*, the shape this was reported in. A fix that only asked after a
        // successful trash would leave exactly that case broken.
        val server = outsideTree()
        val h = ReconcileHarness(server, trashes = { false })
        h.bind(lecture, "Inkstone/Lecture.pdf", LECTURE_OC_ID, ETag("f1", weak = false))
        server.moveFile("Inkstone/Lecture.pdf", "Outside/Lecture.pdf")

        val report = h.reconciler.reconcile()

        assertEquals(1, report.moved)
        assertTrue(h.gate.refusedTrash.isEmpty(), "the gate was asked about a note that had not been deleted")
        assertEquals("Outside/Lecture.pdf", assertNotNull(h.bindings.rows[lecture.value]).remotePath)
    }

    @Test
    fun `a pruned subtree and a never-uploaded note cost no search at all`() = runTest {
        // ⭐ The cost claim, and the reason the search sits *behind* `isAbsentRemotely` rather than in
        // front of it. Both guards leave a binding unstamped exactly as a deleted file does: a pruned
        // subtree because nobody looked, and a never-uploaded note because there is nothing up there
        // to have lost. Asking first would be one SEARCH per note in the library every time anything
        // anywhere in it changed — which is the request amplification SPEC §7.2's neighbourhood exists
        // to prevent.
        val server = FakeServer()
            .directory("Inkstone", etag = "r2", ocId = "oc-root")
            .directory("Inkstone/Archive", etag = "a1", ocId = "oc-archive")
            .file("Inkstone/Archive/Old.pdf", etag = "f1", ocId = LECTURE_OC_ID, fileId = LECTURE_FILE_ID)
        val h = ReconcileHarness(server)
        h.bind(lecture, "Inkstone/Archive/Old.pdf", LECTURE_OC_ID, ETag("f1", weak = false))
        h.bindings.upsert(RemoteBinding(seminar, testAccount.id, "Inkstone/Seminar.pdf"))
        h.cache("Inkstone/Archive", etag = "a1")

        val report = h.reconciler.reconcile()

        assertEquals(1, report.pruned, "the fixture did not prune, so this proves nothing")
        assertEquals(0, report.trashed)
        assertEquals(0, h.requests("SEARCH"), "a search was spent on a note nobody had looked for")
    }

    private companion object {
        /** SPEC §7.3's shape: eight padded digits then the instanceid, which always begins `oc`. */
        const val LECTURE_OC_ID = "00000042ocinstance"

        /** What a `SEARCH` matches, and what [RemoteBinding.remoteFileId] has to read out of the above. */
        const val LECTURE_FILE_ID = 42L
    }
}
