package dev.inkstone.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ROADMAP M8b — shape outlines, placement, and draw-and-hold classification.
 *
 * ⚠️ **What these tests can and cannot establish.** They prove the arithmetic: that a perfect
 * ellipse classifies as an ellipse, that a horizontal line gets an invertible matrix, that an
 * outline closes. They prove **nothing** about whether a person's hand-drawn rectangle snaps, or
 * whether the feature fights someone trying to draw a curve. M7's rotated-text detector passed its
 * unit test and produced 105 false positives on real pages, for exactly this reason — *"a synthetic
 * fixture is the worst possible witness for a geometric heuristic"*. The acceptance criterion
 * *"shape snapping is predictable"* is settled on the tablet, and nowhere else.
 */
class ShapesTest {

    // -----------------------------------------------------------------------
    // Outlines
    // -----------------------------------------------------------------------

    @Test
    fun `a rectangle outline closes and covers its box`() {
        val path = ShapeGeometry.outline(ShapeKind.RECTANGLE, 100f, 60f)
        assertTrue(path.closed)
        val points = path.flattened()
        assertEquals(0f, points.minOf { it.x })
        assertEquals(100f, points.maxOf { it.x })
        assertEquals(0f, points.minOf { it.y })
        assertEquals(60f, points.maxOf { it.y })
    }

    @Test
    fun `a corner radius larger than the box degrades to a stadium rather than self-intersecting`() {
        val path = ShapeGeometry.outline(ShapeKind.RECTANGLE, 100f, 40f, cornerRadiusPt = 500f)
        val points = path.flattened()
        assertTrue(points.all { it.x in -TOLERANCE..(100f + TOLERANCE) })
        assertTrue(points.all { it.y in -TOLERANCE..(40f + TOLERANCE) })
    }

    @Test
    fun `an ellipse stays inside its box and touches all four edges`() {
        val points = ShapeGeometry.outline(ShapeKind.ELLIPSE, 200f, 100f).flattened(samplesPerCurve = 32)
        assertClose(0f, points.minOf { it.x })
        assertClose(200f, points.maxOf { it.x })
        assertClose(0f, points.minOf { it.y })
        assertClose(100f, points.maxOf { it.y })
    }

    @Test
    fun `a line is a segment through the middle of its band and not the box diagonal`() {
        // The diagonal is the reading that makes an axis-aligned line unrepresentable — see
        // ShapeEdit.placeSegment.
        val path = ShapeGeometry.outline(ShapeKind.LINE, 80f, 4f)
        assertTrue(!path.closed)
        assertEquals(listOf(PagePoint(0f, 2f), PagePoint(80f, 2f)), path.flattened())
    }

    @Test
    fun `only a rectangle rounds and only a closed shape fills`() {
        assertTrue(ShapeGeometry.roundsCorners(ShapeKind.RECTANGLE))
        assertTrue(!ShapeGeometry.roundsCorners(ShapeKind.ELLIPSE))
        assertTrue(ShapeGeometry.isFillable(ShapeKind.TRIANGLE))
        assertTrue(!ShapeGeometry.isFillable(ShapeKind.ARROW))
    }

    // -----------------------------------------------------------------------
    // Boundaries — what an auto anchor lands on
    // -----------------------------------------------------------------------

    @Test
    fun `a rectangle boundary lands on the edge facing the target`() {
        val right = ShapeGeometry.boundaryToward(ShapeKind.RECTANGLE, 100f, 100f, PagePoint(500f, 50f))
        assertClose(100f, right.x)
        assertClose(50f, right.y)

        val up = ShapeGeometry.boundaryToward(ShapeKind.RECTANGLE, 100f, 100f, PagePoint(50f, -500f))
        assertClose(50f, up.x)
        assertClose(0f, up.y)
    }

    @Test
    fun `an ellipse boundary lies on the ellipse`() {
        val point = ShapeGeometry.boundaryToward(ShapeKind.ELLIPSE, 200f, 100f, PagePoint(1000f, 1000f))
        val nx = (point.x - 100f) / 100f
        val ny = (point.y - 50f) / 50f
        assertClose(1f, nx * nx + ny * ny, "on the unit circle in normalised coordinates")
    }

    @Test
    fun `a degenerate box answers its centre rather than NaN`() {
        // A NaN here would propagate into a matrix and out to disk.
        val point = ShapeGeometry.boundaryToward(ShapeKind.RECTANGLE, 0f, 0f, PagePoint(10f, 10f))
        assertTrue(point.x.isFinite() && point.y.isFinite())
    }

    // -----------------------------------------------------------------------
    // Placement
    // -----------------------------------------------------------------------

    @Test
    fun `a horizontal line gets an invertible matrix`() {
        // ⭐ The case that decides the local geometry. A diagonal-in-the-box line would need a
        // zero y-scale here, which `SelectionEdit.transform` correctly refuses to write and which
        // nothing could then move or erase.
        val placement = ShapeEdit.placeSegment(100f, 200f, 400f, 200f)
        assertTrue(!placement.transform.isDegenerate())
        assertNotNull(placement.transform.inverse())
        assertEndpoints(placement, PagePoint(100f, 200f), PagePoint(400f, 200f))
    }

    @Test
    fun `a vertical line gets an invertible matrix`() {
        val placement = ShapeEdit.placeSegment(50f, 50f, 50f, 300f)
        assertTrue(!placement.transform.isDegenerate())
        assertEndpoints(placement, PagePoint(50f, 50f), PagePoint(50f, 300f))
    }

    @Test
    fun `a line at an angle keeps both endpoints`() {
        val placement = ShapeEdit.placeSegment(10f, 10f, 110f, 60f)
        assertEndpoints(placement, PagePoint(10f, 10f), PagePoint(110f, 60f))
    }

    @Test
    fun `a zero-size drag still produces a usable shape`() {
        val placement = ShapeEdit.place(ShapeKind.RECTANGLE, PageRect(10f, 10f, 10f, 10f))
        assertTrue(placement.widthPt >= ShapeGeometry.MIN_EXTENT_PT)
        assertTrue(placement.heightPt >= ShapeGeometry.MIN_EXTENT_PT)
        assertNotNull(placement.transform.inverse())
    }

    // -----------------------------------------------------------------------
    // ShapeEdit — the ops
    // -----------------------------------------------------------------------

    @Test
    fun `creating a plain shape writes one op and creating a connector writes two`() {
        val plain = ShapeEdit.create(
            id = ObjectId("obj-1"),
            placement = ShapePlacement(ShapeKind.RECTANGLE, 10f, 10f, Matrix3.Identity),
            z = FractionalKey("m"),
            nextOpId = ::nextId,
            nextClock = ::nextClock,
        )
        assertEquals(1, plain.size)
        assertTrue(plain.single() is PageOp.AddObject)

        val connector = ShapeEdit.create(
            id = ObjectId("obj-2"),
            placement = ShapePlacement(ShapeKind.ARROW, 10f, 1f, Matrix3.Identity),
            z = FractionalKey("n"),
            record = ShapeRecord(endA = ConnectorEnd.Attached(ObjectId("obj-1"))),
            nextOpId = ::nextId,
            nextClock = ::nextClock,
        )
        assertEquals(2, connector.size)
        assertTrue(connector[0] is PageOp.AddObject)
        assertTrue(connector[1] is PageOp.SetTextContent)
    }

    // -----------------------------------------------------------------------
    // Draw-and-hold classification
    // -----------------------------------------------------------------------

    @Test
    fun `a straight gesture is a line`() {
        val candidate = assertNotNull(ShapeSnap.recognise(line(0f, 0f, 300f, 120f)))
        assertEquals(ShapeKind.LINE, candidate.kind)
        assertClose(0f, candidate.from.x)
        assertClose(300f, candidate.to.x)
    }

    @Test
    fun `a drawn box is a rectangle`() {
        assertEquals(ShapeKind.RECTANGLE, assertNotNull(ShapeSnap.recognise(rectangle(200f, 120f))).kind)
    }

    @Test
    fun `a drawn oval is an ellipse`() {
        assertEquals(ShapeKind.ELLIPSE, assertNotNull(ShapeSnap.recognise(ellipse(200f, 120f))).kind)
    }

    @Test
    fun `a drawn triangle is a triangle`() {
        assertEquals(ShapeKind.TRIANGLE, assertNotNull(ShapeSnap.recognise(triangle(200f, 120f))).kind)
    }

    @Test
    fun `a wandering curve snaps to nothing`() {
        // ⭐ The acceptance criterion: "no fighting it to draw a freehand curve". A gesture that
        // matches nothing well must leave the ink alone.
        val squiggle = (0..60).map { step ->
            val t = step / 10f
            PagePoint(t * 20f, 40f * sin(t * 2f) + 10f * sin(t * 7f))
        }
        assertNull(ShapeSnap.recognise(squiggle))
    }

    @Test
    fun `a tap snaps to nothing`() {
        assertNull(ShapeSnap.recognise(List(8) { PagePoint(10f, 10f) }))
        assertNull(ShapeSnap.recognise(line(0f, 0f, 3f, 0f)))
    }

    @Test
    fun `an arrow is never recognised`() {
        // An arrowhead is indistinguishable from a hooked pen-lift. It is a tool the user picks.
        val kinds = listOf(line(0f, 0f, 200f, 0f), rectangle(100f, 100f), ellipse(100f, 100f))
            .mapNotNull { ShapeSnap.recognise(it)?.kind }
        assertTrue(ShapeKind.ARROW !in kinds)
    }

    @Test
    fun `the allocation-free entry point agrees with the readable one`() {
        // The ink path calls the FloatArray form; every test above calls the list form. If they
        // could disagree, every assertion here would be about code the app does not run.
        val points = rectangle(150f, 90f)
        val xs = FloatArray(points.size) { points[it].x }
        val ys = FloatArray(points.size) { points[it].y }
        assertEquals(
            ShapeSnap.recognise(points)?.kind,
            ShapeSnap.recognise(xs, ys, points.size)?.kind,
        )
    }

    // -----------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------

    private var counter = 0

    private fun nextId(): String = "op-${counter++}"

    private fun nextClock(): LamportClock = LamportClock(counter.toLong(), "device")

    private fun assertEndpoints(placement: ShapePlacement, from: PagePoint, to: PagePoint) {
        val path = ShapeGeometry.outline(ShapeKind.LINE, placement.widthPt, placement.heightPt)
            .under(placement.transform)
            .flattened()
        assertClose(from.x, path.first().x)
        assertClose(from.y, path.first().y)
        assertClose(to.x, path.last().x)
        assertClose(to.y, path.last().y)
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float): List<PagePoint> =
        (0..STEPS).map { PagePoint(x0 + (x1 - x0) * it / STEPS, y0 + (y1 - y0) * it / STEPS) }

    private fun rectangle(w: Float, h: Float): List<PagePoint> = corners(
        listOf(PagePoint(0f, 0f), PagePoint(w, 0f), PagePoint(w, h), PagePoint(0f, h)),
    )

    private fun triangle(w: Float, h: Float): List<PagePoint> = corners(
        listOf(PagePoint(w / 2f, 0f), PagePoint(w, h), PagePoint(0f, h)),
    )

    private fun ellipse(w: Float, h: Float): List<PagePoint> = (0..STEPS).map { step ->
        val angle = 2f * PI.toFloat() * step / STEPS
        PagePoint(w / 2f + w / 2f * cos(angle), h / 2f + h / 2f * sin(angle))
    }

    /** A closed polygon walked corner to corner, sampled so the path length is realistic. */
    private fun corners(points: List<PagePoint>): List<PagePoint> {
        val walked = ArrayList<PagePoint>()
        for (index in points.indices) {
            val from = points[index]
            val to = points[(index + 1) % points.size]
            for (step in 0 until STEPS) {
                walked += PagePoint(
                    from.x + (to.x - from.x) * step / STEPS,
                    from.y + (to.y - from.y) * step / STEPS,
                )
            }
        }
        walked += points.first()
        return walked
    }

    private fun assertClose(expected: Float, actual: Float, message: String = "") {
        assertTrue(abs(expected - actual) < TOLERANCE, "$message expected $expected but was $actual")
    }

    private companion object {
        const val STEPS = 24
        const val TOLERANCE = 0.05f
    }
}
