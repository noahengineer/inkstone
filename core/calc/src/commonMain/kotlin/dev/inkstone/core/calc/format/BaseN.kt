package dev.inkstone.core.calc.format

import dev.inkstone.core.calc.input.Item
import dev.inkstone.core.calc.parse.Radix
import dev.inkstone.core.calc.value.ComplexValue
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.Value

/**
 * BASE-N results (research 03 §5, VERIFIED): `Dec` unpadded with a plain minus; `Hex` 8 digits,
 * `Oct` 11 and `Bin` 16, zero-padded, a negative value as its two's complement — 32 bits, but
 * 16 in binary (`Neg(101101)` is `1111111111010011`, 05 EX-BASEN-11). The label goes above the
 * result: `Dec`, `Hex`, `Bin`, `Oct` (03 §5.3, SECONDARY strings).
 *
 * A fractional part is cut off (03 §5.1). The evaluator keeps values inside each base's range;
 * what the calculator shows when a 32-bit value is converted to 16-bit binary is UNKNOWN
 * (INFERRED Math ERROR, 03 §5.2) and a [Shown] cannot be an error, so this draws the low 16 bits
 * — a **GUESS** the machine should pre-empt by refusing the conversion.
 */
internal object BaseN {
    private const val HEX = 16
    private const val OCT = 8
    private const val BIN = 2
    private const val HEX_WIDTH = 8
    private const val OCT_WIDTH = 11
    private const val BIN_WIDTH = 16
    private const val WORD: Long = 0xFFFF_FFFFL
    private const val HALF_WORD: Long = 0xFFFFL

    fun label(radix: Radix): String = when (radix) {
        Radix.DEC -> "Dec"
        Radix.HEX -> "Hex"
        Radix.BIN -> "Bin"
        Radix.OCT -> "Oct"
    }

    fun items(value: Value, radix: Radix): List<Item> {
        val n = integerOf(value)
        return when (radix) {
            Radix.DEC -> Items.signed(n < 0L, Items.integer(if (n < 0L) -n else n))
            Radix.HEX -> padded(n and WORD, HEX, HEX_WIDTH)
            Radix.BIN -> padded(n and HALF_WORD, BIN, BIN_WIDTH)
            Radix.OCT -> padded(n and WORD, OCT, OCT_WIDTH)
        }
    }

    private fun padded(bits: Long, base: Int, width: Int): List<Item> =
        Items.text(bits.toString(base).uppercase().padStart(width, '0'))

    /** The whole part of a real (or of a complex number's real part); 0 for anything else. */
    private fun integerOf(value: Value): Long {
        val real = when (value) {
            is Real -> value
            is ComplexValue -> value.re
            else -> null
        }
        return real?.dec?.truncate()?.toLongOrNull() ?: 0L
    }
}
