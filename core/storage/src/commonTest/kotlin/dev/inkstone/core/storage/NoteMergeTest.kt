package dev.inkstone.core.storage

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Document
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.EraseCapsule
import dev.inkstone.core.model.ErasedArea
import dev.inkstone.core.model.FoldOutcome
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageRef
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SPEC §7.7 layers 1 and 2, exercised over two real bundles.
 *
 * These are the JVM half of SPEC §13.2's merge matrix. The matrix itself runs the same
 * mechanisms against a real Nextcloud, because that is what reproduces weak ETags, fileid
 * churn and lockout — but the *convergence* claims are properties of the fold and are
 * cheaper and sharper to state here, where two devices can be conjured in four lines and
 * a clock can be set by hand.
 *
 * Each device is its own [FakeFileSystem] and its own `deviceId`, so nothing is shared but
 * the bytes that pass between them — which is exactly the relationship two tablets have.
 */
class NoteMergeTest {

    private val clock = TestClock()

    /** One device: its own disk, its own store, its own identity. */
    private class Device(val name: String, val clock: TestClock) {
        val fs = FakeFileSystem()
        val ids = IdFactory(clock, Random(name.hashCode()))
        val store = NoteStore(fs, clock, ids)

        suspend fun session(note: OpenNote) = NoteSession.open(store, ids, clock, note, deviceId = name)

        suspend fun open(id: DocumentId) = session(assertNotNull(store.open(id), "$name cannot open $id"))

        /** The bytes this device would publish — what the other one downloads. */
        suspend fun bundleBytes(id: DocumentId): ByteArray = fs.readBytes(NoteLayout.bundle(id))

        suspend fun readerOver(bytes: ByteArray): BundleReader =
            assertNotNull(BundleReader.open(MemoryByteSource(bytes)), "not a readable bundle")
    }

    private fun stroke(id: String) = StrokeObject(
        id = ObjectId(id),
        geometry = StrokeGeometry(ByteArray(48) { (it + id.length).toByte() }),
    )

    private fun addOp(id: String, device: String, at: Long) = PageOp.AddObject(
        opId = "op-$id",
        clock = LamportClock(at, device),
        obj = stroke(id),
    )

    private fun deleteOp(target: String, device: String, at: Long) = PageOp.DeleteObject(
        opId = "op-del-$target-$device",
        clock = LamportClock(at, device),
        target = ObjectId(target),
    )

    private fun transformOp(target: String, device: String, at: Long, scale: Float) = PageOp.SetTransform(
        opId = "op-xf-$target-$device",
        clock = LamportClock(at, device),
        target = ObjectId(target),
        absolute = Matrix3.scale(scale, scale),
    )

    // -----------------------------------------------------------------------

    @Test
    fun `both devices strokes survive a merge in both directions`() = runTest {
        // SPEC §13.2 row 1. The grow-only half of layer 1, and the property everything else
        // rests on: two people writing in different corners of a page both keep their work.
        val a = Device("device-a", clock)
        val b = Device("device-b", clock)
        val note = a.store.create("Shared", "device-a")
        val id = note.document.id
        val page = note.document.pages.single().id

        val sessionA = a.session(note)
        sessionA.apply(page, listOf(addOp("from-a", "device-a", 10)))
        sessionA.materialize()

        // B starts from A's published bundle, then writes its own stroke offline.
        b.fs.writeBytesAtomically(NoteLayout.bundle(id), a.bundleBytes(id))
        b.fs.writeBytesAtomically(NoteLayout.journal(id), Journal.header())
        val sessionB = b.open(id)
        sessionB.apply(page, listOf(addOp("from-b", "device-b", 11)))
        sessionB.materialize()

        // A pulls B.
        val intoA = NoteMerge.merge(sessionA, a.readerOver(b.bundleBytes(id)))
        assertIs<MergeReport.Merged>(intoA)
        sessionA.materialize(intoA.carried, intoA.carriedManifest)

        // B pulls A.
        val intoB = NoteMerge.merge(sessionB, b.readerOver(a.bundleBytes(id)))
        assertIs<MergeReport.Merged>(intoB)
        sessionB.materialize(intoB.carried, intoB.carriedManifest)

        val onA = a.open(id).page(page).objects.map { it.id.value }.sorted()
        val onB = b.open(id).page(page).objects.map { it.id.value }.sorted()
        assertEquals(listOf("from-a", "from-b"), onA, "device A lost a stroke")
        assertEquals(onA, onB, "the two devices did not converge")
    }

    @Test
    fun `a concurrent delete beats a transform and leaves no ghost`() = runTest {
        // SPEC §13.2 row 2 — a design-bug row. Presence is an LWW register keyed independently
        // of when the AddObject arrives, so a delete at a higher clock wins whichever order the
        // ops are folded in, and the transform becomes a no-op on a tombstoned object.
        val a = Device("device-a", clock)
        val note = a.store.create("Shared", "device-a")
        val page = note.document.pages.single().id
        val session = a.session(note)
        session.apply(page, listOf(addOp("obj-x", "device-a", 5)))
        session.flush()

        // A transforms; B deletes at a higher clock. Both arrive as remote ops.
        session.merge(page, listOf(transformOp("obj-x", "device-a", 6, scale = 2f)))
        session.merge(page, listOf(deleteOp("obj-x", "device-b", 7)))

        val folded = session.page(page)
        assertTrue(folded.objects.none { it.id.value == "obj-x" }, "a deleted object is still rendered")
        assertTrue(ObjectId("obj-x") in folded.deleted, "the tombstone is missing")
        // And the geometry is still in the log — nothing was discarded (CLAUDE.md rule 1).
        session.materialize()
        val reader = a.readerOver(a.bundleBytes(note.document.id))
        val ops = assertNotNull(reader.page(page)).ops
        assertTrue(ops.any { it is PageOp.AddObject && it.obj.id.value == "obj-x" }, "the AddObject was dropped")
    }

    @Test
    fun `two concurrent transforms resolve to one of them and never a product`() = runTest {
        // SPEC §13.2 row 3 — the row that catches the deltas-vs-absolute design bug, and the
        // one that was failing before M6 because both devices shared a deviceId.
        val a = Device("device-a", clock)
        val note = a.store.create("Shared", "device-a")
        val page = note.document.pages.single().id
        val session = a.session(note)
        session.apply(page, listOf(addOp("obj-x", "device-a", 5)))
        session.flush()

        // The same counter from two devices — the case that only resolves if the deviceId
        // tiebreak is total. "device-b" sorts after "device-a", so B wins.
        session.merge(page, listOf(transformOp("obj-x", "device-a", 9, scale = 2f)))
        session.merge(page, listOf(transformOp("obj-x", "device-b", 9, scale = 3f)))

        val transform = assertNotNull(session.page(page).find(ObjectId("obj-x"))).transform
        // 3, not 6: the registers resolved, they did not compose. A delta log would give 6.
        assertEquals(3f, transform.m00, absoluteTolerance = 0.0001f, "the transforms were composed, not resolved")
    }

    @Test
    fun `no two ops in a merged log share a Lamport clock`() = runTest {
        // The invariant `Register.write` rests its correctness on, asserted directly. Equal
        // clocks between devices make LWW resolve to "whichever I folded first", which is
        // insertion-order dependent — so two devices keep different values permanently with
        // every op present and no error.
        val a = Device("device-a", clock)
        val b = Device("device-b", clock)
        val idA = DeviceIdentity(a.fs, a.ids).get()
        val idB = DeviceIdentity(b.fs, b.ids).get()

        val note = a.store.create("Shared", idA)
        val page = note.document.pages.single().id
        val session = a.session(note)
        session.apply(page, listOf(addOp("obj-1", idA, 4)))
        // The peer's op at the *same counter* — legal, and only distinguishable by deviceId.
        session.merge(page, listOf(addOp("obj-2", idB, 4)))
        session.materialize()

        val ops = assertNotNull(a.readerOver(a.bundleBytes(note.document.id)).page(page)).ops
        val clocks = ops.map { it.clock }
        assertEquals(clocks.size, clocks.toSet().size, "two ops share a Lamport clock — the tiebreak is not total")
    }

    @Test
    fun `a page added on each device offline gives three pages on both`() = runTest {
        // SPEC §13.2 row 5 — a design-bug row. A plain LWW page list silently loses one
        // device's new page and orphans its file.
        val a = Device("device-a", clock)
        val b = Device("device-b", clock)
        val note = a.store.create("Shared", "device-a")
        val id = note.document.id
        val sessionA = a.session(note)
        sessionA.materialize()

        b.fs.writeBytesAtomically(NoteLayout.bundle(id), a.bundleBytes(id))
        b.fs.writeBytesAtomically(NoteLayout.journal(id), Journal.header())
        val sessionB = b.open(id)

        val pageA = sessionA.addPage()
        sessionA.apply(pageA, listOf(addOp("on-a-page", "device-a", 20)))
        sessionA.materialize()

        val pageB = sessionB.addPage()
        sessionB.apply(pageB, listOf(addOp("on-b-page", "device-b", 21)))
        sessionB.materialize()

        val intoA = NoteMerge.merge(sessionA, a.readerOver(b.bundleBytes(id)))
        assertIs<MergeReport.Merged>(intoA)
        sessionA.materialize(intoA.carried, intoA.carriedManifest)

        val intoB = NoteMerge.merge(sessionB, b.readerOver(a.bundleBytes(id)))
        assertIs<MergeReport.Merged>(intoB)
        sessionB.materialize(intoB.carried, intoB.carriedManifest)

        // Reopened from disk, because the trap is that materialize drops a page whose PageRef
        // is missing — so an in-memory check would pass while the file lost the page.
        val reopenedA = assertNotNull(a.store.open(id))
        val reopenedB = assertNotNull(b.store.open(id))
        assertEquals(3, reopenedA.document.livePages.size, "device A does not have three pages")
        assertEquals(3, reopenedB.document.livePages.size, "device B does not have three pages")
        assertEquals(
            reopenedA.document.livePages.map { it.id.value },
            reopenedB.document.livePages.map { it.id.value },
            "the two devices ordered the pages differently",
        )
        // And each new page still has its stroke — the page survived, not just its name.
        assertEquals(1, a.session(reopenedA).page(pageB).objects.size, "B's page arrived empty on A")
        assertEquals(1, b.session(reopenedB).page(pageA).objects.size, "A's page arrived empty on B")
    }

    @Test
    fun `an erase and a concurrent transform of one stroke converge without duplicating it`() = runTest {
        // SPEC §13.2 row 7 — a design-bug row. Erasing records an *area* beside the stroke
        // rather than cutting it (SPEC §3.5), so there are no fragments to duplicate, and the
        // erased region is an absolute register like every other.
        val a = Device("device-a", clock)
        val note = a.store.create("Shared", "device-a")
        val page = note.document.pages.single().id
        val session = a.session(note)
        session.apply(page, listOf(addOp("obj-s", "device-a", 5)))
        session.flush()

        val erase = PageOp.SetErasedRegion(
            opId = "op-erase",
            clock = LamportClock(6, "device-a"),
            target = ObjectId("obj-s"),
            absolute = ErasedArea(listOf(EraseCapsule(fromX = 10f, fromY = 10f, toX = 20f, toY = 20f, radius = 4f))),
        )
        session.merge(page, listOf(erase))
        session.merge(page, listOf(transformOp("obj-s", "device-b", 7, scale = 1.5f)))

        val objects = session.page(page).objects
        assertEquals(1, objects.size, "the stroke was duplicated or fragmented")
        val only = objects.single()
        assertEquals("obj-s", only.id.value)
        assertNotNull(only.erased, "the erased region was lost")
        assertEquals(1.5f, only.transform.m00, absoluteTolerance = 0.0001f)
    }

    @Test
    fun `an op older than a snapshot forces a re-fold and is not lost`() = runTest {
        // SPEC §13.2 row 8, on a THREE-page note with the peer's ops on page 2 — which is the
        // only version of this test that means anything. While snapshots were per-document
        // rather than per-page, every page but the last had no snapshot at all, so the row
        // passed or failed according to which page it happened to pick.
        val a = Device("device-a", clock)
        val note = a.store.create("Shared", "device-a", pageCount = 3)
        val id = note.document.id
        val pages = note.document.pages.map { it.id }
        val session = a.session(note)
        for (page in pages) session.apply(page, listOf(addOp("seed-${page.value}", "device-a", 30)))
        session.materialize()
        clock.millis += TWO_MONTHS_MILLIS
        for (page in pages) a.store.writeSnapshot(id, session.page(page), "device-a")
        assertNotNull(a.store.readSnapshot(id, pages[1]), "page 2 has no snapshot to invalidate")

        // A peer's op that sorts *before* the snapshot, on page 2.
        val reopened = a.open(id)
        val outcome = reopened.merge(pages[1], listOf(addOp("late-from-b", "device-b", 1)))

        assertEquals(FoldOutcome.SNAPSHOT_STALE, outcome, "the late op did not invalidate the snapshot")
        val folded = reopened.page(pages[1])
        assertEquals(2, folded.objects.size, "the peer's late op was lost in the re-fold")
        assertTrue(folded.objects.any { it.id.value == "late-from-b" })
    }

    @Test
    fun `a bundle from a newer schema is refused rather than merged`() = runTest {
        // SPEC §5.6 / §5.8 and SPEC §13.2 row 13's second variant. Merging it would rewrite a
        // newer format in this build's vocabulary under the same documentId, and it would then
        // win the next sync and take the newer data with it.
        val a = Device("device-a", clock)
        val note = a.store.create("Shared", "device-a")
        val session = a.session(note)
        session.materialize()

        val future = BundleWriter.write(
            BundleContents(
                document = note.document.copy(schemaVersion = Document.SCHEMA_VERSION + 1),
                pages = mapOf(
                    note.document.pages.single().id to PageContents(PageSize.Letter, Background.Blank, emptyList()),
                ),
                highestClockCounter = 0L,
            ),
        )
        // BundleReader refuses a newer schema outright, so the reconciler cannot even get a
        // reader for it — which is itself the layer-3 signal. Assert that rather than pretend
        // NoteMerge is the gate.
        val reader = runCatching { BundleReader.open(MemoryByteSource(future)) }
        assertTrue(
            reader.isFailure || reader.getOrNull() == null,
            "a bundle from the future was opened; SPEC §5.8 forbids re-saving one",
        )
    }

    @Test
    fun `a merge reports the title it overrode and not the winner`() = runTest {
        // SPEC §7.7 layer 2 — "with the override recorded in history". The string worth
        // recording is the one the user typed and lost. Reading it after the merge yields the
        // *winner*, which would make the attention row say "we replaced X with X".
        val a = Device("device-a", clock)
        val note = a.store.create("Original", "device-a")
        val session = a.session(note)
        session.rename("Typed on this device")
        val localClock = assertNotNull(session.document.titleClock)
        session.materialize()

        val peer = BundleWriter.write(
            BundleContents(
                document = session.document.copy(
                    title = "Typed on the peer",
                    titleClock = LamportClock(localClock.counter + 1, "device-b"),
                ),
                pages = mapOf(
                    note.document.pages.single().id to PageContents(PageSize.Letter, Background.Blank, emptyList()),
                ),
                highestClockCounter = localClock.counter + 1,
            ),
        )

        val report = assertIs<MergeReport.Merged>(NoteMerge.merge(session, a.readerOver(peer)))

        assertTrue(report.titleOverridden)
        assertEquals("Typed on this device", report.overriddenTitle, "the report named the winner, not the loser")
        assertEquals("Typed on the peer", session.document.title)
    }

    @Test
    fun `a peers unknown bundle entry survives the merge and the materialize`() = runTest {
        // The entries at risk are by definition the ones this build cannot render — a peer's
        // recognised text (M7), or anything a newer version added. Dropping them looks like
        // nothing at all.
        val a = Device("device-a", clock)
        val note = a.store.create("Shared", "device-a")
        val id = note.document.id
        val session = a.session(note)
        session.materialize()

        // ⚠️ M7 made `text/` a KNOWN entry family (SPEC §5.5), so it is no longer an example
        // of one this build cannot interpret — `BundleWriter` emits it from `PageContents`
        // and `carriedNames` excludes it. The carried path still has to work, so the
        // fixture uses a name no build has ever claimed.
        val peerEntry = "future/peer.bin"
        val peer = BundleWriter.write(
            BundleContents(
                document = note.document,
                pages = mapOf(
                    note.document.pages.single().id to PageContents(PageSize.Letter, Background.Blank, emptyList()),
                ),
                highestClockCounter = 0L,
                carried = mapOf(peerEntry to "from a newer build".encodeToByteArray()),
                carriedManifest = mapOf("futureField" to JsonPrimitive(7)),
            ),
        )

        val report = assertIs<MergeReport.Merged>(NoteMerge.merge(session, a.readerOver(peer)))
        session.materialize(report.carried, report.carriedManifest)

        val reader = a.readerOver(a.bundleBytes(id))
        assertTrue(peerEntry in reader.carriedNames, "the peer's entry was dropped by materialize")
        assertContentEquals("from a newer build".encodeToByteArray(), assertNotNull(reader.carried()[peerEntry]))
        assertEquals(JsonPrimitive(7), reader.carriedManifest["futureField"], "the peer's manifest field was dropped")
    }

    @Test
    fun `a merge unions the peers images with this devices own`() = runTest {
        // ⭐ SPEC §5.3's M8c rule, over the **production path** — `NoteMerge.merge` then
        // `session.materialize(report.carried, report.carriedManifest)`, which is verbatim what
        // `NoteEditor` and `NoteSessionHost` do. Asserting the union against `NoteStore` alone
        // would prove the rule and not that anything reaches it.
        //
        // Local-wins is exactly wrong here. An asset name embeds a UUIDv7, so a name only the
        // peer has is a picture only the peer can draw — and keeping the local set alone deletes
        // it into a blank rectangle, on a device that never sees an error.
        val a = Device("device-a", clock)
        val note = a.store.create("Diagram", "device-a")
        val id = note.document.id
        val pageId = note.document.pages.single().id
        val ourImage = Bundle.imageAsset(ObjectId("obj-ours"))
        val theirImage = Bundle.imageAsset(ObjectId("obj-theirs"))
        val session = a.session(note)
        // This device's own picture, already in its bundle — the half a naive union drops.
        session.materialize(mapOf(ourImage to "ours".encodeToByteArray()))

        val peer = BundleWriter.write(
            BundleContents(
                document = note.document,
                pages = mapOf(pageId to PageContents(PageSize.Letter, Background.Blank, emptyList())),
                highestClockCounter = 0L,
                assets = mapOf(theirImage to "theirs".encodeToByteArray()),
            ),
        )

        val report = assertIs<MergeReport.Merged>(NoteMerge.merge(session, a.readerOver(peer)))
        session.materialize(report.carried, report.carriedManifest)

        val merged = a.readerOver(a.bundleBytes(id)).assets()
        assertEquals(setOf(ourImage, theirImage), merged.keys, "the merge did not union both devices' images")
        assertContentEquals("ours".encodeToByteArray(), merged.getValue(ourImage))
        assertContentEquals("theirs".encodeToByteArray(), merged.getValue(theirImage))
    }

    @Test
    fun `a pull that carries nothing new reports nothing new`() = runTest {
        // The termination argument for the ETag ping-pong. A poll whose remote ETag moved
        // because *this* device uploaded must report zero novel ops, so the reconciler does
        // not re-upload and start the two devices echoing each other forever.
        val a = Device("device-a", clock)
        val note = a.store.create("Shared", "device-a")
        val id = note.document.id
        val page = note.document.pages.single().id
        val session = a.session(note)
        session.apply(page, listOf(addOp("obj-1", "device-a", 5)))
        session.materialize()

        // The peer's copy is byte-identical to ours — which is what the server holds after we
        // uploaded it.
        val report = assertIs<MergeReport.Merged>(NoteMerge.merge(session, a.readerOver(a.bundleBytes(id))))

        assertEquals(0, report.remoteNovelOps, "our own upload came back as remote work")
        assertFalse(report.changedAnything)
        assertTrue(report.pagesTouched.isEmpty())
    }

    @Test
    fun `merging a different note is refused`() = runTest {
        val a = Device("device-a", clock)
        val mine = a.store.create("Mine", "device-a")
        val other = a.store.create("Theirs", "device-a")
        val session = a.session(mine)
        a.session(other).materialize()

        val report = NoteMerge.merge(session, a.readerOver(a.bundleBytes(other.document.id)))

        assertIs<MergeReport.DifferentDocument>(report)
    }

    @Test
    fun `a merged note still uploads because its local_rev moved`() = runTest {
        // If a merge did not bump local_rev, the outbox's "is there anything to upload"
        // comparison would say no and the merged result would never reach the peer.
        val a = Device("device-a", clock)
        val b = Device("device-b", clock)
        val note = a.store.create("Shared", "device-a")
        val id = note.document.id
        val page = note.document.pages.single().id
        val sessionA = a.session(note)
        sessionA.materialize()
        val revBefore = sessionA.document.localRev

        b.fs.writeBytesAtomically(NoteLayout.bundle(id), a.bundleBytes(id))
        b.fs.writeBytesAtomically(NoteLayout.journal(id), Journal.header())
        val sessionB = b.open(id)
        sessionB.apply(page, listOf(addOp("from-b", "device-b", 12)))
        sessionB.materialize()

        val report = assertIs<MergeReport.Merged>(NoteMerge.merge(sessionA, a.readerOver(b.bundleBytes(id))))

        assertTrue(report.remoteNovelOps > 0)
        assertTrue(sessionA.document.localRev > revBefore, "a merge did not bump local_rev")
    }

    @Test
    fun `a page the peer deleted stays deleted and keeps its ops`() = runTest {
        val a = Device("device-a", clock)
        val note = a.store.create("Shared", "device-a", pageCount = 2)
        val id = note.document.id
        val session = a.session(note)
        val doomed = note.document.pages[1].id
        session.apply(doomed, listOf(addOp("obj-on-doomed", "device-a", 5)))
        session.materialize()

        val peerDoc = session.document.copy(
            pages = session.document.pages.map {
                if (it.id == doomed) it.copy(deleted = true, deletedEpochMillis = clock.millis) else it
            },
        )
        val peer = BundleWriter.write(
            BundleContents(
                document = peerDoc,
                pages = session.document.pages.associate { ref ->
                    ref.id to PageContents(PageSize.Letter, Background.Blank, emptyList())
                },
                highestClockCounter = 5L,
            ),
        )

        assertIs<MergeReport.Merged>(NoteMerge.merge(session, a.readerOver(peer)))
        session.materialize()

        val reopened = assertNotNull(a.store.open(id))
        assertTrue(assertNotNull(reopened.document.page(doomed)).deleted, "the peer's page deletion did not propagate")
        // The ops are still there — a page delete is a tombstone, never a removal.
        assertEquals(1, a.session(reopened).page(doomed).objects.size, "the deleted page's ops were destroyed")
    }

    @Test
    fun `a malformed sort key from a peer is repaired`() = runTest {
        // `mergePageOrder` takes the lexicographically smaller key without validating it, so a
        // malformed key from a peer's file survives and only surfaces later at `keyForInsert`,
        // which silently appends at the end instead of inserting where the user asked.
        val a = Device("device-a", clock)
        val note = a.store.create("Shared", "device-a")
        val session = a.session(note)

        session.mergeDocument(
            Document(
                id = note.document.id,
                title = note.document.title,
                // A key ending in '0' is not usable as a bound — FractionalKey forbids it.
                pages = listOf(PageRef(PageId("peer-page"), FractionalKey("a0"))),
            ),
        )

        assertTrue(
            session.document.pages.all { it.sortKey.isWellFormed },
            "a malformed sort key survived the merge",
        )
        // And an insert still lands where it was asked to, which is what the repair protects.
        val inserted = session.addPage(index = 1)
        assertEquals(1, session.document.livePages.indexOfFirst { it.id == inserted })
    }

    @Test
    fun `a merge does not compact a note that is still conflicted`() = runTest {
        val a = Device("device-a", clock)
        val note = a.store.create("Shared", "device-a")
        val page = note.document.pages.single().id
        val session = a.session(note)
        session.apply(page, listOf(addOp("obj-1", "device-a", 5)))
        session.materialize()
        clock.millis += TWO_MONTHS_MILLIS

        session.close(NoteSyncState.CONFLICTED)

        assertTrue(
            a.store.readSnapshot(note.document.id, page) == null,
            "a CONFLICTED note was compacted, so a peer's older ops would have nowhere to reintegrate",
        )
    }

    // -----------------------------------------------------------------------
    // `mergeBundle` — the bytes-in door, and the only place TooNew can be decided
    // -----------------------------------------------------------------------

    @Test
    fun `a bundle whose manifest is from the future comes back TooNew and not DifferentDocument`() = runTest {
        // ⚠️ `MergeReport.TooNew` was unreachable for all of M6. The check lived in `merge`, which
        // takes an already-open `BundleReader` — and `BundleReader.open` refuses a schema from the
        // future by *throwing* (SPEC §5.8's `require`), with a private constructor, so no reader that
        // could satisfy the check has ever existed. Both gate implementations therefore wrote
        // `runCatching { BundleReader.open(…) }.getOrNull()` and reported every refusal as
        // `DifferentDocument`: SPEC §7.7 layer 3 kept both copies, correctly, and told the user
        // "this file cannot be merged" instead of "update Inkstone and it opens".
        //
        // Reachable in production because the two schema numbers are written in two places: the XMP
        // marker (which `PulledPdfExtractor` reads, and which short-circuits to TOO_NEW without
        // staging a bundle at all) and the manifest inside the bundle. What arrives here is a file
        // where those disagree.
        val a = Device("device-a", clock)
        val note = a.store.create("Shared", "device-a")
        val page = note.document.pages.single().id
        val session = a.session(note)
        session.apply(page, listOf(addOp("mine", "device-a", 5)))
        session.materialize()

        val fromTheFuture = BundleWriter.write(
            BundleContents(
                document = note.document.copy(schemaVersion = FUTURE_SCHEMA),
                pages = mapOf(page to PageContents(PageSize.Letter, Background.Blank, listOf(addOp("theirs", "b", 9)))),
                highestClockCounter = 9L,
            ),
        )

        val report = NoteMerge.mergeBundle(session, MemoryByteSource(fromTheFuture))

        val tooNew = assertIs<MergeReport.TooNew>(report, "a schema from the future was not recognised as such")
        assertEquals(FUTURE_SCHEMA, tooNew.remoteVersion)
        assertEquals(Document.SCHEMA_VERSION, tooNew.understoodVersion)
        // And nothing was folded. SPEC §5.6: a file from the future must not be opened and above all
        // must not be re-saved in this build's vocabulary, because it would then win the next sync.
        assertEquals(
            listOf("mine"),
            session.page(page).objects.map { it.id.value },
            "the peer's ops were folded out of a bundle this build cannot read",
        )
    }

    @Test
    fun `bytes that are not a bundle at all come back as a different document and never throw`() = runTest {
        // A truncated download, or a stranger's file the extractor let through. The answer has to be
        // a report: an exception out of a WorkManager job is read as a transport failure and retried
        // forever, and layer 3's whole job is to keep both copies without stopping the pass.
        val a = Device("device-a", clock)
        val note = a.store.create("Shared", "device-a")
        val session = a.session(note)

        val report = NoteMerge.mergeBundle(session, MemoryByteSource(byteArrayOf(0x50, 0x4B, 1, 2, 3)))

        val different = assertIs<MergeReport.DifferentDocument>(report)
        // The **local** id, because that is all this side knows: there is no readable document in
        // those bytes to name. `Reconciler` routes it to layer 3 and leaves the ETag alone.
        assertEquals(note.document.id, different.remoteId)
    }

    private companion object {
        /** Comfortably past SPEC §4.7's 30-day causal-stability horizon. */
        const val TWO_MONTHS_MILLIS = 60L * 24 * 60 * 60 * 1000

        /** Comfortably past any schema this build will grow into. */
        const val FUTURE_SCHEMA = 99
    }
}
