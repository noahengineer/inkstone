package dev.inkstone.app.ink

import androidx.compose.runtime.Immutable
import dev.inkstone.core.model.ConnectorRoute
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
    // ⭐ ROADMAP M8b — the record's own properties. Each is one `SetTextContent` per selected
    // shape, one gesture, one undo entry (SPEC §4.8.2). Offered only when the selection contains
    // something they apply to; see `ShapeSelectionCounts`.
    val onFillSelection: (Int?, Float) -> Unit,
    val onRoundSelection: (Float) -> Unit,
    val onRouteSelection: (ConnectorRoute) -> Unit,
    // ⭐ The two geometry buttons a selected shape offers. Each is one gesture and therefore one
    // undo entry, and each writes nothing at all for a shape it would not change. The bar's third
    // chip, `Grid`, is the *mode* toggle and needs no action of its own — see `ShapeGeometryChips`.
    val onRegulariseSelection: () -> Unit,
    val onArrowSelection: (Boolean) -> Unit,
    // ⭐ SPEC §4.9.6 — hide what is underneath. One `SetFormat` per object, one gesture.
    val onKnockoutSelection: (Boolean) -> Unit,
    // ⭐ The selection, as an Element — handwriting included, which the Elements tool's box could
    // not save before the surface could enumerate it. Opens the same name dialog the box does.
    val onSaveSelectionAsElement: () -> Unit,
)
