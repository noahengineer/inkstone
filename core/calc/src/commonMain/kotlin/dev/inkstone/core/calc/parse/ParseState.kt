package dev.inkstone.core.calc.parse

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.input.Cursor
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.Item
import dev.inkstone.core.calc.input.PathStep
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.input.tokOrNull

/**
 * What one [parse] shares across every run it reads: the first error, the source map, and the two
 * stack depths.
 *
 * Errors are values here too: a parse function that fails records the error once (the first one
 * wins, which is where Goto must go) and returns null, and every caller passes the null up.
 *
 * @param complex CMPLX mode, where "each input value uses two levels of the numeric stack … the
 *   numeric stack effectively has only five levels" (research 02 [S570] p.E-67; INFERRED for the
 *   ZA, whose guide gives no stack sizes at all).
 */
internal class ParseContext(complex: Boolean = false) {
    val spans = SourceMap()

    var error: CalcError? = null
        private set

    var errorAt: Cursor = Cursor()
        private set

    private val levelsPerValue = if (complex) COMPLEX_LEVELS_PER_VALUE else 1
    private var numeric = 0
    private var commands = 0

    fun fail(error: CalcError, at: Cursor) {
        if (this.error == null) {
            this.error = error
            errorAt = at
        }
    }

    /**
     * Push [heldValues] values held while the next operand is read and [pendingCommands]
     * commands; false on overflow. The operand about to be read is on the numeric stack too —
     * [S570]'s illustration lists 2, 3, 4, 5 **and** the 4 being typed — so it is counted here,
     * which is only sound because every push is followed by reading at least one value.
     */
    fun push(heldValues: Int, pendingCommands: Int): Boolean {
        numeric += heldValues * levelsPerValue
        commands += pendingCommands
        return numeric + levelsPerValue <= NUMERIC_STACK && commands <= COMMAND_STACK
    }

    fun pop(heldValues: Int, pendingCommands: Int) {
        numeric -= heldValues * levelsPerValue
        commands -= pendingCommands
    }

    companion object {
        /**
         * Stack ERROR limits (research 02 §11: "the numeric or command stack capacity is exceeded";
         * sizes UNKNOWN for the ZA). ⚠️ GUESS from the ES family's guide (research 02 [S570]
         * p.E-66): "The numeric stack has 10 levels and the command stack has 24 levels", with an
         * illustration — `2×((3+4×(5+4)÷3)÷5)+8` peaks at five values and seven commands
         * (`× ( ( + × ( +`) — that this accounting reproduces: every value held while an operand
         * to its right is read, plus that operand, and every pending operator, `(`, function or
         * template slot. A power's base, a fraction's numerator and earlier arguments are held
         * the same way. The emulator settles the ZA by nesting `1+(` until it fails (research 02
         * §15 #8).
         */
        const val NUMERIC_STACK = 10
        const val COMMAND_STACK = 24

        private const val COMPLEX_LEVELS_PER_VALUE = 2
    }
}

/** A cursor over one run of items (the root, or one template slot at [path]). */
internal class ParseState(val items: List<Item>, val path: List<PathStep>, val ctx: ParseContext) {
    var pos = 0

    val atEnd: Boolean get() = pos >= items.size

    fun peek(): Item? = items.getOrNull(pos)

    fun peekTok(): Tok? = peek()?.tokOrNull()

    fun here(): Cursor = Cursor(path, pos)

    /** Note where [node] ended (now), and return it. */
    fun <T : Node> record(node: T): T {
        ctx.spans.add(node, here())
        return node
    }

    /** Record [error] at the current position; always null, for `return fail()`. */
    fun fail(error: CalcError = CalcError.SYNTAX): Node? {
        ctx.fail(error, here())
        return null
    }

    /** The run inside slot [slot] of the template at [index] of this run. */
    fun slot(index: Int, slot: Int, expr: Expr): ParseState = ParseState(expr.items, path + PathStep(index, slot), ctx)

    /**
     * Run [body], which reads an operand, while [heldValues] values and [pendingCommands] commands
     * wait on the stacks; Stack ERROR if they overflow (see [ParseContext.push]).
     */
    inline fun nested(heldValues: Int, pendingCommands: Int, body: () -> Node?): Node? {
        val fits = ctx.push(heldValues, pendingCommands)
        val result = if (fits) body() else fail(CalcError.STACK)
        ctx.pop(heldValues, pendingCommands)
        return result
    }
}
