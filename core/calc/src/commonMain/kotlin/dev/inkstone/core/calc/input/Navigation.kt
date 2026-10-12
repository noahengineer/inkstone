package dev.inkstone.core.calc.input

/**
 * Cursor movement through templates (research 02 §7.3, VERIFIED from the guide's key sequences).
 *
 * ▶ and ◀ walk the slots in [TemplateKind] order — the order the guide's sequences visit them —
 * entering a template at its first (▶) or last (◀) slot and leaving it past its last (▶) or first
 * (◀) one. That one rule gives every sequence the guide prints: `FRAC 2 + √ 2 ▶ ▶ 1` (the first ▶
 * leaves the √, the second crosses into the denominator), `∫ ln X ) ▶ 1 ▶ e`, `log□ 2 ▶ 4 ▶ x³`.
 */
internal object Navigation {
    fun right(expr: Expr, cursor: Cursor, wrap: Boolean): Cursor {
        val run = expr.exprAt(cursor.path)
        if (cursor.index < run.items.size) {
            val item = run.items[cursor.index]
            return if (item is Template) {
                Cursor(cursor.path + PathStep(cursor.index, 0), 0)
            } else {
                cursor.copy(index = cursor.index + 1)
            }
        }
        if (cursor.path.isEmpty()) return if (wrap) Cursor() else cursor
        val step = cursor.path.last()
        val parentPath = cursor.path.dropLast(1)
        val template = expr.exprAt(parentPath).templateAt(step)
        return if (step.slot + 1 < template.slots.size) {
            Cursor(parentPath + PathStep(step.item, step.slot + 1), 0)
        } else {
            Cursor(parentPath, step.item + 1)
        }
    }

    fun left(expr: Expr, cursor: Cursor, wrap: Boolean): Cursor {
        val run = expr.exprAt(cursor.path)
        if (cursor.index > 0) {
            val item = run.items[cursor.index - 1]
            return if (item is Template) {
                val last = item.slots.size - 1
                Cursor(cursor.path + PathStep(cursor.index - 1, last), item.slots[last].items.size)
            } else {
                cursor.copy(index = cursor.index - 1)
            }
        }
        if (cursor.path.isEmpty()) return if (wrap) Cursor(index = run.items.size) else cursor
        val step = cursor.path.last()
        val parentPath = cursor.path.dropLast(1)
        if (step.slot == 0) return Cursor(parentPath, step.item)
        val template = expr.exprAt(parentPath).templateAt(step)
        return Cursor(parentPath + PathStep(step.item, step.slot - 1), template.slots[step.slot - 1].items.size)
    }

    /**
     * ▲ / ▼ in Natural display: the innermost enclosing template that has a slot in that direction
     * takes the cursor to the **end** of that slot. Outside any such template nothing moves.
     *
     * VERIFIED: fraction numerator ▼ denominator (`FRAC 2 ▼ 3`), mixed fraction numerator ▼
     * denominator (`SHIFT FRAC 3 ▶ 1 ▼ 2`), ∫ lower ▲ upper (`… ▶ 0 ▲ π`). The rest of the table is
     * a ⚠️ GUESS that follows where each slot is drawn: a limit above or below, ⁿ√'s raised index,
     * log's lowered base, d/dx's `x=` slot below-right. Landing at the end rather than the start is
     * also a guess; the guide's sequences only ever move into empty slots.
     */
    fun vertical(expr: Expr, cursor: Cursor, down: Boolean): Cursor {
        val table = if (down) DOWN else UP
        for (depth in cursor.path.indices.reversed()) {
            val step = cursor.path[depth]
            val outer = cursor.path.take(depth)
            val template = expr.exprAt(outer).templateAt(step)
            val target = table[template.kind]?.get(step.slot) ?: continue
            return Cursor(outer + PathStep(step.item, target), template.slots[target].items.size)
        }
        return cursor
    }

    private const val FIRST = 0
    private const val SECOND = 1
    private const val THIRD = 2

    private val DOWN: Map<TemplateKind, Map<Int, Int>> = mapOf(
        TemplateKind.FRACTION to mapOf(FIRST to SECOND),
        TemplateKind.MIXED_FRACTION to mapOf(SECOND to THIRD),
        TemplateKind.INTEGRAL to mapOf(FIRST to SECOND, THIRD to SECOND),
        TemplateKind.SUMMATION to mapOf(FIRST to SECOND, THIRD to SECOND),
        TemplateKind.DERIVATIVE to mapOf(FIRST to SECOND),
        TemplateKind.NTH_ROOT to mapOf(FIRST to SECOND),
        TemplateKind.LOG_BASE to mapOf(SECOND to FIRST),
    )

    private val UP: Map<TemplateKind, Map<Int, Int>> = mapOf(
        TemplateKind.FRACTION to mapOf(SECOND to FIRST),
        TemplateKind.MIXED_FRACTION to mapOf(THIRD to SECOND),
        TemplateKind.INTEGRAL to mapOf(FIRST to THIRD, SECOND to THIRD),
        TemplateKind.SUMMATION to mapOf(FIRST to THIRD, SECOND to THIRD),
        TemplateKind.DERIVATIVE to mapOf(SECOND to FIRST),
        TemplateKind.NTH_ROOT to mapOf(SECOND to FIRST),
        TemplateKind.LOG_BASE to mapOf(FIRST to SECOND),
    )
}
