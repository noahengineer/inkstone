package dev.inkstone.core.handwriting.quality

import dev.inkstone.core.handwriting.GlyphIndex
import dev.inkstone.core.handwriting.HandFixtures
import dev.inkstone.core.handwriting.LabelBook
import dev.inkstone.core.handwriting.SampleSplit
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.PenTrajectory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/HANDWRITING-SYNTHESIS.md §8.3 — the distance's properties and the score's arithmetic, over the
 * fixture hand. Whether F says anything about a real hand is measured on the user's profile (§20).
 */
class FidelityTest {

    private val nun = assertNotNull(GlyphIndex.normalisedWord(HandFixtures.sample("nun"), LabelBook()))
    private val mix = assertNotNull(GlyphIndex.normalisedWord(HandFixtures.sample("mix"), LabelBook()))

    @Test
    fun aWordIsAtNoDistanceFromItself() {
        assertEquals(0f, assertNotNull(Fidelity.distance(nun, nun)), 1e-6f)
    }

    @Test
    fun theDistanceIsSymmetric() {
        assertEquals(assertNotNull(Fidelity.distance(nun, mix)), assertNotNull(Fidelity.distance(mix, nun)), 1e-4f)
    }

    @Test
    fun aDifferentWordIsFurtherThanASlightlyMovedCopy() {
        val nudged = assertNotNull(nun.transformed(Matrix3.translate(0f, 0.05f)))
        val near = assertNotNull(Fidelity.distance(nun, nudged))
        val far = assertNotNull(Fidelity.distance(nun, mix))
        assertTrue(near < far, "nudged $near, other word $far")
        assertTrue(near > 0f)
    }

    @Test
    fun anIDotAddedAfterTheWordCostsDtwButNotChamfer() {
        // The same ink, with the dot stroke moved to the end — written after the word, as hands do.
        val ix = assertNotNull(GlyphIndex.normalisedWord(HandFixtures.sample("ix"), LabelBook()))
        // Strokes: the i's body, its dot, the x's two. The dot moves to the end.
        val reordered = reorder(ix, listOf(0, 2, 3, 1))
        val a = Fidelity.Path.of(ix)!!
        val b = Fidelity.Path.of(reordered)!!
        assertEquals(0f, Fidelity.chamfer(a, b), 1e-5f)
        assertTrue(Fidelity.dtw(a, b) > 0f)
    }

    @Test
    fun theScoreRunsFromGenericToSelf() {
        assertEquals(0f, Fidelity.score(dGeneric = 1f, dBackend = 1.2f, dSelf = 0.4f))
        assertEquals(1f, Fidelity.score(dGeneric = 1f, dBackend = 0.3f, dSelf = 0.4f))
        assertEquals(0.5f, assertNotNull(Fidelity.score(dGeneric = 1f, dBackend = 0.7f, dSelf = 0.4f)), 1e-6f)
        assertNull(Fidelity.score(dGeneric = 0.4f, dBackend = 0.3f, dSelf = 0.5f), "no room: nothing to say")
    }

    @Test
    fun aBackendThatWritesTheUsersOwnWordScoresOne() = runTest {
        val samples = listOf(
            HandFixtures.sample("nun", split = SampleSplit.HELD_OUT),
            HandFixtures.sample("nun"),
            HandFixtures.sample("nun", letterGap = 0.4f),
            HandFixtures.sample("mix"),
        )
        val index = GlyphIndex.build(samples, LabelBook())
        val set = FidelitySet.of(samples, LabelBook(), index)
        assertEquals(1, set.words.size)
        val held = set.words.single()
        val generic = FidelityProbe.generic(set, WordWriter { ls -> ls.map { mix } })
        val perfect = FidelityProbe.measure(set, generic, WordWriter { ls -> ls.map { held.ink } })
        assertEquals(1f, perfect.median)
        val generic2 = FidelityProbe.measure(set, generic, WordWriter { ls -> ls.map { mix } })
        assertEquals(0f, generic2.median, "writing what the generic writes scores zero")
        val none = FidelityProbe.measure(set, generic, WordWriter { ls -> ls.map { null } })
        assertEquals(0, none.written)
        assertNull(none.median)
    }

    private fun reorder(t: PenTrajectory, order: List<Int>): PenTrajectory {
        val b = PenTrajectory.Builder(t.pointCount, t.pressure != null, t.tilt != null)
        var clock = 0
        for (s in order) {
            for (i in t.strokeStart(s) until t.strokeEnd(s)) {
                b.add(t.x[i], t.y[i], clock, t.pressure?.get(i) ?: 0f, t.tilt?.get(i) ?: 0f)
                clock += 5
            }
            b.endStroke()
            clock += 100
        }
        return assertNotNull(b.build())
    }
}
