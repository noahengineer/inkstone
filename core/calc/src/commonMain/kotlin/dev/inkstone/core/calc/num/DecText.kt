package dev.inkstone.core.calc.num

/*
 * Machine text for Dec — persistence and the CONST/CONV tables (input/Expr.kt). Never what the
 * LCD shows: that uses a decimal COMMA, 10 digits and ×10ⁿ, and is the formatter's job.
 */

private const val MAX_EXPONENT_DIGITS = 9
private const val PLAIN_MIN_EXPONENT = -6
private const val PLAIN_MAX_EXPONENT = 20

/**
 * `[+-]digits[.digits][E[+-]digits]`, with at least one mantissa digit on one side of the point
 * (`5.` and `.5` are accepted) and at most nine exponent digits; null otherwise.
 */
internal fun parseDecText(text: String): Dec? {
    val eAt = text.indexOfFirst { it == 'E' || it == 'e' }
    val mantissa = if (eAt < 0) text else text.substring(0, eAt)
    val exponent = if (eAt < 0) 0 else parseExponent(text.substring(eAt + 1)) ?: return null
    val negative = mantissa.startsWith('-')
    val body = if (negative || mantissa.startsWith('+')) mantissa.substring(1) else mantissa
    val point = body.indexOf('.')
    val digits = if (point < 0) body else body.removeRange(point, point + 1)
    if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
    return Dec.fromDigits(negative, digits, if (point < 0) digits.length else point, exponent)
}

private fun parseExponent(text: String): Int? {
    val negative = text.startsWith('-')
    val body = if (negative || text.startsWith('+')) text.substring(1) else text
    if (body.isEmpty() || body.length > MAX_EXPONENT_DIGITS || !body.all { it in '0'..'9' }) return null
    val v = body.toInt()
    return if (negative) -v else v
}

/**
 * One spelling per value: plain for exponents −6…20 (`3.1415926535898`, `0.0005`, `1200`),
 * otherwise `d.dddE±n` (`1.5E-7`, `1E100`).
 */
internal fun canonicalText(negative: Boolean, digits: String, exponent: Int): String {
    if (digits == "0") return "0"
    val body = if (exponent in PLAIN_MIN_EXPONENT..PLAIN_MAX_EXPONENT) {
        plainText(digits, exponent)
    } else {
        val head = if (digits.length == 1) digits else digits.substring(0, 1) + "." + digits.substring(1)
        "${head}E$exponent"
    }
    return if (negative) "-$body" else body
}

private fun plainText(digits: String, exponent: Int): String = when {
    exponent >= digits.length - 1 -> digits + "0".repeat(exponent - digits.length + 1)
    exponent >= 0 -> digits.substring(0, exponent + 1) + "." + digits.substring(exponent + 1)
    else -> "0." + "0".repeat(-exponent - 1) + digits
}
