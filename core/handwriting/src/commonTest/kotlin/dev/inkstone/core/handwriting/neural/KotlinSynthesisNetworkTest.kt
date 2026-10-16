package dev.inkstone.core.handwriting.neural

import dev.inkstone.core.model.PenTrajectory
import dev.inkstone.core.storage.sha256Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §7.4's behaviours beyond the arithmetic [SynthesisNetworkGoldenTest] pins: words are written in
 * batches, one result per word; the runaway guard stops a window that never reaches the end of its
 * text, and such a word is a failure, never ink; a prime is read once and every word continues from it.
 */
class KotlinSynthesisNetworkTest {

    private val weights = assertIs<ModelLoad.Loaded>(
        WeightsFile.read(TinyModelFixture.bytes, LicencePolicy.RELEASE),
    ).weights
    private val net = KotlinSynthesisNetwork(weights)

    @Test
    fun aBatchGivesOneResultPerWordInOrderAndSkipsWhatItCannotWrite() {
        val words = listOf("ab", "cab", "dad", "zz", "a", "bad")
        val out = net.write(words, Primed.NONE, 0.5f, Random(4), batch = 4)
        assertEquals(words.size, out.size)
        assertNull(out[3], "'z' is outside the alphabet")
        out.forEachIndexed { i, w -> if (w != null) assertEquals(words[i], w.text) }
        assertTrue(out.count { it != null } >= 3, "most of a random model's words still reach the end")
    }

    @Test
    fun aWrittenWordIsValidInkWithOnePointPerStep() {
        val w = assertNotNull(net.write(listOf("dab"), Primed.NONE, 1f, Random(9)).single())
        assertEquals(w.steps, w.x.size)
        assertEquals(w.x.size, w.strokeEnds.last())
        assertTrue(w.strokeEnds.toList().zipWithNext().all { (a, b) -> b > a })
        assertTrue(w.x.all { it.isFinite() } && w.y.all { it.isFinite() })
    }

    /** ⭐ §7.4's runaway guard: a window that never moves never finishes, and the word is a failure. */
    @Test
    fun aWindowThatNeverMovesIsStoppedByTheGuardAndReturnsNothing() {
        val frozen = KotlinSynthesisNetwork(frozenWindow())
        val out = frozen.write(listOf("ab", "dcba"), Primed.NONE, 0.5f, Random(1))
        assertEquals(listOf(null, null), out)
    }

    @Test
    fun aPrimeIsReadOnceAndAWordStartsWhereItsPenLeftOff() {
        val ink = assertNotNull(
            PenTrajectory.of(
                floatArrayOf(0f, 0.2f, 0.4f, 0.6f, 1.2f, 1.4f),
                floatArrayOf(0f, 0.8f, 0.1f, 0.9f, 0.2f, 0.7f),
                intArrayOf(0, 10, 20, 30, 120, 130),
                null,
                null,
                intArrayOf(4, 6),
            ),
        )
        val primed = net.prime(Prime("ab", ink))
        assertTrue(primed.isPrimed)
        val state = assertNotNull(primed.state)
        assertTrue(state.kappa.any { it > 0f }, "the window moved while reading the prime")
        assertNull(net.prime(Prime("zz", ink)).state, "a prime the model cannot read is no prime")
        // A word continues from where the prime left the pen, with the prime's last point as its first
        // input -- the same as a phrase's second word in training.
        assertEquals(1.4f, state.lastX, 1e-5f)
        assertEquals(0.7f, state.lastY, 1e-5f)
        assertEquals(1f, state.lastInput[2], "the prime's last point lifts the pen")
    }

    /** The tiny model with κ's increment forced to exp(−30): the window stays on the first letter. */
    private fun frozenWindow(): WeightsFile {
        val bytes = TinyModelFixture.bytes.copyOf()
        val headerLength = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8) or
            ((bytes[10].toInt() and 0xFF) shl 16) or ((bytes[11].toInt() and 0xFF) shl 24)
        val header = Json.parseToJsonElement(bytes.decodeToString(12, 12 + headerLength)).jsonObject
        val k = header.getValue("arch").jsonObject.getValue("window").jsonPrimitive.int
        val entry = header.getValue("tensors").jsonArray.map { it.jsonObject }
            .first { it.getValue("name").jsonPrimitive.content == "window.b" }
        var dataStart = 12 + headerLength
        dataStart += (4 - dataStart % 4) % 4
        val at = dataStart + entry.getValue("offset").jsonPrimitive.int
        for (j in 2 * k until 3 * k) {
            val bits = (-30f).toRawBits()
            for (b in 0 until 4) bytes[at + 4 * j + b] = (bits ushr (8 * b)).toByte()
        }
        val body = bytes.copyOfRange(0, bytes.size - 32)
        val digest = body.sha256Hex().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        digest.copyInto(bytes, bytes.size - 32)
        return assertIs<ModelLoad.Loaded>(WeightsFile.read(bytes, LicencePolicy.RELEASE)).weights
    }
}
