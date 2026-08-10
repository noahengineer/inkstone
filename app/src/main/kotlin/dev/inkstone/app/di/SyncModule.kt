package dev.inkstone.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.inkstone.app.sync.NoteSessionHost
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.database.sync.SqlAccountStore
import dev.inkstone.core.database.sync.SqlAttentionStore
import dev.inkstone.core.database.sync.SqlBindingStore
import dev.inkstone.core.database.sync.SqlFolderBindingStore
import dev.inkstone.core.database.sync.SqlForeignRemoteStore
import dev.inkstone.core.database.sync.SqlOutboxStore
import dev.inkstone.core.database.sync.SqlPeerClockStore
import dev.inkstone.core.database.sync.SqlPendingDownloadStore
import dev.inkstone.core.database.sync.SqlRemoteDirectoryStore
import dev.inkstone.core.database.sync.SqlSyncCursorStore
import dev.inkstone.core.database.sync.SqlSyncNotes
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.CredentialStore
import dev.inkstone.core.model.DocumentMaterializer
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.RemoteBundleExtractor
import dev.inkstone.core.model.WorkScheduler
import dev.inkstone.core.sync.AccountStore
import dev.inkstone.core.sync.AttentionLog
import dev.inkstone.core.sync.BindingStore
import dev.inkstone.core.sync.DirectoryCache
import dev.inkstone.core.sync.FolderBindingStore
import dev.inkstone.core.sync.ForeignRemoteCache
import dev.inkstone.core.sync.NextcloudTransport
import dev.inkstone.core.sync.OutboxDrainer
import dev.inkstone.core.sync.OutboxStore
import dev.inkstone.core.sync.PeerClockStore
import dev.inkstone.core.sync.PendingDownloadStore
import dev.inkstone.core.sync.ReconcileStores
import dev.inkstone.core.sync.Reconciler
import dev.inkstone.core.sync.RemoteMergeGate
import dev.inkstone.core.sync.SyncCursorStore
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
// TooManyFunctions: a DI module is one `@Provides` per collaborator by construction, which is the
// shape this rule judges worst — merging two providers to satisfy a count would hide a dependency
// rather than remove one. Same reasoning as `Reconciler` and `LibraryRepository`.
@Suppress("TooManyFunctions")
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

    // -----------------------------------------------------------------------
    // The pull side (M6, SPEC §7.6)
    // -----------------------------------------------------------------------

    /**
     * The seven reconcile stores.
     *
     * Every one of them is `Sql…` from `:core:database`, and every one is provided as its
     * `:core:sync` port rather than as the concrete class — with the exception of the two the
     * library screen also reads as a `Flow`, which are provided concretely in
     * [dev.inkstone.app.di.PlatformProvidersModule] and adapted here. The reason for the
     * asymmetry is the direction of the read: a *port* is what the reconciler is allowed to see,
     * and a reconciler that could subscribe to a stream of UI rows is a reconciler that could
     * start making decisions from them.
     */
    @Provides
    @Singleton
    fun provideDirectoryCache(database: InkstoneDatabase): DirectoryCache =
        SqlRemoteDirectoryStore(database, Dispatchers.IO)

    @Provides
    @Singleton
    fun provideSyncCursorStore(database: InkstoneDatabase): SyncCursorStore =
        SqlSyncCursorStore(database, Dispatchers.IO)

    @Provides
    @Singleton
    fun providePendingDownloadStore(database: InkstoneDatabase): PendingDownloadStore =
        SqlPendingDownloadStore(database, Dispatchers.IO)

    /** SPEC §5.6's identity check, negatively: PDFs beside the notes that are not ours. */
    @Provides
    @Singleton
    fun provideForeignRemoteCache(database: InkstoneDatabase): ForeignRemoteCache =
        SqlForeignRemoteStore(database, Dispatchers.IO)

    /** The concrete store, because the "Needs attention" screen reads it as a joined `Flow`. */
    @Provides
    @Singleton
    fun provideAttentionStore(database: InkstoneDatabase): SqlAttentionStore =
        SqlAttentionStore(database, Dispatchers.IO)

    @Provides
    @Singleton
    fun provideAttentionLog(store: SqlAttentionStore): AttentionLog = store

    /** Already provided concretely for the library's folder pin; this is the reconciler's view of it. */
    @Provides
    @Singleton
    fun provideFolderBindingPort(store: SqlFolderBindingStore): FolderBindingStore = store

    @Provides
    @Singleton
    fun providePeerClockPort(store: SqlPeerClockStore): PeerClockStore = store

    @Provides
    @Singleton
    @Suppress("LongParameterList") // Seven stores, which is what SPEC §7.6's pull side needs.
    fun provideReconcileStores(
        directories: DirectoryCache,
        cursors: SyncCursorStore,
        downloads: PendingDownloadStore,
        attention: AttentionLog,
        folders: FolderBindingStore,
        peers: PeerClockStore,
        foreign: ForeignRemoteCache,
    ): ReconcileStores = ReconcileStores(directories, cursors, downloads, attention, folders, peers, foreign)

    /**
     * ⭐ The reconciler.
     *
     * Nine collaborators, and the `provideOutboxDrainer` precedent below applies for the same
     * reason: each one is a distinct SPEC §7.6 responsibility and the two store bundles are
     * already grouped. See [Reconciler]'s own `@Suppress`, which argues it at length.
     *
     * [RemoteBundleExtractor] resolves to `:android:pdf`'s `PulledPdfExtractor` and
     * [RemoteMergeGate] to [NoteSessionHost]'s, both bound in `PlatformModule`. Those two are the
     * whole reason the reconciler can live in a platform-free module.
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // See the KDoc, and Reconciler's own suppression.
    fun provideReconciler(
        stores: SyncStores,
        pull: ReconcileStores,
        credentials: CredentialStore,
        fs: FileSystem,
        clock: Clock,
        transport: NextcloudTransport,
        extractor: RemoteBundleExtractor,
        gate: RemoteMergeGate,
        engine: SyncEngine,
    ): Reconciler = Reconciler(
        stores = stores,
        pull = pull,
        credentials = credentials,
        fs = fs,
        clock = clock,
        transport = transport,
        extractor = extractor,
        gate = gate,
        engine = engine,
    )

    /**
     * SPEC §7.7's merge gate, from the host that owns the single-writer exclusion.
     *
     * A `@Provides` reading one property rather than a `@Binds` on the host itself: the gate is a
     * facet of [NoteSessionHost], not the whole of it — `:app` calls `register`/`unregister` on the
     * host, and the reconciler must not be able to.
     */
    @Provides
    @Singleton
    fun provideRemoteMergeGate(host: NoteSessionHost): RemoteMergeGate = host.gate

    /**
     * ⭐ **The upload path publishes through the host, not around it** (M6's gate-5 fix).
     *
     * `OutboxDrainer` materializes before every PUT, and it used to be handed
     * `InkstonePdfMaterializer` directly — so for a note the user had **open** it published from
     * outside the note's mutex, behind the editor that was also writing that bundle, and then
     * recorded `lastSyncedRev` from bytes that did not contain the editor's work. After that
     * `SyncEngine.noteChanged` reads `localRev <= lastSyncedRev` and enqueues nothing, so the
     * device goes permanently silent for that work while the note reads `SYNCED`. Two peers with
     * the page open never converged, and neither of them could tell.
     *
     * `MaterializeWorker` already routed through the host and carried a KDoc saying everything must.
     * A KDoc was not enough, which is why `DocumentMaterializer` and `PdfPublisher` are now
     * different types: this is the only binding for the former, and the latter is reachable only by
     * the host and the editor.
     */
    @Provides
    @Singleton
    fun provideDocumentMaterializer(host: NoteSessionHost): DocumentMaterializer = host

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
