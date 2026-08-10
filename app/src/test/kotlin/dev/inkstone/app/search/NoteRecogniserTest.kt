package dev.inkstone.app.search

import dev.inkstone.app.note.InMemoryFileSystem
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.HandwritingRecogniser
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.InkSample
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageText
import dev.inkstone.core.model.RecognisedLine
import dev.inkstone.core.model.RecognisedWord
import dev.inkstone.core.model.RecognitionOutcome
import dev.inkstone.core.model.RecognitionUnavailable
import dev.inkstone.core.model.SampledStroke
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.StrokeSampler
import dev.inkstone.core.search.SearchHit
import dev.inkstone.core.search.SearchIndex
import dev.inkstone.core.storage.NoteSession
import dev.inkstone.core.storage.NoteStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC §8.3 — one note's recognition pass, over a **real** [NoteSession].
 *
 * The session is real on purpose. Every interesting decision this class makes is a decision
 * about session state — *"is this page's text already current"*, *"did the ink move while the
 * recogniser was working"* — and a fake session is a fake answer to exactly those questions.
 * What is faked is the three ports, because each of them is a platform the JVM does not have:
 * `androidx.ink`'s native sampler, ML Kit, and SQLDelight.
 *
 * ⚠️ **[RecordingIndex] can only prove that the pass *asked*.** That it is told about a page is
 * not evidence the word is findable — `SqlSearchIndex` and the search screen are what make it
 * so, and only driving the app covers that (CLAUDE.md definition of done #6).
 */
class NoteRecogniserTest {

    private val clock = Clock { NOW }

    // -----------------------------------------------------------------------
    // 1 — the ordinary case
    // -----------------------------------------------------------------------

    @Test
    fun `a page with strokes is recognised and the index is told what it says`() = runTest {
        val note = note()
        val page = note.pages.single()
        val index = RecordingIndex()
        val pass = NoteRecogniser(sampler(), recogniser(recognised(line("hello world", "hello", "world"))), index)

        val report = pass.recognise(note.session)

        val stored = assertNotNull(
            note.session.recognisedText(page),
            "the pass did not record the text on the session, so the bundle gets none",
        )
        assertEquals("hello world", stored.text)
        assertEquals(RECOGNISER, stored.recogniser)
        assertEquals(1, index.indexed.size, "the index was not told about the page the pass just read")
        assertEquals(note.id, index.indexed.single().first)
        // Indexed from what the *session* answered, not from the recogniser's own outcome: that is
        // the read-back that re-checks the ink digest, so a stroke drawn while ML Kit was working
        // keeps its page out of the index instead of putting stale words in it.
        assertEquals(stored, index.indexed.single().second)
        assertEquals(0, index.forgotten.size, "an ordinary read told the index to forget something")
        assertNull(report.unavailable)
    }

    // -----------------------------------------------------------------------
    // 2 — ⭐ the skip that stops the nightly batch re-uploading the library
    // -----------------------------------------------------------------------

    @Test
    fun `a page whose text is already current is never sampled again`() = runTest {
        // ⭐ Without this branch the batch pass re-recognises every page of every note it walks,
        // which rewrites the bundle, which bumps `localRev`, which enqueues an upload — so a
        // notebook nobody has touched is re-uploaded in full every single night, forever.
        val note = note()
        val page = note.pages.single()
        val recorded = note.session.recognised(page, listOf(line("already read", "already", "read")), RECOGNISER)
        assertTrue(recorded, "the fixture failed to record any text, so the skip could not fire")

        val sampler = sampler()
        val index = RecordingIndex()
        val report = NoteRecogniser(sampler, recogniser(recognised(line("re-read", "re-read"))), index)
            .recognise(note.session)

        // The sampler is the first thing past the guard and the expensive half of the pass, so its
        // call count is the honest measure of "was this page skipped".
        assertEquals(0, sampler.sampled.size, "a page whose text was already current was sampled again")
        assertEquals(0, report.pagesRead)
        assertTrue(index.indexed.isEmpty(), "a skipped page was re-indexed")
        assertTrue(index.forgotten.isEmpty(), "a skipped page was forgotten from the index")
        assertEquals(
            "already read",
            note.session.recognisedText(page)?.text,
            "the current text was overwritten by a pass that should not have run",
        )
    }

    // -----------------------------------------------------------------------
    // 3 — ⭐ unavailable stops the pass and leaves the index alone
    // -----------------------------------------------------------------------

    @Test
    fun `an unavailable recogniser stops the pass and removes nothing from the index`() = runTest {
        // ⭐ Two failures in one. Carrying on would spend a model download's worth of failures per
        // page; clearing the index would delete a working search because a phone happened to be
        // offline — and a missing model is the normal state of a device that has never been online,
        // not evidence that the words already recorded were wrong (CLAUDE.md rule 1).
        val note = note(pageCount = 2)
        val sampler = sampler()
        val index = RecordingIndex()
        val recogniser = recogniser(RecognitionOutcome.Unavailable(RecognitionUnavailable.MODEL_NOT_DOWNLOADED))

        val report = NoteRecogniser(sampler, recogniser, index).recognise(note.session)

        assertEquals(
            RecognitionUnavailable.MODEL_NOT_DOWNLOADED,
            report.unavailable,
            "the reason was swallowed, so the UI cannot say 'download the English model'",
        )
        assertEquals(
            listOf(note.pages.first()),
            sampler.sampled,
            "the pass carried on past an unavailable recogniser, once per page",
        )
        assertTrue(index.forgotten.isEmpty(), "an offline device deleted a page's search results")
        assertTrue(index.forgottenNotes.isEmpty(), "an offline device deleted the whole note's search results")
        assertTrue(index.indexed.isEmpty(), "a failed pass wrote to the index")
        assertEquals(0, report.pagesRead)
        assertFalse(report.changedAnything)
    }

    // -----------------------------------------------------------------------
    // 4 — the two ways a page ends up with nothing on it
    // -----------------------------------------------------------------------

    @Test
    fun `a page with no strokes is forgotten by the index`() = runTest {
        // The user erased the last word on the page. Leaving the row behind is how a word someone
        // deliberately rubbed out goes on being findable.
        val note = note()
        val index = RecordingIndex()
        val pass = NoteRecogniser(sampler(strokes = emptyList()), recogniser(recognised(line("x", "x"))), index)

        val report = pass.recognise(note.session)

        assertEquals(listOf(note.id to note.pages.single()), index.forgotten)
        assertTrue(index.indexed.isEmpty())
        assertEquals(0, report.pagesRead)
        assertFalse(report.changedAnything, "a page with nothing on it was reported as a change")
    }

    @Test
    fun `a page the recogniser reports as nothing to read is forgotten by the index`() = runTest {
        // A page of diagram: strokes present, none of them writing. Distinct from the branch above
        // and it must reach the same place, or the erased word survives whenever a stroke remains.
        val note = note()
        val index = RecordingIndex()

        val report = NoteRecogniser(sampler(), recogniser(RecognitionOutcome.NothingToRead), index)
            .recognise(note.session)

        assertEquals(listOf(note.id to note.pages.single()), index.forgotten)
        assertTrue(index.indexed.isEmpty())
        assertNull(note.session.recognisedText(note.pages.single()), "a page with no writing was given text")
        assertEquals(0, report.pagesRead)
    }

    // -----------------------------------------------------------------------
    // 5 — which pages a pass reads
    // -----------------------------------------------------------------------

    @Test
    fun `an explicit page list reads only those pages`() = runTest {
        // The editor's debounce passes the pages the pen actually touched. Re-reading a 200-page
        // notebook three seconds after every stroke is not a debounce.
        val note = note(pageCount = THREE_PAGES)
        val sampler = sampler()

        val report = NoteRecogniser(sampler, recogniser(recognised(line("one", "one"))), RecordingIndex())
            .recognise(note.session, listOf(note.pages[1]))

        assertEquals(listOf(note.pages[1]), sampler.sampled, "a pass given one page read others as well")
        assertEquals(1, report.pagesRead)
    }

    @Test
    fun `an empty page list reads every live page`() = runTest {
        // What the background batch does, and the reason the parameter defaults to empty rather
        // than to "the first page" or to null.
        val note = note(pageCount = THREE_PAGES)
        val sampler = sampler()

        val report = NoteRecogniser(sampler, recogniser(recognised(line("one", "one"))), RecordingIndex())
            .recognise(note.session)

        assertEquals(note.pages, sampler.sampled, "the batch pass missed a live page")
        assertEquals(THREE_PAGES, report.pagesRead)
    }

    // -----------------------------------------------------------------------
    // 6 — what the report says
    // -----------------------------------------------------------------------

    @Test
    fun `the report counts the pages read and the words found`() = runTest {
        // The counts are what the batch worker logs and what the editor decides to materialize on,
        // so an off-by-one here is a note that never gets its text written to the bundle.
        val note = note(pageCount = 2)
        val outcome = recognised(
            line("hello world", "hello", "world"),
            line("third line", "third", "line"),
        )

        val report = NoteRecogniser(sampler(), recogniser(outcome), RecordingIndex()).recognise(note.session)

        assertEquals(2, report.pagesRead)
        assertEquals(WORDS_PER_PAGE * 2, report.wordsFound, "words are counted per word, not per line")
        assertTrue(report.changedAnything)
        assertNull(report.unavailable)
    }

    // -----------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------

    /** A note on disk, its session open, and its live page ids in order. */
    private class Fixture(val session: NoteSession, val pages: List<PageId>) {
        val id: DocumentId get() = session.document.id
    }

    /**
     * A real [NoteStore] over [InMemoryFileSystem], with one stroke drawn on every page.
     *
     * The stroke matters even though the sampler is faked: `recognised` computes the ink digest
     * from the folded page, so a fixture with no ink at all would certify text against an empty
     * page and the staleness check would be testing nothing.
     */
    private suspend fun note(pageCount: Int = 1): Fixture {
        val ids = IdFactory(clock)
        val store = NoteStore(InMemoryFileSystem(), clock, ids)
        val open = store.create(title = "Lecture", deviceId = DEVICE, pageCount = pageCount)
        val session = NoteSession.open(store, ids, clock, open, DEVICE)
        val pages = session.document.livePages.map { it.id }
        for ((index, pageId) in pages.withIndex()) {
            session.replay(
                pageId,
                listOf(
                    PageOp.AddObject(
                        opId = session.nextOpId(),
                        clock = session.nextClock(),
                        obj = StrokeObject(ObjectId("stroke-$index"), StrokeGeometry(byteArrayOf(1, 2, 3))),
                    ),
                ),
            )
        }
        return Fixture(session, pages)
    }

    private fun sampler(strokes: List<SampledStroke> = listOf(sampledStroke())) = CountingSampler(strokes)

    private fun recogniser(outcome: RecognitionOutcome) = ScriptedRecogniser(outcome)

    private fun recognised(vararg lines: RecognisedLine) =
        RecognitionOutcome.Recognised(lines.toList(), emptyList(), RECOGNISER)

    private fun line(text: String, vararg words: String) =
        RecognisedLine(text, LINE_BOX, words.map { RecognisedWord(it, LINE_BOX) })

    private fun sampledStroke() = SampledStroke(
        id = ObjectId("stroke-0"),
        samples = listOf(InkSample(LINE_BOX.left, LINE_BOX.top, 0L), InkSample(LINE_BOX.right, LINE_BOX.top, 20L)),
        bounds = LINE_BOX,
    )

    // -----------------------------------------------------------------------
    // Ports
    // -----------------------------------------------------------------------

    /**
     * A sampler that answers the same strokes for every page and remembers which pages it was
     * asked about — the one measurement that separates "the page was skipped" from "the page was
     * read and produced nothing".
     */
    private class CountingSampler(private val strokes: List<SampledStroke>) : StrokeSampler {

        val sampled = ArrayList<PageId>()

        override suspend fun sample(page: FoldedPage): List<SampledStroke> {
            sampled += page.pageId
            return strokes
        }
    }

    /** A recogniser that answers one scripted outcome, however many times it is asked. */
    private class ScriptedRecogniser(private val outcome: RecognitionOutcome) : HandwritingRecogniser {

        override suspend fun recognise(strokes: List<SampledStroke>): RecognitionOutcome = outcome
    }

    /**
     * A [SearchIndex] that records rather than stores.
     *
     * Both lists are asserted on for their *emptiness* as often as for their contents: the
     * unavailable case is defined by the index being left alone, and only a double that
     * distinguishes "told to forget" from "never spoken to" can say that.
     */
    private class RecordingIndex : SearchIndex {

        val indexed = ArrayList<Pair<DocumentId, PageText>>()
        val forgotten = ArrayList<Pair<DocumentId, PageId>>()
        val forgottenNotes = ArrayList<DocumentId>()

        override suspend fun indexPage(noteId: DocumentId, text: PageText) {
            indexed += noteId to text
        }

        override suspend fun forgetPage(noteId: DocumentId, pageId: PageId) {
            forgotten += noteId to pageId
        }

        override suspend fun forgetNote(noteId: DocumentId) {
            forgottenNotes += noteId
        }

        override suspend fun search(query: String, limit: Int): List<SearchHit> = emptyList()
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val DEVICE = "device-a"
        const val RECOGNISER = "test-recogniser/1.0/en"
        const val THREE_PAGES = 3

        /** Two lines of two words each, which is what `the report counts…` scripts per page. */
        const val WORDS_PER_PAGE = 4

        /** One line of writing, an inch in from the top-left corner of a Letter page. */
        val LINE_BOX = PageRect(72f, 72f, 200f, 90f)
    }
}
