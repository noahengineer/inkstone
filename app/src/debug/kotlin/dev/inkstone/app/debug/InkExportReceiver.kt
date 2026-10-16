package dev.inkstone.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.inkstone.app.library.LibraryRepository
import dev.inkstone.app.sync.NoteSessionHost
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.StrokeSampler
import java.io.File
import java.util.Locale

/**
 * **Debug builds only.** Writes every note's pages out **as the line segmenter sees them**, so that
 * `LineSegmenter` can be measured on the JVM against real ink:
 *
 * ```
 * adb shell am broadcast -n dev.inkstone/.app.debug.InkExportReceiver [--es note <documentId>]
 * adb exec-out run-as dev.inkstone cat files/debug/ink-export.json > ink.json
 * ```
 *
 * The strokes come from the production `StrokeSampler` (`InkStrokeSampler`, which needs
 * androidx.ink's native code and so cannot run in a JVM test). The output is the format
 * `RealInkSegmentationReport` reads, which is `LineSegmenterCorpusTest`'s:
 * `{"pages":[{"id","groundTruth","strokes":[{"id","points"}]}]}`. Real notes have no ground truth,
 * so `groundTruth` is always empty. `LineReadReceiver` reads the same file.
 *
 * Each note is read the way handwriting learning reads it: `NoteSessionHost.readForLearning`,
 * read-only, through the single-writer gate, skipping a note an editor holds. Nothing is written
 * into a note.
 *
 * The work runs as a [DebugTaskWorker] job, not in this receiver, because a receiver's coroutine is
 * frozen when the device is locked (see there). `files/debug/ink-export.done` appears when the export
 * has been written; read the log tagged `InkExport` if it does not.
 */
class InkExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        DebugTaskWorker.enqueue(context, DebugTaskWorker.TASK_EXPORT, intent.getStringExtra(EXTRA_NOTE))
    }

    internal companion object {
        private const val TAG = "InkExport"
        private const val DIR = "debug"
        private const val OUTPUT = "ink-export.json"
        private const val DONE = "ink-export.done"
        private const val EXTRA_NOTE = "note"

        /** Every note, or only [note] when it is given. */
        suspend fun export(context: Context, note: String?) {
            val app = context.applicationContext
            val entry = EntryPointAccessors.fromApplication(app, InkExportEntryPoint::class.java)
            val dir = File(app.filesDir, DIR)
            dir.mkdirs()
            File(dir, DONE).delete()
            val notes = note?.let { listOf(DocumentId(it)) } ?: entry.library().notesForLearning().map { it.first }
            val pages = ArrayList<String>()
            var strokes = 0
            for (noteId in notes) {
                val exported = entry.sessions().readForLearning(noteId) { opened ->
                    opened.pageIds.map { pageId ->
                        val sampled = entry.sampler().sample(opened.page(pageId))
                        strokes += sampled.size
                        val body = sampled.joinToString(",") { stroke ->
                            val points = stroke.samples.joinToString(",") { sample ->
                                String.format(Locale.ROOT, "[%.2f,%.2f,%d]", sample.x, sample.y, sample.tMillis)
                            }
                            "{\"id\":\"${stroke.id.value}\",\"points\":[$points]}"
                        }
                        "{\"id\":\"${noteId.value}/${pageId.value}\",\"groundTruth\":[],\"strokes\":[$body]}"
                    }
                }
                if (exported == null) Log.w(TAG, "skipped ${noteId.value}: open in an editor, or would not open")
                pages += exported.orEmpty()
            }
            val out = File(dir, OUTPUT)
            val temp = File(dir, "$OUTPUT.tmp")
            temp.writeText("{\"pages\":[${pages.joinToString(",")}]}")
            check(temp.renameTo(out)) { "could not move ${temp.name} into place" }
            File(dir, DONE).writeText("${pages.size} pages, $strokes strokes\n")
            Log.i(TAG, "exported ${pages.size} pages, $strokes strokes, to ${out.path}")
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface InkExportEntryPoint {
    fun sessions(): NoteSessionHost

    fun sampler(): StrokeSampler

    fun library(): LibraryRepository
}
