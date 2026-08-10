package dev.inkstone.app.search

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.inkstone.app.library.LibraryRepository
import dev.inkstone.app.sync.NoteSessionHost
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.RecognitionUnavailable

/**
 * SPEC §8.3 — *"batched in WorkManager when charging and idle for backlog."*
 *
 * The editor's three-second debounce covers the page in front of the user. This covers
 * everything else: a note written in and closed inside three seconds, a note imported from a
 * PDF, and every page that already existed on the day recognition was first switched on.
 *
 * ## The constraints are the feature
 *
 * Charging **and** idle, and deliberately **no network**. Recognition is entirely on-device, so
 * requiring a network would stop the batch running on a tablet in a bag on a plane — the exact
 * circumstance in which it is most welcome. Only the *model download* needs a connection, and
 * that is a separate, user-initiated act (SPEC §8.1).
 *
 * ## Why it goes through `NoteSessionHost`
 *
 * Recognition writes `text/<pageId>.json` into the bundle, which makes it a bundle writer, which
 * makes it the second writer `NoteSessionHost` exists to exclude. This worker therefore holds
 * the host and no `NoteStore` — M6's gate-5 lesson, which was that a caller given the raw
 * publisher and a KDoc telling it to behave will eventually not.
 */
internal class RecognitionWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, RecognitionEntryPoint::class.java)
        val sessions = entryPoint.sessions()

        val notes = inputData.getString(KEY_NOTE_ID)
            ?.let { listOf(DocumentId(it)) }
            ?: entryPoint.library().allNoteIdsForRecognition()

        for (noteId in notes) {
            val report = sessions.recognise(noteId)
            when (report.unavailable) {
                // ⚠️ `success`, not `retry`, and for the same reason a 401 does not retry
                // (CLAUDE.md rule 5): the backoff would *be* the retry loop, and there is
                // nothing on the other side of it to fix. A missing model is fixed by the user
                // pressing a button, not by asking again in thirty seconds.
                RecognitionUnavailable.MODEL_NOT_DOWNLOADED,
                RecognitionUnavailable.PLAY_SERVICES_MISSING,
                -> return Result.success()

                // Transient. The next scheduled batch tries again; retrying now would burn the
                // charge this job was scheduled to be polite about.
                RecognitionUnavailable.RECOGNISER_FAILED -> return Result.success()

                null -> Unit
            }
        }
        return Result.success()
    }

    internal companion object {
        const val KEY_NOTE_ID: String = "noteId"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface RecognitionEntryPoint {
    /** The single-writer gate. Deliberately the only way in — see the worker's KDoc. */
    fun sessions(): NoteSessionHost

    fun library(): LibraryRepository
}
