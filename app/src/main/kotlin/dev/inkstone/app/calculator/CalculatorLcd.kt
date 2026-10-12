package dev.inkstone.app.calculator

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.inkstone.core.calc.lcd.Indicator
import dev.inkstone.core.calc.lcd.LcdFrame
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * ⭐ The 96 × 31 dot matrix and the 18 status icons above it, filling the face's glass.
 *
 * ## ⚠️ One bitmap, never 2,976 rectangles
 *
 * [LcdFrame.pixels] becomes one 96 × 31 [ImageBitmap] when — and only when — the frame changes, and
 * is drawn scaled with [FilterQuality.None] so every dot stays a hard-edged square. A loop of
 * `drawRect` per dot is the 7,000-call dot grid M10 removed (`rememberDotGridBrush`), and this
 * window sits over the ink surface, where CLAUDE.md rule 7 says nothing heavy goes. The dot-matrix
 * look — thin unlit gaps between square dots (research 01 §3.1) — is one more call: a grid of lines
 * in the glass colour, built once per size.
 *
 * ## ⚠️ The cursor blinks in the DRAW phase
 *
 * The blink is a state read only inside the draw lambda, so the twice-a-second flip invalidates
 * this one layer's drawing and never recomposes anything — not the window, not `InkScreen`. It
 * restarts **visible** on every new frame, so the cursor is there the moment a key lands. The
 * period is ~0.5 s (research 00 Tier 3 #21 lists the real one as unmeasured).
 *
 * Off (`SHIFT AC`) is blank glass: no icons, no dots, no cursor (research 01 §3.5).
 *
 * The matrix is invisible to `uiautomator dump` as pixels, so it carries [LcdFrame.text] as its
 * content description — what an `adb` walk reads the answer from (research 08 §4.8).
 */
@Composable
internal fun CalculatorLcd(frame: LcdFrame, modifier: Modifier = Modifier) {
    val ink = LcdPalette.inkFor(frame.contrast)
    val image = remember(frame, ink) { if (frame.off) null else lcdBitmap(frame, ink) }
    val picture = rememberUpdatedState(LcdPicture(frame, image, ink))
    val cursorOn = remember { mutableStateOf(true) }
    LaunchedEffect(frame) {
        cursorOn.value = true
        while (frame.cursor != null) {
            delay(CURSOR_BLINK_MILLIS)
            cursorOn.value = !cursorOn.value
        }
    }
    val measurer = rememberTextMeasurer()
    Spacer(
        modifier
            .semantics { contentDescription = frame.text }
            .drawWithCache {
                val art = lcdArt(size, CapSetter(measurer, this))
                onDrawBehind { drawLcd(art, picture.value, cursorOn.value) }
            },
    )
}

/** What one frame draws with: the frame, its bitmap (null when off), and the ink. */
private class LcdPicture(val frame: LcdFrame, val image: ImageBitmap?, val ink: Color)

/** One status icon's place in the band, and its label set and fitted. */
private class IconArt(val slot: Rect, val label: CapLayout, val origin: Offset, val squeeze: Float, val arrow: Path?)

/** The glass's layout in pixels: the matrix (snapped to whole pixels), the icon band, the dot grid. */
private class LcdArt(
    val matrix: IntOffset,
    val matrixSize: IntSize,
    val icons: Map<Indicator, IconArt>,
    val grid: List<Offset>,
    val gap: Float,
) {
    val dotWidth: Float get() = matrixSize.width.toFloat() / LcdFrame.WIDTH
    val dotHeight: Float get() = matrixSize.height.toFloat() / LcdFrame.HEIGHT
}

/** Dark dots in [ink], clear elsewhere, row-major as [LcdFrame.pixels] is. */
internal fun lcdArgb(frame: LcdFrame, ink: Color): IntArray {
    val argb = ink.toArgb()
    return IntArray(frame.pixels.size) { i -> if (frame.pixels[i]) argb else 0 }
}

private fun lcdBitmap(frame: LcdFrame, ink: Color): ImageBitmap =
    Bitmap.createBitmap(lcdArgb(frame, ink), LcdFrame.WIDTH, LcdFrame.HEIGHT, Bitmap.Config.ARGB_8888).asImageBitmap()

/**
 * Lays the glass out in dot pitches *p* (research 01 §3.1, INFERRED from a real-unit photo, ±1 p):
 * the glass is ≈ 101 p wide; from its top, 2.6 p, the 3 p icon band, 1.2 p, then the 31-row matrix;
 * 2.5 p margins either side.
 */
private fun lcdArt(size: Size, setter: CapSetter): LcdArt {
    val p = size.width / GLASS_PITCHES
    val matrix = IntOffset((MATRIX_LEFT_P * p).roundToInt(), (MATRIX_TOP_P * p).roundToInt())
    val matrixSize = IntSize((LcdFrame.WIDTH * p).roundToInt(), (LcdFrame.HEIGHT * p).roundToInt())
    val dotX = matrixSize.width.toFloat() / LcdFrame.WIDTH
    val dotY = matrixSize.height.toFloat() / LcdFrame.HEIGHT
    val grid = buildList {
        for (i in 0..LcdFrame.WIDTH) {
            val x = matrix.x + i * dotX
            add(Offset(x, matrix.y.toFloat()))
            add(Offset(x, matrix.y + matrixSize.height.toFloat()))
        }
        for (j in 0..LcdFrame.HEIGHT) {
            val y = matrix.y + j * dotY
            add(Offset(matrix.x.toFloat(), y))
            add(Offset(matrix.x + matrixSize.width.toFloat(), y))
        }
    }
    // Below ~3 px a dot, a whole-pixel gap would eat most of it; the dots simply touch there.
    val gap = if (dotX < MIN_DOT_PX_FOR_GAPS) 0f else maxOf(dotX * GAP_FRACTION, 1f)
    val icons = Indicator.entries.associateWith { iconArt(it, matrix.x.toFloat(), p, setter) }
    return LcdArt(matrix, matrixSize, icons, grid, gap)
}

/**
 * One icon's slot: its x and width in dots from the matrix's left edge (research 01 §3.2, from the
 * sibling's emulator skin — SECONDARY for the sibling, INFERRED for this model), in a band 3 p tall.
 * The label is set in a small bold sans and squeezed to its slot, never past it.
 */
private fun iconArt(indicator: Indicator, matrixLeft: Float, p: Float, setter: CapSetter): IconArt {
    val (x, width) = ICON_SLOTS.getValue(indicator)
    val slot = Rect(
        left = matrixLeft + (x + ICON_INSET) * p,
        top = BAND_TOP_P * p,
        right = matrixLeft + (x + width - ICON_INSET) * p,
        bottom = (BAND_TOP_P + BAND_HEIGHT_P) * p,
    )
    val size = ICON_FONT_P * p
    val label = setter.set(listOf(t(indicator.label)), CapStyle(size, FontWeight.Bold))
    val squeeze = (slot.width / label.width).coerceAtMost(1f)
    val origin = Offset(slot.center.x - label.width * squeeze / 2f, slot.center.y + size * CapSetter.CAP_HEIGHT / 2f)
    return IconArt(slot, label, origin, squeeze, arrowFor(indicator, slot, p))
}

/** ▼ and ▲ are "solid triangles" (research 01 §3.2), drawn rather than set. */
private fun arrowFor(indicator: Indicator, slot: Rect, p: Float): Path? {
    if (indicator != Indicator.DOWN && indicator != Indicator.UP) return null
    val half = ARROW_WIDTH_P * p / 2f
    val top = slot.center.y - ARROW_HEIGHT_P * p / 2f
    val bottom = top + ARROW_HEIGHT_P * p
    val (base, point) = if (indicator == Indicator.DOWN) top to bottom else bottom to top
    return Path().apply {
        moveTo(slot.center.x - half, base)
        lineTo(slot.center.x + half, base)
        lineTo(slot.center.x, point)
        close()
    }
}

private fun DrawScope.drawLcd(art: LcdArt, picture: LcdPicture, cursorOn: Boolean) {
    drawRect(LcdPalette.Glass)
    val frame = picture.frame
    if (frame.off) return
    frame.indicators.forEach { drawIcon(art.icons.getValue(it), it.inverted, picture.ink) }
    picture.image?.let {
        drawImage(it, dstOffset = art.matrix, dstSize = art.matrixSize, filterQuality = FilterQuality.None)
    }
    val cursor = frame.cursor
    if (cursor != null && cursorOn) {
        drawRect(
            color = picture.ink,
            topLeft = Offset(art.matrix.x + cursor.x * art.dotWidth, art.matrix.y + cursor.y * art.dotHeight),
            size = Size(cursor.width * art.dotWidth, cursor.height * art.dotHeight),
        )
    }
    if (art.gap > 0f) drawPoints(art.grid, PointMode.Lines, LcdPalette.Glass, strokeWidth = art.gap)
}

/** S, A, D, R and G are light letters in a dark box (research 01 §3.2, VERIFIED guide p.8). */
private fun DrawScope.drawIcon(icon: IconArt, inverted: Boolean, ink: Color) {
    val arrow = icon.arrow
    when {
        arrow != null -> drawPath(arrow, ink)

        inverted -> {
            drawRect(ink, icon.slot.topLeft, icon.slot.size)
            drawCap(icon.label, LcdPalette.Glass, icon.origin, squeeze = icon.squeeze)
        }

        else -> drawCap(icon.label, ink, icon.origin, squeeze = icon.squeeze)
    }
}

/** Research 01 §3.2's table: each icon's x and width, in dots from the matrix's left edge. */
private val ICON_SLOTS: Map<Indicator, Pair<Float, Float>> = mapOf(
    Indicator.SHIFT to (0.0f to 3.3f),
    Indicator.ALPHA to (3.0f to 3.7f),
    Indicator.MEMORY to (6.7f to 3.3f),
    Indicator.STO to (10.0f to 6.7f),
    Indicator.RCL to (16.3f to 6.3f),
    Indicator.STAT to (23.0f to 8.0f),
    Indicator.CMPLX to (31.0f to 10.7f),
    Indicator.MAT to (41.7f to 6.7f),
    Indicator.VCT to (48.0f to 6.7f),
    Indicator.DEG to (54.7f to 4.0f),
    Indicator.RAD to (58.3f to 3.3f),
    Indicator.GRA to (61.7f to 3.7f),
    Indicator.FIX to (65.3f to 5.7f),
    Indicator.SCI to (70.7f to 5.3f),
    Indicator.MATH to (76.0f to 8.0f),
    // ▼ and ▲'s slots overlap by 1.3 dots in the source table; their triangles are drawn narrower
    // than either slot, so the two meet rather than overlap.
    Indicator.DOWN to (84.0f to 3.3f),
    Indicator.UP to (86.0f to 3.3f),
    Indicator.DISP to (89.3f to 6.7f),
)

private const val GLASS_PITCHES = 101f
private const val MATRIX_LEFT_P = 2.5f
private const val BAND_TOP_P = 2.6f
private const val BAND_HEIGHT_P = 3f
private const val MATRIX_TOP_P = 6.8f

/** Neighbouring slots touch (S ends at 3.3, A starts at 3.0); a sliver either side keeps boxes apart. */
private const val ICON_INSET = 0.15f

/** A bold cap 2.3 p tall in the 3 p band: "about 3 dot-pitches tall", condensed (research 01 §3.2). */
private const val ICON_FONT_P = 3.2f
private const val ARROW_WIDTH_P = 2.0f
private const val ARROW_HEIGHT_P = 1.8f

/** "Square dots with a thin unlit gap between them" (research 01 §3.1). */
private const val GAP_FRACTION = 0.12f
private const val MIN_DOT_PX_FOR_GAPS = 3f

private const val CURSOR_BLINK_MILLIS = 500L
