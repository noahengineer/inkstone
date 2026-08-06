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
        for (index in 0 until original.inputs.size) {
            original.inputs.populate(index, before)
            decoded.stroke.inputs.populate(index, after)
            assertEquals("x at $index", before.x, after.x, TOLERANCE)
            assertEquals("y at $index", before.y, after.y, TOLERANCE)
            assertEquals("pressure at $index", before.pressure, after.pressure, TOLERANCE)
        }
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
        const val TOLERANCE = 1e-3f
        const val BOX_TOLERANCE = 0.5f
        const val HEADER_BYTES = 20
        const val JSON_BYTES_PER_POINT = 60
    }
}
