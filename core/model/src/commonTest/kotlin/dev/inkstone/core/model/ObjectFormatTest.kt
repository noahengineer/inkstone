package dev.inkstone.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ROADMAP M8c — the format register (SPEC §4.9.2) and its wire form.
 *
 * ⚠️ **Test names here may contain no commas and no apostrophes.** These compile for
 * `iosSimulatorArm64`, where Kotlin/Native rejects a comma in a backticked name, and the JVM
 * compiler accepts both — so neither shows up until the klib compile runs (CLAUDE.md).
 */
class ObjectFormatTest {

    // -----------------------------------------------------------------------
    // The record's wire form
    // -----------------------------------------------------------------------

    @Test
    fun `the default format encodes to nothing at all`() {
        // Not merely short — empty. A register written for an object nobody has restyled is a
        // register the sync engine uploads for no reason, on every autosave.
        assertEquals("", ObjectFormat.Default.encode())
        assertTrue(ObjectFormat.Default.isDefault)
    }

    @Test
    fun `a text format round-trips through its wire form`() {
        val format = ObjectFormat(
            font = TextFont.SERIF,
            sizePt = 18.5f,
            align = TextAlign.CENTER,
            listLevel = 2,
            listStyle = ListStyle.NUMBERED,
        )
        assertEquals(format, ObjectFormat.decode(format.encode()))
    }

    @Test
    fun `a rectangular crop round-trips`() {
        val format = ObjectFormat(crop = ImageCrop.Rect(0.1f, 0f, 0.9f, 0.75f))
        assertEquals(format, ObjectFormat.decode(format.encode()))
    }

    @Test
    fun `a freehand crop round-trips with its point order intact`() {
        val format = ObjectFormat(
            crop = ImageCrop.Polygon(listOf(0f, 0f, 1f, 0f, 1f, 1f, 0.5f, 0.8f)),
        )
        val decoded = ObjectFormat.decode(format.encode())
        assertEquals(format, decoded)
        assertEquals(4, (decoded.crop as ImageCrop.Polygon).pointCount)
    }

    @Test
    fun `a text box frame round-trips`() {
        val format = ObjectFormat(boxWidthPt = 260.5f, boxHeightPt = 88f)
        assertEquals(format, ObjectFormat.decode(format.encode()))
    }

    @Test
    fun `an absent frame means the extents the box was created with`() {
        // Null has to mean *the payload* rather than a number. A default here would resize every
        // text box in every note written before the frame existed - the payload is first-write-wins
        // and is the only record of how big a box was placed.
        val placed = ObjectFormat.decode("if1;f=mo")
        assertNull(placed.boxWidthPt)
        assertEquals(120f, placed.frameWidth(120f))
        assertEquals(44f, placed.frameHeight(44f))
        assertEquals(260f, ObjectFormat(boxWidthPt = 260f).frameWidth(120f))
    }

    @Test
    fun `a frame with no extent is refused rather than clamped`() {
        // A degenerate frame gives the object a singular matrix and cannot be grabbed again. The
        // property is left alone, which means the size the user has already seen.
        val decoded = ObjectFormat.decode("if1;w=0;h=-4;f=se")
        assertNull(decoded.boxWidthPt)
        assertNull(decoded.boxHeightPt)
        assertEquals(TextFont.SERIF, decoded.font)
    }

    @Test
    fun `a frame does not disturb the registers beside it`() {
        // The whole reason the frame is a key here and not a `SetTransform`: resizing a box and
        // restyling it are the same absolute register, so a resize must carry the font through.
        val styled = ObjectFormat(font = TextFont.MONO, sizePt = 18f, listStyle = ListStyle.BULLET, listLevel = 1)
        val resized = styled.copy(boxWidthPt = 300f)
        assertEquals(resized, ObjectFormat.decode(resized.encode()))
    }

    @Test
    fun `encoding is byte-stable across a decode and re-encode`() {
        // What keeps a two-second autosave from rewriting a register nobody touched — and
        // therefore from enqueueing an upload nobody asked for.
        val once = ObjectFormat(font = TextFont.MONO, sizePt = 11.25f, crop = ImageCrop.Rect(0f, 0f, 0.333f, 1f))
            .encode()
        assertEquals(once, ObjectFormat.decode(once).encode())
    }

    // -----------------------------------------------------------------------
    // Fail-closed reading
    // -----------------------------------------------------------------------

    @Test
    fun `a value that is not a format record decodes to the default rather than a guess`() {
        // The register next door holds a text box's actual words. Reading one as the other is how
        // a stranger's prose would become a font size.
        assertSame(ObjectFormat.Default, ObjectFormat.decode("Buy milk on the way home"))
        assertSame(ObjectFormat.Default, ObjectFormat.decode(""))
        assertSame(ObjectFormat.Default, ObjectFormat.decode(null))
    }

    @Test
    fun `a shape record is not read as a format record`() {
        // Both are `k=v;k=v` with a three-character prefix. Only the prefix tells them apart, and
        // a build that confused them would draw a fill colour as a font size.
        val shape = ShapeRecord(fillArgb = -16777216, cornerRadiusPt = 8f).encode()
        assertSame(ObjectFormat.Default, ObjectFormat.decode(shape))
        assertNull(ShapeRecord.decode(ObjectFormat(font = TextFont.MONO).encode()))
    }

    @Test
    fun `one unreadable key does not discard the rest of the record`() {
        // Fail-closed per property, not per record: an unreadable crop is no reason to forget a
        // font size the user did choose.
        val decoded = ObjectFormat.decode("if1;f=se;sz=notanumber;a=c")
        assertEquals(TextFont.SERIF, decoded.font)
        assertEquals(TextAlign.CENTER, decoded.align)
        assertEquals(ObjectFormat.DEFAULT_SIZE_PT, decoded.sizePt)
    }

    @Test
    fun `a degenerate crop is refused`() {
        // An inverted or zero-area crop renders an image with no pixels and gives the UI no
        // handles to drag back — unrecoverable through the interface that produced it.
        assertNull(ObjectFormat.decode("if1;c=0.9:0:0.1:1").crop)
        assertNull(ObjectFormat.decode("if1;c=0:0.5:1:0.5").crop)
        assertNull(ObjectFormat.decode("if1;cp=0,0:1,1").crop)
    }

    // -----------------------------------------------------------------------
    // Forward compatibility, one level down (SPEC §5.8)
    // -----------------------------------------------------------------------

    @Test
    fun `an unknown key is kept and written back`() {
        // Without this a later build adding a key would have it destroyed by every M8c device
        // that opened the note — the same obligation `OpExtras` discharges one level up.
        val decoded = ObjectFormat.decode("if1;f=mo;zz=future;aa=alsofuture")
        assertEquals(TextFont.MONO, decoded.font)
        assertEquals(mapOf("zz" to "future", "aa" to "alsofuture"), decoded.extras)
        val reencoded = decoded.encode()
        assertTrue("zz=future" in reencoded, reencoded)
        assertTrue("aa=alsofuture" in reencoded, reencoded)
    }

    @Test
    fun `unknown keys are written in a stable order`() {
        // Identical content must produce identical bytes whatever order it was read in, or an
        // autosave rewrites the register and the sync engine faithfully uploads the churn.
        val a = ObjectFormat.decode("if1;zz=1;aa=2").encode()
        val b = ObjectFormat.decode("if1;aa=2;zz=1").encode()
        assertEquals(a, b)
    }

    @Test
    fun `a record carrying only a later builds key is not treated as default`() {
        // `isDefault` short-circuits the write. If extras did not count, this build would drop a
        // later build's data the first time the user changed nothing at all.
        val decoded = ObjectFormat.decode("if1;zz=future")
        assertEquals(ObjectFormat.Default.copy(extras = mapOf("zz" to "future")), decoded)
        assertTrue(!decoded.isDefault)
        assertNotEquals("", decoded.encode())
    }

    @Test
    fun `an extras value containing a separator survives encoding rather than throwing`() {
        // Deliberate: extras are a *later* build's bytes and this build's job is to hand them
        // back. Rejecting one would turn forward compatibility into a crash on save. What keeps
        // this build's own values safe is that none of them is a free string — see TextFont.
        val decoded = ObjectFormat.decode("if1;zz=a,b")
        assertEquals("a,b", decoded.extras["zz"])
        assertTrue("zz=a,b" in decoded.encode())
    }
}
