package dev.inkstone.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⭐ *"buttons for makeing it a perfect circle … or perfect triangle"*, as arithmetic.
 *
 * The claim this file exists for above all others is the one the first version got wrong:
 * **regularising a closed shape must not touch its matrix**, because `PagePainter` concatenates
 * that matrix onto the canvas and strokes with one width, so a non-uniform scale stretches the pen
 * — *"the line sizes get messed up"*. Everything else here is the geometry: regular to the
 * tolerance the outline actually uses, centred where it was, turned the way it was, and null when
 * there is nothing left to do.
 */
class ShapeRegularTest {

    // -----------------------------------------------------------------------
    // ⛔ The bug: the matrix is left alone
    // -----------------------------------------------------------------------

    @Test
    fun a_closed_shape_is_regularised_by_its_vertices_and_never_by_its_matrix() {
        val placed = ShapeEdit.place(ShapeKind.ELLIPSE, PageRect(10f, 20f, 90f, 60f))
        val edit = assertNotNull(regularise(ShapeKind.ELLIPSE, placed))
        assertNull(edit.transform, "a scale here is what stretched the stroke; there must not be one")
        assertNotNull(edit.points)
    }

    @Test
    fun a_segment_is_regularised_by_a_rotation_which_cannot_stretch_anything() {
        val placed = ShapeEdit.placeSegment(0f, 0f, 100f, 9f, bandPt = 3f)
        val edit = assertNotNull(regularise(ShapeKind.LINE, placed))
        assertNull(edit.points)
        val matrix = assertNotNull(edit.transform)
        // Rigid: both axes keep their length, so the pen keeps its width.
        assertClose(1f, hypot(matrix.m00, matrix.m10), "x axis length")
        assertClose(1f, hypot(matrix.m01, matrix.m11), "y axis length")
    }

    // -----------------------------------------------------------------------
    // Closed shapes
    // -----------------------------------------------------------------------

    @Test
    fun an_oval_becomes_a_circle_of_the_mean_diameter() {
        val placed = ShapeEdit.place(ShapeKind.ELLIPSE, PageRect(10f, 20f, 90f, 60f))
        val page = pageOutlineOf(placed, assertNotNull(regularise(ShapeKind.ELLIPSE, placed)).points)
        // The four quadrant points are all one radius from the centre.
        val centre = centreOf(page)
        val radii = page.map { distance(centre, it) }
        for (r in radii) assertClose(30f, r, "80 and 40 average to 60, so the radius is 30")
    }

    @Test
    fun a_rectangle_becomes_a_square_without_moving() {
        val placed = ShapeEdit.place(ShapeKind.RECTANGLE, PageRect(10f, 20f, 90f, 60f))
        val page = pageOutlineOf(placed, assertNotNull(regularise(ShapeKind.RECTANGLE, placed)).points)
        assertClose(60f, distance(page[0], page[1]), "top edge")
        assertClose(60f, distance(page[1], page[2]), "right edge")
        assertClose(60f, distance(page[2], page[3]), "bottom edge")
        // The centre is the fixed point: a shape made regular must not walk off the ink under it.
        val before = placed.transform.map(ShapeGeometry.centre(placed.widthPt, placed.heightPt))
        val after = centreOf(page)
        assertClose(before.x, after.x, "centre x")
        assertClose(before.y, after.y, "centre y")
    }

    /** Equilateral is a fact about [ShapeGeometry]'s outline, not about a square box. */
    @Test
    fun a_triangle_becomes_equilateral() {
        val placed = ShapeEdit.place(ShapeKind.TRIANGLE, PageRect(0f, 0f, 100f, 40f))
        val page = pageOutlineOf(placed, assertNotNull(regularise(ShapeKind.TRIANGLE, placed)).points)
        val a = distance(page[0], page[1])
        val b = distance(page[1], page[2])
        val c = distance(page[2], page[0])
        assertClose(a, b, "two sides")
        assertClose(b, c, "and the third")
        // …and the box it sits in is √3/2 as tall as it is wide, which a square box would not be.
        val height = abs(page[1].y - page[0].y)
        assertClose(b * (sqrt(3.0) / 2.0).toFloat(), height, "height = side × √3/2")
    }

    @Test
    fun a_rotated_shape_keeps_its_rotation() {
        val box = OrientedBox(centreX = 50f, centreY = 40f, widthPt = 80f, heightPt = 40f, angleRadians = 0.4f)
        val placed = ShapeEdit.placeOriented(ShapeKind.RECTANGLE, box)
        val page = pageOutlineOf(placed, assertNotNull(regularise(ShapeKind.RECTANGLE, placed)).points)
        val bearing = atan2(page[1].y - page[0].y, page[1].x - page[0].x)
        assertClose(0.4f, bearing, "the top edge still points the way the box does")
        assertClose(distance(page[0], page[1]), distance(page[1], page[2]), "and it is square")
    }

    @Test
    fun a_circle_is_left_alone() {
        val placed = ShapeEdit.place(ShapeKind.ELLIPSE, PageRect(0f, 0f, 50f, 50f))
        assertNull(regularise(ShapeKind.ELLIPSE, placed), "no op, so no undo entry that does nothing")
        assertTrue(ShapeRegular.isRegular(ShapeKind.ELLIPSE, placed.widthPt, placed.heightPt, null, placed.transform))
    }

    /** ⭐ The property that makes the chip disappear after one press instead of sticking. */
    @Test
    fun regularising_twice_is_a_no_op_the_second_time() {
        val placed = ShapeEdit.place(ShapeKind.RECTANGLE, PageRect(3f, 7f, 90f, 41f))
        val once = assertNotNull(regularise(ShapeKind.RECTANGLE, placed)).points
        assertNull(
            ShapeRegular.regularise(ShapeKind.RECTANGLE, placed.widthPt, placed.heightPt, once, placed.transform),
            "the live vertices are what is measured, so a second press has nothing to write",
        )
    }

    /** The predicate a chip's visibility is built on must be the action's own arithmetic. */
    @Test
    fun the_predicate_agrees_with_the_action() {
        val oval = ShapeEdit.place(ShapeKind.ELLIPSE, PageRect(0f, 0f, 80f, 40f))
        assertTrue(!ShapeRegular.isRegular(ShapeKind.ELLIPSE, oval.widthPt, oval.heightPt, null, oval.transform))
        val circle = assertNotNull(regularise(ShapeKind.ELLIPSE, oval)).points
        assertTrue(ShapeRegular.isRegular(ShapeKind.ELLIPSE, oval.widthPt, oval.heightPt, circle, oval.transform))
    }

    /** A shape resized after it was drawn: the live size is the payload through the transform. */
    @Test
    fun the_live_transform_is_what_is_measured_and_not_the_payload() {
        val placed = ShapeEdit.place(ShapeKind.RECTANGLE, PageRect(0f, 0f, 50f, 50f))
        // Square by payload, oblong on the page — an edge-handle resize wrote this matrix.
        val stretched = Matrix3.scale(2f, 1f) * placed.transform
        val edit = assertNotNull(
            ShapeRegular.regularise(ShapeKind.RECTANGLE, placed.widthPt, placed.heightPt, null, stretched),
        )
        val page = assertNotNull(edit.points).map { stretched.map(it) }
        assertClose(distance(page[0], page[1]), distance(page[1], page[2]), "square on the page")
    }

    /** A shape whose corners were dragged: "make it perfect" is the request to go back to the kind. */
    @Test
    fun dragged_vertices_are_replaced_by_the_regular_outline() {
        val placed = ShapeEdit.place(ShapeKind.RECTANGLE, PageRect(0f, 0f, 60f, 60f))
        val dragged = listOf(PagePoint(0f, 0f), PagePoint(90f, -20f), PagePoint(60f, 60f), PagePoint(0f, 60f))
        val edit = assertNotNull(
            ShapeRegular.regularise(ShapeKind.RECTANGLE, placed.widthPt, placed.heightPt, dragged, placed.transform),
        )
        val page = pageOutlineOf(placed, edit.points)
        assertClose(distance(page[0], page[1]), distance(page[1], page[2]), "square again")
    }

    // -----------------------------------------------------------------------
    // Segments
    // -----------------------------------------------------------------------

    @Test
    fun a_nearly_horizontal_line_is_turned_flat_about_its_midpoint() {
        val placed = ShapeEdit.placeSegment(0f, 0f, 100f, 9f, bandPt = 3f)
        val next = assertNotNull(assertNotNull(regularise(ShapeKind.LINE, placed)).transform)
        val (a, b) = endpointsOf(placed, next)
        assertClose(a.y, b.y, "flat")
        val (a0, b0) = endpointsOf(placed, placed.transform)
        assertClose((a0.x + b0.x) / 2f, (a.x + b.x) / 2f, "midpoint x")
        assertClose((a0.y + b0.y) / 2f, (a.y + b.y) / 2f, "midpoint y")
        assertClose(distance(a0, b0), distance(a, b), "length is kept")
    }

    @Test
    fun a_line_near_the_diagonal_goes_to_forty_five_degrees() {
        val placed = ShapeEdit.placeSegment(0f, 0f, 100f, 88f, bandPt = 3f)
        val next = assertNotNull(assertNotNull(regularise(ShapeKind.LINE, placed)).transform)
        val (a, b) = endpointsOf(placed, next)
        assertClose(45f, atan2(b.y - a.y, b.x - a.x) * DEGREES_PER_RADIAN)
    }

    @Test
    fun an_already_straight_line_is_left_alone() {
        val placed = ShapeEdit.placeSegment(10f, 10f, 90f, 10f, bandPt = 3f)
        assertNull(regularise(ShapeKind.LINE, placed))
    }

    /** An arrow is a segment; its head is drawn from the outline, so the angle rule is the same. */
    @Test
    fun an_arrow_regularises_exactly_as_a_line_does() {
        val line = ShapeEdit.placeSegment(0f, 0f, 100f, 9f, bandPt = 3f, kind = ShapeKind.LINE)
        val arrow = ShapeEdit.placeSegment(0f, 0f, 100f, 9f, bandPt = 3f, kind = ShapeKind.ARROW)
        val a = assertNotNull(assertNotNull(regularise(ShapeKind.LINE, line)).transform)
        val b = assertNotNull(assertNotNull(regularise(ShapeKind.ARROW, arrow)).transform)
        assertClose(a.m00, b.m00)
        assertClose(a.m01, b.m01)
        assertClose(a.m02, b.m02)
        assertClose(a.m10, b.m10)
        assertClose(a.m11, b.m11)
        assertClose(a.m12, b.m12)
    }

    @Test
    fun a_shape_with_no_extent_is_refused() {
        assertNull(ShapeRegular.regularise(ShapeKind.RECTANGLE, 0f, 0f, null, Matrix3.Identity))
    }

    // -----------------------------------------------------------------------

    private fun regularise(kind: ShapeKind, placement: ShapePlacement): ShapeRegularEdit? =
        ShapeRegular.regularise(kind, placement.widthPt, placement.heightPt, null, placement.transform)

    /** The returned local vertices, where they land on the page. */
    private fun pageOutlineOf(placement: ShapePlacement, points: List<PagePoint>?): List<PagePoint> =
        assertNotNull(points).map { placement.transform.map(it) }

    private fun centreOf(points: List<PagePoint>): PagePoint = PagePoint(
        points.sumOf { it.x.toDouble() }.toFloat() / points.size,
        points.sumOf { it.y.toDouble() }.toFloat() / points.size,
    )

    private fun endpointsOf(placement: ShapePlacement, matrix: Matrix3): Pair<PagePoint, PagePoint> {
        val middle = placement.heightPt / 2f
        return matrix.map(PagePoint(0f, middle)) to matrix.map(PagePoint(placement.widthPt, middle))
    }

    private fun distance(a: PagePoint, b: PagePoint): Float = hypot(b.x - a.x, b.y - a.y)

    private fun assertClose(expected: Float, actual: Float, what: String = "") {
        assertTrue(abs(expected - actual) < TOLERANCE, "$what expected $expected but was $actual")
    }

    private companion object {
        const val TOLERANCE = 1e-2f
        val DEGREES_PER_RADIAN = (180.0 / PI).toFloat()
    }
}
