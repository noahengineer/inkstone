package dev.inkstone.core.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ROADMAP M8b — the predicate that decides whether an eraser bites a shape, and whether a tap
 * selects one.
 *
 * ⚠️ **One predicate for both**, which is the point of testing it here rather than through either
 * caller: a shape you can erase but cannot select, or the reverse, is the kind of inconsistency
 * nobody would ever trace back to two nearly-identical hit tests.
 */
class ShapeHitTest {

    private val box = ShapeGeometry.outline(ShapeKind.RECTANGLE, 100f, 60f)
    private val line = ShapeGeometry.outline(ShapeKind.LINE, 100f, 2f)

    @Test
    fun `an eraser crossing an edge hits`() {
        assertTrue(sweep(box, filled = false, fromX = -10f, fromY = 30f, toX = 10f, toY = 30f))
    }

    @Test
    fun `an eraser near but not on an edge misses`() {
        assertFalse(sweep(box, filled = false, fromX = -30f, fromY = 30f, toX = -20f, toY = 30f))
    }

    @Test
    fun `an eraser through the middle of a hollow shape passes straight through`() {
        // ⭐ Deliberate: an unfilled box is empty, and erasing *through* it must not touch it.
        assertFalse(sweep(box, filled = false, fromX = 30f, fromY = 30f, toX = 70f, toY = 30f))
    }

    @Test
    fun `an eraser through the middle of a filled shape hits`() {
        // ⭐ And the same sweep must hit once there is paint there. Without this, dragging an
        // eraser across solid colour would do nothing at all.
        assertTrue(sweep(box, filled = true, fromX = 30f, fromY = 30f, toX = 70f, toY = 30f))
    }

    @Test
    fun `a dab inside a filled shape hits even though no edge is near`() {
        assertTrue(sweep(box, filled = true, fromX = 50f, fromY = 30f, toX = 50f, toY = 30f, radius = 1f))
    }

    @Test
    fun `the pen width counts as reach`() {
        // The outline is a mathematical curve; the ink on it is not. A sweep that misses the
        // curve by less than half the line width is still on the line the user can see.
        assertFalse(sweep(line, filled = false, fromX = 50f, fromY = 6f, toX = 50f, toY = 6f, radius = 1f))
        assertTrue(sweep(line, filled = false, fromX = 50f, fromY = 6f, toX = 50f, toY = 6f, radius = 1f, slop = 5f))
    }

    @Test
    fun `an open path is never treated as enclosing anything`() {
        // A line has no interior whatever `filled` says, because `ShapePath.closed` is false.
        assertFalse(sweep(line, filled = true, fromX = 50f, fromY = 40f, toX = 50f, toY = 45f))
    }

    @Test
    fun `the tap test agrees with the eraser`() {
        // Same predicate, so "selectable" and "erasable" cannot drift apart.
        assertTrue(ShapeHit.hitsPoint(box, filled = false, x = 0f, y = 30f, reach = 2f))
        assertFalse(ShapeHit.hitsPoint(box, filled = false, x = 50f, y = 30f, reach = 2f))
        assertTrue(ShapeHit.hitsPoint(box, filled = true, x = 50f, y = 30f, reach = 2f))
    }

    @Test
    fun `containment uses the same rule as the lasso`() {
        // Both are even-odd ray casting with the same half-open edge test — a point one calls
        // inside and the other calls outside is a shape you can lasso but cannot tap.
        val polygon = box.flattened()
        val inside = PagePoint(50f, 30f)
        assertEquals(
            Lasso.freehand(polygon).contains(inside.x, inside.y),
            ShapeHit.contains(polygon, inside.x, inside.y),
        )
    }

    @Suppress("LongParameterList") // A capsule is four coordinates and a radius.
    private fun sweep(
        path: ShapePath,
        filled: Boolean,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        radius: Float = 2f,
        slop: Float = 0f,
    ): Boolean = ShapeHit.hitsCapsule(path, filled, fromX, fromY, toX, toY, radius, slop)

    private fun assertEquals(expected: Boolean, actual: Boolean) {
        assertTrue(expected == actual, "expected $expected but was $actual")
    }
}
