package dev.inkstone.app.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * SPEC §3.4 / ROADMAP M1 — "colour picker with hex + wheel + 12 slots."
 *
 * Deliberately plain. M10 owns how the toolbar looks; this owes the milestone
 * the three input methods and nothing more. The eyedropper SPEC §3.4 also lists
 * is not here: the ROADMAP's M1 scope line names hex, wheel and slots only, and
 * sampling the canvas is a rendering concern that belongs with the toolbar work.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColorPicker(
    argb: Int,
    slots: List<Int>,
    onColorChange: (Int) -> Unit,
    onSaveToSlot: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hsv = remember(argb) { argb.toHsv() }
    val alpha = (argb ushr ALPHA_SHIFT) and BYTE_MASK

    Column(modifier = modifier.width(PANEL_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Slots — tap to use, long-press to save", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            slots.forEachIndexed { index, slotArgb ->
                Box(
                    modifier = Modifier
                        .size(SWATCH_SIZE)
                        .background(Color(slotArgb), CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .combinedClickable(
                            onClick = { onColorChange(slotArgb) },
                            onLongClick = { onSaveToSlot(index) },
                        ),
                )
            }
        }

        HueSaturationWheel(
            hue = hsv[0],
            saturation = hsv[1],
            value = hsv[2],
            alpha = alpha,
            onChange = onColorChange,
            modifier = Modifier.size(WHEEL_SIZE).align(Alignment.CenterHorizontally),
        )

        LabelledSlider("Brightness", hsv[2]) { onColorChange(hsvToArgb(hsv[0], hsv[1], it, alpha)) }
        LabelledSlider("Opacity", alpha / MAX_BYTE) {
            onColorChange(hsvToArgb(hsv[0], hsv[1], hsv[2], (it * MAX_BYTE).roundToInt()))
        }

        HexField(argb, onColorChange)
    }
}

@Composable
private fun LabelledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(SLIDER_LABEL_WIDTH))
        Slider(value = value, onValueChange = onChange)
    }
}

@Composable
private fun HexField(argb: Int, onColorChange: (Int) -> Unit) {
    // Editing is local until the text parses, so a half-typed "#1A" does not
    // repaint the pen black.
    var text by remember(argb) { mutableStateOf(argb.toHexString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next
            parseHex(next, argb)?.let(onColorChange)
        },
        label = { Text("Hex (#RRGGBB or #AARRGGBB)", fontSize = 10.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Hue around the circle, saturation from centre to rim — the standard wheel.
 * Brightness and opacity are sliders, because a two-dimensional control cannot
 * carry four dimensions honestly.
 */
@Composable
private fun HueSaturationWheel(
    hue: Float,
    saturation: Float,
    value: Float,
    alpha: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hueRing = remember {
        Brush.sweepGradient(
            List(HUE_STOPS + 1) { i ->
                Color(hsvToArgb(i * DEGREES / HUE_STOPS, 1f, 1f, MAX_BYTE.toInt()))
            },
        )
    }

    Canvas(
        modifier = modifier
            .pointerInput(value, alpha) {
                detectTapGestures { offset -> emit(offset, size.width, size.height, value, alpha, onChange) }
            }
            .pointerInput(value, alpha) {
                detectDragGestures { change, _ ->
                    emit(change.position, size.width, size.height, value, alpha, onChange)
                }
            },
    ) {
        val radius = min(size.width, size.height) / 2f
        drawCircle(hueRing, radius = radius)
        // White in the middle desaturates outward; drawn on top of the hue
        // sweep rather than blended into it, which keeps the maths invertible.
        drawCircle(
            Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), radius = radius),
            radius = radius,
        )

        val angle = Math.toRadians(hue.toDouble())
        val markerRadius = saturation * radius
        drawCircle(
            color = Color.Black,
            radius = MARKER_RADIUS_PX,
            center = center + Offset(
                (cos(angle) * markerRadius).toFloat(),
                (sin(angle) * markerRadius).toFloat(),
            ),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
        )
    }
}

private fun emit(position: Offset, widthPx: Int, heightPx: Int, value: Float, alpha: Int, onChange: (Int) -> Unit) {
    val cx = widthPx / 2f
    val cy = heightPx / 2f
    val dx = position.x - cx
    val dy = position.y - cy
    val radius = min(cx, cy)
    val distance = hypot(dx, dy)
    val hue = ((Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() % DEGREES) + DEGREES) % DEGREES
    val saturation = (distance / radius).coerceIn(0f, 1f)
    onChange(hsvToArgb(hue, saturation, value, alpha))
}

// ---------------------------------------------------------------------------
// Colour maths. android.graphics.Color does HSV already; there is no reason to
// re-implement it, and its rounding is the one the rest of the platform uses.
// ---------------------------------------------------------------------------

private fun Int.toHsv(): FloatArray = FloatArray(HSV_COMPONENTS).also { android.graphics.Color.colorToHSV(this, it) }

private fun hsvToArgb(hue: Float, saturation: Float, value: Float, alpha: Int): Int =
    android.graphics.Color.HSVToColor(alpha.coerceIn(0, MAX_BYTE.toInt()), floatArrayOf(hue, saturation, value))

private fun Int.toHexString(): String {
    val opaque = (this ushr ALPHA_SHIFT) and BYTE_MASK == BYTE_MASK
    return if (opaque) {
        "#%06X".format(this and RGB_MASK)
    } else {
        "#%08X".format(this)
    }
}

private fun parseHex(text: String, fallback: Int): Int? {
    val digits = text.removePrefix("#").trim()
    return when (digits.length) {
        RGB_DIGITS -> digits.toLongOrNull(HEX)?.toInt()?.or(OPAQUE)
        ARGB_DIGITS -> digits.toLongOrNull(HEX)?.toInt()
        else -> null
    }?.takeIf { it != fallback }
}

private val PANEL_WIDTH = 260.dp
private val WHEEL_SIZE = 160.dp
private val SWATCH_SIZE = 18.dp
private val SLIDER_LABEL_WIDTH = 64.dp
private const val MARKER_RADIUS_PX = 6f
private const val HUE_STOPS = 24
private const val DEGREES = 360f
private const val MAX_BYTE = 255f
private const val BYTE_MASK = 0xFF
private const val ALPHA_SHIFT = 24
private const val RGB_MASK = 0xFFFFFF
private const val OPAQUE = 0xFF000000.toInt()
private const val RGB_DIGITS = 6
private const val ARGB_DIGITS = 8
private const val HEX = 16
private const val HSV_COMPONENTS = 3
