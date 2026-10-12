package dev.inkstone.android.ink

import androidx.ink.strokes.StrokeInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkstone.core.model.EraseCapsule
import dev.inkstone.core.model.ErasedArea
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.InkSample
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.SampledStroke
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.TextBoxObject
import dev.inkstone.core.model.foldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ⭐ The recogniser's sampler gives the **same list** it gave when it tessellated the page.
 *
 * `InkStrokeSampler` built a whole `StrokeStore` — decode, brush, native tessellation, erased
 * region — for every stroke on the page on every recognition pass, and read `stroke.inputs` off it.
 * It reads the input batch straight out of the blob now ([StrokeGeometryCodec.decodeInputs]). The
 * only acceptable difference is the cost, so this compares the new path against [storeWalk], which
 * is the old implementation kept verbatim, on a page built to exercise every rule it had: pens in
 * an order their z keys contradict, a highlighter, a moved stroke, a restyled stroke, an erased
 * stroke, a text box and a blob this build cannot read.
 *
 * Instrumented because both halves decode a native `StrokeInputBatch`. The ordering and skipping
 * rules are also asserted on the JVM (`PenStrokeOrderTest`). ⚠️ **Not run on a device when it was
 * written.**
 */
@RunWith(AndroidJUnit4::class)
class InkStrokeSamplerTest {

    private lateinit var factory: InkBrushFactory

    @Before
    fun setUp() {
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        factory = InkBrushFactory(metrics.density, metrics.densityDpi / POINTS_PER_INCH)
    }

    @Test
    fun theSampledListIsWhatTheStoreWalkProduced() {
        val fixture = mixedPage()

        val expected = storeWalk(fixture.page)
        val actual = runBlocking { InkStrokeSampler(factory, Dispatchers.Unconfined).sample(fixture.page) }

        // The fixture has to exercise what it claims to, or equality proves nothing.
        val ids = expected.map { it.id }
        assertTrue("the moved stroke is missing from the reference", fixture.moved in ids)
        assertTrue("the restyled stroke is missing from the reference", fixture.restyled in ids)
        assertTrue("the erased stroke is missing from the reference", fixture.erased in ids)
        assertFalse("the reference sampled a highlighter", fixture.highlighter in ids)
        assertFalse("the reference sampled a text box", fixture.textBox in ids)
        assertFalse("the reference sampled an unreadable blob", fixture.damaged in ids)
        assertTrue("the reference's order was the arrival order, so order is untested", ids != fixture.arrivalOrder)

        assertEquals(expected, actual)
    }

    /** What `InkStrokeSampler.sample` did before it stopped building a store — kept verbatim. */
    private fun storeWalk(page: FoldedPage): List<SampledStroke> {
        val store = StrokeStore()
        store.applyOps(
            ops = page.objects.map { PageOp.AddObject(opId = "", clock = LamportClock(0, ""), obj = it) },
            brushes = factory,
            record = false,
        )
        val result = ArrayList<SampledStroke>(store.size)
        val scratch = StrokeInput()
        var clock = 0L
        store.forEachStrokeInRenderOrder(visibleBounds = null) { dry ->
            if (dry.kind.drawsOverInk) return@forEachStrokeInRenderOrder
            val inputs = dry.stroke.inputs
            if (inputs.size == 0) return@forEachStrokeInRenderOrder
            val samples = ArrayList<InkSample>(inputs.size)
            for (index in 0 until inputs.size) {
                inputs.populate(index, scratch)
                val point = dry.transform.map(PagePoint(scratch.x, scratch.y))
                samples += InkSample(point.x, point.y, clock + scratch.elapsedTimeMillis)
            }
            clock = samples.last().tMillis + PEN_LIFT_MILLIS
            val bounds = PageRect.around(samples.map { PagePoint(it.x, it.y) }) ?: return@forEachStrokeInRenderOrder
            result += SampledStroke(id = dry.id, samples = samples, bounds = bounds)
        }
        return result
    }

    private class MixedPage(
        val page: FoldedPage,
        val arrivalOrder: List<ObjectId>,
        val moved: ObjectId,
        val restyled: ObjectId,
        val erased: ObjectId,
        val highlighter: ObjectId,
        val textBox: ObjectId,
        val damaged: ObjectId,
    )

    /** Ops for a page, with their clocks handed out in order. */
    private class OpLog {
        val ops = ArrayList<PageOp>()
        private var counter = 0L

        fun next(): LamportClock = LamportClock(++counter, DEVICE)
    }

    private fun mixedPage(): MixedPage {
        val log = OpLog()
        // `SyntheticInk` makes every twentieth stroke a highlighter, starting with the first.
        val strokes = SyntheticInk.generate(PENS, PageSize.Letter, factory, seed = SEED, pointsPerStroke = 40).items
        val arrival = ArrayList<ObjectId>()
        val pens = ArrayList<ObjectId>()
        var highlighter: ObjectId? = null
        var z = FractionalKey.Initial
        for ((index, pair) in strokes.withIndex()) {
            val id = ObjectId("stroke-%02d".format(index))
            if (index > 0) z = FractionalKey.between(z, null)
            val geometry = StrokeGeometryCodec.encode(pair.first, pair.second, InkInputModel.SPRING)
            log.ops += PageOp.AddObject("add-$index", log.next(), StrokeObject(id = id, geometry = geometry, z = z))
            arrival += id
            if (pair.second == InkBrushKind.HIGHLIGHTER) highlighter = id
            if (pair.second != InkBrushKind.HIGHLIGHTER) pens += id
        }
        addRegisters(log, moved = pens[1], restyled = pens[2], erased = pens[3], sunk = pens[4])

        val above = FractionalKey.between(z, null)
        val textBox = ObjectId("text")
        val text = TextBoxObject(id = textBox, text = "words", widthPt = 60f, heightPt = 14f, z = above)
        log.ops += PageOp.AddObject("text", log.next(), text)
        val damaged = ObjectId("damaged")
        val unreadable = StrokeObject(id = damaged, geometry = StrokeGeometry(ByteArray(DAMAGED_BYTES)), z = above)
        log.ops += PageOp.AddObject("damaged", log.next(), unreadable)

        return MixedPage(
            page = foldPage(PageId("page"), log.ops),
            arrivalOrder = arrival.filter { it != highlighter },
            moved = pens[1],
            restyled = pens[2],
            erased = pens[3],
            highlighter = checkNotNull(highlighter) { "the fixture produced no highlighter" },
            textBox = textBox,
            damaged = damaged,
        )
    }

    /** A move with a rotation, a restyle, an erase, and one stroke sunk below all the others. */
    private fun addRegisters(log: OpLog, moved: ObjectId, restyled: ObjectId, erased: ObjectId, sunk: ObjectId) {
        val placement = Matrix3.translate(MOVE_X, MOVE_Y) * Matrix3.rotate(ROTATION)
        log.ops += PageOp.SetTransform("move", log.next(), moved, placement)
        log.ops += PageOp.SetStyle("style", log.next(), restyled, ObjectStyle(RESTYLE_COLOR, 6f))
        val band = EraseCapsule(0f, 0f, PageSize.Letter.widthPt, PageSize.Letter.heightPt, 30f)
        log.ops += PageOp.SetErasedRegion("erase", log.next(), erased, ErasedArea(listOf(band)))
        // Below everything: the store's `(z, id)` order must now disagree with the arrival order.
        log.ops += PageOp.SetZOrder("sink", log.next(), sunk, FractionalKey.between(null, FractionalKey.Initial))
    }

    private companion object {
        const val POINTS_PER_INCH = 72f
        const val SEED = 11
        const val PENS = 8
        const val DEVICE = "device-a"
        const val PEN_LIFT_MILLIS = 60L
        const val MOVE_X = 40f
        const val MOVE_Y = -25f
        const val ROTATION = 0.3f
        const val RESTYLE_COLOR = 0xFF1D4ED8.toInt()
        const val DAMAGED_BYTES = 64
    }
}
