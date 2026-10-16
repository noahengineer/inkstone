package dev.inkstone.core.handwriting.neural

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * docs/HANDWRITING-SYNTHESIS.md §4.4 and D13: a weights file is checked before anything is built from
 * it, and a release refuses one trained on anything not known to allow commercial use.
 */
class WeightsFileTest {

    @Test
    fun aCleanModelLoadsUnderTheReleasePolicy() {
        val loaded = assertIs<ModelLoad.Loaded>(WeightsFile.read(TinyModelFixture.bytes, LicencePolicy.RELEASE))
        assertTrue(loaded.weights.nonCommercial.isEmpty())
        val set = loaded.weights.header.datasets.single()
        assertEquals("LicenseRef-Inkstone-Synthetic", set.licence)
        assertEquals(listOf("OFL-1.1"), set.sources.map { it.licence })
    }

    /** ⭐ D13's pin: a header that names a non-commercial licence is refused by a release build. */
    @Test
    fun aReleaseRefusesAHeaderThatNamesANonCommercialLicence() {
        val refused =
            assertIs<ModelLoad.Refused>(WeightsFile.read(TinyModelFixture.nonCommercial, LicencePolicy.RELEASE))
        assertEquals(ModelLoad.Reason.NOT_COMMERCIAL, refused.reason)
        assertTrue("CC-BY-NC-SA-4.0" in refused.detail, refused.detail)
    }

    @Test
    fun aDebugBuildLoadsItFlagged() {
        val loaded =
            assertIs<ModelLoad.Loaded>(WeightsFile.read(TinyModelFixture.nonCommercial, LicencePolicy.DEVELOPMENT))
        assertEquals(listOf("CC-BY-NC-SA-4.0"), loaded.weights.nonCommercial)
    }

    @Test
    fun anUnknownLicenceIsRefusedNotGuessedAt() {
        assertTrue("CC-BY-NC-4.0" !in WeightsFile.COMMERCIAL_LICENCES)
        assertTrue("" !in WeightsFile.COMMERCIAL_LICENCES)
    }

    /** ⭐ The fault test's core half: a corrupted file turns the backend off, it does not half-load. */
    @Test
    fun aFlippedByteFailsTheChecksum() {
        for (at in listOf(5, 200, TinyModelFixture.bytes.size / 2, TinyModelFixture.bytes.size - 40)) {
            val bytes = TinyModelFixture.bytes.copyOf()
            bytes[at] = (bytes[at].toInt() xor 0x10).toByte()
            val refused = assertIs<ModelLoad.Refused>(WeightsFile.read(bytes, LicencePolicy.RELEASE), "byte $at")
            assertEquals(ModelLoad.Reason.CORRUPT, refused.reason, "byte $at")
        }
    }

    @Test
    fun truncatedEmptyAbsentAndForeignFilesAreRefused() {
        val bytes = TinyModelFixture.bytes
        assertEquals(ModelLoad.Reason.CORRUPT, refusal(bytes.copyOf(bytes.size - 1)))
        assertEquals(ModelLoad.Reason.CORRUPT, refusal(bytes.copyOf(100)))
        assertEquals(ModelLoad.Reason.CORRUPT, refusal(ByteArray(0)))
        assertEquals(
            ModelLoad.Reason.CORRUPT,
            refusal("%PDF-1.7 not a model at all, padded out".repeat(4).encodeToByteArray()),
        )
        assertEquals(ModelLoad.Reason.ABSENT, refusal(null))
    }

    @Test
    fun halfFloatsDecodeExactly() {
        assertEquals(1f, WeightsFile.halfToFloat(0x3C00))
        assertEquals(-2f, WeightsFile.halfToFloat(0xC000))
        assertEquals(65504f, WeightsFile.halfToFloat(0x7BFF))
        assertEquals(0.000060975552f, WeightsFile.halfToFloat(0x03FF))
        assertEquals(5.9604645E-8f, WeightsFile.halfToFloat(0x0001))
        assertEquals(0f, WeightsFile.halfToFloat(0x0000))
        assertEquals(Float.NEGATIVE_INFINITY, WeightsFile.halfToFloat(0xFC00))
        assertTrue(WeightsFile.halfToFloat(0x7E00).isNaN())
    }

    private fun refusal(bytes: ByteArray?): ModelLoad.Reason =
        assertIs<ModelLoad.Refused>(WeightsFile.read(bytes, LicencePolicy.RELEASE)).reason
}
