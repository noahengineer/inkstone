package dev.inkstone.core.handwriting

import dev.inkstone.core.model.GeneratedInk
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.PenTrajectory
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * One run of a maths expression, written and read back by the ladder, **normalised**: x-heights, y
 * up from the baseline, the slant removed, its ink starting at x = 0.
 */
internal class MathPiece(val ink: PenTrajectory, val backend: Char, val model: String)

/**
 * The hand's measurements a layout needs, in x-heights and milliseconds.
 *
 * @property letterGap between two runs typed with nothing between them (`2` and a fraction).
 * @property wordGap where a space was typed.
 * @property penUpMs the hand's pause between strokes, for the composite's one clock.
 */
internal class MathHand(val letterGap: Float, val wordGap: Float, val penUpMs: Int) {
    companion object {
        private const val MIN_LETTER_GAP = 0.1f
        private const val MAX_LETTER_GAP = 0.45f
        private const val MIN_WORD_GAP = 0.4f
        private const val MAX_WORD_GAP = 1.5f

        fun of(stats: HandStats, wordGapXHeights: Float?): MathHand = MathHand(
            letterGap = median(stats.letterGapsFor("x", "x")).coerceIn(MIN_LETTER_GAP, MAX_LETTER_GAP),
            wordGap = (wordGapXHeights ?: median(stats.wordGapsOrDefault())).coerceIn(MIN_WORD_GAP, MAX_WORD_GAP),
            penUpMs = stats.penUpMs,
        )
    }
}

/** A maths expression as one word: its ink (normalised), its width, and each stroke's `hw` backend and model. */
internal class MathInk(val ink: PenTrajectory, val width: Float, val strokeTags: List<Pair<Char, String>>)

/**
 * ⭐ The box layout — docs/HANDWRITING-MATHS.md §3. Every run of the expression was written by the
 * ladder and read back on its own ([MathPiece]); this places them in two dimensions and draws what no
 * recogniser can read: the fraction bar, the radical and its bar, and brackets as tall as what they
 * hold. It works in the **normalised** frame, so the caller puts the whole expression back on the page
 * once, and the bar, the radical and the brackets lean with the hand's slant like everything else.
 *
 * Boxes are measured by their **ink**, not by a font's metrics: a numerator with a descender sits higher
 * over the bar, as it does on paper.
 *
 * The structural marks come from the user's own verified single-letter copies where there are any —
 * a `-` stretched into the bar, a `√` carried on over its radicand, a `(` stretched to height — and are
 * drawn in plain geometry otherwise ([glyphs] answers null). Never by the network: a lone bracket is
 * nothing a recogniser can check, and a stretched one is geometry whoever drew the original.
 *
 * Structural strokes are tagged `a` (assembly, no model). `hw` has two backends and a reader refuses a
 * third ([GeneratedInk.decode]), so a new letter would make the strokes look unmarked to every build,
 * and unmarked ink is what the learner learns from (D2).
 */
internal class MathLayout(
    private val hand: MathHand,
    private val glyphs: (Char) -> PenTrajectory?,
    private val rng: Random,
) {
    /** A stroke group placed in a box's own coordinates; [drawn] strokes are timed at the hand's speed. */
    private class Part(val ink: PenTrajectory, val backend: Char, val model: String, val drawn: Boolean) {
        fun moved(dx: Float, dy: Float): Part =
            ink.transformed(Matrix3.translate(dx, dy))?.let { Part(it, backend, model, drawn) } ?: this
    }

    /** A laid-out box: its origin on the baseline at its left; [ascent] up and [descent] down, by ink. */
    private class Box(val width: Float, val ascent: Float, val descent: Float, val parts: List<Part>) {
        fun moved(dx: Float, dy: Float): List<Part> = parts.map { it.moved(dx, dy) }
    }

    private var pieces: Iterator<MathPiece> = emptyList<MathPiece>().iterator()
    private var speed = DEFAULT_SPEED

    /**
     * [row] laid out from [written], which holds one piece per [MathNotation.texts] entry, in that
     * order. Null when a piece is missing or the composite would not be a valid trajectory.
     */
    fun compose(row: MathRow, written: List<MathPiece>): MathInk? {
        if (written.size != MathNotation.texts(row).size) return null
        speed = handSpeed(written)
        pieces = written.iterator()
        val box = rowBox(row, 1f) ?: return null
        val ink = assemble(box.parts) ?: return null
        return MathInk(ink.first, box.width, ink.second)
    }

    // --- Boxes ----------------------------------------------------------------------------------------

    private fun box(node: MathNode, scale: Float): Box? = when (node) {
        is MathText -> textBox(scale)

        MathSpace -> Box(hand.wordGap * scale, 0f, 0f, emptyList())

        is MathRow -> rowBox(node, scale)

        is MathFraction -> fractionBox(node, scale)

        is MathRoot -> rootBox(node, scale)

        is MathFence -> fenceBox(node, scale)

        // A script is placed against what precedes it, in [rowBox].
        is MathScript -> null
    }

    private fun textBox(scale: Float): Box? {
        if (!pieces.hasNext()) return null
        val piece = pieces.next()
        val ink = scaled(piece.ink, scale) ?: return null
        val e = extent(ink)
        val placed = ink.transformed(Matrix3.translate(-e.minX, 0f)) ?: return null
        return Box(e.width, e.maxY, -e.minY, listOf(Part(placed, piece.backend, piece.model, drawn = false)))
    }

    private fun rowBox(row: MathRow, scale: Float): Box? {
        val parts = ArrayList<Part>()
        var x = 0f
        var ascent = 0f
        var descent = 0f
        var previous: Box? = null
        var spaced = false
        for (node in row.items) {
            if (node == MathSpace) {
                spaced = true
            } else {
                val box = when (node) {
                    is MathScript -> scriptBox(node, previous, scale)
                    else -> box(node, scale)
                } ?: return null
                val at = x + gapBefore(node, previous, spaced, scale)
                parts += box.moved(at, 0f)
                x = at + box.width
                ascent = max(ascent, box.ascent)
                descent = max(descent, box.descent)
                previous = if (node is MathScript) withScript(previous, box) else box
                spaced = false
            }
        }
        return Box(x, ascent, descent, parts)
    }

    /** A script hugs what it follows; anything else keeps the hand's own gap, or its word gap after a space. */
    private fun gapBefore(node: MathNode, previous: Box?, spaced: Boolean, scale: Float): Float = when {
        node is MathScript -> SCRIPT_GAP * scale
        previous == null -> 0f
        spaced -> hand.wordGap * scale
        else -> hand.letterGap * scale
    }

    /** What a script that follows a scripted base is raised against: the two together. */
    private fun withScript(base: Box?, script: Box): Box = Box(
        width = script.width,
        ascent = max(base?.ascent ?: 0f, script.ascent),
        descent = max(base?.descent ?: 0f, script.descent),
        parts = emptyList(),
    )

    /**
     * Raised and lowered against what precedes it: a superscript's baseline [SUP_DROP] below the base's
     * top (never lower than [SUP_MIN]), so `b²` sits higher than `x²`, as a hand puts it; a subscript
     * dropped below the baseline, further under a descender.
     */
    private fun scriptBox(script: MathScript, base: Box?, scale: Float): Box? {
        val small = max(scale * SCRIPT_SCALE, MIN_SCALE)
        val baseTop = base?.ascent ?: scale
        val baseBottom = base?.descent ?: 0f
        val parts = ArrayList<Part>()
        var width = 0f
        var ascent = 0f
        var descent = 0f
        script.sup?.let { row ->
            val sup = rowBox(row, small) ?: return null
            val lift = max(baseTop - SUP_DROP * scale, SUP_MIN * scale)
            parts += sup.moved(0f, lift)
            width = max(width, sup.width)
            ascent = max(ascent, lift + sup.ascent)
        }
        script.sub?.let { row ->
            val sub = rowBox(row, small) ?: return null
            val drop = max(SUB_DROP * scale, baseBottom * SUB_UNDER_DESCENDER)
            parts += sub.moved(0f, -drop)
            width = max(width, sub.width)
            descent = max(descent, drop + sub.descent)
        }
        return Box(width, ascent, descent, parts)
    }

    /** Numerator over a bar on the maths axis over denominator, each centred on the bar. */
    private fun fractionBox(fraction: MathFraction, scale: Float): Box? {
        val part = max(scale * FRACTION_SCALE, MIN_SCALE)
        val numerator = rowBox(fraction.numerator, part) ?: return null
        val denominator = rowBox(fraction.denominator, part) ?: return null
        val side = FRACTION_SIDE * scale
        val bar = max(numerator.width, denominator.width) + 2 * BAR_OVERHANG * scale
        val axis = AXIS * scale
        val gap = BAR_GAP * scale
        val numeratorBaseline = axis + gap + numerator.descent
        val denominatorBaseline = axis - gap - denominator.ascent
        val parts = ArrayList<Part>()
        parts += numerator.moved(side + (bar - numerator.width) / 2, numeratorBaseline)
        barStroke(side, side + bar, axis)?.let { parts += it }
        parts += denominator.moved(side + (bar - denominator.width) / 2, denominatorBaseline)
        return Box(
            width = bar + 2 * side,
            ascent = numeratorBaseline + numerator.ascent,
            descent = -(denominatorBaseline - denominator.descent),
            parts = parts,
        )
    }

    /**
     * The radical: a short tick, down to the bottom, up past the radicand's top, and on over it in one
     * stroke. An index (`3` in a cube root) sits small over the tick.
     */
    private fun rootBox(root: MathRoot, scale: Float): Box? {
        // ⚠️ The index first: pieces are read in [MathNotation.texts]' order, which is writing order.
        // The radicand first put `27` in the notch of `∛27` and `3` under the bar.
        val index = root.index?.let { rowBox(it, max(scale * INDEX_SCALE, MIN_SCALE)) ?: return null }
        val radicand = rowBox(root.radicand, scale) ?: return null
        val top = max(radicand.ascent, scale) + ROOT_CLEAR * scale
        val bottom = -max(radicand.descent, 0f) - ROOT_BELOW * scale
        val sign = radicalSign(bottom, top, scale) ?: return null
        // The radicand starts just past the rising stroke; the bar runs a little past its end.
        val radicandAt = sign.rise + ROOT_GAP * scale
        val barEnd = radicandAt + radicand.width + ROOT_OVERRUN * scale
        val stroke = withBar(sign.ink, sign.rise, top, barEnd) ?: return null
        // The index over the tick: its right edge at the bottom of the vee, its foot above the tick.
        val indexRight = sign.notch
        val indexLeft = index?.let { indexRight - it.width } ?: 0f
        val shift = max(0f, -indexLeft)
        val indexBaseline = bottom + (top - bottom) * INDEX_FOOT + (index?.descent ?: 0f)
        val parts = ArrayList<Part>()
        index?.let { parts += it.moved(indexLeft + shift, indexBaseline) }
        parts += Part(stroke, GeneratedInk.ASSEMBLY, GeneratedInk.NO_MODEL, drawn = true).moved(shift, 0f)
        parts += radicand.moved(radicandAt + shift, 0f)
        return Box(
            width = barEnd + shift,
            ascent = max(top, index?.let { indexBaseline + it.ascent } ?: top),
            descent = max(radicand.descent, -bottom),
            parts = parts,
        )
    }

    /** Brackets or bars, at least a line tall, stretched to cover what they hold. */
    private fun fenceBox(fence: MathFence, scale: Float): Box? {
        val content = rowBox(fence.content, scale) ?: return null
        val top = max(content.ascent, FENCE_MIN_TOP * scale) + FENCE_PAD * scale
        val bottom = -max(content.descent, FENCE_MIN_DROP * scale) - FENCE_PAD * scale
        val open = fenceMark(fence.open, 0f, bottom, top, scale) ?: return null
        val contentAt = open.second + FENCE_GAP * scale
        val closeAt = contentAt + content.width + FENCE_GAP * scale
        val close = fenceMark(fence.close, closeAt, bottom, top, scale) ?: return null
        val parts = ArrayList<Part>()
        parts += open.first
        parts += content.moved(contentAt, 0f)
        parts += close.first
        return Box(close.second, top, -bottom, parts)
    }

    // --- Structural marks -----------------------------------------------------------------------------

    private class Sign(val ink: PenTrajectory, val rise: Float, val notch: Float)

    /** The user's own `√` fitted between [bottom] and [top], or a drawn one in the same proportions. */
    private fun radicalSign(bottom: Float, top: Float, scale: Float): Sign? {
        val height = top - bottom
        glyphs('√')?.let { glyph ->
            val e = extent(glyph)
            if (e.height > MIN_GLYPH && e.width > MIN_GLYPH) {
                val sy = height / e.height
                // Taller without growing as wide: a radical over a fraction is a long rising stroke.
                val sx = sy.coerceIn(ROOT_WIDTH_MIN, ROOT_WIDTH_MAX)
                val fit = Matrix3.translate(-e.minX * sx, bottom - e.minY * sy) * Matrix3.scale(sx, sy)
                val fitted = glyph.transformed(fit)
                if (fitted != null) {
                    val f = extent(fitted)
                    return Sign(fitted, f.maxX, lowestX(fitted))
                }
            }
        }
        val tickX = TICK_WIDTH * scale
        val notchX = NOTCH_X * scale
        val riseX = notchX + RISE_WIDTH * scale + RISE_PER_HEIGHT * height
        val points = listOf(
            0f to bottom + height * TICK_START,
            tickX to bottom + height * TICK_TOP,
            notchX to bottom,
            riseX to top,
        )
        return Sign(polyline(listOf(points)) ?: return null, riseX, notchX)
    }

    /** The radical's last stroke carried on along [top] to [barEnd]: one pen movement, as written. */
    private fun withBar(sign: PenTrajectory, rise: Float, top: Float, barEnd: Float): PenTrajectory? {
        val strokes = (0 until sign.strokeCount).map { k ->
            (sign.strokeStart(k) until sign.strokeEnd(k)).map { sign.x[it] to sign.y[it] }
        }.toMutableList()
        val last = strokes.last()
        val end = last.last()
        val reachesTop = end.second >= top - (top - strokes.flatten().minOf { it.second }) * BAR_JOIN
        val bar = densify(listOf((if (reachesTop) end.first else rise) to top, barEnd to top + BAR_LIFT))
        if (reachesTop) strokes[strokes.lastIndex] = last + bar.drop(1) else strokes += bar
        return polyline(strokes, dense = true)
    }

    /** The bar: the user's own `-` stretched along it with its wobble kept small, or a plain line. */
    private fun barStroke(left: Float, right: Float, axis: Float): Part? {
        val dash = glyphs('-')?.takeIf { extent(it).width > MIN_GLYPH }
        val ink = if (dash != null) {
            val e = extent(dash)
            val first = dash.y[0]
            val last = dash.y[dash.pointCount - 1]
            val points = (0 until dash.pointCount).map { i ->
                val t = (dash.x[i] - e.minX) / e.width
                val trend = first + (last - first) * t
                (left + t * (right - left)) to axis + (dash.y[i] - trend).coerceIn(-MAX_WOBBLE, MAX_WOBBLE)
            }
            // Stretched, its samples are far apart: resample at the drawn marks' spacing.
            polyline(listOf(points.sortedBy { it.first }))
        } else {
            val sag = rng.nextFloat() * MAX_SAG
            val steps = max(2, ceil((right - left) / SPACING).toInt())
            val line = (0..steps).map { k ->
                val t = k.toFloat() / steps
                (left + t * (right - left)) to axis - sag * sin(PI.toFloat() * t)
            }
            polyline(listOf(line), dense = true)
        } ?: return null
        return Part(ink, GeneratedInk.ASSEMBLY, GeneratedInk.NO_MODEL, drawn = true)
    }

    /** One bracket or bar from [left], covering [bottom]..[top]; and where its ink ends. */
    private fun fenceMark(mark: Char, left: Float, bottom: Float, top: Float, scale: Float): Pair<Part, Float>? {
        val height = top - bottom
        val own = glyphs(mark)?.let { glyph ->
            val e = extent(glyph)
            if (e.height <= MIN_GLYPH) return@let null
            val sy = height / e.height
            // A bracket grows taller far faster than it grows wider.
            val sx = if (sy <= 1f) sy else 1f + (sy - 1f) * FENCE_WIDEN
            glyph.transformed(Matrix3.translate(left - e.minX * sx, bottom - e.minY * sy) * Matrix3.scale(sx, sy))
        }
        val ink = own ?: drawnFence(mark, left, bottom, top, scale) ?: return null
        return Part(ink, GeneratedInk.ASSEMBLY, GeneratedInk.NO_MODEL, drawn = true) to extent(ink).maxX
    }

    private fun drawnFence(mark: Char, left: Float, bottom: Float, top: Float, scale: Float): PenTrajectory? {
        val height = top - bottom
        val jitter = 1f + (rng.nextFloat() - HALF) * FENCE_JITTER
        val w = (FENCE_WIDTH + FENCE_WIDTH_PER_HEIGHT * height) * scale * jitter
        val steps = max(MIN_STEPS, ceil(height / SPACING).toInt())
        fun along(f: (Float) -> Pair<Float, Float>) = (0..steps).map { k -> f(k.toFloat() / steps) }
        val points: List<Pair<Float, Float>> = when (mark) {
            '(' -> along { t -> left + w * (1f - sin(PI.toFloat() * t)) to top - t * height }
            ')' -> along { t -> left + w * sin(PI.toFloat() * t) to top - t * height }
            '[' -> listOf(left + w to top, left to top, left to bottom, left + w to bottom)
            ']' -> listOf(left to top, left + w to top, left + w to bottom, left to bottom)
            '{' -> brace(left, w, bottom, top, opens = true)
            '}' -> brace(left, w, bottom, top, opens = false)
            else -> listOf(left + w / 2 to top, left + w / 2 to bottom)
        }
        return polyline(listOf(points), dense = true)
    }

    private fun brace(left: Float, w: Float, bottom: Float, top: Float, opens: Boolean): List<Pair<Float, Float>> {
        val mid = (top + bottom) / 2
        val h = top - bottom
        val outer = if (opens) left + w else left
        val stem = if (opens) left + w * BRACE_STEM else left + w * (1 - BRACE_STEM)
        val tip = if (opens) left else left + w
        return smooth(
            listOf(
                outer to top,
                stem to top - h * BRACE_SHOULDER,
                stem to mid + h * BRACE_NECK,
                tip to mid,
                stem to mid - h * BRACE_NECK,
                stem to bottom + h * BRACE_SHOULDER,
                outer to bottom,
            ),
        )
    }

    // --- Ink ------------------------------------------------------------------------------------------

    /** [ink] scaled about its origin, its timing scaled with it so the pen's speed is the same. */
    private fun scaled(ink: PenTrajectory, s: Float): PenTrajectory? {
        if (s == 1f) return ink
        val moved = ink.transformed(Matrix3.scale(s)) ?: return null
        val t0 = ink.tMs[0]
        val t = IntArray(ink.pointCount) { t0 + ((ink.tMs[it] - t0) * s).roundToInt() }
        return PenTrajectory.of(moved.x, moved.y, t, moved.pressure, moved.tilt, moved.strokeEnds) ?: moved
    }

    /**
     * Every part on one clock, in writing order: a written piece keeps its own timing and pauses, a
     * drawn mark is timed at the hand's own speed ([handSpeed]), a pen-up between parts. Pressure and
     * tilt are on every sample or none (`PenTrajectory`'s rule, which is `androidx.ink`'s): a part
     * without them borrows the median of the parts that have them.
     */
    private fun assemble(parts: List<Part>): Pair<PenTrajectory, List<Pair<Char, String>>>? {
        if (parts.isEmpty()) return null
        val pressures = parts.mapNotNull { it.ink.pressure }.flatMap { it.asList() }
        val tilts = parts.mapNotNull { it.ink.tilt }.flatMap { it.asList() }
        val pressure = if (pressures.isEmpty()) null else median(pressures)
        val tilt = if (tilts.isEmpty()) null else median(tilts)
        val builder = PenTrajectory.Builder(parts.sumOf { it.ink.pointCount }, pressure != null, tilt != null)
        val tags = ArrayList<Pair<Char, String>>()
        var clock = 0
        for (part in parts) {
            for (k in 0 until part.ink.strokeCount) {
                clock = addStroke(builder, part, k, clock, Channels(pressure ?: 0f, tilt ?: 0f)) + hand.penUpMs
                tags += part.backend to part.model
            }
        }
        return builder.build()?.let { it to tags }
    }

    /** What a sample without pressure or tilt is given. */
    private class Channels(val pressure: Float, val tilt: Float)

    /** Stroke [k] of [part] from [start], on the composite's clock; returns the time of its last sample. */
    private fun addStroke(builder: PenTrajectory.Builder, part: Part, k: Int, start: Int, fill: Channels): Int {
        val ink = part.ink
        var t = start
        val first = ink.strokeStart(k)
        for (i in first until ink.strokeEnd(k)) {
            t = when {
                !part.drawn -> start + (ink.tMs[i] - ink.tMs[first])
                i == first -> start
                else -> t + max(0, (hypot(ink.x[i] - ink.x[i - 1], ink.y[i] - ink.y[i - 1]) / speed).roundToInt())
            }
            builder.add(ink.x[i], ink.y[i], t, ink.pressure?.get(i) ?: fill.pressure, ink.tilt?.get(i) ?: fill.tilt)
        }
        builder.endStroke()
        return t
    }

    /** x-heights per millisecond, measured over the written pieces' own strokes. */
    private fun handSpeed(written: List<MathPiece>): Float {
        var length = 0f
        var time = 0
        for (piece in written) {
            val ink = piece.ink
            for (k in 0 until ink.strokeCount) {
                for (i in ink.strokeStart(k) + 1 until ink.strokeEnd(k)) {
                    length += hypot(ink.x[i] - ink.x[i - 1], ink.y[i] - ink.y[i - 1])
                }
                time += ink.tMs[ink.strokeEnd(k) - 1] - ink.tMs[ink.strokeStart(k)]
            }
        }
        return if (time <= 0 || length <= 0f) DEFAULT_SPEED else (length / time).coerceIn(MIN_SPEED, MAX_SPEED)
    }

    private fun polyline(strokes: List<List<Pair<Float, Float>>>, dense: Boolean = false): PenTrajectory? {
        val capacity = strokes.sumOf { it.size } * DENSE_FACTOR
        val builder = PenTrajectory.Builder(capacity, hasPressure = false, hasTilt = false)
        var t = 0
        for (stroke in strokes) {
            val points = if (dense) stroke else densify(stroke)
            for ((x, y) in points) builder.add(x, y, t++)
            builder.endStroke()
        }
        return builder.build()
    }

    private fun densify(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        for (k in 0 until points.size - 1) {
            val (ax, ay) = points[k]
            val (bx, by) = points[k + 1]
            val steps = max(1, ceil(hypot(bx - ax, by - ay) / SPACING).toInt())
            for (j in 0 until steps) {
                val t = j.toFloat() / steps
                out += (ax + (bx - ax) * t) to (ay + (by - ay) * t)
            }
        }
        out += points.last()
        return out
    }

    /** A Catmull-Rom curve through [points], for the drawn brace. */
    private fun smooth(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        for (k in 0 until points.size - 1) {
            val p0 = points[max(0, k - 1)]
            val p1 = points[k]
            val p2 = points[k + 1]
            val p3 = points[min(points.size - 1, k + 2)]
            val steps = max(2, ceil(hypot(p2.first - p1.first, p2.second - p1.second) / SPACING).toInt())
            for (j in 0 until steps) {
                val t = j.toFloat() / steps
                val x = catmull(p0.first, p1.first, p2.first, p3.first, t)
                out += x to catmull(p0.second, p1.second, p2.second, p3.second, t)
            }
        }
        out += points.last()
        return out
    }

    /** The uniform Catmull-Rom basis; its coefficients are the curve's definition, not tuning. */
    @Suppress("MagicNumber")
    private fun catmull(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        val a = 2 * p0 - 5 * p1 + 4 * p2 - p3
        val b = -p0 + 3 * p1 - 3 * p2 + p3
        return HALF * ((2 * p1) + (-p0 + p2) * t + a * t2 + b * t3)
    }

    private data class Extent(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float) {
        val width: Float get() = maxX - minX
        val height: Float get() = maxY - minY
    }

    private fun extent(ink: PenTrajectory): Extent {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until ink.pointCount) {
            minX = min(minX, ink.x[i])
            maxX = max(maxX, ink.x[i])
            minY = min(minY, ink.y[i])
            maxY = max(maxY, ink.y[i])
        }
        return Extent(minX, maxX, minY, maxY)
    }

    /** Where the radical's vee bottoms out: the x of its lowest sample. */
    private fun lowestX(ink: PenTrajectory): Float {
        var at = 0
        for (i in 1 until ink.pointCount) if (ink.y[i] < ink.y[at]) at = i
        return ink.x[at]
    }

    internal companion object {
        /** A fraction's numerator and denominator, each level down. */
        const val FRACTION_SCALE = 0.88f

        /** Exponents and subscripts. */
        const val SCRIPT_SCALE = 0.62f

        /** A root's index. */
        const val INDEX_SCALE = 0.55f
        const val MIN_SCALE = 0.4f

        /** The maths axis — the height of a `-`, a `+`, an `=` — where a fraction's bar sits. */
        const val AXIS = 0.5f
        const val BAR_GAP = 0.22f
        const val BAR_OVERHANG = 0.14f
        const val FRACTION_SIDE = 0.06f
        const val MAX_WOBBLE = 0.05f
        const val MAX_SAG = 0.03f

        const val SCRIPT_GAP = 0.05f

        /**
         * An exponent's foot, below the top of what it follows. 0.42 and a floor of 0.5 put `e⁻ˣ`'s
         * exponent half inside the `e` on the first sheet, where it read as `e-x` on one line.
         */
        const val SUP_DROP = 0.32f
        const val SUP_MIN = 0.68f
        const val SUB_DROP = 0.3f
        const val SUB_UNDER_DESCENDER = 0.6f

        const val ROOT_CLEAR = 0.22f
        const val ROOT_BELOW = 0.06f
        const val ROOT_GAP = 0.1f
        const val ROOT_OVERRUN = 0.1f
        const val ROOT_WIDTH_MIN = 0.8f
        const val ROOT_WIDTH_MAX = 1.4f
        const val TICK_WIDTH = 0.1f
        const val NOTCH_X = 0.3f
        const val RISE_WIDTH = 0.16f
        const val RISE_PER_HEIGHT = 0.06f
        const val TICK_START = 0.42f
        const val TICK_TOP = 0.5f
        const val INDEX_FOOT = 0.52f
        const val BAR_JOIN = 0.25f
        const val BAR_LIFT = 0.02f

        const val FENCE_MIN_TOP = 1.3f
        const val FENCE_MIN_DROP = 0.25f
        const val FENCE_PAD = 0.12f
        const val FENCE_GAP = 0.08f
        const val FENCE_WIDTH = 0.16f
        const val FENCE_WIDTH_PER_HEIGHT = 0.05f
        const val FENCE_WIDEN = 0.25f
        const val FENCE_JITTER = 0.2f
        const val BRACE_STEM = 0.45f
        const val BRACE_SHOULDER = 0.08f
        const val BRACE_NECK = 0.08f

        const val MIN_GLYPH = 0.05f

        /** Drawn marks are sampled every [SPACING] x-heights — about what a pen at 240 Hz leaves. */
        const val SPACING = 0.04f
        const val MIN_STEPS = 8
        const val DENSE_FACTOR = 4
        const val DEFAULT_SPEED = 0.006f
        const val MIN_SPEED = 0.002f
        const val MAX_SPEED = 0.03f
        const val HALF = 0.5f
    }
}
