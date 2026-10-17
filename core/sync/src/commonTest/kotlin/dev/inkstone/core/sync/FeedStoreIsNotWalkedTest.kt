package dev.inkstone.core.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A store whose pull is a change feed is never *walked* (docs/CLOUD-PROVIDERS.md §4, [PullShape]).
 *
 * The failure this prevents is silent: Graph's folder ETags are not documented to propagate to their
 * parents, so a Nextcloud-style walk of a OneDrive account would find "nothing changed" on every poll
 * and report a healthy pass while missing every remote edit. Until the `delta` pull is built, the
 * honest state is *skipped*, and the report says so.
 *
 * ⚠️ This pins the skip, not the absence of a pull: an upload-only OneDrive account is a known limit,
 * and the day the feed lands this test's premise changes with it.
 */
class FeedStoreIsNotWalkedTest {

    private class FeedStore(override val account: SyncAccount) : CloudStore {
        var touched = 0

        override val kind: ProviderKind = ProviderKind.ONEDRIVE
        override val supportsUncheckedReplace: Boolean = false
        override val pullShape: PullShape = PullShape.FEED

        private fun hit(): Nothing {
            touched++
            error("a feed store must not be walked")
        }

        override suspend fun ensureFolder(path: String): DavOutcome<Unit> = hit()

        override suspend fun put(
            target: RemoteTarget,
            bytes: ByteArray,
            precondition: WritePrecondition,
            mtimeEpochSeconds: Long?,
            md5Hex: String?,
            kind: UploadKind,
        ): DavOutcome<UploadReceipt> = hit()

        override suspend fun move(from: RemoteTarget, toPath: String): DavOutcome<UploadReceipt> = hit()

        override suspend fun trash(target: RemoteTarget): DavOutcome<Unit> = hit()

        override suspend fun confirmStillAuthorised(): Boolean? = hit()

        override suspend fun listing(path: String, depth: ListingDepth): DavOutcome<List<DavResource>> = hit()

        override suspend fun download(path: String): DavOutcome<DownloadedFile> = hit()

        override suspend fun locate(fileId: Long): DavOutcome<List<DavResource>> = hit()
    }

    @Test
    fun `the reconciler skips a feed account without asking its store for anything`() = runTest {
        val account = testAccount.copy(provider = ProviderKind.ONEDRIVE)
        val store = FeedStore(account)
        val harness = ReconcileHarness(FakeServer(), account = account, storeFactory = { store })

        val report = harness.reconciler.reconcile()

        assertEquals(setOf(account.id), report.skippedAccounts, "the skipped account must be named in the report")
        assertEquals(0, store.touched, "the reconciler walked a store that has no folder ETags")
        assertEquals(0, report.accountsPolled)
        assertTrue(harness.recorded.count("PROPFIND") == 0 && harness.recorded.count("GET") == 0)
    }
}
