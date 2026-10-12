package dev.inkstone.core.calc.input

/**
 * The edits behind [InputState]. Each returns the new state, or **null when the key is refused** —
 * the calculator's answer to a key it will not take is to do nothing, so [InputState] turns null
 * back into itself. Capacity is checked once, in [commit], on the tree that would result.
 */
internal object Editing {
    fun insertItem(s: InputState, item: Item): InputState? {
        val run = s.run()
        val i = s.cursor.index
        if (item.isPowerPostfix() && followsPowerPostfix(s)) return null
        val replaced = if (s.overwrite && !s.natural && i < run.size) 1 else 0
        return commit(s, s.cursor.path, run.splice(i, replaced, listOf(item)), Cursor(s.cursor.path, i + 1))
    }

    /** Natural display only; LineIO turns template keys into tokens before reaching here. */
    fun insertTemplate(s: InputState, kind: TemplateKind): InputState? {
        val run = s.run()
        val i = s.cursor.index
        return when {
            kind == TemplateKind.SCI_EXPONENT -> null
            kind == TemplateKind.POWER && followsPowerPostfix(s) -> null
            s.wrapPending -> wrapInto(s, kind, run, i)
            kind == TemplateKind.FRACTION -> fractionAfterNumber(s, run, i)
            else -> place(s, run, i until i, Template.empty(kind), Cursor(s.cursor.path + PathStep(i, 0), 0))
        }
    }

    /**
     * DEL (research 02 §7.6). Insert mode deletes what is left of the cursor and overwrite mode
     * (LineIO only) what is under it — at the very end, where nothing is under it, the item to its
     * left (⚠️ GUESS). Templates are not deleted in one press, because a template can hold most of
     * an expression and one DEL should never take more than one keystroke's worth of typing:
     *
     * - DEL just after a template steps into the end of its last slot, so further presses delete
     *   its contents from the right; an **empty** template is deleted outright.
     * - DEL at the start of a later slot steps back to the end of the slot before it.
     * - DEL at the start of the first slot dissolves the template and keeps everything its slots
     *   held, in slot order, where the template was.
     *
     * ⚠️ All three are GUESSES — the guide documents DEL only for characters — chosen so that
     * repeated DEL deletes everything, one typed item at a time, and nothing typed is lost to a
     * single press.
     */
    fun delete(s: InputState): InputState? {
        val run = s.run()
        val i = s.cursor.index
        val path = s.cursor.path
        return when {
            s.overwrite && !s.natural && i < run.size -> commit(s, path, run.splice(i, 1, emptyList()), s.cursor)
            i > 0 -> deleteBefore(s, run, i)
            path.isEmpty() -> null
            else -> deleteAtSlotStart(s)
        }
    }

    private fun deleteBefore(s: InputState, run: List<Item>, i: Int): InputState? {
        val path = s.cursor.path
        val item = run[i - 1]
        if (item is Template && item.slots.any { !it.isEmpty }) {
            val last = item.slots.size - 1
            return s.copy(cursor = Cursor(path + PathStep(i - 1, last), item.slots[last].items.size))
        }
        return commit(s, path, run.splice(i - 1, 1, emptyList()), Cursor(path, i - 1))
    }

    private fun deleteAtSlotStart(s: InputState): InputState? {
        val step = s.cursor.path.last()
        val parentPath = s.cursor.path.dropLast(1)
        val parent = s.expr.exprAt(parentPath)
        val template = parent.templateAt(step)
        if (step.slot > 0) {
            val previous = template.slots[step.slot - 1]
            return s.copy(cursor = Cursor(parentPath + PathStep(step.item, step.slot - 1), previous.items.size))
        }
        val contents = template.slots.flatMap { it.items }
        return commit(s, parentPath, parent.items.splice(step.item, 1, contents), Cursor(parentPath, step.item))
    }

    /**
     * `FRAC` "directly after typing a number makes that number the numerator and moves the cursor
     * to the denominator" (research 02 §7.4, VERIFIED: `1 + 7 FRAC 6`). Only a number literal is
     * taken — the guide says "number", and `π FRAC` taking π would make `2π FRAC` ambiguous.
     */
    private fun fractionAfterNumber(s: InputState, run: List<Item>, i: Int): InputState? {
        var start = i
        while (start > 0 && isNumberPart(run, start - 1)) start--
        val path = s.cursor.path
        if (start == i) {
            return place(s, run, i until i, Template.empty(TemplateKind.FRACTION), Cursor(path + PathStep(i, 0), 0))
        }
        val fraction = Template(TemplateKind.FRACTION, listOf(Expr(run.subList(start, i)), Expr.EMPTY))
        return place(s, run, start until i, fraction, Cursor(path + PathStep(start, 1), 0))
    }

    /** INS then a template key (research 02 §7.5); see [Wrap] for what is taken. */
    private fun wrapInto(s: InputState, kind: TemplateKind, run: List<Item>, i: Int): InputState? {
        val end = Wrap.extent(run, i)
        val target = Wrap.targetSlot(kind)
        val argument = Expr(run.subList(i, end))
        val slots = List(kind.slotCount) { if (it == target) argument else Expr.EMPTY }
        val firstEmpty = slots.indexOfFirst { it.isEmpty }
        val slot = if (firstEmpty >= 0) firstEmpty else target
        val cursor = Cursor(s.cursor.path + PathStep(i, slot), slots[slot].items.size)
        return place(s, run, i until end, Template(kind, slots), cursor)
    }

    private fun place(s: InputState, run: List<Item>, range: IntRange, template: Template, cursor: Cursor) =
        commit(s, s.cursor.path, run.splice(range.first, range.last - range.first + 1, listOf(template)), cursor)

    /**
     * x², x³, x^□ and x⁻¹ "cannot be input in consecutive sequence" (research 02 §7.4, VERIFIED;
     * the ZA guide's power_functions page words it as a key sequence). Two places count as
     * straight after one of them: after the item itself, and — in Natural display, where `x^`
     * leaves the cursor *inside* its exponent — the start of an x^□ slot, so `2 x^ x²` ignores the
     * x² exactly as LineIO's `2 ^( x²` does. That the start of a slot that already holds something
     * (`2 x^ 3 ◀ x²`) is refused too is a ⚠️ GUESS that the firmware tests the byte before the
     * cursor, which there is still the x^ opener.
     */
    private fun followsPowerPostfix(s: InputState): Boolean {
        val i = s.cursor.index
        if (i > 0) return s.run()[i - 1].isPowerPostfix()
        val step = s.cursor.path.lastOrNull() ?: return false
        return s.expr.exprAt(s.cursor.path.dropLast(1)).templateAt(step).kind == TemplateKind.POWER
    }

    private fun commit(s: InputState, path: List<PathStep>, run: List<Item>, cursor: Cursor): InputState? {
        val expr = s.expr.replaceAt(path, Expr(run))
        if (InputBytes.of(expr) > InputBytes.CAPACITY) return null
        return s.copy(expr = expr, cursor = cursor, wrapPending = false)
    }

    private fun InputState.run(): List<Item> = expr.exprAt(cursor.path).items
}
