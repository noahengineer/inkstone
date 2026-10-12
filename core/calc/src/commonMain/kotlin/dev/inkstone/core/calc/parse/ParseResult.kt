package dev.inkstone.core.calc.parse

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.input.Cursor

/** What [parse] returns. */
sealed interface ParseResult {
    /** A well-formed expression; [spans] tells the evaluator where each node ended. */
    data class Ok(val node: Node, val spans: SourceMap) : ParseResult

    /**
     * [error] is [CalcError.SYNTAX] or [CalcError.STACK]. [at] is where `[◀][▶]:Goto` puts the
     * cursor (research 02 §11): the gap just before the item the parser could not accept, or the
     * end of the run it ran out of.
     */
    data class Failure(val error: CalcError, val at: Cursor) : ParseResult
}

/**
 * Where each node of a parse ended in the input, so that an evaluation error can send the cursor
 * there as the calculator does: `14÷0×2` → Math ERROR → `▶` puts the cursor just after the `0`
 * (research 02 §11), which is the end of the `14÷0` node.
 *
 * Keyed by **identity** (`===`), because structurally equal nodes occur at different places
 * (`0+0`). An input is at most 99 bytes, so a linear search is all this needs.
 */
class SourceMap internal constructor() {
    private val nodes = mutableListOf<Node>()
    private val ends = mutableListOf<Cursor>()

    internal fun add(node: Node, end: Cursor) {
        nodes += node
        ends += end
    }

    /** The cursor just after [node]'s last item, or null if [node] is not from this parse. */
    fun endOf(node: Node): Cursor? {
        val i = nodes.indexOfFirst { it === node }
        return if (i < 0) null else ends[i]
    }

    val size: Int get() = nodes.size
}
