package dev.inkstone.core.database.recording

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.Recording
import dev.inkstone.core.model.RecordingState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * M12 — the whole `recording` table (docs/M12-AUDIO.md).
 *
 * The sibling of `LibraryIndex`, and here for the same two reasons: the generated API is
 * blocking and speaks in row types, and callers should not be able to run a query on the UI
 * thread or see a `String` where a [DocumentId] belongs.
 *
 * `:core:sync` deliberately does **not** use this. It gets `RecordingUploads`, which is four
 * questions about what a note owes the server — a port that exposed [start] would let the sync
 * layer open a microphone.
 *
 * ⚠️ **Nothing here deletes a row.** CLAUDE.md rule 1 has no exception for metadata, and a
 * recording's row is the only record that a lecture was captured at all; the .opus beside it is
 * the one thing in this app that cannot be re-derived from anything else.
 */
public class RecordingIndex(private val database: InkstoneDatabase, private val dispatcher: CoroutineDispatcher) {

    private val queries = database.recordingQueries

    /**
     * A recording is beginning. Written **before the microphone opens**, not after it closes.
     *
     * That order is §9's orphan recovery in one line: a process killed mid-lecture leaves a row
     * that still says CAPTURING, and the next launch can find it, size the Ogg stream on disk and
     * offer it. Writing the row at *stop* instead would mean a killed lecture left a file nothing
     * knew the name of — the leak `pendingDownload.tempPath` documents, with an hour of audio in
     * it.
     */
    public suspend fun start(recordingId: String, noteId: DocumentId, startedAtEpochMillis: Long): Unit =
        withContext(dispatcher) { queries.insertRecording(recordingId, noteId.value, startedAtEpochMillis) }

    /** Finished, over the floor, and kept. The only transition after which an upload is owed. */
    public suspend fun finish(recordingId: String, durationMillis: Long, audioBytes: Long): Unit =
        withContext(dispatcher) { queries.finishRecording(durationMillis, audioBytes, recordingId) }

    /**
     * Under §10's thirty-second floor, or the recorder produced nothing.
     *
     * The row stays and says why. Without the floor, auto-record plus stop-on-leaving means
     * opening a note for twenty seconds to check one thing costs an upload, a transcription job
     * and a line on the bill — every time, all day.
     */
    public suspend fun discard(recordingId: String, durationMillis: Long, audioBytes: Long, reason: String): Unit =
        withContext(dispatcher) {
            queries.discardRecording(durationMillis, audioBytes, reason, recordingId)
        }

    public suspend fun find(recordingId: String): Recording? =
        withContext(dispatcher) { queries.selectRecording(recordingId).executeAsOneOrNull()?.toRecording() }

    public suspend fun forNote(noteId: DocumentId): List<Recording> = withContext(dispatcher) {
        queries.selectRecordingsForNote(noteId.value).executeAsList().map { it.toRecording() }
    }

    /**
     * §9 — recordings the process died in the middle of.
     *
     * Read once at launch. Each one's audio is playable up to its last complete Ogg page, so the
     * answer is to finalise and offer, never to discard: *"you cannot re-record a lecture"*.
     */
    public suspend fun interrupted(): List<Recording> =
        withContext(dispatcher) { queries.selectInterruptedRecordings().executeAsList().map { it.toRecording() } }

    /** §11 — everything still waiting on the server, newest first, for the pending list. */
    public fun pending(): Flow<List<Recording>> =
        queries.selectPendingRecordings().asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toRecording() } }

    /**
     * §11 — how many are waiting and how much disk they hold, as one row.
     *
     * A `Flow` over the aggregate rather than over [pending] mapped in Kotlin: the badge wants a
     * number and a size, and deriving them from a list the caller does not draw would hold every
     * row in memory behind a subscription that never reads them.
     */
    public fun pendingSummary(): Flow<PendingRecordings> = queries.countPendingRecordings()
        .asFlow()
        .mapToList(dispatcher)
        .map { rows ->
            val row = rows.firstOrNull()
            PendingRecordings(count = row?.pending?.toInt() ?: 0, bytes = row?.bytes ?: 0L)
        }
}

/** §11 — *"pending recordings and their sizes, somewhere reachable"*. */
public data class PendingRecordings(val count: Int, val bytes: Long) {
    public val isEmpty: Boolean get() = count == 0
}

private fun dev.inkstone.core.database.Recording.toRecording() = Recording(
    id = id,
    noteId = DocumentId(noteId),
    // An unrecognised state reads as CAPTURING — see [RecordingState.parse] for why that is the
    // safe direction rather than the lenient one.
    state = RecordingState.parse(state),
    startedAtEpochMillis = startedAt,
    durationMillis = durationMs,
    audioBytes = audioBytes,
    uploadedAtEpochMillis = uploadedAt,
    discardReason = discardReason,
)
