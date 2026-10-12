package dev.inkstone.core.calc.lcd

import dev.inkstone.core.calc.input.ConstantItem
import dev.inkstone.core.calc.input.ConversionItem
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.Item
import dev.inkstone.core.calc.input.ScientificConstants
import dev.inkstone.core.calc.input.Template
import dev.inkstone.core.calc.input.TemplateKind
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.input.TokenItem

/**
 * Turns an [Expr] into boxes, either as the Natural display draws it ([natural]) — templates
 * stacked, nested content in the small font — or as Linear display does: one row of glyphs in the
 * LineIO spellings ([ExprText.Style.SCREEN]).
 */
internal class MathBuilder(private val natural: Boolean) {
    private val templates = NaturalTemplates(this)

    fun root(expr: Expr, font: LcdFont = LcdFont.LARGE): ExprRow = row(expr, font, ExprRow.ROOT, root = true)

    fun row(expr: Expr, font: LcdFont, slot: Int, root: Boolean = false): ExprRow {
        val boxes = ArrayList<Box>(expr.items.size)
        var marks = 0
        for (item in expr.items) {
            marks = dmsCount(item, marks)
            // An exponent takes its trailing blank column only once something follows it (ScriptBox).
            boxes.lastOrNull()?.let { last -> boxes[boxes.lastIndex] = last.withScriptGap() }
            boxes += box(item, boxes.lastOrNull(), font, marks)
        }
        val placeholder = if (root || !natural) null else PlaceholderBox(font)
        return ExprRow(boxes, slot, font, placeholder)
    }

    private fun box(item: Item, previous: Box?, font: LcdFont, marks: Int): Box = when (item) {
        is TokenItem -> token(item.tok, previous, font, marks)
        is ConstantItem -> constant(item.number, font)
        is ConversionItem -> TextBox(ExprText.conversion(item.number), font)
        is Template -> template(item, previous, font)
    }

    private fun template(template: Template, previous: Box?, font: LcdFont): Box = if (natural) {
        TemplateBox(template.kind, templates.build(template, previous, font))
    } else {
        TextBox(ExprText.item(template, 0, ExprText.Style.SCREEN), font)
    }

    /**
     * In Natural display `x²`, `x³` and `x⁻¹` are superscripts, the same as an `x^□` exponent:
     * research 01 §3.6's `3A²+4B` drops the whole line two rows for the small `2`. Linear display
     * draws the token's own raised glyph in the line instead.
     */
    private fun token(tok: Tok, previous: Box?, font: LcdFont, marks: Int): Box {
        val raised = SUPERSCRIPT_TOKENS[tok]
        return if (natural && raised != null) {
            superscript(previous, font, TextBox(raised, LcdFont.SMALL))
        } else {
            TextBox(ExprText.token(tok, marks), font)
        }
    }

    /** `mₚ`, `C₀`: the symbol in the line's font, its subscript one size down, bottoms aligned. */
    private fun constant(number: Int, font: LcdFont): Box {
        val symbol = ScientificConstants.get(number)?.symbol ?: return TextBox("?", font)
        val base = TextBox(symbol.base, font)
        if (symbol.subscript.isEmpty()) return base
        val subFont = if (font === LcdFont.LARGE) LcdFont.SMALL else LcdFont.TINY
        return HBox(listOf(base, ShiftBox(TextBox(symbol.subscript, subFont), font.below - subFont.below)))
    }

    /**
     * Research 01 §3.6: the exponent's bottom row is the base glyph's 4th row, so a small exponent
     * after a large glyph sits two rows above it and the base drops two rows. Horizontally the
     * exponent's cell follows the base's directly and a blank column comes after it — `X²−6X=0`
     * measures X on 1–5, the 2 on 7–11 and the − on 14–18 (SA slide 14); see [ScriptBox].
     */
    fun superscript(previous: Box?, font: LcdFont, exponent: Box): Box {
        val baseAbove = previous?.above ?: font.axis
        val bottom = SCRIPT_OVERLAP - baseAbove
        return ScriptBox(exponent, bottom - (exponent.below - 1))
    }

    /** A log base hangs from the axis: its top row is the one under the axis. A guess (unmeasured). */
    fun subscript(content: Box): Box = ShiftBox(content, 1 + content.above)

    private companion object {
        /** Rows of the base glyph above the exponent's bottom row, plus one: the 4th row. */
        const val SCRIPT_OVERLAP = 3

        val SUPERSCRIPT_TOKENS = mapOf(Tok.SQUARE to "2", Tok.CUBE to "3", Tok.RECIPROCAL to "-1")
    }
}

/**
 * The Natural-display shape of each template. Where research 01 §3.6 measured one (fraction,
 * superscript) it is followed exactly; the others are our drawings on the same grid, and their
 * box classes say so.
 */
internal class NaturalTemplates(private val builder: MathBuilder) {
    private val small = LcdFont.SMALL

    fun build(template: Template, previous: Box?, font: LcdFont): Box = when (template.kind) {
        TemplateKind.FRACTION -> FractionBox(slot(template, 0, small), slot(template, 1, small))

        TemplateKind.MIXED_FRACTION -> HBox(
            listOf(slot(template, 0, font), FractionBox(slot(template, 1, small), slot(template, 2, small))),
        )

        TemplateKind.SQRT -> RadicalBox(null, slot(template, 0, font))

        TemplateKind.CBRT -> RadicalBox(TextBox("3", small), slot(template, 0, font))

        TemplateKind.NTH_ROOT -> RadicalBox(slot(template, 0, small), slot(template, 1, font))

        TemplateKind.ABS -> BarsBox(slot(template, 0, font))

        TemplateKind.INTEGRAL, TemplateKind.DERIVATIVE, TemplateKind.SUMMATION -> bigOperator(template, font)

        else -> scripted(template, previous, font)
    }

    private fun scripted(template: Template, previous: Box?, font: LcdFont): Box {
        val first = slot(template, 0, small)
        return when (template.kind) {
            TemplateKind.POWER -> builder.superscript(previous, font, first)

            TemplateKind.TEN_POWER -> raisedAfter("10", font, first)

            TemplateKind.E_POWER -> raisedAfter("e", font, first)

            // Research 02 §8.3: a compact × and small 10 — the one-cell glyph of the ×10ˣ key.
            TemplateKind.SCI_EXPONENT -> raisedAfter(Tok.EXP10.text, font, first)

            TemplateKind.LOG_BASE -> HBox(
                listOf(
                    TextBox("log", font),
                    builder.subscript(first),
                    TextBox("(", font),
                    slot(template, 1, font),
                    TextBox(")", font),
                ),
            )

            else -> TextBox(ExprText.item(template, 0, ExprText.Style.SCREEN), font)
        }
    }

    private fun raisedAfter(text: String, font: LcdFont, exponent: Box): Box {
        val base = TextBox(text, font)
        return HBox(listOf(base, builder.superscript(base, font, exponent)))
    }

    private fun bigOperator(template: Template, font: LcdFont): Box = when (template.kind) {
        TemplateKind.INTEGRAL -> IntegralBox(
            upper = slot(template, 2, small),
            lower = slot(template, 1, small),
            body = HBox(listOf(slot(template, 0, font), TextBox("dx", font))),
        )

        TemplateKind.DERIVATIVE -> DerivativeBox(
            body = bracketed(FractionBox(TextBox("d", small), TextBox("dx", small)), slot(template, 0, font), font),
            at = HBox(listOf(TextBox("x=", small), slot(template, 1, small))),
        )

        TemplateKind.SUMMATION -> bracketed(
            ColumnBox(
                top = slot(template, 2, small),
                middle = TextBox("Σ", font),
                bottom = HBox(listOf(TextBox("x=", small), slot(template, 1, small))),
            ),
            slot(template, 0, font),
            font,
        )

        else -> TextBox(ExprText.item(template, 0, ExprText.Style.SCREEN), font)
    }

    private fun bracketed(operator: Box, argument: Box, font: LcdFont): Box =
        HBox(listOf(operator, TextBox("(", font), argument, TextBox(")", font)))

    private fun slot(template: Template, index: Int, font: LcdFont): ExprRow =
        builder.row(template.slots[index], font, index)
}
