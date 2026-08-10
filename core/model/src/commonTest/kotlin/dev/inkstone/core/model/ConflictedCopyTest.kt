package dev.inkstone.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * SPEC §7.7 layer 3 — [RemoteName.conflictedCopy].
 *
 * Two devices that disagree on this string each create their own copy of the same conflict,
 * and neither is obviously the other's twin. So the tests are about *agreement* rather than
 * about looking right: the name must survive [RemoteName.sanitise] untouched (or the server
 * sees a path the formatter never produced), and it must depend on nothing but the `Long`
 * (or a tablet in Zürich and a tablet in Auckland disagree by a day).
 *
 * The civil-date arithmetic is hand-written because `:core:model` has zero dependencies, so
 * the dates below are chosen to hit each way that goes wrong rather than to be representative.
 */
class ConflictedCopyTest {

    @Test
    fun `the documented example`() {
        assertEquals(
            "Lecture Notes (conflicted copy 2026-08-05 143012)",
            RemoteName.conflictedCopy("Lecture Notes", AUGUST_5_2026_143012),
        )
    }

    @Test
    fun `known instants convert to the right civil date and time`() {
        // Each of these was cross-checked against `date -u`, not against the implementation.
        assertStamp("1970-01-01 000000", 0L)
        assertStamp("1999-12-31 235959", 946_684_799_000L)
        assertStamp("2026-08-05 143012", AUGUST_5_2026_143012)
    }

    @Test
    fun `a leap day in an ordinary leap year and in a century one`() {
        // 2024 is the easy case; 2000 is the century that *is* a leap year, which is the rule
        // a naive "divisible by 100 is not a leap year" gets wrong.
        assertStamp("2024-02-29 120000", 1_709_208_000_000L)
        assertStamp("2000-02-29 060708", 951_804_428_000L)
    }

    @Test
    fun `a century that is not a leap year`() {
        // 2100 is divisible by 100 and not by 400, so it has no 29 February. If the century
        // rule were missing this instant would print as 2100-02-29.
        assertStamp("2100-03-01 000000", 4_107_542_400_000L)
    }

    @Test
    fun `both sides of a year boundary`() {
        assertStamp("2023-12-31 235959", 1_704_067_199_000L)
        assertStamp("2024-01-01 000000", 1_704_067_200_000L)
    }

    @Test
    fun `instants before the epoch do not round forward into 1970`() {
        // Kotlin's `/` truncates towards zero, so -1 / 86_400_000 is 0. Without floor
        // division this whole range lands on 1970-01-01 with a negative time of day.
        assertStamp("1969-12-31 235959", -1_000L)
        assertStamp("1969-12-31 235959", -1L)
        assertStamp("1969-12-31 000000", -86_400_000L)
    }

    @Test
    fun `the date is UTC and not the devices local day`() {
        // Half an hour either side of midnight UTC. In every timezone east of Greenwich the
        // first of these is already "tomorrow" locally, and in every timezone west of it the
        // second is still "yesterday" — so a formatter that consulted a platform calendar
        // would put two devices on two different paths for one conflict.
        assertStamp("2026-08-05 233000", 1_785_972_600_000L)
        assertStamp("2026-08-06 003000", 1_785_976_200_000L)
    }

    @Test
    fun `the result is a legal segment and sanitise is the identity on it`() {
        // The property SPEC §7.7 actually depends on. A colon in the time would fail this,
        // because `sanitise` would rewrite it and the two devices' paths could then differ.
        for (title in listOf("Lecture Notes", "", "   ", "CON", "notes.part", "Physics / Term 2")) {
            val name = RemoteName.conflictedCopy(title, AUGUST_5_2026_143012)
            assertTrue(RemoteName.isLegal(name), name)
            assertEquals(name, RemoteName.sanitise(name))
            assertTrue(name.endsWith(EXPECTED_SUFFIX), name)
        }
    }

    @Test
    fun `a title full of forbidden characters still yields one legal segment`() {
        val name = RemoteName.conflictedCopy("a/b\\c:d?e", AUGUST_5_2026_143012)
        assertEquals("a_b_c_d_e $EXPECTED_SUFFIX", name)
        // One segment, not five levels of accidental hierarchy (SPEC §7.8).
        assertTrue('/' !in name)
    }

    @Test
    fun `the stamp survives a title at the byte limit`() {
        val overlong = "L".repeat(400)
        val name = RemoteName.conflictedCopy(overlong, AUGUST_5_2026_143012)

        assertTrue(name.endsWith(EXPECTED_SUFFIX), name)
        assertTrue(name.encodeToByteArray().size <= RemoteName.MAX_BYTES, "${name.encodeToByteArray().size} bytes")
        assertTrue(RemoteName.isLegal(name), name)
        // A copy whose name is the original's name is not a copy — it is the original,
        // overwritten. Truncating the concatenation rather than the title does exactly that.
        assertNotEquals(RemoteName.sanitise(overlong), name)
    }

    @Test
    fun `a multi-byte title is truncated by bytes and not by characters`() {
        // 400 two-byte characters is 800 bytes, well past MAX_BYTES, and cutting by `length`
        // would leave a name the server rejects for being too long.
        val name = RemoteName.conflictedCopy("é".repeat(400), AUGUST_5_2026_143012)

        assertTrue(name.encodeToByteArray().size <= RemoteName.MAX_BYTES, "${name.encodeToByteArray().size} bytes")
        assertTrue(name.endsWith(EXPECTED_SUFFIX), name)
        assertTrue(RemoteName.isLegal(name), name)
    }

    @Test
    fun `an empty title falls back rather than producing a nameless copy`() {
        assertEquals("${RemoteName.FALLBACK} $EXPECTED_SUFFIX", RemoteName.conflictedCopy("", AUGUST_5_2026_143012))
    }

    /** Asserts the whole stamp by reading it back out of a name with a known, inert title. */
    private fun assertStamp(expected: String, epochMillis: Long) {
        assertEquals("Note (conflicted copy $expected)", RemoteName.conflictedCopy("Note", epochMillis))
    }

    private companion object {
        /** 2026-08-05T14:30:12Z. The example in [RemoteName.conflictedCopy]'s KDoc. */
        const val AUGUST_5_2026_143012 = 1_785_940_212_000L

        const val EXPECTED_SUFFIX = "(conflicted copy 2026-08-05 143012)"
    }
}
