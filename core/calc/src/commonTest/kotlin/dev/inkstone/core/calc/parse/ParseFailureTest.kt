package dev.inkstone.core.calc.parse

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.input.Cursor
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.Keys
import dev.inkstone.core.calc.input.PathStep
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.input.TokenItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Malformed lines fail with Syntax ERROR (or Stack ERROR) and say where `[◀][▶]:Goto` puts the
 * cursor (research 02 §11): just before the item the parser could not take, or at the end of the
 * run it ran out of — inside a template's slot when that is where the problem is.
 */
class ParseFailureTest {
    private class Row(val keys: String, val at: Cursor, val natural: Boolean = true)

    private val syntax = listOf(
        Row("1 +", Cursor(index = 2)),
        Row("×", Cursor(index = 0)),
        Row("( 1 + 2 ) )", Cursor(index = 5)),
        Row("sin )", Cursor(index = 1)),
        Row("GCD 2 )", Cursor(index = 3)),
        Row("1 , 2 , 3", Cursor(index = 3)),
        Row("1 ×10", Cursor(index = 2)),
        Row("1 ×10 (−)", Cursor(index = 3)),
        Row(",", Cursor(index = 1)),
        Row("3 + 3 :", Cursor(index = 4)),
        Row("π 2", Cursor(index = 1)),
        Row("2 DMS DMS", Cursor(index = 2)),
        Row("Y EQ 1 EQ 2", Cursor(index = 3)),
        Row("( 1 EQ 2 )", Cursor(index = 2)),
        Row("1 ; 2", Cursor(index = 2)),
        Row("1 FRAC 2 FRAC 3 FRAC 4", Cursor(index = 5), natural = false),
        Row("2 (−) 3", Cursor(index = 1)),
        Row("h , 1", Cursor(index = 1), natural = false),
        Row("1 hA , 5", Cursor(index = 4), natural = false),
        Row("▶t", Cursor(index = 0), natural = false),
        Row("FRAC ▼ 3", Cursor(listOf(PathStep(0, 0)), 0)),
        Row("2 x^ ▶", Cursor(listOf(PathStep(1, 0)), 0)),
        Row("1 + √ 2 + ▶", Cursor(listOf(PathStep(2, 0)), 2)),
        Row("∫ X ▶ ▶ 1", Cursor(listOf(PathStep(0, 1)), 0)),
    )

    @Test
    fun `a malformed line is a Syntax ERROR with the cursor where it went wrong`() {
        for (row in syntax) {
            val failure = failure(row.keys, row.natural)
            assertEquals(CalcError.SYNTAX, failure.error, row.keys)
            assertEquals(row.at, failure.at, row.keys)
        }
    }

    @Test
    fun `constant and conversion numbers outside 1 to 40 are a Syntax ERROR`() {
        assertEquals(Cursor(index = 0), failure("CONST41").at)
        assertEquals(Cursor(index = 1), failure("5 CONV41").at)
        assertEquals(Cursor(index = 0), failure("CONST0").at)
    }

    @Test
    fun `an empty line is a Syntax ERROR at its start`() {
        val result = assertIs<ParseResult.Failure>(parse(Expr.EMPTY))
        assertEquals(CalcError.SYNTAX, result.error)
        assertEquals(Cursor(), result.at)
    }

    @Test
    fun `nesting past the command stack is a Stack ERROR`() {
        assertIs<ParseResult.Ok>(parse(nest(ParseContext.COMMAND_STACK)))
        val deep = assertIs<ParseResult.Failure>(parse(nest(ParseContext.COMMAND_STACK + 1)))
        assertEquals(CalcError.STACK, deep.error)
    }

    @Test
    fun `values on the numeric stack past ten are a Stack ERROR`() {
        // pending(n) holds n ones and reads one more: n + 1 values on the stack.
        assertIs<ParseResult.Ok>(parse(pending(ParseContext.NUMERIC_STACK - 1)))
        val deep = assertIs<ParseResult.Failure>(parse(pending(ParseContext.NUMERIC_STACK)))
        assertEquals(CalcError.STACK, deep.error)
    }

    /**
     * The one worked example of the stacks in the ES family, the fx-570ES guide's illustration
     * (research 02 [S570] p.E-66): `2×((3+4×(5+4)÷3)÷5)+8` peaks at **five** numeric levels
     * (2, 3, 4, 5, 4 — the value being read counts) and **seven** command levels (× ( ( + × ( +).
     * Prefixing it so that exactly one more level would overflow pins both peaks.
     */
    @Test
    fun `the guide illustration peaks at five numeric and seven command levels`() {
        val illustration = "2 × ( ( 3 + 4 × ( 5 + 4 ) ÷ 3 ) ÷ 5 ) + 8"
        val numericRoom = ParseContext.NUMERIC_STACK - ILLUSTRATION_VALUES
        assertIs<ParseResult.Ok>(lineParse("1 + ( ".repeat(numericRoom) + illustration))
        assertStack(lineParse("1 + ( ".repeat(numericRoom + 1) + illustration))
        val commandRoom = ParseContext.COMMAND_STACK - ILLUSTRATION_COMMANDS
        assertIs<ParseResult.Ok>(lineParse("( ".repeat(commandRoom) + illustration))
        assertStack(lineParse("( ".repeat(commandRoom + 1) + illustration))
    }

    /** A power's base, a fraction's numerator and earlier arguments are held while the rest is read. */
    @Test
    fun `values held by a power or a template count on the numeric stack`() {
        // k × "1+(" holds k values; then base/numerator, a held 1, and the 1 being read: k + 3.
        val room = ParseContext.NUMERIC_STACK - HELD_BY_TAIL
        for ((tail, natural) in listOf("2 ^( 1 + ( 1" to false, "2 x^ 1 + ( 1" to true, "FRAC 1 ▼ 1 + ( 1" to true)) {
            assertIs<ParseResult.Ok>(parse(Keys.type("1 + ( ".repeat(room) + tail, natural).expr), tail)
            assertStack(parse(Keys.type("1 + ( ".repeat(room + 1) + tail, natural).expr))
        }
    }

    /** [S570] p.E-67: "In the CMPLX Mode, each input value uses two levels of the numeric stack." */
    @Test
    fun `in CMPLX each value takes two numeric levels`() {
        val half = ParseContext.NUMERIC_STACK / 2
        assertIs<ParseResult.Ok>(parse(pending(half - 1), complex = true))
        assertStack(parse(pending(half), complex = true))
        assertIs<ParseResult.Ok>(parse(pending(half)))
    }

    private fun lineParse(keys: String): ParseResult = parse(Keys.type(keys, natural = false).expr)

    private fun assertStack(result: ParseResult) =
        assertEquals(CalcError.STACK, assertIs<ParseResult.Failure>(result).error)

    private companion object {
        const val ILLUSTRATION_VALUES = 5
        const val ILLUSTRATION_COMMANDS = 7
        const val HELD_BY_TAIL = 3
    }

    @Test
    fun `a function given the wrong number of arguments is a Syntax ERROR`() {
        for (keys in listOf("Pol 1 )", "GCD 1 ; 2 ; 3 )", "sin 1 ; 2 )", "log 1 ; 2 ; 3 )")) {
            assertEquals(CalcError.SYNTAX, failure(keys).error, keys)
        }
        for (keys in listOf("∫ 1 ; 2 )", "d/dx 1 )", "Σ 1 ; 2 ; 3 ; 4 )", "∫ 1 ; 2 ; 3 ; 4 ; 5 )")) {
            assertEquals(CalcError.SYNTAX, failure(keys, natural = false).error, keys)
        }
    }

    /** `((((…(1` with [depth] brackets: one command each. */
    private fun nest(depth: Int): Expr = Expr(List(depth) { TokenItem(Tok.OPEN) } + TokenItem(Tok.D1))

    /** `1+(1+(…(1`: each level holds a 1 while the bracket after it is read. */
    private fun pending(depth: Int): Expr = Expr(
        List(depth) { listOf(TokenItem(Tok.D1), TokenItem(Tok.PLUS), TokenItem(Tok.OPEN)) }.flatten() +
            TokenItem(Tok.D1),
    )
}
