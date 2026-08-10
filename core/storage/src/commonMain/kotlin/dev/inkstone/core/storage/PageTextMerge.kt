package dev.inkstone.core.storage

import dev.inkstone.core.model.PageText

/** What `NoteSession.reconcileText` did to one page, for the merge report. */
public enum class TextOutcome {
    /** Neither side had text for this page. The overwhelmingly common case. */
    NEITHER,

    /** Local text still describes the merged page and was kept. */
    KEPT_LOCAL,

    /** The peer's text describes the merged page and this device's did not, or it had none. */
    ADOPTED_REMOTE,

    /**
     * Neither candidate describes the merged page, so both were discarded.
     *
     * Expected after a merge that brought in strokes, not a failure — but worth counting,
     * because a note that reports this on every poll is a note whose two devices are
     * recognising in a loop and re-invalidating each other.
     */
    DROPPED,
}

/**
 * ⭐ **SPEC §5.5's prerequisite, discharged: how two devices agree on one page's recognised text.**
 *
 * ## The problem M6 recorded and did not solve
 *
 * `NoteStore.materialize` unions the peer's bundle entries with the local ones **local-wins**.
 * That is right for an entry only one device produces, and it is *symmetric* — so for a name
 * both devices produce, each keeps its own copy, neither is told, and the two never converge.
 * Nothing produced such a name before M7, which is the only reason it was left. SPEC §5.5's
 * `text/<pageId>.json` is named per **page**, so every device produces the same name and the
 * clash stops being hypothetical the day recognition ships.
 *
 * ## Why not a clock
 *
 * A Lamport clock would order the two writes and it would be answering the wrong question.
 * *"Whose recognition ran later"* is not interesting; both devices may have run against ink
 * that has since changed, in which case the newer one is just a more recent wrong answer. The
 * question that matters is **"is this text still about the ink on this page"**, and after a
 * merge the honest answer for both candidates is usually no — the merged page is the union of
 * two devices' strokes and neither device recognised that.
 *
 * ## The rule
 *
 * Recognised text is derived data, so it is content-keyed on the ink it was derived from
 * (`PageTextCodec.inkDigestOf`) and [choose] is a pure function of the merged page:
 *
 * 1. Candidates are the local file and the peer's — a **set**, so both devices see the same one.
 * 2. Keep those whose `inkDigest` equals the merged page's digest.
 * 3. None match → keep **nothing**. Both devices drop, both converge immediately, and the page
 *    falls out of the index until recognition runs again. Recognition is advisory (SPEC §8.3);
 *    stale text is *wrong* text, and wrong is worse than absent in the one feature whose whole
 *    job is to tell you what a page says.
 * 4. Both match → they describe the same ink and both are valid, so pick by a total order on
 *    content (longest, then lexicographic). Arbitrary, but **identical on both devices**, which
 *    is the only property required — and a rule that is arbitrary and deterministic beats one
 *    that is meaningful and asymmetric.
 *
 * Convergence in one step, with no clock, no tiebreak on `deviceId`, and no way to represent
 * the divergent outcome.
 *
 * ⚠️ **Step 3 is not a licence to delete on a whim.** It fires only when neither candidate
 * describes the current ink, i.e. the text is already known to be wrong. A merge that brings
 * in no new strokes leaves the digest alone, so the ordinary poll keeps the text it had.
 */
public object PageTextMerge {

    /**
     * @param local this device's `text/<pageId>.json`, or null if it has none.
     * @param remote the peer's, or null.
     * @param inkDigest the digest of the page **after** the merge — never before, or the
     *   surviving text describes ink that is one op out of date.
     */
    public fun choose(local: PageText?, remote: PageText?, inkDigest: String): PageText? {
        val matching = listOfNotNull(local, remote).filter { it.describes(inkDigest) }
        return when (matching.size) {
            0 -> null

            1 -> matching.single()

            // Longest first: with both valid, the one that read more of the page is the more
            // useful, and length is a property of the bytes rather than of who is asking.
            else -> matching.maxWithOrNull(compareBy({ it.text.length }, { it.text }))
        }
    }
}
