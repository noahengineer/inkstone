package dev.inkstone.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⭐ Editing a shape by its points, and the curve that gave a segment a third handle.
 *
 * Unlike its neighbour [ShapesTest], **these tests can settle their subject**. Draw-and-hold's
 * classifier is a heuristic about a hand and a fixture is the worst possible witness for one (M7);
 * a handle drag is arithmetic with a right answer. *"The dragged point ends up under the finger and
 * the others do not move"* is a fact about a list, and if it holds here it holds on the tablet.
 *
 * What is **not** settled here is whether the handles can be hit, whether they are drawn where they
 * are computed, and whether the ops reach the log — that is `SelectionControllerShapeTest` on a
 * device, and CLAUDE.md's definition-of-done #6 is why it exists.
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
    // ⭐ One rule: a drag moves one point, and nothing else moves
    // -----------------------------------------------------------------------

    @Test
    fun `moving a rectangle corner moves that corner and nothing else`() {
        val before = ShapePoints.of(ShapeKind.RECTANGLE, 100f, 60f).map { it.local }
        val target = PagePoint(180f, -40f)

        val after = assertNotNull(ShapePoints.moved(ShapeKind.RECTANGLE, 100f, 60f, null, index = 2, toLocal = target))

        // ⭐ *"editing the points is just resizeing the shape from those points rather then actually
        // editing the shape"*. The first design solved this as a placement, which for a rectangle
        // can only scale: every one of these four assertions failed except the first.
        assertEquals(4, after.size)
        assertPoint(target, after[2])
        assertPoint(before[0], after[0])
        assertPoint(before[1], after[1])
        assertPoint(before[3], after[3])
    }

    @Test
    fun `moving one end of a line moves that end and nothing else`() {
        val target = PagePoint(140f, -60f)

        val after = assertNotNull(ShapePoints.moved(ShapeKind.LINE, 200f, 4f, null, index = 2, toLocal = target))

        // ⭐ *"straight lines … have the stretching by size changing, i dont want any shape to do
        // that no matter what"*. This used to be a similarity about the far end, which scaled the
        // whole local frame — the far end stayed put, but everything else in that frame moved.
        assertEquals(2, after.size)
        assertPoint(PagePoint(0f, 2f), after[0])
        assertPoint(target, after[1])
    }

    @Test
    fun `dragging an end of a curved line leaves the curve where it is`() {
        val bend = PagePoint(100f, -40f)
        val moved =
            assertNotNull(ShapePoints.moved(ShapeKind.LINE, 200f, 4f, null, index = 2, toLocal = PagePoint(400f, 2f)))

        // The bend is its own register in the shape's own space, so stretching the chord does not
        // touch it. Under the old similarity the bulge doubled with the line — the visible half of
        // "stretching by size changing".
        val curve = ShapeGeometry.outline(ShapeKind.LINE, 200f, 4f, bendLocal = bend, points = moved)
        assertPoint(bend, curve.flattened(samplesPerCurve = 2)[1])
    }

    @Test
    fun `a second drag edits the list it was given rather than re-seeding`() {
        val once = assertNotNull(
            ShapePoints.moved(ShapeKind.TRIANGLE, 100f, 60f, null, index = 0, toLocal = PagePoint(10f, -30f)),
        )

        val twice = assertNotNull(
            ShapePoints.moved(ShapeKind.TRIANGLE, 100f, 60f, once, index = 1, toLocal = PagePoint(140f, 90f)),
        )

        assertPoint(PagePoint(10f, -30f), twice[0])
        assertPoint(PagePoint(140f, 90f), twice[1])
        assertPoint(once[2], twice[2])
    }

    @Test
    fun `a line's second drag edits the ends it was given`() {
        val once =
            assertNotNull(ShapePoints.moved(ShapeKind.LINE, 200f, 4f, null, index = 0, toLocal = PagePoint(-50f, 30f)))

        val twice =
            assertNotNull(ShapePoints.moved(ShapeKind.LINE, 200f, 4f, once, index = 2, toLocal = PagePoint(300f, 90f)))

        assertPoint(PagePoint(-50f, 30f), twice[0])
        assertPoint(PagePoint(300f, 90f), twice[1])
    }

    @Test
    fun `the bend handle writes no vertex list`() {
        // Its index is between the two ends, and it belongs to the other register entirely.
        assertNull(ShapePoints.moved(ShapeKind.LINE, 200f, 4f, null, index = 1, toLocal = PagePoint(50f, 50f)))
    }

    @Test
    fun `a vertex may be dragged anywhere including onto its neighbours line`() {
        // A degenerate *polygon* is recoverable — drag the point back — where the first design's
        // degenerate *matrix* was not: an uninvertible placement cannot map an eraser into the
        // object's own space, so the shape became uneraseable as well as invisible.
        val flattened = ShapePoints.moved(ShapeKind.TRIANGLE, 100f, 60f, null, index = 0, toLocal = PagePoint(50f, 60f))

        assertNotNull(flattened)
        assertPoint(PagePoint(50f, 60f), flattened[0])
    }

    @Test
    fun `an edited line offers its handles where its stored ends are`() {
        val ends = listOf(PagePoint(-20f, 10f), PagePoint(260f, 90f))

        val handles = ShapePoints.of(ShapeKind.LINE, 200f, 4f, points = ends)

        assertPoint(ends[0], handles[0].local)
        assertPoint(ends[1], handles[2].local)
        // …and the bend handle sits at the middle of the *edited* chord, not the payload's.
        assertPoint(PagePoint(120f, 50f), handles[1].local)
    }

    // -----------------------------------------------------------------------
    // ⭐ Seeding is invisible — the property that makes the first drag safe
    // -----------------------------------------------------------------------

    @Test
    fun `seeded vertices reproduce the parametric outline for every closed kind`() {
        for (kind in listOf(ShapeKind.RECTANGLE, ShapeKind.TRIANGLE, ShapeKind.ELLIPSE)) {
            val seeded = ShapePoints.of(kind, 200f, 120f).map { it.local }
            val parametric = ShapeGeometry.outline(kind, 200f, 120f).flattened(samplesPerCurve = 16)
            val fromPoints = ShapeGeometry.outline(kind, 200f, 120f, points = seeded).flattened(samplesPerCurve = 16)

            // ⚠️ The ellipse is the one that had to be *made* true: its smoothing constant is
            // KAPPA/2 rather than Catmull-Rom's 1/6 precisely so that four quadrant points redraw
            // the same ellipse. Any other constant and the first drag would visibly re-draw the
            // whole shape — which is the complaint this feature answers, arriving backwards.
            assertEquals(parametric.size, fromPoints.size, "$kind changed shape on seeding")
            for (index in parametric.indices) assertPoint(parametric[index], fromPoints[index])
        }
    }

    @Test
    fun `a seeded segment reproduces its own payload geometry`() {
        val seeded = ShapePoints.of(ShapeKind.LINE, 200f, 4f).filter { it.role == ShapePointRole.VERTEX }

        assertEquals(
            ShapeGeometry.outline(ShapeKind.LINE, 200f, 4f).flattened(),
            ShapeGeometry.outline(ShapeKind.LINE, 200f, 4f, points = seeded.map { it.local }).flattened(),
        )
    }

    @Test
    fun `an unedited shape stores no points at all`() {
        assertTrue(ShapeRecord.None.points == null)
        assertTrue(ShapeRecord.None.isDefault)
        assertTrue(!ShapeRecord(points = listOf(PagePoint(0f, 0f), PagePoint(1f, 0f), PagePoint(0f, 1f))).isDefault)
    }

    // -----------------------------------------------------------------------
    // The curve
    // -----------------------------------------------------------------------

    @Test
    fun `the bend is the point under the finger in the shape own space`() {
        val line = ShapeEdit.placeSegment(100f, 200f, 300f, 200f, bandPt = 4f)

        val drag = assertNotNull(bendAt(line, PagePoint(200f, 120f)))

        assertNotNull(drag.stored)
        assertPoint(PagePoint(200f, 120f), line.transform.map(drag.stored))
        assertEquals(drag.stored, drag.preview, "a real bend previews exactly what it stores")
    }

    @Test
    fun `a bend within the straighten tolerance stores nothing and previews straight`() {
        val line = ShapeEdit.placeSegment(100f, 200f, 300f, 200f, bandPt = 4f)

        val drag = assertNotNull(bendAt(line, PagePoint(180f, 204f)))

        // ⭐ The two halves, which used to be one nullable and therefore one bug. `stored` is null
        // because straight is *no bend* in the record — that is what restores isDefault and stops
        // the line costing a string in the file.
        assertNull(drag.stored)
        // …and `preview` is the foot of the perpendicular, so the drag *draws* straight instead of
        // meaning "nothing to preview". Reading null as the latter is what sprang a flattened curve
        // back to its stored arc with the finger still down.
        assertPoint(PagePoint(80f, 2f), drag.preview)
        assertTrue(
            ShapeGeometry.outline(ShapeKind.LINE, line.widthPt, line.heightPt, bendLocal = drag.preview)
                .segments.none { it is ShapeSegment.CubicTo },
            "a straightened preview must draw as a line",
        )
    }

    @Test
    fun `a bend dragged onto the chord keeps the handle under the finger`() {
        val line = ShapeEdit.placeSegment(0f, 0f, 200f, 0f, bandPt = 4f)

        // A quarter of the way along, not the middle: the preview must not jump to the chord's
        // centre just because the line went straight, which is a visible snap under the finger.
        val drag = assertNotNull(bendAt(line, PagePoint(50f, 1f)))

        assertNull(drag.stored)
        assertEquals(50f, line.transform.map(drag.preview).x, TOLERANCE)
    }

    @Test
    fun `straightness is measured against the edited chord and not the payload's`() {
        // ⚠️ Once an end has been dragged the segment is no longer (0, h/2) → (w, h/2). Measuring
        // against the payload's chord would call a genuine curve straight, or the reverse.
        val line = ShapeEdit.placeSegment(0f, 0f, 200f, 0f, bandPt = 4f)
        val tilted = listOf(PagePoint(0f, 2f), PagePoint(200f, 102f))

        // Dead on the tilted chord's midpoint: straight by the edited chord, 50 pt off the old one.
        val drag = assertNotNull(bendAt(line, PagePoint(100f, 50f), points = tilted))

        assertNull(drag.stored, "a point on the edited chord must count as straight")
    }

    @Test
    fun `the straighten tolerance is measured on the page and not in the shape own units`() {
        val scaled = ShapePlacement(ShapeKind.LINE, 100f, 4f, Matrix3.scale(4f, 4f))

        assertNull(assertNotNull(bendAt(scaled, PagePoint(200f, 14f))).stored)
        assertNotNull(assertNotNull(bendAt(scaled, PagePoint(200f, 28f))).stored)
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
        // ⚠️ There is no `vertices(kind)` to assert against any more: *every* kind edits its points,
        // so a predicate answering a constant was a question with no information in it — detekt said
        // so, and deleting it is the honest form of the rule. `bends` is the one that still varies.
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
    fun `vertices round trip through the record and re-encode byte for byte`() {
        val record = ShapeRecord(points = listOf(PagePoint(0f, 0f), PagePoint(180f, -40.5f), PagePoint(0f, 60f)))

        val wire = record.encode()
        val back = assertNotNull(ShapeRecord.decode(wire))

        assertEquals(record.points, back.points)
        assertEquals(wire, back.encode())
    }

    @Test
    fun `a segment's two ends round trip because two is a legal list now`() {
        // ⚠️ MIN_POINTS was 3 until a segment's ends moved into this register, which would have made
        // the decoder refuse a list the encoder had just written.
        val record = ShapeRecord(points = listOf(PagePoint(-20f, 10f), PagePoint(260f, 90f)))

        assertEquals(record.points, assertNotNull(ShapeRecord.decode(record.encode())).points)
    }

    @Test
    fun `one unreadable vertex abandons the whole list rather than shortening it`() {
        // ⚠️ Every other key here degrades to its default. This one cannot: dropping a vertex from a
        // quadrilateral yields a triangle, which is defined, non-crashing and a different shape.
        assertNull(assertNotNull(ShapeRecord.decode("is1;pts=0:0,180:oops,0:60")).points)
        assertNull(assertNotNull(ShapeRecord.decode("is1;pts=0:0,180:20:9,0:60")).points)
        assertNull(assertNotNull(ShapeRecord.decode("is1;pts=0:0")).points, "one point is not geometry")
    }

    @Test
    fun `a curved line is not a default record`() {
        assertTrue(!ShapeRecord(bend = PagePoint(10f, 10f)).isDefault)
        assertTrue(ShapeRecord(bend = null).isDefault)
    }

    @Test
    fun `an unreadable bend leaves the line straight and keeps the rest of the record`() {
        val record = assertNotNull(ShapeRecord.decode("is1;f=-16777216;bd=oops"))

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

    private fun bendAt(
        placement: ShapePlacement,
        toPage: PagePoint,
        points: List<PagePoint>? = null,
        tolerance: Float = 8f,
    ): BendDrag? = ShapePoints.bend(
        widthPt = placement.widthPt,
        heightPt = placement.heightPt,
        transform = placement.transform,
        points = points,
        toPage = toPage,
        straightenTolerancePt = tolerance,
    )

    private fun assertPoint(expected: PagePoint, actual: PagePoint) {
        assertEquals(expected.x, actual.x, TOLERANCE, "x")
        assertEquals(expected.y, actual.y, TOLERANCE, "y")
    }

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
