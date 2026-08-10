package dev.inkstone.app.note

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.DocumentMaterializer
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.MaterializedFile
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.storage.BundleContents
import dev.inkstone.core.storage.BundleReader
import dev.inkstone.core.storage.BundleWriter
import dev.inkstone.core.storage.FileByteSource
import dev.inkstone.core.storage.MergeReport
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.storage.NoteSession
import dev.inkstone.core.storage.NoteStore
import dev.inkstone.core.storage.PageContents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⭐ **One writer at a time over the open note's session.**
 *
 * `NoteEditor.work` is a scope, not a queue. It runs on `Dispatchers.Main.immediate`, so nothing in
 * the editor races for memory — and every coroutine in it still interleaves freely at every
 * suspension point. The autosave loop is one long-lived coroutine there; since M6 a merge is another;
 * and `NoteSession.materialize` snapshots every page and only *then* resets the journal:
 *
 * ```
 * merge:     materialize() — snapshot the pages, write the bundle      ← suspends here
 * autosave:  save() → apply(gesture) → flush() → a journal record on disk
 * merge:     ... reset the journal to a bare header
 * ```
 *
 * The stroke drawn during the merge is then in no journal, no bundle, no `pending` list and no page
 * cache. The note is still a valid PDF with the right page count, `MergeCommit` reports the revision
 * from *before* the flush, and the library index and the remote binding are both told nothing is
 * owed. There is no error anywhere.
 *
 * So every test here parks a real operation mid-write and runs another one into it. Calling two
 * editor operations in sequence — which is what the rest of this package does — cannot see any of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorConcurrencyTest {

    private val clock = Clock { NOW }

    /** A note on disk, and a peer's bundle for it, before any editor exists. */
    private class Fixture(val fs: GatedFileSystem, val id: DocumentId, val page: PageId, val peerBundle: String)

    private suspend fun fixture(): Fixture {
        val fs = GatedFileSystem()
        val store = NoteStore(fs, clock, IdFactory(clock))
        // Created up front rather than by the editor, because the peer's bundle has to name this
        // note: a bundle carrying a different `DocumentId` comes back `DifferentDocument`, which is a
        // different code path with none of this test's suspension points in it.
        val note = store.create(title = "Shared", deviceId = "device-a")
        val page = note.document.pages.single().id
        val peerBundle = "download/peer.inkstone"
        fs.writeBytesAtomically(
            peerBundle,
            BundleWriter.write(
                BundleContents(
                    document = note.document,
                    pages = mapOf(page to PageContents(PageSize.Letter, Background.Blank, listOf(peerStroke()))),
                    highestClockCounter = PEER_COUNTER,
                ),
            ),
        )
        return Fixture(fs, note.document.id, page, peerBundle)
    }

    private fun TestScope.editorOver(fixture: Fixture, materializer: DocumentMaterializer? = null) = NoteEditor(
        fs = fixture.fs,
        clock = clock,
        scope = this,
        drainDispatcher = UnconfinedTestDispatcher(testScheduler),
        // The autosave loop is driven by hand; a live timer would make the interleaving depend on how
        // long the test took to run.
        autosaveIntervalMs = Long.MAX_VALUE,
        documentId = fixture.id,
        materializer = materializer,
    )

    /**
     * Park the merge where `NoteSession.materialize` is between its page snapshot and its journal
     * reset — which is the bundle write.
     *
     * Only the first write parks ([CompletableDeferred.complete] answers false once it is complete),
     * so an operation that wrongly runs alongside still finishes and still does its damage. A gate
     * that parked every writer would turn a failing assertion into a hanging test.
     */
    private fun Fixture.parkAtBundleWrite(parked: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) {
        fs.onWrite = { path -> if (path == NoteLayout.bundle(id) && parked.complete(Unit)) release.await() }
    }

    /** Fold the note from the files alone, sharing nothing with the editor's session. */
    private suspend fun objectsOnDisk(fixture: Fixture): Set<ObjectId> {
        val store = NoteStore(fixture.fs, clock, IdFactory(clock))
        val note = assertNotNull(store.open(fixture.id), "the note is not on disk at all")
        val session = NoteSession.open(store, IdFactory(clock), clock, note, "device-verifier")
        return session.page(fixture.page).objects.mapTo(HashSet()) { it.id }
    }

    @Test
    fun `a stroke drawn during a merge survives it`() = runTest {
        val fixture = fixture()
        val editor = editorOver(fixture)
        val surface = FakeSurface()
        editor.onSurfaceAttached(surface)
        runCurrent()
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.parkAtBundleWrite(parked, release)

        val merge = async { editor.mergeRemoteBundle(fixture.peerBundle) }
        parked.await()
        // The user is writing. This is the two-second autosave landing inside the merge, and the merge
        // arrives at the reconciler's convenience — a background WorkManager pass or the sixty-second
        // poll — not the user's.
        surface.queueGesture(listOf(localStroke()))
        val autosave = launch { editor.save() }
        runCurrent()
        release.complete(Unit)
        autosave.join()

        val commit = assertNotNull(merge.await(), "the merge did not run against the editor's session")
        assertIs<MergeReport.Merged>(commit.report, "the merge itself failed, so this test proves nothing")
        assertEquals(
            setOf(ObjectId(LOCAL_STROKE), ObjectId(PEER_STROKE)),
            objectsOnDisk(fixture),
            "the stroke drawn during the merge was destroyed by it",
        )
        editor.close()
    }

    @Test
    fun `an autosave that lands during a merge runs after it and not inside it`() = runTest {
        // The order, not the bytes. A later change could keep the stroke by luck — a folded page holds
        // a live reference to its own op list — while restoring the interleaving that regresses
        // `localRev` and writes one revision number twice. What has to be true is that the flush lands
        // on one side of the merge or the other, never in the middle of it.
        val fixture = fixture()
        val editor = editorOver(fixture)
        val surface = FakeSurface()
        editor.onSurfaceAttached(surface)
        runCurrent()
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.parkAtBundleWrite(parked, release)

        val merge = async { editor.mergeRemoteBundle(fixture.peerBundle) }
        parked.await()
        surface.queueGesture(listOf(localStroke()))
        val autosave = launch { editor.save() }
        runCurrent()
        release.complete(Unit)
        autosave.join()
        merge.await()

        val journal = NoteLayout.journal(fixture.id)
        // ⚠️ `lastIndexOf`, not `indexOf`: creating the note writes a bare journal header, so the
        // *first* `write:` here belongs to the fixture and comparing against it passes vacuously.
        // The reset that matters is the last one, inside the merge's materialize.
        val reset = fixture.fs.writes.lastIndexOf("write:$journal")
        val flushed = fixture.fs.writes.lastIndexOf("append:$journal")
        assertTrue(
            fixture.fs.writes.count { it == "write:$journal" } >= 2,
            "the merge never reset the journal, so this test proves nothing",
        )
        assertTrue(flushed > reset, "the autosave appended to the journal inside the merge, which then reset it")
        editor.close()
    }

    @Test
    fun `a merge that arrives while the note is still opening waits for the session`() = runTest {
        // ⭐ The note-open window. `NoteEditorViewModel` registers this editor's handle in its `init`,
        // before the open has read a byte — deliberately, because that is exactly when a pull is
        // dangerous. Null here means "no session, do it yourself", so the host would open a *second*
        // session over this note and rewrite `note.inkstone` under the byte offsets this editor's
        // `BundleReader` has already recorded. The editor's next read through it fails a local-header
        // or CRC check and materialize throws out of `viewModelScope.launch`.
        val fixture = fixture()
        val editor = editorOver(fixture)
        // No `runCurrent`: the open has been launched and has not begun. That is the production
        // window — on `Dispatchers.Main.immediate` the open starts inline and then suspends on its
        // first file read, which is longer than a PROPFIND plus a GET needs to land.
        val commit = editor.mergeRemoteBundle(fixture.peerBundle)

        assertNotNull(commit, "the merge was told there is no session here while the note was opening")
        assertIs<MergeReport.Merged>(commit.report, "the bundle would not open, so the null was not what was caught")
        assertEquals(
            setOf(ObjectId(PEER_STROKE)),
            objectsOnDisk(fixture),
            "the peer's stroke did not reach the note the editor is opening",
        )
        // And the editor holds the one session, folded from the merged bundle.
        val folded = assertNotNull(editor.foldedPage(fixture.page), "the editor never opened the note")
        assertEquals(setOf(ObjectId(PEER_STROKE)), folded.objects.mapTo(HashSet()) { it.id })
        editor.close()
    }

    @Test
    fun `a worker materialize that lands during a merge publishes the merged bundle`() = runTest {
        // ⭐ The other half of M6's overlap finding. `MaterializeWorker` republished `document.pdf` by
        // calling `DocumentMaterializer` straight out of the job, which for an open note is a second
        // writer with no ordering against the editor. The damage is not a torn file — both writes are
        // atomic and, since M6, uniquely named — it is a **stale marker**: the materializer reads
        // `note.inkstone`, embeds it, and records its SHA-256 (SPEC §5.6). A bundle rewritten by the
        // editor between that read and the PDF write leaves `document.pdf` describing a bundle that no
        // longer exists, which the next library rebuild reads as `Recovered(BUNDLE_HASH_MISMATCH)`.
        // Recovery is REPLACE-not-union stamped at `highestKnownCounter + 1_000_000`, so it then wins
        // every register on the next sync: a healthy note rewritten lossily because it was published
        // twice.
        //
        // ⚠️ This cannot fail against the old code, and saying so is the point: the old code had no
        // route from the worker into the editor at all. The fix is structural — `MaterializeWorker`
        // now goes through `NoteSessionHost`, which holds the note's mutex and hands the job to the
        // live editor. What is asserted here is that the route it was given serializes.
        val fixture = fixture()
        val seen = ArrayList<Set<ObjectId>>()
        val editor = editorOver(fixture, materializer = recordingMaterializer(fixture, seen))
        val surface = FakeSurface()
        editor.onSurfaceAttached(surface)
        runCurrent()
        seen.clear()
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.parkAtBundleWrite(parked, release)

        val merge = async { editor.mergeRemoteBundle(fixture.peerBundle) }
        parked.await()
        // The worker waking up while the pull is mid-write. In production this is WorkManager's
        // "idle while charging", which lands whenever it lands.
        val publish = async { editor.materializeInSession() }
        runCurrent()
        assertTrue(seen.isEmpty(), "the worker published from inside the merge's bundle write")
        release.complete(Unit)
        merge.await()

        assertTrue(publish.await(), "the worker's publish reported failure")
        assertEquals(
            listOf(setOf(ObjectId(PEER_STROKE))),
            seen,
            "the worker published a bundle that did not have the peer's stroke in it",
        )
        editor.close()
    }

    /**
     * A materializer that records what was in `note.inkstone` **at the moment it ran** — which is
     * exactly what `InkstonePdfMaterializer` embeds and hashes.
     */
    private fun recordingMaterializer(fixture: Fixture, seen: MutableList<Set<ObjectId>>) =
        DocumentMaterializer { noteId ->
            val reader = assertNotNull(
                BundleReader.open(FileByteSource(fixture.fs, NoteLayout.bundle(noteId))),
                "the bundle the publisher read is not a bundle",
            )
            val ids = reader.pageIds.flatMap { page -> reader.page(page)?.ops.orEmpty() }
                .filterIsInstance<PageOp.AddObject>()
                .mapTo(HashSet()) { it.obj.id }
            seen += ids
            MaterializedFile(path = "published.pdf", sizeBytes = 1L, sha256 = "", localRev = reader.document.localRev)
        }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val PEER_STROKE = "peer-1"
        const val LOCAL_STROKE = "mine-1"

        /** Above anything the local note has issued, so the peer's op is genuinely novel. */
        const val PEER_COUNTER = 9L

        fun peerStroke() = PageOp.AddObject(
            opId = "peer-op-1",
            clock = LamportClock(PEER_COUNTER, "device-b"),
            obj = StrokeObject(ObjectId(PEER_STROKE), StrokeGeometry(byteArrayOf(4, 5, 6))),
        )

        /** Stamped above the peer's, as a real gesture drawn after the merge landed would be. */
        fun localStroke() = PageOp.AddObject(
            opId = "local-op-1",
            clock = LamportClock(PEER_COUNTER + 3, "device-a"),
            obj = StrokeObject(ObjectId(LOCAL_STROKE), StrokeGeometry(byteArrayOf(1, 2, 3))),
        )
    }
}
