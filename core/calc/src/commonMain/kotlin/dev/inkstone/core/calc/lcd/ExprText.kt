package dev.inkstone.core.calc.lcd

import dev.inkstone.core.calc.input.ConstantItem
import dev.inkstone.core.calc.input.ConversionItem
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.Item
import dev.inkstone.core.calc.input.MetricConversions
import dev.inkstone.core.calc.input.ScientificConstants
import dev.inkstone.core.calc.input.Template
import dev.inkstone.core.calc.input.TemplateKind
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.input.TokenItem

/**
 * An [Expr] as one line of text, in two spellings.
 *
 * [Style.SCREEN] is what Linear display draws: the LineIO spellings of research 02 §7.4 (`2⌟3`,
 * `√(2)`, `log(2;8)`, `∫(f;a;b)`) and an exponential result as the one-cell `×₁₀` glyph with a
 * raised exponent (research 02 §8.3: "a compact multiplication sign followed by a small 10").
 * [Style.PLAIN] is [dev.inkstone.core.calc.lcd.LcdFrame.text]'s accessibility form: `1/200` for a
 * stacked fraction and `1,25×10^-3` for exponential form, as the frame's KDoc promises.
 */
internal object ExprText {
    enum class Style { SCREEN, PLAIN }

    fun of(expr: Expr, style: Style): String {
        val out = StringBuilder()
        var marks = 0
        for (item in expr.items) {
            marks = dmsCount(item, marks)
            out.append(item(item, marks, style))
        }
        return out.toString()
    }

    fun item(item: Item, marks: Int, style: Style): String = when (item) {
        is TokenItem -> token(item.tok, marks)
        is ConstantItem -> ScientificConstants.get(item.number)?.symbol?.text ?: UNKNOWN
        is ConversionItem -> conversion(item.number)
        is Template -> template(item, style)
    }

    fun token(tok: Tok, marks: Int): String = if (tok == Tok.DMS) dmsMark(marks) else tok.text

    fun conversion(number: Int): String = MetricConversions.get(number)?.text ?: UNKNOWN

    /**
     * The `°'"` key is one token; the n-th of them inside one number is drawn as `°`, `'` or `"`
     * (research 02 §8.9). A fourth in a row cannot be typed usefully and repeats `"`.
     */
    fun dmsMark(marks: Int): String = DMS_MARKS[(marks - 1).coerceIn(0, DMS_MARKS.lastIndex)]

    /** Turns exponent text into raised marks: `-12` → `⁻¹²`. */
    fun superscripted(text: String): String = text.map { RAISED[it] ?: it }.joinToString("")

    private fun template(template: Template, style: Style): String {
        if (style == Style.PLAIN) plain(template)?.let { return it }
        if (template.kind == TemplateKind.SCI_EXPONENT) {
            return Tok.EXP10.text + superscripted(of(template.slots[0], style))
        }
        val spelling = SPELLINGS.getValue(template.kind)
        val slots = spelling.order.map { of(template.slots[it], style) }
        val out = StringBuilder(spelling.open)
        slots.forEachIndexed { i, slot ->
            if (i > 0) out.append(spelling.separators[i - 1])
            out.append(slot)
        }
        return out.append(spelling.close).toString()
    }

    /** The accessibility spellings that differ from the screen's; null = same as the screen. */
    private fun plain(template: Template): String? {
        val slots = template.slots.map { of(it, Style.PLAIN) }
        val grouped = template.slots.map { grouped(it) }
        return when (template.kind) {
            TemplateKind.FRACTION -> "${grouped[0]}/${grouped[1]}"
            TemplateKind.MIXED_FRACTION -> "${slots[0]} ${grouped[1]}/${grouped[2]}"
            TemplateKind.POWER -> "^${grouped[0]}"
            TemplateKind.TEN_POWER -> "10^${grouped[0]}"
            TemplateKind.E_POWER -> "e^${grouped[0]}"
            TemplateKind.SCI_EXPONENT -> "×10^${slots[0]}"
            else -> null
        }
    }

    /** Brackets an operand unless it is one item or a plain number: `1/200`, `(1+2)/3`. */
    private fun grouped(expr: Expr): String {
        val text = of(expr, Style.PLAIN)
        val plainNumber = expr.items.all { (it as? TokenItem)?.tok in NUMBER_PARTS }
        return if (expr.items.size > 1 && !plainNumber) "($text)" else text
    }

    private class Spelling(val open: String, val separators: List<String>, val close: String, val order: List<Int>)

    private fun spelling(open: String, close: String, vararg separators: String) =
        Spelling(open, separators.toList(), close, List(separators.size + 1) { it })

    private val SPELLINGS: Map<TemplateKind, Spelling> = mapOf(
        TemplateKind.FRACTION to spelling("", "", "⌟"),
        TemplateKind.MIXED_FRACTION to spelling("", "", "⌟", "⌟"),
        TemplateKind.SQRT to spelling("√(", ")"),
        TemplateKind.CBRT to spelling("∛(", ")"),
        TemplateKind.NTH_ROOT to spelling("", ")", "ˣ√("),
        TemplateKind.POWER to spelling("^(", ")"),
        TemplateKind.LOG_BASE to spelling("log(", ")", ";"),
        TemplateKind.TEN_POWER to spelling("10^(", ")"),
        TemplateKind.E_POWER to spelling("e^(", ")"),
        TemplateKind.ABS to spelling("Abs(", ")"),
        TemplateKind.INTEGRAL to spelling("∫(", ")", ";", ";"),
        TemplateKind.DERIVATIVE to spelling("d/dx(", ")", ";"),
        TemplateKind.SUMMATION to spelling("Σ(", ")", ";", ";"),
    )

    private val DMS_MARKS = listOf("°", "'", "\"")
    private const val UNKNOWN = "?"
    private val RAISED: Map<Char, Char> = ("0123456789-−".toList() zip "⁰¹²³⁴⁵⁶⁷⁸⁹⁻⁻".toList()).toMap()
}

/** The running count of `°'"` tokens inside the current number: digits keep it, anything else resets it. */
internal fun dmsCount(item: Item, previous: Int): Int {
    val tok = (item as? TokenItem)?.tok
    return when {
        tok == Tok.DMS -> previous + 1
        tok != null && tok in NUMBER_PARTS -> previous
        else -> 0
    }
}

private val NUMBER_PARTS = setOf(
    Tok.D0, Tok.D1, Tok.D2, Tok.D3, Tok.D4, Tok.D5, Tok.D6, Tok.D7, Tok.D8, Tok.D9, Tok.DECIMAL,
)
