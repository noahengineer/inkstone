package dev.inkstone.core.handwriting

import dev.inkstone.core.model.InkReader
import dev.inkstone.core.model.InkReading
import dev.inkstone.core.model.InkToRead
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PenTrajectory
import dev.inkstone.core.model.RecognitionUnavailable

/**
 * How closely a word must read back as itself to be drawn — docs/HANDWRITING-SYNTHESIS.md §8.2's
 * rule sets, strictest first.
 *
 * Phase 5 calibrates which one a profile uses, against the user's own held-out words. Until then
 * [DEFAULT] is fixed, and it is the strictest rule a real hand plausibly passes: the label must be
 * among the recogniser's candidates, ignoring only spaces the recogniser put inside a word and the
 * case of letters whose capital is the same shape (`o`/`O`, `s`/`S`, …).
 *
 * ⭐ **A token with a digit is read against one reading only, under every rule: the first that holds a
 * digit** ([digitBearing], [digitReading], §21.4). ML Kit's other candidates for a number are its
 * digit-for-digit look-alikes — `9.81`'s ink reads `[9.81, 0.81, 1.81, 2.81, 981]` — so *"among the
 * five"* passed a wrong digit 5 of 24 times and a gained decimal point 6 of 10 on real ink, and a typed
 * `0.81` could have been drawn reading `9.81`. Readings with no digit at all are skipped first, because a
 * digit read as a letter is never a wrong number on a page: the user's lone `1` reads `[l, (, I, i, 1]`
 * and their `5` `[s, S, 5, g, 9]`, and the top reading alone refused both (§21.4.2). Both rules were the
 * user's choice.
 */
public enum class GateRule {
    /** Character for character. */
    EXACT,

    /** Ignoring whitespace — ML Kit reads `CaCO3` as `CaCO 3` as readily as not. */
    SPACING,

    /** [SPACING], and case-blind for letters whose capital is the same shape. */
    CASE_ALIKE,

    /**
     * [CASE_ALIKE], ignoring punctuation too — §8.2's most lenient rung. ⚠️ **Never for a token with a
     * digit**: folding punctuation is exactly how `2.5` passes as `25` (§18.10's vanished decimal point),
     * so [GateCalibration] offers it to words alone.
     */
    PUNCTUATION,
    ;

    /** Whether [label] reads back under this rule: against [digitReading] alone if it has a digit. */
    public fun passes(label: String, readings: List<String>): Boolean =
        passesAmong(label, if (digitBearing(label)) digitReading(readings) else readings)

    /**
     * Whether [label] is any of [readings] under this rule — what [passes] was for every token before
     * §21.4. Only the calibration's report asks it ([ClassCalibration.positiveAmongCandidates]).
     */
    public fun passesAmong(label: String, readings: List<String>): Boolean = when (this) {
        EXACT -> readings.any { it == label }

        SPACING -> readings.any { it.filterNot(Char::isWhitespace) == label.filterNot(Char::isWhitespace) }

        CASE_ALIKE -> Harvester.readsAs(label.filterNot(Char::isWhitespace), readings)

        PUNCTUATION -> Harvester.readsAs(
            label.filter(Char::isLetterOrDigit),
            readings.map { r -> r.filter(Char::isLetterOrDigit) },
        )
    }

    public companion object {
        public val DEFAULT: GateRule = CASE_ALIKE

        /** §21.4: whether [label] is judged on [digitReading] alone, not on any reading — any token with a digit. */
        public fun digitBearing(label: String): Boolean = label.any(Char::isDigit)

        /**
         * The one reading a [digitBearing] label is judged against: the first that holds a digit, best
         * first. Empty when none does — a token with a digit that reads only as letters is not confirmed.
         */
        public fun digitReading(readings: List<String>): List<String> =
            readings.firstOrNull { r -> r.any(Char::isDigit) }?.let(::listOf).orEmpty()
    }
}

/**
 * ⭐ D9 — every word is read back by the recogniser before it is shown, and a word that does not
 * read as itself is not drawn.
 *
 * It asks [InkReader] for [CANDIDATES] readings of each word, with the words before it as context,
 * in an area the height of a line of writing at the word's size, and passes the word when its rule
 * finds its text among them — in the first that holds a digit, for a token with one ([GateRule.digitReading]).
 * One reader call per batch, so a phrase is one recogniser client, not one per word.
 *
 * ⚠️ **What a test of this proves.** Gate *logic* is tested against fakes, and a fake that always
 * agrees is M6's `FakeMergeGate` trap: it passes everything. Behaviour against the real ML Kit is
 * the emulator's question (docs §11), and the numbers live in the phase notes.
 */
public class ReliabilityGate(
    private val reader: InkReader,
    /** §8.2: which rule each kind of token is held to, measured on this hand ([GateCalibrator]). */
    public val calibration: GateCalibration = GateCalibration.UNCALIBRATED,
) {
    /** The rule [label] is held to. */
    public fun ruleFor(label: String): GateRule = calibration.ruleFor(label)

    /**
     * One word to check.
     *
     * @property ink the word as it will be drawn, in page orientation (y down), with its left baseline
     *   point at the origin. Only a translation separates it from the ink that lands on the page, and a
     *   recogniser does not care where on a page a word is.
     */
    public class Item(
        public val label: String,
        public val ink: PenTrajectory,
        public val xHeightPt: Float,
        public val preContext: String?,
    )

    /** What [check] decided. */
    public sealed interface Verdict {
        /** Per item: whether it passed, and the readings it was given (best first). */
        public data class Read(val passed: List<Boolean>, val readings: List<List<String>>) : Verdict

        public data class Unavailable(val reason: RecognitionUnavailable) : Verdict
    }

    public suspend fun check(items: List<Item>): Verdict {
        if (items.isEmpty()) return Verdict.Read(emptyList(), emptyList())
        return when (val reading = reader.read(items.map(::toRead), CANDIDATES)) {
            is InkReading.Unavailable -> Verdict.Unavailable(reading.reason)

            is InkReading.Read -> {
                val readings = items.indices.map { i -> reading.results.getOrNull(i).orEmpty().map { it.text.trim() } }
                Verdict.Read(
                    items.indices.map { i ->
                        ruleFor(items[i].label).passes(items[i].label, readings[i])
                    },
                    readings,
                )
            }
        }
    }

    public companion object {
        /** k in §8.2 — the same five the harvester asks for, so the two read alike. */
        public const val CANDIDATES: Int = Harvester.CANDIDATES

        /**
         * [item] as the recogniser is shown it: its strokes, in an area the height of a line of writing
         * at its size. The one place this is decided, so calibration reads the user's own words exactly
         * as the gate reads generated ones (§8.2).
         */
        public fun toRead(item: Item): InkToRead {
            val box = item.ink.bounds()
            val xh = item.xHeightPt
            val area = PageRect(
                left = box.left - xh * SIDE_MARGIN,
                top = minOf(box.top, -xh * AREA_ABOVE),
                right = box.right + xh * SIDE_MARGIN,
                bottom = maxOf(box.bottom, xh * AREA_BELOW),
            )
            return InkToRead(sampledStrokesOf(item.ink), area, item.preContext)
        }

        private const val AREA_ABOVE = 2.2f
        private const val AREA_BELOW = 1.1f
        private const val SIDE_MARGIN = 0.3f
    }
}
