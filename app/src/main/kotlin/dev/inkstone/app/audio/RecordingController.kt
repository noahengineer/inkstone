package dev.inkstone.app.audio

import dev.inkstone.core.database.recording.RecordingIndex
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageId
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.storage.OggOpus
import dev.inkstone.core.storage.TimelineWriter
import dev.inkstone.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * ⭐ M12's capture half, as one state machine (docs/M12-AUDIO.md §§9–11).
 *
 * ## Why this is a process-wide singleton and not part of the editor
 *
 * Two constraints meet here and only one shape satisfies both.
 *
 * **It must outlive the screen.** §9: *"Screen timeout, lock, a glance at a message, rotation,
 * split-screen: keep recording."* Anything owned by an Activity or a `ViewModel` is the wrong
 * lifetime — and hanging the stop off `onStop` is the specific failure the doc names, where the
 * screen dims at minute 8 and you lose the other 42 and do not find out until you look.
 *
 * **And the stop must be able to suspend.** *"Leaving the note"* is `NoteEditorViewModel.onCleared`
 * (see [noteLeft]) — but `ViewModel.clear()` cancels `viewModelScope` **before** calling
 * `onCleared`, so there is no scope left there to suspend in, and finalising an Ogg container,
 * flushing the last timeline records, applying §10's floor and enqueuing an outbox row are all
 * suspending I/O. `NoteSessionHost` already documents that trap for `unregister`.
 *
 * So: a `@Singleton` with its **own** scope. Every public entry point is non-suspending and hands
 * off into [scope]. Rotation and screen-off are invisible to it because it observes neither.
 *
 * ## The serialisation
 *
 * One [Mutex], for the reason `NoteEditor.serial` exists: start, stop and recovery all touch the
 * recorder, the row and the timeline, and two of them interleaving would finalise a file the other
 * is still writing. ⚠️ [strokeCaptured] is deliberately **outside** it — that runs on the UI
 * thread in the frame the dry layer redraws (CLAUDE.md rule 7), so it does one subtraction and one
 * enqueue onto a concurrent queue, exactly as `PageOpRecorder` does with a finished stroke.
 */
// One function per thing that can happen to a recording — press, auto-start, leave the note, draw
// a stroke, lose the microphone, get it back, crash and come back — plus the two the service calls.
// Collapsing any of them would merge two lifecycles that differ, which is the whole subject here.
@Suppress("TooManyFunctions", "LongParameterList")
class RecordingController(
    private val recorder: AudioRecorder,
    private val recordings: RecordingIndex,
    private val sync: SyncEngine,
    private val fs: FileSystem,
    private val ids: IdFactory,
    private val clock: Clock,
    private val scope: CoroutineScope,
    /** Foreground service control. See [RecordingService] for why the mic opens after it, not with it. */
    private val foreground: ForegroundHost,
    /** Absolute path for a [FileSystem] path — `MediaRecorder` writes through its own descriptor. */
    private val absolutePathOf: (String) -> String,
    /** Free space where recordings are written. §11: refuse to start rather than fail at minute 50. */
    private val freeBytes: suspend () -> Long,
    /** §10 — is auto-record on for the folder this note is in? Resolved by `AutoRecordFolders`. */
    private val autoRecordFor: suspend (DocumentId) -> Boolean,
    /** `SystemClock.elapsedRealtime`. Monotonic — see [dev.inkstone.core.storage.RecordingTimeline]. */
    private val elapsedRealtime: () -> Long,
) {

    /** What the service needs to do, without this class knowing what a `Context` is. */
    interface ForegroundHost {
        fun start()

        fun stop()
    }

    private val serial = Mutex()

    private val _state = MutableStateFlow(RecordingUiState())
    val state: StateFlow<RecordingUiState> = _state.asStateFlow()

    /** The live recording, or null. Only touched under [serial]. */
    private var live: Live? = null

    /**
     * ⚠️ Read by [strokeCaptured] on the **UI thread**, written under [serial] on another.
     *
     * `@Volatile` and nothing more: the only failure a race can produce here is a stroke landing
     * in the microsecond either side of a start or a stop, which costs one line's alignment. A lock
     * on the ink path would cost a frame, every stroke, forever (CLAUDE.md rule 7).
     */
    @Volatile
    private var anchorElapsed: Long = 0L

    /** Filled on the UI thread, drained by [flushLoop]. Concurrent, like `PageOpRecorder`'s. */
    private val captured = ConcurrentLinkedQueue<CapturedStroke>()

    /** One drawn stroke, as the ink path hands it over. */
    private class CapturedStroke(val objectId: ObjectId, val offsetMillis: Long, val pageId: PageId?)

    private class Live(
        val recordingId: String,
        val noteId: DocumentId,
        val timeline: TimelineWriter,
        /**
         * ⚠️ Zero until the microphone actually opens, and **everything** is measured from it: the
         * stroke offsets, the gaps, and the duration §10's floor is applied to.
         *
         * Not the moment the button was pressed. Starting a foreground service is I/O, and anchoring
         * to the press would make every offset late by however long that took — a constant error in
         * the one measurement §7's window is tuned against, and one that varies by device.
         */
        var anchorElapsed: Long = 0L,
        var silencedAt: Long? = null,
        var flusher: Job? = null,
    )

    // -----------------------------------------------------------------------
    // What the UI does
    // -----------------------------------------------------------------------

    /** The record button. One press. */
    fun toggle(noteId: DocumentId) {
        scope.launch {
            serial.withLock {
                if (live?.noteId == noteId) stopLocked(StopReason.USER) else startLocked(noteId)
            }
        }
    }

    /**
     * §10 — *"Auto-record is per folder, not global."*
     *
     * Called when a note is opened. ⚠️ It also **stops a recording running in a different note**,
     * which is not a nicety: `NoteActivity` is `launchMode="standard"`, so the moment anything
     * opens note B from inside note A — a search hit, a link — a second Activity stacks and note
     * A's `onCleared` never fires. Its microphone would run until the process died.
     */
    fun noteOpened(noteId: DocumentId) {
        scope.launch {
            serial.withLock {
                val running = live
                if (running != null && running.noteId != noteId) stopLocked(StopReason.LEFT_NOTE)
                if (live == null && autoRecordFor(noteId)) startLocked(noteId)
            }
        }
    }

    /**
     * ⭐ **The user left the note** — from `NoteEditorViewModel.onCleared`, which is the *only*
     * callback that means this.
     *
     * `onStop` fires for a screen timeout, a lock, a glance at a message, and going home; rotation
     * fires nothing at all, because `NoteActivity` declares `configChanges` for it. `onCleared`
     * fires when the Activity is genuinely finishing and not on a configuration change. That is
     * exactly §9's rule, and it is why this method exists rather than the editor calling [toggle].
     *
     * Guarded on [noteId] so a stacked second editor's teardown cannot stop a recording that
     * belongs to the note still on screen.
     */
    fun noteLeft(noteId: DocumentId) {
        scope.launch { serial.withLock { if (live?.noteId == noteId) stopLocked(StopReason.LEFT_NOTE) } }
    }

    /**
     * A stroke was just drawn. **Called on the UI thread; this must stay cheap** (CLAUDE.md rule 7).
     *
     * One volatile read, one subtraction, one enqueue. The file append rides [flushLoop] on the
     * journal's own rhythm, and takes no lock the editor holds — a timeline write that could wait
     * on `NoteEditor.serial` would queue behind every whole-bundle materialize that dimming the
     * screen causes, for the length of a lecture.
     *
     * @param atElapsedRealtime when the stroke **started**, latched at its down event. Writing lags
     *   speech, so §7's asymmetric window is anchored to when the mark began, not when it landed.
     * @param pageId the page it landed on — §15.6's alignment unit. Null is tolerated (the line is
     *   then written with two columns) but should not happen in the app: it means the editor did
     *   not know which page was on screen.
     */
    fun strokeCaptured(objectId: ObjectId, atElapsedRealtime: Long, pageId: PageId?) {
        val anchor = anchorElapsed
        if (anchor == 0L) return
        captured += CapturedStroke(objectId, atElapsedRealtime - anchor, pageId)
    }

    // -----------------------------------------------------------------------
    // What the service and the platform do
    // -----------------------------------------------------------------------

    /**
     * The foreground service is up. **Now** the microphone may open — see [RecordingService].
     *
     * Opening it first would work while the app is visible and fail the moment a lecture began
     * with the screen off, which is not a case anybody tests by hand.
     */
    fun onForegroundReady() {
        scope.launch { serial.withLock { openMicrophoneLocked() } }
    }

    /**
     * The message has been shown. Cleared so a rotation does not show it again.
     *
     * ⚠️ This exists because the messages were being **set and never read** — `LOW_SPACE` and the
     * thirty-second notice were written into the state with nothing in the UI collecting them, so
     * pressing record with a full disk would have done nothing at all and said nothing. That is the
     * inert-but-green shape CLAUDE.md lists six times; it was caught by grepping for the reader of
     * a new field rather than by any test.
     */
    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** §9 — a lecture the process died in the middle of. Called once, from the first screen. */
    fun recoverInterrupted() {
        scope.launch { serial.withLock { recoverLocked() } }
    }

    // -----------------------------------------------------------------------

    private suspend fun startLocked(noteId: DocumentId) {
        if (live != null) return
        // §11 — *"Refuse to start when free space is low. Discovering at minute 50 that writes
        // have been failing is not a recoverable outcome."*
        if (freeBytes() < MINIMUM_FREE_BYTES) {
            _state.value = RecordingUiState(message = LOW_SPACE)
            return
        }
        val recordingId = ids.newRecordingId()
        // ⚠️ The row is written **before** the microphone opens, not after it closes. That one
        // ordering is the whole of §9's orphan recovery: a process killed mid-lecture leaves a row
        // that says CAPTURING and names a file that is already playable.
        recordings.start(recordingId, noteId, clock.nowEpochMillis())
        live = Live(
            recordingId = recordingId,
            noteId = noteId,
            timeline = TimelineWriter(fs, NoteLayout.recordingTimeline(recordingId)),
        )
        captured.clear()
        // The service first; the microphone when it answers.
        foreground.start()
    }

    private suspend fun openMicrophoneLocked() {
        val running = live ?: return
        val failure = recorder.start(absolutePathOf(NoteLayout.recordingAudio(running.recordingId)), Listener())
        if (failure != null) {
            recordings.discard(running.recordingId, durationMillis = 0, audioBytes = 0, reason = failure)
            live = null
            foreground.stop()
            _state.value = RecordingUiState(message = failure)
            return
        }
        // Audio is flowing: this instant is zero for every offset this recording will carry.
        running.anchorElapsed = elapsedRealtime()
        anchorElapsed = running.anchorElapsed
        running.flusher = scope.launch { flushLoop(running) }
        _state.value = RecordingUiState(
            recordingId = running.recordingId,
            noteId = running.noteId,
            anchorElapsedRealtime = running.anchorElapsed,
        )
    }

    private suspend fun stopLocked(reason: StopReason) {
        val running = live ?: return
        live = null
        anchorElapsed = 0L
        running.flusher?.cancel()

        val audioBytes = recorder.stop()
        drainInto(running)
        // A microphone lost and never regained still owes its gap, or the tail of the lecture
        // silently claims to be audio.
        running.silencedAt?.let { running.timeline.addGap(it, elapsedRealtime() - running.anchorElapsed) }
        running.timeline.flush()

        val duration = elapsedRealtime() - running.anchorElapsed
        if (duration < MINIMUM_DURATION_MILLIS || audioBytes == 0L) {
            // §10 — *"Recordings under 30 seconds are discarded, not uploaded."* Discarded means
            // **not uploaded**: the row is marked and the audio stays on disk. CLAUDE.md rule 1 has
            // no exception for a short recording, and a genuinely important twenty-five seconds is
            // exactly as unrepeatable as an hour.
            recordings.discard(running.recordingId, duration, audioBytes, reason = TOO_SHORT)
            foreground.stop()
            _state.value = RecordingUiState(message = if (reason == StopReason.USER) TOO_SHORT_MESSAGE else null)
            return
        }
        recordings.finish(running.recordingId, duration, audioBytes)
        // ⚠️ The other half of saving. `RecordingIndex.finish` records that a lecture exists;
        // **this** is the only thing that makes it leave the device. M6's lesson twice over —
        // `session.flush()` without `library.onSaved` enqueued no upload, and every structural edit
        // was silently local for a whole session because of it. Finishing a lecture is exactly the
        // kind of thing a user does and then puts the tablet down.
        sync.recordingCaptured(running.noteId, running.recordingId)
        // ⚠️ **Last, not first.** `foreground.stop()` used to run before both writes above, which
        // dropped the process out of foreground importance while the lecture was still only on
        // disk — leaving Android free to kill it in the gap between `recordings.finish` (the
        // recording becomes READY) and `recordingCaptured` (the outbox row appears).
        //
        // That gap produces exactly the state M12 met on the first real device: READY,
        // `uploadedAt = 0`, counted in the Sync screen's header, and **no outbox row** — a lecture
        // the app can see and no button in the app could queue. `SyncEngine.syncNowRequested`
        // repairs it after the fact; this ordering is what stops it happening.
        //
        // The service is worth ~a hundred milliseconds of extra lifetime. The lecture is not
        // repeatable.
        foreground.stop()
        _state.value = RecordingUiState()
    }

    /**
     * §9 — finalise what the process died in the middle of, and offer it.
     *
     * The Ogg on disk is playable up to its last complete page, so the answer is never to discard.
     * The duration cannot be known — nothing recorded when the process stopped — so it is derived
     * from the file's length at the bitrate it was written with, which is right to within a page
     * and is only ever shown to a human.
     */
    private suspend fun recoverLocked() {
        for (orphan in recordings.interrupted()) {
            val path = NoteLayout.recordingAudio(orphan.id)
            val bytes = fs.size(path)
            if (bytes <= 0L) {
                recordings.discard(orphan.id, durationMillis = 0, audioBytes = 0, reason = NOTHING_CAPTURED)
                continue
            }
            // ⚠️ From the **container**, not from bytes ÷ bitrate. Measured on the emulator, the
            // bitrate estimate was 42% high — `MediaRecorder` treats the requested 24 kbit/s as a
            // hint and Opus is VBR, so a 23.03-second recording of 98,369 bytes estimated at 32.8.
            // The last Ogg page's granule is the exact answer and costs one 96 KB tail read.
            // Null means "cannot tell", which shows nothing rather than something wrong.
            val duration = OggOpus.durationMillis(fs, path) ?: 0L
            recordings.finish(orphan.id, durationMillis = duration, audioBytes = bytes)
            // Offered by being *pending*: it appears in §11's list with its size, and the outbox
            // takes it from there. A modal would be CLAUDE.md rule 6's shape, and the answer after
            // a crash mid-lecture is never "throw it away".
            sync.recordingCaptured(orphan.noteId, orphan.id)
        }
    }

    private suspend fun flushLoop(running: Live) {
        while (scope.isActive) {
            delay(FLUSH_INTERVAL_MILLIS)
            drainInto(running)
            running.timeline.flush()
        }
    }

    private fun drainInto(running: Live) {
        while (true) {
            val stroke = captured.poll() ?: break
            running.timeline.add(stroke.objectId, stroke.offsetMillis, stroke.pageId)
        }
    }

    private inner class Listener : AudioRecorder.Listener {
        override fun onSilenced() {
            scope.launch { serial.withLock { live?.silencedAt = elapsedRealtime() - (live?.anchorElapsed ?: 0L) } }
            _state.value = _state.value.copy(silenced = true)
        }

        override fun onResumed() {
            scope.launch {
                serial.withLock {
                    val running = live ?: return@withLock
                    val from = running.silencedAt ?: return@withLock
                    running.silencedAt = null
                    running.timeline.addGap(from, elapsedRealtime() - running.anchorElapsed)
                }
            }
            _state.value = _state.value.copy(silenced = false)
        }

        override fun onFailed(reason: String) {
            scope.launch { serial.withLock { stopLocked(StopReason.FAILED) } }
            _state.value = _state.value.copy(message = reason)
        }
    }

    private enum class StopReason { USER, LEFT_NOTE, FAILED }

    companion object {
        /** §10's floor. In a companion so detekt's `MagicNumber` sees a name, and so a test can read it. */
        const val MINIMUM_DURATION_MILLIS: Long = 30_000L

        /** SPEC §5.1's rhythm, borrowed: the timeline is flushed with the journal, not per stroke. */
        const val FLUSH_INTERVAL_MILLIS: Long = 2_000L

        /**
         * §11 — refuse below this. Roughly two hours of headroom at 24 kbit/s plus the note itself,
         * which is enough that a lecture started now can finish.
         */
        const val MINIMUM_FREE_BYTES: Long = 64L * 1024 * 1024

        const val LOW_SPACE: String = "Not enough free space to record. Free some space and try again."
        const val TOO_SHORT: String = "Shorter than 30 seconds"
        const val TOO_SHORT_MESSAGE: String = "That recording was under 30 seconds, so it was not uploaded."
        const val NOTHING_CAPTURED: String = "The recording was interrupted before any audio was written"
    }
}

/**
 * What the note screen draws (§10: *"a visible elapsed timer, not a tinted icon"*).
 *
 * ⚠️ **Deliberately not a field on `NoteEditorState`.** `NoteEditor.publish()` *reconstructs* that
 * object rather than copying it, so a recording field added there would be reset to its default on
 * every autosave tick, every page change, every undo and every merge — an elapsed timer that blanks
 * itself every two seconds while passing any test that calls `publish` once. This state has a
 * different owner and a different lifetime, and keeping it in its own flow says so.
 *
 * @property anchorElapsedRealtime when the audio started, on the monotonic clock. The screen
 *   derives the elapsed time from it on its own tick, so a running timer costs no state writes.
 */
data class RecordingUiState(
    val recordingId: String? = null,
    val noteId: DocumentId? = null,
    val anchorElapsedRealtime: Long = 0L,
    val silenced: Boolean = false,
    val message: String? = null,
) {
    val isRecording: Boolean get() = recordingId != null

    fun isRecording(note: DocumentId): Boolean = recordingId != null && noteId == note
}
