package dev.inkstone.app.calculator

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import dev.inkstone.core.calc.Key
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One key, ready to paint: its outline in pixels, its cap already set and placed, and — for the
 * silver round and cursor keys — the radial highlight its top is filled with.
 */
internal class KeyArt(
    val spec: KeySpec,
    val path: Path,
    val brush: Brush?,
    val cap: CapLayout?,
    val capOrigin: Offset,
    val capZoom: Float,
)

/**
 * One legend above a key, set and placed. [squeeze] condenses it to fit beside its neighbour;
 * [bracketPad] is how far its ┌ ┐ reach past the text either side, already fitted.
 */
internal class LegendArt(
    val legend: Legend,
    val layout: CapLayout,
    val origin: Offset,
    val squeeze: Float,
    val capHeight: Float,
    val bracketPad: Float,
)

/**
 * Everything about the face that depends only on its size, in pixels — built once per size in
 * `drawWithCache` and never per frame.
 *
 * The one thing drawn per frame that changes is which keys are held down, and that is a fill on
 * top of a path built here.
 */
internal class FaceArt(
    /** Pixels per millimetre. */
    val mm: Float,
    val keys: List<KeyArt>,
    val legends: List<LegendArt>,
    val honeycomb: Path,
    val plate: Path,
    val logo: CapLayout,
) {
    fun rect(r: MmRect): Rect = Rect(r.left * mm, r.top * mm, r.right * mm, r.bottom * mm)

    fun px(millimetres: Float): Float = millimetres * mm
}

/** Builds the face for a body [widthPx] wide (the height follows from the locked aspect). */
internal fun buildFaceArt(widthPx: Float, setter: CapSetter): FaceArt {
    val mm = widthPx / FaceMm.WIDTH
    return FaceArt(
        mm = mm,
        keys = CalculatorKeypad.KEYS.map { keyArt(it, mm, setter) },
        legends = CalculatorKeypad.KEYS.flatMap { legendsFor(it, mm, setter) },
        honeycomb = honeycomb(mm),
        plate = Path().apply {
            val plate = FaceMm.HONEYCOMB
            val corner = CornerRadius(PLATE_CORNER_MM * mm)
            addRoundRect(RoundRect(Rect(plate.left * mm, plate.top * mm, plate.right * mm, plate.bottom * mm), corner))
        },
        logo = setter.set(listOf(t(LOGO_TEXT)), CapStyle(LOGO_SIZE_MM * mm, FontWeight.Bold, LOGO_TRACKING)),
    )
}

private fun keyArt(spec: KeySpec, mm: Float, setter: CapSetter): KeyArt {
    val r = spec.rect
    val bounds = Rect(r.left * mm, r.top * mm, r.right * mm, r.bottom * mm)
    val path = when (spec.kind) {
        KeyKind.ROUND -> Path().apply { addOval(bounds) }
        KeyKind.CURSOR -> cursorPath(spec.key, bounds)
        else -> Path().apply { addRoundRect(roundedKey(bounds, spec.corner, mm)) }
    }
    val brush = if (spec.kind == KeyKind.ROUND || spec.kind == KeyKind.CURSOR) silverBrush(bounds) else null
    if (spec.cap.isEmpty()) return KeyArt(spec, path, brush, null, bounds.center, 1f)
    val size = capSizeMm(spec) * mm
    val cap = setter.set(spec.cap, CapStyle(size, if (isDigit(spec)) FontWeight.Light else FontWeight.Normal))
    // Shrunk uniformly, never squeezed, when it would crowd its own key: `CALC` and `S⇔D` are the
    // two that reach for the edges. A cap is a word and keeps its shape; only legends are condensed.
    val zoom = (bounds.width * CAP_MAX_FILL / cap.width).coerceAtMost(1f)
    val origin = Offset(
        x = bounds.center.x - cap.width * zoom / 2f,
        y = bounds.center.y + size * zoom * CapSetter.CAP_HEIGHT / 2f,
    )
    return KeyArt(spec, path, brush, cap, origin, zoom)
}

/**
 * A key's top: every corner the small radius, and the one block corner the large one (research 01
 * §4.3: the ten keys at the corners of the two key blocks, plus the two that wrap the cursor pad).
 */
private fun roundedKey(bounds: Rect, corner: BigCorner?, mm: Float): RoundRect {
    fun radius(which: BigCorner) = CornerRadius(mm * if (corner == which) BIG_RADIUS_MM else KEY_RADIUS_MM)
    return RoundRect(
        rect = bounds,
        topLeft = radius(BigCorner.TOP_LEFT),
        topRight = radius(BigCorner.TOP_RIGHT),
        bottomRight = radius(BigCorner.BOTTOM_RIGHT),
        bottomLeft = radius(BigCorner.BOTTOM_LEFT),
    )
}

/**
 * A cursor key: a "home plate" pentagon, rounded on its outer side and chamfered to a blunt point
 * aimed at the pad's centre (research 01 §4.3, VERIFIED by sight; the proportions are INFERRED).
 *
 * The outline is written once, for UP, in `(across, along)` units — `along` 0 is the outer side,
 * 1 is the point — and turned to face each way, so the four keys cannot disagree about their shape.
 */
private fun cursorPath(key: Key, bounds: Rect): Path {
    fun at(across: Float, along: Float): Offset = when (key) {
        Key.UP -> Offset(bounds.left + across * bounds.width, bounds.top + along * bounds.height)
        Key.DOWN -> Offset(bounds.left + across * bounds.width, bounds.bottom - along * bounds.height)
        Key.LEFT -> Offset(bounds.left + along * bounds.width, bounds.top + across * bounds.height)
        else -> Offset(bounds.right - along * bounds.width, bounds.top + across * bounds.height)
    }
    return Path().apply {
        val start = at(0f, PENTAGON_SHOULDER)
        moveTo(start.x, start.y)
        val control = at(1f / 2f, PENTAGON_BULGE)
        val end = at(1f, PENTAGON_SHOULDER)
        quadraticTo(control.x, control.y, end.x, end.y)
        listOf(
            at(1f, PENTAGON_WAIST),
            at(1f - PENTAGON_TIP, 1f),
            at(PENTAGON_TIP, 1f),
            at(0f, PENTAGON_WAIST),
        ).forEach { lineTo(it.x, it.y) }
        close()
    }
}

/**
 * Hexagonal cells over the plate behind the cursor pad, ~2 mm across (research 01 §2.3). One path,
 * stroked once: a hundred-odd hexagons as separate draw calls is the dot-grid mistake M10 fixed.
 */
private fun honeycomb(mm: Float): Path {
    val plate = FaceMm.HONEYCOMB
    val r = HEX_RADIUS_MM * mm
    val dx = SQRT_3 * r
    val dy = r * HEX_ROW_PITCH
    val path = Path()
    var row = 0
    var y = plate.top * mm
    while (y <= plate.bottom * mm + r) {
        var x = plate.left * mm + if (row % 2 == 0) 0f else dx / 2f
        while (x <= plate.right * mm + r) {
            hexagon(path, Offset(x, y), r)
            x += dx
        }
        y += dy
        row++
    }
    return path
}

private fun hexagon(path: Path, centre: Offset, r: Float) {
    for (i in 0..HEX_SIDES) {
        val angle = HEX_FIRST_RADIANS + i * HEX_STEP_RADIANS
        val x = centre.x + r * cos(angle).toFloat()
        val y = centre.y + r * sin(angle).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
}

/** Digits and the four operators are set large and light on the grey keys; words smaller. */
private fun isDigit(spec: KeySpec): Boolean {
    val only = spec.cap.singleOrNull() as? Glyph.Run ?: return false
    return (spec.kind == KeyKind.NUMBER) && only.text.length == 1
}

private fun capSizeMm(spec: KeySpec): Float = when {
    isDigit(spec) -> DIGIT_SIZE_MM
    spec.kind == KeyKind.FUNCTION -> FUNCTION_SIZE_MM
    else -> PAD_WORD_SIZE_MM
}

/**
 * The legends above one key (research 01 §5.1): SHIFT at the left, or centred when alone; ALPHA at
 * the right. A round key's printed name and its SHIFT legend (`MODE SETUP`) sit side by side,
 * centred. When the two would collide they are condensed together — never overlapped, never cut.
 */
private fun legendsFor(spec: KeySpec, mm: Float, setter: CapSetter): List<LegendArt> {
    val pieces = listOfNotNull(spec.name, spec.shift, spec.alpha)
    if (pieces.isEmpty()) return emptyList()
    val style = CapStyle(LEGEND_SIZE_MM * mm, FontWeight.SemiBold)
    val layouts = pieces.map { setter.set(it.glyphs, style) }
    val widths = pieces.zip(layouts) { legend, layout -> layout.width * LEGEND_CONDENSED + bracketRoom(legend, mm) }
    val span = (spec.width + 2 * overhangMm(spec.kind)) * mm
    val gap = LEGEND_GAP_MM * mm
    val squeeze = LEGEND_CONDENSED * ((span - gap * (pieces.size - 1)) / widths.sum()).coerceAtMost(1f)
    val fitted = widths.map { it * squeeze / LEGEND_CONDENSED }
    val baseline = (spec.rect.top - LEGEND_LIFT_MM) * mm
    val lefts = legendLefts(spec, fitted, span, gap, mm)
    return pieces.indices.map { i ->
        val pad = bracketRoom(pieces[i], mm) * squeeze / LEGEND_CONDENSED / 2f
        LegendArt(
            legend = pieces[i],
            layout = layouts[i],
            origin = Offset(lefts[i] + pad, baseline),
            squeeze = squeeze,
            capHeight = style.sizePx * CapSetter.CAP_HEIGHT,
            bracketPad = pad,
        )
    }
}

/** Where each fitted legend starts: a row centred over a round key; else SHIFT left, ALPHA right. */
private fun legendLefts(spec: KeySpec, widths: List<Float>, span: Float, gap: Float, mm: Float): List<Float> {
    val spanLeft = spec.cx * mm - span / 2f
    val spanRight = spanLeft + span
    // Only a blank round key carries a printed name, and its legends are always a centred row.
    val pairAtTheEnds = spec.name == null && widths.size == 2
    val alphaAlone = spec.name == null && spec.shift == null && widths.size == 1
    return when {
        pairAtTheEnds -> listOf(spanLeft, spanRight - widths[1])

        alphaAlone -> listOf(spanRight - widths[0])

        else -> {
            // A round key's row, or a lone SHIFT legend: centred over the key.
            val total = widths.sum() + gap * (widths.size - 1)
            var x = spec.cx * mm - total / 2f
            widths.map { w -> x.also { x += w + gap } }
        }
    }
}

private fun bracketRoom(legend: Legend, mm: Float): Float = if (legend.bracket == null) 0f else 2 * BRACKET_PAD_MM * mm

private fun overhangMm(kind: KeyKind): Float = when (kind) {
    KeyKind.ROUND -> ROUND_OVERHANG_MM
    KeyKind.FUNCTION -> FUNCTION_OVERHANG_MM
    else -> PAD_OVERHANG_MM
}

// ---- Proportions, in millimetres on the face unless stated (research 01 §4.3, §7; INFERRED) ------

/** "≈ 1.2 mm corner radius" on every key; the ten block corners "≈ 3 mm". */
private const val KEY_RADIUS_MM = 1.2f
private const val BIG_RADIUS_MM = 3f

/** Research 01 §7: digits ≈ 4.9 mm tall, so a font of ≈ 6.8 mm; white-key words cap height ≈ 2.6–3 mm. */
private const val DIGIT_SIZE_MM = 6.8f
private const val FUNCTION_SIZE_MM = 3.7f
private const val PAD_WORD_SIZE_MM = 3.9f

/** A cap may fill this much of its key's width before it is shrunk. */
private const val CAP_MAX_FILL = 0.86f

/** Legends: cap height ≈ 1.5–1.7 mm (research 01 §7), ≈ 1 mm above the key (§4.2). */
private const val LEGEND_SIZE_MM = 2.2f
private const val LEGEND_LIFT_MM = 0.9f
private const val LEGEND_GAP_MM = 0.6f

/** "Slightly condensed" (research 01 §7): every legend at 88 % width before any fitting. */
private const val LEGEND_CONDENSED = 0.88f

/** How far a legend row may reach past its key's sides — a little under half the gap to the next key. */
private const val ROUND_OVERHANG_MM = 1.6f
private const val FUNCTION_OVERHANG_MM = 0.6f
private const val PAD_OVERHANG_MM = 0.9f

/** The room a ┌ ┐ bracket takes either side of its text. */
internal const val BRACKET_PAD_MM = 0.45f

private const val PENTAGON_SHOULDER = 0.18f
private const val PENTAGON_BULGE = -0.12f
private const val PENTAGON_WAIST = 0.6f
private const val PENTAGON_TIP = 0.36f

/** The honeycomb plate's own corner, and its cells' size: "≈ 2 mm across" (research 01 §2.3). */
internal const val PLATE_CORNER_MM = 2.5f
private const val HEX_RADIUS_MM = 1.05f
private const val HEX_SIDES = 6

/** A pointy-topped hexagonal grid: columns √3·r apart, rows 1.5·r. */
private val SQRT_3 = sqrt(3f)
private const val HEX_ROW_PITCH = 1.5f

/** Pointy-topped cells: the first vertex at 30°, then every 60°. */
private const val HEX_FIRST_RADIANS = PI / 6
private const val HEX_STEP_RADIANS = PI / 3

/** Docs/CALCULATOR.md D1: Inkstone's name, small, where the maker's wordmark would be. */
private const val LOGO_TEXT = "INKSTONE"
private const val LOGO_SIZE_MM = 2.6f
private const val LOGO_TRACKING = 0.12f
