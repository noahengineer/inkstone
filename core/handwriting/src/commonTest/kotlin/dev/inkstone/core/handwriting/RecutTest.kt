package dev.inkstone.core.handwriting

import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.StrokeReader
import dev.inkstone.core.model.StrokeSampler
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 3's joined runs: a word with fewer clusters than letters is shared out by width
 * ([GlyphClusters.partition]) and each share is kept only if it reads back as its letters. Words
 * learned before this existed are cut once more by [Harvester.recut].
 */
class RecutTest {

    private val harvester = { reader: ScriptedReader ->
        Harvester(
            StrokeSampler { emptyList() },
            StrokeReader { _, _ -> emptyMap() },
            reader,
            IdFactory(Clock { 0L }, Random(1)),
        )
    }

    @Test
    fun `widths are shared out in proportion to letters`() {
        // "ml": a wide letter and a narrow one in one cluster each — one letter each.
        assertEquals(listOf(1, 1), GlyphClusters.partition(listOf(15f, 5f), listOf("m", "l")))
        // "num" written as "nu" joined and "m": the wide cluster takes two letters.
        assertEquals(listOf(2, 1), GlyphClusters.partition(listOf(20f, 11f), listOf("n", "u", "m")))
        assertEquals(listOf(1, 2), GlyphClusters.partition(listOf(10f, 21f), listOf("n", "u", "m")))
    }

    @Test
    fun `a partition is refused rather than forced`() {
        assertNull(GlyphClusters.partition(emptyList(), listOf("a")))
        assertNull(GlyphClusters.partition(listOf(1f, 1f, 1f), listOf("a", "b")))
        assertNull(GlyphClusters.partition(listOf(10f), "abcdef".map { it.toString() }))
    }

    @Test
    fun `a joined word learned whole is cut into the runs that read back`() = runTest {
        // "numl" written as "nu" joined, then m and l on their own.
        val word = HandFixtures.sample("numl", joined = setOf("nu"), cut = false)
        assertTrue(Harvester.needsRecut(word))
        val reader = ScriptedReader(listOf(listOf(listOf("nu"), listOf("m"), listOf("1", "I"))))

        val recut = harvester(reader).recut(listOf(word))?.single()

        assertEquals(listOf("nu", "m"), recut?.glyphs?.map { it.text })
        assertEquals(3, reader.calls.single().size)
        assertEquals("nu", reader.calls.single()[1].preContext)
    }

    @Test
    fun `a word already cut - or printed one letter at a time needs no recut`() {
        assertFalse(Harvester.needsRecut(HandFixtures.sample("numl", joined = setOf("nu"))))
        assertFalse(Harvester.needsRecut(HandFixtures.sample("lul", cut = false)))
    }

    @Test
    fun `a recogniser that cannot run leaves the samples as they were`() = runTest {
        val word = HandFixtures.sample("numl", joined = setOf("nu"), cut = false)
        val reader = ScriptedReader(unavailable = dev.inkstone.core.model.RecognitionUnavailable.MODEL_NOT_DOWNLOADED)

        assertNull(harvester(reader).recut(listOf(word)))
    }

    @Test
    fun `cut runs teach assembly the join and the letters`() {
        val word = HandFixtures.sample("numl", joined = setOf("nu"))
        val index = GlyphIndex.build(listOf(word), LabelBook())

        assertTrue(index.variants("nu").isNotEmpty())
        assertEquals(1, index.stats.joinCount("n", "u").joined)
        assertEquals(1, index.stats.joinCount("u", "m").lifted)
    }

    @Test
    fun `coverage counts letters and joined runs apart`() {
        val coverage = Coverage.of(listOf(HandFixtures.sample("numl", joined = setOf("nu"))), LabelBook())

        assertEquals(setOf("m", "l"), coverage.letters.keys)
        assertEquals(1, coverage.joinedRuns)
    }

    /**
     * H5, found on the tablet: *My handwriting* listed `= 1` for an `=` cut from `=27`, a word whose
     * frame put its letters six x-heights tall, and assembly could not write `=`. The panel counts what
     * assembly can use, not what was cut.
     */
    @Test
    fun `coverage counts only letters assembly can use`() {
        val usable = HandFixtures.sample("numl", joined = setOf("nu"))
        // Kept back to measure with (§5.5): its cuts never reach assembly.
        val heldOut = HandFixtures.sample("x", split = SampleSplit.HELD_OUT)
        // Its line measured a sixth of its real x-height, and it has no x-height letter to correct that.
        val li = HandFixtures.sample("li")
        val misframed = li.copy(frame = li.frame.copy(xHeightPt = li.frame.xHeightPt / 6f))

        val coverage = Coverage.of(listOf(usable, heldOut, misframed), LabelBook())

        assertEquals(mapOf("m" to 1, "l" to 1), coverage.letters, "only numl's cuts are usable")
        assertEquals(3, coverage.words, "every word still counts as learned")
    }
}
