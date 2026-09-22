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
     * @param gate how to get exclusive access to [session]. See [SessionGate] — the whole
     *   point is that the ML Kit call happens **outside** it.
     */
    suspend fun recognise(
        session: NoteSession,
        pages: Collection<PageId> = emptyList(),
        gate: SessionGate = SessionGate.Direct,
    ): RecognitionReport {
        val targets = pages.ifEmpty { gate.locked { session.document.livePages.map { it.id } } }
        var read = 0
        var words = 0
        for (pageId in targets) {
            when (val outcome = readPage(session, pageId, gate)) {
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

    /**
     * ⭐ **Three phases, and the middle one deliberately holds no lock.**
     *
     * Sampling reads the session and the commit writes it, so both go through [gate]. The ML Kit
     * pass in between is a pure function of a list of sampled strokes — it cannot see the session
     * and does not need to — and it is the part that takes **seconds** on a dense page, one
     * recogniser call per line.
     *
     * ⚠️ **Holding the editor's lock across it is what made undo stop responding.** `NoteEditor`
     * used to wrap this whole method in `serial.withLock`, and the pass fires three seconds after
     * the pen stops — which is exactly the moment a user stops drawing and reaches for undo. Every
     * press in that window queued, and the lot landed at once when ML Kit finished.
     *
     * ⭐ **And the storage layer was already written for this.** `NoteSession.recognised`'s KDoc
     * says *"recognition is asynchronous by construction — SPEC §8.3 debounces it three seconds
     * behind the pen — so the ink can and does change while it runs"*, and it returns **false**
     * when the digest moved. Under the old locking that could never happen, so the guard was
     * unreachable and the comment below about a stroke landing mid-pass described something the
     * code prevented. Both are load-bearing now.
     */
    private suspend fun readPage(session: NoteSession, pageId: PageId, gate: SessionGate): PageOutcome {
        val noteId = gate.locked { session.document.id }
        val strokes = gate.locked {
            // ⚠️ Skipping a page whose text is already current is not an optimisation, it is what
            // stops the batch pass rewriting the bundle — and therefore re-uploading the note —
            // every night for a notebook nobody has touched.
            if (session.recognisedText(pageId) != null) null else sampler.sample(session.page(pageId))
        } ?: return PageOutcome.Nothing
        if (strokes.isEmpty()) {
            index.forgetPage(noteId, pageId)
            return PageOutcome.Nothing
        }

        // ⭐ The expensive part, outside the gate. `strokes` is plain sampled geometry; nothing
        // here touches [session].
        return when (val outcome = recogniser.recognise(strokes)) {
            is RecognitionOutcome.Recognised -> gate.locked {
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
