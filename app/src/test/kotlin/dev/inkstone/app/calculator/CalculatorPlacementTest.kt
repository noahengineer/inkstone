package dev.inkstone.app.calculator

import dev.inkstone.app.ink.DockRowGap
import dev.inkstone.app.ink.DockTopPadding
import dev.inkstone.app.ui.InkDims
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Where the calculator window goes and how big it is — the half of the window that is arithmetic.
 *
 * ⚠️ What this cannot say: that `CalculatorWindow` actually feeds these functions the screen's root
 * `Box` in dp, rebases the chip's root coordinates, or reads the clamp in the placement phase. That
 * is the emulator walk (research 08 §9: drag, rotate both ways, dump the bounds).
 *
 * The viewports are the emulator's `Test_Tablet` (1,280 × 800 dp) less a 24 dp status bar and a
 * 24 dp navigation bar, with the window's real chrome: 14 dp of `GlassPanel` padding all round, a
 * 34 dp strip and an 8 dp gap.
 */
class CalculatorPlacementTest {

    private val chromeWidth = 28f
    private val chromeHeight = 14f + 34f + 8f + 14f

    /** The window's real top line, not a copy of it: a copy is what let it sit under dock row two. */
    private val safeTop = WindowSafeTop.value
    private val bottomReserve = 66f
    private val edge = 12f

    private val landscape = Viewport(1280f, 752f, safeTop, bottomReserve, edge)
    private val portrait = Viewport(800f, 1232f, safeTop, bottomReserve, edge)

    private fun windowIn(viewport: Viewport): WindowSize = CalculatorPlacement.windowSize(
        CalculatorPlacement.faceWidth(viewport, chromeWidth, chromeHeight),
        chromeWidth,
        chromeHeight,
    )

    @Test
    fun `in landscape the window fills the height between the dock and the bottom zone`() {
        val size = windowIn(landscape)
        assertEquals(landscape.height - safeTop - bottomReserve, size.height, 0.01f)
        assertTrue(size.width - chromeWidth < CalculatorPlacement.MAX_FACE_WIDTH_DP)
    }

    @Test
    fun `in portrait the face is capped and the body aspect stays locked`() {
        val face = CalculatorPlacement.faceWidth(portrait, chromeWidth, chromeHeight)
        assertEquals(CalculatorPlacement.MAX_FACE_WIDTH_DP, face)
        val size = windowIn(portrait)
        assertEquals((161.5f / 77f), (size.height - chromeHeight) / (size.width - chromeWidth), 0.0001f)
    }

    @Test
    fun `a clamped window stays below the dock and inside the root in both orientations`() {
        val wild = listOf(-5000f, -12f, 0f, 77f, 400f, 1000f, 5000f)
        listOf(landscape, portrait).forEach { viewport ->
            val size = windowIn(viewport)
            wild.forEach { x ->
                wild.forEach { y ->
                    val at = CalculatorPlacement.clamp(WindowPosition(x, y), size, viewport)
                    assertTrue(at.y >= safeTop, "above the dock at $x, $y")
                    assertTrue(at.x >= edge && at.x + size.width <= viewport.width - edge + 0.01f, "off the side")
                    assertTrue(at.y + size.height <= viewport.height - bottomReserve + 0.01f, "into the bottom zone")
                }
            }
        }
    }

    @Test
    fun `a position that fits is left exactly where the user put it`() {
        // Portrait, because in landscape the window is as tall as the free space and has one height.
        val put = WindowPosition(300f, 200f)
        assertEquals(put, CalculatorPlacement.clamp(put, windowIn(portrait), portrait))
    }

    @Test
    fun `the window never reaches the second row of top chrome`() {
        // Research 08 §4.5 clamped at DockSafeTop, 78 dp — clear of the dock's primary row, which is
        // what ContextBars needs because a contextual bar hides row two. But row two (the pen's
        // brushes, colours and sizes) is on screen in the editor's default state, from 66 to 112 dp
        // and 915 dp wide, and the window is composed under the chrome: its drag strip and close
        // glyph sat beneath the pen row. The library's chrome is the same two rows on the same tokens.
        val bar = InkDims.ChipMinHeight + InkDims.BarPadding * 2
        val rowTwoBottom = (DockTopPadding + bar + DockRowGap + bar).value
        assertTrue(safeTop >= rowTwoBottom, "the window's top line $safeTop is inside row two (ends $rowTwoBottom)")
        listOf(landscape, portrait).forEach { viewport ->
            val chip = Anchor(left = 12f, top = 12f, right = 72f, bottom = 58f)
            val opened = CalculatorPlacement.hangFrom(chip, windowIn(viewport), viewport, gap = 4f)
            assertTrue(opened.y >= rowTwoBottom, "opened under row two at ${opened.y}")
        }
    }

    @Test
    fun `rotating to portrait and back returns the window to the stored position`() {
        // In landscape the window is exactly as tall as the free space, so its one valid y is the top.
        val stored = WindowPosition(900f, safeTop)
        val inLandscape = CalculatorPlacement.clamp(stored, windowIn(landscape), landscape)
        val inPortrait = CalculatorPlacement.clamp(stored, windowIn(portrait), portrait)
        val backInLandscape = CalculatorPlacement.clamp(stored, windowIn(landscape), landscape)

        assertEquals(stored, inLandscape)
        assertNotEquals(stored, inPortrait, "the portrait screen is too narrow for x = 900, so it must move")
        assertEquals(stored, backInLandscape)
    }

    @Test
    fun `clamping the clamped value instead would ratchet the window and never give it back`() {
        // The control for the round trip above: it passes only because the *stored* value is clamped
        // at read. Writing the portrait clamp back to storage loses the landscape position for good.
        val stored = WindowPosition(900f, safeTop)
        val written = CalculatorPlacement.clamp(stored, windowIn(portrait), portrait)
        assertNotEquals(stored, CalculatorPlacement.clamp(written, windowIn(landscape), landscape))
    }

    @Test
    fun `a window that has never been dragged hangs below the chip that opened it`() {
        val size = WindowSize(200f, 300f)
        val chip = Anchor(left = 500f, top = 100f, right = 560f, bottom = 146f)
        val at = CalculatorPlacement.hangFrom(chip, size, landscape, gap = 4f)
        assertEquals(WindowPosition(430f, 150f), at)
    }

    @Test
    fun `with no room below the chip the window flips above it`() {
        val size = WindowSize(200f, 300f)
        val chip = Anchor(left = 500f, top = 600f, right = 560f, bottom = 646f)
        val at = CalculatorPlacement.hangFrom(chip, size, landscape, gap = 4f)
        assertEquals(WindowPosition(430f, 296f), at)
    }

    @Test
    fun `the editor chip at the top left opens the window at the top left of the free space`() {
        // TopStart chip: 12 dp in, ~60 dp wide, 46 dp tall. Hanging below it is above the dock's
        // safe line, so the clamp brings it down to exactly that line and in to the edge.
        val chip = Anchor(left = 12f, top = 12f, right = 72f, bottom = 58f)
        val at = CalculatorPlacement.hangFrom(chip, windowIn(landscape), landscape, gap = 4f)
        assertEquals(WindowPosition(edge, safeTop), at)
    }

    @Test
    fun `with no chip laid out yet the window starts at the top left of the free space`() {
        assertEquals(
            WindowPosition(edge, safeTop),
            CalculatorPlacement.hangFrom(null, windowIn(portrait), portrait, gap = 4f),
        )
    }

    @Test
    fun `a window bigger than the screen keeps its top left corner on screen`() {
        val tiny = Viewport(300f, 400f, safeTop, bottomReserve, edge)
        val at = CalculatorPlacement.clamp(WindowPosition(250f, 380f), WindowSize(500f, 900f), tiny)
        assertEquals(WindowPosition(edge, safeTop), at)
    }
}
