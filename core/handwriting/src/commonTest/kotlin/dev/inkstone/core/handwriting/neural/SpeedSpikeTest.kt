package dev.inkstone.core.handwriting.neural

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Only that the spike runs and counts what it says; its numbers are the tablet's (§7.5). */
class SpeedSpikeTest {

    @Test
    fun aStepRunsForEveryKernelAndBatch() {
        val shape = SpeedSpike.Shape(layers = 2, cells = 32, alphabet = 20, window = 3, mixtures = 4)
        for (kernel in SpeedSpike.Kernel.entries) {
            for (batch in listOf(1, 3)) {
                val t = SpeedSpike.stepTime(shape, batch, kernel, steps = 20, warmup = 5, rounds = 3)
                assertTrue(t.bestMicros > 0.0 && t.bestMicros <= t.medianMicros)
            }
        }
    }

    @Test
    fun aPhraseCountsThePrimeAndEachBatchsLongestWord() {
        val shape = SpeedSpike.Shape(layers = 1, cells = 16, alphabet = 10, window = 2, mixtures = 2)
        val p = SpeedSpike.phraseTime(shape, words = listOf(3, 5, 2), batch = 2, pointsPerChar = 4, primeChars = 2)
        // Prime 2×4, then batch (3, 5) runs 6×4 and batch (2) runs 3×4.
        assertEquals(8 + 24 + 12, p.steps)
        assertTrue(p.firstWordMillis <= p.totalMillis)
    }

    @Test
    fun theParameterCountIsTheDesignsEstimate() {
        // §7.1: "3×256 (estimate ~1.7M parameters)", Graves's 3×400 "about 3.7M".
        val small = SpeedSpike.Shape(3, 256).parameters
        val graves = SpeedSpike.Shape(3, 400).parameters
        assertTrue(small in 1_600_000..1_900_000, "3x256 has $small")
        assertTrue(graves in 3_500_000..4_100_000, "3x400 has $graves")
    }
}
