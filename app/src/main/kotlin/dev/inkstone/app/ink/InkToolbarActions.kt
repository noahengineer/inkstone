package dev.inkstone.app.ink

import androidx.compose.runtime.Immutable
import dev.inkstone.core.model.FitMode

/** Everything the toolbar can ask the ink surface to do. */
@Immutable
data class InkToolbarActions(
    val onFit: (FitMode) -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onClear: () -> Unit,
    val onLoadSyntheticPage: (Int) -> Unit,
    val onResetLatency: () -> Unit,
    // ⭐ ROADMAP M8a — the actions a selection offers. Each is one gesture on the surface and
    // therefore one undo entry; none of them writes a file, and all of them emit only ops M2
    // already defined.
    val onDeleteSelection: () -> Unit,
    val onDuplicateSelection: () -> Unit,
    val onRecolourSelection: (Int) -> Unit,
    val onResizeSelectionStroke: (Float) -> Unit,
    val onBringSelectionToFront: () -> Unit,
    val onSendSelectionToBack: () -> Unit,
    val onClearSelection: () -> Unit,
)
