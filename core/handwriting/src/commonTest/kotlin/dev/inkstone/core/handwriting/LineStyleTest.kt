package dev.inkstone.core.handwriting

import dev.inkstone.core.model.InkSample
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.SampledStroke
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * docs/HANDWRITING-SYNTHESIS.md §5.2. ⚠️ These lines are built, so they prove the *arithmetic* —
 * that what a fixture was drawn with comes back out. Whether the estimator is right about a real
 * hand is phase 2's check on real ink (docs §12, H2); M7 learned what a synthetic fixture can hide.
 */
class LineStyleTest {

    @Test
    fun `a frame round trips`() {
        val frame = NormalFrame(originX = 120f, originY = 300f, angleRad = 0.05f, slantRad = 0.2f, xHeightPt = 7f)
        val page = FloatArray(2)
        val back = FloatArray(2)
        for ((u, v) in listOf(0f to 0f, 1f to 1f, -2f to 0.5f, 3.5f to -0.8f)) {
            frame.toPage(u, v, page)
            frame.toNormal(page[0], page[1], back)
            assertTrue(
                abs(back[0] - u) < EPSILON && abs(back[1] - v) < EPSILON,
                "($u, $v) came back as (${back[0]}, ${back[1]})",
            )
        }
        // Up is up: a point one x-height above the origin is at smaller y on the page.
        frame.toPage(0f, 1f, page)
        assertTrue(page[1] < 300f)
    }

    @Test
    fun `the baseline slant and x height of a line come back out`() {
        val frame = NormalFrame(originX = 50f, originY = 400f, angleRad = 0.04f, slantRad = 0.25f, xHeightPt = 6f)
        val line = writeLine(frame, "nmlnunpnhn")

        val style = assertNotNull(LineStyles.measure(line))

        assertTrue(abs(style.slope - kotlin.math.tan(0.04f)) < SLOPE_TOLERANCE, "slope ${style.slope}")
        assertTrue(abs(style.xHeightPt - 6f) < 6f * HEIGHT_TOLERANCE, "x-height ${style.xHeightPt}")
        assertTrue(abs(style.slantRad - 0.25f) < SLANT_TOLERANCE, "slant ${style.slantRad}")
        assertTrue(
            abs(style.baselineY(50f) - 400f) < 6f * HEIGHT_TOLERANCE,
            "baseline at origin ${style.baselineY(50f)}",
        )
    }

    @Test
    fun `descenders do not drag the baseline down and ascenders do not lift the x height`() {
        // Half the letters reach above or below: the medians have to come from the turning points
        // and the split, not from the extremes.
        val frame = NormalFrame(originX = 0f, originY = 100f, angleRad = 0f, slantRad = 0f, xHeightPt = 5f)
        val line = writeLine(frame, "lplplpnn")

        val style = assertNotNull(LineStyles.measure(line))

        assertTrue(abs(style.baselineY(40f) - 100f) < 5f * HEIGHT_TOLERANCE, "baseline ${style.baselineY(40f)}")
        assertTrue(abs(style.xHeightPt - 5f) < 5f * HEIGHT_TOLERANCE, "x-height ${style.xHeightPt}")
        assertEquals(0f, style.slope, SLOPE_TOLERANCE)
    }

    @Test
    fun `a page knows its pitch and margin`() {
        val lines = (0 until 3).map { index ->
            val frame =
                NormalFrame(originX = 72f, originY = 100f + index * 24f, angleRad = 0f, slantRad = 0f, xHeightPt = 5f)
            assertNotNull(LineStyles.measure(writeLine(frame, "nnnn")))
        }
        val page = PageStyle.of(lines.reversed())

        assertEquals(24f, assertNotNull(page.linePitchPt), 0.5f)
        assertEquals(72f, assertNotNull(page.leftMarginPt), 1f)
        assertEquals(5f, assertNotNull(page.xHeightPt), 5f * HEIGHT_TOLERANCE)
    }

    /**
     * One stroke per letter, drawn in the normalised frame and put on the page through [frame]:
     * `n`/`m`/`u` hump between the baseline and the x-height, `l`/`h` rise to twice it, `p` falls
     * below the baseline.
     */
    private fun writeLine(frame: NormalFrame, letters: String): List<SampledStroke> =
        letters.mapIndexed { index, letter ->
            val left = index * LETTER_PITCH
            val path: List<Pair<Float, Float>> = when (letter) {
                'l', 'h' -> listOf(0f to 0f, 0.2f to 2f, 0.4f to 0f, 0.7f to 1f, 0.9f to 0f)
                'p' -> listOf(0.1f to 1f, 0.1f to -0.8f, 0.1f to 1f, 0.6f to 0.5f, 0.8f to 1f, 0.9f to 0f)
                'm' -> listOf(0f to 0f, 0.1f to 1f, 0.3f to 0f, 0.45f to 1f, 0.6f to 0f, 0.75f to 1f, 0.9f to 0f)
                else -> listOf(0f to 0f, 0.15f to 1f, 0.45f to 0f, 0.65f to 1f, 0.9f to 0f)
            }
            stroke("$index$letter", frame, path.map { (u, v) -> (left + u) to v })
        }

    /** Each segment of [path] subdivided, so slant and turning points have real samples to read. */
    private fun stroke(id: String, frame: NormalFrame, path: List<Pair<Float, Float>>): SampledStroke {
        val out = FloatArray(2)
        val samples = ArrayList<InkSample>()
        var t = 0L
        for ((a, b) in path.zipWithNext()) {
            for (step in 0 until STEPS) {
                val f = step / STEPS.toFloat()
                frame.toPage(a.first + (b.first - a.first) * f, a.second + (b.second - a.second) * f, out)
                samples += InkSample(out[0], out[1], t)
                t += 4
            }
        }
        val last = path.last()
        frame.toPage(last.first, last.second, out)
        samples += InkSample(out[0], out[1], t)
        val bounds = assertNotNull(PageRect.around(samples.map { PagePoint(it.x, it.y) }))
        return SampledStroke(ObjectId(id), samples, bounds)
    }

    private companion object {
        const val EPSILON = 1e-3f
        const val STEPS = 8
        const val LETTER_PITCH = 1.1f
        const val SLOPE_TOLERANCE = 0.01f
        const val HEIGHT_TOLERANCE = 0.12f
        const val SLANT_TOLERANCE = 0.06f
    }
}
