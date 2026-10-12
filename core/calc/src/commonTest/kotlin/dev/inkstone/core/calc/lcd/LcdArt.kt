package dev.inkstone.core.calc.lcd

import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.Item
import dev.inkstone.core.calc.input.Template
import dev.inkstone.core.calc.input.TemplateKind
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.input.TokenItem

/** The frame as 31 lines of 96 `#` (dark) and `.` (clear) — the form golden tests compare. */
internal fun LcdFrame.art(): String = (0 until LcdFrame.HEIGHT).joinToString("\n") { y ->
    (0 until LcdFrame.WIDTH).joinToString("") { x -> if (this[x, y]) "#" else "." }
}

/** Golden art written as a raw string: blank lines around it and indentation are ignored. */
internal fun golden(picture: String): String = picture.trimIndent().trim()

/** The bounding box of the dark dots inside a region, or null if the region is clear. */
internal data class Ink(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal fun LcdFrame.ink(columns: IntRange = 0 until LcdFrame.WIDTH, rows: IntRange = 0 until LcdFrame.HEIGHT): Ink? {
    val dark = rows.flatMap { y -> columns.filter { x -> this[x, y] }.map { x -> x to y } }
    if (dark.isEmpty()) return null
    return Ink(dark.minOf { it.first }, dark.minOf { it.second }, dark.maxOf { it.first }, dark.maxOf { it.second })
}

/** Columns that are dark on row [y]. */
internal fun LcdFrame.darkColumns(y: Int): List<Int> = (0 until LcdFrame.WIDTH).filter { this[it, y] }

/** Rows that have any dark dot in [columns]. */
internal fun LcdFrame.darkRows(columns: IntRange = 0 until LcdFrame.WIDTH): List<Int> =
    (0 until LcdFrame.HEIGHT).filter { y -> columns.any { this[it, y] } }

// ---- Building expressions in tests ---------------------------------------------------------

internal fun tok(t: Tok): Item = TokenItem(t)

private val CHAR_TOKENS = mapOf(
    '0' to Tok.D0, '1' to Tok.D1, '2' to Tok.D2, '3' to Tok.D3, '4' to Tok.D4,
    '5' to Tok.D5, '6' to Tok.D6, '7' to Tok.D7, '8' to Tok.D8, '9' to Tok.D9,
    ',' to Tok.DECIMAL, '-' to Tok.NEGATIVE, '−' to Tok.MINUS, '+' to Tok.PLUS, '×' to Tok.TIMES,
    '÷' to Tok.DIVIDE, '(' to Tok.OPEN, ')' to Tok.CLOSE, 'A' to Tok.VAR_A, 'B' to Tok.VAR_B,
    'X' to Tok.VAR_X, '=' to Tok.EQUALS, ';' to Tok.SEMICOLON,
)

/** Token items for simple text: digits, `,`, `-` (the (−) key), `−`, `+`, `×`, `÷`, brackets, A B X. */
internal fun items(text: String): List<Item> = text.map { tok(CHAR_TOKENS.getValue(it)) }

internal fun ex(text: String): Expr = Expr(items(text))

internal fun ex(vararg parts: Any): Expr = Expr(
    parts.flatMap { part ->
        when (part) {
            is String -> items(part)
            is Item -> listOf(part)
            is Tok -> listOf(tok(part))
            else -> error("not an expression part: $part")
        }
    },
)

internal fun template(kind: TemplateKind, vararg slots: Expr): Template = Template(kind, slots.toList())

internal fun frac(numerator: String, denominator: String): Template =
    template(TemplateKind.FRACTION, ex(numerator), ex(denominator))
