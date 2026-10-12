package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.AngleUnit
import dev.inkstone.core.calc.Key
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.NumberFormat
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.eval.EvalContext
import dev.inkstone.core.calc.eval.Evaluator
import dev.inkstone.core.calc.eval.Memory
import dev.inkstone.core.calc.eval.StatisticsSource
import dev.inkstone.core.calc.format.Formatter
import dev.inkstone.core.calc.lcd.Indicator
import dev.inkstone.core.calc.lcd.LcdFrame
import dev.inkstone.core.calc.parse.Radix
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.ComplexValue
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.Value
import kotlin.random.Random

/**
 * The key machine: physical presses in, [LcdFrame]s out. It owns what the whole calculator shares
 * — power, the S/A latch, mode, SETUP, memory, history, the screen stack — and hands every other
 * press to the screen on top (see [Screen], [ModeController]).
 *
 * Global keys, taken before any screen sees them: `ON` (power on, or clear to the mode's resting
 * screen and drop history, research 02 §10.2), `SHIFT AC` (OFF), `MODE`, `SHIFT MODE` (SETUP) and
 * `SHIFT 9` (CLR). The last three open their menu over whatever is showing; `AC` there returns to
 * it untouched ("AC closes a menu without selecting anything", research 02 §4) — that the screen
 * under the menu survives is ⚠️ GUESS.
 *
 * The S/A latch (research 02 §6, VERIFIED: "It clears after the next key"): `SHIFT` sets S,
 * `SHIFT` again cancels it, `ALPHA` swaps it for A (and the reverse); any other key is read on the
 * latched layer and clears the latch, even when that combination does nothing.
 *
 * `AC` during a long calculation (∫, d/dx, Σ, SOLVE — "AC interrupts it", research 02 §10.7) breaks
 * it, and the calculation returns to its input "showing nothing" (EvalApi.kt). The owner feeds keys
 * one at a time, so that `AC` is still waiting when the broken press returns: a press's `interrupt`
 * answering true is the promise that it is coming. It has already done its work — the break — so
 * the machine spends it there and the next `AC` does nothing more; otherwise it would clear the very
 * input the break returned to (the SOLVE equation, a value typed into an editor cell, which an `AC`
 * there would zero with every other coefficient). Keys queued between the broken press and its `AC`
 * still act, which the real one — which ignores them while it calculates — would not (⚠️ GUESS that
 * the difference never matters; the queue is the owner's).
 *
 * The self-check `ON` runs "when an error or unexpected result occurs" (research 02 §5.3) — re-
 * initialise if memory is found corrupt — has nothing to find here: state is typed and checked as
 * it is built, and the one way corrupt state could enter, a saved text, is checked whole by
 * [MachineSnapshot] and gives a freshly initialised calculator, which is the self-check's outcome.
 */
class Machine(
    override val evaluator: Evaluator,
    override val formatter: Formatter,
    override val random: Random = Random.Default,
    private val modes: ModeRegistry = ModeRegistry.standard(),
) : Host {
    override var mode: Mode = Mode.COMP
        private set

    override var setup: Setup = Setup()
        private set

    override val memory: Memory = Memory()
    override val history: History = History()

    private val stack = mutableListOf<Screen>()

    /** Stack size under the open MODE/SETUP/CLR menus, or -1 when none is open. */
    private var globalDepth = NONE_OPEN
    private var layer = Layer.NONE

    /** An `AC` broke the last long calculation and is still to arrive; it has done its work (class KDoc). */
    private var acSpent = false

    /** Switched off by `SHIFT AC` or auto power-off; only `ON` acts until it is switched on. */
    var off: Boolean = false
        private set

    /** What the display shows now. */
    var frame: LcdFrame = LcdFrame.BLANK
        private set

    init {
        controller.enter(this)
        frame = render()
    }

    internal val controller: ModeController get() = modes[mode]

    override val naturalInput: Boolean get() = setup.io.naturalInput && !controller.linearOnly

    /** One physical key. */
    fun press(key: Key, interrupt: () -> Boolean = { false }): LcdFrame {
        when {
            key == Key.ON -> powerOn()
            off -> Unit
            key == Key.AC && acSpent -> spendAc()
            key == Key.SHIFT -> layer = if (layer == Layer.SHIFT) Layer.NONE else Layer.SHIFT
            key == Key.ALPHA -> layer = if (layer == Layer.ALPHA) Layer.NONE else Layer.ALPHA
            else -> dispatch(key, interrupt)
        }
        frame = render()
        return frame
    }

    /** The `AC` that broke a calculation arrives: the break was its work, and a latch set meanwhile goes. */
    private fun spendAc() {
        acSpent = false
        layer = Layer.NONE
    }

    /** `SHIFT AC`, or the owner's idle timer (research 02 §14: "about 10 or about 60 minutes"). */
    fun powerOff(): LcdFrame {
        off = true
        layer = Layer.NONE
        frame = LcdFrame.OFF
        return frame
    }

    private fun dispatch(key: Key, interrupt: () -> Boolean) {
        var broken = false
        val polled = { interrupt().also { if (it) broken = true } }
        val action = Keymap.resolve(key, layer, mode, controller.keys)
        val press = Press(key, layer, action, polled)
        layer = Layer.NONE
        val command = press.command
        val top = top()
        when {
            command == Command.OFF -> powerOff()
            command in GLOBAL_MENUS && !top.modal -> openGlobal(GlobalMenus.open(this, command))
            else -> top.handle(press)
        }
        // Whoever polled and heard "AC" stopped (EvalContext.interrupt): that AC is on its way.
        if (broken) acSpent = true
    }

    /**
     * `ON`: switches the calculator on, or — already on — clears to the mode's resting screen.
     * Either way history is cleared (research 02 §10.2, VERIFIED: "History is cleared by ON").
     */
    private fun powerOn() {
        off = false
        layer = Layer.NONE
        acSpent = false
        history.clear()
        home()
    }

    private fun render(): LcdFrame {
        if (off) return LcdFrame.OFF
        val drawn = top().render()
        return LcdFrame(drawn.pixels, drawn.indicators, drawn.cursor, drawn.off, drawn.text, setup.contrast)
    }

    /** A controller's [ModeController.enter] and [ModeController.home] must leave a screen up. */
    private fun top(): Screen = checkNotNull(stack.lastOrNull()) { "the $mode controller showed no screen" }

    override fun indicators(extra: Set<Indicator>): Set<Indicator> = buildSet {
        if (layer == Layer.SHIFT) add(Indicator.SHIFT)
        if (layer == Layer.ALPHA) add(Indicator.ALPHA)
        if (!isZero(memory.variables[Variable.M])) add(Indicator.MEMORY)
        MODE_ICONS[mode]?.let(::add)
        add(ANGLE_ICONS.getValue(setup.angle))
        when (setup.format) {
            is NumberFormat.Fix -> add(Indicator.FIX)
            is NumberFormat.Sci -> add(Indicator.SCI)
            is NumberFormat.Norm -> Unit
        }
        if (naturalInput) add(Indicator.MATH)
        addAll(extra)
    }

    override fun context(interrupt: () -> Boolean, statistics: StatisticsSource?, radix: Radix): EvalContext =
        EvalContext(mode, setup, memory, statistics, radix, random, interrupt)

    override fun recordAnswer(value: Value) {
        if (mode == Mode.COMP) memory.preAns = memory.ans
        memory.ans = value
    }

    override fun show(screen: Screen) {
        stack.clear()
        stack.add(screen)
        globalDepth = NONE_OPEN
    }

    override fun push(screen: Screen) {
        stack.add(screen)
    }

    override fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        if (globalDepth >= stack.size) globalDepth = NONE_OPEN
    }

    override fun replace(screen: Screen) {
        stack[stack.lastIndex] = screen
    }

    override fun home() {
        stack.clear()
        globalDepth = NONE_OPEN
        controller.home(this)
    }

    /** Opens a MODE/SETUP/CLR screen over what is showing, replacing one already open. */
    internal fun openGlobal(screen: Screen) {
        closeGlobal()
        globalDepth = stack.size
        stack.add(screen)
    }

    /** Closes the MODE/SETUP/CLR screens, back to what they were opened over. */
    internal fun closeGlobal() {
        if (globalDepth == NONE_OPEN) return
        while (stack.size > globalDepth.coerceAtLeast(1)) stack.removeAt(stack.lastIndex)
        globalDepth = NONE_OPEN
    }

    /**
     * The MODE menu chose [next] (research 02 §4). History is cleared by a mode change and PreAns
     * when COMP is left (research 02 §9, §10.2, VERIFIED). Choosing the mode already in use runs
     * it afresh too (⚠️ GUESS).
     */
    internal fun selectMode(next: Mode) {
        controller.leave(this)
        if (mode == Mode.COMP && next != Mode.COMP) memory.preAns = Memory.ZERO
        history.clear()
        mode = next
        stack.clear()
        globalDepth = NONE_OPEN
        controller.enter(this)
    }

    /**
     * A SETUP item was chosen. The SETUP screens close; a change of input/output format clears
     * history (research 02 §10.2: "a display-format change"); every screen on the stack and the
     * mode are told.
     */
    internal fun changeSetup(next: Setup) {
        val previous = setup
        setup = next
        closeGlobal()
        if (previous.io != next.io) history.clear()
        stack.toList().forEach { it.setupChanged(previous) }
        controller.setupChanged(previous, this)
    }

    /** SETUP ◀CONT▶ adjusts the contrast live, without leaving its screen (research 02 §5.2). */
    internal fun changeContrast(level: Int) {
        setup = setup.copy(contrast = level.coerceIn(Setup.MIN_CONTRAST, Setup.MAX_CONTRAST))
    }

    /**
     * CLR (research 02 §5.3). Setup: mode back to COMP, SETUP to its defaults except the contrast
     * (which no reset touches, `Setup`'s KDoc), history cleared. Memory: Ans, PreAns, M and the
     * variables — and, ⚠️ GUESS, the matrix and vector slots, as "all memories". All: both, plus
     * every mode's own data.
     */
    internal fun clear(scope: ClearScope) {
        if (scope != ClearScope.SETUP) clearMemory()
        modes.all.forEach { it.clear(scope) }
        if (scope != ClearScope.MEMORY) {
            controller.leave(this)
            if (mode != Mode.COMP) memory.preAns = Memory.ZERO
            mode = Mode.COMP
            setup = Setup(contrast = setup.contrast)
            history.clear()
            stack.clear()
            globalDepth = NONE_OPEN
            controller.enter(this)
        }
    }

    private fun clearMemory() {
        Variable.entries.forEach { memory.variables[it] = Memory.ZERO }
        memory.ans = Memory.ZERO
        memory.preAns = Memory.ZERO
        memory.matrices.keys.toList().forEach { memory.matrices[it] = null }
        memory.vectors.keys.toList().forEach { memory.vectors[it] = null }
    }

    /** What survives power-off, as versioned lossless text ([MachineSnapshot]). */
    fun snapshot(): String = MachineSnapshot.write(this)

    /**
     * Reads [snapshot]'s text back and switches on into the saved mode. False when the text is
     * unreadable — the machine may then hold part of it, so the caller must discard it and start
     * a fresh one (`Calculator.restore` does).
     */
    fun restore(text: String): Boolean = MachineSnapshot.read(text, this)

    /** Everything a restore puts back, all at once (see [MachineSnapshot]). */
    internal fun load(saved: MachineSnapshot.Saved) {
        mode = saved.mode
        setup = saved.setup
        saved.memory(memory)
        history.clear()
        off = false
        layer = Layer.NONE
        acSpent = false
        home()
        frame = render()
    }

    internal fun registry(): ModeRegistry = modes

    private companion object {
        const val NONE_OPEN = -1

        val GLOBAL_MENUS = setOf(Command.MODE, Command.SETUP, Command.CLR)

        /** Research 02 §6 (VERIFIED); BASE-N, EQN, TABLE, DIST, INEQ and RATIO have no icon of their own. */
        val MODE_ICONS: Map<Mode, Indicator> = mapOf(
            Mode.STAT to Indicator.STAT,
            Mode.CMPLX to Indicator.CMPLX,
            Mode.MATRIX to Indicator.MAT,
            Mode.VECTOR to Indicator.VCT,
        )

        val ANGLE_ICONS: Map<AngleUnit, Indicator> = mapOf(
            AngleUnit.DEG to Indicator.DEG,
            AngleUnit.RAD to Indicator.RAD,
            AngleUnit.GRA to Indicator.GRA,
        )

        /** M "holds a value other than zero" (research 02 §6). */
        fun isZero(value: Value?): Boolean = when (value) {
            null -> true
            is Real -> value.dec.isZero
            is ComplexValue -> value.re.dec.isZero && value.im.dec.isZero
            else -> false
        }
    }
}
