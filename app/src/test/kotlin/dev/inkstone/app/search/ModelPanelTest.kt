package dev.inkstone.app.search

import androidx.work.Data
import androidx.work.WorkInfo
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The model panel's decisions, as pure functions — what `RecognitionModelController` draws from, and
 * the half of it a JVM test can reach without WorkManager (CLAUDE.md, *"hoist the decision"*).
 *
 * [RecognitionJob.of] is tested against **real** `WorkInfo` objects, built with the library's own
 * constructor, so the tag parsing and the output keys are the ones WorkManager hands back and not a
 * fixture's idea of them.
 */
class ModelPanelTest {

    private fun job(batch: Long?, finished: Boolean, pages: Int = 0, unread: Boolean = false) =
        RecognitionJob(batch = batch, finished = finished, pagesRead = pages, unread = unread)

    @Test
    fun `nothing is said until the model has been asked about`() {
        assertNull(ModelPanels.panelFor(ModelFacts(), emptyList()))
    }

    @Test
    fun `a missing model is offered and a failed download says why`() {
        assertEquals(ModelPanel.Offer(null), ModelPanels.panelFor(ModelFacts(downloaded = false), emptyList()))
        assertEquals(
            ModelPanel.Offer("no network"),
            ModelPanels.panelFor(ModelFacts(downloaded = false, error = "no network"), emptyList()),
        )
    }

    @Test
    fun `a download in flight wins over everything`() {
        val facts = ModelFacts(downloaded = false, downloading = true, watching = 1, expected = 3)
        assertEquals(
            ModelPanel.Downloading(waitingForNetwork = false),
            ModelPanels.panelFor(facts, listOf(job(1, finished = true))),
        )
    }

    /** ML Kit waits for a network rather than failing, so offline the panel must not draw progress. */
    @Test
    fun `a download with no network says it is waiting`() {
        val facts = ModelFacts(downloaded = false, downloading = true, online = false)
        assertEquals(ModelPanel.Downloading(waitingForNetwork = true), ModelPanels.panelFor(facts, emptyList()))
    }

    @Test
    fun `a model that is here and no batch being followed says nothing`() {
        assertNull(ModelPanels.panelFor(ModelFacts(downloaded = true), listOf(job(7, finished = true))))
    }

    /**
     * ⚠️ The moment between the jobs being written and WorkManager reporting them. Without `expected`
     * this counts zero jobs, all of them finished, and says *Ready* before anything has been read.
     */
    @Test
    fun `a batch with no rows reported yet is reading not ready`() {
        val facts = ModelFacts(downloaded = true, watching = 5, expected = 3)
        assertEquals(ModelPanel.Reading(notesRead = 0, notes = 3), ModelPanels.panelFor(facts, emptyList()))
    }

    @Test
    fun `reading counts the finished jobs of its own batch only`() {
        val facts = ModelFacts(downloaded = true, watching = 5, expected = 3)
        val jobs = listOf(
            job(5, finished = true),
            job(5, finished = false),
            job(5, finished = false),
            // An earlier download's rows, which WorkManager keeps for about a day.
            job(4, finished = true),
            job(4, finished = true),
            job(null, finished = false),
        )
        assertEquals(ModelPanel.Reading(notesRead = 1, notes = 3), ModelPanels.panelFor(facts, jobs))
    }

    @Test
    fun `a finished batch is ready with what it read`() {
        val facts = ModelFacts(downloaded = true, watching = 5, expected = 3)
        val jobs = listOf(
            job(5, finished = true, pages = 4),
            job(5, finished = true, pages = 0),
            job(5, finished = true, pages = 2, unread = true),
            job(4, finished = true, pages = 99),
        )
        assertEquals(
            ModelPanel.Ready(notes = 3, pagesRead = 6, notesUnread = 1),
            ModelPanels.panelFor(facts, jobs),
        )
    }

    @Test
    fun `an empty library is ready at once`() {
        val facts = ModelFacts(downloaded = true, watching = 5, expected = 0)
        assertEquals(ModelPanel.Ready(0, 0, 0), ModelPanels.panelFor(facts, emptyList()))
    }

    @Test
    fun `a batch found running is followed and its size is what WorkManager reports`() {
        val jobs = listOf(job(8, finished = true), job(8, finished = false), job(3, finished = false))
        val adopted = ModelPanels.adopt(ModelFacts(downloaded = true), jobs)
        assertEquals(8L, adopted.watching)
        assertNull(adopted.expected)
        assertEquals(ModelPanel.Reading(notesRead = 1, notes = 2), ModelPanels.panelFor(adopted, jobs))
    }

    /** Adopting a finished one would repeat a *Ready* the user dismissed, at every start-up. */
    @Test
    fun `a batch that finished while nobody watched is not adopted`() {
        val facts = ModelFacts(downloaded = true)
        assertSame(facts, ModelPanels.adopt(facts, listOf(job(8, finished = true), job(8, finished = true))))
    }

    @Test
    fun `a batch already followed is not swapped for another`() {
        val facts = ModelFacts(downloaded = true, watching = 2, expected = 1)
        assertSame(facts, ModelPanels.adopt(facts, listOf(job(9, finished = false))))
    }

    @Test
    fun `the library offers only while searching but always shows what follows a press`() {
        assertFalse(ModelPanel.Offer(null).shownInLibrary(searching = false))
        assertTrue(ModelPanel.Offer(null).shownInLibrary(searching = true))
        assertTrue(ModelPanel.Offer("no network").shownInLibrary(searching = false))
        assertTrue(ModelPanel.Downloading(waitingForNetwork = true).shownInLibrary(searching = false))
        assertTrue(ModelPanel.Reading(1, 2).shownInLibrary(searching = false))
        assertTrue(ModelPanel.Ready(2, 5, 0).shownInLibrary(searching = false))
    }

    @Test
    fun `ready says pages when it read some and already read when it read none`() {
        assertEquals(
            "Read 183 pages in 38 notes. What you write from now on is read as you go.",
            readySummary(ModelPanel.Ready(notes = 38, pagesRead = 183, notesUnread = 0)),
        )
        assertEquals(
            "Read 1 page in 1 note. What you write from now on is read as you go.",
            readySummary(ModelPanel.Ready(notes = 1, pagesRead = 1, notesUnread = 0)),
        )
        assertEquals(
            "Every note had already been read. What you write from now on is read as you go.",
            readySummary(ModelPanel.Ready(notes = 38, pagesRead = 0, notesUnread = 0)),
        )
        assertEquals(
            "There were no notes to read. What you write from now on is read as you go.",
            readySummary(ModelPanel.Ready(notes = 0, pagesRead = 0, notesUnread = 0)),
        )
    }

    // -----------------------------------------------------------------------
    // RecognitionJob.of, over real WorkInfo
    // -----------------------------------------------------------------------

    private fun info(state: WorkInfo.State, tags: Set<String>, output: Data = Data.EMPTY) =
        WorkInfo(UUID.randomUUID(), state, tags, output)

    private val batchTags = setOf(
        RecognitionJob.TAG,
        "${RecognitionJob.BATCH_PREFIX}1760381234567",
        // WorkManager tags every request with its worker's class name.
        RecognitionWorker::class.java.name,
    )

    @Test
    fun `a succeeded job carries its batch and what it read`() {
        val output = Data.Builder()
            .putInt(RecognitionWorker.KEY_PAGES_READ, 7)
            .putBoolean(RecognitionWorker.KEY_UNREAD, false)
            .build()
        assertEquals(
            RecognitionJob(batch = 1_760_381_234_567L, finished = true, pagesRead = 7, unread = false),
            RecognitionJob.of(info(WorkInfo.State.SUCCEEDED, batchTags, output)),
        )
    }

    @Test
    fun `a pass that stopped early is unread although it succeeded`() {
        val output = Data.Builder()
            .putInt(RecognitionWorker.KEY_PAGES_READ, 1)
            .putBoolean(RecognitionWorker.KEY_UNREAD, true)
            .build()
        val job = RecognitionJob.of(info(WorkInfo.State.SUCCEEDED, batchTags, output))
        assertTrue(job.finished)
        assertTrue(job.unread)
        assertEquals(1, job.pagesRead)
    }

    /** No output at all must not read as *"read everything"*. */
    @Test
    fun `a failed or cancelled job is finished and unread`() {
        for (state in listOf(WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)) {
            val job = RecognitionJob.of(info(state, batchTags))
            assertTrue(job.finished, "$state")
            assertTrue(job.unread, "$state")
            assertEquals(0, job.pagesRead, "$state")
        }
    }

    @Test
    fun `a job still to run is neither finished nor unread`() {
        for (state in listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)) {
            val job = RecognitionJob.of(info(state, batchTags))
            assertFalse(job.finished, "$state")
            assertFalse(job.unread, "$state")
        }
    }

    @Test
    fun `a job with no batch tag belongs to no batch`() {
        assertNull(RecognitionJob.of(info(WorkInfo.State.ENQUEUED, setOf(RecognitionJob.TAG))).batch)
        assertNull(
            RecognitionJob.of(info(WorkInfo.State.ENQUEUED, setOf("${RecognitionJob.BATCH_PREFIX}not-a-number"))).batch,
        )
    }
}
