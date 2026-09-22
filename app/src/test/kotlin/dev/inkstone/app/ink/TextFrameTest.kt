package dev.inkstone.app.ink

import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The arithmetic behind *"resizing a text box changes its bounds, not its letters"*.
 *
 * `TextBoxLayer`'s grips cannot be reached from a JVM test — they are Compose nodes over a
 * `SurfaceView` — so what is asserted here is the *decision* they hand to the register, hoisted into
 * [TextEditing] and [ObjectToolState] for exactly CLAUDE.md's reason: a test that cannot reach the
 * real path should at least prove the number it would have written. The gesture itself is checked on
 * the emulator; see `docs/M8-TEXT-FRAME.md`.
 */
class TextFrameTest {

    private val id = ObjectId("0192f000-0000-7000-8000-00000000000a")

    @Test
    fun `a frame drag on an unscaled box writes the page-space size it was dragged to`() {
        val state = ObjectToolState()
        state.edit(focus(bounds = PageRect(10f, 10f, 130f, 54f), widthPt = 120f, heightPt = 44f))

        state.resizeFrame(PageRect(10f, 10f, 260f, 90f))

        val editing = requireNotNull(state.editing)
        assertEquals(250f, editing.frameWidthPt)
        assertEquals(80f, editing.frameHeightPt)
    }

    @Test
    fun `a frame drag on a scaled box is divided by the scale before it is written`() {
        // The trap this exists for: a box the selection handles have doubled has a page-space box
        // twice its frame, so a drag measured in page points would be magnified by that scale a
        // second time — the resize would overshoot by exactly the factor the user had applied.
        val state = ObjectToolState()
        state.edit(focus(bounds = PageRect(0f, 0f, 240f, 88f), widthPt = 120f, heightPt = 44f))

        state.resizeFrame(PageRect(0f, 0f, 300f, 88f))

        assertEquals(150f, requireNotNull(state.editing).frameWidthPt)
        assertEquals(44f, requireNotNull(state.editing).frameHeightPt)
    }

    @Test
    fun `a degenerate box leaves the scale at one rather than dividing by nothing`() {
        val state = ObjectToolState()
        state.edit(focus(bounds = PageRect(0f, 0f, 0f, 0f), widthPt = 0f, heightPt = 0f))

        state.resizeFrame(PageRect(0f, 0f, 60f, 20f))

        assertEquals(60f, requireNotNull(state.editing).frameWidthPt)
    }

    @Test
    fun `placing a new box carries the formatting forward and drops the last one's frame`() {
        // `insertTextBox` takes its extents from the placement rectangle, so a carried-over frame
        // would put a record on the new object contradicting its own payload — in a register that
        // is absolute and wins.
        val state = ObjectToolState()
        state.place(
            bounds = PageRect(0f, 0f, 220f, 44f),
            format = ObjectFormat(font = dev.inkstone.core.model.TextFont.MONO, boxWidthPt = 400f, boxHeightPt = 200f),
        )

        assertEquals(dev.inkstone.core.model.TextFont.MONO, state.format.font)
        assertNull(state.format.boxWidthPt)
        assertNull(state.format.boxHeightPt)
        assertNull(requireNotNull(state.editing).id)
    }

    @Test
    fun `a selection re-report cannot close a field that is open`() {
        // `onSelectionChanged` fires for reasons that are not the user changing their mind — a
        // peer's op landing, a bounds refresh after a drag. Dropping the focus then would take the
        // frame handles off the screen mid-edit.
        val state = ObjectToolState()
        val focus = focus(bounds = PageRect(0f, 0f, 120f, 44f), widthPt = 120f, heightPt = 44f)
        state.edit(focus)

        state.focusText(null)

        assertEquals(id, requireNotNull(state.editing).id)
    }

    @Test
    fun `editing from a selection carries the words and the format into the field`() {
        val state = ObjectToolState()
        val format = ObjectFormat(sizePt = 22f, boxWidthPt = 120f)
        state.edit(focus(PageRect(0f, 0f, 120f, 44f), 120f, 44f, text = "two lines", format = format))

        assertEquals("two lines", state.draft)
        assertSame(format, state.format)
    }

    private fun focus(
        bounds: PageRect,
        widthPt: Float,
        heightPt: Float,
        text: String = "",
        format: ObjectFormat = ObjectFormat.Default,
    ) = TextFocus(
        id = id,
        bounds = bounds,
        widthPt = widthPt,
        heightPt = heightPt,
        text = text,
        format = format,
        style = ObjectStyle(colorArgb = -0x1000000, widthPt = 1f),
    )
}
