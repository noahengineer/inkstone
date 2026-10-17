package dev.inkstone.app.sync.cloud

import dev.inkstone.app.sync.matrix.MatrixClock
import dev.inkstone.core.sync.AccountState
import dev.inkstone.core.sync.CloudStore
import dev.inkstone.core.sync.DavFailure
import dev.inkstone.core.sync.DavOutcome
import dev.inkstone.core.sync.ETag
import dev.inkstone.core.sync.ListingDepth
import dev.inkstone.core.sync.MicrosoftOAuthConfig
import dev.inkstone.core.sync.MicrosoftTokenSource
import dev.inkstone.core.sync.NextcloudTransport
import dev.inkstone.core.sync.OAuthSecret
import dev.inkstone.core.sync.OneDriveStore
import dev.inkstone.core.sync.ProviderKind
import dev.inkstone.core.sync.RemoteTarget
import dev.inkstone.core.sync.SyncAccount
import dev.inkstone.core.sync.TokenVault
import dev.inkstone.core.sync.UploadReceipt
import dev.inkstone.core.sync.WritePrecondition
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `OneDriveStore` and `MicrosoftTokenSource` against [GraphStandIn] — the production transport and
 * parsing, a fake service behind a real socket.
 *
 * ⚠️ **What a green run here means.** The store does what `docs/CLOUD-PROVIDERS.md` §3–§5 say
 * Microsoft's documentation promises, *as the stand-in encodes it*. It does not mean Graph behaves
 * so: that is `tools/cloud/graph_spike.py`'s job, and it has not been run. Every test that leans on
 * a documented-but-unmeasured behaviour (412 on a stale `If-Match`, 409 on a duplicate create, an
 * invisible half-finished session, the pre-authorised URL refusing a bearer token) says so in its name
 * or its comment, so a green tick is not read as more than it is.
 */
class OneDriveStoreTest {

    private val server = GraphStandIn()
    private val clock = MatrixClock()
    private val transport = NextcloudTransport.create()

    @AfterTest
    fun tearDown() {
        server.close()
        transport.close()
    }

    /** A vault that logs into the server's own log, so *order* — vault write before API use — is assertable. */
    private inner class Vault(initial: String? = server.initialRefreshToken) : TokenVault {
        var secret: OAuthSecret? = initial?.let { OAuthSecret(it) }
        var failNextPut = false

        override suspend fun get(accountId: String): OAuthSecret? = secret

        override suspend fun put(accountId: String, secret: OAuthSecret) {
            if (failNextPut) {
                failNextPut = false
                server.log += "vault put FAILED"
                error("disk full")
            }
            server.log += "vault put ${secret.refreshToken}"
            this.secret = secret
        }

        override suspend fun remove(accountId: String) {
            secret = null
        }
    }

    private fun account(state: AccountState = AccountState.ACTIVE) = SyncAccount(
        id = "od-1",
        serverUrl = server.baseUrl,
        loginName = "me@example.com",
        uid = server.driveId,
        state = state,
        provider = ProviderKind.ONEDRIVE,
    )

    private fun tokenSource(vault: TokenVault) = MicrosoftTokenSource(
        transport = transport,
        config = MicrosoftOAuthConfig(clientId = "client-1", tokenEndpoint = server.tokenUrl),
        vault = vault,
        clock = clock,
        accountId = "od-1",
    )

    private fun store(vault: TokenVault = Vault(), fragmentBytes: Int = FRAGMENT): OneDriveStore =
        OneDriveStore(account(), transport, tokenSource(vault), fragmentBytes)

    private suspend fun <T> DavOutcome<T>.ok(): T = when (this) {
        is DavOutcome.Ok -> value
        is DavOutcome.Failed -> error("expected success but got ${error.describe}")
    }

    private fun DavOutcome<*>.failure(): DavFailure = (this as DavOutcome.Failed).error

    private suspend fun CloudStore.create(path: String, bytes: ByteArray): UploadReceipt =
        put(RemoteTarget(path), bytes, WritePrecondition.CreateOnly, null, null).ok()

    private fun etagOf(receipt: UploadReceipt): ETag = assertNotNull(receipt.etag, "the receipt carries no version")

    // ------------------------------------------------------------------------------------------
    // Writes: rule 1
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a create lands the bytes and returns an id and a version`() = runTest {
        val store = store()
        store.ensureFolder("Inkstone").ok()

        val receipt = store.create("Inkstone/Note.pdf", "hello".encodeToByteArray())

        assertContentEquals("hello".encodeToByteArray(), server.bytesAt("Inkstone/Note.pdf"))
        assertEquals(server.idAt("Inkstone/Note.pdf"), receipt.ocId)
        assertEquals(server.etagAt("Inkstone/Note.pdf")?.trim('"'), receipt.etag?.value)
    }

    @Test
    fun `the upload url is sent no bearer token but the session request is (documented, not measured)`() = runTest {
        val store = store()
        store.ensureFolder("Inkstone").ok()

        store.create("Inkstone/Note.pdf", ByteArray(10))

        assertTrue(server.sessionAuthorization.single().orEmpty().startsWith("Bearer "))
        assertEquals(listOf<String?>(null), server.uploadAuthorization, "a bearer token reached the pre-authorised URL")
    }

    @Test
    fun `a large file goes up in fragments of a multiple of 320 KiB with the last one short`() = runTest {
        val store = store(fragmentBytes = FRAGMENT)
        store.ensureFolder("Inkstone").ok()
        val bytes = ByteArray(FRAGMENT * 2 + 1234) { it.toByte() }

        store.create("Inkstone/Big.pdf", bytes)

        assertEquals(
            listOf("0-${FRAGMENT - 1}", "$FRAGMENT-${FRAGMENT * 2 - 1}", "${FRAGMENT * 2}-${bytes.size - 1}"),
            server.ranges,
        )
        assertContentEquals(bytes, server.bytesAt("Inkstone/Big.pdf"), "the fragments did not reassemble")
    }

    @Test
    fun `a fragment size that is not a multiple of 320 KiB is refused outright`() {
        assertFailsWith<IllegalArgumentException> { store(fragmentBytes = FRAGMENT + 1) }
    }

    @Test
    fun `a replace with the current version succeeds and moves the version`() = runTest {
        val store = store()
        store.ensureFolder("Inkstone").ok()
        val first = store.create("Inkstone/Note.pdf", "v1".encodeToByteArray())

        val second = store.put(
            RemoteTarget("Inkstone/Note.pdf", first.ocId),
            "v2".encodeToByteArray(),
            WritePrecondition.IfMatch(etagOf(first)),
            null,
            null,
        ).ok()

        assertContentEquals("v2".encodeToByteArray(), server.bytesAt("Inkstone/Note.pdf"))
        assertNotEquals(first.etag, second.etag)
        assertEquals(etagOf(first).asIfMatch, server.sessionIfMatch.last(), "the session did not carry If-Match")
    }

    @Test
    fun `a replace with a stale version by id is a conflict and the remote bytes are untouched (documented 412)`() =
        runTest {
            val store = store()
            store.ensureFolder("Inkstone").ok()
            val first = store.create("Inkstone/Note.pdf", "v1".encodeToByteArray())
            server.editBehindTheirBack("Inkstone/Note.pdf", "theirs".encodeToByteArray())

            val result = store.put(
                RemoteTarget("Inkstone/Note.pdf", first.ocId),
                "mine".encodeToByteArray(),
                WritePrecondition.IfMatch(etagOf(first)),
                null,
                null,
            )

            assertIs<DavFailure.PreconditionFailed>(result.failure())
            assertContentEquals(
                "theirs".encodeToByteArray(),
                server.bytesAt("Inkstone/Note.pdf"),
                "rule 1: somebody else's bytes were overwritten",
            )
            assertEquals(1, server.upload.count, "a fragment of ours was sent after the first create's")
        }

    @Test
    fun `a replace found stale by path is a conflict before any session is opened`() = runTest {
        val store = store()
        store.ensureFolder("Inkstone").ok()
        val first = store.create("Inkstone/Note.pdf", "v1".encodeToByteArray())
        server.editBehindTheirBack("Inkstone/Note.pdf", "theirs".encodeToByteArray())
        val sessionsBefore = server.sessionIfMatch.size

        // No id: the binding lost it (a proxy stripped it, say). The store looks the file up and
        // compares versions itself — and so spends no upload session on a write that cannot win.
        val result = store.put(
            RemoteTarget("Inkstone/Note.pdf", null),
            "mine".encodeToByteArray(),
            WritePrecondition.IfMatch(etagOf(first)),
            null,
            null,
        )

        assertIs<DavFailure.PreconditionFailed>(result.failure())
        assertEquals(
            sessionsBefore,
            server.sessionIfMatch.size,
            "a session was opened for a write that was already stale",
        )
        assertContentEquals("theirs".encodeToByteArray(), server.bytesAt("Inkstone/Note.pdf"))
    }

    @Test
    fun `a create over an existing file is a conflict and the old bytes survive (documented 409)`() = runTest {
        val store = store()
        store.ensureFolder("Inkstone").ok()
        store.create("Inkstone/Note.pdf", "original".encodeToByteArray())

        val result = store.put(
            RemoteTarget("Inkstone/Note.pdf"),
            "intruder".encodeToByteArray(),
            WritePrecondition.CreateOnly,
            null,
            null,
        )

        assertIs<DavFailure.PreconditionFailed>(result.failure())
        assertContentEquals("original".encodeToByteArray(), server.bytesAt("Inkstone/Note.pdf"))
    }

    @Test
    fun `an unchecked write and a weak version are refused before any request is made`() = runTest {
        val store = store()
        val before = server.api.count + server.tokenEndpoint.count

        val unchecked = store.put(
            RemoteTarget("Inkstone/Note.pdf"),
            ByteArray(1),
            WritePrecondition.Unchecked,
            null,
            null,
        )
        val weak = store.put(
            RemoteTarget("Inkstone/Note.pdf"),
            ByteArray(1),
            WritePrecondition.IfMatch(ETag("w", weak = true)),
            null,
            null,
        )

        assertIs<DavFailure.Forbidden>(unchecked.failure())
        assertIs<DavFailure.Forbidden>(weak.failure())
        assertEquals(before, server.api.count + server.tokenEndpoint.count, "a refused write still reached the network")
    }

    @Test
    fun `trash moves to the recycle bin and never destroys`() = runTest {
        val store = store()
        store.ensureFolder("Inkstone").ok()
        val receipt = store.create("Inkstone/Note.pdf", "keep".encodeToByteArray())

        store.trash(RemoteTarget("Inkstone/Note.pdf", receipt.ocId)).ok()

        assertNull(server.nodeAt("Inkstone/Note.pdf"), "still in the namespace")
        assertEquals(listOf("Note.pdf"), server.recycled(), "not in the recycle bin — rule 1: no hard deletes")
        assertIs<DavFailure.NotFound>(store.trash(RemoteTarget("Inkstone/Gone.pdf")).failure())
    }

    @Test
    fun `a move keeps the id and refuses to overwrite a name that is taken`() = runTest {
        val store = store()
        store.ensureFolder("A").ok()
        store.ensureFolder("B").ok()
        val moving = store.create("A/Note.pdf", "moving".encodeToByteArray())
        store.create("B/Taken.pdf", "occupant".encodeToByteArray())

        val moved = store.move(RemoteTarget("A/Note.pdf", moving.ocId), "B/Note.pdf").ok()
        assertEquals(moving.ocId, moved.ocId, "a move must not change the file's identity")
        assertContentEquals("moving".encodeToByteArray(), server.bytesAt("B/Note.pdf"))
        assertNull(server.nodeAt("A/Note.pdf"))

        val clash = store.move(RemoteTarget("B/Note.pdf", moving.ocId), "B/Taken.pdf")
        assertIs<DavFailure.PreconditionFailed>(clash.failure())
        assertContentEquals(
            "occupant".encodeToByteArray(),
            server.bytesAt("B/Taken.pdf"),
            "a move overwrote its destination",
        )
        assertContentEquals("moving".encodeToByteArray(), server.bytesAt("B/Note.pdf"))
    }

    @Test
    fun `ensureFolder builds every level once and is one request when the folder is there`() = runTest {
        val store = store()

        store.ensureFolder("Notes/School/Physics").ok()
        assertNotNull(server.nodeAt("Notes/School/Physics"))

        val before = server.api.count
        store.ensureFolder("Notes/School/Physics").ok()
        assertEquals(1, server.api.count - before, "an existing folder cost more than one lookup")
    }

    @Test
    fun `a listing and a download carry the version and the id`() = runTest {
        val store = store()
        server.seedFile("Inkstone/One.pdf", "1".encodeToByteArray())
        server.seedFile("Inkstone/Two.pdf", "22".encodeToByteArray())

        val children = store.listing("Inkstone", ListingDepth.CHILDREN).ok()
        assertEquals(setOf("Inkstone/One.pdf", "Inkstone/Two.pdf"), children.map { it.path }.toSet())
        assertTrue(children.all { it.etag != null && it.ocId != null })

        val file = store.download("Inkstone/Two.pdf").ok()
        assertContentEquals("22".encodeToByteArray(), file.bytes)
        assertEquals(server.idAt("Inkstone/Two.pdf"), file.ocId)
        assertTrue(
            server.log.last {
                it.startsWith("download")
            }.endsWith("auth=null"),
            "a bearer token went to the download URL",
        )
    }

    // ------------------------------------------------------------------------------------------
    // Failure mapping, and nothing retries
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a 429 is reported with its Retry-After and is not retried`() = runTest {
        val store = store()
        store.ensureFolder("Inkstone").ok()
        server.throttleApiWith = 7
        val before = server.api.count

        val failure = store.put(
            RemoteTarget("Inkstone/Note.pdf"),
            ByteArray(1),
            WritePrecondition.CreateOnly,
            null,
            null,
        ).failure()

        assertEquals(DavFailure.RateLimited(7), failure)
        assertEquals(1, server.api.count - before, "a throttled request was sent again")
    }

    // ------------------------------------------------------------------------------------------
    // Auth: CLAUDE.md rule 5's analogue
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a refused grant is Unauthorized, costs one token request, and is never asked again`() = runTest {
        server.grantDead = true
        val store = store()

        val first = store.put(RemoteTarget("A/x.pdf"), ByteArray(1), WritePrecondition.CreateOnly, null, null)
        val second = store.ensureFolder("A")
        val confirmed = store.confirmStillAuthorised()

        assertEquals(DavFailure.Unauthorized, first.failure())
        assertEquals(DavFailure.Unauthorized, second.failure())
        assertEquals(false, confirmed, "a refused grant means the credential is dead")
        assertEquals(1, server.tokenEndpoint.count, "invalid_grant was retried — that is a credential retry loop")
        assertEquals(0, server.api.count, "an API request was made with no token")
    }

    @Test
    fun `a 401 with a good token costs one refresh then false, and the cooldown stops a second`() = runTest {
        val vault = Vault()
        val store = store(vault)
        store.ensureFolder("Inkstone").ok()
        assertEquals(1, server.tokenEndpoint.count, "the first call should have refreshed once")

        server.rejectAccessTokens = true
        val refused = store.ensureFolder("Other")
        assertEquals(DavFailure.Unauthorized, refused.failure())

        assertEquals(
            false,
            store.confirmStillAuthorised(),
            "a token the service refuses straight after a refresh is dead",
        )
        assertEquals(2, server.tokenEndpoint.count, "exactly one forced refresh")
        assertEquals(clock.now, vault.secret?.forcedRefreshAtEpochMillis, "the cooldown must be persisted")

        assertEquals(false, store.confirmStillAuthorised())
        assertEquals(2, server.tokenEndpoint.count, "a second forced refresh inside the cooldown")
    }

    @Test
    fun `a 401 that a fresh token fixes is a refused path not a dead credential`() = runTest {
        val store = store()
        store.ensureFolder("Inkstone").ok()
        // The cached token is bad, a freshly issued one is fine: simulate by revoking only the old.
        server.rejectAccessTokens = true
        assertEquals(DavFailure.Unauthorized, store.ensureFolder("Other").failure())
        server.rejectAccessTokens = false

        assertEquals(true, store.confirmStillAuthorised())
    }

    @Test
    fun `an unreachable token endpoint concludes nothing`() = runTest {
        val store = store()
        store.ensureFolder("Inkstone").ok()
        server.tokenEndpointUnreachable = true
        server.rejectAccessTokens = true
        store.ensureFolder("Other") // 401

        assertNull(store.confirmStillAuthorised(), "unreachable must not read as revoked")
    }

    @Test
    fun `the rotated refresh token is durable before the access token is used`() = runTest {
        val vault = Vault()
        val store = store(vault)

        store.ensureFolder("Inkstone").ok()

        val log = server.log.toList()
        val stored = log.indexOfFirst { it == "vault put rt-2" }
        val firstUse = log.indexOfFirst { it.startsWith("api ") && it.endsWith("auth=at-2") }
        assertTrue(stored >= 0 && firstUse >= 0, "expected both events in $log")
        assertTrue(stored < firstUse, "the access token was used before its refresh token was saved: $log")
        assertEquals("rt-2", vault.secret?.refreshToken)
    }

    @Test
    fun `if the new refresh token cannot be saved the access token is never used`() = runTest {
        val vault = Vault().also { it.failNextPut = true }
        val store = store(vault)

        assertFailsWith<IllegalStateException> { store.ensureFolder("Inkstone") }

        assertEquals(0, server.api.count, "an API request used a token whose refresh token was lost")
    }

    @Test
    fun `an access token is reused across calls and refreshed again only near expiry`() = runTest {
        server.accessLifetimeSeconds = 3600
        val store = store()
        store.ensureFolder("Inkstone").ok()
        store.ensureFolder("Inkstone").ok()
        assertEquals(1, server.tokenEndpoint.count)

        clock.advance(56 * 60 * 1000L) // inside the five-minute margin of a one-hour token
        store.ensureFolder("Inkstone").ok()

        assertEquals(2, server.tokenEndpoint.count)
    }
}

private const val FRAGMENT = 327_680
