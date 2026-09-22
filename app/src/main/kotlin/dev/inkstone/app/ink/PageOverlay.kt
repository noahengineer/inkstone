package dev.inkstone.app.ink

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.pageToView
import dev.inkstone.core.model.viewToPage
import kotlin.math.max
import kotlin.math.min
import dev.inkstone.core.model.Offset as ViewOffset

/**
 * ⭐ ROADMAP M8c — the layer that makes the five new tools reachable by tapping.
 *
 * ## Why a Compose overlay and not a mode inside `InkSurfaceView`
 *
 * Three of M8c's tools place or manipulate objects that are **not ink**: a tap that creates a text
 * box, a drag that crops a picture, a lasso that captures an Element. None of them needs a
 * tessellator, a brush or a `MotionEvent`'s pressure and tilt, and all of them need an IME, a
 * dialog or a text field — which is Compose's side of the `AndroidView` seam (SPEC §3.2). Putting
 * them in the ink surface would drag Compose's needs across a boundary that exists to keep
 * `androidx.ink` on one side of it, and would put a second gesture state machine into the file
 * that owns the ink path (CLAUDE.md rule 7).
 *
 * ## ⚠️ The overlay is present only when it owns the touches
 *
 * [EditorTool.ownsTouch] gates it, and the gate is not tidiness. A `pointerInput` sitting over the
 * ink surface while the pen tool is active is a stylus that draws nothing — Compose hit-tests the
 * topmost node first, and the surface below never sees the event. That is the whole feature of the
 * app, disabled by a layout decision, with every test still green. So while the pen, the eraser or
 * the lasso is selected this composable puts **nothing** in front of the surface.
 *
 * ⚠️ **The laser and the ruler are not here either**, and that is the same rule one step further:
 * `:android:ink` draws the laser's trail (`LaserController`) and owns the ruler's edge and its snap
 * (`RulerController`). An overlay version of either would have worked in isolation and having both
 * would have been the failure — two pointers fading on different clocks, or a straight edge drawn
 * here that guided nothing there. `EditorTool` records which side owns which.
 *
 * ## Coordinates
 *
 * Every callback speaks page space (SPEC §4.4 rule 1) — the overlay is the one place the
 * conversion happens, using the same [Camera] the ink surface is drawing with. It is mirrored into
 * [InkUiState.camera] by `onCameraChanged`, which is a push and not a read, because a `View`'s
 * field is not Compose state and nothing would invalidate this on a pan.
 */
@Composable
fun PageOverlay(ui: InkUiState, objects: ObjectToolState, gestures: PageGestures, modifier: Modifier = Modifier) {
    val tool = ui.tool
    if (!tool.ownsTouch) return
    val camera = ui.camera
    var preview by remember { mutableStateOf<Preview?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(tool, camera, objects.pendingElement) {
                gestureFor(dragging(tool, objects), camera, gestures) { preview = it }
            },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            when (val shown = preview) {
                null -> Unit
                is Preview.Box -> drawPageBox(shown.box, camera)
                is Preview.Trace -> drawPageTrace(shown.points, camera, Color(LASSO_ARGB))
            }
        }
    }
}

/**
 * What the overlay reports, in page space.
 *
 * A callback triple rather than one `onGesture(Gesture)` sealed type, because the three have
 * genuinely different meanings per tool and the `when` that routed them would be a second place
 * the tool's semantics live. `InkScreen` owns that decision; this owns the arithmetic.
 */
@Immutable
class PageGestures(
    /** A tap: place a text box, focus an image, or stamp the pending Element. */
    val onTap: (PagePoint) -> Unit,
    /** A dragged rectangle: an Element capture area. */
    val onDragBox: (PageRect) -> Unit,
)

/**
 * Which of the three gestures this tool and mode is currently listening for.
 *
 * Hoisted out of the composable so the routing is one `when` over named states rather than a
 * condition tree inside a `pointerInput` block — the same reason `InkScreen` keeps its wiring in
 * named functions. [drag] is passed in because *"the Element tool with nothing armed"* is a drag
 * and *"with something armed"* is a tap, and that is a fact about the panel, not about the tool.
 */
private suspend fun PointerInputScope.gestureFor(
    drag: Boolean,
    camera: Camera,
    gestures: PageGestures,
    onPreview: (Preview?) -> Unit,
) {
    if (drag) {
        boxGesture(camera) { box, done ->
            // ⚠️ The **final** call carries `done` and a non-null box, so drawing the preview
            // unconditionally re-armed the outline one call after `boxGesture` had just cleared it
            // — and nothing clears it again, so the capture rectangle stayed on the paper for the
            // rest of the session, over the diagram it had just been drawn round. A preview
            // belongs to the gesture; the reported box belongs to the caller.
            if (done) onPreview(null) else onPreview(box?.let(Preview::Box))
            if (done && box != null) gestures.onDragBox(box)
        }
    } else {
        tapGesture(camera, gestures.onTap)
    }
}

/** The Element tool drags a capture box until the panel arms something; then it stamps on a tap. */
private fun dragging(tool: EditorTool, objects: ObjectToolState): Boolean =
    tool == EditorTool.ELEMENT && objects.pendingElement == null

private sealed interface Preview {
    data class Box(val box: PageRect) : Preview

    data class Trace(val points: List<PagePoint>) : Preview
}

private suspend fun PointerInputScope.tapGesture(camera: Camera, onTap: (PagePoint) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        var moved = 0f
        var pressed = true
        while (pressed) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
            if (change == null) {
                pressed = false
            } else {
                moved += change.positionChange().getDistance()
                change.consume()
                pressed = change.pressed
            }
        }
        // A tap, not a drag. Somebody resting a palm or panning with one finger has not asked for
        // a text box, and an accidental empty box is an object in the log and an undo press to be
        // rid of.
        if (moved <= TAP_SLOP_PX) onTap(camera.toPage(down.position))
    }
}

private suspend fun PointerInputScope.boxGesture(camera: Camera, onBox: (PageRect?, Boolean) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        val start = camera.toPage(down.position)
        var current = start
        var pressed = true
        while (pressed) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
            if (change == null) {
                pressed = false
            } else {
                current = camera.toPage(change.position)
                change.consume()
                onBox(rectOf(start, current), false)
                pressed = change.pressed
            }
        }
        val box = rectOf(start, current)
        onBox(null, false)
        // A zero-area drag is a tap that missed; reporting it would crop an image to nothing,
        // which `ObjectFormatCodec.decodeRect` refuses to read back.
        if (box.width > MIN_DRAG_PT && box.height > MIN_DRAG_PT) onBox(box, true)
    }
}

// ⚠️ `traceGesture` was here and is gone with the freehand crop it served. That gesture — "draw
// over the picture to say what to keep" — is the one the user named as the thing to get rid of, and
// it had no other caller. A freehand `ImageCrop.Polygon` written by an older build or by a peer
// still decodes, still renders and still publishes; there is simply no longer a way to *author* one
// by hand, and `CropOverlay` degrades one to its bounding box when you edit it.

private fun Camera.toPage(view: Offset): PagePoint = viewToPage(ViewOffset(view.x, view.y), this)

private fun rectOf(a: PagePoint, b: PagePoint) = PageRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))

private fun DrawScope.drawPageBox(box: PageRect, camera: Camera) {
    val topLeft = pageToView(PagePoint(box.left, box.top), camera)
    drawRect(
        color = Color(LASSO_ARGB),
        topLeft = Offset(topLeft.x, topLeft.y),
        size = androidx.compose.ui.geometry.Size(box.width * camera.scale, box.height * camera.scale),
        style = Stroke(width = OUTLINE_PX),
    )
}

private fun DrawScope.drawPageTrace(points: List<PagePoint>, camera: Camera, color: Color) {
    for (index in 1 until points.size) {
        val from = pageToView(points[index - 1], camera)
        val to = pageToView(points[index], camera)
        drawLine(color, Offset(from.x, from.y), Offset(to.x, to.y), strokeWidth = OUTLINE_PX)
    }
}

/** How far a finger may travel and still be a tap, in pixels. Roughly a 3 mm wobble. */
private const val TAP_SLOP_PX = 24f

/** Page points. Below this a drag is a mis-hit, not a rectangle. */
private const val MIN_DRAG_PT = 4f

/**
 * ⭐ ROADMAP M10 — sky-500, the same hue `SelectionOverlayView.ACCENT` uses.
 *
 * One colour for *"this is the region you are describing"* wherever it is drawn, and it is sky-500
 * rather than the chrome's sky-400 for the reason recorded there: this is painted on **white paper**,
 * not on the near-black canvas.
 */
private const val LASSO_ARGB = 0xFF0EA5E9.toInt()
private const val OUTLINE_PX = 2f
