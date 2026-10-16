package dev.inkstone.core.handwriting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** docs/HANDWRITING-SYNTHESIS.md §4.2 — a shard of samples, and what a later build may add to one. */
class WordSampleCodecTest {

    @Test
    fun `a shard round trips every field`() {
        val samples = listOf(Fixtures.sample("one"), Fixtures.sample("two", label = "ok", glyphs = emptyList()))
        val decoded = assertNotNull(WordSampleCodec.decode(WordSampleCodec.encode(samples)))

        // The trajectories are already on the codec's grid, so everything compares exactly.
        assertEquals(samples, decoded)
    }

    @Test
    fun `fields a later build appended to a record are skipped`() {
        // SPEC §5.8, for a file that is not a note: each record is length-prefixed so a newer build
        // can add to it, and this build reads what it knows and steps over the rest.
        val sample = Fixtures.sample("one")
        val record = ByteSink().also { sink ->
            val inner = WordSampleCodec.encode(listOf(sample))
            // Re-frame the single record with ten bytes of "future" appended to it.
            val cursor = ByteCursor(inner, start = 5)
            cursor.varint()
            val length = assertNotNull(cursor.varint()).toInt()
            val body = assertNotNull(cursor.take(length))
            sink.bytes("IHWS".encodeToByteArray())
            sink.byte(1)
            sink.varint(1)
            sink.varint((body.size + 10).toLong())
            sink.bytes(body)
            sink.bytes(ByteArray(10) { 7 })
        }.toByteArray()

        assertEquals(listOf(sample), WordSampleCodec.decode(record))
    }

    @Test
    fun `a shard from a later version is not read at all`() {
        val bytes = WordSampleCodec.encode(listOf(Fixtures.sample("one")))
        bytes[4] = 9
        assertNull(WordSampleCodec.decode(bytes))
    }

    @Test
    fun `a damaged record costs that record and not the shard`() {
        val good = Fixtures.sample("good")
        val bytes = WordSampleCodec.encode(listOf(Fixtures.sample("bad"), good))
        // Corrupt the first record's trajectory magic: it cannot decode, the second still does.
        val firstTrajectory = indexOf(bytes, "IHWT".encodeToByteArray())
        bytes[firstTrajectory] = 'X'.code.toByte()

        assertEquals(listOf(good), WordSampleCodec.decode(bytes))
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int =
        (0..haystack.size - needle.size).first { start -> needle.indices.all { haystack[start + it] == needle[it] } }
}
