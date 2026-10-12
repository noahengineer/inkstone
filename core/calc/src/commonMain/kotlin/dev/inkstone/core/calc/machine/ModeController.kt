package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.Setup

/**
 * One calculation mode's behaviour — the extension point STAT, EQN, MATRIX, TABLE, VECTOR, DIST
 * and INEQ implement. COMP, CMPLX and BASE-N are [CalculationMode]; **RATIO is the worked example**
 * (`dev.inkstone.core.calc.mode.RatioMode`): copy its shape.
 *
 * ## 1. Plugging in
 *
 * Write `class XxxMode : ModeController` in `dev.inkstone.core.calc.mode` and add it to
 * `StandardModes.registry()` there; a mode with no controller shows [PlaceholderMode]. One instance
 * lives as long as the calculator, so the mode's data (STAT's table, EQN's coefficients, TABLE's
 * functions) lives in its fields — never in a screen, which is thrown away whenever the mode is
 * shown afresh.
 *
 * ## 2. What the machine does for every mode
 *
 * Power, the S/A latch, the keymap, SETUP, CLR, the MODE menu, Ans/PreAns, the nine variables,
 * matrices and vectors ([dev.inkstone.core.calc.eval.Memory], persisted by the machine), history,
 * the status icons, and the global keys (`ON`, `SHIFT AC`, `MODE`, `SHIFT MODE`, `SHIFT 9`), which
 * a mode never sees. Choosing a mode calls the old one's [leave], clears history and calls the new
 * one's [enter]; `ON`, switching on (a restore) and the `AC` that ends a CLR call [home].
 *
 * ## 3. Screens
 *
 * A mode is a stack of [Screen]s. `host.show(s)` makes `s` the whole stack — the mode's own screen;
 * `host.push` puts a menu, prompt, error or result over the top, and that screen takes itself off
 * with `host.pop()` (or swaps itself with `host.replace`). Every press the machine does not take
 * reaches the top screen as a [Press], already resolved by [Keymap] to an [Action]; a screen draws
 * a **whole** frame with `dev.inkstone.core.calc.lcd.Screens`. Reuse, never copy:
 *
 * - **Menus** — [MenuScreen] / [MenuPage] for every numbered menu (a type menu is
 *   `MenuScreen(host, pages, columns = 1, onCancel = {})`, research 03 §6, §11, §12).
 * - **Editors** — a [CellGrid] owned by the controller, shown by a [CellEditor] with an
 *   [EditorLayout] (headers, row numbers, brackets, the template underneath) and [EditorHooks]
 *   (`solve` for a bare `=`, `clear` for `AC`, `modeMenu`). The editor already does research 03
 *   §2.4: moving, typing a value (LineIO, evaluated by [evaluateLine]), `=` row by row, the refused
 *   keys, hyp/CONST/CONV into a cell, cell text by `Formatter.cellText`, the full value bottom right
 *   by `Formatter.valueExpr`, and its own errors.
 * - **Answers** — compute with the evaluator, never by hand: build the formula over the values the
 *   mode holds with [Literals] (`Binary(DIV, Binary(MUL, Literals.of(a), Literals.of(d)), …)`),
 *   evaluate it with `host.evaluator.evaluate(node, SourceMap(), host.context(press.interrupt))`,
 *   and show a `Done` answer with [LabelledResult] (`X=`, `X₁=`, `p=`) or an error with
 *   [ErrorScreen]. Call `host.recordAnswer(value)` only for an answer that becomes Ans (DIST's Var
 *   result does, research 03 §10.2). `Interrupted` means `AC` broke a long one (TABLE's rows, a ∫
 *   typed into a cell): leave the screen exactly as it was — the machine spends that `AC` on the
 *   break, so it will not also reach your screen and clear it.
 * - **Prompts** — [DigitPrompt], [ConfirmScreen], [NoticeScreen], [NumberPrompt]; one value typed
 *   after a label is `Screens.labelValue` with a [LineEditor]-driven `InputState` and
 *   [evaluateLine], as `EquationRun`'s prompts do.
 * - **The calculation line** — STAT and DIST after `AC`, MATRIX and VECTOR: show a
 *   `Calculation(host, hooks)` and adapt it with [CalculationHooks]: `statistics` (STAT's data for
 *   the evaluator), `takeAnswer` (show a matrix or vector answer on your own MatAns/VctAns screen;
 *   `+ − × ÷ …` there go `host.pop()` then `line.startWith(MatAns, press)`, and
 *   [dev.inkstone.core.calc.input.AnsContinuation] says which keys), `modeMenu`.
 *
 * ## 4. Keys
 *
 * [Keymap] already routes the bracketed mode menus to [Command.MODE_MENU] in their own modes:
 * `SHIFT 1` (STAT, DIST), `SHIFT 4` (MATRIX), `SHIFT 5` (VECTOR); a screen sends that on to its
 * hook ([CalculationHooks.modeMenu], [EditorHooks.modeMenu]), which pushes a menu —
 * [LineMenus.typing] when its items type into the line or a cell ([TypingTarget]). [keys] overrides
 * any binding for the whole mode, before the keymap's own tables; map a key to [Action.None] to
 * refuse it everywhere in the mode. Refusals that belong to one screen (an editor's M+) are that
 * screen's, not the mode's.
 *
 * ## 5. Status icons and display
 *
 * Every screen takes its icons from `host.indicators(extra)`: the machine lights S/A, M, the mode's
 * own icon (STAT, CMPLX, MAT, VCT — research 02 §6), the angle unit, FIX/SCI and Math; `extra` adds
 * the screen's own (`▼`/`▲` for more solutions or rows, `Disp`). Math, and Natural input, follow
 * [linearOnly]; read `host.naturalInput`, never SETUP directly.
 *
 * ## 6. Persistence and resets
 *
 * [snapshot] returns what the real calculator keeps while switched off (docs/CALCULATOR.md D4) as
 * text the controller reads back in [restore] — newlines and any characters are fine, the machine
 * escapes them; [CellGrid.snapshot] covers a table. [restore] runs **before** [home], so `home`
 * shows what it read; when it answers false the whole calculator is discarded and a fresh one
 * built ([MachineSnapshot] is all or nothing), so a half-read controller never shows. Text it does
 * not understand must be refused, not guessed at. [clear] is CLR on every controller, whatever the
 * mode: [ClearScope.ALL] drops the mode's data; what [ClearScope.MEMORY] drops is the mode's call
 * (RATIO keeps its coefficients — ⚠️ GUESS). [setupChanged] follows the screens' own
 * [Screen.setupChanged] (TABLE's functions go on a Natural/Linear change, research 03 §2.3).
 */
interface ModeController {
    val mode: Mode

    /**
     * Always Linear, whatever SETUP says. Research 02 §4 (VERIFIED): "Entering STAT, BASE-N,
     * MATRIX or VECTOR switches the display to Linear Display automatically"; DIST is added because
     * no DIST screen in the guide shows Math (research 03 §2.2, INFERRED). Here that is a property of
     * the mode and SETUP is left alone, so `Math` comes back on return to COMP — ⚠️ GUESS: whether
     * the firmware rewrites the SETUP byte instead is UNKNOWN.
     */
    val linearOnly: Boolean get() = mode in LINEAR_ONLY

    /**
     * Bindings this mode changes, checked before [Keymap]'s own tables — for example a key the
     * mode refuses (map it to [Action.None]) or gives a meaning of its own.
     */
    val keys: Map<Binding, Action> get() = emptyMap()

    /** The mode was chosen in the MODE menu: put its first screen up with `host.show`. */
    fun enter(host: Host)

    /**
     * Back to this mode's resting screen after `ON`, power-on, a CLR notice — and after [restore],
     * which is how a saved calculator switches on. By default, [enter]. ⚠️ So a mode whose [enter]
     * drops its data (choosing RATIO's or STAT's type zeroes the editor) must override this: `home`
     * must keep what [restore] just read and what `ON` is documented to keep (research 02 §9).
     */
    fun home(host: Host) = enter(host)

    /** Another mode is being chosen, or CLR Setup/All is returning to COMP: drop what leaving drops. */
    fun leave(host: Host) = Unit

    /** SETUP changed ([previous] is the old one); the machine has already told the screens. */
    fun setupChanged(previous: Setup, host: Host) = Unit

    /** CLR (research 02 §5.3): drop whatever [scope] covers among this mode's own data. */
    fun clear(scope: ClearScope) = Unit

    /** What survives power-off, as lossless text the controller itself can read back; null for nothing. */
    fun snapshot(): String? = null

    /** Reads [snapshot]'s text; false when it is unreadable, and the whole calculator then starts fresh. */
    fun restore(text: String): Boolean = true

    companion object {
        val LINEAR_ONLY: Set<Mode> = setOf(Mode.STAT, Mode.BASE_N, Mode.MATRIX, Mode.VECTOR, Mode.DIST)
    }
}

/**
 * One controller per mode. Built-in for COMP, CMPLX and BASE-N; a [PlaceholderMode] for any mode
 * nothing was registered for. Later entries win, so [standard]'s `extra` replaces a built-in.
 */
class ModeRegistry(controllers: List<ModeController>) {
    private val byMode: Map<Mode, ModeController> =
        Mode.entries.associateWith { mode -> controllers.lastOrNull { it.mode == mode } ?: PlaceholderMode(mode) }

    operator fun get(mode: Mode): ModeController = byMode.getValue(mode)

    val all: Collection<ModeController> get() = byMode.values

    companion object {
        /**
         * A fresh set — controllers hold state, so every calculator needs its own. The calculator's
         * own list of [extra] modes is `dev.inkstone.core.calc.mode.StandardModes`.
         */
        fun standard(extra: List<ModeController> = emptyList()): ModeRegistry {
            val builtIn = listOf(Mode.COMP, Mode.CMPLX, Mode.BASE_N).map { CalculationMode(it) }
            return ModeRegistry(builtIn + extra)
        }
    }
}
