package dev.inkstone.core.model

import kotlin.math.roundToLong

/**
 * ⭐ `hw` — the one format key handwriting synthesis adds (docs/HANDWRITING-SYNTHESIS.md D2,
 * SPEC §4.10). It is on every stroke of a written phrase.
 *
 * ```
 * hw=<group>:<backend>:<model>
 * hw=01a1b2c3-…:a:-
 * ```
 *
 * - [group] is a UUIDv7 per phrase, so a tap on any stroke selects the whole phrase ([copyKey]).
 * - [backend] says which backend wrote it: `a` for glyph assembly, `n` for the neural model.
 * - [model] is the first eight hex digits of the base model's SHA-256, or `-` when no model was used.
 *
 * It lives in [ObjectFormat.extras] and not in a field of its own. A build that does not know it
 * keeps it there and writes it back, and the strokes it marks are ordinary ink to every reader:
 * erasing, selecting, sync, merge, search and the PDF need nothing new. That was the point of D1.
 *
 * What it is **for**, besides grouping:
 * - **learning** never reads ink that carries it (`Harvester.ineligibility`), so the profile can
 *   never be taught its own output;
 * - **provenance**: a generated mark can always be told from a written one, in the note and in the
 *   published PDF (D11).
 */
public data class GeneratedInk(val group: String, val backend: Char, val model: String = NO_MODEL) {

    public fun encode(): String = "$group$PART$backend$PART$model"

    /**
     * Which phrase this stroke belongs to: the group **and** where the stroke has been moved to.
     *
     * Every stroke of a phrase is placed with the identity transform, and moving the phrase moves
     * them all together, so they keep sharing one transform. The transform is in the key for the
     * reason it is in `ElementInstance.copyKey`: a duplicated phrase carries the same group at a
     * different place, and is a second phrase.
     */
    public fun copyKey(transform: Matrix3): String = group + "@" + listOf(
        transform.m00,
        transform.m01,
        transform.m02,
        transform.m10,
        transform.m11,
        transform.m12,
    ).joinToString(",") { (it * KEY_ROUNDING).roundToLong().toString() }

    /** This phrase's tag, ready for [ObjectFormat.extras]. */
    public fun applyTo(format: ObjectFormat): ObjectFormat =
        format.copy(extras = format.extras + (FORMAT_KEY to encode()))

    public companion object {
        /** The [ObjectFormat.extras] key. Short, as every format key is: it is on every stroke. */
        public const val FORMAT_KEY: String = "hw"

        public const val ASSEMBLY: Char = 'a'
        public const val NEURAL: Char = 'n'
        public const val NO_MODEL: String = "-"

        private const val PART = ':'
        private const val PARTS = 3
        private const val MODEL_DIGITS = 8
        private const val KEY_ROUNDING = 100f

        /** The tag on [format], or null when it has none or one this build cannot read. */
        public fun of(format: ObjectFormat): GeneratedInk? = format.extras[FORMAT_KEY]?.let(::decode)

        /** The tag in a raw `SetFormat` record. */
        public fun ofRecord(record: String?): GeneratedInk? = record?.let { of(ObjectFormat.decode(it)) }

        @Suppress("ReturnCount")
        public fun decode(value: String): GeneratedInk? {
            val parts = value.split(PART)
            if (parts.size != PARTS) return null
            val group = parts[0].takeIf(::isCarriable) ?: return null
            val backend = parts[1].singleOrNull()?.takeIf { it == ASSEMBLY || it == NEURAL } ?: return null
            val model = parts[2].takeIf { it == NO_MODEL || isModelDigits(it) } ?: return null
            return GeneratedInk(group, backend, model)
        }

        /** A group id that the record's separators cannot hide in — `ElementInstance`'s rule. */
        public fun isCarriable(id: String): Boolean =
            id.isNotEmpty() && id.none { it == PART || it == ';' || it == '=' }

        private fun isModelDigits(value: String): Boolean =
            value.length == MODEL_DIGITS && value.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

/**
 * Where a phrase was put and how big it was written — what *Write again* and *Edit text* need to
 * write it again in the same place (docs/HANDWRITING-SYNTHESIS.md §6.2).
 *
 * @property originX the phrase's first line's left baseline point, in page points, **at the time
 *   it was written**. Moving the phrase afterwards moves its strokes, not this record, so a reader
 *   maps it through the first stroke's current transform ([PhraseRecord.placementOn]).
 * @property wrapWidthPt the width lines were broken at.
 * @property linePitchPt the distance from one baseline to the next.
 * @property neatness `0..1`: how much variation the phrase was written with.
 * @property wordGapXHeights the word space it was laid out with, when the page gave one.
 */
public data class PhrasePlacement(
    val originX: Float,
    val originY: Float,
    val xHeightPt: Float,
    val slantRad: Float,
    val angleRad: Float,
    val wrapWidthPt: Float,
    val linePitchPt: Float,
    val neatness: Float,
    val wordGapXHeights: Float? = null,
)

/**
 * ⭐ The words a phrase was written from, on its first stroke's content register
 * (docs/HANDWRITING-SYNTHESIS.md D3, SPEC §4.10).
 *
 * ```
 * hw1;x=120.5;y=304;xh=5.2;sl=0.21;an=-0.01;w=320;p=17.5;n=0.5
 * Remember the milk
 * ```
 *
 * The first line is a header in the `k=v;k=v` style of every other record in this codebase; the
 * text follows the first newline, verbatim. The header carries the [PhrasePlacement], so *Write
 * again* lands where the phrase is and at its size. Unknown header keys are kept ([extras]) — SPEC
 * §5.8's rule, one level down.
 *
 * `SetTextContent` is the one arbitrary-length register in the vocabulary, and nothing reads it
 * for a stroke, so this is free. If the first stroke is erased away or deleted, the phrase becomes
 * plain ink — a degrade, never a fault.
 */
public data class PhraseRecord(
    val text: String,
    val placement: PhrasePlacement?,
    val extras: Map<String, String> = emptyMap(),
) {

    public fun encode(): String {
        val header = ArrayList<String>()
        header += PREFIX
        placement?.let { p ->
            header += "$K_X=${RecordText.number(p.originX)}"
            header += "$K_Y=${RecordText.number(p.originY)}"
            header += "$K_X_HEIGHT=${RecordText.number(p.xHeightPt)}"
            header += "$K_SLANT=${RecordText.number(p.slantRad)}"
            header += "$K_ANGLE=${RecordText.number(p.angleRad)}"
            header += "$K_WRAP=${RecordText.number(p.wrapWidthPt)}"
            header += "$K_PITCH=${RecordText.number(p.linePitchPt)}"
            header += "$K_NEATNESS=${RecordText.number(p.neatness)}"
            p.wordGapXHeights?.let { header += "$K_WORD_GAP=${RecordText.number(it)}" }
        }
        for (entry in extras.entries.sortedBy { it.key }) header += "${entry.key}=${entry.value}"
        return header.joinToString(";") + "\n" + text
    }

    /**
     * The placement as it is on the page now, given the first stroke's [transform]: the origin
     * mapped through it, and the size, slant and angle changed by as much as it scales and turns.
     */
    public fun placementOn(transform: Matrix3): PhrasePlacement? {
        val p = placement ?: return null
        val origin = transform.map(PagePoint(p.originX, p.originY))
        val scale = kotlin.math.sqrt(kotlin.math.abs(transform.m00 * transform.m11 - transform.m01 * transform.m10))
        if (!scale.isFinite() || scale <= 0f) return null
        val turn = kotlin.math.atan2(transform.m10, transform.m00)
        return p.copy(
            originX = origin.x,
            originY = origin.y,
            xHeightPt = p.xHeightPt * scale,
            angleRad = p.angleRad + turn,
            wrapWidthPt = p.wrapWidthPt * scale,
            linePitchPt = p.linePitchPt * scale,
        )
    }

    public companion object {
        public const val PREFIX: String = "hw1"
        private const val K_X = "x"
        private const val K_Y = "y"
        private const val K_X_HEIGHT = "xh"
        private const val K_SLANT = "sl"
        private const val K_ANGLE = "an"
        private const val K_WRAP = "w"
        private const val K_PITCH = "p"
        private const val K_NEATNESS = "n"
        private const val K_WORD_GAP = "g"
        private val PLACEMENT_KEYS =
            setOf(K_X, K_Y, K_X_HEIGHT, K_SLANT, K_ANGLE, K_WRAP, K_PITCH, K_NEATNESS, K_WORD_GAP)

        /**
         * The record in a content register, or null for anything else — a shape's `is1`, a text
         * box's words, nothing. The prefix is the discriminator, as it is for every record here.
         */
        @Suppress("ReturnCount")
        public fun decode(content: String?): PhraseRecord? {
            if (content == null) return null
            val newline = content.indexOf('\n')
            if (newline < 0) return null
            val header = content.substring(0, newline).split(';')
            if (header.firstOrNull() != PREFIX) return null
            val values = LinkedHashMap<String, String>()
            for (pair in header.drop(1)) {
                val split = pair.indexOf('=')
                if (split > 0) values[pair.substring(0, split)] = pair.substring(split + 1)
            }
            return PhraseRecord(
                text = content.substring(newline + 1),
                placement = placementOf(values),
                extras = values.filterKeys { it !in PLACEMENT_KEYS },
            )
        }

        /** All eight numbers, finite, or no placement at all: half a placement is a wrong one. */
        @Suppress("ReturnCount")
        private fun placementOf(values: Map<String, String>): PhrasePlacement? {
            fun number(key: String): Float? = values[key]?.toFloatOrNull()?.takeIf { it.isFinite() }
            val xHeight = number(K_X_HEIGHT)?.takeIf { it > 0f } ?: return null
            return PhrasePlacement(
                originX = number(K_X) ?: return null,
                originY = number(K_Y) ?: return null,
                xHeightPt = xHeight,
                slantRad = number(K_SLANT) ?: return null,
                angleRad = number(K_ANGLE) ?: return null,
                wrapWidthPt = number(K_WRAP)?.takeIf { it > 0f } ?: return null,
                linePitchPt = number(K_PITCH)?.takeIf { it > 0f } ?: return null,
                neatness = number(K_NEATNESS)?.coerceIn(0f, 1f) ?: return null,
                // Optional: a phrase written where the page gave no word space has none.
                wordGapXHeights = number(K_WORD_GAP)?.takeIf { it > 0f },
            )
        }
    }
}
