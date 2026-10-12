package dev.inkstone.core.calc.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The 99-byte buffer and the ■ cursor (research 02 §7.2). The byte costs are docs/CALCULATOR.md
 * §3's table — the ES family's printed Math-format figures ([S570] p.E-15), 1 for everything else,
 * a template's figure plus its contents — which is a GUESS for the ZA, so these tests pin that
 * guess and are the ones to change when the emulator measures the real costs.
 */
class CapacityTest {
    private fun digits(count: Int): InputState = Keys.type(List(count) { "7" }.joinToString(" "))

    @Test
    fun `ninety-nine bytes are accepted and the hundredth key is refused`() {
        val full = digits(InputBytes.CAPACITY)
        assertEquals(InputBytes.CAPACITY, full.bytesUsed)
        assertEquals(0, full.bytesLeft)
        assertSame(full, full.insert(Tok.D1))
        assertSame(full, full.insert(ConstantItem(28)))
        assertSame(full, full.insertTemplate(TemplateKind.SQRT))
        assertEquals(InputBytes.CAPACITY - 1, full.delete().bytesUsed)
    }

    @Test
    fun `the block cursor appears with ten bytes or fewer left`() {
        assertFalse(digits(InputBytes.CAPACITY - InputBytes.BLOCK_CURSOR_THRESHOLD - 1).nearlyFull)
        assertTrue(digits(InputBytes.CAPACITY - InputBytes.BLOCK_CURSOR_THRESHOLD).nearlyFull)
        assertFalse(InputState.empty().nearlyFull)
    }

    @Test
    fun `each template costs the printed Math-format figure`() {
        val expected = mapOf(
            "FRAC" to 9, "MIXED" to 13, "log□" to 6, "10^" to 4, "e^" to 4, "√" to 4, "∛" to 9,
            "x^" to 4, "ⁿ√" to 9, "∫" to 8, "d/dx" to 6, "Σ" to 8, "Abs" to 4,
        )
        for ((key, bytes) in expected) assertEquals(bytes, Keys.type(key).bytesUsed, key)
    }

    @Test
    fun `the power postfixes cost their figures and everything else one byte`() {
        assertEquals(1 + 4, Keys.type("2 x²").bytesUsed)
        assertEquals(1 + 4, Keys.type("2 x³").bytesUsed)
        assertEquals(1 + 5, Keys.type("2 x⁻¹").bytesUsed)
        assertEquals(2, Keys.type("CONST28 CONV02").bytesUsed)
        assertEquals(1, Keys.type("sin").bytesUsed)
        assertEquals(1, InputBytes.of(TokenItem(Tok.RAN_INT)))
        assertEquals(1, InputBytes.of(TokenItem(Tok.SQRT_FN)))
        assertEquals(1, InputBytes.of(TokenItem(Tok.FRAC_SEP)))
    }

    @Test
    fun `a template costs its figure plus its contents`() {
        assertEquals(1 + 9, Keys.type("2 FRAC").bytesUsed)
        assertEquals(4 + 1, Keys.type("√ 2").bytesUsed)
        assertEquals(9 + 4 + 1, Keys.type("FRAC √ 2").bytesUsed)
    }

    @Test
    fun `a template that would overflow the buffer is refused whole`() {
        val nearly = digits(InputBytes.CAPACITY - 9)
        assertEquals(InputBytes.CAPACITY, nearly.insertTemplate(TemplateKind.FRACTION).bytesUsed)
        val closer = digits(InputBytes.CAPACITY - 8)
        assertSame(closer, closer.insertTemplate(TemplateKind.FRACTION))
        assertEquals(InputBytes.CAPACITY, closer.insertTemplate(TemplateKind.INTEGRAL).bytesUsed)
        val closest = digits(InputBytes.CAPACITY - 7)
        assertSame(closest, closest.insertTemplate(TemplateKind.INTEGRAL))
        assertSame(closest, closest.insertTemplate(TemplateKind.MIXED_FRACTION))
    }
}
