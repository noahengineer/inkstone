package dev.inkstone.core.handwriting

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.ObjectId

/**
 * ⭐ `handwriting/ledger/<documentId>.ledger` — which strokes **this device's pen** drew, and when
 * (docs/HANDWRITING-SYNTHESIS.md D8, §5.1).
 *
 * ## Why a ledger, and not the op log's `deviceId`
 *
 * Learning a person's handwriting must not learn anybody else's, and nothing in a note says who
 * wrote a stroke. An op's `deviceId` names the *install* that minted it, and copies re-stamp it:
 * an Element stamp, a duplicated note, a conflicted copy and a Recovered import all carry this
 * device's id on ink it never drew, and a compaction snapshot re-stamps every `AddObject` with the
 * page's clock. The one signal that means *this pen, this hand* is `InkSurfaceView.onStrokeCaptured`,
 * which fires for a stroke the pen has just drawn and for nothing else — page loads, merges, undo,
 * `addAll` and stamps never reach it. This file is that callback, written down.
 *
 * ## The format — `RecordingTimeline`'s, on purpose
 *
 * ```
 * <objectId> <downElapsedMs>\n
 * <objectId> <downElapsedMs> r\n      ← drawn against the ruler: not handwriting, never learned from
 * ```
 *
 * Plain text, append-only, one stroke per line. A process killed mid-append loses at most the line
 * it was writing, and [read] stops at the first line it cannot parse. A line with **more** fields
 * than this build knows is read, not rejected — `RecordingTimeline` learned that the hard way.
 *
 * ⚠️ The times are `SystemClock.elapsedRealtime` at the stroke's down event: comparable within one
 * session, which is all a word needs (the gap between its strokes), and never across devices.
 */
public object PenLedger {
    private const val SEPARATOR = ' '
    private const val RULED = "r"
    private const val MIN_FIELDS = 2

    public fun line(objectId: ObjectId, downElapsedMs: Long, ruled: Boolean): String {
        val flags = if (ruled) "$SEPARATOR$RULED" else ""
        return "${objectId.value}$SEPARATOR$downElapsedMs$flags\n"
    }

    /** Every complete line, in order. Stops at the first unparseable line — a torn tail. */
    public fun read(text: String): List<LedgerEntry> = text.lineSequence()
        .filter { it.isNotEmpty() }
        .map(::parse)
        .takeWhile { it != null }
        .filterNotNull()
        .toList()

    private fun parse(line: String): LedgerEntry? {
        val parts = line.split(SEPARATOR)
        if (parts.size < MIN_FIELDS || parts[0].isEmpty()) return null
        val at = parts[1].toLongOrNull() ?: return null
        return LedgerEntry(ObjectId(parts[0]), at, ruled = parts.drop(MIN_FIELDS).contains(RULED))
    }
}

/** One stroke the pen drew. See [PenLedger]. */
public data class LedgerEntry(val objectId: ObjectId, val downElapsedMs: Long, val ruled: Boolean)

/**
 * The buffer between the ink path and the disk — `TimelineWriter`'s shape, for the same reasons.
 *
 * ⚠️ **CLAUDE.md rule 7.** [add] is called on the UI thread, in the frame a stroke lands, so it
 * appends to a buffer and returns. [flush] does the I/O, off that thread, and takes no lock the
 * editor holds. Not thread-safe by contract: the owner (`:app`'s `PenLedgerRecorder`) serialises
 * every call.
 */
public class PenLedgerWriter(private val fs: FileSystem) {

    private val pending = LinkedHashMap<String, StringBuilder>()
    private var directoryMade = false

    public val hasUnflushedWork: Boolean get() = pending.isNotEmpty()

    public fun add(noteId: DocumentId, objectId: ObjectId, downElapsedMs: Long, ruled: Boolean) {
        pending.getOrPut(noteId.value) { StringBuilder() }.append(PenLedger.line(objectId, downElapsedMs, ruled))
    }

    /**
     * Append what has accumulated. The buffer is taken **before** the writes, so a write that
     * throws cannot make the next flush write the same lines twice — a lost line costs one stroke's
     * worth of learning, a doubled one costs nothing more but is still wrong.
     */
    public suspend fun flush() {
        if (pending.isEmpty()) return
        val batch = LinkedHashMap(pending)
        pending.clear()
        if (!directoryMade) {
            fs.createDirectories(HandwritingLayout.LEDGER_DIR)
            directoryMade = true
        }
        for ((note, lines) in batch) {
            fs.appendBytes(HandwritingLayout.ledger(DocumentId(note)), lines.toString().encodeToByteArray())
        }
    }
}

/**
 * The ledger of one note, keyed by stroke. Empty when there is none — a note written before the
 * feature existed, or on another device.
 */
public suspend fun FileSystem.readLedger(noteId: DocumentId): Map<ObjectId, LedgerEntry> {
    val path = HandwritingLayout.ledger(noteId)
    if (!exists(path)) return emptyMap()
    val text = runCatching { readBytes(path).decodeToString() }.getOrNull() ?: return emptyMap()
    return PenLedger.read(text).associateBy { it.objectId }
}
