package dev.inkstone.core.calc.lcd

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.input.MetricConversions
import dev.inkstone.core.calc.input.ScientificConstants
import dev.inkstone.core.calc.input.Tok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every character the engine can put on screen has a real glyph — never the missing-glyph box —
 * in every font it is drawn in. Token text, conversions and constant symbols are drawn in the large
 * font on the main line and the small font when nested in a template; a constant's subscript one
 * size down (small under large, tiny under small).
 */
class FontCoverageTest {
    private fun missing(font: LcdFont, texts: Iterable<String>): Set<String> =
        texts.flatMap { font.keys(it) }.filterNot { font.has(it) }.toSet()

    private fun assertCovered(font: LcdFont, texts: Iterable<String>, what: String) {
        assertEquals(emptySet(), missing(font, texts), "$what without a glyph")
    }

    @Test
    fun everyTokenTextIsCoveredInTheLargeAndSmallFonts() {
        val texts = Tok.entries.map { it.text } + listOf("°", "'", "\"")
        assertCovered(LcdFont.LARGE, texts, "large token characters")
        assertCovered(LcdFont.SMALL, texts, "small token characters")
    }

    @Test
    fun constantSymbolsAreCoveredInTheFontsTheyAreDrawnIn() {
        val bases = ScientificConstants.all.map { it.symbol.base }
        val subscripts = ScientificConstants.all.map { it.symbol.subscript }
        assertCovered(LcdFont.LARGE, bases, "large constant bases")
        assertCovered(LcdFont.SMALL, bases, "small constant bases")
        assertCovered(LcdFont.SMALL, subscripts, "subscripts under large bases")
        assertCovered(LcdFont.TINY, subscripts, "subscripts under small bases")
    }

    @Test
    fun conversionTextsAreCoveredInTheLargeAndSmallFonts() {
        val texts = MetricConversions.all.map { it.text }
        assertCovered(LcdFont.LARGE, texts, "large conversion characters")
        assertCovered(LcdFont.SMALL, texts, "small conversion characters")
    }

    @Test
    fun printableAsciiIsCoveredInTheLargeAndSmallFonts() {
        val ascii = (0x20..0x7E).map { it.toChar().toString() }
        assertCovered(LcdFont.LARGE, ascii, "large ASCII")
        assertCovered(LcdFont.SMALL, ascii, "small ASCII")
    }

    @Test
    fun theDisplaySymbolsAreCoveredInTheLargeAndSmallFonts() {
        val symbols = listOf(
            "−", "×", "÷", "±", "√", "∛", "π", "θ", "∠", "▶", "▷", "◀", "▲", "▼", "°", "ʳ", "ᵍ", "'", "\"",
            "⌟", "Σ", "∫", "σ", "μ", "α", "γ", "λ", "ε", "φ", "ℏ", "∞", "ℓ", "•", "·", "⁻", "¹", "²", "³",
            "⁴", "₀", "₁", "₂", "₃", "ₙ", "x̄", "ȳ", "x̂", "ŷ", "≤", "≥", "≠", "×₁₀", ",", "-",
        )
        assertCovered(LcdFont.LARGE, symbols, "large symbols")
        assertCovered(LcdFont.SMALL, symbols, "small symbols")
    }

    @Test
    fun screenStringsAreCoveredInTheirFonts() {
        val small = CalcError.entries.map { it.message } + listOf(
            "[AC]  :Cancel",
            "[◀][▶]:Goto",
            "Fix 0~9?",
            "Number 01~40?",
            "1:aₙX+bₙY=cₙ",
            "3:▶r∠θ  4:▶a+bi",
        )
        assertCovered(LcdFont.SMALL, small, "small screen strings")
        assertCovered(LcdFont.MENU, Mode.entries.map { it.menuLabel }, "MODE menu labels")
        assertCovered(LcdFont.TINY, listOf("X", "Y", "FREQ", "F(X)", "G(X)", "Ans", "Bpd", "abcd", "-1,23"), "tiny")
        assertCovered(LcdFont.INVERTED, (0..9).map { it.toString() }, "inverted digits")
    }

    @Test
    fun glyphsHaveTheMeasuredCellSizes() {
        val expected = mapOf(
            LcdFont.LARGE to (5 to 9),
            LcdFont.SMALL to (5 to 6),
            LcdFont.MENU to (5 to 7),
            LcdFont.INVERTED to (5 to 5),
        )
        for ((font, size) in expected) {
            for (key in font.glyphKeys) {
                val glyph = font.glyph(key) ?: error(key)
                assertEquals(size, glyph.width to glyph.height, "glyph '$key'")
                assertEquals(6, font.advance(key), "advance of '$key'")
            }
        }
        for (key in LcdFont.TINY.glyphKeys) {
            val glyph = LcdFont.TINY.glyph(key) ?: error(key)
            assertTrue(glyph.width in 1..5 && glyph.height == 5, "tiny glyph '$key'")
        }
    }

    @Test
    fun combiningMarksAndTheExponentLigatureAreSingleKeys() {
        assertEquals(listOf("x̄"), LcdFont.LARGE.keys("x̄"))
        assertEquals(listOf("x̂", "₁"), LcdFont.LARGE.keys("x̂₁"))
        assertEquals(listOf("1", "×₁₀", "3"), LcdFont.LARGE.keys("1×₁₀3"))
        assertEquals(listOf("2", "×", "1", "0"), LcdFont.LARGE.keys("2×10"))
    }

    @Test
    fun anUnknownCharacterDrawsAHollowBox() {
        assertNull(LcdFont.LARGE.glyph("☃"))
        val canvas = LcdCanvas()
        canvas.text(LcdFont.LARGE, "☃", 0, 1)
        val frame = canvas.freeze(emptySet(), null, "")
        assertEquals(Ink(1, 1, 5, 9), frame.ink())
        assertEquals((1..5).toList(), frame.darkColumns(1))
        assertEquals(listOf(1, 5), frame.darkColumns(5))
    }

    @Test
    fun scriptDigitsAreTheSmallerFontsGlyphsRaisedOrLowered() {
        val small2 = LcdFont.SMALL.rowsOf("2")
        assertEquals(small2, LcdFont.LARGE.rowsOf("²").take(6))
        assertEquals(LcdFont.SMALL.rowsOf("1"), LcdFont.LARGE.rowsOf("₁").drop(3))
        assertTrue(LcdFont.LARGE.rowsOf("₁").take(3).all { row -> row.all { it == '.' } })
    }
}
