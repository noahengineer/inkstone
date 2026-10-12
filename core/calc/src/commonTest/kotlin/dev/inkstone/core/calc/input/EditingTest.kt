package dev.inkstone.core.calc.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Keystroke editing (research 02 §7.4–7.6). Each row is keys and the line they must leave. */
class EditingTest {
    private fun lineOf(keys: String, natural: Boolean = true) = linearText(Keys.type(keys, natural).expr)

    private val powers = listOf(
        // "2 x² x² ignores the last x²" (research 02 §7.4, VERIFIED), and the same for every pair.
        "2 x² x²" to "2²",
        "2 x² x^" to "2²",
        "2 x^ 3 ▶ x²" to "2^(3)",
        "2 x⁻¹ x³" to "2⁻¹",
        "2 x³ x⁻¹" to "2³",
        // …but ◀ first, and the second one goes in front of the first (research 02 §7.4).
        "2 x² ◀ x²" to "2²²",
        "2 ! x²" to "2!²",
        "( 2 x² ) x²" to "(2²)²",
    )

    @Test
    fun `power keys cannot follow one another directly`() {
        for ((keys, expected) in powers) assertEquals(expected, lineOf(keys), keys)
        assertEquals("2²", lineOf("2 x² x^", natural = false))
        assertEquals("2^(", lineOf("2 x^ x²", natural = false))
    }

    /**
     * The ZA guide's own words are a key sequence — "the following functions cannot be input in
     * consecutive sequence: x², x³, x^■, x⁻¹" (power_functions.html) — so `x^` then `x²` is refused
     * even though, in Natural display, the second key would land inside the first one's exponent
     * slot rather than after it. LineIO already refused the same keys (`2^(` then `²`, above).
     * Refusing at the start of a non-empty exponent too (the last row) is a ⚠️ GUESS that the
     * firmware tests the byte before the cursor, which there is still the x^ opener.
     */
    private val powerInsideExponent = listOf(
        "2 x^ x²" to "2^()",
        "2 x^ x³" to "2^()",
        "2 x^ x⁻¹" to "2^()",
        "2 x^ x^" to "2^()",
        "2 x^ 3 ◀ x²" to "2^(3)",
        // Control: after a digit inside the exponent the key is an ordinary postfix again.
        "2 x^ 3 x²" to "2^(3²)",
    )

    @Test
    fun `a power key straight after the x-power key is ignored even inside its exponent slot`() {
        for ((keys, expected) in powerInsideExponent) assertEquals(expected, lineOf(keys), keys)
        val opened = Keys.type("2 x^")
        assertSame(opened, opened.insert(Tok.SQUARE))
        assertSame(opened, opened.insertTemplate(TemplateKind.POWER))
    }

    private val fractions = listOf(
        "1 + 7 FRAC 6" to "1+7⌟6",
        "1 2 , 5 FRAC 3" to "12,5⌟3",
        "2 ×10 (−) 3 FRAC 4" to "(2×₁₀-3)⌟4",
        "π FRAC 2" to "π2⌟",
        "( 1 ) FRAC 2" to "(1)2⌟",
        "(−) 3 FRAC 4" to "-3⌟4",
    )

    @Test
    fun `FRAC after a number takes the number as its numerator`() {
        for ((keys, expected) in fractions) assertEquals(expected, lineOf(keys), keys)
        assertEquals(Cursor(listOf(PathStep(2, 1)), 0), Keys.type("1 + 7 FRAC").cursor)
        assertEquals(Cursor(listOf(PathStep(1, 0)), 0), Keys.type("π FRAC").cursor)
    }

    private val deletes = listOf(
        "1 2 3 DEL" to "12",
        "1 2 3 ◀ DEL" to "13",
        "√ 2 ▶ DEL" to "√(2)",
        "√ 2 ▶ DEL DEL" to "√()",
        "√ 2 ▶ DEL DEL DEL" to "",
        "√ ▶ DEL" to "",
        "FRAC DEL" to "",
        "FRAC 1 ▼ 2 DEL DEL" to "1⌟",
        "FRAC 1 ▼ 2 DEL DEL DEL DEL" to "",
        "√ 7 ◀ DEL" to "7",
        "2 FRAC 3 ◀ ◀ DEL DEL" to "3",
        "1 + MIXED 3 ▶ 4 ▶ 5 ◀ ◀ ◀ ◀ ◀ DEL" to "1+345",
    )

    @Test
    fun `DEL deletes one typed item at a time and dissolves a template from its first slot`() {
        for ((keys, expected) in deletes) assertEquals(expected, lineOf(keys), keys)
        assertEquals(Cursor(listOf(PathStep(0, 0)), 1), Keys.type("√ 2 ▶ DEL").cursor)
    }

    @Test
    fun `a refused key returns the very same state`() {
        val empty = InputState.empty()
        assertSame(empty, empty.delete())
        val squared = Keys.type("2 x²")
        assertSame(squared, squared.insert(Tok.SQUARE))
        assertSame(squared, squared.insertTemplate(TemplateKind.SCI_EXPONENT))
        val line = InputState.empty(natural = false)
        assertSame(line, line.beginWrap())
        assertSame(empty, empty.toggleOverwrite())
    }

    private val overwrites = listOf(
        "1 2 3 START OVR 9" to "923",
        "1 2 3 START OVR 9 8 7 6" to "9876",
        "1 2 3 START OVR DEL" to "23",
        "1 2 3 OVR DEL" to "12",
        "1 2 3 START OVR OVR 9" to "9123",
        "1 2 3 START INS 9" to "923",
    )

    @Test
    fun `LineIO overwrite replaces and deletes under the cursor`() {
        for ((keys, expected) in overwrites) assertEquals(expected, lineOf(keys, natural = false), keys)
        assertEquals("9123", lineOf("1 2 3 START OVR 9"))
    }

    private val wraps = listOf(
        "1 + ( 2 + 3 ) × 4 START ▶ ▶ INS √" to "1+√((2+3))×4",
        "sin 3 0 ) + 1 START INS Abs" to "Abs(sin(30))+1",
        "2 + 3 4 5 START ▶ ▶ INS FRAC" to "2+345⌟",
        "π + 1 START INS ⁿ√" to "ˣ√(π)+1",
        "π + 1 START INS log□" to "log(;π)+1",
        "X START INS ∫" to "∫(X;;)",
        "+ 1 START INS √" to "√()+1",
        "1 START INS 2 √" to "2√()1",
        "CONST28 START INS x^" to "^(C0)",
    )

    @Test
    fun `INS wraps the value right of the cursor in the next template`() {
        for ((keys, expected) in wraps) assertEquals(expected, lineOf(keys), keys)
        assertEquals(Cursor(listOf(PathStep(2, 1)), 0), Keys.type("2 + 3 4 5 START ▶ ▶ INS FRAC").cursor)
        assertEquals(Cursor(listOf(PathStep(0, 0)), 0), Keys.type("π + 1 START INS ⁿ√").cursor)
        assertEquals(Cursor(listOf(PathStep(0, 0)), 1), Keys.type("7 START INS √").cursor)
    }

    /**
     * The ES family guide's own INS table (research 02 [S570] pp.E-16–17, INFERRED for the ZA):
     * with the cursor before the `(` of `1+(2+3)+4` (or `1+(X+3)+4`), INS then each key wraps the
     * whole bracketed group, brackets kept, into the slot the template applies to.
     */
    private val siblingWraps = listOf(
        "√" to "1+√((2+3))+4",
        "FRAC" to "1+((2+3))⌟+4",
        "log□" to "1+log(;(2+3))+4",
        "ⁿ√" to "1+ˣ√((2+3))+4",
        "∫" to "1+∫((X+3);;)+4",
        "d/dx" to "1+d/dx((X+3);)+4",
        "Σ" to "1+Σ((X+3);;)+4",
    )

    @Test
    fun `INS wraps a bracketed group as the ES family guide table shows`() {
        for ((key, expected) in siblingWraps) {
            val inner = if ("X" in expected) "X" else "2"
            val keys = "1 + ( $inner + 3 ) + 4 START ▶ ▶ INS $key"
            assertEquals(expected, lineOf(keys), keys)
        }
    }

    /** [S570] p.E-13's two LineIO corrections, made in insert mode and in overwrite mode. */
    private val siblingCorrections = listOf(
        "3 6 9 × × 1 2 ◀ ◀ DEL" to Pair("369×12", 4),
        "OVR 3 6 9 × × 1 2 ◀ ◀ ◀ DEL" to Pair("369×12", 4),
        "cos 6 0 ) ◀ ◀ ◀ DEL sin" to Pair("sin(60)", 1),
        "OVR cos 6 0 ) ◀ ◀ ◀ ◀ sin" to Pair("sin(60)", 1),
    )

    @Test
    fun `LineIO corrections match the ES family guide in both input modes`() {
        for ((keys, expected) in siblingCorrections) {
            val state = Keys.type(keys, natural = false)
            assertEquals(expected.first, linearText(state.expr), keys)
            assertEquals(Cursor(index = expected.second), state.cursor, keys)
        }
    }

    @Test
    fun `template keys type their LineIO spelling in LineIO`() {
        val keys = "FRAC MIXED √ ∛ ⁿ√ x^ log□ 10^ e^ Abs ∫ d/dx Σ"
        assertEquals("⌟⌟√(∛(ˣ√(^(log(10^(e^(Abs(∫(d/dx(Σ(", lineOf(keys, natural = false))
    }
}
