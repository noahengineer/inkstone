package dev.inkstone.app.sync.cloud

import dev.inkstone.app.sync.matrix.MatrixClock
import dev.inkstone.core.sync.ChangeBatch
import dev.inkstone.core.sync.DavFailure
import dev.inkstone.core.sync.DavOutcome
import dev.inkstone.core.sync.DeltaKind
import dev.inkstone.core.sync.MicrosoftOAuthConfig
import dev.inkstone.core.sync.MicrosoftTokenSource
import dev.inkstone.core.sync.NextcloudTransport
import dev.inkstone.core.sync.OAuthSecret
import dev.inkstone.core.sync.OneDriveStore
import dev.inkstone.core.sync.ProviderKind
import dev.inkstone.core.sync.RemoteChange
import dev.inkstone.core.sync.SyncAccount
import dev.inkstone.core.sync.TokenVault
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `OneDriveStore.changes` against [GraphStandIn]'s `delta`.
 *
 * ⚠️ **Every behaviour here is the stand-in's reading of Microsoft's documentation of `delta`, not
 * Graph's.** In particular: that a changed item's parents are repeated, that a delta item has no
 * `parentReference.path`, that a folder rename omits its descendants, and what a `410` looks like.
 * `tools/cloud/graph_spike.py` M-M7 is the check, and it has not run.
 */
class OneDriveFeedTest {

    private val server = GraphStandIn()
    private val clock = MatrixClock()
    private val transport = NextcloudTransport.create()

    @AfterTest
    fun tearDown() {
        server.close()
        transport.close()
    }

    private val vault = object : TokenVault {
        var secret: OAuthSecret? = OAuthSecret(server.initialRefreshToken)

        override suspend fun get(accountId: String): OAuthSecret? = secret

        override suspend fun put(accountId: String, secret: OAuthSecret) {
            this.secret = secret
        }

        override suspend fun remove(accountId: String) {
            secret = null
        }
    }

    private fun store() = OneDriveStore(
        SyncAccount(
            id = "od-1",
            serverUrl = server.baseUrl,
            loginName = "me@example.com",
            uid = server.driveId,
            provider = ProviderKind.ONEDRIVE,
        ),
        transport,
        MicrosoftTokenSource(
            transport,
            MicrosoftOAuthConfig("client-1", tokenEndpoint = server.tokenUrl),
            vault,
            clock,
            "od-1",
        ),
    )

    private suspend fun OneDriveStore.read(
        since: String? = null,
        scope: List<String> = listOf("Inkstone"),
    ): ChangeBatch = when (val out = changes(since, scope)) {
        is DavOutcome.Ok -> out.value
        is DavOutcome.Failed -> error("expected a batch but got ${out.error.describe}")
    }

    private fun ChangeBatch.files(): Map<String, RemoteChange.File> =
        changes.filterIsInstance<RemoteChange.File>().associateBy { it.resource.path }

    private fun ChangeBatch.removed(): Set<String> =
        changes.filterIsInstance<RemoteChange.Removed>().map { it.id }.toSet()

    // -----------------------------------------------------------------------

    @Test
    fun `a first enumeration lists only the pdfs and resolves their paths from the folders it was given`() = runTest {
        server.seedFile("Inkstone/Lecture.pdf", "a".encodeToByteArray())
        server.seedFile("Inkstone/Physics/Waves.PDF", "b".encodeToByteArray())
        server.seedFile("Inkstone/readme.txt", "c".encodeToByteArray())
        server.seedFile("Photos/cat.jpg", "d".encodeToByteArray())

        val batch = store().read()

        assertEquals(DeltaKind.FIRST, batch.kind)
        assertEquals(setOf("Inkstone/Lecture.pdf", "Inkstone/Physics/Waves.PDF"), batch.files().keys)
        val lecture = batch.files().getValue("Inkstone/Lecture.pdf")
        assertEquals(server.idAt("Inkstone/Lecture.pdf"), lecture.resource.ocId)
        assertEquals(server.etagAt("Inkstone/Lecture.pdf")?.trim('"'), lecture.resource.etag?.value)
        assertEquals(server.idAt("Inkstone"), lecture.parentId)
        assertTrue(batch.cursor.startsWith(server.baseUrl), "the cursor must be a link on this account's own host")
    }

    @Test
    fun `a path does not depend on which order the feed lists a folder and its children`() = runTest {
        server.deltaChildrenFirst = true
        val first = store().read()
        server.seedFile("A/B/C/Deep.pdf", "x".encodeToByteArray())

        assertEquals(setOf("A/B/C/Deep.pdf"), store().read(first.cursor).files().keys)
    }

    @Test
    fun `paging is followed to the end`() = runTest {
        server.deltaPageSize = 2
        val first = store().read()
        repeat(5) { server.seedFile("Inkstone/Note$it.pdf", "x".encodeToByteArray()) }
        val before = server.delta.count

        val batch = store().read(first.cursor)

        assertEquals(5, batch.files().size)
        assertTrue(server.delta.count - before > 1, "five files and a folder tree cannot fit one page of two")
    }

    @Test
    fun `an incremental read after nothing happened is empty and keeps the cursor moving`() = runTest {
        server.seedFile("Inkstone/Lecture.pdf", "a".encodeToByteArray())
        val first = store().read()

        val again = store().read(first.cursor)

        assertEquals(DeltaKind.INCREMENTAL, again.kind)
        assertTrue(again.changes.isEmpty(), "nothing changed, but ${again.changes.size} changes came back")
    }

    @Test
    fun `an edit comes back as that file alone at its resolved path`() = runTest {
        server.seedFile("Inkstone/Physics/Waves.pdf", "v1".encodeToByteArray())
        server.seedFile("Inkstone/Other.pdf", "o".encodeToByteArray())
        val first = store().read()

        server.editBehindTheirBack("Inkstone/Physics/Waves.pdf", "v2".encodeToByteArray())
        val batch = store().read(first.cursor)

        assertEquals(
            setOf("Inkstone/Physics/Waves.pdf"),
            batch.files().keys,
            "the delta's own parents resolve the path",
        )
        assertEquals(
            server.etagAt("Inkstone/Physics/Waves.pdf")?.trim('"'),
            batch.files().values.single().resource.etag?.value,
        )
    }

    @Test
    fun `a parent the feed did not repeat is asked for once and the path still resolves`() = runTest {
        server.seedFile("Inkstone/Physics/Waves.pdf", "v1".encodeToByteArray())
        val first = store().read()
        server.deltaOmitsAncestors = true
        server.editBehindTheirBack("Inkstone/Physics/Waves.pdf", "v2".encodeToByteArray())

        val batch = store().read(first.cursor)

        assertEquals(setOf("Inkstone/Physics/Waves.pdf"), batch.files().keys)
    }

    @Test
    fun `a deletion is named by id`() = runTest {
        server.seedFile("Inkstone/Lecture.pdf", "a".encodeToByteArray())
        val id = server.idAt("Inkstone/Lecture.pdf")!!
        val first = store().read()

        server.removeBehindTheirBack("Inkstone/Lecture.pdf")
        val batch = store().read(first.cursor)

        assertEquals(setOf(id), batch.removed())
        assertTrue(batch.files().isEmpty())
    }

    @Test
    fun `a rename is one change under the new name with the same id`() = runTest {
        server.seedFile("Inkstone/Old.pdf", "a".encodeToByteArray())
        val id = server.idAt("Inkstone/Old.pdf")
        val first = store().read()

        server.renameBehindTheirBack("Inkstone/Old.pdf", "New.pdf")
        val batch = store().read(first.cursor)

        assertEquals(setOf("Inkstone/New.pdf"), batch.files().keys)
        assertEquals(id, batch.files().values.single().resource.ocId)
    }

    @Test
    fun `a folder rename reports nothing about the files under it (the documented limit)`() = runTest {
        // Graph: "renaming a folder doesn't result in any descendants of the folder being returned".
        // So a binding under a renamed folder keeps its old *path*; it stays addressable by id, which is
        // what writes use. This test pins the limit so it cannot be mistaken for handled.
        server.seedFile("Inkstone/Lecture.pdf", "a".encodeToByteArray())
        val first = store().read()

        server.renameBehindTheirBack("Inkstone", "Notes")
        val batch = store().read(first.cursor)

        assertTrue(batch.files().isEmpty(), "the stand-in follows the docs here; a file was reported")
    }

    @Test
    fun `a cursor the service refused starts over as a resync`() = runTest {
        server.seedFile("Inkstone/Lecture.pdf", "a".encodeToByteArray())
        val first = store().read()
        server.expireDeltaTokens = true

        val batch = store().read(first.cursor)

        assertEquals(DeltaKind.RESYNC, batch.kind, "after a 410 the absence of a file proves nothing")
        assertEquals(setOf("Inkstone/Lecture.pdf"), batch.files().keys)
    }

    // -- the first pass must not be the whole drive --------------------------------------------------
    //
    // Found on the first real sign-in: a first enumeration asked Graph for `root/delta` with no token,
    // which is every file in the account. On a real OneDrive (game folders, mod packs, a ComfyUI install)
    // that was ~118 MB of metadata and a pull that never finished. The stand-in served it without
    // complaint, because a small fake drive makes "everything" cheap — the fixture was more generous
    // than production, which is this repo's oldest lesson.

    @Test
    fun `a first enumeration never asks for the whole drive`() = runTest {
        repeat(30) { server.seedFile("Games/Pack$it/manual.pdf", "x".encodeToByteArray()) }
        server.seedFile("Inkstone/Lecture.pdf", "a".encodeToByteArray())

        val batch = store().read()

        assertEquals(0, server.fullEnumerations.count, "a first pass walked the whole drive")
        assertEquals(1, server.latestTokens.count, "a first pass must take the feed's latest cursor")
        assertEquals(setOf("Inkstone/Lecture.pdf"), batch.files().keys, "only the scope is listed")
        assertEquals(DeltaKind.FIRST, batch.kind)
    }

    @Test
    fun `a resync is scoped the same way`() = runTest {
        repeat(10) { server.seedFile("Games/Pack$it/manual.pdf", "x".encodeToByteArray()) }
        server.seedFile("Inkstone/Lecture.pdf", "a".encodeToByteArray())
        val first = store().read()
        server.expireDeltaTokens = true

        val batch = store().read(first.cursor)

        assertEquals(DeltaKind.RESYNC, batch.kind)
        assertEquals(0, server.fullEnumerations.count, "a resync walked the whole drive")
        assertEquals(setOf("Inkstone/Lecture.pdf"), batch.files().keys)
    }

    @Test
    fun `a change made after the first pass is seen anywhere in the drive`() = runTest {
        val first = store().read()
        server.seedFile("Elsewhere/Note.pdf", "x".encodeToByteArray())

        val batch = store().read(first.cursor)

        assertEquals(setOf("Elsewhere/Note.pdf"), batch.files().keys)
    }

    @Test
    fun `a scope folder that does not exist yet is an empty answer and not a failure`() = runTest {
        val batch = store().read(scope = listOf("Inkstone"))

        assertTrue(batch.files().isEmpty())
        assertTrue(batch.cursor.startsWith(server.baseUrl))
    }

    @Test
    fun `the top of the drive is listed without descending into it`() = runTest {
        server.seedFile("TopLevel.pdf", "a".encodeToByteArray())
        server.seedFile("Games/Nested.pdf", "b".encodeToByteArray())

        val batch = store().read(scope = listOf(""))

        assertEquals(setOf("TopLevel.pdf"), batch.files().keys, "a blank root turned into a walk of everything")
    }

    @Test
    fun `a scope folder is listed to its depth`() = runTest {
        server.seedFile("Inkstone/A/B/C/Deep.pdf", "x".encodeToByteArray())

        assertEquals(setOf("Inkstone/A/B/C/Deep.pdf"), store().read().files().keys)
    }

    // -- hostile or broken feeds --------------------------------------------------------------------

    @Test
    fun `a cursor for another host is refused before any request is made`() = runTest {
        val before = server.api.count + server.tokenEndpoint.count

        val out = store().changes("https://evil.invalid/v1.0/drives/x/root/delta?token=S1", listOf("Inkstone"))

        assertIs<DavFailure.Malformed>((out as DavOutcome.Failed).error)
        assertEquals(before, server.api.count + server.tokenEndpoint.count, "a request went out for a foreign cursor")
    }

    @Test
    fun `a next link on another host is not followed so the bearer token stays home`() = runTest {
        val first = store().read()
        server.deltaPageSize = 1
        server.deltaPointsElsewhere = true
        repeat(3) { server.seedFile("Inkstone/Note$it.pdf", "x".encodeToByteArray()) }
        val before = server.delta.count

        val out = store().changes(first.cursor, listOf("Inkstone"))

        assertIs<DavFailure.Malformed>((out as DavOutcome.Failed).error)
        assertEquals(1, server.delta.count - before, "the store followed a link to somebody else's server")
    }

    @Test
    fun `a refused feed is a failure and never a partial batch`() = runTest {
        server.seedFile("Inkstone/Lecture.pdf", "a".encodeToByteArray())
        val store = store()
        store.read() // gets a token
        server.rejectAccessTokens = true

        val out = store.changes(null, listOf("Inkstone"))

        assertEquals(DavFailure.Unauthorized, (out as DavOutcome.Failed).error)
    }
}
