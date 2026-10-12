package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.eval.Answer
import dev.inkstone.core.calc.format.Presentation
import dev.inkstone.core.calc.lcd.LcdFrame
import dev.inkstone.core.calc.lcd.Screens
import dev.inkstone.core.calc.parse.Radix

/**
 * A mode's answer on a screen of its own: a label top-left in the large font and the value
 * bottom-right — RATIO's `X=` / `9/2` (research 03 §12, VERIFIED screen `RAITO_03`), and the same
 * look EQN's `X₁=`, the vertex screens and DIST's `p=` use (03 §6.2, §10.1; `Screens.labelValue`).
 *
 * The value is presented by the [dev.inkstone.core.calc.format.Formatter] like any result, so it is
 * a stacked fraction in MathO and a decimal in LineO; [restyles] are the result keys this screen
 * honours, by default S⇔D and `SHIFT S⇔D` — RATIO's `125/3` becomes `41,66666667` on S⇔D (research
 * 05 EX-SA-RATIO-01, VERIFIED capture) — and not ENG, because EQN's and INEQ's solutions "cannot be
 * converted with ENG" (03 §6.2, §11.3, VERIFIED); that RATIO's cannot either is ⚠️ GUESS.
 *
 * Nothing here touches Ans: a mode whose answer becomes Ans (DIST's Var result, 03 §10.2) calls
 * [Host.recordAnswer] itself when it shows one. `=` and `AC` are the mode's: [onEquals] and [onAc]
 * must take this screen off the stack.
 */
class LabelledResult(
    private val host: Host,
    private val label: String,
    answer: Answer,
    private val onEquals: () -> Unit,
    private val onAc: () -> Unit,
    private val restyles: Set<Command> = setOf(Command.S_TO_D, Command.MIXED),
) : Screen {
    /** The face on show; starts as `=` shows it and changes with [restyles]. */
    var presentation: Presentation = host.formatter.present(answer, host.setup, host.mode, decimal = false)
        private set

    override fun handle(press: Press) {
        val command = press.command
        when {
            command == Command.EQUALS -> onEquals()
            command == Command.AC -> onAc()
            command in restyles -> command?.let { host.restyle(presentation, it) }?.let { presentation = it }
        }
    }

    override fun render(): LcdFrame {
        val shown = host.formatter.show(presentation, host.setup, host.mode, Radix.DEC)
        val value = shown.result ?: shown.parts.firstOrNull()?.value
        return Screens.labelValue(host.indicators(), label, value, natural = shown.naturalResult)
    }

    /** A Fix or angle change redraws the answer in the new format, from its standard face (as the line does). */
    override fun setupChanged(previous: Setup) {
        presentation = host.formatter.present(presentation.answer, host.setup, host.mode, decimal = false)
    }
}
