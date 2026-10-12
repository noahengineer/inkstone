package dev.inkstone.core.calc.format

import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.Item
import dev.inkstone.core.calc.input.Template
import dev.inkstone.core.calc.input.TemplateKind
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.value.IntegerFunctions
import dev.inkstone.core.calc.value.PrimePower
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.getOrNull

/**
 * `FACT`'s display (research 02 §10.13, VERIFIED screens): prime powers joined by `×`, smallest
 * first, and whatever has no prime factor of three digits or fewer in parentheses —
 * `1014` → `2×3×13²`, `4104676` → `2²×(1026169)`. The factorising is the value library's
 * ([IntegerFunctions.factorize]); this only draws it.
 *
 * Exponents 2 and 3 are the `²`/`³` tokens, as typed; higher ones are an `x^▪` template in MathO
 * and `^(n)` in linear output — the guide shows neither, a **GUESS**. `1` factorises to `1`, also
 * a GUESS (the guide's range is "positive integer").
 */
internal object Factors {
    private const val CUBE = 3

    private val TIMES: Item = Items.tok(Tok.TIMES)

    /** Null when [x] cannot be factorised — the machine shows Math ERROR for it. */
    fun shape(x: Real, natural: Boolean): Shape? {
        val factors = IntegerFunctions.factorize(x).getOrNull() ?: return null
        val pieces = factors.powers.map { power(it, natural) } +
            if (factors.leftover > 1L) listOf(bracketed(factors.leftover)) else emptyList()
        val items = if (pieces.isEmpty()) Items.integer(1L) else Items.joined(pieces, TIMES)
        return Shape(items, Look.FACTORS)
    }

    private fun power(p: PrimePower, natural: Boolean): List<Item> {
        val base = Items.integer(p.prime)
        return when {
            p.exponent == 1 -> base
            p.exponent == 2 -> base + Items.tok(Tok.SQUARE)
            p.exponent == CUBE -> base + Items.tok(Tok.CUBE)
            natural -> base + Template(TemplateKind.POWER, listOf(Expr(Items.integer(p.exponent.toLong()))))
            else -> base + Items.tok(Tok.POWER_FN) + Items.integer(p.exponent.toLong()) + Items.tok(Tok.CLOSE)
        }
    }

    private fun bracketed(n: Long): List<Item> = listOf(Items.tok(Tok.OPEN)) + Items.integer(n) + Items.tok(Tok.CLOSE)
}
