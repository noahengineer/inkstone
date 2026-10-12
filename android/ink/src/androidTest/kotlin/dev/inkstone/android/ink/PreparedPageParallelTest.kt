package dev.inkstone.android.ink

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inkstone.core.model.EraseCapsule
import dev.inkstone.core.model.ErasedArea
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.foldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ⭐ `PreparedPage.build` — the page built across `Dispatchers.Default`'s threads — is the page
 * `PreparedPage.of` builds on one.
 *
 * The parallel build exists for the physics note's open, where building 3,903 strokes one after
 * another on one core was most of the wait (docs/IMAGES-PERF-2026-10.md §3). What it must not do is
 * change *what* gets built: the objects' order is the `(z, id)` tie-break's input order, and the
 * erased-away decision decides what is drawn. So this compares the two builds of one page object by
 * object — ids, order, bounds and the erase decisions — on a page large enough to fan out.
 *
 * Instrumented because building a stroke needs `androidx.ink`'s native tessellation.
 */
@RunWith(AndroidJUnit4::class)
class PreparedPageParallelTest {

    private lateinit var factory: InkBrushFactory

    private val page = PageSize.Letter

    @Before
    fun setUp() {
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        factory = InkBrushFactory(metrics.density, metrics.densityDpi / POINTS_PER_INCH)
    }

    @Test
    fun aParallelBuildIsTheSequentialBuildObjectForObject() {
        val folded = folded()

        val sequential = PreparedPage.of(folded, factory)
        val parallel = runBlocking(Dispatchers.Default) { PreparedPage.build(folded, factory) }

        assertTrue("the fixture must be big enough to fan out", folded.objects.size > PARALLEL_SIZE)
        assertEquals(sequential.objects.map { it.id }, parallel.objects.map { it.id })
        // `PageBounds` is not a data class, so its four numbers are what get compared.
        assertEquals(sequential.objects.map { it.boxOrNull() }, parallel.objects.map { it.boxOrNull() })
        assertEquals(sequential.erasedAway.map { it.id }, parallel.erasedAway.map { it.id })
        assertEquals(sequential.undecided.map { it.id }, parallel.undecided.map { it.id })
        assertEquals(sequential.registers, parallel.registers)
        assertTrue("every tenth stroke is erased away, so some must be", parallel.erasedAway.isNotEmpty())
    }

    @Test
    fun aParallelBuildInstallsTheSameRenderOrder() {
        val folded = folded()
        val sequential = StrokeStore().apply { install(PreparedPage.of(folded, factory), factory) }
        val parallel = StrokeStore().apply {
            install(runBlocking(Dispatchers.Default) { PreparedPage.build(folded, factory) }, factory)
        }

        val first = ArrayList<ObjectId>()
        val second = ArrayList<ObjectId>()
        sequential.forEachInRenderOrder(null) { first += it.id }
        parallel.forEachInRenderOrder(null) { second += it.id }
        assertEquals(first, second)
    }

    private fun DryObject.boxOrNull(): List<Float>? = bounds?.let { listOf(it.xMin, it.yMin, it.xMax, it.yMax) }

    /**
     * [STROKES] strokes stacked by key, every tenth erased away completely and every seventh dabbed —
     * folded from ops, as the editor hands a page to the surface.
     */
    private fun folded() = foldPage(PageId("page"), ops())

    private fun ops(): List<PageOp> {
        val strokes = SyntheticInk.generate(STROKES, page, factory, seed = SEED, pointsPerStroke = 40).items
        val ops = ArrayList<PageOp>()
        var z: FractionalKey? = null
        var clock = 0L
        strokes.forEachIndexed { index, (stroke, kind) ->
            val id = ObjectId("s$index")
            z = FractionalKey.between(z, null)
            ops += PageOp.AddObject(
                opId = "add-$index",
                clock = LamportClock(++clock, DEVICE),
                obj = StrokeObject(id = id, geometry = StrokeGeometryCodec.encode(stroke, kind, InkInputModel.SPRING), z = checkNotNull(z)),
            )
            val box = stroke.shape.computeBoundingBox()
            if (box != null && index % ERASED_AWAY_EVERY == 0) {
                val whole = EraseCapsule(box.xMin - WIDE_PT, box.yMin - WIDE_PT, box.xMax + WIDE_PT, box.yMax + WIDE_PT, WIDE_PT)
                ops += PageOp.SetErasedRegion("erase-$index", LamportClock(++clock, DEVICE), id, ErasedArea(listOf(whole)))
            } else if (index % DABBED_EVERY == 0) {
                val middle = stroke.inputs.get(stroke.inputs.size / 2)
                val dab = EraseCapsule(middle.x, middle.y, middle.x, middle.y, DAB_PT)
                ops += PageOp.SetErasedRegion("dab-$index", LamportClock(++clock, DEVICE), id, ErasedArea(listOf(dab)))
            }
        }
        return ops
    }

    private companion object {
        const val POINTS_PER_INCH = 72f
        const val SEED = 11
        const val STROKES = 600

        /** `PreparedPage`'s own threshold is 256; the fixture must be well over it. */
        const val PARALLEL_SIZE = 256
        const val ERASED_AWAY_EVERY = 10
        const val DABBED_EVERY = 7
        const val WIDE_PT = 60f
        const val DAB_PT = 3f
        const val DEVICE = "device-a"
    }
}
