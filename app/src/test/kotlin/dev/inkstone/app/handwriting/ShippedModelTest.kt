package dev.inkstone.app.handwriting

import android.content.pm.ApplicationInfo
import dev.inkstone.core.handwriting.neural.KotlinSynthesisNetwork
import dev.inkstone.core.handwriting.neural.LicencePolicy
import dev.inkstone.core.handwriting.neural.ModelLoad
import dev.inkstone.core.handwriting.neural.WeightsFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * docs/HANDWRITING-SYNTHESIS.md H4, against **the asset that ships**: `assets/handwriting/base-v1.ihwm`.
 *
 * - D13: the policy a release build runs under is [LicencePolicy.RELEASE] — read off the APK's own
 *   `FLAG_DEBUGGABLE`, so the debug-key-signed release on the tablet counts as a release — and the
 *   shipped file loads under it, so every dataset it names allows commercial use.
 * - §7.3: Kotlin agrees with PyTorch on the shipped weights to 1e-4, on `export.py`'s golden vectors
 *   for this exact file (they carry its SHA-256, and the test refuses a mismatch rather than
 *   comparing two different models).
 *
 * Reads the asset from the source tree — a JVM test has no `AssetManager` — which is the same bytes
 * the APK packages.
 */
class ShippedModelTest {

    private val asset = File("src/main/assets/${AssetModelSource.PATH}")
    private val golden = File("src/test/resources/handwriting/base-v1.golden.json")

    @Test
    fun aReleaseBuildRunsUnderTheReleasePolicyAndADebugBuildDoesNot() {
        assertEquals(LicencePolicy.RELEASE, ModelPolicy.of(0))
        assertEquals(LicencePolicy.RELEASE, ModelPolicy.of(ApplicationInfo.FLAG_LARGE_HEAP))
        assertEquals(LicencePolicy.DEVELOPMENT, ModelPolicy.of(ApplicationInfo.FLAG_DEBUGGABLE))
    }

    @Test
    fun theShippedModelLoadsUnderTheReleasePolicy() {
        assumeTrue("no model asset yet", asset.exists())
        val load = WeightsFile.read(asset.readBytes(), LicencePolicy.RELEASE)
        assertTrue("refused: $load", load is ModelLoad.Loaded)
        val weights = (load as ModelLoad.Loaded).weights
        assertTrue(weights.nonCommercial.isEmpty())
        assertTrue("the header names its datasets", weights.header.datasets.isNotEmpty())
        assertTrue("every dataset names its sources", weights.header.datasets.all { it.sources.isNotEmpty() })
    }

    @Test
    fun kotlinAgreesWithPyTorchOnTheShippedWeights() {
        assumeTrue("no model asset yet", asset.exists() && golden.exists())
        val weights = (WeightsFile.read(asset.readBytes(), LicencePolicy.RELEASE) as ModelLoad.Loaded).weights
        val json = Json.parseToJsonElement(golden.readText()).jsonObject
        val goldenSha = json.getValue("sha256").jsonPrimitive.content
        assertEquals("the golden vectors are this file's", goldenSha, weights.sha256)
        val net = KotlinSynthesisNetwork(weights)
        val inputs = json.getValue("inputs").jsonArray.map { row ->
            row.jsonArray.map { it.jsonPrimitive.double.toFloat() }.toFloatArray()
        }
        val records = json.getValue("records").jsonArray.map { it.jsonObject }
        val traces = net.trace(
            json.getValue("text").jsonPrimitive.content,
            inputs,
            records.map { it.getValue("step").jsonPrimitive.int }.toSet(),
        )
        assertEquals(records.size, traces.size)
        for ((r, s) in records.zip(traces)) {
            val t = s.step
            for ((l, layer) in r.getValue("h").jsonArray.withIndex()) close("h[$l]@$t", layer.doubles(), s.h[l])
            for ((l, layer) in r.getValue("c").jsonArray.withIndex()) close("c[$l]@$t", layer.doubles(), s.c[l])
            close("kappa@$t", r.getValue("kappa").doubles(), s.kappa)
            close("w@$t", r.getValue("w").doubles(), s.w)
            close("phi@$t", r.getValue("phi").doubles(), s.phi)
            close("y@$t", r.getValue("y").doubles(), s.y)
        }
    }

    private fun kotlinx.serialization.json.JsonElement.doubles(): DoubleArray =
        jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()

    private fun close(what: String, want: DoubleArray, got: FloatArray) {
        for (i in want.indices) {
            val d = abs(want[i] - got[i])
            assertTrue("$what[$i]: Kotlin ${got[i]}, PyTorch ${want[i]}, |d| = $d", d <= TOLERANCE)
        }
    }

    private companion object {
        const val TOLERANCE = 1e-4
    }
}
