package dev.inkstone.core.calc.parse

import dev.inkstone.core.calc.input.Template
import dev.inkstone.core.calc.input.TemplateKind
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.input.tokOrNull

/*
 * Priority levels 5, 4 and 3 of research 02 §12:
 *
 *   5  the (−) sign, and the BASE-N prefixes d h b o
 *   4  fractions (LineIO a⌟b, a⌟b⌟c; a Natural fraction is a template, see Calls.kt)
 *   3  postfix x² x³ x⁻¹ x! °'" ° ʳ ᵍ % ▶t, powers x^□, roots ˣ√
 */

/**
 * Level 5. `(−)2²` is `−(2²)` because x² (level 3) binds tighter (research 02 §12, VERIFIED rule;
 * F-04's values −4 and 4 are INFERRED). A `−` or `+` typed where a value is expected is read as a
 * sign — ⚠️ GUESS, INFERRED from the SA handout typing `[−]3` into an EQN cell and getting −3
 * (research 05 EX-SA-EQN-01); the guide's own sequences always use `(−)`.
 */
internal fun ParseState.parseSigned(): Node? {
    val tok = peekTok()
    val radix = tok?.let { Tables.radixPrefixes[it] }
    return when {
        tok == Tok.NEGATIVE || tok == Tok.MINUS -> {
            pos++
            nested(0, 1) { parseSigned() }?.let { record(Negate(it)) }
        }

        tok == Tok.PLUS -> {
            pos++
            parseSigned()
        }

        radix != null -> prefixed(radix)

        else -> parseFraction()
    }
}

/** `h10`: a BASE-N prefix applies to the literal typed after it (research 03 §5.3). */
private fun ParseState.prefixed(radix: Radix): Node? {
    val scanned = scanPrefixed(items, pos + 1, radix)
    pos = scanned.end
    val node = scanned.node ?: return fail()
    return record(node)
}

/** Level 4: `a⌟b` and the mixed `a⌟b⌟c` (research 02 §7.4). A fourth part is a Syntax ERROR. */
private fun ParseState.parseFraction(): Node? {
    val first = parsePostfix() ?: return null
    if (peekTok() != Tok.FRAC_SEP) return first
    pos++
    val second = nested(1, 1) { parsePostfix() } ?: return null
    return if (peekTok() == Tok.FRAC_SEP) mixedTail(first, second) else record(Fraction(first, second))
}

private fun ParseState.mixedTail(whole: Node, numerator: Node): Node? {
    pos++
    val denominator = nested(2, 2) { parsePostfix() } ?: return null
    return if (peekTok() == Tok.FRAC_SEP) fail() else record(MixedFraction(whole, numerator, denominator))
}

/** Level 3: a primary followed by any number of postfix operators, applied left to right. */
private fun ParseState.parsePostfix(): Node? {
    var node = parsePrimary() ?: return null
    while (true) {
        val next = postfixStep(node) ?: return null
        if (next === node) return node
        node = next
    }
}

/** One postfix operator applied to [node]; [node] itself when the next item is not one. */
private fun ParseState.postfixStep(node: Node): Node? {
    val item = peek()
    if (item is Template && item.kind == TemplateKind.POWER) {
        val exponent = templateSlots(item, held = 1)?.single() ?: return null
        return record(Power(node, exponent))
    }
    val tok = peekTok() ?: return node
    Tables.postfix[tok]?.let {
        pos++
        return record(Postfix(it, node))
    }
    return when (tok) {
        Tok.POWER_FN -> lineOperand()?.let { record(Power(node, it)) }
        Tok.ROOT_FN -> lineOperand()?.let { record(Root(node, it)) }
        Tok.DMS -> dms(node)
        else -> node
    }
}

/**
 * The single argument of LineIO `^(…)` or `ˣ√(…)`, whose closing `)` may be omitted at the end.
 * The value before it (base, or index) waits on the numeric stack while it is read.
 */
private fun ParseState.lineOperand(): Node? {
    pos++
    val args = arguments(held = 1) ?: return null
    return args.singleOrNull() ?: fail()
}

/**
 * `a°`, `a°b'`, `a°b'c"` (research 02 §8.9). The `°'"` key after a value starts a DMS literal;
 * minutes and seconds are plain literals each followed by the same key. A mark straight after a
 * complete literal (`2°°`) is a Syntax ERROR. The guide says degrees and minutes "must always be
 * entered", so `2°30"` cannot be typed differently from `2°30'` — the second mark is minutes.
 */
private fun ParseState.dms(degrees: Node): Node? {
    if (degrees is Dms) return fail()
    pos++
    val minutes = dmsPart() ?: return record(Dms(degrees))
    val seconds = dmsPart()
    return record(Dms(degrees, minutes, seconds))
}

/** A literal followed by the `°'"` mark, consumed; otherwise null with nothing consumed. */
private fun ParseState.dmsPart(): NumberLit? {
    if (peekTok() !in LITERAL_STARTS) return null
    val scanned = scanNumber(items, pos)
    val literal = scanned.node as? NumberLit ?: return null
    val marked = literal.exponent == null && items.getOrNull(scanned.end)?.tokOrNull() == Tok.DMS
    if (!marked) return null
    pos = scanned.end + 1
    return literal
}
