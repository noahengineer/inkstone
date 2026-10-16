package dev.inkstone.core.model

import kotlin.math.PI

/*
 * Handwriting synthesis (docs/HANDWRITING-SYNTHESIS.md) — the vocabulary its halves share.
 *
 * `:android:ink` reads a stroke's samples out of SPEC §5.4's opaque blob (and, from phase 3, turns
 * generated ones back into one), `:android:ocr` reads ink back as text, and `:core:handwriting` does
 * everything in between. None of them may depend on another, so the types they agree on live here —
 * the same reason `Recognition.kt` does.
 *
 * Block comment, not KDoc — a file-level KDoc attached to no declaration is a dangling toplevel KDoc
 * that ktlint cannot auto-correct.
 */

/**
 * Which brush a stroke was drawn with, as `:core` may name it.
 *
 * Mirrors `:android:ink`'s `InkBrushKind` **by name**. The blob stores that enum's ordinal, which is
 * an `:android:ink` detail; mapping by name means reordering either enum cannot silently turn a
 * pencil into a highlighter here.
 */
public enum class PenKind { FOUNTAIN, BALLPOINT, PENCIL, HIGHLIGHTER }

/**
 * The pen a stroke was drawn with — what docs/HANDWRITING-SYNTHESIS.md D15 copies, so that text
 * dropped onto a page is written in the pen of the writing around it.
 *
 * @property widthPt the brush size the stroke was **captured** with, from the blob's header. A
 *   `SetStyle` override is a later restyle and is not part of how the user writes.
 */
public data class PenStyle(val kind: PenKind, val colorArgb: Int, val widthPt: Float)

/**
 * Why a set of arrays is not a [PenTrajectory] — each one a rule `androidx.ink` enforces on a
 * `StrokeInputBatch`, read off `libink.so`'s own validation messages (docs/HANDWRITING-SYNTHESIS.md
 * §4.1), plus the shape rules the arrays need to mean anything at all.
 */
public enum class TrajectoryProblem {
    EMPTY,
    LENGTHS_DIFFER,
    BAD_STROKE_ENDS,
    NOT_FINITE,
    TIME_NEGATIVE,
    TIME_DECREASES,
    REPEATED_SAMPLE,
    PRESSURE_OUT_OF_RANGE,
    TILT_OUT_OF_RANGE,
}

/**
 * ⭐ A pen's path: the compact stroke format (docs/HANDWRITING-SYNTHESIS.md §4.1).
 *
 * Parallel arrays rather than a point class: a word is hundreds of points and a profile is
 * thousands of words, and nothing here is on the ink path that would make a lighter type matter
 * less.
 *
 * ## ⚠️ Its invariants are `androidx.ink`'s
 *
 * `MutableStrokeInputBatch.add` **throws** on bad input — non-finite positions, time that goes
 * backwards, a sample repeated at the same instant, pressure outside `[0, 1]`, tilt outside
 * `[0, π/2]`, a channel on some samples and not others. A generated stroke must never be the thing
 * that throws inside the editor's lock, so the rules are checked here, in `:core`, where a test can
 * reach them — [of] refuses rather than constructs. The one rule this adds of its own is that
 * time is on **one clock for the whole trajectory**: strokes come in the order they were written.
 *
 * @property x page points, or the normalised frame of `:core:handwriting`'s `NormalFrame`.
 * @property y as [x]; **y grows downward** in page space (SPEC §4.4).
 * @property tMs milliseconds from the first sample. Non-decreasing across the whole trajectory.
 * @property pressure `0..1` per sample, or null when nothing measured it.
 * @property tilt `0..π/2` radians per sample, or null.
 * @property strokeEnds the exclusive end of each stroke; the pen lifts after each one.
 */
public class PenTrajectory private constructor(
    public val x: FloatArray,
    public val y: FloatArray,
    public val tMs: IntArray,
    public val pressure: FloatArray?,
    public val tilt: FloatArray?,
    public val strokeEnds: IntArray,
) {

    public val pointCount: Int get() = x.size

    public val strokeCount: Int get() = strokeEnds.size

    public fun strokeStart(stroke: Int): Int = if (stroke == 0) 0 else strokeEnds[stroke - 1]

    public fun strokeEnd(stroke: Int): Int = strokeEnds[stroke]

    public val durationMs: Int get() = tMs[tMs.size - 1] - tMs[0]

    /** The box around the samples — the centreline, not the inked mesh, as `SampledStroke`'s is. */
    public fun bounds(): PageRect {
        var left = x[0]
        var right = x[0]
        var top = y[0]
        var bottom = y[0]
        for (i in 1 until x.size) {
            if (x[i] < left) left = x[i]
            if (x[i] > right) right = x[i]
            if (y[i] < top) top = y[i]
            if (y[i] > bottom) bottom = y[i]
        }
        return PageRect(left, top, right, bottom)
    }

    /**
     * Just [strokes], in the order given, on the same clock. Null when the selection is empty or out
     * of range — never a trajectory with nothing in it.
     */
    public fun strokes(strokes: List<Int>): PenTrajectory? {
        if (strokes.isEmpty() || strokes.any { it !in 0 until strokeCount }) return null
        val total = strokes.sumOf { strokeEnd(it) - strokeStart(it) }
        val builder = Builder(total, pressure != null, tilt != null)
        for (stroke in strokes) {
            for (i in strokeStart(stroke) until strokeEnd(stroke)) {
                builder.add(x[i], y[i], tMs[i], pressure?.get(i) ?: 0f, tilt?.get(i) ?: 0f)
            }
            builder.endStroke()
        }
        return builder.build()
    }

    /** Every sample moved by [transform]; timing, pressure and tilt untouched. */
    public fun transformed(transform: Matrix3): PenTrajectory? {
        val nx = FloatArray(pointCount)
        val ny = FloatArray(pointCount)
        for (i in 0 until pointCount) {
            nx[i] = transform.m00 * x[i] + transform.m01 * y[i] + transform.m02
            ny[i] = transform.m10 * x[i] + transform.m11 * y[i] + transform.m12
        }
        return of(nx, ny, tMs, pressure, tilt, strokeEnds)
    }

    override fun equals(other: Any?): Boolean = other is PenTrajectory &&
        x.contentEquals(other.x) &&
        y.contentEquals(other.y) &&
        tMs.contentEquals(other.tMs) &&
        pressure.contentEquals(other.pressure) &&
        tilt.contentEquals(other.tilt) &&
        strokeEnds.contentEquals(other.strokeEnds)

    override fun hashCode(): Int {
        var hash = x.contentHashCode()
        hash = HASH_PRIME * hash + y.contentHashCode()
        hash = HASH_PRIME * hash + tMs.contentHashCode()
        hash = HASH_PRIME * hash + strokeEnds.contentHashCode()
        return hash
    }

    override fun toString(): String = "PenTrajectory($pointCount points, $strokeCount strokes, ${durationMs}ms)"

    /**
     * Builds a trajectory a sample at a time. Consecutive samples repeating the previous one's
     * position **and** time are dropped rather than refused — they are what quantisation (a codec
     * rounding to 1/100 pt, an input batch rounding to 1/4096 of its range) produces from two
     * genuinely distinct samples, and `androidx.ink` refuses them.
     */
    public class Builder(capacity: Int, private val hasPressure: Boolean, private val hasTilt: Boolean) {
        private var x = FloatArray(capacity.coerceAtLeast(1))
        private var y = FloatArray(capacity.coerceAtLeast(1))
        private var t = IntArray(capacity.coerceAtLeast(1))
        private var p = if (hasPressure) FloatArray(capacity.coerceAtLeast(1)) else null
        private var tl = if (hasTilt) FloatArray(capacity.coerceAtLeast(1)) else null
        private var size = 0
        private var strokeStartedAt = 0
        private val ends = ArrayList<Int>()

        public fun add(x: Float, y: Float, tMs: Int, pressure: Float = 0f, tilt: Float = 0f) {
            if (repeatsLast(x, y, tMs)) return
            if (size == this.x.size) grow()
            this.x[size] = x
            this.y[size] = y
            t[size] = tMs
            p?.set(size, pressure)
            tl?.set(size, tilt)
            size++
        }

        /** Close the current stroke. A stroke with no samples is dropped, not recorded. */
        public fun endStroke() {
            if (size > strokeStartedAt) ends += size
            strokeStartedAt = size
        }

        /** Null when what was added breaks one of [PenTrajectory]'s rules — see [problemWith]. */
        public fun build(): PenTrajectory? {
            endStroke()
            return of(
                x.copyOf(size),
                y.copyOf(size),
                t.copyOf(size),
                p?.copyOf(size),
                tl?.copyOf(size),
                ends.toIntArray(),
            )
        }

        /** The same position at the same instant as the sample before it, within this stroke. */
        private fun repeatsLast(x: Float, y: Float, tMs: Int): Boolean =
            size > strokeStartedAt && this.x[size - 1] == x && this.y[size - 1] == y && t[size - 1] == tMs

        private fun grow() {
            val capacity = x.size * 2
            x = x.copyOf(capacity)
            y = y.copyOf(capacity)
            t = t.copyOf(capacity)
            p = p?.copyOf(capacity)
            tl = tl?.copyOf(capacity)
        }
    }

    public companion object {
        private const val HASH_PRIME = 31
        private const val HALF_PI = (PI / 2).toFloat()

        /** A trajectory, or null when the arrays break one of its rules. */
        @Suppress("LongParameterList")
        public fun of(
            x: FloatArray,
            y: FloatArray,
            tMs: IntArray,
            pressure: FloatArray?,
            tilt: FloatArray?,
            strokeEnds: IntArray,
        ): PenTrajectory? = if (problemWith(x, y, tMs, pressure, tilt, strokeEnds) == null) {
            PenTrajectory(x, y, tMs, pressure, tilt, strokeEnds)
        } else {
            null
        }

        /**
         * The first rule the arrays break, or null when they make a valid trajectory.
         *
         * Exposed so a test — and a bug report — can say *which* rule, rather than only that one
         * failed.
         */
        @Suppress("LongParameterList", "ReturnCount", "CyclomaticComplexMethod")
        public fun problemWith(
            x: FloatArray,
            y: FloatArray,
            tMs: IntArray,
            pressure: FloatArray?,
            tilt: FloatArray?,
            strokeEnds: IntArray,
        ): TrajectoryProblem? {
            val n = x.size
            if (n == 0 || strokeEnds.isEmpty()) return TrajectoryProblem.EMPTY
            if (!allSized(n, y, tMs, pressure, tilt)) return TrajectoryProblem.LENGTHS_DIFFER
            var previousEnd = 0
            for (end in strokeEnds) {
                if (end <= previousEnd || end > n) return TrajectoryProblem.BAD_STROKE_ENDS
                previousEnd = end
            }
            if (previousEnd != n) return TrajectoryProblem.BAD_STROKE_ENDS

            var stroke = 0
            for (i in 0 until n) {
                if (!x[i].isFinite() || !y[i].isFinite()) return TrajectoryProblem.NOT_FINITE
                if (tMs[i] < 0) return TrajectoryProblem.TIME_NEGATIVE
                if (i > 0 && tMs[i] < tMs[i - 1]) return TrajectoryProblem.TIME_DECREASES
                if (i == strokeEnds[stroke]) stroke++
                val startsStroke = i == (if (stroke == 0) 0 else strokeEnds[stroke - 1])
                if (!startsStroke && repeatsPrevious(x, y, tMs, i)) return TrajectoryProblem.REPEATED_SAMPLE
                if (pressure != null && !(pressure[i] in 0f..1f)) return TrajectoryProblem.PRESSURE_OUT_OF_RANGE
                if (tilt != null && !(tilt[i] in 0f..HALF_PI)) return TrajectoryProblem.TILT_OUT_OF_RANGE
            }
            return null
        }

        private fun allSized(n: Int, y: FloatArray, tMs: IntArray, pressure: FloatArray?, tilt: FloatArray?): Boolean =
            y.size == n && tMs.size == n && (pressure?.size ?: n) == n && (tilt?.size ?: n) == n

        private fun repeatsPrevious(x: FloatArray, y: FloatArray, tMs: IntArray, i: Int): Boolean =
            x[i] == x[i - 1] && y[i] == y[i - 1] && tMs[i] == tMs[i - 1]
    }
}

/**
 * One stroke as the pen made it — what handwriting synthesis learns from
 * (docs/HANDWRITING-SYNTHESIS.md §5).
 *
 * @property trajectory exactly one stroke, in **page space** with the object's transform applied,
 *   its time measured from the stroke's own first sample (the blob carries no absolute time).
 * @property unitLengthCm `androidx.ink`'s `stroke_unit_length` for the stroke, when it was recorded.
 *   Generated strokes reuse the median of a user's, so a fountain pen's speed term behaves for them
 *   as it does for the user's own writing (docs/HANDWRITING-SYNTHESIS.md §6.3).
 */
public class CapturedStroke(
    public val id: ObjectId,
    public val trajectory: PenTrajectory,
    public val pen: PenStyle,
    public val unitLengthCm: Float?,
)

/**
 * SPEC §2.2 — read strokes back with **everything** the pen recorded.
 *
 * [StrokeSampler] exists for the recogniser and hands over `(x, y, t)` alone, because that is all
 * ML Kit takes. Learning someone's handwriting needs pressure, tilt and the brush as well, and only
 * `:android:ink` can decode the blob — so this is a second seam beside that one, with the same
 * shape and the same reason.
 *
 * ⚠️ **Never on the ink path and never on the UI thread** (CLAUDE.md rule 7). Implementations hop
 * off the caller's thread themselves.
 */
public fun interface StrokeReader {
    /**
     * @return the strokes among [ids] that are strokes and decode, keyed by id. Anything else —
     *   a shape, an undecodable blob, an id that is not on [page] — is simply absent.
     */
    public suspend fun read(page: FoldedPage, ids: Collection<ObjectId>): Map<ObjectId, CapturedStroke>
}

/**
 * One piece of ink to read as a single line of writing.
 *
 * @property area what ML Kit's `WritingArea` is sized to — the **line** the ink sits on, even when
 *   the ink is one word or one letter of it. A word read in a word-sized area has lost the height
 *   that tells an `o` from an `O`.
 * @property preContext the text before this ink, SPEC §8.1's language-model hint.
 */
public class InkToRead(
    public val strokes: List<SampledStroke>,
    public val area: PageRect,
    public val preContext: String? = null,
)

/**
 * One way the recogniser read some ink.
 *
 * @property score ML Kit's own number for the candidate, when it gives one. ⚠️ **Its range and
 *   direction are undocumented** (docs/HANDWRITING-SYNTHESIS.md §5.3), so it is recorded and never
 *   thresholded until it has been measured.
 */
public data class ReadCandidate(val text: String, val score: Float?)

/** What [InkReader.read] produced — or why it produced nothing. */
public sealed interface InkReading {
    /** One list of candidates per input, in the input's order, best first. Empty for empty ink. */
    public data class Read(val results: List<List<ReadCandidate>>) : InkReading

    /** No model, no Play Services, or a recogniser failure — see [RecognitionUnavailable]. */
    public data class Unavailable(val reason: RecognitionUnavailable) : InkReading
}

/**
 * SPEC §2.2 — read pieces of ink one at a time, with **alternatives**.
 *
 * [HandwritingRecogniser] takes a whole page, segments it, and keeps only the top guess per line —
 * right for search and the PDF's text layer, and too little for handwriting synthesis, which has
 * to ask *"does this word read as this text"* about one word (labelling, docs §5.3), one letter
 * (§5.4) or one generated phrase (the gate, §8.2). A sibling port rather than a change to that one,
 * so M7's behaviour and tests stay exactly as they are.
 */
public fun interface InkReader {
    /** @param candidates how many alternatives to ask for per input — ML Kit's `maxResultCount`. */
    public suspend fun read(inks: List<InkToRead>, candidates: Int): InkReading
}

/**
 * SPEC §2.2 — the synthesis network's weights file (docs/HANDWRITING-SYNTHESIS.md §4.4), as bytes the
 * platform reads: on Android, an APK asset. `:core` parses and checks it; it never opens a file itself.
 *
 * Null when there is no file. The neural backend is then off and glyph assembly carries on, which is
 * also what happens to a file that fails its own checksum or names a licence a release refuses.
 */
public fun interface ModelSource {
    public suspend fun bytes(): ByteArray?

    public companion object {
        /** No model: the neural backend stays off. */
        public val None: ModelSource = ModelSource { null }
    }
}
