package dev.inkstone.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
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
    fun `everything with corners rounds and only a closed shape fills`() {
        // ⚠️ The triangle is the point of this one. It answered false for six milestones, on the
        // reasoning that a single radius cannot suit both a wide corner and a sharp one — see
        // ShapeGeometry.roundsCorners for why clamping per corner makes that a non-problem. An
        // ellipse still refuses, because it has no corners at all.
        assertTrue(ShapeGeometry.roundsCorners(ShapeKind.RECTANGLE))
        assertTrue(ShapeGeometry.roundsCorners(ShapeKind.TRIANGLE))
        assertTrue(!ShapeGeometry.roundsCorners(ShapeKind.ELLIPSE))
        assertTrue(!ShapeGeometry.roundsCorners(ShapeKind.LINE))
        assertTrue(ShapeGeometry.isFillable(ShapeKind.TRIANGLE))
        assertTrue(!ShapeGeometry.isFillable(ShapeKind.ARROW))
    }

    @Test
    fun `a connector can attach only to a shape with an inside`() {
        // A line answered yes by omission, and a line drawn starting on another line became a
        // connector bound to it. See ShapeKind.isAnchorable.
        assertTrue(ShapeKind.RECTANGLE.isAnchorable)
        assertTrue(ShapeKind.ELLIPSE.isAnchorable)
        assertTrue(ShapeKind.TRIANGLE.isAnchorable)
        assertTrue(!ShapeKind.LINE.isAnchorable)
        assertTrue(!ShapeKind.ARROW.isAnchorable)
    }

    @Test
    fun `a rounded triangle keeps its box and pulls its outline in at the corners`() {
        val sharp = ShapeGeometry.outline(ShapeKind.TRIANGLE, 120f, 100f)
        val round = ShapeGeometry.outline(ShapeKind.TRIANGLE, 120f, 100f, cornerRadiusPt = 12f)
        assertTrue(round.closed)
        assertTrue(round.segments.any { it is ShapeSegment.CubicTo })

        // Still inside the box it was drawn in — a fillet only ever removes area.
        val points = round.flattened(samplesPerCurve = 16)
        assertTrue(points.all { it.x in -TOLERANCE..(120f + TOLERANCE) })
        assertTrue(points.all { it.y in -TOLERANCE..(100f + TOLERANCE) })

        // And it is genuinely rounder: the apex is the sharpest corner, so it is the one that
        // visibly retreats from the top edge.
        assertTrue(points.minOf { it.y } > sharp.flattened().minOf { it.y } + TOLERANCE)
    }

    @Test
    fun `a radius far larger than the shape rounds each corner by as much as it has room for`() {
        // The clamp is what makes "round this" mean something for a 15-degree sliver and a right
        // angle at once. Unclamped, the tangent points run past the ends of their own edges and the
        // outline folds through itself — which shows up as points outside the box.
        val path = ShapeGeometry.outline(ShapeKind.TRIANGLE, 60f, 200f, cornerRadiusPt = 400f)
        val points = path.flattened(samplesPerCurve = 16)
        assertTrue(points.all { it.x in -TOLERANCE..(60f + TOLERANCE) })
        assertTrue(points.all { it.y in -TOLERANCE..(200f + TOLERANCE) })
    }

    @Test
    fun `edited vertices round too rather than dropping the radius`() {
        // ⚠️ An explicit vertex list used to discard the corner radius outright. A quadrilateral a
        // user has dragged into shape is exactly the thing they then want rounded.
        val quad = listOf(PagePoint(0f, 0f), PagePoint(90f, 10f), PagePoint(80f, 70f), PagePoint(5f, 60f))
        val sharp = ShapeGeometry.outline(ShapeKind.RECTANGLE, 90f, 70f, points = quad)
        val round = ShapeGeometry.outline(ShapeKind.RECTANGLE, 90f, 70f, cornerRadiusPt = 10f, points = quad)
        assertTrue(sharp.segments.none { it is ShapeSegment.CubicTo })
        assertEquals(4, round.segments.count { it is ShapeSegment.CubicTo })
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

    // -----------------------------------------------------------------------
    // Rotation invariance — the property M8b's classifier did not have
    // -----------------------------------------------------------------------

    @Test
    fun `every closed kind survives being drawn at an angle`() {
        // ⭐ The regression this file exists for. Against the AXIS-ALIGNED box a square's fill is
        // 1/(1 + k·sin 2θ), which sweeps the entire feature range as the hand tilts — so a square
        // drawn at 7° classified as an ELLIPSE and one at 45° as a TRIANGLE at confidence 1.000,
        // while a circle (the one shape whose axis-aligned fill does not move) worked at every
        // angle. That is the whole of "i can only do ovels/circles".
        //
        // Against the minimum-area box the fill is a property of the shape and nothing else.
        for (degrees in listOf(0, 3, 7, 12, 20, 30, 45, 60, 89)) {
            val turn = degrees * PI.toFloat() / 180f
            assertEquals(
                ShapeKind.RECTANGLE,
                assertNotNull(ShapeSnap.recognise(turned(rectangle(200f, 120f), turn)), "rect at $degrees°").kind,
                "a rectangle drawn at $degrees°",
            )
            assertEquals(
                ShapeKind.ELLIPSE,
                assertNotNull(ShapeSnap.recognise(turned(ellipse(200f, 120f), turn)), "ellipse at $degrees°").kind,
                "an ellipse drawn at $degrees°",
            )
            assertEquals(
                ShapeKind.TRIANGLE,
                assertNotNull(ShapeSnap.recognise(turned(triangle(200f, 120f), turn)), "tri at $degrees°").kind,
                "a triangle drawn at $degrees°",
            )
        }
    }

    @Test
    fun `a square drawn at forty-five degrees is a rectangle and not a triangle`() {
        // Named separately because it is the single worst case of the old feature: fill was exactly
        // 0.5, which is the TRIANGLE target, so a diamond scored triangle at confidence 1.000 —
        // maximum confidence, maximally wrong.
        val candidate = assertNotNull(ShapeSnap.recognise(turned(rectangle(160f, 160f), PI.toFloat() / 4f)))
        assertEquals(ShapeKind.RECTANGLE, candidate.kind)
    }

    @Test
    fun `a rectangle drawn at an angle is placed at that angle`() {
        val turn = 25f * PI.toFloat() / 180f
        val candidate = assertNotNull(ShapeSnap.recognise(turned(rectangle(200f, 120f), turn)))
        val oriented = assertNotNull(candidate.oriented, "a closed candidate carries its oriented box")
        assertClose(turn, oriented.angleRadians, "placed at the angle it was drawn")
        // The extents are the shape's own, not the axis-aligned hull's — which for this rectangle
        // would be 232 x 193 rather than 200 x 120.
        assertClose(200f, oriented.widthPt)
        assertClose(120f, oriented.heightPt)
    }

    @Test
    fun `a nearly-square rectangle is straightened rather than left visibly tilted`() {
        // Nobody draws to within two degrees on purpose, and a shape sitting at 1.4° next to a line
        // of text reads as a mistake the user cannot easily correct.
        val candidate = assertNotNull(ShapeSnap.recognise(turned(rectangle(200f, 120f), 0.017f)))
        assertEquals(0f, assertNotNull(candidate.oriented).angleRadians)
    }

    @Test
    fun `an open arc is not an ellipse however far round it goes`() {
        // M8b's CLOSED_MAX_GAP of 0.22 let a 300° arc through at ellipse-confidence 0.84, so a C,
        // a bracket or any large open curve held still was eaten and turned into an oval. This is
        // a completely separate mechanism from the fill ratio and the second half of the complaint.
        for (sweep in listOf(180, 240, 270, 300)) {
            val kind = ShapeSnap.recognise(arc(200f, 120f, sweep))?.kind
            assertTrue(kind != ShapeKind.ELLIPSE, "a $sweep° arc came back as an ellipse")
        }
        // …while a circle the user closed properly still snaps.
        assertEquals(ShapeKind.ELLIPSE, assertNotNull(ShapeSnap.recognise(arc(200f, 120f, 355))).kind)
    }

    // -----------------------------------------------------------------------
    // ⭐ Curves — "when not drawing a straight line it should create a curve out of that"
    // -----------------------------------------------------------------------

    @Test
    fun `a drawn arc becomes a curve through the ink`() {
        val drawn = arc(200f, 200f, 140)

        val candidate = assertNotNull(ShapeSnap.recognise(drawn), "a 140° arc should snap")

        // A curve *is* a line plus a bend — there is no CURVE kind, because a sixth ShapeKind is a
        // note an older build cannot open (SPEC 4.8.8).
        assertEquals(ShapeKind.LINE, candidate.kind)
        val bend = assertNotNull(candidate.bend, "the arc lost its apex")
        // The apex is on the ink the user drew, not somewhere off it: a control point would be.
        assertTrue(
            drawn.any { hypot(it.x - bend.x, it.y - bend.y) < 1f },
            "the bend at $bend is not one of the drawn points",
        )
    }

    @Test
    fun `the curve a snapped arc draws follows the arc that was drawn`() {
        val drawn = arc(200f, 200f, 140)
        val candidate = assertNotNull(ShapeSnap.recognise(drawn))
        val placement = candidate.placement(bandPt = 2f)

        val curve = ShapeGeometry
            .outline(ShapeKind.LINE, placement.widthPt, placement.heightPt, bendLocal = candidate.bendLocal(placement))
            .under(placement.transform)
            .flattened(samplesPerCurve = 24)

        // Every drawn point is near the curve that replaces it — the only assertion that says the
        // snap kept the user's gesture rather than merely producing *a* curve between its ends.
        val worst = drawn.maxOf { point -> curve.minOf { hypot(it.x - point.x, it.y - point.y) } }
        val chord = hypot(drawn.last().x - drawn.first().x, drawn.last().y - drawn.first().y)
        assertTrue(worst < chord * 0.08f, "the snapped curve strays ${worst}pt from the ink")
    }

    // -----------------------------------------------------------------------
    // ⭐ "it is far too easy to make a circle" — the rectangle's one-sided window
    // -----------------------------------------------------------------------

    @Test
    fun `an imperfect square is a rectangle and not an ellipse`() {
        // A hand's square loses fill to rounded corners, bowed edges and overshoot past a corner
        // (which widens the hull and therefore the box). Every one of those moves it *toward* the
        // ellipse, because 1.0 is the top of the range — so a square that is 8% short used to be
        // refused as a rectangle and accepted as an oval.
        for (rounding in listOf(0.06f, 0.12f, 0.18f)) {
            assertEquals(
                ShapeKind.RECTANGLE,
                assertNotNull(ShapeSnap.recognise(roundedSquare(200f, rounding)), "r=$rounding").kind,
                "a square with corners rounded by $rounding of its side came back as something else",
            )
        }
    }

    @Test
    fun `a circle is still a circle and keeps a wide margin over the rectangle`() {
        // The other half of the trade, asserted so the allowance cannot grow until it eats ellipses.
        val candidate = assertNotNull(ShapeSnap.recognise(ellipse(200f, 200f)))
        assertEquals(ShapeKind.ELLIPSE, candidate.kind)
        assertTrue(candidate.confidence > 0.9f, "a perfect circle scored only ${candidate.confidence}")
        assertEquals(ShapeKind.ELLIPSE, assertNotNull(ShapeSnap.recognise(ellipse(240f, 140f))).kind)
    }

    @Test
    fun `a corner stays ink and the boundary is where the measurements put it`() {
        // ⚠️ The gesture that sets ARC_MAX_RESIDUAL. A rounded corner and a deep arc are genuinely
        // similar shapes, and no threshold separates them cleanly — so the limit sits below the
        // corner rather than above the deepest arc, because turning someone's elbow into a smooth
        // curve is a drawing that changed under them, where a refused half-circle is only a feature
        // that did not fire.
        for (radius in listOf(0.5f, 10f, 20f)) {
            assertNull(ShapeSnap.recognise(roundedCorner(radius)), "a corner rounded by $radius snapped")
        }
        // ⭐ …and the far side of the boundary, asserted rather than left to drift: a corner rounded
        // by 30 of a 100 pt arm is a quarter circle with two stubs on it, and it *does* snap. If a
        // future change moves this line, it has moved the boundary — which is the thing to argue
        // about, not the constant.
        assertEquals(ShapeKind.LINE, assertNotNull(ShapeSnap.recognise(roundedCorner(30f))).kind)
    }

    @Test
    fun `an S is not one arc and stays ink`() {
        // Two humps cannot lie on one quadratic, and a quadratic is what a curve is drawn as. This
        // is the guard that keeps "snap my curve" from becoming "snap anything I hold still on".
        val s = (0..40).map { step ->
            val t = step / 40f
            PagePoint(t * 300f, 40f * sin(t * 2f * PI.toFloat()))
        }
        assertNull(ShapeSnap.recognise(s))
    }

    @Test
    fun `a straight line is still a line and not a shallow curve`() {
        val candidate = assertNotNull(ShapeSnap.recognise(line(0f, 0f, 300f, 120f)))

        assertEquals(ShapeKind.LINE, candidate.kind)
        assertNull(candidate.bend, "a straight line must not acquire a bend")
    }

    @Test
    fun `nothing straight enough to be a line ever comes back as a curve`() {
        // ⭐ **The property, not the constant.** The line and the arc are separated by their
        // *features* and not by comparing two confidence ramps — a line's is 0.03 wide and falls off
        // a cliff, an arc's is nearly flat, and at a 60° bow the line was winning by 0.03, which is
        // noise on a real gesture. *"Straight lines dont create properly"* was the result.
        //
        // ⚠️ This is what pins ARC_MIN_SAGITTA to LINE_MIN_STRAIGHTNESS. Move either constant
        // without the other and this fails, which is the whole point of asserting it this way.
        for (sweep in 2..80 step 2) {
            val drawn = arc(200f, 200f, sweep)
            val chord = hypot(drawn.last().x - drawn.first().x, drawn.last().y - drawn.first().y)
            val path = drawn.zipWithNext().sumOf { (a, b) -> hypot(b.x - a.x, b.y - a.y).toDouble() }.toFloat()
            if (chord / path < ShapeSnap.LINE_MIN_STRAIGHTNESS) continue
            val candidate = ShapeSnap.recognise(drawn)
            assertNull(candidate?.bend, "a $sweep° bow is straight enough to be a line but became a curve")
        }
    }

    @Test
    fun `a bow deep enough to have been refused as a line is still a curve`() {
        // The other side of the same boundary: making the two disjoint must not cost the feature.
        //
        // ⚠️ 140° is the top of the range, and the ceiling is ARC_MAX_RESIDUAL rather than the
        // sagitta — a 150° arc's sagitta (0.384) is well inside the window, but one quadratic
        // strays 5.2% of the chord from it and the residual bar is 5%. That is the *deliberate*
        // limit (a quadratic is a parabola), not a side effect of separating the line from the arc.
        for (sweep in listOf(80, 90, 120, 140)) {
            assertNotNull(
                assertNotNull(ShapeSnap.recognise(arc(200f, 200f, sweep)), "a $sweep° arc snapped to nothing").bend,
                "a $sweep° arc came back without a bend",
            )
        }
    }

    @Test
    fun `the minimum-area box of a tilted rectangle is the rectangle itself`() {
        // The theorem the classifier now rests on, asserted directly: the minimal enclosing
        // rectangle of a convex polygon is flush with one of its edges.
        val turn = 33f * PI.toFloat() / 180f
        val points = turned(rectangle(180f, 100f), turn)
        val xs = FloatArray(points.size) { points[it].x }
        val ys = FloatArray(points.size) { points[it].y }
        val box = assertNotNull(OrientedBounds.minimumArea(xs, ys, points.size))
        assertClose(180f * 100f, box.area, "no larger than the rectangle drawn")
    }

    @Test
    fun `the minimum-area box of a triangle is twice its area at every angle`() {
        for (degrees in listOf(0, 17, 40, 70)) {
            val points = turned(triangle(200f, 120f), degrees * PI.toFloat() / 180f)
            val xs = FloatArray(points.size) { points[it].x }
            val ys = FloatArray(points.size) { points[it].y }
            val box = assertNotNull(OrientedBounds.minimumArea(xs, ys, points.size))
            // A triangle of base w and height h has area wh/2; its minimal box is wh.
            assertTrue(
                abs(box.area - 200f * 120f) < 200f * 120f * 0.02f,
                "at $degrees° expected ~24000 but was ${box.area}",
            )
        }
    }

    @Test
    fun `collinear points have no orientation to find`() {
        val xs = FloatArray(10) { it * 10f }
        val ys = FloatArray(10) { it * 10f }
        assertNull(OrientedBounds.minimumArea(xs, ys, 10))
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

    // -----------------------------------------------------------------------
    // Duplication — the selection tool's shape copy (ShapeEdit.duplicate)
    // -----------------------------------------------------------------------

    @Test
    fun `duplicate collapses the live registers onto the copy and emits one AddObject`() {
        val source = ShapeObject(
            id = ObjectId("shape-1"),
            shape = ShapeKind.RECTANGLE,
            widthPt = 100f,
            heightPt = 60f,
            // Stale on purpose: the payload's values are what the shape was *created* with, and
            // the copy must carry the live registers instead.
            transform = Matrix3.translate(10f, 10f),
            style = ObjectStyle(colorArgb = 0xFF112233.toInt(), widthPt = 5f),
        )
        val liveTransform = Matrix3.translate(28f, 28f)
        val liveStyle = ObjectStyle(colorArgb = 0xFF445566.toInt(), widthPt = 7f)
        val bite = ErasedArea(listOf(EraseCapsule(0f, 0f, 10f, 0f, 3f)))
        val z = FractionalKey.between(null, null)

        val ops = ShapeEdit.duplicate(
            source = source,
            id = ObjectId("copy-1"),
            transform = liveTransform,
            style = liveStyle,
            erased = bite,
            z = z,
            record = ShapeRecord.None,
            nextOpId = ::nextId,
            nextClock = ::nextClock,
        )

        assertEquals(1, ops.size, "a default record must not put a string in the file")
        val copy = (ops.single() as PageOp.AddObject).obj as ShapeObject
        assertEquals(ObjectId("copy-1"), copy.id)
        assertEquals(ShapeKind.RECTANGLE, copy.shape)
        assertEquals(100f, copy.widthPt)
        assertEquals(60f, copy.heightPt)
        assertEquals(liveTransform, copy.transform)
        assertEquals(liveStyle, copy.style)
        assertEquals(z, copy.z)
        // The bite travels with the copy: it is in the object's own space.
        assertEquals(bite, copy.erased)
    }

    @Test
    fun `duplicating a connector re-targets ends inside the copy and keeps ends outside it`() {
        val record = ShapeRecord(
            endA = ConnectorEnd.Attached(ObjectId("box-in"), ConnectorAnchor.Auto),
            endB = ConnectorEnd.Attached(ObjectId("box-out"), ConnectorAnchor.Auto),
        )
        // What SelectionController hands in: only ids duplicated alongside the connector remap.
        val remap = mapOf(ObjectId("box-in") to ObjectId("box-in-copy"))

        val ops = ShapeEdit.duplicate(
            source = ShapeObject(ObjectId("conn-1"), ShapeKind.LINE, widthPt = 200f, heightPt = 2f),
            id = ObjectId("copy-2"),
            transform = Matrix3.Identity,
            style = null,
            erased = ErasedArea.None,
            z = FractionalKey.between(null, null),
            record = record.remapTargets { remap[it] },
            nextOpId = ::nextId,
            nextClock = ::nextClock,
        )

        assertEquals(2, ops.size)
        val write = ops[1] as PageOp.SetTextContent
        assertEquals(ObjectId("copy-2"), write.target)
        val decoded = ShapeRecord.decode(write.text)
        assertNotNull(decoded)
        assertEquals(ConnectorEnd.Attached(ObjectId("box-in-copy"), ConnectorAnchor.Auto), decoded.endA)
        assertEquals(ConnectorEnd.Attached(ObjectId("box-out"), ConnectorAnchor.Auto), decoded.endB)
    }

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

    /**
     * A square of side [side] whose corners are rounded by [rounding] of that side — the nearest a
     * fixture gets to a square drawn by a hand, and the gesture the fill ratio punishes.
     */
    private fun roundedSquare(side: Float, rounding: Float): List<PagePoint> {
        val r = side * rounding
        val points = ArrayList<PagePoint>()
        // Corner centres, in drawing order: top-left, top-right, bottom-right, bottom-left.
        val centres = listOf(
            PagePoint(r, r) to PI.toFloat(),
            PagePoint(side - r, r) to -PI.toFloat() / 2f,
            PagePoint(side - r, side - r) to 0f,
            PagePoint(r, side - r) to PI.toFloat() / 2f,
        )
        for ((index, corner) in centres.withIndex()) {
            val (centre, start) = corner
            for (step in 0..CORNER_STEPS) {
                val angle = start + PI.toFloat() / 2f * step / CORNER_STEPS
                points += PagePoint(centre.x + r * cos(angle), centre.y + r * sin(angle))
            }
            // ⚠️ **The straight edges are sampled too**, and leaving them out is not a harmless
            // simplification: a fixture with points only on its corner arcs is 37 points that are
            // *all* corner, which flatters any turning-based measure and — because it is under 48 —
            // used to trip a resampling guard and be classified round. A drawn square has samples
            // along its sides.
            // ⚠️ Toward the next arc's **start point**, not the next corner's centre. Aiming at the
            // centre pulls the edges inward and cuts the corners off: this fixture's fill read 0.799
            // at an 18% rounding — an *oval's* number — and a threshold was very nearly moved to
            // accommodate a shape the fixture had deformed. The real figure is 0.972.
            val (nextCentre, nextStart) = centres[(index + 1) % centres.size]
            val next = PagePoint(
                nextCentre.x + r * cos(nextStart),
                nextCentre.y + r * sin(nextStart),
            )
            val from = points.last()
            for (step in 1..EDGE_STEPS) {
                val fraction = step.toFloat() / (EDGE_STEPS + 1)
                points += PagePoint(
                    from.x + (next.x - from.x) * fraction,
                    from.y + (next.y - from.y) * fraction,
                )
            }
        }
        points += points.first()
        return points
    }

    /** Two 100 pt arms meeting at a right angle, joined by a quarter circle of [radius]. */
    private fun roundedCorner(radius: Float, arm: Float = 100f): List<PagePoint> {
        val steps = 12
        val points = ArrayList<PagePoint>(steps * 3 + 1)
        for (step in 0..steps) points += PagePoint(-arm + (arm - radius) * step / steps, 0f)
        for (step in 1..steps) {
            val angle = PI.toFloat() / 2f * step / steps
            points += PagePoint(-radius + radius * sin(angle), radius - radius * cos(angle))
        }
        for (step in 1..steps) points += PagePoint(0f, radius + (arm - radius) * step / steps)
        return points
    }

    /** [sweepDegrees] of an ellipse, left open — the gesture `CLOSED_MAX_GAP` has to refuse. */
    private fun arc(w: Float, h: Float, sweepDegrees: Int): List<PagePoint> {
        val steps = STEPS * 2
        return (0..steps).map { step ->
            val angle = sweepDegrees * PI.toFloat() / 180f * step / steps
            PagePoint(w / 2f + w / 2f * cos(angle), h / 2f + h / 2f * sin(angle))
        }
    }

    /** [points] rotated by [radians] about the origin — the same shape, drawn by a tilted hand. */
    private fun turned(points: List<PagePoint>, radians: Float): List<PagePoint> {
        val cosine = cos(radians)
        val sine = sin(radians)
        return points.map { PagePoint(it.x * cosine - it.y * sine, it.x * sine + it.y * cosine) }
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

        /** Samples along each rounded corner, and along each straight edge — see [roundedSquare]. */
        const val CORNER_STEPS = 8
        const val EDGE_STEPS = 3
        const val TOLERANCE = 0.05f
    }
}
