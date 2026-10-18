package dev.inkstone.app.search

import android.util.Log
import dev.inkstone.android.ocr.MlKitRecogniser
import dev.inkstone.app.library.LibraryRepository
import dev.inkstone.app.platform.NetworkStatus
import dev.inkstone.app.platform.WorkManagerScheduler
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.ModelDownload
import dev.inkstone.core.model.RecognitionModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ⭐ SPEC §8.1's model download, and the first read of the library that follows it, as one thing the
 * user can watch — [panel] is what both screens that offer the model draw.
 *
 * ## ⚠️ The process's scope, not a screen's
 *
 * The download used to run in `LibraryViewModel`'s `viewModelScope`, which dies with the activity. ML
 * Kit's own transfer carries on without us, but the step *after* it — queueing a read of every note —
 * was the coroutine's, so backing out of the app mid-download left the whole library unread until the
 * periodic batch's *"charging and idle"* came round, while the panel had already gone. This is a
 * singleton with its own scope for the reason `RecordingController` is (CLAUDE.md, *"`ViewModel.onCleared`
 * cannot suspend"*): the work must outlive the screen that asked for it.
 *
 * ## How the reading is counted
 *
 * Not by asking the database how many pages are current — that answer exists, but it changes for
 * reasons that have nothing to do with the download (a page drawn on, a note synced in), and a
 * progress bar that moves backwards is worse than none. The download starts one WorkManager job per
 * note, all tagged with one batch id ([WorkManagerScheduler.enqueueRecognition]); the bar is how many
 * of *those* have finished.
 */
@Singleton
class RecognitionModelController(
    private val models: RecognitionModels,
    /** The notes to read once the model is here. `LibraryRepository.allNoteIdsForRecognition`. */
    private val notes: suspend () -> List<DocumentId>,
    /** Queue one batch: its id, and the notes in it. `WorkManagerScheduler.enqueueRecognition`. */
    private val enqueue: suspend (Long, List<DocumentId>) -> Unit,
    /** Every job a download has queued, as WorkManager reports them. */
    jobs: Flow<List<RecognitionJob>>,
    /** Whether there is a network at all — so a download waiting for one can say so (`NetworkStatus`). */
    online: Flow<Boolean>,
    /** For the batch id only — a label for *which download*, never an order of operations. */
    private val clock: Clock,
    /**
     * The controller's own. A parameter so a test can drive it, and a secondary `@Inject` constructor
     * below rather than a default value, because Hilt would fail the build asking for a
     * `CoroutineScope` — `PollTicker`'s reasoning.
     */
    private val scope: CoroutineScope,
) {

    @Inject
    constructor(
        models: RecognitionModels,
        scheduler: WorkManagerScheduler,
        library: LibraryRepository,
        network: NetworkStatus,
        clock: Clock,
    ) : this(
        models = models,
        notes = library::allNoteIdsForRecognition,
        enqueue = scheduler::enqueueRecognition,
        jobs = scheduler.recognitionJobs(),
        online = network.online,
        clock = clock,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private val facts = MutableStateFlow(ModelFacts())

    private val seen: StateFlow<List<RecognitionJob>> = jobs
        .onEach { current -> facts.update { ModelPanels.adopt(it, current) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** What to draw. Null says nothing at all. */
    val panel: StateFlow<ModelPanel?> = combine(facts, seen, ModelPanels::panelFor)
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Downloaded, downloading and the last error, for Settings' recognition-model section. */
    val status: StateFlow<ModelFacts> = facts.asStateFlow()

    /** Whether the model is on the device; null until asked. *My handwriting* re-reads itself on a change. */
    val downloaded: StateFlow<Boolean?> = facts.map { it.downloaded }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        online.onEach { here -> facts.update { it.copy(online = here) } }.launchIn(scope)
        refresh()
    }

    /**
     * ⚠️ **Asked, not polled.** `isModelDownloaded` is a Play Services round trip; it is asked when the
     * process starts and after a download, which are the two times the answer can have changed.
     */
    fun refresh() {
        scope.launch {
            val here = models.isDownloaded(MlKitRecogniser.DEFAULT_LANGUAGE)
            facts.update { it.copy(downloaded = here) }
        }
    }

    /**
     * SPEC §8.1 — the user asked for the model, so it is fetched. **Never called on their behalf**: it
     * is tens of megabytes on a connection they may be paying for, and ML Kit reports anonymous usage
     * metrics to Google, which is a thing to say before the download and not after.
     *
     * Pressing it twice is one download: the flag is claimed atomically, and only the press that
     * claimed it starts anything.
     */
    fun download() {
        val before = facts.getAndUpdate { if (it.downloading) it else it.copy(downloading = true, error = null) }
        if (before.downloading) return
        scope.launch {
            val outcome = models.download(MlKitRecogniser.DEFAULT_LANGUAGE, unmeteredOnly = false)
            if (outcome != ModelDownload.Done) {
                val reason = (outcome as? ModelDownload.Failed)?.message ?: "the download did not finish"
                facts.update { it.copy(downloading = false, downloaded = false, error = reason) }
                return@launch
            }
            // Everything already written is unread. One pass now, rather than waiting for the device
            // to be idle and charging — the user just said they want this.
            val batch = clock.nowEpochMillis()

            @Suppress("TooGenericExceptionCaught") // Escaping this scope would crash the app.
            val queued = try {
                notes().also { enqueue(batch, it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // The model *is* here, so this is not a failed download: it is a library that is read
                // later, by the periodic batch. Saying "ready" would be untrue, so say nothing.
                Log.w(TAG, "the model arrived but the library's first read could not be queued", failure)
                facts.update { it.copy(downloading = false, downloaded = true, error = null) }
                return@launch
            }
            facts.update {
                it.copy(downloading = false, downloaded = true, error = null, watching = batch, expected = queued.size)
            }
        }
    }

    /**
     * The user's OK. Clears *Ready*, or the error under the offer — never a download or a read in
     * progress, which have nothing to dismiss and must not be made to look finished.
     */
    fun dismiss() {
        facts.update { current ->
            when (ModelPanels.panelFor(current, seen.value)) {
                is ModelPanel.Ready -> current.copy(watching = null, expected = null)
                is ModelPanel.Offer -> current.copy(error = null)
                else -> current
            }
        }
    }

    private companion object {
        const val TAG = "RecognitionModel"
    }
}
