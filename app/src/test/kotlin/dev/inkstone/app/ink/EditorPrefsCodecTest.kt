package dev.inkstone.app.ink

import dev.inkstone.android.ink.EraseMode
import dev.inkstone.android.ink.InkBrushKind
import dev.inkstone.android.ink.PenConfig
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ROADMAP M10 — the editor's remembered toolbar, as strings.
 *
 * What these tests can establish: that a round trip is lossless, that a corrupt row degrades to a
 * default rather than throwing, and that the encoding does not depend on the tester's locale. What
 * they cannot: that the values ever reach the database, or that `InkScreen` seeds from them. That is
 * `EditorPreferencesTest` and the emulator walk in docs/M10-DESIGN.md §10.
 */
class EditorPrefsCodecTest {

    @Test
    fun `a round trip preserves every field including the palette order`() {
        val prefs = EditorPrefs(
            palette = EditorPrefs.DEFAULT_PALETTE.reversed(),
            widthSlots = listOf(0.5f, 2.5f, 7f),
            defaultWidthDp = 2.5f,
            maxWidthDp = 24f,
            pens = mapOf(
                InkBrushKind.FOUNTAIN to PenConfig(InkBrushKind.FOUNTAIN, 0xFF102030.toInt(), 1.5f),
                InkBrushKind.BALLPOINT to PenConfig(InkBrushKind.BALLPOINT, 0x80FFFFFF.toInt(), 9f),
                InkBrushKind.PENCIL to PenConfig(InkBrushKind.PENCIL, 0xFF000000.toInt(), 4f),
                InkBrushKind.HIGHLIGHTER to PenConfig(InkBrushKind.HIGHLIGHTER, 0x40FF00FF.toInt(), 20f),
            ),
            brush = InkBrushKind.PENCIL,
            eraseMode = EraseMode.WHOLE_STROKE,
            fingerDrawingEnabled = true,
            shapeSnapEnabled = false,
            arrowHeads = true,
            gridSnapEnabled = true,
            rotationSnapDegrees = 45,
            scrollPagesEnabled = true,
        )
        assertEquals(prefs, EditorPrefsCodec.decode(EditorPrefsCodec.encode(prefs)))
    }

    @Test
    fun `rotation snaps to 15 degrees until a step is chosen and an unknown step reads as the stock one`() {
        assertEquals(15, EditorPrefsCodec.decode(emptyMap()).rotationSnapDegrees)
        assertEquals(0, EditorPrefsCodec.decode(mapOf("ink.rotationSnap" to "0")).rotationSnapDegrees)
        // A step the chip does not offer could never be cycled back to, so it is not adopted.
        assertEquals(15, EditorPrefsCodec.decode(mapOf("ink.rotationSnap" to "7")).rotationSnapDegrees)
        assertEquals(15, EditorPrefsCodec.decode(mapOf("ink.rotationSnap" to "lots")).rotationSnapDegrees)
    }

    @Test
    fun `scrolling pages like a PDF is off on a fresh install and survives being switched on`() {
        // Off by default: a note that has always stopped at the page's edge keeps doing so.
        assertEquals(false, EditorPrefsCodec.decode(emptyMap()).scrollPagesEnabled)
        val on = EditorPrefs(scrollPagesEnabled = true)
        assertEquals(true, EditorPrefsCodec.decode(EditorPrefsCodec.encode(on)).scrollPagesEnabled)
        val off = on.copy(scrollPagesEnabled = false)
        assertEquals(false, EditorPrefsCodec.decode(EditorPrefsCodec.encode(off)).scrollPagesEnabled)
    }

    @Test
    fun `the palette order is the value and a reorder is a different encoding`() {
        // The user's words were "the colors you select and more to position do not stay like that".
        // A palette that persisted its *contents* and not its order would pass every other test here.
        val stock = EditorPrefs()
        val moved = stock.copy(palette = stock.palette.toMutableList().also { it.add(0, it.removeAt(4)) })
        assertNotEquals(EditorPrefsCodec.encode(stock), EditorPrefsCodec.encode(moved))
        assertEquals(moved.palette, EditorPrefsCodec.decode(EditorPrefsCodec.encode(moved)).palette)
    }

    @Test
    fun `a comma-decimal locale can read back what it wrote`() {
        // "%.1f".format(3f) is "3,0" under this locale and "3,0".toFloatOrNull() is null, so a
        // format-based codec writes a width list it cannot parse -- once, silently, then for ever.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val prefs = EditorPrefs(widthSlots = listOf(0.5f, 3.5f, 12f), defaultWidthDp = 3.5f, maxWidthDp = 47.5f)
            val decoded = EditorPrefsCodec.decode(EditorPrefsCodec.encode(prefs))
            assertEquals(listOf(0.5f, 3.5f, 12f), decoded.widthSlots)
            assertEquals(3.5f, decoded.defaultWidthDp)
            assertEquals(47.5f, decoded.maxWidthDp)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `an opaque colour survives, which a signed hex conversion would not`() {
        // Int.toString(16) emits a leading minus for every colour with alpha above 0x7F, which is
        // every opaque colour in the stock palette.
        val decoded = EditorPrefsCodec.decode(EditorPrefsCodec.encode(EditorPrefs()))
        assertEquals(EditorPrefs.DEFAULT_PALETTE, decoded.palette)
        assertTrue(EditorPrefsCodec.encode(EditorPrefs()).getValue("ink.palette").none { it == '-' })
    }

    @Test
    fun `a pens map missing a brush still decodes to every brush`() {
        // InkUiState.pen is perBrush.getValue(kind) and is read on every composition of the dock, so
        // a partial map is a crash on opening a note -- sourced from a settings row.
        val partial = EditorPrefsCodec.encode(EditorPrefs()) - "ink.pen.PENCIL" - "ink.pen.HIGHLIGHTER"
        val decoded = EditorPrefsCodec.decode(partial)
        assertEquals(InkBrushKind.entries.toSet(), decoded.pens.keys)
        assertEquals(EditorPrefs.DEFAULT_PENS.getValue(InkBrushKind.PENCIL), decoded.pens.getValue(InkBrushKind.PENCIL))
    }

    @Test
    fun `garbage in every row decodes to the factory defaults rather than throwing`() {
        val junk = EditorPrefsCodec.encode(EditorPrefs()).mapValues { "!!! not a value !!!" }
        assertEquals(EditorPrefs(), EditorPrefsCodec.decode(junk))
        assertEquals(EditorPrefs(), EditorPrefsCodec.decode(emptyMap()))
    }

    @Test
    fun `a palette of the wrong length is refused whole rather than padded`() {
        // The slots are addressed by index by SwatchStrip, ColorPanel and swapSlots, so a ten-colour
        // palette is not a palette with two missing -- it is two indices that throw.
        val short = mapOf("ink.palette" to List(10) { "ff000000" }.joinToString(","))
        assertEquals(EditorPrefs.DEFAULT_PALETTE, EditorPrefsCodec.decode(short).palette)
    }

    @Test
    fun `stored widths come back sorted deduplicated and inside the settable range`() {
        val messy = mapOf("ink.widths" to "12.0,3.0,3.0,0.1,900.0,2.0")
        assertEquals(listOf(2f, 3f, 12f), EditorPrefsCodec.decode(messy).widthSlots)
    }

    @Test
    fun `deleting every saved size survives a restart rather than bringing the stock sizes back`() {
        // "it should not be preset sizes". An empty row is the user's choice; reading it as corrupt
        // would restore all six on the next launch.
        assertEquals(emptyList(), EditorPrefsCodec.decode(mapOf("ink.widths" to "")).widthSlots)
    }

    @Test
    fun `only a missing or unreadable width list is the stock ladder`() {
        assertEquals(EditorPrefs.DEFAULT_WIDTH_SLOTS, EditorPrefsCodec.decode(emptyMap()).widthSlots)
        assertEquals(EditorPrefs.DEFAULT_WIDTH_SLOTS, EditorPrefsCodec.decode(mapOf("ink.widths" to "abc")).widthSlots)
    }

    @Test
    fun `widths above the stored maximum are brought down to it`() {
        val stored = mapOf(
            "ink.maxWidth" to "10.0",
            "ink.widths" to "4.0,16.0",
            "ink.defaultWidth" to "12.0",
            "ink.pen.PENCIL" to "ff000000,14.0",
        )
        val decoded = EditorPrefsCodec.decode(stored)
        assertEquals(10f, decoded.maxWidthDp)
        assertEquals(listOf(4f, 10f), decoded.widthSlots)
        assertEquals(10f, decoded.defaultWidthDp)
        // The stored pencil, and the highlighter's stock 16 dp that nothing stored at all.
        assertTrue(decoded.pens.values.all { it.widthDp <= 10f })
    }

    @Test
    fun `a maximum outside the settable range is damage and decodes to the default`() {
        listOf("0", "0.5", "5000", "NaN").forEach { junk ->
            val decoded = EditorPrefsCodec.decode(mapOf("ink.maxWidth" to junk))
            assertEquals(EditorPrefs.DEFAULT_MAX_WIDTH_DP, decoded.maxWidthDp, "\"$junk\" was accepted")
        }
    }

    @Test
    fun `a width above the old fixed 32 dp is no longer dropped on load`() {
        val prefs = EditorPrefs(widthSlots = listOf(4f, 64f), maxWidthDp = 80f)
        assertEquals(listOf(4f, 64f), EditorPrefsCodec.decode(EditorPrefsCodec.encode(prefs)).widthSlots)
    }

    @Test
    fun `the stock sizes and brush defaults all fit under the stock maximum`() {
        val slots = EditorPrefs.DEFAULT_WIDTH_SLOTS
        assertTrue(slots.all { it in MIN_WIDTH_DP..EditorPrefs.DEFAULT_MAX_WIDTH_DP })
        assertTrue(EditorPrefs.DEFAULT_PENS.values.all { it.widthDp <= EditorPrefs.DEFAULT_MAX_WIDTH_DP })
        assertEquals(slots.sorted().distinct(), slots)
    }
}
