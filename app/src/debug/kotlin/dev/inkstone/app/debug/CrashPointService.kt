package dev.inkstone.app.debug

import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.IBinder
import android.os.Process
import dev.inkstone.app.platform.AndroidFileSystem
import dev.inkstone.app.platform.SystemClock
import dev.inkstone.core.database.AndroidDatabaseDriverFactory
import dev.inkstone.core.database.createDatabase
import dev.inkstone.core.database.sync.SqlOutboxStore
import dev.inkstone.core.handwriting.HandwritingLayout
import dev.inkstone.core.handwriting.Personaliser
import dev.inkstone.core.handwriting.neural.Fitter
import dev.inkstone.core.handwriting.neural.TrainableNetwork
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.storage.NewPage
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.storage.NoteSession
import dev.inkstone.core.storage.NoteStore
import dev.inkstone.core.sync.OutboxCoalescing
import dev.inkstone.core.sync.OutboxOpType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * SPEC §13.3's kill switch. **Debug builds only** — see `src/debug/AndroidManifest.xml`.
 *
 * ## Why a service in another process
 *
 * *"Instrumented process kill during stroke capture, journal flush, materialization, and
 * upload"* cannot be an ordinary instrumented test: those run inside `dev.inkstone`, so the
 * kill would take the assertions with it. This runs in `dev.inkstone:crashpoint`, builds the
 * pre-crash state with the **production** classes ([AndroidFileSystem], [NoteStore],
 * [NoteSession], [SqlOutboxStore]) against this app's own storage, and then SIGKILLs itself.
 * `CrashSafetyTest` survives and reads what is left on disk.
 *
 * It has to run as the app's uid rather than the test APK's: [AndroidFileSystem] is rooted at
 * `context.filesDir`, and `dev.inkstone.test` has a different one — a component declared in
 * `src/androidTest` would be writing somewhere the app can never see.
 *
 * ## Why `Process.killProcess(myPid())` and not `am force-stop`
 *
 * They are the same signal. This one can be aimed from inside the instant being tested, which
 * is the whole difficulty: the interesting moment is *between two writes*, and no external
 * command is that precise. [killNow] leaves a marker before the kill and another two seconds
 * after it, so the test can check the process really died there.
 *
 * Nothing here touches the user's notes: every scenario works inside
 * `filesDir/crashpoint/<scenario>/` (see [ScratchContext]) and its own database file.
 */
class CrashPointService : Service() {

    private val clock = SystemClock()
    private val ids = IdFactory(clock)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val scenario = intent?.getStringExtra(CrashPoint.EXTRA_SCENARIO) ?: return START_NOT_STICKY
        val noteId = intent.getStringExtra(CrashPoint.EXTRA_NOTE_ID) ?: return START_NOT_STICKY
        // Blocking on purpose: this process exists to reach one instruction and die at it.
        runBlocking { run(scenario, DocumentId(noteId)) }
        return START_NOT_STICKY
    }

    private suspend fun run(scenario: String, noteId: DocumentId) {
        val dir = CrashPoint.scratchDir(this, scenario)
        dir.mkdirs()
        val context = ScratchContext(this, dir)
        when (scenario) {
            CrashPoint.STROKE_CAPTURE -> strokeCapture(context, dir, noteId)

            CrashPoint.JOURNAL_FLUSH -> journalFlush(context, dir, noteId)

            CrashPoint.MATERIALIZE -> materialize(context, dir, noteId)

            CrashPoint.UPLOAD -> upload(context, dir, noteId)

            CrashPoint.PERSONALISE -> personalise(AndroidFileSystem(context), dir)

            CrashPoint.PERSONALISE_WRITE -> personalise(
                StopWrite(AndroidFileSystem(context), HandwritingLayout.checkpoint(PersonaliseCrash.KEY), nth = 2) {
                    killNow(dir)
                },
                dir,
            )

            else -> Unit
        }
    }

    // -----------------------------------------------------------------------
    // The four kill points
    // -----------------------------------------------------------------------

    /**
     * Killed with a finished stroke applied to the session and **not yet flushed**.
     *
     * The documented bound: at most the stroke still in the user's hand. Everything the
     * autosave timer already appended is on disk and has to come back.
     */
    private suspend fun strokeCapture(context: Context, dir: File, noteId: DocumentId) {
        val session = openScratchNote(AndroidFileSystem(context), noteId)
        val page = session.document.pages.first().id
        session.apply(page, listOf(stroke(session, CrashPoint.FLUSHED_STROKE)))
        session.flush()
        session.apply(page, listOf(stroke(session, CrashPoint.UNFLUSHED_STROKE)))
        killNow(dir)
    }

    /**
     * Killed **inside** the journal append, with half a record written.
     *
     * A kill mid-`appendBytes` leaves a trailing partial record, which is the one state
     * `Journal`'s reader has to recognise rather than throw on. [TearAppend] reproduces it
     * exactly: the delegate gets the first half of the bytes, then the process dies.
     */
    private suspend fun journalFlush(context: Context, dir: File, noteId: DocumentId) {
        // The 2nd append, because the 1st is what makes "everything before the torn record
        // survives" a claim with something in it.
        val fs = TearAppend(AndroidFileSystem(context), NoteLayout.journal(noteId), nth = 2) { killNow(dir) }
        val session = openScratchNote(fs, noteId)
        val page = session.document.pages.first().id
        session.apply(page, listOf(stroke(session, CrashPoint.FLUSHED_STROKE)))
        session.flush()
        session.apply(page, listOf(stroke(session, CrashPoint.UNFLUSHED_STROKE)))
        session.flush()
        killNow(dir)
    }

    /**
     * Killed **between** materialization's two writes: the new bundle has landed and the
     * journal has not yet been reset to a bare header (`NoteStore.materialize`, in that order).
     *
     * So at the next open every op is in the bundle *and* still in the journal, which replays
     * on top of it (SPEC §5.1). The documented bound is **nothing lost and nothing doubled** —
     * true only if the fold is keyed on `opId` rather than on arrival.
     */
    private suspend fun materialize(context: Context, dir: File, noteId: DocumentId) {
        // `create` writes the header, so materialize's reset is the 2nd atomic write to it.
        val fs = StopWrite(AndroidFileSystem(context), NoteLayout.journal(noteId), nth = 2) { killNow(dir) }
        val session = openScratchNote(fs, noteId)
        val page = session.document.pages.first().id
        session.apply(
            page,
            listOf(stroke(session, CrashPoint.FLUSHED_STROKE), stroke(session, CrashPoint.UNFLUSHED_STROKE)),
        )
        session.materialize()
        killNow(dir)
    }

    /**
     * Killed while the note is being republished with an UPLOAD already owed.
     *
     * Two things have to survive: the outbox row, with its `attempts` unspent and still due
     * (SPEC §7.6 — rows are markers and outlive the process), and `document.pdf`, which must be
     * the **complete previous** file rather than a torn one (CLAUDE.md rule 10).
     *
     * What dies here is the local half — the row, and the bytes the PUT would send. A body that
     * stops arriving mid-socket is SPEC §13.2 row 12 and lives in the merge matrix.
     */
    private suspend fun upload(context: Context, dir: File, noteId: DocumentId) {
        val real = AndroidFileSystem(context)
        val published = NoteLayout.documentPdf(noteId)
        real.createDirectories(NoteLayout.noteDir(noteId))
        real.writeBytesAtomically(published, CrashPoint.PUBLISHED_PDF.encodeToByteArray())

        // The production factory, over a redirected `getDatabasePath` — so this is the real Android
        // driver and the real schema, in a file beside the scenario rather than the user's library.
        val outbox = SqlOutboxStore(createDatabase(AndroidDatabaseDriverFactory(context)), Dispatchers.IO)
        outbox.mutate(noteId) { pending ->
            OutboxCoalescing.plan(
                pending = pending,
                request = OutboxOpType.UPLOAD,
                noteId = noteId,
                localRev = OWED_REV,
                everUploaded = true,
                now = CrashPoint.ENQUEUED_AT,
            )
        }

        // Killed republishing over the very file the queued row points at.
        val replacement = ByteArray(CrashPoint.PUBLISHED_PDF.length)
        StopWrite(real, published, nth = 1) { killNow(dir) }.writeBytesAtomically(published, replacement)
        killNow(dir)
    }

    /**
     * docs/HANDWRITING-SYNTHESIS.md §8.1 — a personalisation fit (P2, the full backward pass) killed
     * mid-run. [CrashPoint.PERSONALISE] dies **between** checkpoints, as iteration
     * [PersonaliseCrash.KILL_AT_CALL] is about to start; [CrashPoint.PERSONALISE_WRITE] dies **inside**
     * the second checkpoint's write. The documented bound: at most [dev.inkstone.core.handwriting.neural.FitPlan.checkpointEvery]
     * iterations lost, the checkpoint on disk always whole, and a resumed fit ending exactly where an
     * unbroken one does — the test resumes it and checks all three.
     */
    private suspend fun personalise(fs: FileSystem, dir: File) {
        val weights = PersonaliseCrash.weights(this)
        val trainer = TrainableNetwork(weights)
        val words = PersonaliseCrash.words(weights, trainer)
        val fitter = Fitter(trainer, weights.modelId)
        var calls = 0
        fitter.run(
            fitter.start(PersonaliseCrash.PLAN, words, words.take(1)),
            words.associateBy { it.sampleId },
            null,
            outOfTime = {
                if (++calls == PersonaliseCrash.KILL_AT_CALL) killNow(dir)
                false
            },
        ) { checkpoint -> Personaliser.saveCheckpoint(fs, PersonaliseCrash.KEY, checkpoint) }
        killNow(dir)
    }

    // -----------------------------------------------------------------------
    // Machinery
    // -----------------------------------------------------------------------

    private suspend fun openScratchNote(fs: FileSystem, noteId: DocumentId): NoteSession {
        val store = NoteStore(fs, clock, ids)
        val note = store.create(title = "Crash point", deviceId = DEVICE_ID, pages = listOf(BLANK_PAGE), id = noteId)
        return NoteSession.open(store, ids, clock, note, DEVICE_ID)
    }

    /**
     * One stroke, tagged so the test can say which one it is looking at.
     *
     * Stroke geometry is opaque to storage by design (SPEC §5.4), so a tag is a perfectly
     * legitimate stroke down here — nothing in the journal or the bundle decodes it.
     */
    private fun stroke(session: NoteSession, tag: String): PageOp.AddObject = PageOp.AddObject(
        opId = session.nextOpId(),
        clock = session.nextClock(),
        obj = StrokeObject(id = ids.newObjectId(), geometry = StrokeGeometry(tag.encodeToByteArray())),
    )

    private fun killNow(dir: File) {
        File(dir, CrashPoint.ARMED).writeBytes(ByteArray(0))
        // ⚠️ A started service killed mid-`onStartCommand` is *restarted* by the system, with a back-off
        // that multiplies with every crash (1 s, 4 s, 16 s, 64 s…) — and the next scenario's start
        // waits behind it. Six kills in a row (H5 added two) pushed one test past its 60 s wait,
        // looking exactly like a scenario that never reached its kill point. Stopped first, there is
        // nothing to restart. The disk is untouched in between: this process does nothing else now.
        stopSelf()
        Thread.sleep(STOP_SETTLE_MILLIS)
        Process.killProcess(Process.myPid())
        // Only reachable if the kill did not happen. Two seconds is long enough that a signal
        // still in flight cannot produce a false accusation.
        Thread.sleep(SURVIVAL_PROOF_MILLIS)
        File(dir, CrashPoint.SURVIVED).writeBytes(ByteArray(0))
    }

    private companion object {
        const val DEVICE_ID = "crashpoint-device"
        const val SURVIVAL_PROOF_MILLIS = 2_000L

        /** Long enough for the system to take the service off its started list before the kill. */
        const val STOP_SETTLE_MILLIS = 200L
        const val OWED_REV = 7L
        val BLANK_PAGE = NewPage(PageSize.Letter, Background.Blank)
    }
}

/**
 * A [Context] whose `filesDir` **and** database directory are one scenario's scratch directory.
 *
 * So the production [AndroidFileSystem] (rooted at `context.filesDir`) and the production
 * `AndroidDatabaseDriverFactory` (which hard-codes `inkstone.db` and resolves it through
 * `getDatabasePath`) can both be used unchanged, without any of this landing in the user's
 * `notes/` or their library index.
 */
private class ScratchContext(base: Context, private val root: File) : ContextWrapper(base) {
    override fun getFilesDir(): File = root

    override fun getDatabasePath(name: String): File = File(root, name)
}

/**
 * Hands the delegate the first half of the [nth] append to [target], then [kill]s.
 *
 * Everything else is delegated, so the rest of the scenario runs against the real
 * implementation and only the one instant is synthetic.
 */
private class TearAppend(
    private val delegate: FileSystem,
    private val target: String,
    private val nth: Int,
    private val kill: () -> Unit,
) : FileSystem by delegate {
    private var seen = 0

    override suspend fun appendBytes(path: String, bytes: ByteArray) {
        if (path != target) {
            delegate.appendBytes(path, bytes)
            return
        }
        seen++
        if (seen < nth) {
            delegate.appendBytes(path, bytes)
            return
        }
        delegate.appendBytes(path, bytes.copyOf(bytes.size / 2))
        kill()
    }
}

/** Dies on the [nth] atomic write to [target], before any byte of it can land. */
private class StopWrite(
    private val delegate: FileSystem,
    private val target: String,
    private val nth: Int,
    private val kill: () -> Unit,
) : FileSystem by delegate {
    private var seen = 0

    override suspend fun writeBytesAtomically(path: String, bytes: ByteArray) {
        if (path != target) {
            delegate.writeBytesAtomically(path, bytes)
            return
        }
        seen++
        if (seen < nth) {
            delegate.writeBytesAtomically(path, bytes)
            return
        }
        kill()
    }
}
