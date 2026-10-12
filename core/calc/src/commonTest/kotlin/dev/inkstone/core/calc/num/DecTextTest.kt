package dev.inkstone.core.calc.num

import dev.inkstone.core.calc.input.MetricConversions
import dev.inkstone.core.calc.input.ScientificConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `Dec.parse`, `toCanonicalString` and `fromDigits`: persistence text and typed literals. */
class DecTextTest {
    /** text → canonical spelling of the value it denotes. */
    private val canonical = listOf(
        "-12.5" to "-12.5",
        "1.5E-3" to "0.0015",
        "0.52917721067E-10" to "5.2917721067E-11",
        "927.4009994E-26" to "9.274009994E-24",
        "3.14159265358980" to "3.1415926535898",
        "+7" to "7",
        ".5" to "0.5",
        "5." to "5",
        "1e5" to "100000",
        "0.0005" to "0.0005",
        "0.00000012345" to "1.2345E-7",
        "1200" to "1200",
        "123456789012345E6" to "123456789012345000000",
        "1.23456789012345E21" to "1.23456789012345E21",
        "1E100" to "1E100",
        "-1.23456789012345E-99" to "-1.23456789012345E-99",
        "000123.4500" to "123.45",
        "-0" to "0",
    )

    /** More than 15 significant digits: rounded once, half away from zero. */
    private val rounded = listOf(
        "3.14159265358979323846" to "3.14159265358979",
        "2.718281828459045" to "2.71828182845905",
        "-2.718281828459045" to "-2.71828182845905",
        "0.99999999999999949" to "0.999999999999999",
        "0.9999999999999995" to "1",
        "9.999999999999995E-100" to "1E-99",
        "9.99999999999999E-100" to "0",
    )

    private val malformed = listOf(
        "", "-", ".", "E5", "1E", "1.2.3", "1,5", " 1", "1 ", "1E+-5", "abc", "1E9999999999", "--1", "0x10",
    )

    @Test
    fun `parse reads the forms persistence and the constant tables use`() {
        for ((text, expected) in canonical) {
            assertEquals(expected, d(text).toCanonicalString(), text)
        }
    }

    @Test
    fun `parse rounds extra digits half away from zero`() {
        for ((text, expected) in rounded) assertEquals(d(expected), d(text), text)
    }

    @Test
    fun `parse rejects what is not a number`() {
        for (text in malformed) assertNull(Dec.parse(text), "'$text'")
    }

    @Test
    fun `canonical text reads back to the identical value`() {
        val values = listOf(
            Dec.PI, Dec.E, Dec.ZERO, Dec.ONE, -Dec.HALF, d("123456789012345"), d("1.23456789012345E-99"),
            d("-9.99999999999999E99"), d("1E99") * d("1E99"), d("0.000001"), d("0.0000001"), d("1E20"),
            d("1E21"), d("2") / d("3"), d("-1") / d("7"), d("299792458"), d("6.62607004E-34"),
        )
        for (v in values) {
            val text = v.toCanonicalString()
            assertEquals(v, Dec.parse(text), text)
            assertEquals(text, d(text).toCanonicalString())
        }
    }

    @Test
    fun `fromDigits builds a typed literal from up to ninety-nine digits`() {
        val typed = "000123456789012345678901234567"
        assertEquals(30, typed.length)
        // 000,123456789012345|678… : the 16th significant digit is 6, so the 15th rounds up.
        assertEquals(d("0.123456789012346"), Dec.fromDigits(false, typed, 3, 0))
        assertEquals(d("-123456789012346E12"), Dec.fromDigits(true, typed, typed.length, 0))
        assertEquals(d("1.23456789012346E29"), Dec.fromDigits(false, "123456789012345500000000000000", 30, 0))
        assertEquals(d("1.5E-3"), Dec.fromDigits(false, "15", 1, -3))
        assertEquals(d("0.5"), Dec.fromDigits(false, "5", 0, 0))
        assertEquals(d("2.5E-98"), Dec.fromDigits(false, "25", 1, -98))
        assertEquals(Dec.ZERO, Dec.fromDigits(true, "0000", 2, 5))
        assertEquals(d("1E99"), Dec.fromDigits(false, "9".repeat(99), 99, 0))
        assertTrue(Dec.fromDigits(false, "9".repeat(100), 100, 0).isOverflow)
        assertEquals(Dec.ZERO, Dec.fromDigits(false, "1", 0, -99))
    }

    @Test
    fun `every CONST value and CONV factor parses`() {
        for (c in ScientificConstants.all) assertNotNull(Dec.parse(c.value), c.name)
        for (c in MetricConversions.all) assertNotNull(Dec.parse(c.factor), c.text)
        assertEquals(d("-9.284764620E-24"), d(ScientificConstants.all[18].value))
    }
}
