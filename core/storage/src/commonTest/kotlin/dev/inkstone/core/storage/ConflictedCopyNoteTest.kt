package dev.inkstone.core.storage

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Document
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PageTemplate
import dev.inkstone.core.model.RemoteName
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.foldPage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC §7.7 layer 3 — the non-destructive conflicted copy.
 *
 * The peer is a second [FakeFileSystem] with its own store and its own device id, so the only
 * thing shared between the two is the bundle bytes that pass between them — which is exactly
 * the relationship two tablets have, and it makes *"the local note is untouched"* a claim about
 * a real second disk rather than about a mock.
 *
 * Three of these are about identity, because identity is the whole of what a copy can get
 * wrong: a shared `DocumentId` overwrites the original, and a shared `ObjectId` makes the two
 * notes converge onto one geometry with no error anywhere.
 */
class ConflictedCopyNoteTest {

    private val clock = TestClock()

    private val fs = FakeFileSystem()
    private val ids = IdFactory(clock, Random(11))
    private val store = NoteStore(fs, clock, ids)

    private val peerFs = FakeFileSystem()
    private val peerIds = IdFactory(clock, Random(22))
    private val peerStore = NoteStore(peerFs, clock, peerIds)

    private fun stroke(id: String) = StrokeObject(
        id = ObjectId(id),
        geometry = StrokeGeometry(ByteArray(48) { (it + id.length).toByte() }),
    )

    private fun peerAdd(objectId: String, at: Long) = PageOp.AddObject(
        opId = "peer-op-$objectId",
        clock = LamportClock(at, PEER),
        obj = stroke(objectId),
    )

    /**
     * A two-page note as the peer would have published it, and the bytes we downloaded.
     *
     * Page one is Cornell, so "the copy came back blank" is distinguishable from "the copy came
     * back right".
     */
    private suspend fun peerPublished(): Pair<DocumentId, ByteArray> {
        val note = peerStore.create(TITLE, PEER, pageCount = 2)
        val session = NoteSession.open(peerStore, peerIds, clock, note, PEER)
        val pages = note.document.livePages
        session.apply(
            pages[0].id,
            listOf(
                PageOp.SetPageMeta(
                    session.nextOpId(),
                    session.nextClock(),
                    background = Background.Template(PageTemplate.CORNELL),
                ),
                peerAdd("peer-1", 10),
                peerAdd("peer-2", 11),
            ),
        )
        session.apply(pages[1].id, listOf(peerAdd("peer-3", 12)))
        session.close(NoteSyncState.SYNCED)
        return note.document.id to peerFs.readBytes(NoteLayout.bundle(note.document.id))
    }

    /** The local note that could not be merged: the same `DocumentId`, different content. */
    private suspend fun localNote(id: DocumentId): OpenNote {
        val note = store.create(
            title = TITLE,
            deviceId = LOCAL,
            pages = listOf(NewPage(PageSize.Letter, Background.Blank)),
            id = id,
        )
        val session = NoteSession.open(store, ids, clock, note, LOCAL)
        session.apply(
            note.document.livePages.single().id,
            listOf(PageOp.AddObject(session.nextOpId(), session.nextClock(), obj = stroke("mine-1"))),
        )
        session.close(NoteSyncState.CONFLICTED)
        return assertNotNull(store.open(id))
    }

    /** What the peer's document looked like, for the hand-built bundles below. */
    private suspend fun peerDocument(id: DocumentId): Document = assertNotNull(peerStore.open(id)).document

    /**
     * What `Reconciler.layerThree` hands in: the **finished** name, stamped once from that pass's
     * one reading of the clock, because the same string becomes the remote path of the verbatim
     * bytes. `conflictedCopyOf` used to stamp it itself, which meant the caller had to pass the base
     * title and the two names came from two clock readings a download apart.
     */
    private fun stampedTitle() = RemoteName.conflictedCopy(TITLE, clock.millis)

    private suspend fun copyFrom(bytes: ByteArray, title: String = stampedTitle()): Document? =
        store.conflictedCopyOf(MemoryByteSource(bytes), title, LOCAL, ids, clock)

    private suspend fun foldOf(id: DocumentId, pageId: PageId): FoldedPage {
        val note = assertNotNull(store.open(id))
        val loaded = store.loadPage(note, pageId)
        return foldPage(
            pageId,
            loaded.ops,
            defaultCanvas = PageCanvas.fixed(loaded.size),
            defaultBackground = loaded.background,
        )
    }

    private suspend fun readerOf(id: DocumentId): BundleReader =
        assertNotNull(BundleReader.open(FileByteSource(fs, NoteLayout.bundle(id))), "$id has no readable bundle")

    // -----------------------------------------------------------------------

    @Test
    fun `a conflicted copy is a new document with the same picture`() = runTest {
        val (remoteId, bytes) = peerPublished()
        localNote(remoteId)

        val copy = assertNotNull(copyFrom(bytes))

        // Identity reason 1: `notes/<id>/` is one directory and `note.id` is the library index's
        // primary key, so a copy sharing the id would not be a copy — it would be the original,
        // overwritten.
        assertNotEquals(remoteId, copy.id, "the copy took the remote document id")
        assertEquals(2, copy.livePages.size, "the copy lost a page")

        val first = foldOf(copy.id, copy.livePages[0].id)
        val second = foldOf(copy.id, copy.livePages[1].id)
        assertContentEquals(
            listOf("peer-1", "peer-2").map { stroke(it).geometry.bytes.toList() },
            first.objects.map { (it as StrokeObject).geometry.bytes.toList() },
            "page one of the copy is not the peer's page one",
        )
        assertEquals(1, second.objects.size, "page two of the copy lost a stroke")
        // Page metadata came across too: a conflicted copy of a Cornell page that comes back
        // blank looks exactly like a copy that lost something.
        assertEquals(Background.Template(PageTemplate.CORNELL), first.background)
    }

    @Test
    fun `every object in a conflicted copy has a new identity`() = runTest {
        val (remoteId, bytes) = peerPublished()
        localNote(remoteId)

        val copy = assertNotNull(copyFrom(bytes))

        val copied = copy.livePages.flatMap { ref -> foldOf(copy.id, ref.id).objects }
        assertEquals(3, copied.size)
        // Identity reason 3: two objects with one id in a grow-only union converge onto one
        // geometry, because the fold's payload rule is first-write-wins per ObjectId. One note's
        // strokes would appear in the other, on every device, consistently, with no error.
        val peerObjectIds = setOf(ObjectId("peer-1"), ObjectId("peer-2"), ObjectId("peer-3"))
        assertTrue(
            copied.none { it.id in peerObjectIds },
            "the copy reused an ObjectId — the fold would apply one geometry to both notes",
        )
        assertEquals(3, copied.mapTo(HashSet()) { it.id }.size, "two objects within the copy share an id")
        // The geometry is the peer's, byte for byte. Only the identity changed.
        assertContentEquals(
            listOf("peer-1", "peer-2", "peer-3").map { stroke(it).geometry.bytes.toList() },
            copied.map { (it as StrokeObject).geometry.bytes.toList() },
        )
    }

    @Test
    fun `the local note that could not be merged is untouched`() = runTest {
        val (remoteId, bytes) = peerPublished()
        val before = localNote(remoteId)
        val bundleBefore = fs.readBytes(NoteLayout.bundle(remoteId))
        val pageBefore = before.document.livePages.single().id

        copyFrom(bytes)

        // Nothing local is superseded — that is the whole point of layer 3. Byte equality of the
        // bundle is the strongest available statement of it, and it covers the title and the page
        // list as well, which a per-field check would have to remember to.
        assertContentEquals(bundleBefore, fs.readBytes(NoteLayout.bundle(remoteId)), "the local bundle was rewritten")
        val after = assertNotNull(store.open(remoteId))
        assertEquals(before.document.localRev, after.document.localRev, "the local note's local_rev moved")
        assertEquals(TITLE, after.document.title, "the local note was renamed")
        assertEquals(
            listOf("mine-1"),
            foldOf(remoteId, pageBefore).objects.map { it.id.value },
            "the local note's own strokes changed",
        )
    }

    @Test
    fun `the copy takes the callers name verbatim and never stamps it twice`() = runTest {
        val (_, bytes) = peerPublished()
        val stamped = stampedTitle()
        // The download happens between the caller's stamp and this call. A minute is generous for a
        // bundle and modest for a 40 MB lecture over a home connection.
        clock.millis += ONE_MINUTE_MILLIS

        val copy = assertNotNull(copyFrom(bytes, stamped))

        // ⭐ One string serves as the note's title **and** as the remote path of the verbatim bytes,
        // so it has to come from one reading of the clock. Stamping here as well left two choices,
        // both wrong: pass the stamped string through and get
        // `Lecture (conflicted copy …) (conflicted copy …)`, or pass the base title — which is what
        // `NoteSessionHost` did — and get two names for one conflict, separated by however long the
        // download took. Advancing the clock above is what makes this assertion say so.
        assertEquals(stamped, copy.title)
        assertEquals(
            1,
            copy.title.split(" (conflicted copy ").size - 1,
            "the name was stamped a second time: ${copy.title}",
        )
        assertTrue(RemoteName.isLegal(copy.title), "the copy's name is not a legal remote name")
        assertTrue(copy.title.startsWith("$TITLE (conflicted copy "), "unrecognisable name: ${copy.title}")
    }

    @Test
    fun `the copys log carries this devices clocks and none of the peers op ids`() = runTest {
        val (_, bytes) = peerPublished()

        val copy = assertNotNull(copyFrom(bytes))

        val reader = readerOf(copy.id)
        val ops = copy.livePages.flatMap { ref -> assertNotNull(reader.page(ref.id)).ops }
        assertTrue(ops.isNotEmpty())
        // No peer has ever seen this document, so its history is this device's from counter one.
        // Inheriting the peer's clocks would leave a brand-new note's first edits sorting behind
        // the log they were written on top of.
        assertTrue(ops.all { it.clock.deviceId == LOCAL }, "the copy inherited the peer's Lamport clocks")
        assertTrue(ops.none { it.opId.startsWith("peer-op-") }, "the copy inherited the peer's op ids")
    }

    @Test
    fun `a copy is not stale when it is returned`() = runTest {
        val (_, bytes) = peerPublished()

        val copy = assertNotNull(copyFrom(bytes))

        // SPEC §5.1's invariant. Handing back a note that exists only as a journal would leave it
        // one session stale before anyone had touched it.
        store.assertNotStale(copy.id)
    }

    @Test
    fun `the peers unknown bundle entries survive into the copy`() = runTest {
        val (remoteId, _) = peerPublished()
        val document = peerDocument(remoteId)
        // ⚠️ M7 made `text/` a KNOWN entry family (SPEC §5.5), so it is no longer an example
        // of one this build cannot interpret — `BundleWriter` emits it from `PageContents`
        // and `carriedNames` excludes it. The carried path still has to work, so the
        // fixture uses a name no build has ever claimed.
        val peerEntry = "future/${document.livePages[0].id.value}.bin"
        val bytes = BundleWriter.write(
            BundleContents(
                document = document,
                // Page two is deliberately absent from the entries while still listed in the
                // manifest — the two can disagree, and a page listed with no entry must still
                // become a page in the copy rather than change its page count.
                pages = mapOf(
                    document.livePages[0].id to PageContents(
                        PageCanvas.fixed(PageSize.Letter),
                        Background.Blank,
                        emptyList(),
                    ),
                ),
                highestClockCounter = 12L,
                carried = mapOf(peerEntry to "from a newer build".encodeToByteArray()),
                carriedManifest = mapOf("futureField" to JsonPrimitive(7)),
            ),
        )

        val copy = assertNotNull(copyFrom(bytes))

        assertEquals(2, copy.livePages.size, "a page listed with no entry was dropped from the copy")
        // The entries at risk are by definition the ones this build cannot render, so dropping
        // them looks like nothing at all. `assets/` is the visible case: an ImageObject names its
        // asset by string, and that string survives re-identification.
        val reader = readerOf(copy.id)
        assertTrue(peerEntry in reader.carriedNames, "the peer's entry was dropped by the copy")
        assertContentEquals("from a newer build".encodeToByteArray(), assertNotNull(reader.carried()[peerEntry]))
        assertEquals(JsonPrimitive(7), reader.carriedManifest["futureField"], "the peer's manifest field was dropped")
    }

    @Test
    fun `the peers image bytes survive into the copy`() = runTest {
        // ⚠️ Layer 3's promise is that nothing is discarded, and `assets/` is the one family
        // where dropping it is *visible* — an ImageObject names its asset by string, that string
        // survives re-identification, and a copy without the bytes shows a blank rectangle where
        // the picture was. This line read `remote.carried()` until M8c, which was right only
        // while the family was a carried unknown; taking it out of `carriedNames` in the same
        // commit that gave it a writer would otherwise have made a conflicted copy lose every
        // picture in the note it exists to preserve.
        val (remoteId, _) = peerPublished()
        val document = peerDocument(remoteId)
        val asset = Bundle.imageAsset(ObjectId("peer-picture"))
        val bytes = ByteArray(48) { (it * 5).toByte() }
        val remoteBundle = BundleWriter.write(
            BundleContents(
                document = document,
                pages = document.livePages.associate { ref ->
                    ref.id to PageContents(PageCanvas.fixed(PageSize.Letter), Background.Blank, emptyList())
                },
                highestClockCounter = 12L,
                assets = mapOf(asset to bytes),
            ),
        )

        val copy = assertNotNull(copyFrom(remoteBundle))

        assertContentEquals(bytes, assertNotNull(readerOf(copy.id).assets()[asset], "the copy has no picture bytes"))
    }

    @Test
    fun `bytes that are not a bundle produce no copy and write nothing`() = runTest {
        val paths = fs.paths.toSet()

        assertNull(copyFrom("not a zip at all".encodeToByteArray()))

        assertEquals(paths, fs.paths.toSet(), "an unreadable download left a half-built note behind")
    }

    @Test
    fun `a bundle from a newer schema produces no copy instead of throwing`() = runTest {
        val (remoteId, _) = peerPublished()
        val document = peerDocument(remoteId)
        val future = BundleWriter.write(
            BundleContents(
                document = document.copy(schemaVersion = Document.SCHEMA_VERSION + 1),
                pages = mapOf(
                    document.livePages[0].id to PageContents(
                        PageCanvas.fixed(PageSize.Letter),
                        Background.Blank,
                        emptyList(),
                    ),
                ),
                highestClockCounter = 0L,
            ),
        )

        // `BundleReader.open` throws on a schema from the future rather than answering null, and a
        // newer schema is one of layer 3's three triggers — so this is among the likeliest inputs
        // to the function, not an edge case. An exception here reaches a WorkManager job, which
        // reads it as a transport failure and retries it forever.
        assertNull(copyFrom(future))
    }

    @Test
    fun `a bundle whose every page is a tombstone produces no copy`() = runTest {
        val (remoteId, _) = peerPublished()
        val document = peerDocument(remoteId)
        val allDeleted = BundleWriter.write(
            BundleContents(
                document = document.copy(
                    pages = document.pages.map { it.copy(deleted = true, deletedEpochMillis = clock.millis) },
                ),
                pages = document.pages.associate { ref ->
                    ref.id to PageContents(PageCanvas.fixed(PageSize.Letter), Background.Blank, emptyList())
                },
                highestClockCounter = 0L,
            ),
        )

        // A note with no pages has nothing to draw on and no way back to a page, which is why
        // `create` refuses one. Answering null keeps that refusal from becoming a crash.
        assertNull(copyFrom(allDeleted))
        assertTrue(fs.paths.isEmpty(), "a page-less copy was written anyway")
    }

    private companion object {
        const val TITLE = "Lecture Notes"
        const val LOCAL = "device-local"
        const val PEER = "device-peer"

        /** How long a download stands between the caller's stamp and the copy being made. */
        const val ONE_MINUTE_MILLIS = 60_000L
    }
}
