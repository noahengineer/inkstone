package dev.inkstone.core.handwriting

import dev.inkstone.core.handwriting.quality.Backend
import dev.inkstone.core.handwriting.quality.Fidelity
import dev.inkstone.core.handwriting.quality.Route
import dev.inkstone.core.model.GeneratedInk
import dev.inkstone.core.model.InkReader
import dev.inkstone.core.model.InkReading
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PenTrajectory
import dev.inkstone.core.model.ReadCandidate
import kotlin.math.roundToInt

/**
 * ⭐ docs/HANDWRITING-SYNTHESIS.md §11's contact sheet — one sentence, [TIMES] times, per backend and
 * per neatness — defined **once**, so the JVM's SVG sheet and the debug screen's `androidx.ink` sheet
 * are the same sheet by construction: the same seeds, the same style, the same rows, so the same ink
 * reaches two renderers. M4's lesson is why there are two: a picture checked only by the library that
 * drew it proves nothing about what anyone else sees.
 *
 * - **Assembly's blocks** are written with no network at all, so a word assembly cannot build is
 *   refused, and the row says so: the block is assembly alone.
 * - **The network's blocks** are written as the *Model* setting writes (§8.4): the network first, for
 *   every word it can write, and assembly only for a word the network failed. The row says which
 *   backend wrote each word ([Row.caption]), so a block that is not purely the network's says so.
 * - **Rows are laid out in page points** from [Block.top], so a block can be drawn as one page.
 *
 * [fingerprint] is a hash of every point of a block, quantised to [FINGERPRINT_PT]: printed by both
 * sides, it says whether the two renderers were really handed the same ink.
 */
public object ContactSheet {
    public const val TIMES: Int = 12
    public val NEATNESSES: List<Float> = listOf(0f, 0.5f, 1f)
    public val BACKENDS: List<Backend> = listOf(Backend.ASSEMBLY, Backend.NEURAL)

    /** The sheet's writing size and page: an x-height of 9 pt, rows of up to 860 pt. */
    public const val X_HEIGHT_PT: Float = 9f
    public const val WRAP_WIDTH_PT: Float = 860f
    public const val LINE_PITCH_PT: Float = 30f
    public const val MARGIN_PT: Float = 20f
    public const val PAGE_WIDTH_PT: Float = WRAP_WIDTH_PT + 2 * MARGIN_PT

    /** Room above a row's first baseline for ascenders and capitals, and below its last for descenders. */
    public const val ABOVE_PT: Float = 24f
    public const val BELOW_PT: Float = 14f

    /** Coordinates are compared at a hundredth of a point: far finer than a pen, far coarser than an ulp. */
    public const val FINGERPRINT_PT: Float = 0.01f

    /** One phrase: the [seed] it was written with, where its first baseline starts, and the result. */
    public class Row(public val seed: Long, public val origin: PagePoint, public val phrase: WrittenPhrase) {
        /** Which backend wrote each word — `[word:net]` for the network's — and why a refused one was. */
        public val caption: String
            get() = phrase.words.joinToString(" ") { word ->
                when (word) {
                    is WordOutcome.Written -> if (word.backend == GeneratedInk.NEURAL) {
                        "[${word.text}:net]"
                    } else {
                        word.text
                    }

                    is WordOutcome.Refused -> "{${word.text}: ${word.reason}}"
                }
            }
    }

    /** [TIMES] rows of one backend at one neatness, in page points from [top] to [bottom]. */
    public class Block(
        public val backend: Backend,
        public val neatness: Float,
        public val top: Float,
        public val bottom: Float,
        public val rows: List<Row>,
    ) {
        public val title: String get() = "${backend.name.lowercase()} · neatness $neatness"

        /**
         * A 64-bit FNV-1a over every point of every row, quantised to [FINGERPRINT_PT], as hex. Each row is
         * hashed from its own origin, so where a renderer stacks its blocks does not change the answer.
         */
        public fun fingerprint(): String {
            var hash = FNV_OFFSET
            fun mix(value: Int) {
                var v = value
                repeat(Int.SIZE_BYTES) {
                    hash = (hash xor (v and BYTE_MASK).toLong()) * FNV_PRIME
                    v = v ushr Byte.SIZE_BITS
                }
            }
            for (row in rows) {
                for (stroke in row.phrase.strokes(PagePoint(0f, 0f))) {
                    mix(stroke.pointCount)
                    for (i in 0 until stroke.pointCount) {
                        mix((stroke.x[i] / FINGERPRINT_PT).roundToInt())
                        mix((stroke.y[i] / FINGERPRINT_PT).roundToInt())
                    }
                }
            }
            return hash.toULong().toString(HEX).padStart(HEX_DIGITS, '0')
        }

        /**
         * §11's variation, measured: [Fidelity.distance] between the same word in every two rows, in
         * the normalised frame. Refused words are left out, so on a device this is the spread of what
         * the gate let through. Null when no word was written twice.
         */
        public fun spread(): Spread? {
            val distances = ArrayList<Float>()
            val words = rows.maxOfOrNull { it.phrase.words.size } ?: 0
            for (k in 0 until words) {
                val inks = rows.mapNotNull { normalisedWord(it, k) }
                for (i in inks.indices) {
                    for (j in i + 1 until inks.size) Fidelity.distance(inks[i], inks[j])?.let(distances::add)
                }
            }
            if (distances.isEmpty()) return null
            distances.sort()
            return Spread(
                pairs = distances.size,
                min = distances.first(),
                p50 = distances[distances.size / 2],
                p95 = distances[distances.size * P95 / PERCENT],
                max = distances.last(),
            )
        }
    }

    /** Distances between two copies of one word, in x-heights. */
    public class Spread(
        public val pairs: Int,
        public val min: Float,
        public val p50: Float,
        public val p95: Float,
        public val max: Float,
    ) {
        override fun toString(): String =
            "pairs=$pairs min=${min.twoPlaces()} p50=${p50.twoPlaces()} p95=${p95.twoPlaces()} max=${max.twoPlaces()}"

        private fun Float.twoPlaces(): String = ((this * HUNDRED).roundToInt() / HUNDRED.toFloat()).toString()
    }

    /** Row [row]'s [k]th word back in the normalised frame (x-heights, y up), or null if it was refused. */
    public fun normalisedWord(row: Row, k: Int): PenTrajectory? {
        val word = row.phrase.words.getOrNull(k) as? WordOutcome.Written ?: return null
        val style = row.phrase.style
        return NormalFrame(0f, 0f, style.angleRad, style.slantRad, style.xHeightPt).normalise(word.ink)
    }

    /** The style every row is written in: the sheet's size, the hand's own slant, level baselines. */
    public fun style(neatness: Float, slantRad: Float): WriteStyle = WriteStyle(
        xHeightPt = X_HEIGHT_PT,
        slantRad = slantRad,
        angleRad = 0f,
        wrapWidthPt = WRAP_WIDTH_PT,
        linePitchPt = LINE_PITCH_PT,
        neatness = neatness,
    )

    /**
     * One block: [sentence] written with seeds `1..times`, its rows stacked from [top]. Null for the
     * network's block when there is no network ([neural] null).
     */
    @Suppress("LongParameterList")
    public suspend fun block(
        backend: Backend,
        neatness: Float,
        sentence: String,
        index: GlyphIndex,
        gate: ReliabilityGate,
        neural: NeuralBackend?,
        top: Float = 0f,
        times: Int = TIMES,
        onRow: (Row) -> Unit = {},
    ): Block? {
        val synthesizer = when (backend) {
            Backend.ASSEMBLY -> HandwritingSynthesizer(gate)
            Backend.NEURAL -> HandwritingSynthesizer(gate, neural ?: return null, Route(Backend.NEURAL))
        }
        val style = style(neatness, index.stats.slantRad)
        val rows = ArrayList<Row>(times)
        var y = top
        for (seed in 1L..times.toLong()) {
            val phrase = synthesizer.write(WriteRequest(sentence, style, seed), index)
            val origin = PagePoint(MARGIN_PT, y + ABOVE_PT)
            rows += Row(seed, origin, phrase).also(onRow)
            y += ABOVE_PT + (maxOf(phrase.lineCount, 1) - 1) * LINE_PITCH_PT + BELOW_PT
        }
        return Block(backend, neatness, top, y, rows)
    }

    /**
     * ⚠️ **A reader that agrees with everything** — every ink reads as every word of [sentence]. It is
     * what lets a sheet show *what a backend offers* where ML Kit does not run (the JVM), and it proves
     * nothing about legibility: that is the gate's question, asked of the real recogniser on a device
     * (`GateMlKitTest`). M6's `FakeMergeGate` is the warning; this one is named for what it does.
     */
    public fun trusting(sentence: String): InkReader {
        val words = HandwritingSynthesizer.tokensOf(sentence).map { ReadCandidate(it.text, null) }
        return InkReader { inks, _ -> InkReading.Read(inks.map { words }) }
    }

    private const val P95 = 95
    private const val PERCENT = 100
    private const val HUNDRED = 100
    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L
    private const val BYTE_MASK = 0xFF
    private const val HEX = 16
    private const val HEX_DIGITS = 16
}
