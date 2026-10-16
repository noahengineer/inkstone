package dev.inkstone.app.handwriting

import dev.inkstone.core.model.PenTrajectory
import kotlin.math.ceil
import kotlin.math.hypot

/** One pen sample, in page points, ms from the prompt's first touch. */
internal class Sample(val x: Float, val y: Float, val t: Long, val pressure: Float, val tilt: Float)

/**
 * What has been written for one quick-start prompt, with undo and redo.
 *
 * ⭐ **Every edit is a set of strokes shown or hidden, never a change to a stroke.** Writing shows a
 * new stroke, erasing hides the strokes the eraser touched, and *Clear* hides everything; undo shows
 * or hides the same strokes again. Erasing is therefore whole-stroke, which is right for this surface
 * and not for a note: what is written here is a *sample* of the hand, and half a stroke is a stroke
 * the hand never made. It also keeps every sample's time untouched, so the trajectory a prompt saves
 * is exactly what the pen did, minus what the writer took back.
 *
 * The edits are absolute (shown, hidden), not toggles, for CLAUDE.md rule 8's reason: an inverse that
 * flips a flag is wrong the moment two edits touch one stroke.
 */
internal class PromptInk {
    private val strokes = ArrayList<List<Sample>>()
    private val shown = ArrayList<Boolean>()
    private val undos = ArrayList<Edit>()
    private val redos = ArrayList<Edit>()
    private val erasing = LinkedHashSet<Int>()

    /** One gesture's edit: [indices] went to [show]. */
    private class Edit(val indices: IntArray, val show: Boolean)

    /** The strokes on the area now, in the order they were drawn. */
    val visible: List<List<Sample>> get() = strokes.filterIndexed { i, _ -> shown[i] }

    val isEmpty: Boolean get() = shown.none { it }
    val canUndo: Boolean get() = undos.isNotEmpty()
    val canRedo: Boolean get() = redos.isNotEmpty()

    /** A finished stroke. An empty one is nothing, and records nothing. */
    fun add(stroke: List<Sample>) {
        if (stroke.isEmpty()) return
        strokes += stroke
        shown += true
        commit(Edit(intArrayOf(strokes.lastIndex), show = true))
    }

    /**
     * The eraser moved from ([fromX], [fromY]) to ([x], [y]): every shown stroke within [radius] of
     * that path is hidden at once, and the whole gesture becomes one undo at [endErase]. The path is
     * walked in steps of half the radius, so a quick swipe cannot jump over a thin stroke.
     *
     * @return whether anything was hidden, so the caller knows to redraw.
     */
    fun eraseAlong(fromX: Float, fromY: Float, x: Float, y: Float, radius: Float): Boolean {
        val length = hypot(x - fromX, y - fromY)
        val steps = maxOf(1, ceil(length / (radius / 2f)).toInt())
        var hid = false
        for (s in 0..steps) {
            val px = fromX + (x - fromX) * s / steps
            val py = fromY + (y - fromY) * s / steps
            for (i in strokes.indices) {
                if (shown[i] && touches(strokes[i], px, py, radius)) {
                    shown[i] = false
                    erasing += i
                    hid = true
                }
            }
        }
        return hid
    }

    /** The eraser lifted: what it hid is one edit, undone in one go. */
    fun endErase() {
        if (erasing.isEmpty()) return
        commit(Edit(erasing.toIntArray(), show = false))
        erasing.clear()
    }

    /** *Clear*: hides everything, as one edit, so a clear by mistake is one undo away. */
    fun clear() {
        val all = shown.indices.filter { shown[it] }
        if (all.isEmpty()) return
        all.forEach { shown[it] = false }
        commit(Edit(all.toIntArray(), show = false))
    }

    fun undo(): Boolean {
        val edit = undos.removeLastOrNull() ?: return false
        edit.indices.forEach { shown[it] = !edit.show }
        redos += edit
        return true
    }

    fun redo(): Boolean {
        val edit = redos.removeLastOrNull() ?: return false
        edit.indices.forEach { shown[it] = edit.show }
        undos += edit
        return true
    }

    /** A new prompt: nothing written, nothing to undo. */
    fun reset() {
        strokes.clear()
        shown.clear()
        undos.clear()
        redos.clear()
        erasing.clear()
    }

    /**
     * The shown strokes on one clock that starts at zero, or null when nothing is shown. Hidden
     * strokes leave their time behind as a longer pen-up, which is what the pen really did.
     */
    fun trajectory(): PenTrajectory? {
        val kept = visible
        if (kept.isEmpty()) return null
        val origin = kept.first().first().t
        val builder = PenTrajectory.Builder(kept.sumOf { it.size }, hasPressure = true, hasTilt = true)
        var last = 0
        for (stroke in kept) {
            for (sample in stroke) {
                // Never backwards: events of one gesture share a clock, but a coalesced sample can repeat.
                last = maxOf(last, (sample.t - origin).toInt())
                builder.add(sample.x, sample.y, last, sample.pressure, sample.tilt)
            }
            builder.endStroke()
        }
        return builder.build()
    }

    /** A new edit forgets what could have been redone, exactly as the note editor's undo does. */
    private fun commit(edit: Edit) {
        undos += edit
        redos.clear()
    }

    private fun touches(stroke: List<Sample>, x: Float, y: Float, radius: Float): Boolean {
        if (stroke.size == 1) return hypot(stroke[0].x - x, stroke[0].y - y) <= radius
        for (k in 1 until stroke.size) {
            if (distanceToSegment(x, y, stroke[k - 1], stroke[k]) <= radius) return true
        }
        return false
    }

    private fun distanceToSegment(x: Float, y: Float, a: Sample, b: Sample): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0f) return hypot(x - a.x, y - a.y)
        val along = (((x - a.x) * dx + (y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(x - (a.x + along * dx), y - (a.y + along * dy))
    }
}
