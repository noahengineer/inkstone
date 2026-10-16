package dev.inkstone.app.search

import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.ModelDownload
import dev.inkstone.core.model.RecognitionModel
import dev.inkstone.core.model.RecognitionModels
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⭐ The press, followed: *Download* → a moving bar → *"reading N of M"* → *Ready* → OK.
 *
 * The controller is the production class, through its primary constructor. What is driven by hand is
 * what it talks to: ML Kit (a download the test finishes when it chooses), the library's note list,
 * and WorkManager — whose rows the test writes into the jobs flow itself, **after** `enqueue` returns,
 * because that is the order they arrive in on a device. The mapping from a real `WorkInfo` to those
 * rows is [ModelPanelTest]'s.
 *
 * ⚠️ The controller is given `backgroundScope`, for `RecordingControllerTest`'s reason: its flows are
 * collected eagerly for its whole life, and a `runTest` body waits for its children.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecognitionModelControllerTest {

    private class FakeModels(var here: Boolean) : RecognitionModels {
        var downloads = 0
        var finish = CompletableDeferred<ModelDownload>()

        override suspend fun available(): List<RecognitionModel> = emptyList()

        override suspend fun isDownloaded(languageTag: String): Boolean = here

        override suspend fun download(languageTag: String, unmeteredOnly: Boolean): ModelDownload {
            downloads++
            return finish.await().also { if (it == ModelDownload.Done) here = true }
        }

        override suspend fun delete(languageTag: String): Boolean = true.also { here = false }
    }

    private class Harness(scope: CoroutineScope, here: Boolean, val library: List<DocumentId>) {
        val models = FakeModels(here)
        val jobs = MutableStateFlow<List<RecognitionJob>>(emptyList())
        val online = MutableStateFlow(true)
        val queued = mutableListOf<Pair<Long, List<DocumentId>>>()
        val controller = RecognitionModelController(
            models = models,
            notes = { library },
            enqueue = { batch, notes -> queued += batch to notes },
            jobs = jobs,
            online = online,
            clock = Clock { BATCH },
            scope = scope,
        )

        /** WorkManager reporting the batch, [done] of it finished. */
        fun report(done: Int, pagesEach: Int = 2) {
            jobs.value = library.indices.map { i ->
                RecognitionJob(BATCH, finished = i < done, pagesRead = if (i < done) pagesEach else 0, unread = false)
            }
        }
    }

    private fun TestScope.harness(here: Boolean, notes: Int = 3) =
        Harness(backgroundScope, here, List(notes) { DocumentId("note-$it") }).also { runCurrent() }

    @Test
    fun `a press is followed from the download to ready and OK clears it`() = runTest {
        val h = harness(here = false)
        assertEquals(ModelPanel.Offer(null), h.controller.panel.value)

        h.controller.download()
        runCurrent()
        assertEquals(ModelPanel.Downloading(waitingForNetwork = false), h.controller.panel.value)

        h.models.finish.complete(ModelDownload.Done)
        runCurrent()
        assertEquals(listOf(BATCH to h.library), h.queued)
        // WorkManager has not reported the rows yet, and this must still not read as finished.
        assertEquals(ModelPanel.Reading(notesRead = 0, notes = 3), h.controller.panel.value)
        assertEquals(true, h.controller.downloaded.value)

        h.report(done = 0)
        runCurrent()
        assertEquals(ModelPanel.Reading(0, 3), h.controller.panel.value)
        h.report(done = 2)
        runCurrent()
        assertEquals(ModelPanel.Reading(2, 3), h.controller.panel.value)
        h.report(done = 3)
        runCurrent()
        assertEquals(ModelPanel.Ready(notes = 3, pagesRead = 6, notesUnread = 0), h.controller.panel.value)

        h.controller.dismiss()
        runCurrent()
        assertNull(h.controller.panel.value)
    }

    @Test
    fun `pressing twice is one download`() = runTest {
        val h = harness(here = false)
        h.controller.download()
        h.controller.download()
        runCurrent()
        h.controller.download()
        runCurrent()
        assertEquals(1, h.models.downloads)
    }

    @Test
    fun `a failed download says why and offers again and OK clears only the error`() = runTest {
        val h = harness(here = false)
        h.controller.download()
        h.models.finish.complete(ModelDownload.Failed("no network"))
        runCurrent()
        assertEquals(ModelPanel.Offer("no network"), h.controller.panel.value)
        assertTrue(h.queued.isEmpty())

        h.controller.dismiss()
        runCurrent()
        assertEquals(ModelPanel.Offer(null), h.controller.panel.value)

        // And the offer works a second time.
        h.models.finish = CompletableDeferred(ModelDownload.Done)
        h.controller.download()
        runCurrent()
        assertEquals(ModelPanel.Reading(0, 3), h.controller.panel.value)
    }

    @Test
    fun `OK does nothing while the model downloads or the notes are read`() = runTest {
        val h = harness(here = false)
        h.controller.download()
        runCurrent()
        h.controller.dismiss()
        runCurrent()
        assertEquals(ModelPanel.Downloading(waitingForNetwork = false), h.controller.panel.value)

        h.models.finish.complete(ModelDownload.Done)
        runCurrent()
        h.report(done = 1)
        runCurrent()
        h.controller.dismiss()
        runCurrent()
        assertEquals(ModelPanel.Reading(1, 3), h.controller.panel.value)
    }

    /** Measured: offline, ML Kit's download neither fails nor progresses. It waits for a network. */
    @Test
    fun `a download started offline says it is waiting and carries on when the network returns`() = runTest {
        val h = harness(here = false)
        h.online.value = false
        runCurrent()
        h.controller.download()
        runCurrent()
        assertEquals(ModelPanel.Downloading(waitingForNetwork = true), h.controller.panel.value)

        h.online.value = true
        runCurrent()
        assertEquals(ModelPanel.Downloading(waitingForNetwork = false), h.controller.panel.value)
        h.models.finish.complete(ModelDownload.Done)
        runCurrent()
        assertEquals(ModelPanel.Reading(0, 3), h.controller.panel.value)
    }

    @Test
    fun `an empty library is ready as soon as the model is`() = runTest {
        val h = harness(here = false, notes = 0)
        h.controller.download()
        h.models.finish.complete(ModelDownload.Done)
        runCurrent()
        assertEquals(ModelPanel.Ready(0, 0, 0), h.controller.panel.value)
    }

    /** A restarted process: WorkManager carried the jobs across, and nothing in memory did. */
    @Test
    fun `a batch still running at start-up is followed to ready`() = runTest {
        val h = Harness(backgroundScope, here = true, List(2) { DocumentId("note-$it") })
        h.report(done = 1)
        runCurrent()
        assertEquals(ModelPanel.Reading(1, 2), h.controller.panel.value)
        h.report(done = 2)
        runCurrent()
        assertEquals(ModelPanel.Ready(2, 4, 0), h.controller.panel.value)
    }

    @Test
    fun `a batch that finished before start-up is not announced again`() = runTest {
        val h = Harness(backgroundScope, here = true, List(2) { DocumentId("note-$it") })
        h.report(done = 2)
        runCurrent()
        assertNull(h.controller.panel.value)
    }

    @Test
    fun `a model that is already here offers nothing`() = runTest {
        val h = harness(here = true)
        assertNull(h.controller.panel.value)
        assertEquals(true, h.controller.downloaded.value)
    }

    private companion object {
        const val BATCH = 1_760_000_000_000L
    }
}
