package dev.inkstone.core.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SPEC §4.5. The acceptance criterion these exist for is ROADMAP M1's last one:
 * *"zoomed to 400%, ink lands exactly under the pen tip"*. That is a statement
 * about [viewToPage], and it is cheaper to prove here than on a tablet.
 */
class CameraTest {

    private val tolerance = 1e-3f

    private fun assertClose(expected: Float, actual: Float, message: String = "") {
        assertTrue(
            abs(expected - actual) < tolerance,
            "$message expected $expected but was $actual",
        )
    }

    private fun assertClose(expected: PagePoint, actual: PagePoint) {
        assertClose(expected.x, actual.x, "x:")
        assertClose(expected.y, actual.y, "y:")
    }

    @Test
    fun `viewToPage inverts pageToView at any scale`() {
        val camera = Camera(Offset(-137.5f, 42.25f), scale = 3.7f)
        val page = PagePoint(211.5f, 604.25f)

        assertClose(page, viewToPage(pageToView(page, camera), camera))
    }

    @Test
    fun `ink lands under the pen tip at 400 percent`() {
        // 400% on a 2.625 px/pt display (a 189-dpi tablet panel is close to this).
        val pixelsPerPoint = 189f / 72f
        val camera = Camera(Offset(-800f, -1200f), scale = pixelsPerPoint * 4f)

        // A touch 500 px right and 300 px down from the view's top-left.
        val tip = Offset(500f, 300f)
        val landed = viewToPage(tip, camera)

        // Converting back must reproduce the pixel the user actually touched,
        // because that pixel is where the wet ink was drawn.
        val backToView = pageToView(landed, camera)
        assertClose(tip.x, backToView.x, "x:")
        assertClose(tip.y, backToView.y, "y:")
        assertClose(400f, camera.zoomPercent(pixelsPerPoint), "zoom:")
    }

    @Test
    fun `affine arrays agree with the point conversions`() {
        val camera = Camera(Offset(31f, -17f), scale = 2.5f)
        val p = PagePoint(93f, 145f)

        val forward = camera.pageToViewAffine()
        assertClose(pageToView(p, camera).x, forward[0] * p.x + forward[1] * p.y + forward[2], "fwd x:")
        assertClose(pageToView(p, camera).y, forward[3] * p.x + forward[4] * p.y + forward[5], "fwd y:")

        val v = Offset(260f, 345f)
        val inverse = camera.viewToPageAffine()
        assertClose(viewToPage(v, camera).x, inverse[0] * v.x + inverse[1] * v.y + inverse[2], "inv x:")
        assertClose(viewToPage(v, camera).y, inverse[3] * v.x + inverse[4] * v.y + inverse[5], "inv y:")
    }

    @Test
    fun `zoom holds the focus point stationary`() {
        val camera = Camera(Offset(12f, -80f), scale = 1.4f)
        val limits = ZoomLimits(0.1f, 40f)
        val focus = Offset(410f, 655f)
        val pageUnderFocus = viewToPage(focus, camera)

        val zoomed = camera.zoomedAround(focus, factor = 2.75f, limits = limits)

        assertClose(pageUnderFocus.x, viewToPage(focus, zoomed).x, "x:")
        assertClose(pageUnderFocus.y, viewToPage(focus, zoomed).y, "y:")
        assertClose(1.4f * 2.75f, zoomed.scale, "scale:")
    }

    @Test
    fun `zoom holds the focus point stationary even when the limit bites`() {
        val camera = Camera(Offset(0f, 0f), scale = 1f)
        val limits = ZoomLimits(0.5f, 2f)
        val focus = Offset(300f, 300f)
        val pageUnderFocus = viewToPage(focus, camera)

        val zoomed = camera.zoomedAround(focus, factor = 100f, limits = limits)

        assertClose(2f, zoomed.scale, "clamped scale:")
        assertClose(pageUnderFocus.x, viewToPage(focus, zoomed).x, "x:")
        assertClose(pageUnderFocus.y, viewToPage(focus, zoomed).y, "y:")
    }

    @Test
    fun `zoom limits come from the display density`() {
        val pixelsPerPoint = 3f
        val limits = ZoomLimits.forDisplay(pixelsPerPoint)

        assertClose(0.3f, limits.minScale, "10%:")
        assertClose(48f, limits.maxScale, "1600%:")
        assertClose(0.3f, limits.clamp(0.0001f), "clamps down:")
        assertClose(48f, limits.clamp(1000f), "clamps up:")
    }

    @Test
    fun `panning is free and only the leash stops the page leaving`() {
        val page = PageSize(100f, 1000f)
        val viewport = Viewport(widthPx = 400f, heightPx = 500f)
        val keep = 64f

        // The page is 200px wide inside a 400px viewport. The old behaviour
        // centred it and refused to move; the leash lets it go anywhere that
        // keeps `keep` pixels of it on screen.
        val nudged = Camera(Offset(30f, 0f), scale = 2f).leashedTo(page, viewport, keep)
        assertClose(30f, nudged.translate.x, "a page that fits can still be moved:")

        val shovedRight = Camera(Offset(9999f, 0f), scale = 2f).leashedTo(page, viewport, keep)
        assertClose(viewport.widthPx - keep, shovedRight.translate.x, "leashed at the right:")

        val shovedLeft = Camera(Offset(-9999f, 0f), scale = 2f).leashedTo(page, viewport, keep)
        assertClose(keep - 200f, shovedLeft.translate.x, "leashed at the left:")
    }

    @Test
    fun `the leash always leaves something to grab`() {
        val page = PageSize.Letter
        val viewport = Viewport(widthPx = 1200f, heightPx = 800f)
        val keep = 64f

        for (scale in listOf(0.2f, 1f, 8f)) {
            for (shove in listOf(-1e6f, 1e6f)) {
                val camera = Camera(Offset(shove, shove), scale).leashedTo(page, viewport, keep)
                val pageWidthPx = page.widthPt * scale
                val pageHeightPx = page.heightPt * scale
                val visibleX = minOf(camera.translate.x + pageWidthPx, viewport.widthPx) -
                    maxOf(camera.translate.x, 0f)
                val visibleY = minOf(camera.translate.y + pageHeightPx, viewport.heightPx) -
                    maxOf(camera.translate.y, 0f)
                assertTrue(visibleX >= minOf(keep, pageWidthPx) - tolerance, "lost the page in x at $scale")
                assertTrue(visibleY >= minOf(keep, pageHeightPx) - tolerance, "lost the page in y at $scale")
            }
        }
    }

    @Test
    fun `a page smaller than the leash is still reachable`() {
        // The leash cannot ask for more page than exists, or it is unsatisfiable
        // and the clamp range inverts.
        val page = PageSize(10f, 10f)
        val viewport = Viewport(widthPx = 800f, heightPx = 800f)
        val camera = Camera(Offset(5000f, 5000f), scale = 1f).leashedTo(page, viewport, 64f)

        assertTrue(camera.translate.x <= viewport.widthPx, "tiny page pushed off screen")
        assertTrue(camera.translate.x + 10f >= 0f, "tiny page pushed off screen")
    }

    @Test
    fun `fit modes`() {
        val page = PageSize.Letter // 612 x 792
        val viewport = Viewport(widthPx = 1224f, heightPx = 1000f)
        val pixelsPerPoint = 2f
        val limits = ZoomLimits.forDisplay(pixelsPerPoint)

        val fitWidth = cameraFor(FitMode.FIT_WIDTH, page, viewport, pixelsPerPoint, limits)
        assertClose(2f, fitWidth.scale, "fit-width fills the width:")
        assertClose(0f, fitWidth.translate.x, "no horizontal slack:")
        assertClose(0f, fitWidth.translate.y, "starts at the top of a tall page:")

        // Fit modes still centre — that is the point of them. It is only
        // *panning* that is unconstrained.
        val shortPage = PageSize(612f, 100f)
        val fitShort = cameraFor(FitMode.FIT_WIDTH, shortPage, viewport, pixelsPerPoint, limits)
        assertClose((1000f - 200f) / 2f, fitShort.translate.y, "a short page is centred:")

        val fitPage = cameraFor(FitMode.FIT_PAGE, page, viewport, pixelsPerPoint, limits)
        assertClose(1000f / 792f, fitPage.scale, "fit-page is limited by height:")
        assertTrue(fitPage.translate.x > 0f, "fit-page centres horizontally")

        val actual = cameraFor(FitMode.ACTUAL_SIZE, page, viewport, pixelsPerPoint, limits)
        assertClose(100f, actual.zoomPercent(pixelsPerPoint), "actual size is 100%:")
    }

    @Test
    fun `a non-positive scale is a programming error`() {
        assertFailsWith<IllegalArgumentException> { Camera(Offset.Zero, 0f) }
        assertFailsWith<IllegalArgumentException> { Camera(Offset.Zero, -1f) }
    }

    @Test
    fun `panning is a pure view-space translation`() {
        val camera = Camera(Offset(10f, 20f), scale = 1.5f)
        val panned = camera.panned(Offset(-30f, 45f))

        assertEquals(Camera(Offset(-20f, 65f), 1.5f), panned)
    }
}
