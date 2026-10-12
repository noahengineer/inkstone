package dev.inkstone.core.calc.format

import dev.inkstone.core.calc.num.Dec

/**
 * An editor cell's short text (research 03 §2.4). Cells are about six characters wide in the tiny
 * font, and their text is **truncated, not rounded**: Ref's `8/7` shows as `1,1428` (05 EX-MAT-10,
 * VERIFIED screen), and the DIST list's `0,185937845` as `0,1859` (EX-DIST-02) — both six
 * characters cut from the full value. The full value is on the bottom line.
 *
 * **GUESSES** beyond those two screens: the cut is taken from the stored 15 digits (no ten-digit
 * rounding first), a value is always shown as a decimal in a cell (Ref's exact `8/7` is), the
 * SETUP format does not apply, and a value whose integer part does not fit — or that is below
 * `0,001`, where six characters would leave at most one significant digit — is written
 * `1,23E6` / `-1E-5`, keeping the exponent whole and cutting the mantissa. The tiny font has `E`
 * and no `×`.
 */
internal object Cells {
    private const val WIDTH = 6
    private const val EXPONENT_MARK = "E"
    private val SMALLEST_PLAIN: Dec = Dec.fromDigits(false, "1", 0, -2)

    fun text(d: Dec): String {
        if (d.isZero) return "0"
        val sign = if (d.isNegative) "-" else ""
        val room = WIDTH - sign.length
        val a = d.abs()
        val digits = a.significandDigits
        if (a >= SMALLEST_PLAIN && a.exponent < room) {
            return sign + Numerals.positional(digits, a.exponent + 1).take(room).trimEnd(',')
        }
        val exponent = EXPONENT_MARK + a.exponent
        val mantissaRoom = room - exponent.length
        val kept = if (mantissaRoom >= MIN_FRACTION_MANTISSA) mantissaRoom - 2 else 0
        val fraction = digits.drop(1).take(kept).trimEnd('0')
        val mantissa = if (fraction.isEmpty()) digits.take(1) else digits.take(1) + "," + fraction
        return sign + mantissa + exponent
    }

    /** `d,d` is the shortest mantissa with a fraction. */
    private const val MIN_FRACTION_MANTISSA = 3
}
