package dev.inkstone.app.handwriting

import dev.inkstone.core.handwriting.PenLedgerWriter
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.ObjectId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ⭐ docs/HANDWRITING-SYNTHESIS.md D8 — writes down which strokes **this device's pen** drew, so
 * handwriting synthesis learns the user's hand and nobody else's.
 *
 * Fed from `InkSurfaceView.onStrokeCaptured`, which fires for a stroke the pen just drew and for
 * nothing else; see `PenLedger` for why that is the one trustworthy signal.
 *
 * ## The shape is `RecordingController`'s, for its reasons
 *
 * [strokeCaptured] runs on the UI thread in the frame a stroke lands (CLAUDE.md rule 7): one enqueue,
 * one compare-and-set, return. The file append runs in this object's own scope, two seconds later,
 * and takes **no lock the editor holds** — a ledger write that could wait on `NoteEditor.serial`
 * would queue behind every whole-bundle materialize.
 *
 * A process-wide singleton rather than part of the editor: a stroke drawn a second before Back
 * still has to reach the file after the editor's scope is gone.
 *
 * ⚠️ **Losing a line loses learning, never ink.** A failed append is swallowed: the stroke is in the
 * note regardless, and at worst it is never learned from (or is, later, through the older-notes
 * opt-in).
 */
@Singleton
class PenLedgerRecorder @Inject constructor(fs: FileSystem) {

    private class Captured(val noteId: DocumentId, val objectId: ObjectId, val downElapsedMs: Long, val ruled: Boolean)

    private val queue = ConcurrentLinkedQueue<Captured>()
    private val scheduled = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serial = Mutex()
    private val writer = PenLedgerWriter(fs)

    /** A stroke was just drawn. **UI thread; must stay cheap.** */
    fun strokeCaptured(noteId: DocumentId, objectId: ObjectId, downElapsedMs: Long, ruled: Boolean) {
        queue += Captured(noteId, objectId, downElapsedMs, ruled)
        if (scheduled.compareAndSet(false, true)) {
            scope.launch {
                delay(FLUSH_INTERVAL_MILLIS)
                flush()
            }
        }
    }

    /** Write everything captured so far. Called by the scheduled flush, and before a harvest pass. */
    suspend fun flush() {
        serial.withLock {
            // Cleared before draining, so a stroke captured during the write schedules another.
            scheduled.set(false)
            while (true) {
                val next = queue.poll() ?: break
                writer.add(next.noteId, next.objectId, next.downElapsedMs, next.ruled)
            }
            runCatching { writer.flush() }
        }
    }

    private companion object {
        /** SPEC §5.1's rhythm, borrowed as the recording timeline borrows it. */
        const val FLUSH_INTERVAL_MILLIS = 2_000L
    }
}
