package dev.inkstone.core.handwriting

import dev.inkstone.core.model.InkSample
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PenTrajectory
import dev.inkstone.core.model.SampledStroke
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What a [GlyphUnit] was cut as. */
public enum class UnitKind {
    /** A whole word the user wrote, exactly as its label says. */
    WORD,

    /** Several letters together: consecutive letters cut from one word, or letters the pen joined. */
    RUN,

    /** One letter. */
    LETTER,
}

/**
 * Where a unit came from — what [GlyphIndex.weightOf] uses to prefer writing near the drop point
 * (D15), and what makes two units the *same variant* (never used twice in a phrase while another
 * exists, §6.1).
 */
public data class UnitSource(
    val sampleId: String,
    val noteId: String,
    val pageId: String,
    val epochDay: Long,
    val strokes: List<Int>,
)

/**
 * ⭐ One piece of the user's own writing that assembly can copy — docs/HANDWRITING-SYNTHESIS.md §6.1.
 *
 * @property trajectory **normalised** (§5.2): x along the baseline in x-heights, starting at 0 at
 *   the ink's left edge; y **up** from the baseline, so 1 is the top of an `x`. Timing, pressure and
 *   tilt are the source's own.
 * @property width the ink's width, in the same units.
 * @property verified whether this exact piece was read back as its text: always for a [UnitKind.WORD]
 *   (its label came from two agreeing reads or from the user) and for a cut the harvester checked.
 * @property vouched whether the **user** says what it is — a quick-start prompt, a confirmed or corrected
 *   label — rather than the recogniser. §8.2's route for tokens the recogniser cannot judge builds only
 *   from single letters (each read back on its own) and vouched units: a number the harvester learned
 *   was labelled by the very reader that number's class has been measured not to trust.
 */
public class GlyphUnit(
    public val text: String,
    public val kind: UnitKind,
    public val trajectory: PenTrajectory,
    public val width: Float,
    public val source: UnitSource,
    public val verified: Boolean,
    public val vouched: Boolean = false,
) {
    public val letters: List<String> = writtenUnits(text)
}

/** The letter classes gaps and joins fall back to when a pair of letters has never been seen. */
public enum class LetterClass {
    LOWER,
    UPPER,
    DIGIT,
    OTHER,
    ;

    public companion object {
        public fun of(letter: String): LetterClass {
            val c = letter.firstOrNull() ?: return OTHER
            return when {
                c.isDigit() -> DIGIT
                c.isLetter() && c.isUpperCase() -> UPPER
                c.isLetter() -> LOWER
                else -> OTHER
            }
        }
    }
}

/** How often the pen stayed down between two letters, and how often it lifted. */
public data class JoinCount(val joined: Int = 0, val lifted: Int = 0) {
    public val total: Int get() = joined + lifted

    public operator fun plus(other: JoinCount): JoinCount = JoinCount(joined + other.joined, lifted + other.lifted)
}

/**
 * ⭐ What assembly needs to know about a hand besides its letters — P0 of §8.1, measured.
 *
 * Every distribution is the user's own: gaps between letters and between words, how long the pen
 * is up between strokes, and **where the pen stays down between letters** (§6.1's joins). A mixed
 * hand comes out mixed in the same places the user's does because these are counted per letter
 * pair, falling back to the pair's letter classes and then to the hand's overall habit.
 *
 * @property xHeightPt the median x-height the user writes at, in points: the size a phrase falls
 *   back to when nothing on the page says otherwise (§6.2).
 * @property slantRad the median slant.
 * @property letterGaps gaps between the inks of two neighbouring letters the pen lifted between, in
 *   x-heights, per pair of letter classes.
 * @property wordGaps gaps between neighbouring words on one line, in x-heights.
 * @property penUpMs the median time the pen is lifted between two strokes of a word.
 */
public class HandStats(
    public val xHeightPt: Float,
    public val slantRad: Float,
    public val angleRad: Float,
    private val letterGaps: Map<Pair<LetterClass, LetterClass>, List<Float>>,
    private val allLetterGaps: List<Float>,
    public val wordGaps: List<Float>,
    public val penUpMs: Int,
    private val pairJoins: Map<String, JoinCount>,
    private val classJoins: Map<Pair<LetterClass, LetterClass>, JoinCount>,
) {
    /** The measured gaps between [left] and [right], or the hand's gaps in general, or a default. */
    public fun letterGapsFor(left: String, right: String): List<Float> = (
        letterGaps[LetterClass.of(left) to LetterClass.of(right)]?.takeIf { it.size >= MIN_GAPS }
            ?: allLetterGaps.takeIf { it.isNotEmpty() }
            ?: DEFAULT_LETTER_GAPS
        ).map { it.coerceAtLeast(MIN_LETTER_GAP) }

    public fun wordGapsOrDefault(): List<Float> = wordGaps.takeIf { it.size >= MIN_GAPS } ?: DEFAULT_WORD_GAPS

    /**
     * Whether this hand joins [left] to [right]: by the pair itself when it has been seen often
     * enough, then by the pair's letter classes, then never. *Never* is the honest default — a join
     * nobody has seen is a guess (§6.1), and a lifted pair is still legible.
     */
    public fun joins(left: String, right: String): Boolean {
        pairJoins[left + right]?.takeIf { it.total >= MIN_PAIR_JOINS }?.let { return it.joined > it.lifted }
        val classes = LetterClass.of(left) to LetterClass.of(right)
        classJoins[classes]?.takeIf { it.total >= MIN_CLASS_JOINS }?.let { return it.joined > it.lifted }
        return false
    }

    /** The counts behind [joins], for the notes and the harness. */
    public fun joinCount(left: String, right: String): JoinCount = pairJoins[left + right] ?: JoinCount()

    public companion object {
        private const val MIN_GAPS = 3

        /**
         * The most two letters from different words may overlap, in x-heights. Inside one word a `t`'s
         * bar reaches over its neighbour and the measured gap is negative, rightly; between letters
         * that never met it makes them collide (*"not"* drawn as one blot on the first sheet).
         */
        private const val MIN_LETTER_GAP = -0.15f
        private const val MIN_PAIR_JOINS = 2
        private const val MIN_CLASS_JOINS = 4

        /** Estimates, used only until a hand has been measured: a gap of a fifth of an x-height. */
        private val DEFAULT_LETTER_GAPS = listOf(0.15f, 0.2f, 0.25f)

        /**
         * Estimates, used only until a hand or a page has been measured: a word space of about one and
         * a third x-heights. The first sheet's 0.8–1.2 read as words run together beside the user's own
         * spacing; a page's measured gaps replace this at drop time (D15, `MeasuredLine.wordGaps`).
         */
        private val DEFAULT_WORD_GAPS = listOf(1.15f, 1.35f, 1.55f)
    }
}

/**
 * ⭐ Every unit assembly can use, built from a profile — docs/HANDWRITING-SYNTHESIS.md §6.1.
 *
 * Built from what the profile holds and nothing else, so it is derived data: `glyphs.idx` in §4.2 is
 * this, kept in memory. It is cheap to rebuild — a pass over the samples — and is rebuilt whenever
 * the profile changes.
 *
 * ## What becomes a unit
 *
 * - **Words**, under the label the user's corrections give them. Never a held-out word (§5.5), never
 *   one the user removed, never one from a note they excluded.
 * - **Letters and runs of letters** cut out by the harvester (§5.4), only when the word's label was
 *   not corrected: a correction invalidates cuts made against the old label.
 * - **Runs** of two and three consecutive letters wherever a word's cuts cover every letter, so the
 *   user's real spacing inside a word is copied rather than invented.
 *
 * Nothing synthetic is ever added. A letter the user has never written is simply absent, and
 * assembly refuses the word that needs it.
 */
public class GlyphIndex private constructor(
    private val units: Map<String, List<GlyphUnit>>,
    public val stats: HandStats,
    public val longestUnit: Int,
    /** Every cut letter judged by [LetterFit], in sample order — what the sheet report prints. */
    public val placements: List<LetterPlacement>,
    /** Words not used whole because their ink holds more than their letters ([carriesStrayInk]). */
    public val strayInkWords: List<String>,
) {
    public val isEmpty: Boolean get() = units.isEmpty()

    /** Every variant of [text], or empty. */
    public fun variants(text: String): List<GlyphUnit> = units[text].orEmpty()

    /** Every whole word the user wrote — what the neural backend primes with (§7.4, D15). */
    public fun wholeWords(): List<GlyphUnit> = units.values.flatten().filter { it.kind == UnitKind.WORD }

    /** Every character (written unit) some variant can write. */
    public val alphabet: Set<String> by lazy { units.values.flatten().flatMap { it.letters }.toSet() }

    public val unitCount: Int get() = units.values.sumOf { it.size }

    public companion object {
        /** Runs longer than this are only ever whole words or joined cuts. */
        private const val MAX_RUN = 3
        private const val MIN_WIDTH = 1e-3f
        private const val MIN_WORD_GAP = 0.1f
        private const val MAX_WORD_GAP = 4f
        private const val SAME_LINE_X_HEIGHTS = 0.5f
        private const val DEFAULT_X_HEIGHT_PT = 5f
        private const val DEFAULT_PEN_UP_MS = 120
        private const val MAX_PEN_UP_MS = 1500

        public val Empty: GlyphIndex = build(emptyList(), LabelBook())

        public fun build(samples: List<WordSample>, labels: LabelBook): GlyphIndex {
            val live = samples.filter {
                it.sourceKey !in labels.rejected &&
                    it.noteId.value !in labels.excludedNotes &&
                    it.split == SampleSplit.TRAIN
            }
            val into = Tally()
            for (sample in live) into.add(sample, labels.labelOf(sample), labels.sourceOf(sample) != LabelSource.AGREED)
            val stats = statsOf(live, into.gaps, into.joins)
            val longest = into.units.keys.maxOfOrNull { writtenUnits(it).size } ?: 0
            return GlyphIndex(into.units, stats, longest, into.placements, into.strayInkWords)
        }

        /**
         * [sample] as [build] would hold it as a whole word — normalised, self-scaled, its ink starting at
         * x = 0 — or null when [build] would not use it: a cut letter that does not sit where its kind
         * belongs means the frame is wrong for the word ([LetterFit]). Whatever the split: this is how
         * fidelity reads the user's **held-out** words (§8.3) on exactly the terms assembly's units are
         * read, so a difference between them is the backend's and not the normaliser's.
         */
        public fun normalisedWord(sample: WordSample, labels: LabelBook): PenTrajectory? {
            val label = labels.labelOf(sample)
            val cuts = if (label == sample.label) cutsOf(sample) else null
            val normal = sample.frame.normalise(sample.trajectory)?.let { selfScaled(it, cuts) } ?: return null
            if (placementsOf(sample, label, cuts, normal).any { !it.fits }) return null
            val box = normal.bounds()
            return normal.transformed(Matrix3.translate(-box.left, 0f))
        }

        /** What [build] gathers, one sample at a time. */
        private class Tally {
            val units = HashMap<String, MutableList<GlyphUnit>>()
            val gaps = HashMap<Pair<LetterClass, LetterClass>, MutableList<Float>>()
            val joins = HashMap<String, JoinCount>()
            val placements = ArrayList<LetterPlacement>()
            val strayInkWords = ArrayList<String>()

            /**
             * The word under [label], its cuts when the label is still the one they were cut under,
             * the runs and gaps those cuts imply, and its joins.
             *
             * ⭐ A word whose cut letters do not sit where their kinds belong ([LetterFit]) gives no
             * units and no gaps: its frame is wrong, so everything cut from it is at the wrong height.
             * Its joins still count — they are about where the pen lifted, not about where the line is.
             */
            fun add(sample: WordSample, label: String, vouched: Boolean) {
                val cuts = if (label == sample.label) cutsOf(sample) else null
                val normal = sample.frame.normalise(sample.trajectory)?.let { selfScaled(it, cuts) } ?: return
                val judged = placementsOf(sample, label, cuts, normal)
                placements += judged
                if (judged.any { !it.fits }) {
                    joinsOf(label, cuts, normal, joins)
                    return
                }
                val source = UnitSource(
                    sample.id,
                    sample.noteId.value,
                    sample.pageId.value,
                    sample.epochDay,
                    emptyList(),
                )
                if (carriesStrayInk(label, normal)) {
                    strayInkWords += label
                } else {
                    keep(
                        unitOf(
                            label,
                            UnitKind.WORD,
                            normal,
                            (0 until normal.strokeCount).toList(),
                            source,
                            true,
                            vouched,
                        ),
                    )
                }
                cuts?.forEach { span -> keep(cutUnit(span, normal, source, vouched)) }
                if (cuts != null && cuts.sumOf { it.letters.size } == writtenUnits(label).size) {
                    runsOf(cuts, normal, source, vouched).forEach(::keep)
                    gapsOf(cuts, normal, gaps)
                }
                joinsOf(label, cuts, normal, joins)
            }

            private fun keep(unit: GlyphUnit?) {
                if (unit != null) units.getOrPut(unit.text) { ArrayList() } += unit
            }

            /** One cut as a unit, or null when it is not one to copy. */
            private fun cutUnit(span: Cut, normal: PenTrajectory, source: UnitSource, vouched: Boolean): GlyphUnit? {
                val kind = if (span.letters.size == 1) UnitKind.LETTER else UnitKind.RUN
                // A subscript or exponent digit is right inside its formula and wrong alone (§18.10).
                if (kind == UnitKind.LETTER && isScript(span, normal)) return null
                val unit = unitOf(span.text, kind, normal, span.strokes, source, true, vouched) ?: return null
                // ⭐ H5: a cut letter can carry a stray mark as a whole word can — v1 hands a delayed
                // stroke to the letter whose x-range covers it, so a full stop after `N` became part of
                // the `N`, and the maths sheet drew `NO2` as `N·O2` from verified letters. Letters and
                // digits only: `=`, `:` and `"` are two marks by right.
                val plain = kind == UnitKind.LETTER && plainEnd(span.text)
                return unit.takeUnless { plain && carriesStrayInk(span.text, it.trajectory) }
            }
        }

        /** Lower-case letters with neither ascender nor descender: their tops are the x-height. */
        private const val X_LETTERS = "acemnorsuvwxz"
        private const val SCALE_TOLERANCE = 0.2f
        private const val MIN_SELF_SCALE = 0.4f
        private const val MAX_SELF_SCALE = 2.5f

        /**
         * ⭐ A word whose own x-height letters do not stand one x-height tall, rescaled until they do.
         *
         * A sample's frame is its *line's* (§5.2), and a line's x-height can be wrong for the word: on
         * the first sheet `CaCO3` came out twice the size of every other word, because its line — capitals,
         * subscripts and an arrow — measured an x-height half the height of its one `a`. The cut letters
         * say what the word's own x-height is, so they correct it; a word with no such letter cut is left
         * as its line measured it.
         */
        private fun selfScaled(normal: PenTrajectory, cuts: List<Cut>?): PenTrajectory {
            val tops = cuts.orEmpty()
                .filter { it.letters.size == 1 && it.text.single() in X_LETTERS }
                .mapNotNull { cut -> normal.strokes(cut.strokes.sorted())?.bounds()?.bottom }
                .filter { it > 0f }
            if (tops.isEmpty()) return normal
            val measured = median(tops)
            if (abs(measured - 1f) <= SCALE_TOLERANCE) return normal
            val scale = (1f / measured).coerceIn(MIN_SELF_SCALE, MAX_SELF_SCALE)
            return normal.transformed(Matrix3.scale(scale)) ?: normal
        }

        /** A mark this small, in x-heights, standing off the end of a word is a stray dot. */
        private const val STRAY_SIZE = 0.3f

        /** How far clear of the rest of the word's ink a stray mark stands, in x-heights. */
        private const val STRAY_GAP = 0.1f

        /** The x-height a word is clustered at for [carriesStrayInk]: the gate's own reading size. */
        private const val CLUSTER_X_HEIGHT_PT = 5.5f

        /**
         * ⭐ Whether a learned word's ink holds more than its letters (§18.7). Its cuts were each read
         * back on their own, but the word itself was read as a whole, and ML Kit reads past a bracket
         * or a dot: the tablet's `a` came with the `)` written after it, and its `th` with a dot in
         * front. Used whole, that ink goes into every word assembled from them — a dot before every
         * `the`. Such a word still gives its cut letters; only the whole-word unit is not kept.
         *
         * Two signs: more letter-sized clusters than letters, or a mark no bigger than a dot standing
         * clear of either end, where the label has no punctuation and no dotted letter.
         */
        internal fun carriesStrayInk(label: String, normal: PenTrajectory): Boolean {
            val letters = writtenUnits(label)
            val strokes = sampledStrokesOf(normal, flipY = true)
            if (strokes.isEmpty() || letters.isEmpty()) return false
            // ⚠️ Clustered at a writing size, not in x-heights: GlyphClusters' widths are points
            // (MIN_WIDTH_PT), and in the normalised frame its 0.25 pt floor became 0.25 x-heights, so
            // a stroke narrower than that, the legs of a `π` and a straight `t` stem among them, could
            // not join the stroke it sat under. A cut letter was then "more clusters than letters" and
            // dropped (docs/HANDWRITING-MATHS.md §2.4).
            val atSize = normal.transformed(Matrix3.scale(CLUSTER_X_HEIGHT_PT))?.let {
                sampledStrokesOf(it, flipY = true)
            }
            if (GlyphClusters.of(atSize ?: strokes).size > letters.size) return true
            val boxes = strokes.map { it.bounds }
            val tiny = boxes.map { max(it.width, it.height) < STRAY_SIZE }
            val body = boxes.filterIndexed { i, _ -> !tiny[i] }
            if (body.isEmpty()) return false
            val left = body.minOf { it.left }
            val right = body.maxOf { it.right }
            val strayBefore = plainEnd(letters.first()) &&
                boxes.indices.any { tiny[it] && boxes[it].right < left - STRAY_GAP }
            val strayAfter = plainEnd(letters.last()) &&
                boxes.indices.any { tiny[it] && boxes[it].left > right + STRAY_GAP }
            return strayBefore || strayAfter
        }

        /** A letter or digit with no dot of its own: a mark beside it is not part of it. */
        private fun plainEnd(letter: String): Boolean =
            letter.singleOrNull()?.let { it.isLetterOrDigit() && it !in "ij" } ?: false

        private fun isScript(span: Cut, normal: PenTrajectory): Boolean {
            val box = normal.strokes(span.strokes.sorted())?.bounds() ?: return false
            // Normalised space is y up, so a PageRect's `bottom` is the ink's highest point.
            return LetterFit.isScript(span.text, low = box.top, high = box.bottom)
        }

        /** Each single-letter cut of the word: where its ink runs, in the word's normalised frame. */
        private fun placementsOf(
            sample: WordSample,
            label: String,
            cuts: List<Cut>?,
            normal: PenTrajectory,
        ): List<LetterPlacement> = cuts.orEmpty()
            .filter { it.letters.size == 1 }
            .mapNotNull { cut ->
                val box = normal.strokes(cut.strokes.sorted())?.bounds() ?: return@mapNotNull null
                // Normalised space is y up, so a PageRect's `top` is the ink's lowest point.
                LetterPlacement(sample.id, label, cut.text, low = box.top, high = box.bottom)
            }

        /** A sample's cuts in letter order, each knowing where in the word it starts. */
        private fun cutsOf(sample: WordSample): List<Cut>? {
            if (sample.glyphs.isEmpty()) return null
            val letters = writtenUnits(sample.label)
            val cuts = ArrayList<Cut>()
            var position = 0
            for (glyph in sample.glyphs) {
                val wanted = writtenUnits(glyph.text)
                // Glyphs are stored left to right; find this one at or after the last.
                val found = (position..letters.size - wanted.size)
                    .firstOrNull { letters.subList(it, it + wanted.size) == wanted }
                    ?: return cuts.ifEmpty { null }
                cuts += Cut(glyph.text, wanted, found, glyph.strokes)
                position = found + wanted.size
            }
            return cuts
        }

        private class Cut(val text: String, val letters: List<String>, val start: Int, val strokes: List<Int>)

        @Suppress("LongParameterList")
        private fun unitOf(
            text: String,
            kind: UnitKind,
            normal: PenTrajectory,
            strokes: List<Int>,
            source: UnitSource,
            verified: Boolean,
            vouched: Boolean,
        ): GlyphUnit? {
            val piece = normal.strokes(strokes.sorted()) ?: return null
            val box = piece.bounds()
            if (box.width < MIN_WIDTH && box.height < MIN_WIDTH) return null
            val shifted = piece.transformed(Matrix3.translate(-box.left, 0f)) ?: return null
            return GlyphUnit(text, kind, shifted, box.width, source.copy(strokes = strokes.sorted()), verified, vouched)
        }

        /**
         * Two- and three-letter runs out of a word whose cuts cover every letter. Never the whole
         * word, which is a unit already.
         */
        private fun runsOf(
            cuts: List<Cut>,
            normal: PenTrajectory,
            source: UnitSource,
            vouched: Boolean,
        ): List<GlyphUnit> {
            val runs = ArrayList<GlyphUnit>()
            for (length in 2..minOf(MAX_RUN, cuts.size - 1)) {
                for (first in 0..cuts.size - length) {
                    val pieces = cuts.subList(first, first + length)
                    val contiguous = pieces.zipWithNext().all { (a, b) -> a.start + a.letters.size == b.start }
                    if (!contiguous) continue
                    val text = pieces.joinToString("") { it.text }
                    unitOf(text, UnitKind.RUN, normal, pieces.flatMap { it.strokes }, source, true, vouched)?.let {
                        runs +=
                            it
                    }
                }
            }
            return runs
        }

        /** The gap between each two neighbouring cuts, which the pen lifted between by construction. */
        private fun gapsOf(
            cuts: List<Cut>,
            normal: PenTrajectory,
            into: MutableMap<Pair<LetterClass, LetterClass>, MutableList<Float>>,
        ) {
            val boxes = cuts.map { normal.strokes(it.strokes.sorted())?.bounds() }
            for (i in 0 until cuts.size - 1) {
                val left = boxes[i]
                val right = boxes[i + 1]
                if (left == null || right == null) continue
                val key = LetterClass.of(cuts[i].letters.last()) to LetterClass.of(cuts[i + 1].letters.first())
                into.getOrPut(key) { ArrayList() } += right.left - left.right
            }
        }

        /**
         * Where this word's pen stayed down between letters. A cut holding several letters was one
         * piece of ink, so they were joined; two cuts side by side were lifted between. A word with no
         * cuts still says something: one cluster per letter means every pair lifted, and one cluster
         * for the whole word means every pair joined. A word in between is shared out by
         * [GlyphClusters.partition] — unverified, but these are counts, not ink.
         */
        private fun joinsOf(
            label: String,
            cuts: List<Cut>?,
            normal: PenTrajectory,
            into: MutableMap<String, JoinCount>,
        ) {
            val letters = writtenUnits(label)
            if (letters.size < 2) return
            val groupOf = IntArray(letters.size) { -1 }
            if (cuts != null && cuts.sumOf { it.letters.size } == letters.size) {
                cuts.forEachIndexed { index, cut -> for (i in cut.letters.indices) groupOf[cut.start + i] = index }
            } else {
                val strokes = sampledStrokesOf(normal, flipY = true)
                val clusters = GlyphClusters.of(strokes)
                val widths = clusters.map { c -> c.map { strokes[it].bounds }.reduce(PageRect::union).width }
                val shares = GlyphClusters.partition(widths, letters) ?: return
                var at = 0
                shares.forEachIndexed { index, count -> repeat(count) { groupOf[at++] = index } }
            }
            for (i in 0 until letters.size - 1) {
                if (groupOf[i] < 0 || groupOf[i + 1] < 0) continue
                val count = if (groupOf[i] == groupOf[i + 1]) JoinCount(joined = 1) else JoinCount(lifted = 1)
                val key = letters[i] + letters[i + 1]
                into[key] = (into[key] ?: JoinCount()) + count
            }
        }

        private fun statsOf(
            live: List<WordSample>,
            gaps: Map<Pair<LetterClass, LetterClass>, List<Float>>,
            joins: Map<String, JoinCount>,
        ): HandStats {
            val classJoins = HashMap<Pair<LetterClass, LetterClass>, JoinCount>()
            for ((pair, count) in joins) {
                val letters = writtenUnits(pair)
                if (letters.size != 2) continue
                val key = LetterClass.of(letters[0]) to LetterClass.of(letters[1])
                classJoins[key] = (classJoins[key] ?: JoinCount()) + count
            }
            return HandStats(
                xHeightPt = live.map { it.frame.xHeightPt }.ifEmpty { listOf(DEFAULT_X_HEIGHT_PT) }.let(::median),
                slantRad = live.map { it.frame.slantRad }.ifEmpty { listOf(0f) }.let(::median),
                angleRad = live.map { it.frame.angleRad }.ifEmpty { listOf(0f) }.let(::median),
                letterGaps = gaps,
                allLetterGaps = gaps.values.flatten(),
                wordGaps = wordGapsOf(live),
                penUpMs = penUpOf(live),
                pairJoins = joins,
                classJoins = classJoins,
            )
        }

        /**
         * The gaps between words learned from one line of one page — the only place two words'
         * spacing can be read, because each sample is one word.
         *
         * ⚠️ **Never the quick start's** (§18.10). Its prompt asks for *"a clear gap between
         * words"*, because that is how it finds them, so its gaps are an instruction followed and not
         * a hand: on the tablet's profile they were 34 of the 35 gaps measured, median 1.7 x-heights
         * and up to 3.4, against 1.17–1.28 on the user's own pages. *"The spacing between words is a
         * bit big."*
         */
        private fun wordGapsOf(live: List<WordSample>): List<Float> {
            val gaps = ArrayList<Float>()
            val fromNotes = live.filter { it.noteId.value != QuickStart.NOTE_ID }
            for (page in fromNotes.groupBy { it.noteId to it.pageId }.values) {
                val boxes = page.map { it to it.trajectory.bounds() }
                for ((sample, box) in boxes) {
                    val xh = sample.frame.xHeightPt
                    val next = boxes
                        .filter { (other, otherBox) ->
                            other !== sample &&
                                otherBox.left >= box.right - xh * MIN_WORD_GAP &&
                                abs(other.frame.originY - sample.frame.originY) < xh * SAME_LINE_X_HEIGHTS
                        }
                        .minByOrNull { it.second.left } ?: continue
                    val gap = (next.second.left - box.right) / xh
                    if (gap in MIN_WORD_GAP..MAX_WORD_GAP) gaps += gap
                }
            }
            return gaps
        }

        private fun penUpOf(live: List<WordSample>): Int {
            val gaps = ArrayList<Float>()
            for (sample in live) {
                val t = sample.trajectory
                for (stroke in 1 until t.strokeCount) {
                    val up = t.tMs[t.strokeStart(stroke)] - t.tMs[t.strokeEnd(stroke - 1) - 1]
                    if (up in 1..MAX_PEN_UP_MS) gaps += up.toFloat()
                }
            }
            return if (gaps.isEmpty()) DEFAULT_PEN_UP_MS else median(gaps).toInt()
        }
    }
}

/**
 * A trajectory's strokes as the recogniser and the clusterer see them. [flipY] turns a normalised
 * trajectory (y up) back into page orientation (y down), which is what every box test in
 * `:core:search` assumes.
 */
internal fun sampledStrokesOf(
    trajectory: PenTrajectory,
    flipY: Boolean = false,
    timeBase: Long = 0L,
): List<SampledStroke> = (0 until trajectory.strokeCount).map { stroke ->
    val samples = ArrayList<InkSample>()
    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    for (i in trajectory.strokeStart(stroke) until trajectory.strokeEnd(stroke)) {
        val x = trajectory.x[i]
        val y = if (flipY) -trajectory.y[i] else trajectory.y[i]
        samples += InkSample(x, y, timeBase + trajectory.tMs[i])
        left = min(left, x)
        right = max(right, x)
        top = min(top, y)
        bottom = max(bottom, y)
    }
    SampledStroke(ObjectId("s$stroke"), samples, PageRect(left, top, right, bottom))
}

/**
 * One cut letter and where its ink runs in its word's normalised frame (y up, baseline 0, x-height 1).
 * [fits] is [LetterFit]'s verdict; a word with any letter that does not fit is not assembled from.
 */
public data class LetterPlacement(
    val sampleId: String,
    val word: String,
    val letter: String,
    val low: Float,
    val high: Float,
) {
    val fits: Boolean get() = LetterFit.fits(letter, low, high)
}
