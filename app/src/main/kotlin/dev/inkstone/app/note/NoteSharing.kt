package dev.inkstone.app.note

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.Recording
import dev.inkstone.core.model.RecordingState
import dev.inkstone.core.model.RemoteName
import dev.inkstone.core.storage.NoteLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gets the published PDF out of the app — SPEC §5.2's *"opens in any PDF viewer, in
 * Nextcloud's web viewer, on an iPad, emailed to anyone"*.
 *
 * Until this existed that sentence was aspirational: notes live in the app's private
 * storage, and nothing in the app could hand one to another app. ROADMAP M4's first
 * acceptance criterion — *"annotate 10 pages, export → correct in Acrobat, Chrome and
 * macOS Preview"* — had no export in it to exercise.
 *
 * ## Why it copies rather than sharing `document.pdf` where it lies
 *
 * Two reasons, and the first is a data-integrity one:
 *
 * 1. **Materialization rewrites that file in place** (SPEC §5.1), and a receiving app may
 *    read a content Uri minutes later, in its own process. Handing out a Uri to the live
 *    file invites exactly the torn read CLAUDE.md rule 10 exists to prevent — a half-written
 *    PDF, in someone else's mail client, with no way to tell.
 * 2. **The name.** `FileProvider` serves the file's own filename, so every note in the world
 *    would arrive called `document.pdf`.
 *
 * The copy is a snapshot: once written, nothing in the app touches it again.
 *
 * ## The cache is swept on every export
 *
 * Shared copies are duplicates of data that is already safe on disk, so they are pure cost —
 * and a 20 MB lecture PDF left behind per export adds up on a tablet. Sweeping before each
 * write also means at most one stale copy exists at a time. It cannot orphan anything the
 * user needs: the note itself is untouched, and this directory holds nothing else.
 *
 * A grant already handed to another app survives the sweep for as long as that app holds it,
 * because the file is only unlinked once the last reader closes it.
 */
@Singleton
class NoteSharing @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * Snapshot [id]'s published PDF and return a Uri another app may read.
     *
     * **Call after materializing.** This shares whatever is on disk; SPEC §5.1 allows that
     * to be one editing session stale, and exporting a note without its last page of ink is
     * the kind of wrong that looks like a data-loss bug. [NoteEditorViewModel.exportPdf]
     * sequences the two.
     *
     * @return null when the note has never been materialized — a note created and exported
     *   without the PDF writer wired up, which is a real state (SPEC §5.1: the bundle is the
     *   truth and the PDF is the published form) and not a crash.
     */
    suspend fun snapshot(id: DocumentId, title: String): Uri? = withContext(Dispatchers.IO) {
        val published = File(context.filesDir, NoteLayout.documentPdf(id))
        if (!published.isFile || published.length() == 0L) return@withContext null

        val outbox = File(context.cacheDir, SHARE_DIR)
        outbox.deleteRecursively()
        if (!outbox.mkdirs()) return@withContext null

        // The same sanitiser sync uses for remote paths (SPEC §7.8). Its rules are a
        // superset of what a filename needs here — it also strips the Windows set and the
        // DOS reserved basenames — so a note exported to a phone and dropped on a desktop
        // keeps the name it was given.
        val copy = File(outbox, "${RemoteName.sanitise(title)}.pdf")
        published.copyTo(copy, overwrite = true)

        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copy)
    }

    /**
     * The chooser for a Uri from [snapshot].
     *
     * `FLAG_GRANT_READ_URI_PERMISSION` is what makes the Uri readable at all — without it
     * every receiving app gets a `SecurityException` it will report as a corrupt file.
     */
    fun chooserFor(uri: Uri, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = PDF_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /**
     * ⭐ M12 — get a lecture **out of the app** (docs/M12-AUDIO.md §15.5).
     *
     * ## Why this exists, and why it is a rule 1 concern rather than a convenience
     *
     * Found the first time a real lecture was recorded on the tablet: a recording lives in
     * app-private storage, so MTP cannot see it, and `adb` needs USB debugging — which a
     * managed device may simply not offer. Until the note is bound to Nextcloud and the outbox
     * has drained, **the only copy of the one file this app cannot re-make is on one device**,
     * with no way to move it, back it up, or even listen to it elsewhere. That is not a missing
     * nicety; it is CLAUDE.md rule 1 with no route out.
     *
     * ## Copied, like the PDF, but for only one of the two reasons
     *
     * [snapshot]'s first reason does **not** apply: a finished recording is immutable — nothing
     * in the app ever rewrites it — so there is no torn read to prevent. The second does, and
     * harder: `019fede0-dd02-7000-….opus` is a useless thing to find in a mail client a year
     * later, and this is somebody's archive. So each one is copied under
     * `<note title> <date>.opus`.
     *
     * ⚠️ The copy transiently doubles the disk a recording takes, which for a three-hour lecture
     * is ~45 MB. That is why this shares **one note's** recordings rather than offering a
     * library-wide export: a term of lectures copied into the cache at once is exactly the
     * "discovered when the device fills up" outcome §11 exists to prevent.
     *
     * @return the audio Uris, and the timeline beside each one. Empty when the note has no
     *   finished recording — the caller says so rather than opening an empty chooser.
     */
    suspend fun snapshotRecordings(recordings: List<Recording>, title: String): List<Uri> =
        withContext(Dispatchers.IO) {
            val kept = recordings.filter { it.state == RecordingState.READY }
            if (kept.isEmpty()) return@withContext emptyList()

            val outbox = File(context.cacheDir, SHARE_DIR)
            outbox.deleteRecursively()
            if (!outbox.mkdirs()) return@withContext emptyList()

            val base = RemoteName.sanitise(title)
            kept.flatMapIndexed { index, recording ->
                // The index rather than the timestamp: two recordings in one sitting would
                // collide on a minute-resolution name, which is the same trap §6's remote
                // filename hit. The order is `startedAt`, so `1` is the earlier one.
                val stem = if (kept.size == 1) base else "$base ${index + 1}"
                listOfNotNull(
                    copyForShare(NoteLayout.recordingAudio(recording.id), File(outbox, "$stem.opus")),
                    copyForShare(NoteLayout.recordingTimeline(recording.id), File(outbox, "$stem.timeline")),
                )
            }
        }

    /** Null when the source is missing — a timeline is absent for a lecture nobody wrote in. */
    private fun copyForShare(relativePath: String, destination: File): Uri? {
        val source = File(context.filesDir, relativePath)
        if (!source.isFile || source.length() == 0L) return null
        source.copyTo(destination, overwrite = true)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destination)
    }

    /**
     * The chooser for [snapshotRecordings].
     *
     * `ACTION_SEND_MULTIPLE`, because the timeline travels with its audio — the same reason they
     * ride in one outbox op (§5): a lecture whose alignment data was left behind is just a
     * recording.
     */
    fun chooserForRecordings(uris: List<Uri>, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            // The common denominator of an .opus and a .timeline. A stricter type here would hide
            // every app that can take one but not the other.
            type = ANY_MIME
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private companion object {
        const val SHARE_DIR = "share"
        const val PDF_MIME = "application/pdf"
        const val ANY_MIME = "*/*"
    }
}
