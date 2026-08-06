// Every number below is a coordinate on the 24x24 design grid documented on `FolderGlyph`.
// Naming them would mean ten constants per shape — `FLASK_NECK_LEFT_X`, `FLASK_SHOULDER_Y`
// — which is strictly less readable than the drawing itself: the shapes are legible as
// coordinates and meaningless as names. The grid size, stroke weight and every *tunable*
// ratio ARE named, at the bottom of the file.
@file:Suppress("MagicNumber")

package dev.inkstone.app.library

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.inkstone.core.model.FolderIcon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The folder icons, drawn.
 *
 * ## Why not glyphs, and why not an icon library
 *
 * M3 first used Unicode characters — `🗀`, `⚗`, `🖩` and friends — and they looked wrong on
 * the tablet, which is exactly what you would expect once you look at what they are. Half
 * that set is *text-presentation* symbols rather than emoji, so the font renders them as
 * thin monochrome line art at a different optical weight and baseline from the emoji beside
 * them. Sizes disagree, vertical alignment disagrees, and a few fall back to a box.
 *
 * `androidx.compose.material:material-icons-core` would have solved it, and it is not on
 * the classpath: Material3 1.4.0 dropped the dependency, and it cannot be added here.
 *
 * So they are drawn. Ten shapes on a normalised 24×24 grid, filled or stroked with the
 * caller's colour. Every one is the same optical size, upright by construction, sharp at any
 * resolution, and tinted to contrast with whatever the folder's colour is — which is what
 * "the icons look bad" actually meant.
 *
 * Deliberately simple geometry. These are 40 px tiles in a grid, not illustrations, and a
 * shape that reads instantly at that size has no room for detail anyway.
 */
@Composable
fun FolderGlyph(icon: FolderIcon, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        // One square, centred, so a non-square box never stretches the shape.
        val side = min(size.width, size.height)
        val unit = side / GRID
        val originX = (size.width - side) / 2f
        val originY = (size.height - side) / 2f

        fun point(x: Float, y: Float) = Offset(originX + x * unit, originY + y * unit)

        val stroke = Stroke(width = unit * STROKE_UNITS)
        when (icon) {
            FolderIcon.FOLDER -> drawFolder(::point, tint)
            FolderIcon.STAR -> drawStar(::point, unit, tint)
            FolderIcon.BOOK -> drawBook(::point, tint, stroke)
            FolderIcon.NOTE -> drawNote(::point, tint, stroke)
            FolderIcon.FLASK -> drawFlask(::point, tint, stroke)
            FolderIcon.CALCULATOR -> drawCalculator(::point, unit, tint, stroke)
            FolderIcon.GLOBE -> drawGlobe(::point, unit, tint, stroke)
            FolderIcon.HEART -> drawHeart(::point, tint)
            FolderIcon.BRIEFCASE -> drawBriefcase(::point, tint, stroke)
            FolderIcon.TAG -> drawTag(::point, unit, tint, stroke)
        }
    }
}

private typealias P = (Float, Float) -> Offset

/** A classic folder: a body with a tab on the top left. */
private fun DrawScope.drawFolder(p: P, tint: Color) {
    val path = Path().apply {
        moveTo(p(3f, 6f))
        lineTo(p(10f, 6f))
        lineTo(p(12f, 8f))
        lineTo(p(21f, 8f))
        lineTo(p(21f, 19f))
        lineTo(p(3f, 19f))
        close()
    }
    drawPath(path, tint, style = Fill)
}

/** A five-pointed star, computed rather than drawn by eye so the points are even. */
private fun DrawScope.drawStar(p: P, unit: Float, tint: Color) {
    val centre = p(12f, 12f)
    val outer = OUTER_RADIUS_UNITS * unit
    val inner = outer * INNER_RADIUS_RATIO
    val path = Path()
    for (index in 0 until STAR_POINTS * 2) {
        val radius = if (index % 2 == 0) outer else inner
        // Start at -90° so a point is at the top; a star rotated by a tenth of a turn is the
        // "bad orientation" complaint in miniature.
        val angle = (-PI / 2 + index * PI / STAR_POINTS).toFloat()
        val vertex = Offset(centre.x + radius * cos(angle), centre.y + radius * sin(angle))
        if (index == 0) path.moveTo(vertex.x, vertex.y) else path.lineTo(vertex.x, vertex.y)
    }
    path.close()
    drawPath(path, tint, style = Fill)
}

/** A closed book: cover, and a spine down the left. */
private fun DrawScope.drawBook(p: P, tint: Color, stroke: Stroke) {
    drawRoundedRect(p(5f, 4f), p(19f, 20f), tint, stroke)
    drawLine(tint, p(9f, 4f), p(9f, 20f), strokeWidth = stroke.width)
}

/** A page with ruled lines. */
private fun DrawScope.drawNote(p: P, tint: Color, stroke: Stroke) {
    drawRoundedRect(p(5f, 3f), p(19f, 21f), tint, stroke)
    for (row in 0 until NOTE_RULES) {
        val y = 8f + row * NOTE_RULE_SPACING
        drawLine(tint, p(8f, y), p(16f, y), strokeWidth = stroke.width)
    }
}

/** An Erlenmeyer flask: neck, shoulders, base. */
private fun DrawScope.drawFlask(p: P, tint: Color, stroke: Stroke) {
    val path = Path().apply {
        moveTo(p(10f, 3f))
        lineTo(p(10f, 10f))
        lineTo(p(5f, 20f))
        lineTo(p(19f, 20f))
        lineTo(p(14f, 10f))
        lineTo(p(14f, 3f))
    }
    drawPath(path, tint, style = stroke)
    // The lip, so the neck reads as open rather than as two stray lines.
    drawLine(tint, p(9f, 3f), p(15f, 3f), strokeWidth = stroke.width)
}

/** A calculator: a case, a display, and a keypad. */
private fun DrawScope.drawCalculator(p: P, unit: Float, tint: Color, stroke: Stroke) {
    drawRoundedRect(p(6f, 3f), p(18f, 21f), tint, stroke)
    drawLine(tint, p(9f, 7f), p(15f, 7f), strokeWidth = stroke.width)
    for (row in 0 until KEYPAD_ROWS) {
        for (column in 0 until KEYPAD_COLUMNS) {
            val centre = p(9f + column * KEY_SPACING, 11f + row * KEY_SPACING)
            drawCircle(tint, radius = unit * KEY_RADIUS_UNITS, center = centre)
        }
    }
}

/** A globe: outline, equator, and one meridian. */
private fun DrawScope.drawGlobe(p: P, unit: Float, tint: Color, stroke: Stroke) {
    val centre = p(12f, 12f)
    val radius = unit * GLOBE_RADIUS_UNITS
    drawCircle(tint, radius = radius, center = centre, style = stroke)
    drawLine(tint, p(3f, 12f), p(21f, 12f), strokeWidth = stroke.width)
    // The meridian is an ellipse, not a circle — that is what makes it read as a sphere.
    drawOval(
        color = tint,
        topLeft = Offset(centre.x - radius * MERIDIAN_WIDTH_RATIO, centre.y - radius),
        size = Size(radius * MERIDIAN_WIDTH_RATIO * 2, radius * 2),
        style = stroke,
    )
}

/** A heart: two lobes and a point. */
private fun DrawScope.drawHeart(p: P, tint: Color) {
    val path = Path().apply {
        moveTo(p(12f, 20f))
        cubicTo(p(2f, 13f), p(4f, 4f), p(12f, 9f))
        cubicTo(p(20f, 4f), p(22f, 13f), p(12f, 20f))
        close()
    }
    drawPath(path, tint, style = Fill)
}

/** A briefcase: case and handle. */
private fun DrawScope.drawBriefcase(p: P, tint: Color, stroke: Stroke) {
    drawRoundedRect(p(3f, 8f), p(21f, 19f), tint, stroke)
    val handle = Path().apply {
        moveTo(p(9f, 8f))
        lineTo(p(9f, 5f))
        lineTo(p(15f, 5f))
        lineTo(p(15f, 8f))
    }
    drawPath(handle, tint, style = stroke)
}

/** A luggage tag: a pentagon with an eyelet. */
private fun DrawScope.drawTag(p: P, unit: Float, tint: Color, stroke: Stroke) {
    val path = Path().apply {
        moveTo(p(4f, 5f))
        lineTo(p(14f, 5f))
        lineTo(p(20f, 12f))
        lineTo(p(14f, 19f))
        lineTo(p(4f, 19f))
        close()
    }
    drawPath(path, tint, style = stroke)
    drawCircle(tint, radius = unit * EYELET_RADIUS_UNITS, center = p(8f, 12f))
}

private fun DrawScope.drawRoundedRect(topLeft: Offset, bottomRight: Offset, tint: Color, stroke: Stroke) {
    val rect = Rect(topLeft, bottomRight)
    drawRoundRect(
        color = tint,
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke.width * CORNER_RADIUS_RATIO),
        style = stroke,
    )
}

private fun Path.moveTo(point: Offset) = moveTo(point.x, point.y)

private fun Path.lineTo(point: Offset) = lineTo(point.x, point.y)

private fun Path.cubicTo(first: Offset, second: Offset, end: Offset) =
    cubicTo(first.x, first.y, second.x, second.y, end.x, end.y)

/** The design grid every shape above is laid out on. */
private const val GRID = 24f

/** Stroke weight, in grid units. Heavy enough to read at 40 px. */
private const val STROKE_UNITS = 1.6f

private const val CORNER_RADIUS_RATIO = 1.2f

private const val STAR_POINTS = 5
private const val OUTER_RADIUS_UNITS = 9.5f
private const val INNER_RADIUS_RATIO = 0.42f

private const val NOTE_RULES = 3
private const val NOTE_RULE_SPACING = 4f

private const val KEYPAD_ROWS = 3
private const val KEYPAD_COLUMNS = 3
private const val KEY_SPACING = 3f
private const val KEY_RADIUS_UNITS = 0.9f

private const val GLOBE_RADIUS_UNITS = 9f
private const val MERIDIAN_WIDTH_RATIO = 0.45f

private const val EYELET_RADIUS_UNITS = 1.3f
