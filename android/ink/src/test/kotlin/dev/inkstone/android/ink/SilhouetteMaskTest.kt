package dev.inkstone.android.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * ⭐ SPEC §4.9.6 — the filled-silhouette rule behind knockout, on plain grids.
 *
 * ⚠️ **What these cannot say**: that [PagePainter] draws the object into the picture this is handed,
 * at the scale it thinks, and turns the mask back into a `Path` in the object's own space. That is
 * `KnockoutRenderTest`, instrumented, which reads the rendered pixels back.
 */
class SilhouetteMaskTest {

    @Test
    fun `a closed ring hides its middle and not the paper round it`() {
        val picture = ring(gapDegrees = 0f)
        val mask = SilhouetteMask.of(picture, SIZE, SIZE, closeRadius = 2)

        assertTrue("the middle of the ring", mask.at(CENTRE, CENTRE))
        assertTrue("the ring itself", mask.at(CENTRE + RADIUS, CENTRE))
        assertFalse("well outside the ring", mask.at(4, 4))
        assertFalse("just outside the ring", mask.at(CENTRE + RADIUS + LINE + 3, CENTRE))
    }

    @Test
    fun `a loop that does not quite meet still counts as closed`() {
        // The ordinary hand-drawn circle: the pen lifts a few pixels short of where it began.
        val picture = ring(gapDegrees = 8f)
        assertTrue(SilhouetteMask.of(picture, SIZE, SIZE, closeRadius = 4).at(CENTRE, CENTRE))
    }

    @Test
    fun `a gap wider than the closing radius lets the outside in`() {
        // The other half of the rule: an open "C" is not a closed shape, and must not hide what is in
        // its mouth — otherwise every arc on the page would be a filled pie.
        val picture = ring(gapDegrees = 60f)
        assertFalse(SilhouetteMask.of(picture, SIZE, SIZE, closeRadius = 2).at(CENTRE, CENTRE))
    }

    @Test
    fun `a straight line hides a band no wider than itself`() {
        val picture = IntArray(SIZE * SIZE)
        for (y in 30..33) for (x in 10 until SIZE - 10) picture[y * SIZE + x] = OPAQUE
        val mask = SilhouetteMask.of(picture, SIZE, SIZE, closeRadius = 3)

        assertTrue(mask.at(CENTRE, 31))
        assertFalse(mask.at(CENTRE, 25))
        assertFalse(mask.at(CENTRE, 40))
    }

    @Test
    fun `a faint picture has the same silhouette as a solid one`() {
        // A threshold fixed at 50 % gave a 40 %-opacity picture no silhouette at all.
        val solid = filledSquare(OPAQUE)
        val faint = filledSquare(FAINT)
        val solidMask = SilhouetteMask.of(solid, SIZE, SIZE, closeRadius = 2)
        val faintMask = SilhouetteMask.of(faint, SIZE, SIZE, closeRadius = 2)

        assertTrue(faintMask.at(CENTRE, CENTRE))
        assertEquals(solidMask.count { it }, faintMask.count { it })
    }

    @Test
    fun `an empty picture has no silhouette`() {
        assertEquals(0, SilhouetteMask.of(IntArray(SIZE * SIZE), SIZE, SIZE, closeRadius = 3).count { it })
    }

    @Test
    fun `the silhouette never reaches past the ink`() {
        // ⭐ Step 4's extra pixel: every edge pixel of the silhouette is itself ink, so the object
        // drawn over its own hole covers the hole's staircase edge. A closed ring, because a gap the
        // closing step bridged is by definition paper that the silhouette now covers.
        val picture = ring(gapDegrees = 0f)
        val mask = SilhouetteMask.of(picture, SIZE, SIZE, closeRadius = 4)
        for (y in 1 until SIZE - 1) {
            for (x in 1 until SIZE - 1) {
                if (!mask.at(x, y)) continue
                val onEdge = !mask.at(x - 1, y) || !mask.at(x + 1, y) || !mask.at(x, y - 1) || !mask.at(x, y + 1)
                if (onEdge) assertTrue("edge pixel ($x, $y) is ink", picture[y * SIZE + x] >= OPAQUE / 2)
            }
        }
    }

    @Test
    fun `runs cover exactly the set pixels`() {
        val mask = SilhouetteMask.of(ring(gapDegrees = 0f), SIZE, SIZE, closeRadius = 2)
        var covered = 0
        SilhouetteMask.forEachRun(mask, SIZE, SIZE) { y, from, to ->
            for (x in from until to) assertTrue(mask.at(x, y))
            covered += to - from
        }
        assertEquals(mask.count { it }, covered)
    }

    private fun BooleanArray.at(x: Int, y: Int) = this[y * SIZE + x]

    /** A ring of [LINE]-pixel ink round the centre, missing [gapDegrees] of its circumference. */
    private fun ring(gapDegrees: Float): IntArray {
        val picture = IntArray(SIZE * SIZE)
        val steps = 2000
        for (step in 0 until steps) {
            val degrees = step * 360f / steps
            if (degrees < gapDegrees) continue
            val angle = degrees * PI / 180.0
            dab(picture, CENTRE + RADIUS * cos(angle), CENTRE + RADIUS * sin(angle))
        }
        return picture
    }

    /** One round pen dab, [LINE] pixels across, centred on `(cx, cy)`. */
    private fun dab(picture: IntArray, cx: Double, cy: Double) {
        for (dy in -LINE..LINE) {
            for (dx in -LINE..LINE) {
                val x = (cx + dx).roundToInt()
                val y = (cy + dy).roundToInt()
                val inside = hypot(dx.toDouble(), dy.toDouble()) <= LINE / 2.0
                if (inside && x in 0 until SIZE && y in 0 until SIZE) picture[y * SIZE + x] = OPAQUE
            }
        }
    }

    private fun filledSquare(alpha: Int): IntArray {
        val picture = IntArray(SIZE * SIZE)
        for (y in 20 until SIZE - 20) for (x in 20 until SIZE - 20) picture[y * SIZE + x] = alpha
        return picture
    }

    private companion object {
        const val SIZE = 96
        const val CENTRE = 48
        const val RADIUS = 30
        const val LINE = 4
        const val OPAQUE = 255
        const val FAINT = 100
    }
}
