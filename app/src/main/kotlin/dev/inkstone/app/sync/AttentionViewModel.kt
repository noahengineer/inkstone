package dev.inkstone.app.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.inkstone.core.database.sync.AttentionItem
import dev.inkstone.core.database.sync.SqlAttentionStore
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ⭐ **SPEC §7.7's "Needs attention" list, as state for a screen.**
 *
 * ## Why this exists at all, in one paragraph
 *
 * CLAUDE.md rule 6: conflicts resolve automatically and non-destructively, then appear in a list.
 * **Never a modal.** Nextcloud's own Android client resolves conflicts with a dialog raised from a
 * notification and has roughly nine open bugs from it — a dialog interrupts whoever happens to be
 * holding the tablet, forces a decision they may not have the information for, and blocks sync until
 * they make it. A list is something you come back to.
 *
 * ## One query, joined
 *
 * The rows arrive from [SqlAttentionStore.openWithTitles], which is a single SQLDelight query joined
 * to `note`. That is deliberate and not an optimisation: SQLDelight's invalidation is per table, so
 * combining an attention Flow with the library's in Kotlin goes stale in whichever direction happens
 * not to fire — and the one that goes stale leaves the screen naming a note under a title it no
 * longer has. `Sync.sq` says the rest.
 *
 * `WhileSubscribed(5000)` is the in-repo idiom (`LibraryViewModel`), long enough that a rotation does
 * not re-subscribe to the database.
 */
@HiltViewModel
class AttentionViewModel @Inject constructor(private val attention: SqlAttentionStore, private val clock: Clock) :
    ViewModel() {

    val state: StateFlow<AttentionUiState> = attention.openWithTitles()
        .map { items -> AttentionUiState(items = items, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AttentionUiState())

    /**
     * The user has read it.
     *
     * ⚠️ **A tombstone, never a delete** (CLAUDE.md rule 1). The row stays with a timestamp on it,
     * because what sync had to do to somebody's notes is worth being able to look up afterwards —
     * and because a deleted row is a row the next reconcile pass would happily raise again as new.
     */
    fun acknowledge(id: String) {
        viewModelScope.launch { attention.acknowledge(id, clock.nowEpochMillis()) }
    }
}

/**
 * @property loaded false until the first database emission, so an empty list is not shown as
 *   "nothing needs attention" to somebody who has a conflict.
 */
data class AttentionUiState(val items: List<AttentionItem> = emptyList(), val loaded: Boolean = false) {
    val isEmpty: Boolean get() = items.isEmpty()
}

/** What the "Needs attention" screen can do. Two verbs, and neither of them resolves anything. */
data class AttentionActions(
    val onAcknowledge: (String) -> Unit,
    /**
     * Open a note the entry names — the local one, or SPEC §7.7 layer 3's copy.
     *
     * The only *action* on this screen, and it is navigation. Nothing here merges, discards or
     * chooses: layer 3 already kept both versions, and the point of the list is that the user
     * decides what to do with them in the editor, in their own time.
     */
    val onOpenNote: (DocumentId) -> Unit,
    val onBack: () -> Unit,
)

/** Long enough to survive a rotation without re-subscribing to the database. */
private const val STOP_TIMEOUT_MS = 5_000L
