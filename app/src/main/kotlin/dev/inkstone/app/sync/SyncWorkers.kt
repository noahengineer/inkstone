package dev.inkstone.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.inkstone.app.library.LibraryRepository
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.DocumentMaterializer
import dev.inkstone.core.sync.OutboxDrainer

/**
 * SPEC §2.4 — *"Sync: **WorkManager only**"*, because it must survive process death.
 *
 * ## Why an `EntryPoint` rather than `@HiltWorker`
 *
 * `@HiltWorker` needs `androidx.hilt:hilt-work` and a `Configuration.Provider` on the
 * `Application`. That is a new pinned coordinate for two workers that each need three
 * objects, and SPEC §0.2 makes adding a coordinate a deliberate act rather than a
 * convenience. `EntryPointAccessors.fromApplication` reaches the same singleton graph
 * with no new dependency and no custom `WorkerFactory` to keep in step.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SyncWorkEntryPoint {
    fun drainer(): OutboxDrainer

    fun materializer(): DocumentMaterializer

    fun library(): LibraryRepository
}

/**
 * Drains the outbox (SPEC §7.6). The **only** thing in the app that talks to Nextcloud
 * (CLAUDE.md rule 4).
 */
class OutboxDrainWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, SyncWorkEntryPoint::class.java)
        val report = entryPoint.drainer().drain()

        return when {
            // ⚠️ SPEC §7.2 / CLAUDE.md rule 5. An account halted on a 401 must **not** be
            // retried — WorkManager's backoff would become exactly the credential retry
            // loop that gets real users IP-blocked by their own server. `success` here
            // means "this pass is finished", not "everything uploaded": the account is
            // flagged NEEDS_REAUTH and the UI asks for a login.
            report.haltedAccounts.isNotEmpty() -> Result.success()

            // Something is still owed and running later would help. WorkManager's own
            // backoff decides *when*; the rows' `next_attempt_at` decides whether a given
            // row is actually due, so a too-early wake-up is a cheap no-op rather than a
            // request.
            report.shouldRunAgain -> Result.retry()

            else -> Result.success()
        }
    }

    companion object {
        /** Unique work name — a second enqueue while one is pending replaces it, never queues a duplicate. */
        const val NAME: String = "inkstone.outbox.drain"

        /** The periodic safety net. SPEC §7.6: *"Background: WorkManager periodic ≥15 min."* */
        const val PERIODIC_NAME: String = "inkstone.outbox.periodic"
    }
}

/**
 * SPEC §5.1 — rewrite `document.pdf` off the ink path.
 *
 * Materialization also happens in the editor's `onStop`, which covers the ordinary case.
 * This is for the ones it does not: a note bound to Nextcloud from the library without
 * ever being opened, and "idle while charging".
 */
class MaterializeWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val noteId = inputData.getString(KEY_NOTE_ID)?.let(::DocumentId) ?: return Result.failure()
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, SyncWorkEntryPoint::class.java)

        @Suppress("TooGenericExceptionCaught")
        // Materializing tessellates every stroke and re-serialises a PDF; it can fail for
        // reasons from a missing note to a full disk. Retrying is right for all of them and
        // the note is untouched either way — `document.pdf` is written atomically, so a
        // failed pass leaves the previous published file exactly as it was.
        return try {
            val materialized = entryPoint.materializer().materialize(noteId)
            entryPoint.library().noteRepublished(noteId, materialized.localRev)
            Result.success()
        } catch (failure: Exception) {
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val KEY_NOTE_ID: String = "noteId"
        private const val MAX_ATTEMPTS = 3

        fun nameFor(noteId: DocumentId): String = "inkstone.materialize.${noteId.value}"
    }
}
