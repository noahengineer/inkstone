package dev.inkstone.core.calc

import dev.inkstone.core.calc.eval.Evaluation
import dev.inkstone.core.calc.eval.Evaluator
import dev.inkstone.core.calc.format.Formatter
import dev.inkstone.core.calc.format.Formatting
import dev.inkstone.core.calc.lcd.LcdFrame
import dev.inkstone.core.calc.machine.Machine
import dev.inkstone.core.calc.machine.ModeRegistry
import dev.inkstone.core.calc.mode.StandardModes
import kotlin.random.Random

/**
 * The fx-991ZA PLUS II, as one object: press physical keys, read the display.
 *
 * Not thread-safe by design. One owner (the app's controller) feeds it keys **one at a time** from
 * a single consumer, which is also what makes a slow `∫` behave like the real one: later keys
 * wait. Long operations poll [press]'s `interrupt` so `AC` can break them (research 07 §4.6).
 *
 * Persist with [snapshot] / [restore]: modes, SETUP, the nine variables, Ans/PreAns, statistics
 * data, matrices and vectors survive, as they survive switching the real calculator off.
 *
 * The behaviour is the key machine's (`machine.Machine`), with the modes in [StandardModes], on the
 * one production evaluator and formatter ([Evaluation], [Formatting]). The internal constructor is
 * the seam the machine's own tests use to run the same key logic over documented fakes; the
 * everyday calculations run through the public one (`e2e.EverydayCalculationsTest`). ⚠️ This
 * constructor once built the stand-in pair the machine was first written against (integer
 * arithmetic only), and it reached the tablet that way — Math ERROR for every decimal, with every
 * test green, because every test used the internal constructor.
 */
class Calculator internal constructor(
    evaluator: Evaluator,
    formatter: Formatter,
    random: Random = Random.Default,
    modes: ModeRegistry = StandardModes.registry(),
) {
    constructor(random: Random = Random.Default) : this(Evaluation, Formatting, random)

    internal val machine = Machine(evaluator, formatter, random, modes)

    /** What the display shows now. */
    var frame: LcdFrame = machine.frame
        private set

    /**
     * Press one physical key and return the new display.
     *
     * [interrupt] is polled by long calculations (∫, d/dx, Σ, SOLVE); answering true means "an `AC`
     * is queued behind this key", and it is a promise: the calculation stops, returns to its input,
     * and the next `AC` pressed is taken as the one that broke it and does nothing more — so it does
     * not then clear that input (`machine.Machine`). Answer true only while an `AC` really is queued.
     */
    fun press(key: Key, interrupt: () -> Boolean = { false }): LcdFrame {
        frame = machine.press(key, interrupt)
        return frame
    }

    /** SETUP ▼6 APO: minutes of no key presses before the calculator switches itself off. */
    val autoPowerOffMinutes: Int get() = machine.setup.autoPowerOffMinutes

    /**
     * Auto power-off: the owner's idle timer calls this after [autoPowerOffMinutes]. Same as
     * `SHIFT AC` — the glass goes blank and memory is kept; `ON` brings it back.
     */
    fun autoPowerOff(): LcdFrame {
        frame = machine.powerOff()
        return frame
    }

    /** Everything the real calculator keeps while switched off, as lossless text. */
    fun snapshot(): String = machine.snapshot()

    /** Reads [text] into this calculator; false (and then this one must be discarded) if unreadable. */
    internal fun load(text: String): Boolean {
        val ok = machine.restore(text)
        frame = machine.frame
        return ok
    }

    companion object {
        /** Rebuild a calculator from [snapshot] output; an unreadable snapshot gives a reset one. */
        fun restore(snapshot: String, random: Random = Random.Default): Calculator =
            restore(snapshot) { Calculator(random) }

        /** [restore] with the calculators built by [create] — the seam the machine tests use. */
        internal fun restore(snapshot: String, create: () -> Calculator): Calculator {
            val restored = create()
            return if (restored.load(snapshot)) restored else create()
        }
    }
}
