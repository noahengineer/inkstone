package dev.inkstone.app.handwriting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.inkstone.android.ink.TrajectoryInk
import dev.inkstone.app.platform.WorkManagerScheduler
import dev.inkstone.app.search.ModelPanel
import dev.inkstone.app.search.RecognitionModelController
import dev.inkstone.core.handwriting.QuickStart
import dev.inkstone.core.handwriting.QuickStartResult
import dev.inkstone.core.handwriting.quality.BackendChoice
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.PenKind
import dev.inkstone.core.model.PenStyle
import dev.inkstone.core.model.PenTrajectory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * *My handwriting* (docs/HANDWRITING-SYNTHESIS.md §5.7): what has been learned, and every way to
 * correct or remove it.
 *
 * A pass runs as a WorkManager job, not in this scope — *Learn now* must not stop when the user
 * leaves the screen — so this reads the learner's own progress and re-reads the overview when a
 * pass finishes, rather than once per page it harvests: the overview reads every sample on disk.
 * It re-reads it when the recognition model arrives, too, which is what enables *Learn now*.
 */
@HiltViewModel
class HandwritingViewModel @Inject constructor(
    private val learner: HandwritingLearner,
    private val scheduler: WorkManagerScheduler,
    private val recognitionModel: RecognitionModelController,
    private val clock: dev.inkstone.core.model.Clock,
) : ViewModel() {

    private val overviewState = MutableStateFlow<HandwritingOverview?>(null)

    /** Null until the first read. */
    val overview: StateFlow<HandwritingOverview?> = overviewState.asStateFlow()

    val progress: StateFlow<LearningProgress> = learner.progress

    /** The same panel the library's search shows — see [RecognitionModelController]. */
    val modelPanel: StateFlow<ModelPanel?> = recognitionModel.panel

    init {
        viewModelScope.launch {
            learner.progress.collect { progress -> if (progress is LearningProgress.Idle) refreshNow() }
        }
        viewModelScope.launch {
            // `drop(1)`: the first value is the state the screen opened in, which its own `refresh`
            // reads. Only a change afterwards — the download finishing — needs another read.
            recognitionModel.downloaded.drop(1).collect { refreshNow() }
        }
    }

    /** The OK on the model panel's *Ready*, or on a failed download's error. */
    fun dismissModelPanel() = recognitionModel.dismiss()

    fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    /** One pass, now, unconstrained — see [WorkManagerScheduler.enqueueHandwritingNow]. */
    fun learnNow() = scheduler.enqueueHandwritingNow()

    fun setLearning(on: Boolean) = act { learner.setLearning(on) }

    /** §8.4's override: who writes first. */
    fun setBackendChoice(choice: BackendChoice) = act { learner.setBackendChoice(choice) }

    fun setLearnFromOlderNotes(on: Boolean) = act { learner.setLearnFromOlderNotes(on) }

    fun correct(row: SampleRow, text: String) = act { learner.correct(row.sample, text) }

    fun confirm(row: SampleRow) = act { learner.confirm(row.sample) }

    fun remove(row: SampleRow) = act { learner.remove(row.sample) }

    fun ignoreNote(row: SampleRow) = act { learner.excludeNote(row.sample.noteId) }

    fun forgetEverything() = act { learner.forgetEverything() }

    /**
     * ⭐ D14's quick start: one prompt's writing, as `CONFIRMED` samples. No recogniser is asked
     * anything; the prompt is the label.
     */
    suspend fun saveQuickStart(prompt: String, index: Int, written: PenTrajectory): QuickStartResult {
        val result = QuickStart.samples(
            prompt = prompt,
            promptIndex = index,
            written = written,
            pen = PenStyle(PenKind.FOUNTAIN, QUICK_START_INK, QUICK_START_WIDTH_PT),
            unitLengthCm = TrajectoryInk.POINT_CM,
            ids = IdFactory(clock),
            epochDay = clock.nowEpochMillis() / MILLIS_PER_DAY,
        )
        if (result is QuickStartResult.Learned) {
            learner.addQuickStart(result.samples)
            refreshNow()
        }
        return result
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            refreshNow()
        }
    }

    private suspend fun refreshNow() {
        overviewState.value = learner.overview(RECENT_SAMPLES)
    }

    private companion object {
        /** Enough to review a night's learning; the counts above them cover the rest. */
        const val RECENT_SAMPLES = 80
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        /** The quick start's writing area draws in black at about a fine pen's width. */
        const val QUICK_START_INK = 0xFF1A1A1A.toInt()
        const val QUICK_START_WIDTH_PT = 1.2f
    }
}
