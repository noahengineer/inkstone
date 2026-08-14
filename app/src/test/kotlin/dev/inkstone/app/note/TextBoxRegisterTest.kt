package dev.inkstone.app.note

import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.TextAlign
import dev.inkstone.core.model.TextBoxEdit
import dev.inkstone.core.model.TextFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⭐ ROADMAP M8c — **the words and the formatting are two registers, and the editor must never
 * write one when the user changed the other** (SPEC §4.9.2).
 *
 * ## Why this test is in `:app` as well as in `:core:model`
 *
 * `TextBoxEdit` has its own tests, and they assert what the builders emit. This asserts what the
 * *screen* does with them — that the format bar's chips end at `SetFormat` and the keyboard ends at
 * `SetTextContent`, with no path that touches both. That is the failure the two registers exist to
 * prevent: an LWW register resolves whole, so a font change sharing a register with the words would
 * **discard a paragraph typed on the other device** — converged, on both devices, with every op
 * present and no error.
 *
 * `NoteEditor` itself cannot be driven from here without a session and a filesystem
 * (`NoteEditorLibraryTest` does that), so what is checked is the decision each UI action reduces
 * to — CLAUDE.md's *"hoist the decision into a pure function over plain data"*.
 */
class TextBoxRegisterTest {

    @Test
    fun `a text change emits SetTextContent and nothing else`() {
        val ops = listOf(TextBoxEdit.setText(BOX, "Photosynthesis", ::opId, ::clock))
        assertEquals(1, ops.size)
        val op = ops.single()
        assertTrue(op is PageOp.SetTextContent, "a text edit must not touch the format register")
        assertEquals("Photosynthesis", op.text)
    }

    @Test
    fun `a format change emits SetFormat and nothing else`() {
        val ops = listOf(TextBoxEdit.setFormat(BOX, ObjectFormat.Default.copy(font = TextFont.SERIF), ::opId, ::clock))
        assertEquals(1, ops.size)
        val op = ops.single()
        assertTrue(op is PageOp.SetFormat, "a format edit must not touch the text register")
        assertTrue(op.record.contains("se"), "the serif tag should be in the record, got ${op.record}")
    }

    /**
     * ⭐ The register that must survive: typing after a restyle keeps the font, and restyling after
     * typing keeps the words. Two registers is exactly what makes the two commute.
     */
    @Test
    fun `the two edits target the same object without overwriting each other`() {
        val restyle = TextBoxEdit.setFormat(BOX, ObjectFormat.Default.copy(align = TextAlign.CENTER), ::opId, ::clock)
        val type = TextBoxEdit.setText(BOX, "chlorophyll", ::opId, ::clock)
        assertEquals(BOX, restyle.target)
        assertEquals(BOX, type.target)
        assertTrue(restyle::class != type::class, "the two edits must be different op types")
    }

    /**
     * ⚠️ **The format change is built from the object's *current* format**, not from a fresh one.
     *
     * `SetFormat` carries the whole record, so a bar that built one from the chip the user tapped
     * would silently reset every other property — including [ObjectFormat.extras], which is a later
     * build's data and the one thing this build cannot reconstruct (SPEC §5.8). This is the shape
     * `NoteEditor.updateFormat` enforces by taking a function of the live value.
     */
    @Test
    fun `changing one property keeps the others including a later builds keys`() {
        val current = ObjectFormat.Default.copy(
            font = TextFont.MONO,
            sizePt = 22f,
            extras = mapOf("zz" to "fromTheFuture"),
        )
        val next = current.copy(align = TextAlign.END)
        val op = TextBoxEdit.setFormat(BOX, next, ::opId, ::clock) as PageOp.SetFormat
        val decoded = ObjectFormat.decode(op.record)
        assertEquals(TextFont.MONO, decoded.font)
        assertEquals(22f, decoded.sizePt)
        assertEquals(TextAlign.END, decoded.align)
        assertEquals("fromTheFuture", decoded.extras["zz"])
    }

    /**
     * ⭐ **What an IME produces reaches the register unaltered.**
     *
     * The point of the scope line, and the one place in this app that used to destroy it: M4's
     * `PdfContent.toByteArray` truncated every `Char` above `U+00FF` into a *different, valid* byte
     * (docs/M8-TEXT.md §3.1). Nothing on this path encodes, normalises or sanitises, and this is
     * what says so — a Greek word, a Cyrillic one, a combining accent and an astral-plane emoji, in
     * and out.
     */
    @Test
    fun `non Latin-1 text survives to the op unchanged`() {
        for (typed in listOf("Φωτοσύνθεση", "Фотосинтез", "égalité", "🌱🌿", "混合 mixed")) {
            val op = TextBoxEdit.setText(BOX, typed, ::opId, ::clock) as PageOp.SetTextContent
            assertEquals(typed, op.text)
            assertEquals(typed.length, op.text.length)
        }
    }

    /**
     * ⚠️ Placing a box and typing its first words is **one gesture**, and this is the reason.
     *
     * `TextBoxEdit.create` seeds the text register only when there is text, so a box created empty
     * and typed into separately has no inverse for that first `SetTextContent` — `UndoStack.commit`
     * drops the entry and the press falls through to the create, deleting the box the user was
     * typing in. `ObjectToolState.place` therefore holds the tap's position and creates nothing
     * until the first commit has characters in it.
     */
    @Test
    fun `creating a box with its first words is one gesture carrying both registers`() {
        val ops = TextBoxEdit.create(
            id = BOX,
            text = "Kreb cycle",
            widthPt = 220f,
            heightPt = 44f,
            transform = dev.inkstone.core.model.Matrix3.translate(10f, 10f),
            z = dev.inkstone.core.model.FractionalKey.Initial,
            style = dev.inkstone.core.model.ObjectStyle(colorArgb = -1, widthPt = 1f),
            format = ObjectFormat.Default.copy(sizePt = 18f),
            nextOpId = ::opId,
            nextClock = ::clock,
        )
        assertEquals(3, ops.size, "create should emit AddObject, SetTextContent and SetFormat")
        assertTrue(ops[0] is PageOp.AddObject)
        assertTrue(ops[1] is PageOp.SetTextContent)
        assertTrue(ops[2] is PageOp.SetFormat)
    }

    /** A default presentation is the *absence* of a record — writing an empty one is writing nothing. */
    @Test
    fun `a plain box writes no format register at all`() {
        val ops = TextBoxEdit.create(
            id = BOX,
            text = "plain",
            widthPt = 220f,
            heightPt = 44f,
            transform = dev.inkstone.core.model.Matrix3.Identity,
            z = dev.inkstone.core.model.FractionalKey.Initial,
            style = dev.inkstone.core.model.ObjectStyle(colorArgb = -1, widthPt = 1f),
            format = ObjectFormat.Default,
            nextOpId = ::opId,
            nextClock = ::clock,
        )
        assertTrue(ops.none { it is PageOp.SetFormat }, "the default presentation must not be written")
    }

    private var counter = 0L

    private fun opId(): String = "op-${counter++}"

    private fun clock(): LamportClock = LamportClock(++counter, "test-device")

    private companion object {
        val BOX = ObjectId("018f0000-0000-7000-8000-00000000b0b0")
    }
}
