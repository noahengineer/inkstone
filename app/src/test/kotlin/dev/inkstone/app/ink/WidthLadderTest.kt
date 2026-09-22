package dev.inkstone.app.ink

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules the size panel, `InkUiState` and the codec share. */
class WidthLadderTest {

    @Test
    fun `normalising sorts, merges rungs within a tenth, and brings everything under the maximum`() {
        assertEquals(listOf(1f, 3f, 10f), WidthLadder.normalised(listOf(10f, 3f, 3.04f, 1f, 16f), maxDp = 10f))
    }

    @Test
    fun `the maximum is bounded at both ends and otherwise exactly what was asked for`() {
        assertEquals(WidthLadder.MIN_MAX_DP, WidthLadder.clampMax(0.2f))
        assertEquals(WidthLadder.HARD_MAX_DP, WidthLadder.clampMax(5000f))
        assertEquals(47.5f, WidthLadder.clampMax(47.5f))
        assertTrue(WidthLadder.MIN_MAX_DP > MIN_WIDTH_DP, "a maximum at the minimum leaves the slider no travel")
    }

    @Test
    fun `a typed width parses with either decimal separator and an optional unit`() {
        // The field shows "%.1f".format, which is "3,5" on a German tablet -- refusing the comma
        // would refuse the field's own text.
        assertEquals(3.5f, WidthLadder.parse("3.5"))
        assertEquals(3.5f, WidthLadder.parse("3,5"))
        assertEquals(12f, WidthLadder.parse(" 12 dp "))
        assertNull(WidthLadder.parse(""))
        assertNull(WidthLadder.parse("-"))
        assertNull(WidthLadder.parse("NaN"))
    }

    @Test
    fun `every width up to the hard maximum fits the dock's seven-glyph readout`() {
        // DockRows.WidthReadout holds seven monospace glyphs, and "200.0 dp" is eight -- the
        // truncation that reads as a value, which that readout's KDoc records once already.
        var width = MIN_WIDTH_DP
        while (width <= WidthLadder.HARD_MAX_DP) {
            val label = WidthLadder.label(width)
            assertTrue(label.length <= 7, "\"$label\" is wider than the readout")
            width += 0.05f
        }
    }
}
