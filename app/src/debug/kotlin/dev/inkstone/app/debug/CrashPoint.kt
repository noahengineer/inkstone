package dev.inkstone.app.debug

import android.content.Context
import java.io.File

/**
 * The contract between the process that dies and the test that reads the wreckage.
 *
 * SPEC §13.3. In `src/debug` rather than `src/androidTest` because both sides need it and only
 * this side can be seen from both — see [CrashPointService] for why the victim cannot live in
 * the test APK.
 */
object CrashPoint {
    const val EXTRA_SCENARIO: String = "dev.inkstone.crashpoint.scenario"
    const val EXTRA_NOTE_ID: String = "dev.inkstone.crashpoint.noteId"

    const val STROKE_CAPTURE: String = "STROKE_CAPTURE"
    const val JOURNAL_FLUSH: String = "JOURNAL_FLUSH"
    const val MATERIALIZE: String = "MATERIALIZE"
    const val UPLOAD: String = "UPLOAD"

    /** docs/HANDWRITING-SYNTHESIS.md §8.1: a personalisation fit killed between checkpoints. */
    const val PERSONALISE: String = "PERSONALISE"

    /** The same fit killed inside a checkpoint's atomic write. */
    const val PERSONALISE_WRITE: String = "PERSONALISE_WRITE"

    /** Written immediately before the kill, so the test knows the moment was reached. */
    const val ARMED: String = "armed"

    /**
     * Written two seconds *after* the kill.
     *
     * Its existence means the process outlived its own SIGKILL, which would make every
     * assertion in `CrashSafetyTest` a statement about an orderly shutdown instead of a crash.
     * Asserting it is absent is what makes "the process died here" checked rather than assumed.
     */
    const val SURVIVED: String = "survived"

    /** The stroke the autosave timer had already appended. Must always come back. */
    const val FLUSHED_STROKE: String = "flushed-stroke"

    /** The stroke still in the user's hand. The only thing §13.3 permits losing. */
    const val UNFLUSHED_STROKE: String = "unflushed-stroke"

    /** Not a real PDF. Nothing here parses it; what matters is that it stays intact. */
    const val PUBLISHED_PDF: String = "%PDF-1.7 crashpoint published bytes"

    /** A fixed `created_at`, so the test can tell a surviving row from a rewritten one. */
    const val ENQUEUED_AT: Long = 1_700_000_000_000L

    private const val ROOT = "crashpoint"

    /**
     * Where one scenario keeps its note **and its database**.
     *
     * A directory per scenario under `filesDir/crashpoint/`, so nothing lands in the user's
     * `notes/` or their `inkstone.db`, and two scenarios cannot see each other's wreckage.
     * Deleting this directory is the whole of a scenario's cleanup.
     */
    fun scratchDir(context: Context, scenario: String): File = File(File(context.filesDir, ROOT), scenario)
}
