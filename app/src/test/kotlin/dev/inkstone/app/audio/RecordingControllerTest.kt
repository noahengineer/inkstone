package dev.inkstone.app.audio

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.inkstone.app.sync.matrix.TempFileSystem
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.database.recording.RecordingIndex
import dev.inkstone.core.database.sync.SqlAccountStore
import dev.inkstone.core.database.sync.SqlBindingStore
import dev.inkstone.core.database.sync.SqlOutboxStore
import dev.inkstone.core.database.sync.SqlRecordingUploads
import dev.inkstone.core.database.sync.SqlSyncNotes
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.RecordingState
import dev.inkstone.core.model.WorkScheduler
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.storage.RecordingTimeline
import dev.inkstone.core.storage.TimelineEntry
import dev.inkstone.core.sync.SyncEngine
import dev.inkstone.core.sync.SyncStores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⭐ M12's capture **policy**, on the JVM (docs/M12-AUDIO.md §§9–10).
 *
 * ## What this can and cannot say
 *
 * `RecordingController` exists as a plain class taking its collaborators precisely so this test can
 * exist — the same move `NoteEditor` made when it stopped being the `ViewModel`. Everything below
 * runs the **real** `RecordingIndex`, the **real** `SqlOutboxStore` over the real schema, and the
 * real `SyncEngine`, with a fake microphone in front.
 *
 * ⚠️ It cannot say anything about the microphone, about Ogg surviving a kill, or about whether
 * pressing the button reaches any of this. Those are `adb`, an emulator and the tablet — CLAUDE.md
 * definition-of-done #6, and the reason this file's green tick is not the milestone's evidence.
 */
class RecordingControllerTest {

    private val note = DocumentId("019fecef-0000-7000-8000-00000000note")
    private val page = PageId("019fecef-0000-7000-8000-00000000pag1")

    /** A microphone that writes whatever it is told to, so the policy is what is under test. */
    private class FakeRecorder(private val bytesPerStop: Long = 4_096L) : AudioRecorder {
        var started = 0
        var listener: AudioRecorder.Listener? = null
        var failWith: String? = null
        var lastPath: String? = null

        override suspend fun start(path: String, listener: AudioRecorder.Listener): String? {
            failWith?.let { return it }
            started++
            lastPath = path
            this.listener = listener
            java.io.File(path).also { it.parentFile?.mkdirs() }.writeBytes(ByteArray(bytesPerStop.toInt()))
            return null
        }

        override suspend fun stop(): Long {
            listener = null
            return lastPath?.let { java.io.File(it).length() } ?: 0L
        }
    }

    /** The service, reduced to the two facts the controller depends on. */
    private class FakeForeground(val controller: () -> RecordingController) : RecordingController.ForegroundHost {
        var running = false

        override fun start() {
            running = true
            // The real service calls this from `onStartCommand` after `startForeground`.
            controller().onForegroundReady()
        }

        override fun stop() {
            running = false
        }
    }

    /**
     * ⚠️ Two things about driving this class from `runTest`, and both are properties of the
     * production design rather than test scaffolding.
     *
     * **The controller is given `backgroundScope`, not the test scope.** Its flush loop runs
     * `while (isActive)` forever — correct in production, where the scope outlives everything —
     * and a `runTest` body waits for its children, so handing it the test scope hangs the test for
     * a minute and then fails it. `backgroundScope` is cancelled when the test body finishes,
     * which models the process ending.
     *
     * **And `runCurrent`, never `advanceUntilIdle`.** The same forever-loop means advancing virtual
     * time never reaches "idle": there is always another delayed flush scheduled. `runCurrent`
     * drains what is runnable *now*, which is every step of start and stop — neither of which
     * delays. The timeline is still asserted honestly, because `stopLocked` drains and flushes
     * itself rather than relying on the loop having ticked.
     */
    private class Fixture(val scope: TestScope) : AutoCloseable {
        val root = Files.createTempDirectory("inkstone-recording-")
        val fs = TempFileSystem(root)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = InkstoneDatabase(driver).also { InkstoneDatabase.Schema.create(driver) }
        val recordings = RecordingIndex(database, Dispatchers.Unconfined)
        val clock = Clock { 1_700_000_000_000L }
        val ids = IdFactory(clock)
        var elapsed = 10_000L
        val recorder = FakeRecorder()
        var autoRecord = false

        val engine = SyncEngine(
            SyncStores(
                SqlAccountStore(database, Dispatchers.Unconfined),
                SqlBindingStore(database, Dispatchers.Unconfined),
                SqlOutboxStore(database, Dispatchers.Unconfined),
                SqlSyncNotes(database, Dispatchers.Unconfined),
                SqlRecordingUploads(database, Dispatchers.Unconfined),
            ),
            clock,
            object : WorkScheduler {
                override fun enqueueOutboxDrain() = Unit

                override fun enqueueMaterialization(noteId: DocumentId) = Unit
            },
        )

        lateinit var controller: RecordingController
        val foreground = FakeForeground { controller }

        init {
            controller = RecordingController(
                recorder = recorder,
                recordings = recordings,
                sync = engine,
                fs = fs,
                ids = ids,
                clock = clock,
                scope = scope.backgroundScope,
                foreground = foreground,
                absolutePathOf = { path -> root.resolve(path).toString() },
                freeBytes = { Long.MAX_VALUE },
                autoRecordFor = { autoRecord },
                elapsedRealtime = { elapsed },
            )
        }

        /**
         * ⚠️ Absent reads as empty, which is what `OutboxDrainer.performMediaUpload` does too.
         *
         * A lecture with no strokes in it is ordinary — you listened without writing — and
         * `TimelineWriter.flush` is a no-op with nothing buffered, so there is genuinely no file.
         * A helper that threw here would make the fixture stricter than production and turn the
         * commonest recording into a test failure.
         */
        suspend fun timelineOf(recordingId: String): List<TimelineEntry> {
            val path = NoteLayout.recordingTimeline(recordingId)
            if (!fs.exists(path)) return emptyList()
            return RecordingTimeline.read(fs.readBytes(path).decodeToString())
        }

        override fun close() {
            driver.close()
        }
    }

    private fun fixture(body: suspend TestScope.(Fixture) -> Unit) = runTest(StandardTestDispatcher()) {
        Fixture(this).use { body(it) }
    }

    /**
     * ⚠️ §10's floor. Without it, auto-record plus stop-on-leaving means opening a note for twenty
     * seconds to check one thing costs an upload, a transcription job and a line on the bill —
     * every time, all day.
     *
     * And **the audio is still on disk**. "Discarded" means not uploaded: CLAUDE.md rule 1 has no
     * exception for a short recording, and a genuinely important twenty-five seconds is exactly as
     * unrepeatable as an hour.
     */
    @Test
    fun `a recording under thirty seconds is not uploaded and its audio is kept`() = fixture { f ->
        f.controller.toggle(note)
        runCurrent()
        val recordingId = assertNotNull(f.recordings.forNote(note).firstOrNull()).id

        f.elapsed += 20_000L
        f.controller.toggle(note)
        runCurrent()

        val row = assertNotNull(f.recordings.find(recordingId))
        assertEquals(RecordingState.DISCARDED, row.state)
        assertEquals(0, f.database.syncQueries.countOutbox().executeAsOne().toInt(), "a short recording was queued")
        assertTrue(f.fs.exists(NoteLayout.recordingAudio(recordingId)), "the audio was deleted — CLAUDE.md rule 1")
    }

    @Test
    fun `a real recording is finished and enqueued`() = fixture { f ->
        f.controller.toggle(note)
        runCurrent()
        val recordingId = assertNotNull(f.recordings.forNote(note).firstOrNull()).id

        f.elapsed += 45L * 60 * 1000
        f.controller.toggle(note)
        runCurrent()

        val row = assertNotNull(f.recordings.find(recordingId))
        assertEquals(RecordingState.READY, row.state)
        assertTrue(row.awaitingUpload)
        // ⚠️ `RecordingIndex.finish` records that a lecture exists; `SyncEngine.recordingCaptured`
        // is the only thing that makes it leave the device. M6's lesson twice over — a flush
        // without the report enqueued no upload, silently, for a whole session. A note with no
        // binding owes nothing yet, which is why this asserts the state and not a row.
        assertEquals(0L, row.uploadedAtEpochMillis)
    }

    /**
     * ⭐ §9 — *"stops when you leave the note"*, and only for the note you left.
     *
     * The signal is `NoteEditorViewModel.onCleared`; this is the controller half of it. The second
     * half of the assertion is the one that would otherwise be found by a user: a *different*
     * note's teardown must not stop a recording that is still running.
     */
    @Test
    fun `leaving the note stops it and leaving a different note does not`() = fixture { f ->
        f.controller.toggle(note)
        runCurrent()
        f.elapsed += 60_000L

        f.controller.noteLeft(DocumentId("019fecef-0000-7000-8000-0000000other"))
        runCurrent()
        assertTrue(f.controller.state.value.isRecording, "another note's teardown stopped this recording")

        f.controller.noteLeft(note)
        runCurrent()
        assertTrue(!f.controller.state.value.isRecording, "leaving the note did not stop the recording")
        assertTrue(!f.foreground.running, "the foreground service was left running with no recording")
    }

    /**
     * ⚠️ §9 — *"Losing the microphone is not the same as stopping."* A phone call takes the mic and
     * the recorder writes **silence**; the gap is what lets the server say "nothing was captured
     * here" instead of aligning the rest of the lecture to the wrong words.
     */
    @Test
    fun `a microphone taken away leaves a marked gap`() = fixture { f ->
        f.controller.toggle(note)
        runCurrent()
        val recordingId = assertNotNull(f.recordings.forNote(note).firstOrNull()).id

        f.elapsed += 60_000L
        f.recorder.listener?.onSilenced()
        runCurrent()
        f.elapsed += 135_000L
        f.recorder.listener?.onResumed()
        runCurrent()

        f.elapsed += 45L * 60 * 1000
        f.controller.toggle(note)
        runCurrent()

        val gap = f.timelineOf(recordingId).filterIsInstance<TimelineEntry.Gap>().single()
        assertEquals(60_000L, gap.fromMillis)
        assertEquals(195_000L, gap.toMillis)
    }

    /** Strokes land in the timeline at their offset from the **audio's** start, not the press. */
    @Test
    fun `strokes are written at their offset from the start of the audio`() = fixture { f ->
        f.controller.toggle(note)
        runCurrent()
        val recordingId = assertNotNull(f.recordings.forNote(note).firstOrNull()).id
        val anchor = f.controller.state.value.anchorElapsedRealtime

        val first = ObjectId("019fecef-0000-7000-8000-00000000aaa1")
        f.controller.strokeCaptured(first, anchor + 12_000L, page)

        f.elapsed += 45L * 60 * 1000
        f.controller.toggle(note)
        runCurrent()

        // ⭐ The page travels with the offset — §15.6's alignment unit. Without it the server
        // would have to fold the note's op log to learn which page a stroke landed on.
        assertEquals(
            listOf(TimelineEntry.Stroke(first, 12_000L, page)),
            f.timelineOf(recordingId).filterIsInstance<TimelineEntry.Stroke>(),
        )
    }

    /** A stroke drawn when nothing is recording must not be attributed to the next recording. */
    @Test
    fun `a stroke drawn while not recording is dropped`() = fixture { f ->
        f.controller.strokeCaptured(ObjectId("019fecef-0000-7000-8000-00000000bbb1"), 5_000L, page)

        f.controller.toggle(note)
        runCurrent()
        val recordingId = assertNotNull(f.recordings.forNote(note).firstOrNull()).id
        f.elapsed += 45L * 60 * 1000
        f.controller.toggle(note)
        runCurrent()

        assertTrue(f.timelineOf(recordingId).isEmpty(), "a stroke from before the recording was attributed to it")
    }

    /**
     * §9's orphan recovery. The row says CAPTURING because the process died with the microphone
     * open; the Ogg on disk is playable up to its last complete page, so it is finalised and
     * offered — never discarded.
     */
    @Test
    fun `an interrupted recording is finalised on the next launch`() = fixture { f ->
        val orphan = f.ids.newRecordingId()
        f.recordings.start(orphan, note, f.clock.nowEpochMillis())
        // A real Ogg tail, because the duration is read from the **container** now. The estimate
        // this replaced — bytes divided by the requested bitrate — measured 42% high on a real
        // emulator recording, because `MediaRecorder` treats that bitrate as a hint.
        f.fs.writeBytesAtomically(NoteLayout.recordingAudio(orphan), oggPage(1_105_920L))

        f.controller.recoverInterrupted()
        runCurrent()

        val row = assertNotNull(f.recordings.find(orphan))
        assertEquals(RecordingState.READY, row.state, "an interrupted lecture was discarded")
        assertEquals(23_040L, row.durationMillis, "the duration did not come from the container")
    }

    /**
     * ⚠️ Bytes this build cannot parse are still the user's audio.
     *
     * A CAPTURING row whose file is not readable Opus is finalised anyway, with no duration —
     * *"cannot tell"* rather than a guess. Discarding a lecture because a header would not parse
     * is the one outcome CLAUDE.md rule 1 has no room for, and the server may well make more of
     * the bytes than this does.
     */
    @Test
    fun `an interrupted recording that will not parse is kept with no duration`() = fixture { f ->
        val orphan = f.ids.newRecordingId()
        f.recordings.start(orphan, note, f.clock.nowEpochMillis())
        f.fs.writeBytesAtomically(NoteLayout.recordingAudio(orphan), ByteArray(4_096) { 9 })

        f.controller.recoverInterrupted()
        runCurrent()

        val row = assertNotNull(f.recordings.find(orphan))
        assertEquals(RecordingState.READY, row.state, "unparseable audio was thrown away")
        assertEquals(4_096L, row.audioBytes)
        assertEquals(0L, row.durationMillis)
    }

    /** A minimal Ogg page carrying [granule]. Only the header is read by `OggOpus`. */
    private fun oggPage(granule: Long): ByteArray {
        val header = ByteArray(27)
        "OggS".encodeToByteArray().copyInto(header)
        for (i in 0 until 8) header[6 + i] = ((granule shr (8 * i)) and 0xFF).toByte()
        header[26] = 1
        return header + byteArrayOf(4) + ByteArray(4)
    }

    /** An interrupted recording that never got a byte written is not a lecture. */
    @Test
    fun `an interrupted recording with no audio is discarded`() = fixture { f ->
        val orphan = f.ids.newRecordingId()
        f.recordings.start(orphan, note, f.clock.nowEpochMillis())

        f.controller.recoverInterrupted()
        runCurrent()

        assertEquals(RecordingState.DISCARDED, assertNotNull(f.recordings.find(orphan)).state)
    }

    /**
     * §11 — *"Refuse to start when free space is low. Discovering at minute 50 that writes have
     * been failing is not a recoverable outcome."*
     */
    @Test
    fun `recording refuses to start when free space is low`() = runTest(StandardTestDispatcher()) {
        Fixture(this).use { f ->
            val tight = RecordingController(
                recorder = f.recorder,
                recordings = f.recordings,
                sync = f.engine,
                fs = f.fs,
                ids = f.ids,
                clock = f.clock,
                scope = backgroundScope,
                foreground = f.foreground,
                absolutePathOf = { path -> f.root.resolve(path).toString() },
                freeBytes = { RecordingController.MINIMUM_FREE_BYTES - 1 },
                autoRecordFor = { false },
                elapsedRealtime = { f.elapsed },
            )

            tight.toggle(note)
            runCurrent()

            assertNull(f.recordings.forNote(note).firstOrNull(), "a recording was started with no room to write it")
            assertEquals(RecordingController.LOW_SPACE, tight.state.value.message)
            assertEquals(0, f.recorder.started, "the microphone was opened anyway")
        }
    }

    /** §10 — auto-record is per folder, and it is what opening the note consults. */
    @Test
    fun `opening a note in an auto-record folder starts recording`() = fixture { f ->
        f.autoRecord = true

        f.controller.noteOpened(note)
        runCurrent()

        assertTrue(f.controller.state.value.isRecording(note))
    }

    /**
     * ⚠️ `NoteActivity` is `launchMode="standard"`, so opening note B from inside note A stacks a
     * second editor and note A's `onCleared` never fires. Without this its microphone would run
     * until the process died.
     */
    @Test
    fun `opening a different note stops the recording already running`() = fixture { f ->
        f.controller.toggle(note)
        runCurrent()
        f.elapsed += 60L * 60 * 1000

        val other = DocumentId("019fecef-0000-7000-8000-0000000other")
        f.controller.noteOpened(other)
        runCurrent()

        assertTrue(!f.controller.state.value.isRecording, "two notes were recording at once")
        assertEquals(RecordingState.READY, assertNotNull(f.recordings.forNote(note).firstOrNull()).state)
    }
}
