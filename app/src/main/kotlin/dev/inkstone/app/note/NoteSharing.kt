package dev.inkstone.app.note

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.inkstone.core.model.DocumentId
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

    private companion object {
        const val SHARE_DIR = "share"
        const val PDF_MIME = "application/pdf"
    }
}
