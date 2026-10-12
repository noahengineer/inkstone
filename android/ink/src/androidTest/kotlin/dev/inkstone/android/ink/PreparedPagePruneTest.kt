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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ⭐ A page load decides which strokes are erased away **before** the store sees them.
 *
 * `InkSurfaceView.finishPageLoad` used to call `StrokeStore.pruneFullyErased` on the UI thread
 * right after `install`, rationed by an estimate 56–101× the real work — on a real note it pruned
 * two to six of ~555 fully erased strokes and left the rest paying a `saveLayer` each on every
 * re-record. `PreparedPage.of` now runs the metered coverage test off the main thread, and
 * `install` parks what it found without drawing it.
 *
 * Instrumented because deciding coverage needs a tessellated `Stroke`. ⚠️ **Not run on a device
 * when it was written** — the scheduling it relies on is covered on the JVM by `ErasePruneTest`,
 * and this is the half that needs real ink: that the load path hands the right objects to it.
 */
@RunWith(AndroidJUnit4::class)
class PreparedPagePruneTest {

    private lateinit var factory: InkBrushFactory

    private val page = PageSize.Letter

    @Before
    fun setUp() {
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        factory = InkBrushFactory(metrics.density, metrics.densityDpi / POINTS_PER_INCH)
    }

    @Test
    fun aFullyErasedStrokeIsDecidedAtLoadAndNeverDrawn() {
        val fixture = Fixture()
        val prepared = PreparedPage.of(fixture.folded(), factory)

        assertEquals("the covered stroke was not decided at load", listOf(fixture.covered), prepared.erasedAway.map { it.id })
        assertTrue("a page like this must not leave work for the UI thread", prepared.undecided.isEmpty())
        assertTrue("an erased-away stroke is still in the drawn list", prepared.objects.none { it.id == fixture.covered })

        val store = StrokeStore()
        store.install(prepared, factory)

        val drawn = ArrayList<ObjectId>()
        store.forEachInRenderOrder(null) { drawn += it.id }
        assertEquals(listOf(fixture.visible, fixture.partial), drawn)
        assertEquals("the load left candidates for the UI thread", 0, store.pendingPruneCount)
    }

    /** Parked, not dropped: an undo of the erase must put it back on screen. */
    @Test
    fun anUnEraseAfterLoadBringsTheStrokeBack() {
        val fixture = Fixture()
        val store = StrokeStore()
        store.install(PreparedPage.of(fixture.folded(), factory), factory)
        assertEquals(2, store.size)

        store.applyOps(
            listOf(PageOp.SetErasedRegion("undo", LamportClock(CLOCK_UNDO, DEVICE), fixture.covered, ErasedArea.None)),
            factory,
        )

        assertEquals("the un-erase did not reach a stroke the load parked", 3, store.size)
        assertTrue(store.objectOf(fixture.covered) != null)
    }

    /**
     * A parked stroke's z key is still on the page, so the next stroke must land above it — exactly
     * as it did when the load inserted it and the UI thread pruned it afterwards.
     */
    @Test
    fun theNextStrokeLandsAboveAStrokeTheLoadParked() {
        val fixture = Fixture(coveredOnTop = true)
        val store = StrokeStore()
        store.install(PreparedPage.of(fixture.folded(), factory), factory)

        val next = store.allocateTopZ(1).single()

        assertTrue("the next key $next is not above the parked stroke's ${fixture.topZ}", next > fixture.topZ)
    }

    /**
     * Three strokes: one covered completely by its erased region, one erased only in part, one
     * untouched — folded from ops, as the editor hands a page to the surface.
     */
    private inner class Fixture(coveredOnTop: Boolean = false) {
        private val strokes = SyntheticInk.generate(STROKES, page, factory, seed = SEED, pointsPerStroke = 60).items
            .filter { it.second != InkBrushKind.HIGHLIGHTER }
            .take(3)
        val covered = ObjectId("covered")
        val partial = ObjectId("partial")
        val visible = ObjectId("visible")
        private val keys = run {
            val first = FractionalKey.between(null, null)
            val second = FractionalKey.between(first, null)
            val third = FractionalKey.between(second, null)
            listOf(first, second, third)
        }
        private val zCovered = if (coveredOnTop) keys[2] else keys[0]
        private val zVisible = if (coveredOnTop) keys[0] else keys[1]
        private val zPartial = if (coveredOnTop) keys[1] else keys[2]
        val topZ: FractionalKey = keys[2]

        fun folded() = foldPage(
            PageId("page"),
            listOf(
                add(covered, 0, zCovered, clock = 1),
                add(visible, 1, zVisible, clock = 2),
                add(partial, 2, zPartial, clock = 3),
                erase(covered, wholeOf(0), clock = 4),
                erase(partial, dabOn(2), clock = 5),
            ),
        )

        private fun add(id: ObjectId, index: Int, z: FractionalKey, clock: Long) = PageOp.AddObject(
            opId = "add-${id.value}",
            clock = LamportClock(clock, DEVICE),
            obj = StrokeObject(
                id = id,
                geometry = StrokeGeometryCodec.encode(strokes[index].first, strokes[index].second, InkInputModel.SPRING),
                z = z,
            ),
        )

        private fun erase(id: ObjectId, area: ErasedArea, clock: Long) =
            PageOp.SetErasedRegion("erase-${id.value}", LamportClock(clock, DEVICE), id, area)

        /** One capsule far wider than the stroke, along its whole diagonal. */
        private fun wholeOf(index: Int): ErasedArea {
            val box = checkNotNull(strokes[index].first.shape.computeBoundingBox())
            val sweep = EraseCapsule(
                fromX = box.xMin - WIDE_PT,
                fromY = box.yMin - WIDE_PT,
                toX = box.xMax + WIDE_PT,
                toY = box.yMax + WIDE_PT,
                radius = WIDE_PT,
            )
            return ErasedArea(listOf(sweep))
        }

        /** A small dab on the stroke's middle sample — erased, visibly not away. */
        private fun dabOn(index: Int): ErasedArea {
            val inputs = strokes[index].first.inputs
            val middle = inputs.get(inputs.size / 2)
            return ErasedArea(listOf(EraseCapsule(middle.x, middle.y, middle.x, middle.y, DAB_PT)))
        }
    }

    private companion object {
        const val POINTS_PER_INCH = 72f
        const val SEED = 3
        const val STROKES = 8
        const val WIDE_PT = 60f
        const val DAB_PT = 3f
        const val DEVICE = "device-a"
        const val CLOCK_UNDO = 10L
    }
}
