package dev.inkstone.app.library

import dev.inkstone.core.database.sync.SqlAttentionStore
import dev.inkstone.core.database.sync.SqlFolderBindingStore
import dev.inkstone.core.database.sync.SqlPeerClockStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The three `:core:database` sync tables the **library** reads directly, as one parameter.
 *
 * Same argument `SyncStores` and `ReconcileStores` make in `:core:sync`, and for the same reason:
 * they always come from one database, they always travel together, and a caller holding two of the
 * three could not do anything correct with them. Passing them individually also put
 * [LibraryRepository]'s constructor past detekt's parameter limit, which is the linter noticing
 * something true rather than something stylistic.
 *
 * ## Why the concrete stores and not the `:core:sync` ports
 *
 * Because what the library wants from all three is a **`Flow`**, and the ports deliberately do not
 * offer one. `AttentionLog` is write-mostly by design — *"a reconciler with a stream of UI rows is a
 * reconciler that could start making decisions from them"* — so the streaming reads live on the SQL
 * classes, and the module that draws them is the module allowed to see them. `:app` already depends
 * on `:core:database` for `LibraryIndex`, so this adds no edge.
 *
 * @property folders which library folders are pinned to a remote path (SPEC §9's folder mirror).
 * @property peers SPEC §4.7's causal-stability horizon — read to answer `Compaction.isAllowed`'s
 *   `oldestAckedCounter`, which was passed null from M2 until M6.
 * @property attention SPEC §7.7's list. The library reads only a count and a set of ids from it; the
 *   entries themselves belong to the "Needs attention" screen.
 */
@Singleton
class LibrarySyncTables @Inject constructor(
    val folders: SqlFolderBindingStore,
    val peers: SqlPeerClockStore,
    val attention: SqlAttentionStore,
)
