package dev.inkstone.app.calculator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.inkstone.core.calc.Key
import dev.inkstone.core.calc.lcd.LcdFrame
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * A screen's handle on the one [CalculatorController] — a pass-through, deliberately.
 *
 * Its own small view model rather than more surface on `NoteEditorViewModel`, which is already at
 * its suppressed limits and has nothing to do with a calculator (research 08 §4.9). Both the `CALC`
 * chip and the window call `hiltViewModel()` on the same screen and so share this instance; both
 * screens reach the same controller.
 */
@HiltViewModel
class CalculatorViewModel @Inject constructor(private val controller: CalculatorController) : ViewModel() {

    val frame: StateFlow<LcdFrame> = controller.frame
    val open: StateFlow<Boolean> = controller.open
    val position: StateFlow<WindowPosition?> = controller.position
    val face: StateFlow<Float?> = controller.face

    /**
     * Where this screen's `CALC` chip is, in the Compose root's coordinates (`boundsInRoot`), for the
     * window to hang from on first open.
     *
     * ⚠️ Here and not on the controller: it is a fact about **this** screen. Two activities each
     * have a chip in a different corner, and a process-wide anchor would be whichever was laid out
     * last — so the library's window would open from where the editor's chip was.
     */
    var chipBounds: Rect? by mutableStateOf(null)

    fun press(key: Key) = controller.press(key)

    fun toggle() = controller.toggle()

    fun close() = controller.close()

    fun moveTo(position: WindowPosition) = controller.moveTo(position)

    fun dock() = controller.dock()

    fun resizeTo(face: Float) = controller.resizeTo(face)
}
