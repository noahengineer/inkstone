package dev.inkstone.core.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * docs/CLOUD-PROVIDERS.md §7 — an account reaches only the factory for its own provider, and an
 * account no factory claims is *unusable*, never "Nextcloud by default".
 */
class ProviderRoutingTest {

    /** A store that only says which factory built it; every operation is a test bug if reached. */
    private class Marker(override val account: SyncAccount, override val kind: ProviderKind) : CloudStore {
        override val supportsUncheckedReplace: Boolean = false
        override val pullShape: PullShape = PullShape.LISTING

        private fun never(): Nothing = error("these tests only ask which store was opened")

        override suspend fun ensureFolder(path: String): DavOutcome<Unit> = never()

        override suspend fun put(
            target: RemoteTarget,
            bytes: ByteArray,
            precondition: WritePrecondition,
            mtimeEpochSeconds: Long?,
            md5Hex: String?,
            kind: UploadKind,
        ): DavOutcome<UploadReceipt> = never()

        override suspend fun move(from: RemoteTarget, toPath: String): DavOutcome<UploadReceipt> = never()

        override suspend fun trash(target: RemoteTarget): DavOutcome<Unit> = never()

        override suspend fun confirmStillAuthorised(): Boolean? = never()

        override suspend fun listing(path: String, depth: ListingDepth): DavOutcome<List<DavResource>> = never()

        override suspend fun download(path: String): DavOutcome<DownloadedFile> = never()

        override suspend fun locate(fileId: Long): DavOutcome<List<DavResource>> = never()

        override suspend fun changes(since: String?, firstPassScope: List<String>): DavOutcome<ChangeBatch> = never()
    }

    private fun factory(kind: ProviderKind, opened: MutableList<String>) = CloudStoreFactory { account ->
        opened += "${kind.name}:${account.id}"
        Marker(account, kind)
    }

    private fun account(provider: ProviderKind) = testAccount.copy(id = "a-${provider.name}", provider = provider)

    @Test
    fun `an account is opened by the factory for its own provider only`() = runTest {
        val opened = mutableListOf<String>()
        val router = ProviderRoutingFactory(
            mapOf(
                ProviderKind.NEXTCLOUD to factory(ProviderKind.NEXTCLOUD, opened),
                ProviderKind.ONEDRIVE to factory(ProviderKind.ONEDRIVE, opened),
            ),
        )

        val store = assertNotNull(router.open(account(ProviderKind.ONEDRIVE)))

        assertEquals(ProviderKind.ONEDRIVE, store.kind)
        assertEquals(listOf("ONEDRIVE:a-ONEDRIVE"), opened, "the Nextcloud factory must not see a OneDrive account")
    }

    @Test
    fun `an unknown provider opens as nothing instead of falling back to nextcloud`() = runTest {
        val opened = mutableListOf<String>()
        val router = ProviderRoutingFactory(mapOf(ProviderKind.NEXTCLOUD to factory(ProviderKind.NEXTCLOUD, opened)))

        assertNull(router.open(account(ProviderKind.UNKNOWN)))
        assertNull(router.open(account(ProviderKind.ONEDRIVE)), "no factory was given for OneDrive")
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `the nextcloud factory declines an account that is not nextcloud even when asked directly`() = runTest {
        // Defence in depth: a mis-wired container that handed it a OneDrive account would otherwise
        // find a Nextcloud-shaped credential under that id and build a WebDAV client from it.
        val credentials = FakeCredentialStore(testAccount.id to testCredential)
        val direct = NextcloudStoreFactory(credentials, mockTransport(RecordedRequests()) { error("no HTTP") })

        assertNull(direct.open(testAccount.copy(provider = ProviderKind.ONEDRIVE)))
        assertNotNull(direct.open(testAccount), "a genuine Nextcloud account still opens")
    }
}
