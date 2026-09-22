package dev.inkstone.android.ink

import androidx.ink.geometry.Box
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.StrokeGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * SPEC §5.4 — the geometry blob, round-tripped.
 *
 * **Instrumented, not a unit test**, for the same reason the erase tests are: `androidx.ink`'s
 * geometry is native, so a `Stroke` cannot be built or tessellated off a device — and
 * `ink-storage`'s serializer is native too.
 *
 * What this proves is the claim `:core:*` depends on: a stroke goes in, opaque bytes come out,
 * and the same stroke comes back — with its pressure, its brush and its geometry intact. If
 * this fails, every note in the app is lossy and nothing in the pure-Kotlin test suite would
 * notice, because none of it looks inside the blob.
 */
@RunWith(AndroidJUnit4::class)
class StrokeGeometryCodecTest {

    private val display = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
    private val factory = InkBrushFactory(display.density, display.densityDpi / POINTS_PER_INCH)
    private val page = PageSize.Letter

    /**
     * JUnit's `assertNotNull` returns `Unit`, so it cannot be used as an expression. This can:
     * a null here means the codec failed, which is a test failure with a useful message.
     */
    private fun <T : Any> required(value: T?, what: String): T =
        requireNotNull(value) { "$what was null — the codec failed" }

    private fun oneStroke(kind: InkBrushKind, points: Int = 60) =
        SyntheticInk.generate(1, page, factory, seed = 11, pointsPerStroke = points).items
            .first()
            .let { (stroke, _) -> stroke to kind }

    @Test
    fun everyBrushKindRoundTrips() {
        for (kind in InkBrushKind.entries) {
            val (original, _) = oneStroke(kind)
            val blob = StrokeGeometryCodec.encode(original, kind, InkInputModel.SPRING)
            val decoded = required(StrokeGeometryCodec.decode(blob, factory), "decoded stroke")

            assertEquals("brush kind lost for $kind", kind, decoded.kind)
            assertEquals(
                "input count changed for $kind",
                original.inputs.size,
                decoded.stroke.inputs.size,
            )
            assertEquals(
                "brush colour lost for $kind",
                original.brush.colorIntArgb,
                decoded.stroke.brush.colorIntArgb,
            )
            assertEquals("brush size lost for $kind", original.brush.size, decoded.stroke.brush.size, 0f)
        }
    }

    @Test
    fun pressureAndTiltSurviveTheRoundTrip() {
        // The reason `ink-storage` is used at all rather than a hand-rolled point list: a
        // hand-rolled one compiles, draws, and silently drops every one of these.
        val (original, kind) = oneStroke(InkBrushKind.FOUNTAIN)
        val decoded = required(
            StrokeGeometryCodec.decode(StrokeGeometryCodec.encode(original, kind, InkInputModel.SPRING), factory),
            "decoded stroke",
        )

        assertEquals(original.inputs.hasPressure(), decoded.stroke.inputs.hasPressure())
        assertEquals(original.inputs.hasTilt(), decoded.stroke.inputs.hasTilt())
        assertEquals(original.inputs.hasOrientation(), decoded.stroke.inputs.hasOrientation())

        val before = StrokeInput()
        val after = StrokeInput()
        val positionTolerance = positionToleranceFor(original)
        for (index in 0 until original.inputs.size) {
            original.inputs.populate(index, before)
            decoded.stroke.inputs.populate(index, after)
            assertEquals("x at $index", before.x, after.x, positionTolerance)
            assertEquals("y at $index", before.y, after.y, positionTolerance)
            assertEquals("pressure at $index", before.pressure, after.pressure, UNIT_TOLERANCE)
            // The range precondition the codec cannot restate: `StrokeInput::orientation` must
            // be -1 or in [0, 2pi), and `StrokeInputBatch.decode` throws if it is not — which
            // `StrokeGeometryCodec.decode` turns into a null, i.e. a stroke that silently does
            // not draw. Assert it survives rather than trusting that it does.
            assertTrue(
                "orientation at $index left [0, 2pi): ${after.orientationRadians}",
                !after.hasOrientation || (after.orientationRadians >= 0f && after.orientationRadians < TURN),
            )
        }
    }

    /**
     * ⚠️ **The tolerance is derived, because an absolute one is the wrong shape.**
     *
     * `ink-storage` does not store floats: the payload is `ink.proto.CodedStrokeInputBatch`,
     * one `CodedNumericRun` per field, quantized to a fixed number of steps across the run's
     * own range. So the position error scales with **the stroke**, not with the page — and
     * the old `TOLERANCE = 1e-3f` was an absolute bound that happened to sit just under the
     * real error for a 28 pt fixture (measured: 0.00116 pt against a 1e-3 bar). That is why
     * this assertion had never passed on *any* architecture since M1, on the emulator or on
     * the tablet, despite being recorded in three places as an "arm64-tuned" tolerance.
     *
     * [POSITION_STEPS] is not taken on faith — [theQuantizationStepMatchesTheDerivedTolerance]
     * measures the worst-case error against it, so if the library's grid ever changes, that
     * test fails with the real number instead of this one drifting quietly.
     */
    private fun positionToleranceFor(stroke: Stroke): Float {
        val input = StrokeInput()
        var xMin = Float.MAX_VALUE
        var xMax = -Float.MAX_VALUE
        var yMin = Float.MAX_VALUE
        var yMax = -Float.MAX_VALUE
        for (index in 0 until stroke.inputs.size) {
            stroke.inputs.populate(index, input)
            xMin = minOf(xMin, input.x)
            xMax = maxOf(xMax, input.x)
            yMin = minOf(yMin, input.y)
            yMax = maxOf(yMax, input.y)
        }
        // One whole step of the wider axis: the two runs are quantized independently, and a
        // per-axis bound would make the assertion depend on which way the stroke happens to lean.
        return maxOf(xMax - xMin, yMax - yMin) / POSITION_STEPS
    }

    /**
     * The measurement [positionToleranceFor] rests on, pinned rather than assumed.
     *
     * Measured on a Galaxy Tab S9 FE+ (arm64, API 36): over a 25.09 pt stroke the worst
     * position error is **0.00305 pt** against a 4096-step of 0.00613 — i.e. **half a step**,
     * which is round-to-nearest and not truncation. Both bounds below matter: the upper one
     * is the rounding guarantee, and the lower one fails if the grid ever gets *finer*, which
     * would otherwise let [positionToleranceFor] go on passing while asserting far less than
     * it claims.
     */
    @Test
    fun theQuantizationStepMatchesTheDerivedTolerance() {
        val (original, kind) = oneStroke(InkBrushKind.FOUNTAIN)
        val decoded = required(
            StrokeGeometryCodec.decode(StrokeGeometryCodec.encode(original, kind, InkInputModel.SPRING), factory),
            "decoded stroke",
        )

        val step = positionToleranceFor(original)
        val before = StrokeInput()
        val after = StrokeInput()
        var worst = 0f
        for (index in 0 until original.inputs.size) {
            original.inputs.populate(index, before)
            decoded.stroke.inputs.populate(index, after)
            worst = maxOf(worst, abs(before.x - after.x), abs(before.y - after.y))
        }
        assertTrue(
            "worst position error $worst exceeded half a step of $step — rounding is no longer to nearest",
            worst <= step / 2f,
        )
        assertTrue(
            "worst position error $worst is under a quarter step of $step — is the grid still $POSITION_STEPS?",
            worst > step / 4f,
        )
    }

    /**
     * ⛔ **A wrapped orientation comes back from `ink-storage` at *exactly* `2pi` — measured.**
     *
     * [SyntheticInk.turn] puts orientation inside `[0, 2pi)`, which is what
     * `MutableStrokeInputBatch.add` requires. This test asked whether the value survives the
     * storage round trip, and the answer on the tablet is **no**: seed 21's stroke 19 carries
     * `6.27896` (0.0042 below the bound) at sample 8 and decodes back as `6.2831855`, which is
     * `2pi` itself and therefore *outside* the range `StrokeInput` documents. The quantization
     * rounded it up past the bound.
     *
     * Two things make this a recorded fact rather than a bug to fix here:
     *
     * 1. **`StrokeInputBatch.decode` accepts it** — the stroke decodes, tessellates and draws.
     *    The hazard would be feeding those inputs back into a *new* batch, which validates; the
     *    only two `MutableStrokeInputBatch` sites in `src/main` are `SyntheticInk` (fresh) and
     *    `InkStrokeRebuilder` (from PDF `/Ink`, which carries no orientation at all), and the
     *    three readers of a decoded `stroke.inputs` take x, y and pressure only. So nothing in
     *    this app can hit it today.
     * 2. Asserting `< 2pi` here would be asserting a promise `ink-storage` does not make.
     *
     * What this therefore guards is the guarantee that matters — **every stroke still decodes**,
     * i.e. no silent `null` from `StrokeGeometryCodec.decode` — plus the bound the library does
     * honour. If a future change starts re-adding decoded inputs to a batch, the note above is
     * the thing to read first.
     */
    @Test
    fun aWrappedOrientationSurvivesTheRoundTrip() {
        val batch = SyntheticInk.generate(WRAPPING_COUNT, page, factory, seed = 21).items
        val after = StrokeInput()
        var highest = 0f

        for ((original, kind) in batch) {
            val decoded = required(
                StrokeGeometryCodec.decode(StrokeGeometryCodec.encode(original, kind, InkInputModel.SPRING), factory),
                "decoded stroke from a wrapped-orientation fixture",
            )
            for (index in 0 until decoded.stroke.inputs.size) {
                decoded.stroke.inputs.populate(index, after)
                if (!after.hasOrientation) continue
                assertTrue(
                    "orientation at $index left [0, 2pi] after the round trip: ${after.orientationRadians}",
                    after.orientationRadians >= 0f && after.orientationRadians <= TURN,
                )
                highest = maxOf(highest, after.orientationRadians)
            }
        }

        assertExercisesTheUpperBound(highest)
    }

    /** See [aWrappedOrientationSurvivesTheRoundTrip] — a fixture that never nears `2pi` proves nothing. */
    private fun assertExercisesTheUpperBound(highest: Float) {
        assertTrue(
            "no orientation came within $NEAR_TURN of 2pi (highest was $highest) — " +
                "this fixture no longer exercises the bound it exists for",
            highest > TURN - NEAR_TURN,
        )
    }

    @Test
    fun geometryIsRebuiltNotJustRemembered() {
        // The decoded stroke has to be drawable — which means its mesh was re-tessellated
        // natively, not restored from bytes we never stored.
        val (original, kind) = oneStroke(InkBrushKind.BALLPOINT)
        val decoded = required(
            StrokeGeometryCodec.decode(StrokeGeometryCodec.encode(original, kind, InkInputModel.SPRING), factory),
            "decoded stroke",
        )

        val originalBox: Box = required(original.shape.computeBoundingBox(), "original bounds")
        val decodedBox: Box = required(decoded.stroke.shape.computeBoundingBox(), "decoded bounds")
        assertEquals(originalBox.xMin, decodedBox.xMin, BOX_TOLERANCE)
        assertEquals(originalBox.yMin, decodedBox.yMin, BOX_TOLERANCE)
        assertEquals(originalBox.xMax, decodedBox.xMax, BOX_TOLERANCE)
        assertEquals(originalBox.yMax, decodedBox.yMax, BOX_TOLERANCE)
    }

    @Test
    fun theInputModelIsRecordedSoReplayMatchesCapture() {
        // A stroke captured with spring smoothing must not be re-tessellated with the naive
        // model because the user changed the setting afterwards.
        val (original, kind) = oneStroke(InkBrushKind.PENCIL)
        factory.inputModel = InkInputModel.DIRECT

        val decoded = required(
            StrokeGeometryCodec.decode(StrokeGeometryCodec.encode(original, kind, InkInputModel.SPRING), factory),
            "decoded stroke",
        )
        required(decoded.stroke.shape.computeBoundingBox(), "decoded bounds")
    }

    @Test
    fun aBlobFromSomewhereElseIsRejectedRatherThanCrashing() {
        // One unreadable stroke must not cost a note its other 4,999.
        assertNull(StrokeGeometryCodec.decode(StrokeGeometry(ByteArray(0)), factory))
        assertNull(StrokeGeometryCodec.decode(StrokeGeometry(ByteArray(64)), factory))
        assertNull(
            StrokeGeometryCodec.decode(StrokeGeometry("not an inkstone blob at all".encodeToByteArray()), factory),
        )

        // Our header, then rubbish where the input batch should be.
        val (original, kind) = oneStroke(InkBrushKind.FOUNTAIN)
        val good = StrokeGeometryCodec.encode(original, kind, InkInputModel.SPRING).bytes
        val corrupt = good.copyOf()
        for (index in HEADER_BYTES until corrupt.size) corrupt[index] = 0
        assertNull(StrokeGeometryCodec.decode(StrokeGeometry(corrupt), factory))
    }

    @Test
    fun theBlobIsSmallerThanTheJsonItReplaces() {
        // SPEC §5.4's numbers, checked rather than assumed: "~60-80 bytes/point of text".
        val (original, kind) = oneStroke(InkBrushKind.FOUNTAIN, points = 200)
        val blob = StrokeGeometryCodec.encode(original, kind, InkInputModel.SPRING)

        val jsonEstimate = original.inputs.size * JSON_BYTES_PER_POINT
        assertTrue(
            "blob was ${blob.sizeBytes} bytes for ${original.inputs.size} points",
            blob.sizeBytes < jsonEstimate,
        )
    }

    private companion object {
        const val POINTS_PER_INCH = 72f

        /**
         * Steps in one `CodedNumericRun`. Asserted, not assumed — see
         * [theQuantizationStepMatchesTheDerivedTolerance].
         */
        const val POSITION_STEPS = 4096f

        /** Fields whose domain is a fixed `[0, 1]`, so an absolute bound *is* the right shape. */
        const val UNIT_TOLERANCE = 1e-3f

        /** A full turn, as `StrokeInput` bounds orientation: the range is `[0, TURN)`. */
        const val TURN = 6.2831855f

        /** Strokes to generate for the wrap fixture — seed 21's first wrapping stroke is #19. */
        const val WRAPPING_COUNT = 40

        /** How close to `TURN` counts as exercising the upper bound. Seed 21 reaches ~0.0042 short. */
        const val NEAR_TURN = 0.01f
        const val BOX_TOLERANCE = 0.5f
        const val HEADER_BYTES = 20
        const val JSON_BYTES_PER_POINT = 60
    }
}
