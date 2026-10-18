package dev.inkstone.app.sync.cloud

import dev.inkstone.core.sync.AccessTokenSource
import dev.inkstone.core.sync.BearerResult
import dev.inkstone.core.sync.ChangeBatch
import dev.inkstone.core.sync.CloudStore
import dev.inkstone.core.sync.DavOutcome
import dev.inkstone.core.sync.DeltaKind
import dev.inkstone.core.sync.DriveStore
import dev.inkstone.core.sync.ForcedRefresh
import dev.inkstone.core.sync.NextcloudTransport
import dev.inkstone.core.sync.ProviderKind
import dev.inkstone.core.sync.RemoteChange
import dev.inkstone.core.sync.RemoteTarget
import dev.inkstone.core.sync.SyncAccount
import dev.inkstone.core.sync.WritePrecondition
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `DriveStore.changes` — Google Drive's `changes` feed — against [DriveStandIn].
 *
 * ⚠️ As everywhere in this package, a green run means the reader does what `DriveFeedReader`'s comment says
 * *as the stand-in encodes it*. What a real Drive answers to a stale page token, whether `trashed` arrives
 * on a change's file, and what a folder rename reports are all read from documentation and not observed.
 */
class DriveFeedTest {

    private val server = DriveStandIn()
    private val transport = NextcloudTransport.create()

    private val tokens = object : AccessTokenSource {
        override suspend fun bearer(): BearerResult = BearerResult.Token("tok")

        override suspend fun refreshAfterRefusal(): ForcedRefresh = ForcedRefresh.Refreshed
    }

    @AfterTest
    fun tearDown() {
        server.close()
        transport.close()
    }

    private fun store(): CloudStore = DriveStore(
        SyncAccount("gd-1", server.baseUrl, "me@gmail.com", "perm-1", provider = ProviderKind.GOOGLE_DRIVE),
        transport,
        tokens,
    )

    private suspend fun CloudStore.read(since: String? = null): ChangeBatch = when (val out = changes(since, listOf("Inkstone"))) {
        is DavOutcome.Ok -> out.value
        is DavOutcome.Failed -> error("expected a batch but got ${out.error.describe}")
    }

    private fun ChangeBatch.files(): Map<String, RemoteChange.File> =
        changes.filterIsInstance<RemoteChange.File>().associateBy { it.resource.path }

    private fun ChangeBatch.removed(): Set<String> = changes.filterIsInstance<RemoteChange.Removed>().map { it.id }.toSet()

    @Test
    fun `a first enumeration lists the pdfs with their paths and the version to check against`() = runTest {
        server.seedFile("Inkstone/Lecture.pdf", "a".encodeToByteArray())
        server.seedFile("Inkstone/Physics/Waves.pdf", "b".encodeToByteArray())

        val batch = store().read()

        assertEquals(DeltaKind.FIRST, batch.kind)
        assertEquals(setOf("Inkstone/Lecture.pdf", "Inkstone/Physics/Waves.pdf"), batch.files().keys)
        val lecture = batch.files().getValue("Inkstone/Lecture.pdf")
        assertEquals(server.idAt("Inkstone/Lecture.pdf"), lecture.resource.ocId)
        assertEquals(server.headRevisionAt("Inkstone/Lecture.pdf"), lecture.resource.etag?.value)
        assertEquals(server.idAt("Inkstone"), lecture.parentId)
    }

    @Test
    fun `a trashed file is not listed by a first enumeration`() = runTest {
        server.seedFile("Inkstone/Gone.pdf", "a".encodeToByteArray())
        server.seedFile("Inkstone/Here.pdf", "b".encodeToByteArray())
        server.trashBehindTheirBack("Inkstone/Gone.pdf")

        assertEquals(setOf("Inkstone/Here.pdf"), store().read().files().keys)
    }

    @Test
    fun `an incremental read after nothing happened is empty`() = runTest {
        server.seedFile("Inkstone/Lecture.pdf", "a".encodeToByteArray())
        val first = store().read()

        val again = store().read(first.cursor)

        assertEquals(DeltaKind.INCREMENTAL, again.kind)
        assertTrue(again.changes.isEmpty(), "nothing changed, but ${again.changes.size} changes came back")
    }

    @Test
    fun `an edit comes back as that file at its resolved path with the new version`() = runTest {
        server.seedFile("Inkstone/Physics/Waves.pdf", "v1".encodeToByteArray())
        server.seedFile("Inkstone/Other.pdf", "o".encodeToByteArray())
        val first = store().read()

        server.writeBehindTheirBack("Inkstone/Physics/Waves.pdf", "v2".encodeToByteArray())
        val batch = store().read(first.cursor)

        assertEquals(setOf("Inkstone/Physics/Waves.pdf"), batch.files().keys)
        assertEquals(server.headRevisionAt("Inkstone/Physics/Waves.pdf"), batch.files().values.single().resource.etag?.value)
    }

    @Test
    fun `a file trashed in drive is named as removed`() = runTest {
        server.seedFile("Inkstone/Lecture.pdf", "a".encodeToByteArray())
        val id = server.idAt("Inkstone/Lecture.pdf")!!
        val first = store().read()

        server.trashBehindTheirBack("Inkstone/Lecture.pdf")
        val batch = store().read(first.cursor)

        assertEquals(setOf(id), batch.removed())
        assertTrue(batch.files().isEmpty())
    }

    @Test
    fun `a rename is one change under the new name with the same id`() = runTest {
        server.seedFile("Inkstone/Old.pdf", "a".encodeToByteArray())
        val id = server.idAt("Inkstone/Old.pdf")
        val first = store().read()

        store().move(RemoteTarget("Inkstone/Old.pdf", id), "Inkstone/New.pdf")
        val batch = store().read(first.cursor)

        assertEquals(setOf("Inkstone/New.pdf"), batch.files().keys)
        assertEquals(id, batch.files().values.single().resource.ocId)
    }

    @Test
    fun `a page token drive no longer honours starts over as a resync`() = runTest {
        server.seedFile("Inkstone/Lecture.pdf", "a".encodeToByteArray())
        val first = store().read()
        server.expireChangeTokens = true

        val batch = store().read(first.cursor)

        assertEquals(DeltaKind.RESYNC, batch.kind, "after a stale token the absence of a file proves nothing")
        assertEquals(setOf("Inkstone/Lecture.pdf"), batch.files().keys)
    }

    @Test
    fun `paging is followed to the end`() = runTest {
        val first = store().read()
        server.pageCap = 2
        repeat(5) { server.seedFile("Inkstone/Note$it.pdf", "x".encodeToByteArray()) }

        assertEquals(5, store().read(first.cursor).files().size)
    }

    @Test
    fun `a change made after the first read is seen on the next`() = runTest {
        val first = store().read()
        server.seedFile("Elsewhere/Note.pdf", "x".encodeToByteArray())

        assertEquals(setOf("Elsewhere/Note.pdf"), store().read(first.cursor).files().keys)
    }

    @Test
    fun `the store's own write is not mistaken for somebody else's change that needs merging`() = runTest {
        val store = store()
        val created = store.put(RemoteTarget("Inkstone/Lecture.pdf"), "v1".encodeToByteArray(), WritePrecondition.CreateOnly, null, null)
        val first = store.read()

        // The feed reports the file, at the version the store itself recorded, so the reconciler's
        // "has it changed since I last saw it" comparison finds nothing to do.
        val receipt = (created as DavOutcome.Ok).value
        assertEquals(receipt.etag?.value, first.files().getValue("Inkstone/Lecture.pdf").resource.etag?.value)
    }
}
