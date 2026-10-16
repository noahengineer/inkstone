package dev.inkstone.app.search

import androidx.work.WorkInfo
import kotlin.math.max

/**
 * ⭐ What the app says about SPEC §8.1's language model, on the two screens that offer it — the
 * library's search and *My handwriting*.
 *
 * ## Why there are four states and not two
 *
 * It used to be *downloaded or not*, with a chip that read *"Downloading…"* while the request was in
 * flight. So the whole of the feedback for pressing *Download* was a word changing, and then the panel
 * **vanishing** — which is also what it does when nothing happened, and the next search still found
 * only titles, because the library had not been read yet. *"I think it worked but there was no real way
 * of knowing."* The press is now followed all the way to the point where search is actually better:
 * [Downloading] (a bar that moves — ML Kit reports no bytes), then [Reading] (a bar that fills, one
 * step per note), then [Ready], which stays until the user says OK.
 */
sealed interface ModelPanel {

    /**
     * Not on the device. The offer, with the size and SPEC §8.1's metrics disclosure in front of the
     * button.
     *
     * @property error why the last attempt failed, or null if there has not been one.
     */
    data class Offer(val error: String?) : ModelPanel

    /**
     * The request is with ML Kit. It reports no progress, so neither can this.
     *
     * @property waitingForNetwork there is no network at all. ML Kit does not fail then — it waits, and
     *   starts by itself when one appears — so the panel says that instead of drawing a moving bar
     *   over a download that is not happening.
     */
    data class Downloading(val waitingForNetwork: Boolean) : ModelPanel

    /** The model is here and the library is being read for the first time, note by note. */
    data class Reading(val notesRead: Int, val notes: Int) : ModelPanel

    /**
     * Every note the download started reading has been read.
     *
     * @property notesUnread notes whose pass stopped early (the recogniser failed, or the job did).
     *   The periodic batch reads them later; SPEC §8.3.
     */
    data class Ready(val notes: Int, val pagesRead: Int, val notesUnread: Int) : ModelPanel
}

/**
 * The library screen's rule, which is not *My handwriting*'s.
 *
 * ROADMAP M7: *"search degrades to titles, message is clear"* — so the offer appears while the user is
 * searching, as an answer to the question they just asked, and not as a banner about a 20 MB download
 * on a screen they opened to find a note. **Everything after the press is shown wherever they are**,
 * because it answers the press: clearing the search box while the model downloads must not take the
 * only progress bar with it. A failed download is the same — the error is the answer to a press.
 */
fun ModelPanel.shownInLibrary(searching: Boolean): Boolean = this !is ModelPanel.Offer || searching || error != null

/**
 * One note's recognition job, as the panel needs it. Plain data, so the decision below is a pure
 * function a JVM test can reach (CLAUDE.md, *"hoist the decision"*).
 *
 * @property batch the download that started it, or null for a job that is not part of one.
 */
data class RecognitionJob(val batch: Long?, val finished: Boolean, val pagesRead: Int, val unread: Boolean) {
    companion object {
        /** Tag on every job a model download enqueues; what the progress is queried by. */
        const val TAG: String = "inkstone.recognition.now"

        /** Prefix of the tag that names which download a job belongs to. */
        const val BATCH_PREFIX: String = "inkstone.recognition.batch."

        /**
         * ⚠️ **A job that did not succeed did not read.** `RecognitionWorker` returns `success` even
         * when it stops early (rule 5's reasoning: a backoff would be a retry loop), and says so in its
         * output — but a job that was cancelled, or threw, has no output at all, and *"no output"* must
         * not read as *"read everything"*.
         */
        fun of(info: WorkInfo): RecognitionJob {
            val succeeded = info.state == WorkInfo.State.SUCCEEDED
            return RecognitionJob(
                batch = info.tags.firstNotNullOfOrNull { tag ->
                    if (tag.startsWith(BATCH_PREFIX)) tag.removePrefix(BATCH_PREFIX).toLongOrNull() else null
                },
                finished = info.state.isFinished,
                pagesRead = if (succeeded) info.outputData.getInt(RecognitionWorker.KEY_PAGES_READ, 0) else 0,
                unread = info.state.isFinished &&
                    (!succeeded || info.outputData.getBoolean(RecognitionWorker.KEY_UNREAD, false)),
            )
        }
    }
}

/**
 * Everything the panel is a function of, apart from the jobs.
 *
 * @property downloaded null until Play Services has been asked. Unknown shows nothing: offering a
 *   download of a model that is already here would be the one thing worse than saying nothing.
 * @property watching the batch the panel follows — the one a download started, or one found still
 *   running after the process was restarted. Null once the user has said OK.
 * @property expected how many notes [watching] was started with. It is what keeps the bar honest in
 *   the moment between the jobs being written and WorkManager reporting them: without it the first
 *   frame after a download would count zero jobs, all of them finished, and say *Ready*. Null for a
 *   batch found running, whose size is whatever WorkManager reports.
 * @property online whether there is a network at all (`NetworkStatus`). True until told otherwise,
 *   so a device that cannot say is never told it is offline.
 */
data class ModelFacts(
    val downloaded: Boolean? = null,
    val downloading: Boolean = false,
    val error: String? = null,
    val watching: Long? = null,
    val expected: Int? = null,
    val online: Boolean = true,
)

/** The decisions, as pure functions over [ModelFacts] and the jobs. */
object ModelPanels {

    fun panelFor(facts: ModelFacts, jobs: List<RecognitionJob>): ModelPanel? {
        if (facts.downloading) return ModelPanel.Downloading(waitingForNetwork = !facts.online)
        if (facts.downloaded == false) return ModelPanel.Offer(facts.error)
        val batch = facts.watching ?: return null
        val mine = jobs.filter { it.batch == batch }
        val read = mine.count { it.finished }
        val notes = max(mine.size, facts.expected ?: 0)
        return if (read < notes) {
            ModelPanel.Reading(notesRead = read, notes = notes)
        } else {
            ModelPanel.Ready(
                notes = notes,
                pagesRead = mine.sumOf { it.pagesRead },
                notesUnread = mine.count { it.unread },
            )
        }
    }

    /**
     * Follow a batch that is still running when the panel is following none — which is what a
     * restarted process sees: WorkManager carried the jobs across, and nothing in memory did.
     *
     * Only an **unfinished** one. A batch that finished while nobody was looking has already said
     * everything it had to say in the library itself, and adopting it would show a *Ready* the user
     * dismissed yesterday every time the app starts, for as long as WorkManager keeps the rows.
     */
    fun adopt(facts: ModelFacts, jobs: List<RecognitionJob>): ModelFacts {
        if (facts.watching != null) return facts
        val running = jobs.filter { !it.finished }.mapNotNull { it.batch }.maxOrNull() ?: return facts
        return facts.copy(watching = running, expected = null)
    }
}
