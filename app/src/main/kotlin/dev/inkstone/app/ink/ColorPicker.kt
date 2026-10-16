package dev.inkstone.app.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.inkstone.app.ui.ChipKind
import dev.inkstone.app.ui.ColorDragState
import dev.inkstone.app.ui.ColorSwatch
import dev.inkstone.app.ui.GlassPanel
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.PanelHeader
import dev.inkstone.app.ui.Readout
import dev.inkstone.app.ui.SlimSlider
import dev.inkstone.app.ui.TextChip
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * ⭐ ROADMAP M10 — the design's *"Extended Color Toggle: a `+` chip that summons a side-panel for
 * granular RGB/Hex inputs when the inline swatches are insufficient."*
 *
 * SPEC §3.4 requires *"hex + wheel + 12 slots"*, and the design asks for R/G/B/A sliders. Both are
 * here, and the one thing that went is the separate **Brightness** slider: it was the value axis of
 * the wheel expressed twice, and beside three channel sliders it is a control that moves the other
 * three without saying so.
 *
 * The eyedropper SPEC §3.4 also lists is still not here — the ROADMAP's M1 scope line names hex,
 * wheel and slots only, and sampling the canvas would have to read pixels back from a front-buffered
 * `SurfaceView` (SPEC §3.2), which is the same impossibility that rules out a backdrop blur.
 *
 * @param onChooseSlot a slot was tapped. The caller records it so that every later change here —
 *   the wheel, a channel, the hex field — writes into that slot as well as applying the colour,
 *   which is what *"pick a colour and edit it"* means.
 * @param onDropped a slot was dragged onto another. See [ColorDragState].
 */
@Composable
fun ColorPanel(
    argb: Int,
    slots: List<Int>,
    onColorChange: (Int) -> Unit,
    onChooseSlot: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    drag: ColorDragState? = null,
    onDropped: ((from: Int, to: Int) -> Unit)? = null,
) {
    val hsv = remember(argb) { argb.toHsv() }
    val alpha = (argb ushr ALPHA_SHIFT) and BYTE_MASK

    GlassPanel(modifier = modifier.width(PanelWidth)) {
        PanelHeader("Colour", onClose)

        SlotGrid(
            slots = slots,
            selectedArgb = argb,
            onPick = { index ->
                onChooseSlot(index)
                onColorChange(slots[index])
            },
            drag = drag,
            onDropped = onDropped,
        )

        HueSaturationWheel(
            hue = hsv[0],
            saturation = hsv[1],
            value = hsv[2],
            alpha = alpha,
            onChange = onColorChange,
            modifier = Modifier.size(WheelSize).align(Alignment.CenterHorizontally),
        )

        ChannelSlider("R", (argb ushr RED_SHIFT) and BYTE_MASK) { onColorChange(argb.withChannel(RED_SHIFT, it)) }
        ChannelSlider("G", (argb ushr GREEN_SHIFT) and BYTE_MASK) { onColorChange(argb.withChannel(GREEN_SHIFT, it)) }
        ChannelSlider("B", argb and BYTE_MASK) { onColorChange(argb.withChannel(BLUE_SHIFT, it)) }
        ChannelSlider("A", alpha) { onColorChange(argb.withChannel(ALPHA_SHIFT, it)) }

        HexField(argb, onColorChange)
    }
}

/**
 * SPEC §3.4's *"12 saved slots"*, in two rows of six so the panel stays a side panel.
 *
 * ⚠️ **A tap now *chooses* the slot rather than only taking its colour**, and that is what makes the
 * wheel below edit a swatch instead of only the pen. The long-press that used to mean *"save the
 * current colour here"* is gone with it: it did the same job through a gesture nothing on screen
 * mentioned, and a hidden gesture and a visible one competing to write the same twelve values is one
 * too many. Press-and-hold now picks the colour **up**, to drop on another slot.
 */
@Composable
private fun SlotGrid(
    slots: List<Int>,
    selectedArgb: Int,
    onPick: (Int) -> Unit,
    drag: ColorDragState?,
    onDropped: ((from: Int, to: Int) -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(InkDims.ChipGap)) {
        slots.chunked(SLOTS_PER_ROW).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap)) {
                row.forEachIndexed { column, slotArgb ->
                    val index = rowIndex * SLOTS_PER_ROW + column
                    ColorSwatch(
                        argb = slotArgb,
                        selected = slotArgb == selectedArgb,
                        onClick = { onPick(index) },
                        drag = drag,
                        slot = index,
                        dragKey = "panel:$index",
                        onDropped = onDropped,
                    )
                }
            }
        }
    }
}

/** One 0–255 channel. The readout is fixed-width so four stacked sliders do not stagger. */
@Composable
private fun ChannelSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap)) {
        MicroLabel(label, modifier = Modifier.width(ChannelLabel))
        SlimSlider(
            value = value.toFloat(),
            valueRange = 0f..MAX_BYTE,
            onValueChange = { onChange(it.roundToInt()) },
            label = label,
            width = ChannelSliderWidth,
        )
        // Right-aligned, so 0, 26 and 255 line their units up down the four channels.
        Readout(value.toString(), modifier = Modifier.width(ChannelReadout), textAlign = TextAlign.End)
    }
}

@Composable
private fun HexField(argb: Int, onColorChange: (Int) -> Unit) {
    // Editing is local until the text parses, so a half-typed "#1A" does not repaint the pen black.
    var text by remember(argb) { mutableStateOf(argb.toHexString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next
            parseHex(next, argb)?.let(onColorChange)
        },
        label = { MicroLabel("hex") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Hue around the circle, saturation from centre to rim — the standard wheel.
 *
 * Brightness and opacity are channels below, because a two-dimensional control cannot carry four
 * dimensions honestly.
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
            List(HUE_STOPS + 1) { i -> Color(hsvToArgb(i * DEGREES / HUE_STOPS, 1f, 1f, MAX_BYTE.toInt())) },
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
        // White in the middle desaturates outward; drawn on top of the hue sweep rather than blended
        // into it, which keeps the maths invertible.
        drawCircle(
            Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), radius = radius),
            radius = radius,
        )
        val angle = Math.toRadians(hue.toDouble())
        val markerRadius = saturation * radius
        val centre = center + Offset((cos(angle) * markerRadius).toFloat(), (sin(angle) * markerRadius).toFloat())
        drawCircle(color = Ink.Canvas, radius = MARKER_RADIUS_PX + 1f, center = centre, style = Stroke(width = 3f))
        drawCircle(color = Color.White, radius = MARKER_RADIUS_PX, center = centre, style = Stroke(width = 2f))
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

/** Replaces one 8-bit channel, leaving the other three exactly as they were. */
private fun Int.withChannel(shift: Int, value: Int): Int =
    (this and (BYTE_MASK shl shift).inv()) or ((value.coerceIn(0, BYTE_MASK)) shl shift)

private fun Int.toHexString(): String {
    val opaque = (this ushr ALPHA_SHIFT) and BYTE_MASK == BYTE_MASK
    return if (opaque) "#%06X".format(this and RGB_MASK) else "#%08X".format(this)
}

private fun parseHex(text: String, fallback: Int): Int? {
    val digits = text.removePrefix("#").trim()
    return when (digits.length) {
        RGB_DIGITS -> digits.toLongOrNull(HEX)?.toInt()?.or(OPAQUE)
        ARGB_DIGITS -> digits.toLongOrNull(HEX)?.toInt()
        else -> null
    }?.takeIf { it != fallback }
}

private val PanelWidth = 280.dp
private val WheelSize = 168.dp
private val ChannelLabel = 16.dp
private val ChannelSliderWidth = 168.dp
private val ChannelReadout = 34.dp

private const val SLOTS_PER_ROW = 6
private const val MARKER_RADIUS_PX = 6f
private const val HUE_STOPS = 24
private const val DEGREES = 360f
private const val MAX_BYTE = 255f
private const val BYTE_MASK = 0xFF
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val BLUE_SHIFT = 0
private const val RGB_MASK = 0xFFFFFF
private const val OPAQUE = 0xFF000000.toInt()
private const val RGB_DIGITS = 6
private const val ARGB_DIGITS = 8
private const val HEX = 16
private const val HSV_COMPONENTS = 3
