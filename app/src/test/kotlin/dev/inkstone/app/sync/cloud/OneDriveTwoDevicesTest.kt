package dev.inkstone.app.sync.cloud

import dev.inkstone.app.sync.matrix.Device
import dev.inkstone.app.sync.matrix.DeviceWiring
import dev.inkstone.app.sync.matrix.LocalMatrixServer
import dev.inkstone.app.sync.matrix.MATRIX_ACCOUNT_ID
import dev.inkstone.app.sync.matrix.MatrixClock
import dev.inkstone.app.sync.matrix.drawStroke
import dev.inkstone.app.sync.matrix.remotePath
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.sync.AttentionKind
import dev.inkstone.core.sync.MicrosoftOAuthConfig
import dev.inkstone.core.sync.NextcloudTransport
import dev.inkstone.core.sync.OAuthSecret
import dev.inkstone.core.sync.OneDriveStoreFactory
import dev.inkstone.core.sync.OutboxOpType
import dev.inkstone.core.sync.ProviderKind
import dev.inkstone.core.sync.SyncAccount
import dev.inkstone.core.sync.TokenVault
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⭐ **Two real tablets over OneDrive** — the matrix's fixture with Graph's stand-in behind it instead of
 * Nextcloud's.
 *
 * Everything on each device is the production object: `NoteStore`, `NoteSession`, the SQL stores, the
 * PDF publisher, the extractor, the merge gate, `OutboxDrainer`, `Reconciler`, `OneDriveStore` and its
 * token source. Only the *service* is fake. That is what makes this the check that OneDrive is
 * **connected** and not merely correct in pieces (CLAUDE.md definition of done #6): a note written on
 * one tablet has to arrive on the other through the outbox, the feed, the extractor and the merge.
 *
 * ⚠️ What it cannot show: that Graph behaves like [GraphStandIn] (nothing here has met one), a device,
 * a sign-in, or the Android Keystore. The rows named after SPEC §13.2's are that row's *idea* on this
 * transport, not a re-run of it.
 */
@OptIn(ExperimentalPathApi::class)
class OneDriveTwoDevicesTest {

    private val standIn = GraphStandIn()
    private val unusedNextcloud = LocalMatrixServer()
    private val clock = MatrixClock()
    private val transports = mutableListOf<NextcloudTransport>()
    private val roots = List(2) { Files.createTempDirectory("inkstone-onedrive-device-$it-") }

    private val a: Device
    private val b: Device

    init {
        a = device("device-a", roots[0])
        b = device("device-b", roots[1])
    }

    /** Each device signs in on its own, so each holds its own refresh-token chain. */
    private fun device(name: String, root: java.nio.file.Path): Device {
        val transport = NextcloudTransport.create().also { transports += it }
        val vault = object : TokenVault {
            var secret: OAuthSecret? = OAuthSecret(
                if (name ==
                    "device-a"
                ) {
                    standIn.initialRefreshToken
                } else {
                    standIn.signIn()
                },
            )

            override suspend fun get(accountId: String): OAuthSecret? = secret

            override suspend fun put(accountId: String, secret: OAuthSecret) {
                this.secret = secret
            }

            override suspend fun remove(accountId: String) {
                secret = null
            }
        }
        val account = SyncAccount(
            id = MATRIX_ACCOUNT_ID,
            serverUrl = standIn.baseUrl,
            loginName = "me@example.com",
            uid = standIn.driveId,
            defaultFolder = FOLDER,
            provider = ProviderKind.ONEDRIVE,
        )
        val factory = OneDriveStoreFactory(
            MicrosoftOAuthConfig("client-1", tokenEndpoint = standIn.tokenUrl),
            vault,
            transport,
            clock,
        )
        return Device(name, unusedNextcloud, FOLDER, clock, root, DeviceWiring(account, factory))
    }

    @AfterTest
    fun tearDown() {
        a.close()
        b.close()
        transports.forEach { it.close() }
        standIn.close()
        unusedNextcloud.close()
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        try {
            roots.forEach { it.deleteRecursively() }
        } catch (ignored: Throwable) {
            println("  could not delete a temp directory")
        }
    }

    private suspend fun start() {
        a.start()
        b.start()
    }

    /** A note on both devices, uploaded, adopted and settled — the matrix's own `share`, over OneDrive. */
    private suspend fun share(title: String): DocumentId {
        val id = a.createNote(title)
        a.bind(id, title)
        a.drain()
        b.pull()
        a.pull()
        return id
    }

    private fun downloads(): Int = standIn.log.count { it.startsWith("download") }

    // -----------------------------------------------------------------------

    @Test
    fun `a note written on one tablet arrives on the other as a real pdf through the outbox and the feed`() = runTest {
        start()
        val id = a.createNote("Hello")
        a.bind(id, "Hello")

        val drained = a.drain()
        assertEquals(1, drained.uploaded, "the note never left the outbox")
        val bytes = assertNotNull(standIn.bytesAt(a.remotePath(id)), "nothing reached OneDrive")
        assertTrue(bytes.decodeToString(0, 4) == "%PDF", "what OneDrive holds is not a PDF")

        val pulled = b.pull()

        assertEquals(1, pulled.adopted, "the other tablet did not adopt the note")
        assertNotNull(b.summary(id), "adopted, but not in the library")
        val binding = assertNotNull(b.binding(id))
        assertEquals(standIn.idAt(a.remotePath(id)), binding.remoteOcId, "the binding should hold OneDrive's item id")
        assertEquals(NoteSyncState.SYNCED, b.summary(id)?.syncState)
        assertTrue(b.outboxRows().isEmpty(), "an adopted note must owe the server nothing")
    }

    @Test
    fun `after sharing both tablets idle at one feed call and one apiece`() = runTest {
        start()
        share("Idle")
        val uploadsBefore = standIn.upload.count
        val downloadsBefore = downloads()
        val deltaBefore = standIn.delta.count

        repeat(3) {
            assertEquals(1, a.pull().unchanged, "A's idle poll did something")
            assertEquals(1, b.pull().unchanged, "B's idle poll did something")
        }

        assertEquals(deltaBefore + 6, standIn.delta.count, "an idle poll must cost exactly one feed call")
        assertEquals(uploadsBefore, standIn.upload.count, "an idle poll uploaded — the echo loop")
        assertEquals(downloadsBefore, downloads(), "an idle poll downloaded a file")
    }

    @Test
    fun `both tablets write offline and both strokes survive on both`() = runTest {
        start()
        val id = share("Both")
        val page = a.firstPage(id)
        val fromA = a.drawStroke(id)
        val fromB = b.drawStroke(id)

        // Poll and drain until neither has anything left to say — the bound is the claim.
        var rounds = 0
        while (rounds < ROUND_LIMIT) {
            val onA = a.pull()
            a.drain()
            val onB = b.pull()
            b.drain()
            rounds++
            if (onA.unchanged == 1 && onB.unchanged == 1) break
        }
        assertTrue(
            rounds < ROUND_LIMIT,
            "never settled in $ROUND_LIMIT rounds — the tablets are uploading each other's merges",
        )

        for (device in listOf(a, b)) {
            val objects = device.page(id, page).objects.map { it.id }
            assertTrue(fromA in objects, "${device.name} lost A's stroke")
            assertTrue(fromB in objects, "${device.name} lost B's stroke")
        }
        assertEquals(
            assertNotNull(a.binding(id)).lastSyncedEtag,
            assertNotNull(b.binding(id)).lastSyncedEtag,
            "the tablets disagree about which version is on OneDrive",
        )
    }

    @Test
    fun `a file deleted on onedrive trashes the note and never enqueues a delete`() = runTest {
        start()
        val id = share("Doomed")
        val unsynced = a.drawStroke(id)
        val page = a.firstPage(id)

        standIn.removeBehindTheirBack(a.remotePath(id))
        val pull = a.pull()

        assertEquals(1, pull.trashed, "a named deletion was not acted on")
        assertFalse(a.fs.exists(NoteLayout.noteDir(id)), "the note is still in notes/")
        assertTrue(a.fs.list(NoteLayout.TRASH_DIR).isNotEmpty(), "the note was hard-deleted")
        assertTrue(
            a.outboxRows().none { it.opType == OutboxOpType.DELETE },
            "a remote DELETE was enqueued, which annihilates the pending UPLOAD",
        )
        assertTrue(a.attention().any { it.kindOrNull == AttentionKind.REMOTE_DELETED })
        assertEquals(1, standIn.recycled().size, "the file went somewhere other than the recycle bin")
        // Restorable with the never-uploaded edit intact.
        assertTrue(dev.inkstone.core.storage.NoteTrash(a.fs, a.clock).restore(id), "the note could not be restored")
        a.index.restoreNote(id)
        assertTrue(a.page(id, page).objects.any { it.id == unsynced }, "the restore lost the unsynced edit")
    }

    @Test
    fun `a rename on onedrive repaths the binding and costs one download and no upload`() = runTest {
        // Unlike Nextcloud, whose ETag survives a move, Graph's eTag moves on *any* metadata change
        // (docs §3.1), so a rename reads as a remote edit: one download, a merge that finds nothing
        // new, and — by SPEC §7.6's table, row 1 — **no upload**. A cTag would tell a rename from an
        // edit, but Graph omits it from `delta` on OneDrive for Business, so it cannot be relied on.
        start()
        val id = share("Renamed")
        val downloadsBefore = downloads()
        val uploadsBefore = standIn.upload.count

        standIn.renameBehindTheirBack(a.remotePath(id), "Moved Away.pdf")
        val pull = a.pull()

        assertEquals(1, pull.merged, "the rename was not reconciled")
        assertEquals(
            "$FOLDER/Moved Away.pdf",
            assertNotNull(a.binding(id)).remotePath,
            "the binding did not follow the file",
        )
        assertEquals(downloadsBefore + 1, downloads(), "a rename costs exactly one download")
        assertEquals(uploadsBefore, standIn.upload.count, "a merge that found nothing new uploaded")
        assertTrue(a.outboxRows().isEmpty(), "a fast-forward must owe the server nothing")
    }

    @Test
    fun `an edit made behind our back is a conflict that overwrites nothing`() = runTest {
        start()
        val id = share("Contested")
        val path = a.remotePath(id)
        val theirs = "somebody else's file".encodeToByteArray()
        standIn.editBehindTheirBack(path, theirs)

        a.drawStroke(id)
        val report = a.drain()

        assertEquals(1, report.parked, "the stale write was not stopped")
        assertEquals(NoteSyncState.CONFLICTED, a.summary(id)?.syncState)
        assertContentEquals(theirs, standIn.bytesAt(path), "rule 1: somebody else's bytes were overwritten")
        assertTrue(a.outboxRows().any { it.opType == OutboxOpType.UPLOAD }, "the parked row must stay (rule 1)")
    }

    @Test
    fun `a refused grant on one tablet halts only that tablet and sends nothing more`() = runTest {
        start()
        val id = share("Revoked")
        standIn.grantDead = true
        standIn.rejectAccessTokens = true
        a.drawStroke(id)

        val first = a.drain()
        val tokenRequests = standIn.tokenEndpoint.count
        a.pull()
        a.drain()

        assertEquals(setOf(MATRIX_ACCOUNT_ID), first.haltedAccounts)
        assertEquals(tokenRequests, standIn.tokenEndpoint.count, "a halted account asked for a token again")
    }

    private companion object {
        const val FOLDER = "Inkstone"
        const val ROUND_LIMIT = 8
    }
}
