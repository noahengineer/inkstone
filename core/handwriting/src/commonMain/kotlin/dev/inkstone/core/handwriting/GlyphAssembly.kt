package dev.inkstone.core.handwriting

import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.PenTrajectory
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * Which of the user's writing to prefer — D15's *"this page first, then this note, then recent
 * writing, then the rest"*. Assembly weighs each variant by it, so a phrase dropped into a note is
 * built from that note's own letters where it has them.
 */
public data class SourcePreference(val noteId: String? = null, val pageId: String? = null, val epochDay: Long = 0L) {
    public fun weightOf(source: UnitSource): Float = when {
        noteId != null && source.noteId == noteId && pageId != null && source.pageId == pageId -> SAME_PAGE
        noteId != null && source.noteId == noteId -> SAME_NOTE
        epochDay - source.epochDay in 0..RECENT_DAYS -> RECENT
        else -> 1f
    }

    private companion object {
        const val SAME_PAGE = 4f
        const val SAME_NOTE = 2f
        const val RECENT = 1.5f
        const val RECENT_DAYS = 30L
    }
}

/** What [GlyphAssembly.word] made of one word. */
public sealed interface AssembledWord {
    public val text: String

    /**
     * @property trajectory the word in the **normalised** frame (§5.2): its ink starts at x = 0, its
     *   baseline is y = 0, y is up and one x-height is 1. Laying it out and the page's style undo
     *   that (`HandwritingSynthesizer`).
     * @property joins how many letter pairs were joined by a bridge.
     */
    public class Written(
        override val text: String,
        public val trajectory: PenTrajectory,
        public val width: Float,
        public val units: List<GlyphUnit>,
        public val joins: Int,
    ) : AssembledWord

    /** No cover exists: [letters] are what the user has never written in a usable way. */
    public data class Missing(override val text: String, val letters: List<String>) : AssembledWord
}

/**
 * ⭐ Glyph assembly — docs/HANDWRITING-SYNTHESIS.md §6.1. Builds a word out of the user's own
 * words, runs of letters and letters, with bounded variation and the user's own joins.
 *
 * ## The steps, for one word
 *
 * 1. **Cover the text with the longest units there are** ([cover]): the whole word if the user has
 *    written it, else the fewest pieces — runs before letters — chosen at random among covers of
 *    equal size. A letter nothing covers makes the word [AssembledWord.Missing]; nothing stands in
 *    for it.
 * 2. **Pick a variant of each unit**, weighted by whether it was read back and by D15's proximity,
 *    and never one already used in this phrase while another exists.
 * 3. **Place each unit on the baseline** after the last, by a gap drawn from the user's measured
 *    gaps for that pair of letter classes, varied per unit within [Variation]'s bounds.
 * 4. **Join where the user joins** ([HandStats.joins]): a short bridge from the left unit's exit to
 *    the right unit's entry, following both tangents, merged into one stroke. Where the user lifts,
 *    the units stay apart, so a mixed hand comes out mixed in the same places.
 * 5. **Wobble each stroke** along its length, and lay the strokes on one clock with the user's own
 *    pen-up pauses.
 *
 * Pure and deterministic for a given [Random], so a test can pin it and a drop can never repeat:
 * every drop seeds a fresh generator.
 */
public class GlyphAssembly(private val index: GlyphIndex) {

    /**
     * @param used variants already in this phrase, shared across its words — §6.1's *"never reuse a
     *   variant within a phrase while another one exists"*. Added to as units are picked.
     * @param strict §8.2's route for a token the recogniser cannot judge (a number, a formula, a symbol,
     *   when calibration says so): built only from **single letters**, each read back on its own when it
     *   was cut, and from units the user **vouched** for ([GlyphUnit.vouched]); never joined; and laid
     *   out with clear air between every two units ([STRICT_GAP]). Correct by construction, because
     *   construction is the only proof there is: nothing reads the result back.
     */
    @Suppress("LongParameterList")
    public fun word(
        text: String,
        variation: Variation,
        rng: Random,
        used: MutableSet<UnitSource>,
        preference: SourcePreference = SourcePreference(),
        strict: Boolean = false,
    ): AssembledWord {
        val letters = writtenUnits(text)
        if (letters.isEmpty()) return AssembledWord.Missing(text, emptyList())
        val cover = cover(letters, rng, strict) ?: return AssembledWord.Missing(text, missingLetters(letters, strict))
        val units = cover.map { piece -> choose(variantsOf(piece, strict), rng, used, preference) }
        return build(text, units, variation, rng, strict)
    }

    /** [index]'s variants of [text], only those [strict] allows. */
    private fun variantsOf(text: String, strict: Boolean): List<GlyphUnit> =
        if (strict) index.variants(text).filter { it.kind == UnitKind.LETTER || it.vouched } else index.variants(text)

    /** The texts of a fewest-pieces cover of [letters], or null when none exists. */
    internal fun cover(letters: List<String>, rng: Random, strict: Boolean = false): List<String>? {
        val n = letters.size
        val longest = min(n, max(1, index.longestUnit))
        val best = IntArray(n + 1) { UNREACHABLE }
        best[n] = 0
        for (i in n - 1 downTo 0) {
            val reachable = (1..min(longest, n - i)).filter { k ->
                best[i + k] != UNREACHABLE && variantsOf(join(letters, i, k), strict).isNotEmpty()
            }
            best[i] = reachable.minOfOrNull { k -> best[i + k] + 1 } ?: UNREACHABLE
        }
        if (best[0] == UNREACHABLE) return null
        val pieces = ArrayList<String>()
        var at = 0
        while (at < n) {
            val options = (1..min(longest, n - at)).filter { k ->
                best[at + k] != UNREACHABLE && best[at + k] + 1 == best[at] &&
                    variantsOf(join(letters, at, k), strict).isNotEmpty()
            }
            val k = options[rng.nextInt(options.size)]
            pieces += join(letters, at, k)
            at += k
        }
        return pieces
    }

    /** The letters no unit reaches — what a refusal names. */
    private fun missingLetters(letters: List<String>, strict: Boolean): List<String> {
        val longest = min(letters.size, max(1, index.longestUnit))
        val spans = letters.indices.flatMap { i ->
            (1..min(longest, letters.size - i))
                .filter { k -> variantsOf(join(letters, i, k), strict).isNotEmpty() }
                .map { k -> i until i + k }
        }
        val missing = letters.filterIndexed { i, _ -> spans.none { i in it } }.distinct()
        // Every letter is reachable, yet no chain of units covers the word: say which letters exist
        // only inside other words, since those are what writing them on their own would fix.
        return missing.ifEmpty { letters.filter { variantsOf(it, strict).isEmpty() }.distinct() }
    }

    private fun choose(
        variants: List<GlyphUnit>,
        rng: Random,
        used: MutableSet<UnitSource>,
        preference: SourcePreference,
    ): GlyphUnit {
        val fresh = variants.filter { it.source !in used }.ifEmpty { variants }
        val weights = fresh.map { (if (it.verified) 1f else UNVERIFIED_WEIGHT) * preference.weightOf(it.source) }
        var roll = rng.nextFloat() * weights.sum()
        var chosen = fresh.last()
        for ((i, unit) in fresh.withIndex()) {
            roll -= weights[i]
            if (roll <= 0f) {
                chosen = unit
                break
            }
        }
        used += chosen.source
        return chosen
    }

    @Suppress("LongParameterList")
    private fun build(
        text: String,
        units: List<GlyphUnit>,
        variation: Variation,
        rng: Random,
        strict: Boolean,
    ): AssembledWord {
        val pieces = ArrayList<Piece>()
        // Where the previous unit's pieces begin in [pieces]: what the next join looks for an exit in.
        var previousStart = 0
        var penRight = 0f
        var joins = 0
        for ((i, unit) in units.withIndex()) {
            val transformed = vary(piecesOf(unit.trajectory), variation, rng)
            val box = boundsOf(transformed)
            val gap = if (i == 0) {
                0f
            } else {
                val left = units[i - 1].letters.last()
                val right = unit.letters.first()
                val least = if (strict) maxOf(STRICT_GAP, clearance(left, right)) else clearance(left, right)
                maxOf(rng.pick(index.stats.letterGapsFor(left, right), variation.gapJitter), least)
            }
            val dx = (if (i == 0) 0f else penRight + gap) - box.left
            val placed = transformed.map { it.translated(dx) }.toMutableList()
            if (i > 0) {
                val upMs = (index.stats.penUpMs * (1f + rng.within(PEN_UP_JITTER))).toInt().coerceAtLeast(MIN_PEN_UP_MS)
                placed[0] = placed[0].withGap(upMs)
            }
            val start = pieces.size
            val joinable = !strict && i > 0 && index.stats.joins(units[i - 1].letters.last(), unit.letters.first())
            val merged = if (joinable) {
                bridge(pieces, previousStart, placed)
            } else {
                NOT_JOINED
            }
            if (merged == NOT_JOINED) {
                pieces += placed
                previousStart = start
            } else {
                joins++
                previousStart = merged
            }
            penRight = box.right + dx
        }
        val wobbled = pieces.map { wobble(it, variation.wobble, rng) }
        val trajectory = trajectoryOf(wobbled) ?: return AssembledWord.Missing(text, emptyList())
        val box = trajectory.bounds()
        val shifted = trajectory.transformed(Matrix3.translate(-box.left, 0f)) ?: trajectory
        return AssembledWord.Written(text, shifted, box.width, units, joins)
    }

    /**
     * Joins the unit just placed ([right]) to the one before it, whose pieces are those in [pieces]
     * from [unitStart] on. @return where the merged stroke now is in [pieces], or [NOT_JOINED],
     * changing nothing, when the two do not meet the way a join needs: an exit stroke ending low and
     * to the left of a first stroke that starts low.
     */
    @Suppress("ReturnCount")
    private fun bridge(pieces: MutableList<Piece>, unitStart: Int, right: List<Piece>): Int {
        if (unitStart !in pieces.indices || right.isEmpty()) return NOT_JOINED
        val exitAt = (unitStart until pieces.size)
            .filter { pieces[it].arcLength() >= MIN_JOIN_ARC }
            .maxByOrNull { pieces[it].x.last() } ?: return NOT_JOINED
        val exit = pieces[exitAt]
        val entry = right[0]
        if (entry.arcLength() < MIN_JOIN_ARC) return NOT_JOINED
        val leftmostStart = right.filter { it.arcLength() >= MIN_JOIN_ARC }.minOf { it.x.first() }
        if (entry.x.first() > leftmostStart + ENTRY_SLACK) return NOT_JOINED
        val ex = exit.x.last()
        val ey = exit.y.last()
        val sx = entry.x.first()
        val sy = entry.y.first()
        val distance = hypot(sx - ex, sy - ey)
        val joinable = distance <= MAX_BRIDGE && sx >= ex - BACKTRACK && ey <= MAX_JOIN_HEIGHT && sy <= MAX_JOIN_HEIGHT
        if (!joinable) return NOT_JOINED
        pieces[exitAt] = exit.joinedTo(entry, distance)
        pieces += right.drop(1)
        return exitAt
    }

    /**
     * ⭐ §18.10 — the least gap between [left] and [right], whatever the hand's measured gaps say.
     *
     * A letter gap may be negative, rightly: a `t`'s bar reaches over its neighbour. But the gaps are
     * measured per class pair and fall back to *all* the hand's gaps, so a decimal point inherited a
     * letter's −0.15 x-heights — and a dot 0.06 wide placed 0.15 inside a `2`'s foot vanished into it.
     * `2.5` was drawn as `25`, `9.81` as `981`: a different number, and the gate's reader would agree.
     * Digits get no overlap either: they are written apart, and `-1708` came out with its `7` and `0`
     * fused.
     */
    private fun clearance(left: String, right: String): Float = when {
        LetterFit.shapeOf(left) == LetterFit.Shape.POINT || LetterFit.shapeOf(right) == LetterFit.Shape.POINT ->
            POINT_CLEARANCE

        LetterClass.of(left) == LetterClass.DIGIT && LetterClass.of(right) == LetterClass.DIGIT -> DIGIT_CLEARANCE

        else -> Float.NEGATIVE_INFINITY
    }

    internal companion object {
        /**
         * §8.2's *"laid out without overlap"*: the least air between two units of a token nothing can
         * read back, in x-heights — wider than the hand's own wobble ([Variation.wobble] is 1.5 % of an
         * x-height), so no two units' ink can touch.
         */
        const val STRICT_GAP = 0.12f

        /** Clear air either side of a `.` or `,`, in x-heights. */
        const val POINT_CLEARANCE = 0.2f

        /** Between two digits: touching at most, never overlapping. */
        const val DIGIT_CLEARANCE = 0.05f

        const val UNREACHABLE = Int.MAX_VALUE
        const val NOT_JOINED = -1
        const val UNVERIFIED_WEIGHT = 0.5f
        const val PEN_UP_JITTER = 0.2f
        const val MIN_PEN_UP_MS = 20

        /** A stroke shorter than this is a dot or a crossbar, never a join's end. In x-heights. */
        const val MIN_JOIN_ARC = 0.4f

        /** How much further left than the right unit's leftmost start its first stroke may begin. */
        const val ENTRY_SLACK = 0.15f

        /** The longest bridge drawn, in x-heights. Further apart than this, a join would be a line. */
        const val MAX_BRIDGE = 1.0f

        /** How far the entry may sit left of the exit before a bridge would loop back on itself. */
        const val BACKTRACK = 0.15f

        /**
         * A join runs between letters, not over their tops: an exit or entry above this height (an
         * ascender's start, a capital's top) is not joined. In x-heights above the baseline.
         */
        const val MAX_JOIN_HEIGHT = 1.25f

        fun join(letters: List<String>, from: Int, count: Int): String =
            letters.subList(from, from + count).joinToString("")
    }
}

/**
 * One stroke being assembled: its samples, its own time from 0, and the pen-up pause before it.
 * Mutable arrays never escape; every change makes a new piece.
 */
internal class Piece(
    val x: FloatArray,
    val y: FloatArray,
    val t: IntArray,
    val p: FloatArray?,
    val tilt: FloatArray?,
    val gapBefore: Int,
) {
    val size: Int get() = x.size

    fun arcLength(): Float {
        var length = 0f
        for (i in 1 until size) length += hypot(x[i] - x[i - 1], y[i] - y[i - 1])
        return length
    }

    fun translated(dx: Float, dy: Float = 0f): Piece =
        Piece(FloatArray(size) { x[it] + dx }, FloatArray(size) { y[it] + dy }, t, p, tilt, gapBefore)

    fun withGap(ms: Int): Piece = Piece(x, y, t, p, tilt, ms)

    /**
     * This stroke, a bridge, and [next], as one stroke. The bridge is a cubic that leaves along this
     * stroke's last direction and arrives along [next]'s first, drawn at this stroke's closing speed.
     */
    fun joinedTo(next: Piece, distance: Float): Piece {
        val (bx, by) = bridgeTo(next, distance)
        val speed = closingSpeed()
        val bt = IntArray(bx.size)
        var clock = t.last().toFloat()
        var px = x.last()
        var py = y.last()
        var last = t.last()
        for (j in bx.indices) {
            clock += hypot(bx[j] - px, by[j] - py) / speed
            px = bx[j]
            py = by[j]
            last = max(last + 1, clock.toInt())
            bt[j] = last
        }
        clock += hypot(next.x.first() - px, next.y.first() - py) / speed
        val offset = max(last + 1, clock.toInt()) - next.t.first()
        val bridge = Piece(bx, by, bt, blend(p, next.p, bx.size), blend(tilt, next.tilt, bx.size), 0)
        return concat(this, bridge, next.shifted(offset))
    }

    /** The bridge's samples, ends excluded: they are this stroke's last sample and [next]'s first. */
    private fun bridgeTo(next: Piece, distance: Float): Pair<FloatArray, FloatArray> {
        val (ox, oy) = direction(fromEnd = true)
        val (ix, iy) = next.direction(fromEnd = false)
        val reach = distance / CONTROL_DIVISOR
        val x0 = x.last()
        val y0 = y.last()
        val x3 = next.x.first()
        val y3 = next.y.first()
        val steps = ceil(distance / BRIDGE_STEP).toInt().coerceIn(MIN_BRIDGE_STEPS, MAX_BRIDGE_STEPS)
        val bx = FloatArray(steps - 1) { cubic((it + 1f) / steps, x0, x0 + ox * reach, x3 - ix * reach, x3) }
        val by = FloatArray(steps - 1) { cubic((it + 1f) / steps, y0, y0 + oy * reach, y3 - iy * reach, y3) }
        return bx to by
    }

    fun shifted(ms: Int): Piece = Piece(x, y, IntArray(size) { t[it] + ms }, p, tilt, gapBefore)

    /** The unit direction of the last (or first) [TANGENT_SPAN] of the stroke. */
    private fun direction(fromEnd: Boolean): Pair<Float, Float> {
        val anchor = if (fromEnd) size - 1 else 0
        var other = anchor
        var travelled = 0f
        while (travelled < TANGENT_SPAN) {
            val next = if (fromEnd) other - 1 else other + 1
            if (next !in 0 until size) break
            travelled += hypot(x[next] - x[other], y[next] - y[other])
            other = next
        }
        val dx = if (fromEnd) x[anchor] - x[other] else x[other] - x[anchor]
        val dy = if (fromEnd) y[anchor] - y[other] else y[other] - y[anchor]
        val length = hypot(dx, dy)
        return if (length < EPSILON) 1f to 0f else (dx / length) to (dy / length)
    }

    /** x-heights per millisecond over the stroke's last stretch, floored so a bridge never stalls. */
    private fun closingSpeed(): Float {
        var distance = 0f
        var i = size - 1
        while (i > 0 && distance < TANGENT_SPAN) {
            distance += hypot(x[i] - x[i - 1], y[i] - y[i - 1])
            i--
        }
        val ms = (t.last() - t[i]).coerceAtLeast(1)
        return max(distance / ms, MIN_SPEED)
    }

    companion object {
        /** Pieces end to end, as one: a channel survives only when every piece has it. */
        fun concat(vararg parts: Piece): Piece {
            val total = parts.sumOf { it.size }
            val x = FloatArray(total)
            val y = FloatArray(total)
            val t = IntArray(total)
            val p = if (parts.all { it.p != null }) FloatArray(total) else null
            val tilt = if (parts.all { it.tilt != null }) FloatArray(total) else null
            var at = 0
            for (part in parts) {
                part.x.copyInto(x, at)
                part.y.copyInto(y, at)
                part.t.copyInto(t, at)
                part.p?.let { pressure -> p?.let { pressure.copyInto(it, at) } }
                part.tilt?.let { angle -> tilt?.let { angle.copyInto(it, at) } }
                at += part.size
            }
            return Piece(x, y, t, p, tilt, parts.first().gapBefore)
        }

        /** [count] values from [from]'s last to [to]'s first, ends excluded; null unless both exist. */
        fun blend(from: FloatArray?, to: FloatArray?, count: Int): FloatArray? = if (from == null || to == null) {
            null
        } else {
            FloatArray(count) { lerp(from.last(), to.first(), (it + 1f) / (count + 1)) }
        }

        /** A cubic Bézier coordinate at [u]. */
        fun cubic(u: Float, a: Float, b: Float, c: Float, d: Float): Float {
            val v = 1 - u
            return v * v * v * a + BERNSTEIN * v * v * u * b + BERNSTEIN * v * u * u * c + u * u * u * d
        }

        private const val BERNSTEIN = 3f
        const val CONTROL_DIVISOR = 3f
        const val BRIDGE_STEP = 0.04f
        const val MIN_BRIDGE_STEPS = 3
        const val MAX_BRIDGE_STEPS = 40
        const val TANGENT_SPAN = 0.15f

        /** One x-height in 200 ms: slow for a hand, so a bridge is never faster than the letters. */
        const val MIN_SPEED = 0.005f

        fun lerp(a: Float, b: Float, f: Float): Float = a + (b - a) * f
    }
}

/** [trajectory]'s strokes as pieces, each with the pause its source had before it. */
internal fun piecesOf(trajectory: PenTrajectory): List<Piece> = (0 until trajectory.strokeCount).map { s ->
    val start = trajectory.strokeStart(s)
    val end = trajectory.strokeEnd(s)
    val base = trajectory.tMs[start]
    val previousEnd = if (s == 0) base else trajectory.tMs[trajectory.strokeEnd(s - 1) - 1]
    val gap = if (s == 0) 0 else (base - previousEnd).coerceIn(MIN_GAP_MS, MAX_GAP_MS)
    Piece(
        x = trajectory.x.copyOfRange(start, end),
        y = trajectory.y.copyOfRange(start, end),
        t = IntArray(end - start) { trajectory.tMs[start + it] - base },
        p = trajectory.pressure?.copyOfRange(start, end),
        tilt = trajectory.tilt?.copyOfRange(start, end),
        gapBefore = gap,
    )
}

/**
 * Per-unit variation (§6.1): scale, slant, rotation and baseline offset, about the unit's left
 * baseline point. Normalised space, y up.
 */
internal fun vary(pieces: List<Piece>, variation: Variation, rng: Random): List<Piece> {
    val scale = 1f + rng.within(variation.unitScale)
    val shear = tan(rng.within(variation.unitSlantRad))
    val turn = rng.within(variation.unitRotationRad)
    val lift = rng.within(variation.unitBaseline)
    val cosT = cos(turn)
    val sinT = sin(turn)
    return pieces.map { piece ->
        val nx = FloatArray(piece.size)
        val ny = FloatArray(piece.size)
        for (i in 0 until piece.size) {
            val u = piece.x[i] * scale + piece.y[i] * scale * shear
            val v = piece.y[i] * scale
            nx[i] = u * cosT - v * sinT
            ny[i] = u * sinT + v * cosT + lift
        }
        Piece(nx, ny, piece.t, piece.p, piece.tilt, piece.gapBefore)
    }
}

/**
 * §6.1's wobble: each stroke displaced along its own length by two or three slow sinusoids whose
 * amplitudes sum to at most [bound]. Smooth by construction, so a curve stays a curve.
 */
internal fun wobble(piece: Piece, bound: Float, rng: Random): Piece {
    if (bound <= 0f || piece.size < 2) return piece
    val waves = MIN_WAVES + rng.nextInt(WAVE_CHOICES)
    val ax = FloatArray(waves) { rng.nextFloat() }
    val ay = FloatArray(waves) { rng.nextFloat() }
    val sumX = ax.sum().coerceAtLeast(EPSILON)
    val sumY = ay.sum().coerceAtLeast(EPSILON)
    val lengths = FloatArray(waves) { MIN_WAVELENGTH + rng.nextFloat() * WAVELENGTH_RANGE }
    val phases = FloatArray(waves) { rng.nextFloat() * TAU }
    val nx = FloatArray(piece.size)
    val ny = FloatArray(piece.size)
    var s = 0f
    for (i in 0 until piece.size) {
        if (i > 0) s += hypot(piece.x[i] - piece.x[i - 1], piece.y[i] - piece.y[i - 1])
        var dx = 0f
        var dy = 0f
        for (w in 0 until waves) {
            val angle = TAU * s / lengths[w] + phases[w]
            dx += ax[w] / sumX * bound * sin(angle)
            dy += ay[w] / sumY * bound * cos(angle)
        }
        nx[i] = piece.x[i] + dx
        ny[i] = piece.y[i] + dy
    }
    return Piece(nx, ny, piece.t, piece.p, piece.tilt, piece.gapBefore)
}

/** Pieces laid end to end on one clock: each after the last, by its own pen-up pause. */
internal fun trajectoryOf(pieces: List<Piece>): PenTrajectory? {
    if (pieces.isEmpty()) return null
    val hasP = pieces.all { it.p != null }
    val hasTilt = pieces.all { it.tilt != null }
    val builder = PenTrajectory.Builder(pieces.sumOf { it.size }, hasP, hasTilt)
    var clock = 0
    for ((index, piece) in pieces.withIndex()) {
        val start = if (index == 0) 0 else clock + max(1, piece.gapBefore)
        for (i in 0 until piece.size) {
            builder.add(
                x = piece.x[i],
                y = piece.y[i],
                tMs = start + piece.t[i],
                pressure = piece.p?.get(i)?.coerceIn(0f, 1f) ?: 0f,
                tilt = piece.tilt?.get(i)?.coerceIn(0f, HALF_PI) ?: 0f,
            )
        }
        builder.endStroke()
        clock = start + piece.t.last()
    }
    return builder.build()
}

internal class Box(val left: Float, val right: Float)

internal fun boundsOf(pieces: List<Piece>): Box {
    var left = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    for (piece in pieces) {
        for (x in piece.x) {
            left = min(left, x)
            right = max(right, x)
        }
    }
    return if (left > right) Box(0f, 0f) else Box(left, right)
}

private const val MIN_GAP_MS = 20
private const val MAX_GAP_MS = 800
private const val MIN_WAVES = 2
private const val WAVE_CHOICES = 2
private const val MIN_WAVELENGTH = 0.6f
private const val WAVELENGTH_RANGE = 1.4f
private const val TAU = (2 * PI).toFloat()
private const val HALF_PI = (PI / 2).toFloat()
private const val EPSILON = 1e-6f
