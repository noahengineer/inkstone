package dev.inkstone.app.calculator

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.inkstone.app.ink.PanelDock

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
 * ⭐ **With a [target], the answer on the glass can be dragged onto the page** (docs/CALCULATOR.md
 * §6): the LCD is the drag source, this layer draws the ghost above the window, and a release that is
 * not over the window is handed to [target] in this layer's own pixels — the surface's view space.
 * The library passes no target and its LCD is not a drag source at all.
 */
@Composable
internal fun BoxScope.CalculatorLayer(
    calc: CalculatorViewModel = hiltViewModel(),
    dock: PanelDock? = null,
    safeTop: Dp = WindowSafeTop,
    target: AnswerTarget? = null,
) {
    val open by calc.open.collectAsState()
    BackHandler(enabled = open) { calc.close() }
    if (!open) return
    val answers = remember(target) { target?.let { AnswerDrag() } }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    if (answers != null && target != null) {
        answers.onDrop = { answer, at ->
            val scale = target.pxPerPt()
            val text = measurer.measure(answer, answerStyle(density, scale, Color.Black))
            target.drop(answer, at, text.inPagePoints(scale))
        }
    }
    Box(Modifier.matchParentSize().onGloballyPositioned { answers?.layer = it }) {
        CalculatorWindow(calc, Modifier.matchParentSize(), dock, safeTop, answers)
        val held = answers?.held
        if (held != null && target != null) AnswerGhost(held, answers.layer, target.pxPerPt())
    }
}
