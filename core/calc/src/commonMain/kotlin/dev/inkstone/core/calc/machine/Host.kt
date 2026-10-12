package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.eval.EvalContext
import dev.inkstone.core.calc.eval.Evaluator
import dev.inkstone.core.calc.eval.Memory
import dev.inkstone.core.calc.eval.StatisticsSource
import dev.inkstone.core.calc.format.Formatter
import dev.inkstone.core.calc.lcd.Indicator
import dev.inkstone.core.calc.lcd.LcdFrame
import dev.inkstone.core.calc.parse.Radix
import dev.inkstone.core.calc.value.Value
import kotlin.random.Random

/**
 * One thing on the glass that takes keys: the calculation line, a menu, a prompt, an editor, a
 * result screen. The machine keeps a **stack** of them — the bottom one is the mode's own screen,
 * menus and prompts are pushed over it — and gives every press to the top one, after taking the
 * keys that act on the whole calculator (`ON`, `SHIFT AC`, `MODE`, `SHIFT MODE`, `SHIFT 9`).
 *
 * A screen draws a **whole** frame: there is no compositing, because the real LCD never shows two
 * screens at once. It takes its status icons from [Host.indicators] and adds its own (`Disp`,
 * `▲`/`▼`, `STO`); the machine stamps SETUP's contrast on afterwards.
 */
interface Screen {
    fun handle(press: Press)

    fun render(): LcdFrame

    /**
     * True while the screen accepts nothing but its own keys — `Press [AC] key` after a reset. The
     * machine then hands `MODE`, `SHIFT MODE` and `SHIFT 9` to it instead of opening their menus;
     * `ON` and `SHIFT AC` still work, because no screen can hold the power switch.
     */
    val modal: Boolean get() = false

    /**
     * SETUP changed while this screen was on the stack ([previous] is what it was). The machine
     * calls it on every screen in the stack, bottom first, after closing the SETUP menu.
     */
    fun setupChanged(previous: Setup) = Unit
}

/**
 * What a [Screen] or a [ModeController] may ask of the machine. One implementation, the machine
 * itself; an interface so mode code depends on a short, documented surface and never on how the
 * machine keeps its state.
 */
interface Host {
    val mode: Mode
    val setup: Setup
    val memory: Memory
    val evaluator: Evaluator
    val formatter: Formatter
    val random: Random

    /**
     * The calculation history (research 02 §10.2): kept in COMP, CMPLX and BASE-N, cleared by ON, a
     * mode change, an input/output format change and CLR Setup/All. A mode without history simply
     * never adds to it.
     */
    val history: History

    /**
     * Natural (MthIO) input in the current mode: SETUP's choice, except that STAT, BASE-N, MATRIX
     * and VECTOR are always Linear (research 02 §4, VERIFIED; see [ModeController.linearOnly]).
     */
    val naturalInput: Boolean

    /** The icons every screen shows — S/A, M, mode, angle unit, FIX/SCI, Math — plus [extra]. */
    fun indicators(extra: Set<Indicator> = emptySet()): Set<Indicator>

    /** An [EvalContext] for this press, in the current mode and SETUP. */
    fun context(interrupt: () -> Boolean, statistics: StatisticsSource? = null, radix: Radix = Radix.DEC): EvalContext

    /**
     * A result was displayed: Ans takes [value], and in COMP the old Ans moves to PreAns first
     * (research 02 §9, VERIFIED). Call it exactly once per displayed result.
     */
    fun recordAnswer(value: Value)

    /** Makes [screen] the whole stack: the mode's own screen, with nothing over it. */
    fun show(screen: Screen)

    /** Puts [screen] over the current top. */
    fun push(screen: Screen)

    /** Removes the top screen; the bottom one is never removed. */
    fun pop()

    /** Swaps the top screen for [screen]. */
    fun replace(screen: Screen)

    /** Leaves everything and shows the current mode's resting screen ([ModeController.home]). */
    fun home()
}

/** What CLR clears (research 02 §5.3). */
enum class ClearScope {
    /** `SHIFT 9 1`: SETUP and the mode back to their defaults. */
    SETUP,

    /** `SHIFT 9 2`: Ans, PreAns, M and the variables (and here the matrix and vector slots). */
    MEMORY,

    /** `SHIFT 9 3`: both, plus every mode's own data. */
    ALL,
}
