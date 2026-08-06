package dev.inkstone.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.database.sync.SqlAccountStore
import dev.inkstone.core.database.sync.SqlBindingStore
import dev.inkstone.core.database.sync.SqlOutboxStore
import dev.inkstone.core.database.sync.SqlSyncNotes
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.CredentialStore
import dev.inkstone.core.model.DocumentMaterializer
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.WorkScheduler
import dev.inkstone.core.sync.AccountStore
import dev.inkstone.core.sync.BindingStore
import dev.inkstone.core.sync.NextcloudTransport
import dev.inkstone.core.sync.OutboxDrainer
import dev.inkstone.core.sync.OutboxStore
import dev.inkstone.core.sync.SyncEngine
import dev.inkstone.core.sync.SyncNotes
import dev.inkstone.core.sync.SyncStores
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton
import kotlin.random.Random

/**
 * M5's wiring. SPEC §2.2 — `:core` declares, `:app` supplies.
 *
 * The one thing worth noting is what is **not** here: there is no provider that hands a
 * `WebDavClient` or an `HttpClient` to anything outside `:core:sync` and
 * [dev.inkstone.app.sync.NextcloudAccounts]. Every remote *mutation* goes through the
 * outbox (CLAUDE.md rule 4), and the way to keep that true is to leave nothing else
 * holding a client it could use.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    /**
     * One client for the whole app.
     *
     * Its `User-Agent` is [dev.inkstone.core.sync.INKSTONE_USER_AGENT], which becomes the
     * app password's display name in the user's Nextcloud security settings (SPEC §7.1
     * rule 4) — this is what stops the entry reading "okhttp".
     */
    @Provides
    @Singleton
    fun provideNextcloudTransport(): NextcloudTransport = NextcloudTransport.create()

    @Provides
    @Singleton
    fun provideAccountStore(database: InkstoneDatabase): AccountStore = SqlAccountStore(database, Dispatchers.IO)

    @Provides
    @Singleton
    fun provideBindingStore(database: InkstoneDatabase): BindingStore = SqlBindingStore(database, Dispatchers.IO)

    @Provides
    @Singleton
    fun provideOutboxStore(database: InkstoneDatabase): OutboxStore = SqlOutboxStore(database, Dispatchers.IO)

    @Provides
    @Singleton
    fun provideSyncNotes(database: InkstoneDatabase): SyncNotes = SqlSyncNotes(database, Dispatchers.IO)

    /**
     * The four stores as one value. They always come from the same database and always
     * travel together — see [SyncStores].
     */
    @Provides
    @Singleton
    fun provideSyncStores(
        accounts: AccountStore,
        bindings: BindingStore,
        outbox: OutboxStore,
        notes: SyncNotes,
    ): SyncStores = SyncStores(accounts, bindings, outbox, notes)

    @Provides
    @Singleton
    fun provideSyncEngine(stores: SyncStores, clock: Clock, scheduler: WorkScheduler): SyncEngine =
        SyncEngine(stores, clock, scheduler)

    @Provides
    @Singleton
    @Suppress("LongParameterList") // Every collaborator is one SPEC §7.6 responsibility.
    fun provideOutboxDrainer(
        stores: SyncStores,
        credentials: CredentialStore,
        materializer: DocumentMaterializer,
        fs: FileSystem,
        clock: Clock,
        transport: NextcloudTransport,
    ): OutboxDrainer = OutboxDrainer(
        stores = stores,
        credentials = credentials,
        materializer = materializer,
        fs = fs,
        clock = clock,
        transport = transport,
        // Real randomness in the app, a fixed value in tests. Backoff jitter is what stops
        // every note that failed during one offline period becoming due at the same
        // instant — which is how a client turns its own server's brute-force protection on
        // (SPEC §7.2).
        jitter = { Random.nextDouble() },
    )
}
