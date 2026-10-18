package dev.inkstone.android.pdf

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.storage.InkstoneMarker
import dev.inkstone.core.storage.InkstoneXmp
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * SPEC §6.2 — the file to draw a note's paper from: the imported PDF, **without** the ink.
 *
 * ## ⛔ Why `document.pdf` is the wrong file to render
 *
 * `document.pdf` is the original plus one incremental update (SPEC §5.2), and that update is
 * the ink as it was at the last materialization — per-stroke `/Ink` annotations with their
 * appearance streams, or the flattened overlay on a dense page (SPEC §6.4). A renderer draws
 * all of it. So every close baked the session's ink, text boxes and pictures into the paper the
 * editor drew next time, at the paper's resolution, while the live objects drew on top in the
 * same place. It looked like a faint outline around text; moving or erasing anything left the
 * old copy behind on the paper, where no tool could reach it. `SourcePages` had predicted the
 * doubling in its KDoc and called it harmless "because both come from the same geometry" —
 * which stops being true the moment anything moves.
 *
 * ## What this returns
 *
 * The original's bytes are the verbatim prefix of `document.pdf`, and a PDF renderer reads a
 * file from its end, so the prefix has to be its own file: it is copied once into [cacheDir],
 * keyed by the marker's `originalSha256`, and reused until the original changes. A cache, not a
 * second copy of user data — losing it costs one copy on the next open.
 *
 * Where there is no original to cut back to — no marker, a marker for another document, a
 * from-scratch note, an import not yet published — the published file *is* the paper, and it is
 * returned unchanged. That is also the safe direction: a wrong answer here can only ever be
 * "drew the old ink", never "drew less than the original".
 */
public class OriginalPdfCache(private val cacheDir: File) {

    /**
     * @return the file a renderer should open for [noteId]'s source pages.
     *
     * Locked across every instance, because the editor and the library's covers both ask, from
     * different threads, and one must not sweep away the other's half-written copy. Blocking
     * I/O: call it off the main thread.
     */
    public fun paperFor(noteId: DocumentId, published: File): File = synchronized(LOCK) {
        val marker = originalOf(noteId, published) ?: return published
        val length = marker.originalLength
        val dir = File(cacheDir, DIR).apply { mkdirs() }
        val name = "${noteId.value}-${marker.originalSha256.ifEmpty { length.toString() }.take(KEY_CHARS)}.pdf"
        val cached = File(dir, name)
        if (cached.length() == length) return cached

        // CLAUDE.md rule 10: the renderer must never open a half-copied file.
        val temp = File.createTempFile("${noteId.value}-", ".tmp", dir)
        try {
            copyPrefix(published, temp, length)
            if (!temp.renameTo(cached)) {
                cached.delete()
                check(temp.renameTo(cached)) { "could not cache the original of ${noteId.value}" }
            }
        } finally {
            temp.delete()
        }
        // A superseded original (a new version pulled from the server) is never asked for again.
        dir.listFiles { file ->
            file.name.startsWith("${noteId.value}-") && file.name.endsWith(".pdf") && file != cached
        }
            ?.forEach { it.delete() }
        cached
    }

    /** @return the marker naming [published]'s original prefix, or null to draw all of it. */
    private fun originalOf(noteId: DocumentId, published: File): InkstoneMarker? {
        if (!published.exists()) return null
        return findMarker(published)?.takeIf {
            it.documentId == noteId && it.hasOriginal && it.originalLength < published.length()
        }
    }

    private fun copyPrefix(from: File, to: File, length: Long) {
        from.inputStream().use { input -> to.outputStream().use { output -> copy(input, output, length) } }
    }

    private fun copy(input: InputStream, output: OutputStream, length: Long) {
        val buffer = ByteArray(COPY_BUFFER)
        var left = length
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            check(read >= 0) { "the published file ended before its original's $length bytes" }
            output.write(buffer, 0, read)
            left -= read
        }
    }

    internal companion object {
        private val LOCK = Any()
        private const val DIR = "source-pdf"
        private const val KEY_CHARS = 16
        private const val COPY_BUFFER = 64 * 1024
        private const val CHUNK = 64 * 1024

        /** Enough either side of the namespace to hold the whole packet (SPEC §5.6). */
        private const val PACKET_WINDOW = 2048

        private val NAMESPACE = InkstoneMarker.NAMESPACE.toByteArray(Charsets.ISO_8859_1)

        /**
         * The **last** XMP marker in the file, found by scanning backwards.
         *
         * The marker is written uncompressed precisely so it can be found this way
         * (`PdfBoxWriter.writeMarker`), and scanning avoids parsing a 20 MB lecture's object
         * graph on every open. The last one is the current one: an incremental update appends,
         * so an original that was itself an Inkstone export carries an older packet further up.
         */
        internal fun findMarker(file: File): InkstoneMarker? = RandomAccessFile(file, "r").use { raf ->
            val at = lastIndexOf(raf, NAMESPACE) ?: return null
            val from = (at - PACKET_WINDOW).coerceAtLeast(0)
            val to = minOf(raf.length(), at + PACKET_WINDOW)
            val window = ByteArray((to - from).toInt())
            raf.seek(from)
            raf.readFully(window)
            val text = window.toString(Charsets.ISO_8859_1)
            val local = (at - from).toInt()
            val start = text.lastIndexOf("<x:xmpmeta", local).takeIf { it >= 0 } ?: return null
            val end = text.indexOf("</x:xmpmeta>", local).takeIf { it >= 0 } ?: return null
            InkstoneXmp.parse(text.substring(start, end))
        }

        private fun lastIndexOf(raf: RandomAccessFile, needle: ByteArray): Long? {
            val buffer = ByteArray(CHUNK + needle.size)
            var end = raf.length()
            while (end > 0) {
                val start = (end - CHUNK).coerceAtLeast(0)
                // Overlap by the needle's length so a match straddling two chunks is still seen.
                val readEnd = minOf(raf.length(), end + needle.size - 1)
                val count = (readEnd - start).toInt()
                raf.seek(start)
                raf.readFully(buffer, 0, count)
                for (i in count - needle.size downTo 0) {
                    if (matches(buffer, i, needle)) return start + i
                }
                end = start
            }
            return null
        }

        private fun matches(buffer: ByteArray, at: Int, needle: ByteArray): Boolean {
            for (j in needle.indices) if (buffer[at + j] != needle[j]) return false
            return true
        }
    }
}
