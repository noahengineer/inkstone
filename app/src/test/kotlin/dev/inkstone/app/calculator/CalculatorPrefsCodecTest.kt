package dev.inkstone.app.calculator

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The calculator's remembered state, as strings.
 *
 * What these establish: a lossless round trip, a closed `calc.*` key space, a corrupt row degrading
 * to a default, and — the one that matters most — that "not read yet" is never written as "empty".
 * What they cannot: that the rows reach the database or that the controller restores from them;
 * `CalculatorControllerTest` runs the real `SettingStore` for that, and the emulator walk
 * (`am force-stop`, reopen, read `calc.*` with `run-as`) is the device half.
 */
class CalculatorPrefsCodecTest {

    @Test
    fun `a round trip preserves every field`() {
        val prefs = CalculatorPrefs(
            // Whatever the engine writes: separators, newlines and maths characters included.
            snapshot = "mode=COMP\nA=1,25;B=-3\nAns=√2·π ×10^-3\n",
            open = true,
            window = WindowPosition(412.5f, 78f),
            face = 260.5f,
            docked = false,
        )
        assertEquals(prefs, CalculatorPrefsCodec.decode(CalculatorPrefsCodec.encode(prefs)))
    }

    @Test
    fun `a docked window has no position even though its old one is still in the table`() {
        // `putAll` cannot delete the old x and y when the window goes back in the panel column, so the
        // docked flag is what outranks them (docs/UI-RAILS.md §3).
        val dragged = CalculatorPrefsCodec.encode(
            CalculatorPrefs(open = true, window = WindowPosition(300f, 200f), docked = false),
        )
        val docked = dragged + CalculatorPrefsCodec.encode(CalculatorPrefs(open = true, docked = true))
        val read = CalculatorPrefsCodec.decode(docked)
        assertNull(read.window)
        assertEquals(true, read.docked)
        // And dragged out again, the new position is the one read.
        val again =
            docked +
                CalculatorPrefsCodec.encode(
                    CalculatorPrefs(open = true, window = WindowPosition(9f, 8f), docked = false),
                )
        assertEquals(WindowPosition(9f, 8f), CalculatorPrefsCodec.decode(again).window)
    }

    @Test
    fun `no size and no dock choice are left out rather than written`() {
        val encoded = CalculatorPrefsCodec.encode(CalculatorPrefs(open = true))
        assertFalse(CalculatorPrefsCodec.FACE in encoded)
        assertFalse(CalculatorPrefsCodec.DOCKED in encoded)
        assertNull(CalculatorPrefsCodec.decode(mapOf(CalculatorPrefsCodec.FACE to "-4")).face)
    }

    @Test
    fun `an unread snapshot is left out rather than written as empty`() {
        // `putAll` leaves absent keys alone, so this is what keeps a write made before `warm()` has
        // finished from erasing every memory the calculator had.
        val encoded = CalculatorPrefsCodec.encode(CalculatorPrefs(snapshot = null, open = true))
        assertFalse(CalculatorPrefsCodec.SNAPSHOT in encoded)
        assertFalse(CalculatorPrefsCodec.WINDOW_X in encoded)
        assertFalse(CalculatorPrefsCodec.WINDOW_Y in encoded)
        assertEquals("true", encoded[CalculatorPrefsCodec.OPEN])
    }

    @Test
    fun `every key written is one of the calc keys`() {
        val encoded = CalculatorPrefsCodec.encode(
            CalculatorPrefs("s", open = false, window = WindowPosition(1f, 2f), face = 200f, docked = false),
        )
        assertEquals(CalculatorPrefsCodec.KEYS, encoded.keys)
        assertTrue(CalculatorPrefsCodec.KEYS.all { it.startsWith("calc.") })
    }

    @Test
    fun `an empty store decodes to a fresh closed calculator with no remembered place`() {
        assertEquals(CalculatorPrefs(), CalculatorPrefsCodec.decode(emptyMap()))
    }

    @Test
    fun `half a window position is no position`() {
        val decoded = CalculatorPrefsCodec.decode(mapOf(CalculatorPrefsCodec.WINDOW_X to "300.0"))
        assertNull(decoded.window)
    }

    @Test
    fun `unparseable or non finite coordinates are no position`() {
        listOf("", "abc", "NaN", "Infinity", "-Infinity", "12,5").forEach { bad ->
            val decoded = CalculatorPrefsCodec.decode(
                mapOf(CalculatorPrefsCodec.WINDOW_X to bad, CalculatorPrefsCodec.WINDOW_Y to "10.0"),
            )
            assertNull(decoded.window, "x = '$bad'")
        }
    }

    @Test
    fun `a corrupt open flag reads as closed`() {
        listOf("yes", "1", "", "TRUE ish").forEach { bad ->
            assertFalse(CalculatorPrefsCodec.decode(mapOf(CalculatorPrefsCodec.OPEN to bad)).open, bad)
        }
    }

    @Test
    fun `a comma decimal locale can read back what it wrote`() {
        // This calculator's own market writes decimals with a comma; "%.1f" would too, and
        // "412,5".toFloatOrNull() is null -- a remembered place lost once, silently, then for ever.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val prefs = CalculatorPrefs(snapshot = "x", open = true, window = WindowPosition(412.5f, 1033.25f))
            assertEquals(prefs, CalculatorPrefsCodec.decode(CalculatorPrefsCodec.encode(prefs)))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
