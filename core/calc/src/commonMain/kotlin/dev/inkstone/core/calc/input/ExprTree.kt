package dev.inkstone.core.calc.input

/**
 * Path arithmetic over the immutable [Expr] tree. A [Cursor] names a gap in one nested run; these
 * read that run and rebuild the tree around a changed copy of it. Inputs are at most 99 bytes, so
 * rebuilding the spine per keystroke is cheaper than any cleverness would be.
 *
 * A path that does not lead through templates is a programming error, not an input the user can
 * produce, so it fails a `require`.
 */
internal fun Expr.exprAt(path: List<PathStep>): Expr =
    path.fold(this) { expr, step -> expr.templateAt(step).slots[step.slot] }

internal fun Expr.templateAt(step: PathStep): Template {
    val item = items[step.item]
    require(item is Template) { "path step $step does not lead into a template" }
    return item
}

/** This tree with the run at [path] replaced by [replacement]. */
internal fun Expr.replaceAt(path: List<PathStep>, replacement: Expr): Expr {
    if (path.isEmpty()) return replacement
    val step = path.first()
    val template = templateAt(step)
    val slot = template.slots[step.slot].replaceAt(path.drop(1), replacement)
    val rebuilt = Template(template.kind, template.slots.mapIndexed { i, s -> if (i == step.slot) slot else s })
    return Expr(items.mapIndexed { i, item -> if (i == step.item) rebuilt else item })
}

/** [items] with [count] items removed at [from] and [inserted] put in their place. */
internal fun List<Item>.splice(from: Int, count: Int, inserted: List<Item>): List<Item> =
    take(from) + inserted + drop(from + count)
