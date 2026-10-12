package dev.inkstone.core.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ⭐ Snap-to-grid, as arithmetic.
 *
 * The claims worth writing down are the ones a device walk cannot make cheaply: that the lattice
 * matches the *drawn* template, that a segment snaps by its endpoints and a closed shape keeps its
 * angle, and that a resize touches only the edges its handle moved. What a fixture cannot say is
 * whether the pitch feels right under a hand — CLAUDE.md's *"a synthetic fixture is the worst
 * possible witness for a geometric heuristic"* — which is why the numbers below are the paper's
 * own rather than tuned here.
 */
class SnapGridTest {

    private val grid = SnapGrid(spacingXPt = 10f, spacingYPt = 10f)

    @Test
    fun rounds_to_the_nearest_line_on_both_axes() {
        assertEquals(20f, grid.snapX(23f))
        assertEquals(30f, grid.snapX(26f))
        assertEquals(-10f, grid.snapY(-12f))
    }

    @Test
    fun an_origin_offsets_the_whole_lattice() {
        val ruled = SnapGrid(spacingXPt = 0f, spacingYPt = 24f, originYPt = 36f)
        assertEquals(36f, ruled.snapY(38f))
        assertEquals(60f, ruled.snapY(55f))
    }

    @Test
    fun a_free_axis_returns_its_argument() {
        val ruled = SnapGrid(spacingXPt = 0f, spacingYPt = 24f, originYPt = 36f)
        assertFalse(ruled.snapsX)
        assertEquals(123.4f, ruled.snapX(123.4f))
        assertFalse(ruled.isEmpty)
    }

    @Test
    fun the_empty_grid_changes_nothing() {
        assertTrue(SnapGrid.None.isEmpty)
        assertEquals(7.3f, SnapGrid.None.snapX(7.3f))
        assertEquals(7.3f, SnapGrid.None.snapY(7.3f))
    }

    @Test
    fun offset_is_the_correction_a_move_adds() {
        val offset = grid.offsetTo(23f, 26f)
        assertEquals(-3f, offset.x)
        assertEquals(4f, offset.y)
    }

    @Test
    fun an_extent_rounds_to_whole_cells_and_never_to_nothing() {
        assertEquals(20f, grid.snapWidth(23f))
        assertEquals(10f, grid.snapWidth(1f), "a shape smaller than a cell keeps one cell, not zero")
        assertEquals(10f, grid.snapHeight(0.1f))
    }

    // -----------------------------------------------------------------------
    // Placements
    // -----------------------------------------------------------------------

    @Test
    fun a_segment_snaps_by_its_endpoints() {
        val placed = ShapeEdit.placeSegment(2f, 3f, 41f, 27f, bandPt = 3f)
        val snapped = grid.snapped(placed)
        val (a, b) = endpointsOf(snapped)
        assertEquals(0f, a.x)
        assertEquals(0f, a.y)
        assertEquals(40f, b.x)
        assertEquals(30f, b.y)
    }

    /** Two intersections at the same y *are* a horizontal line — the point of snapping a line. */
    @Test
    fun a_nearly_horizontal_line_comes_out_horizontal() {
        val placed = ShapeEdit.placeSegment(1f, 19f, 59f, 22f, bandPt = 3f)
        val (a, b) = endpointsOf(grid.snapped(placed))
        assertEquals(a.y, b.y)
        assertEquals(20f, a.y)
    }

    @Test
    fun a_segment_that_would_collapse_is_left_alone() {
        val placed = ShapeEdit.placeSegment(1f, 1f, 2f, 2f, bandPt = 3f)
        val snapped = grid.snapped(placed)
        val (a, b) = endpointsOf(snapped)
        assertNotEquals(a, b, "both ends round to (0, 0); the line must survive rather than vanish")
        assertEquals(placed, snapped)
    }

    @Test
    fun a_closed_shape_puts_its_origin_on_the_lattice_and_rounds_its_extents() {
        val placed = ShapeEdit.place(ShapeKind.RECTANGLE, PageRect(3f, 7f, 46f, 34f))
        val snapped = grid.snapped(placed)
        val origin = snapped.transform.map(PagePoint(0f, 0f))
        assertEquals(0f, origin.x)
        assertEquals(10f, origin.y)
        assertEquals(40f, snapped.widthPt)
        assertEquals(30f, snapped.heightPt)
    }

    @Test
    fun a_rotated_shape_keeps_its_angle() {
        val box = OrientedBox(centreX = 51f, centreY = 33f, widthPt = 42f, heightPt = 21f, angleRadians = QUARTER)
        val placed = ShapeEdit.placeOriented(ShapeKind.RECTANGLE, box)
        val snapped = grid.snapped(placed)
        // The linear part is untouched: only a translation was composed in front of it.
        assertEquals(placed.transform.m00, snapped.transform.m00, TOLERANCE)
        assertEquals(placed.transform.m01, snapped.transform.m01, TOLERANCE)
        assertEquals(placed.transform.m10, snapped.transform.m10, TOLERANCE)
        assertEquals(placed.transform.m11, snapped.transform.m11, TOLERANCE)
        val origin = snapped.transform.map(PagePoint(0f, 0f))
        assertEquals(grid.snapX(origin.x), origin.x, TOLERANCE)
        assertEquals(grid.snapY(origin.y), origin.y, TOLERANCE)
    }

    // -----------------------------------------------------------------------
    // Moving
    // -----------------------------------------------------------------------

    @Test
    fun a_move_puts_the_selection_s_top_left_on_the_lattice() {
        val box = PageRect(3f, 7f, 43f, 47f)
        val delta = grid.moveBy(box, dx = 12f, dy = 6f, alignment = Alignment.None)
        assertEquals(17f, delta.x, "3 + 12 = 15, which rounds to 20")
        assertEquals(3f, delta.y, "7 + 6 = 13, which rounds to 10")
    }

    /** A guide the user can see beats a lattice that is always there — per axis. */
    @Test
    fun an_alignment_guide_wins_its_own_axis_and_no_other() {
        val box = PageRect(3f, 7f, 43f, 47f)
        val guide = AlignmentGuide(GuideAxis.VERTICAL, positionPt = 17f, kind = GuideKind.EDGE)
        val guided = Alignment(dx = 2f, dy = 0f, guides = listOf(guide))
        val delta = grid.moveBy(box, dx = 12f, dy = 6f, alignment = guided)
        assertEquals(14f, delta.x, "the guide's own correction, unrounded")
        assertEquals(3f, delta.y, "y had no guide, so the lattice speaks")
    }

    @Test
    fun the_empty_grid_leaves_a_move_as_the_guides_left_it() {
        val box = PageRect(3f, 7f, 43f, 47f)
        val guided = Alignment(dx = 2f, dy = -1f, guides = emptyList())
        val delta = SnapGrid.None.moveBy(box, dx = 12f, dy = 6f, alignment = guided)
        assertEquals(14f, delta.x)
        assertEquals(5f, delta.y)
    }

    // -----------------------------------------------------------------------
    // Resizing
    // -----------------------------------------------------------------------

    @Test
    fun a_resize_snaps_only_the_edges_that_moved() {
        val from = PageRect(3f, 7f, 43f, 47f)
        val to = PageRect(3f, 7f, 51f, 47f) // a RIGHT grip drag
        val snapped = grid.snappedResize(from, to, minimumPt = 8f)
        assertEquals(3f, snapped.left, "the fixed edge must not drift onto the lattice")
        assertEquals(7f, snapped.top)
        assertEquals(47f, snapped.bottom)
        assertEquals(50f, snapped.right)
    }

    @Test
    fun a_resize_keeps_the_minimum_by_moving_the_dragged_edge() {
        val from = PageRect(0f, 0f, 40f, 40f)
        // A LEFT grip dragged almost onto the right edge; rounding would collapse it entirely.
        val to = PageRect(38f, 0f, 40f, 40f)
        val snapped = grid.snappedResize(from, to, minimumPt = 8f)
        assertEquals(40f, snapped.right, "the anchored edge stays put")
        assertTrue(snapped.right - snapped.left >= 8f)
    }

    // -----------------------------------------------------------------------
    // The paper's own lattice
    // -----------------------------------------------------------------------

    @Test
    fun a_grid_page_snaps_to_the_squares_it_draws() {
        val snap = SnapGrid.forBackground(Background.Template(PageTemplate.GRID))
        assertEquals(PageTemplate.GRID.spacingPt, snap.spacingXPt)
        assertEquals(PageTemplate.GRID.spacingPt, snap.spacingYPt)
        assertEquals(0f, snap.originXPt, "TemplateBackground rules at multiples of the spacing")
        assertTrue(snap.drawnByThePaper)
    }

    @Test
    fun a_dot_grid_page_snaps_exactly_as_a_grid_page_does() {
        assertEquals(
            SnapGrid.forBackground(Background.Template(PageTemplate.GRID)),
            SnapGrid.forBackground(Background.Template(PageTemplate.DOT_GRID)),
        )
    }

    /** The rule this whole file serves: never snap to a line the paper does not draw. */
    @Test
    fun a_ruled_page_snaps_to_its_rules_and_leaves_x_free() {
        val snap = SnapGrid.forBackground(Background.Template(PageTemplate.RULED))
        assertFalse(snap.snapsX)
        assertEquals(PageTemplate.RULED.spacingPt, snap.spacingYPt)
        assertEquals(PageTemplate.RULED_MARGIN_PT, snap.originYPt, "the first rule is at the margin")
        assertEquals(PageTemplate.RULED_MARGIN_PT, snap.snapY(PageTemplate.RULED_MARGIN_PT + 2f))
        assertTrue(snap.drawnByThePaper)
    }

    @Test
    fun blank_paper_and_a_pdf_page_get_a_lattice_nothing_draws() {
        for (background in listOf(Background.Template(PageTemplate.BLANK), Background.SourcePdfPage(0))) {
            val snap = SnapGrid.forBackground(background)
            assertEquals(SnapGrid.DEFAULT_SPACING_PT, snap.spacingXPt, "$background")
            assertEquals(SnapGrid.DEFAULT_SPACING_PT, snap.spacingYPt, "$background")
            assertFalse(snap.drawnByThePaper, "$background — the surface must draw this one as chrome")
        }
    }

    /** Turning a blank page into a grid page must not move what a shape snaps to. */
    @Test
    fun the_default_pitch_is_the_grid_template_s_own() {
        assertEquals(PageTemplate.GRID.spacingPt, SnapGrid.DEFAULT_SPACING_PT)
    }

    // -----------------------------------------------------------------------
    // Through the recogniser, which is how a drawn shape reaches this
    // -----------------------------------------------------------------------

    @Test
    fun a_recognised_candidate_places_on_the_lattice() {
        val candidate = SnapCandidate(
            kind = ShapeKind.LINE,
            box = PageRect(2f, 3f, 41f, 27f),
            from = PagePoint(2f, 3f),
            to = PagePoint(41f, 27f),
            confidence = 1f,
        )
        val (a, b) = endpointsOf(candidate.placement(bandPt = 3f, grid = grid))
        // Per component and with a tolerance: the placement composes a rotation, so an endpoint
        // comes back through two trig calls and lands a float ulp or two off the lattice.
        assertEquals(0f, a.x)
        assertEquals(0f, a.y)
        assertEquals(40f, b.x)
        assertEquals(30f, b.y)
    }

    @Test
    fun no_grid_leaves_a_candidate_exactly_where_it_was() {
        val candidate = SnapCandidate(
            kind = ShapeKind.LINE,
            box = PageRect(2f, 3f, 41f, 27f),
            from = PagePoint(2f, 3f),
            to = PagePoint(41f, 27f),
            confidence = 1f,
        )
        assertEquals(candidate.placement(bandPt = 3f), candidate.placement(bandPt = 3f, grid = SnapGrid.None))
    }

    /** [ShapeEdit.placeSegment]'s inverse: the two ends of the band's middle. */
    private fun endpointsOf(placement: ShapePlacement): Pair<PagePoint, PagePoint> {
        val middle = placement.heightPt / 2f
        return placement.transform.map(PagePoint(0f, middle)) to
            placement.transform.map(PagePoint(placement.widthPt, middle))
    }

    private fun assertEquals(expected: Float, actual: Float, message: String? = null) {
        assertTrue(abs(expected - actual) < TOLERANCE, "${message.orEmpty()} expected $expected but was $actual")
    }

    @Test
    fun a_move_by_a_pivot_lands_the_pivot_and_not_the_box_corner() {
        // SPEC §4.9.7 — an Element copy snaps by the point somebody chose, not by its bounding box.
        val grid = SnapGrid(spacingXPt = 0f, spacingYPt = 24f, originYPt = 36f)
        val pivot = PagePoint(90f, 101f)
        val delta = grid.moveBy(pivot, dx = 3f, dy = 10f, alignment = Alignment.None)
        assertEquals(93f, pivot.x + delta.x, TOLERANCE)
        assertEquals(108f, pivot.y + delta.y, TOLERANCE)
    }

    @Test
    fun a_move_by_a_box_is_a_move_by_its_top_left() {
        val grid = SnapGrid(spacingXPt = 10f, spacingYPt = 10f)
        val box = PageRect(3f, 7f, 50f, 60f)
        assertEquals(
            grid.moveBy(PagePoint(3f, 7f), 4f, 4f, Alignment.None),
            grid.moveBy(box, 4f, 4f, Alignment.None),
        )
    }

    @Test
    fun a_paper_grid_starts_from_the_papers_own_corner() {
        // An infinite canvas that grew 40 pt up and 25 pt left draws its squares from (-25, -40),
        // so its lattice must start there too, or every snap misses the lines by that much.
        val grid = SnapGrid.forBackground(Background.Template(PageTemplate.GRID), paperLeft = -25f, paperTop = -40f)
        assertEquals(-7f, grid.snapX(-5f), TOLERANCE)
        assertEquals(-4f, grid.snapY(-3f), TOLERANCE)
    }

    @Test
    fun ruled_paper_rules_from_its_margin_below_the_papers_corner() {
        val grid = SnapGrid.forBackground(Background.Template(PageTemplate.RULED), paperLeft = 0f, paperTop = -50f)
        // Rules at -50 + 36 + 24k: -14, 10, 34, …
        assertEquals(10f, grid.snapY(12f), TOLERANCE)
        assertEquals(41f, grid.snapX(41f), TOLERANCE)
    }

    @Test
    fun a_dragged_box_lands_every_edge_on_the_lattice() {
        val grid = SnapGrid(spacingXPt = 18f, spacingYPt = 18f)
        assertEquals(PageRect(36f, 18f, 90f, 72f), grid.snappedBox(PageRect(40f, 20f, 85f, 70f)))
    }

    @Test
    fun a_dragged_box_too_small_to_round_keeps_a_whole_cell() {
        val grid = SnapGrid(spacingXPt = 18f, spacingYPt = 18f)
        assertEquals(PageRect(36f, 36f, 54f, 54f), grid.snappedBox(PageRect(37f, 37f, 40f, 40f)))
    }

    @Test
    fun a_dragged_box_on_ruled_paper_moves_only_vertically() {
        val grid = SnapGrid(spacingXPt = 0f, spacingYPt = 24f, originYPt = 36f)
        assertEquals(PageRect(41f, 36f, 97f, 84f), grid.snappedBox(PageRect(41f, 40f, 97f, 80f)))
    }

    @Test
    fun enclosing_widens_outward_and_never_cuts_content_off() {
        val grid = SnapGrid(spacingXPt = 18f, spacingYPt = 18f)
        assertEquals(PageRect(18f, 0f, 108f, 54f), grid.enclosing(PageRect(19f, 1f, 107f, 37f)))
    }

    @Test
    fun enclosing_leaves_a_box_already_on_the_lattice_alone() {
        val grid = SnapGrid(spacingXPt = 18f, spacingYPt = 18f)
        assertEquals(PageRect(18f, 36f, 90f, 72f), grid.enclosing(PageRect(18f, 36f, 90f, 72f)))
    }

    @Test
    fun no_grid_changes_no_box() {
        val box = PageRect(1f, 2f, 3f, 4f)
        assertEquals(box, SnapGrid.None.snappedBox(box))
        assertEquals(box, SnapGrid.None.enclosing(box))
    }

    private companion object {
        const val TOLERANCE = 1e-3f
        const val QUARTER = 0.4f
    }
}
