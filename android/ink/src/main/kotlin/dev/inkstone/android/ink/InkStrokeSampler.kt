package dev.inkstone.android.ink

import androidx.ink.strokes.ImmutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.InkObject
import dev.inkstone.core.model.InkSample
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.SampledStroke
import dev.inkstone.core.model.StrokeObject
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
 *
 * ## ⛔ It no longer tessellates the page
 *
 * This built a whole `StrokeStore` out of the fold — `applyOps` → `buildDryStroke` →
 * `StrokeGeometryCodec.decode` → `Stroke(brush, inputs)`, i.e. a **native tessellation of every
 * stroke on the page**, plus a copy of every erased region — and then read `stroke.inputs` and
 * nothing else. SPEC §8.3 runs a pass three seconds after every burst of writing, so on a note
 * like B (2,097 objects) the recogniser re-tessellated the whole page each time the user paused:
 * the same cost `PreparedPage` exists to keep off the UI thread, spent again in the background
 * for no pixel. [StrokeGeometryCodec.decodeInputs] reads the batch and stops.
 *
 * ⚠️ **The output is the same list, and that was the constraint, not an aspiration** — same ids,
 * same order, same timestamps, same boxes, same objects skipped. [penStrokesInOrder] is where the
 * old store's behaviour is reproduced rule by rule, and its KDoc lists them.
 *
 * @param brushes no longer read. Kept because `:app`'s DI builds this with one, and the sampler
 *   stopping needing a brush is not a reason to change that module's graph.
 */
public class InkStrokeSampler(
    @Suppress("UnusedPrivateProperty", "unused") private val brushes: InkBrushFactory,
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
     * else. It comes from `(z, id)` — the order [FoldedPage.objects] is already in — and since
     * `PageOpRecorder` hands out fractional z keys in insertion order, that *is* the order the
     * strokes were drawn in. Each stroke is offset past the end of the one before, with a
     * pen-lift gap between, which reconstructs a plausible page-wide clock from real
     * within-stroke timing.
     */
    override suspend fun sample(page: FoldedPage): List<SampledStroke> = withContext(dispatcher) {
        val strokes = penStrokesInOrder(
            objects = page.objects,
            decode = { StrokeGeometryCodec.decodeInputs(it.geometry) },
            isHighlighter = { it.kind.drawsOverInk },
        )
        val result = ArrayList<SampledStroke>(strokes.size)
        // One scratch for the whole page. `populate` is a JNI call per sample and a dense
        // page is thousands of strokes at ~200 Hz; allocating per sample is the difference
        // between a background pass and a visible stall.
        val scratch = StrokeInput()
        var clock = 0L
        for ((obj, decoded) in strokes) {
            val samples = samplesOf(decoded.inputs, obj.transform, scratch, clock)
            if (samples.isEmpty()) continue
            // Before the box, exactly as the store walk did it: a stroke skipped for want of a box
            // still moved the page clock on.
            clock = samples.last().tMillis + PEN_LIFT_MILLIS
            val bounds = PageRect.around(samples.map { PagePoint(it.x, it.y) }) ?: continue
            result += SampledStroke(id = obj.id, samples = samples, bounds = bounds)
        }
        result
    }

    private fun samplesOf(
        inputs: ImmutableStrokeInputBatch,
        transform: Matrix3,
        scratch: StrokeInput,
        base: Long,
    ): List<InkSample> {
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
        /** A plausible pen lift between two strokes, so their timings do not abut. */
        const val PEN_LIFT_MILLIS = 60L
    }
}

/**
 * The page's **pen** strokes, decoded, in `(z, id)` order — exactly the strokes, and exactly the
 * order, that walking a `StrokeStore` built from [objects] visited.
 *
 * The rules, each one a behaviour of that store rather than a choice made here:
 *
 * 1. **An undecodable blob is skipped** — `buildDryObject` answered null and the store held it out.
 * 2. **A repeated id is skipped** — `addObject` refused an id already in the store, so the first
 *    object *that built* won, whatever its kind. (An undecodable stroke never entered the store, so
 *    it does not block a later object with its id.) A fold cannot produce a repeat; a hand-built
 *    [FoldedPage] can.
 * 3. **Highlighters are skipped** — they sat in the store's second band and the sampler dropped them.
 * 4. **The order is `(z.value, id.value)`**, the store's `RENDER_ORDER`, over the pen band alone.
 *    [FoldedPage.objects] is already in that order when a fold made it; sorting again is a stable
 *    no-op then, and the same answer the store gave when it was not.
 *
 * Generic in the decoder so the rules can be tested on the JVM, where no blob can be decoded.
 */
internal fun <D : Any> penStrokesInOrder(
    objects: List<InkObject>,
    decode: (StrokeObject) -> D?,
    isHighlighter: (D) -> Boolean,
): List<Pair<StrokeObject, D>> {
    val seen = HashSet<ObjectId>()
    val pens = ArrayList<Pair<StrokeObject, D>>()
    for (obj in objects) {
        if (obj.id in seen) continue
        if (obj !is StrokeObject) {
            seen += obj.id
            continue
        }
        val decoded = decode(obj) ?: continue
        seen += obj.id
        if (!isHighlighter(decoded)) pens += obj to decoded
    }
    pens.sortWith(compareBy({ it.first.z.value }, { it.first.id.value }))
    return pens
}
