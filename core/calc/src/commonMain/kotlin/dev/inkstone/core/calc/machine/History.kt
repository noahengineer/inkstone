package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.ComplexFormat
import dev.inkstone.core.calc.eval.Answer
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.InputBytes

/**
 * One remembered calculation: what was typed, what it gave, whether `SHIFT =` asked for a decimal,
 * and the complex form a closing `▶r∠θ`/`▶a+bi` asked for ([ComplexOverride]) — ▲ shows it again
 * exactly as `=` did.
 */
data class HistoryEntry(
    val expr: Expr,
    val answer: Answer,
    val decimal: Boolean = false,
    val complex: ComplexFormat? = null,
)

/**
 * The calculation history (research 02 §10.2, VERIFIED): "about 200 bytes of the newest
 * calculations", replayed with ▲/▼ on the result screen. Oldest first; adding past the budget
 * drops entries from the old end.
 *
 * ⚠️ GUESS: what an entry costs. The guide gives only the 200-byte total. Here an entry is its
 * input's bytes ([InputBytes]) plus [RESULT_BYTES] for the result, which is one 10-byte number
 * record (research 07 §4.1) — so 200 bytes holds about a dozen short sums, and one 99-byte line
 * and its result take half of it. The emulator settles it by counting how far ▲ goes back after
 * a run of `1+1=` (research 00 §5 Tier 2 #13).
 *
 * Not persisted: ON clears it (research 02 §10.2), and the calculator is switched on again by ON,
 * so nothing a power cycle could show survives one anyway.
 */
class History(private val capacity: Int = CAPACITY) {
    private val entries = ArrayDeque<HistoryEntry>()

    val size: Int get() = entries.size

    val lastIndex: Int get() = entries.size - 1

    fun isEmpty(): Boolean = entries.isEmpty()

    fun getOrNull(index: Int): HistoryEntry? = entries.getOrNull(index)

    fun add(entry: HistoryEntry) {
        entries.addLast(entry)
        while (entries.size > 1 && bytes() > capacity) entries.removeFirst()
    }

    fun clear() = entries.clear()

    /** What the entries occupy, by the cost rule above. */
    fun bytes(): Int = entries.sumOf { cost(it) }

    companion object {
        /** "About 200 bytes" (research 02 §10.2, VERIFIED). */
        const val CAPACITY = 200

        /** One 10-byte BCD number record (research 07 §4.1) per result — ⚠️ GUESS, see above. */
        const val RESULT_BYTES = 10

        fun cost(entry: HistoryEntry): Int = InputBytes.of(entry.expr) + RESULT_BYTES
    }
}
