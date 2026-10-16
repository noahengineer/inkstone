package dev.inkstone.core.handwriting

import dev.inkstone.core.model.GeneratedInk
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.PenTrajectory
import kotlin.random.Random

/**
 * ⭐ A maths phrase's words, between the ladder and the layout — docs/HANDWRITING-MATHS.md §3.
 *
 * [of] turns each token that is maths ([MathNotation.parse]) into its runs, [pieces], which the
 * synthesizer writes and reads back exactly as it does any word: the user's letters first, the network
 * for what they have never written, every run read by the gate, a token the recogniser cannot judge
 * built strictly. [compose] folds the runs back into one word per token, laid out by [MathLayout].
 *
 * A token with nothing two-dimensional in it is one piece, itself, and comes back untouched: the
 * switch on and plain words is the switch off.
 */
internal class MathWords private constructor(
    private val tokens: List<HandwritingSynthesizer.Token>,
    private val trees: List<MathRow?>,
    private val ranges: List<IntRange>,
    val pieces: List<HandwritingSynthesizer.Token>,
) {
    /**
     * One outcome per token, in token order. An expression is refused when any of its runs was, and
     * says why: the letters it lacks, across all its runs, or what the first illegible run was read as.
     */
    fun compose(written: List<WordOutcome>, index: GlyphIndex, style: WriteStyle, rng: Random): List<WordOutcome> =
        tokens.indices.map { k ->
            val text = tokens[k].text
            val tree = trees[k]
            val runs = ranges[k].map { written[it] }
            when {
                tree == null -> relabel(runs.single(), k)

                runs.any { it is WordOutcome.Refused } -> WordOutcome.Refused(k, text, refusalOf(runs))

                else -> composite(k, text, tree, runs.map { it as WordOutcome.Written }, index, style, rng)
                    ?: WordOutcome.Refused(k, text, Refusal.Illegible(null))
            }
        }

    private fun relabel(outcome: WordOutcome, k: Int): WordOutcome = when (outcome) {
        is WordOutcome.Written -> outcome.at(k)
        is WordOutcome.Refused -> outcome.copy(index = k)
    }

    private fun refusalOf(runs: List<WordOutcome>): Refusal {
        val refused = runs.filterIsInstance<WordOutcome.Refused>()
        val missing = refused.mapNotNull { it.reason as? Refusal.MissingLetters }.flatMap { it.letters }.distinct()
        return if (missing.isNotEmpty()) Refusal.MissingLetters(missing) else refused.first().reason
    }

    /**
     * The runs taken back to the normalised frame they were written in — the page's slant and angle
     * undone — laid out, and the whole expression put on the page once, so its bar and brackets lean
     * with the hand. Pieces are only translated and uniformly scaled: what the gate read is what is drawn.
     */
    @Suppress("LongParameterList", "ReturnCount")
    private fun composite(
        k: Int,
        text: String,
        tree: MathRow,
        runs: List<WordOutcome.Written>,
        index: GlyphIndex,
        style: WriteStyle,
        rng: Random,
    ): WordOutcome.Written? {
        val frame = NormalFrame(0f, 0f, style.angleRad, style.slantRad, style.xHeightPt)
        val pieces = runs.map { run ->
            val normal = frame.normalise(run.ink)?.let(::fromLeft) ?: return null
            MathPiece(normal, run.backend, run.model)
        }
        val layout = MathLayout(MathHand.of(index.stats, style.wordGapXHeights), glyphsOf(index, rng), rng)
        val maths = layout.compose(tree, pieces) ?: return null
        val ink = frame.denormalise(maths.ink) ?: return null
        val neural = runs.firstOrNull { it.backend == GeneratedInk.NEURAL }
        return WordOutcome.Written(
            index = k,
            text = text,
            ink = ink,
            advancePt = maths.width * style.xHeightPt,
            attempts = runs.maxOf { it.attempts },
            joins = runs.sumOf { it.joins },
            backend = neural?.backend ?: GeneratedInk.ASSEMBLY,
            model = neural?.model ?: GeneratedInk.NO_MODEL,
            readBack = runs.all { it.readBack },
            strokeTags = maths.strokeTags,
        )
    }

    private fun fromLeft(ink: PenTrajectory): PenTrajectory? =
        ink.transformed(Matrix3.translate(-ink.bounds().left, 0f))

    companion object {
        fun of(tokens: List<HandwritingSynthesizer.Token>): MathWords {
            val trees = ArrayList<MathRow?>()
            val ranges = ArrayList<IntRange>()
            val pieces = ArrayList<HandwritingSynthesizer.Token>()
            for (token in tokens) {
                val tree = MathNotation.parse(token.text)
                val texts = tree?.let(MathNotation::texts).orEmpty()
                val start = pieces.size
                if (tree == null || texts.isEmpty()) {
                    trees += null
                    pieces += token
                } else {
                    trees += tree
                    texts.forEach { pieces += HandwritingSynthesizer.Token(it, 0) }
                }
                ranges += start until pieces.size
            }
            return MathWords(tokens, trees, ranges, pieces)
        }

        /**
         * The user's own copies of a structural mark, one picked at random per use, or null when they
         * have never written one on its own and it is drawn instead. A verified single letter, or a
         * one-character word they vouched for: the quick start's symbols prompt keeps a `√` whole when
         * its strokes do not cluster as one letter.
         */
        fun glyphsOf(index: GlyphIndex, rng: Random): (Char) -> PenTrajectory? = { mark ->
            index.variants(mark.toString())
                .filter { it.kind == UnitKind.LETTER || (it.kind == UnitKind.WORD && it.vouched) }
                .randomOrNull(rng)?.trajectory
        }
    }
}
