package dev.inkstone.app.calculator

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/**
 * ⭐ The calculator, as one layer of a screen's root `Box` — one line in `InkScreen` and one in
 * `LibraryScreen` (research 08 §4.3).
 *
 * The layer itself fills the `Box` and takes no input: only the window inside it does, so the page
 * and the chrome around the window behave exactly as if it were not there. Where it is composed is
 * its z-order and its hit-test order: in the editor it goes **above** every page layer (so it works
 * while the Text or Elements tool owns the page's touch) and **below** the chrome (so a window
 * dragged under the dock or the page bar never hides them — and the clamp keeps it from trying).
 *
 * ⚠️ **Back closes the window first.** The handler is registered after the screen's own, so it is
 * the one Android asks; with the window shut it is disabled and Back falls through as before.
 *
 * No insert-into-note action: docs/CALCULATOR.md D6.
 */
@Composable
fun BoxScope.CalculatorLayer(calc: CalculatorViewModel = hiltViewModel()) {
    val open by calc.open.collectAsState()
    BackHandler(enabled = open) { calc.close() }
    if (open) CalculatorWindow(calc, Modifier.matchParentSize())
}
