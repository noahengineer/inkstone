package dev.inkstone.app.ink

import androidx.compose.ui.geometry.Rect

/**
 * ⭐ ROADMAP M10 — which summoned panel is open, and where it hangs from.
 *
 * ## Why one value rather than one nullable anchor per panel
 *
 * The colour panel arrived first as `var colourAnchor: Rect?`, and the size panel would have been a
 * second one beside it — at which point *"both open at once"* is representable, sits over the paper
 * in two overlapping cards, and each has a full-screen dismissal scrim that eats the other's taps.
 * One value makes it unrepresentable, which is the move `EditorTool` made when selection would have
 * been a second boolean beside `eraserSelected`.
 *
 * It also means the callback that opens them is **one** callback threaded through `TopChrome` →
 * `EditorDock` → `SecondaryContent` → `DrawControls`, rather than a second one at every layer —
 * four signatures that each grow a parameter, in a file where `LongParameterList` is already close.
 *
 * @property kind which panel.
 * @property anchor the control's bounds **in root coordinates**, so `AnchoredPanel` can hang the
 *   card under the thing that was pressed rather than in a corner. `Rect.Zero` is a legitimate
 *   value before the control has been laid out; the panel falls back to centring.
 */
data class SummonedPanel(val kind: PanelKind, val anchor: Rect)

/** The panels a toolbar control can summon. See [SummonedPanel]. */
enum class PanelKind { COLOUR, SIZE }
