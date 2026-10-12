package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.input.ConstantItem
import dev.inkstone.core.calc.input.ConversionItem
import dev.inkstone.core.calc.input.Item
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.input.TokenItem

/**
 * The menus that type into a line, and a builder for more of them. Layouts are the sibling
 * firmware's strings, two items per line (SECONDARY, research 00 §4), except where marked.
 *
 * Every one of them types into a [TypingTarget], so the same screens serve the calculation line,
 * the CALC/SOLVE prompts and the editor cells — `hyp 1` types `sinh(` wherever the cursor is.
 */
object LineMenus {
    /** `hyp` (research 02 §10.5): items 1 and 5 VERIFIED, the rest SECONDARY. */
    val HYP: List<MenuPage> = listOf(MenuPage.of("sinh", "cosh", "tanh", "sinh⁻¹", "cosh⁻¹", "tanh⁻¹"))

    /**
     * DRG▶ (research 02 §10.4): items 2 and 3 VERIFIED, item 1 SECONDARY. The firmware string is
     * `1:°     2:r` / `3:g`; ⚠️ GUESS that `r` and `g` are drawn as the raised ʳ and ᵍ they type.
     */
    val DRG: List<MenuPage> = listOf(MenuPage.of("°", "ʳ", "ᵍ"))

    /** CMPLX's `SHIFT 2` (research 03 §3): items VERIFIED from key sequences, layout SECONDARY. */
    val CMPLX: List<MenuPage> = listOf(MenuPage.of("arg", "Conjg", "▶r∠θ", "▶a+bi"))

    /** BASE-N's `SHIFT 3`, two pages (research 03 §5.3): items VERIFIED, layout SECONDARY. */
    val BASE: List<MenuPage> = listOf(
        MenuPage.of("and", "or", "xor", "xnor", "Not", "Neg"),
        MenuPage.of("d", "h", "b", "o"),
    )

    private val HYP_TOKENS = listOf(listOf(Tok.SINH, Tok.COSH, Tok.TANH, Tok.ASINH, Tok.ACOSH, Tok.ATANH))
    private val DRG_TOKENS = listOf(listOf(Tok.DEG_MARK, Tok.RAD_MARK, Tok.GRAD_MARK))
    private val CMPLX_TOKENS = listOf(listOf(Tok.ARG, Tok.CONJG, Tok.TO_POLAR, Tok.TO_RECT))
    private val BASE_TOKENS = listOf(
        listOf(Tok.AND, Tok.OR, Tok.XOR, Tok.XNOR, Tok.NOT, Tok.NEG),
        listOf(Tok.PREFIX_DEC, Tok.PREFIX_HEX, Tok.PREFIX_BIN, Tok.PREFIX_OCT),
    )

    /**
     * A menu in which item `n` of page `p` types `picks[p][n − 1]` into [target] and closes the
     * menu — what STAT's Sum/Var/Reg, MATRIX's MatA…Trn and VECTOR's VctA…Dot items all are.
     */
    fun typing(host: Host, target: TypingTarget, pages: List<MenuPage>, picks: List<List<Item>>): MenuScreen =
        MenuScreen(host, pages) { page, number ->
            val item = picks.getOrNull(page)?.getOrNull(number - 1)
            host.pop()
            if (item != null) target.insert(item)
        }

    /**
     * The `hyp`, `SHIFT Ans` (DRG▶), `SHIFT 7` (CONST) and `SHIFT 8` (CONV) keys, for any [target]:
     * pushes the screen and answers true, or answers false for any other [command] so the caller
     * can decide what that key does. The keymap has already made CONST and CONV legend-less where
     * they are refused (BASE-N, and CONV in TABLE), so they never arrive here there.
     */
    fun open(host: Host, command: Command?, target: TypingTarget): Boolean {
        val screen = when (command) {
            Command.HYP -> typing(host, target, HYP, items(HYP_TOKENS))
            Command.DRG -> typing(host, target, DRG, items(DRG_TOKENS))
            Command.CONST -> number(host, target, constant = true)
            Command.CONV -> number(host, target, constant = false)
            else -> return false
        }
        host.push(screen)
        return true
    }

    internal fun cmplx(host: Host, target: TypingTarget) = typing(host, target, CMPLX, items(CMPLX_TOKENS))

    internal fun base(host: Host, target: TypingTarget) = typing(host, target, BASE, items(BASE_TOKENS))

    /**
     * CONST or CONV: two digits, then the constant or conversion goes in at the cursor. A number
     * outside 01–40 is a Syntax ERROR (research 02 §10.16, SECONDARY for CONV; CONST is assumed
     * the same) — shown at once, with Goto returning to the line as it was.
     */
    private fun number(host: Host, target: TypingTarget, constant: Boolean): NumberPrompt {
        val title = if (constant) NumberPrompt.CONSTANT else NumberPrompt.CONVERSION
        return NumberPrompt(host, title, host::pop) { number ->
            host.pop()
            when {
                number !in 1..LAST_ENTRY -> target.fail(CalcError.SYNTAX, target.input.cursor)
                constant -> target.insert(ConstantItem(number))
                else -> target.insert(ConversionItem(number))
            }
        }
    }

    private fun items(tokens: List<List<Tok>>): List<List<Item>> = tokens.map { page -> page.map(::TokenItem) }

    /** CONST and CONV both number their entries 01–40 (research 03 §14–15). */
    private const val LAST_ENTRY = 40
}
