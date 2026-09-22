package dev.inkstone.app.search

/**
 * ⭐ How a long-running pass borrows exclusive access to a `NoteSession` **in pieces**.
 *
 * `NoteSession` is not thread-safe and whoever holds it owns a lock over it — `NoteEditor.serial`
 * for an open note, `NoteSessionHost`'s per-note mutex for a background one. A pass that reads the
 * session, thinks for a long time, and then writes it has two shapes available:
 *
 * - hold the lock for all three, which is one line and blocks every other operation on the note
 *   for the whole of the thinking; or
 * - hold it for the read, release, think, and take it again for the write.
 *
 * ⚠️ **The first shape is what made undo stop responding.** `NoteEditor` wrapped a whole
 * `NoteRecogniser` pass in `serial.withLock`, and SPEC §8.3 fires that pass three seconds after the
 * pen stops — precisely when a user stops drawing and reaches for undo. ML Kit is one recogniser
 * call per line, so a dense page is seconds; every press in that window queued behind the lock and
 * the whole batch landed at once when it finished. Reported as *"at some point the undo does not
 * seem to work anymore and then I press undo a couple of times to see if it just did not register,
 * when everything suddenly gets undone in an instant."*
 *
 * This interface makes the second shape expressible without `NoteRecogniser` knowing which lock it
 * is, or that there is one at all.
 *
 * ⚠️ **Never nest a [locked] inside another.** `NoteEditor.serial` is a `Mutex` and a `Mutex` is
 * never re-entrant: a caller that already holds it and hands out a gate over it deadlocks the
 * editor permanently. The caller's job is to be *outside* its own lock before it calls a pass that
 * takes a gate.
 */
interface SessionGate {

    /** Run [block] with exclusive access to the session, and release before returning. */
    suspend fun <T> locked(block: suspend () -> T): T

    companion object {
        /**
         * For a caller that already owns the session outright and has nobody to stay responsive
         * for — the background batch in `NoteSessionHost`, which opened the session itself.
         *
         * ⚠️ Correct there and **wrong for the editor**: it is the old behaviour by another name,
         * so a pass given this from inside a lock holds that lock for its whole length.
         */
        val Direct: SessionGate = object : SessionGate {
            override suspend fun <T> locked(block: suspend () -> T): T = block()
        }
    }
}
