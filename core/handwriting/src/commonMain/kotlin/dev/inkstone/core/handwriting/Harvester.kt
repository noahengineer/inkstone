package dev.inkstone.core.handwriting

import dev.inkstone.core.model.CapturedStroke
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.InkReader
import dev.inkstone.core.model.InkReading
import dev.inkstone.core.model.InkToRead
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageText
import dev.inkstone.core.model.PenKind
import dev.inkstone.core.model.PenTrajectory
import dev.inkstone.core.model.ReadCandidate
import dev.inkstone.core.model.RecognisedLine
import dev.inkstone.core.model.RecognitionUnavailable
import dev.inkstone.core.model.SampledStroke
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.StrokeReader
import dev.inkstone.core.model.StrokeSampler
import dev.inkstone.core.model.TextLine
import dev.inkstone.core.search.LineSegmenter
import dev.inkstone.core.search.WordBoxes
import dev.inkstone.core.search.WordStrokes
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One page, ready to learn from. [text] must be **current** — recognised from exactly the ink on
 * [page] (`NoteSession.recognisedText` re-checks the digest) — because the harvester labels with it.
 *
 * @property ledger which of [page]'s strokes this device's pen drew ([PenLedger]).
 * @property epochDay today, for the samples' [WordSample.epochDay].
 */
public class PageInput(
    public val noteId: DocumentId,
    public val page: FoldedPage,
    public val text: PageText,
    public val ledger: Map<ObjectId, LedgerEntry>,
    public val epochDay: Long,
)

/** @property learnFromOlderNotes [ProfileState.learnFromOlderNotes]: may strokes the ledger cannot vouch for be learned? */
public data class HarvestPolicy(val learnFromOlderNotes: Boolean)

/**
 * How far a page got, step by step, for the review screen and the phase notes. Each count is a subset
 * of the one before it, so the place a page's writing was lost can be read straight off the line.
 *
 * @property linesFound lines `LineSegmenter` found in the ink.
 * @property linesRead those that meet a line M7 recognised.
 * @property linesMiscounted of those, the lines whose gaps did not split into as many words as M7
 *   read, so no stroke on them can be given its word.
 * @property linesSplit those whose gaps split into exactly as many words as M7 read, with a style
 *   that could be measured.
 * @property wordsSeen the words on those lines.
 * @property wordsEligible those whose strokes are the user's own, unchanged, and not yet learned.
 * @property skipped why each of the others was not, by its first reason.
 * @property wordsAgreed those a second, word-level read gave the same best answer for.
 * @property wordsSteady those whose second read also *repeated* the answer among its other guesses.
 *   These are the ones learned ([Harvester.MIN_ECHOES]).
 * @property glyphsKept letters cut out of the steady words and read back as themselves.
 */
public data class HarvestStats(
    val linesFound: Int = 0,
    val linesRead: Int = 0,
    val linesMiscounted: Int = 0,
    val linesSplit: Int = 0,
    val wordsSeen: Int = 0,
    val wordsEligible: Int = 0,
    val skipped: Map<SkipReason, Int> = emptyMap(),
    val wordsAgreed: Int = 0,
    val wordsSteady: Int = 0,
    val glyphsKept: Int = 0,
) {
    public operator fun plus(other: HarvestStats): HarvestStats = HarvestStats(
        linesFound = linesFound + other.linesFound,
        linesRead = linesRead + other.linesRead,
        linesMiscounted = linesMiscounted + other.linesMiscounted,
        linesSplit = linesSplit + other.linesSplit,
        wordsSeen = wordsSeen + other.wordsSeen,
        wordsEligible = wordsEligible + other.wordsEligible,
        skipped = (skipped.keys + other.skipped.keys).associateWith { (skipped[it] ?: 0) + (other.skipped[it] ?: 0) },
        wordsAgreed = wordsAgreed + other.wordsAgreed,
        wordsSteady = wordsSteady + other.wordsSteady,
        glyphsKept = glyphsKept + other.glyphsKept,
    )
}

/** Why a word was not learned from, by the first rule it failed ([Harvester.ineligibility]). */
public enum class SkipReason {
    /** Nothing but punctuation: there is no letter to learn. */
    NO_LETTERS,

    /** Not a stroke at all, or gone from the page. */
    NOT_A_STROKE,

    /** Not in the pen ledger, and older ink is not opted in (D8). */
    NOT_THE_PEN,

    /** Drawn against the ruler. */
    RULED,

    /** Partly erased: the sampler reads it whole. */
    ERASED,

    /** Scaled, rotated or skewed since it was written. */
    TRANSFORMED,

    /** Part of a placed Element. */
    ELEMENT,

    /** Ink this feature generated (D2). */
    GENERATED,

    /** The same strokes are learned already, or the user removed them. */
    ALREADY_LEARNED,
}

/** What [Harvester.harvest] made of one page — or why it could not. */
public sealed interface PageHarvest {
    public data class Harvested(val samples: List<WordSample>, val stats: HarvestStats) : PageHarvest

    /** The recogniser could not run, so nothing can be labelled. Stop; try again another night. */
    public data class Unavailable(val reason: RecognitionUnavailable) : PageHarvest
}

/**
 * ⭐ docs/HANDWRITING-SYNTHESIS.md §5 — turns a page the user wrote into word samples.
 *
 * ## The pipeline, and why each step refuses rather than guesses
 *
 * 1. **The page's lines, exactly as M7 found them.** The same sampler and the same [LineSegmenter]
 *    over the same ink produce the same lines, so each one meets the recognised line that was read
 *    from it. A line with no match is skipped.
 * 2. **Words with their strokes** ([WordBoxes.strokesOfWords]) — only when the gaps between strokes
 *    and the recogniser agree on how many words there are. Otherwise nothing on that line is learned.
 * 3. **Only the user's own pen** ([ineligibility]): in the ledger (or old enough for the opt-in), not
 *    generated, not an Element, not moved by anything but a translation, not partly erased, not
 *    drawn against the ruler, not a highlighter.
 * 4. **Two reads must agree, and the second must be sure.** Each word is read again on its own. It is
 *    kept only when that read's best guess is exactly M7's word, *and* at least [MIN_ECHOES] of the
 *    read's other guesses are the same word again. The dataset is therefore filtered for legibility by
 *    construction: a word the recogniser cannot read twice, steadily, is not taught (§5.3). The second
 *    condition is what keeps out drawings, which a recogniser reads as a word just as consistently.
 * 5. **Letters are cut only where they cut cleanly** ([GlyphClusters]) and each is kept only if it
 *    reads, on its own, as its letter.
 *
 * Never in the editor, never on the UI thread: `:app` runs it from a charging-and-idle worker.
 *
 * ⚠️ **What the tests cannot tell you.** `HarvesterTest` drives this with fakes for the sampler and
 * the recogniser, so it proves the *decisions* — eligibility, agreement, deduplication, staleness —
 * and nothing about how often ML Kit agrees with itself on a real hand. That number comes from the
 * device (docs §12, H2), and a fake that always agrees would make it look like 100 %.
 */
public class Harvester(
    private val sampler: StrokeSampler,
    private val strokes: StrokeReader,
    private val ink: InkReader,
    private val ids: IdFactory,
) {

    /**
     * @param known `sourceKey`s already in the profile or rejected by the user: strokes that must not
     *   become a second sample.
     */
    @Suppress("ReturnCount")
    public suspend fun harvest(input: PageInput, policy: HarvestPolicy, known: Set<String>): PageHarvest {
        val sampled = sampler.sample(input.page)
        if (sampled.isEmpty()) return PageHarvest.Harvested(emptyList(), HarvestStats())

        val keysThisPage = HashSet<String>()
        val funnel = Funnel()
        val candidates = ArrayList<Candidate>()
        for (line in LineSegmenter.segment(sampled).lines) {
            val words = wordsOf(line, input.text.lines, funnel) ?: continue
            candidates += candidatesOn(line, words, input, policy, funnel) { it !in known && keysThisPage.add(it) }
        }
        var stats = funnel.stats(wordsEligible = candidates.size)
        if (candidates.isEmpty()) return PageHarvest.Harvested(emptyList(), stats)

        val reads = when (val reading = ink.read(candidates.map { it.toRead() }, CANDIDATES)) {
            is InkReading.Unavailable -> return PageHarvest.Unavailable(reading.reason)
            is InkReading.Read -> reading.results
        }
        val agreed = candidates.indices.filter { reads.getOrNull(it)?.firstOrNull()?.text == candidates[it].word.text }
        // Agreement alone lets drawings through: both reads read the same strokes, so a shape taken for
        // a word is taken for it twice. See MIN_ECHOES.
        val steady = agreed.filter { echoes(candidates[it].word.text, reads[it]) >= MIN_ECHOES }
        stats = stats.copy(wordsAgreed = agreed.size, wordsSteady = steady.size)
        if (steady.isEmpty()) return PageHarvest.Harvested(emptyList(), stats)

        val wanted = steady.flatMap { index -> candidates[index].word.strokes.map(SampledStroke::id) }
        val captured = strokes.read(input.page, wanted)
        val built = steady.mapNotNull { index -> build(input, candidates[index], reads[index], captured) }

        val glyphs = verifiedGlyphs(built) ?: return PageHarvest.Unavailable(RecognitionUnavailable.RECOGNISER_FAILED)
        val samples = built.mapIndexed { index, word -> word.sample.copy(glyphs = glyphs[index]) }
        return PageHarvest.Harvested(samples, stats.copy(glyphsKept = glyphs.sumOf { it.size }))
    }

    private class Candidate(
        val word: WordStrokes,
        val area: PageRect,
        val style: LineStyle,
        val key: String,
        val preContext: String,
    ) {
        fun toRead(): InkToRead = InkToRead(word.strokes, area, preContext.ifEmpty { null })
    }

    private class Built(val sample: WordSample, val candidate: Candidate)

    /** One page's line-by-line counts, which [wordsOf] fills in ([HarvestStats]). */
    private class Funnel {
        var linesFound = 0
        var linesRead = 0
        var linesMiscounted = 0
        var linesSplit = 0
        var wordsSeen = 0
        private val skipped = HashMap<SkipReason, Int>()

        fun skip(reason: SkipReason) {
            skipped[reason] = (skipped[reason] ?: 0) + 1
        }

        fun stats(wordsEligible: Int): HarvestStats = HarvestStats(
            linesFound = linesFound,
            linesRead = linesRead,
            linesMiscounted = linesMiscounted,
            linesSplit = linesSplit,
            wordsSeen = wordsSeen,
            wordsEligible = wordsEligible,
            skipped = skipped.toMap(),
        )
    }

    /**
     * The line's style and its words with their strokes, or null when either cannot be had. Each step
     * the line gets through is counted in [funnel].
     */
    private fun wordsOf(
        line: TextLine,
        recognised: List<RecognisedLine>,
        funnel: Funnel,
    ): Pair<LineStyle, List<WordStrokes>>? {
        funnel.linesFound++
        val text = matchLine(line, recognised)?.text ?: return null
        funnel.linesRead++
        val words = WordBoxes.strokesOfWords(line, text) ?: return null.also { funnel.linesMiscounted++ }
        val style = LineStyles.measure(line.strokes) ?: return null
        funnel.linesSplit++
        funnel.wordsSeen += words.size
        return style to words
    }

    /**
     * The words on one line worth asking about: eligible, and accepted by [isNew]. Each word that is
     * not is counted in [funnel] under its reason.
     */
    @Suppress("LongParameterList")
    private fun candidatesOn(
        line: TextLine,
        words: Pair<LineStyle, List<WordStrokes>>,
        input: PageInput,
        policy: HarvestPolicy,
        funnel: Funnel,
        isNew: (String) -> Boolean,
    ): List<Candidate> {
        val result = ArrayList<Candidate>()
        var before = ""
        for (word in words.second) {
            val skip = skipReason(input, word, policy)
            val key = if (skip == null) sourceKeyOf(input.page, word.strokes.map(SampledStroke::id)) else null
            when {
                skip != null -> funnel.skip(skip)
                key == null -> funnel.skip(SkipReason.NOT_A_STROKE)
                !isNew(key) -> funnel.skip(SkipReason.ALREADY_LEARNED)
                else -> result += Candidate(word, line.writingArea, words.first, key, before.takeLast(PRE_CONTEXT))
            }
            before = if (before.isEmpty()) word.text else "$before ${word.text}"
        }
        return result
    }

    @Suppress("ReturnCount")
    private fun build(
        input: PageInput,
        candidate: Candidate,
        read: List<ReadCandidate>,
        captured: Map<ObjectId, CapturedStroke>,
    ): Built? {
        val parts = candidate.word.strokes.map { captured[it.id] ?: return null }
        if (parts.any { it.pen.kind == PenKind.HIGHLIGHTER }) return null
        val trajectory = joined(parts, input.ledger) ?: return null
        val units = parts.mapNotNull { it.unitLengthCm }
        val sample = WordSample(
            id = ids.newUuid7(),
            noteId = input.noteId,
            pageId = input.page.pageId,
            sourceStrokes = parts.map { it.id },
            sourceKey = candidate.key,
            epochDay = input.epochDay,
            label = candidate.word.text,
            labelSource = LabelSource.AGREED,
            alternatives = read.drop(1).map { it.text }.filter { it != candidate.word.text }.take(MAX_ALTERNATIVES),
            frame = candidate.style.frameAt(candidate.word.bounds.left),
            pen = parts.first().pen,
            unitLengthCm = if (units.isEmpty()) null else median(units),
            split = splitOf(candidate.key),
            trajectory = trajectory,
            glyphs = emptyList(),
        )
        return Built(sample, candidate)
    }

    /**
     * The words' letters, cut and checked: one recogniser pass over every cluster of every word on
     * the page. @return per word, the glyphs kept; null when the recogniser failed half-way.
     */
    private suspend fun verifiedGlyphs(words: List<Built>): List<List<GlyphSpan>>? = cut(
        words.map { CutRequest(it.candidate.word.strokes, it.sample.label, it.candidate.area) },
    )

    /** One word to cut into letters: its strokes in drawing order, its label, and its line's area. */
    private class CutRequest(val strokes: List<SampledStroke>, val label: String, val area: PageRect)

    /**
     * Cuts each word into clusters ([GlyphClusters.of]), gives each cluster its letters — one each
     * when the counts match, a share of a joined run when there are fewer clusters
     * ([GlyphClusters.partition]) — and keeps a cluster only when it reads back as its letters.
     */
    private suspend fun cut(words: List<CutRequest>): List<List<GlyphSpan>>? {
        class Pending(val word: Int, val text: String, val members: List<Int>)

        val pending = ArrayList<Pending>()
        val reads = ArrayList<InkToRead>()
        for ((index, word) in words.withIndex()) {
            val letters = writtenUnits(word.label)
            val clusters = GlyphClusters.of(word.strokes)
            val widths = clusters.map { members ->
                members.map { word.strokes[it].bounds }.reduce(PageRect::union).width
            }
            val shares = GlyphClusters.partition(widths, letters) ?: continue
            var at = 0
            for ((position, members) in clusters.withIndex()) {
                val text = letters.subList(at, at + shares[position]).joinToString("")
                pending += Pending(index, text, members)
                val before = letters.subList(0, at).joinToString("")
                reads += InkToRead(members.map { word.strokes[it] }, word.area, before.ifEmpty { null })
                at += shares[position]
            }
        }
        val glyphs = List(words.size) { ArrayList<GlyphSpan>() }
        if (reads.isEmpty()) return glyphs
        val results = when (val reading = ink.read(reads, CANDIDATES)) {
            is InkReading.Unavailable -> return null
            is InkReading.Read -> reading.results
        }
        for ((index, item) in pending.withIndex()) {
            val texts = results.getOrNull(index).orEmpty().map { it.text.trim() }
            if (readsAs(item.text, texts)) glyphs[item.word] += GlyphSpan(item.text, item.members)
        }
        return glyphs
    }

    /**
     * ⭐ Cuts words learned before joined runs could be cut (phase 3): a sample whose strokes were
     * fewer clusters than letters was stored with no glyphs at all, so a joined hand taught assembly
     * whole words and nothing else. Its trajectory is still the ink as measured, so it can be cut now
     * without the note, and read back exactly as a fresh harvest would.
     *
     * @return the samples given, with glyphs where some now read back; unchanged where none did.
     *   Null when the recogniser could not run.
     */
    public suspend fun recut(samples: List<WordSample>): List<WordSample>? {
        if (samples.isEmpty()) return samples
        val requests = samples.map { sample ->
            val strokes = sampledStrokesOf(sample.trajectory)
            val box = sample.trajectory.bounds()
            val xHeight = sample.frame.xHeightPt
            val area = PageRect(
                left = box.left,
                top = minOf(box.top, sample.frame.originY - xHeight * AREA_ABOVE),
                right = box.right,
                bottom = maxOf(box.bottom, sample.frame.originY + xHeight * AREA_BELOW),
            )
            CutRequest(strokes, sample.label, area)
        }
        val glyphs = cut(requests) ?: return null
        return samples.mapIndexed { index, sample ->
            if (glyphs[index].isEmpty()) sample else sample.copy(glyphs = glyphs[index])
        }
    }

    public companion object {
        /** How many alternatives to ask the recogniser for (D9 uses the same number). */
        public const val CANDIDATES: Int = 5

        /** One word in this many is held out of everything but measurement (§5.5). */
        public const val HELD_OUT_EVERY: Long = 10L

        /** The key [ObjectFormat] extras carry on generated strokes (D2). Never learned from. */
        public const val GENERATED_FORMAT_KEY: String = "hw"

        /**
         * How many of a word read's *other* guesses must be the word again before the word is learned
         * (§5.3).
         *
         * ⚠️ **Two reads agreeing is not enough.** Both read the same strokes, so a shape the recogniser
         * takes for a word is taken for it twice. On the emulator's first harvest of real notes
         * (docs/HANDWRITING-SYNTHESIS.md §16.5), 16 of the 18 "words" learned were drawings:
         * - zigzags learned as `mm` and `m`;
         * - five wavy scribbles, each learned as `M`;
         * - an arrow learned as `b`;
         * - brackets and corners learned as `c`, `l` and `n`;
         * - a tick learned as `d`;
         * - ovals learned as `o`.
         *
         * What separates them is the rest of the read. For writing ML Kit really reads, its other guesses
         * repeat the answer with different spacing and punctuation: `Examples` came with `examples,` and
         * `Examples)`, and `CaCO3` with `CaCO 3` and `Ca CO3`. For a shape they scatter: the zigzag's were
         * `mmm`, `umm`, `mmu` and `um`. All 16 drawings had at most one echo, and both real words had
         * three or more.
         *
         * Eighteen samples, most of them drawings, is a small set. Measure it again on the tablet's real
         * notes. The gate errs towards learning less. That is the right way round for a profile whose
         * letters will be copied into the user's notes.
         */
        public const val MIN_ECHOES: Int = 2

        private const val PRE_CONTEXT = 20
        private const val MAX_ALTERNATIVES = 4
        private const val MIN_LINE_OVERLAP = 0.6f
        private const val PEN_LIFT_MS = 60
        private const val TRANSLATION_TOLERANCE = 1e-4f

        /** Letters whose capital is the same shape, smaller or larger — a recogniser cannot be blamed. */
        private const val CASE_ALIKE = "cCkKoOpPsSuUvVwWxXyYzZ"

        /**
         * ⭐ Why [id] may not be learned from, or null when it may: D8, as one decision with a test of
         * its own.
         *
         * The rules run in the order a reader would check them:
         * 1. Is it the user's? It must be in the ledger, or the older-notes opt-in must be on.
         * 2. Is it handwriting? Not generated, not an Element, not ruled.
         * 3. Is it still as the pen left it? No rotation or scale, and no erased bite. `InkStrokeSampler`
         *    reads a partly erased stroke whole, so its samples would teach ink that is not on the page.
         */
        @Suppress("ReturnCount")
        public fun ineligibility(
            page: FoldedPage,
            id: ObjectId,
            ledger: Map<ObjectId, LedgerEntry>,
            policy: HarvestPolicy,
        ): SkipReason? {
            val stroke = page.find(id) as? StrokeObject ?: return SkipReason.NOT_A_STROKE
            val entry = ledger[id]
            if (entry == null && !policy.learnFromOlderNotes) return SkipReason.NOT_THE_PEN
            if (entry?.ruled == true) return SkipReason.RULED
            if (!stroke.erased.isEmpty) return SkipReason.ERASED
            if (!isTranslation(stroke.transform)) return SkipReason.TRANSFORMED
            val format = ObjectFormat.decode(page.formatOf(id))
            if (format.instance != null) return SkipReason.ELEMENT
            if (GENERATED_FORMAT_KEY in format.extras) return SkipReason.GENERATED
            return null
        }

        /**
         * The fingerprint of [ids]' geometry on [page], or null when any is missing or not a stroke.
         * Geometry, not ids: a duplicated word has new ids and the same bytes, and counts once.
         */
        public fun sourceKeyOf(page: FoldedPage, ids: List<ObjectId>): String? {
            val parts = ids.map { id ->
                val stroke = page.find(id) as? StrokeObject ?: return null
                Fingerprint.of(stroke.geometry.bytes)
            }
            return Fingerprint.hex(Fingerprint.of(parts))
        }

        /**
         * Samples learned from [page] that no longer describe it: a source stroke deleted, its geometry
         * changed, erased into, rotated or scaled since. Withdrawn at the next pass — a word the user
         * scrubbed out is not one they would have chosen to be copied (§4.2).
         */
        public fun stale(samples: List<WordSample>, page: FoldedPage): Set<String> = samples
            .filter { sample ->
                sourceKeyOf(page, sample.sourceStrokes) != sample.sourceKey ||
                    sample.sourceStrokes.any { id ->
                        val stroke = page.find(id) as? StrokeObject
                        stroke == null || !stroke.erased.isEmpty || !isTranslation(stroke.transform)
                    }
            }
            .map { it.id }
            .toSet()

        /** By what the strokes *are*, so a re-harvest cannot move a word between the splits. */
        internal fun splitOf(sourceKey: String): SampleSplit =
            if (Fingerprint.of(sourceKey.encodeToByteArray()).mod(HELD_OUT_EVERY) == 0L) {
                SampleSplit.HELD_OUT
            } else {
                SampleSplit.TRAIN
            }

        /** How many of [read]'s guesses after the first are [label] again, ignoring case, spacing and punctuation. */
        internal fun echoes(label: String, read: List<ReadCandidate>): Int {
            val wanted = comparable(label)
            return read.drop(1).count { comparable(it.text) == wanted }
        }

        private fun comparable(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase()

        /**
         * Whether one of [readings] is [letters]: exactly, or with spaces the recogniser put inside a
         * joined run removed, or differing only in the case of letters whose capital is the same
         * shape ([CASE_ALIKE]).
         */
        internal fun readsAs(letters: String, readings: List<String>): Boolean = readings.any { reading ->
            val read = reading.filterNot { it.isWhitespace() }
            read.length == letters.length && read.indices.all { i -> sameLetter(read[i], letters[i]) }
        }

        private fun sameLetter(read: Char, wanted: Char): Boolean =
            read == wanted || (wanted in CASE_ALIKE && read.equals(wanted, ignoreCase = true))

        /** Which samples [recut] could add letters to: none cut yet, and more letters than clusters. */
        public fun needsRecut(sample: WordSample): Boolean {
            if (sample.glyphs.isNotEmpty()) return false
            val letters = writtenUnits(sample.label)
            return letters.size >= 2 && GlyphClusters.of(sampledStrokesOf(sample.trajectory)).size < letters.size
        }

        /** How far above and below the baseline a learned word's line is taken to reach, in x-heights. */
        private const val AREA_ABOVE = 2.2f
        private const val AREA_BELOW = 1.1f

        internal fun isTranslation(transform: Matrix3): Boolean = abs(transform.m00 - 1f) < TRANSLATION_TOLERANCE &&
            abs(transform.m11 - 1f) < TRANSLATION_TOLERANCE &&
            abs(transform.m01) < TRANSLATION_TOLERANCE &&
            abs(transform.m10) < TRANSLATION_TOLERANCE

        /** The first reason [word] may not be learned from, or null when it may. */
        private fun skipReason(input: PageInput, word: WordStrokes, policy: HarvestPolicy): SkipReason? {
            if (word.text.none { it.isLetterOrDigit() }) return SkipReason.NO_LETTERS
            return word.strokes.firstNotNullOfOrNull { ineligibility(input.page, it.id, input.ledger, policy) }
        }

        private fun matchLine(line: TextLine, recognised: List<RecognisedLine>): RecognisedLine? {
            val best = recognised.maxByOrNull { overlapShare(it.bounds, line.writingArea) } ?: return null
            return best.takeIf { overlapShare(it.bounds, line.writingArea) >= MIN_LINE_OVERLAP }
        }

        /** Intersection over union of two boxes. */
        private fun overlapShare(a: PageRect, b: PageRect): Float {
            val width = min(a.right, b.right) - max(a.left, b.left)
            val height = min(a.bottom, b.bottom) - max(a.top, b.top)
            if (width <= 0f || height <= 0f) return 0f
            val intersection = width * height
            val union = a.width * a.height + b.width * b.height - intersection
            return if (union <= 0f) 0f else intersection / union
        }

        /**
         * A word's strokes on one clock. Where the ledger has both down times the real pause between
         * strokes is kept; otherwise a plausible pen lift stands in, as `InkStrokeSampler` does.
         */
        private fun joined(parts: List<CapturedStroke>, ledger: Map<ObjectId, LedgerEntry>): PenTrajectory? {
            val hasPressure = parts.all { it.trajectory.pressure != null }
            val hasTilt = parts.all { it.trajectory.tilt != null }
            val builder = PenTrajectory.Builder(parts.sumOf { it.trajectory.pointCount }, hasPressure, hasTilt)
            val firstDown = ledger[parts.first().id]?.downElapsedMs
            var clock = 0
            for ((index, part) in parts.withIndex()) {
                val stroke = part.trajectory
                val start = if (index == 0) {
                    0
                } else {
                    val down = ledger[part.id]?.downElapsedMs
                    val measured = if (firstDown != null && down != null) (down - firstDown).toInt() else null
                    max(clock + 1, measured ?: (clock + PEN_LIFT_MS))
                }
                val base = stroke.tMs[0]
                for (i in 0 until stroke.pointCount) {
                    builder.add(
                        x = stroke.x[i],
                        y = stroke.y[i],
                        tMs = start + (stroke.tMs[i] - base),
                        pressure = stroke.pressure?.get(i) ?: 0f,
                        tilt = stroke.tilt?.get(i) ?: 0f,
                    )
                }
                builder.endStroke()
                clock = start + (stroke.tMs[stroke.pointCount - 1] - base)
            }
            return builder.build()
        }
    }
}
