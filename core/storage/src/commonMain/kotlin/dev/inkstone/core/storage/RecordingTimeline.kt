package dev.inkstone.core.storage

import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageId

/**
 * ⭐ M12 — `recordings/<recordingId>.timeline`: which stroke was drawn when, relative to the
 * start of the audio (docs/M12-AUDIO.md §3).
 *
 * ## Why this is a sidecar and not an op, and not a bundle entry
 *
 * The two obvious designs are to put the offsets in the op log (a new op, or a field on
 * `AddObject`) or in the bundle as a known entry with a merge rule like M7's `text/`. Both are
 * wrong, and expensively so: SPEC's position is that **the op vocabulary *is* the sync format**,
 * and changing it after sync is live is the milestone where everything breaks.
 *
 * Neither is needed, because a recording has none of the properties that make something document
 * data. It is written by **one** device in **one** sitting; it is never merged, never edited, and
 * consumed **once**, by one server job. So: an append-only log beside the note, with no op, no
 * merge rule, no bundle schema bump and no convergence problem.
 *
 * ## The format
 *
 * ```
 * <objectId> <offsetMs>\n
 * <objectId> <offsetMs>\n
 * #gap <fromMs> <toMs>\n        ← the microphone was taken away (§9)
 * ```
 *
 * Plain text, one record per line, space-separated. Deliberately not the [Journal]'s framed
 * binary: there is no blob to carry, the whole file for a 90-minute lecture is a few kilobytes,
 * and the consumer is a **server-side script** that should not have to implement a container to
 * read it. A UUIDv7 contains no space and no newline, so the framing cannot be ambiguous.
 *
 * ⭐ **A stroke line carries a third field: the page it landed on** (§15.6's revision, where the
 * alignment unit became the page rather than the line). It is a *column* rather than something the
 * server derives, because the alternative is folding the note's op log to learn which page an
 * object is on — which means shipping Kotlin to the server or reimplementing the fold. One field
 * on a line the app already writes removes that whole dependency: the server needs the timeline
 * and the transcript, and nothing else.
 *
 * The field is **optional**. A recording made before it existed has two columns and still reads —
 * see [parse] — and simply aligns at whole-lesson granularity instead of per page.
 *
 * ## Crash safety, for free
 *
 * Append-only is crash-safe by construction, the way `journal.ops` already is: a process killed
 * mid-append loses at most the line it was writing, and [read] stops at the first line it cannot
 * parse rather than throwing. There is no checksum because there is nothing here worth resurrecting
 * a corrupt record for — a dropped line costs one stroke's alignment, and §3 already accepts that a
 * stroke deleted after the recording leaves a line pointing at nothing.
 *
 * ## ⚠️ The offsets are elapsed time, not wall clock
 *
 * CLAUDE.md forbids ordering sync operations by wall clock and this looks like a violation. It is
 * not: the alignment is between strokes and audio **captured on one device in one session**, and
 * is never compared across peers. `SystemClock.elapsedRealtime` from the recording's start —
 * monotonic, and immune to the clock correction that would otherwise move half a lecture.
 */
public object RecordingTimeline {

    /** A gap line's leading token. `#` cannot begin a UUIDv7, so the two can never be confused. */
    public const val GAP_MARKER: String = "#gap"

    private const val SEPARATOR = ' '
    private const val STROKE_FIELDS = 2
    private const val GAP_FIELDS = 3
    private const val PAGE_FIELD = 2

    /**
     * One stroke's line.
     *
     * @param offsetMillis elapsed-realtime since the recording started. **The stroke's start**,
     *   not its end: writing lags speech, so §7's window is anchored to when the user *began*
     *   making the mark. For a slow underline the two differ by about a second, which is a tenth
     *   of the asymmetry the window is built out of.
     */
    public fun strokeLine(objectId: ObjectId, offsetMillis: Long, pageId: PageId? = null): String {
        val page = pageId?.let { "$SEPARATOR${it.value}" } ?: ""
        return "${objectId.value}$SEPARATOR$offsetMillis$page\n"
    }

    /**
     * A stretch with no audio, because something took the microphone.
     *
     * ⚠️ §9: *"Losing the microphone is not the same as stopping."* A phone call takes the mic and
     * a recorder that carried on would write **silence** — worse than stopping, because it is
     * discovered an hour later, and worse still because the audio after the gap is then offset
     * from every stroke by the length of the call. The gap is what lets the server say *"nothing
     * was captured here"* instead of aligning the rest of the lecture to the wrong words.
     */
    public fun gapLine(fromMillis: Long, toMillis: Long): String =
        "$GAP_MARKER$SEPARATOR$fromMillis$SEPARATOR$toMillis\n"

    /**
     * Read it back — for a test, and for §9's recovery reporting what an interrupted lecture holds.
     *
     * Stops at the first unparseable line and keeps everything before it. A torn tail is the
     * expected shape of a file whose process was killed, not an error.
     */
    public fun read(text: String): List<TimelineEntry> = text.lineSequence()
        .filter { it.isNotEmpty() }
        // ⚠️ `takeWhile`, not `mapNotNull`: a line that will not parse is the **torn tail** of a
        // file whose process was killed, and everything after it is by definition not there. A
        // filter would silently skip it and keep reading whatever bytes happened to follow.
        .map(::parse)
        .takeWhile { it != null }
        .filterNotNull()
        .toList()

    /**
     * ⚠️ **`>=`, not `==`: a line with more fields than this build knows about is read, not
     * rejected.**
     *
     * CLAUDE.md requires forward compatibility of every storage format — *"unknown ops and fields
     * survive"* — and this one did not have it. With an exact field count, the first line a newer
     * build wrote with an extra column (a page id, say) would fail to parse, and because [read]
     * stops at the first unparseable line to preserve a torn tail, **the whole remainder of the
     * lecture's alignment would be silently discarded** by any older build that opened it. A format
     * whose extension path destroys data is worse than one that cannot be extended.
     *
     * The torn-tail behaviour is unaffected: a truncated final line has *fewer* fields than it
     * needs, or a number that will not parse, and both still stop the read.
     */
    private fun parse(line: String): TimelineEntry? {
        val parts = line.split(SEPARATOR)
        return when {
            parts.firstOrNull() == GAP_MARKER && parts.size >= GAP_FIELDS -> {
                val from = parts[1].toLongOrNull() ?: return null
                val to = parts[2].toLongOrNull() ?: return null
                TimelineEntry.Gap(from, to)
            }

            parts.size >= STROKE_FIELDS && parts[0] != GAP_MARKER -> {
                val offset = parts[1].toLongOrNull() ?: return null
                // Absent for a recording made before the column existed, which is a legitimate
                // file and not a damaged one — it aligns at whole-lesson granularity.
                val page = parts.getOrNull(PAGE_FIELD)?.takeIf { it.isNotEmpty() }?.let(::PageId)
                TimelineEntry.Stroke(ObjectId(parts[0]), offset, page)
            }

            else -> null
        }
    }
}

public sealed interface TimelineEntry {

    /**
     * A stroke, how far into the audio the user started drawing it, and which page it landed on.
     *
     * @property pageId null for a recording made before the column existed. Not an error — see
     *   [RecordingTimeline]'s format note.
     */
    public data class Stroke(val objectId: ObjectId, val offsetMillis: Long, val pageId: PageId? = null) :
        TimelineEntry

    /** No audio was captured between these two offsets. See [RecordingTimeline.gapLine]. */
    public data class Gap(val fromMillis: Long, val toMillis: Long) : TimelineEntry
}

/**
 * The buffer between the ink path and the disk.
 *
 * ⚠️ **CLAUDE.md rule 7.** A stroke is recorded on the UI thread, in the frame the dry layer
 * redraws — so [add] does exactly one thing: append to a list. The file write rides the journal's
 * own two-second rhythm through [flush], which runs off that thread and takes **no lock the editor
 * holds**: a timeline append must never be able to wait on `NoteEditor.serial`, or a fifty-minute
 * lecture's writes queue behind every whole-bundle materialize the screen dimming causes.
 *
 * Not thread-safe by contract, like [dev.inkstone.core.storage.NoteSession] and for the same
 * reason: a lock on the ink path is a cost with no benefit. The owner serialises it — see `:app`'s
 * `RecordingController`, which does every [add] and every [flush] inside its own single scope.
 */
public class TimelineWriter(private val fs: FileSystem, private val path: String) {

    private val pending = StringBuilder()

    /** How many lines have been written, buffered and flushed together. For the recovery report. */
    public var lineCount: Int = 0
        private set

    public val hasUnflushedWork: Boolean get() = pending.isNotEmpty()

    public fun add(objectId: ObjectId, offsetMillis: Long, pageId: PageId? = null) {
        pending.append(RecordingTimeline.strokeLine(objectId, offsetMillis, pageId))
        lineCount++
    }

    public fun addGap(fromMillis: Long, toMillis: Long) {
        pending.append(RecordingTimeline.gapLine(fromMillis, toMillis))
        lineCount++
    }

    /**
     * Append what has accumulated. A no-op when nothing has.
     *
     * The buffer is cleared **before** the append rather than after, so a write that throws
     * cannot make the next flush write the same lines twice. Losing a few offsets costs alignment
     * for a handful of strokes; duplicating them would put one stroke at two times, which the
     * server cannot tell from two strokes.
     */
    public suspend fun flush() {
        if (pending.isEmpty()) return
        val bytes = pending.toString().encodeToByteArray()
        pending.clear()
        fs.appendBytes(path, bytes)
    }
}
