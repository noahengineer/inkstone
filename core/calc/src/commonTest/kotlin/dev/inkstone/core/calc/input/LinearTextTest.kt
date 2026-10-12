package dev.inkstone.core.calc.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinearTextTest {
    private fun tokens(vararg toks: Tok) = Expr(toks.map { TokenItem(it) })

    @Test
    fun `degree marks are drawn by their place in the run of digits`() {
        val dms = tokens(Tok.D2, Tok.DMS, Tok.D3, Tok.DMS, Tok.D4, Tok.DMS, Tok.PLUS, Tok.D1, Tok.DMS)
        assertEquals("2°3'4\"+1°", linearText(dms))
        assertEquals("90°", linearText(tokens(Tok.D9, Tok.D0, Tok.DEG_MARK)))
    }

    @Test
    fun `a slot of one number or one value needs no brackets`() {
        val simple = Template(TemplateKind.FRACTION, listOf(tokens(Tok.D1, Tok.DECIMAL, Tok.D5), tokens(Tok.VAR_X)))
        assertEquals("1,5⌟X", linearText(Expr(listOf(simple))))
        val compound = Template(TemplateKind.FRACTION, listOf(tokens(Tok.D1, Tok.PLUS, Tok.D2), tokens(Tok.ANS)))
        assertEquals("(1+2)⌟Ans", linearText(Expr(listOf(compound))))
        val constant = Template(TemplateKind.NTH_ROOT, listOf(Expr(listOf(ConstantItem(35))), tokens(Tok.D8)))
        assertEquals("gˣ√(8)", linearText(Expr(listOf(constant))))
    }

    @Test
    fun `results and unknown numbers have a spelling too`() {
        val exponent = Template(TemplateKind.SCI_EXPONENT, listOf(tokens(Tok.NEGATIVE, Tok.D3)))
        assertEquals("1,25×10^-3", linearText(Expr(tokens(Tok.D1, Tok.DECIMAL, Tok.D2, Tok.D5).items + exponent)))
        assertEquals("□□", linearText(Expr(listOf(ConstantItem(99), ConversionItem(0)))))
        assertEquals("", linearText(Expr.EMPTY))
    }

    @Test
    fun `Ans is typed first only for keys that need a value on their left`() {
        assertTrue(AnsContinuation.continues(Tok.DIVIDE))
        assertTrue(AnsContinuation.continues(Tok.SQUARE))
        assertTrue(AnsContinuation.continues(TemplateKind.POWER))
        assertTrue(AnsContinuation.continues(ConversionItem(2)))
        assertFalse(AnsContinuation.continues(Tok.D1))
        assertFalse(AnsContinuation.continues(Tok.NEGATIVE))
        assertFalse(AnsContinuation.continues(TemplateKind.FRACTION))
        assertFalse(AnsContinuation.continues(ConstantItem(28)))
        // A template key types its LineIO spelling in LineIO, so whether it continues from Ans
        // depends on the display: SHIFT x^ is the ⁿ√ template (no left operand) in Natural
        // display and the infix ˣ√( in LineIO, which needs one; ⌟ stays excluded in both.
        assertFalse(AnsContinuation.continues(TemplateKind.NTH_ROOT, natural = true))
        assertTrue(AnsContinuation.continues(TemplateKind.NTH_ROOT, natural = false))
        assertTrue(AnsContinuation.continues(TemplateKind.POWER, natural = false))
        assertTrue(AnsContinuation.continues(TemplateKind.POWER, natural = true))
        assertFalse(AnsContinuation.continues(TemplateKind.FRACTION, natural = false))
        assertFalse(AnsContinuation.continues(TemplateKind.SQRT, natural = false))
        // D-01: 3×4 = then ÷30 shows Ans÷30.
        val line = InputState.empty(natural = false).insert(Tok.ANS).insert(Tok.DIVIDE)
        assertEquals("Ans÷30", linearText(Keys.type("3 0", start = line).expr))
    }

    /**
     * Research 03 §7 and §9.3 (VERIFIED): on the MatAns screen only `+ − × ÷ x⁻¹ x² x³` jump to
     * the line with `MatAns` in front, and on the VctAns screen only `+ − × ÷` with `VctAns`
     * (research 05 EX-VEC-03 types `−` and gets `VctAns−VctB`).
     */
    @Test
    fun `a matrix or vector result continues with its own Ans on fewer keys`() {
        val matrixKeys = listOf(Tok.PLUS, Tok.MINUS, Tok.TIMES, Tok.DIVIDE, Tok.RECIPROCAL, Tok.SQUARE, Tok.CUBE)
        for (tok in matrixKeys) assertTrue(AnsContinuation.continues(tok, AnsContinuation.Result.MATRIX), tok.name)
        for (tok in matrixKeys.take(4)) assertTrue(AnsContinuation.continues(tok, AnsContinuation.Result.VECTOR))
        for (tok in listOf(Tok.FACTORIAL, Tok.DOT, Tok.PERCENT, Tok.NPR)) {
            assertFalse(AnsContinuation.continues(tok, AnsContinuation.Result.MATRIX), tok.name)
        }
        for (tok in listOf(Tok.RECIPROCAL, Tok.SQUARE, Tok.CUBE, Tok.DOT)) {
            assertFalse(AnsContinuation.continues(tok, AnsContinuation.Result.VECTOR), tok.name)
        }
        assertTrue(AnsContinuation.continues(Tok.FACTORIAL, AnsContinuation.Result.VALUE))
        assertEquals(Tok.ANS, AnsContinuation.Result.VALUE.ans)
        assertEquals(Tok.MAT_ANS, AnsContinuation.Result.MATRIX.ans)
        assertEquals(Tok.VCT_ANS, AnsContinuation.Result.VECTOR.ans)
    }
}
