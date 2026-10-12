package dev.inkstone.core.calc.input

/**
 * What `SHIFT DEL` (INS) hands to the next template key in Natural display (research 02 §7.5).
 *
 * The ZA guide says the argument runs from the cursor "up to the first opening parenthesis … or up
 * to the first function", which is a paraphrase that cannot be implemented literally — its own
 * example wraps a *fraction*, which is neither. The ES family's guide states the rule this
 * implements (research 02 [S570] p.E-16, INFERRED for the ZA): "If the cursor is located left of
 * a particular value or fraction (instead of an open parentheses), that value or fraction will be
 * incorporated", and "if the cursor is located left of function, the entire function is
 * incorporated"; its table wraps `(2+3)` of `1+(2+3)+4` whole, brackets kept, into √, a
 * fraction, log▪▯, ⁿ√, ∫, d/dx and Σ. So the argument is a number, a single variable or constant,
 * one template (a fraction is one), or a parenthesised group or function call through its
 * matching `)` (or to the end, if it is never closed). Anything else — an operator, or nothing —
 * wraps nothing, and the template is inserted empty.
 */
internal object Wrap {
    /** The end (exclusive) of the argument starting at [start]; equal to [start] when there is none. */
    fun extent(items: List<Item>, start: Int): Int {
        val first = items.getOrNull(start) ?: return start
        val tok = first.tokOrNull()
        return when {
            first is Template || first is ConstantItem -> start + 1
            tok == null -> start
            TokenKinds.opensParen(tok) -> matchingClose(items, start)
            tok in TokenKinds.values -> start + 1
            isNumberPart(items, start) -> numberEnd(items, start)
            else -> start
        }
    }

    /** Index just past the `)` that closes the bracket opened at [open], or the end of [items]. */
    private fun matchingClose(items: List<Item>, open: Int): Int {
        var depth = 0
        for (i in open until items.size) {
            val tok = items[i].tokOrNull() ?: continue
            depth += bracketDelta(tok)
            if (depth == 0) return i + 1
        }
        return items.size
    }

    private fun bracketDelta(tok: Tok): Int = when {
        TokenKinds.opensParen(tok) -> 1
        tok == Tok.CLOSE -> -1
        else -> 0
    }

    private fun numberEnd(items: List<Item>, start: Int): Int {
        var end = start
        while (end < items.size && isNumberPart(items, end)) end++
        return end
    }

    /**
     * The slot that receives the wrapped argument. For one-slot templates it is the only one; for
     * the rest it is the operand the template applies *to* — numerator, logarithm argument,
     * radicand, integrand, d/dx's function, Σ's summand, as [S570]'s table shows each one —
     * leaving the cursor free for the slot the user still has to type. ⚠️ GUESS for the mixed
     * fraction (its numerator), which the ZA lists as wrappable and no guide shows.
     */
    fun targetSlot(kind: TemplateKind): Int = when (kind) {
        TemplateKind.NTH_ROOT, TemplateKind.LOG_BASE, TemplateKind.MIXED_FRACTION -> 1
        else -> 0
    }
}
