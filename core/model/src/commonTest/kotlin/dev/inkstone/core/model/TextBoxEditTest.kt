package dev.inkstone.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ROADMAP M8c — the ops a text box gesture writes.
 *
 * ⚠️ **What these establish and what they do not.** They prove that one gesture emits one ordered
 * run of ops, that the two registers stay two, and that nothing empty is written. They prove
 * nothing about whether a text box is reachable in the app — `TextBoxEdit` is a pure op builder,
 * and CLAUDE.md's definition-of-done #6 is explicit that a green suite here says nothing about a
 * feature being wired to a button. That check belongs to the emulator walk.
 */
class TextBoxEditTest {

    private var opCounter = 0
    private var clockCounter = 0L

    private fun nextOpId(): String = "op-${opCounter++}"

    private fun nextClock(): LamportClock = LamportClock(++clockCounter, DEVICE)

    // -----------------------------------------------------------------------
    // create — one gesture, in order
    // -----------------------------------------------------------------------

    @Test
    fun `a formatted text box is an AddObject then its words then its formatting`() {
        val ops = TextBoxEdit.create(
            id = BOX,
            text = "Titration",
            widthPt = 200f,
            heightPt = 40f,
            transform = Matrix3.translate(30f, 50f),
            z = FractionalKey("m"),
            style = STYLE,
            format = ObjectFormat(font = TextFont.SERIF, sizePt = 18f, align = TextAlign.CENTER),
            nextOpId = ::nextOpId,
            nextClock = ::nextClock,
        )

        assertEquals(3, ops.size)
        val add = assertNotNull(ops[0] as? PageOp.AddObject)
        val box = assertNotNull(add.obj as? TextBoxObject)
        assertEquals(BOX, box.id)
        assertEquals("Titration", box.text)
        assertEquals(Matrix3.translate(30f, 50f), box.transform)

        // The register order is the whole point: a register write sorting before its AddObject is
        // pointless work for the fold, and one gesture is one undo entry.
        assertEquals("Titration", assertNotNull(ops[1] as? PageOp.SetTextContent).text)
        val format = ObjectFormat.decode(assertNotNull(ops[2] as? PageOp.SetFormat).record)
        assertEquals(TextFont.SERIF, format.font)
        assertEquals(TextAlign.CENTER, format.align)
    }

    @Test
    fun `a plain empty text box writes one op and no empty registers`() {
        // ShapeEdit's isDefault short-circuit, applied to both registers: writing an empty record
        // would put a string in every note that places a label.
        val ops = TextBoxEdit.create(
            id = BOX,
            text = "",
            widthPt = 100f,
            heightPt = 20f,
            transform = Matrix3.Identity,
            z = FractionalKey("m"),
            style = STYLE,
            format = ObjectFormat.Default,
            nextOpId = ::nextOpId,
            nextClock = ::nextClock,
        )

        assertEquals(1, ops.size)
        assertTrue(ops.single() is PageOp.AddObject)
    }

    @Test
    fun `words without formatting write the text register only`() {
        val ops = TextBoxEdit.create(
            id = BOX,
            text = "Note",
            widthPt = 100f,
            heightPt = 20f,
            transform = Matrix3.Identity,
            z = FractionalKey("m"),
            style = STYLE,
            format = ObjectFormat.Default,
            nextOpId = ::nextOpId,
            nextClock = ::nextClock,
        )

        assertEquals(2, ops.size)
        assertTrue(ops[1] is PageOp.SetTextContent)
    }

    @Test
    fun `the box is created with a style so the first recolour has an inverse`() {
        // ⭐ SPEC §4.9.2's trap. `SetStyle` cannot express "unset", so a text box shipped with a
        // null style has no previous value to invert to: `UndoStack.commit` drops the entry and
        // "change the colour, press undo" does nothing while eating the press. The parameter is
        // non-null, so this asserts the payload actually carries it rather than dropping it.
        val ops = TextBoxEdit.create(
            id = BOX,
            text = "",
            widthPt = 100f,
            heightPt = 20f,
            transform = Matrix3.Identity,
            z = FractionalKey("m"),
            style = STYLE,
            format = ObjectFormat.Default,
            nextOpId = ::nextOpId,
            nextClock = ::nextClock,
        )
        val box = assertNotNull((ops[0] as? PageOp.AddObject)?.obj)
        assertEquals(STYLE, box.style)

        val page = foldPage(PAGE, ops)
        val inverse = Undo.inverseOf(
            PageOp.SetStyle(nextOpId(), nextClock(), BOX, ObjectStyle(0x00FF0000, 1f)),
            page,
            nextOpId(),
            nextClock(),
        )
        assertEquals(STYLE, assertNotNull(inverse as? PageOp.SetStyle).style)
    }

    @Test
    fun `a zero-height box is clamped so it can still be resized and erased`() {
        val ops = TextBoxEdit.create(
            id = BOX,
            text = "",
            widthPt = 0f,
            heightPt = 0f,
            transform = Matrix3.Identity,
            z = FractionalKey("m"),
            style = STYLE,
            format = ObjectFormat.Default,
            nextOpId = ::nextOpId,
            nextClock = ::nextClock,
        )
        val box = assertNotNull((ops[0] as? PageOp.AddObject)?.obj as? TextBoxObject)
        assertTrue(box.widthPt >= ShapeGeometry.MIN_EXTENT_PT)
        assertTrue(box.heightPt >= ShapeGeometry.MIN_EXTENT_PT)
    }

    // -----------------------------------------------------------------------
    // The two registers stay two
    // -----------------------------------------------------------------------

    @Test
    fun `restyling a text box does not touch its words`() {
        // ⭐ SPEC §4.9.2's reason for a second register. One register holding both would resolve
        // whole, so a font change on one device would discard a paragraph typed on the other —
        // converged, with every op present and no error.
        val created = TextBoxEdit.create(
            id = BOX,
            text = "A paragraph somebody typed",
            widthPt = 200f,
            heightPt = 60f,
            transform = Matrix3.Identity,
            z = FractionalKey("m"),
            style = STYLE,
            format = ObjectFormat.Default,
            nextOpId = ::nextOpId,
            nextClock = ::nextClock,
        )
        val restyle = TextBoxEdit.setFormat(
            BOX,
            ObjectFormat(font = TextFont.MONO, sizePt = 9f),
            ::nextOpId,
            ::nextClock,
        )

        val page = foldPage(PAGE, created + restyle)
        assertEquals("A paragraph somebody typed", page.textOf(BOX))
        assertEquals(TextFont.MONO, page.formatting(BOX).font)
    }

    @Test
    fun `the default format encodes to nothing so an unset register reads as the default`() {
        val op = assertNotNull(
            TextBoxEdit.setFormat(BOX, ObjectFormat.Default, ::nextOpId, ::nextClock) as? PageOp.SetFormat,
        )
        assertEquals("", op.record)
        assertEquals(ObjectFormat.Default, ObjectFormat.decode(op.record))
    }

    @Test
    fun `formatting answers the default for an object with no register`() {
        val page = foldPage(PAGE, emptyList())
        assertEquals(ObjectFormat.Default, page.formatting(BOX))
        assertNull(page.formatOf(BOX))
    }

    private companion object {
        const val DEVICE = "device-a"
        val PAGE = PageId("page-1")
        val BOX = ObjectId("obj-box")
        val STYLE = ObjectStyle(colorArgb = -0x1000000, widthPt = 1f)
    }
}
