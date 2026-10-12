package dev.inkstone.core.calc.parse

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.input.ConversionItem
import dev.inkstone.core.calc.input.MetricConversions
import dev.inkstone.core.calc.input.Tok

/*
 * Priority levels 13 down to 6 of research 02 §12 (VERIFIED, the guide's "Calculation Priority
 * Sequence"), one function per level, loosest first. Equal levels associate left to right.
 *
 *   13  or xor xnor        10  × ÷ ÷R             7  omitted ×
 *   12  and                 9  • (Dot)            6  conversions, x̂ ŷ x̂₁ x̂₂
 *   11  + −                 8  nPr nCr ∠          5…1 in Operands.kt
 */

/** A whole expression: level 13 downwards. */
internal fun ParseState.parseExpression(): Node? = leftAssociative(Tables.orOps) { parseAnd() }

private fun ParseState.parseAnd(): Node? = leftAssociative(Tables.andOps) { parseSum() }

private fun ParseState.parseSum(): Node? = leftAssociative(Tables.sumOps) { parseProduct() }

private fun ParseState.parseProduct(): Node? = leftAssociative(Tables.productOps) { parseDot() }

private fun ParseState.parseDot(): Node? = leftAssociative(Tables.dotOps) { parsePermutation() }

private fun ParseState.parsePermutation(): Node? = leftAssociative(Tables.permutationOps) { parseImplicit() }

/**
 * Level 7, the omitted `×` (research 02 §7.1). It is its own level, tighter than `×` and `÷`, so
 * `1÷2π` is `1÷(2π)` (INFERRED from the table, as research 02 says) and `2π²` is `2·(π²)`.
 */
private fun ParseState.parseImplicit(): Node? {
    var left = parseConversion() ?: return null
    while (peek()?.let(Tables::startsImplicit) == true) {
        val right = nested(1, 1) { parseConversion() } ?: return null
        left = record(Binary(BinOp.IMPLICIT_MUL, left, right))
    }
    return left
}

/**
 * Level 6: a metric conversion or a STAT estimate after its operand. Both are postfix and bind
 * looser than the `(−)` sign, which is why `(−)31°C▶°F` converts −31 (research 04 E-55: −23,8).
 */
private fun ParseState.parseConversion(): Node? {
    var node = parseSigned() ?: return null
    while (true) {
        val next = conversionStep(node) ?: return null
        if (next === node) return node
        node = next
    }
}

/** One conversion or estimate applied to [node]; [node] itself when there is none. */
private fun ParseState.conversionStep(node: Node): Node? {
    val item = peek()
    if (item is ConversionItem) {
        // A CONV number outside 01…40 is a Syntax ERROR (research 02 §10.16, SECONDARY).
        if (MetricConversions.get(item.number) == null) return fail(CalcError.SYNTAX)
        pos++
        return record(Convert(item.number, node))
    }
    val estimate = peekTok()?.let { Tables.estimates[it] } ?: return node
    pos++
    return record(Postfix(estimate, node))
}

private fun ParseState.leftAssociative(ops: Map<Tok, BinOp>, operand: ParseState.() -> Node?): Node? {
    var left = operand() ?: return null
    while (true) {
        val op = peekTok()?.let { ops[it] } ?: return left
        pos++
        val right = nested(1, 1) { operand() } ?: return null
        left = record(Binary(op, left, right))
    }
}
