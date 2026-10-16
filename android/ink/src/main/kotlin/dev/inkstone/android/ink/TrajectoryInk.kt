package dev.inkstone.android.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import dev.inkstone.core.model.PenKind
import dev.inkstone.core.model.PenStyle
import dev.inkstone.core.model.PenTrajectory
import dev.inkstone.core.model.StrokeGeometry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ⭐ Generated handwriting, turned into ordinary ink — docs/HANDWRITING-SYNTHESIS.md D1, §6.3.
 *
 * [InkStrokeReader]'s inverse: a [PenTrajectory] stroke and a pen become exactly the blob a stroke
 * the user drew would have (SPEC §5.4), through the same `MutableStrokeInputBatch` → [Stroke] →
 * [StrokeGeometryCodec] path every captured stroke takes. Nothing downstream can tell the two apart
 * by their geometry, which is the point: erasing, selecting, sync, merge, search and the PDF all
 * work on generated ink because it *is* ink. What marks it as generated is the `hw` format key, set
 * beside it, never something in the blob.
 *
 * Every channel the trajectory has goes into the batch: pressure and tilt when it carries them, the
 * real timing, and the stroke-unit length, so a fountain pen's speed term (`InkstoneBrushFamilies`)
 * responds to generated ink as it does to the user's.
 *
 * ⚠️ **Off the main thread** — encoding builds a `Stroke`, which tessellates natively
 * (`StrokeGeometryCodec`'s KDoc), and this runs before the editor's lock is taken, never under it.
 */
public class TrajectoryInk(
    private val brushes: InkBrushFactory,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /**
     * @param strokes one trajectory per pen-down, in page points, each with one stroke.
     * @param unitLengthCm `androidx.ink`'s stroke-unit length: the median of the user's own strokes,
     *   or [POINT_CM] — one point at 100 % zoom — when nothing has been learned (§6.3).
     * @return one geometry per stroke, in order; a stroke the batch refuses is left out rather than
     *   thrown, so the caller can see a shortfall instead of a crash inside the editor.
     */
    public suspend fun encode(
        strokes: List<PenTrajectory>,
        pen: PenStyle,
        inputModel: InkInputModel,
        unitLengthCm: Float? = null,
    ): List<StrokeGeometry> = withContext(dispatcher) {
        val kind = InkBrushKind.entries.first { it.name == pen.kind.name }
        val brush = brushes.brushFor(kind, pen.colorArgb, pen.widthPt, brushes.epsilonPt, inputModel)
        val unit = unitLengthCm?.takeIf { it > 0f && it.isFinite() } ?: POINT_CM
        strokes.mapNotNull { trajectory ->
            val batch = batchOf(trajectory, unit) ?: return@mapNotNull null
            StrokeGeometryCodec.encode(Stroke(brush, batch), kind, inputModel)
        }
    }

    /** The current pen as `:core` names one: its width in points, as a captured stroke records it. */
    public fun penStyleOf(config: PenConfig): PenStyle =
        PenStyle(PenKind.entries.first { it.name == config.kind.name }, config.colorArgb, brushes.sizePtOf(config))

    /**
     * Every sample of the trajectory's strokes, on one clock from the first sample. Null — never a
     * throw — when the batch refuses one; `PenTrajectory` keeps ink's own rules, so that would be a
     * bug, and a bug must not be the thing that throws inside a drop.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun batchOf(trajectory: PenTrajectory, unitLengthCm: Float): MutableStrokeInputBatch? = try {
        val batch = MutableStrokeInputBatch()
        val base = trajectory.tMs[0]
        val pressure = trajectory.pressure
        val tilt = trajectory.tilt
        for (i in 0 until trajectory.pointCount) {
            batch.add(
                type = InputToolType.STYLUS,
                x = trajectory.x[i],
                y = trajectory.y[i],
                elapsedTimeMillis = (trajectory.tMs[i] - base).toLong(),
                strokeUnitLengthCm = unitLengthCm,
                pressure = pressure?.get(i) ?: StrokeInput.NO_PRESSURE,
                tiltRadians = tilt?.get(i) ?: StrokeInput.NO_TILT,
            )
        }
        batch
    } catch (refused: IllegalArgumentException) {
        null
    }

    public companion object {
        /** One page point in centimetres: 2.54 cm per inch, 72 points per inch. */
        public const val POINT_CM: Float = 2.54f / 72f
    }
}
