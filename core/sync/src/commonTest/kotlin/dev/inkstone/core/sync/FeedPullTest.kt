package dev.inkstone.core.sync

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.ExtractedRemote
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reconciler reading a **change feed** (docs/CLOUD-PROVIDERS.md §4) instead of walking folders.
 *
 * The store here is scripted, so these tests are about what the *reconciler does* with a batch: which
 * per-file decisions it reuses, when it believes a deletion, and — the part that matters most — when
 * it lets the cursor move. A real feed is `OneDriveStoreTest`'s and the stand-in's business.
 *
 * ⚠️ What a green run does not show: that Graph's `delta` really behaves as `OneDriveStore.changes`
 * assumes. Nothing in this repository has seen one.
 */
class FeedPullTest {

    private val note = DocumentId("note-1")
    private val account = testAccount.copy(provider = ProviderKind.ONEDRIVE)

    private fun resource(path: String, id: String, etag: String) = DavResource(
        path = path,
        href = path,
        isCollection = false,
        etag = ETag(etag, weak = false),
        lastModified = null,
        contentLength = 10L,
        ocId = id,
        fileId = id,
        permissions = null,
        recursiveSize = null,
        checksums = emptyList(),
        dataFingerprint = null,
        lockOwner = null,
        locked = false,
        quotaAvailableBytes = null,
    )

    private fun file(path: String, id: String, etag: String) =
        RemoteChange.File(resource(path, id, etag), parentId = "folder-1")

    private class ScriptedFeed(override val account: SyncAccount) : CloudStore {
        var script: () -> DavOutcome<ChangeBatch> = {
            DavOutcome.Ok(ChangeBatch(emptyList(), "c0", DeltaKind.INCREMENTAL))
        }
        var downloadFails: DavFailure? = null
        var confirms: Boolean? = true
        val sinceSeen = mutableListOf<String?>()
        val downloads = mutableListOf<String>()
        var walked = 0

        override val kind: ProviderKind = ProviderKind.ONEDRIVE
        override val supportsUncheckedReplace: Boolean = false
        override val pullShape: PullShape = PullShape.FEED

        override suspend fun changes(since: String?): DavOutcome<ChangeBatch> {
            sinceSeen += since
            return script()
        }

        override suspend fun download(path: String): DavOutcome<DownloadedFile> {
            downloads += path
            downloadFails?.let { return DavOutcome.Failed(it) }
            return DavOutcome.Ok(DownloadedFile("pdf-bytes".encodeToByteArray(), ETag("served", weak = false), "id-x"))
        }

        override suspend fun confirmStillAuthorised(): Boolean? = confirms

        // A feed store is never walked. Reaching any of these is a reconciler bug.
        private fun walk(): Nothing {
            walked++
            error("a feed store must not be walked")
        }

        override suspend fun ensureFolder(path: String): DavOutcome<Unit> = walk()

        override suspend fun put(
            target: RemoteTarget,
            bytes: ByteArray,
            precondition: WritePrecondition,
            mtimeEpochSeconds: Long?,
            md5Hex: String?,
            kind: UploadKind,
        ): DavOutcome<UploadReceipt> = walk()

        override suspend fun move(from: RemoteTarget, toPath: String): DavOutcome<UploadReceipt> = walk()

        override suspend fun trash(target: RemoteTarget): DavOutcome<Unit> = walk()

        override suspend fun listing(path: String, depth: ListingDepth): DavOutcome<List<DavResource>> = walk()

        override suspend fun locate(fileId: Long): DavOutcome<List<DavResource>> = walk()
    }

    private class Setup(
        val account: SyncAccount,
        verdict: (String) -> ExtractedRemote = { normal(null) },
        trashes: (DocumentId) -> Boolean = { true },
        val feed: ScriptedFeed = ScriptedFeed(account),
    ) {
        val trashed = mutableListOf<DocumentId>()
        val harness = ReconcileHarness(
            FakeServer(),
            account = account,
            verdict = verdict,
            trashes = { id ->
                trashed += id
                trashes(id)
            },
            storeFactory = { feed },
        )
        val cursor: SyncCursor? get() = harness.cursors.rows[account.id]
    }

    private fun batch(kind: DeltaKind, cursor: String, vararg changes: RemoteChange) =
        DavOutcome.Ok(ChangeBatch(changes.toList(), cursor, kind))

    private suspend fun Setup.bound(id: String? = "id-1", etag: String? = "e1") =
        harness.bind(note, "Inkstone/Lecture.pdf", ocId = id, etag = etag?.let { ETag(it, weak = false) })

    // -----------------------------------------------------------------------

    @Test
    fun `an idle feed costs one call and moves the cursor`() = runTest {
        val setup = Setup(account)
        setup.feed.script = { DavOutcome.Ok(ChangeBatch(emptyList(), "c1", DeltaKind.INCREMENTAL)) }

        val report = setup.harness.reconciler.reconcile()

        assertEquals(1, report.unchanged)
        assertEquals("c1", setup.cursor?.feedCursor)
        assertEquals(listOf<String?>(null), setup.feed.sinceSeen, "the first read starts from no cursor")
        assertEquals(0, setup.feed.walked)
    }

    @Test
    fun `a file at the version the binding holds is not downloaded`() = runTest {
        val setup = Setup(account)
        setup.bound()
        setup.feed.script = { batch(DeltaKind.INCREMENTAL, "c1", file("Inkstone/Lecture.pdf", "id-1", "e1")) }

        val report = setup.harness.reconciler.reconcile()

        assertTrue(setup.feed.downloads.isEmpty(), "our own upload echoing back must cost nothing")
        assertEquals(0, report.merged)
        assertEquals("c1", setup.cursor?.feedCursor)
    }

    @Test
    fun `a file at a new version is downloaded merged and the cursor moves`() = runTest {
        val setup = Setup(account, verdict = { normal(note) })
        setup.bound()
        setup.feed.script = { batch(DeltaKind.INCREMENTAL, "c1", file("Inkstone/Lecture.pdf", "id-1", "e2")) }

        val report = setup.harness.reconciler.reconcile()

        assertEquals(listOf("Inkstone/Lecture.pdf"), setup.feed.downloads)
        assertEquals(1, report.merged)
        assertEquals("c1", setup.cursor?.feedCursor)
        assertEquals(
            "served",
            setup.harness.bindings.find(note)?.lastSyncedEtag?.value,
            "the version downloaded is the one recorded",
        )
    }

    @Test
    fun `an unknown pdf inside a root is adopted`() = runTest {
        val peer = DocumentId("peer-note")
        val setup = Setup(account, verdict = { normal(peer) })
        setup.feed.script = { batch(DeltaKind.FIRST, "c1", file("Inkstone/FromPeer.pdf", "id-9", "e1")) }

        val report = setup.harness.reconciler.reconcile()

        assertEquals(1, report.adopted)
        assertEquals("c1", setup.cursor?.feedCursor)
    }

    @Test
    fun `an unknown pdf outside every root is not even downloaded`() = runTest {
        val setup = Setup(account, verdict = { normal(DocumentId("x")) })
        setup.feed.script = { batch(DeltaKind.FIRST, "c1", file("Taxes/Return.pdf", "id-9", "e1")) }

        val report = setup.harness.reconciler.reconcile()

        assertTrue(setup.feed.downloads.isEmpty(), "somebody's OneDrive is not ours to read")
        assertEquals(0, report.adopted)
        assertEquals("c1", setup.cursor?.feedCursor)
    }

    @Test
    fun `a note followed out of every root is still seen`() = runTest {
        val setup = Setup(account, verdict = { normal(note) })
        setup.bound()
        // Same id and the version we hold, at a path no root reaches: a move, and it must be noticed.
        setup.feed.script = { batch(DeltaKind.INCREMENTAL, "c1", file("Archive/Lecture.pdf", "id-1", "e1")) }

        val report = setup.harness.reconciler.reconcile()

        assertEquals(1, report.moved)
        assertEquals("Archive/Lecture.pdf", setup.harness.bindings.find(note)?.remotePath)
        assertTrue(setup.feed.downloads.isEmpty(), "a move spends no bytes")
    }

    // -- deletion ----------------------------------------------------------------------------------

    @Test
    fun `a named deletion trashes the note and the cursor moves`() = runTest {
        val setup = Setup(account)
        setup.bound()
        setup.feed.script = { batch(DeltaKind.INCREMENTAL, "c1", RemoteChange.Removed("id-1")) }

        val report = setup.harness.reconciler.reconcile()

        assertEquals(listOf(note), setup.trashed)
        assertEquals(1, report.trashed)
        assertEquals("c1", setup.cursor?.feedCursor)
    }

    @Test
    fun `a file missing from a first enumeration or a resync is never read as a deletion`() = runTest {
        for (kind in listOf(DeltaKind.FIRST, DeltaKind.RESYNC)) {
            val setup = Setup(account)
            setup.bound()
            // The note's file is simply not in the list — and, in these two kinds, also *named* as removed,
            // which a reset server can produce. Neither may trash.
            setup.feed.script =
                { batch(kind, "c1", RemoteChange.Removed("id-1"), file("Inkstone/Other.pdf", "id-2", "e1")) }

            val report = setup.harness.reconciler.reconcile()

            assertTrue(setup.trashed.isEmpty(), "a $kind batch trashed a note")
            assertEquals(0, report.trashed)
            assertNotNull(setup.harness.bindings.find(note), "the binding must survive a $kind batch")
        }
    }

    @Test
    fun `a deletion of a file that was re-created under a new id does not trash the note`() = runTest {
        val setup = Setup(account, verdict = { normal(note) })
        setup.bound()
        setup.feed.script = {
            batch(
                DeltaKind.INCREMENTAL,
                "c1",
                RemoteChange.Removed("id-1"),
                file("Inkstone/Lecture.pdf", "id-2", "e9"),
            )
        }

        setup.harness.reconciler.reconcile()

        assertTrue(setup.trashed.isEmpty(), "the note's file still exists — under a new id")
        assertEquals("id-2", setup.harness.bindings.find(note)?.remoteOcId)
    }

    @Test
    fun `a deletion with an editor holding the note keeps the cursor so the next pass tries again`() = runTest {
        var editorHolds = true
        val setup = Setup(account, trashes = { !editorHolds })
        setup.bound()
        setup.feed.script = { batch(DeltaKind.INCREMENTAL, "c1", RemoteChange.Removed("id-1")) }

        val first = setup.harness.reconciler.reconcile()
        assertEquals(0, first.trashed)
        assertNull(setup.cursor?.feedCursor, "the cursor moved past a deletion nobody acted on")

        editorHolds = false
        setup.harness.clock.advance(1L)
        val second = setup.harness.reconciler.reconcile()
        assertEquals(1, second.trashed)
        assertEquals("c1", setup.cursor?.feedCursor)
    }

    // -- the cursor --------------------------------------------------------------------------------

    @Test
    fun `a failed download keeps the cursor so the change comes back`() = runTest {
        val setup = Setup(account, verdict = { normal(note) })
        setup.bound()
        setup.feed.downloadFails = DavFailure.Transport("dropped")
        setup.feed.script = { batch(DeltaKind.INCREMENTAL, "c1", file("Inkstone/Lecture.pdf", "id-1", "e2")) }

        val report = setup.harness.reconciler.reconcile()

        assertEquals(1, report.failed)
        assertNull(setup.cursor?.feedCursor, "the cursor moved past a change that was never applied")
    }

    @Test
    fun `the pass limit keeps the cursor`() = runTest {
        val setup = Setup(account, verdict = { normal(note) })
        setup.bound()
        setup.feed.script = {
            batch(
                DeltaKind.INCREMENTAL,
                "c1",
                file("Inkstone/Lecture.pdf", "id-1", "e2"),
                file("Inkstone/Other.pdf", "id-7", "e2"),
            )
        }

        setup.harness.reconciler.reconcile(limit = 1)

        assertNull(setup.cursor?.feedCursor, "work was left undone and the cursor still moved")
    }

    @Test
    fun `the next read resumes from the stored cursor`() = runTest {
        val setup = Setup(account)
        setup.feed.script = { DavOutcome.Ok(ChangeBatch(emptyList(), "c1", DeltaKind.FIRST)) }
        setup.harness.reconciler.reconcile()
        setup.harness.clock.advance(1L)
        setup.feed.script = { DavOutcome.Ok(ChangeBatch(emptyList(), "c2", DeltaKind.INCREMENTAL)) }

        setup.harness.reconciler.reconcile()

        assertEquals(listOf(null, "c1"), setup.feed.sinceSeen)
        assertEquals("c2", setup.cursor?.feedCursor)
    }

    // -- rule 5 ------------------------------------------------------------------------------------

    @Test
    fun `a refused feed halts the account and then sends nothing at all`() = runTest {
        val setup = Setup(account)
        setup.feed.confirms = false
        setup.feed.script = { DavOutcome.Failed(DavFailure.Unauthorized) }

        val first = setup.harness.reconciler.reconcile()
        assertEquals(setOf(account.id), first.haltedAccounts)
        assertEquals(AccountState.NEEDS_REAUTH, setup.harness.accounts.find(account.id)?.state)
        assertEquals(1, setup.feed.sinceSeen.size)

        setup.harness.clock.advance(1L)
        setup.harness.reconciler.reconcile()
        assertEquals(1, setup.feed.sinceSeen.size, "a halted account was asked for its feed again")
    }

    @Test
    fun `a refused feed that the confirmation could not settle is cooled down not retried every poll`() = runTest {
        val setup = Setup(account)
        setup.feed.confirms = null // could not be reached: conclude nothing, stay ACTIVE
        setup.feed.script = { DavOutcome.Failed(DavFailure.Unauthorized) }

        setup.harness.reconciler.reconcile()
        val cooldown = assertNotNull(setup.cursor).refusedUntilEpochMillis
        assertTrue(cooldown > setup.harness.clock.nowEpochMillis(), "no cooldown was opened")

        setup.harness.clock.advance(1L)
        setup.harness.reconciler.reconcile()
        assertEquals(1, setup.feed.sinceSeen.size, "the cooldown did not stop a second request")
    }

    @Test
    fun `a feed store is never walked`() = runTest {
        val setup = Setup(account, verdict = { normal(note) })
        setup.bound()
        setup.feed.script = { batch(DeltaKind.INCREMENTAL, "c1", file("Inkstone/Lecture.pdf", "id-1", "e2")) }

        setup.harness.reconciler.reconcile()

        assertEquals(0, setup.feed.walked)
    }
}
