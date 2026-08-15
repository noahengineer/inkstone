package dev.inkstone.core.model

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⭐ Editing a shape by its points, and the curve that gave it a third handle.
 *
 * Unlike its neighbour [ShapesTest], **these tests can settle their subject**. Draw-and-hold's
 * classifier is a heuristic about a hand and a fixture is the worst possible witness for one
 * (M7); a handle drag is arithmetic with a right answer. *"The dragged vertex ends up under the
 * finger and the other two do not move"* is a fact about a matrix, and if it holds here it holds
 * on the tablet.
 *
 * What is **not** settled here is whether the handles can be hit, whether they are drawn where
 * they are computed, and whether the ops reach the log — that is `SelectionControllerPointTest`
 * on a device, and CLAUDE.md's definition-of-done #6 is why it exists at all.
 */
class ShapePointsTest {

    // -----------------------------------------------------------------------
    // What each kind offers
    // -----------------------------------------------------------------------

    @Test
    fun `a line offers two ends and a bend at the middle of the chord`() {
        val points = ShapePoints.of(ShapeKind.LINE, widthPt = 200f, heightPt = 4f)

        assertEquals(3, points.size)
        assertEquals(PagePoint(0f, 2f), points[0].local)
        assertEquals(PagePoint(200f, 2f), points[2].local)
        // ⭐ The affordance: a straight line's bend handle sits on the line, so the way to make a
        // curve is to grab the middle and pull. A handle that only appeared once the line was
        // already curved could never be found.
        assertEquals(ShapePointRole.BEND, points[1].role)
        assertEquals(PagePoint(100f, 2f), points[1].local)
    }

    @Test
    fun `a bent line puts its middle handle on the bend and not on the chord`() {
        val bend = PagePoint(60f, -40f)

        val points = ShapePoints.of(ShapeKind.LINE, widthPt = 200f, heightPt = 4f, bendLocal = bend)

        assertEquals(bend, points[1].local)
    }

    @Test
    fun `every closed kind offers its own points and nothing offers a duplicate`() {
        val triangle = ShapePoints.of(ShapeKind.TRIANGLE, 100f, 60f)
        val rectangle = ShapePoints.of(ShapeKind.RECTANGLE, 100f, 60f)
        val ellipse = ShapePoints.of(ShapeKind.ELLIPSE, 100f, 60f)

        assertEquals(3, triangle.size)
        assertEquals(4, rectangle.size)
        assertEquals(4, ellipse.size)
        assertTrue(triangle.all { it.role == ShapePointRole.VERTEX })
        for (points in listOf(triangle, rectangle, ellipse)) {
            assertEquals(points.size, points.map { it.local }.toSet().size)
            assertEquals(points.indices.toList(), points.map { it.index })
        }
        // The triangle's vertices are ShapeGeometry's own, or a handle would sit off the ink.
        assertEquals(
            ShapeGeometry.outline(ShapeKind.TRIANGLE, 100f, 60f).flattened().take(3).toSet(),
            triangle.map { it.local }.toSet(),
        )
    }

    @Test
    fun `a shape with no extent offers no handles`() {
        assertTrue(ShapePoints.of(ShapeKind.RECTANGLE, widthPt = 0f, heightPt = 60f).isEmpty())
        assertTrue(ShapePoints.of(ShapeKind.LINE, widthPt = 200f, heightPt = 0f).isEmpty())
    }

    // -----------------------------------------------------------------------
    // A segment's ends — the similarity solve
    // -----------------------------------------------------------------------

    @Test
    fun `dragging one end of a line moves that end and leaves the other where it was`() {
        val line = ShapeEdit.placeSegment(100f, 200f, 300f, 200f, bandPt = 4f)
        val end = handle(line, index = 2)
        val target = PagePoint(300f, 400f)

        val placed = placementAfter(line, end, target)

        assertPoint(target, placed.map(end.local))
        assertPoint(PagePoint(100f, 200f), placed.map(handle(line, index = 0).local))
    }

    @Test
    fun `dragging the end of a rotated line still pins the other end`() {
        // A line drawn at 40 degrees, which is the case a page-space box cannot express at all —
        // its axis-aligned frame has corners nowhere near the ink.
        val line = ShapeEdit.placeSegment(120f, 500f, 320f, 100f, bandPt = 3f)
        val start = handle(line, index = 0)
        val target = PagePoint(60f, 620f)

        val placed = placementAfter(line, start, target)

        assertPoint(target, placed.map(start.local))
        assertPoint(PagePoint(320f, 100f), placed.map(handle(line, index = 2).local))
    }

    @Test
    fun `a line keeps its band proportions so a bend on it scales with the chord`() {
        val line = ShapeEdit.placeSegment(0f, 0f, 100f, 0f, bandPt = 4f)
        val bend = PagePoint(50f, -20f)
        val middle = handle(line, index = 1).local
        val end = handle(line, index = 2)

        val placed = placementAfter(line, end, PagePoint(200f, 0f))

        // Twice as long, so the bulge is twice as deep: the bend is a point in the shape's own
        // frame and a similarity carries it. A non-uniform scale would stretch the curve along the
        // chord and leave its depth behind, which reads as a curve flattening as it grows.
        val before = line.transform.map(middle).y - line.transform.map(bend).y
        val after = placed.map(middle).y - placed.map(bend).y
        assertEquals(2f * before, after, TOLERANCE)
    }

    @Test
    fun `a line cannot be dragged onto its own far end`() {
        val line = ShapeEdit.placeSegment(100f, 200f, 300f, 200f, bandPt = 4f)

        val refused = gestureFor(line, handle(line, index = 2), PagePoint(100f, 200f))

        // Refused rather than clamped: a drag onto the pivot has no direction to rotate toward, so
        // any answer would be an invented one. SelectionEdit.transform would refuse the resulting
        // matrix anyway — this refuses it a step earlier, where the reason is visible.
        assertNull(refused)
    }

    @Test
    fun `a line dragged almost onto its far end keeps a length that can be grabbed again`() {
        val line = ShapeEdit.placeSegment(100f, 200f, 300f, 200f, bandPt = 4f)
        val end = handle(line, index = 2)

        val placed = placementAfter(line, end, PagePoint(100.5f, 200f))

        val landed = placed.map(end.local)
        assertTrue(landed.x - 100f > 1f, "the line collapsed onto its own start: $landed")
    }

    // -----------------------------------------------------------------------
    // A triangle's vertices — the exact affine
    // -----------------------------------------------------------------------

    @Test
    fun `dragging a triangle vertex lands it exactly and pins the other two`() {
        val triangle = ShapePlacement(ShapeKind.TRIANGLE, 100f, 60f, Matrix3.Identity)
        val apex = handle(triangle, index = 0)
        val target = PagePoint(10f, -30f)

        val placed = placementAfter(triangle, apex, target)

        assertPoint(target, placed.map(apex.local))
        assertPoint(PagePoint(100f, 60f), placed.map(handle(triangle, index = 1).local))
        assertPoint(PagePoint(0f, 60f), placed.map(handle(triangle, index = 2).local))
    }

    @Test
    fun `a triangle vertex drag shears the placement because a different triangle is a shear`() {
        val triangle = ShapePlacement(ShapeKind.TRIANGLE, 100f, 60f, Matrix3.Identity)

        val placed = placementAfter(triangle, handle(triangle, index = 0), PagePoint(10f, -30f))

        // Matrix3.isSkewed is a *diagnostic* — the signature of a delta-composition bug (SPEC §4.2)
        // — and not a guard, which is what makes this legal. There is no other way to reach an
        // arbitrary triangle from the one the user drew, and the write is still one absolute matrix.
        assertTrue(placed.isSkewed(), "an off-axis vertex should have sheared the placement")
        assertTrue(!placed.isDegenerate())
    }

    @Test
    fun `flattening a triangle onto a line is refused rather than written`() {
        val triangle = ShapePlacement(ShapeKind.TRIANGLE, 100f, 60f, Matrix3.Identity)

        // Straight onto the line through the other two vertices: the resulting affine is singular,
        // and a singular one cannot be inverted — which is what maps an eraser into the object's
        // own space, so the shape would become uneraseable as well as invisible.
        val refused = gestureFor(triangle, handle(triangle, index = 0), PagePoint(50f, 60f))

        assertNull(refused)
    }

    // -----------------------------------------------------------------------
    // A box's corners — the shape's own axes, which is what rotation-invariance means
    // -----------------------------------------------------------------------

    @Test
    fun `dragging a rectangle corner lands it exactly and pins the opposite corner`() {
        val rectangle = ShapePlacement(ShapeKind.RECTANGLE, 100f, 60f, Matrix3.translate(200f, 300f))
        val corner = handle(rectangle, index = 2)
        val target = PagePoint(400f, 420f)

        val placed = placementAfter(rectangle, corner, target)

        assertPoint(target, placed.map(corner.local))
        assertPoint(PagePoint(200f, 300f), placed.map(handle(rectangle, index = 0).local))
    }

    @Test
    fun `a rotated rectangle stays a rectangle when a corner is dragged`() {
        val turned = Matrix3.translate(200f, 300f) * Matrix3.rotate((PI / 6).toFloat())
        val rectangle = ShapePlacement(ShapeKind.RECTANGLE, 100f, 60f, turned)
        val corner = handle(rectangle, index = 2)
        // Somewhere the shape's own axes can reach — 1.6× along its width, 1.5× along its height.
        val target = turned.map(PagePoint(160f, 90f))

        val placed = placementAfter(rectangle, corner, target)

        assertPoint(target, placed.map(corner.local))
        assertPoint(turned.map(PagePoint(0f, 0f)), placed.map(handle(rectangle, index = 0).local))
        // ⭐ The whole point of solving in the shape's own basis. A page-space solve would have put
        // the corner under the finger too — by shearing the rectangle into a parallelogram.
        assertTrue(!placed.isSkewed(), "a corner drag sheared a rectangle")
    }

    @Test
    fun `dragging a corner past its pin refuses to mirror the rectangle`() {
        val rectangle = ShapePlacement(ShapeKind.RECTANGLE, 100f, 60f, Matrix3.Identity)

        val placed = placementAfter(rectangle, handle(rectangle, index = 2), PagePoint(-50f, -30f))

        // A flip is a second gesture nobody asked this one to perform, and it is indistinguishable
        // from a shape that has simply vanished behind its own pin.
        assertTrue(placed.determinant > 0f, "the corner drag mirrored the shape: $placed")
        assertTrue(!placed.isDegenerate())
    }

    @Test
    fun `an ellipse quadrant point travels along its own diameter and no further`() {
        val ellipse = ShapePlacement(ShapeKind.ELLIPSE, 200f, 100f, Matrix3.Identity)
        val right = handle(ellipse, index = 1)

        // Dragged diagonally: the sideways half of the drag has nowhere to go on a diameter, and
        // shearing it in would turn the ellipse into a tilted one nobody asked for.
        val placed = placementAfter(ellipse, right, PagePoint(260f, 90f))

        assertPoint(PagePoint(260f, 50f), placed.map(right.local))
        assertPoint(PagePoint(0f, 50f), placed.map(handle(ellipse, index = 3).local))
        assertPoint(PagePoint(130f, 0f), placed.map(handle(ellipse, index = 0).local))
    }

    // -----------------------------------------------------------------------
    // The curve
    // -----------------------------------------------------------------------

    @Test
    fun `the bend is the point under the finger in the shape own space`() {
        val line = ShapeEdit.placeSegment(100f, 200f, 300f, 200f, bandPt = 4f)

        val bend = ShapePoints.bend(line.heightPt, line.transform, PagePoint(200f, 120f), 8f)

        assertNotNull(bend)
        assertPoint(PagePoint(200f, 120f), line.transform.map(bend))
    }

    @Test
    fun `a bend within the straighten tolerance is no bend at all`() {
        val line = ShapeEdit.placeSegment(100f, 200f, 300f, 200f, bandPt = 4f)

        val bend = ShapePoints.bend(line.heightPt, line.transform, PagePoint(180f, 204f), 8f)

        // Null, not "a bend that happens to be flat". The record stores null as "straight", so the
        // line goes back to a plain segment and — because ShapeRecord.isDefault then holds again —
        // stops costing a string in the file.
        assertNull(bend)
    }

    @Test
    fun `the straighten tolerance is measured on the page and not in the shape own units`() {
        // A line placed at 4x: 6 page points off the chord is 1.5 in its own space, and measuring
        // the tolerance in the wrong space is the conversion bug M8b shipped twice (the connector
        // bind radius, and the alignment guides before it).
        val scaled = ShapePlacement(ShapeKind.LINE, 100f, 4f, Matrix3.scale(4f, 4f))

        assertNull(ShapePoints.bend(4f, scaled.transform, PagePoint(200f, 14f), 8f))
        assertNotNull(ShapePoints.bend(4f, scaled.transform, PagePoint(200f, 28f), 8f))
    }

    @Test
    fun `a curve passes exactly through its bend handle`() {
        val bend = PagePoint(100f, -40f)

        val path = ShapeGeometry.outline(ShapeKind.LINE, 200f, 4f, bendLocal = bend)

        // ⭐ The reason the handle is the curve's midpoint rather than the Bezier's control point:
        // the ink goes where the finger put it. Sampled at two steps, so the first sample is t=0.5.
        val sampled = path.flattened(samplesPerCurve = 2)
        assertEquals(3, sampled.size)
        assertPoint(PagePoint(0f, 2f), sampled[0])
        assertPoint(bend, sampled[1])
        assertPoint(PagePoint(200f, 2f), sampled[2])
    }

    @Test
    fun `a curve keeps the ends the payload says it has`() {
        val straight = ShapeGeometry.outline(ShapeKind.ARROW, 200f, 4f).flattened()
        val bent = ShapeGeometry.outline(ShapeKind.ARROW, 200f, 4f, bendLocal = PagePoint(100f, -40f)).flattened()

        // A connector falls back to `(0, h/2) → (w, h/2)` under its transform when an end does not
        // resolve, and an older build draws exactly that. Both readings stay true of a bent line.
        assertEquals(straight.first(), bent.first())
        assertEquals(straight.last(), bent.last())
        assertTrue(!ShapeGeometry.outline(ShapeKind.LINE, 200f, 4f, bendLocal = PagePoint(100f, -40f)).closed)
    }

    @Test
    fun `a bend on the chord draws the straight segment rather than a flat cubic`() {
        val path = ShapeGeometry.outline(ShapeKind.LINE, 200f, 4f, bendLocal = PagePoint(30f, 2f))

        // Otherwise "is this line straight" becomes a question about float noise, and every
        // straight-looking line published as a cubic is a difference two renderers can disagree on.
        assertTrue(path.segments.any { it is ShapeSegment.LineTo }, "a flat bend produced ${path.segments}")
        assertTrue(path.segments.none { it is ShapeSegment.CubicTo })
    }

    @Test
    fun `only a segment bends`() {
        assertTrue(ShapeGeometry.bends(ShapeKind.LINE))
        assertTrue(ShapeGeometry.bends(ShapeKind.ARROW))
        assertTrue(!ShapeGeometry.bends(ShapeKind.RECTANGLE))
        // A bend handed to a box is ignored, not approximated — the same refusal isFillable makes.
        assertEquals(
            ShapeGeometry.outline(ShapeKind.RECTANGLE, 100f, 60f),
            ShapeGeometry.outline(ShapeKind.RECTANGLE, 100f, 60f, bendLocal = PagePoint(50f, -80f)),
        )
    }

    // -----------------------------------------------------------------------
    // The record — the one thing here that reaches the file
    // -----------------------------------------------------------------------

    @Test
    fun `a bend round trips through the record and re-encodes byte for byte`() {
        val record = ShapeRecord(bend = PagePoint(60.5f, -40.25f))

        val wire = record.encode()
        val back = assertNotNull(ShapeRecord.decode(wire))

        assertEquals(PagePoint(60.5f, -40.25f), back.bend)
        assertEquals(wire, back.encode())
    }

    @Test
    fun `a curved line is not a default record`() {
        // ⚠️ ShapeEdit.create omits a default record entirely and editRecords writes its undo
        // baseline only for a default one. A curve reading as "default" would be published with no
        // record by one path and given a baseline that straightens it by the other.
        assertTrue(!ShapeRecord(bend = PagePoint(10f, 10f)).isDefault)
        assertTrue(ShapeRecord(bend = null).isDefault)
    }

    @Test
    fun `an unreadable bend leaves the line straight and keeps the rest of the record`() {
        val record = assertNotNull(ShapeRecord.decode("is1;f=-16777216;bd=oops"))

        // Fail closed, per key: a record is several independent properties and one unreadable bend
        // is no reason to forget a fill.
        assertNull(record.bend)
        assertEquals(-16777216, record.fillArgb)
    }

    @Test
    fun `a bend from a build that meant more than two numbers is refused rather than guessed`() {
        assertNull(assertNotNull(ShapeRecord.decode("is1;bd=1:2:3")).bend)
    }

    @Test
    fun `a non-finite bend is refused before it can reach the geometry`() {
        // `toFloatOrNull` parses both of these happily. A NaN bend would go into a cubic, out of
        // `flattened` into the object's bounds, and from there into the culling test and the PDF —
        // and not one of those would report it.
        assertNull(assertNotNull(ShapeRecord.decode("is1;bd=NaN:3")).bend)
        assertNull(assertNotNull(ShapeRecord.decode("is1;bd=1:Infinity")).bend)
    }

    @Test
    fun `a bend travels beside an attachment without disturbing it`() {
        val record = ShapeRecord(
            endA = ConnectorEnd.Attached(ObjectId("obj-a")),
            bend = PagePoint(12f, -8f),
        )

        val back = assertNotNull(ShapeRecord.decode(record.encode()))

        assertEquals(record, back)
        assertTrue(back.isConnector)
    }

    // -----------------------------------------------------------------------

    private fun handle(placement: ShapePlacement, index: Int): ShapePoint =
        ShapePoints.of(placement.kind, placement.widthPt, placement.heightPt)[index]

    private fun gestureFor(placement: ShapePlacement, point: ShapePoint, to: PagePoint): Matrix3? =
        ShapePoints.gesture(placement.kind, placement.widthPt, placement.heightPt, placement.transform, point, to)

    /**
     * Where the shape ends up: the gesture composed onto its own matrix, exactly as
     * [SelectionEdit.transform] composes it before writing the op.
     *
     * Going through that composition rather than asserting on the gesture is deliberate — the
     * gesture alone is meaningless, and a test that checked it would pass for a matrix the real
     * caller then applies to the wrong side.
     */
    private fun placementAfter(placement: ShapePlacement, point: ShapePoint, to: PagePoint): Matrix3 {
        val gesture = assertNotNull(gestureFor(placement, point, to), "the drag was refused")
        return gesture * placement.transform
    }

    private fun assertPoint(expected: PagePoint, actual: PagePoint) {
        assertEquals(expected.x, actual.x, TOLERANCE, "x")
        assertEquals(expected.y, actual.y, TOLERANCE, "y")
    }

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
