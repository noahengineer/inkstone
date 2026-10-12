package dev.inkstone.app.calculator

import dev.inkstone.core.calc.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The keypad's press rule (docs/CALCULATOR.md D5): a press lands on release, on the key it went down
 * on; sliding off withdraws it for good; a cancelled release — how Android's palm rejection arrives —
 * presses nothing.
 *
 * ⚠️ What this cannot say: that Compose really delivers a rejected palm as a consumed up (it does for
 * `ACTION_CANCEL`; whether a palm flagged on one pointer of several arrives that way is the tablet's
 * question, D5), or that `CalculatorFace.track` maps a touch to the right key — the emulator walk
 * (`input tap` on `7`, read the LCD's text) is that.
 */
class KeyPressesTest {

    @Test
    fun `a release on the key it went down on presses that key once`() {
        val presses = KeyPresses()
        presses.down(1, Key.D7)
        presses.move(1, Key.D7)
        assertEquals(setOf(Key.D7), presses.held)
        assertEquals(Key.D7, presses.up(1, Key.D7, cancelled = false))
        assertTrue(presses.held.isEmpty())
        assertNull(presses.up(1, Key.D7, cancelled = false), "a second release of the same pointer")
    }

    @Test
    fun `sliding off withdraws the press and sliding back does not restore it`() {
        val presses = KeyPresses()
        presses.down(1, Key.D7)
        presses.move(1, Key.D8)
        assertTrue(presses.held.isEmpty(), "a withdrawn press is not drawn held")
        presses.move(1, Key.D7)
        assertNull(presses.up(1, Key.D7, cancelled = false))
    }

    @Test
    fun `sliding into the gap between keys withdraws the press too`() {
        val presses = KeyPresses()
        presses.down(1, Key.SIN)
        presses.move(1, null)
        assertNull(presses.up(1, Key.SIN, cancelled = false))
    }

    @Test
    fun `a cancelled release presses nothing`() {
        // A palm Android rejected: Compose turns the cancel into an up that is already consumed.
        val presses = KeyPresses()
        presses.down(1, Key.D5)
        assertNull(presses.up(1, Key.D5, cancelled = true))
        assertTrue(presses.held.isEmpty())
    }

    @Test
    fun `a pointer that went down between keys presses nothing wherever it lifts`() {
        val presses = KeyPresses()
        presses.down(1, null)
        presses.move(1, Key.EQUALS)
        assertTrue(presses.held.isEmpty())
        assertNull(presses.up(1, Key.EQUALS, cancelled = false))
    }

    @Test
    fun `two pointers press their own keys independently`() {
        val presses = KeyPresses()
        presses.down(1, Key.SHIFT)
        presses.down(2, Key.D9)
        assertEquals(setOf(Key.SHIFT, Key.D9), presses.held)
        presses.move(2, Key.D8)
        assertEquals(setOf(Key.SHIFT), presses.held)
        assertNull(presses.up(2, Key.D8, cancelled = false))
        assertEquals(Key.SHIFT, presses.up(1, Key.SHIFT, cancelled = false))
    }

    @Test
    fun `a release the face never saw go down presses nothing`() {
        assertNull(KeyPresses().up(7, Key.AC, cancelled = false))
    }
}
