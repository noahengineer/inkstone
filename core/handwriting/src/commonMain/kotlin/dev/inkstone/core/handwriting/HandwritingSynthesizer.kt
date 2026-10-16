package dev.inkstone.core.handwriting

import dev.inkstone.core.handwriting.neural.Primed
import dev.inkstone.core.handwriting.quality.Backend
import dev.inkstone.core.handwriting.quality.Route
import dev.inkstone.core.model.GeneratedInk
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PenTrajectory
import dev.inkstone.core.model.RecognitionUnavailable
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The size and shape a phrase is written at — what D15 measures from the page around the drop
 * point ([Placements]), or the profile's own medians when nothing on the page says otherwise.
 *
 * @property xHeightPt the height of an `x`, in page points.
 * @property slantRad the lean; positive is forward.
 * @property angleRad the baseline's angle; positive runs downhill to the right (y grows downward).
 * @property wrapWidthPt lines are broken before they pass this width.
 * @property linePitchPt from one baseline to the next.
 * @property neatness `0..1`; see [Variation.forNeatness].
 * @property wordGapXHeights the page's own word space, when D15 measured one; otherwise the hand's.
 */
public data class WriteStyle(
    val xHeightPt: Float,
    val slantRad: Float,
    val angleRad: Float,
    val wrapWidthPt: Float,
    val linePitchPt: Float,
    val neatness: Float = Variation.DEFAULT_NEATNESS,
    val wordGapXHeights: Float? = null,
)

/**
 * @property seed a fresh one per generation: that is what makes the same sentence different every
 *   time (§6.1). Tests pin it.
 * @property accountName the signed-in account's display name, for D11's signature rule.
 * @property maths the Write panel's *Maths* switch: the text is maths notation, written in two dimensions
 *   ([MathNotation], docs/HANDWRITING-MATHS.md). Off, nothing about writing changes.
 */
public class WriteRequest(
    public val text: String,
    public val style: WriteStyle,
    public val seed: Long,
    public val preference: SourcePreference = SourcePreference(),
    public val accountName: String? = null,
    public val maths: Boolean = false,
)

/** Why a word was not written. Each one is said in the panel, never silently skipped (§8.2). */
public sealed interface Refusal {
    /** The user has never written [letters] in a way assembly can use. */
    public data class MissingLetters(val letters: List<String>) : Refusal

    /** Every attempt failed the read-back. [readAs] is what the last one was taken for. */
    public data class Illegible(val readAs: String?) : Refusal

    /** Nothing has been learned yet. */
    public data object NothingLearned : Refusal

    /** D11: the text is the account holder's name. Handwriting synthesis never writes a signature. */
    public data object Signature : Refusal

    /** D12: no recogniser, so nothing can be checked, so nothing is written. */
    public data class Unavailable(val reason: RecognitionUnavailable) : Refusal
}

/** What happened to one word of a phrase. [index] is its place among the phrase's words. */
public sealed interface WordOutcome {
    public val index: Int
    public val text: String

    /**
     * @property ink the word in page orientation (y down), its left baseline point at the origin,
     *   exactly as the gate read it.
     * @property advancePt its width.
     * @property attempts how many tries the read-back took.
     * @property backend who wrote it: [GeneratedInk.ASSEMBLY] or [GeneratedInk.NEURAL] — what its
     *   strokes' `hw` tag says (D2).
     * @property model the network's identity for a neural word, [GeneratedInk.NO_MODEL] otherwise.
     * @property readBack false for a token the recogniser cannot judge on this hand (§8.2): built only
     *   from letters verified one by one, laid out without overlap, and not read back — construction is
     *   its proof.
     * @property strokeTags for a maths expression, each stroke's own backend and model: its runs can come
     *   from both backends, and its bar and brackets are drawn (docs/HANDWRITING-MATHS.md §3). Null means
     *   every stroke is [backend]'s.
     */
    public class Written(
        override val index: Int,
        override val text: String,
        public val ink: PenTrajectory,
        public val advancePt: Float,
        public val attempts: Int,
        public val joins: Int,
        public val backend: Char = GeneratedInk.ASSEMBLY,
        public val model: String = GeneratedInk.NO_MODEL,
        public val readBack: Boolean = true,
        public val strokeTags: List<Pair<Char, String>>? = null,
    ) : WordOutcome {
        /** The two-dimensional maths a [WriteRequest.maths] phrase is built from, not one run of letters. */
        public val isMaths: Boolean get() = strokeTags != null

        /** The same word at another place in the phrase. */
        internal fun at(index: Int): Written =
            Written(index, text, ink, advancePt, attempts, joins, backend, model, readBack, strokeTags)
    }

    public data class Refused(override val index: Int, override val text: String, val reason: Refusal) : WordOutcome
}

/**
 * Where one word sits: on line [line], [offsetPt] along the baseline from the line's start, lifted by
 * [liftPt] (the line's drift). A refused word has a place too, so a preview can show the gap where it
 * would be.
 */
public class PlacedWord(
    public val outcome: WordOutcome,
    public val line: Int,
    public val offsetPt: Float,
    public val liftPt: Float,
    public val widthPt: Float,
)

/**
 * ⭐ A phrase as written: every word's outcome and where it sits. It is put on a page with
 * [strokes], which only *translates* each word — the ink the gate read is the ink that is drawn.
 */
public class WrittenPhrase(
    public val text: String,
    public val style: WriteStyle,
    public val words: List<WordOutcome>,
    public val placed: List<PlacedWord>,
) {
    public val refusals: List<WordOutcome.Refused> get() = words.filterIsInstance<WordOutcome.Refused>()

    /** Whether every word was written — the only state in which the phrase may be dropped (§8.2). */
    public val complete: Boolean get() = words.isNotEmpty() && refusals.isEmpty()

    public val lineCount: Int get() = (placed.maxOfOrNull { it.line } ?: -1) + 1

    /**
     * Every stroke, one trajectory per pen-down, in writing order, with the first line's left
     * baseline point at [origin]. Refused words contribute nothing.
     */
    public fun strokes(origin: PagePoint): List<PenTrajectory> = placed.flatMap { place ->
        val word = place.outcome as? WordOutcome.Written
        val at = pointOf(origin, place)
        val moved = word?.ink?.transformed(Matrix3.translate(at.x, at.y))
        if (moved == null) emptyList() else (0 until moved.strokeCount).mapNotNull { moved.strokes(listOf(it)) }
    }

    /**
     * [strokes], each with its `hw` tag: [group] for the whole phrase, and its own word's backend and
     * model — so a phrase mixing assembly and neural words says which wrote which, stroke by stroke
     * (D2), and still selects as one thing. Pairs rather than two lists, so the two cannot disagree.
     */
    public fun taggedStrokes(origin: PagePoint, group: String): List<Pair<PenTrajectory, GeneratedInk>> =
        placed.flatMap { place ->
            val word = place.outcome as? WordOutcome.Written ?: return@flatMap emptyList()
            val at = pointOf(origin, place)
            val moved = word.ink.transformed(Matrix3.translate(at.x, at.y)) ?: return@flatMap emptyList()
            val tag = GeneratedInk(group, word.backend, word.model)
            (0 until moved.strokeCount).mapNotNull { k ->
                val own = word.strokeTags?.getOrNull(k)?.let { (backend, model) -> GeneratedInk(group, backend, model) }
                moved.strokes(listOf(k))?.let { it to (own ?: tag) }
            }
        }

    /** The model behind the phrase's neural words, or null when assembly wrote it all. */
    public val neuralModel: String?
        get() = words.filterIsInstance<WordOutcome.Written>().firstOrNull { it.backend == GeneratedInk.NEURAL }?.model

    /** The box round every written stroke at [origin], or null with none. */
    public fun boundsAt(origin: PagePoint): PageRect? =
        strokes(origin).map { it.bounds() }.reduceOrNull(PageRect::union)

    /** Where [place]'s left baseline point lands on the page. */
    public fun pointOf(origin: PagePoint, place: PlacedWord): PagePoint {
        val cosA = cos(style.angleRad)
        val sinA = sin(style.angleRad)
        val down = place.line * style.linePitchPt - place.liftPt
        return PagePoint(
            origin.x + place.offsetPt * cosA - down * sinA,
            origin.y + place.offsetPt * sinA + down * cosA,
        )
    }
}

/**
 * ⭐ The one entry point the app calls — docs/HANDWRITING-SYNTHESIS.md §3.
 *
 * ## What [write] does with a phrase
 *
 * 1. **Refuses outright** a phrase that is only the account holder's name (D11, [SignatureRule]), and
 *    everything when nothing has been learned.
 * 2. **Assembles each word** ([GlyphAssembly]) in the normalised frame, then undoes that frame with the
 *    page's slant, angle and size ([WriteStyle]), at the origin.
 * 3. **Reads every word back** ([ReliabilityGate]) — all of a round's words in one recogniser call —
 *    and keeps those that pass.
 * 4. **Tries again** (§8.2's ladder): up to [VARIED_ATTEMPTS] fresh samples, then one with no
 *    variation at all. **Assembly stays primary** in phase 4: the neural backend, when there is one,
 *    writes what assembly cannot — a word with a letter the user has never written goes to it from
 *    the first round, and a word assembly could not make legible gets its rungs afterwards — up to
 *    [NeuralBackend.ATTEMPTS] samples, raising Graves's bias each time. Every word from either backend
 *    is read by the same gate. A word nothing could write legibly is **refused**, saying why. Never a
 *    garbled word on a page.
 * 5. **Lays the words out** ([layOut]): broken into lines at the wrap width, spaced by the user's
 *    measured word gaps, each line drifting a little off its baseline as a hand does. Layout only
 *    translates words, which is why what was read is what is drawn.
 *
 * ## Phase 5 (§8.2, §8.4)
 *
 * - **A token the recogniser cannot judge** on this hand ([GateCalibration.judgeable] — numbers,
 *   formulas or symbols, as measured) is never written by the network and never read back: it is
 *   assembled only from letters verified one by one and units the user vouched for, with clear air
 *   between every two ([GlyphAssembly.word]'s `strict`). Correct by construction, the only proof there is.
 * - **[route] says who goes first.** With the network primary (the measured switch, or the user's
 *   *Model* setting) its rungs come first and assembly's after; a word holding a character the network
 *   fails goes to assembly first whenever assembly can write it (§8.4's per-character routing).
 *
 * Runs wherever its caller runs; the app calls it on `Dispatchers.Default`, never the UI thread.
 */
public class HandwritingSynthesizer(
    private val gate: ReliabilityGate,
    private val neural: NeuralBackend? = null,
    private val route: Route = Route.ASSEMBLY_FIRST,
) {

    /**
     * @param onWord told about each word as it is decided, for a preview that fills in as it goes.
     * @param primed the neural backend's prime, computed ahead by the panel (§19.1). Null computes it
     *   here, once, the first time a word needs the network.
     */
    public suspend fun write(
        request: WriteRequest,
        index: GlyphIndex,
        onWord: (WordOutcome) -> Unit = {},
        primed: Primed? = null,
    ): WrittenPhrase {
        val tokens = tokensOf(request.text, request.maths)
        // Maths: every run of an expression goes up the ladder as a word of its own, read back like any
        // other, and the expression is then composed from them (docs/HANDWRITING-MATHS.md §3).
        val maths = if (request.maths) MathWords.of(tokens) else null
        val writing = Writing(request, maths?.pieces ?: tokens, index, if (maths == null) onWord else { _ -> }, primed)
        val refuseAll: Refusal? = when {
            tokens.isEmpty() -> null
            SignatureRule.refuses(request.text, request.accountName) -> Refusal.Signature
            index.isEmpty -> Refusal.NothingLearned
            else -> null
        }
        if (refuseAll != null) writing.refuseEvery(refuseAll) else writing.attempt()
        val words =
            maths?.compose(writing.words(), index, request.style, writing.rng)?.onEach(onWord) ?: writing.words()
        val placed = layOut(tokens, words, request.style, index.stats, writing.rng)
        return WrittenPhrase(request.text, request.style, words, placed)
    }

    /** One call of [write]: each word's outcome so far, and what a failing word was last read as. */
    private inner class Writing(
        private val request: WriteRequest,
        private val tokens: List<Token>,
        private val index: GlyphIndex,
        private val onWord: (WordOutcome) -> Unit,
        private var primed: Primed?,
    ) {
        val rng = Random(request.seed)
        private val style = request.style
        private val assembly = GlyphAssembly(index)

        /** Words assembly has no cover for, which the neural backend writes from the first round. */
        private val toNeural = HashMap<Int, List<String>>()

        /** §8.4: words the network writes first, because it is primary and they hold nothing routed away. */
        private val neuralFirst = HashSet<Int>()
        private val used = HashSet<UnitSource>()
        private val outcomes = arrayOfNulls<WordOutcome>(tokens.size)
        private val lastRead = arrayOfNulls<String>(tokens.size)
        private val frame = NormalFrame(0f, 0f, style.angleRad, style.slantRad, style.xHeightPt)

        fun refuseEvery(reason: Refusal) {
            for (i in tokens.indices) decide(WordOutcome.Refused(i, tokens[i].text, reason))
        }

        private fun pending(): List<Int> = tokens.indices.filter { outcomes[it] == null }

        /**
         * §8.2's ladder: first the tokens the recogniser cannot judge, built strictly and not read back;
         * then [VARIED_ATTEMPTS] varied rounds and one with no variation at all — assembly's words, and
         * the network's rungs, in the same rounds, for the words it goes first on (§8.4) or that
         * assembly cannot cover; then each backend's rungs for whatever the other could not make legible.
         */
        suspend fun attempt() {
            val varied = Variation.forNeatness(style.neatness)
            for (i in tokens.indices) if (!gate.calibration.judgeable(tokens[i].text)) writeStrict(i, varied)
            val backend = neural
            if (route.primary == Backend.NEURAL && backend != null) {
                for (i in pending()) if (backend.canWrite(tokens[i].text) && !routedAway(i)) neuralFirst += i
            }
            sharedRounds(varied)
            if (backend != null) networkAfterAssembly(backend)
            assemblyAfterNetwork(varied)
        }

        private fun variationFor(attempt: Int, varied: Variation) = if (attempt >
            VARIED_ATTEMPTS
        ) {
            Variation.None
        } else {
            varied
        }

        /** Assembly's rounds, and in the same rounds the network's, for what it goes first on or alone can write. */
        private suspend fun sharedRounds(varied: Variation) {
            for (attempt in 1..VARIED_ATTEMPTS + 1) {
                val pending = pending()
                if (pending.isEmpty()) return
                val assembled =
                    assemble(pending.filter { it !in toNeural && it !in neuralFirst }, variationFor(attempt, varied))
                val networks = pending.filter { it in toNeural || it in neuralFirst }
                val generated = if (attempt <= NeuralBackend.ATTEMPTS) generate(networks, attempt) else emptyList()
                val round = assembled + generated
                if (round.isNotEmpty()) readBack(round, attempt)
            }
        }

        /** The network's rungs for what assembly built and could not make legible. */
        private suspend fun networkAfterAssembly(backend: NeuralBackend) {
            for (attempt in 1..NeuralBackend.ATTEMPTS) {
                val pending = pending().filter {
                    it !in toNeural && it !in neuralFirst &&
                        backend.canWrite(tokens[it].text)
                }
                if (pending.isEmpty()) return
                val round = generate(pending, attempt)
                if (round.isNotEmpty()) readBack(round, VARIED_ATTEMPTS + 1 + attempt)
            }
        }

        /** The network went first and failed every sample: the user's own letters, if they can. */
        private suspend fun assemblyAfterNetwork(varied: Variation) {
            for (attempt in 1..VARIED_ATTEMPTS + 1) {
                val pending = pending().filter { it in neuralFirst }
                if (pending.isEmpty()) return
                val round = assemble(pending, variationFor(attempt, varied), fallback = true)
                if (round.isNotEmpty()) readBack(round, NeuralBackend.ATTEMPTS + attempt)
            }
        }

        /** §8.4: [i] holds a character the network fails, and the user's own letters can write it. */
        private fun routedAway(i: Int): Boolean {
            val text = tokens[i].text
            return text.any { it in route.avoidNeural } && assembly.cover(writtenUnits(text), Random(0)) != null
        }

        /**
         * §8.2 — a token the recogniser cannot judge: the user's verified letters only, laid out without
         * overlap, decided by construction. A letter with no verified copy refuses it; the network is
         * never asked, since nothing could check what it wrote.
         */
        private fun writeStrict(i: Int, variation: Variation) {
            val text = tokens[i].text
            when (val word = assembly.word(text, variation, rng, used, request.preference, strict = true)) {
                is AssembledWord.Missing -> decide(WordOutcome.Refused(i, text, Refusal.MissingLetters(word.letters)))

                is AssembledWord.Written -> {
                    val ink = frame.denormalise(word.trajectory)
                    if (ink == null) {
                        decide(WordOutcome.Refused(i, text, Refusal.Illegible(null)))
                    } else {
                        decide(
                            WordOutcome.Written(
                                i, text, ink, word.width * style.xHeightPt, 1, word.joins,
                                GeneratedInk.ASSEMBLY, GeneratedInk.NO_MODEL, readBack = false,
                            ),
                        )
                    }
                }
            }
        }

        /**
         * Every pending word, assembled. A word that needs an unwritten letter goes to the neural
         * backend when it can write it, and is refused here otherwise — unless this is the [fallback]
         * for words the network went first on and failed, which simply stay pending and are refused as
         * illegible at the end.
         */
        private fun assemble(pending: List<Int>, variation: Variation, fallback: Boolean = false): List<Draft> =
            pending.mapNotNull { i ->
                when (val word = assembly.word(tokens[i].text, variation, rng, used, request.preference)) {
                    is AssembledWord.Missing -> {
                        when {
                            fallback -> Unit
                            neural?.canWrite(tokens[i].text) == true -> toNeural[i] = word.letters
                            else -> decide(WordOutcome.Refused(i, tokens[i].text, Refusal.MissingLetters(word.letters)))
                        }
                        null
                    }

                    is AssembledWord.Written -> frame.denormalise(word.trajectory)?.let {
                        Draft(i, it, word.width, word.joins, GeneratedInk.ASSEMBLY, GeneratedInk.NO_MODEL)
                    }
                }
            }

        /** [pending] written by the network at [attempt]'s bias, after the drop's one prime. */
        private fun generate(pending: List<Int>, attempt: Int): List<Draft> {
            val backend = neural ?: return emptyList()
            if (pending.isEmpty()) return emptyList()
            val prime = primed ?: backend.prime(index, request.preference, rng).also { primed = it }
            val words = backend.write(pending.map { tokens[it].text }, prime, style.neatness, attempt, rng)
            return pending.zip(words).mapNotNull { (i, word) ->
                word?.let { w ->
                    frame.denormalise(w.trajectory)?.let {
                        Draft(i, it, w.width, 0, GeneratedInk.NEURAL, backend.modelId)
                    }
                }
            }
        }

        /** The whole round in one recogniser call; a word that fails stays pending. */
        private suspend fun readBack(round: List<Draft>, attempt: Int) {
            val items = round.map {
                ReliabilityGate.Item(tokens[it.index].text, it.ink, style.xHeightPt, contextBefore(tokens, it.index))
            }
            when (val verdict = gate.check(items)) {
                is ReliabilityGate.Verdict.Unavailable -> for (draft in round) {
                    val reason = Refusal.Unavailable(verdict.reason)
                    decide(WordOutcome.Refused(draft.index, tokens[draft.index].text, reason))
                }

                is ReliabilityGate.Verdict.Read -> round.forEachIndexed { k, draft ->
                    if (verdict.passed[k]) {
                        decide(draft.written(tokens[draft.index].text, style.xHeightPt, attempt))
                    } else {
                        lastRead[draft.index] = verdict.readings[k].firstOrNull()
                    }
                }
            }
        }

        private fun decide(outcome: WordOutcome) {
            outcomes[outcome.index] = outcome
            onWord(outcome)
        }

        /**
         * Every word's outcome. One still pending failed every rung: refused for the letters the user
         * has never written when assembly had no cover for it, as illegible otherwise.
         */
        fun words(): List<WordOutcome> = tokens.indices.map { i ->
            outcomes[i] ?: run {
                val reason = toNeural[i]?.let { Refusal.MissingLetters(it) } ?: Refusal.Illegible(lastRead[i])
                WordOutcome.Refused(i, tokens[i].text, reason).also(onWord)
            }
        }
    }

    /** A word written by either backend and put back on the page's frame, waiting to be read. */
    private class Draft(
        val index: Int,
        val ink: PenTrajectory,
        val width: Float,
        val joins: Int,
        val backend: Char,
        val model: String,
    ) {
        fun written(text: String, xHeightPt: Float, attempt: Int): WordOutcome.Written =
            WordOutcome.Written(index, text, ink, width * xHeightPt, attempt, joins, backend, model)
    }

    /** One word of the text, and how many line breaks the user typed before it. */
    internal class Token(val text: String, val breaksBefore: Int)

    public companion object {
        /** §8.2's assembly rungs before the one with no variation at all. */
        public const val VARIED_ATTEMPTS: Int = 3

        private const val PRE_CONTEXT = 20

        /** A refused word keeps a place this wide per letter, so the preview shows where it would go. */
        private const val REFUSED_LETTER_WIDTH = 0.9f
        private const val DRIFT_WAVELENGTHS = 1.5f
        private const val NARROWER = 0.85f
        private const val WIDER = 1.15f
        private const val TAU = (2 * PI).toFloat()

        /** The least air between a line of maths and its neighbour, in x-heights. */
        private const val MATHS_CLEARANCE = 0.3f

        /** With [maths], a space inside brackets does not end a word ([MathNotation.words]). */
        internal fun tokensOf(text: String, maths: Boolean = false): List<Token> {
            val tokens = ArrayList<Token>()
            var breaks = 0
            for ((lineIndex, line) in text.split('\n').withIndex()) {
                if (lineIndex > 0) breaks++
                val words = if (maths) MathNotation.words(line) else line.split(' ', '\t')
                for (word in words.filter { it.isNotBlank() }) {
                    tokens += Token(word.trim(), if (tokens.isEmpty()) 0 else breaks)
                    breaks = 0
                }
            }
            return tokens
        }

        private fun contextBefore(tokens: List<Token>, index: Int): String? =
            tokens.take(index).joinToString(" ") { it.text }.takeLast(PRE_CONTEXT).ifEmpty { null }

        /**
         * Greedy line breaking at [WriteStyle.wrapWidthPt], words spaced by the user's own measured
         * word gaps, and a line's baseline drifting by up to [Variation.lineDrift] along it — a slow
         * sinusoid, evaluated at each word's centre and applied to the whole word, which keeps the word
         * exactly the ink the gate read.
         */
        internal fun layOut(
            tokens: List<Token>,
            words: List<WordOutcome>,
            style: WriteStyle,
            stats: HandStats,
            rng: Random,
        ): List<PlacedWord> {
            val variation = Variation.forNeatness(style.neatness)
            val xh = style.xHeightPt
            // The page's own spacing first (D15), the hand's median when it has none — spread a little
            // either way. The median and not the raw list: a gap measured between two *learned* words can
            // span a word that was not learned, and a raw pick hands that span to one gap in five.
            val gap = style.wordGapXHeights ?: median(stats.wordGapsOrDefault())
            val gaps = listOf(gap * NARROWER, gap, gap * WIDER)
            val placed = ArrayList<PlacedWord>()
            var line = 0
            var x = 0f
            var drift = newDrift(rng, variation, style)
            for ((i, outcome) in words.withIndex()) {
                val width = when (outcome) {
                    is WordOutcome.Written -> outcome.advancePt
                    is WordOutcome.Refused -> writtenUnits(outcome.text).size * REFUSED_LETTER_WIDTH * xh
                }
                val gap = rng.pick(gaps, variation.gapJitter) * xh
                val forced = tokens[i].breaksBefore
                val overflows = x > 0f && x + gap + width > style.wrapWidthPt
                if (forced > 0 || overflows) {
                    line += maxOf(forced, 1)
                    x = 0f
                    drift = newDrift(rng, variation, style)
                }
                val at = if (x == 0f) 0f else x + gap
                placed += PlacedWord(outcome, line, at, drift(at + width / 2f) * xh, width)
                x = at + width
            }
            return if (words.any {
                    (it as? WordOutcome.Written)?.isMaths == true
                }
            ) {
                clearTallLines(placed, style)
            } else {
                placed
            }
        }

        /**
         * A fraction or a root is taller than a line. Where one would reach into the line above or below
         * it, the lines after it move down by whole line pitches, so a phrase on ruled paper stays on the
         * ruling (docs/HANDWRITING-MATHS.md §3.6). Only lines holding maths are looked at: ordinary
         * writing fits its pitch by definition, since the pitch was measured from it.
         */
        private fun clearTallLines(placed: List<PlacedWord>, style: WriteStyle): List<PlacedWord> {
            val lines = placed.map { it.line }.distinct().sorted()
            fun reach(line: Int, below: Boolean): Float = placed.filter { it.line == line }.maxOfOrNull { place ->
                val word = place.outcome as? WordOutcome.Written ?: return@maxOfOrNull 0f
                val box = word.ink.bounds()
                if (below) box.bottom - place.liftPt else -box.top + place.liftPt
            } ?: 0f
            fun holdsMaths(line: Int) = placed.any {
                it.line == line &&
                    (it.outcome as? WordOutcome.Written)?.isMaths == true
            }
            val shift = HashMap<Int, Int>()
            var extra = 0
            for ((k, line) in lines.withIndex()) {
                val previous = lines.getOrNull(k - 1)
                if (previous != null && (holdsMaths(previous) || holdsMaths(line))) {
                    val room = (line - previous) * style.linePitchPt
                    val needed =
                        reach(previous, below = true) + reach(line, below = false) + MATHS_CLEARANCE * style.xHeightPt
                    if (needed > room) extra += ceil((needed - room) / style.linePitchPt).toInt()
                }
                shift[line] = extra
            }
            return placed.map {
                PlacedWord(it.outcome, it.line + (shift[it.line] ?: 0), it.offsetPt, it.liftPt, it.widthPt)
            }
        }

        private fun newDrift(rng: Random, variation: Variation, style: WriteStyle): (Float) -> Float {
            val amplitude = variation.lineDrift / 2f * rng.nextFloat()
            val wavelength = style.wrapWidthPt * (1f + rng.nextFloat() * DRIFT_WAVELENGTHS)
            val phase = rng.nextFloat() * TAU
            return { along -> amplitude * sin(TAU * along / wavelength + phase) }
        }
    }
}
