package dev.inkstone.core.calc.lcd

import dev.inkstone.core.calc.input.ConstantItem
import dev.inkstone.core.calc.input.ConversionItem
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.TemplateKind
import dev.inkstone.core.calc.input.Tok
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two linear spellings: what Linear display draws (research 02 §7.4's LineIO forms) and the
 * plain accessibility text [LcdFrame.text] promises (`1/200`, `1,25×10^-3`).
 */
class ExprTextTest {
    private class Case(val expr: Expr, val plain: String, val screen: String)

    private val cases = listOf(
        Case(Expr(listOf(frac("1", "200"))), "1/200", "1⌟200"),
        Case(Expr(listOf(template(TemplateKind.FRACTION, ex("1+2"), ex("3")))), "(1+2)/3", "1+2⌟3"),
        Case(Expr(listOf(template(TemplateKind.MIXED_FRACTION, ex("1"), ex("2"), ex("3")))), "1 2/3", "1⌟2⌟3"),
        Case(Expr(listOf(template(TemplateKind.SQRT, ex("2")))), "√(2)", "√(2)"),
        Case(Expr(listOf(template(TemplateKind.CBRT, ex("27")))), "∛(27)", "∛(27)"),
        Case(Expr(listOf(template(TemplateKind.NTH_ROOT, ex("5"), ex("32")))), "5ˣ√(32)", "5ˣ√(32)"),
        Case(Expr(items("2") + template(TemplateKind.POWER, ex("10"))), "2^10", "2^(10)"),
        Case(Expr(items("2") + template(TemplateKind.POWER, ex("1+1"))), "2^(1+1)", "2^(1+1)"),
        Case(Expr(listOf(template(TemplateKind.TEN_POWER, ex("3")))), "10^3", "10^(3)"),
        Case(Expr(listOf(template(TemplateKind.E_POWER, ex("2")))), "e^2", "e^(2)"),
        Case(Expr(listOf(template(TemplateKind.LOG_BASE, ex("2"), ex("8")))), "log(2;8)", "log(2;8)"),
        Case(Expr(listOf(template(TemplateKind.ABS, ex("-3")))), "Abs(-3)", "Abs(-3)"),
        Case(Expr(listOf(template(TemplateKind.INTEGRAL, ex("X"), ex("0"), ex("1")))), "∫(X;0;1)", "∫(X;0;1)"),
        Case(Expr(listOf(template(TemplateKind.DERIVATIVE, ex("X"), ex("2")))), "d/dx(X;2)", "d/dx(X;2)"),
        Case(Expr(listOf(template(TemplateKind.SUMMATION, ex("X"), ex("1"), ex("5")))), "Σ(X;1;5)", "Σ(X;1;5)"),
        Case(Expr(items("1,25") + template(TemplateKind.SCI_EXPONENT, ex("-3"))), "1,25×10^-3", "1,25×₁₀⁻³"),
        Case(ex("2", Tok.DMS, "20", Tok.DMS, "30", Tok.DMS), "2°20'30\"", "2°20'30\""),
        Case(ex("1", Tok.DMS, "+2", Tok.DMS), "1°+2°", "1°+2°"),
        Case(Expr(listOf(ConstantItem(28), ConversionItem(2))), "C0cm▶in", "C0cm▶in"),
        Case(ex(Tok.ASIN, "1)", Tok.RECIPROCAL), "sin⁻¹(1)⁻¹", "sin⁻¹(1)⁻¹"),
    )

    @Test
    fun plainTextSpellings() {
        for (case in cases) assertEquals(case.plain, ExprText.of(case.expr, ExprText.Style.PLAIN))
    }

    @Test
    fun linearDisplaySpellings() {
        for (case in cases) assertEquals(case.screen, ExprText.of(case.expr, ExprText.Style.SCREEN))
    }

    @Test
    fun theFrameTextIsThePlainSpelling() {
        assertEquals("1,25×10^-3", MathLayout.plainText(cases[15].expr))
    }

    @Test
    fun dmsMarksAreCountedWithinOneNumber() {
        assertEquals(listOf("°", "'", "\"", "\""), (1..4).map { ExprText.dmsMark(it) })
    }
}
