package dev.inkstone.core.handwriting

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.ObjectId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/HANDWRITING-SYNTHESIS.md D8 — the only record of which ink is the user's own. */
class PenLedgerTest {

    @Test
    fun `a line reads back with its ruler flag`() {
        val text =
            PenLedger.line(ObjectId("a"), 1_000L, ruled = false) + PenLedger.line(ObjectId("b"), 1_250L, ruled = true)
        assertEquals(
            listOf(LedgerEntry(ObjectId("a"), 1_000L, false), LedgerEntry(ObjectId("b"), 1_250L, true)),
            PenLedger.read(text),
        )
    }

    @Test
    fun `a torn tail ends the read and keeps everything before it`() {
        // A process killed mid-append. The half-written line is not a stroke, and anything after it
        // is by definition not there.
        val text = PenLedger.line(ObjectId("a"), 1L, false) + "b-torn" + "\n" + PenLedger.line(ObjectId("c"), 3L, false)
        assertEquals(listOf(ObjectId("a")), PenLedger.read(text).map { it.objectId })
    }

    @Test
    fun `a line with fields from a later build is read not rejected`() {
        // RecordingTimeline's lesson: an exact field count turns one extra column into the loss of
        // every line after it.
        assertEquals(listOf(LedgerEntry(ObjectId("a"), 5L, true)), PenLedger.read("a 5 x r future\n"))
    }

    @Test
    fun `the writer appends per note and only what was added`() = runTest {
        val fs = FakeFileSystem()
        val writer = PenLedgerWriter(fs)
        val one = DocumentId("note-1")
        val two = DocumentId("note-2")

        writer.add(one, ObjectId("a"), 10L, false)
        writer.add(two, ObjectId("b"), 20L, true)
        assertTrue(writer.hasUnflushedWork)
        writer.flush()
        writer.flush()
        writer.add(one, ObjectId("c"), 30L, false)
        writer.flush()

        assertFalse(writer.hasUnflushedWork)
        assertEquals(listOf("a", "c"), fs.readLedger(one).keys.map { it.value })
        assertEquals(true, fs.readLedger(two)[ObjectId("b")]?.ruled)
        assertTrue(fs.readLedger(DocumentId("never-written")).isEmpty())
    }
}
