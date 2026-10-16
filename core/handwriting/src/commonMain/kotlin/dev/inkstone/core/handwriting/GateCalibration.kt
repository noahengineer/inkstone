package dev.inkstone.core.handwriting

import dev.inkstone.core.model.InkReader
import dev.inkstone.core.model.InkReading
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlin.random.Random

/**
 * What kind of token a word is, as far as the recogniser's judgement goes — docs/HANDWRITING-SYNTHESIS.md
 * §8.2's *"digit strings, symbols, formulas, codes"*. The gate's rule and whether it can be trusted at
 * all are measured per class ([GateCalibration]).
 */
public enum class TokenClass {
    /** Letters, perhaps punctuated (`sin`, `KWh`, `d)`), and no digit. */
    WORD,

    /** A number and nothing else: sign, digits, one decimal point or comma, a trailing `%` (`-0.0375`, `46%`). */
    NUMBER,

    /** Digits with letters or operators (`2x+3`, `CaCO3`, `2A`, `(4.9`). */
    FORMULA,

    /** Neither letter nor digit (`=`, `+`, `/`, `(`). */
    SYMBOL,
    ;

    public companion object {
        private val NUMBER_SHAPE = Regex("^[-+−]?[0-9]+([.,][0-9]+)?%?$|^[-+−]?[.,][0-9]+%?$")

        public fun of(text: String): TokenClass {
            val t = text.filterNot(Char::isWhitespace)
            val letters = t.any(Char::isLetter)
            val digits = t.any(Char::isDigit)
            return when {
                digits && !letters && NUMBER_SHAPE.matches(t) -> NUMBER
                digits -> FORMULA
                letters -> WORD
                else -> SYMBOL
            }
        }
    }
}

/**
 * One class's calibration: how the user's own writing of it reads back.
 *
 * @property samples the positive control's size: the user's own words of this class that were read.
 * @property vouched of those, how many were labelled by the user (the quick start's prompts, a
 *   correction) rather than by the recogniser. ⚠️ A word the harvester learned was *chosen* for reading
 *   back as its label (§5.3), so it passes nearly by construction; only a vouched word is an unbiased
 *   witness. [positive] is measured on the vouched words when there are [GateCalibrator.MIN_VOUCHED] of
 *   them, on all of them otherwise, and [biased] says which.
 * @property positive each rule's pass rate on the user's words against their own labels — for a class
 *   with digits, on its first reading that holds a digit, as the gate reads one ([GateRule.digitReading]).
 * @property negative each rule's **failure** rate against a wrong label of similar length — §8.2's
 *   negative control.
 * @property negativeOneEdit the same, against a label one character away (`2.5`→`2.6`, `sin`→`sim`):
 *   the harder control, and the one a garbled word actually looks like. Reported; not a threshold.
 * @property judgeable whether the recogniser's verdict is worth anything for this class: false when even
 *   the most lenient safe rule passes less than [GateCalibrator.FLOOR] of the user's own writing. Such a
 *   token is written only from letters verified one by one, laid out without overlap (§8.2).
 * @property positiveAmongCandidates [positive] as it would be if any of the recogniser's candidates
 *   counted ([GateRule.passesAmong]). For a class with digits, the difference is what holding it to its
 *   first reading with a digit costs on this hand (§21.4); for any other class the two are equal.
 *   Reported; not a threshold. Empty in a calibration made before it existed.
 */
public data class ClassCalibration(
    val tokenClass: TokenClass,
    val samples: Int,
    val vouched: Int,
    val biased: Boolean,
    val positive: Map<GateRule, Float>,
    val negative: Map<GateRule, Float>,
    val negativeOneEdit: Map<GateRule, Float>,
    val rule: GateRule,
    val judgeable: Boolean,
    val positiveAmongCandidates: Map<GateRule, Float> = emptyMap(),
)

/**
 * ⭐ docs/HANDWRITING-SYNTHESIS.md §8.2 — the gate's rules, measured on this hand. Stored in
 * `profile.json` ([toJson]) and reported in the phase 5 notes.
 *
 * Before any calibration ([UNCALIBRATED]) every class is held to [GateRule.DEFAULT], which is what H3
 * and H4 shipped.
 */
public data class GateCalibration(
    val measuredEpochMillis: Long,
    val classes: Map<TokenClass, ClassCalibration>,
    /**
     * How many of the user's words were read — every class's, including one with too few words to be
     * calibrated. What tells a pass the profile has grown since ([classes]' own counts leave those out,
     * and a pass that compared against them recalibrated every time).
     */
    val wordsRead: Int = classes.values.sumOf { it.samples },
    /**
     * Which [GateRule] semantics the rates were measured under ([RULE_VERSION]). A calibration from an
     * older build is measured again on the next pass: its rules still apply in the meantime, read the
     * current way, but its rates — and so its rule and [ClassCalibration.judgeable] — describe a gate
     * that no longer exists.
     */
    val ruleVersion: Int = RULE_VERSION,
) {
    public fun ruleFor(text: String): GateRule = classes[TokenClass.of(text)]?.rule ?: GateRule.DEFAULT

    /**
     * False when the recogniser cannot judge [text]: its class on this hand ([ClassCalibration.judgeable]),
     * or a character it cannot read on any hand ([UNREADABLE]).
     */
    public fun judgeable(text: String): Boolean =
        text.none { it in UNREADABLE } && (classes[TokenClass.of(text)]?.judgeable ?: true)

    public fun toJson(): JsonObject = JsonObject(
        mapOf(
            "measured" to JsonPrimitive(measuredEpochMillis),
            "wordsRead" to JsonPrimitive(wordsRead),
            "ruleVersion" to JsonPrimitive(ruleVersion),
            "classes" to JsonArray(
                classes.values.map { c ->
                    JsonObject(
                        mapOf(
                            "class" to JsonPrimitive(c.tokenClass.name),
                            "samples" to JsonPrimitive(c.samples),
                            "vouched" to JsonPrimitive(c.vouched),
                            "biased" to JsonPrimitive(c.biased),
                            "rule" to JsonPrimitive(c.rule.name),
                            "judgeable" to JsonPrimitive(c.judgeable),
                            "positive" to rates(c.positive),
                            "negative" to rates(c.negative),
                            "negativeOneEdit" to rates(c.negativeOneEdit),
                            "positiveAmongCandidates" to rates(c.positiveAmongCandidates),
                        ),
                    )
                },
            ),
        ),
    )

    public companion object {
        public val UNCALIBRATED: GateCalibration = GateCalibration(0L, emptyMap())

        /**
         * The gate's rule semantics a calibration is measured under. 1: H5, every token against all of
         * the recogniser's candidates. 2: a token with a digit against the top one alone (§21.4.1). 3: a
         * token with a digit against its first reading that holds one (§21.4.2).
         */
        public const val RULE_VERSION: Int = 3

        /**
         * ⭐ Characters ML Kit's handwriting model cannot output at all, so no reading can confirm them
         * (docs/HANDWRITING-SYNTHESIS.md §20.6.4). Measured on `Test_Tablet`: 0 of 144 samples of twelve
         * formulas passed. `×` read as `x`, `÷` as `:`, `√` as `V`, `≤` as `<`, `≥` as `Z`, `≠` as `F`,
         * `±` as `t`, `π` as `t` or `17`, `θ` as `0`, `μ` as `N`, `Δ` as `A`; `°` and `≈` vanished. ML Kit
         * has no maths model to ask instead.
         *
         * A token holding one is judged as §8.2 judges every class the recogniser cannot read: built
         * only from the user's own verified copies, laid out without overlap, never by the network, never
         * read back. Its copies come from the quick start's symbols prompt ([QuickStart.PROMPTS]).
         */
        public const val UNREADABLE: String = "±×÷√π≤≥≠≈θΔμ°"

        private fun rates(map: Map<GateRule, Float>) = JsonObject(
            map.entries.associate {
                it.key.name to
                    JsonPrimitive(it.value)
            },
        )

        /** Null for anything unreadable: the gate then runs [UNCALIBRATED] until the next calibration. */
        @Suppress("ReturnCount")
        public fun fromJson(element: JsonElement?): GateCalibration? {
            val o = element as? JsonObject ?: return null
            val measured = (o["measured"] as? JsonPrimitive)?.longOrNull ?: return null
            val entries = runCatching { o["classes"]?.jsonArray }.getOrNull() ?: return null
            val classes = entries.mapNotNull { (it as? JsonObject)?.let(::classOf) }.associateBy { it.tokenClass }
            val read = (o["wordsRead"] as? JsonPrimitive)?.intOrNull ?: classes.values.sumOf { it.samples }
            // Absent: written by H5, before there was a version to write.
            val version = (o["ruleVersion"] as? JsonPrimitive)?.intOrNull ?: 1
            return GateCalibration(measured, classes, read, version)
        }

        /** One class's entry, or null when it names no class this build knows. */
        private fun classOf(c: JsonObject): ClassCalibration? {
            fun str(k: String) = (c[k] as? JsonPrimitive)?.contentOrNull
            fun int(k: String) = (c[k] as? JsonPrimitive)?.intOrNull
            fun flag(k: String) = (c[k] as? JsonPrimitive)?.booleanOrNull
            fun rates(k: String): Map<GateRule, Float> = (c[k] as? JsonObject)?.mapNotNull { (name, v) ->
                val rule = GateRule.entries.firstOrNull { it.name == name }
                val rate = (v as? JsonPrimitive)?.floatOrNull
                if (rule != null && rate != null) rule to rate else null
            }?.toMap().orEmpty()
            val tokenClass = TokenClass.entries.firstOrNull { it.name == str("class") } ?: return null
            return ClassCalibration(
                tokenClass = tokenClass,
                samples = int("samples") ?: 0,
                vouched = int("vouched") ?: 0,
                biased = flag("biased") ?: true,
                positive = rates("positive"),
                negative = rates("negative"),
                negativeOneEdit = rates("negativeOneEdit"),
                rule = GateRule.entries.firstOrNull { it.name == str("rule") } ?: GateRule.DEFAULT,
                judgeable = flag("judgeable") ?: true,
                positiveAmongCandidates = rates("positiveAmongCandidates"),
            )
        }
    }
}

/**
 * ⭐ Measures [GateCalibration] — §8.2's two controls, per [TokenClass], on the user's own words, through
 * the **real** recogniser and exactly the reading the gate gives a generated word
 * ([ReliabilityGate.toRead]).
 *
 * For each class, the rule is the **strictest** that passes at least [POSITIVE] of the user's own words
 * and fails at least [NEGATIVE] of wrong labels. With no rule reaching [POSITIVE], the most lenient one
 * that still fails [NEGATIVE] is used — and if even that passes less than [FLOOR] of the user's own
 * writing, the class is marked **not judgeable**. A class with fewer than [MIN_SAMPLES] words is not
 * calibrated at all and keeps [GateRule.DEFAULT].
 *
 * ⚠️ [GateRule.PUNCTUATION] is never a candidate for a class with digits: it would pass `25` for `2.5`.
 * And every rate is measured through [GateRule.passes], so a class with digits is measured on its first
 * reading that holds a digit, exactly as the gate will judge it (§21.4): its judgeability falls with what
 * that costs.
 */
public object GateCalibrator {
    public const val POSITIVE: Float = 0.95f
    public const val NEGATIVE: Float = 0.99f
    public const val FLOOR: Float = 0.8f
    public const val MIN_SAMPLES: Int = 8
    public const val MIN_VOUCHED: Int = 10
    private const val BATCH = 24
    private const val X_HEIGHT_PT = 5.5f

    /** Strictest first. */
    private fun candidates(c: TokenClass): List<GateRule> = when (c) {
        TokenClass.WORD -> listOf(GateRule.EXACT, GateRule.SPACING, GateRule.CASE_ALIKE, GateRule.PUNCTUATION)
        TokenClass.NUMBER, TokenClass.FORMULA -> listOf(GateRule.EXACT, GateRule.SPACING, GateRule.CASE_ALIKE)
        TokenClass.SYMBOL -> listOf(GateRule.EXACT, GateRule.SPACING)
    }

    /** One of the user's words, read back. */
    public class Reading(public val label: String, public val vouched: Boolean, public val readings: List<String>)

    /** Every usable word of [samples], read by [reader] as the gate would read it. Null if it cannot read. */
    public suspend fun read(samples: List<WordSample>, labels: LabelBook, reader: InkReader): List<Reading>? {
        val frame = NormalFrame(0f, 0f, 0f, 0f, X_HEIGHT_PT)
        val items = samples.filter { it.sourceKey !in labels.rejected && it.noteId.value !in labels.excludedNotes }
            .mapNotNull { s ->
                val ink = GlyphIndex.normalisedWord(s, labels)?.let(frame::denormalise) ?: return@mapNotNull null
                val vouched = labels.sourceOf(s) != LabelSource.AGREED
                Triple(ReliabilityGate.Item(labels.labelOf(s), ink, X_HEIGHT_PT, null), vouched, s)
            }
        val out = ArrayList<Reading>()
        for (chunk in items.chunked(BATCH)) {
            val reading = reader.read(chunk.map { ReliabilityGate.toRead(it.first) }, ReliabilityGate.CANDIDATES)
            if (reading !is InkReading.Read) return null
            chunk.forEachIndexed { i, (item, vouched, _) ->
                out += Reading(item.label, vouched, reading.results.getOrNull(i).orEmpty().map { it.text.trim() })
            }
        }
        return out
    }

    /** The calibration [readings] imply. Pure: the device reads, this decides, and a test can pin it. */
    public fun calibrate(readings: List<Reading>, nowEpochMillis: Long, seed: Long = 1L): GateCalibration {
        val rng = Random(seed)
        val byClass = readings.groupBy { TokenClass.of(it.label) }
        val allLabels = readings.map { it.label }.distinct()
        val classes = byClass.mapNotNull { (tokenClass, group) ->
            if (group.size < MIN_SAMPLES) return@mapNotNull null
            val vouched = group.filter { it.vouched }
            val biased = vouched.size < MIN_VOUCHED
            val control = if (biased) group else vouched
            val rules = candidates(tokenClass)
            val positive = rules.associateWith { r -> rate(control) { r.passes(it.label, it.readings) } }
            val wrong = group.associateWith { wrongLabel(it.label, tokenClass, allLabels, rng) }
            val edited = group.associateWith { oneEdit(it.label, rng) }
            val negative = rules.associateWith { r -> rate(group) { !r.passes(wrong.getValue(it), it.readings) } }
            val negativeOneEdit = rules.associateWith { r ->
                rate(group) { !r.passes(edited.getValue(it), it.readings) }
            }
            val safe = rules.filter { (negative[it] ?: 0f) >= NEGATIVE }
            val chosen = safe.firstOrNull { (positive[it] ?: 0f) >= POSITIVE } ?: safe.lastOrNull() ?: rules.first()
            val judgeable = (positive[chosen] ?: 0f) >= FLOOR && chosen in safe
            val amongCandidates = rules.associateWith { r -> rate(control) { r.passesAmong(it.label, it.readings) } }
            ClassCalibration(
                tokenClass, group.size, vouched.size, biased, positive, negative, negativeOneEdit, chosen, judgeable,
                amongCandidates,
            )
        }
        return GateCalibration(nowEpochMillis, classes.associateBy { it.tokenClass }, readings.size)
    }

    private inline fun rate(group: List<Reading>, pass: (Reading) -> Boolean): Float =
        if (group.isEmpty()) 0f else group.count(pass).toFloat() / group.size

    /** §8.2's negative: another of the user's labels, of this class where one exists, of similar length. */
    private fun wrongLabel(label: String, tokenClass: TokenClass, all: List<String>, rng: Random): String {
        fun near(c: String) = c != label && kotlin.math.abs(c.length - label.length) <= 1 &&
            !c.equals(label, ignoreCase = true)
        val sameClass = all.filter { near(it) && TokenClass.of(it) == tokenClass }
        val pool = sameClass.ifEmpty { all.filter(::near) }
        return if (pool.isEmpty()) oneEdit(label, rng) else pool[rng.nextInt(pool.size)]
    }

    /** The label with one character changed for one of its own kind: a digit for a digit, a letter for a letter. */
    internal fun oneEdit(label: String, rng: Random): String {
        if (label.isEmpty()) return "?"
        val positions = label.indices.filter { label[it].isLetterOrDigit() }.ifEmpty { label.indices.toList() }
        val at = positions[rng.nextInt(positions.size)]
        val c = label[at]
        val pool = when {
            c.isDigit() -> DIGITS
            c.isLetter() -> LETTERS
            else -> SYMBOLS
        }.filter { !it.equals(c, ignoreCase = true) && it != c }
        return label.substring(0, at) + pool[rng.nextInt(pool.length)] + label.substring(at + 1)
    }

    private const val DIGITS = "0123456789"
    private const val LETTERS = "abcdefghjkmnpqrtuvwy"
    private const val SYMBOLS = "+-=/()%"
}
