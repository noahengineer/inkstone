package dev.inkstone.core.calc.mode

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.eval.EvalOutcome
import dev.inkstone.core.calc.machine.CellEditor
import dev.inkstone.core.calc.machine.CellGrid
import dev.inkstone.core.calc.machine.ClearScope
import dev.inkstone.core.calc.machine.EditorHooks
import dev.inkstone.core.calc.machine.EditorLayout
import dev.inkstone.core.calc.machine.ErrorScreen
import dev.inkstone.core.calc.machine.Host
import dev.inkstone.core.calc.machine.LabelledResult
import dev.inkstone.core.calc.machine.Literals
import dev.inkstone.core.calc.machine.MenuPage
import dev.inkstone.core.calc.machine.MenuScreen
import dev.inkstone.core.calc.machine.ModeController
import dev.inkstone.core.calc.machine.Press
import dev.inkstone.core.calc.parse.BinOp
import dev.inkstone.core.calc.parse.Binary
import dev.inkstone.core.calc.parse.Node
import dev.inkstone.core.calc.parse.SourceMap

/**
 * RATIO's two forms, in menu order (research 03 §12, VERIFIED screen `RAITO_01`): which three of
 * a, b, c, d the editor asks for, the template it shows under them, and X's formula — "the obvious
 * ones; they reproduce all three of [the guide's] results" (INFERRED).
 */
enum class RatioForm(val label: String, val headers: List<String>) {
    /** `1:a:b=X:d`: X = a·d/b. */
    A_B_X_D("a:b=X:d", listOf("a", "b", "d")),

    /** `2:a:b=c:X`: X = b·c/a. */
    A_B_C_X("a:b=c:X", listOf("a", "b", "c")),
    ;

    /** X over the three cells in the editor's order, as nodes for the evaluator. */
    internal fun x(first: Node, second: Node, third: Node): Node = when (this) {
        A_B_X_D -> Binary(BinOp.DIV, Binary(BinOp.MUL, first, third), second)
        A_B_C_X -> Binary(BinOp.DIV, Binary(BinOp.MUL, second, third), first)
    }
}

/**
 * RATIO (`MODE ▼ 3`, research 03 §12, examples 05 EX-RATIO-01…04 and EX-SA-RATIO-01) — and the
 * **reference [ModeController]**: the smallest mode that has every part a stage-3 mode needs (a
 * type menu, data that outlives its screens, an editor, an answer computed by the evaluator, an
 * error of its own, persistence). Read it beside the guide on [ModeController].
 *
 * - **Entry.** Every entry shows the form menu, one item per line; choosing a form zeroes the
 *   coefficients ("Re-entering RATIO and choosing a type also zeroes them", VERIFIED) and shows the
 *   Coefficient Editor. `AC` on the menu does nothing — there is nothing under it to go back to
 *   (⚠️ GUESS).
 * - **Home.** `ON`, switching on and the end of a CLR return to the editor with the coefficients as
 *   they were, once a form has been chosen (⚠️ GUESS: research 02 §9 says data survives power-off,
 *   not which screen comes back); before that, to the menu.
 * - **Editor.** [CellEditor] over the controller's own [CellGrid]: `a b d` (or `a b c`) in one
 *   bracketed row with no row number, the template underneath, the current cell bottom right
 *   (VERIFIED screens `RAITO_02`, `04`, `06`). Everything research 03 §2.4 says about editors is the
 *   editor's; this mode adds only what `=` with nothing typed does ([EditorHooks.solve]) and keeps the
 *   default `AC` ("Pressing [AC] will clear all of the coefficients to zero", VERIFIED). "Input up to
 *   10 digits for each" is not enforced: a coefficient holds whatever its expression evaluates to
 *   (⚠️ GUESS — the guide does not say what an eleventh digit does).
 * - **Solving.** "A Math ERROR will occur if you perform a calculation while 0 is input for a
 *   coefficient" (VERIFIED), shown over the editor; `AC` (or `◀`/`▶` — ⚠️ GUESS, there is no place to
 *   go to) returns to it. Otherwise X is computed **by the evaluator**: the formula over the
 *   coefficients as exact [Literals], so `1,5 : 12,5 = 5 : X` is `125/3` (VERIFIED capture), not the
 *   decimal a decimal reading would give. Its errors (a result out of range) show the same way.
 * - **Answer.** `X=` top left and the value bottom right ([LabelledResult], VERIFIED `RAITO_03`);
 *   S⇔D toggles it (`125/3` ⇔ `41,66666667`, VERIFIED capture); "Pressing [=] again will return to
 *   the Coefficient Editor" (VERIFIED), and so does `AC` (⚠️ GUESS, as EQN's solutions do). X does
 *   not become Ans (⚠️ GUESS: the guide says DIST's result is stored in Ans and says nothing of the
 *   kind for RATIO or EQN).
 * - **Persistence.** The form and the three coefficients survive power-off (docs/CALCULATOR.md D4);
 *   CLR All forgets both, CLR Memory neither (⚠️ GUESS: "Memory" is research 02 §5.3's variables
 *   and answers, and nothing says the editors are among them).
 * - **Display.** No mode icon, and Math when SETUP is MthIO (research 03 §2.2, VERIFIED) — both the
 *   machine's defaults, so nothing here.
 */
class RatioMode : ModeController {
    override val mode: Mode = Mode.RATIO

    /** The form chosen, or null until one is (a fresh calculator, or after CLR All). */
    private var form: RatioForm? = null

    /** a, b and d — or a, b and c — in the editor's order. */
    private val coefficients = CellGrid(rows = 1, columns = COEFFICIENTS)

    override fun enter(host: Host) = host.show(formMenu(host))

    override fun home(host: Host) {
        val chosen = form
        if (chosen == null) enter(host) else host.show(editor(host, chosen))
    }

    override fun clear(scope: ClearScope) {
        if (scope != ClearScope.ALL) return
        form = null
        coefficients.zero()
    }

    /** `A_B_X_D F3/1 F8/1 F12/1`: the form, then the coefficients' value words. */
    override fun snapshot(): String? = form?.let { it.name + " " + coefficients.snapshot() }

    override fun restore(text: String): Boolean {
        val saved = RatioForm.entries.firstOrNull { it.name == text.substringBefore(' ') } ?: return false
        if (!coefficients.restore(text.substringAfter(' ', ""))) return false
        form = saved
        return true
    }

    private fun formMenu(host: Host): MenuScreen = MenuScreen(host, FORMS, columns = 1, onCancel = {}) { _, number ->
        val chosen = RatioForm.entries[number - 1]
        form = chosen
        coefficients.zero()
        host.show(editor(host, chosen))
    }

    private fun editor(host: Host, form: RatioForm): CellEditor =
        CellEditor(host, coefficients, EditorLayout(form.headers, footer = form.label), Solver(host, form))

    /** What `=` with nothing typed does in RATIO's editor: solve for X. */
    private inner class Solver(private val host: Host, private val form: RatioForm) : EditorHooks {
        override fun solve(editor: CellEditor, press: Press) {
            val values = coefficients.values
            if (values.any { it.dec.isZero }) {
                host.push(ErrorScreen(host, CalcError.MATH, onAc = host::pop))
                return
            }
            val (first, second, third) = values.map(Literals::of)
            val x = form.x(first, second, third)
            when (val outcome = host.evaluator.evaluate(x, SourceMap(), host.context(press.interrupt))) {
                is EvalOutcome.Done -> host.push(LabelledResult(host, X_LABEL, outcome.answer, host::pop, host::pop))
                is EvalOutcome.Failed -> host.push(ErrorScreen(host, outcome.error, onAc = host::pop))
                EvalOutcome.Interrupted -> Unit
            }
        }
    }

    private companion object {
        const val COEFFICIENTS = 3
        const val X_LABEL = "X="

        val FORMS: List<MenuPage> = listOf(MenuPage.numbered(RatioForm.entries.map { it.label }))
    }
}
