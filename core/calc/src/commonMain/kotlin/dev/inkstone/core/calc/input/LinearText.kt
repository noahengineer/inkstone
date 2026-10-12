package dev.inkstone.core.calc.input

/**
 * [expr] as one line of text, every template spelled the LineIO way (research 02 §7.4): `a⌟b` (or
 * `(a+1)⌟(b)` when a slot is more than one number or value), `√(…)`, `∛(…)`, `nˣ√(…)`, `^(…)`,
 * `log(a;b)`, `10^(…)`, `e^(…)`, `Abs(…)`, `∫(f;a;b)`, `d/dx(f;a)`, `Σ(f;a;b)`. Constants are
 * their symbol, conversions their `cm▶in` text, and each `°'"` mark is drawn as the first, second
 * or third mark of its run of digits (research 02 §8.9).
 *
 * For accessibility and as a test oracle. It is a spelling, not a round trip: parsing it back is
 * not promised, because an empty slot is spelled as nothing at all.
 */
fun linearText(expr: Expr): String = buildString { appendRun(expr.items) }

private const val DMS_MINUTES = 2
private const val UNKNOWN = "□"

private val DMS_RUN: Set<Tok> = TokenKinds.digits + Tok.DECIMAL + Tok.DMS

private fun StringBuilder.appendRun(items: List<Item>) {
    var marks = 0
    for (item in items) {
        val tok = item.tokOrNull()
        marks = if (tok == Tok.DMS) {
            marks + 1
        } else if (tok in DMS_RUN) {
            marks
        } else {
            0
        }
        when {
            tok == Tok.DMS -> append(dmsMark(marks))
            tok != null -> append(tok.text)
            else -> appendNonToken(item)
        }
    }
}

private fun dmsMark(ordinal: Int): String = when {
    ordinal <= 1 -> "°"
    ordinal == DMS_MINUTES -> "'"
    else -> "\""
}

private fun StringBuilder.appendNonToken(item: Item) {
    when (item) {
        is ConstantItem -> append(ScientificConstants.get(item.number)?.symbol?.text ?: UNKNOWN)
        is ConversionItem -> append(MetricConversions.get(item.number)?.text ?: UNKNOWN)
        is Template -> appendTemplate(item)
        is TokenItem -> append(item.tok.text)
    }
}

/** Function-shaped templates: an opening, the slots joined by `;`, and `)`. */
private val CALL_SPELLING: Map<TemplateKind, String> = mapOf(
    TemplateKind.SQRT to "√(",
    TemplateKind.CBRT to "∛(",
    TemplateKind.POWER to "^(",
    TemplateKind.LOG_BASE to "log(",
    TemplateKind.TEN_POWER to "10^(",
    TemplateKind.E_POWER to "e^(",
    TemplateKind.ABS to "Abs(",
    TemplateKind.INTEGRAL to "∫(",
    TemplateKind.DERIVATIVE to "d/dx(",
    TemplateKind.SUMMATION to "Σ(",
)

private fun StringBuilder.appendTemplate(template: Template) {
    val opening = CALL_SPELLING[template.kind]
    when {
        opening != null -> {
            append(opening)
            template.slots.forEachIndexed { i, slot ->
                if (i > 0) append(';')
                appendRun(slot.items)
            }
            append(')')
        }

        template.kind == TemplateKind.NTH_ROOT -> {
            appendOperand(template.slots[0])
            append("ˣ√(")
            appendRun(template.slots[1].items)
            append(')')
        }

        template.kind == TemplateKind.SCI_EXPONENT -> {
            append("×10^")
            appendRun(template.slots[0].items)
        }

        else -> template.slots.forEachIndexed { i, slot ->
            if (i > 0) append('⌟')
            appendOperand(slot)
        }
    }
}

/** A slot that is one number or one value needs no brackets around it; anything else does. */
private fun StringBuilder.appendOperand(slot: Expr) {
    if (isAtomic(slot.items)) {
        appendRun(slot.items)
    } else {
        append('(')
        appendRun(slot.items)
        append(')')
    }
}

private fun isAtomic(items: List<Item>): Boolean {
    if (items.isEmpty()) return true
    if (items.all { it.tokOrNull() in TokenKinds.digits || it.tokOrNull() == Tok.DECIMAL }) return true
    val only = items.singleOrNull() ?: return false
    return only is ConstantItem || only.tokOrNull() in TokenKinds.values
}
