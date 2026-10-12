package dev.inkstone.app.calculator

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import dev.inkstone.core.calc.Key

/** The radial highlight on a round or cursor key, built once per face size in `FaceArt`. */
internal fun silverBrush(bounds: Rect): Brush = Brush.radialGradient(
    colors = listOf(FacePalette.SilverHighlight, FacePalette.Silver, FacePalette.SilverShade),
    center = Offset(bounds.left + bounds.width * HIGHLIGHT_AT, bounds.top + bounds.height * HIGHLIGHT_AT),
    radius = maxOf(bounds.width, bounds.height),
)

/**
 * Paints the whole face: body, top band, wells, honeycomb, keys, legends — in that order, which is
 * the order the real one is built in, so each layer sits on the one under it. [pressed] darkens the
 * keys under a finger; it is the only per-frame input.
 */
internal fun DrawScope.drawFace(art: FaceArt, pressed: Set<Key>) {
    drawBody(art)
    drawTopBand(art)
    drawWells(art)
    drawHoneycomb(art)
    art.keys.forEach { drawKey(art, it, it.spec.key in pressed) }
    art.legends.forEach { drawLegend(art, it) }
}

private fun DrawScope.drawBody(art: FaceArt) {
    val body = art.rect(FaceMm.BODY)
    val corner = CornerRadius(art.px(FaceMm.CORNER))
    drawRoundRect(FacePalette.Face, body.topLeft, body.size, corner)
    drawRoundRect(FacePalette.FaceEdge, body.topLeft, body.size, corner, style = Stroke(art.px(EDGE_MM)))
}

/** The solar cell in its recess, the logo strip, and the raised bezel the LCD glass sits in. */
private fun DrawScope.drawTopBand(art: FaceArt) {
    val solar = art.rect(FaceMm.SOLAR)
    val recess = solar.inflate(art.px(RECESS_MM))
    drawRoundRect(FacePalette.SolarRecess, recess.topLeft, recess.size, CornerRadius(art.px(SMALL_CORNER_MM)))
    drawRoundRect(FacePalette.Solar, solar.topLeft, solar.size, CornerRadius(art.px(SMALL_CORNER_MM)))

    val logo = art.rect(FaceMm.LOGO)
    drawCap(art.logo, FacePalette.Logo, Offset(logo.left, logo.bottom - art.px(LOGO_DESCENT_MM)))

    val bezel = art.rect(FaceMm.BEZEL)
    drawRoundRect(FacePalette.Bezel, bezel.topLeft, bezel.size, CornerRadius(art.px(BEZEL_CORNER_MM)))
    val rim = art.rect(FaceMm.GLASS).inflate(art.px(GLASS_RIM_MM))
    drawRoundRect(FacePalette.GlassRim, rim.topLeft, rim.size, CornerRadius(art.px(SMALL_CORNER_MM)))
}

private fun DrawScope.drawWells(art: FaceArt) {
    FaceMm.WELLS.forEach { well ->
        val r = art.rect(well)
        val corner = CornerRadius(r.height / 2f)
        drawRoundRect(FacePalette.Well, r.topLeft, r.size, corner)
        drawRoundRect(FacePalette.WellGroove, r.topLeft, r.size, corner, style = Stroke(art.px(GROOVE_MM)))
    }
}

/** The frosted plate, seen only in the four diagonal gaps between the cursor keys (§2.3). */
private fun DrawScope.drawHoneycomb(art: FaceArt) {
    drawPath(art.plate, FacePalette.Plate)
    clipPath(art.plate) { drawPath(art.honeycomb, FacePalette.PlateCell, style = Stroke(art.px(CELL_LINE_MM))) }
}

/** A key: its dark rim and drop shadow, its top, its cap, and the shade of a press. */
private fun DrawScope.drawKey(art: FaceArt, key: KeyArt, down: Boolean) {
    val look = lookOf(key.spec.kind)
    translate(art.px(SHADOW_DX_MM), art.px(SHADOW_DY_MM)) { drawPath(key.path, look.rim) }
    drawPath(key.path, look.rim, style = Stroke(art.px(RIM_MM)))
    val brush = key.brush
    if (brush != null) drawPath(key.path, brush) else drawPath(key.path, look.top)
    key.cap?.let { drawCap(it, look.cap, key.capOrigin, zoom = key.capZoom) }
    if (down) drawPath(key.path, FacePalette.Pressed)
}

private fun DrawScope.drawLegend(art: FaceArt, legend: LegendArt) {
    drawCap(legend.layout, colourOf(legend.legend.ink), legend.origin, squeeze = legend.squeeze)
    legend.legend.bracket?.let { drawBracket(art, legend, colourOf(it)) }
}

/**
 * ┌ and ┐ at the ends of a legend, their short strokes pointing down towards the key (research 01
 * §5.1). The bracket's colour is its own: ┌A┐ is a magenta A in cyan brackets.
 */
private fun DrawScope.drawBracket(art: FaceArt, legend: LegendArt, colour: Color) {
    val left = legend.origin.x - legend.bracketPad
    val right = legend.origin.x + legend.layout.width * legend.squeeze + legend.bracketPad
    val top = legend.origin.y - legend.capHeight - art.px(BRACKET_RISE_MM)
    val arm = art.px(BRACKET_ARM_MM)
    val tick = art.px(BRACKET_TICK_MM)
    val stroke = art.px(BRACKET_STROKE_MM)
    drawLine(colour, Offset(left, top + tick), Offset(left, top), stroke)
    drawLine(colour, Offset(left, top), Offset(left + arm, top), stroke)
    drawLine(colour, Offset(right - arm, top), Offset(right, top), stroke)
    drawLine(colour, Offset(right, top), Offset(right, top + tick), stroke)
}

/** A key's paint. Silver keys take their top from a brush; see [silverBrush]. */
private class KeyLook(val top: Color, val rim: Color, val cap: Color)

private val SilverLook = KeyLook(FacePalette.Silver, FacePalette.SilverRim, FacePalette.CapOnWhite)
private val FunctionLook = KeyLook(FacePalette.FunctionKey, FacePalette.FunctionRim, FacePalette.CapOnWhite)
private val NumberLook = KeyLook(FacePalette.NumberKey, FacePalette.NumberRim, FacePalette.CapOnGrey)
private val ClearLook = KeyLook(FacePalette.ClearKey, FacePalette.NumberRim, FacePalette.CapOnGrey)

private fun lookOf(kind: KeyKind): KeyLook = when (kind) {
    KeyKind.ROUND, KeyKind.CURSOR -> SilverLook
    KeyKind.FUNCTION -> FunctionLook
    KeyKind.NUMBER -> NumberLook
    KeyKind.CLEAR -> ClearLook
}

private fun colourOf(ink: LegendInk): Color = when (ink) {
    LegendInk.SHIFT -> FacePalette.Shift
    LegendInk.ALPHA -> FacePalette.Alpha
    LegendInk.BASE -> FacePalette.Base
    LegendInk.CMPLX -> FacePalette.Cmplx
    LegendInk.MODE -> FacePalette.Mode
    LegendInk.LABEL -> FacePalette.Label
}

// ---- Proportions in millimetres on the face (INFERRED from research 01 §2–4) -------------------

private const val EDGE_MM = 0.25f
private const val RECESS_MM = 0.7f
private const val SMALL_CORNER_MM = 0.8f
private const val BEZEL_CORNER_MM = 2.2f
private const val GLASS_RIM_MM = 0.35f
private const val LOGO_DESCENT_MM = 0.5f
private const val GROOVE_MM = 0.15f
private const val CELL_LINE_MM = 0.14f

/** The dark gap round a key, "heaviest along the bottom and right as a shadow" (§4.3). */
private const val RIM_MM = 0.28f
private const val SHADOW_DX_MM = 0.18f
private const val SHADOW_DY_MM = 0.3f

private const val BRACKET_RISE_MM = 0.35f
private const val BRACKET_ARM_MM = 0.8f
private const val BRACKET_TICK_MM = 0.8f
private const val BRACKET_STROKE_MM = 0.17f

/** Where on a silver key the light falls, as a fraction of its box from the top-left. */
private const val HIGHLIGHT_AT = 0.35f
