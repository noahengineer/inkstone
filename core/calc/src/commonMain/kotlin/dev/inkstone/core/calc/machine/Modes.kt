package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.lcd.LcdFrame
import dev.inkstone.core.calc.lcd.Screens
import dev.inkstone.core.calc.lcd.TextLine
import dev.inkstone.core.calc.lcd.TextRow
import dev.inkstone.core.calc.parse.Radix
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.Value

/**
 * COMP, CMPLX and BASE-N: modes that are nothing but the calculation line (research 02 §4, 03 §3,
 * §5). All three keep history (research 02 §10.2). CMPLX adds its `SHIFT 2` menu; BASE-N its base
 * keys, its label and its `SHIFT 3` menu, and remembers the base — which entering the mode resets
 * to decimal ("The initial base is decimal", research 03 §5, VERIFIED) and power cycles keep
 * (⚠️ GUESS). A base key on a result "converts that result" (research 03 §5.1, VERIFIED) — unless
 * the new base cannot hold it ([BaseRanges]).
 */
class CalculationMode(override val mode: Mode) : ModeController {
    init {
        require(mode in BUILT_IN) { "$mode has its own controller" }
    }

    private var radix: Radix = Radix.DEC

    override fun enter(host: Host) {
        radix = Radix.DEC
        home(host)
    }

    override fun home(host: Host) = host.show(Calculation(host, Hooks(host)))

    override fun snapshot(): String? = if (mode == Mode.BASE_N) radix.name else null

    override fun restore(text: String): Boolean {
        radix = Radix.entries.firstOrNull { it.name == text } ?: return false
        return true
    }

    private inner class Hooks(private val host: Host) : CalculationHooks {
        override val keepsHistory: Boolean get() = true

        override val radix: Radix? get() = if (mode == Mode.BASE_N) this@CalculationMode.radix else null

        override fun changeRadix(radix: Radix, line: Calculation) {
            if (mode != Mode.BASE_N) return
            this@CalculationMode.radix = radix
            val shown = (line.view as? CalcView.Result)?.answer?.value ?: return
            if (!BaseRanges.holds(radix, shown)) line.fail(CalcError.MATH, null)
        }

        override fun modeMenu(line: Calculation) {
            when (mode) {
                Mode.CMPLX -> host.push(LineMenus.cmplx(host, line))
                Mode.BASE_N -> host.push(LineMenus.base(host, line))
                else -> Unit
            }
        }
    }

    private companion object {
        val BUILT_IN = setOf(Mode.COMP, Mode.CMPLX, Mode.BASE_N)
    }
}

/**
 * What each base can show (research 03 §5.2, VERIFIED): 16 bits in binary and 32 in the others,
 * two's complement, a fractional part cut off (03 §5.1). The evaluator keeps every result inside
 * its own base's range; a result already on screen switched to a narrower base — "DEC 70000 then
 * BIN" — is what the guide leaves open, research 03 §5.2 INFERS a Math ERROR, and the formatter
 * cannot show an error (it would draw the low 16 bits), so the machine decides it (⚠️ GUESS: the
 * Math ERROR, and that the base switches anyway, so `AC` leaves an empty `Bin` line).
 */
internal object BaseRanges {
    fun holds(radix: Radix, value: Value): Boolean {
        val whole = (value as? Real)?.dec?.truncate()?.toLongOrNull() ?: return false
        return whole in (if (radix == Radix.BIN) BINARY else WORD)
    }

    private val BINARY: LongRange = -32_768L..32_767L
    private val WORD: LongRange = -2_147_483_648L..2_147_483_647L
}

/**
 * A mode whose controller has not been built yet: entering it shows its name and a notice, keeps
 * its status icon (STAT, MAT, VCT) so the mode is recognisable, and ignores every key except the
 * ones the machine takes (MODE leaves it). Replaced by registering the real controller in
 * `dev.inkstone.core.calc.mode.StandardModes`.
 */
class PlaceholderMode(override val mode: Mode) : ModeController {
    override fun enter(host: Host) = host.show(PlaceholderScreen(host, mode))
}

private class PlaceholderScreen(private val host: Host, private val mode: Mode) : Screen {
    override fun handle(press: Press) = Unit

    override fun render(): LcdFrame = Screens.text(
        host.indicators(),
        listOf(TextLine(mode.menuLabel, TextRow.SMALL_1), TextLine(NOT_YET, TextRow.SMALL_3)),
    )

    private companion object {
        const val NOT_YET = "Not built yet"
    }
}
