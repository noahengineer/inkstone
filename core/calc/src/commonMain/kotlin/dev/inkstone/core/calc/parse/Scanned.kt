package dev.inkstone.core.calc.parse

import dev.inkstone.core.calc.input.Item
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.input.TokenKinds
import dev.inkstone.core.calc.input.tokOrNull

/**
 * A number literal read off a run without touching any parse state, so the DMS reader can look
 * ahead and back off. [node] is null when the literal is malformed; [end] is then where it went
 * wrong, otherwise just past it.
 */
internal class Scanned(val node: Node?, val end: Int)

/** Tokens a literal can start with. */
internal val LITERAL_STARTS: Set<Tok> = TokenKinds.digits + TokenKinds.hexDigits + Tok.DECIMAL + Tok.EXP10

/**
 * `digits [, digits] [×10 [(−)] digits]`, or a run of digits with hex letters (BASE-N). Malformed:
 * a second comma or `×10`, a `×10` with no exponent digits, a comma with no digit either side of
 * it, and a hex run with a comma or exponent.
 */
internal fun scanNumber(items: List<Item>, start: Int): Scanned {
    val whole = digitRun(items, start, hex = true)
    var i = whole.end
    var fraction: String? = null
    if (items.tokAt(i) == Tok.DECIMAL) {
        val after = digitRun(items, i + 1, hex = false)
        fraction = after.digits
        i = after.end
    }
    val exponent = if (items.tokAt(i) == Tok.EXP10) scanExponent(items, i + 1) else null
    if (exponent != null) i = exponent.end
    val repeated = items.tokAt(i) == Tok.DECIMAL || items.tokAt(i) == Tok.EXP10
    return Scanned(if (repeated) null else literalOf(whole, fraction, exponent), i)
}

private fun literalOf(whole: Run, fraction: String?, exponent: Run?): Node? = when {
    exponent != null && exponent.digits.isEmpty() -> null
    fraction != null && whole.digits.isEmpty() && fraction.isEmpty() -> null
    whole.hex -> if (fraction == null && exponent == null) BaseLit(null, whole.digits) else null
    else -> NumberLit(whole.digits, fraction, exponent?.signedDigits)
}

/** `h`, `d`, `b`, `o` then digits (research 03 §5.3: the prefix comes before the value). */
internal fun scanPrefixed(items: List<Item>, start: Int, radix: Radix): Scanned {
    val run = digitRun(items, start, hex = true)
    val malformed = run.digits.isEmpty() || items.tokAt(run.end) == Tok.DECIMAL || items.tokAt(run.end) == Tok.EXP10
    return Scanned(if (malformed) null else BaseLit(radix, run.digits), run.end)
}

private class Run(val digits: String, val end: Int, val hex: Boolean, val negative: Boolean = false) {
    val signedDigits: String get() = if (negative) "-$digits" else digits
}

private fun List<Item>.tokAt(i: Int): Tok? = getOrNull(i)?.tokOrNull()

private fun digitRun(items: List<Item>, start: Int, hex: Boolean): Run {
    val digits = StringBuilder()
    var sawHex = false
    var i = start
    var tok = items.digitAt(i, hex)
    while (tok != null) {
        digits.append(tok.text)
        sawHex = sawHex || tok in TokenKinds.hexDigits
        i++
        tok = items.digitAt(i, hex)
    }
    return Run(digits.toString(), i, sawHex)
}

private fun List<Item>.digitAt(i: Int, hex: Boolean): Tok? {
    val tok = tokAt(i) ?: return null
    return if (tok in TokenKinds.digits || (hex && tok in TokenKinds.hexDigits)) tok else null
}

/** After `×10`: an optional `(−)`, then the exponent's digits. */
private fun scanExponent(items: List<Item>, start: Int): Run {
    val negative = items.tokAt(start) == Tok.NEGATIVE
    val run = digitRun(items, if (negative) start + 1 else start, hex = false)
    return Run(run.digits, run.end, hex = false, negative = negative)
}
