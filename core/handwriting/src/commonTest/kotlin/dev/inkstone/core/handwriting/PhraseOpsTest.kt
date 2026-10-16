package dev.inkstone.core.handwriting

import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.GeneratedInk
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PhraseRecord
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §4.3 — what one written phrase is, as ops: one `session.apply`, so one undo entry. */
class PhraseOpsTest {

    private var next = 0
    private val tag = GeneratedInk("group-1", GeneratedInk.ASSEMBLY)
    private val record = PhraseRecord("hi there", null)

    private fun build(count: Int, replacing: List<ObjectId> = emptyList(), top: FractionalKey? = FractionalKey("m")) =
        PhraseOps.build(
            strokes = (1..count).map { StrokeGeometry(byteArrayOf(it.toByte())) },
            tags = List(count) { tag },
            record = record,
            topZ = top,
            replacing = replacing,
            newObjectId = { ObjectId("o${next++}") },
            nextOpId = { "op${next++}" },
            nextClock = { LamportClock(next.toLong(), "device") },
        )

    /** D2: a phrase with a neural word says so on that word's strokes, and is still one group. */
    @Test
    fun `each stroke carries its own words backend and model`() {
        val neural = GeneratedInk("group-1", GeneratedInk.NEURAL, "608af21a")
        val ops = PhraseOps.build(
            strokes = (1..3).map { StrokeGeometry(byteArrayOf(it.toByte())) },
            tags = listOf(tag, neural, neural),
            record = record,
            topZ = null,
            replacing = emptyList(),
            newObjectId = { ObjectId("o${next++}") },
            nextOpId = { "op${next++}" },
            nextClock = { LamportClock(next.toLong(), "device") },
        )
        val tags = ops.filterIsInstance<PageOp.SetFormat>().map { GeneratedInk.ofRecord(it.record) }
        assertEquals(listOf(tag, neural, neural), tags)
        assertEquals(1, tags.mapNotNull { it?.group }.toSet().size)
    }

    @Test
    fun `tombstones - then strokes above everything then their tags then the words`() {
        val ops = build(3, replacing = listOf(ObjectId("old")))

        assertTrue(ops[0] is PageOp.DeleteObject)
        val adds = ops.filterIsInstance<PageOp.AddObject>()
        assertEquals(3, adds.size)
        val keys = adds.map { (it.obj as StrokeObject).z }
        assertTrue(keys.zipWithNext().all { (a, b) -> a < b } && keys.first() > FractionalKey("m"))
        assertTrue(adds.all { (it.obj as StrokeObject).style == null }, "as captured, like the pen's own ink")
        val formats = ops.filterIsInstance<PageOp.SetFormat>()
        assertEquals(adds.map { it.obj.id }, formats.map { it.target })
        assertTrue(formats.all { GeneratedInk.ofRecord(it.record) == tag })
        val words = ops.last() as PageOp.SetTextContent
        assertEquals(adds.first().obj.id, words.target)
        assertEquals(record, PhraseRecord.decode(words.text))
    }

    @Test
    fun `nothing to write is no edit at all`() {
        assertTrue(build(0, replacing = listOf(ObjectId("old"))).isEmpty())
    }

    @Test
    fun `a phrase on an empty page starts at the first key`() {
        val ops = build(1, top = null)
        assertEquals(1, PhraseOps.addedIds(ops).size)
    }
}
