package dev.inkstone.core.handwriting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** docs/HANDWRITING-MATHS.md §2 — what the *Maths* switch reads typed text as. */
class MathNotationTest {

    private fun row(vararg items: MathNode) = MathRow(items.toList())

    private fun t(text: String) = MathText(text)

    private fun parse(token: String) = MathNotation.parse(token)

    @Test
    fun `a slash between two terms is a stacked fraction`() {
        assertEquals(row(MathFraction(row(t("a")), row(t("b")))), parse("a/b"))
        assertEquals(row(MathFraction(row(t("3")), row(t("8")))), parse("3/8"))
    }

    @Test
    fun `brackets round a whole numerator or denominator only group it`() {
        assertEquals(row(MathFraction(row(t("x+1")), row(t("2x")))), parse("(x+1)/(2x)"))
    }

    @Test
    fun `the numerator is the term before the slash back to a sign that joins terms`() {
        assertEquals(row(MathFraction(row(t("2x")), row(t("3")))), parse("2x/3"))
        assertEquals(row(t("x+"), MathFraction(row(t("1")), row(t("2")))), parse("x+1/2"))
    }

    @Test
    fun `a whole number before a numeric fraction is a mixed number with no word gap`() {
        assertEquals(listOf("y", "=", "2 3/4"), MathNotation.words("y = 2 3/4"))
        assertEquals(row(t("2"), MathFraction(row(t("3")), row(t("4")))), parse("2 3/4"))
    }

    @Test
    fun `a space inside brackets does not end the word`() {
        assertEquals(
            listOf("x", "=", "(−b ± √(b^2−4ac))/(2a)"),
            MathNotation.words("x = (−b ± √(b^2−4ac))/(2a)"),
        )
        assertEquals(listOf("|x − 1|", "+", "2"), MathNotation.words("|x − 1| + 2"))
    }

    @Test
    fun `square roots and cube roots and nth roots`() {
        val three = row(t("3"))
        assertEquals(row(MathRoot(null, row(t("x+1")))), parse("√(x+1)"))
        assertEquals(row(MathRoot(null, row(t("9")))), parse("sqrt(9)"))
        assertEquals(row(MathRoot(three, row(t("8")))), parse("∛8"))
        assertEquals(row(MathRoot(three, row(t("x")))), parse("cbrt(x)"))
        assertEquals(row(MathRoot(row(t("4")), row(t("x")))), parse("∜x"))
        assertEquals(row(MathRoot(row(t("5")), row(t("x+y")))), parse("root(5, x+y)"))
    }

    @Test
    fun `scripts take a number or one letter and anything longer in brackets`() {
        assertEquals(row(t("x"), MathScript(row(t("2")), null)), parse("x^2"))
        assertEquals(row(t("x"), MathScript(row(t("2")), null), t("y")), parse("x^2y"))
        assertEquals(row(t("x"), MathScript(row(t("n+1")), null)), parse("x^(n+1)"))
        assertEquals(row(t("e"), MathScript(row(t("-x")), null)), parse("e^-x"))
        assertEquals(row(t("x"), MathScript(row(t("2")), row(t("1")))), parse("x_1^2"))
        assertEquals(row(t("CO"), MathScript(null, row(t("2")))), parse("CO_2"))
    }

    @Test
    fun `bars and abs are absolute values and are always drawn`() {
        assertEquals(row(MathFence('|', '|', row(t("x-1")))), parse("|x-1|"))
        assertEquals(row(MathFence('|', '|', row(t("x")))), parse("abs(x)"))
    }

    @Test
    fun `brackets round one line are written as letters and only taller ones are drawn`() {
        // `(x+1)` is a word in the user's own hand, with a 2 raised after it.
        assertEquals(row(t("(x+1)"), MathScript(row(t("2")), null)), parse("(x+1)^2"))
        val tall = parse("((a+b)/2)^2")
        assertEquals(
            row(MathFence('(', ')', row(MathFraction(row(t("a+b")), row(t("2"))))), MathScript(row(t("2")), null)),
            tall,
        )
    }

    @Test
    fun `a token with nothing two-dimensional in it is an ordinary word`() {
        assertNull(parse("hello"))
        assertNull(parse("(x+1)"))
        assertNull(parse("x÷2"))
        // Half a construction is text, not a guess.
        assertNull(parse("/2"))
        assertNull(parse("x^"))
        assertNull(parse("√"))
    }

    @Test
    fun `the runs are read in writing order`() {
        val quadratic = parse("(−b ± √(b^2−4ac))/(2a)") ?: error("not maths")
        assertEquals(listOf("−b", "±", "b", "2", "−4ac", "2a"), MathNotation.texts(quadratic))
    }
}
