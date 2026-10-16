package dev.inkstone.app.calculator

import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import kotlinx.coroutines.CancellationException

/**
 * ⭐ What a dropped answer becomes: **the user's handwriting, and a text box when it cannot be** —
 * the user's choice of 2026-10-16 (*"Handwriting, else text"*, docs/CALCULATOR.md §6, A1).
 *
 * Plain functions in, so the rule can be tested without an editor (CLAUDE.md: hoist the decision
 * where a JVM test can reach it); `rememberAnswerTarget` hands it the real writes.
 *
 * - The words are the page's spelling ([AnswerPlacement.forPage]: a decimal point), for both.
 * - **Handwriting first**, starting on the ghost's baseline at the box's left edge. [handwrite] answers
 *   null when it wrote, or why it did not — no model, nothing learned, or a word the read-back gate
 *   refused (docs/HANDWRITING-SYNTHESIS.md §21.4.2 for how a number is judged).
 * - **Otherwise a text box** in [box], and the user is told why it is text, so a refusal never passes
 *   for a feature that did nothing.
 * - **A handwriting failure that throws is a refusal**, not a crash: this runs in a composition's
 *   scope, where an escaped exception takes the process with it (CLAUDE.md, *"an exception escaping a
 *   `LaunchedEffect` kills the process"*). Cancellation is not a failure and is rethrown.
 *
 * @param handwrite writes the words from a baseline start; null when written, else the reason.
 * @param typeset inserts a text box holding the words; false when the page took nothing.
 * @param report a line in the editor's snackbar.
 */
internal class AnswerLanding(
    private val handwrite: suspend (words: String, start: PagePoint) -> String?,
    private val typeset: suspend (box: PageRect, words: String) -> Boolean,
    private val report: (String) -> Unit,
) {
    @Suppress("TooGenericExceptionCaught")
    // Broad on purpose — see the class KDoc: anything the synthesizer throws must become a text box.
    suspend fun land(answer: String, box: PageRect, baselinePt: Float) {
        val words = AnswerPlacement.forPage(answer)
        val refused = try {
            handwrite(words, AnswerPlacement.baselineStart(box, baselinePt))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            "handwriting failed (${failure.message ?: failure::class.simpleName})"
        } ?: return
        if (typeset(box, words)) report("$AS_TEXT $refused") else report(NOT_PLACED)
    }

    companion object {
        const val AS_TEXT = "The answer went down as text —"
        const val NOT_PLACED = "The answer could not be put on this page."
    }
}
