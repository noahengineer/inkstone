package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.ComplexFormat
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.parse.Equation
import dev.inkstone.core.calc.parse.Node
import dev.inkstone.core.calc.parse.Postfix
import dev.inkstone.core.calc.parse.PostfixOp

/**
 * CMPLX's `▶r∠θ` and `▶a+bi` "go at the end of an expression and override the setup for that one
 * result" (research 03 §3, VERIFIED; 05 EX-CMPLX-09/10, 04 E-48 `A+Bi▶r∠θ` → `2∠30` under CALC).
 * They do not change the value — the evaluator hands back the same number either way — so which
 * form was asked for can only be read off the statement that ran, and that is the machine's to do.
 *
 * The override travels with the result (a [CalcView.Result], a [HistoryEntry], a CALC answer)
 * and every formatter call for that result is given [Host.setupFor] instead of SETUP itself, so a
 * later SETUP change (Fix, the angle unit) redraws it in the new format and keeps the form asked
 * for. A command on the right of a CALC assignment (`A=B+Ci▶r∠θ`) applies to the value assigned,
 * which is the one shown.
 */
internal object ComplexOverride {
    /** The complex format [statement] asks for, or null for SETUP's own. */
    fun of(statement: Node): ComplexFormat? = when (statement) {
        is Postfix -> FORMATS[statement.op]
        is Equation -> statement.rhs?.let(::of)
        else -> null
    }

    private val FORMATS: Map<PostfixOp, ComplexFormat> = mapOf(
        PostfixOp.TO_POLAR to ComplexFormat.POLAR,
        PostfixOp.TO_RECT to ComplexFormat.RECTANGULAR,
    )
}

/** SETUP as a result that asked for [complex] is drawn ([ComplexOverride]); SETUP itself for null. */
internal fun Host.setupFor(complex: ComplexFormat?): Setup =
    if (complex == null || complex == setup.complex) setup else setup.copy(complex = complex)
