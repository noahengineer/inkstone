package dev.inkstone.app.sync

import dev.inkstone.core.model.Clock
import dev.inkstone.core.sync.AccountStore
import dev.inkstone.core.sync.BindingStore
import dev.inkstone.core.sync.NextcloudTransport
import dev.inkstone.core.sync.OAuthSecret
import dev.inkstone.core.sync.OneDriveStoreFactory
import dev.inkstone.core.sync.PendingSignIn
import dev.inkstone.core.sync.PendingSignInStore
import dev.inkstone.core.sync.PkceCrypto
import dev.inkstone.core.sync.RemoteFolderCache
import dev.inkstone.core.sync.SyncEngine
import dev.inkstone.core.sync.TokenVault

/** A vault that holds nothing and a pending store that never has a record — nothing here ever signs in. */
private object NoVault : TokenVault {
    override suspend fun get(accountId: String): OAuthSecret? = null

    override suspend fun put(accountId: String, secret: OAuthSecret) = Unit

    override suspend fun remove(accountId: String) = Unit
}

private object NoPending : PendingSignInStore {
    override suspend fun put(pending: PendingSignIn) = Unit

    override suspend fun take(): PendingSignIn? = null
}

private object NoCrypto : PkceCrypto {
    override fun randomBytes(count: Int): ByteArray = ByteArray(count)

    override fun sha256(bytes: ByteArray): ByteArray = ByteArray(32)
}

/**
 * A [OneDriveAccounts] for tests of the *Nextcloud* path, which has to be handed one. It is built from
 * production objects with nothing configured: asking it to sign in answers "not configured", and an
 * account whose provider is Nextcloud never reaches it.
 */
internal fun unusedOneDrive(
    accounts: AccountStore,
    bindings: BindingStore,
    sync: SyncEngine,
    clock: Clock,
    folderCache: RemoteFolderCache,
    transport: NextcloudTransport,
): OneDriveAccounts {
    val settings = MicrosoftSettings(rawClientId = "", redirectUri = "msauth://dev.inkstone/test", rawStandIn = "")
    return OneDriveAccounts(
        accounts = accounts,
        vault = NoVault,
        bindings = bindings,
        sync = sync,
        clock = clock,
        folderCache = folderCache,
        settings = settings,
        factory = OneDriveStoreFactory(settings.oauth, NoVault, transport, clock),
        transport = transport,
        pending = NoPending,
        crypto = NoCrypto,
    )
}
