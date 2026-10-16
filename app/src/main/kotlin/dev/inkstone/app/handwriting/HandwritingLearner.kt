package dev.inkstone.app.handwriting

import android.util.Log
import dev.inkstone.app.library.LibraryRepository
import dev.inkstone.app.sync.LearningNote
import dev.inkstone.app.sync.NoteSessionHost
import dev.inkstone.core.handwriting.Coverage
import dev.inkstone.core.handwriting.Departures
import dev.inkstone.core.handwriting.HarvestPolicy
import dev.inkstone.core.handwriting.HarvestStats
import dev.inkstone.core.handwriting.Harvester
import dev.inkstone.core.handwriting.LabelBook
import dev.inkstone.core.handwriting.LabelSource
import dev.inkstone.core.handwriting.LedgerEntry
import dev.inkstone.core.handwriting.PageHarvest
import dev.inkstone.core.handwriting.PageInput
import dev.inkstone.core.handwriting.ProfileKey
import dev.inkstone.core.handwriting.ProfileState
import dev.inkstone.core.handwriting.ProfileStore
import dev.inkstone.core.handwriting.QuickStart
import dev.inkstone.core.handwriting.WordSample
import dev.inkstone.core.handwriting.quality.BackendChoice
import dev.inkstone.core.handwriting.readLedger
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.InkReader
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.RecognitionModels
import dev.inkstone.core.model.RecognitionUnavailable
import dev.inkstone.core.model.StrokeReader
import dev.inkstone.core.model.StrokeSampler
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.storage.NoteTrash
import dev.inkstone.core.sync.AccountStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ⭐ docs/HANDWRITING-SYNTHESIS.md phase 2 — learning the user's handwriting from what they write.
 *
 * The one owner of the profile's files (`ProfileStore` is not thread-safe by contract): every pass
 * and every edit from *My handwriting* goes through [serial].
 *
 * ## A pass ([learn])
 *
 * 1. **Recognise first, where it is owed.** Labels come from M7's recognised text, so a page with
 *    none teaches nothing — and M7's periodic batch has never been seen to fire
 *    (docs/M7-RECOGNITION.md), so older notes may have no text at all. [NoteSessionHost.recognise]
 *    skips pages already read, so this is cheap on a note that has been.
 * 2. **Read through the gate, read-only** ([NoteSessionHost.readForLearning]) — never a note an
 *    editor holds.
 * 3. **Harvest each changed page** ([Harvester]), withdrawing first whatever it learned from strokes
 *    that have since been erased, deleted or turned.
 * 4. **Remember where it got to**, per page (ink digest) and per note (`localRev`), so a pass that
 *    runs out of time — WorkManager stops a job at ten minutes — continues next time instead of
 *    starting again from the first note.
 *
 * ⚠️ **One note's failure is that note's.** An unmodelled exception from one note is logged and the
 * pass moves on: the M12 lesson (`OutboxDrainer`) was that one throw took every row queued behind
 * it. A missing recognition model stops the whole pass — nothing can be labelled — and is reported.
 */
@Singleton
@Suppress("LongParameterList", "TooManyFunctions")
class HandwritingLearner @Inject constructor(
    private val fs: FileSystem,
    private val clock: Clock,
    private val accounts: AccountStore,
    private val library: LibraryRepository,
    private val sessions: NoteSessionHost,
    private val sampler: StrokeSampler,
    private val strokes: StrokeReader,
    private val ink: InkReader,
    private val models: RecognitionModels,
    private val ledger: PenLedgerRecorder,
) {

    /**
     * Every write to the profile's files goes through this — `ProfileStore` is not thread-safe.
     * Held per note during a pass, never for a whole one, so a correction on *My handwriting* waits
     * seconds, not the length of a pass.
     */
    private val serial = Mutex()

    /** One pass at a time: a periodic and a *Learn now* pass interleaving would learn words twice. */
    private val passLock = Mutex()
    private val ids = IdFactory(clock)
    private val progressState = MutableStateFlow<LearningProgress>(LearningProgress.Idle(null))

    /** What the last pass did, or what the current one has done so far. */
    val progress: StateFlow<LearningProgress> = progressState.asStateFlow()

    private val revisionState = MutableStateFlow(0L)

    /**
     * Bumped whenever what the profile holds may have changed — a pass, a correction, a removal, the
     * quick start. `HandwritingWriter` rebuilds its glyph index when this moves, and not otherwise.
     */
    val revision: StateFlow<Long> = revisionState.asStateFlow()

    /**
     * Whose profile learning writes to: the first usable Nextcloud account by creation order
     * (account ids are UUIDv7), or [ProfileKey.LOCAL] when none is signed in (D10). A device with two
     * accounts learns into the older one; the pen does not say whose hand holds it.
     */
    suspend fun profileKey(): ProfileKey {
        val owner = accounts.all().filter { it.isUsable }.minByOrNull { it.id }
        return owner?.let { ProfileKey.forAccount(it.id) } ?: ProfileKey.LOCAL
    }

    /** Say that the profile may hold something different now. See [revision]. */
    private fun changed() {
        revisionState.value = revisionState.value + 1
    }

    /** One learning pass, for at most [budgetMillis] of wall-clock time. */
    suspend fun learn(budgetMillis: Long): LearningReport = passLock.withLock {
        val store = ProfileStore(fs, profileKey())
        val pass = serial.withLock { openPass(store, budgetMillis) }
            ?: return@withLock LearningReport(paused = true).also(::finish)
        if (!models.isDownloaded(pass.state.language)) {
            val missing = LearningReport(unavailable = RecognitionUnavailable.MODEL_NOT_DOWNLOADED)
            return@withLock missing.also(::finish)
        }
        ledger.flush()
        progressState.value = LearningProgress.Running(pages = 0, samples = 0)

        // Q8: a note purged from the trash takes its samples and its watermarks with it. Read off the
        // files and never the library index, which a lost database empties (Departures says why).
        val onDevice = notesOnDevice()
        serial.withLock { pass.forget(Departures.of(pass.heldSamples, pass.state, onDevice)) }
        // Phase 3: words learned whole before joined runs could be cut — and the quick start's, which
        // had no recogniser — are cut once, read back like a fresh harvest.
        serial.withLock { pass.recut() }

        val owed = library.notesForLearning().filter { (noteId, localRev) ->
            noteId.value !in pass.labels.excludedNotes && pass.state.harvestedNotes[noteId.value] != localRev
        }
        for ((noteId, _) in owed) {
            if (!pass.carryOn(learnSafely(pass, noteId))) break
        }

        serial.withLock {
            // Re-read rather than written from the copy the pass began with: the user may have paused
            // learning, or changed what it may read, while it ran — and that is theirs to keep.
            val now = store.state()
            val held = pass.heldSamples.mapTo(HashSet()) { it.id }
            store.saveState(
                now.copy(
                    harvestedPages = pass.pages,
                    harvestedNotes = pass.notes,
                    recutTried = (now.recutTried + pass.recutTried).filterTo(HashSet()) { it in held },
                ),
            )
            store.purgeTrash(clock.nowEpochMillis())
        }
        pass.report().also(::finish)
    }

    /**
     * Everything *My handwriting* shows, read in one go.
     *
     * Not under [serial]: every file it reads is replaced whole by an atomic rename (rule 10), so a
     * read can see the profile a moment out of date and never half of it — and taking the lock would
     * make the screen wait for whichever note a pass is on.
     */
    suspend fun overview(recent: Int): HandwritingOverview {
        val key = profileKey()
        val store = ProfileStore(fs, key)
        val state = store.state()
        val labels = store.labels()
        val set = store.samples()
        val live = set.samples.filter { it.sourceKey !in labels.rejected && it.noteId.value !in labels.excludedNotes }
        return HandwritingOverview(
            profile = key,
            state = state,
            coverage = Coverage.of(set.samples, labels),
            recent = live.sortedWith(compareByDescending<WordSample> { it.epochDay }.thenByDescending { it.id })
                .take(recent)
                .map { SampleRow(it, labels.labelOf(it), labels.sourceOf(it)) },
            unreadableShards = set.unreadableShards,
            modelReady = models.isDownloaded(state.language),
            quickStartDone = QuickStart.promptsDone(live),
        )
    }

    /**
     * What the personalisation pass reads (docs/HANDWRITING-SYNTHESIS.md §8): the profile as it stands.
     * Not under [serial], for [overview]'s reason — every file is replaced whole, atomically.
     */
    suspend fun snapshot(): ProfileSnapshot {
        val key = profileKey()
        val store = ProfileStore(fs, key)
        return ProfileSnapshot(key, store.samples().samples, store.labels(), store.state())
    }

    /**
     * A change to `profile.json` from the personalisation pass, under [serial] like every other write —
     * re-read inside the lock, so a learning pass's progress written meanwhile is kept — and the
     * revision moved, so the Write panel picks up a new calibration, rung or switch.
     */
    suspend fun editProfile(change: (ProfileState) -> ProfileState): ProfileState = serial.withLock {
        val store = ProfileStore(fs, profileKey())
        val next = change(store.state())
        store.saveState(next)
        changed()
        next
    }

    /** §8.4's override: *Auto*, *My letters* or *Model*. */
    suspend fun setBackendChoice(choice: BackendChoice) = editState { it.copy(backend = choice) }

    suspend fun setLearning(on: Boolean) = editState { it.copy(learning = on) }

    /**
     * Widening what may be learned from forgets where earlier passes got to, because a page they
     * finished under the narrower rule still has older ink nobody looked at.
     */
    suspend fun setLearnFromOlderNotes(on: Boolean) = editState { state ->
        if (on && !state.learnFromOlderNotes) {
            state.copy(learnFromOlderNotes = true, harvestedPages = emptyMap(), harvestedNotes = emptyMap())
        } else {
            state.copy(learnFromOlderNotes = on)
        }
    }

    /** The user says the strokes behind [sample] really say [text]. */
    suspend fun correct(sample: WordSample, text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        editLabels { book ->
            book.copy(
                corrected = book.corrected + (sample.sourceKey to cleaned),
                confirmed =
                book.confirmed - sample.sourceKey,
            )
        }
    }

    /** The user says [sample] was read correctly. */
    suspend fun confirm(sample: WordSample) = editLabels { it.copy(confirmed = it.confirmed + sample.sourceKey) }

    /**
     * Stop learning from [sample]'s strokes: the sample goes to the trash, and its strokes are
     * remembered as rejected so the next pass does not learn them again from the note.
     */
    suspend fun remove(sample: WordSample) = serial.withLock {
        val store = ProfileStore(fs, profileKey())
        val labels = store.labels()
        store.saveLabels(labels.copy(rejected = labels.rejected + sample.sourceKey), clock.nowEpochMillis())
        store.withdraw(setOf(sample.id), clock.nowEpochMillis())
        changed()
    }

    /**
     * ⭐ D14's quick start: prompted words, labelled by the prompt, added as they are. Nothing reads
     * them with the recogniser here; the next pass cuts any joined runs (`Harvester.recut`).
     */
    suspend fun addQuickStart(samples: List<WordSample>) = serial.withLock {
        if (samples.isEmpty()) return@withLock
        ProfileStore(fs, profileKey()).add(samples)
        changed()
        Log.i(TAG, "quick start: added=${samples.size}")
    }

    /** Never learn from [noteId] again, and withdraw what was learned from it. */
    suspend fun excludeNote(noteId: DocumentId) = serial.withLock {
        val store = ProfileStore(fs, profileKey())
        val labels = store.labels()
        store.saveLabels(labels.copy(excludedNotes = labels.excludedNotes + noteId.value), clock.nowEpochMillis())
        val fromNote = store.samples().samples.filter { it.noteId == noteId }.map { it.id }.toSet()
        store.withdraw(fromNote, clock.nowEpochMillis())
        changed()
    }

    /** *Delete my handwriting data*: the whole profile to its trash, recoverable for thirty days. */
    suspend fun forgetEverything() = serial.withLock {
        ProfileStore(fs, profileKey()).trashEverything(clock.nowEpochMillis())
        changed()
    }

    private suspend fun editState(change: (ProfileState) -> ProfileState) = serial.withLock {
        val store = ProfileStore(fs, profileKey())
        store.saveState(change(store.state()))
        changed()
    }

    private suspend fun editLabels(change: (LabelBook) -> LabelBook) = serial.withLock {
        val store = ProfileStore(fs, profileKey())
        store.saveLabels(change(store.labels()), clock.nowEpochMillis())
        changed()
    }

    /**
     * Ends every pass, including one that stopped because learning is paused or the model is missing.
     * It logs one line, so a pass the periodic worker ran overnight can be seen to have run, and what it
     * read can be counted, from logcat alone. The line holds counts only: no word, label or stroke.
     */
    private fun finish(report: LearningReport) {
        Log.i(TAG, "pass: $report")
        changed()
        progressState.value = LearningProgress.Idle(report)
    }

    /** Every note this device holds, live or in the local trash, read off the files, which are the truth. */
    private suspend fun notesOnDevice(): Set<String> =
        fs.list(NoteLayout.NOTES_DIR).toHashSet() + NoteTrash(fs, clock).entries().map { it.id.value }

    /** Null when learning is paused. Everything the pass needs, read once, under [serial]. */
    private suspend fun openPass(store: ProfileStore, budgetMillis: Long): Pass? {
        val state = store.state()
        if (!state.learning) return null
        val labels = store.labels()
        val held = store.samples().samples.toMutableList()
        val known = HashSet<String>().apply {
            held.forEach { add(it.sourceKey) }
            addAll(labels.rejected)
        }
        return Pass(store, state, held, known, labels, clock.nowEpochMillis(), budgetMillis)
    }

    /**
     * [Pass.learnFrom], with one note's unmodelled failure confined to that note: the M12 lesson
     * (`OutboxDrainer`) was that one throw took every row queued behind it. Cancellation still ends
     * the pass — WorkManager stopping a job is not a note's failure.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun learnSafely(pass: Pass, noteId: DocumentId): NoteOutcome = try {
        if (pass.outOfTime()) NoteOutcome.OutOfTime else serial.withLock { pass.learnFrom(noteId) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Log.w(TAG, "learning skipped note ${noteId.value}", failure)
        NoteOutcome.Skipped
    }

    private sealed interface NoteOutcome {
        data object Done : NoteOutcome

        data object Skipped : NoteOutcome

        data object OutOfTime : NoteOutcome

        data class Unavailable(val reason: RecognitionUnavailable) : NoteOutcome
    }

    /** The state of one [learn] call, so the per-note work reads as one method rather than ten locals. */
    private inner class Pass(
        private val store: ProfileStore,
        val state: ProfileState,
        private val held: MutableList<WordSample>,
        private val known: MutableSet<String>,
        val labels: LabelBook,
        private val started: Long,
        private val budgetMillis: Long,
    ) {
        val pages = state.harvestedPages.toMutableMap()
        val notes = state.harvestedNotes.toMutableMap()
        var finished = true
        var unavailable: RecognitionUnavailable? = null
        private var notesVisited = 0
        private var pagesHarvested = 0
        private var added = 0
        private var withdrawn = 0
        private var recutWords = 0
        private var stats = HarvestStats()
        val recutTried = HashSet<String>()
        private val harvester = Harvester(sampler, strokes, ink, ids)
        private val policy = HarvestPolicy(state.learnFromOlderNotes)

        fun outOfTime(): Boolean = clock.nowEpochMillis() - started > budgetMillis

        val heldSamples: List<WordSample> get() = held

        suspend fun withdraw(sampleIds: Set<String>) {
            if (sampleIds.isEmpty()) return
            withdrawn += store.withdraw(sampleIds, clock.nowEpochMillis())
            held.removeAll { it.id in sampleIds }
        }

        /** Withdraw what was learned from [noteIds], and forget how far passes got in them. */
        suspend fun forget(noteIds: Set<String>) {
            if (noteIds.isEmpty()) return
            withdraw(held.filter { it.noteId.value in noteIds }.mapTo(HashSet()) { it.id })
            notes.keys.removeAll(noteIds)
            pages.keys.removeAll { ProfileState.noteOfPageKey(it) in noteIds }
        }

        /**
         * Cuts held words that have no letters yet and more letters than clusters, once each. A
         * recogniser that cannot run leaves them owed for the next pass.
         */
        suspend fun recut() {
            val owed = held.filter { it.id !in state.recutTried && Harvester.needsRecut(it) }
            if (owed.isEmpty()) return
            val cut = harvester.recut(owed) ?: return
            val changed = cut.filterIndexed { i, sample -> sample.glyphs != owed[i].glyphs }
            store.replace(changed)
            val byId = changed.associateBy { it.id }
            held.replaceAll { byId[it.id] ?: it }
            owed.forEach { recutTried += it.id }
            recutWords += changed.size
        }

        /** Records [outcome]; false when the pass should stop at this note. */
        fun carryOn(outcome: NoteOutcome): Boolean {
            if (outcome is NoteOutcome.Unavailable) unavailable = outcome.reason
            val stop = outcome is NoteOutcome.Unavailable || outcome == NoteOutcome.OutOfTime
            if (stop) finished = false
            return !stop
        }

        suspend fun learnFrom(noteId: DocumentId): NoteOutcome {
            val noteLedger = fs.readLedger(noteId)
            if (noteLedger.isEmpty() && !state.learnFromOlderNotes) return NoteOutcome.Done
            sessions.recognise(noteId).unavailable?.let { return NoteOutcome.Unavailable(it) }
            notesVisited++
            return sessions.readForLearning(noteId) { note -> learnFrom(noteId, note, noteLedger) }
                ?: NoteOutcome.Skipped
        }

        private suspend fun learnFrom(
            noteId: DocumentId,
            note: LearningNote,
            noteLedger: Map<ObjectId, LedgerEntry>,
        ): NoteOutcome {
            for (pageId in note.pageIds) {
                val outcome = learnPage(noteId, note, pageId, noteLedger)
                if (outcome != null) return outcome
            }
            notes[noteId.value] = note.localRev
            return NoteOutcome.Done
        }

        /** One page. Null to go on to the next; an outcome to stop the note there. */
        private suspend fun learnPage(
            noteId: DocumentId,
            note: LearningNote,
            pageId: PageId,
            noteLedger: Map<ObjectId, LedgerEntry>,
        ): NoteOutcome? {
            if (outOfTime()) return NoteOutcome.OutOfTime
            // No current text: recognition skipped it or could not read it. Nothing to label with.
            val text = note.text(pageId) ?: return null
            val pageKey = ProfileState.pageKey(noteId.value, pageId.value)
            if (pages[pageKey] == text.inkDigest) return null
            val page = note.page(pageId)
            withdraw(Harvester.stale(held.filter { it.noteId == noteId && it.pageId == pageId }, page))
            val input = PageInput(noteId, page, text, noteLedger, clock.nowEpochMillis() / MILLIS_PER_DAY)
            return when (val result = harvester.harvest(input, policy, known)) {
                is PageHarvest.Unavailable -> NoteOutcome.Unavailable(result.reason)

                is PageHarvest.Harvested -> {
                    store.add(result.samples)
                    held += result.samples
                    result.samples.forEach { known += it.sourceKey }
                    added += result.samples.size
                    stats += result.stats
                    pages[pageKey] = text.inkDigest
                    pagesHarvested++
                    progressState.value = LearningProgress.Running(pages = pagesHarvested, samples = added)
                    null
                }
            }
        }

        fun report(): LearningReport = LearningReport(
            notesVisited = notesVisited,
            pagesHarvested = pagesHarvested,
            added = added,
            withdrawn = withdrawn,
            recut = recutWords,
            stats = stats,
            unavailable = unavailable,
            finished = finished,
            labelsCorrected = labels.corrected.size,
        )
    }

    private companion object {
        const val TAG = "HandwritingLearner"
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}

/**
 * What one pass did — what *My handwriting* says after "Learn now", and what phase 2's notes quote.
 *
 * @property finished false when the pass stopped early — out of time, or the recogniser missing —
 *   so the next one has more to do.
 */
data class LearningReport(
    val notesVisited: Int = 0,
    val pagesHarvested: Int = 0,
    val added: Int = 0,
    val withdrawn: Int = 0,
    val recut: Int = 0,
    val stats: HarvestStats = HarvestStats(),
    val unavailable: RecognitionUnavailable? = null,
    val paused: Boolean = false,
    val finished: Boolean = true,
    val labelsCorrected: Int = 0,
)

/** Whether a pass is running, and if not, what the last one did. */
sealed interface LearningProgress {
    data class Idle(val last: LearningReport?) : LearningProgress

    data class Running(val pages: Int, val samples: Int) : LearningProgress
}

/** The profile as one personalisation pass reads it. */
class ProfileSnapshot(
    val key: ProfileKey,
    val samples: List<WordSample>,
    val labels: LabelBook,
    val state: ProfileState,
)

/** One learned word as the review list shows it: the user's corrections applied. */
data class SampleRow(val sample: WordSample, val label: String, val source: LabelSource)

/** Everything *My handwriting* shows. */
data class HandwritingOverview(
    val profile: ProfileKey,
    val state: ProfileState,
    val coverage: Coverage,
    val recent: List<SampleRow>,
    val unreadableShards: Int,
    val modelReady: Boolean,
    /** Which quick-start prompts are recorded, so the quick start opens at the first that is not. */
    val quickStartDone: Set<Int> = emptySet(),
)
