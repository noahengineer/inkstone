package dev.inkstone.app.calculator

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.inkstone.app.ui.GlassBar
import dev.inkstone.app.ui.TextChip

/**
 * ⭐ The `CALC` pill that opens and closes the calculator (docs/CALCULATOR.md D2).
 *
 * A one-chip `GlassBar`, the `RecordButton` shape. In the editor it takes the top-right corner with
 * the record chip beside it — the design's *"Global Utilities"* zone, now that the tools are a rail
 * down the left (docs/UI-RAILS.md) — and in the library at `BottomStart`, beside a create bar that is
 * capped at three chips.
 *
 * `selected` while the window is up, so the chip says what pressing it will do. It reports where it
 * is so the window can hang from it on first open (`CalculatorViewModel.chipBounds`).
 */
@Composable
fun CalculatorChip(modifier: Modifier = Modifier, calc: CalculatorViewModel = hiltViewModel()) {
    val open by calc.open.collectAsState()
    GlassBar(modifier = modifier.onGloballyPositioned { calc.chipBounds = it.boundsInRoot() }, scrollable = false) {
        TextChip(label = "Calc", selected = open, onClick = calc::toggle)
    }
}
