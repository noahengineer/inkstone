package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.ComplexFormat
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.eval.Answer
import dev.inkstone.core.calc.eval.EvalOutcome
import dev.inkstone.core.calc.eval.Memory
import dev.inkstone.core.calc.eval.SolveOutcome
import dev.inkstone.core.calc.format.Presentation
import dev.inkstone.core.calc.input.Cursor
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.InputState
import dev.inkstone.core.calc.input.Item
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.lcd.Indicator
import dev.inkstone.core.calc.lcd.LabelledValue
import dev.inkstone.core.calc.lcd.LcdFrame
import dev.inkstone.core.calc.lcd.Screens
import dev.inkstone.core.calc.parse.BinOp
import dev.inkstone.core.calc.parse.Binary
import dev.inkstone.core.calc.parse.Call
import dev.inkstone.core.calc.parse.Derivative
import dev.inkstone.core.calc.parse.Equation
import dev.inkstone.core.calc.parse.Fn
import dev.inkstone.core.calc.parse.Integral
import dev.inkstone.core.calc.parse.Node
import dev.inkstone.core.calc.parse.ParseResult
import dev.inkstone.core.calc.parse.Radix
import dev.inkstone.core.calc.parse.SourceMap
import dev.inkstone.core.calc.parse.Statements
import dev.inkstone.core.calc.parse.Summation
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.parse.parse
import dev.inkstone.core.calc.value.DecimalReal
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.Value

/**
 * `CALC` (COMP and CMPLX) and `SHIFT CALC` SOLVE (COMP), research 02 §10.14–10.15. The line —
 * or the line under a result — is parsed; an error, or an equation SOLVE refuses, goes to the
 * line's own error screen, anything else starts an [EquationRun] over it.
 */
internal fun Calculation.startEquation(press: Press, solve: Boolean) {
    val allowed = if (solve) setOf(Mode.COMP) else setOf(Mode.COMP, Mode.CMPLX)
    val expr = (view as? CalcView.Result)?.expr ?: input.expr
    if (host.mode !in allowed || expr.isEmpty) return
    pending = null
    input = InputState.editing(expr, host.naturalInput)
    when (val parsed = parse(expr, complex = host.mode == Mode.CMPLX, solve = solve)) {
        is ParseResult.Failure -> fail(parsed.error, parsed.at)

        is ParseResult.Ok -> {
            val refusal = if (solve) EquationRun.refusal(host, parsed.node, parsed.spans) else null
            if (refusal != null) {
                fail(refusal.error, refusal.at)
            } else {
                val run = EquationRun(host, this, expr, parsed.node, parsed.spans, solve)
                host.push(run)
                run.begin(press)
            }
        }
    }
}

/**
 * The CALC and SOLVE screens, in order: one `A?` prompt per variable showing its current value
 * (research 02 §10.14: "The prompt is `A?` on the top line with the variable's current value at
 * the bottom right"), then for CALC the result under the expression, or for SOLVE `Solve for X`
 * and the solution screen `X=` / `L-R=` (SA slides 13–14, VERIFIED), with `Continue:[=]` when the
 * iterations run out.
 *
 * Values are typed with "Linear Display procedures" (research 02 §10.14, VERIFIED) and `=` enters
 * each; `=` with nothing typed keeps the value shown. The hyp, DRG▶, CONST and CONV keys type into
 * the value as they would into the line ([LineMenus.open]) — ⚠️ GUESS: the guide types only digits
 * and `√3)` at a prompt. An `=` in the expression assigns (`Y=X²+X+3`, research 02 §10.14): that is
 * the evaluator's reading of an [Equation], and which variables it prompts for is its
 * `variablesOf`.
 *
 * On a CALC result `=` runs the next `:` statement, or after the last one starts the prompts
 * again, and `CALC` starts them again at once (VERIFIED: "`CALC` (or `=`) starts the prompts
 * again"; that `CALC` does not step through the statements first is ⚠️ GUESS). The result keys
 * restyle a CALC result as they would the line's — ⚠️ GUESS, the guide never presses one there.
 * On a solution `=` (or `SHIFT CALC`) starts the prompts again (VERIFIED for `=`). `AC` leaves,
 * back to the equation on the line — ⚠️ GUESS: the guide says only "AC exits". An `AC` that
 * breaks the calculation or Newton's iteration leaves the same way, the variable as it was (the
 * evaluator puts it back) and that `AC` spent on the break (see [Machine]); one that breaks the
 * evaluation of a value typed at a prompt leaves the value typed there. CALC's result updates Ans
 * like any displayed result; SOLVE's leaves Ans alone (⚠️ GUESS, research 02 §15 #12). A closing
 * `▶r∠θ`/`▶a+bi` draws every CALC answer of the line in that form ([ComplexOverride], 04 E-48).
 */
internal class EquationRun(
    private val host: Host,
    private val line: Calculation,
    private val expr: Expr,
    private val node: Node,
    private val spans: SourceMap,
    private val solve: Boolean,
) : Screen,
    TypingTarget {
    private sealed interface Phase {
        data class Asking(val index: Int) : Phase

        data object SolveFor : Phase

        /** A CALC result, drawn in the complex form its statement asked for ([ComplexOverride]). */
        class Answered(
            val presentation: Presentation,
            val statement: Int,
            val statements: Int,
            val complex: ComplexFormat?,
        ) : Phase

        class Solved(val variable: Variable, val value: Real, val leftMinusRight: Real) : Phase

        class Unfinished(val current: Real) : Phase

        /** An error; [resume] is the prompt it came from, or null for one that ends the run. */
        class Failed(val error: CalcError, val at: Cursor?, val resume: Phase?) : Phase
    }

    private val equation: Equation = node as? Equation ?: Equation(node, null, null)
    private val target: Variable = equation.solveFor ?: Variable.X
    private val prompts: List<Variable> =
        if (solve) unknowns(host, equation).filter { it != target } else host.evaluator.variablesOf(node)
    private var phase: Phase = Phase.Asking(0)
    private var field: InputState = InputState.empty(natural = false)

    /** The value being typed at a prompt (a [TypingTarget] for the hyp/CONST/CONV menus). */
    override val input: InputState get() = this.field

    fun begin(press: Press) = ask(0, press)

    override fun handle(press: Press) {
        when (val now = phase) {
            is Phase.Asking, Phase.SolveFor -> prompt(now, press)
            is Phase.Failed -> failed(now, press)
            else -> finished(press)
        }
    }

    private fun prompt(now: Phase, press: Press) {
        when {
            press.command == Command.AC -> leave()
            press.command == Command.EQUALS -> enter(now, press)
            LineEditor.edits(press.action) -> field = LineEditor.apply(field, press.action)
            else -> LineMenus.open(host, press.command, this)
        }
    }

    /** A menu pick at a prompt types into the value, as a key would. */
    override fun insert(item: Item) {
        if (prompting()) field = LineEditor.insert(field, item)
    }

    /** A CONST/CONV number out of range at a prompt: the prompt's own error, back to the prompt after. */
    override fun fail(error: CalcError, at: Cursor?) {
        val now = phase
        if (prompting()) phase = Phase.Failed(error, at, now)
    }

    private fun prompting(): Boolean = phase is Phase.Asking || phase == Phase.SolveFor

    /** `=` on a prompt: the typed value (if any) goes into the variable, then the next step. */
    private fun enter(now: Phase, press: Press) {
        val variable = (now as? Phase.Asking)?.let { prompts[it.index] } ?: target
        if (!field.expr.isEmpty) {
            val value = evaluateField(now, press) ?: return
            host.memory.variables[variable] = value
        }
        field = InputState.empty(natural = false)
        if (now is Phase.Asking) ask(now.index + 1, press) else runSolve(startValue(), press)
    }

    /** The typed value; on an error the run shows it and returns to this prompt after (`AC`, `◀`, `▶`). */
    private fun evaluateField(now: Phase, press: Press): Value? =
        when (val typed = host.evaluateLine(field.expr, press.interrupt)) {
            is LineValue.Ok -> typed.value
            is LineValue.Error -> null.also { phase = Phase.Failed(typed.error, typed.at, now) }
            LineValue.Interrupted -> null
        }

    private fun ask(index: Int, press: Press) {
        phase = when {
            index < prompts.size -> Phase.Asking(index)
            solve -> Phase.SolveFor
            else -> return calculate(0, press)
        }
    }

    private fun calculate(statement: Int, press: Press) {
        val list = (node as? Statements)?.list ?: listOf(node)
        val outcome = host.evaluator.evaluate(list[statement], spans, host.context(press.interrupt))
        phase = when (outcome) {
            is EvalOutcome.Done -> {
                host.recordAnswer(outcome.answer.value)
                val complex = ComplexOverride.of(list[statement])
                Phase.Answered(present(outcome.answer, complex), statement, list.size, complex)
            }

            is EvalOutcome.Failed -> Phase.Failed(outcome.error, outcome.at, null)

            EvalOutcome.Interrupted -> return leave()
        }
    }

    private fun startValue(): Real = host.memory.variables[target] as? Real ?: Memory.ZERO

    /**
     * Newton's method from [start]. The solution goes into its variable — "the solution, already
     * stored" is the evaluator's promise, and the machine writes it again so that what the screen
     * shows and what `X` holds cannot differ (research 02 §10.15, SA slide 14: the next SOLVE starts
     * from it).
     */
    private fun runSolve(start: Real, press: Press) {
        val context = host.context(press.interrupt)
        phase = when (val outcome = host.evaluator.solve(equation, spans, context, target, start)) {
            is SolveOutcome.Solved -> {
                host.memory.variables[outcome.variable] = outcome.value
                Phase.Solved(outcome.variable, outcome.value, outcome.leftMinusRight)
            }

            is SolveOutcome.Unfinished -> Phase.Unfinished(outcome.current)

            is SolveOutcome.Failed -> Phase.Failed(outcome.error, outcome.at, null)

            SolveOutcome.Interrupted -> return leave()
        }
    }

    /** A CALC result, a solution or `Continue:[=]`. */
    private fun finished(press: Press) {
        val command = press.command
        val now = phase
        when {
            command == Command.AC -> leave()
            now is Phase.Unfinished -> if (command == Command.EQUALS) runSolve(now.current, press)
            command == startKey -> ask(0, press)
            command != Command.EQUALS -> if (now is Phase.Answered) restyle(now, press)
            now is Phase.Answered && now.statement < now.statements - 1 -> calculate(now.statement + 1, press)
            else -> ask(0, press)
        }
    }

    /** The key that started this run, which starts its prompts again from a result. */
    private val startKey: Command get() = if (solve) Command.SOLVE else Command.CALC

    /** S⇔D, `SHIFT S⇔D`, ENG, `SHIFT ENG` and `°'"` on a CALC result, as on the line's (research 02 §8). */
    private fun restyle(now: Phase.Answered, press: Press) {
        val command = press.command
        val setup = host.setupFor(now.complex)
        val next = when {
            press.action == Action.Type(Tok.DMS) -> host.toggleDms(now.presentation, setup)
            command != null -> host.restyle(now.presentation, command, setup)
            else -> null
        }
        next?.let { phase = Phase.Answered(it, now.statement, now.statements, now.complex) }
    }

    /**
     * Research 02 §11 applied to the run. An error in a value typed at a prompt returns to that
     * prompt: `AC` "clears the message and the expression" — the value typed, so the prompt shows
     * the variable's value again — and `◀`/`▶` put the cursor at the error inside the typed value
     * (⚠️ GUESS that a prompt follows the line's rule; the guide shows only the line's). An error
     * of the run itself (the expression's Math ERROR, Can't Solve) is the line's error: `AC` clears
     * the message and the expression, exactly as SOLVE's Variable ERROR from the same key does,
     * and `◀`/`▶` go to the error's place on the line.
     */
    private fun failed(now: Phase.Failed, press: Press) {
        val resume = now.resume
        when (press.command) {
            Command.AC -> if (resume != null) retype(resume, null) else clearLine()
            Command.LEFT, Command.RIGHT -> if (resume != null) retype(resume, now.at ?: field.cursor) else goto(now)
            else -> Unit
        }
    }

    private fun clearLine() {
        host.pop()
        line.clear()
    }

    /** Back to the prompt [resume]: an empty field, or the typed value with the cursor at [at]. */
    private fun retype(resume: Phase, at: Cursor?) {
        field = if (at == null) InputState.empty(natural = false) else field.copy(cursor = at)
        phase = resume
    }

    /**
     * An input/output change has already emptied the line under this run and cleared history
     * (research 02 §7.2, §10.2), so the run goes too: leaving it later would reopen an expression
     * typed for the other display, whose templates the Linear parser cannot read. The machine
     * calls this with the run on top — it pushes nothing over itself, and SETUP has closed. Any
     * other change redraws a CALC result in the new format, as the line does.
     */
    override fun setupChanged(previous: Setup) {
        if (previous.io != host.setup.io) {
            host.pop()
            return
        }
        val now = phase as? Phase.Answered ?: return
        val redrawn = present(now.presentation.answer, now.complex)
        phase = Phase.Answered(redrawn, now.statement, now.statements, now.complex)
    }

    private fun goto(now: Phase.Failed) {
        host.pop()
        line.editAt(expr, now.at)
    }

    private fun leave() {
        host.pop()
        line.reopen(expr, atStart = false)
    }

    private fun present(answer: Answer, complex: ComplexFormat?): Presentation =
        host.formatter.present(answer, host.setupFor(complex), host.mode, false)

    private fun valueExpr(value: Value): Expr = host.formatter.valueExpr(value, host.setup, host.mode, DEC)

    /**
     * SOLVE's "solution is always decimal" (research 02 §10.15, VERIFIED) is a rule about the
     * screen, so it is kept here whatever kind of [Real] the evaluator returns; the variable keeps
     * the value as solved.
     */
    private fun decimal(value: Real): Real = value as? DecimalReal ?: DecimalReal(value.dec)

    override fun render(): LcdFrame {
        val icons = host.indicators()
        return when (val now = phase) {
            is Phase.Asking -> promptFrame(icons, prompts[now.index].name + "?", prompts[now.index])

            Phase.SolveFor -> promptFrame(icons, SOLVE_FOR + target.name, target)

            is Phase.Answered -> answered(now)

            is Phase.Solved -> Screens.stacked(
                icons,
                expr,
                listOf(
                    LabelledValue(now.variable.name + "=", valueExpr(decimal(now.value))),
                    LabelledValue(LEFT_MINUS_RIGHT, valueExpr(decimal(now.leftMinusRight))),
                ),
                natural = host.naturalInput,
                answer = 1,
            )

            is Phase.Unfinished -> Screens.labelValue(icons, CONTINUE, valueExpr(now.current))

            is Phase.Failed -> Screens.error(icons, now.error)
        }
    }

    /** Nothing typed: the variable's current value, bottom right; typing: the typed line, with the cursor. */
    private fun promptFrame(icons: Set<Indicator>, label: String, variable: Variable): LcdFrame {
        if (field.expr.isEmpty) {
            val current = host.memory.variables[variable] ?: Memory.ZERO
            return Screens.labelValue(icons, label, valueExpr(current), natural = host.naturalInput)
        }
        return Screens.labelValue(
            icons,
            label,
            field.expr,
            natural = false,
            cursor = field.cursor,
            cursorShape = LineEditor.cursorShape(field),
        )
    }

    private fun answered(now: Phase.Answered): LcdFrame {
        val laidOut = host.formatter.show(now.presentation, host.setupFor(now.complex), host.mode, DEC)
        val icons = host.indicators(if (now.statement < now.statements - 1) setOf(Indicator.DISP) else emptySet())
        if (laidOut.parts.isNotEmpty()) {
            return Screens.stacked(icons, expr, laidOut.parts, host.naturalInput, answer = laidOut.parts.size)
        }
        return Screens.calculation(
            icons,
            expr,
            null,
            host.naturalInput,
            result = laidOut.result,
            naturalResult = laidOut.naturalResult,
        )
    }

    /** Why SOLVE will not start, and where Goto puts the cursor. */
    class Refusal(val error: CalcError, val at: Cursor?)

    companion object {
        private val DEC = Radix.DEC

        /** `Solve for ` + the variable (research 02 §10.15, SECONDARY string). */
        private const val SOLVE_FOR = "Solve for "
        private const val LEFT_MINUS_RIGHT = "L-R="

        /** The firmware string (research 02 §10.15, SECONDARY); its screen layout is ⚠️ GUESS. */
        private const val CONTINUE = "Continue:[=]"

        /**
         * Why SOLVE cannot start, or null:
         * - a multi-statement is refused (⚠️ GUESS: Syntax ERROR);
         * - "The following functions are not allowed inside of an equation: ∫, d/dx, Σ, Π, Pol,
         *   Rec, ÷R" (research 02 §10.15, VERIFIED; the ZA has no Π) — the guide does not say what
         *   the calculator does with one, so ⚠️ GUESS: Syntax ERROR, with Goto just after it;
         * - an equation without its variable — "No solution variable given and no X in the
         *   equation, or the given variable is not in the equation" is Variable ERROR (research 02
         *   §11, VERIFIED).
         */
        fun refusal(host: Host, node: Node, spans: SourceMap): Refusal? {
            if (node is Statements) return Refusal(CalcError.SYNTAX, null)
            NodeTree.find(node, ::forbidden)?.let { return Refusal(CalcError.SYNTAX, spans.endOf(it)) }
            val equation = node as? Equation ?: Equation(node, null, null)
            val target = equation.solveFor ?: Variable.X
            return if (target in unknowns(host, equation)) null else Refusal(CalcError.VARIABLE, null)
        }

        private fun forbidden(node: Node): Boolean = when (node) {
            is Integral, is Derivative, is Summation -> true
            is Call -> node.fn == Fn.POL || node.fn == Fn.REC
            is Binary -> node.op == BinOp.DIV_R
            else -> false
        }

        /**
         * Every variable of [equation], left side first, in order of appearance — "the calculator
         * prompts for every other variable in order of appearance" (research 02 §10.15). Each side
         * is asked of the evaluator on its own: `variablesOf` answers what *CALC* would prompt for,
         * which for an assignment `Y = …` need not include the `Y` that SOLVE may be solving for.
         */
        private fun unknowns(host: Host, equation: Equation): List<Variable> =
            (listOf(equation.lhs) + listOfNotNull(equation.rhs)).flatMap { host.evaluator.variablesOf(it) }.distinct()
    }
}
