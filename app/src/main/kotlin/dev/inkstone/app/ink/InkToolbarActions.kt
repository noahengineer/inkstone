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
)
