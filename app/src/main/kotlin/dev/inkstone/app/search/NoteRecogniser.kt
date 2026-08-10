package dev.inkstone.app.search

import dev.inkstone.core.model.HandwritingRecogniser
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.RecognitionOutcome
import dev.inkstone.core.model.RecognitionUnavailable
import dev.inkstone.core.model.StrokeSampler
import dev.inkstone.core.search.SearchIndex
import dev.inkstone.core.storage.NoteSession
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SPEC §8.3 — read one note's pages and record what they say.
 *
 * The unit of work both callers share: `NoteEditor` runs it three seconds after the pen stops
 * (SPEC §8.3's debounce) on the session it already holds, and `NoteSessionHost.recognise` runs
 * it for the background batch on a session it opens itself. Neither may open a second session
 * over a note the other is writing, which is what the host's per-note mutex is for — so this
 * class deliberately takes a [NoteSession] rather than a [DocumentId] and cannot open one.
 *
 * ## What it does *not* do
 *
 * **It does not materialize.** Recognition is advisory (SPEC §8.3) and never worth a bundle
 * rewrite of its own; the text sits in the session and goes to disk with the next save. The
 * background caller materializes once, after the whole note, because it has nothing else to
 * wait for.
 *
 * **It does not delete text when the recogniser is unavailable.** A missing model is the
 * expected state on a device that has never been online — it is not evidence that the words
 * already recorded were wrong (CLAUDE.md rule 1).
 */
@Singleton
class NoteRecogniser @Inject constructor(
    private val sampler: StrokeSampler,
    private val recogniser: HandwritingRecogniser,
    private val index: SearchIndex,
) {

    /**
     * @param pages which pages to read. Empty means every live page — what the batch pass does.
     *   The editor passes the pages the pen has actually touched, because re-reading a
     *   200-page notebook every three seconds is not a debounce.
     */
    suspend fun recognise(session: NoteSession, pages: Collection<PageId> = emptyList()): RecognitionReport {
        val targets = pages.ifEmpty { session.document.livePages.map { it.id } }
        var read = 0
        var words = 0
        for (pageId in targets) {
            when (val outcome = readPage(session, pageId)) {
                // ⚠️ Stop, and do NOT touch the index. Carrying on would spend a model
                // download's worth of failures per page, and clearing the index would delete
                // a working search because a phone happened to be offline.
                is PageOutcome.Unavailable -> return RecognitionReport(read, words, outcome.reason)

                is PageOutcome.Read -> {
                    read++
                    words += outcome.words
                }

                PageOutcome.Nothing -> Unit
            }
        }
        return RecognitionReport(read, words)
    }

    private suspend fun readPage(session: NoteSession, pageId: PageId): PageOutcome {
        // ⚠️ Skipping a page whose text is already current is not an optimisation, it is what
        // stops the batch pass rewriting the bundle — and therefore re-uploading the note —
        // every night for a notebook nobody has touched.
        if (session.recognisedText(pageId) != null) return PageOutcome.Nothing

        val noteId = session.document.id
        val strokes = sampler.sample(session.page(pageId))
        if (strokes.isEmpty()) {
            index.forgetPage(noteId, pageId)
            return PageOutcome.Nothing
        }

        return when (val outcome = recogniser.recognise(strokes)) {
            is RecognitionOutcome.Recognised -> {
                if (!session.recognised(pageId, outcome.lines, outcome.recogniser)) {
                    PageOutcome.Nothing
                } else {
                    // Read back rather than indexed from `outcome`: `recognisedText` is the
                    // one accessor that re-checks the ink digest, so a stroke that landed
                    // while ML Kit was working keeps its page out of the index rather than
                    // putting stale words in it.
                    session.recognisedText(pageId)?.let { index.indexPage(noteId, it) }
                    PageOutcome.Read(outcome.lines.sumOf { it.words.size })
                }
            }

            // Nothing on the page could be writing — a page of diagram. The index must forget
            // whatever it held, or a word the user erased stays findable.
            RecognitionOutcome.NothingToRead -> {
                index.forgetPage(noteId, pageId)
                PageOutcome.Nothing
            }

            is RecognitionOutcome.Unavailable -> PageOutcome.Unavailable(outcome.reason)
        }
    }

    private sealed interface PageOutcome {
        data class Read(val words: Int) : PageOutcome

        data object Nothing : PageOutcome

        data class Unavailable(val reason: RecognitionUnavailable) : PageOutcome
    }
}

/**
 * What one recognition pass did.
 *
 * @property unavailable non-null when the pass stopped early. The UI turns this into the one
 *   message ROADMAP M7 asks to be clear — *"download the English model to search your
 *   handwriting"* — rather than a silent absence of results.
 */
data class RecognitionReport(
    val pagesRead: Int,
    val wordsFound: Int,
    val unavailable: RecognitionUnavailable? = null,
) {
    val changedAnything: Boolean get() = pagesRead > 0

    companion object {
        val NOTHING: RecognitionReport = RecognitionReport(0, 0)
    }
}
