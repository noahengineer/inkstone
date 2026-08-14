package dev.inkstone.app.note

import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⭐ ROADMAP M8c — the insert-time downscale, as arithmetic.
 *
 * `BitmapFactory` cannot run in a JVM unit test: with `isReturnDefaultValues` it answers null and a
 * zero-size `Options`, so a test written against the decoder would pass while asserting nothing.
 * That is why [ImageDownscale] holds every decision and `InsertedImage` holds only the decoder —
 * CLAUDE.md's definition-of-done #6, *"hoist the decision into a pure function over plain data so a
 * test can at least assert the decision was made"*.
 *
 * The claim under test is a claim about **bytes**: a 4,000 px tablet photograph must not end up in
 * a note's bundle, inside the published PDF, uploaded to the user's server on every sync, forever.
 */
class ImageDownscaleTest {

    @Test
    fun `a picture already inside the cap is not sampled at all`() {
        assertEquals(1, ImageDownscale.sampleSizeFor(1600, 1200))
        assertEquals(1f, ImageDownscale.scaleAfterSampling(1600, 1200))
    }

    /**
     * ⚠️ The case that makes the second step necessary. A 4,000 px photograph samples by **1** —
     * halving it would take the long edge to 2,000, which is *under* the cap and throws away detail
     * the cap allows — so without [ImageDownscale.scaleAfterSampling] a "2,048 px cap" is really a
     * 4,095 px cap.
     */
    @Test
    fun `sampling alone leaves a photograph above the cap and the exact scale finishes it`() {
        val sample = ImageDownscale.sampleSizeFor(4000, 3000)
        assertEquals(1, sample)
        val sampledWidth = 4000 / sample
        val scale = ImageDownscale.scaleAfterSampling(sampledWidth, 3000 / sample)
        assertTrue(scale < 1f, "a 4000 px photograph must still be scaled down, got $scale")
        assertEquals(ImageDownscale.MAX_LONG_EDGE_PX, (sampledWidth * scale).toInt())
    }

    @Test
    fun `a twelve megapixel photograph samples down to within one doubling of the cap`() {
        val sample = ImageDownscale.sampleSizeFor(4032, 3024)
        val longEdge = max(4032 / sample, 3024 / sample)
        assertTrue(longEdge >= ImageDownscale.MAX_LONG_EDGE_PX, "sampled to $longEdge, below the cap")
        assertTrue(longEdge < ImageDownscale.MAX_LONG_EDGE_PX * 2, "sampled to $longEdge, more than one doubling")
    }

    /** `inSampleSize` is honoured only for powers of two; any other value is silently rounded to 1. */
    @Test
    fun `every sample size is a power of two`() {
        for (width in listOf(1, 999, 2049, 4000, 8000, 16000, 40000)) {
            val sample = ImageDownscale.sampleSizeFor(width, width / 2)
            assertEquals(0, sample and (sample - 1), "sample $sample for width $width is not a power of two")
        }
    }

    @Test
    fun `a degenerate source does not divide by zero`() {
        assertEquals(1, ImageDownscale.sampleSizeFor(0, 0))
        assertEquals(1f, ImageDownscale.scaleAfterSampling(0, 0))
    }

    /**
     * The insert lands on what the user can **see**, not on the page.
     *
     * Centring on the page instead puts a picture off-screen for anybody zoomed into a corner,
     * which is indistinguishable from nothing having happened — definition-of-done #6 in its
     * mildest form.
     */
    @Test
    fun `an inserted picture is centred on the visible area`() {
        val visible = PageRect(100f, 400f, 300f, 600f)
        val box = ImageDownscale.placeOnPage(2000, 1000, PageSize.Letter, visible)
        assertEquals(visible.centreX, box.centreX, TOLERANCE)
        assertEquals(visible.centreY, box.centreY, TOLERANCE)
    }

    @Test
    fun `an inserted picture keeps its aspect ratio and fits the visible area`() {
        val visible = PageRect(0f, 0f, 612f, 792f)
        val box = ImageDownscale.placeOnPage(2000, 1000, PageSize.Letter, visible)
        assertEquals(2f, box.width / box.height, TOLERANCE)
        assertTrue(box.width <= visible.width, "wider than the viewport")
        assertTrue(box.height <= visible.height, "taller than the viewport")
    }

    /**
     * ⭐ A very tall picture is bounded by height rather than width, and **still keeps its ratio**.
     *
     * This is the test that found a real bug: the minimum-extent clamp was applied to width and
     * height independently, so a 1:8 photograph fitted into a short viewport came out narrower than
     * the minimum, was widened on its own, and landed as 1:4. Distorted at the moment of insertion,
     * silently, and only for the shapes and zooms that hit the clamp — the user can resize a
     * picture but cannot un-stretch one.
     */
    @Test
    fun `a tall picture is bounded by the height and is not distorted by the minimum extent`() {
        val visible = PageRect(0f, 0f, 612f, 200f)
        val box = ImageDownscale.placeOnPage(500, 4000, PageSize.Letter, visible)
        assertEquals(500f / 4000f, box.width / box.height, TOLERANCE)
    }

    @Test
    fun `a source with no pixels still produces a usable box`() {
        val box = ImageDownscale.placeOnPage(0, 0, PageSize.Letter, PageRect(0f, 0f, 612f, 792f))
        assertTrue(box.width > 0f && box.height > 0f, "a degenerate box cannot be grabbed or resized")
    }

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
