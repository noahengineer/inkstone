package dev.inkstone.core.storage

import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M12 §3 — the append-only stroke↔audio sidecar.
 *
 * The claims worth testing are the ones that make it safe to write *while a lecture is running*:
 * that a killed process loses at most the line it was mid-way through, that a flush cannot
 * duplicate a line, and that a gap is unambiguous against a stroke.
 */
class RecordingTimelineTest {

    private val first = ObjectId("019fecef-0000-7000-8000-000000000001")
    private val second = ObjectId("019fecef-0000-7000-8000-000000000002")

    @Test
    fun `strokes and gaps round-trip`() {
        val text = buildString {
            append(RecordingTimeline.strokeLine(first, 1_200L))
            append(RecordingTimeline.gapLine(60_000L, 195_000L))
            append(RecordingTimeline.strokeLine(second, 240_500L))
        }

        assertEquals(
            listOf(
                TimelineEntry.Stroke(first, 1_200L),
                TimelineEntry.Gap(60_000L, 195_000L),
                TimelineEntry.Stroke(second, 240_500L),
            ),
            RecordingTimeline.read(text),
        )
    }

    /**
     * ⚠️ The shape a file has when the process was killed while writing it — which for a lecture
     * is the *expected* ending, not an exceptional one. Everything before the tear survives.
     */
    @Test
    fun `a torn tail keeps every complete line before it`() {
        val text = RecordingTimeline.strokeLine(first, 1_200L) + "019fecef-0000-7000-8000-00000000"

        assertEquals(listOf(TimelineEntry.Stroke(first, 1_200L)), RecordingTimeline.read(text))
    }

    /** A gap marker cannot be read as a stroke: `#` never begins a UUIDv7. */
    @Test
    fun `a gap is never mistaken for a stroke`() {
        val entries = RecordingTimeline.read(RecordingTimeline.gapLine(0L, 5L))

        assertTrue(entries.single() is TimelineEntry.Gap)
    }

    @Test
    fun `a flush writes each line exactly once`() = runTest {
        val fs = FakeFileSystem()
        val writer = TimelineWriter(fs, "recordings/r1.timeline")

        writer.add(first, 1_000L)
        writer.flush()
        // Nothing new: the second flush must not re-append what the first already wrote.
        writer.flush()
        writer.add(second, 2_000L)
        writer.flush()

        assertEquals(
            listOf(TimelineEntry.Stroke(first, 1_000L), TimelineEntry.Stroke(second, 2_000L)),
            RecordingTimeline.read(fs.readBytes("recordings/r1.timeline").decodeToString()),
        )
    }

    /**
     * A lecture where nobody wrote anything is ordinary — you listened. The upload must still
     * happen, so an empty timeline is a file with no lines and never a missing file.
     */
    @Test
    fun `a recording with no strokes flushes nothing and is not an error`() = runTest {
        val fs = FakeFileSystem()
        val writer = TimelineWriter(fs, "recordings/r1.timeline")

        writer.flush()

        assertEquals(0, writer.lineCount)
        assertTrue(!writer.hasUnflushedWork)
    }
}

/**
 * M12 §9 — a recovered recording's duration comes from the **container**, not from its size.
 *
 * ⚠️ The estimate this replaced was measured 42% high on a real emulator recording: 98,369 bytes
 * of a 23.03-second capture, against the 24 kbit/s that was asked for and the ~34 kbit/s
 * `MediaRecorder` actually produced. A duration shown to a user should not be a guess when the
 * file states the answer.
 */
class OggOpusTest {

    /** A minimal Ogg page carrying [granule]. Only the header matters to [OggOpus]. */
    private fun page(granule: Long, payload: ByteArray = ByteArray(4)): ByteArray {
        val header = ByteArray(27)
        "OggS".encodeToByteArray().copyInto(header)
        for (i in 0 until 8) header[6 + i] = ((granule shr (8 * i)) and 0xFF).toByte()
        header[26] = 1
        return header + byteArrayOf(payload.size.toByte()) + payload
    }

    @Test
    fun `the duration is the last page's granule at 48 kHz`() = runTest {
        val fs = FakeFileSystem()
        // 1_105_920 samples is the real granule of the force-stopped emulator recording.
        fs.writeBytesAtomically("r.opus", page(48_000L) + page(1_105_920L))

        assertEquals(23_040L, OggOpus.durationMillis(fs, "r.opus"))
    }

    /** A file that is not an Opus stream answers *"cannot tell"*, never a number. */
    @Test
    fun `a file with no Ogg pages has no duration`() = runTest {
        val fs = FakeFileSystem()
        fs.writeBytesAtomically("r.opus", ByteArray(64) { 7 })

        assertEquals(null, OggOpus.durationMillis(fs, "r.opus"))
        assertEquals(null, OggOpus.durationMillis(fs, "missing.opus"))
    }
}

/**
 * ⚠️ CLAUDE.md's storage rule — *"forward compatibility (unknown ops and fields survive)"* — applied
 * to M12's timeline, which did not have it when first written.
 *
 * `read` stops at the first line it cannot parse, so that a process killed mid-append loses only
 * the line it was writing. With an exact field count that same rule turned a **newer build's extra
 * column** into a truncation: every line after the first extended one would be discarded, silently,
 * by any older build that opened the file.
 */
class TimelineForwardCompatibilityTest {

    private val first = ObjectId("019fecef-0000-7000-8000-000000000001")
    private val second = ObjectId("019fecef-0000-7000-8000-000000000002")

    @Test
    fun `a newer build's extra fields are ignored and the rest of the file still reads`() {
        // ⚠️ Column three is the **page** since §15.6, so the unknown field here is the fourth.
        // This test failed the moment the page column landed, which is exactly its job: it asserts
        // that an *unrecognised* field is skipped, not that column three means nothing.
        val text = buildString {
            append("${first.value} 1200 page-7 pressure-0.4\n")
            append("#gap 60000 195000 microphone\n")
            append("${second.value} 240500\n")
        }

        assertEquals(
            listOf(
                TimelineEntry.Stroke(first, 1_200L, PageId("page-7")),
                TimelineEntry.Gap(60_000L, 195_000L),
                TimelineEntry.Stroke(second, 240_500L, null),
            ),
            RecordingTimeline.read(text),
            "an extra column truncated the file, losing every line after it",
        )
    }

    /** The torn tail must still stop the read — that is the property the strictness protected. */
    @Test
    fun `a truncated final line still ends the read`() {
        val text = "${first.value} 1200\n019fecef-0000"

        assertEquals(listOf(TimelineEntry.Stroke(first, 1_200L)), RecordingTimeline.read(text))
    }
}

/**
 * ⭐ M12 §15.6 — the page column, which is what makes the server's job a lookup.
 *
 * The alignment unit became the **page** after the first real lesson: fragmenting per line
 * destroyed the explanation, recognition is blind to diagrams, and the objectId↔line association
 * is never persisted anyway. A page id on the line the app is already writing removes the server's
 * only other route — folding the note's op log, which would mean shipping Kotlin to the server.
 */
class TimelinePageColumnTest {

    private val stroke = ObjectId("019fecef-0000-7000-8000-000000000001")
    private val page = PageId("019fecef-0000-7000-8000-0000000000p1")

    @Test
    fun `a page id round-trips as the third column`() {
        val text = RecordingTimeline.strokeLine(stroke, 1_200L, page)

        assertEquals("${stroke.value} 1200 ${page.value}\n", text)
        assertEquals(listOf(TimelineEntry.Stroke(stroke, 1_200L, page)), RecordingTimeline.read(text))
    }

    /**
     * ⚠️ The 48-minute lesson already captured has two columns and can never gain a third. It must
     * still read — it is the tuning fixture — and it aligns at whole-lesson granularity instead.
     */
    @Test
    fun `a recording made before the column still reads`() {
        val entries = RecordingTimeline.read("${stroke.value} 1200\n")

        assertEquals(listOf(TimelineEntry.Stroke(stroke, 1_200L, null)), entries)
    }

    @Test
    fun `a null page writes two columns rather than an empty third`() {
        assertEquals("${stroke.value} 1200\n", RecordingTimeline.strokeLine(stroke, 1_200L, null))
    }
}
