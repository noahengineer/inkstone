package dev.inkstone.app.calculator

import dev.inkstone.core.calc.Calculator
import dev.inkstone.core.calc.Key
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a dropped answer lands and what it says — [AnswerPlacement], the arithmetic half of the drag.
 *
 * ⚠️ What this cannot say: that the LCD hands the right window position to the layer, that the layer
 * converts it with the camera, or that the box reaches the journal. That is the emulator walk
 * (docs/CALCULATOR.md §6) — drag on `Test_Tablet`, then read the op out of `journal.ops`.
 */
class AnswerPlacementTest {
    private val letter = PageCanvas.fixed(PageSize.Letter)

    @Test
    fun `the box is centred on the finger and hugs the text`() {
        val box = assertNotNull(AnswerPlacement.boxFor(PagePoint(300f, 400f), 80f, 16f, letter))
        assertEquals(260f, box.left)
        assertEquals(392f, box.top)
        assertEquals(80f + AnswerPlacement.SLACK_PT, box.width)
        assertTrue(box.height >= 16f, "the frame is at least the text's height")
    }

    @Test
    fun `a drop beside a fixed sheet is refused and lands on an infinite one`() {
        val beside = PagePoint(letter.right + 40f, 100f)
        assertNull(AnswerPlacement.boxFor(beside, 80f, 16f, letter))
        val infinite = letter.copy(infinite = true)
        val box = assertNotNull(AnswerPlacement.boxFor(beside, 80f, 16f, infinite))
        assertEquals(beside.x - 40f, box.left, "an infinite page grows to it, so it is not slid back")
    }

    @Test
    fun `an answer that would overhang the edge of the sheet is slid onto it`() {
        val corner = PagePoint(letter.right - 5f, letter.bottom - 2f)
        val box = assertNotNull(AnswerPlacement.boxFor(corner, 80f, 16f, letter))
        assertTrue(box.right <= letter.right, "right ${box.right} past ${letter.right}")
        assertTrue(box.bottom <= letter.bottom, "bottom ${box.bottom} past ${letter.bottom}")
    }

    @Test
    fun `a grown canvas clamps to its own edge and not to the origin`() {
        // SPEC §4.5: grown up and left, the paper starts at negative page coordinates.
        val grown = PageCanvas(PageSize(800f, 900f), PagePoint(-200f, -100f))
        val box = assertNotNull(AnswerPlacement.boxFor(PagePoint(-195f, -98f), 80f, 16f, grown))
        assertEquals(-200f, box.left)
        assertEquals(-100f, box.top)
    }

    /**
     * Every answer the engine can show must survive into the PDF, whose embedded face drops what it
     * cannot represent (research 08 §5.2). Typed on the public `Calculator()`, as the app builds it.
     */
    @Test
    fun `answers from the real engine are within the PDF face`() {
        val typed = listOf(
            listOf(Key.D1, Key.D0, Key.D0, Key.DIVIDE, Key.D1, Key.DECIMAL, Key.D8, Key.D0, Key.EQUALS),
            listOf(Key.D1, Key.DIVIDE, Key.D2, Key.D0, Key.D0, Key.EQUALS),
            listOf(Key.SQRT, Key.D8, Key.EQUALS),
            listOf(Key.SHIFT, Key.EXP, Key.MULTIPLY, Key.D2, Key.EQUALS),
            listOf(Key.D3, Key.MULTIPLY, Key.D1, Key.EXP, Key.D1, Key.D2, Key.EQUALS),
            listOf(Key.NEGATIVE, Key.D2, Key.DECIMAL, Key.D5, Key.PLUS, Key.D1, Key.EQUALS),
        ).map { keys -> Calculator().also { c -> keys.forEach { c.press(it) } }.frame.answer }
        assertEquals(listOf("55,55555556", "1/200", "2√(2)", "2π", "3×10^12", "-1,5"), typed)
        val outside = (typed.filterNotNull() + "2∠45").map(AnswerPlacement::forPage)
            .flatMap { answer -> answer.filterNot { it in AnswerPlacement.PDF_ALPHABET }.toList() }
        assertEquals(emptyList(), outside)
    }

    @Test
    fun `polar form is spelled without the angle sign`() {
        assertEquals("2 ang 45", AnswerPlacement.forPage("2∠45"))
    }

    @Test
    fun `the page gets a decimal point where the LCD shows a comma`() {
        assertEquals("55.55555556", AnswerPlacement.forPage("55,55555556"))
        assertEquals("-1.5", AnswerPlacement.forPage("-1,5"))
        assertEquals("1.25×10^-3", AnswerPlacement.forPage("1,25×10^-3"))
        // The engine separates values with `;` and parts with a line, so no comma is anything but a decimal.
        assertEquals("2;R=1", AnswerPlacement.forPage("2;R=1"))
        assertEquals("r=2.5\nθ=45", AnswerPlacement.forPage("r=2,5\nθ=45"))
    }

    @Test
    fun `handwriting starts on the ghost baseline at the left of the box`() {
        val box = assertNotNull(AnswerPlacement.boxFor(PagePoint(300f, 400f), 80f, 16f, letter))
        assertEquals(PagePoint(box.left, box.top + 13f), AnswerPlacement.baselineStart(box, 13f))
    }
}
