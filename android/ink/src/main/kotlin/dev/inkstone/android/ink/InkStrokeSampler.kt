package dev.inkstone.android.ink

import androidx.ink.strokes.StrokeInput
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.InkSample
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.SampledStroke
import dev.inkstone.core.model.StrokeSampler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SPEC §8.1's `(x, y, t)`, out of SPEC §5.4's opaque blob.
 *
 * The same shape as [InkStrokeOutliner], and for the same reason: the geometry is
 * `androidx.ink`'s native `StrokeInputBatch`, so there is no JVM path to it and this has to
 * be the seam. `:core:search` gets plain numbers and can therefore be unit-tested; only this
 * file needs a device.
 *
 * ## Three things it does that a naive extractor does not
 *
 * **The highlighter is excluded.** It is a stroke like any other and the recogniser would
 * read it as a very wide letter across the words it covers.
 *
 * **`transform` is applied.** It is `Matrix3.Identity` for every stroke this build produces —
 * ink is authored in page space (SPEC §4.4 rule 1) — so today this is a no-op. M8's selection
 * tools are the first writer of a non-identity transform, and an extractor that ignored it
 * would silently stop recognising every stroke the user had ever moved.
 *
 * **Timestamps are laid end to end.** See [sample].
 */
public class InkStrokeSampler(
    private val brushes: InkBrushFactory,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : StrokeSampler {

    /**
     * ⚠️ **`elapsedTimeMillis` restarts at ~0 for every stroke, and feeding that to a
     * recogniser is a silent error.**
     *
     * `androidx.ink` measures a stroke's elapsed time from *its own* `MotionEvent.downTime`,
     * so a page's strokes all come back spanning `0..duration`. Handed over unchanged, the
     * whole page looks to ML Kit as though every word was written simultaneously — which is
     * not an error it can report, only a result it can get wrong.
     *
     * There is no absolute capture time anywhere in the blob (the 20-byte header has no room
     * for one and the input batch carries none), so the ordering has to come from somewhere
     * else. It comes from [FoldedPage.objects], which is already sorted `(z, id)` — and since
     * `PageOpRecorder` hands out fractional z keys in insertion order, that *is* the order the
     * strokes were drawn in. Each stroke is offset past the end of the one before, with a
     * pen-lift gap between, which reconstructs a plausible page-wide clock from real
     * within-stroke timing.
     */
    override suspend fun sample(page: FoldedPage): List<SampledStroke> = withContext(dispatcher) {
        val store = StrokeStore()
        // The same decoder the editor, the thumbnail renderer and the PDF outliner use. A
        // second one would be a second place for the erase model to drift.
        store.applyOps(
            ops = page.objects.map { PageOp.AddObject(opId = "", clock = SAMPLE_CLOCK, obj = it) },
            brushes = brushes,
            record = false,
        )

        // ⭐ M8a — the transform comes off the `DryStroke` now. It used to be looked up from
        // the fold in a side map here, because the store was a pure render cache that did not
        // carry one; the store applies `SetTransform` itself since M8a, so the side map became
        // a *second* answer to the same question, differing from the store's exactly when one
        // of them was wrong. There is one now, and it is the one the screen draws.
        val result = ArrayList<SampledStroke>(store.size)
        // One scratch for the whole page. `populate` is a JNI call per sample and a dense
        // page is thousands of strokes at ~200 Hz; allocating per sample is the difference
        // between a background pass and a visible stall.
        val scratch = StrokeInput()
        var clock = 0L
        store.forEachInRenderOrder(visibleBounds = null) { dry ->
            if (dry.kind.drawsOverInk) return@forEachInRenderOrder
            val samples = samplesOf(dry, dry.transform, scratch, clock)
            if (samples.isEmpty()) return@forEachInRenderOrder
            clock = samples.last().tMillis + PEN_LIFT_MILLIS
            val bounds = PageRect.around(samples.map { PagePoint(it.x, it.y) })
                ?: return@forEachInRenderOrder
            result += SampledStroke(id = dry.id, samples = samples, bounds = bounds)
        }
        result
    }

    private fun samplesOf(dry: DryStroke, transform: Matrix3, scratch: StrokeInput, base: Long): List<InkSample> {
        val inputs = dry.stroke.inputs
        if (inputs.size == 0) return emptyList()
        val samples = ArrayList<InkSample>(inputs.size)
        for (index in 0 until inputs.size) {
            inputs.populate(index, scratch)
            val point = transform.map(PagePoint(scratch.x, scratch.y))
            samples += InkSample(point.x, point.y, base + scratch.elapsedTimeMillis)
        }
        return samples
    }

    private companion object {
        /**
         * Ops built here are never journaled (`record = false`), so this clock is never
         * compared against a real one — the same stand-in [InkStrokeOutliner] uses.
         */
        val SAMPLE_CLOCK = dev.inkstone.core.model.LamportClock(0, "")

        /** A plausible pen lift between two strokes, so their timings do not abut. */
        const val PEN_LIFT_MILLIS = 60L
    }
}
