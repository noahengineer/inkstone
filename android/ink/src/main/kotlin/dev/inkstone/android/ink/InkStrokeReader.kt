package dev.inkstone.android.ink

import androidx.ink.strokes.ImmutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import dev.inkstone.core.model.CapturedStroke
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PenKind
import dev.inkstone.core.model.PenStyle
import dev.inkstone.core.model.PenTrajectory
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.StrokeReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Strokes as the pen made them — pressure, tilt, timing and brush — out of SPEC §5.4's blob, for
 * handwriting synthesis (docs/HANDWRITING-SYNTHESIS.md §5).
 *
 * [InkStrokeSampler]'s sibling, and built the same way for the same reason: the blob is
 * `androidx.ink`'s native `StrokeInputBatch`, so this seam is the only way `:core` sees inside it.
 * It reads the batch and stops — [StrokeGeometryCodec.decodeInputs] builds no `Stroke` and
 * tessellates nothing.
 *
 * What it adds over the sampler is what the recogniser cannot use and handwriting is made of:
 * pressure and tilt when the batch recorded them, the brush from the blob's header, and the
 * stroke-unit length, so a generated stroke can ask a fountain pen for the speed response the
 * user's own strokes had.
 *
 * The object's transform is applied, so the result is in page space whatever the stroke has been
 * through. Whether a stroke that was rotated may be *learned* from is `:core`'s decision
 * (`Harvester.ineligibility`), not this reader's.
 */
public class InkStrokeReader(private val dispatcher: CoroutineDispatcher = Dispatchers.Default) : StrokeReader {

    override suspend fun read(page: FoldedPage, ids: Collection<ObjectId>): Map<ObjectId, CapturedStroke> =
        withContext(dispatcher) {
            val wanted = ids.toHashSet()
            // One scratch for the call: `populate` is a JNI call per sample.
            val scratch = StrokeInput()
            page.objects
                .filterIsInstance<StrokeObject>()
                .filter { it.id in wanted }
                .distinctBy { it.id }
                .mapNotNull { stroke -> captured(stroke, scratch)?.let { stroke.id to it } }
                .toMap()
        }

    /** One stroke, decoded — or null for a blob that is not ours or a brush this build does not know. */
    private fun captured(stroke: StrokeObject, scratch: StrokeInput): CapturedStroke? {
        val decoded = StrokeGeometryCodec.decodeInputs(stroke.geometry) ?: return null
        val pen = StrokeGeometryCodec.penOf(stroke.geometry) ?: return null
        val kind = PenKind.entries.firstOrNull { it.name == pen.kind.name } ?: return null
        val trajectory = trajectoryOf(decoded.inputs, stroke.transform, scratch) ?: return null
        val inputs = decoded.inputs
        return CapturedStroke(
            id = stroke.id,
            trajectory = trajectory,
            pen = PenStyle(kind, pen.colorArgb, pen.sizePt),
            unitLengthCm = if (inputs.hasStrokeUnitLength()) inputs.getStrokeUnitLengthCm() else null,
        )
    }

    private fun trajectoryOf(
        inputs: ImmutableStrokeInputBatch,
        transform: Matrix3,
        scratch: StrokeInput,
    ): PenTrajectory? {
        if (inputs.size == 0) return null
        val hasPressure = inputs.hasPressure()
        val hasTilt = inputs.hasTilt()
        val builder = PenTrajectory.Builder(inputs.size, hasPressure, hasTilt)
        for (index in 0 until inputs.size) {
            inputs.populate(index, scratch)
            builder.add(
                x = transform.m00 * scratch.x + transform.m01 * scratch.y + transform.m02,
                y = transform.m10 * scratch.x + transform.m11 * scratch.y + transform.m12,
                tMs = scratch.elapsedTimeMillis.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                pressure = if (hasPressure) scratch.pressure.coerceIn(0f, 1f) else 0f,
                tilt = if (hasTilt) scratch.tiltRadians.coerceIn(0f, HALF_PI) else 0f,
            )
        }
        return builder.build()
    }

    private companion object {
        const val HALF_PI = (Math.PI / 2).toFloat()
    }
}
