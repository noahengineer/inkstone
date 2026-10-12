package dev.inkstone.app.calculator

import dev.inkstone.core.calc.Key
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The face's key table: the data the painter draws and the input handler hit-tests.
 *
 * ⚠️ This proves the table is complete and consistent — fifty keys, one per `Key`, no press target
 * overlapping another, all of them on the body. It cannot say that the face *looks* like the
 * fx-991ZA PLUS II (a screenshot beside research 01's photos is that), nor that a tap on the device
 * reaches `CalculatorKeypad.keyAt` (the emulator walk: `input tap` on `7`, read the LCD's text).
 */
class CalculatorKeypadTest {

    private val keys = CalculatorKeypad.KEYS

    @Test
    fun `there are exactly fifty keys and one entry per Key value`() {
        assertEquals(50, keys.size)
        assertEquals(Key.entries.size, keys.size)
        assertEquals(Key.entries.toSet(), keys.map { it.key }.toSet())
    }

    @Test
    fun `no two press targets overlap anywhere on the face`() {
        // Touching is allowed (the cursor keys interlock); sharing area is not. The tolerance is for
        // float arithmetic on tenths of a millimetre, a thousand times smaller than any real gap.
        keys.forEachIndexed { i, a ->
            keys.drop(i + 1).forEach { b ->
                val across = min(a.hitRect.right, b.hitRect.right) - max(a.hitRect.left, b.hitRect.left)
                val down = min(a.hitRect.bottom, b.hitRect.bottom) - max(a.hitRect.top, b.hitRect.top)
                assertTrue(across <= TOLERANCE || down <= TOLERANCE, "${a.key} and ${b.key} overlap")
            }
        }
    }

    @Test
    fun `every press target is on the body and clear of the LCD and the solar cell`() {
        keys.forEach { spec ->
            val r = spec.hitRect
            assertTrue(r.left >= 0f && r.top >= 0f, "${spec.key} off the top left")
            assertTrue(r.right <= FaceMm.WIDTH && r.bottom <= FaceMm.HEIGHT, "${spec.key} off the bottom right")
            assertTrue(r.top >= FaceMm.BEZEL.bottom, "${spec.key} reaches into the LCD bezel")
            assertTrue(r.top >= FaceMm.SOLAR.bottom, "${spec.key} reaches into the solar cell")
        }
    }

    @Test
    fun `a press at a key centre is that key`() {
        keys.forEach { spec -> assertEquals(spec.key, CalculatorKeypad.keyAt(spec.cx, spec.cy), spec.description) }
    }

    @Test
    fun `a press between keys or on the LCD presses nothing`() {
        val nowhere = listOf(
            // The legend band between function rows 1 and 2, under SOLVE.
            11.4f to 80.5f,
            // The middle of the LCD glass.
            38f to 37f,
            // The left margin beside the number pad.
            3f to 140f,
            // Below the bottom row.
            38f to 158f,
            // The centre of the cursor pad, where the four points meet.
            38.4f to 67.9f,
        )
        nowhere.forEach { (x, y) -> assertNull(CalculatorKeypad.keyAt(x, y), "($x, $y)") }
    }

    @Test
    fun `every key has a distinct accessibility name`() {
        val names = keys.map { it.description }
        assertTrue(names.none { it.isBlank() })
        assertEquals(
            names.size,
            names.toSet().size,
            "duplicate names: ${names.groupBy {
                it
            }.filter { it.value.size > 1 }}",
        )
        listOf("SHIFT", "sin", "x squared", "7", "decimal comma").forEach { assertTrue(it in names, it) }
    }

    @Test
    fun `the oversized corners are the ten research 01 lists`() {
        val expected = mapOf(
            Key.D7 to BigCorner.TOP_LEFT,
            Key.AC to BigCorner.TOP_RIGHT,
            Key.D0 to BigCorner.BOTTOM_LEFT,
            Key.EQUALS to BigCorner.BOTTOM_RIGHT,
            Key.CALC to BigCorner.TOP_LEFT,
            Key.LOG_BASE to BigCorner.TOP_RIGHT,
            Key.RCL to BigCorner.BOTTOM_LEFT,
            Key.M_PLUS to BigCorner.BOTTOM_RIGHT,
            Key.INTEGRAL to BigCorner.TOP_RIGHT,
            Key.RECIPROCAL to BigCorner.TOP_LEFT,
        )
        assertEquals(expected, keys.mapNotNull { spec -> spec.corner?.let { spec.key to it } }.toMap())
    }

    @Test
    fun `only the blank round keys carry a printed name`() {
        // The painter lays a name out as part of a centred row (MODE SETUP); a name on any other key
        // would be placed by the SHIFT-left, ALPHA-right rule instead.
        keys.forEach { spec -> assertEquals(spec.kind == KeyKind.ROUND, spec.name != null, "${spec.key}") }
    }

    @Test
    fun `round and cursor keys are blank and every other key has a cap`() {
        keys.forEach { spec ->
            val blank = spec.kind == KeyKind.ROUND || spec.kind == KeyKind.CURSOR
            assertEquals(blank, spec.cap.isEmpty(), "${spec.key}")
        }
    }

    @Test
    fun `the South African printing is a comma key and a semicolon on SHIFT close bracket`() {
        // Research 01 §5.5: the two legends that differ from every sibling, and the two a copy of
        // the international layout would get wrong.
        assertEquals(listOf(t(",")), CalculatorKeypad.spec(Key.DECIMAL).cap)
        assertEquals(listOf(t(";")), CalculatorKeypad.spec(Key.CLOSE_PAREN).shift?.glyphs)
        assertNull(CalculatorKeypad.spec(Key.D6).shift, "nothing is printed above 6 on this model")
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
