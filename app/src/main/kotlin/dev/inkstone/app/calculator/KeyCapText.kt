package dev.inkstone.app.calculator

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import kotlin.math.max

/** One mark of a set [Glyph] list, relative to its own origin: the left edge, on the baseline. */
internal sealed interface CapOp {
    fun moved(dx: Float, dy: Float): CapOp

    class Text(val layout: TextLayoutResult, val x: Float, val baseline: Float) : CapOp {
        override fun moved(dx: Float, dy: Float): CapOp = Text(layout, x + dx, baseline + dy)
    }

    /** A placeholder square or bar: filled, or outlined with a [stroke] of that width. */
    class Shape(val left: Float, val top: Float, val right: Float, val bottom: Float, val stroke: Float?) : CapOp {
        override fun moved(dx: Float, dy: Float): CapOp = Shape(left + dx, top + dy, right + dx, bottom + dy, stroke)
    }
}

/** A set legend or cap: what to draw, and how far it advances. */
internal class CapLayout(val ops: List<CapOp>, val width: Float)

/**
 * The type a cap or legend is set in.
 *
 * @param sizePx the font size, in pixels — derived from millimetres on the face, so the face's text
 *   keeps its proportions under the system font scale. The face is a picture of an object, and a
 *   larger system font must not push `CALC` off its own key.
 * @param tracking letter spacing in em; only the logo strip uses it.
 */
internal data class CapStyle(val sizePx: Float, val weight: FontWeight, val tracking: Float = 0f)

/**
 * ⭐ Sets [Glyph]s into [CapLayout]s: runs of text, scripts, stacks, and Casio's placeholder squares.
 *
 * A typesetter of about sixty lines rather than `AnnotatedString`, because the face needs exactly
 * the two things an `AnnotatedString` cannot do — a **stack** (the fraction key, `d/dx`, `a b/c`)
 * and a **drawn** placeholder whose size follows the text beside it. Everything is measured once
 * per face size, in `drawWithCache`; nothing here runs per frame.
 *
 * The proportions below are typographic conventions, not measurements of Casio's artwork: a script
 * at 62 % raised 42 % of the size, a fraction bar on the maths axis (the height of a minus sign).
 */
internal class CapSetter(private val measurer: TextMeasurer, private val density: Density) {

    fun set(glyphs: List<Glyph>, style: CapStyle): CapLayout {
        val ops = mutableListOf<CapOp>()
        val width = place(glyphs, style.sizePx, style, Offset.Zero, ops)
        return CapLayout(ops, width)
    }

    /** Lays [glyphs] out from [at] (left edge, baseline) and returns the advance. */
    private fun place(glyphs: List<Glyph>, size: Float, style: CapStyle, at: Offset, ops: MutableList<CapOp>): Float {
        var x = at.x
        glyphs.forEach { glyph -> x += one(glyph, size, style, Offset(x, at.y), ops) }
        return x - at.x
    }

    private fun one(glyph: Glyph, size: Float, style: CapStyle, at: Offset, ops: MutableList<CapOp>): Float =
        when (glyph) {
            is Glyph.Run -> run(glyph, size, style, at, ops)

            is Glyph.Script -> {
                val shift = if (glyph.up) -size * SCRIPT_RAISE else size * SCRIPT_DROP
                place(glyph.body, size * SCRIPT_SCALE, style, Offset(at.x, at.y + shift), ops)
            }

            is Glyph.Stack -> stack(glyph, size, style, at, ops)

            is Glyph.Slot -> slot(glyph.filled, size, at, ops)

            Glyph.Bar -> bar(size, at, ops)
        }

    private fun run(glyph: Glyph.Run, size: Float, style: CapStyle, at: Offset, ops: MutableList<CapOp>): Float {
        val italic = glyph.face == GlyphFace.SERIF_ITALIC
        val layout = measurer.measure(
            text = glyph.text,
            style = TextStyle(
                fontSize = with(density) { size.toSp() },
                fontWeight = if (italic) FontWeight.Normal else style.weight,
                fontFamily = if (italic) FontFamily.Serif else FontFamily.SansSerif,
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                letterSpacing = style.tracking.em,
            ),
            softWrap = false,
            maxLines = 1,
            density = density,
        )
        ops += CapOp.Text(layout, at.x, at.y)
        return layout.size.width.toFloat()
    }

    /** [Glyph.Stack.top] over [Glyph.Stack.bottom], each centred on the wider, the bar on the maths axis. */
    private fun stack(glyph: Glyph.Stack, size: Float, style: CapStyle, at: Offset, ops: MutableList<CapOp>): Float {
        val small = size * STACK_SCALE
        val top = mutableListOf<CapOp>()
        val topWidth = place(glyph.top, small, style, Offset.Zero, top)
        val bottom = mutableListOf<CapOp>()
        val bottomWidth = place(glyph.bottom, small, style, Offset.Zero, bottom)
        val width = max(topWidth, bottomWidth) + 2 * size * STACK_PAD
        val axis = at.y - size * AXIS
        val gap = size * STACK_GAP
        top.mapTo(ops) { it.moved(at.x + (width - topWidth) / 2f, axis - gap) }
        bottom.mapTo(ops) { it.moved(at.x + (width - bottomWidth) / 2f, axis + gap + small * CAP_HEIGHT) }
        if (glyph.bar) {
            val half = max(size * STROKE, 1f) / 2f
            ops += CapOp.Shape(at.x, axis - half, at.x + width, axis + half, stroke = null)
        }
        return width
    }

    private fun slot(filled: Boolean, size: Float, at: Offset, ops: MutableList<CapOp>): Float {
        val side = size * SLOT
        val left = at.x + size * BEARING
        ops += CapOp.Shape(left, at.y - side, left + side, at.y, stroke = if (filled) null else max(size * STROKE, 1f))
        return side + 2 * size * BEARING
    }

    private fun bar(size: Float, at: Offset, ops: MutableList<CapOp>): Float {
        val left = at.x + size * BEARING
        val middle = at.y - size * AXIS
        val half = size * BAR_HEIGHT / 2f
        ops += CapOp.Shape(left, middle - half, left + size * BAR_WIDTH, middle + half, stroke = null)
        return size * BAR_WIDTH + 2 * size * BEARING
    }

    companion object {
        /** Cap height as a fraction of the font size, for centring a cap on its key. */
        const val CAP_HEIGHT = 0.72f

        private const val SCRIPT_SCALE = 0.62f
        private const val SCRIPT_RAISE = 0.42f
        private const val SCRIPT_DROP = 0.18f
        private const val STACK_SCALE = 0.6f
        private const val STACK_PAD = 0.06f
        private const val STACK_GAP = 0.09f
        private const val AXIS = 0.3f
        private const val SLOT = 0.55f
        private const val BEARING = 0.07f
        private const val STROKE = 0.07f
        private const val BAR_WIDTH = 0.7f
        private const val BAR_HEIGHT = 0.2f
    }
}

/**
 * Draws [layout] with its origin (left edge, baseline) at [origin], squeezed horizontally by
 * [squeeze] and scaled uniformly by [zoom] — both about the origin, so a fitted legend stays where
 * it was placed.
 */
internal fun DrawScope.drawCap(layout: CapLayout, color: Color, origin: Offset, squeeze: Float = 1f, zoom: Float = 1f) {
    translate(origin.x, origin.y) {
        scale(squeeze * zoom, zoom, pivot = Offset.Zero) {
            layout.ops.forEach { op -> drawOp(op, color) }
        }
    }
}

private fun DrawScope.drawOp(op: CapOp, color: Color) {
    when (op) {
        is CapOp.Text -> drawText(op.layout, color, topLeft = Offset(op.x, op.baseline - op.layout.firstBaseline))

        is CapOp.Shape -> drawRect(
            color = color,
            topLeft = Offset(op.left, op.top),
            size = Size(op.right - op.left, op.bottom - op.top),
            style = op.stroke?.let { Stroke(width = it) } ?: Fill,
        )
    }
}
