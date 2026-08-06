package dev.inkstone.android.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeRebuilder

/**
 * SPEC §5.6 — the other half of the PDF handoff: an `/Ink` annotation's centreline
 * back into a stroke this app can draw, erase, undo and sync.
 *
 * ## What is honestly recovered, and what is not
 *
 * **Recovered:** the path, the colour, the opacity, a plausible width, and — the
 * part that matters — the [dev.inkstone.core.model.ObjectId] from `/NM`, which is
 * what stops a recovered note from duplicating every stroke against a peer that
 * still holds the originals.
 *
 * **Not recovered:** pressure, tilt, orientation, timing, tool type. `/Ink` has no
 * per-point width and `/AP` is a filled outline with no centre, so a rebuilt
 * stroke has none of the dynamics it was written with. It is uniform width. That
 * is [dev.inkstone.core.storage.RecoveryLoss.PRESSURE_AND_WIDTH], and SPEC §5.6
 * requires it to be *said*, not merely accepted.
 *
 * ## Why [InkInputModel.DIRECT]
 *
 * A recovered polyline has already been smoothed — once by the capture that
 * produced it and again by the thinning that wrote `/InkList`. Running it through
 * spring dynamics a third time would move the ink off the path the annotation
 * describes, so the naive model is the honest choice: follow the samples given.
 */
public class InkStrokeRebuilder(private val brushes: InkBrushFactory) : StrokeRebuilder {

    override fun rebuild(points: List<PagePoint>, colorArgb: Int, widthPt: Float): StrokeGeometry? {
        if (points.size < MIN_POINTS) return null

        val batch = MutableStrokeInputBatch()
        for ((index, point) in points.withIndex()) {
            // No pressure, tilt or orientation: recording an invented one would be
            // worse than recording none, because the fountain brush would then vary
            // its width along a curve nobody drew.
            batch.add(
                type = InputToolType.STYLUS,
                x = point.x,
                y = point.y,
                elapsedTimeMillis = index.toLong() * MILLIS_PER_POINT,
            )
        }

        val kind = kindFor(colorArgb)
        val brush = brushes.brushFor(
            kind = kind,
            colorArgb = colorArgb,
            sizePt = widthPt,
            epsilonPt = brushes.epsilonPt,
            inputModel = InkInputModel.DIRECT,
        )
        return StrokeGeometryCodec.encode(Stroke(brush, batch), kind, InkInputModel.DIRECT)
    }

    /**
     * Which brush a recovered stroke gets.
     *
     * The PDF records a colour and an opacity and nothing about the pen. Opacity is
     * the one signal that survives and it is a reliable one here: SPEC §3.4 makes
     * the highlighter the only translucent brush of the four, so a translucent
     * annotation was a highlight and an opaque one was ink. Guessing between
     * fountain, ballpoint and pencil is not possible and not attempted — the
     * fountain is the default pen (SPEC §3.4), and with no pressure to vary it
     * draws the uniform line the annotation actually describes.
     */
    private fun kindFor(colorArgb: Int): InkBrushKind =
        if ((colorArgb ushr ALPHA_SHIFT) and BYTE_MASK < OPAQUE) InkBrushKind.HIGHLIGHTER else InkBrushKind.FOUNTAIN

    private companion object {
        const val MIN_POINTS = 2

        /**
         * A stand-in cadence. Nothing reads it — the families forbid any behaviour
         * driven by time along the stroke (CLAUDE.md, `InkstoneBrushFamilies`) —
         * but a batch with every sample at t=0 is a degenerate input, so it counts.
         */
        const val MILLIS_PER_POINT = 4L

        const val ALPHA_SHIFT = 24
        const val BYTE_MASK = 0xFF
        const val OPAQUE = 0xFF
    }
}
