package dev.inkstone.app.ink

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Test
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where a contextual toolbar floats — the half of *"it covers the rotation handle"* that is arithmetic.
 *
 * ⚠️ This covers the placement only. That the anchor handed in is the selection's **chrome** (box plus
 * rotate grip) and not its box is `ContextBars` reading `InkUiState.selectionChrome`, which no JVM test
 * can reach; the emulator walk is what checks it.
 */
class ContextBarPlacementTest {

    // A 2560 × 1600 tablet at density 2: 14 dp gap, 22 dp grip reach, 16 dp padding, 78 dp safe top.
    private val gap = 28f
    private val reach = 44f
    private val pad = 32f
    private val safeTop = 156f

    private fun place(
        top: Float,
        bottom: Float,
        left: Float = 1000f,
        right: Float = 1400f,
        clearance: Float = reach,
        width: Float = 1400f,
        keepOut: List<Rect> = emptyList(),
    ) = ContextBarPlacement.place(
        anchorLeft = left,
        anchorTop = top,
        anchorRight = right,
        anchorBottom = bottom,
        barWidth = width,
        barHeight = 96f,
        viewportWidth = 2560f,
        viewportHeight = 1600f,
        clearance = clearance,
        edgePadding = pad,
        safeTop = safeTop,
        keepOut = keepOut,
    )

    // The stock rails on the emulator, in px: tools down the left, pages down the right, both centred.
    private val toolRail = Rect(24f, 438f, 256f, 1170f)
    private val pageRail = Rect(2300f, 438f, 2536f, 1170f)

    private val rails = listOf(toolRail, pageRail)

    @Test
    fun `a bar level with a rail is kept out from under it`() {
        // A selection hard against the left edge: centred on it, the bar would start under the tools.
        val at = place(top = 700f, bottom = 900f, left = 280f, right = 400f, width = 1000f, keepOut = rails)
        assertEquals(toolRail.right + pad, at.x)
        val right = place(top = 700f, bottom = 900f, left = 2100f, right = 2280f, width = 1000f, keepOut = rails)
        assertEquals(pageRail.left - pad - 1000f, right.x)
    }

    @Test
    fun `a bar above the rails has the whole width`() {
        // Above a rail centred on the screen's height, nothing is in the way: only what the bar would
        // overlap top to bottom counts.
        val at = place(top = 380f, bottom = 420f, left = 0f, right = 100f, width = 1000f, keepOut = rails)
        assertTrue(at.y + 96f <= toolRail.top)
        assertEquals(pad, at.x)
    }

    @Test
    fun `a panel column reaching past the middle of the screen still counts as being on the right`() {
        // Found on the emulator: three panels open, the leftmost with its centre left of the middle,
        // so sorting by "which half" called it a left-hand obstacle, found no room, and let the card
        // slide under the panels it was meant to avoid.
        val panels =
            listOf(Rect(970f, 208f, 1464f, 1272f), Rect(1480f, 208f, 2040f, 1240f), Rect(2056f, 208f, 2536f, 688f))
        val keepOut = listOf(toolRail) + panels
        val width = ContextBarPlacement.freeWidth(1100f, 700f, 980f, 320f, keepOut, 2560f, pad, atLeast = 560f)
        assertEquals(970f - toolRail.right - 2 * pad, width)
        val at = place(top = 700f, bottom = 980f, left = 700f, right = 1500f, width = 600f, keepOut = keepOut)
        assertTrue(at.x >= toolRail.right && at.x + 600f <= 970f, "the card sits between the tools and the panels")
    }

    /** A card of 1,100 px of chips in 80 px lines: held narrower, it wraps and is taller. */
    private val chips = 1100f
    private val cardHeight: (Float) -> Float = { width -> ceil(chips / width) * 80f + 40f }

    @Test
    fun `with the column full, the card goes under the shortest panel rather than under the panels`() {
        // The emulator walk that found it (docs/UI-RAILS.md §3): calculator, diagnostics, colour and
        // Elements open, a stroke selected beneath the first two. Every gap level with the stroke is
        // narrower than a card, so it used to take the screen's width and slide under all four.
        val tools = Rect(24f, 96f, 256f, 830f)
        val corner = Rect(2170f, 96f, 2536f, 192f)
        val bottom =
            listOf(Rect(24f, 1416f, 312f, 1512f), Rect(440f, 1416f, 1634f, 1512f), Rect(1760f, 1416f, 2536f, 1512f))
        val diagnostics = Rect(826f, 208f, 1464f, 994f)
        val calculator = Rect(312f, 208f, 808f, 1270f)
        val panels = listOf(calculator, diagnostics, Rect(1480f, 208f, 2040f, 1240f), Rect(2056f, 208f, 2536f, 688f))
        val anchor = Rect(698f, 698f, 980f, 982f)

        val spot = ContextBarPlacement.spot(
            anchor = anchor,
            naturalWidth = chips,
            heightAt = cardHeight,
            viewport = Size(2560f, 1600f),
            clearance = reach,
            edgePadding = pad,
            safeTop = pad,
            reach = 320f,
            minWidth = 560f,
            rails = listOf(tools) + panels,
            chrome = listOf(tools, corner) + bottom + panels,
        )
        val card = Rect(spot.x, spot.y, spot.x + spot.width, spot.y + cardHeight(spot.width))
        (listOf(tools, corner, anchor) + bottom + panels).forEach {
            assertTrue(!card.overlaps(it), "the card $card overlaps $it")
        }
        assertTrue(card.top >= diagnostics.bottom && card.left >= 808f && card.right <= 1480f, "not under it: $card")
    }

    @Test
    fun `a space only counts if the card fits it at that space's width`() {
        // A strip along the top too short for the card on one line, and a column down the right where it
        // wraps to two: the column, and the card is the two-line height there — at 575, the widest of
        // its trial widths (400, 575, 750, 925, 1100) that fits the 600 the column has.
        val asked = mutableListOf<Float>()
        val at = ContextBarPlacement.refuge(
            centre = Offset(700f, 650f),
            naturalWidth = chips,
            heightAt = {
                asked += it
                cardHeight(it)
            },
            blocks = listOf(Rect(0f, 100f, 1400f, 1000f)),
            area = Rect(0f, 0f, 2000f, 1000f),
            padding = 0f,
            minWidth = 400f,
        )
        assertEquals(Rect(1400f, 550f, 1975f, 750f), at)
        assertTrue(asked.distinct().size <= 5, "asked the card its height at ${asked.distinct()}")
    }

    @Test
    fun `the card is asked its height at a handful of widths however many spaces there are`() {
        // Each answer is an intrinsic measurement of the whole card. One per space was a layout pass of
        // over a second on the emulator, repeated on every panel change.
        val blocks = (0 until 20).map { Rect(it * 120f, 300f + it * 30f, it * 120f + 60f, 1600f) }
        val asked = mutableSetOf<Float>()
        val heightAt: (Float) -> Float = {
            asked += it
            cardHeight(it)
        }
        ContextBarPlacement.refuge(Offset(1200f, 800f), chips, heightAt, blocks, Rect(0f, 0f, 2560f, 1600f), 8f, 280f)
        assertTrue(asked.size <= 5, "asked at ${asked.size} widths")
    }

    @Test
    fun `a screen with no room anywhere has no refuge`() {
        val full = listOf(Rect(0f, 0f, 1200f, 1600f), Rect(1200f, 0f, 2560f, 1600f))
        val screen = Rect(0f, 0f, 2560f, 1600f)
        val at = ContextBarPlacement.refuge(Offset(1000f, 800f), chips, cardHeight, full, screen, pad, 560f)
        assertEquals(null, at)
    }

    @Test
    fun `with room beside its object the card goes exactly where it always did`() {
        val anchor = Rect(1000f, 700f, 1400f, 900f)
        val viewport = Size(2560f, 1600f)
        val spot =
            ContextBarPlacement.spot(anchor, 600f, { 96f }, viewport, reach, pad, safeTop, 320f, 560f, rails, rails)
        assertEquals(BarPosition(spot.x, spot.y), place(top = 700f, bottom = 900f, width = 600f, keepOut = rails))
        assertEquals(600f, spot.width)
    }

    @Test
    fun `too narrow a gap is no gap, and the card takes the screen's width`() {
        val keepOut = listOf(Rect(0f, 0f, 1200f, 1600f), Rect(1500f, 0f, 2560f, 1600f))
        assertEquals(null, ContextBarPlacement.freeWidth(1300f, 700f, 900f, 320f, keepOut, 2560f, pad, atLeast = 560f))
    }

    @Test
    fun `a bar wider than the gap between the rails falls back to the screen's edges`() {
        val at = place(top = 700f, bottom = 900f, left = 0f, right = 100f, width = 2200f, keepOut = rails)
        assertEquals(pad, at.x)
    }

    @Test
    fun `above the anchor, the bar ends a whole grip reach short of it`() {
        // The anchor's top is the rotate grip's centre, so a press anywhere in the grip's reach
        // must land on paper rather than on the bar.
        val gripCentreY = 500f
        val at = place(top = gripCentreY, bottom = 900f)
        assertEquals(gripCentreY - reach, at.y + 96f)
    }

    @Test
    fun `the old 14 dp gap left the bar inside the grip's touch target`() {
        // The regression, stated as geometry: with the gap alone the bar's bottom edge sits closer to
        // the grip's centre than a finger's reach, so the grip is under the bar.
        val gripCentreY = 500f
        val at = place(top = gripCentreY, bottom = 900f, clearance = gap)
        assertTrue(gripCentreY - (at.y + 96f) < reach)
    }

    @Test
    fun `with no room above, the bar goes below and clears the bottom grips too`() {
        val at = place(top = 200f, bottom = 700f)
        assertEquals(700f + reach, at.y)
    }

    @Test
    fun `the bar is centred on the anchor and clamped to the screen`() {
        assertEquals(1200f - 700f, place(top = 500f, bottom = 900f).x)
        assertEquals(pad, place(top = 500f, bottom = 900f, left = 0f, right = 100f).x)
        assertEquals(2560f - 1400f - pad, place(top = 500f, bottom = 900f, left = 2400f, right = 2560f).x)
    }

    @Test
    fun `never above the dock, even for an object taller than the screen`() {
        val at = place(top = -400f, bottom = 2000f)
        assertTrue(at.y >= safeTop)
        assertTrue(at.y + 96f <= 1600f - pad)
    }
}
