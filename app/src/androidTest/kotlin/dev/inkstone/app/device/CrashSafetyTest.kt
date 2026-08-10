package dev.inkstone.app.device

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.inkstone.app.debug.CrashPoint
import dev.inkstone.app.debug.CrashPointService
import dev.inkstone.app.platform.AndroidFileSystem
import dev.inkstone.app.platform.SystemClock
import dev.inkstone.core.database.AndroidDatabaseDriverFactory
import dev.inkstone.core.database.createDatabase
import dev.inkstone.core.database.sync.SqlOutboxStore
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.storage.NoteSession
import dev.inkstone.core.storage.NoteStore
import dev.inkstone.core.storage.OpenNote
import dev.inkstone.core.sync.OutboxOpType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SPEC §13.3 — **crash safety, with a real process kill.**
 *
 * > Instrumented process kill during stroke capture, journal flush, materialization, and upload.
 *
 * Never written before M6. Each test starts [CrashPointService] in `dev.inkstone:crashpoint`,
 * which builds one scenario's state with the production storage classes and then SIGKILLs itself
 * at a named instant; this process survives and reads what is on disk afterwards.
 *
 * Every test asserts a **bound**, not merely "it still opens":
 *
 * | Kill point | Permitted loss |
 * |---|---|
 * | stroke capture | at most the stroke not yet flushed |
 * | journal flush | at most the record that was mid-write |
 * | materialization | nothing — and nothing duplicated either |
 * | upload | nothing; the queued row and the published bytes both survive whole |
 *
 * Test names stay inside `[A-Za-z0-9 _-]`: D8 rejects a comma or an apostrophe in a dex method
 * name and the JVM compiler does not, so the cost of forgetting is a suite that compiles and
 * cannot run (M5 lost twelve names to this).
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class CrashSafetyTest {

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val clock = SystemClock()
    private val ids = IdFactory(clock)

    @Test
    fun `a kill during stroke capture loses at most the stroke that was not flushed`() {
        val (noteId, scratch) = crash(CrashPoint.STROKE_CAPTURE)
        val note = openOrFail(scratch, noteId)

        assertFalse(note.journalWasTruncated, "nothing was mid-write, so the journal is whole")
        assertEquals(
            listOf(CrashPoint.FLUSHED_STROKE),
            strokeTags(scratch, note),
            "the flushed stroke must come back and the unflushed one is the documented loss",
        )
    }

    @Test
    fun `a kill inside the journal append keeps every record before the torn one`() {
        val (noteId, scratch) = crash(CrashPoint.JOURNAL_FLUSH)
        val note = openOrFail(scratch, noteId)

        // Surfaced rather than swallowed: `NoteSession.recoveredFromTornJournal` reads this and
        // the editor shows it. A reader that threw here would turn a crash into an unopenable note.
        assertTrue(note.journalWasTruncated, "half a record was written and the reader must say so")
        assertEquals(
            listOf(CrashPoint.FLUSHED_STROKE),
            strokeTags(scratch, note),
            "the complete record survives the torn one that followed it",
        )
    }

    @Test
    fun `a kill between the bundle write and the journal reset loses nothing and doubles nothing`() {
        val (noteId, scratch) = crash(CrashPoint.MATERIALIZE)
        val note = openOrFail(scratch, noteId)

        // The dangerous state, and the reason this scenario exists: `NoteStore.materialize` writes
        // the bundle and *then* truncates the journal, so a kill in between leaves every op in both
        // places. The journal replays on top of the bundle at the next open (SPEC §5.1), and only a
        // fold keyed on `opId` survives that without duplicating the page.
        assertFalse(note.journalWasTruncated, "the journal was never mid-write here")
        val tags = strokeTags(scratch, note)
        assertEquals(2, tags.size, "both strokes, and neither of them twice")
        assertEquals(setOf(CrashPoint.FLUSHED_STROKE, CrashPoint.UNFLUSHED_STROKE), tags.toSet())
    }

    @Test
    fun `a kill during upload keeps the queued row and the published bytes whole`() {
        val (noteId, scratch) = crash(CrashPoint.UPLOAD)

        val database = createDatabase(AndroidDatabaseDriverFactory(RootedAt(app, scratch)))
        val outbox = SqlOutboxStore(database, Dispatchers.IO)
        val rows = runBlocking { outbox.all() }

        assertEquals(1, rows.size, "an UPLOAD was owed when the process died and is still owed")
        val row = rows.single()
        assertEquals(OutboxOpType.UPLOAD, row.opType)
        assertEquals(noteId, row.noteId)
        // SPEC §7.6 — a crash is not an attempt. Spending one here would shorten the backoff ladder
        // every time a process died, which is how a device ends up hammering a server it never reached.
        assertEquals(0, row.attempts, "a process death is not a failed attempt")
        assertEquals(CrashPoint.ENQUEUED_AT, row.createdAt, "the row was not rewritten")
        assertTrue(
            runBlocking { outbox.due(clock.nowEpochMillis(), limit = DUE_LIMIT) }.any { it.noteId == noteId },
            "and it drains on the next launch rather than sitting there",
        )

        // CLAUDE.md rule 10. The kill landed inside the atomic replacement of this exact file; what
        // must never be readable afterwards is a half-written one.
        val published = File(scratch, NoteLayout.documentPdf(noteId))
        assertTrue(published.isFile, "the previously published file is still there")
        assertEquals(
            CrashPoint.PUBLISHED_PDF,
            published.readBytes().decodeToString(),
            "the complete previous bytes, never a torn file",
        )
    }

    // -----------------------------------------------------------------------
    // Driving the victim
    // -----------------------------------------------------------------------

    /**
     * Run one scenario in the other process and wait for it to die at its kill point.
     *
     * @return the note it was told to build, and the directory it built it in.
     */
    private fun crash(scenario: String): Pair<DocumentId, File> {
        val scratch = CrashPoint.scratchDir(app, scenario)
        // The scenario's note, its journal and its database all live in here, so one delete is
        // the whole of the cleanup — and a leftover from a previous run cannot be mistaken for a
        // survivor of this one.
        scratch.deleteRecursively()

        val noteId = ids.newDocumentId()
        app.startService(
            Intent(app, CrashPointService::class.java)
                .putExtra(CrashPoint.EXTRA_SCENARIO, scenario)
                .putExtra(CrashPoint.EXTRA_NOTE_ID, noteId.value),
        )

        val armed = File(scratch, CrashPoint.ARMED)
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MILLIS
        while (!armed.exists() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MILLIS)
        assertTrue(armed.exists(), "$scenario never reached its kill point")

        // Long enough for the SIGKILL to land, and past the moment the victim would have written
        // the marker that says it did not.
        Thread.sleep(SETTLE_MILLIS)
        assertFalse(
            File(scratch, CrashPoint.SURVIVED).exists(),
            "$scenario outlived its own kill — every assertion here would be about a clean shutdown",
        )
        return noteId to scratch
    }

    private fun openOrFail(scratch: File, noteId: DocumentId): OpenNote {
        val note = runBlocking { storeIn(scratch).open(noteId) }
        return assertNotNull(note, "the note must still open after the crash")
    }

    /** Fold page one from the files as they were left, and name the strokes on it. */
    private fun strokeTags(scratch: File, note: OpenNote): List<String> = runBlocking {
        val session = NoteSession.open(storeIn(scratch), ids, clock, note, DEVICE_ID)
        session.page(note.document.pages.first().id)
            .objects
            .filterIsInstance<StrokeObject>()
            .map { it.geometry.bytes.decodeToString() }
    }

    /**
     * A store over the victim's scratch directory.
     *
     * The production [AndroidFileSystem] is rooted at `context.filesDir`, so pointing it at
     * another directory is a matter of lying about that one method — which is what the victim did
     * too, so both sides resolve the same `NoteLayout` paths.
     */
    private fun storeIn(scratch: File): NoteStore = NoteStore(AndroidFileSystem(RootedAt(app, scratch)), clock, ids)

    private class RootedAt(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getFilesDir(): File = root

        override fun getDatabasePath(name: String): File = File(root, name)
    }

    private companion object {
        const val DEVICE_ID = "crash-safety-reader"
        const val START_TIMEOUT_MILLIS = 60_000L
        const val POLL_MILLIS = 100L
        const val DUE_LIMIT = 10

        /** Two seconds is when the victim would write its survival marker; wait past it. */
        const val SETTLE_MILLIS = 3_000L
    }
}
