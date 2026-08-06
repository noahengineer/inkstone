package dev.inkstone.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC §4.1 — the comparator, and the `max + 1` rule that a reinstall depends on. */
class LamportClockTest {

    @Test
    fun `ordering is by counter then deviceId`() {
        assertTrue(LamportClock(1, "zzzz") < LamportClock(2, "aaaa"))
        assertTrue(LamportClock(2, "aaaa") < LamportClock(2, "bbbb"))
        assertEquals(LamportClock(2, "aaaa"), LamportClock(2, "aaaa"))
    }

    @Test
    fun `deviceId is compared as a plain string — with no locale collation`() {
        // Uppercase sorts before lowercase in code-unit order. A locale-aware
        // comparator can disagree, and two devices in different locales that disagree
        // about op order do not converge.
        assertTrue(LamportClock(1, "Z") < LamportClock(1, "a"))
        assertTrue(LamportClock(1, "a") < LamportClock(1, "ä"))
    }

    @Test
    fun `sorting a mixed log is total and deterministic`() {
        val clocks = listOf(
            LamportClock(3, "b"),
            LamportClock(1, "b"),
            LamportClock(3, "a"),
            LamportClock(1, "a"),
        )
        assertEquals(
            listOf(LamportClock(1, "a"), LamportClock(1, "b"), LamportClock(3, "a"), LamportClock(3, "b")),
            clocks.sorted(),
        )
    }

    @Test
    fun `a fresh install issues clocks after all loaded history`() {
        // The failure this guards: a reinstall generates a new deviceId and a counter
        // of zero, so without max+1 every op it writes sorts behind all existing
        // history and loses every conflict it takes part in — silently, because the
        // ops are all present and just fold in the wrong order.
        val history = listOf(
            PageOp.DeleteObject("op-1", LamportClock(40, "old-device"), ObjectId("obj-1")),
            PageOp.DeleteObject("op-2", LamportClock(7, "other-device"), ObjectId("obj-2")),
        )

        val generator = LamportClockGenerator.onOpen("new-device", history)
        val issued = generator.next()

        assertEquals(41, issued.counter)
        assertTrue(history.all { it.clock < issued })
    }

    @Test
    fun `observe never moves the counter backwards`() {
        val generator = LamportClockGenerator("device", highestSeen = 10)
        generator.observe(LamportClock(3, "peer"))
        assertEquals(11, generator.next().counter)
    }

    @Test
    fun `a register keeps the later write and ignores the earlier one`() {
        val register = Register("first", LamportClock(5, "a"))

        assertEquals("second", register.write("second", LamportClock(6, "a")).value)
        assertEquals("first", register.write("stale", LamportClock(4, "z")).value)
        // Same counter, higher deviceId — the tiebreak decides, and it decides the same
        // way on every device.
        assertEquals("tiebreak", register.write("tiebreak", LamportClock(5, "b")).value)
        assertEquals("first", register.write("loses", LamportClock(5, "A")).value)
    }
}
