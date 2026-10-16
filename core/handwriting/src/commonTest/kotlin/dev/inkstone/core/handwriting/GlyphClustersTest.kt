package dev.inkstone.core.handwriting

import dev.inkstone.core.model.InkSample
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.SampledStroke
import kotlin.test.Test
import kotlin.test.assertEquals

/** docs/HANDWRITING-SYNTHESIS.md §5.4, v1 — what counts as one letter's strokes. */
class GlyphClustersTest {

    @Test
    fun `printed letters are one cluster each in reading order`() {
        val strokes = listOf(box("c", 20f), box("a", 0f), box("b", 10f))
        assertEquals(listOf(listOf(1), listOf(2), listOf(0)), GlyphClusters.of(strokes))
    }

    @Test
    fun `a dot joins the stem it sits over whichever was drawn first`() {
        val dotFirst = listOf(box("dot", 1f, top = -6f, width = 1.5f, height = 1.5f), box("stem", 0f, width = 3f))
        val stemFirst = dotFirst.reversed()

        assertEquals(listOf(listOf(0, 1)), GlyphClusters.of(dotFirst))
        assertEquals(listOf(listOf(0, 1)), GlyphClusters.of(stemFirst))
    }

    @Test
    fun `a crossbar joins its t and the next letter stays apart`() {
        val strokes =
            listOf(box("stem", 2f, width = 2f), box("o", 10f), box("bar", 0f, top = 3f, width = 6f, height = 1f))
        assertEquals(listOf(listOf(0, 2), listOf(1)), GlyphClusters.of(strokes))
    }

    @Test
    fun `a joined pair is one cluster and so is never cut`() {
        // One stroke spanning two letters: v1 cannot split a stroke, so the word's clusters will not
        // number its letters and the harvester leaves it whole (v2's job, phase 4).
        val strokes = listOf(box("an", 0f, width = 16f))
        assertEquals(listOf(listOf(0)), GlyphClusters.of(strokes))
    }

    @Test
    fun `written units drop spaces and keep surrogate pairs whole`() {
        assertEquals(listOf("h", "i", "!"), writtenUnits("h i!"))
        assertEquals(listOf("a", "😀"), writtenUnits("a😀"))
    }

    private fun box(id: String, left: Float, top: Float = 0f, width: Float = 8f, height: Float = 12f): SampledStroke {
        val bounds = PageRect(left, top, left + width, top + height)
        return SampledStroke(
            ObjectId(id),
            listOf(InkSample(bounds.left, bounds.top, 0L), InkSample(bounds.right, bounds.bottom, 8L)),
            bounds,
        )
    }
}
