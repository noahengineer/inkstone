package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.lcd.LcdFrame
import dev.inkstone.core.calc.lcd.Screens
import dev.inkstone.core.calc.lcd.TextAlign
import dev.inkstone.core.calc.lcd.TextLine
import dev.inkstone.core.calc.lcd.TextRow

/**
 * A one-line question answered by a single digit: SETUP's `Fix 0~9?`, `Sci 0~9?`, `Norm 1~2?`
 * (research 02 §5.2; `Fix` and `Norm` VERIFIED from the SA captures, `Sci` SECONDARY). The text is
 * on the first small-font line, as the `Norm 1~2?` capture measures (research 00 C4). A digit
 * outside [digits] is ignored; `AC` cancels.
 */
class DigitPrompt(
    private val host: Host,
    private val title: String,
    private val digits: IntRange,
    private val onCancel: () -> Unit,
    private val onDigit: (Int) -> Unit,
) : Screen {
    override fun handle(press: Press) {
        when {
            press.command == Command.AC -> onCancel()
            press.digit in digits -> onDigit(press.digit)
        }
    }

    override fun render(): LcdFrame = Screens.text(host.indicators(), listOf(TextLine(title, TextRow.SMALL_1)))
}

/**
 * CLR's confirmation (research 02 §5.3; `Reset All?` VERIFIED from [SA s.4], `Clear Setup?` and
 * `Clear Memory?` SECONDARY from the sibling firmware's strings): the question, a blank line,
 * `[=]   :Yes`, `[AC]  :Cancel`, all in the small font. Only `=` and `AC` do anything.
 */
class ConfirmScreen(
    private val host: Host,
    private val question: String,
    private val onCancel: () -> Unit,
    private val onYes: () -> Unit,
) : Screen {
    override fun handle(press: Press) {
        when (press.command) {
            Command.EQUALS -> onYes()
            Command.AC -> onCancel()
            else -> Unit
        }
    }

    override fun render(): LcdFrame = Screens.text(
        host.indicators(),
        listOf(TextLine(question, TextRow.SMALL_1), TextLine(YES, TextRow.SMALL_3), TextLine(CANCEL, TextRow.SMALL_4)),
    )

    private companion object {
        const val YES = "[=]   :Yes"
        const val CANCEL = "[AC]  :Cancel"
    }
}

/**
 * A message that waits for `AC` and nothing else: `Reset All` / `Press [AC] key` (research 02 §5.3,
 * VERIFIED from [SA s.4]) and `Complete!` / `Press [AC] key` after CLR Setup or Memory (INFERRED
 * from the sibling firmware's string table). It is [modal], so even `MODE` waits for the `AC` the
 * screen asks for. Both lines are centred by whole cells, which reproduces the firmware strings'
 * leading spaces (`"   Reset All"`, `" Press [AC] key"`) exactly.
 */
class NoticeScreen(private val host: Host, private val message: String, private val onAc: () -> Unit) : Screen {
    override val modal: Boolean get() = true

    override fun handle(press: Press) {
        if (press.command == Command.AC) onAc()
    }

    override fun render(): LcdFrame = Screens.text(
        host.indicators(),
        listOf(
            TextLine(message, TextRow.SMALL_2, TextAlign.CENTER),
            TextLine(PRESS_AC, TextRow.SMALL_3, TextAlign.CENTER),
        ),
    )

    private companion object {
        const val PRESS_AC = "Press [AC] key"
    }
}

/**
 * An error screen pushed over another screen — an editor cell's Math ERROR, RATIO's zero
 * coefficient. Research 02 §11 (VERIFIED): the message, `[AC]  :Cancel` and `[◀][▶]:Goto`;
 * `AC` "clears the message and the expression" and `◀`/`▶` return "with the cursor at the error
 * position". What those mean depends on what is under it, so both are the caller's: [onAc] and
 * [onGoto] (by default the same) must take this screen off the stack. Every other key is ignored,
 * as on the calculation line's own error screen (⚠️ GUESS there too).
 */
class ErrorScreen(
    private val host: Host,
    private val error: CalcError,
    private val onAc: () -> Unit,
    private val onGoto: () -> Unit = onAc,
) : Screen {
    override fun handle(press: Press) {
        when (press.command) {
            Command.AC -> onAc()
            Command.LEFT, Command.RIGHT -> onGoto()
            else -> Unit
        }
    }

    override fun render(): LcdFrame = Screens.error(host.indicators(), error)
}

/**
 * CONST and CONV (research 03 §14–15, screens VERIFIED): `CONSTANT` or `CONVERSION`, then
 * `Number 01~40?`, then the `[__]` field on the row SA slide 19 measures. Two digits choose; the
 * choice is made on the second digit (INFERRED: "the insert happens as soon as the second digit
 * is typed"). `DEL` takes back the first digit and `AC` cancels — ⚠️ GUESS, research 06 §5.27
 * calls both UNKNOWN. A number outside 01–40 is reported to [onNumber] as typed; the caller
 * decides (CONV above 40 is a Syntax ERROR, research 02 §10.16, SECONDARY).
 */
class NumberPrompt(
    private val host: Host,
    private val title: String,
    private val onCancel: () -> Unit,
    private val onNumber: (Int) -> Unit,
) : Screen {
    private var typed = ""

    override fun handle(press: Press) {
        when {
            press.command == Command.AC -> onCancel()
            press.command == Command.DEL -> typed = typed.dropLast(1)
            press.digit >= 0 -> digit(press.digit)
        }
    }

    private fun digit(value: Int) {
        typed += value.toString()
        if (typed.length == FIELD) {
            val number = typed.toIntOrNull() ?: return
            typed = ""
            onNumber(number)
        }
    }

    override fun render(): LcdFrame = Screens.text(
        host.indicators(),
        listOf(
            TextLine(title, TextRow.SMALL_1),
            TextLine(QUESTION, TextRow.SMALL_2),
            TextLine("[" + typed.padEnd(FIELD, '_') + "]", TextRow.LARGE_FIELD, TextAlign.RIGHT),
        ),
    )

    companion object {
        const val CONSTANT = "CONSTANT"
        const val CONVERSION = "CONVERSION"
        private const val QUESTION = "Number 01~40?"
        private const val FIELD = 2
    }
}
