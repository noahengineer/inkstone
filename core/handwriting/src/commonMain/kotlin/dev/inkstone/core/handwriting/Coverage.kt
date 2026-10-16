package dev.inkstone.core.handwriting

/**
 * What a profile has learned, in the terms *My handwriting* shows (docs/HANDWRITING-SYNTHESIS.md
 * §5.7) — and the numbers phase 2's notes report from the physics note.
 *
 * @property words samples the user has not removed.
 * @property heldOut of those, how many are kept back for measurement only (§5.5).
 * @property distinctWords different words, case-folded.
 * @property written how often each character occurs in the learned words: what the user has
 *   written at all.
 * @property letters verified letter samples per character: what assembly can use one letter at a
 *   time (§6.1). Counted from [GlyphIndex], not from the cuts: a cut from a held-out word, from a word
 *   whose frame is wrong ([LetterFit]), a subscript, or a letter carrying a stray mark is not one
 *   assembly can use.
 * @property joinedRuns verified pieces holding several joined letters — `th` written as one stroke —
 *   which are how assembly copies the user's joins (phase 3).
 */
public data class Coverage(
    val words: Int,
    val heldOut: Int,
    val distinctWords: Int,
    val written: Map<String, Int>,
    val letters: Map<String, Int>,
    val joinedRuns: Int = 0,
) {
    public companion object {
        public fun of(samples: List<WordSample>, labels: LabelBook): Coverage {
            val live = samples.filter { it.sourceKey !in labels.rejected && it.noteId.value !in labels.excludedNotes }
            val written = HashMap<String, Int>()
            for (sample in live) {
                for (unit in writtenUnits(labels.labelOf(sample))) written[unit] = (written[unit] ?: 0) + 1
            }
            // A corrected label invalidates letters cut against the old one.
            val cuts = live.filter { labels.labelOf(it) == it.label }.flatMap { it.glyphs }
            val joinedRuns = cuts.count { writtenUnits(it.text).size > 1 }
            // ⭐ H5: counting the cuts showed the tablet `= 1` for an `=` cut from `=27`, a word whose
            // letters measured six x-heights tall. GlyphIndex rightly never uses it, so the panel said
            // the user had an `=` that assembly could not write (§20.6.1).
            val index = GlyphIndex.build(samples, labels)
            val letters = index.alphabet
                .associateWith { a -> index.variants(a).count { it.kind == UnitKind.LETTER } }
                .filterValues { it > 0 }
            return Coverage(
                words = live.size,
                heldOut = live.count { it.split == SampleSplit.HELD_OUT },
                distinctWords = live.map { labels.labelOf(it).lowercase() }.toSet().size,
                written = written,
                letters = letters,
                joinedRuns = joinedRuns,
            )
        }
    }
}
