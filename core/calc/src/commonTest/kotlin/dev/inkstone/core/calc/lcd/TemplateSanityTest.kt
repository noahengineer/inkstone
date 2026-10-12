package dev.inkstone.core.calc.lcd

import dev.inkstone.core.calc.input.Cursor
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.PathStep
import dev.inkstone.core.calc.input.Template
import dev.inkstone.core.calc.input.TemplateKind
import dev.inkstone.core.calc.input.Tok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every Natural-display template, filled and empty: it draws only inside its own box, its slots do
 * not overlap, and the cursor can stand at every position of every slot. Most template shapes are
 * our own drawings (research 01 §3.6 measured only the fraction and the superscript), so these
 * check that the shapes are consistent with the layout, not that they match Casio's.
 */
class TemplateSanityTest {
    private val samples: Map<TemplateKind, List<Expr>> = mapOf(
        TemplateKind.FRACTION to listOf(ex("12"), ex("345")),
        TemplateKind.MIXED_FRACTION to listOf(ex("1"), ex("2"), ex("3")),
        TemplateKind.SQRT to listOf(ex("2")),
        TemplateKind.CBRT to listOf(ex("27")),
        TemplateKind.NTH_ROOT to listOf(ex("5"), ex("32")),
        TemplateKind.POWER to listOf(ex("10")),
        TemplateKind.LOG_BASE to listOf(ex("2"), ex("8")),
        TemplateKind.TEN_POWER to listOf(ex("3")),
        TemplateKind.E_POWER to listOf(ex("2")),
        TemplateKind.ABS to listOf(ex("-3")),
        TemplateKind.INTEGRAL to listOf(ex("X"), ex("0"), ex("1")),
        TemplateKind.DERIVATIVE to listOf(ex("X"), ex("2")),
        TemplateKind.SUMMATION to listOf(ex("X"), ex("1"), ex("5")),
        TemplateKind.SCI_EXPONENT to listOf(ex("-3")),
    )

    private val templates: List<Template> =
        samples.map { (kind, slots) -> Template(kind, slots) } + TemplateKind.entries.map { Template.empty(it) }

    /** Each template after a `2`, so a power has a base. */
    private fun expr(template: Template) = Expr(items("2") + template)

    private fun templateBox(template: Template): TemplateBox =
        MathBuilder(natural = true).root(expr(template)).item(1) as TemplateBox

    @Test
    fun everyKindHasASample() {
        assertEquals(TemplateKind.entries.toSet(), samples.keys)
    }

    @Test
    fun everyTemplateDrawsOnlyInsideItsOwnBox() {
        for (template in templates) {
            val box = LaidOutLine(expr(template), natural = true).box
            assertTrue(box.width <= 90 && box.height <= 29, "${template.kind} is ${box.width} × ${box.height}")
            val canvas = LcdCanvas()
            box.draw(canvas, 2, 2 + box.above)
            val ink = assertNotNull(canvas.freeze(emptySet(), null, "").ink(), "${template.kind} drew nothing")
            assertTrue(ink.left >= 2 && ink.top >= 2, "${template.kind} drew above or left of its box: $ink")
            val inside = ink.right <= 1 + box.width && ink.bottom <= 1 + box.height
            assertTrue(inside, "${template.kind} drew past its box (${box.width} × ${box.height}): $ink")
        }
    }

    @Test
    fun slotsDoNotOverlap() {
        for (template in templates) {
            val rects = templateBox(template).slotRows().map { slot ->
                listOf(slot.dx, slot.dy - slot.row.above, slot.dx + slot.row.width, slot.dy + slot.row.below)
            }
            assertEquals(template.kind.slotCount, rects.size, "${template.kind} slots")
            for (i in rects.indices) {
                for (j in i + 1 until rects.size) {
                    val (a, b) = rects[i] to rects[j]
                    val apart = a[2] <= b[0] || b[2] <= a[0] || a[3] <= b[1] || b[3] <= a[1]
                    assertTrue(apart, "${template.kind} slots $i and $j overlap: $a $b")
                }
            }
        }
    }

    @Test
    fun theCursorCanStandEverywhereInEverySlot() {
        for (template in templates) {
            val positions = template.slots.flatMapIndexed { slot, content ->
                (0..content.items.size).map { index -> Cursor(listOf(PathStep(1, slot)), index) }
            }
            val rects = positions.map { cursor ->
                val frame = Screens.calculation(emptySet(), expr(template), cursor, natural = true)
                assertNotNull(frame.cursor, "${template.kind} at $cursor")
            }
            assertEquals(rects.size, rects.toSet().size, "${template.kind}: two positions share a cursor rectangle")
        }
    }

    @Test
    fun theSlotsVisitedByTheCursorAreInSlotOrder() {
        val integral = templateBox(Template(TemplateKind.INTEGRAL, listOf(ex("X"), ex("0"), ex("1"))))
        val rows = integral.slotRows()
        assertTrue(rows[1].dy > 0 && rows[2].dy < 0, "lower limit below the axis, upper above it")
        assertEquals(listOf(0, 1, 2), rows.map { it.row.slotIndex })
    }

    @Test
    fun nestedContentStaysInTheSmallFont() {
        val nested = Expr(listOf(template(TemplateKind.FRACTION, Expr(listOf(frac("1", "2"))), ex("3"))))
        assertEquals(24, MathLayout.measure(nested, natural = true).height)
        val squared = ex(Tok.VAR_A, Tok.SQUARE)
        assertEquals(11, MathLayout.measure(squared, natural = true).height)
        assertEquals(9, MathLayout.measure(squared, natural = false).height)
    }

    @Test
    fun linearDisplayDrawsOneRowOfLineIoSpellings() {
        val size = MathLayout.measure(Expr(listOf(frac("12", "345"))), natural = false)
        assertEquals(LcdSize(36, 9), size)
    }
}
