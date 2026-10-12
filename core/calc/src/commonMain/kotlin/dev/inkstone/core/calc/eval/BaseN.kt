package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.parse.BaseLit
import dev.inkstone.core.calc.parse.BinOp
import dev.inkstone.core.calc.parse.Binary
import dev.inkstone.core.calc.parse.Call
import dev.inkstone.core.calc.parse.Fn
import dev.inkstone.core.calc.parse.Negate
import dev.inkstone.core.calc.parse.Node
import dev.inkstone.core.calc.parse.NumberLit
import dev.inkstone.core.calc.parse.Radix
import dev.inkstone.core.calc.parse.Sym
import dev.inkstone.core.calc.parse.Symbolic
import dev.inkstone.core.calc.parse.Var
import dev.inkstone.core.calc.value.ComplexValue
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.Reals
import dev.inkstone.core.calc.value.Value

/**
 * BASE-N (research 03 §5, 06 §6.5): integers only, in **32-bit two's complement** — **16-bit** in
 * BIN — with a typed digit run read in the current base and `d`/`h`/`b`/`o` naming a base for one
 * literal. A separate small evaluator, because nothing else in COMP carries over: no fractions,
 * no exponents, no functions but `Not(` and `Neg(`, and `÷` truncates.
 *
 * - **Literals** (research 03 §5.2): BIN, OCT and HEX digits are the bit pattern, so `FFFFFFFF`
 *   is −1 and `1111111111110101` is −11; a DEC literal is the number itself and must be at most
 *   2147483647 (a negative one is typed with `(−)`). A prefixed literal has its own base's width
 *   (`b` 16 bits). A digit the base does not have (`2` in BIN, a letter outside HEX) is Syntax
 *   ERROR; a literal wider than its base is Math ERROR — both **GUESSES**.
 * - **Arithmetic** is on the signed value, so the bit operations are width-independent under
 *   sign extension: `Not(x)` = −x−1, `Neg(x)` = −x, `xnor` = `Not(xor)`. `÷` truncates toward
 *   zero ("a fractional part in a result is cut off", 03 §5.1); `÷0` is Math ERROR.
 * - **Range**: every intermediate value must fit the current base's width (−32768 … 32767 in BIN,
 *   the 32-bit range otherwise), else Math ERROR — research 03 §5.2 says what happens out of
 *   range is UNKNOWN and INFERS Math ERROR.
 * - A variable or Ans holding a non-integer (stored in COMP) is cut toward zero, like a result
 *   (a **GUESS**).
 */
internal class BaseN(private val session: Session) {
    private val radix: Radix = session.context.radix

    fun value(node: Node): Ev<Value> = eval(node).map { Reals.of(it) }

    private fun eval(node: Node): Ev<Long> = when (node) {
        is NumberLit -> literal(node)
        is BaseLit -> digits(node.digits, node.radix ?: radix, node).then { checked(it, node) }
        is Negate -> eval(node.operand).then { checked(-it, node) }
        is Binary -> binary(node)
        is Call -> call(node)
        is Var -> stored(session.memory.variables[node.variable], node)
        is Sym -> if (node.symbol == Symbolic.ANS) stored(session.memory.ans, node) else failAt(CalcError.SYNTAX, node)
        else -> failAt(CalcError.SYNTAX, node)
    }

    private fun literal(node: NumberLit): Ev<Long> {
        if (node.fracDigits != null || node.exponent != null) return failAt(CalcError.SYNTAX, node)
        return digits(node.intDigits, radix, node).then { checked(it, node) }
    }

    private fun binary(node: Binary): Ev<Long> {
        if (node.op !in OPERATORS) return failAt(CalcError.SYNTAX, node)
        val a = eval(node.left).orElse { return it }
        val b = eval(node.right).orElse { return it }
        if (node.op == BinOp.DIV && b == 0L) return failAt(CalcError.MATH, node)
        return checked(apply(node.op, a, b), node)
    }

    private fun apply(op: BinOp, a: Long, b: Long): Long = when (op) {
        BinOp.ADD -> a + b
        BinOp.SUB -> a - b
        BinOp.DIV -> a / b
        BinOp.AND -> a and b
        BinOp.OR -> a or b
        BinOp.XOR -> a xor b
        BinOp.XNOR -> (a xor b).inv()
        else -> a * b
    }

    private fun call(node: Call): Ev<Long> {
        if (node.fn != Fn.NOT && node.fn != Fn.NEG) return failAt(CalcError.SYNTAX, node)
        val x = eval(node.args[0]).orElse { return it }
        return checked(if (node.fn == Fn.NOT) x.inv() else -x, node)
    }

    private fun stored(value: Value?, node: Node): Ev<Long> {
        val real = when (value) {
            is Real -> value
            is ComplexValue -> value.re
            else -> return failAt(CalcError.SYNTAX, node)
        }
        val whole = real.dec.truncate().toLongOrNull() ?: return failAt(CalcError.MATH, node)
        return checked(whole, node)
    }

    /** [text] read in [base] as the bit pattern of that base's width (see the class KDoc). */
    private fun digits(text: String, base: Radix, node: Node): Ev<Long> {
        val b = BASES.getValue(base)
        var unsigned = 0L
        for (c in text) {
            val d = c.digitToIntOrNull(HEX) ?: return failAt(CalcError.SYNTAX, node)
            if (d >= b) return failAt(CalcError.SYNTAX, node)
            unsigned = unsigned * b + d
            if (unsigned > WORD) return failAt(CalcError.MATH, node)
        }
        return signed(unsigned, base)?.let { Ev.Ok(it) } ?: failAt(CalcError.MATH, node)
    }

    private fun signed(unsigned: Long, base: Radix): Long? {
        if (base == Radix.DEC) return unsigned.takeIf { it <= Int.MAX_VALUE }
        val limit = if (base == Radix.BIN) HALF_WORD_LIMIT else WORD_LIMIT
        return when {
            unsigned >= limit -> null
            unsigned >= limit / 2 -> unsigned - limit
            else -> unsigned
        }
    }

    private fun checked(value: Long, node: Node): Ev<Long> {
        val bound = if (radix == Radix.BIN) HALF_WORD_LIMIT / 2 else WORD_LIMIT / 2
        return if (value >= -bound && value < bound) Ev.Ok(value) else failAt(CalcError.MATH, node)
    }

    private companion object {
        const val HEX = 16
        const val WORD_LIMIT = 1L shl 32
        const val HALF_WORD_LIMIT = 1L shl 16
        const val WORD = WORD_LIMIT - 1

        val BASES: Map<Radix, Int> = mapOf(Radix.DEC to 10, Radix.HEX to HEX, Radix.BIN to 2, Radix.OCT to 8)

        val OPERATORS: Set<BinOp> = setOf(
            BinOp.ADD, BinOp.SUB, BinOp.MUL, BinOp.IMPLICIT_MUL, BinOp.DIV,
            BinOp.AND, BinOp.OR, BinOp.XOR, BinOp.XNOR,
        )
    }
}
