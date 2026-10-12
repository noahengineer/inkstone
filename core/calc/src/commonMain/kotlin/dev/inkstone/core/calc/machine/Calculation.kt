package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.ComplexFormat
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.eval.Answer
import dev.inkstone.core.calc.eval.EvalOutcome
import dev.inkstone.core.calc.eval.StatisticsSource
import dev.inkstone.core.calc.format.Presentation
import dev.inkstone.core.calc.input.AnsContinuation
import dev.inkstone.core.calc.input.Cursor
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.InputState
import dev.inkstone.core.calc.input.Item
import dev.inkstone.core.calc.input.TemplateKind
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.input.TokenItem
import dev.inkstone.core.calc.lcd.LcdFrame
import dev.inkstone.core.calc.lcd.MathLayout
import dev.inkstone.core.calc.parse.ParseResult
import dev.inkstone.core.calc.parse.Radix
import dev.inkstone.core.calc.parse.Statements
import dev.inkstone.core.calc.parse.parse

/**
 * How a mode adapts the shared [Calculation] screen. COMP, CMPLX and BASE-N are nothing but this
 * screen; STAT, MATRIX and VECTOR show it after `AC` leaves their editors (research 03 §4.2, §7,
 * §9) and plug their differences in here rather than copying the line.
 */
interface CalculationHooks {
    /** History is kept in COMP, CMPLX and BASE-N only (research 02 §10.2, VERIFIED). */
    val keepsHistory: Boolean get() = false

    /** STAT's data, for the evaluator's statistics variables and estimates. */
    val statistics: StatisticsSource? get() = null

    /**
     * BASE-N's current base, or null in every other mode. Non-null also puts the base's label
     * (`Dec`/`Hex`/`Bin`/`Oct`) above the result line (research 03 §5.1).
     */
    val radix: Radix? get() = null

    /** A cyan base key was pressed (BASE-N only): switch to [radix]. A result on screen is redrawn in it. */
    fun changeRadix(radix: Radix, line: Calculation) = Unit

    /**
     * A successful answer, before it is shown. Return true to take it over — MATRIX and VECTOR
     * show a matrix or vector result on their own MatAns/VctAns screen (research 03 §7.2, §9.3) —
     * and then **the hook owns Ans**: the line records nothing and adds nothing to history, and is
     * left editing what was typed; a STO or M+ that evaluated the line stops there too, so a
     * matrix never lands in a variable or in M. Return false and the line shows the answer itself.
     */
    fun takeAnswer(answer: Answer, line: Calculation): Boolean = false

    /** The mode's own `SHIFT` digit menu ([Command.MODE_MENU]); its picks go in with [Calculation.insert]. */
    fun modeMenu(line: Calculation) = Unit

    companion object {
        /** No history, no statistics, decimal. */
        val PLAIN: CalculationHooks = object : CalculationHooks {}
    }
}

/** What the calculation line is showing. */
internal sealed interface CalcView {
    /** The input with its cursor (research 02 §7). */
    data object Editing : CalcView

    /**
     * A result: the line that produced it ([expr]), which statement of it ran (research 02 §10.1),
     * when it came from or went into the history its index there (research 02 §10.2), and the
     * complex form its statement asked for ([ComplexOverride]).
     */
    class Result(
        val expr: Expr,
        val answer: Answer,
        var presentation: Presentation,
        val decimal: Boolean,
        val statement: Int,
        val statements: Int,
        val historyIndex: Int?,
        val complex: ComplexFormat? = null,
    ) : CalcView {
        /** The result line's offset in dots, while a wide result is scrolled (research 02 §8.1). */
        var scroll: Int = 0

        /** More `:` statements follow: the Disp indicator (research 02 §6, §10.1). */
        val intermediate: Boolean get() = statement < statements - 1
    }

    /** An error screen for [expr]; [at] is where `◀`/`▶` put the cursor (research 02 §11). */
    class Failure(val expr: Expr, val error: CalcError, val at: Cursor?) : CalcView
}

/**
 * The calculation screen of COMP, CMPLX and BASE-N, and of every mode that has a calculation line
 * (research 02 §7–§11): typing with the cursor, `=` and `SHIFT =`, the result keys, Ans and PreAns,
 * the history, multi-statements, STO/RCL/M+/M−, the error screen, and the menus that type into the
 * line (hyp, DRG▶, CONST, CONV, the mode's own `SHIFT` menu). CALC and SOLVE start here too.
 *
 * Mode code drives it with [insert] (a menu pick), [startWith] (MatAns `+` back to the line),
 * [clear] and [fail]; everything else arrives as key presses through [handle].
 *
 * Behaviour that the guide does not settle is marked ⚠️ GUESS where it is implemented.
 */
class Calculation(internal val host: Host, internal val hooks: CalculationHooks = CalculationHooks.PLAIN) :
    Screen,
    TypingTarget {
    /** The line being typed — or, under a result, the line that produced it. */
    override var input: InputState = InputState.empty(host.naturalInput)
        internal set

    internal var scroll = 0
    internal var view: CalcView = CalcView.Editing

    /** STO or RCL waiting for its variable key (the STO/RCL indicator, research 02 §6). */
    internal var pending: Command? = null

    override fun handle(press: Press) {
        val waiting = pending
        if (waiting != null) {
            awaitVariable(waiting, press)
            return
        }
        when (val shown = view) {
            CalcView.Editing -> editing(press)
            is CalcView.Result -> showing(shown, press)
            is CalcView.Failure -> failed(shown, press)
        }
    }

    override fun render(): LcdFrame = when (val shown = view) {
        CalcView.Editing -> renderEditing()
        is CalcView.Result -> renderResult(shown)
        is CalcView.Failure -> renderFailure(shown)
    }

    /**
     * Any input/output change (MthIO-MathO, MthIO-LineO, LineIO) starts an empty line. Between
     * Natural and Linear input the line was typed for the other display; and every such change is
     * "a display-format change", which clears history (research 02 §10.2) — so a result left on
     * screen would point into an emptied history, ▲ lit with nothing above it. Any other change
     * redraws a result in the new format, which is how FACT's display "is left by changing the
     * angle unit or Fix/Sci/Norm" (research 02 §10.13), and from its beginning, as every restyled
     * result is: the new form is not the old one's width, so an old scroll offset can point past
     * its end. ⚠️ GUESS that the line survives at all.
     */
    override fun setupChanged(previous: Setup) {
        if (previous.io != host.setup.io || input.natural != host.naturalInput) {
            pending = null
            clear()
            return
        }
        (view as? CalcView.Result)?.let {
            it.presentation = present(it.answer, it.decimal, it.complex)
            it.scroll = 0
        }
    }

    /**
     * Types [item] as a key would: at the cursor while editing, or — with a result on screen — on a
     * fresh line, after `Ans` when the item needs a left operand ([AnsContinuation], research 02 §9).
     */
    override fun insert(item: Item) {
        if (view is CalcView.Result) {
            newLine(if (AnsContinuation.continues(item)) TokenItem(Tok.ANS) else null) { LineEditor.insert(it, item) }
        } else {
            view = CalcView.Editing
            accept(LineEditor.insert(input, item))
        }
    }

    /** A fresh line that starts with [prefix] (`MatAns`, `VctAns`) and then takes [press] (research 03 §7.2). */
    fun startWith(prefix: Item, press: Press) = newLine(prefix) { LineEditor.apply(it, press.action) }

    /** An empty line, editing. */
    fun clear() {
        input = InputState.empty(host.naturalInput)
        scroll = 0
        view = CalcView.Editing
    }

    /** Shows [error] for the current line; `◀`/`▶` will put the cursor at [at] (research 02 §11). */
    override fun fail(error: CalcError, at: Cursor?) {
        pending = null
        view = CalcView.Failure(input.expr, error, at)
    }

    private fun editing(press: Press) {
        val command = press.command
        when {
            command == null -> accept(LineEditor.apply(input, press.action))
            replays(command) -> replay(command)
            LineEditor.edits(press.action) -> accept(LineEditor.apply(input, press.action))
            else -> commands[command]?.invoke(press)
        }
    }

    private fun showing(shown: CalcView.Result, press: Press) {
        val command = press.command
        if (command == null) {
            typeAfterResult(shown, press.action)
        } else {
            (resultCommands[command] ?: commands[command])?.invoke(press)
        }
    }

    /**
     * The error screen takes `AC`, which "clears the message and the expression", and `◀`/`▶`,
     * which return to the expression "with the cursor at the error position" (research 02 §11,
     * VERIFIED). Every other key is ignored (⚠️ GUESS).
     */
    private fun failed(shown: CalcView.Failure, press: Press) {
        when (press.command) {
            Command.AC -> clear()
            Command.LEFT, Command.RIGHT -> editAt(shown.expr, shown.at)
            else -> Unit
        }
    }

    /** Editing [expr] with the cursor at [at] — Goto from an error — or at the end when it has no place. */
    internal fun editAt(expr: Expr, at: Cursor?) {
        input = InputState(expr, at ?: Cursor(index = expr.items.size), host.naturalInput)
        scroll = MathLayout.inputScroll(input.expr, input.cursor, input.natural)
        view = CalcView.Editing
    }

    /**
     * A typing key while a result is shown starts a new line; one that needs a left operand
     * starts it with `Ans` (research 02 §9: `3×4 =` then `÷30` shows `Ans÷30`). `°'"` is the
     * exception: on a result it converts to and from sexagesimal (research 02 §8.9).
     */
    private fun typeAfterResult(shown: CalcView.Result, action: Action) {
        val continues = when (action) {
            Action.Type(Tok.DMS) -> return toggleDms(shown)

            is Action.Type -> AnsContinuation.continues(action.tok)

            is Action.Open -> AnsContinuation.continues(action.kind, host.naturalInput) || splitsAns(action.kind)

            // A legend-less key leaves the result where it is.
            else -> return
        }
        newLine(if (continues) TokenItem(Tok.ANS) else null) { LineEditor.apply(it, action) }
    }

    /**
     * ⚠️ GUESS: in LineIO both fraction keys type `⌟` (research 02 §7.4), and `⌟` needs a value on
     * its left exactly as `÷` does — a fresh line that starts with it can only ever be a Syntax
     * ERROR, which is the argument that puts every INFERRED key in
     * [dev.inkstone.core.calc.input.AnsContinuation] there. That object leaves `⌟` out because in
     * Natural display the same key opens an *empty* fraction, which needs nothing on its left;
     * the reason stops at Natural display, so in LineIO the line starts `Ans⌟`. The emulator
     * settles both (research 00 §5 #16), and the rule belongs in `AnsContinuation` once it does.
     */
    private fun splitsAns(kind: TemplateKind): Boolean = !host.naturalInput && kind in FRACTION_KEYS

    internal fun newLine(prefix: Item?, edit: (InputState) -> InputState) {
        clear()
        val started = prefix?.let { LineEditor.insert(input, it) } ?: input
        input = started
        accept(edit(started))
    }

    /** Takes an edited line, unless the edit was refused (the same instance came back). */
    internal fun accept(next: InputState) {
        if (next === input) return
        input = next
        scroll = MathLayout.inputScroll(next.expr, next.cursor, next.natural, scroll)
    }

    /**
     * Runs statement [statement] of [expr] (research 02 §10.1: one statement per `=`) and shows the
     * answer, or the error. Returns the answer this line shows, or null — including when the mode's
     * [CalculationHooks.takeAnswer] took it, so STO and M+ never act on a value the mode now owns
     * (a matrix in a variable, a vector added to M).
     */
    internal fun execute(expr: Expr, statement: Int, decimal: Boolean, press: Press): Answer? {
        input = InputState.editing(expr, host.naturalInput)
        scroll = 0
        return when (val parsed = parse(expr, complex = host.mode == Mode.CMPLX)) {
            is ParseResult.Failure -> null.also { fail(parsed.error, parsed.at) }

            is ParseResult.Ok -> {
                val list = (parsed.node as? Statements)?.list ?: listOf(parsed.node)
                val index = if (statement in list.indices) statement else 0
                val context = host.context(press.interrupt, hooks.statistics, hooks.radix ?: Radix.DEC)
                when (val outcome = host.evaluator.evaluate(list[index], parsed.spans, context)) {
                    is EvalOutcome.Done -> outcome.answer.takeIf {
                        show(Ran(expr, index, list.size, ComplexOverride.of(list[index])), it, decimal)
                    }

                    is EvalOutcome.Failed -> null.also { fail(outcome.error, outcome.at) }

                    // "AC broke a long calculation. The machine returns to the input, showing nothing."
                    EvalOutcome.Interrupted -> null.also { view = CalcView.Editing }
                }
            }
        }
    }

    /** Which line ran, which of its `:` statements, and the complex form that statement asked for. */
    private class Ran(val expr: Expr, val statement: Int, val statements: Int, val complex: ComplexFormat?)

    /** Shows [answer] as this line's result; false when the mode's hook took it instead. */
    private fun show(ran: Ran, answer: Answer, decimal: Boolean): Boolean {
        if (hooks.takeAnswer(answer, this)) {
            view = CalcView.Editing
            return false
        }
        host.recordAnswer(answer.value)
        var index: Int? = null
        if (hooks.keepsHistory) {
            host.history.add(HistoryEntry(ran.expr, answer, decimal, ran.complex))
            index = host.history.lastIndex
        }
        val presentation = present(answer, decimal, ran.complex)
        view = CalcView.Result(
            ran.expr,
            answer,
            presentation,
            decimal,
            ran.statement,
            ran.statements,
            index,
            ran.complex,
        )
        return true
    }

    /** The first face of [answer] — in the complex form its statement asked for ([ComplexOverride]). */
    internal fun present(answer: Answer, decimal: Boolean, complex: ComplexFormat? = null): Presentation =
        host.formatter.present(answer, host.setupFor(complex), host.mode, decimal)

    /** `◀` puts the cursor at the end of the replayed line, `▶` at its start (research 02 §10.2). */
    internal fun reopen(expr: Expr, atStart: Boolean) {
        val cursor = if (atStart) Cursor() else Cursor(index = expr.items.size)
        input = InputState(expr, cursor, host.naturalInput)
        scroll = MathLayout.inputScroll(expr, cursor, input.natural)
        view = CalcView.Editing
    }

    /** `=` again: the next `:` statement, or the whole line from the start (research 02 §10.1). */
    private fun again(press: Press, decimal: Boolean) {
        val shown = view as? CalcView.Result ?: return
        val next = if (shown.intermediate) shown.statement + 1 else 0
        execute(shown.expr, next, decimal, press)
    }

    private fun run(press: Press, decimal: Boolean) {
        if (!input.expr.isEmpty) execute(input.expr, 0, decimal, press)
    }

    /**
     * Keys that act alike while editing and under a result. The result keys (S⇔D, ENG, FACT, …)
     * are not here: while a line is being typed they do nothing — ⚠️ GUESS, the guide only ever
     * presses them on a result.
     */
    private val commands: Map<Command, (Press) -> Unit> = mapOf(
        Command.AC to { _ -> clear() },
        Command.EQUALS to { press -> run(press, decimal = false) },
        Command.EQUALS_DECIMAL to { press -> run(press, decimal = true) },
        Command.STO to { _ -> pending = Command.STO },
        Command.RCL to { _ -> pending = Command.RCL },
        Command.M_PLUS to { press -> accumulate(press, subtract = false) },
        Command.M_MINUS to { press -> accumulate(press, subtract = true) },
        Command.CALC to { press -> startEquation(press, solve = false) },
        Command.SOLVE to { press -> startEquation(press, solve = true) },
        Command.HYP to { press -> LineMenus.open(host, press.command, this) },
        Command.DRG to { press -> LineMenus.open(host, press.command, this) },
        Command.CONST to { press -> LineMenus.open(host, press.command, this) },
        Command.CONV to { press -> LineMenus.open(host, press.command, this) },
        Command.MODE_MENU to { _ -> hooks.modeMenu(this) },
        Command.BASE_DEC to { _ -> hooks.changeRadix(Radix.DEC, this) },
        Command.BASE_HEX to { _ -> hooks.changeRadix(Radix.HEX, this) },
        Command.BASE_BIN to { _ -> hooks.changeRadix(Radix.BIN, this) },
        Command.BASE_OCT to { _ -> hooks.changeRadix(Radix.OCT, this) },
    )

    /** Keys that mean something else while a result is shown (research 02 §8, §10.2). */
    private val resultCommands: Map<Command, (Press) -> Unit> = mapOf(
        Command.EQUALS to { press -> again(press, decimal = false) },
        Command.EQUALS_DECIMAL to { press -> again(press, decimal = true) },
        Command.LEFT to { _ -> onResult { arrow(it, right = false) } },
        Command.RIGHT to { _ -> onResult { arrow(it, right = true) } },
        Command.UP to { _ -> onResult { browse(it, -1) } },
        Command.DOWN to { _ -> onResult { browse(it, 1) } },
        Command.DEL to { _ -> onResult { reopen(it.expr, atStart = false) } },
        Command.INS to { _ -> Unit },
        Command.S_TO_D to { _ -> onResult { restyle(it, Command.S_TO_D) } },
        Command.MIXED to { _ -> onResult { restyle(it, Command.MIXED) } },
        Command.ENG to { _ -> onResult { restyle(it, Command.ENG) } },
        Command.ENG_LEFT to { _ -> onResult { restyle(it, Command.ENG_LEFT) } },
        Command.FACT to { _ -> onResult { factorize(it) } },
    )

    private inline fun onResult(action: (CalcView.Result) -> Unit) {
        (view as? CalcView.Result)?.let(action)
    }

    private companion object {
        val FRACTION_KEYS = setOf(TemplateKind.FRACTION, TemplateKind.MIXED_FRACTION)
    }
}
