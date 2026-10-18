package dev.inkstone.app.sync.cloud

import dev.inkstone.core.sync.AccessTokenSource
import dev.inkstone.core.sync.BearerResult
import dev.inkstone.core.sync.CloudStore
import dev.inkstone.core.sync.DavFailure
import dev.inkstone.core.sync.DavOutcome
import dev.inkstone.core.sync.DriveStore
import dev.inkstone.core.sync.ETag
import dev.inkstone.core.sync.ForcedRefresh
import dev.inkstone.core.sync.ListingDepth
import dev.inkstone.core.sync.NextcloudTransport
import dev.inkstone.core.sync.ProviderKind
import dev.inkstone.core.sync.RemoteTarget
import dev.inkstone.core.sync.SyncAccount
import dev.inkstone.core.sync.UploadReceipt
import dev.inkstone.core.sync.WritePrecondition
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `DriveStore` against [DriveStandIn]: the production transport and parsing, a fake service behind a
 * real socket.
 *
 * ⚠️ **What a green run means.** The store does what its class comment says Drive's documentation allows,
 * *as the stand-in encodes it*. Nothing here has met Google. Above all, the check-write-verify protocol
 * exists because Drive ignores `If-Match` (the stand-in does too), and **whether a real Drive pins the new
 * head with `keepRevisionForever`, orders `revisions.list` the way the store assumes, and downloads a pinned
 * revision is exactly what has not been observed.**
 *
 * The race is made with `DriveStandIn.beforeCommit`: a second writer landing after the store's own check
 * and before its bytes.
 */
class DriveStoreTest {

    private val server = DriveStandIn()
    private val transport = NextcloudTransport.create()

    private class Tokens : AccessTokenSource {
        var refreshes = 0

        override suspend fun bearer(): BearerResult = BearerResult.Token("tok")

        override suspend fun refreshAfterRefusal(): ForcedRefresh {
            refreshes++
            return ForcedRefresh.Refreshed
        }
    }

    private val tokens = Tokens()

    @AfterTest
    fun tearDown() {
        server.close()
        transport.close()
    }

    private fun store(): CloudStore = DriveStore(
        SyncAccount(
            id = "gd-1",
            serverUrl = server.baseUrl,
            loginName = "me@gmail.com",
            uid = "perm-1",
            provider = ProviderKind.GOOGLE_DRIVE,
        ),
        transport,
        tokens,
    )

    private suspend fun <T> DavOutcome<T>.ok(): T = when (this) {
        is DavOutcome.Ok -> value
        is DavOutcome.Failed -> error("expected success but got ${error.describe}")
    }

    private fun DavOutcome<*>.failure(): DavFailure = (this as DavOutcome.Failed).error

    private suspend fun CloudStore.create(path: String, text: String): UploadReceipt =
        put(RemoteTarget(path), text.encodeToByteArray(), WritePrecondition.CreateOnly, null, null).ok()

    private suspend fun CloudStore.replace(path: String, text: String, basedOn: ETag, id: String? = null) =
        put(RemoteTarget(path, id), text.encodeToByteArray(), WritePrecondition.IfMatch(basedOn), null, null)

    // ------------------------------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a create makes the folders and the file and returns an id and a version`() = runTest {
        val receipt = store().create("Inkstone/Physics/Waves.pdf", "v1")

        assertContentEquals("v1".encodeToByteArray(), server.bytesAt("Inkstone/Physics/Waves.pdf"))
        assertEquals(server.idAt("Inkstone/Physics/Waves.pdf"), receipt.ocId)
        assertEquals(server.headRevisionAt("Inkstone/Physics/Waves.pdf"), receipt.etag?.value)
    }

    @Test
    fun `a create refuses a name that is already there and makes no second file`() = runTest {
        val store = store()
        store.create("Inkstone/Lecture.pdf", "mine")

        val out = store.put(
            RemoteTarget("Inkstone/Lecture.pdf"),
            "other".encodeToByteArray(),
            WritePrecondition.CreateOnly,
            null,
            null,
        )

        assertIs<DavFailure.PreconditionFailed>(out.failure())
        assertEquals(listOf("Lecture.pdf"), server.namesIn("Inkstone"))
        assertContentEquals("mine".encodeToByteArray(), server.bytesAt("Inkstone/Lecture.pdf"))
    }

    @Test
    fun `a name with a quote in it is found`() = runTest {
        val store = store()
        store.create("Inkstone/O'Brien's notes.pdf", "x")

        assertEquals(listOf("O'Brien's notes.pdf"), server.namesIn("Inkstone"))
        val listed = store.listing("Inkstone", ListingDepth.CHILDREN).ok()
        assertEquals(listOf("Inkstone/O'Brien's notes.pdf"), listed.map { it.path })
    }

    // ------------------------------------------------------------------------------------------
    // Replace: check, write pinned, verify
    // ------------------------------------------------------------------------------------------

    @Test
    fun `an overwrite against the version it was based on lands and moves the version`() = runTest {
        val store = store()
        val first = store.create("Inkstone/Lecture.pdf", "v1")

        val second = store.replace("Inkstone/Lecture.pdf", "v2", first.etag!!, first.ocId).ok()

        assertContentEquals("v2".encodeToByteArray(), server.bytesAt("Inkstone/Lecture.pdf"))
        assertNotEquals(first.etag?.value, second.etag?.value)
        assertEquals(server.headRevisionAt("Inkstone/Lecture.pdf"), second.etag?.value)
    }

    @Test
    fun `the new head is pinned and the revision it replaced is let go`() = runTest {
        val store = store()
        val first = store.create("Inkstone/Lecture.pdf", "v1")

        store.replace("Inkstone/Lecture.pdf", "v2", first.etag!!, first.ocId).ok()

        val revisions = server.revisionsAt("Inkstone/Lecture.pdf")
        assertEquals(2, revisions.size)
        assertFalse(
            revisions.first().keepForever,
            "the replaced revision must be unpinned (never deleted) or pins pile up to Drive's cap",
        )
        assertTrue(
            revisions.last().keepForever,
            "the new head must be pinned, so a verify can find what lies behind it",
        )
    }

    @Test
    fun `an overwrite against a stale version writes nothing and says conflict`() = runTest {
        val store = store()
        val first = store.create("Inkstone/Lecture.pdf", "v1")
        server.writeBehindTheirBack("Inkstone/Lecture.pdf", "theirs".encodeToByteArray())
        val commits = server.contentCommits.count

        val out = store.replace("Inkstone/Lecture.pdf", "mine", first.etag!!, first.ocId)

        assertIs<DavFailure.PreconditionFailed>(out.failure())
        assertEquals(commits, server.contentCommits.count, "a stale write sent bytes")
        assertContentEquals("theirs".encodeToByteArray(), server.bytesAt("Inkstone/Lecture.pdf"))
    }

    @Test
    fun `a writer who lands between the check and the write is kept as a file beside the note`() = runTest {
        val store = store()
        val first = store.create("Inkstone/Lecture.pdf", "v1")
        // After the store has checked and before its bytes land: somebody else writes.
        server.beforeCommit = { server.writeBehindTheirBack("Inkstone/Lecture.pdf", "THEIRS".encodeToByteArray()) }

        val out = store.replace("Inkstone/Lecture.pdf", "MINE", first.etag!!, first.ocId)

        // Our write is the head, as asked…
        assertIs<DavOutcome.Ok<UploadReceipt>>(out)
        assertContentEquals("MINE".encodeToByteArray(), server.bytesAt("Inkstone/Lecture.pdf"))
        // …and what it displaced is a file the user can open, not a revision nobody will look at.
        val copies = server.namesIn("Inkstone").filter { it.contains("displaced") }
        assertEquals(1, copies.size, "the displaced write was not kept: ${server.namesIn("Inkstone")}")
        assertContentEquals("THEIRS".encodeToByteArray(), server.bytesAt("Inkstone/${copies.single()}"))
        // …and their revision is pinned, so Drive will not purge it behind our back.
        val theirs = server.revisionsAt("Inkstone/Lecture.pdf").first { String(it.bytes) == "THEIRS" }
        assertTrue(theirs.keepForever)
    }

    @Test
    fun `after a clean overwrite nothing was displaced and no copy exists`() = runTest {
        val store = store()
        val first = store.create("Inkstone/Lecture.pdf", "v1")

        store.replace("Inkstone/Lecture.pdf", "v2", first.etag!!, first.ocId).ok()

        assertEquals(listOf("Lecture.pdf"), server.namesIn("Inkstone"))
    }

    @Test
    fun `an overwrite by path with no id finds the file and checks it`() = runTest {
        val store = store()
        val first = store.create("Inkstone/Lecture.pdf", "v1")

        store.replace("Inkstone/Lecture.pdf", "v2", first.etag!!).ok()

        assertContentEquals("v2".encodeToByteArray(), server.bytesAt("Inkstone/Lecture.pdf"))
    }

    @Test
    fun `an unchecked or weak overwrite is refused outright`() = runTest {
        val store = store()
        store.create("Inkstone/Lecture.pdf", "v1")
        val commits = server.contentCommits.count

        val unchecked = store.put(
            RemoteTarget("Inkstone/Lecture.pdf"),
            "x".encodeToByteArray(),
            WritePrecondition.Unchecked,
            null,
            null,
        )
        val weak = store.put(
            RemoteTarget("Inkstone/Lecture.pdf"),
            "x".encodeToByteArray(),
            WritePrecondition.IfMatch(ETag("whatever", weak = true)),
            null,
            null,
        )

        assertIs<DavFailure.Forbidden>(unchecked.failure())
        assertIs<DavFailure.Forbidden>(weak.failure())
        assertEquals(commits, server.contentCommits.count)
    }

    // ------------------------------------------------------------------------------------------
    // Rule 1 and rule 5, off the wire
    // ------------------------------------------------------------------------------------------

    @Test
    fun `nothing is ever deleted and the upload session is sent no bearer token`() = runTest {
        val store = store()
        val first = store.create("Inkstone/Lecture.pdf", "v1")
        store.replace("Inkstone/Lecture.pdf", "v2", first.etag!!, first.ocId).ok()
        store.trash(RemoteTarget("Inkstone/Lecture.pdf", first.ocId)).ok()

        assertTrue(server.log.none { it.startsWith("DELETE") }, "a DELETE reached Drive: ${server.log}")
        assertTrue(server.sessionAuthorization.all { it == null }, "a bearer token went to an upload session")
    }

    @Test
    fun `trash sets trashed and the bytes are still there`() = runTest {
        val store = store()
        val receipt = store.create("Inkstone/Lecture.pdf", "v1")

        store.trash(RemoteTarget("Inkstone/Lecture.pdf", receipt.ocId)).ok()

        assertTrue(server.isTrashed("Inkstone/Lecture.pdf"))
        assertNull(server.idAt("Inkstone/Lecture.pdf"), "a trashed file must not be found by path")
        assertEquals(emptyList(), server.namesIn("Inkstone"))
    }

    @Test
    fun `a refused token is unauthorised and nothing is retried`() = runTest {
        val store = store()
        store.create("Inkstone/Lecture.pdf", "v1")
        server.rejectAccessTokens = true
        val before = server.api.count

        val out = store.listing("Inkstone", ListingDepth.CHILDREN)

        assertEquals(DavFailure.Unauthorized, out.failure())
        assertEquals(before + 1, server.api.count, "a 401 was retried")
        assertEquals(0, tokens.refreshes, "listing must not refresh on its own; only confirmStillAuthorised may")
    }

    @Test
    fun `confirming after a 401 refreshes once and asks Drive who we are`() = runTest {
        val store = store()
        server.rejectAccessTokens = true

        val confirmed = store.confirmStillAuthorised()

        assertEquals(false, confirmed, "Drive still refuses the fresh token: the credential is dead")
        assertEquals(1, tokens.refreshes)
    }

    // ------------------------------------------------------------------------------------------
    // Reads, move
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a listing shows folders and files with the version the store will check against`() = runTest {
        val store = store()
        store.create("Inkstone/Physics/Waves.pdf", "a")
        store.create("Inkstone/Lecture.pdf", "b")

        val listed = store.listing("Inkstone", ListingDepth.CHILDREN).ok().associateBy { it.path }

        assertEquals(setOf("Inkstone/Physics", "Inkstone/Lecture.pdf"), listed.keys)
        assertTrue(listed.getValue("Inkstone/Physics").isCollection)
        assertEquals(server.headRevisionAt("Inkstone/Lecture.pdf"), listed.getValue("Inkstone/Lecture.pdf").etag?.value)
    }

    @Test
    fun `paging is followed to the end`() = runTest {
        val store = store()
        server.pageCap = 2
        repeat(5) { store.create("Inkstone/Note$it.pdf", "x") }

        assertEquals(5, store.listing("Inkstone", ListingDepth.CHILDREN).ok().size)
    }

    @Test
    fun `a download returns the bytes, the version and the id`() = runTest {
        val store = store()
        val receipt = store.create("Inkstone/Lecture.pdf", "hello")

        val file = store.download("Inkstone/Lecture.pdf").ok()

        assertContentEquals("hello".encodeToByteArray(), file.bytes)
        assertEquals(receipt.etag?.value, file.etag?.value)
        assertEquals(receipt.ocId, file.ocId)
    }

    @Test
    fun `a missing path is not found rather than an empty answer`() = runTest {
        assertIs<DavFailure.NotFound>(store().download("Inkstone/Nope.pdf").failure())
        assertIs<DavFailure.NotFound>(store().listing("Inkstone/Nope", ListingDepth.CHILDREN).failure())
    }

    @Test
    fun `two files with one name resolve to the older and a move onto a taken name is refused`() = runTest {
        val store = store()
        val a = store.create("Inkstone/A.pdf", "a")
        store.create("Inkstone/B.pdf", "b")

        val clash = store.move(RemoteTarget("Inkstone/A.pdf", a.ocId), "Inkstone/B.pdf")

        assertIs<DavFailure.PreconditionFailed>(clash.failure())
        assertEquals(setOf("A.pdf", "B.pdf"), server.namesIn("Inkstone").toSet())
    }

    @Test
    fun `a move renames and keeps the version`() = runTest {
        val store = store()
        val a = store.create("Inkstone/A.pdf", "a")

        val moved = store.move(RemoteTarget("Inkstone/A.pdf", a.ocId), "Inkstone/Done/A2.pdf").ok()

        assertEquals(listOf("A2.pdf"), server.namesIn("Inkstone/Done"))
        assertEquals(a.etag?.value, moved.etag?.value, "a rename is not a change of content")
        assertNotNull(server.idAt("Inkstone/Done/A2.pdf"))
    }

    @Test
    fun `recordings are refused rather than stored as a pdf`() = runTest {
        val out = store().put(
            RemoteTarget("Inkstone/.media/a.opus"),
            "x".encodeToByteArray(),
            WritePrecondition.CreateOnly,
            null,
            null,
            dev.inkstone.core.sync.UploadKind.AUDIO_OGG,
        )

        assertIs<DavFailure.Forbidden>(out.failure())
    }
}
