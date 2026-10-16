package dev.inkstone.android.ink

/**
 * What [InkSurfaceView] knew about a stroke at its down event, kept **per stroke** until ink reports
 * the stroke finished.
 *
 * ⚠️ **One "the live stroke's" field is not enough.** The surface stops calling a stroke live when
 * the pen lifts. Ink reports it finished later, once its wet layer has handed it over. A quick next
 * stroke can begin in between, and ink sometimes reports two strokes in one call. With single fields,
 * the earlier stroke was:
 * - stored with the later stroke's pen kind, so a pen line could come back as a highlighter;
 * - handed to M12's timeline and the handwriting ledger with the later stroke's start time and ruler
 *   flag.
 *
 * The field's KDoc said one was enough *"because `beginStroke` returns early while a stroke is
 * already live"*. That is true of *live* and says nothing about *finished*. Found by the handwriting
 * walk on the emulator: three quick strokes, and two of them in the ledger at the same millisecond
 * (docs/HANDWRITING-SYNTHESIS.md §16).
 *
 * A fixed ring of [capacity] slots, so a down event allocates nothing (CLAUDE.md rule 7). A stroke
 * that never finishes leaves its slot to be overwritten: one that was cancelled, or one that snapped
 * into a shape. More than [capacity] strokes begun before the oldest one finishes is not something a
 * hand does. If it ever happened, the cost would be one stroke reported without its start.
 *
 * @param K the pen kind. Generic only so this can be tested without the ink stack.
 */
internal class StrokeStarts<K : Any>(capacity: Int = DEFAULT_CAPACITY) {

    private val ids = arrayOfNulls<Any>(capacity)
    private val startedAt = LongArray(capacity)
    private val ruled = BooleanArray(capacity)
    private val kinds = arrayOfNulls<Any>(capacity)
    private var next = 0

    /** Remember [id]'s start, overwriting the oldest slot. */
    fun put(id: Any, atElapsed: Long, isRuled: Boolean, kind: K) {
        ids[next] = id
        startedAt[next] = atElapsed
        ruled[next] = isRuled
        kinds[next] = kind
        next = (next + 1) % ids.size
    }

    /** The slot holding [id], or -1 when it was never put, has been taken, or was overwritten. */
    fun find(id: Any): Int = ids.indexOfFirst { it == id }

    fun startedAt(slot: Int): Long = startedAt[slot]

    fun ruled(slot: Int): Boolean = ruled[slot]

    @Suppress("UNCHECKED_CAST")
    fun kind(slot: Int): K = kinds[slot] as K

    /** Forget [slot], so the stroke cannot be found again. */
    fun clear(slot: Int) {
        ids[slot] = null
        kinds[slot] = null
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 16
    }
}
