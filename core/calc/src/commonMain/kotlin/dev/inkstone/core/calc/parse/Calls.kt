package dev.inkstone.core.calc.parse

import dev.inkstone.core.calc.input.ConstantItem
import dev.inkstone.core.calc.input.ConversionItem
import dev.inkstone.core.calc.input.ScientificConstants
import dev.inkstone.core.calc.input.Template
import dev.inkstone.core.calc.input.TemplateKind
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.input.TokenItem

/*
 * Priority levels 2 and 1 of research 02 §12: functions that take an argument list closed by `)`,
 * and parenthesised expressions — plus the operands that need no operator at all (literals,
 * variables, constants) and the Natural templates, each of which is self-delimiting.
 */

/** A value: a literal, a name, `(…)`, a function call or a template. */
internal fun ParseState.parsePrimary(): Node? = when (val item = peek()) {
    null -> fail()
    is Template -> template(item)
    is ConstantItem -> constant(item)
    is ConversionItem -> fail()
    is TokenItem -> tokenPrimary(item.tok)
}

private fun ParseState.tokenPrimary(tok: Tok): Node? {
    val value = Tables.value(tok)
    val fn = Tables.functions[tok]
    return when {
        value != null -> {
            pos++
            record(value)
        }

        tok in LITERAL_STARTS -> literal()

        tok == Tok.OPEN -> group()

        fn != null -> call(fn)

        tok in Tables.calculus -> calculus(tok)

        else -> fail()
    }
}

private fun ParseState.literal(): Node? {
    val scanned = scanNumber(items, pos)
    pos = scanned.end
    val node = scanned.node ?: return fail()
    return record(node)
}

/** A scientific constant; a number outside 01…40 is a Syntax ERROR (as for CONV, research 02 §10.16). */
private fun ParseState.constant(item: ConstantItem): Node? {
    if (ScientificConstants.get(item.number) == null) return fail()
    pos++
    return record(Const(item.number))
}

/** `( … )`; the `)` may be omitted at the end of the run (research 02 §7.1). */
private fun ParseState.group(): Node? {
    pos++
    val inner = nested(0, 1) { parseExpression() } ?: return null
    return if (closeOrEnd()) inner else null
}

private fun ParseState.call(fn: Fn): Node? {
    pos++
    val args = arguments() ?: return null
    return if (fn.accepts(args.size)) record(Call(fn, args)) else fail()
}

/**
 * `∫(f;a;b[;tol])`, `d/dx(f;a[;tol])`, `Σ(f;a;b)` (research 02 §10.7–10.9). The optional `tol` is
 * a LineIO-only argument; the Natural templates have no slot for it.
 */
private fun ParseState.calculus(tok: Tok): Node? {
    pos++
    val a = arguments() ?: return null
    val node = when {
        tok == Tok.INTEGRAL_FN && a.size in INTEGRAL_ARGS -> Integral(a[0], a[1], a[2], a.getOrNull(LIMITS))
        tok == Tok.DERIVATIVE_FN && a.size in DERIVATIVE_ARGS -> Derivative(a[0], a[1], a.getOrNull(2))
        tok == Tok.SUM_FN && a.size == LIMITS -> Summation(a[0], a[1], a[2])
        else -> null
    }
    return if (node == null) fail() else record(node)
}

/** f, lower, upper: the three arguments ∫ and Σ always have. */
private const val LIMITS = 3
private val INTEGRAL_ARGS = LIMITS..LIMITS + 1
private val DERIVATIVE_ARGS = 2..LIMITS

/**
 * After a function's opening token: arguments separated by `;`, then `)` — which may be omitted
 * at the end of the run, so `sin 30 + 15 =` takes `30+15` as the argument (research 02 §7.1,
 * VERIFIED: 0,7071067812). Earlier arguments wait on the numeric stack while later ones are
 * read, as do [held] values from outside the call (the base of LineIO `^(`).
 */
internal fun ParseState.arguments(held: Int = 0): List<Node>? {
    val args = mutableListOf<Node>()
    do {
        if (args.isNotEmpty()) pos++
        args += nested(held + args.size, 1) { parseExpression() } ?: return null
    } while (peekTok() == Tok.SEMICOLON)
    return if (closeOrEnd()) args else null
}

/** Consume a `)`, or accept its omission at the end of the run; anything else is a Syntax ERROR. */
private fun ParseState.closeOrEnd(): Boolean {
    if (peekTok() == Tok.CLOSE) {
        pos++
        return true
    }
    if (atEnd) return true
    fail()
    return false
}

/** A Natural template in operand position (x^□ is postfix and cannot start a value). */
private fun ParseState.template(t: Template): Node? {
    if (t.kind == TemplateKind.POWER || t.kind == TemplateKind.SCI_EXPONENT) return fail()
    val slots = templateSlots(t) ?: return null
    return Tables.fromTemplate(t.kind, slots)?.let { record(it) } ?: fail()
}

/**
 * Parse every slot of the template at [pos] as a complete expression, then step past it. A slot
 * is a run of its own, so a `)` omitted inside it closes at the slot's end, and an empty slot is a
 * Syntax ERROR whose Goto lands inside that slot. Earlier slots wait on the numeric stack while
 * later ones are read, exactly as a function's LineIO arguments do ([arguments]), so `log▪▯`,
 * √, x^□, ∫ … reach Stack ERROR at the same depth as `log(a;b`, `√(`, `^(`, `∫(`. (A fraction or
 * ⁿ√ template spends one command more than `a⌟b` or `nˣ√(`, whose first operand needs no
 * opener; the firmware's accounting of templates is unknown either way.) [held] counts a value
 * from outside the template: the base of x^□.
 */
internal fun ParseState.templateSlots(t: Template, held: Int = 0): List<Node>? {
    val index = pos
    val nodes = ArrayList<Node>(t.slots.size)
    for ((i, slot) in t.slots.withIndex()) {
        nodes += nested(held + i, 1) { slot(index, i, slot).parseWhole() } ?: return null
    }
    pos++
    return nodes
}

/** A complete run (a template slot): one expression and nothing after it. */
private fun ParseState.parseWhole(): Node? {
    val node = parseExpression() ?: return null
    return if (atEnd) node else fail()
}
