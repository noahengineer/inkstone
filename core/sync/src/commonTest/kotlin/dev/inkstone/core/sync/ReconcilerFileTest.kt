package dev.inkstone.core.sync

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.storage.MergeReport
import dev.inkstone.core.storage.NoteLayout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SPEC §7.6 step 3's four branches, SPEC §7.7's three layers, and the outbox decision table.
 *
 * The half of the reconciler that decides **what happens to a note**. Everything asserted here is
 * something that fails silently if it is wrong: a note that never uploads, two devices uploading
 * each other's merges forever, a torn PDF, a stranger's document adopted under one of our ids.
 */
class ReconcilerFileTest {

    private val lecture = DocumentId("note-lecture")
    private val seminar = DocumentId("note-seminar")
    private val peerNote = DocumentId("note-from-peer")

    private fun tree(fileEtag: String = "f2", md5: String? = null, rootEtag: String = "r2") = FakeServer()
        .directory("Inkstone", etag = rootEtag, ocId = "oc-root")
        .file("Inkstone/Lecture.pdf", etag = fileEtag, ocId = "oc-42", md5 = md5)

    /**
     * Poll again [times], with the note's **folder** having moved each time.
     *
     * ⚠️ Not a decoration, and a test that just calls `reconcile()` in a loop asserts far less than it
     * looks like. A pass that left nothing unfinished caches the directory's ETag, so the next poll
     * prunes the whole subtree and never reaches the file at all — three reconciles then prove only
     * that SPEC §7.4 rule 1 works. What moves a directory's ETag in practice is any note inside it
     * being uploaded, which is to say every save; and what layer 3 deliberately does *not* advance is
     * the **file's** ETag, so the file is met again on every one of those polls for as long as the
     * condition lasts. That is the premise of everything below.
     */
    private suspend fun ReconcileHarness.pollAgain(times: Int) {
        repeat(times) { pass ->
            server.directory("Inkstone", etag = "r-again-$pass", ocId = "oc-root")
            reconciler.reconcile()
        }
    }

    // -----------------------------------------------------------------------
    // SPEC §7.4 rule 2 — moved
    // -----------------------------------------------------------------------

    @Test
    fun `a moved file repaths the binding and downloads nothing`() = runTest {
        // SPEC §7.4 rule 2 — a file's ETag does not change when it moves. Same oc:id, same ETag,
        // new href is a move, and re-downloading it would be a whole PDF spent learning nothing.
        val server = FakeServer()
            .directory("Inkstone", etag = "r2", ocId = "oc-root")
            .file("Inkstone/Renamed.pdf", etag = "f1", ocId = "oc-42")
        val h = ReconcileHarness(server)
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))

        val report = h.reconciler.reconcile()

        assertEquals(1, report.moved)
        assertEquals("Inkstone/Renamed.pdf", h.bindings.rows.getValue(lecture.value).remotePath)
        assertEquals(0, h.requests("GET"), "a move is not a download")
        assertTrue(h.gate.merges.isEmpty())
    }

    @Test
    fun `a remote edit at a new path repaths the binding too`() = runTest {
        // ⚠️ A rename changes the title *inside* the bundle and the filename *outside* it, so the
        // peer's pull sees a new ETag at a new href — the ordinary case for a rename, not an edge
        // one. The move branch above is skipped (the ETag moved) and `RemoteVersion` carries no
        // path, so this used to advance the ETag and leave the binding aimed at a file that is no
        // longer there: the note's next MOVE 404s, parks for a day, and takes every later UPLOAD of
        // that note with it, because MOVE drains before UPLOAD for one note. Found by SPEC §7.7
        // layer 2's matrix row, in the second direction.
        val server = FakeServer()
            .directory("Inkstone", etag = "r2", ocId = "oc-root")
            .file("Inkstone/Thermodynamics.pdf", etag = "f2", ocId = "oc-42")
        val h = ReconcileHarness(server, verdict = { normal(lecture) })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))

        val report = h.reconciler.reconcile()

        assertEquals(1, report.merged)
        assertEquals(
            "Inkstone/Thermodynamics.pdf",
            h.bindings.rows.getValue(lecture.value).remotePath,
            "the binding still points at the old href, so this note's next MOVE and PUT go nowhere",
        )
    }

    @Test
    fun `a pending local rename keeps its destination and has its source corrected`() = runTest {
        // The other half of following the file. `remotePath` is where the note *should* be and
        // `movedFromPath` is where it still *is* (SPEC §7.6), so a pull that overwrote the first
        // would silently cancel a rename the user has already been shown in the library, and one
        // that left the second on the old href is the 404 above.
        val server = FakeServer()
            .directory("Inkstone", etag = "r2", ocId = "oc-root")
            .file("Inkstone/Renamed by the peer.pdf", etag = "f2", ocId = "oc-42")
        val h = ReconcileHarness(server, verdict = { normal(lecture) })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))
        // What a local rename leaves behind: a MOVE queued from where the file was last seen.
        h.engine.notePathChanged(lecture, "Renamed here")
        val wanted = h.bindings.rows.getValue(lecture.value).remotePath

        h.reconciler.reconcile()

        val binding = h.bindings.rows.getValue(lecture.value)
        assertEquals(wanted, binding.remotePath, "the pull cancelled a rename this device had already made")
        assertEquals(
            "Inkstone/Renamed by the peer.pdf",
            binding.movedFromPath,
            "the queued MOVE would run from a file that is not there",
        )
    }

    @Test
    fun `a move into a mirrored directory moves the note in the library too`() = runTest {
        // ROADMAP M6's "folder mirroring both directions", pull half. A move into a directory
        // nothing mirrors leaves the library alone — inventing a folder there would be the
        // reconciler reorganising somebody's library.
        val folder = FolderId("folder-physics")
        val server = FakeServer()
            .directory("Inkstone", etag = "r2", ocId = "oc-root")
            .directory("Inkstone/Physics", etag = "p1", ocId = "oc-phys")
            .file("Inkstone/Physics/Lecture.pdf", etag = "f1", ocId = "oc-42")
        val h = ReconcileHarness(server)
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))
        h.folders.upsert(FolderBinding(folder, testAccount.id, "Inkstone/Physics", remoteOcId = "oc-phys"))

        h.reconciler.reconcile()

        assertEquals(listOf(lecture to folder), h.gate.refoldered)
    }

    @Test
    fun `a stripped file id is repaired from the path rather than adopted as a second copy`() = runTest {
        // ⚠️ A reverse proxy that strips `OC-FileId` leaves a successfully uploaded note with a
        // null `remoteOcId`. Without SPEC §7.4's secondary key the reconciler meets its own note
        // as a stranger's and adopts a second copy of it — SPEC §5.6's duplication failure, in
        // reverse, on every poll.
        val h = ReconcileHarness(tree(fileEtag = "f1"))
        h.bind(lecture, "Inkstone/Lecture.pdf", ocId = null, etag = ETag("f1", weak = false))

        val report = h.reconciler.reconcile()

        assertEquals(0, report.adopted, "our own note must not be adopted as somebody else's")
        assertEquals("oc-42", h.bindings.rows.getValue(lecture.value).remoteOcId, "the oc:id is repaired for free")
        assertEquals(0, h.requests("GET"))
    }

    // -----------------------------------------------------------------------
    // SPEC §7.3 — the weak-ETag fallback
    // -----------------------------------------------------------------------

    @Test
    fun `a weak stored ETag falls back to comparing the checksum`() = runTest {
        // SPEC §7.3: "treat the ETag as advisory and compare checksums instead of using If-Match."
        // Equal checksums mean equal *bytes*, which is a stronger statement than the ETag would
        // have made even if it had been usable — so this is not a downgrade, it is the fallback
        // working.
        val h = ReconcileHarness(tree(fileEtag = "f9", md5 = "abc123"))
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = true), checksum = "abc123")

        val report = h.reconciler.reconcile()

        assertEquals(0, h.requests("GET"), "equal checksums mean equal bytes; there is nothing to fetch")
        assertEquals(0, report.merged)
    }

    @Test
    fun `a weak stored ETag with a different checksum does download`() = runTest {
        val h = ReconcileHarness(tree(fileEtag = "f9", md5 = "different"), verdict = { normal(lecture) })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = true), checksum = "abc123")

        val report = h.reconciler.reconcile()

        assertEquals(1, h.requests("GET"))
        assertEquals(1, report.merged)
    }

    // -----------------------------------------------------------------------
    // ⭐ The outbox decision table — SPEC §7.6, and the ping-pong's termination argument
    // -----------------------------------------------------------------------

    @Test
    fun `a merge with nothing novel on either side advances the ETag and enqueues no upload`() = runTest {
        // Row 1. This is what a poll finds when the remote ETag moved because *this* device
        // uploaded. Uploading here is the echo loop: two devices push each other's merges forever
        // at the poll interval, and it looks exactly like healthy sync.
        val h = ReconcileHarness(tree(), verdict = { normal(lecture) })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false), localRev = 1, syncedRev = 1)

        val report = h.reconciler.reconcile()

        assertEquals(1, report.merged)
        assertEquals(0, h.outbox.count(), "no upload is owed when neither side had anything novel")
        assertEquals(ETag("f2", weak = false), h.bindings.rows.getValue(lecture.value).lastSyncedEtag)
        assertEquals(NoteSyncState.SYNCED, h.notes.states[lecture.value])
    }

    @Test
    fun `a merge that only took remote ops advances the revision and still enqueues no upload`() = runTest {
        // Row 2. The local note is now the remote's superset-of-nothing; re-uploading only churns
        // the ETag and wakes the peer up for nothing.
        val h = ReconcileHarness(
            tree(),
            verdict = { normal(lecture) },
            report = { FakeMergeGate.defaultMerged(remoteNovelOps = 12) },
            mergedRev = 7L,
        )
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false), localRev = 1, syncedRev = 1)

        h.reconciler.reconcile()

        val binding = h.bindings.rows.getValue(lecture.value)
        assertEquals(7L, binding.lastSyncedRev, "the merged revision is what the server's bytes now correspond to")
        assertEquals(ETag("f2", weak = false), binding.lastSyncedEtag)
        assertEquals(0, h.outbox.count())
        assertEquals(NoteSyncState.SYNCED, h.notes.states[lecture.value])
    }

    @Test
    fun `a merge with local edits advances the ETag first and then enqueues exactly one upload`() = runTest {
        // Row 3, and the ordering inside it. The ETag must be stored **before** the enqueue: an
        // UPLOAD queued against the stale ETag is a guaranteed 412, which turns one merge into an
        // unbounded 412-reconcile-412 cycle instead of the two round trips the design promises.
        //
        // And `lastSyncedRev` must NOT move — the local note is ahead of the bytes on the server,
        // and saying otherwise is SPEC §7.6's mid-flight bug one layer up.
        val h = ReconcileHarness(
            tree(),
            verdict = { normal(lecture) },
            report = { FakeMergeGate.defaultMerged(remoteNovelOps = 3) },
            mergedRev = 9L,
        )
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false), localRev = 5, syncedRev = 1)
        h.notes.setRev(lecture, 9L)

        h.reconciler.reconcile()

        val binding = h.bindings.rows.getValue(lecture.value)
        assertEquals(ETag("f2", weak = false), binding.lastSyncedEtag)
        assertEquals(1L, binding.lastSyncedRev, "the note is ahead of the server; it is not synced")
        val row = assertNotNull(h.outbox.upload(lecture), "local work must still reach the server")
        assertEquals(9L, row.localRevAtEnqueue)
        assertEquals(1, h.outbox.count(), "exactly one upload, not one per merged page")
        assertEquals(NoteSyncState.PENDING_UPLOAD, h.notes.states[lecture.value])
    }

    @Test
    fun `the peer clock is recorded so the compaction horizon stops being null`() = runTest {
        // SPEC §4.7. `Compaction.isAllowed(oldestAckedCounter = …)` has been passed null since M2
        // because nothing knew what a peer had seen. A downloaded bundle says so.
        val h = ReconcileHarness(tree(), verdict = { normal(lecture) })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))

        h.reconciler.reconcile()

        assertEquals(41L, h.peers.oldestAckedCounter(lecture))
    }

    // -----------------------------------------------------------------------
    // The download — CLAUDE.md rule 10
    // -----------------------------------------------------------------------

    @Test
    fun `the download lands in the staging directory and never over document pdf`() = runTest {
        // ⚠️ `NoteLayout.download` documents the three failures that collapse into this one rule,
        // and the first of them is a torn ZIP inside a PDF atomically renamed into place — because
        // `writeBytesAtomically` derives its temp name from the destination, so a concurrent
        // materialization of the same note shares one temp file with the download.
        val h = ReconcileHarness(tree(), verdict = { normal(lecture) })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))

        h.reconciler.reconcile()

        assertEquals(listOf(NoteLayout.download(lecture)), h.extractor.calls)
        assertTrue(
            NoteLayout.download(lecture).startsWith("${NoteLayout.DOWNLOAD_DIR}/"),
            "the staged path must be outside notes/<id>/",
        )
        assertFalse(h.fs.exists(NoteLayout.documentPdf(lecture)), "document.pdf is the materializer's, not ours")
    }

    @Test
    fun `the ETag stored after a merge is the one that came back with the bytes`() = runTest {
        // ⚠️ Not the one the PROPFIND advertised. They differ whenever a peer saved between the two
        // calls, and storing the PROPFIND's would leave the binding claiming a version this device
        // has never held — after which the next `If-Match` upload 412s against bytes nobody can
        // reproduce. This is the entire reason `download()` exists and `get()` was not enough.
        val server = FakeServer()
            .directory("Inkstone", etag = "r2", ocId = "oc-root")
            .file("Inkstone/Lecture.pdf", etag = "advertised", ocId = "oc-42", getEtag = "actually-received")
        val h = ReconcileHarness(server, verdict = { normal(lecture) })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))

        h.reconciler.reconcile()

        assertEquals(
            ETag("actually-received", weak = false),
            h.bindings.rows.getValue(lecture.value).lastSyncedEtag,
        )
    }

    @Test
    fun `a download interrupted before its merge is resumed rather than fetched again`() = runTest {
        // SPEC §13.3 — a process kill between the download and the merge. The `pendingDownload`
        // row is written before the merge and removed only after it, so the staged file is still
        // there and still current. Re-fetching a 40 MB lecture is the difference between a pass
        // finishing and WorkManager killing the job for it.
        val h = ReconcileHarness(tree(), verdict = { normal(lecture) })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))
        h.fs.writeBytesAtomically(NoteLayout.download(lecture), "already here".encodeToByteArray())
        h.downloads.upsert(
            PendingDownload(
                noteId = lecture,
                accountId = testAccount.id,
                remotePath = "Inkstone/Lecture.pdf",
                remoteOcId = "oc-42",
                remoteEtag = ETag("f2", weak = false),
                tempPath = NoteLayout.download(lecture),
                fetchedAtEpochMillis = 1L,
            ),
        )

        val report = h.reconciler.reconcile()

        assertEquals(0, h.requests("GET"), "the staged file is already the version the server has")
        assertEquals(1, report.merged)
        assertTrue(h.downloads.rows.isEmpty(), "the row is only cleared once the merge succeeded")
    }

    @Test
    fun `a stale staged download is fetched again rather than merged`() = runTest {
        // The remote moved on since the interrupted pass. Merging the old staging file would apply
        // a version the server no longer has, and the next upload would overwrite the newer one.
        val h = ReconcileHarness(tree(fileEtag = "f3"), verdict = { normal(lecture) })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))
        h.fs.writeBytesAtomically(NoteLayout.download(lecture), "stale".encodeToByteArray())
        h.downloads.upsert(
            PendingDownload(
                noteId = lecture,
                accountId = testAccount.id,
                remotePath = "Inkstone/Lecture.pdf",
                remoteOcId = "oc-42",
                remoteEtag = ETag("f2", weak = false),
                tempPath = NoteLayout.download(lecture),
                fetchedAtEpochMillis = 1L,
            ),
        )

        h.reconciler.reconcile()

        assertEquals(1, h.requests("GET"))
        assertEquals(ETag("f3", weak = false), h.bindings.rows.getValue(lecture.value).lastSyncedEtag)
    }

    @Test
    fun `a successful merge clears the pending download row and its file`() = runTest {
        // The row is the only record of the staged file's name, so the file must go first. A row
        // left behind claims a version the note does not have; a file left behind is a lecture PDF
        // nothing can name.
        val h = ReconcileHarness(tree(), verdict = { normal(lecture) })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))

        h.reconciler.reconcile()

        assertTrue(h.downloads.rows.isEmpty())
        assertFalse(h.fs.exists(NoteLayout.download(lecture)))
    }

    // -----------------------------------------------------------------------
    // SPEC §7.6 step 3, third branch — adopt
    // -----------------------------------------------------------------------

    @Test
    fun `a stranger's pdf is ignored entirely with no library row and no attention row`() = runTest {
        // ⚠️ "A PDF with ink annotations and no embedded bundle is not necessarily ours — it might
        // be a stranger's Acrobat markup" (SPEC §5.6). Adopting one puts a foreign document under
        // one of our ids, where it starts merging with whatever else has that id.
        val server = FakeServer()
            .directory("Inkstone", etag = "r2", ocId = "oc-root")
            .file("Inkstone/Scanned contract.pdf", etag = "s1", ocId = "oc-99")
        val h = ReconcileHarness(server, verdict = { foreign() })

        val report = h.reconciler.reconcile()

        assertEquals(1, report.ignored)
        assertEquals(0, report.adopted)
        assertTrue(h.gate.installed.isEmpty())
        assertTrue(h.gate.conflicted.isEmpty())
        assertTrue(h.attention.rows.isEmpty(), "a file that is not ours is not a problem to review")
        assertTrue(h.bindings.rows.isEmpty())
    }

    @Test
    fun `a peer's note is installed bound and staged outside the note directory`() = runTest {
        val server = FakeServer()
            .directory("Inkstone", etag = "r2", ocId = "oc-root")
            .file("Inkstone/Seminar.pdf", etag = "s1", ocId = "oc-77")
        val h = ReconcileHarness(server, verdict = { normal(peerNote) })

        val report = h.reconciler.reconcile()

        assertEquals(1, report.adopted)
        val install = h.gate.installed.single()
        assertEquals(peerNote, install.noteId, "identity comes from the marker, never from the filename")
        assertEquals("Inkstone/Seminar.pdf", install.remotePath)
        assertTrue(install.pdfPath.startsWith("${NoteLayout.DOWNLOAD_DIR}/"))
        assertEquals("Inkstone/Seminar.pdf", h.bindings.rows.getValue(peerNote.value).remotePath)
    }

    @Test
    fun `a peer file whose note we already hold repairs the binding and merges next pass`() = runTest {
        // The bound-but-unrecognised case: our own note, whose `oc:id` a proxy stripped *and*
        // whose path changed, so neither key found it. Repair rather than adopt, and defer — which
        // deliberately leaves the directory uncached so the next pass actually looks.
        val server = FakeServer()
            .directory("Inkstone", etag = "r2", ocId = "oc-root")
            .file("Inkstone/Moved.pdf", etag = "s1", ocId = "oc-77")
        val h = ReconcileHarness(server, verdict = { normal(lecture) })
        h.bind(lecture, "Inkstone/Elsewhere.pdf", ocId = null, etag = ETag("f1", weak = false))

        val report = h.reconciler.reconcile()

        assertEquals(0, report.adopted)
        assertEquals("Inkstone/Moved.pdf", h.bindings.rows.getValue(lecture.value).remotePath)
        assertEquals("oc-77", h.bindings.rows.getValue(lecture.value).remoteOcId)
        assertTrue(report.shouldRunAgain, "the merge has not happened yet")
        assertTrue(h.directories.rows.isEmpty(), "an unfinished directory must not be cached")
        assertEquals(0, report.trashed, "the note we just repaired is not absent remotely")
    }

    // -----------------------------------------------------------------------
    // SPEC §7.7 layer 3
    // -----------------------------------------------------------------------

    @Test
    fun `a too new bundle keeps both copies and never advances the ETag`() = runTest {
        // SPEC §5.6 row 4 / §5.8. Not advancing the ETag is what makes the pending UPLOAD behind
        // this note 412 at the drainer and park, instead of overwriting a file written by a build
        // that understands more than this one does.
        val h = ReconcileHarness(tree(), verdict = { tooNew(lecture, schemaVersion = 3) })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))

        val report = h.reconciler.reconcile()

        assertEquals(1, report.conflicted)
        assertTrue(h.gate.merges.isEmpty(), "a file from the future must not be opened, let alone re-saved")
        assertEquals(
            ETag("f1", weak = false),
            h.bindings.rows.getValue(lecture.value).lastSyncedEtag,
            "advancing the ETag here would licence an upload that destroys the newer data",
        )
        assertEquals(NoteSyncState.CONFLICTED, h.notes.states[lecture.value])
        assertEquals(listOf(AttentionKind.REMOTE_TOO_NEW), h.attention.kinds())
        assertEquals("3", h.attention.rows.values.single().detail, "the schema version is the actionable part")
    }

    @Test
    fun `a too new bundle offers no local copy because nothing in it can be opened`() = runTest {
        val h = ReconcileHarness(tree(), verdict = { tooNew(lecture) })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))

        h.reconciler.reconcile()

        // The peer's bytes are preserved by being left exactly where they are — untouched on the
        // server, at the path the attention row carries. There is no bundle this build can read,
        // so there is no note to make; recovering it would rewrite a newer format in this build's
        // vocabulary under the original documentId and win the next sync with it.
        assertNull(h.gate.conflicted.single().bundlePath)
    }

    @Test
    fun `a bundle naming a different note becomes a conflicted copy rather than a merge`() = runTest {
        // SPEC §5.6 inverted. The marker's documentId is identity, so a file at this note's path
        // carrying somebody else's is two notes, and merging them would fuse two documents.
        val h = ReconcileHarness(tree(), verdict = { normal(DocumentId("someone-else")) })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))

        val report = h.reconciler.reconcile()

        assertEquals(1, report.conflicted)
        assertTrue(h.gate.merges.isEmpty())
        val copy = h.gate.conflicted.single()
        assertEquals(lecture, copy.localNoteId)
        assertNotNull(copy.bundlePath, "this one IS openable, so the user gets a note they can read")
        assertTrue(
            copy.title.startsWith("Lecture (conflicted copy "),
            "the name must be the shared one both devices compute: got ${copy.title}",
        )
    }

    @Test
    fun `a conflicted copy is bound so the outbox uploads it and nothing is PUT from here`() = runTest {
        // CLAUDE.md rule 4 has no layer-3 exemption. Binding gets the server-side copy for free,
        // through the one code path that knows how to create a file without clobbering one.
        val h = ReconcileHarness(tree(), verdict = { normal(DocumentId("someone-else")) })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))

        h.reconciler.reconcile()

        assertEquals(0, h.requests("PUT"), "the reconciler performs no remote writes")
        val copyId = h.gate.minted.single()
        val binding = assertNotNull(h.bindings.rows[copyId.value], "the copy must be queued for the server too")
        assertTrue(binding.remotePath.startsWith("Inkstone/Lecture (conflicted copy "))
        assertFalse(binding.everUploaded, "a fresh copy uploads as a create — If-None-Match: *")
    }

    @Test
    fun `a merge the gate refuses as too new goes to layer three with the local note untouched`() = runTest {
        // The extractor said NORMAL and the gate disagreed — `BundleReader` sees the manifest's
        // schemaVersion, the XMP marker does not have to. Both roads lead to layer 3.
        val h = ReconcileHarness(
            tree(),
            verdict = { normal(lecture) },
            report = { MergeReport.TooNew(remoteVersion = 4, understoodVersion = 1) },
        )
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false), localRev = 5, syncedRev = 1)

        val report = h.reconciler.reconcile()

        assertEquals(1, report.conflicted)
        assertEquals(ETag("f1", weak = false), h.bindings.rows.getValue(lecture.value).lastSyncedEtag)
        assertEquals(1L, h.bindings.rows.getValue(lecture.value).lastSyncedRev)
        assertEquals(listOf(AttentionKind.REMOTE_TOO_NEW), h.attention.kinds())
    }

    // -----------------------------------------------------------------------
    // Download — CLAUDE.md rule 10, and what a failed GET may not claim
    // -----------------------------------------------------------------------

    @Test
    fun `a download that fails does not leave the note claiming a pending download`() = runTest {
        // ⭐ `PENDING_DOWNLOAD` means *"bytes are on disk and a merge is owed"* — which is exactly
        // what the `pendingDownload` row beside it means, so the two belong to the same moment. It
        // was set **before** the GET, i.e. as a claim about the future, and nothing on the failure
        // path took it back: a dropped connection or a 503 left the note stuck in it until some
        // later pass happened to succeed. Two consequences, neither an error anywhere: the library
        // tile says "downloading" indefinitely, and SPEC §4.7's compaction gate refuses to compact
        // the note for as long as it does — so a note whose server is briefly down also stops
        // compacting.
        val server = tree()
            .refusing("Inkstone/Lecture.pdf", HttpStatusCode.ServiceUnavailable)
        val h = ReconcileHarness(server)
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))
        h.notes.setSyncState(lecture, NoteSyncState.SYNCED, null)

        val report = h.reconciler.reconcile()

        assertEquals(1, report.failed, "the failed GET was not reported as a failure")
        assertEquals(
            NoteSyncState.SYNCED,
            h.notes.states[lecture.value],
            "a failed download left the note claiming a download it does not have",
        )
        assertNull(h.downloads.rows[lecture.value], "a pendingDownload row was written for bytes that never arrived")
        // And the binding is untouched, so the next pass fetches again rather than believing it
        // already holds this version.
        assertEquals(ETag("f1", weak = false), h.bindings.rows.getValue(lecture.value).lastSyncedEtag)
    }

    @Test
    fun `a download that succeeds records the pending row and the state together`() = runTest {
        // The other half of the pair: the state and the row are one moment, so a process kill
        // between the download and the merge is resumable (SPEC §13.3) and the binding never claims
        // a version the local note does not have. `FakeMergeGate` answers `DifferentDocument` for a
        // foreign id, which is layer 3 — chosen here because it is the one outcome that leaves the
        // download staged instead of consuming it, so both can be observed after one pass.
        val h = ReconcileHarness(tree(), verdict = { normal(DocumentId("someone-else")) })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))

        h.reconciler.reconcile()

        assertEquals(1, h.requests("GET"))
        assertEquals(NoteSyncState.CONFLICTED, h.notes.states[lecture.value], "layer 3 did not flag the note")
    }

    // -----------------------------------------------------------------------
    // SPEC §7.6 step 3, fourth branch — deleted remotely
    // -----------------------------------------------------------------------

    @Test
    fun `a file gone from a walked directory is trashed and its queued upload is not annihilated`() = runTest {
        // ⚠️ SPEC §13.2 row 14. `SyncEngine.noteTrashed` would enqueue a remote DELETE, and
        // `OutboxCoalescing` makes a DELETE **annihilate the pending UPLOAD** — destroying the only
        // queued route the user's unsynced edits had to the server, through a code path whose
        // comments all read correct. `noteDeletedRemotely` exists to not be that.
        val server = FakeServer().directory("Inkstone", etag = "r2", ocId = "oc-root")
        val h = ReconcileHarness(server)
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false), localRev = 9, syncedRev = 1)
        h.engine.noteChanged(lecture)
        assertNotNull(h.outbox.upload(lecture), "the fixture did not queue the edits this row is about")

        val report = h.reconciler.reconcile()

        assertEquals(1, report.trashed)
        assertEquals(listOf(lecture), h.gate.trashed, "the local copy goes to the trash, never a hard delete")
        assertEquals(
            0,
            h.outbox.rows.count { it.key.second == OutboxOpType.DELETE },
            "this device did not delete anything; enqueuing a DELETE would be the annihilating bug",
        )
        // The binding survives with its remote identity cleared, which is what makes a restore a
        // create rather than a permanent 412 — and what stops the next pass concluding the same
        // deletion all over again.
        val binding = assertNotNull(h.bindings.rows[lecture.value])
        assertFalse(binding.everUploaded)
        assertEquals("Inkstone/Lecture.pdf", binding.remotePath, "where it lived is worth remembering")
        assertEquals(listOf(AttentionKind.REMOTE_DELETED), h.attention.kinds())
    }

    @Test
    fun `a note that was never uploaded is not trashed when its directory is empty`() = runTest {
        // SPEC §7.6: the remote DELETE only ever ran for a note that was uploaded, and by the same
        // logic a note that was never up there cannot have been deleted from up there. Without
        // this, binding a note offline and polling once puts it straight in the trash.
        val server = FakeServer().directory("Inkstone", etag = "r2", ocId = "oc-root")
        val h = ReconcileHarness(server)
        h.bindings.upsert(RemoteBinding(lecture, testAccount.id, "Inkstone/Lecture.pdf"))

        val report = h.reconciler.reconcile()

        assertEquals(0, report.trashed)
        assertTrue(h.gate.trashed.isEmpty())
    }

    @Test
    fun `a note an editor holds is not trashed and the next pass concludes it again`() = runTest {
        // ⭐ CLAUDE.md rule 1, and the worst path M6's review found. `NoteTrash.trash` *moves*
        // `notes/<id>/`, and a live `NoteSession` is not told: its next autosave appends to
        // `journal.ops` at the old path and recreates the directory with no bundle in it, after which
        // the note is unrestorable, unopenable, and destroyed by the trash sweeper at day 30. So the
        // gate refuses while an editor holds the note, and a refusal must not be mistaken for a
        // deletion — the binding keeps its identity, the queued upload survives, and no row claims
        // something happened.
        val server = FakeServer().directory("Inkstone", etag = "r2", ocId = "oc-root")
        val h = ReconcileHarness(server, trashes = { false })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false), localRev = 9, syncedRev = 1)
        h.engine.noteChanged(lecture)

        val report = h.reconciler.reconcile()

        assertEquals(listOf(lecture), h.gate.refusedTrash, "the gate must be asked, and its answer honoured")
        assertEquals(0, report.trashed)
        assertTrue(h.gate.trashed.isEmpty())
        val binding = assertNotNull(h.bindings.rows[lecture.value], "nothing may be unbound over a deferral")
        assertEquals("oc-42", binding.remoteOcId)
        assertNotNull(h.outbox.upload(lecture), "noteDeletedRemotely drops the outbox rows; it must not run")
        assertTrue(h.attention.rows.isEmpty(), "no row may claim a deletion that has not happened")
        assertTrue(report.shouldRunAgain, "the pass after the editor lets go has to reach the same conclusion")
        assertTrue(h.directories.rows.isEmpty(), "and it cannot, if this directory is cached as settled")
    }

    @Test
    fun `a directory the pass limit cut short is not read as a mass deletion`() = runTest {
        // ⭐ The mass-trash path on any library bigger than the limit — which `ReconcileWorker` always
        // uses, because it calls `reconcile()` with no argument. The file loop breaks on the budget
        // and the directory is marked unfinished, but `isAbsentRemotely` asked only whether the
        // parent had been *walked*: so every bound note past the cut was trashed, unbound, stripped
        // of its queued upload and given a "Deleted on the server" row, with its file untouched on
        // the server. A device offline for a week meets this on the first poll.
        val server = FakeServer()
            .directory("Inkstone", etag = "r2", ocId = "oc-root")
            .file("Inkstone/Lecture.pdf", etag = "f2", ocId = "oc-42")
            .file("Inkstone/Seminar.pdf", etag = "s2", ocId = "oc-77")
        val h = ReconcileHarness(server, verdict = { normal(lecture) })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))
        h.bind(seminar, "Inkstone/Seminar.pdf", "oc-77", ETag("s1", weak = false))

        val report = h.reconciler.reconcile(limit = 1)

        assertEquals(1, report.merged, "the budget is spent on the first file")
        assertEquals(0, report.trashed, "nobody looked at the second one; that is not a deletion")
        assertTrue(h.gate.trashed.isEmpty())
        assertEquals(
            ETag("s1", weak = false),
            h.bindings.rows.getValue(seminar.value).lastSyncedEtag,
            "and it keeps its remote identity, so the next pass merges rather than adopting a copy",
        )
        assertTrue(report.shouldRunAgain, "the file the limit stopped at is still owed")
        assertTrue(h.directories.rows.isEmpty(), "a directory holding an unread file is not settled")
    }

    // -----------------------------------------------------------------------
    // SPEC §5.6, negatively — a PDF that is not ours, remembered
    // -----------------------------------------------------------------------

    @Test
    fun `a pdf that is not ours is remembered and not downloaded twice`() = runTest {
        // ⚠️ Identity lives in the file (SPEC §5.6), so answering "is this ours" costs the whole
        // download — and a directory's ETag moves every time any note in it uploads. Nothing
        // remembered the answer, so a 40 MB handout beside the notes was re-fetched on every note
        // save, inside a WorkManager job, on whatever connection the tablet had.
        val server = FakeServer()
            .directory("Inkstone", etag = "r2", ocId = "oc-root")
            .file("Inkstone/Scanned contract.pdf", etag = "s1", ocId = "oc-99")
        val h = ReconcileHarness(server, verdict = { foreign() })

        h.reconciler.reconcile()
        assertEquals(1, h.requests("GET"))
        assertEquals(ETag("s1", weak = false), h.foreign.rows.values.single().etag, "the version is the answer's key")

        // A note in the same folder uploads, which is what usually moves this ETag.
        server.directory("Inkstone", etag = "r3", ocId = "oc-root")
        val second = h.reconciler.reconcile()

        assertEquals(1, second.ignored, "still ignored")
        assertEquals(1, h.requests("GET"), "and not fetched again — the answer was already known")
        assertTrue(h.attention.rows.isEmpty())
    }

    @Test
    fun `a pdf that is not ours is looked at again once its bytes change`() = runTest {
        // The other direction, and it must stay expensive: a path that held a stranger's scan
        // yesterday can hold a peer's note today. A cache that answered for the path rather than for
        // the version would be a note that never arrives and never says why.
        val server = FakeServer()
            .directory("Inkstone", etag = "r2", ocId = "oc-root")
            .file("Inkstone/Scanned contract.pdf", etag = "s1", ocId = "oc-99")
        val h = ReconcileHarness(server, verdict = { foreign() })

        h.reconciler.reconcile()
        server
            .directory("Inkstone", etag = "r3", ocId = "oc-root")
            .file("Inkstone/Scanned contract.pdf", etag = "s2", ocId = "oc-99")
        h.reconciler.reconcile()

        assertEquals(2, h.requests("GET"), "different bytes, so the marker has to be read again")
    }

    @Test
    fun `strangers ahead of a note in a folder do not starve it forever`() = runTest {
        // ⚠️ The livelock, which is what makes the missing cache a data-availability bug rather than
        // a bandwidth one. Each unknown file spends one unit of the pass budget, and the budget is
        // never carried over: `limit` strangers ahead of the notes in listing order consumed every
        // pass, the directory was left unfinished so the root ETag was never cached, and the next
        // pass re-downloaded the same files in the same order and never reached file `limit + 1`.
        // The note behind them never synced, and every pass reported success.
        val server = FakeServer()
            .directory("Inkstone", etag = "r2", ocId = "oc-root")
            .file("Inkstone/Handout one.pdf", etag = "h1", ocId = "oc-90")
            .file("Inkstone/Handout two.pdf", etag = "h2", ocId = "oc-91")
            .file("Inkstone/Lecture.pdf", etag = "f2", ocId = "oc-42")
        val h = ReconcileHarness(
            server,
            verdict = { path -> if (path == NoteLayout.download(lecture)) normal(lecture) else foreign() },
        )
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))

        val first = h.reconciler.reconcile(limit = 2)

        assertEquals(0, first.merged, "the whole budget went on the two strangers")
        assertTrue(first.shouldRunAgain)

        val second = h.reconciler.reconcile(limit = 2)

        assertEquals(1, second.merged, "the second pass must reach the note the first could not")
        assertEquals(2, second.ignored, "because it no longer spends the budget re-checking them")
        assertEquals(0, second.trashed)
    }

    @Test
    fun `the same problem rediscovered on the next pass does not grow the attention list`() = runTest {
        // A poll at SPEC §7.6's sixty-second floor rediscovers a parked conflict 1 440 times a day.
        // The id is derived from the problem so the UPSERT lands on one row.
        val h = ReconcileHarness(tree(), verdict = { tooNew(lecture) })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))

        h.reconciler.reconcile()
        // ⚠️ Through [pollAgain], because three plain reconciles never look at the file twice — the
        // directory's cached ETag prunes it. This test used to pass on that and would have passed with
        // the dedup deleted altogether.
        h.pollAgain(times = 2)

        assertEquals(1, h.attention.rows.size)
        assertEquals(3, h.requests("GET"), "the file has to have been met three times for this to mean anything")
    }

    @Test
    fun `an acknowledged row is not re-opened by the pass that rediscovers the problem`() = runTest {
        // ⭐ The other half of the comment above, and the half that shipped broken: "nor clear a
        // dismissal". The guard read the *open* rows, and an acknowledged row is by definition not
        // among them — so the UPSERT rewrote it with `acknowledgedAt = 0`. Every layer-3 outcome
        // recurs on every pass by design (the ETag is deliberately not advanced, so the next pass can
        // find out whether the condition cleared), which made this a row the user could not put away
        // for longer than one poll interval, for a condition that may never clear on its own.
        val h = ReconcileHarness(tree(), verdict = { tooNew(lecture) })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))
        h.reconciler.reconcile()
        val id = h.attention.rows.values.single().id
        h.attention.acknowledge(id, acknowledgedAtEpochMillis = 900L)

        h.pollAgain(times = 3)

        assertEquals(1, h.attention.rows.size, "rediscovering one problem must not grow the list either")
        val row = h.attention.rows.getValue(id)
        assertFalse(row.isOpen, "the next poll un-dismissed it")
        assertEquals(900L, row.acknowledgedAtEpochMillis, "and a tombstone keeps the moment it was read")
    }

    @Test
    fun `an acknowledged row re-opens when the problem itself has changed`() = runTest {
        // The boundary of the fix above, and the reason it is a comparison rather than "a row with
        // this id exists": a dismissal covers the problem the user *read*, not the note. A peer whose
        // bundle schema moved from 3 to 4 has something new to say, and the version is the actionable
        // part of the row ("update Inkstone to open this" versus a bare complaint). This test is what
        // fails against the narrower fix.
        var version = 3
        val h = ReconcileHarness(tree(), verdict = { tooNew(lecture, schemaVersion = version) })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))
        h.reconciler.reconcile()
        val id = h.attention.rows.values.single().id
        h.attention.acknowledge(id, acknowledgedAtEpochMillis = 900L)

        version = 4
        h.pollAgain(times = 1)

        assertEquals(1, h.attention.rows.size, "the same problem with something new to say is the same row")
        assertTrue(h.attention.rows.getValue(id).isOpen, "the user has not seen this one")
        assertEquals("4", h.attention.rows.getValue(id).detail)
    }

    @Test
    fun `an unmergeable file met on four passes leaves one conflicted copy`() = runTest {
        // ⭐ Layer 3 never advances the ETag, so this file is downloaded and refused again on every
        // poll — and `conflictedCopyOf` mints a new `DocumentId` every time it is asked. Unkeyed, one
        // unresolvable condition produced a new local note *and a new bound remote file* per pass:
        // `Lecture (conflicted copy … 120000).pdf`, `… 120500).pdf`, … at the poll interval, for as
        // long as it lasted, none of them removable by anything but the user.
        val h = ReconcileHarness(tree(), verdict = { normal(DocumentId("someone-else")) })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))

        h.reconciler.reconcile()
        h.pollAgain(times = 3)

        assertEquals(4, h.requests("GET"), "the file was not actually met four times")
        val copyId = h.gate.minted.singleOrNull() ?: fail("layer 3 minted ${h.gate.minted.size} copies")
        assertEquals(1, h.attention.rows.size)
        assertEquals(
            copyId,
            h.attention.rows.values.single().conflictedCopyNoteId,
            "the row has to name the copy it kept, or the copy is a mystery file the user deletes",
        )
        assertEquals(
            setOf(lecture.value, copyId.value),
            h.bindings.rows.keys,
            "every extra copy is also an extra file the outbox uploads to somebody's server",
        )
    }

    @Test
    fun `acknowledging a conflicted copy does not make the next pass mint a second one`() = runTest {
        // ⭐ The two rules holding each other up. The record of "this conflict already has a copy" *is*
        // the attention row, so acknowledging has to be a tombstone for a second reason: delete the
        // row and layer 3 starts minting again on the next poll — which is what the user dismissing it
        // would have caused, had a dismissal been a delete.
        val h = ReconcileHarness(tree(), verdict = { normal(DocumentId("someone-else")) })
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))
        h.reconciler.reconcile()
        val id = h.attention.rows.values.single().id
        val copyId = h.gate.minted.single()
        h.attention.acknowledge(id, acknowledgedAtEpochMillis = 900L)

        h.pollAgain(times = 3)

        assertEquals(listOf(copyId), h.gate.minted, "a dismissed conflict was minted a second copy")
        assertFalse(h.attention.rows.getValue(id).isOpen, "and the dismissal must still stand")
        assertEquals(setOf(lecture.value, copyId.value), h.bindings.rows.keys)
    }
}
